package com.vingame.bot.infrastructure.gateway;

import com.vingame.bot.common.exception.GatewayBudgetException;
import com.vingame.bot.common.gateway.GatewayRequestScope;
import com.vingame.bot.common.gateway.RequestTier;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.infrastructure.auth.AuthProfile;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.DisplayNameService;
import com.vingame.bot.infrastructure.client.stub.StubGateway;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import com.vingame.websocketparser.auth.AuthContext;
import com.vingame.websocketparser.auth.LoginRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>The cap, proven against a real HTTP server's own count</b> — V3g (GATEWAY_REQUEST_BUDGET
 * A20.11, AD-22).
 * <p>
 * Every other test in this feature measures our own bookkeeping: they ask the budget how many
 * requests it thinks it admitted. This one asks the <em>receiver</em>. {@link StubGateway}
 * keeps its own sliding count from its own arrival stamps, shares no code with
 * {@link SlidingWindowGatewayBudget}, and is driven through the real
 * {@link ApiGatewayClient} — the real funnel, the real JDK {@code HttpClient}, real sockets. If
 * the two ever disagree, the number on the Grafana panel is not the number the Cloudflare edge
 * is counting, and the whole feature is decoration.
 * <p>
 * <b>On 127.0.0.1, always.</b> The stub binds the loopback address on an ephemeral port. A
 * variant of this test pointed at a gwms host could block a whole brand at the edge, and the
 * user has confirmed there is <b>no tolerable cooldown</b> for that: possibly ~24 hours,
 * possibly until someone clears it manually. Nothing in this file or in {@code StubGateway}
 * contains a hostname.
 * <p>
 * <b>Named {@code *IT}, so surefire's default includes ({@code *Test}, {@code Test*},
 * {@code *Tests}, {@code *TestCase}) skip it</b>, and tagged {@code stub-gateway} so it can be
 * selected explicitly. It is run by hand before the single deployment:
 * <pre>
 *   mvn -pl bot-engine -am test -Dtest=GatewayBudgetEscalationIT \
 *       -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * It takes seconds rather than the ~7 minutes the plan budgeted, because the window is injected:
 * a 5-second window driven on the real clock exercises expiry, refill and the sliding boundary
 * with the same arithmetic a 5-minute one would.
 */
@Tag("stub-gateway")
@Timeout(value = 180, unit = TimeUnit.SECONDS)
@DisplayName("Gateway budget escalation — the cap holds against the receiver's own count")
class GatewayBudgetEscalationIT {

    /**
     * A short window on the real clock. The budget's arithmetic is window-relative, so five
     * seconds proves the same properties as five minutes; what it cannot compress is the
     * real-time expiry, which is the point of running this on the real clock at all.
     */
    private static final Duration WINDOW = Duration.ofSeconds(5);

    /** A small cap, for the same reason: the ratio to the tier ceilings is what matters. */
    private static final int HARD_CAP = 60;
    private static final int DEFAULT_CEILING = 30;
    private static final int PRIORITIZED_CEILING = 45;

    private StubGateway gateway;
    private SlidingWindowGatewayBudget budget;
    private ApiGatewayClient client;

    @BeforeEach
    void setUp() throws Exception {
        gateway = StubGateway.start(WINDOW, System::nanoTime);
        budget = new SlidingWindowGatewayBudget("env-it", "IT", "116", settings(),
                new SimpleMeterRegistry(), System::nanoTime);

        client = new ApiGatewayClient(new DisplayNameService(), new BotMetrics(new SimpleMeterRegistry()));
        client.init(gateway.baseUrl(), "bc114097", new AuthProfile(
                        "/gwms/v1/bot/login.aspx", "/gwms/v1/bot/register.aspx",
                        "/gwms/v1/bot/update-fullname.aspx", "x-tok", loginFactory()),
                budget);
        ReflectionTestUtils.setField(client, "registrationParallelism", 4);
        ReflectionTestUtils.setField(client, "botIp", "127.0.0.1");
    }

    @AfterEach
    void tearDown() {
        budget.shutdown();
        gateway.close();
    }

    private static GatewayBudgetSettings settings() {
        // One constructor call, not a chain of `with…`: every one of them validates, so lowering
        // the ceilings one at a time trips the monotonic check half way through. The validation
        // working is the point — see GatewayBudgetSettingsTest.
        java.util.Map<RequestTier, Integer> ceilings =
                new java.util.EnumMap<>(RequestTier.class);
        ceilings.put(RequestTier.DEFAULT, DEFAULT_CEILING);
        ceilings.put(RequestTier.PRIORITIZED, PRIORITIZED_CEILING);
        ceilings.put(RequestTier.ESSENTIAL, HARD_CAP);
        java.util.Map<RequestTier, Duration> maxWaits =
                new java.util.EnumMap<>(RequestTier.class);
        maxWaits.put(RequestTier.DEFAULT, Duration.ofSeconds(1));
        maxWaits.put(RequestTier.PRIORITIZED, Duration.ofSeconds(1));
        // ESSENTIAL stays unbounded, exactly as production: the window drains by construction,
        // and theOverflowWaitsForExpiry below is what proves that is a real guarantee.
        maxWaits.put(RequestTier.ESSENTIAL, Duration.ZERO);
        return new GatewayBudgetSettings(GatewayBudgetMode.ENFORCE, WINDOW, HARD_CAP, ceilings,
                maxWaits, Duration.ofSeconds(5), true,
                GatewayBudgetSettings.BLOCK_PROBE_INTERVAL_DEFAULT);
    }

    private static Function<AuthContext, ? extends LoginRequest> loginFactory() {
        // A minimal brand body. The wire shape of each real brand's LoginRequest is pinned by
        // LoginRequestSerializationTest; what this IT is about is how many requests leave.
        return ctx -> new StubLoginRequest(ctx.userName());
    }

    /** Public getters are what the library's ObjectMapper serialises. */
    public static final class StubLoginRequest implements LoginRequest {
        private final String username;

        StubLoginRequest(String username) {
            this.username = username;
        }

        public String getUsername() {
            return username;
        }
    }

    /**
     * Every request below is a {@code deposit}, deliberately, and not a balance read.
     * {@code ApiGatewayClient.getBalance} carries a pre-existing unconditional
     * {@code Thread.sleep(500)} before its request (a follow-up item in the plan, harmless under
     * the budget because the stamp is taken at admission). That half-second dominates a compressed
     * window: thirty sequential reads span fifteen seconds, so the first stamps expire before the
     * last is sent and the cap can never be reached. The deposit path has no sleep, so what the
     * numbers below measure is the budget rather than that sleep.
     */
    private static GatewayRequestScope scope(String group, int i) {
        return GatewayRequestScope.forBot(group, "authtestws" + i, () -> false);
    }

    @Test
    @DisplayName("910 mixed-tier attempts: the stub never sees more than the cap in any window")
    void theCapHoldsAgainstTheReceiversOwnCount() throws Exception {
        // The escalation the plan asks for, compressed: attempts far past the cap, mixed across
        // all three tiers, fired concurrently, with the window sampled continuously while they
        // run. What is asserted is the STUB's count, not ours.
        int attempts = 910;
        int threads = 24;
        AtomicInteger admitted = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        AtomicInteger observedMax = new AtomicInteger();
        AtomicInteger budgetMax = new AtomicInteger();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        AtomicLong next = new AtomicLong();

        // A sampler on the receiver's side, so a momentary overshoot cannot hide between the
        // start and the end of the run.
        AtomicLong stop = new AtomicLong(0);
        Thread sampler = Thread.ofVirtual().name("stub-sampler").start(() -> {
            while (stop.get() == 0) {
                observedMax.accumulateAndGet(gateway.countInLastWindow(), Math::max);
                budgetMax.accumulateAndGet(budget.windowRequests(), Math::max);
                Thread.onSpinWait();
            }
        });

        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            Thread.ofVirtual().name("escalation-" + t).start(() -> {
                try {
                    long i;
                    while ((i = next.getAndIncrement()) < attempts) {
                        RequestTier tier = RequestTier.values()[(int) (i % 3)];
                        try {
                            client.deposit("authtestws" + i, 1_000_000L, tier,
                                    scope("group-1", (int) i));
                            admitted.incrementAndGet();
                        } catch (GatewayBudgetException e) {
                            refused.incrementAndGet();
                        } catch (Throwable e) {
                            unexpected.add(e);
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(done.await(150, TimeUnit.SECONDS))
                .as("every attempt returned — an admission that parks for ever is the P13 shape")
                .isTrue();
        stop.set(1);
        sampler.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(unexpected)
                .as("the only acceptable failure is a budget refusal; anything else means the "
                        + "stub or the client misbehaved, which would invalidate the count")
                .isEmpty();

        // Both sides, so a disagreement is attributable rather than a mystery.

        // 1. The budget's own window, STRICTLY within the cap. This is the invariant the
        //    implementation owes: for any interval of length W, the admissions inside it are
        //    counted by the last of them against its own lookback, so there can never be more
        //    than the cap. It is exact, and a failure here is a defect in the arithmetic.
        assertThat(budgetMax.get())
                .as("the budget's own view of its window")
                .isLessThanOrEqualTo(HARD_CAP);

        // 2. The RECEIVER's window. This is the number that actually matters, and it is allowed
        //    to read slightly higher than the cap for a reason worth writing down rather than
        //    papering over: WE STAMP AT ADMISSION, THE EDGE COUNTS AT ARRIVAL. A request admitted
        //    at t is sent at t and arrives at t+L, so when the window rolls and a fresh burst is
        //    admitted, the tail of the previous burst may still be arriving. An observer's window
        //    can therefore hold up to `cap + (requests in flight at the roll)`.
        //
        //    That bound is what the 100-request gap between hard-cap=900 and Cloudflare's 1,000
        //    exists to absorb — AD-5 calls it "the only margin for traffic the JVM cannot see",
        //    and this is one of the things it cannot see. In production the in-flight count is
        //    bounded by `bot.creation.parallelism` and `user.registration.parallelism` (10 each),
        //    i.e. an order of magnitude inside that margin, and admissions are ~3/s rather than a
        //    burst. Here it is bounded by the thread count, deliberately, because a compressed
        //    window exaggerates the ratio of flush time to window length by ~60x.
        //
        //    Measured before this was understood: 84 in a 60-request window with 24 threads —
        //    which is 60 + 24 exactly, and it is what sent us looking and found the stale-clock
        //    defect fixed alongside this test (the stamp used to be dated from before the lock).
        assertThat(observedMax.get())
                .as("the stub's own sliding count of requests it actually received, sampled "
                        + "continuously during the run (paths: %s)", gateway.describe())
                .isLessThanOrEqualTo(HARD_CAP + threads);

        // ...and the two sides agree on what happened, which is what makes the dashboard honest.
        assertThat(gateway.totalReceived())
                .as("every admitted request really was sent, and no refused one was")
                .isEqualTo(admitted.get());
        assertThat(admitted.get() + refused.get()).isEqualTo(attempts);
        assertThat(refused.get())
                .as("910 attempts against a cap of %d cannot all have been admitted", HARD_CAP)
                .isPositive();
        assertThat(admitted.get())
                .as("but the window DOES refill: over several windows far more than one cap's "
                        + "worth of requests gets through, which is what makes a paced 3,000-bot "
                        + "start finish rather than fail")
                .isGreaterThan(HARD_CAP);
    }

    @Test
    @DisplayName("DEFAULT admissions stop at their own ceiling while ESSENTIAL keeps going")
    void defaultStopsAtItsCeilingAndEssentialDoesNot() throws Exception {
        // Fill the window to DEFAULT's ceiling with DEFAULT traffic, on the real client.
        for (int i = 0; i < DEFAULT_CEILING; i++) {
            client.deposit("bot" + i, 1_000_000L, RequestTier.DEFAULT, scope("group-1", i));
        }
        assertThat(gateway.countInLastWindow()).isEqualTo(DEFAULT_CEILING);

        // Five probes with NO wait at all (getBalanceIfAdmitted's semantics), because what is
        // under test is the ceiling and not the patience. With DEFAULT's configured one-second
        // wait, five sequential refusals take five seconds — a whole window on this compressed
        // clock — and the last of them is legitimately admitted as the first stamps expire.
        int defaultRefusals = 0;
        for (int i = 0; i < 5; i++) {
            if (client.getBalanceIfAdmitted("session-1", "fp", "bot-more" + i,
                    scope("group-1", i)).isEmpty()) {
                defaultRefusals++;
            }
        }
        assertThat(defaultRefusals)
                .as("the gap between DEFAULT's 30 and ESSENTIAL's 60 IS the reservation for the "
                        + "tiers above — a DEFAULT request must be refused there even though the "
                        + "window is only half full")
                .isEqualTo(5);

        // A group start's ESSENTIAL traffic uses the gap that was being held for it.
        for (int i = 0; i < HARD_CAP - DEFAULT_CEILING; i++) {
            client.deposit("starting" + i, 1_000_000L, RequestTier.ESSENTIAL, scope("group-2", i));
        }
        assertThat(gateway.countInLastWindow()).isEqualTo(HARD_CAP);
        assertThat(gateway.countFor("deposit"))
                .as("every one of them was a real HTTP request to the stub")
                .isEqualTo(HARD_CAP);
    }

    @Test
    @DisplayName("the overflow is admitted only after the first stamps expire")
    void theOverflowWaitsForExpiry() throws Exception {
        for (int i = 0; i < HARD_CAP; i++) {
            client.deposit("bot" + i, 1_000_000L, RequestTier.ESSENTIAL, scope("g", i));
        }
        assertThat(gateway.countInLastWindow()).isEqualTo(HARD_CAP);

        long before = System.nanoTime();
        // ESSENTIAL's wait is unbounded and the window drains by construction, so this returns —
        // after the oldest stamp ages out, and not before.
        client.deposit("overflow", 1_000_000L, RequestTier.ESSENTIAL, scope("g", 999));
        Duration waited = Duration.ofNanos(System.nanoTime() - before);

        assertThat(waited)
                .as("it really parked on the window rather than slipping past the cap")
                .isGreaterThan(Duration.ofMillis(500));
        assertThat(gateway.totalReceived()).isEqualTo(HARD_CAP + 1);
        assertThat(gateway.countInLastWindow())
                .as("and the window is still within the cap afterwards, because the stamp it "
                        + "waited for is gone")
                .isLessThanOrEqualTo(HARD_CAP);
    }

    @Test
    @DisplayName("a real login goes through the funnel, is counted, and parses the stub's tokens")
    void aLoginIsCountedAndParsed() {
        // The login is the one request that does not go through our own HttpClient — it goes
        // through the library, wrapped by BoundedLogin. So it needs its own end-to-end check that
        // it is (a) counted by the budget and (b) actually received by the gateway.
        var tokens = client.authenticate(BotCredentials.builder()
                        .username("authtestws1").password("123123a").fingerprint("fp").build(),
                RequestTier.ESSENTIAL, scope("group-1", 1));

        assertThat(tokens.getAgencyToken())
                .as("`token` in the envelope is the AGENCY token — the opposite way round from "
                        + "the internal names, and the value the WS AUTH frame carries")
                .startsWith("18-agency-");
        assertThat(tokens.getAuthToken()).startsWith("session-");
        assertThat(tokens.getJwtToken()).startsWith("jwt-");
        assertThat(gateway.countFor("login")).isEqualTo(1);
        assertThat(budget.windowRequests())
                .as("counted, even though the request left through the library's HttpClient "
                        + "rather than ours")
                .isEqualTo(1);
    }
}
