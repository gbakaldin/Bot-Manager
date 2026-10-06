package com.vingame.bot.domain.bot.message.request;

import com.vingame.bot.domain.game.model.Game;

/**
 * Optional capability of a {@code GameMessageTypes} provider: build the outbound
 * {@link GameRequest} for <b>one game</b> (RIK_114_BETTING_MINI AD-20, taken verbatim
 * from RIK_114_ZICZAC AD-9).
 *
 * <h2>Why it is a separate interface and not a method on {@code GameMessageTypes}</h2>
 *
 * {@code GameMessageTypes} lives in <b>bot-api</b> while {@link GameRequest} and every
 * concrete request/bet body live in <b>bot-messages</b>, and bot-messages &rarr; bot-api
 * is the only legal module direction. A {@code default GameRequest requestFor(...)} on
 * the bot-api interface would put a bot-messages return type on a bot-api type, which
 * does not compile. Declaring the capability here and testing for it with
 * {@code instanceof} keeps the dependency pointing the right way.
 *
 * <h2>Why {@code instanceof} is the right shape anyway</h2>
 *
 * It is the pattern the inbound EndGame markers ({@code HasBotWinnings},
 * {@code HasBetTotals}, {@code HasJackpotPool}) already establish: a provider opts in by
 * implementing the interface, and every provider that does not is untouched — the
 * caller's fallback is the pre-existing {@link Request}, bit for bit. Today exactly two
 * providers implement this: {@code RikGameMessageTypes} (the registered P_114 bean,
 * which allowlists {@code stockPlugin}) and {@code RikZicZacGameMessageTypes} (not a
 * bean — reached only through {@code forGame}, so it needs no allowlist).
 * {@code GameRequestFactoryCapabilityTest} pins that set, deliberately, so a third
 * implementor is a conscious widening rather than an accident.
 *
 * <h2>It is a per-GAME decision, not a per-product one</h2>
 *
 * The provider this hangs on has already been resolved per game by
 * {@code GameMessageTypes.forGame(Game)} (RIK_114_ZICZAC AD-3), and the {@link Game}
 * argument lets an implementation re-apply the game dimension <i>below</i> that
 * resolution — which P_114 needs, because {@code stockPlugin} and
 * {@code taixiuMd5Plugin} share one provider instance by design
 * (RIK_114_BETTING_MINI AD-11) and must <b>not</b> share a bet body:
 * {@code taixiuMd5Plugin} settles through the shared {@link Bet} today and
 * {@code stockPlugin} demonstrably does not (Amendment A3). An implementation that
 * dispatches on {@code Game.pluginName} must use an <b>allowlist</b> of names it has a
 * wire capture for, never a denylist (RIK_114_BETTING_MINI AD-21).
 *
 * @see Request the fallback every non-implementing provider keeps
 */
public interface GameRequestFactory {

    /**
     * Build the outbound request helper for one game.
     *
     * @param game     the game being played; implementations may dispatch on
     *                 {@code pluginName}, which <b>may</b> be {@code null}. The
     *                 {@code Game} itself is <b>never</b> {@code null}: the sole
     *                 production call site,
     *                 {@code BettingMiniGameBot.buildRequest}, dereferences
     *                 {@code game.getPluginName()} in the fallback three lines below
     *                 the {@code instanceof}, and its caller
     *                 {@code initializeSubclass} has already read
     *                 {@code game.getOffset()} two statements earlier. So an
     *                 implementation need not be defensive about the {@code Game},
     *                 and one that is buys nothing a caller can rely on.
     *                 <p>
     *                 This paragraph previously <i>mandated</i> null tolerance, which
     *                 was a promise the seam did not keep — the fallback NPEs for the
     *                 six providers that do not implement this interface, so the
     *                 tolerance was unreachable in production.
     *                 {@code RikGameMessageTypes.requestFor} is null-safe anyway and
     *                 {@code RikGameMessageTypesRoutingTest} pins that; read it as
     *                 incidental hardening, not as this interface's contract.
     * @param zoneName the resolved WS zone name (usually {@code MiniGame})
     * @param offset   the game's CMD offset — CMD = CODE + offset
     * @return the request helper the bot's scenario builds its outbound frames from;
     *         never {@code null}
     */
    GameRequest requestFor(Game game, String zoneName, int offset);
}
