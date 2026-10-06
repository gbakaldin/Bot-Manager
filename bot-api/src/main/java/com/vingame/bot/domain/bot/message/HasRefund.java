package com.vingame.bot.domain.bot.message;

/**
 * Marker for {@link EndGameMessage} subtypes that carry the part of a bot's stake the
 * server handed back at round end, independently of win or loss — Tai Xiu's
 * imbalance refund ({@code gR}) is the one implementation today.
 * <p>
 * Exists so the engine can credit a refund by <em>capability</em> instead of naming a
 * concrete per-brand message class (PLUGIN_HOT_RELOAD_3_4 D-5): {@code TaiXiuGameBot}
 * used to test {@code instanceof TaiXiuEndGameMessage}, which tied engine code to a
 * plugin-side class.
 * <p>
 * Identifier contract as {@link HasBotWinnings}: implementations receive the bot's
 * {@code userName}; a recipient-personalized payload may ignore it. A bot absent from
 * the payload, or a round with nothing refunded, is {@code 0}.
 */
public interface HasRefund {

    /**
     * @return the amount refunded to this bot for the just-completed round; {@code 0}
     *         when nothing was refunded or the bot is absent from the payload.
     */
    long refundFor(String userName);
}
