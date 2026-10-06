package com.vingame.bot.domain.bot.core;

import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.message.EndGameMessage;
import com.vingame.bot.domain.bot.message.HasRefund;
import com.vingame.bot.domain.bot.message.taixiu.TaiXiuEndGameMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLUGIN_HOT_RELOAD_3_4 D-5: {@link TaiXiuGameBot#balanceCreditFor} credits a refund
 * through the {@link HasRefund} capability, not by naming the plugin-side
 * {@link TaiXiuEndGameMessage}. Behaviour is unchanged: a Tai Xiu end message still
 * credits {@code gR + winnings}, and any other end message still credits
 * {@code winnings} alone.
 */
@DisplayName("TaiXiuGameBot refund credit by capability (D-5)")
class TaiXiuGameBotRefundCapabilityTest {

    private static final String USER = "taixiubot1";

    private TaiXiuGameBot bot;

    @BeforeEach
    void setUp() {
        bot = new TaiXiuGameBot();
        bot.setConfiguration(BotConfiguration.builder()
                .credentials(BotCredentials.builder()
                        .username(USER).password("pw").fingerprint("fp").build())
                .environmentId("env-1").botGroupId("group-1").botIndex(1)
                .build());
    }

    @Test
    @DisplayName("a Tai Xiu end message credits its gR refund on top of the winnings")
    void taiXiuEndMessageCreditsRefund() {
        // Partial-refund shape: gB 500k, gR 200k, G 120k, GX = gR + G = 320k.
        TaiXiuEndGameMessage end = new TaiXiuEndGameMessage(
                1004, 1, 2, 3, 500_000L, 200_000L, 120_000L, 320_000L,
                0L, 0L, 0L, 0L, false, 0L, 0L);

        assertThat(end).isInstanceOf(HasRefund.class);
        assertThat(end.refundFor(USER)).isEqualTo(end.getGR());
        assertThat(bot.balanceCreditFor(end, 120_000L)).isEqualTo(200_000L + 120_000L);
    }

    @Test
    @DisplayName("a fully refunded Tai Xiu round credits the whole stake back")
    void fullRefundCreditsStake() {
        TaiXiuEndGameMessage end = new TaiXiuEndGameMessage(
                1004, 1, 6, 6, 500_000L, 500_000L, 0L, 500_000L,
                0L, 0L, 0L, 0L, false, 0L, 0L);

        assertThat(bot.balanceCreditFor(end, 0L)).isEqualTo(500_000L);
    }

    @Test
    @DisplayName("an end message without the capability credits the winnings alone")
    void otherEndMessageCreditsNoRefund() {
        EndGameMessage plain = new EndGameMessage(3006) {
            @Override
            public long getSessionId() {
                return 42L;
            }
        };

        assertThat(bot.balanceCreditFor(plain, 7_000L)).isEqualTo(7_000L);
        assertThat(bot.balanceCreditFor(plain, 0L)).isZero();
    }

    @Test
    @DisplayName("the credit keys on the capability, and passes the bot's own userName")
    void anyHasRefundIsCredited() {
        AtomicReference<String> asked = new AtomicReference<>();
        EndGameMessage refunding = new RefundingEnd(asked, 1_500L);

        assertThat(bot.balanceCreditFor(refunding, 500L)).isEqualTo(2_000L);
        assertThat(asked.get()).isEqualTo(USER);
    }

    /** A non-Tai-Xiu end message that carries a refund — the engine must not care which. */
    private static final class RefundingEnd extends EndGameMessage implements HasRefund {
        private final AtomicReference<String> asked;
        private final long refund;

        RefundingEnd(AtomicReference<String> asked, long refund) {
            super(3006);
            this.asked = asked;
            this.refund = refund;
        }

        @Override
        public long getSessionId() {
            return 0L;
        }

        @Override
        public long refundFor(String userName) {
            asked.set(userName);
            return refund;
        }
    }
}
