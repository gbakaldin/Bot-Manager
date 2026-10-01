# RIK_114_ZICZAC — a third 114 game, and the game dimension the message layer never had

## Goal

Support **`ziczacPlugin`** — RIK/P_114's **Plinko** (Galton board) game at **offset 9000**,
subscribe CMD **12000** — as a first-class bot-manager game: correct own-stake and own-payout
metrics (Phase 1), and a bot that can actually drop balls (Phase 2). The game's frames sit
inside the four-CODE contract, so the existing `RikGameMessageTypes` already *parses* them —
and reports **`bot_winnings_total = 0` while `bot_bet_amount_total` climbs**, because ziczac
puts the own return in a field that does not exist on the two modelled 114 games. Its
outbound bet body is `{cmd, b, c, sid, aid}` — a **per-ball stake plus a ball count**, with no
`eid` at all — which proves what `RIK_114_BETTING_MINI` AD-9/AD-10 only suspected: **the bet
body is per-GAME, not per-product**, and `@MessageTypesImpl(gameType, products)` has no axis
for that. This plan adds that axis as a *resolution parameter* (`GameMessageTypes.forGame`),
uses it for both the message classes and the bet body, and leaves every shipped 114 class
byte-for-byte untouched.

## Amendment A1 (2026-09-18) — state after the server-side restart; what Phase 2 now is

**Read this before anything else in the document.** Findings §7 and Phase 2 were written when
the game was silently frozen server-side; two of their premises are gone.

### What is now known (all measured on Bot-1 staging, 2026-09-17)

1. **The game was frozen server-side, not starved by us.** Subscribe ack at 11:41Z read
   `"sid":1995721,"gS":2,"rmT":-77029518` — the round engine had sat on one session since
   ~14:15Z Sep 16. The user restarted it ~14:00Z Sep 17.
2. **After the restart the feed holds on subscribe alone.** Group `88d46075…` relaunched at
   14:07Z: StartGame/EndGame every 30.0 s, 57+ rounds per bot, **0** watchdog reconnects, no
   re-freeze after 28+ min with our (wrong-body) bets going in. **The `12012 {"iM":false}`
   enter-room hypothesis is dead** — no post-subscribe frame is required. Do not add one.
3. **The same restart made stock settle** (`RIK_114_BETTING_MINI` Amendment A6). Both RIK
   games were wedged the same day; the frame-correlation theory (AD-27 there) was a coincidence
   of two server faults. Treat "game emits rounds" as *not* proof the game is healthy.
4. **Our current ziczac bets are accepted in-round and discarded at settlement** — echoed in
   UpdateBets, then `confirmed staked: 0 | total win: 0` on 38/38 EndGames. Expected: the body
   we send is the shared `Bet` (`{cmd,aid,b,eid,sid}`), which has an `eid` the game does not
   know and lacks the mandatory ball count `c`.
5. **OI-2 (stack vs append) is answered by the capture itself.** The `in 12002` echo grows
   `bs` as `eid` 0,1,2,…,6 across seven `c=1` bets — bets **append as server-indexed slots**;
   `eid` is assigned by the server, which is why the outbound carries none. Close OI-2.
6. **Display names are not a factor.** All three nameless probe bots were named
   (`display-name-check.md`); nothing changed for either game.

### What Phase 2 now is — smaller than written

The seam Phase 2 step 1 asked for (`GameRequestFactory`, AD-9) **already shipped** as
`RIK_114_BETTING_MINI` Phase 2 (AD-20…AD-25), together with the `instanceof` branch in
`BettingMiniGameBot.buildRequest` (step 5). `RikGameMessageTypes.requestFor` allowlists
`stockPlugin` and returns `RikStockRequest`; ziczac reaches its own provider through
`forGame(Game)` first, so **ziczac does not go through that allowlist at all** — it needs its
own `GameRequestFactory` implementation on `RikZicZacGameMessageTypes`. Remaining work:

1. **`ZicZacBet`** in `bot-messages/…/request/` — body exactly
   `{"cmd":<offset+3002>,"b":<stake>,"c":1,"sid":<sid>,"aid":1}`. **No `eid`**, no `v`, no
   `iAc`. Mirror `RikStockBet`'s shape (`Body` subclass, `@Getter(AccessLevel.NONE)` fields).
   Every key here is lowercase, so the `<lower><UPPER>` trap (`RIK_114_BETTING_MINI` AD-30 /
   Amendment B1) does **not** bite — say so in the javadoc so nobody adds `@JsonProperty`
   defensively and nobody removes it from `RikStockBet` by analogy.
2. **`ZicZacRequest implements GameRequest`** — standalone like `RikStockRequest`:
   `subscribe()` identical to `Request.subscribe()` (pin by serialized comparison, as
   `RikStockRequestTest` does), `bet()` → `ZicZacBet`, `commit()` inherits the empty default
   (ziczac has no 3022 — the legacy fleet sends none for it and the capture shows none).
3. **`RikZicZacGameMessageTypes implements GameRequestFactory`**, `requestFor(game)` →
   `new ZicZacRequest(pluginName, zone, offset)`. No allowlist needed: this provider is only
   ever reached for `ziczacPlugin` via `forGame`. Add it to
   `GameRequestFactoryCapabilityTest`'s expected set (it currently asserts
   `RikGameMessageTypes` is the *only* factory — that assertion must widen, deliberately).
4. **`entryId` is discarded** (AD-11): the strategy will keep proposing an option from
   `optionAffinities` (`{1:1}` on the probe game); `ZicZacRequest.bet` ignores it. The
   `RikZicZacUpdateBetMessage` echo's `bs[].eid` is the server's slot index, not ours.
5. **Tests** — `ZicZacBet` key set exactly `{cmd,b,c,sid,aid}` and `c == 1`; `ZicZacRequest`
   not a `Request`; bot-level on the real `botBehaviorScenario()`: ziczac emits `ZicZacBet`
   and **no** commit, stock still emits `RikStockBet` + commit, txmd5 still shared `Bet`.
6. `mvn clean install` green (baseline 2267 / 0 at end of 2026-09-17).

### Verification for Phase 2 (replaces the plan's V-6, whose `grep` cannot work at DEBUG)

Group `88d46075-dc8e-476c-b80d-1d0544b29c5c` (2 bots, `min 60000 / max 120000 / inc 60000`,
ACTIVE, feed alive). Baseline: `bot_bets_placed_total` **0**, `confirmed staked: 0` every round.

- **V-Z1** — after deploy, `bot_bets_placed_total{botGroupId=88d46075…}` climbs at roughly the
  round rate (`HasBetTotals` on `RikZicZacEndGameMessage` sums `mbs[].b`, so it counts
  positions — expect 1–2 per bot per round from up to 5 sends). `confirmed staked:` > 0.
- **V-Z2** — `bot_winnings_total` non-zero and RTP over ≥ 200 rounds in **0.93–0.98**
  (theoretical 0.9563 from the odds table, §2). RTP > 1.0 sustained = we are reading the wrong
  field (AD-5's `p.wm` trap) — stop and re-read §4.
- **V-Z3** — one 90 s TRACE window (`com.vingame.bot` → TRACE, then back to **DEBUG**; the
  `OutputPrinter` child override was cleared 2026-09-17 14:37Z so the parent level now reaches
  it) capturing an outbound `12002` with `b`, `c:1`, no `eid`, its `in 12002` echo, and the
  following `12006` with a non-empty `mbs`.
- **V-Z4** — stock control: `cd77131c…` keeps settling (RTP ~0.976); txmd5 `1134449f…` keeps
  its ~1:1. If either stops, the capability test widening in step 3 broke routing — stop.
- **V-Z5** — the game must **not** re-freeze: subscribe-ack `rmT` positive and `sid` advancing
  after 30 min. If it freezes again *only* once correct-body bets flow, that is a server bug to
  hand to the game team with the frames attached.

### Still open after Phase 2

OI-1 (`mbs[].p`), OI-3 (max `c`), OI-4 (`obs` semantics), OI-5 (jackpot discharge), OI-6
(multi-ball) — unchanged. OI-2 is **closed** (append, above). Also open: ziczac has no legal
bet-denomination ladder on record — the capture shows 60,000 and 1,060,000 both accepted, so
unlike stock it may take arbitrary amounts; confirm from the first settled rounds before
loosening the probe group's grid.

### Phase 2 — implementation record (2026-09-18)

Implemented exactly as "What Phase 2 now is" above; nothing from the superseded `### Phase 2`
section further down was re-created (`GameRequestFactory` and the `buildRequest` `instanceof`
branch were already on disk from `RIK_114_BETTING_MINI` Phase 2 and are untouched). Working
tree only — nothing committed, per the note in Implementation Notes.

**Added (bot-messages, main):**
- `bot-messages/src/main/java/com/vingame/bot/domain/bot/message/request/ZicZacBet.java` —
  body exactly `{cmd, b, c, sid, aid}`, `c` pinned to `1`, no `eid`/`v`/`iAc`. Class-level
  `@Getter` and **no** `@JsonProperty`; the javadoc states why the `<lower><UPPER>` trap does
  not apply (every key is lowercase) and that this is no argument for removing the annotation
  from `RikStockBet` / `RikStockCommit`.
- `.../request/ZicZacRequest.java` — standalone `implements GameRequest`, not a `Request`
  subclass; `subscribe()` byte-identical to `Request.subscribe()`; `bet()` → `ZicZacBet`,
  `entryId` discarded (AD-11); `commit()` inherited empty — no override declared.

**Changed (bot-messages, main):**
- `.../g3/rik/RikZicZacGameMessageTypes.java` — `implements GameRequestFactory`;
  `requestFor(game, zone, offset)` → `new ZicZacRequest(game.getPluginName(), zone, offset)`.
  No allowlist (only reachable via `forGame` for `ziczacPlugin`). The "outbound bet body is
  NOT supplied here yet" javadoc section is replaced. Still no annotations, so
  `ziczacProviderIsNotABean` holds. No other Phase 1 class touched.

**Tests added:** `ZicZacBetTest` (8), `ZicZacRequestTest` (6),
`bot-engine/.../core/BettingMiniGameBotZicZacRequestDispatchTest` (4) — the last on the real
`botBehaviorScenario()`: ziczac emits `ZicZacBet` and no `12022`/`sId` frame ever; stock still
`RikStockBet` + `13022`; txmd5 still the shared `Bet` alone.

**Tests changed (the three AD-24 placeholders, flipped deliberately):**
- `GameRequestFactoryCapabilityTest` — expected set widened from `{RikGameMessageTypes}` to
  `{RikGameMessageTypes, RikZicZacGameMessageTypes}`; `resolvedProvidersAreAlsoCovered`
  replaces `...ExceptRik`; new `ziczacImplementsIt` (+1) keeps the widening non-vacuous.
- `RikGameMessageTypesRoutingTest` — `ziczacKeepsTheSharedRequestForNow` →
  `ziczacResolvesToItsOwnRequest`; ziczac row added to `requestRoutingMatrix`.
- `BettingMiniGameBotRikRequestDispatchTest` — `ziczacBotKeepsTheSharedRequest` →
  `ziczacBotBuildsTheZicZacRequest`.

**Not edited, as instructed:** `MessageTypesCoverageTest`, `MessageTypesRegistryTest`,
`ApplicationContextLoadsTest`, `MessageTypesRegistryStartupLogTest`, `Request`, `Bet`,
`RikStockRequest`, `RikStockBet`, `RikGameMessageTypes` (including `requestFor`). Consequence
worth knowing: `RikGameMessageTypes.requestFor`'s javadoc still says ziczac's provider "does
**not** implement `GameRequestFactory` in this phase (AD-24)" — stale prose only, no behaviour;
left for whoever next touches that file. The `docs/plans/RIK_114_BETTING_MINI.md` AD-24 text is
likewise now historical.

**Build:** `mvn clean install` green — bot-api 138, bot-strategies 126, bot-messages 293,
bot-engine 468, bot-app 1261 = **2286 tests / 0 failures** (baseline 2267; +19 = the tests
listed above, nothing else moved).

## Why a new plan document, not an amendment to `RIK_114_BETTING_MINI.md`

`RIK_114_BETTING_MINI` is shipped, green (three PASS verdicts; bot-messages 205, bot-app 1252,
**2158** total) and its Phase 1 already carries a "read this as a record of what is on disk,
**not** as instructions" header. Appending a third phase to a document whose first two phases
are history makes that boundary harder to hold, and the reader who most needs it is the one
implementing this. Three further reasons:

1. **Scope differs in kind.** That plan's question was "can 114 resolve a provider at all".
   This one changes a *cross-product contract* (`GameMessageTypes` gains `forGame`) and
   changes `BotFactory` and `BettingMiniGameBot` — neither of which that plan touches.
2. **It supersedes an unbuilt decision of that plan.** AD-10's `usesStockBetBody()` is a
   product-wide boolean whose own javadoc says "if a second 114 mini game is ever enabled …
   the flag moves onto the `Game` record". That game is here, and the flag has not shipped
   (RIK Phase 2 is still gated on V-6), so this is a clean supersession rather than a rewrite.
   A one-paragraph pointer is added to the head of `RIK_114_BETTING_MINI.md` so nobody
   implements AD-10 from the old text.
3. **Its verification is different and independently runnable.** Nothing here re-verifies
   stock or txmd5; V-8 only asserts they are unchanged.

## Findings — Current State

### 1. The capture, and what it proves

`/Users/gleb/Downloads/ziczacPlugin-12000-capture.jsonl` — ws-inspector, `rik.stgame.win`,
2026-09-16, zone `MiniGame`, `subscribeCmd 12000`, window `[12000, 12999]`, `buffered 213`,
`exported 73`, `durationSeconds 240`, **8 complete rounds** (2 of them with no bet from us).

| dir | cmd | CODE (cmd − 9000) | on the wire | role |
|---|---|---|---|---|
| out/in | 12000 | 3000 | 1 / 1 | subscribe — outbound body is a bare `{"cmd":12000}`, byte-identical to `Request.subscribe()` |
| out | 12012 | 3012 | 1 | `{"cmd":12012,"iM":false}` — real client only; we do not send it |
| in/out | 12002 | 3002 | 25 / 25 | bet out, bet-state echo in |
| in | 12005 | 3005 | 7 | startGame — `{"cmd":12005,"sid":…,"md5":"-"}` |
| in | 12006 | 3006 | 8 | endGame — `mbs`/`p`/`obs`/`tJpV`/`iJp`/`jps`/`sid` |
| in | 12007 | 3007 | 122 | room feed — `jpv`, `tbc`, `tpBs[]{v,dn}`, optional `bs[]{v,dn}` |
| in | 12017 | 3017 | 1 | periodic state frame (the same cross-game 3017 the stock capture saw) |
| out/in | 12018 | 3018 | 7 / 7 | `{"cmd":12018}` → `{"cmd":12018,"s":false}`, once per round; real client only |
| in | 12019 | 3019 | 8 | room-wide ball results — `tbps[]{r,v,dn,odd}` |

**All four contracted CODEs are present** (3000/3002/3005/3006), so `GameMessageTypes`
(`/Users/gleb/IdeaProjects/Bot/bot-api/src/main/java/com/vingame/bot/domain/bot/message/GameMessageTypes.java:18-21,56-64`)
binds them from `Game.offset = 9000` with no code change. 12007/12012/12017/12018/12019 have
no slot in that contract and are never deserialized.

Round cadence from the timestamps: startGame → endGame **17.01 s** in 7/7 (matches
`tFB:17000`), endGame → next startGame **13.00 s** in 6/6 (matches `tFP:13000`).
`tFD:2000`, `mB:50000000`, `gS:2`, `md5:"-"`, `iab:false`.

**Two properties bound what may be concluded:**

- **The outbound frames in *this* file are shape-deduped, 8 of 25** (the export predates the
  tooling fix in `/Users/gleb/IdeaProjects/Bot/scripts/capture/README.md`, which now keeps every
  outbound frame). **Draw no value-level conclusion from outbound frames here** — the *shape*
  `{cmd,b,c,sid,aid}` is solid, the distribution of `c` is not.
- **This account was the only bettor in every round.** `tpBs[].dn` and `bs[].dn` on 12007 are
  `lalalala` in 100% of samples, i.e. us. So, exactly as in the two 2026-09-15 captures, **no
  field here can be shown to be "mine" rather than "the room's" by coincidence of value** —
  which is the trap `RIK_114_BETTING_MINI` Amendment A1 exists to record, and it is live again
  below (§4).

### 2. The game is Plinko, and the payout model is closed-form and verified

- The subscribe frame carries the bucket table:
  `odds = [100,15,8,5,3,2,1.2,0.3,0,0.3,1.2,2,3,5,8,15,100]` — **17 buckets, symmetric,
  centre pays 0x**, i.e. a **16-row** board.
- `mbs[].r == mbs[].b * mbs[].odd` in **81 of 81** balls, zero mismatches.
- `p.wm == sum(mbs[].r)` in **6 of 6** rounds with a bet.
- 16 rows binomial ⇒ theoretical RTP **0.95635** (4.37% house edge). Measured over the
  capture's 81 balls: `sum(r)/sum(b)` = 46 595 000 / 48 910 000 = **0.9527**.
- Observed `odd` histogram over 81 balls: `{0: 13, 0.3: 26, 1.2: 28, 2: 10, 3: 2, 5: 1,
  15: 1}` — centre-heavy, consistent with the binomial and with the table.
- **`r` is a gross return INCLUDING the stake** (`odd` is a total multiplier, not a profit
  multiplier): a ball at `odd:0.3` returns 30% of its stake — a loss that still reports a
  positive `r`. Same convention as `TaiXiuEndGameMessage.winningsFor` and as both games in
  `RIK_114_BETTING_MINI` AD-15. **Never net the stake off it.**

### 3. The betting mechanic, and what the capture does and does not settle

A bet is `(b, c)`: `b` is the **per-ball stake** (chip total — `1 070 000` is plainly
`1 000 000 + 50 000 + 20 000`) and `c` is the **ball count** from the game's 1/5/10/20
selector. Both readings of what `c` does agree on the only quantity this plan consumes:

> **total staked in the round = `sum(mbs[].b)` = `sum(b × c)` over the round's bets.**

They do **not** agree on what an `mbs` entry is, and the capture contradicts the description
the user obtained from the backend:

- **User's description (backend):** the bet adds `b` to each of the **first `c` ball slots**,
  stacking where slots overlap (1 000 000 at c=1 then 50 000 at c=5 ⇒ slot 0 = 1 050 000,
  slots 1-4 = 50 000).
- **What round `1995084` shows:** the inbound 12002 echo grows `[{eid:0,b:60000}]` →
  `[{eid:0..6, b:60000}]` **one entry per frame**, and the round's `mbs` is **7 entries of
  60 000**. Under the stacking model seven c=1 bets would have accumulated on slot 0 as a
  single 420 000 entry. Under an append model (each bet appends `c` balls at stake `b`) the
  observed shape is exactly right, and so is round `1995088` (15 balls all at 1 070 000 =
  c=5 + c=10, a count that no `max(c)` can produce).

**This plan does not resolve that** (OI-2). It does not need to: `betAmountFor` is
`sum(mbs[].b)` either way, and Phase 2 sends `c = 1`, where the two models coincide exactly.

Other facts about `mbs[]` (element `{p, b, r, odd}`):

- `p` runs **0..49 and repeats within a round** (round `1995086`: 19 entries, 17 distinct;
  `1995089`: 20 entries, 16 distinct). So it is neither a bucket index (buckets are 0..16) nor
  a unique slot index. **Unidentified — do not guess** (OI-1).
- Max entries observed in one round: **20**. Whether that is the cap or just the selector
  maximum is unproven (OI-3).
- `odd` arrives as **mixed integer and double** on the wire (`0`, `2`, `1.2`) — 54 doubles,
  27 integers across the capture.

### 4. Where the own-payout lives, and the `p` trap

Two candidate own-return sources on 12006, numerically identical in 6/6 rounds:

| | evidence that it is **mine** | evidence against |
|---|---|---|
| `sum(mbs[].r)` | `mbs` is the backend's `MAIN_BET_ARRAY` — the constant pair that settled Amendment A1. `r = b × odd` in 81/81 | none |
| `p.wm` | equals `sum(mbs[].r)` in 6/6; absent in both no-bet rounds | **carries `uid`/`u`/`dn`** (`15_6447` / `rv_lala00` / `lalalala`) — a display name is what a *room* announcement needs and what a frame addressed to me does not. 12019's `tbps[]`, which is room-wide, carries `dn` the same way |

Both readings coincide **only because we were the only bettor** — precisely the shape that made
the original AD-5 wrong. See AD-5 below for the disposition.

`p.m` is 1 756 504 033 … 1 766 027 033 and moves round to round — balance-shaped, and **82% of
`Integer.MAX_VALUE`** (the tool flags it). `p.jwm` is `0` in 6/6.

### 5. Jackpot: this is the non-zero meter `RIK_114_BETTING_MINI` OI-6 was waiting for

`tJpV` on 12006 rises monotonically across the capture — 200 200 → 204 400 → 406 800 →
513 800 → 674 300 → 684 300 → 689 300 — and 12007's `jpv` ticks up *within* a round
(200 200 → 200 800 → 201 400 → 202 000) as stake accumulates. `iJp` is `false` in 8/8 and
`p.jwm` is `0` in 6/6, so **no discharge was captured**. Subscribe also carries `tJpV:200200`,
`tFJp:6000`, `tJpv2:0`, `tFJp2:0`, and a `jps` array of 3 single-character strings
(`['I','-','-']`, `['R','-','K']`) whose meaning is unknown.

That is a **running pool meter**, positively evidenced, which is exactly what
`HasJackpotPool` documents
(`/Users/gleb/IdeaProjects/Bot/bot-api/src/main/java/com/vingame/bot/domain/bot/message/HasJackpotPool.java:1-28`).
It is *not* evidence about stock's or txmd5's `tJpV`, which were 0 in every sample.

### 6. Crowd: ziczac has none inside the four-CODE contract

- **Inbound 12002 `bs[] = {eid, b}` is our OWN per-ball state, not a crowd feed.** In round
  `1995084` it grows one entry per bet, each carrying our own 60 000, with no `v` and no `bc`
  anywhere. Today `RikUpdateBetMessage.crowdBets()` maps it to
  `CrowdOption(eid, v=0, ownBet=b, bc=0)` — an all-zero-value crowd on a game that has no
  options at all.
- **EndGame 12006 carries no crowd**: `obs` is `[]` in **8/8 rounds**, including the 6 we bet
  in, and there is no `bs` key at all. The user reports that `obs` does carry payouts (`r`)
  when populated; **nothing in this capture shows a populated `obs`** (OI-4).
- The real room feed is 12007 (`tpBs`/`bs` of `{v, dn}`, `tbc`) and 12019 (`tbps[]{r,v,dn,odd}`
  — a room-wide mirror of `mbs`, identical to ours in this single-bettor capture). Both are
  **outside** the four-CODE contract (CODE 3007 / 3019), exactly as `RIK_114_BETTING_MINI`
  AD-7 disposes of 13007/13018.

### 7. What the shipped code does with these frames **today**

Verified by reading
`/Users/gleb/IdeaProjects/Bot/bot-messages/src/main/java/com/vingame/bot/domain/bot/message/g3/rik/RikEndGameMessage.java`
against the capture:

| accessor | result on ziczac | why |
|---|---|---|
| `getSessionId()` | **correct** | `sid` is present on 12006 |
| subscribe `tFB`/`tFD` | **correct** (17000 / 2000) | `RikSubscribeMessage` ignores `odds`, `jps`, `tFJp`, `htr`, `cH` |
| `betAmountFor` | **correct** | `mbs[].b` binds; `sum(b)` is the total stake (§3) |
| `betCountFor` | ball count, not clicks | same array |
| **`winningsFor`** | **0, always** | `RikMainBetSummary` has no `r`; ziczac has no top-level `wm` and no `mbs[].wm` |
| `crowdBets()` | empty list | `obs:[]` is empty and `bs` is null → `List.of()` |
| `RikUpdateBetMessage.crowdBets()` | all-zero values | `RikBetInfo` reads `v`; ziczac's 12002 carries `b` only |

**So a ziczac `Game` created before Phase 1 ships produces a group that stakes and reports
`bot_winnings_total = 0` — visually identical to the "bets never settle / host not
whitelisted" failure in `CLAUDE.md`.** Do not create one until Phase 1 is deployed.

### 8. The outbound bet body is provably per-game

| game | real client's bet body |
|---|---|
| `stockPlugin` (114) | `{"cmd":13002,"v":1000,"sid":…,"aid":1,"eid":0,"iAc":true}` |
| `ziczacPlugin` (114) | `{"cmd":12002,"b":60000,"c":1,"sid":…,"aid":1}` |
| our `Bet` | `{"cmd":…,"aid":1,"b":…,"eid":…,"sid":…}` (`/Users/gleb/IdeaProjects/Bot/bot-messages/src/main/java/com/vingame/bot/domain/bot/message/request/Bet.java:20-37`) |

Two games, **one product**, three different bodies. `@MessageTypesImpl` has a `gameType` and a
`products[]` and nothing else
(`/Users/gleb/IdeaProjects/Bot/bot-api/src/main/java/com/vingame/bot/domain/bot/message/MessageTypesImpl.java:60-75`),
so the product-wide `usesStockBetBody()` seam proposed by `RIK_114_BETTING_MINI` AD-10 cannot
express this — it would force stock's `v`+`iAc` body onto ziczac. This is the **same missing
axis** `PLUGIN_HOT_RELOAD` Amendment A8 records for versions: neither the strategy key nor the
message-types key carries a dimension beyond `(gameType, product)`.

### 9. Engine-side facts that constrain the design

- `BotFactory:160-169` dispatches on `GameType` and resolves
  `messageTypesRegistry.bettingMini(productKey(env))` — **the only production call site** of
  that lookup (`MessageTypesRegistry.java:135`), and it already holds the `Game`.
- `BettingMiniGameBot.buildRequest(Game)` (`:257-263`) is a `protected` seam; `TaiXiuGameBot`
  already overrides it (`:235`). It is called from `initializeSubclass` (`:159`), after
  `BotFactory` has injected `messageTypes`.
- The scenario reads the concrete classes through the five accessor seams (`:269-292`) and
  registers subtypes via `messageTypeRegistrations()` (`:246`, used at `:906`) — so changing
  *which provider instance* the bot holds changes the classes, the registrations and the
  request together, with no further wiring.
- `GameRequest` lives in **bot-messages**
  (`/Users/gleb/IdeaProjects/Bot/bot-messages/src/main/java/com/vingame/bot/domain/bot/message/request/GameRequest.java`)
  while `GameMessageTypes` lives in **bot-api**. bot-engine → bot-messages → bot-api, so a
  seam on `GameMessageTypes` cannot *return* a `GameRequest`; a capability interface declared
  in bot-messages can.
- `bet()` (`:783-791`) calls `creditBalance(amount)` and `memory.recordBetSent(sid, optionId,
  amount)` with the **strategy's amount**, and `RoundResult.balanceDelta = payout −
  sum(betsByOption)` — so local accounting is exact **iff one engine bet debits exactly what
  the server debits**.
- `Game.getEffectiveOptionAffinities()` **throws** when a BETTING_MINI game has no option
  configuration (`Game.java`, end of file) — a ziczac `Game` must carry a non-empty map.
- `MessageTypesRegistry` fails context refresh on a duplicate `(gameType, product)` key
  (`:254-261`), so a second `@MessageTypesImpl(BETTING_MINI, "114")` bean is impossible.

## Per-aspect readiness / mapping

| Aspect | State | Notes |
|---|---|---|
| Subscribe 12000 → `tFB`/`tFD` | **ready, reuse** | `RikSubscribeMessage` parses it unchanged; `odds`/`jps`/`tFJp`/`cH`/`htr` ignored (AD-4) |
| StartGame 12005 → `sid` | **ready, reuse** | identical to stock's 13005, `md5:"-"`, `Game.md5=false` |
| Registrations at offset 9000 | **ready** | CODE+offset, no code change (`RIK_114_BETTING_MINI` AD-2) |
| EndGame 12006 → `sid` | **ready** | the one load-bearing accessor |
| EndGame → own stake / count | **partial → Phase 1** | correct by accident today; made explicit on a ziczac-specific class |
| EndGame → own winnings | **blocked → Phase 1** | `sum(mbs[].r)`; reads **0** today |
| EndGame → jackpot pool | **ready → Phase 1** | `tJpV` is a positively-evidenced rising meter (§5) → `HasJackpotPool` |
| EndGame → per-user jackpot | **blocked** | `iJp` false 8/8, `jwm` 0 6/6 — no discharge captured (OI-5) |
| Crowd (any frame) | **out of scope** | none inside the contract; 12007/12019 have no slot (AD-8) |
| UpdateBet 12002 | **partial → Phase 1** | own per-ball state, **not** crowd — the `HasCrowdBets` marker must come off |
| Outbound subscribe | **ready** | bare `{"cmd":12000}` already matches |
| Outbound bet | **blocked → Phase 2** | `{cmd,b,c,sid,aid}`; needs the game dimension (AD-3/AD-9) |
| Per-game provider selection | **blocked → Phase 1** | `forGame(Game)` (AD-3) |
| Multi-ball (`c` > 1) | **out of scope** | breaks local stake accounting without engine work (AD-10, OI-6) |
| Registry inventories / coverage tests | **no edit** | no new product; boot line unchanged — that is the point of AD-3 |

## Architecture Decisions

**AD-1 — ziczac is `GameType.BETTING_MINI`. Decided first, and it decides the rest.**
`GameType` in this codebase is a **protocol and dispatch key**, not a taxonomy of mechanics:
it selects the bot class and the registry table at `BotFactory:160-190`, nothing else. Against
that key ziczac is unambiguous — round lifecycle driven by broadcast `sid` with a countdown
(`tFB`/`tFD`/`tFP`), CODE+offset CMDs 3000/3002/3005/3006, bets valid only inside the window.
That is `BettingMiniGameBot`'s scenario exactly. `GameType.SLOT` means the **fixed-CMD
1300/1302, product-neutral, request/response spin** protocol with no rounds, no `sid` and a
one-spin-in-flight gate (`SlotMachineBot:379-424`, `messageTypesRegistry.slot()` takes no
product); ziczac is none of those, and classifying it there would mean making `SlotMessageTypes`
product-keyed and rewriting the slot scenario to be round-driven — a large change that buys
nothing. **Yes, it is mechanically a stake→RNG→multiplier game with nothing to pick**; that is
a statement about the *game*, and the consequences are contained to three places, all of them
config or a single discarded argument (AD-11).

**AD-2 — Everything ziczac-specific is a new class; not one shipped 114 class is edited.**
`RikEndGameMessage`, `RikBetInfo`, `RikMainBetSummary`, `RikGameMessageTypes`' five accessors,
the 9 existing fixtures and the 3 RIK test classes stay byte-for-byte. The three PASS verdicts
and the 2158-test baseline therefore remain valid statements about the same artifacts. This is
also the substantive answer to "a third winnings source": **there is no third branch in
`winningsFor`'s chain** — the reviewer's fragility concern is not deepened, it is sidestepped.
The only shipped-code edits in this plan are three additive seams (AD-3, AD-9) and one
provenance-test generalisation (AD-14).

**AD-3 — The game dimension enters as a *resolution parameter*, not as a registry key:
`default GameMessageTypes forGame(Game game) { return this; }` on `GameMessageTypes`.**
`RikGameMessageTypes` overrides it and returns a game-specialised provider for
`ziczacPlugin`, itself for everything else. `BotFactory:164-165` becomes
`messageTypesRegistry.bettingMini(productKey(env)).forGame(game)` — one line, at the one
production call site, which already holds the `Game`.

Why this and not the alternatives:
- **Widening the registry key to `(gameType, product, game)`** touches all eight providers,
  the registry, `MessageTypesCoverageTest`, `MessageTypesRegistryTest`, the boot line and
  `ApplicationContextLoadsTest`, for one game. It is also the *step-5* decision
  `PLUGIN_HOT_RELOAD` Amendment A8 defers, entangled with the version axis; deciding it here,
  under one game's evidence, would be deciding it badly.
- **A `Game` field (`betBodyShape`, `messageVariant`)** makes a wire-protocol fact
  operator-settable, and a wrong value is silent. `RIK_114_BETTING_MINI` AD-10 rejected the
  same shape for the same reason.
- **A `pluginName`-keyed Spring registry** (`@BetBodyImpl("ziczacPlugin")`) is the natural
  end-state once plugin jars are real, and is over-engineering for two bodies today.
  `forGame` does not block it: the routing is one `switch` inside one provider, which is
  exactly what such a registry would replace.

**A typo in `pluginName` cannot mis-route silently**, because the same string is what
`Request.subscribe()` puts in the outbound frame's plugin slot — a misspelled plugin never
receives a round at all. Match case-insensitively on the exact name and document that. The
fallback for an unrecognised 114 game is today's generic provider, i.e. no regression.

**AD-4 — ziczac reuses Subscribe, StartGame and StartGameMd5 verbatim; only UpdateBet and
EndGame are specialised.** Those two frames are where the semantics actually differ. The
subscribe response is the same field set plus ignored extras (`odds`, `jps`, `tFJp`,
`tJpv2`, `tFJp2`, `htr`, `cH`), and `tFB`/`tFD` already resolve correctly; 12005 is
byte-shaped like 13005. Specialising them would be duplication with a maintenance liability
and no behaviour change.

**AD-5 — `winningsFor()` returns `sum(mbs[].r)`. It does NOT read `p.wm`, and `p` is not
modelled at all.** The two are equal in 6/6 rounds, so this costs nothing today and is the
only one of the two that survives the §4 objection: `mbs` is proven own-scoped by the backend
constant pair (`MAIN_BET_ARRAY` / `OTHER_BET_ARRAY`) that Amendment A1 turned on, while `p`
carries `uid`/`u`/`dn` — the payload of a room announcement, not of a frame addressed to me —
and this capture, having exactly one bettor, **cannot tell the two scopes apart**. Reading
`p.wm` would be the original AD-5 mistake replayed on a different field, one plan later. If a
two-account capture later proves `p` is own-scoped, nothing changes, because the numbers agree.
`r = b × odd` in 81/81 and `odd` is a **total** multiplier, so the sum is a **gross return
including stake** — same convention as both other 114 games; `HasBotWinnings` wants exactly
that, and netting the stake off turns a ~0.96 RTP into ~−0.04. A no-bet round has `mbs: []` →
`0`, and `onEndGame` guards on `w > 0`.

**AD-6 — `betAmountFor()` = `sum(mbs[].b)`; `betCountFor()` = entries with `b > 0`, which are
BALLS.** The stake sum is model-independent (§3). The count is **balls, not clicks and not
positions** — one `c=5` click contributes 5 — which differs from every other product's meaning
of the same metric and must be said in the javadoc. `HasBetTotals` already warns that the
server-confirmed count and the locally-counted sends diverge legitimately; under Phase 2's
`c = 1` they in fact agree.

**AD-7 — the ziczac EndGame implements `HasJackpotPool` (`tJpV`) and **not** `HasJackpot`.**
This closes `RIK_114_BETTING_MINI` OI-6 **for ziczac only**: the meter is non-zero and rising
in 8/8 rounds (§5), which is the positive evidence that OI-6 demanded and that stock/txmd5
still lack. The per-user marker stays off — `iJp` was false in 8/8 and `jwm` 0 in 6/6, so no
discharge was observed and `jackpotFor` would be a guess (OI-5). Wiring the pool is inert
unless an operator sets `Game.jackpotScaleEnabled`, and a `0` pool is "not observed" →
neutral, so the downside is bounded at zero.

**AD-8 — no ziczac class implements `HasCrowdBets`.** The 12002 `bs[] = {eid, b}` is our own
per-ball state (§6) and publishing it as a crowd would feed `BetCoordinator.observeCrowd` an
all-zero-value option distribution for a game **with no options**. 12006 carries no crowd at
all. The room does have a feed — 12007 and 12019 — and both are outside the four-CODE
contract, the same disposition `RIK_114_BETTING_MINI` AD-7 gives 13007/13018. Consequence to
state plainly: **crowd-aware coordination and `optionAffinities` are inert on ziczac**, not
broken.

**AD-9 — The bet body is selected by the resolved provider through a capability interface in
bot-messages, checked with `instanceof` in `buildRequest`. This SUPERSEDES
`RIK_114_BETTING_MINI` AD-10.**

```java
// bot-messages …/message/request/GameRequestFactory.java
public interface GameRequestFactory {
    GameRequest requestFor(Game game, String zoneName, int offset);
}
```
```java
// BettingMiniGameBot:257
protected GameRequest buildRequest(Game game) {
    if (messageTypes instanceof GameRequestFactory factory) {
        return factory.requestFor(game, configuration.getZoneName(), offset);
    }
    return new Request(game.getPluginName(), configuration.getZoneName(), offset);
}
```

An optional capability tested with `instanceof` is the pattern this codebase already uses for
every EndGame variation (`HasBotWinnings`, `HasBetTotals`, `HasJackpotPool`), so it needs no
new idea; it keeps request types out of bot-api; and it leaves the other five providers and
`TaiXiuGameBot`'s override untouched. Because the provider is already `forGame`-resolved
(AD-3), the capability is **per game**, which is what AD-10's product-wide boolean could not
be — its own javadoc anticipated this exact moment. **If `RIK_114_BETTING_MINI` V-6 later
fails, stock's `v`+`iAc` body ships through this same mechanism** (a second branch in
`RikGameMessageTypes.forGame` plus a `StockRequest`), and `usesStockBetBody()` is never
written.

**AD-10 — `c` is always `1`, and is not configurable in this plan.** One engine bet = one
ball at the strategy's amount. This is not a simplification, it is what keeps the engine's
arithmetic true: `creditBalance(amount)` (`BettingMiniGameBot:780`) and
`memory.recordBetSent(sid, option, amount)` both assume the send debits exactly `amount`, and
`RoundResult.balanceDelta = payout − sum(bets)` is what every Martingale variant progresses
on. With `c = 1` the server debits `b × 1 = amount` and all three stay exact; with `c > 1`
every one of them is wrong by a factor of `c` until the engine is taught about it. The
per-round volume knob already exists and is `maxBetsPerRound` — 5 balls is 5 ticks. Multi-ball
is OI-6.

**AD-11 — `eid` is not sent; the strategy's `optionId` is discarded at the request boundary,
and the `Game` carries a degenerate single option.** `ZicZacBet` simply has no `eid` field,
and its javadoc says so. `Game.optionAffinities` must be `{"0": 1}` because
`getEffectiveOptionAffinities()` throws on empty; with one option every picker is degenerate
and `WeightedOptionPicker` has nothing to weigh. `crowdCountSemantic` stays `UNKNOWN` — there
is no crowd count (AD-8).

**AD-12 — Types: `odd` is `double`, `b`/`r`/`tJpV` are `long`.** `odd` arrives as both integer
and double on the wire; a `long` field would **silently truncate 1.2 → 1** (Jackson's
`ACCEPT_FLOAT_AS_INT` is on by default), which would break the `r = b × odd` cross-check and
any future payout arithmetic without any error. Money stays `long` per
`RIK_114_BETTING_MINI` AD-3 — `r` reaches 15 900 000 here and `p.m` reaches 1 766 027 033
(82% of `Integer.MAX_VALUE`) on the same frame.

**AD-13 — `mbs[].p`, `p{}`, `obs`, `jps`, `odds` and every out-of-contract frame are NOT
modelled.** The posture is `RIK_114_BETTING_MINI` AD-6/AD-8 and the review's own argument: an
unmodelled field can never fail deserialization, while a modelled one whose real type turns out
to be a string or a fraction **fails the whole frame**, which on Subscribe means the bot never
reaches `markConnectionAuthenticated()` and the group dies with no obvious cause. `p` (the ball
field) is unidentified (§3); `obs` was empty in 8/8; `odds` is the bucket table and nothing
computes a payout bot-side; `jps` is three one-character strings of unknown meaning. All of it
is preserved in the committed capture, which is where an unknown belongs.

**AD-14 — The capture is committed as evidence and the provenance test is generalised to a
directory listing.** `bot-messages/src/test/resources/captures/rik-ziczacPlugin-12000.jsonl`,
byte-for-byte including `_meta`. `RikFixtureProvenanceTest` currently enumerates fixtures in
two hand-maintained lists (`RikFixtureProvenanceTest.java:48-53`) while its javadoc promises
"every `/messages/rik/*.json`" — adding four fixtures is exactly the drift the reviewer
flagged, so this plan replaces the lists with a **classpath directory listing**, keeping the
prefix mapping rule (`txmd5-*` → txmd5 capture, `ziczac-*` → ziczac capture, else stock) and
failing loudly on a fixture matching no prefix rule.

**AD-15 — The capture is committed unscrubbed, consistent with the two shipped ones, and this
is now a decision on record.** It contains 36 chat lines and ~14 in-room display names, plus
our own capturing account's `uid`/`u`/`dn`. No credential material can be present (the AUTH
frame carries no `cmd` and could never match the window). The review asked for the decision to
be explicit either way; it is **keep**, because the three captures must be treated alike and
scrubbing is a separate, all-three pass whose only cost-free moment is before the branch is
pushed. If the user prefers scrubbing, it is a consistent same-shape substitution in capture
**and** fixture, which keeps the provenance test green.

## Plan

### Phase 1 — the ziczac message layer and the game dimension

One Dev session. Everything is additive; no existing 114 class, fixture or test is edited
except the provenance test's enumeration (AD-14). Ships alone and changes nothing for any
existing group.

1. **Evidence** (AD-14/AD-15), byte-for-byte including `_meta`:
   ```
   cp /Users/gleb/Downloads/ziczacPlugin-12000-capture.jsonl \
      /Users/gleb/IdeaProjects/Bot/bot-messages/src/test/resources/captures/rik-ziczacPlugin-12000.jsonl
   ```
2. **Fixtures** under `/Users/gleb/IdeaProjects/Bot/bot-messages/src/test/resources/messages/rik/`,
   each a verbatim frame body from that capture:
   - `ziczac-subscribe.json` — inbound 12000 (keeps the full `cH`/`htr`/`odds`)
   - `ziczac-startGame.json` — inbound 12005
   - `ziczac-updateBet.json` — inbound 12002, the 7-entry sample (`eid` 0..6, `b:60000`)
   - `ziczac-endGame.json` — inbound 12006 `sid 1995089`: **20 balls of 50 000, `sum(r)` =
     1 405 000 > `sum(b)` = 1 000 000**, so a win is not confusable with the stake
   - `ziczac-endGame-loss.json` — 12006 `sid 1995087`: `sum(b)` = 10 700 000, `sum(r)` =
     6 420 000 — a **net loss with a positive return**, the AD-5 convention made falsifiable
   - `ziczac-endGame-noBet.json` — 12006 `sid 1995085`: `mbs:[]`, **no `p`** — the absence case
3. **Message classes** in
   `/Users/gleb/IdeaProjects/Bot/bot-messages/src/main/java/com/vingame/bot/domain/bot/message/g3/rik/`,
   all `@JsonIgnoreProperties(ignoreUnknown = true)`, `@JsonCreator`/`@JsonProperty`
   constructors, money `long`:
   - `RikZicZacBallResult` — `record (long b, long r, double odd)` (AD-12). Javadoc: one ball;
     `r = b × odd` in 81/81; `odd` is a **total** multiplier from the 17-bucket table, so `r`
     includes the stake; `p` deliberately not modelled and why (AD-13).
   - `RikZicZacUpdateBetMessage extends UpdateBetMessage` — `getGameState() → 0` with the
     `RIK_114_BETTING_MINI` AD-13 reasoning. **Implements no marker**; javadoc states that
     `bs` is our own per-ball state, not a crowd, and that this class exists to *remove* the
     `HasCrowdBets` marker `RikUpdateBetMessage` carries (AD-8). Model `bs` only if Dev wants
     it as documentation — nothing reads it, and AD-13's posture says leave it out.
   - `RikZicZacEndGameMessage extends EndGameMessage implements HasBotWinnings, HasBetTotals,
     HasJackpotPool` — fields `sid`, `mbs` (`List<RikZicZacBallResult>`), `tJpV`, `iJp`.
     `winningsFor` = `sum(mbs[].r)` (AD-5, with the `p` trap written out), `betAmountFor` =
     `sum(mbs[].b)`, `betCountFor` = balls with `b > 0` (AD-6, "balls, not clicks"),
     `jackpotPool()` = `tJpV` (AD-7). **No `HasCrowdBets`, no `HasJackpot`** — both omissions
     pinned by a test.
4. **`RikZicZacGameMessageTypes implements GameMessageTypes`** — **not** a `@Component` and
   **not** annotated `@MessageTypesImpl` (a second `(BETTING_MINI, "114")` key fails context
   refresh, `MessageTypesRegistry.java:254-261`). Subscribe / StartGame / StartGameMd5 return
   the existing `Rik*` classes (AD-4); UpdateBet and EndGame return the two new ones.
5. **The seam** (AD-3): `default GameMessageTypes forGame(Game game) { return this; }` on
   `/Users/gleb/IdeaProjects/Bot/bot-api/src/main/java/com/vingame/bot/domain/bot/message/GameMessageTypes.java`,
   javadoc'd as the game dimension the annotation key lacks, with the `PLUGIN_HOT_RELOAD` A8
   pointer and the "a `pluginName` typo cannot mis-route silently" argument.
   `RikGameMessageTypes` overrides it: case-insensitive `"ziczacPlugin"` → the held
   `RikZicZacGameMessageTypes` instance (a `private static final`, stateless), else `this`.
6. **Wiring**: `BotFactory.java:164-165` →
   `messageTypesRegistry.bettingMini(productKey(env)).forGame(game)`. One line; the SLOT and
   TAI_XIU branches are untouched.
7. **Tests** in `.../message/g3/rik/`:
   - `RikZicZacGameShapeTest` — registers the **ziczac provider via
     `RikGameMessageTypes.forGame(game)`** at **offset 9000, `md5=false`** and deserializes
     all six fixtures through `BettingMiniMessage`: subscribe → `tFB 17000`/`tFD 2000`;
     startGame → `sid`; updateBet → the ziczac class and `getGameState() == 0`; endGame
     (`1995089`) → `winningsFor("anything") == 1_405_000`, `betAmountFor == 1_000_000`,
     `betCountFor == 20`, `jackpotPool() == 684_300`; the loss round (`1995087`) →
     `winningsFor == 6_420_000` **>0 and < `betAmountFor == 10_700_000`**; the no-bet round →
     all three `0` and `jackpotPool() == 204_400`.
   - Marker pins: the ziczac endGame `isNotInstanceOf(HasCrowdBets)` and
     `isNotInstanceOf(HasJackpot)`; the ziczac updateBet `isNotInstanceOf(HasCrowdBets)`
     (AD-8) — each with the reason in a comment, so a later "helpful" addition argues with a
     test.
   - `RikGameMessageTypesRoutingTest` — `forGame(ziczac Game)` returns the ziczac provider;
     `forGame(stockPlugin Game)`, `forGame(taixiuMd5Plugin Game)`, `forGame` of an unknown
     plugin name and `forGame(null-pluginName Game)` all return the **same instance** the
     registry returned; a case variant (`"ZicZacPlugin"`) routes to ziczac; and every **other**
     provider's default `forGame` returns `this`.
   - An arithmetic test over the committed capture: for all 81 balls `r == b × odd`, and for
     all 6 betting rounds `sum(mbs[].r) == p.wm` — the cross-check that makes AD-5's choice of
     source costless, asserted against the evidence rather than a fixture.
   - `RikFixtureProvenanceTest` — replace the two literal lists with a directory listing plus
     the three-way prefix rule (AD-14); add the ziczac capture's intactness check (73 bodies;
     cmds contain 12000/12002/12005/12006/12007/12019).
8. `mvn clean install` green. **No edit to `MessageTypesCoverageTest`,
   `MessageTypesRegistryTest`, `ApplicationContextLoadsTest` or
   `MessageTypesRegistryStartupLogTest`** — no product is added, and the boot line must still
   read `BETTING_MINI 6 products [097, 098, 114, 116, 118, 119]`. If any of them needs a
   change, the design has drifted from AD-3.

### Phase 2 — the ziczac bet body (`b` + `c`), so a bot can actually drop balls

One Dev session. **Depends on Phase 1** for the provider to hang the capability on; nothing in
Phase 1 depends on this.

1. **`GameRequestFactory`** in
   `/Users/gleb/IdeaProjects/Bot/bot-messages/src/main/java/com/vingame/bot/domain/bot/message/request/GameRequestFactory.java`
   (AD-9) — one method, javadoc'd with why it is not on `GameMessageTypes` (module direction)
   and why it is `instanceof`-optional rather than a default method.
2. **`ZicZacBet`** in `.../message/request/` — an `ActionRequestMessage` whose body emits
   **exactly** `{"cmd":<offset+3002>,"b":<stake>,"c":1,"sid":<sid>,"aid":1}`; field order
   irrelevant, **no `eid`**, no `v`, no `iAc`. Javadoc: `b` is per-ball, `c` is the ball count
   and is pinned to 1 (AD-10), the caller's `entryId` is discarded (AD-11).
3. **`ZicZacRequest implements GameRequest`** — standalone, not a subclass of `Request`:
   `subscribe()` returns the same bare `SubscribeToLobbyMessage` and `bet()` returns
   `ZicZacBet`. `Request` is used by five products and is not worth touching to save eight
   lines.
4. **`RikZicZacGameMessageTypes implements GameRequestFactory`** returning a `ZicZacRequest`.
5. **`BettingMiniGameBot.buildRequest`** (`:257-263`) gets the three-line `instanceof` branch
   (AD-9). `TaiXiuGameBot`'s override is untouched.
6. **Tests**: `ZicZacBet` serializes to exactly the five keys and no `eid` (assert the key set,
   not just the values); `Request.bet` still emits today's `Bet` byte-for-byte; a
   `BettingMiniGameBot`-level test that a ziczac-resolved provider yields a `ZicZacRequest` and
   any other provider yields a `Request`; a test that no other provider implements
   `GameRequestFactory`.
7. `mvn clean install` green.

### Not a phase — multi-ball, crowd, jackpot discharge

Deliberately not planned here; each is an Open Item with a stated trigger. None of them is a
prerequisite for Phase 1 or Phase 2, and no step above defers work into them.

## Implementation Notes / Concerns

- **Do not create a ziczac `Game` before Phase 1 is deployed.** The shipped provider parses
  ziczac's frames and reports stake without winnings (§7) — the exact silhouette of the
  "bets never settle / host not whitelisted" bug that `CLAUDE.md` records as having been
  misdiagnosed twice.
- **Phase 1 alone cannot be verified end-to-end on the box, and that is structural, not an
  oversight.** `mbs` and `p` are per-recipient: they are populated **only if our own bot's bet
  landed**. A human betting from a browser proves nothing about our connection's frames. So
  V-1..V-5 (parse, resolve, rounds observed) are Phase 1's ceiling and V-6..V-7 (stake and
  payout non-zero) need Phase 2. Ship both if the goal is working metrics.
- **If only Phase 1 ships and a group is started anyway**, the bots send the generic `Bet`
  body with no `c`. Whether the server reads a missing `c` as 0 balls or 1 is **unknown**; at
  0 balls `mbs` stays empty, every metric reads 0, and the engine's local balance drifts down
  (it debits `creditBalance(amount)` for a stake the server never took) until the bot
  auto-deposits or stops. Harmless on staging, misleading on a dashboard.
- **`mB = 50 000 000` is the per-ball max on this game** — a tenth of stock's 500 000 000. A
  group configured above it will have bets rejected; keep the strategy's max under it.
- **The bet window is 15 s** (`tFB 17000` − `tFD 2000`) inside a 30 s round; at `c = 1` the
  round's ball count equals the number of engine ticks that bet, so `maxBetsPerRound` is the
  volume knob (AD-10).
- **Plinko variance is large — do not read RTP off ten rounds.** Per-ball standard deviation
  of the return multiple is ≈1.2 against a mean of 0.956 (the 100× buckets carry ~30% of the
  second moment at ~1/32 768 each), so after 100 balls the observed ratio still has a standard
  error of ≈0.12. The falsifiable short-window checks are "winnings > 0" and "stake matches
  the sends"; the RTP anchor needs thousands of balls.
- **`odd` must be `double`** (AD-12). A `long` silently truncates `1.2` to `1` and nothing
  fails.
- **Do not model `p`, `obs`, `odds` or `jps`** (AD-13) — and specifically, do not reach for
  `p.wm` when the winnings number looks odd; read AD-5 first.
- **`htr[].eid` is the string `"-"` on this game** where other 114 games send ints. `htr` is
  not modelled, so this is inert — but if anyone ever models round history, it is a `String`.
- **The capture's outbound frames are deduped 8-of-25** (pre-fix tooling). Any future claim
  about `c`'s distribution, or about whether repeated bets stack or append (§3), needs a
  **fresh capture** — the current tooling keeps every outbound frame.
- **`forGame` must be called at exactly one place.** If a second call site for
  `MessageTypesRegistry.bettingMini` ever appears without it, ziczac silently falls back to
  the generic provider and winnings go to zero — the routing test in Phase 1 step 7 pins the
  behaviour, not the call site, so reviewers should watch for the second call site.
- **This is a working-tree feature branch; nothing is committed or pushed.** The user controls
  what lands.

## Open Items

- **OI-1 — `mbs[].p` is unidentified.** 0..49, repeats within a round (19 entries / 17
  distinct), so it is neither a bucket index nor a unique slot index. Not modelled; no code
  depends on it. A backend answer or a capture with a known single drop would settle it.
- **OI-2 — stack vs. append is unresolved** (§3): the user's backend-sourced description says
  a bet adds `b` to the first `c` slots and stacks, the 12002 echo and `mbs` of round
  `1995084` say each bet appends its own balls. Both give the same `sum(mbs[].b)`, and Phase
  2 sends `c = 1` where they coincide, so nothing in this plan turns on it. **It becomes
  load-bearing the moment multi-ball ships** (OI-6), because it decides whether a second bet
  raises an existing ball's stake or creates a new one.
- **OI-3 — the maximum ball count per round is unproven.** 20 was the largest observed and is
  also the selector's maximum; whether the server caps the round's total at 20 is unknown.
  Only matters for OI-6.
- **OI-4 — `obs` was empty in 8/8 rounds**, including 6 we bet in. The user reports it carries
  payouts (`r`) like 12019's `tbps[]{r,v,dn,odd}` when populated. Unmodelled, and there is no
  contract slot for the room feed anyway (AD-8). A two-account capture would settle both this
  and OI-5's sibling question about `p`'s scope in one pass.
- **OI-5 — no jackpot discharge was captured** (`iJp` false 8/8, `p.jwm` 0 6/6), so
  `HasJackpot` stays unimplemented for ziczac and it is unknown whether a discharge would be
  added into `p.wm` / `mbs[].r` or reported separately. One capture of a hit settles it.
- **OI-6 — multi-ball (`c` > 1) is out of scope.** It needs a `BotBehaviorConfig` field, an
  engine change so `creditBalance` / `recordBetSent` / `RoundResult.balanceDelta` debit
  `amount × c`, and OI-2 answered. The existing `maxBetsPerRound` already delivers volume at
  `c = 1`, so there is no current requirement.
- **OI-7 — a two-account capture remains the cheapest unblocker on this product.** It would
  close `RIK_114_BETTING_MINI` OI-4 (`v` vs `b`), this plan's `p`-scope question (AD-5 /
  §4) and OI-4 above, all at once.
- **OI-8 — `RIK_114_BETTING_MINI` V-6 is still unrun**, so stock's `v`-vs-`b` question is
  open. Its answer no longer changes *this* design: if V-6 fails, the stock body ships as a
  second branch of AD-9's mechanism and `usesStockBetBody()` is never written.
- **Out of scope:** modelling or sending 12007 / 12012 / 12017 / 12018 / 12019; any change to
  `RikEndGameMessage` / `RikBetInfo` / `RikMainBetSummary` / the five existing `Rik*` accessors;
  any `Game`-schema change; any registry-key change (that is `PLUGIN_HOT_RELOAD` step 5); bot-side
  verification of the board's fairness.
- **Depends on nothing external.** The env (`394301f4-…`), the RIK auth path and the 114
  brand config already exist.

## Verification

Run on staging (Bot-1) after deploy. This feature **has** on-server verification beyond the
universal smoke test. `BASE=http://localhost:8080`,
`ENV=394301f4-6daf-4c55-a073-502a81c00731`.

**Phase split:** V-1..V-5 and V-8..V-9 apply to Phase 1 alone. **V-6 and V-7 require Phase 2**
— until the bet body ships, no bet of ours lands, and `mbs` is populated only by our own bets
(Implementation Notes). Do not read a zero in V-7 as a Phase 1 defect if Phase 2 is not
deployed.

**V-1 — the app is up.**
```
curl -s -o /dev/null -w '%{http_code}\n' $BASE/actuator/health
curl -s $BASE/actuator/health | jq -r .status
```
Expect **200** and **`UP`**.

**V-2 — the registry is UNCHANGED (the decisive check that AD-3 did not perturb it).**
```
docker logs bot-java-bot-manager-1 2>&1 | grep "MessageTypesRegistry initialized"
```
Expect **exactly one** line, containing
`BETTING_MINI 6 products [097, 098, 114, 116, 118, 119]` and `TAI_XIU 3 products [114, 116,
119]` — **identical to the pre-deploy baseline**. A changed product set means a ziczac
provider was registered as a bean, which AD-3 forbids (and which would have failed context
refresh on the duplicate key).

**V-3 — a ziczac `Game` can be created.**
```
curl -s -X POST "$BASE/api/v1/game/G3/P_114/$ENV" -H 'Content-Type: application/json' -d '{
  "name":"RIK ZicZac (Plinko)","gameType":"BETTING_MINI","pluginName":"ziczacPlugin",
  "offset":9000,"gameId":12000,"md5":false,
  "optionAffinities":{"0":1}}'
```
Expect HTTP **200/201** with `offset:9000`, `pluginName:"ziczacPlugin"`, `md5:false` and a
**one-entry** `optionAffinities`. Record `id` as `$GAME`. Note `gameType` is **BETTING_MINI**
(AD-1) and the single option is deliberate (AD-11).

**V-4 — a 2-bot group on that game starts, authenticates and subscribes.** Create a 2-bot
group bound to `$GAME`/`$ENV` (same body shape as the existing P_114 groups; keep the max bet
under `mB = 50 000 000`), then:
```
curl -s -X POST $BASE/api/v1/bot-group/$GROUP/start
sleep 60
curl -s $BASE/api/v1/bot-group/$GROUP/health | jq '{status,bots:[.bots[].status]}'
```
Expect HTTP **200** on start and, within 60 s, **both bots `CONNECTION_AUTHENTICATED`** and
group status `ACTIVE`. `markConnectionAuthenticated()` is called from `onSubscribe`, so this
proves subscribe CMD 12000 was answered and parsed.

**V-5 — StartGame and EndGame deserialize.** After ~3 rounds (≈ 100 s):
```
curl -s "$BASE/actuator/metrics/bot_messages_total?tag=botGroupId:$GROUP&tag=cmd:startGame" | jq '.measurements[0].value'
curl -s "$BASE/actuator/metrics/bot_messages_total?tag=botGroupId:$GROUP&tag=cmd:endGame"   | jq '.measurements[0].value'
docker logs bot-java-bot-manager-1 2>&1 | grep -E "ERROR|Exception" | tail -20
```
Expect **both > 0** (≥ 2 each after 3 round-lengths) and **no line mentioning ziczac, 114,
RIK or `InvalidTypeIdException`**. These counters increment inside `onStartGame`/`onEndGame`,
which only run once the polymorphic subtype resolved.

**V-6 — the outbound bet body is the ziczac one (Phase 2).** With the group running:
```
grep -h '"cmd":12002' /home/sgame/bot-java/logs/detail/detail.log | tail -20
```
Expect **outbound** frames carrying **`"b":<amount>` and `"c":1`**, a `"sid"` and `"aid":1`,
and **no `"eid"`, no `"v"`, no `"iAc"`**. An outbound frame still carrying `"eid"` means
`buildRequest` did not take the `GameRequestFactory` branch — check `forGame` routing
(AD-3/AD-9) before anything else. Expect the **inbound** 12002 echo to carry a `bs` entry
whose `b` equals the amount we sent (it is our own per-ball state, AD-8) — that is the server
confirming the ball, and it is the earliest positive signal that the body was accepted.

**V-7 — the payout metrics are non-zero and the stake is right (Phase 2).** After ≥ 20
settled rounds:
```
G="tag=botGroupId:$GROUP"
curl -s "$BASE/actuator/metrics/bot_bet_amount_total?$G"  | jq '.measurements[0].value'
curl -s "$BASE/actuator/metrics/bot_bets_placed_total?$G" | jq '.measurements[0].value'
curl -s "$BASE/actuator/metrics/bot_winnings_total?$G"    | jq '.measurements[0].value'
```
Expect:
- **all three > 0** — `bot_winnings_total > 0` is the single metric this whole plan exists to
  un-zero (§7). A flat zero with a non-zero stake means `winningsFor` is not firing: confirm
  the ziczac EndGame class is what registered (V-2 + V-6) before suspecting the server.
- `bot_bet_amount_total` ≈ `bot_bets_placed_total × <per-ball stake>` **within a few percent**
  — at `c = 1` the server-confirmed ball count and our send count agree (AD-6/AD-10). A
  systematic factor-of-`c` gap means something started sending `c ≠ 1`.
- `bot_winnings_total / bot_bet_amount_total` **anywhere in [0.3, 2.0]** at this sample size —
  Plinko variance is large (Implementation Notes). The **0.956** anchor only applies over
  thousands of balls; sustained above **1.0** over ≥ 1000 balls is the `CLAUDE.md` RTP-anomaly
  condition and is worth escalating, not celebrating. A ratio near **0.0 or ≈ the stake
  exactly** means someone netted or re-grossed the return (AD-5).

**V-8 — the other two 114 games are unchanged.** The registry line in V-2 already pins the
product set. Additionally, for a running non-ziczac group (a `stockPlugin` or
`taixiuMd5Plugin` group, or any other product's group):
```
curl -s $BASE/api/v1/bot-group/$OTHER_GROUP/health | jq '{status, connected:[.bots[].status]|map(select(.=="CONNECTION_AUTHENTICATED"))|length}'
curl -s "$BASE/actuator/metrics/bot_bet_amount_total?tag=botGroupId:$OTHER_GROUP" | jq '.measurements[0].value'
```
Expect status, connected count and a **still-incrementing** stake metric versus the pre-deploy
baseline. This is the regression check for AD-2 and AD-9: the generic provider and the shared
`Bet` body must behave identically to before.

**V-9 — the observability stack came back.** bot-manager and the observability stack share one
compose project on Bot-1: `docker compose ps` → **all containers `Up`**, `GET :3000/api/health`
→ **200**, `GET :9090/-/healthy` → **200**, `GET :3100/ready` → **`ready`**.

**Cleanup.** The V-4 group is a throwaway: `POST /api/v1/bot-group/$GROUP/stop` (which sets
`targetStatus=STOPPED`, so dead-group auto-recovery will never pick it up), then delete the
group and the `Game` record unless the user wants the game kept.

## Amendment — 2026-09-18 (Compliance, Phase 2)

**Scope: one parenthetical in `## Amendment A1` → "What Phase 2 now is" item 1.** Everything
else in A1 stands and was implemented as written.

Item 1 says: *"Mirror `RikStockBet`'s shape (`Body` subclass, `@Getter(AccessLevel.NONE)`
fields)."* The parenthetical is wrong on two falsifiable counts:

1. **It mis-describes `RikStockBet`.** That class has a **class-level `@Getter @Setter`** on
   `BetData` and suppresses the getter on exactly **one** field — `iAc`, the mixed-case key —
   *paired with* `@JsonProperty("iAc")`. `v`, `sid`, `aid`, `eid` all have ordinary Lombok
   getters. "`@Getter(AccessLevel.NONE)` fields" describes the exception, not the shape.
2. **Taken literally it cannot produce the body the same item specifies.** A private field
   with its getter suppressed and no `@JsonProperty` is invisible to Jackson at default
   visibility (`Body` itself is read through its public `getCmd()`; no mapper in this codebase
   widens field visibility). Reproduced on 2026-09-18 with the project's Jackson: a `Body`
   subclass with getter-less unannotated `b`/`c`/`sid`/`aid` serializes to **`{"cmd":12002}`**
   — a bet with no stake, i.e. the very "accepted in-round, discarded at settlement" outcome
   this phase exists to end. Item 1's own next sentence ("say so in the javadoc so nobody adds
   `@JsonProperty` defensively") already presupposes there is *no* `@JsonProperty` on
   `ZicZacBet`, so the parenthetical contradicts the rest of the item.

**Corrected guidance (what shipped, and what the rule actually is):** the rule is **per key,
not per class**. Every key gets a Lombok getter (class-level `@Getter`), which Jackson
de-mangles to the wire key — sufficient for any all-lowercase key. A key with a
`<lower><UPPER>` run (`iAc`, `sId`) is the only case that needs `@Getter(AccessLevel.NONE)`
**plus** `@JsonProperty`, and both together. `ZicZacBet` has no such key, so it carries
class-level `@Getter` and no field annotations; `RikStockBet` / `RikStockCommit` keep theirs.
`ZicZacBetTest.noKeyNeedsAnExplicitJsonProperty` pins the all-lowercase property and
`keySetIsExact` pins the five-key result under the real mapper.

**Recorded, not amended — a stale sentence in production prose.** `RikGameMessageTypes.
requestFor`'s javadoc still says the ziczac provider "does **not** implement
`GameRequestFactory` in this phase (AD-24)". It is self-dated (it names `RIK_114_ZICZAC`
Phase 2 as the change that supersedes it) and has no behavioural effect; Dev was instructed
not to edit that file. Whoever next touches `RikGameMessageTypes` should delete that
paragraph. `RIK_114_BETTING_MINI.md` AD-24 is likewise historical from this date.
