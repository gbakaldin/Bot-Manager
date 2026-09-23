package com.vingame.bot;

import com.vingame.bot.domain.alert.controller.AlertController;
import com.vingame.bot.domain.alert.service.AlertMessageFormatter;
import com.vingame.bot.domain.alert.service.AlertRoomRegistry;
import com.vingame.bot.domain.alert.service.AlertRouter;
import com.vingame.bot.domain.alert.service.AlertService;
import com.vingame.bot.domain.alert.service.AlertmanagerWebhookService;
import com.vingame.bot.domain.botgroup.repository.BotGroupRepository;
import com.vingame.bot.domain.botgroup.service.ActivationScheduler;
import com.vingame.bot.domain.bot.message.MessageTypesRegistry;
import com.vingame.bot.domain.bot.message.g3.tip.TipGameMessageTypes;
import com.vingame.bot.domain.bot.message.slot.SlotMessageTypesImpl;
import com.vingame.bot.domain.bot.message.taixiu.JackpotTaiXiuMessageTypes;
import com.vingame.bot.domain.bot.service.BotFactory;
import com.vingame.bot.domain.bot.strategy.BettingStrategyFactory;
import com.vingame.bot.domain.bot.strategy.StrategyId;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategyFactory;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategyId;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.config.client.EnvironmentClientRegistry;
import com.vingame.bot.infrastructure.gateway.GatewayBudgetMode;
import com.vingame.bot.infrastructure.gateway.GatewayBudgetRegistry;
import com.vingame.bot.infrastructure.gateway.GatewayBudgetSettings;
import com.vingame.bot.infrastructure.notification.VipTalkClient;
import com.vingame.bot.infrastructure.observability.InfoGaugeRefresher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the real Spring context.
 * <p>
 * <b>Why this exists.</b> On 2026-08-18 a release of VIPTALK_ALERTING_V2 put bot-manager
 * into a crash loop on staging: {@code VipTalkClient} declared two constructors — the
 * {@code @Value} one and a package-private test seam — and neither carried
 * {@code @Autowired}, so Spring fell back to a no-arg constructor that does not exist.
 * 1708 unit tests, a QA pass, a code review and a compliance pass were all green, because
 * every one of them built the class directly through the seam and <em>nothing in the suite
 * ever started the container</em>. The first thing that ever tried to wire the bean was
 * the host.
 * <p>
 * That is a whole class of defect — ambiguous constructors, an unsatisfiable dependency, a
 * duplicate bean name, a {@code @Value} that cannot be converted, a {@code @PostConstruct}
 * that throws — which is invisible to unit tests by construction and only ever surfaces at
 * context refresh. This test is the build's answer to all of it: if the application cannot
 * start, the build fails, not the deploy.
 * <p>
 * <b>What is stubbed, and what deliberately is not.</b> Only {@link BotGroupRepository} is
 * replaced, and only because two beans do real Mongo I/O <em>during</em> context refresh
 * ({@link BotGroupBehaviorService#onStartup()} auto-starts ACTIVE groups;
 * {@link ActivationScheduler} schedules a reconcile pass). Everything else — every
 * controller, service, client, mapper, scheduler and the Mongo/actuator/springdoc
 * auto-configuration — is the real bean, constructed the way production constructs it. The
 * Mongo driver itself connects lazily, so no server is needed to build the context; the
 * URI below just makes any stray attempt fail fast instead of hanging on the 30 s default
 * server-selection timeout. Nothing here touches the network, VipTalk included: the
 * default posture is {@code viptalk.enabled=false}, and the enabled posture is covered by
 * {@link EnabledAlertingContext} without ever sending (the client only opens a socket
 * inside {@code send}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        // No server is contacted (see class javadoc) — the short timeouts exist so that
        // if that ever changes the test fails in a second rather than stalling for 30.
        "spring.data.mongodb.uri=mongodb://localhost:27017/bot-manager-context-test"
                + "?serverSelectionTimeoutMS=200&connectTimeoutMS=200",
        // The driver's background monitor logs a connection-refused stack trace at INFO on
        // a machine with no local Mongo. Expected here and pure noise in the build output;
        // silenced so a real failure in this test is the only thing a reader has to find.
        "logging.level.org.mongodb.driver.cluster=OFF",
})
@DisplayName("The application context starts")
class ApplicationContextLoadsTest {

    /**
     * The one stub. Both startup paths that would otherwise talk to Mongo go through this
     * repository, so replacing it keeps {@code BotGroupService} and everything above it
     * real. Mockito answers list-returning methods with an empty list, which is exactly
     * "no groups to auto-start".
     */
    @MockitoBean
    private BotGroupRepository botGroupRepository;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("every bean in the application can be instantiated and wired")
    void contextLoads() {
        assertThat(context).isNotNull();
        // Not a tautology: @SpringBootTest fails the test on a refresh failure, and this
        // asserts the context is a live one rather than a half-built stand-in.
        assertThat(context.getBeanDefinitionCount()).isPositive();
    }

    @Test
    @DisplayName("the VipTalk transport wires from configuration — the bean that crash-looped")
    void vipTalkClientIsConstructableBySpring() {
        VipTalkClient client = context.getBean(VipTalkClient.class);

        // Default posture: viptalk.enabled=false in application.properties. The assertion
        // that matters is that getBean() returned at all — before the @Autowired fix this
        // line never ran, because the context never got that far.
        assertThat(client.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("the whole alerting pipeline is present as beans")
    void alertingPipelineIsWired() {
        // Named one by one rather than asserted as a count: these are the beans
        // VIPTALK_ALERTING / _V2 added, and this is the list a future change has to keep
        // startable. AlertMessageFormatter carries the same two-constructor shape as
        // VipTalkClient (it already had its @Autowired) — both are covered here.
        assertThat(context.getBean(AlertService.class)).isNotNull();
        assertThat(context.getBean(AlertRouter.class)).isNotNull();
        assertThat(context.getBean(AlertRoomRegistry.class)).isNotNull();
        assertThat(context.getBean(AlertMessageFormatter.class)).isNotNull();
        assertThat(context.getBean(AlertmanagerWebhookService.class)).isNotNull();
        assertThat(context.getBean(AlertController.class)).isNotNull();
    }

    @Test
    @DisplayName("both strategy registries come up with the full built-in catalogue")
    void strategyRegistriesAreFullyPopulated() {
        // PLUGIN_HOT_RELOAD Phase 2a. The registry keys are string literals now
        // (AD-13), so nothing at compile time ties @StrategyImpl("RANDOM") to
        // StrategyId.RANDOM. StrategyCatalogParityTest in bot-strategies is the
        // primary guard, but it scans a package with a bare
        // AnnotationConfigApplicationContext — it cannot see a strategy that is
        // reachable from that scan yet unreachable from *Starter's*. This is the
        // only place in the build where the production scan runs, so this is the
        // only place that can pin it. It is the build-time twin of verification
        // P2-2 ("registered 9 strategies" / "registered 2 strategies" in the
        // boot log); a lower count means a bean lost its annotation or its
        // package.
        BettingStrategyFactory betting = context.getBean(BettingStrategyFactory.class);
        SlotStrategyFactory slot = context.getBean(SlotStrategyFactory.class);

        assertThat(betting.registeredKeys())
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(StrategyId.values()).map(Enum::name).toList());
        assertThat(slot.registeredKeys())
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(SlotStrategyId.values()).map(Enum::name).toList());

        // Resolution, not just registration: a key can be registered while the
        // prototype bean fails to build. This is what BotFactory does per bot.
        assertThat(betting.create(StrategyId.RANDOM.name())).isNotNull();
        assertThat(slot.create(SlotStrategyId.FIXED.name())).isNotNull();
    }

    @Test
    @DisplayName("the message-types registry comes up with every product's provider")
    void messageTypesRegistryIsFullyPopulated() {
        // PLUGIN_HOT_RELOAD Phase 2c. The providers moved from a hardcoded
        // `switch (productCode)` in shared code to @Component + @MessageTypesImpl
        // beans (AD-17/AD-18), so nothing at compile time ties product "116" to
        // TipGameMessageTypes any more. MessageTypesCoverageTest in bot-messages is
        // the primary guard, but it scans a package with a bare
        // AnnotationConfigApplicationContext — it cannot see a provider that is
        // reachable from that scan yet unreachable from *Starter's*, which is the
        // scan that actually runs in production. bot-messages is the first module
        // whose beans cross a jar boundary into this context, so this is also the
        // only place that proves the new spring-context dependency is enough.
        MessageTypesRegistry registry = context.getBean(MessageTypesRegistry.class);

        // A superset check, not an exact set. What this test uniquely proves is that no
        // provider goes *missing* under Starter's scan; "exactly these products and no
        // others" is already pinned once, in bot-messages, and a second copy here would
        // be a file every future brand has to touch for no extra protection.
        assertThat(registry.registeredBettingMiniProducts()).contains("097", "098", "116", "118");
        assertThat(registry.registeredTaiXiuProducts()).contains("114", "116");
        assertThat(registry.hasSlotProvider()).isTrue();

        // Resolution, not just registration — this is what BotFactory does per bot.
        assertThat(registry.bettingMini(ProductCode.P_116.getCode()))
                .isInstanceOf(TipGameMessageTypes.class);
        assertThat(registry.taiXiu(ProductCode.P_114.getCode()))
                .isInstanceOf(JackpotTaiXiuMessageTypes.class);
        assertThat(registry.slot()).isInstanceOf(SlotMessageTypesImpl.class);
    }

    @Test
    @DisplayName("BotFactory wires from the context with the registry injected")
    void botFactoryIsConstructableBySpring() {
        // BotFactory gained a ninth constructor argument in Phase 2c. It has no
        // @Autowired-ambiguity risk today (single constructor), but this is the class
        // of change that crash-looped VipTalkClient — assert the bean actually wires.
        assertThat(context.getBean(BotFactory.class)).isNotNull();
    }

    @Test
    @DisplayName("the gateway request budget binds from configuration and ships in observe mode")
    void gatewayBudgetIsWiredAndObserveOnly() {
        // GATEWAY_REQUEST_BUDGET. Three things only a real refresh can prove:
        //
        // 1. The settings bean BINDS. Its constructor validates the whole policy and throws
        //    IllegalStateException on a non-monotonic or over-cap combination, so a bad default
        //    is a crash loop on the host — exactly the class of defect this test file exists for.
        //    It also binds five Durations from strings like `5m` and `0`, which no unit test of
        //    the record can exercise.
        // 2. The bound values are the SHIPPED ones. The defaults live in three places
        //    (application.properties, GatewayBudgetConfig's @Value fallbacks, and
        //    GatewayBudgetSettings.defaults()), and a box whose real ceiling is not the one the
        //    plan reasoned about is a box that can still be Cloudflare-blocked.
        // 3. The mode is `observe`. Phase 1 must not pace anything anywhere; an `enforce`
        //    default shipped by accident would be a behaviour change on ten prod environments.
        GatewayBudgetSettings settings = context.getBean(GatewayBudgetSettings.class);
        assertThat(settings)
                .as("the bound settings must equal the shipped defaults — if these have drifted, "
                        + "fix the properties file or defaults(), do not relax this assertion")
                .isEqualTo(GatewayBudgetSettings.defaults());
        assertThat(settings.mode()).isEqualTo(GatewayBudgetMode.OBSERVE);

        // The registry is a @Component with a @PostConstruct that logs the startup posture, so
        // getBean() returning also proves that hook ran without throwing.
        GatewayBudgetRegistry budgetRegistry = context.getBean(GatewayBudgetRegistry.class);
        assertThat(budgetRegistry.settings()).isSameAs(settings);
        assertThat(budgetRegistry.size())
                .as("no environment has clients yet, so no budget and no gateway_budget_* series")
                .isZero();

        // And it is the SAME registry EnvironmentClientRegistry resolves budgets from. Two
        // registry beans would each count part of the traffic, and the window would read low.
        assertThat(ReflectionTestUtils.getField(
                context.getBean(EnvironmentClientRegistry.class), "gatewayBudgetRegistry"))
                .isSameAs(budgetRegistry);
    }

    @Test
    @DisplayName("the observability components start with the context")
    void observabilityComponentsAreWired() {
        // @PostConstruct-driven: constructing these also proves their startup hooks ran
        // without throwing, which is the other half of what only shows up at refresh.
        assertThat(context.getBean(InfoGaugeRefresher.class)).isNotNull();
        assertThat(context.getBean(ActivationScheduler.class)).isNotNull();
        assertThat(context.getBean(BotGroupBehaviorService.class)).isNotNull();
    }

    /**
     * The same context with alerting switched on.
     * <p>
     * The disabled path is the default and would hide a defect in the branch that only prod
     * takes — an unparseable timeout, a blank-token guard that throws instead of logging, a
     * room registry that trips over a configured ops room. This is a separate context (a
     * second refresh, ~seconds) precisely because those values are read at construction
     * time and cannot be changed afterwards.
     */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
            "spring.data.mongodb.uri=mongodb://localhost:27017/bot-manager-context-test"
                    + "?serverSelectionTimeoutMS=200&connectTimeoutMS=200",
            "logging.level.org.mongodb.driver.cluster=OFF",
            "viptalk.enabled=true",
            "viptalk.bot-token=context-test-token-never-used",
            "viptalk.ops-room-id=!context-test-ops:matrix-uat.viptalk.org",
            "viptalk.instance-label=context-test",
    })
    @DisplayName("with viptalk.enabled=true")
    class EnabledAlertingContext {

        @MockitoBean
        private BotGroupRepository botGroupRepository;

        @Autowired
        private ApplicationContext enabledContext;

        @Test
        @DisplayName("the channel comes up enabled and routed, still without sending anything")
        void alertingComesUpEnabled() {
            assertThat(enabledContext.getBean(VipTalkClient.class).isEnabled()).isTrue();
            assertThat(enabledContext.getBean(AlertService.class).isEnabled()).isTrue();
            assertThat(enabledContext.getBean(AlertRoomRegistry.class).opsRoom())
                    .contains("!context-test-ops:matrix-uat.viptalk.org");
        }
    }
}
