# Aggregated Session Logging — Replace Per-Frame WS Noise with Per-Session Summaries

## Goal

Stop logging every WebSocket frame and instead log **semantic aggregates per
game session**. At ~200 bots betting every 500–1000 ms the `updateBet`
broadcast class alone is ~250–270 msg/s ≈ 100–200 MB/hr of near-useless
per-frame noise, and a 30 s live sample proved exact-body dedup cuts only ~10%
(updateBet carries continuously-changing aggregate totals, so nearly every
frame is unique). This feature introduces a per-session aggregator keyed by
`(botGroupId, gameId, sid)` fed by the bots' existing inbound/outbound handlers,
emitting: one **StartGame** line per session per group, one **UpdateBet**
running summary every 5 s per active session, and one **EndGame** summary when
the round closes. Raw per-frame `OutputPrinter` output moves to **TRACE**, still
reachable via `/actuator/loggers`. This finalizes and **supersedes the P0a
OutputPrinter demotion** (`docs/plans/RESILIENCE_HARDENING.md` Phase P0a), which
was ineffective because production runs at the DEBUG default and the per-frame
`debugOutputPrinter` lines still flood at DEBUG. It also addresses the disk-fill
half of the 2026-06-30 outage (`docs/plans/RESILIENCE_HARDENING.md`,
`docs/reviews/RESILIENCE_HARDENING/`).

## Findings — Current State

### Where frames are logged today (the amplifier)

- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/util/OutputPrinter.java`
  - `defaultOutputPrinter` → `log::trace` (`:34`), `prettifiedOutputPrinter` →
    `log::trace` (`:109`) — already TRACE (P0a shipped).
  - `debugOutputPrinter` → `log.debug("User {}: {}", name, s)` (`:56`) — **still
    DEBUG**. This is the `User <name>: ...` per-frame line and, because
    production runs at the DEBUG default (CLAUDE.md "Logging Guidelines"), it is
    the surviving flood. It is registered as a **separate scenario** on the WS
    client, independent of `botBehaviorScenario()`.
  - Wired per bot in `BettingMiniGameBot.onStart` (`:715`, cmd list at `:706`)
    and `SlotMachineBot.onStart` (`:397`, cmd list at `:393`). Both pass the
    bot's `mdcSnapshot` so lines carry `botGroupId`/`environmentId`/`gameType`.

### Structured data the aggregator can tap (prefer over re-parsing log strings)

- `BettingMiniGameBot` (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java`)
  - `onStartGame` (`:357`) parses `StartGameMessage.getSessionId()` and calls
    `sidStore.set(...)` (`:366`) — the round-boundary + session id source.
  - `bet()` supplier (`:552`) is the **outbound** tap: it has `optionId`,
    `amount`, and `sidStore.get()` (`:582-587`) — this is where a bot's stake
    for the current session is known.
  - `onUpdate` (`:387`) — inbound updateBet; body (`BomUpdateBetMessage`) carries
    only `gS`/`rmT` (game state / remaining time), **no bettor/stake data** —
    confirming the UpdateBet aggregate must be sourced from the group's own
    outbound bets, not the frame body. (Tip's `TipUpdateBetMessage` does carry a
    `bs` broadcast list, but Bom/Nohu do not — so bot-sourced aggregation is the
    only uniform cross-product source.)
  - `onEndGame` (`:397`) already extracts, per marker interface:
    `HasBotWinnings.winningsFor(userName)` (`:411`), `HasJackpot.jackpotFor`
    (`:425`), `HasBetTotals.betAmountFor/betCountFor` (`:429`). The Tai Xiu
    winnings fix (`winnings = G`, refund `gR`) lives in
    `TaiXiuGameBot.balanceCreditFor` (`:270`) and `HasBotWinnings` impls — this
    is the "correct winnings formula per game type" the EndGame summary reuses.
- `TaiXiuGameBot` (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/TaiXiuGameBot.java`)
  - Extends `BettingMiniGameBot`; only the message/CMD layer differs (offsets
    1105/1102/1104/1100 for P_114). `endGameSessionId` overridden (`:253`) to
    read `sidStore.get()` because the Tai Xiu EndGame frame carries no `sid`.
    Every inbound/outbound tap above is inherited unchanged.
- `SlotMachineBot` (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/SlotMachineBot.java`)
  - No shared round clock, no BET/PAYOUT, no `sid` broadcast (`:44-46`). `spin()`
    (`:301`) is the outbound tap (`amount`, `numLines`, `totalStake` at `:321`);
    `onSpinResult` (`:211`) is the inbound tap (`HasBotWinnings` `:228`, jackpot
    fields `iJ`/`J` on `SlotSpinResultMessage`, per-spin `sid`). There is no
    StartGame/EndGame — slots need a **synthetic per-group time window**, not a
    session key.

### Reusable infrastructure

- `BotMetrics` (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/infrastructure/observability/BotMetrics.java`)
  is a Spring `@Component` singleton reading identity from MDC per call and is
  injected into every bot via `bot.setMetrics(...)` in
  `BotFactory.createBot` (`:184`). The aggregator can be injected the same way.
  Existing counters (`bot_bet_amount_total`, `bot_winnings_total`,
  `bot_jackpot*_total`, `bot_bets_placed_total`) already carry the per-round
  totals — **do not duplicate them**; the aggregator emits *log lines*, not new
  counters (v1).
- MDC keys in `BotMdc` (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/common/logging/BotMdc.java`):
  `botGroupId` (`:17`), `gameId`, `gameName`, `gameType`, `environmentId`,
  `botUserName`. Group **display name** is not in MDC (only the UUID
  `botGroupId`); `gameName` is. StartGame line uses `gameName` + `botGroupId`.
- Virtual-thread scheduled-executor pattern to copy for the 5 s flush:
  `BotGroupBehaviorService.startHealthMonitoring` (`:909`, `scheduleAtFixedRate`
  every 30 s, `:915`) and `startPeriodicLogoutScheduler` (`:994`). Both set
  `BotMdc.setGroupContext` inside the task and clear it in `finally`.
- Group lifecycle hooks for eviction: `BotGroupRuntime.stopAllBots` (`:243`) is
  the single teardown path; `BotGroupBehaviorService.stopBotGroup` calls it at
  (`:559`).

### First-seen guard requirement

`onStartGame` runs on the per-client `netty-ws-message-processor-ws-<userName>`
pool — **once per bot per round**. With 100 bots in a group all 100 fire
`onStartGame` for the same `sid` within milliseconds. Without a thread-safe
first-seen guard on `(botGroupId, gameId, sid)` the StartGame line prints 100×.

## Per-aspect readiness / mapping

| Aspect | Readiness | Notes |
|---|---|---|
| Session key `(groupId, gameId, sid)` for betting | ready | `sidStore` + MDC `gameId`; feed from `onStartGame`/`bet()`/`onEndGame`. |
| First-seen StartGame guard | ready | `ConcurrentHashMap.putIfAbsent` on session key. |
| Outbound stake/bettor source | ready | `bet()` (`:582-587`), `spin()` (`:321`). |
| EndGame totals (bet/win/bettors) | ready | reuse `HasBotWinnings`/`HasBetTotals`; Tai Xiu winnings=`G` already correct. |
| UpdateBet 5 s flush | partial | needs a group-scoped scheduled flush + concurrent counters; frame body has no aggregate (Bom/Nohu). |
| Slot session notion | partial | no `sid` round; needs synthetic per-`(group,gameId)` time window. **Open Item.** |
| Raw frame TRACE escape hatch | ready | demote `debugOutputPrinter` (`:56`) to `log.trace`. |
| Injection into bots | ready | mirror `setMetrics` in `BotFactory` (`:184`). |
| Lifecycle eviction | partial | needs explicit EndGame removal + TTL sweep + group-stop hook + size cap. |

## Architecture Decisions

1. **One Spring singleton `SessionAggregationService`** (new,
   `infrastructure/observability/`), injected into each bot in
   `BotFactory.createBot` via a new `bot.setSessionAggregator(...)` setter,
   exactly as `setMetrics` is wired (`BotFactory.java:184`). It is
   null-tolerant on the bot side (like `metrics`) so unit tests without Spring
   still run.

2. **Session key is `(botGroupId, gameId, sid)`.** `botGroupId` and `gameId`
   are read from the calling thread's MDC (same mechanism as `BotMetrics`);
   `sid` is passed explicitly by the bot from `sidStore`. This keeps the
   service free of a `Bot` reference and matches the existing MDC-tagging model.

3. **Per-session state = `SessionAccumulator`**, a concurrent value object:
   `LongAdder totalStaked`, `LongAdder betEventCount`, a distinct-bettor
   `Set<String>` via `ConcurrentHashMap.newKeySet()`, `LongAdder
   winningsTotal` (filled at EndGame), plus a "since-last-flush" delta baseline
   (see AD-6), an `AtomicInteger flushSeq`, a captured `Map<String,String>
   mdcSnapshot` (so the flush thread emits correctly-tagged lines), and a
   `volatile long lastActivityNanos`. All feeds use lock-free adders/sets — no
   handler ever blocks a netty IO thread.

4. **Per-game-type behavior via a strategy interface, not `instanceof`.**
   Define `SessionAggregationStrategy` with:
   - `boolean hasRoundBoundary()` — betting/Tai Xiu `true`, slot `false`;
   - `String renderStartLine(SessionAccumulator, ctx)`,
   - `String renderFlushLine(SessionAccumulator, ctx)`,
   - `String renderEndLine(SessionAccumulator, ctx)`.
   The bot supplies its strategy (a per-`GameType` singleton) when it registers
   feeds, so the service holds no game-type knowledge. Betting and Tai Xiu share
   `BettingSessionStrategy` (Tai Xiu differs only in field labels, which come
   from the accumulator, not the strategy). Slot uses `SlotSessionStrategy`.

5. **Emit paths (betting / Tai Xiu):**
   - **StartGame** — bot calls `aggregator.onSessionStart(sid, strategy)`; the
     service does `putIfAbsent` on the key. **Only the thread that wins the
     put** logs `"BotGroup <gameName>/<botGroupId> entered session <sid>"` plus
     ONE raw message sample (the bot passes the raw frame string only on the
     first-seen path — see AD-9). Losers return immediately.
   - **UpdateBet** — NOT emitted from the handler. A single app-wide scheduled
     task (AD-7) emits `"UpdateBet #<n> | new bettors since last: X | total
     bettors this round: Y | total staked: Z"` every 5 s per **active** session.
   - **EndGame** — bot calls `aggregator.onSessionEnd(sid, botWinnings,
     botBetAmount, rawSampleIfFirstSeen)`. Winnings/bet amounts accumulate per
     bot; the **first bot to observe EndGame for the key** logs the summary
     (total staked, total win, bettor count) + ONE raw sample, then the entry is
     scheduled for eviction (AD-8). Uses the same winnings values the bot
     already computed via `HasBotWinnings`/`HasBetTotals` (correct per game
     type, incl. Tai Xiu `G`).

6. **Delta-since-last-flush vs total-this-round.** `total bettors this round` =
   `distinctBettors.size()`; `total staked` = `totalStaked.sum()`. The flush
   captures a snapshot and stores it as the baseline; `new bettors since last`
   = `currentDistinct − baselineDistinct` computed from a snapshot count taken
   under the flush (single-threaded per session, so no CAS needed on the
   baseline). Counters are **never reset mid-round** — they reset by the
   accumulator being evicted and recreated at the next session (AD-8), which is
   race-free because a new `sid` creates a new key.

7. **One app-wide flush scheduler**, a single-thread virtual-thread
   `ScheduledExecutorService` created in the service `@PostConstruct` (mirrors
   `startHealthMonitoring`'s factory, `BotGroupBehaviorService.java:910`),
   `scheduleAtFixedRate(..., 5, 5, SECONDS)`. Each tick iterates the live
   accumulator map: for `hasRoundBoundary()` sessions still active it emits the
   UpdateBet flush line (under the entry's `mdcSnapshot`); for slot sessions it
   emits the slot window line; and it sweeps TTL-expired entries (AD-8). One
   shared scheduler (not per-group) keeps thread count flat and matches the
   `BotMetrics`-style singleton model. Shut down in `@PreDestroy`.

8. **Lifecycle / eviction — bounded, no unbounded growth (load-bearing).**
   - Explicit removal: `onSessionEnd` schedules the key for removal after a
     short grace (e.g. one flush interval) so a late straggler EndGame/flush
     does not resurrect-then-orphan it.
   - TTL sweep backstop: the 5 s scheduler removes any entry whose
     `lastActivityNanos` is older than `TTL` (e.g. 60 s) — this reclaims
     sessions whose EndGame was never observed (server pruning, disconnect).
   - Hard size cap: the map is capped (e.g. 10 000 entries); on overflow the
     service drops the oldest and logs one WARN. This makes a leak impossible by
     construction — the failure this whole feature exists to prevent.
   - Group-stop hook: `SessionAggregationService.evictGroup(botGroupId)` called
     from `BotGroupRuntime.stopAllBots` / `BotGroupBehaviorService.stopBotGroup`
     removes all keys for a stopped group so nothing dangles. (Optional but
     recommended; TTL is the backstop if omitted.)

9. **Raw-sample cost control.** The StartGame/EndGame "ONE raw sample" must be
   captured only on the first-seen path to avoid every bot serializing the
   frame. The bot passes the raw `ProcessableMessage`/JSON **only** when it is
   the first-seen thread would be ideal, but first-seen is decided inside the
   service; so the bot passes a `Supplier<String>` (lazy) and the service
   invokes it only on the winning `putIfAbsent`/first-EndGame path. No
   serialization happens on the 99 losing bots.

10. **Emit level = DEBUG for all three summary lines (recommended);**
    StartGame/EndGame MAY be promoted to INFO (Open Item). Rationale per
    CLAUDE.md logging norms: these are **per-round / per-session** detail (a
    round every ~30–60 s, a flush every 5 s), not group lifecycle, so DEBUG is
    the correct bucket — and because production runs at the DEBUG default,
    operators see the aggregates by default while the raw per-frame path (now
    TRACE, AD-11) stays silent. Emitting at INFO would breach the CLAUDE.md
    guidance that "a 30-bot group running for an hour should produce low tens of
    INFO lines" (a 5 s flush alone is 720 lines/hr/session).

11. **Raw frames demoted to TRACE (escape hatch, supersedes P0a).**
    `OutputPrinter.debugOutputPrinter`'s printer (`:56`) changes from
    `log.debug` to `log.trace`. The `User <name>: <frame>` per-bot wire drill-in
    then appears only when `com.vingame.bot` is flipped to TRACE via
    `/actuator/loggers`. The raw path is **not deleted**. Update the
    `debugOutputPrinter` javadoc (`:44-52`) to say TRACE and note it supersedes
    RESILIENCE_HARDENING P0a.

12. **Slot session = synthetic per-`(botGroupId, gameId)` rolling window**
    (no `sid` round). One long-lived accumulator per `(group, gameId)` created
    on the first spin feed, emitting a window summary every 5 s
    (`spins since last | total staked | total win | jackpot hits`) and resetting
    its since-last baseline each flush; evicted by TTL/group-stop only. Final
    keying (per-group vs per-bot, window length) is an **Open Item**.

## Plan

### Phase 1 — Core service + betting/Tai Xiu StartGame & EndGame (CODE)

Targets:
- New `SessionAccumulator` (concurrent value object, AD-3) and
  `SessionAggregationStrategy` interface + `BettingSessionStrategy` (AD-4) in
  `infrastructure/observability/`.
- New `SessionAggregationService` singleton (AD-1, AD-2, AD-5, AD-8) with
  `onSessionStart`, `recordBet`, `onSessionEnd`, `evictGroup`; map keyed by
  `(botGroupId, gameId, sid)` read from MDC + explicit `sid`; first-seen
  `putIfAbsent`; hard size cap + TTL fields (sweep wired in Phase 2).
- Wire injection: add `Bot.setSessionAggregator(...)` (null-tolerant, mirror
  `setMetrics` at `Bot.java:139`) and call it in `BotFactory.createBot`
  (after `.setMetrics(botMetrics)`, `:184`).
- Feed betting taps: `BettingMiniGameBot.onStartGame` (`:366`, after
  `sidStore.set`) → `onSessionStart`; `bet()` (`:584-587`) → `recordBet(bettor,
  amount)`; `onEndGame` (`:446` area) → `onSessionEnd(sid, winnings,
  betAmount, rawSupplier)`. Tai Xiu inherits all three unchanged (verify
  `endGameSessionId` path supplies the tracked sid).
- StartGame + EndGame lines emit here (DEBUG, AD-10). No flush yet.

Verification (Phase 1):
```bash
JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home mvn -q clean test
```
- Expect: build + tests green.
- New unit tests expected: (a) 100 concurrent `onSessionStart` for one key →
  exactly ONE start line, 99 no-ops (assert via a captured log appender or a
  test double counter); (b) `onSessionEnd` for one key from N bots → ONE
  summary with `totalStaked == Σ recordBet` and `bettorCount == distinct
  userNames`; (c) Tai Xiu winnings summary uses `G` (feed a `TaiXiuEndGameMessage`).

### Phase 2 — 5 s UpdateBet flush + eviction sweep (CODE)

Targets:
- Add the single app-wide virtual-thread `ScheduledExecutorService` (AD-7) in
  `SessionAggregationService` `@PostConstruct`, `scheduleAtFixedRate(5s)`;
  `@PreDestroy` shutdown.
- Flush task: per active `hasRoundBoundary()` session emit the UpdateBet running
  summary under the entry's `mdcSnapshot` (AD-5/AD-6), advancing the
  since-last-flush baseline and `flushSeq`.
- Eviction: EndGame grace removal + TTL sweep + size cap (AD-8) in the same
  task. `evictGroup` hook called from `BotGroupRuntime.stopAllBots` (`:243`) or
  `BotGroupBehaviorService.stopBotGroup` (`:559`).

Verification (Phase 2):
```bash
JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home mvn -q test
```
- Expect: green, incl. tests that (a) after two flush intervals a session with
  3 recorded bettors emits `total bettors this round: 3` and `new bettors since
  last` deltas that sum to 3; (b) an entry with no activity past TTL is removed
  (map size returns to 0); (c) `evictGroup` clears all keys for a group;
  (d) map size never exceeds the cap under a flood.

### Phase 3 — Slot aggregation (CODE)

Targets:
- `SlotSessionStrategy` (AD-4, AD-12): synthetic `(group, gameId)` window,
  `hasRoundBoundary()=false`.
- Feed slot taps: `SlotMachineBot.spin()` (`:321`) → `recordBet(bettor,
  totalStake)`; `onSpinResult` (`:228-248`) → record win + jackpot (`iJ`/`J`).
- Flush task renders the slot window line every 5 s; eviction by TTL/group-stop.

Verification (Phase 3):
```bash
JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home mvn -q test
```
- Expect: green; a slot test asserts one window line per 5 s per `(group,gameId)`
  with `spins since last`, `total staked`, `total win`, `jackpot hits` matching
  fed values; no StartGame/EndGame lines for slots.

### Phase 4 — Demote raw frames to TRACE (CODE)

Targets:
- `OutputPrinter.debugOutputPrinter` printer (`:56`): `log.debug` → `log.trace`.
- Update javadoc (`:44-52`) to TRACE; note it finalizes/supersedes
  RESILIENCE_HARDENING P0a and that aggregated summaries replace it at DEBUG.
- Grep for anything asserting `User <name>:` at DEBUG in tests
  (`OutputPrinterMdcTest`) and adjust level expectations.

Verification (Phase 4):
```bash
grep -nE "log\.debug|log\.info|log::info" src/main/java/com/vingame/bot/domain/bot/util/OutputPrinter.java   # expect: no matches (all trace)
JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home mvn -q test
```
- Expect: no non-trace log calls in `OutputPrinter`; tests green.

## Implementation Notes / Concerns

- **Never block a netty IO thread.** All feed methods must be lock-free
  (`LongAdder`, `ConcurrentHashMap.newKeySet`, `putIfAbsent`). No logging of the
  raw sample on the losing paths — pass a lazy `Supplier<String>` (AD-9).
- **Round boundary race.** A bot may still be on the previous `sid` when others
  moved on: because the key includes `sid`, a stale bet lands in the previous
  session's accumulator (correct — it *was* staked in that round) or in a nascent
  new one; either way no double-count. A late EndGame after a flush is handled by
  the grace-then-evict (AD-8); a flush racing eviction is harmless (it reads a
  removed entry → skip).
- **First-seen for EndGame** needs its own guard flag on the accumulator
  (`AtomicBoolean endLogged`) so the summary logs once even though winnings from
  all N bots must still accumulate first. Order: every bot accumulates its
  win/bet, then `compareAndSet(false,true)` decides who logs. Note the summary
  therefore reflects only bettors whose EndGame arrived *before* the logging bot;
  accept this (the flush already reported near-final totals) or log on a short
  delay — call out in the summary that totals are "as of first close". Keep it
  simple: log on first close; the metrics counters remain the authoritative
  totals.
- **Group display name** is not in MDC; use `gameName` + `botGroupId` in the
  StartGame line, or thread the group display name into MDC at group start if a
  friendlier label is wanted (small follow-up, not required).
- **Do not add new Micrometer counters** — the per-round totals already exist in
  `BotMetrics` (`bot_bet_amount_total`, `bot_winnings_total`, `bot_jackpot*`).
  This feature is log-line only in v1.
- **Tai Xiu** rides `BettingMiniGameBot` entirely; verify the inherited
  `onEndGame` passes `endGameSessionId(msg)` (the tracked sid) into
  `onSessionEnd`, since the Tai Xiu frame has no `sid`.

## Open Items

- **Emit level: DEBUG vs INFO for StartGame/EndGame** (AD-10). Recommended DEBUG
  (visible at the production DEBUG default; INFO would breach the low-tens/hr
  norm). Needs operator sign-off.
- **Slot round-keying** (AD-12): per-`(group,gameId)` window vs per-bot; window
  length (fixed at the 5 s flush vs a longer roll-up). Needs product input on
  what a meaningful slot "session" is.
- **`evictGroup` wiring** — confirm the preferred hook (`BotGroupRuntime` vs
  `BotGroupBehaviorService`); TTL sweep is the backstop if deferred.
- **Raw-sample verbosity** — whether EndGame includes a raw sample in addition
  to StartGame, or StartGame only (both currently specified; drop EndGame's if
  noise is a concern).

## Verification

On-server checks the Releaser runs on Bot-1 staging after deploy. Assumes a
betting group (e.g. a BauCua/Tai Xiu group) is startable; use its `<groupId>`.
Log source is the container stdout / `console.log`.

1. **Baseline volume (before, if comparing on the same host):** capture 60 s of
   log volume while a group runs at the DEBUG default:
   ```bash
   timeout 60 grep -c "User .*:" console.log
   ```
   Expect (pre-deploy jar): hundreds–thousands of `User <name>:` frame lines.

2. **Deploy + confirm logger at default DEBUG:**
   ```bash
   curl -s localhost:8080/actuator/loggers/com.vingame.bot | grep -o '"effectiveLevel":"[A-Z]*"'
   ```
   Expect: `"effectiveLevel":"DEBUG"`.

3. **Raw frames silent at DEBUG:** over a 60 s window with a group running,
   ```bash
   timeout 60 grep -c "User .*:" console.log
   ```
   Expect: `0` (raw per-frame lines are now TRACE).

4. **One StartGame line per session per group:** for a single round's `sid`,
   ```bash
   grep "entered session" console.log | grep <groupId> | grep <sid> | wc -l
   ```
   Expect: `1` (not one per bot). Line matches `^.*BotGroup .* entered session <sid>`.

5. **One UpdateBet summary per session per 5 s:** over a 30 s window of one
   active session,
   ```bash
   timeout 30 grep "UpdateBet #" console.log | grep <sid> | wc -l
   ```
   Expect: `~6` (30 s / 5 s), sequence numbers `#1..#6` monotonic; each line
   shows `total bettors this round: Y` with `Y` equal to the group's live bot
   count and `total staked: Z` non-decreasing across the sequence.

6. **One EndGame summary per session with correct totals:**
   ```bash
   grep "session <sid>.*total staked" console.log | wc -l   # or the EndGame summary marker
   ```
   Expect: `1` per closed `sid`; `total win` uses the correct per-game winnings
   (cross-check against `bot_winnings_total` delta for the group over the round
   via `curl -s localhost:8080/actuator/prometheus | grep bot_winnings_total`).

7. **Raw frames reappear at TRACE (escape hatch):**
   ```bash
   curl -s -X POST localhost:8080/actuator/loggers/com.vingame.bot \
     -H 'Content-Type: application/json' -d '{"configuredLevel":"TRACE"}'
   timeout 20 grep -c "User .*:" console.log
   ```
   Expect: HTTP 204 on the POST; `> 0` `User <name>:` lines within 20 s. Then
   restore DEBUG:
   ```bash
   curl -s -X POST localhost:8080/actuator/loggers/com.vingame.bot \
     -H 'Content-Type: application/json' -d '{"configuredLevel":"DEBUG"}'
   ```
   Expect: HTTP 204.

8. **Volume dropped ~2 orders of magnitude:** compare a 60 s line count at the
   DEBUG default before vs after (steps 1 vs a post-deploy `timeout 60 wc -l`
   on new lines for the same group). Expect: post-deploy total for the group's
   session logging is on the order of `StartGame(1) + UpdateBet(~12/min) +
   EndGame(1)` per session — roughly ~100× fewer lines than the per-frame flood.

9. **No aggregator memory leak:** after stopping the group, confirm no dangling
   session logging continues:
   ```bash
   curl -s -X POST localhost:8080/api/v1/bot-group/<groupId>/stop
   sleep 70   # > TTL
   timeout 20 grep -E "UpdateBet #|entered session" console.log | wc -l
   ```
   Expect: `0` new lines for the stopped group after the TTL grace (entries
   evicted; no unbounded map growth).

If a heap gauge for the aggregator map is added later, also assert its size
returns to 0 after group stop; for v1 the log-silence check above is the proxy.
