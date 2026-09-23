package com.vingame.bot.infrastructure.gateway;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * One {@link GatewayBudget} per environment, keyed exactly like
 * {@code EnvironmentClientRegistry} (GATEWAY_REQUEST_BUDGET AD-1).
 * <p>
 * The keying is the design: Cloudflare's rule is <b>per gateway host</b>, and two
 * environments on different hosts must not throttle each other. A single global budget would
 * make a quiet brand's group start wait on a busy brand's reconnect storm for no reason at
 * all.
 * <p>
 * {@code EnvironmentClientRegistry.createClients} resolves the budget here and hands it to
 * {@code ApiGatewayClient.init(...)} and to {@code EnvironmentClients}, from where
 * {@code BotFactory.createBot} wires it into every bot. There is exactly one budget object
 * per environment id for the life of the JVM — {@link #forEnvironment} is a
 * {@code computeIfAbsent} and is never evicted, which costs one small object per environment
 * ever seen and means the window survives an environment's clients being rebuilt.
 * <p>
 * <b>One IP = one JVM.</b> Nothing here coordinates across processes and nothing is designed
 * to: if two instances ever share an egress IP against the same brand they share the 1,000
 * without knowing it, and that is a deployment error. The same caveat applies to
 * {@code scripts/bulk-create-accounts.py}, which is invisible to this registry.
 */
@Slf4j
@Component
public class GatewayBudgetRegistry {

    private final ConcurrentHashMap<String, SlidingWindowGatewayBudget> budgets = new ConcurrentHashMap<>();
    private final GatewayBudgetSettings settings;
    private final MeterRegistry meterRegistry;
    private final LongSupplier nanos;

    /**
     * {@code @Autowired} is mandatory, not decoration: this class has two constructors, and with
     * neither annotated Spring falls back to a no-arg constructor that does not exist and the
     * whole context fails to refresh. That is exactly the defect that crash-looped
     * {@code VipTalkClient} on staging on 2026-08-18 — and it was caught here by
     * {@code ApplicationContextLoadsTest}, which is the test written in response to it.
     */
    @Autowired
    public GatewayBudgetRegistry(GatewayBudgetSettings settings, MeterRegistry meterRegistry) {
        this(settings, meterRegistry, System::nanoTime);
    }

    /**
     * Clock-injecting seam — the {@code ScopedDebugRegistry.withClock} idiom. Tests drive the
     * window without sleeping; production uses {@link System#nanoTime()} and never the wall
     * clock, so an NTP step cannot move the window.
     */
    public GatewayBudgetRegistry(GatewayBudgetSettings settings, MeterRegistry meterRegistry,
                                 LongSupplier nanos) {
        this.settings = settings;
        this.meterRegistry = meterRegistry;
        this.nanos = nanos;
    }

    /**
     * One startup line, at INFO, once per JVM — tier-1 admissible by construction, and the
     * line the release verification greps to prove which posture a box is in.
     * <p>
     * A budget built in {@code enforce} before Phase 3 would <em>not</em> enforce, so it says
     * so explicitly at WARN. An operator who sets {@code GATEWAY_BUDGET_MODE=enforce} and
     * believes the fleet is protected when it is not is the precise failure this whole
     * feature exists to prevent, and it must not be discoverable only from a Cloudflare
     * block page.
     */
    @PostConstruct
    void logStartupPosture() {
        log.info("Gateway budget registry started (mode={}, window={}, hard-cap={}, ceilings {}, "
                        + "count-ws-upgrades={})",
                settings.mode().name().toLowerCase(java.util.Locale.ROOT),
                settings.window(),
                settings.hardCap(),
                settings.describeCeilings(),
                settings.countWsUpgrades());
        if (settings.mode() == GatewayBudgetMode.ENFORCE) {
            log.warn("bot.gateway.budget.mode=enforce, but enforcement is not implemented until "
                    + "GATEWAY_REQUEST_BUDGET Phase 3 — this instance is counting and publishing "
                    + "the window and NOTHING is being paced, queued or refused");
        }
    }

    /**
     * Get or create the budget for an environment.
     *
     * @param environmentId   the key, identical to {@code EnvironmentClientRegistry}'s
     * @param environmentName for the operator-facing lines only
     * @param productCode     numeric product code ({@code ProductCode.getCode()}) — the
     *                        {@code product} metric label, nullable exactly as everywhere
     *                        else that labels with it
     */
    public GatewayBudget forEnvironment(String environmentId, String environmentName, String productCode) {
        return budgets.computeIfAbsent(environmentId, id -> {
            log.debug("Creating gateway request budget for environment {} ({}, product {})",
                    id, environmentName, productCode);
            return new SlidingWindowGatewayBudget(
                    id, environmentName, productCode, settings, meterRegistry, nanos);
        });
    }

    /**
     * The budget for an environment <b>without creating one</b>. Read-only consumers (the
     * fleet rollup line, a future status endpoint) must not conjure a budget — and therefore
     * a fresh set of {@code gateway_budget_*} series — as a side effect of reading.
     */
    public GatewayBudget find(String environmentId) {
        return budgets.get(environmentId);
    }

    /**
     * A snapshot for {@code environmentId}, or a zeroed one carrying the configured hard cap
     * when no budget exists yet.
     * <p>
     * The zeroed form keeps the rollup line's shape constant: an operator (or a grep) reading
     * {@code gateway=0/900 queued=0/0/0 circuit=closed} learns "this environment has sent
     * nothing", which is true, rather than finding the fragment missing and having to work
     * out whether the feature is deployed.
     */
    public GatewayBudget.Snapshot snapshotOrEmpty(String environmentId, String environmentName,
                                                  String productCode) {
        GatewayBudget budget = budgets.get(environmentId);
        if (budget != null) {
            return budget.snapshot();
        }
        return new GatewayBudget.Snapshot(environmentId, environmentName, productCode,
                settings.mode(), 0, settings.hardCap(), 0, 0, 0, false);
    }

    /** Every live budget's snapshot, in creation order of the underlying map. */
    public List<GatewayBudget.Snapshot> snapshotAll() {
        List<GatewayBudget.Snapshot> all = new ArrayList<>(budgets.size());
        budgets.values().forEach(budget -> all.add(budget.snapshot()));
        return all;
    }

    /** How many environments have a budget. Diagnostics and tests. */
    public int size() {
        return budgets.size();
    }

    /** The policy every budget in this registry is built with. */
    public GatewayBudgetSettings settings() {
        return settings;
    }
}
