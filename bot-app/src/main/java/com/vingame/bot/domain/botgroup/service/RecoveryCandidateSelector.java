package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import com.vingame.bot.domain.botgroup.model.RecoveryEligibility;
import com.vingame.bot.domain.botgroup.repository.BotGroupRepository;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The recovery candidate set (DEAD_GROUP_AUTO_RECOVERY AD-3 / AD-4), in one place.
 *
 * <p>Two components need the answer to "which groups may auto-recovery act on right
 * now": the observe-only {@code EnvironmentProbeScheduler}, which probes an
 * environment only while it owns at least one candidate, and
 * {@link DeadGroupRecoveryScheduler}, which attempts them. Those two sets have to be
 * the <em>same</em> set — a group that is probed but never recovered wastes network
 * calls, and a group that is recovered but never probed would be acted on with no
 * evidence at all. So the selection lives here rather than in either scheduler.
 *
 * <p>Deliberately a static function over injected collaborators rather than a bean:
 * it holds no state, and both callers already own a {@link BotGroupRepository} and a
 * {@link BotGroupBehaviorService}.
 *
 * <p><b>Driven by the persisted {@code targetStatus}, not by {@code runningGroups}
 * (AD-4).</b> That ordering is the whole point: {@code onStartup} rebuilds only
 * {@code ACTIVE} groups, so a group that died before this JVM started has no runtime
 * and is invisible to every in-memory dead-group signal — including
 * {@code groups_dead_by_env} and therefore {@code EnvironmentGroupDead}. That is the
 * longest-down case and the one this feature most needs to see. The in-memory DEAD
 * runtimes are then unioned in to cover the converse: {@code handleBotGroupDeath}'s
 * save can throw into a swallowing catch, leaving memory DEAD and Mongo ACTIVE.
 */
public final class RecoveryCandidateSelector {

    private RecoveryCandidateSelector() {
    }

    /**
     * The groups that satisfy {@link RecoveryEligibility} at {@code now}, in a stable
     * order (persisted-DEAD rows first, then the memory-only DEAD runtimes).
     *
     * <p>The global {@code bot.recovery.enabled} flag (AD-3 condition 1) and the
     * per-group attempt budget (condition 7) are <b>not</b> applied here — they are
     * scheduler state, and the probe scheduler must keep observing regardless of
     * both.
     */
    public static List<BotGroup> select(BotGroupRepository repository,
                                        BotGroupBehaviorService behaviorService,
                                        Instant now,
                                        ZoneId zone) {
        Set<String> deadRuntimeIds = new HashSet<>(behaviorService.listDeadRuntimeGroupIds());

        Map<String, BotGroup> byId = new LinkedHashMap<>();
        for (BotGroup group : repository.findByTargetStatus(BotGroupStatus.DEAD)) {
            if (group != null && group.getId() != null) {
                byId.put(group.getId(), group);
            }
        }
        for (String id : deadRuntimeIds) {
            if (id != null && !byId.containsKey(id)) {
                repository.findById(id).ifPresent(g -> byId.put(id, g));
            }
        }

        List<BotGroup> candidates = new ArrayList<>();
        for (BotGroup group : byId.values()) {
            String id = group.getId();
            // A runtime is either DEAD (it is in the union set), ACTIVE (isGroupRunning
            // is exactly that check), or absent. Anything else is not a state the
            // predicate distinguishes.
            BotGroupStatus runtimeStatus = null;
            if (deadRuntimeIds.contains(id)) {
                runtimeStatus = BotGroupStatus.DEAD;
            } else if (behaviorService.isGroupRunning(id)) {
                runtimeStatus = BotGroupStatus.ACTIVE;
            }

            if (RecoveryEligibility.isCandidate(
                    group.getTargetStatus(), group.getActivationMode(), group.getActivationWindow(),
                    group.getBotCount(), runtimeStatus, runtimeStatus == BotGroupStatus.DEAD,
                    now, zone)) {
                candidates.add(group);
            }
        }
        return candidates;
    }
}
