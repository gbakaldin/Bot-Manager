package com.vingame.bot.domain.bot.message.g3.rik;

import com.vingame.bot.domain.bot.message.EndGameMessage;
import com.vingame.bot.domain.bot.message.GameMessageTypes;
import com.vingame.bot.domain.bot.message.MessageTypesImpl;
import com.vingame.bot.domain.bot.message.StartGameMd5Message;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.message.SubscribeMessage;
import com.vingame.bot.domain.bot.message.UpdateBetMessage;
import com.vingame.bot.domain.bot.message.request.GameRequest;
import com.vingame.bot.domain.bot.message.request.GameRequestFactory;
import com.vingame.bot.domain.bot.message.request.Request;
import com.vingame.bot.domain.bot.message.request.RikStockRequest;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import org.springframework.stereotype.Component;

/**
 * Message types provider for P_114 / RIK betting-mini games.
 *
 * <h2>What the evidence is</h2>
 *
 * Two live captures of RIK staging, both taken 2026-09-15 and both committed under
 * {@code bot-messages/src/test/resources/captures/}:
 * <ul>
 *   <li><b>{@code stockPlugin}</b> — a two-option stock up/down game at
 *       <b>offset 10000</b>; {@code rik-stockPlugin-13000.jsonl}, 50 frames, 95 s,
 *       3 complete rounds. All four contracted CODEs present: subscribe
 *       {@code 13000}, updateBet {@code 13002}, startGame {@code 13005}, endGame
 *       {@code 13006}. Own stake and own win ride the {@code mbs} array; the
 *       end-of-round crowd rides {@code obs}; the {@code md5} on 13005 is always
 *       {@code "-"}.</li>
 *   <li><b>{@code taixiuMd5Plugin}</b> — a standard two-option Tài/Xỉu game at
 *       <b>offset 4000</b>; {@code rik-taixiuMd5Plugin-7000.jsonl}, 22 frames, 96 s,
 *       2 rounds. Materially different shape: {@code eid} is {@code 1}/{@code 2} not
 *       {@code 0}/{@code 1}, {@code d1}/{@code d2}/{@code d3} are dice not a percent
 *       move, the end-of-round crowd rides {@code bs} (with a real own-stake
 *       {@code b}) and there is no {@code obs}, own winnings arrive as a
 *       <b>top-level {@code wm}</b> and there is no {@code mbs}, subscribe /
 *       startGame / endGame all carry a <b>real 64-hex md5</b>, and it emits
 *       <b>no {@code 3002} at all</b> — so {@link RikUpdateBetMessage} is simply
 *       never constructed for it and the coordinator sees crowd at subscribe and
 *       end-of-round only. Nothing to fix; do not go looking for a missing
 *       handler.</li>
 * </ul>
 * <b>This game is {@code BETTING_MINI}, not {@code GameType.TAI_XIU}</b>, despite the
 * name: 114 runs a Tài/Xỉu-themed game under both game types and they share nothing.
 * This one is CODE+offset and resolves here; {@code taixiuJackpotPlugin} is the
 * fixed-CMD product ({@code 1105}/{@code 1102}/{@code 1104}/{@code 1100}) served by
 * {@code JackpotTaiXiuMessageTypes}. Creating it with the wrong game type subscribes
 * on a CMD the game never answers.
 * <p>
 * <b>No CMD is hardcoded here (AD-2).</b> {@code getTypeRegistrations(offset, md5)}
 * derives all four from the {@code Game.offset} record, so a 114 game at a different
 * offset binds with no code change — which is exactly what the second capture
 * exercises, the same five classes serving offsets 10000 and 4000.
 *
 * <h2>What the captures do NOT prove (AD-11)</h2>
 *
 * This provider claims <b>all</b> of product 114, because {@code @MessageTypesImpl}
 * has no game dimension — one provider per {@code (gameType, product)}, so
 * {@code "114"} is the only key expressible. <b>Two games at two offsets with two
 * different EndGame shapes</b> is meaningfully stronger evidence for that
 * product-wide claim than one was — and the rule does not relax: offsets
 * 14000-18000 were live on the same connection during both captures and use the
 * identical CODE layout, but not one of their payloads was captured.
 * <b>Enabling any other 114 mini game requires its own capture first</b> (OI-2:
 * capture, re-run {@code scripts/capture/infer_schema.py}, confirm the four frames,
 * only then create the {@code Game}). If a new game's shapes differ materially the
 * correct move is <b>not</b> a second provider — impossible under one product key —
 * but widening these classes, which is what the {@code obs}/{@code bs} fallback
 * already started.
 * <p>
 * What makes claiming the whole product tolerable in the meantime: the only accessor
 * whose failure is fatal is {@code EndGameMessage.getSessionId()} and {@code sid} is
 * present on every captured product's EndGame; {@code tFB}/{@code tFD} are universal
 * across Bom/Tip/Nohu/Win79/RIK, and a game lacking them degrades to a zero bet
 * window rather than a crash; every modelled field is optional, unknown fields are
 * ignored, and {@code crowdBets()} returns an empty list rather than throwing and
 * falls back from {@code obs} to {@code bs} (AD-4). The failure mode for an
 * un-captured 114 game is <b>degraded observability, not a crash or a wrong bet</b>.
 *
 * <h2>A third 114 game exists and is NOT served by this class</h2>
 *
 * {@code ziczacPlugin} — Plinko at offset 9000, subscribe {@code 12000}, captured
 * 2026-09-16 as {@code /captures/rik-ziczacPlugin-12000.jsonl} — sits inside the same
 * four-CODE contract but reports its own return on {@code mbs[].r} with no
 * {@code wm} anywhere, so {@link RikEndGameMessage#winningsFor} would read {@code 0}
 * on every one of its rounds. It is therefore served by
 * {@link RikZicZacGameMessageTypes}, reached through {@link #forGame(Game)} rather
 * than through the registry — see that method. The only change to this class for
 * that was the {@code forGame} override itself, which was the requirement: the two
 * games above keep their classes, their fixtures and their tests byte-for-byte.
 *
 * <h2>The outbound bet frame ANSWERED: stock forks, txmd5 does not</h2>
 *
 * The real client bets on {@code stockPlugin} with
 * {@code {"cmd":13002,"v":1000,"sid":…,"aid":1,"eid":0,"iAc":true}} — stake key
 * {@code v}, plus {@code iAc} — while the shared {@code Bet} emits {@code b} and no
 * {@code iAc}, as every other betting-mini product does. That divergence was
 * deliberately not forked on speculation; V-6 settled it on staging and <b>it
 * failed</b>: 156 settled rounds on the stock group with {@code bot_bets_placed_total}
 * flat at {@code 0}, while {@code taixiuMd5Plugin} confirmed 32 bets through the
 * identical {@code Request}/{@code Bet} on the same box in the same window
 * (Amendment A3). So this class implements
 * {@link com.vingame.bot.domain.bot.message.request.GameRequestFactory} and routes
 * {@code stockPlugin} — <b>and only {@code stockPlugin}</b> — to
 * {@link com.vingame.bot.domain.bot.message.request.RikStockRequest}; see
 * {@link #requestFor(Game, String, int)} for why the allowlist has exactly one entry.
 * <p>
 * Since Phase 3 the stock request also emits the <b>per-bet commit</b>
 * {@code {"cmd":13022,"sId":sid}} after every {@code 13002} (AD-27 amended, AD-29) —
 * {@code RikStockRequest.commit(sid)} on the {@code GameRequest.commit} default
 * (AD-28), sent by the bot from the bet stage's {@code onSent} callback. Phase 2's
 * capture showed the {@code v}+{@code iAc} bet accepted and echoed in-round and then
 * discarded at EndGame; the legacy Node stock bot and the browser both send this frame
 * and we never had. {@code taixiuMd5Plugin} does <b>not</b> send it, inherits the empty
 * default through the shared {@code Request}, and settles without it.
 * <p>
 * A rival hypothesis survives and is <b>not</b> eliminated by that evidence (AD-27):
 * the real client also sends a bare {@code {"cmd":13012}} right after subscribing on
 * stock and on ziczac, and sends nothing of the kind on txmd5 — the one 114 game whose
 * bets settle. The bet body was fixed first because it is necessary under either
 * hypothesis (the server reads the stake from a key we were not sending at all); the
 * per-bet {@code 13022} above is Phase 3; the post-subscribe {@code 13012} is
 * <b>Phase 3b</b> (AD-34), which opens only if V-17 still reads zero after {@code 13022}
 * has been on the box, and never ships in the same deploy as {@code 13022}. One
 * variable per deploy is what made A3's verdict readable.
 */
@Component
@MessageTypesImpl(gameType = GameType.BETTING_MINI, products = "114")
public class RikGameMessageTypes implements GameMessageTypes, GameRequestFactory {

    /**
     * The one plugin name on this product whose frames this class does not serve.
     * Matched case-insensitively against {@code Game.pluginName}.
     */
    private static final String ZICZAC_PLUGIN = "ziczacPlugin";

    /**
     * The one plugin name on this product that bets with its own body
     * ({@code v} + {@code iAc}, no {@code b} — AD-22). Matched case-insensitively
     * against {@code Game.pluginName}, exactly as {@link #ZICZAC_PLUGIN} is.
     * <p>
     * This is an <b>allowlist of one</b>, and that is the decision — see
     * {@link #requestFor(Game, String, int)}.
     */
    private static final String STOCK_PLUGIN = "stockPlugin";

    /**
     * Stateless and shared — it holds nothing but the five {@code Class} answers, so
     * one instance serves every ziczac bot in the JVM.
     */
    private static final RikZicZacGameMessageTypes ZICZAC = new RikZicZacGameMessageTypes();

    /**
     * {@inheritDoc}
     * <p>
     * {@code ziczacPlugin} (offset 9000) gets {@link RikZicZacGameMessageTypes};
     * every other 114 game — {@code stockPlugin}, {@code taixiuMd5Plugin}, anything
     * un-captured, and a {@code null} plugin name — gets {@code this}, which is
     * exactly today's behaviour. So enabling ziczac changes nothing for a game that
     * already runs.
     * <p>
     * The comparison is <b>case-insensitive</b> on the exact name. A typo cannot
     * mis-route silently: the same {@code pluginName} string is what
     * {@code Request.subscribe()} puts in the outbound frame's plugin slot, so a
     * misspelled plugin never receives a round at all — the failure is loud and is
     * not this method's to catch. An unrecognised name falling back to the generic
     * provider is the pre-existing behaviour for every un-captured 114 game, i.e. no
     * regression.
     */
    @Override
    public GameMessageTypes forGame(Game game) {
        if (game != null && ZICZAC_PLUGIN.equalsIgnoreCase(game.getPluginName())) {
            return ZICZAC;
        }
        return this;
    }

    /**
     * {@inheritDoc}
     *
     * <h2>An allowlist of exactly {@code stockPlugin} — never a denylist (AD-21)</h2>
     *
     * This is the <b>second</b> dispatch point on {@code pluginName} in this class, and
     * that is deliberate rather than an oversight. {@link #forGame(Game)} routes
     * {@code ziczacPlugin} away to its own provider, but {@code stockPlugin} and
     * {@code taixiuMd5Plugin} <b>share this one provider instance</b> — AD-11, one
     * provider per {@code (gameType, product)} — and they must not share a bet body.
     * So the game dimension has to be re-applied below {@code forGame}, and the
     * {@link Game} argument here is where.
     * <ul>
     *   <li>{@code stockPlugin} &rarr; {@link RikStockRequest}: its stake key is
     *       {@code v} and the frame carries {@code iAc}. It placed <b>zero</b>
     *       server-confirmed bets across 156 settled rounds through the shared
     *       {@link Request}/{@code Bet} (Amendment A3).</li>
     *   <li><b>Everything else</b> — {@code taixiuMd5Plugin}, an un-captured 114 game
     *       at offsets 14000-18000, an unknown name, a {@code null} name and a
     *       {@code null} game — &rarr; {@code new Request(...)}, i.e. today's behaviour
     *       bit for bit. {@code taixiuMd5Plugin} in particular <b>keeps the shared
     *       {@code Bet} because it demonstrably settles with it</b>: 32 confirmed bets
     *       on the same box, in the same window, in which stock placed none. That
     *       control going flat after a deploy means this allowlist was inverted, and it
     *       is the first thing to check (V-14).</li>
     * </ul>
     * A denylist ("anything that is not txmd5") would silently hand the stock body to
     * the next 114 game someone enables, which is exactly what OI-2 forbids: no
     * un-captured 114 game may inherit a frame shape nobody has seen it accept.
     * <p>
     * {@code ziczacPlugin} never reaches this method — {@link #forGame(Game)} has
     * already routed it to {@code RikZicZacGameMessageTypes}, which implements
     * {@link GameRequestFactory} itself (RIK_114_ZICZAC Phase 2) and hands the bot a
     * {@code ZicZacRequest} ({@code {cmd,b,c,sid,aid}}, no commit). So the allowlist
     * here is only ever consulted for the two games that share <i>this</i> provider,
     * and adding a ziczac branch to it would be dead code.
     * <p>
     * Both dispatch points read the same {@code private static final} plugin-name
     * constants and {@code RikGameMessageTypesRoutingTest} asserts the full matrix in
     * one place, so changing one without the other fails a test that names both.
     */
    @Override
    public GameRequest requestFor(Game game, String zoneName, int offset) {
        if (game != null && STOCK_PLUGIN.equalsIgnoreCase(game.getPluginName())) {
            return new RikStockRequest(game.getPluginName(), zoneName, offset);
        }
        return new Request(game != null ? game.getPluginName() : null, zoneName, offset);
    }

    @Override
    public Class<? extends SubscribeMessage> subscribeType() {
        return RikSubscribeMessage.class;
    }

    @Override
    public Class<? extends StartGameMessage> startGameType() {
        return RikStartGameMessage.class;
    }

    /**
     * {@inheritDoc}
     * <p>
     * A real class, unlike Win79's {@code null} (AD-12) — and <b>this is no longer a
     * hedge</b>. {@code taixiuMd5Plugin} (offset 4000) carries a real 64-hex hash on
     * subscribe, startGame <b>and</b> endGame, with the reveal string {@code rs}
     * embedding the dice as <code>{5-4-4}</code>, so a 114 game created with
     * {@code Game.md5 = true} is a captured, supported configuration. Had this
     * returned {@code null} like Win79's, that game would NPE at registration.
     * {@code stockPlugin} is the other case: {@code md5 = false}, its 13005 hash
     * always the literal {@code "-"}.
     */
    @Override
    public Class<? extends StartGameMd5Message> startGameMd5Type() {
        return RikStartGameMd5Message.class;
    }

    @Override
    public Class<? extends UpdateBetMessage> updateBetType() {
        return RikUpdateBetMessage.class;
    }

    @Override
    public Class<? extends EndGameMessage> endGameType() {
        return RikEndGameMessage.class;
    }
}
