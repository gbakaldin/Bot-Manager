package com.vingame.bot.domain.bot.message.taixiu;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Winnings / bet-amount extraction for {@link TaiXiuEndGameMessage} across both
 * products that share this class:
 * <ul>
 *   <li><b>P_116 {@code taixiuPlugin}</b> (cmd 1004) — frames carry {@code gR} and
 *       {@code G} with the invariant {@code GX = gR + G}.</li>
 *   <li><b>P_114 {@code taixiuJackpotPlugin}</b> (cmd 1104) — frames omit {@code G}
 *       and {@code gR} entirely, carrying only {@code gB} and {@code GX}.</li>
 * </ul>
 * Regression for the P_114 zero-winnings bug: {@code winningsFor} read the {@code G}
 * field, which Jackson defaulted to 0 on the jackpot frames, so every P_114 win
 * reported 0. The fix computes {@code GX − gR}, which equals {@code G} for P_116
 * (by the invariant) and {@code GX} for P_114.
 */
class TaiXiuEndGameMessageTest {

    // ---- P_116 taixiuPlugin: GX = gR + G, so GX - gR == G (unchanged behaviour) ----

    @Test
    void p116_partialRefund_winningsEqualsG_betAmountIsEffectiveStake() {
        // gB=500k, gR=200k, G=120k, GX=320k (= gR + G).
        TaiXiuEndGameMessage msg = new TaiXiuEndGameMessage(
                1004, 5, 3, 4, 500_000, 200_000, 120_000, 320_000,
                0, 0, 0, 0, false, 0, 0);

        assertThat(msg.winningsFor("bot")).isEqualTo(120_000); // GX - gR = 320k - 200k = G
        assertThat(msg.betAmountFor("bot")).isEqualTo(300_000); // gB - gR
        assertThat(msg.betCountFor("bot")).isEqualTo(1);
    }

    @Test
    void p116_fullRefund_winningsZero_betAmountZero() {
        // gB=500k, gR=500k, G=0, GX=500k (captured fully-refunded round).
        TaiXiuEndGameMessage msg = new TaiXiuEndGameMessage(
                1004, 5, 3, 4, 500_000, 500_000, 0, 500_000,
                0, 0, 0, 0, false, 0, 0);

        assertThat(msg.winningsFor("bot")).isEqualTo(0); // GX - gR = 0
        assertThat(msg.betAmountFor("bot")).isEqualTo(0); // gB - gR = 0
        assertThat(msg.betCountFor("bot")).isEqualTo(0);
    }

    // ---- P_114 taixiuJackpotPlugin: real frames, G and gR absent ----

    @Test
    void p114_win_winningsIsGX_whenGandGRabsent() {
        // Real captured RIK 1104 frame carries only gB + GX (no G, no gR). On the wire
        // those absent fields deserialize to 0, exactly as constructed here.
        // gB=56320000, GX=91443200, gR=0, G=0.
        TaiXiuEndGameMessage msg = new TaiXiuEndGameMessage(
                1104, 4, 6, 3, 56_320_000, 0, 0, 91_443_200,
                0, 0, 0, 0, false, 0, 0);

        assertThat(msg.getG()).isZero();   // the old code read this -> returned 0 winnings (the bug)
        assertThat(msg.getGR()).isZero();
        // Winnings = GX - gR = GX - 0 = GX (the gross return on the win).
        assertThat(msg.winningsFor("bot")).isEqualTo(91_443_200);
        // No refund concept on P_114, so effective wagered is the full bet.
        assertThat(msg.betAmountFor("bot")).isEqualTo(56_320_000);
        assertThat(msg.betCountFor("bot")).isEqualTo(1);
    }

    @Test
    void p114_loss_winningsZero_whenGXzero() {
        // Real captured RIK 1104 losing frame: gB=7040000, GX=0 (G/gR absent -> 0).
        TaiXiuEndGameMessage msg = new TaiXiuEndGameMessage(
                1104, 4, 6, 3, 7_040_000, 0, 0, 0,
                0, 0, 0, 0, false, 0, 0);

        assertThat(msg.winningsFor("bot")).isEqualTo(0);
        assertThat(msg.betAmountFor("bot")).isEqualTo(7_040_000);
        assertThat(msg.betCountFor("bot")).isEqualTo(1);
    }
}
