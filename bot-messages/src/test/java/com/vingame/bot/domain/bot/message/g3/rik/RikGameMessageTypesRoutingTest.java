package com.vingame.bot.domain.bot.message.g3.rik;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.domain.bot.message.GameMessageTypes;
import com.vingame.bot.domain.bot.message.g2.b52.B52GameMessageTypes;
import com.vingame.bot.domain.bot.message.g2.bom.BomGameMessageTypes;
import com.vingame.bot.domain.bot.message.g3.tip.TipGameMessageTypes;
import com.vingame.bot.domain.bot.message.g4.nohu.NohuGameMessageTypes;
import com.vingame.bot.domain.bot.message.g4.win79.Win79GameMessageTypes;
import com.vingame.bot.domain.bot.message.request.GameRequest;
import com.vingame.bot.domain.bot.message.request.GameRequestFactory;
import com.vingame.bot.domain.bot.message.request.Request;
import com.vingame.bot.domain.bot.message.request.RikStockRequest;
import com.vingame.bot.domain.bot.message.request.ZicZacBet;
import com.vingame.bot.domain.bot.message.request.ZicZacRequest;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GameMessageTypes#forGame(Game)} — the game dimension the
 * {@code (gameType, product)} registry key does not have (AD-3).
 * <p>
 * Two claims, and both matter equally:
 * <ol>
 *   <li>{@code ziczacPlugin} routes to {@link RikZicZacGameMessageTypes}, so its own
 *       winnings field is read at all;</li>
 *   <li><b>everything else routes to exactly the instance the registry handed
 *       back</b> — same object, not merely an equal one — so a game that already runs
 *       is untouched by this feature existing. That is the regression claim AD-2
 *       makes, expressed as code.</li>
 * </ol>
 * The routing is pinned as <i>behaviour</i>, not as a call site. The one production
 * call site is {@code BotFactory}'s BETTING_MINI branch; a second
 * {@code bettingMini(...)} lookup that forgot {@code .forGame(game)} would still pass
 * this test and would silently send ziczac back to the generic provider, i.e. to
 * {@code bot_winnings_total = 0}. Reviewers watch for the second call site.
 * <p>
 * <b>Since Phase 2 this class pins the OTHER dispatch too.</b>
 * {@code RikGameMessageTypes} now keys on {@code pluginName} in two places —
 * {@link GameMessageTypes#forGame(Game)}, which routes ziczac away, and
 * {@link GameRequestFactory#requestFor(Game, String, int)}, which routes stock to its
 * own bet body (AD-21). Two switches on one key in one class is a maintenance hazard;
 * the mitigation is the full matrix below, in one place, naming both — so a reader who
 * changes one and forgets the other fails a test that spells out the pair.
 */
@DisplayName("GameMessageTypes.forGame — the per-game resolution seam")
class RikGameMessageTypesRoutingTest {

    private static Game game(String pluginName) {
        return Game.builder()
                .gameType(GameType.BETTING_MINI)
                .pluginName(pluginName)
                .offset(9000)
                .md5(false)
                .build();
    }

    @Test
    @DisplayName("ziczacPlugin resolves to the ziczac provider, and to the ziczac message classes")
    void ziczacRoutes() {
        RikGameMessageTypes registryProvider = new RikGameMessageTypes();

        GameMessageTypes resolved = registryProvider.forGame(game("ziczacPlugin"));

        assertThat(resolved).isInstanceOf(RikZicZacGameMessageTypes.class);
        // Resolving the provider changes the classes, the Jackson registrations and
        // (in Phase 2) the outbound request together — there is no further wiring.
        assertThat(resolved.endGameType()).isEqualTo(RikZicZacEndGameMessage.class);
        assertThat(resolved.updateBetType()).isEqualTo(RikZicZacUpdateBetMessage.class);
        // AD-4: the other three are the generic 114 classes, reused verbatim.
        assertThat(resolved.subscribeType()).isEqualTo(RikSubscribeMessage.class);
        assertThat(resolved.startGameType()).isEqualTo(RikStartGameMessage.class);
        assertThat(resolved.startGameMd5Type()).isEqualTo(RikStartGameMd5Message.class);
    }

    @Test
    @DisplayName("the match is case-insensitive on the exact name")
    void ziczacRoutesCaseInsensitively() {
        RikGameMessageTypes provider = new RikGameMessageTypes();

        assertThat(provider.forGame(game("ZicZacPlugin"))).isInstanceOf(RikZicZacGameMessageTypes.class);
        assertThat(provider.forGame(game("ziczacplugin"))).isInstanceOf(RikZicZacGameMessageTypes.class);
        assertThat(provider.forGame(game("ZICZACPLUGIN"))).isInstanceOf(RikZicZacGameMessageTypes.class);
    }

    @Test
    @DisplayName("the resolved ziczac provider is a single shared instance")
    void ziczacProviderIsShared() {
        RikGameMessageTypes provider = new RikGameMessageTypes();

        // Stateless and reused — one instance serves every ziczac bot in the JVM.
        assertThat(provider.forGame(game("ziczacPlugin")))
                .isSameAs(provider.forGame(game("ZicZacPlugin")));
    }

    @Test
    @DisplayName("every other 114 game — including a typo'd and a null plugin name — gets the SAME instance back")
    void everythingElseIsUnchanged() {
        RikGameMessageTypes registryProvider = new RikGameMessageTypes();

        // The two shipped, captured 114 games.
        assertThat(registryProvider.forGame(game("stockPlugin"))).isSameAs(registryProvider);
        assertThat(registryProvider.forGame(game("taixiuMd5Plugin"))).isSameAs(registryProvider);
        // An un-captured 114 game: today's behaviour, i.e. no regression.
        assertThat(registryProvider.forGame(game("someOtherPlugin"))).isSameAs(registryProvider);
        // A typo cannot mis-route SILENTLY here — the same pluginName string is what
        // Request.subscribe() puts in the outbound frame's plugin slot, so a
        // misspelled plugin never receives a round at all. That failure is loud and
        // is not this method's to catch.
        assertThat(registryProvider.forGame(game("zicza cPlugin"))).isSameAs(registryProvider);
        assertThat(registryProvider.forGame(game("ziczac"))).isSameAs(registryProvider);
        // A null plugin name must not NPE.
        assertThat(registryProvider.forGame(game(null))).isSameAs(registryProvider);
        assertThat(registryProvider.forGame(null)).isSameAs(registryProvider);
    }

    /**
     * The full {@code (pluginName) -> (provider, request)} matrix in one place (AD-21).
     * <p>
     * Read the rows as a single claim: the only 114 game that gets the {@code v} +
     * {@code iAc} body is the one with a wire capture proving it needs it, and every
     * other game — including the ones nobody has captured yet — keeps the shared
     * {@link Request} bit for bit. That is an <b>allowlist</b>, and inverting it into a
     * denylist ("anything that is not txmd5") would hand the stock body to the next 114
     * game someone enables, which OI-2 forbids.
     */
    @Test
    @DisplayName("the full routing matrix: stockPlugin gets RikStockRequest, ziczacPlugin gets ZicZacRequest; everything else keeps Request")
    void requestRoutingMatrix() {
        RikGameMessageTypes registryProvider = new RikGameMessageTypes();

        // stockPlugin — the one row this phase exists for. 156 settled rounds, 0
        // server-confirmed bets through the shared Bet (Amendment A3).
        assertThat(resolvedRequest(registryProvider, "stockPlugin"))
                .isInstanceOf(RikStockRequest.class);
        // ...case-insensitively, the same rule forGame uses.
        assertThat(resolvedRequest(registryProvider, "StockPlugin"))
                .isInstanceOf(RikStockRequest.class);
        assertThat(resolvedRequest(registryProvider, "STOCKPLUGIN"))
                .isInstanceOf(RikStockRequest.class);

        // ziczacPlugin — routed AWAY by forGame before requestFor is consulted, and
        // since RIK_114_ZICZAC Phase 2 given its own body by its own provider. Neither
        // RikStockRequest nor Request: the third shape on this product.
        assertThat(resolvedRequest(registryProvider, "ziczacPlugin"))
                .isExactlyInstanceOf(ZicZacRequest.class);

        // taixiuMd5Plugin — THE regression that matters. It shares this provider
        // instance with stock (AD-11) and settles today through the shared Bet: 32
        // confirmed bets in the same window stock placed none. If this row ever reads
        // RikStockRequest, the allowlist has been inverted and the control group goes
        // to zero (V-14).
        assertThat(resolvedRequest(registryProvider, "taixiuMd5Plugin"))
                .isExactlyInstanceOf(Request.class);

        // An un-captured 114 game (offsets 14000-18000 were live during both captures)
        // and the degenerate names: today's behaviour, i.e. no regression.
        assertThat(resolvedRequest(registryProvider, "someOtherPlugin"))
                .isExactlyInstanceOf(Request.class);
        assertThat(resolvedRequest(registryProvider, "stock"))
                .isExactlyInstanceOf(Request.class);
        assertThat(resolvedRequest(registryProvider, "stockPlugin "))
                .isExactlyInstanceOf(Request.class);
        assertThat(resolvedRequest(registryProvider, ""))
                .isExactlyInstanceOf(Request.class);
        assertThat(resolvedRequest(registryProvider, null))
                .isExactlyInstanceOf(Request.class);

        // A null Game does not NPE here. Read this as incidental hardening, NOT as a
        // contract: GameRequestFactory used to mandate null tolerance and no longer
        // does, because buildRequest's own fallback dereferences game.getPluginName()
        // (and initializeSubclass reads game.getOffset() before that), so the tolerance
        // is unreachable in production. Pinned because it is free and because the
        // capability should still not be the thing that throws.
        assertThat(registryProvider.requestFor(null, "MiniGame", 10000))
                .isExactlyInstanceOf(Request.class);
    }

    /**
     * ziczac's row — <b>flipped deliberately</b> by {@code RIK_114_ZICZAC} Phase 2
     * (Amendment A1 item 3). Until then this test asserted the opposite
     * (RIK_114_BETTING_MINI AD-24): {@code forGame} routed ziczac to
     * {@code RikZicZacGameMessageTypes}, which did <b>not</b> implement
     * {@link GameRequestFactory}, so a ziczac bot fell through to the shared
     * {@link Request} and its bets — accepted in-round, discarded at settlement —
     * read {@code confirmed staked: 0} on every EndGame.
     * <p>
     * Now the resolved provider <i>is</i> a factory and hands back a
     * {@link ZicZacRequest}, whose body is
     * {@code {"cmd":12002,"b":…,"c":1,"sid":…,"aid":1}} — a per-ball stake and a ball
     * count, no {@code eid} — and which has no commit. The plugin name in the request
     * is the game's own spelling, so the outbound envelope's plugin slot carries what
     * the operator configured, exactly as the shared {@link Request} would.
     */
    @Test
    @DisplayName("ziczacPlugin DOES implement the request capability (Phase 2): a ZicZacRequest, ZicZacBet, no commit")
    void ziczacResolvesToItsOwnRequest() {
        RikGameMessageTypes registryProvider = new RikGameMessageTypes();

        GameMessageTypes resolved = registryProvider.forGame(game("ziczacPlugin"));

        assertThat(resolved).isInstanceOf(RikZicZacGameMessageTypes.class);
        assertThat(resolved).isInstanceOf(GameRequestFactory.class);

        GameRequest request = resolvedRequest(registryProvider, "ziczacPlugin");
        assertThat(request).isExactlyInstanceOf(ZicZacRequest.class);
        assertThat(request.bet(60_000L, 0, 1995084L)).isInstanceOf(ZicZacBet.class);
        assertThat(request.commit(1995084L)).isEmpty();
        // Case-insensitively, like every other row — and the spelling survives.
        assertThat(resolvedRequest(registryProvider, "ZicZacPlugin")).isExactlyInstanceOf(ZicZacRequest.class);
        assertThat(resolvedRequest(registryProvider, "ZicZacPlugin").subscribe().serialize(new ObjectMapper()))
                .contains("\"ZicZacPlugin\"");
    }

    /**
     * The two dispatch points agree about which games this provider still serves.
     * {@code forGame} sends exactly one name away (ziczac) and {@code requestFor}
     * specialises exactly one name (stock); the sets are disjoint, so no game is both
     * routed away and given a body here.
     */
    @Test
    @DisplayName("the two pluginName switches are disjoint: ziczac leaves, stock specialises, nothing does both")
    void theTwoDispatchPointsAreDisjoint() {
        RikGameMessageTypes registryProvider = new RikGameMessageTypes();

        // ziczac leaves before requestFor is ever consulted...
        assertThat(registryProvider.forGame(game("ziczacPlugin"))).isNotSameAs(registryProvider);
        // ...and stock stays, which is what makes the second switch necessary at all.
        assertThat(registryProvider.forGame(game("stockPlugin"))).isSameAs(registryProvider);
        assertThat(registryProvider.forGame(game("taixiuMd5Plugin"))).isSameAs(registryProvider);
    }

    /** Resolve the provider per game, then ask it for the request the bot would build. */
    private static GameRequest resolvedRequest(RikGameMessageTypes registryProvider, String pluginName) {
        Game game = game(pluginName);
        GameMessageTypes resolved = registryProvider.forGame(game);
        if (resolved instanceof GameRequestFactory factory) {
            return factory.requestFor(game, "MiniGame", 10000);
        }
        // Exactly what BettingMiniGameBot.buildRequest falls back to.
        return new Request(pluginName, "MiniGame", 10000);
    }

    @Test
    @DisplayName("no other GameMessageTypes implementation overrides forGame — every one returns this")
    void everyOtherProviderKeepsTheDefault() {
        List<GameMessageTypes> others = List.of(
                new BomGameMessageTypes(),
                new TipGameMessageTypes(),
                new NohuGameMessageTypes(),
                new Win79GameMessageTypes(),
                new B52GameMessageTypes());

        for (GameMessageTypes provider : others) {
            assertThat(provider.forGame(game("ziczacPlugin")))
                    .as("%s must not specialise: ziczacPlugin is a P_114 plugin name and "
                            + "no other product may claim it", provider.getClass().getSimpleName())
                    .isSameAs(provider);
            assertThat(provider.forGame(game("anything")))
                    .as("%s must keep the default forGame", provider.getClass().getSimpleName())
                    .isSameAs(provider);
            assertThat(provider.forGame(null))
                    .as("%s must tolerate a null game", provider.getClass().getSimpleName())
                    .isSameAs(provider);
        }
    }

    @Test
    @DisplayName("the ziczac provider is reachable ONLY through forGame — it carries no registry annotation")
    void ziczacProviderIsNotABean() {
        Class<?> ziczac = RikZicZacGameMessageTypes.class;

        // A second @MessageTypesImpl(BETTING_MINI, "114") bean would fail context
        // refresh on the duplicate key, and a @Component would perturb the registry
        // inventory and the boot line that V-2 pins. Neither is present, and that is
        // the entire reason this game needs no registry change.
        assertThat(ziczac.getAnnotations())
                .as("RikZicZacGameMessageTypes must carry no annotations at all")
                .isEmpty();
    }
}
