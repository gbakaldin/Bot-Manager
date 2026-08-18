package com.vingame.bot;

import com.vingame.bot.domain.alert.controller.AlertController;
import com.vingame.bot.domain.alert.service.AlertMessageFormatter;
import com.vingame.bot.domain.alert.service.AlertRoomRegistry;
import com.vingame.bot.domain.alert.service.AlertRouter;
import com.vingame.bot.domain.alert.service.AlertService;
import com.vingame.bot.domain.alert.service.AlertmanagerWebhookService;
import com.vingame.bot.domain.botgroup.repository.BotGroupRepository;
import com.vingame.bot.domain.botgroup.service.ActivationScheduler;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService;
import com.vingame.bot.infrastructure.notification.VipTalkClient;
import com.vingame.bot.infrastructure.observability.InfoGaugeRefresher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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
