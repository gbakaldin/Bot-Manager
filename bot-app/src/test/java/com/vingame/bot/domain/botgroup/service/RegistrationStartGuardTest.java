package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.domain.bot.service.BotFactory;
import com.vingame.bot.domain.botgroup.model.ActivationMode;
import com.vingame.bot.domain.botgroup.model.ActivationWindow;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.RegistrationState;
import com.vingame.bot.domain.botgroup.model.StartOrigin;
import com.vingame.bot.domain.botgroup.repository.BotGroupRepository;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import com.vingame.bot.domain.game.service.GameService;
import com.vingame.bot.infrastructure.gateway.GatewayBudgetRegistry;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import com.vingame.bot.infrastructure.observability.GroupLifecycleAggregator;
import com.vingame.bot.infrastructure.observability.ScopedDebugEscalator;
import com.vingame.bot.infrastructure.observability.SessionAggregationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A group whose accounts are still being created, or whose creation stopped, must not start
 * (GATEWAY_REQUEST_BUDGET A2, "the two calls" / A28.5).
 *
 * <p><b>What starting one would actually do</b>, which is why this is a guard and not a
 * nicety: the group's usernames are {@code namePrefix + index} and the gateway has never heard
 * of the ones that have not been registered yet, so every bot past {@code registeredCount}
 * fails to authenticate. N failed logins against a brand's auth endpoint is indistinguishable
 * from an auth outage from the outside — and, under the request budget, N failed logins are N
 * requests spent against the Cloudflare cap for nothing.
 *
 * <p>The messages are asserted on, not just the type. A 400 that only says "no" sends the
 * operator to look at their own request; these name the counts and the two ways forward.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Start guards for a registering bot group")
class RegistrationStartGuardTest {

    @Mock private BotGroupService botGroupService;
    @Mock private EnvironmentService environmentService;
    @Mock private GameService gameService;
    @Mock private BotFactory botFactory;
    @Mock private BotMetrics botMetrics;
    @Mock private SessionAggregationService sessionAggregationService;
    @Mock private GroupLifecycleAggregator groupLifecycleAggregator;
    @Mock private ScopedDebugEscalator scopedDebugEscalator;
    @Mock private GatewayBudgetRegistry gatewayBudgetRegistry;
    /** PLUGIN_HOT_RELOAD_3_4 D-15: answers null, so bots fall back to `builtin` as before. */

    @InjectMocks
    private BotGroupBehaviorService service;

    @AfterEach
    void shutdown() {
        try {
            service.shutdown();
        } catch (Exception ignored) {
            // best effort
        }
    }

    private BotGroup group(String state, int registered) {
        return BotGroup.builder()
                .id("g-1").name("Tai Xiu 500").environmentId("env-1").gameId("game-1")
                .namePrefix("bot").botCount(500)
                .registeredCount(registered)
                .registrationState(state)
                .build();
    }

    @Test
    @DisplayName("a pending group is a 400 naming the counts and the way out")
    void pendingGroupCannotStart() {
        when(botGroupService.findById("g-1")).thenReturn(group(RegistrationState.PENDING, 120));

        assertThatThrownBy(() -> service.startAsync("g-1", StartOrigin.REST, () -> { }))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Tai Xiu 500")
                .hasMessageContaining("120/500")
                .hasMessageContaining("REGISTRATION_PENDING")
                .hasMessageContaining("PATCH botCount down to 120");
    }

    @Test
    @DisplayName("a failed group is a 400 naming the retry endpoint")
    void failedGroupCannotStart() {
        when(botGroupService.findById("g-1")).thenReturn(group(RegistrationState.FAILED, 63));

        assertThatThrownBy(() -> service.startAsync("g-1", StartOrigin.REST, () -> { }))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("63/500")
                .hasMessageContaining("/registration/retry")
                .hasMessageContaining("PATCH botCount down to 63");
    }

    @Test
    @DisplayName("a completed group starts — the guard reads the state, not the counts")
    void aCompleteGroupIsUnaffected() {
        // registrationState cleared, registeredCount equal to botCount: the normal end state.
        // The guard must key on the STATE, because a group created before this feature has
        // neither a state nor a count and must keep starting exactly as it always has.
        BotGroup complete = group(null, 500);
        when(botGroupService.findById("g-1")).thenReturn(complete);

        // The accept is what the guards run on, so a returned STARTING is the assertion: the
        // start was admitted rather than refused. What the build does afterwards on its virtual
        // thread is not this class's subject.
        assertThat(service.startAsync("g-1", StartOrigin.REST, () -> { }))
                .isEqualTo(com.vingame.bot.domain.botgroup.model.BotGroupStatus.STARTING);
    }

    @Test
    @DisplayName("a legacy group with no registration fields at all still starts")
    void aLegacyGroupIsUnaffected() {
        BotGroup legacy = BotGroup.builder()
                .id("g-1").name("Old").environmentId("env-1").gameId("game-1")
                .namePrefix("bot").botCount(10)
                .build();
        when(botGroupService.findById("g-1")).thenReturn(legacy);

        assertThat(service.startAsync("g-1", StartOrigin.REST, () -> { }))
                .isEqualTo(com.vingame.bot.domain.botgroup.model.BotGroupStatus.STARTING);
    }

    @Test
    @DisplayName("a game-less group would register and simply never start (Open Item 14)")
    void aGamelessGroupRegistersAndNeverStarts() {
        // A2's recommendation on the "account factory" group, recorded as an assertion rather
        // than built: the engine is already one DTO constraint away from supporting it.
        // BotGroupService.save's validateGameEnvironmentMatch early-returns on a null gameId, so
        // such a group registers normally — and validateStartable rejects it with a 400, which
        // is the RIGHT behaviour for a group that must never start.
        //
        // So enabling the follow-up is a change to BotGroupDTO.gameId's
        // @NotBlank(groups = OnCreate.class) and nothing else in this path. If that assertion
        // ever stops holding, the follow-up has quietly grown a second requirement.
        BotGroup gameless = BotGroup.builder()
                .id("g-1").name("Account Factory").environmentId("env-1")
                .namePrefix("liengbot").botCount(500)
                .registeredCount(500)
                .build();
        when(botGroupService.findById("g-1")).thenReturn(gameless);

        assertThatThrownBy(() -> service.startAsync("g-1", StartOrigin.REST, () -> { }))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("has no gameId set");
    }

    @Test
    @DisplayName("the activation reconciler skips a registering SCHEDULED group without a 400")
    void activationSchedulerSkipsARegisteringGroup() {
        // A28.5: this reconciler needs its OWN guard because it selects by activation MODE, not
        // by status. Without it, startLocked's 400 is thrown, caught and logged here once a
        // minute for the entire duration of a 500-account registration — and on the tick after
        // the last one it would be indistinguishable from a real misconfiguration.
        BotGroupRepository repository = org.mockito.Mockito.mock(BotGroupRepository.class);
        BotGroupBehaviorService behavior = org.mockito.Mockito.mock(BotGroupBehaviorService.class);

        BotGroup scheduled = BotGroup.builder()
                .id("g-1").name("Scheduled").environmentId("env-1").gameId("game-1")
                .botCount(500).registeredCount(10)
                .registrationState(RegistrationState.PENDING)
                .activationMode(ActivationMode.SCHEDULED)
                .activationWindow(ActivationWindow.builder()
                        .from(java.time.LocalTime.MIDNIGHT)
                        .to(java.time.LocalTime.of(23, 59))
                        .days(Set.of(DayOfWeek.values()))
                        .build())
                .build();
        when(repository.findByActivationMode(ActivationMode.SCHEDULED)).thenReturn(List.of(scheduled));

        new ActivationScheduler(repository, behavior, "Asia/Ho_Chi_Minh", 60).reconcileAll();

        verify(behavior, never()).startAsync(anyString(), any(), any());
        verify(behavior, never()).stop(anyString());
    }

    @Test
    @DisplayName("the activation reconciler still STOPS a registering group whose window has closed")
    void theActivationReconcilerStillStopsARegisteringGroup() {
        // Review B3. The registration guard used to `return` ABOVE ActivationEvaluator.decide, so a
        // group that acquired a registrationState was removed from reconciliation entirely — STOP
        // included. A group can acquire one WHILE RUNNING: that is A2.7's "register 200 more bots
        // for this group". From that moment the reconciler never looked at it again, so its window
        // closed and it kept placing real bets outside the hours an operator configured; and if
        // registration then went FAILED it never stopped at all, because FAILED is equally non-null
        // and was equally skipped.
        BotGroupRepository repository = org.mockito.Mockito.mock(BotGroupRepository.class);
        BotGroupBehaviorService behavior = org.mockito.Mockito.mock(BotGroupBehaviorService.class);

        BotGroup scheduled = BotGroup.builder()
                .id("g-1").name("Scheduled").environmentId("env-1").gameId("game-1")
                .botCount(700).registeredCount(500)
                .registrationState(RegistrationState.PENDING)
                .activationMode(ActivationMode.SCHEDULED)
                // A window that is closed at every instant this test can run.
                .activationWindow(ActivationWindow.builder()
                        .from(java.time.LocalTime.of(3, 0))
                        .to(java.time.LocalTime.of(3, 1))
                        .days(Set.of(DayOfWeek.values()))
                        .build())
                .build();
        when(repository.findByActivationMode(ActivationMode.SCHEDULED)).thenReturn(List.of(scheduled));
        // …and it is running, which is what makes the decision STOP rather than NONE.
        when(behavior.isGroupRunning("g-1")).thenReturn(true);

        new ActivationScheduler(repository, behavior, "Asia/Ho_Chi_Minh", 60).reconcileAll();

        // Stopping a group whose accounts are half created is always safe; it is the START that is
        // not. So the guard gates one arm and not the switch.
        verify(behavior).stop("g-1");
        verify(behavior, never()).startAsync(anyString(), any(), any());
    }

    @Test
    @DisplayName("a FAILED registration does not pin a running group outside its window either")
    void aFailedRegistrationDoesNotPinARunningGroup() {
        BotGroupRepository repository = org.mockito.Mockito.mock(BotGroupRepository.class);
        BotGroupBehaviorService behavior = org.mockito.Mockito.mock(BotGroupBehaviorService.class);

        BotGroup scheduled = BotGroup.builder()
                .id("g-1").name("Scheduled").environmentId("env-1").gameId("game-1")
                .botCount(700).registeredCount(500)
                .registrationState(RegistrationState.FAILED)
                .activationMode(ActivationMode.SCHEDULED)
                .activationWindow(ActivationWindow.builder()
                        .from(java.time.LocalTime.of(3, 0))
                        .to(java.time.LocalTime.of(3, 1))
                        .days(Set.of(DayOfWeek.values()))
                        .build())
                .build();
        when(repository.findByActivationMode(ActivationMode.SCHEDULED)).thenReturn(List.of(scheduled));
        when(behavior.isGroupRunning("g-1")).thenReturn(true);

        new ActivationScheduler(repository, behavior, "Asia/Ho_Chi_Minh", 60).reconcileAll();

        // This was the worse half of B3: FAILED never clears on its own, so the group would have
        // kept betting outside its window until a human noticed.
        verify(behavior).stop("g-1");
    }
}
