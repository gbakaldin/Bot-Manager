package com.vingame.bot.domain.bot.message.g4.win79.cashout;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.vingame.bot.domain.bot.message.cashout.CashoutBetFrame;
import com.vingame.bot.domain.bot.message.cashout.CashoutMessage;
import com.vingame.bot.domain.bot.message.cashout.CashoutSubscribeResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * CASHOUT_BOT Phase 1: the three captured 119 {@code balloonPlugin} frames
 * (real client, 2026-10-05) parse through a mapper configured the way a bot's is —
 * {@code FAIL_ON_UNKNOWN_PROPERTIES=false} plus
 * {@link Win79CashoutMessageTypes#getTypeRegistrations(int)} — and every frame is read
 * through the polymorphic base {@link CashoutMessage}, so the cmd → class routing is
 * what is under test, not a hand-picked target class.
 *
 * <p>Fixtures are under {@code messages/win79/cashout/}. {@code progress.json} and
 * {@code burst.json} are the captured bodies verbatim. In {@code subscribe.json} the
 * {@code jackpots} array is trimmed to what the capture actually shows — the capture
 * itself elides it with {@code ...} — and is not read by anything.
 */
@DisplayName("Win79CashoutMessageTypes — captured 119 Balloon frames")
class Win79CashoutMessageTypesTest {

    private static final int BALLOON = 1500;
    private static final int SOCCER = 2500;

    private static ObjectMapper mapperFor(int offset) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerSubtypes(new Win79CashoutMessageTypes().getTypeRegistrations(offset));
        return mapper;
    }

    private static CashoutMessage readFixture(String name) throws Exception {
        ObjectMapper mapper = mapperFor(BALLOON);
        String resource = "/messages/win79/cashout/" + name;
        try (var in = Win79CashoutMessageTypesTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("fixture %s", resource).isNotNull();
            JsonNode root = mapper.readTree(in.readAllBytes());
            assertThat(root.isArray()).as("array-framed [type,{...}]").isTrue();
            assertThat(root.get(0).asInt()).as("ACTION_RESPONSE category").isEqualTo(5);
            return mapper.treeToValue(root.get(1), CashoutMessage.class);
        }
    }

    private static CashoutMessage readBody(int offset, String json) throws Exception {
        return mapperFor(offset).readValue(json, CashoutMessage.class);
    }

    @Nested
    @DisplayName("captured frames")
    class Captured {

        @Test
        @DisplayName("1500 subscribe → allowedBets is the 7 server values")
        void subscribe() throws Exception {
            CashoutMessage parsed = readFixture("subscribe.json");

            assertThat(parsed).isInstanceOf(Win79CashoutSubscribeResponse.class);
            CashoutSubscribeResponse sub = (CashoutSubscribeResponse) parsed;
            assertThat(sub.getCmd()).isEqualTo(1500);
            assertThat(sub.allowedBets())
                    .containsExactly(1000L, 10_000L, 100_000L, 500_000L, 1_000_000L, 5_000_000L, 10_000_000L);
        }

        @Test
        @DisplayName("1501 progress → not final, multiplier ≈ 2.609, sid 1801744, stake 100000")
        void progress() throws Exception {
            CashoutMessage parsed = readFixture("progress.json");

            assertThat(parsed).isInstanceOf(Win79CashoutProgressFrame.class);
            CashoutBetFrame frame = (CashoutBetFrame) parsed;
            assertThat(frame.getCmd()).isEqualTo(1501);
            assertThat(frame.isFinal()).isFalse();
            assertThat(frame.isBurst()).isFalse();
            assertThat(frame.multiplier()).isCloseTo(2.609, within(0.001));
            assertThat(frame.cashoutValue()).isCloseTo(260_908.608, within(0.001));
            assertThat(frame.sid()).isEqualTo(1_801_744L);
            assertThat(frame.stake()).hasValue(100_000L);
            assertThat(frame.winningsFor("anyone"))
                    .as("a live bet has won nothing yet, whatever crd says")
                    .isZero();
            assertThat(frame.unmapped())
                    .as("every captured key is modelled")
                    .isEmpty();
        }

        @Test
        @DisplayName("1502 burst → final, burst, no winnings, no stake")
        void burst() throws Exception {
            CashoutMessage parsed = readFixture("burst.json");

            assertThat(parsed).isInstanceOf(Win79CashoutResultFrame.class);
            CashoutBetFrame frame = (CashoutBetFrame) parsed;
            assertThat(frame.getCmd()).isEqualTo(1502);
            assertThat(frame.isFinal()).isTrue();
            assertThat(frame.isBurst()).isTrue();
            assertThat(frame.winningsFor("anyone")).isZero();
            assertThat(frame.stake()).isEmpty();
            assertThat(frame.sid()).isEqualTo(1_801_742L);
            assertThat(((Win79CashoutFrame) frame).burstState()).isEqualTo(-1);
            assertThat(frame.unmapped()).isEmpty();
        }
    }

    @Nested
    @DisplayName("synthetic frames — the AD-5 outcome rule")
    class Synthetic {

        @Test
        @DisplayName("X501 {iF:true, crd:0.0} is a burst — the legacy burst, on the progress cmd")
        void burstOnProgressCmd() throws Exception {
            CashoutMessage parsed = readBody(BALLOON,
                    "{\"cmd\":1501,\"b\":100000,\"odds\":1.37,\"crd\":0.0,\"blS\":0,\"iF\":true,\"sid\":42}");

            assertThat(parsed).isInstanceOf(Win79CashoutProgressFrame.class);
            CashoutBetFrame frame = (CashoutBetFrame) parsed;
            assertThat(frame.isBurst()).isTrue();
            assertThat(frame.winningsFor("anyone")).isZero();
        }

        @Test
        @DisplayName("X502 {iF:true, blS:0, crd:250000.4} is a win of round(crd) = 250000")
        void winOnResultCmd() throws Exception {
            CashoutMessage parsed = readBody(BALLOON,
                    "{\"cmd\":1502,\"odds\":2.5,\"crd\":250000.4,\"blS\":0,\"iF\":true,\"sid\":43}");

            assertThat(parsed).isInstanceOf(Win79CashoutResultFrame.class);
            CashoutBetFrame frame = (CashoutBetFrame) parsed;
            assertThat(frame.isFinal()).isTrue();
            assertThat(frame.isBurst()).isFalse();
            assertThat(frame.winningsFor("anyone")).isEqualTo(250_000L);
        }

        @Test
        @DisplayName("an uncaptured key lands in unmapped() instead of being dropped (V-8)")
        void unknownKeysAreKept() throws Exception {
            CashoutBetFrame frame = (CashoutBetFrame) readBody(BALLOON,
                    "{\"cmd\":1502,\"odds\":2.0,\"crd\":2000.0,\"blS\":1,\"iF\":true,\"sid\":44,\"newKey\":7}");

            assertThat(frame.unmapped()).containsEntry("newKey", 7);
        }
    }

    @Test
    @DisplayName("offset 2500 (Soccer) registers 2500/2501/2502 and routes them the same way")
    void soccerOffset() throws Exception {
        NamedType[] registrations = new Win79CashoutMessageTypes().getTypeRegistrations(SOCCER);

        assertThat(Arrays.stream(registrations).map(NamedType::getName))
                .containsExactly("2500", "2501", "2502");
        assertThat(Arrays.stream(registrations).map(NamedType::getType))
                .containsExactly(Win79CashoutSubscribeResponse.class,
                        Win79CashoutProgressFrame.class, Win79CashoutResultFrame.class);

        assertThat(readBody(SOCCER, "{\"cmd\":2501,\"b\":1000,\"odds\":1.1,\"crd\":1100.0,\"blS\":0,\"iF\":false,\"sid\":9}"))
                .isInstanceOf(Win79CashoutProgressFrame.class);
        assertThat(readBody(SOCCER, "{\"cmd\":2502,\"odds\":4.0,\"crd\":0.0,\"blS\":-1,\"iF\":true,\"sid\":9}"))
                .isInstanceOf(Win79CashoutResultFrame.class);
    }

    @Test
    @DisplayName("offset 1500 (Balloon) registers 1500/1501/1502")
    void balloonOffset() {
        assertThat(Arrays.stream(new Win79CashoutMessageTypes().getTypeRegistrations(BALLOON)).map(NamedType::getName))
                .containsExactly("1500", "1501", "1502");
    }
}
