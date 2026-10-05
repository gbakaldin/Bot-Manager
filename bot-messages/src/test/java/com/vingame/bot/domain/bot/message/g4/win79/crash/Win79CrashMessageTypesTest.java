package com.vingame.bot.domain.bot.message.g4.win79.crash;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.vingame.bot.domain.bot.message.CrashMessageTypes;
import com.vingame.bot.domain.bot.message.crash.CrashBetAck;
import com.vingame.bot.domain.bot.message.crash.CrashBettingClosed;
import com.vingame.bot.domain.bot.message.crash.CrashCashoutAck;
import com.vingame.bot.domain.bot.message.crash.CrashMessage;
import com.vingame.bot.domain.bot.message.crash.CrashRoundEnd;
import com.vingame.bot.domain.bot.message.crash.CrashRoundStart;
import com.vingame.bot.domain.bot.message.crash.CrashSubscribeResponse;
import com.vingame.bot.domain.bot.message.crash.CrashTick;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * AVIATOR_BOT Phase 1: the captured 119 {@code aviatorPlugin} frames
 * ({@code docs/captures/aviator-119-2026-10-05.jsonl}) parse through a mapper configured
 * the way a bot's is — {@code FAIL_ON_UNKNOWN_PROPERTIES=false} plus
 * {@link Win79CrashMessageTypes#getTypeRegistrations(int)} — and every frame is read
 * through the polymorphic base {@link CrashMessage}, so the cmd → class routing is what
 * is under test, not a hand-picked target class.
 *
 * <p>Fixtures under {@code messages/win79/crash/} are the captured inbound bodies
 * verbatim, wrapped as {@code [5, body]}, except {@code subscribe.json} (capture L3),
 * whose 50-entry {@code cH} chat log and {@code htr} history are emptied. File → capture
 * line: subscribe L3, tick-climbing L4, tick-crash L12, round-end-no-bet L13,
 * round-start L15, bet-ack L17, bet-board L18, betting-closed L20, cashout-ack L22,
 * round-end L24.
 */
@DisplayName("Win79CrashMessageTypes — captured 119 Avatar frames")
class Win79CrashMessageTypesTest {

    private static final int AVIATOR = 1700;
    private static final int JAKE = 1;
    private static final int NEYTIRI = 2;

    private static ObjectMapper mapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerSubtypes(new Win79CrashMessageTypes().getTypeRegistrations(AVIATOR));
        return mapper;
    }

    private static JsonNode fixtureBody(String name) throws Exception {
        String resource = "/messages/win79/crash/" + name;
        try (var in = Win79CrashMessageTypesTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("fixture %s", resource).isNotNull();
            JsonNode root = new ObjectMapper().readTree(in.readAllBytes());
            assertThat(root.isArray()).as("array-framed [type,{...}]").isTrue();
            assertThat(root.get(0).asInt()).as("ACTION_RESPONSE category").isEqualTo(5);
            return root.get(1);
        }
    }

    private static CrashMessage readFixture(String name) throws Exception {
        return mapper().treeToValue(fixtureBody(name), CrashMessage.class);
    }

    @Nested
    @DisplayName("captured frames")
    class Captured {

        @Test
        @DisplayName("1700 snapshot (L3) → the subscribe reply, sid 1638118")
        void subscribe() throws Exception {
            CrashMessage parsed = readFixture("subscribe.json");

            assertThat(parsed).isInstanceOf(Win79CrashSubscribeResponse.class)
                    .isInstanceOf(CrashSubscribeResponse.class);
            assertThat(parsed.getCmd()).isEqualTo(1700);
            assertThat(((Win79CrashSubscribeResponse) parsed).sid()).isEqualTo(1_638_118L);
        }

        @Test
        @DisplayName("1709 tick (L4) → both runners at 143, neither crashed")
        void climbingTick() throws Exception {
            CrashMessage parsed = readFixture("tick-climbing.json");

            assertThat(parsed).isInstanceOf(Win79CrashTick.class);
            CrashTick tick = (CrashTick) parsed;
            assertThat(tick.getCmd()).isEqualTo(1709);
            assertThat(tick.sid()).isEqualTo(1_638_118L);
            assertThat(tick.multiplierFor(JAKE)).isEqualTo(143L);
            assertThat(tick.multiplierFor(NEYTIRI)).isEqualTo(143L);
            assertThat(tick.crashedFor(JAKE)).isFalse();
            assertThat(tick.crashedFor(NEYTIRI)).isFalse();
        }

        /**
         * F-1: Neytiri crashed at 2.86 while Jake is still flying at 6 — an integer on the
         * wire (F-2). The frozen 286 is a real value that can sit at or above a target;
         * only the flag says it must not be cashed out.
         */
        @Test
        @DisplayName("1709 crash tick (L12) → Neytiri crashed at 286, Jake flying at 600 (integer wire value)")
        void crashTick() throws Exception {
            CrashTick tick = (CrashTick) readFixture("tick-crash.json");

            assertThat(tick.crashedFor(NEYTIRI)).isTrue();
            assertThat(tick.multiplierFor(NEYTIRI)).isEqualTo(286L);
            assertThat(tick.crashedFor(JAKE)).isFalse();
            assertThat(tick.multiplierFor(JAKE)).isEqualTo(600L);
        }

        @Test
        @DisplayName("an eid outside [1..2] reads 0 / crashed — never cash out, never throw")
        void unknownRunner() throws Exception {
            CrashTick tick = (CrashTick) readFixture("tick-climbing.json");

            assertThat(tick.multiplierFor(3)).isZero();
            assertThat(tick.crashedFor(3)).isTrue();
            assertThat(tick.multiplierFor(0)).isZero();
            assertThat(tick.crashedFor(0)).isTrue();
            assertThat(tick.multiplierFor(-1)).isZero();
            assertThat(tick.crashedFor(-1)).isTrue();
        }

        @Test
        @DisplayName("1702 bet ack (L17) → eid 1, stake 10000")
        void betAck() throws Exception {
            CrashMessage parsed = readFixture("bet-ack.json");

            assertThat(parsed).isInstanceOf(Win79CrashBetAck.class);
            CrashBetAck ack = (CrashBetAck) parsed;
            assertThat(ack.getCmd()).isEqualTo(1702);
            assertThat(ack.eid()).isEqualTo(1);
            assertThat(ack.stake()).isEqualTo(10_000L);
        }

        @Test
        @DisplayName("1703 cash-out ack (L22) → winnings 24000 gross, multiplier 2.4, eid 1, stake 10000")
        void cashoutAck() throws Exception {
            CrashMessage parsed = readFixture("cashout-ack.json");

            assertThat(parsed).isInstanceOf(Win79CrashCashoutAck.class);
            CrashCashoutAck ack = (CrashCashoutAck) parsed;
            assertThat(ack.getCmd()).isEqualTo(1703);
            assertThat(ack.winningsFor("anyone")).isEqualTo(24_000L);
            assertThat(ack.winningsFor(null)).isEqualTo(24_000L);
            assertThat(ack.multiplier()).isCloseTo(2.4, within(1e-9));
            assertThat(ack.payout()).isCloseTo(24_000.0, within(1e-9));
            assertThat(ack.eid()).isEqualTo(1);
            assertThat(ack.stake()).isEqualTo(10_000L);
        }

        @Test
        @DisplayName("1707 round end (L24) → sid 1638119, ownStake 10000")
        void roundEnd() throws Exception {
            CrashMessage parsed = readFixture("round-end.json");

            assertThat(parsed).isInstanceOf(Win79CrashRoundEnd.class);
            CrashRoundEnd end = (CrashRoundEnd) parsed;
            assertThat(end.getCmd()).isEqualTo(1707);
            assertThat(end.sid()).isEqualTo(1_638_119L);
            assertThat(end.ownStake()).isEqualTo(10_000L);
            assertThat(((Win79CrashRoundEnd) end).crashPointFor(JAKE)).isEqualTo(1159L);
            assertThat(((Win79CrashRoundEnd) end).crashPointFor(NEYTIRI)).isEqualTo(286L);
        }

        @Test
        @DisplayName("1707 round end with no bet (L13) → sid 1638118, ownStake 0")
        void roundEndWithoutABet() throws Exception {
            CrashRoundEnd end = (CrashRoundEnd) readFixture("round-end-no-bet.json");

            assertThat(end.sid()).isEqualTo(1_638_118L);
            assertThat(end.ownStake()).isZero();
        }

        @Test
        @DisplayName("1705 round start (L15) → sid 1638119")
        void roundStart() throws Exception {
            CrashMessage parsed = readFixture("round-start.json");

            assertThat(parsed).isInstanceOf(Win79CrashRoundStart.class);
            assertThat(parsed.getCmd()).isEqualTo(1705);
            assertThat(((CrashRoundStart) parsed).sid()).isEqualTo(1_638_119L);
        }

        @Test
        @DisplayName("1706 betting closed (L20) → sid 1638119")
        void bettingClosed() throws Exception {
            CrashMessage parsed = readFixture("betting-closed.json");

            assertThat(parsed).isInstanceOf(Win79CrashBettingClosed.class);
            assertThat(parsed.getCmd()).isEqualTo(1706);
            assertThat(((CrashBettingClosed) parsed).sid()).isEqualTo(1_638_119L);
        }

        /** AD-4: the bet board is deliberately not registered, so it never parses. */
        @Test
        @DisplayName("1708 bet board (L18) does not resolve to any registered type")
        void betBoardIsNotRegistered() throws Exception {
            JsonNode body = fixtureBody("bet-board.json");
            assertThat(body.get("cmd").asInt()).isEqualTo(1708);

            assertThatThrownBy(() -> mapper().treeToValue(body, CrashMessage.class))
                    .isInstanceOf(InvalidTypeIdException.class)
                    .hasMessageContaining("1708");
        }
    }

    @Nested
    @DisplayName("synthetic frames")
    class Synthetic {

        @Test
        @DisplayName("absent runner values read 0 and absent flags read not-crashed")
        void absentFields() throws Exception {
            CrashTick tick = (CrashTick) mapper().readValue("{\"cmd\":1709,\"sid\":5}", CrashMessage.class);

            assertThat(tick.multiplierFor(JAKE)).isZero();
            assertThat(tick.multiplierFor(NEYTIRI)).isZero();
            assertThat(tick.crashedFor(JAKE)).isFalse();
        }

        @Test
        @DisplayName("two-decimal values never lose a hundredth to binary rounding")
        void hundredthsAreExact() throws Exception {
            for (int h = 100; h <= 2000; h++) {
                String odd = String.format("%d.%02d", h / 100, h % 100);
                CrashTick tick = (CrashTick) mapper().readValue(
                        "{\"cmd\":1709,\"sid\":5,\"jOdd\":" + odd + ",\"nOdd\":" + odd + "}", CrashMessage.class);
                assertThat(tick.multiplierFor(JAKE)).as("jOdd %s", odd).isEqualTo(h);
                assertThat(tick.multiplierFor(NEYTIRI)).as("nOdd %s", odd).isEqualTo(h);
            }
        }

        @Test
        @DisplayName("Jake crashing alone flips only eid 1")
        void jakeCrashesAlone() throws Exception {
            CrashTick tick = (CrashTick) mapper().readValue(
                    "{\"cmd\":1709,\"sid\":5,\"jOdd\":3.1,\"nOdd\":4.2,\"jFi\":true,\"nFi\":false}", CrashMessage.class);

            assertThat(tick.crashedFor(JAKE)).isTrue();
            assertThat(tick.crashedFor(NEYTIRI)).isFalse();
            assertThat(tick.multiplierFor(JAKE)).isEqualTo(310L);
            assertThat(tick.multiplierFor(NEYTIRI)).isEqualTo(420L);
        }

        @Test
        @DisplayName("cash-out ack winnings round the gross payout (91000.4 → 91000, 91000.5 → 91001)")
        void winningsRound() throws Exception {
            CrashCashoutAck down = (CrashCashoutAck) mapper().readValue(
                    "{\"cmd\":1703,\"eid\":2,\"b\":50000,\"wm\":91000.4,\"odd\":1.82,\"aid\":1}", CrashMessage.class);
            CrashCashoutAck up = (CrashCashoutAck) mapper().readValue(
                    "{\"cmd\":1703,\"eid\":2,\"b\":50000,\"wm\":91000.5,\"odd\":1.82,\"aid\":1}", CrashMessage.class);

            assertThat(down.winningsFor("x")).isEqualTo(91_000L);
            assertThat(up.winningsFor("x")).isEqualTo(91_001L);
        }
    }

    @Test
    @DisplayName("registers exactly the seven AD-4 cmds at offset 1700, one class each, and never 1708 / 1716")
    void registrations() {
        NamedType[] registrations = new Win79CrashMessageTypes().getTypeRegistrations(AVIATOR);

        assertThat(Arrays.stream(registrations).map(NamedType::getName))
                .containsExactly("1700", "1702", "1703", "1705", "1706", "1707", "1709")
                .doesNotContain("1708", "1716");
        assertThat(Arrays.stream(registrations).map(NamedType::getType))
                .containsExactly(Win79CrashSubscribeResponse.class, Win79CrashBetAck.class,
                        Win79CrashCashoutAck.class, Win79CrashRoundStart.class,
                        Win79CrashBettingClosed.class, Win79CrashRoundEnd.class, Win79CrashTick.class)
                .doesNotHaveDuplicates();
        assertThat(new Win79CrashMessageTypes().cmds(AVIATOR))
                .containsExactly(1700, 1702, 1703, 1705, 1706, 1707, 1709);
    }

    @Test
    @DisplayName("119 flies two runners; the contract default is one")
    void runnerCount() {
        assertThat(new Win79CrashMessageTypes().runnerCount()).isEqualTo(2);

        assertThat(new SingleRunnerBrand().runnerCount()).isEqualTo(1);
    }

    /** A brand that does not override {@code runnerCount()} — exercises the contract default. */
    private static final class SingleRunnerBrand implements CrashMessageTypes {

        private final Win79CrashMessageTypes delegate = new Win79CrashMessageTypes();

        @Override
        public Class<? extends CrashSubscribeResponse> subscribeResponseType() {
            return delegate.subscribeResponseType();
        }

        @Override
        public Class<? extends CrashRoundStart> roundStartType() {
            return delegate.roundStartType();
        }

        @Override
        public Class<? extends CrashBettingClosed> bettingClosedType() {
            return delegate.bettingClosedType();
        }

        @Override
        public Class<? extends CrashBetAck> betAckType() {
            return delegate.betAckType();
        }

        @Override
        public Class<? extends CrashTick> tickType() {
            return delegate.tickType();
        }

        @Override
        public Class<? extends CrashCashoutAck> cashoutAckType() {
            return delegate.cashoutAckType();
        }

        @Override
        public Class<? extends CrashRoundEnd> roundEndType() {
            return delegate.roundEndType();
        }

        @Override
        public com.vingame.bot.domain.bot.message.request.CrashRequest newRequest(
                String zoneName, String pluginName, int offset) {
            return delegate.newRequest(zoneName, pluginName, offset);
        }
    }
}
