# Strategy-Decision Aggregation — Fold Per-Bet Decision Logging into the 5s Session Flush

## Goal

Extend the just-shipped session-aggregation feature
(`docs/plans/AGGREGATED_SESSION_LOGGING.md`) so the **strategy decision** — which
option/eid a bot bet on and how much it staked — is captured in the existing 5s
per-session aggregate flush, then demote the now-redundant **per-bot, per-bet
decision DEBUG lines to TRACE**. The prior feature killed the per-frame WS log
flood (176 MB/hr → 0) but total volume dropped only ~22%; the surviving dominant
class is per-bet strategy-decision logging. This plan folds that signal into the
aggregate (do not just drop it) and moves the per-bot drill-in down to TRACE,
reachable via `/actuator/loggers` exactly as the raw frames now are. It builds
directly on the shipped `SessionAggregationService` / `SessionAccumulator` /
`BettingSessionStrategy` / `SlotSessionStrategy` and **further supersedes the
interim per-bot DEBUG logging levels** that AGGREGATED_SESSION_LOGGING left in
place for the strategy layer.

## Motivation (measured on staging, DEBUG default)

After AGGREGATED_SESSION_LOGGING shipped, the raw `User <name>:` frame flood is
gone but total log volume fell only ~22%. The remaining dominant class is
per-bot per-bet strategy-decision DEBUG, measured on staging:

| Source | lines/min | Content |
|---|---|---|
| `BettingMiniGameBot` | ~15k | "strategy parked decision option/amount" + "sending bet option/amount/sid" |
| `MartingaleStrategySupport` | ~5.3k | per-bet "decide: bet option/amount", skip gates, per-round escalation |
| `RandomBehaviorStrategy` | ~3.5k | per-bet "decide: bet option/amount", skip gates, onRoundEnd |
| `FixedBetStrategy` / `RandomBetStrategy` | (per-spin) | "chooseBet: amount" |
| `SlotMachineBot` | (per-spin) | "parked spin", "sending spin" |

These are **per-bet cardinality** — the exact shape the WS frames had — and are
now largely redundant with the session aggregate. Folding the decision into the
aggregate and demoting the per-bot lines to TRACE targets the remaining ~75% of
volume, driving total log output toward the aggregate floor
(StartGame + ~12 UpdateBet/min + EndGame per session).

## Findings — Current State

### The feed / tap points (bot-sourced, consistent with the shipped design)

- **Betting bet tap** —
  `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java`
  - `bet()` supplier pops the parked `BetDecision` at `:610-612` — `optionId` and
    `amount` are **both in scope here** (`decision.optionId()` / `decision.amount()`).
  - The existing aggregate tap is `sessionAggregator.recordBet(currentSid,
    getUserName(), amount)` at `:622` — it drops the option on the floor. This is
    the one call whose signature must grow to carry the chosen option.
  - `onStartGame` tap at `:385-386` (`onSessionStart`) and `onEndGame` tap at
    `:464-466` (`onSessionEnd`) are unchanged by this plan.
- **Slot spin tap** —
  `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/SlotMachineBot.java`
  - `spin()` supplier at `:311-352`: the per-line bet `amount` (`:315-323`) and
    `totalStake = amount * numLines` (`:331`) are in scope. Existing tap
    `sessionAggregator.recordSpin(SlotSessionStrategy.INSTANCE, getUserName(),
    totalStake)` at `:340`. The per-line `amount` (the "decision") is not passed.
- `BetDecision` (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/BetDecision.java`)
  is `record BetDecision(int optionId, long amount)` — carries exactly the two
  fields the histogram + amount summary need. **No deeper strategy state**
  (Martingale `currentBet` vs `minBet`, escalation step) is on the decision; that
  lives in per-bot strategy instance fields (see Open Items).

### The accumulator/flush structures to extend

- `SessionAccumulator`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/infrastructure/observability/SessionAccumulator.java`)
  - Hot-path aggregates are lock-free `LongAdder` + a distinct-bettor key set
    (`:40-50`). `recordBet(String bettor, long amount)` at `:93-102` is the entry
    point to extend with the option.
  - Since-last-flush baselines (`bettorBaseline`, `stakedBaseline`, `spinBaseline`
    `:55-60`) are advanced only by the single flush thread; the per-tick lost-update
    fix captures snapshots once in `captureFlushSnapshot()` (`:234-238`) before the
    strategy renders. The new histogram + min/max must plug into this **same
    capture-then-advance** contract.
- `SessionAggregationService`
  - `recordBet(long sid, String bettor, long amount)` at `:323-333` and
    `recordSpin(...)` at `:347-356` forward to the accumulator; these signatures grow.
  - The 5s flush `emitFlush(...)` at `:216-245` calls `captureFlushSnapshot()` then
    `renderFlushLine(...)` then `advanceBaseline(...)` — the exact seam where a
    per-window histogram snapshot/reset slots in.
- `BettingSessionStrategy.renderFlushLine` at
  `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/infrastructure/observability/BettingSessionStrategy.java:34-44`
  and `SlotSessionStrategy.renderFlushLine` at
  `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/infrastructure/observability/SlotSessionStrategy.java:52-66`
  are the two lines that gain the distribution + amount shape.

### The per-bet DEBUG lines to demote (Part B)

| # | file:line | Current | Covered by aggregate? |
|---|---|---|---|
| 1 | `BettingMiniGameBot.java:624` | `sending bet option=,amount=,sid=` | Yes — option histogram + amount summary |
| 2 | `BettingMiniGameBot.java:665` | `strategy parked decision option=,amount=` | Yes — same signal, logged one tick earlier |
| 3 | `BettingMiniGameBot.java:661` | `strategy skipped tick (no decision)` | Partial — non-participation = liveBots − bettorCount |
| 4 | `RandomBehaviorStrategy.java:103` | `decide: bet option=,amount=` | Yes — option histogram + amount |
| 5 | `RandomBehaviorStrategy.java:75,83` | skip-gate lines | Partial — as #3 |
| 6 | `RandomBehaviorStrategy.java:111` | `onRoundEnd sessionId/payout/delta` | Yes — EndGame summary totals |
| 7 | `MartingaleStrategySupport.java:174` | `decide: bet option=,amount=` | Yes — option histogram + amount |
| 8 | `MartingaleStrategySupport.java:149,161` | skip-gate lines | Partial — as #3 |
| 9 | `MartingaleStrategySupport.java:218` | `onRoundEnd delta/prevBet/nextBet/capHit` | Partial — escalation internal (see Open Items) |
| 10 | `MartingaleStrategySupport.java:304` | `cap hit … resetting to minBet` | Partial — escalation internal, infrequent |
| 11 | `FixedBetStrategy.java:29` | `chooseBet: amount=` | Yes — slot bet-size histogram (Phase 2) |
| 12 | `RandomBetStrategy.java:28` | `chooseBet: amount=` | Yes — slot bet-size histogram (Phase 2) |
| 13 | `SlotMachineBot.java:299` | `parked spin bet=` | Yes — slot bet-size histogram |
| 14 | `SlotMachineBot.java:348` | `sending spin gid/bet/lines/totalStake` | Yes — SlotWindow staked + histogram |

**Do NOT demote (CLAUDE.md DEBUG norms — balance fetch / status transitions):**
- `BettingMiniGameBot.java:319` and `SlotMachineBot.java:166` — "session balance"
  (balance fetch, explicitly DEBUG).
- `SlotMachineBot.java:294` — "balance below spin cost — skipping tick" (balance).
- `SlotMachineBot.java:255` — "spin result b/winnings/sid/balance" — per-spin and
  high-volume, **but carries balance**; straddles the balance-fetch class. Left at
  DEBUG by default; flagged in Open Items as a candidate.
- Race-fallback lines `BettingMiniGameBot.java:595` and `SlotMachineBot.java:321`
  ("no parked decision/bet — re-deriving") — anomaly indicators, effectively
  unreachable in production, **stay DEBUG**.
- `BettingMiniGameBot.java:316` / `SlotMachineBot.java:163` ("triggering deposit")
  — INFO deposit trigger, out of scope.

### CLAUDE.md norms to keep consistent

`CLAUDE.md` "Logging Guidelines": DEBUG explicitly owns "per-bot status
transitions … balance fetch". TRACE is currently "Reserved for wire-level /
packet-level detail. Currently unused." Demoting per-bet **decision** lines to
TRACE requires updating both bullets so code and norms stay aligned (as
AGGREGATED_SESSION_LOGGING did for the raw-frame demotion).

## Per-aspect readiness / mapping

| Aspect | Readiness | Notes |
|---|---|---|
| Option available at bet tap | ready | `decision.optionId()` in scope at `BettingMiniGameBot.java:612`. |
| Extend `recordBet` signature | ready | `recordBet(sid, user, option, amount)`; one call site (`:622`). |
| Option histogram in accumulator | ready | bounded by game option cardinality (<~20 keys); `LongAdder` per key. |
| Amount min/max/avg | ready | avg from existing staked/count deltas; min/max via `LongAccumulator`. |
| Per-window reset semantics | ready | reuse `captureFlushSnapshot` seam; histogram `sumThenReset` per key. |
| Slot bet-size histogram | ready | pass per-line `amount` at `SlotMachineBot.java:340`. |
| Demote 14 per-bet DEBUG lines to TRACE | ready | mechanical; guarded by Part-A coverage check. |
| Martingale escalation state in aggregate | blocked | not on `BetDecision`; needs plumbing → Open Item. |
| CLAUDE.md norm update | ready | edit DEBUG + TRACE bullets. |

## Architecture Decisions

1. **Extend the outbound tap to carry the option, keep it bot-sourced.**
   `SessionAggregationService.recordBet` becomes
   `recordBet(long sid, String bettor, int option, long amount)`; the betting bot
   passes `decision.optionId()` at `BettingMiniGameBot.java:622`. No frame parsing,
   no new data source — the decision is already in hand at the tap. `recordSpin`
   becomes `recordSpin(strategy, bettor, int perLineBet, long totalStake)`; the
   slot bot passes the per-line `amount` (`SlotMachineBot.java:340`) as the
   decision key. Both remain null-tolerant.

2. **Option/position distribution is a per-flush-window (tumbling) histogram.**
   `SessionAccumulator` gains `ConcurrentHashMap<Integer, LongAdder> optionHistogram`.
   `recordBet` does `optionHistogram.computeIfAbsent(option, k -> new
   LongAdder()).increment()`. The key space is the game's option ids (small,
   fixed), so the map is bounded by option cardinality and cannot grow unbounded —
   consistent with the anti-leak invariant. Keys are never removed (bounded set);
   their counters reset per window (AD-4).

3. **Amount summary = min / avg / max over the same window; total staked stays,
   is not re-emitted.** `avg = windowStaked / windowBets`, both already derivable
   from the existing staked/count snapshot deltas (`flushStakedSnapshot -
   stakedBaseline`, `flushSpinSnapshot - spinBaseline`). `min`/`max` add two
   per-window `LongAccumulator(Math::min/max)` fields reset each window. The
   existing `total staked: Z` field on the flush line is unchanged — the amount
   summary is `amount min/avg/max`, not a second total.

4. **Window reset reuses the shipped `captureFlushSnapshot` → render → advance
   seam.** In `captureFlushSnapshot()` (single flush thread), the histogram is
   drained into an immutable `Map<Integer,Long>` via `LongAdder.sumThenReset()`
   per key, and the min/max `LongAccumulator`s via `getThenReset()`. The
   renderer reads only the captured snapshot; a bet arriving mid-flush lands in
   the next window rather than vanishing — identical to the existing bettor/staked
   lost-update handling. **No new baseline fields for the histogram** (reset is by
   drain, not by baseline subtraction), which is simpler than the bettor baseline
   and equally race-free (flush thread is the sole drainer).

5. **Extend the existing `UpdateBet #n` line — do NOT add a companion line
   (recommended).** Rationale: the entire point of this feature is fewer lines;
   a second line per session per 5s doubles the aggregate floor for no structural
   gain. The added shape is compact and greppable on one line:
   ```
   UpdateBet #<n> | new bettors since last: X | total bettors this round: Y | total staked: Z | options: [0]x12 [1]x8 [5]x20 | amount min/avg/max: 100/540/1000
   ```
   (Companion-line vs one-line is an Open Item if the line proves too wide for a
   given game's option cardinality.)

6. **Slots: the per-line bet IS the decision — add a bet-size histogram to
   `SlotWindow`.** The existing `SlotWindow` line already carries `total staked`
   and `spins since last`, but not *what bet sizes* bots chose — that is the slot
   analogue of the option distribution (`FixedBetStrategy` = constant,
   `RandomBetStrategy` = pick from `allowedBetValues`). Reuse AD-2's histogram
   keyed on the per-line bet value, rendered as `bets: [100]x30 [500]x10`. This is
   the minimum needed to make the slot per-spin decision lines redundant so they
   can be demoted (Part B #11-14). Format is an Open Item.

7. **Emit level unchanged: the flush line stays DEBUG.** AGGREGATED_SESSION_LOGGING
   AD-10 set the UpdateBet flush at DEBUG (per-round detail, visible at the
   production DEBUG default). The added distribution/amount shape rides the same
   line at the same level — no new level decision.

8. **Demote the 14 enumerated per-bet decision lines to TRACE; leave balance /
   status / race-fallback / deposit lines at their current level.** Each demotion
   is gated on its "Covered by aggregate?" check (Findings table). Skip-gate and
   onRoundEnd lines (#3,5,8,9,10) are "partial" coverage: non-participation reads
   as `liveBots − bettorCount`, and per-round outcomes read from the EndGame
   summary — the per-bot detail lands at TRACE, not lost. Update `CLAUDE.md`
   Logging Guidelines: DEBUG bullet gains an explicit carve-out that per-bet
   *strategy decision* lines are TRACE (covered by the session aggregate) while
   balance fetch and status transitions remain DEBUG; TRACE bullet is reworded
   from "Currently unused" to include per-bot strategy-decision drill-in.

## Plan

### Phase 1 — Betting/Tai Xiu: option histogram + amount summary in the flush (CODE)

Targets:
- `SessionAccumulator`: add `optionHistogram` (`ConcurrentHashMap<Integer,LongAdder>`),
  window `LongAccumulator` min/max, and captured snapshot fields
  (`Map<Integer,Long> flushOptionSnapshot`, `long flushMinSnapshot`,
  `flushMaxSnapshot`). Extend `recordBet(String bettor, int option, long amount)`
  (`:93`). Extend `captureFlushSnapshot()` (`:234`) to drain histogram
  (`sumThenReset`) + min/max (`getThenReset`) into the snapshot. Add read
  accessors for the renderer.
- `SessionAggregationService.recordBet` (`:323`): add `int option` param, forward
  to accumulator.
- `BettingMiniGameBot.bet()` (`:622`): call `recordBet(currentSid, getUserName(),
  optionId, amount)` (optionId already at `:612`). Tai Xiu inherits unchanged.
- `BettingSessionStrategy.renderFlushLine` (`:34`): append `options: [...]` and
  `amount min/avg/max: ...` from the captured snapshot; leave StartGame/EndGame
  and the existing three flush fields untouched.

Verification (Phase 1):
```bash
JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home mvn -q clean test
```
- Expect: build + tests green.
- New/updated unit tests: (a) feed `recordBet` with options `{0,0,1,5,5,5}` across
  one window → after `captureFlushSnapshot`+render the line shows `[0]x2 [1]x1
  [5]x3` and `amount min/avg/max` matching fed values; (b) a bet arriving after
  the snapshot lands in the **next** window's histogram, not this one (lost-update
  contract); (c) the histogram + min/max are empty/reset at the start of the next
  window; (d) `total bettors`/`total staked` fields are unchanged (no double-emit).

### Phase 2 — Slots: bet-size histogram in the SlotWindow flush (CODE)

Targets:
- `SessionAggregationService.recordSpin` (`:347`): add `int perLineBet`, forward
  to the same `optionHistogram` on the slot accumulator.
- `SlotMachineBot.spin()` (`:340`): pass the per-line `amount` as `perLineBet`.
- `SlotSessionStrategy.renderFlushLine` (`:52`): append `bets: [...]` from the
  captured snapshot; leave `spins since last` / `total staked` / `total win` /
  `jackpot hits` untouched.

Verification (Phase 2):
```bash
JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home mvn -q test
```
- Expect: green; a slot test feeds spins at bets `{100,100,500}` and asserts the
  window line shows `bets: [100]x2 [500]x1`, the histogram resets each window, and
  no StartGame/EndGame lines appear for slots.

### Phase 3 — Demote per-bet decision lines to TRACE + CLAUDE.md (CODE + docs)

Targets (each `log.debug` → `log.trace`, per the Findings table):
- `BettingMiniGameBot.java:624, :665, :661`.
- `RandomBehaviorStrategy.java:103, :75, :83, :111`.
- `MartingaleStrategySupport.java:174, :149, :161, :218, :304`.
- `FixedBetStrategy.java:29`, `RandomBetStrategy.java:28`.
- `SlotMachineBot.java:299, :348`.
- Leave `:595`, `:321` (race fallback), all balance/status/deposit lines, and
  `SlotMachineBot.java:255` (Open Item) at DEBUG.
- `CLAUDE.md` "Logging Guidelines": add the per-bet strategy-decision → TRACE
  carve-out to the DEBUG bullet; reword the TRACE bullet to include per-bot
  strategy-decision drill-in. Cross-reference AGGREGATED_SESSION_LOGGING and note
  this further supersedes the interim per-bot strategy DEBUG levels.

Verification (Phase 3):
```bash
# No per-bet DECISION line remains at debug (balance/status/race lines are allowed):
grep -nE "log\.debug\(\"Bot \{\}: sending bet|strategy parked decision|strategy skipped tick" src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java   # expect: no matches
grep -nE "log\.debug\(.*decide: bet|onRoundEnd" src/main/java/com/vingame/bot/domain/bot/strategy/RandomBehaviorStrategy.java src/main/java/com/vingame/bot/domain/bot/strategy/martingale/MartingaleStrategySupport.java   # expect: no matches
grep -nE "log\.debug\(.*chooseBet" src/main/java/com/vingame/bot/domain/bot/strategy/slot/FixedBetStrategy.java src/main/java/com/vingame/bot/domain/bot/strategy/slot/RandomBetStrategy.java   # expect: no matches
grep -nE "parked spin|sending spin gid" src/main/java/com/vingame/bot/domain/bot/core/SlotMachineBot.java | grep "log.debug"   # expect: no matches
JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home mvn -q test
```
- Expect: no matches above; tests green.

## Implementation Notes / Concerns

- **Never block a netty/scenario thread.** The histogram is `computeIfAbsent`
  over a bounded key set + `LongAdder.increment()` — lock-free. `sumThenReset` /
  `getThenReset` run only on the single flush thread inside `captureFlushSnapshot`.
- **`sumThenReset` is not strictly atomic across cells** (a concurrent
  `increment` may land in either window). This is the same tolerance the existing
  staked/bettor snapshot already accepts; the arrival simply falls into the
  adjacent window. Acceptable — the Micrometer counters remain authoritative.
- **Histogram key space is bounded by game option cardinality** (BauCua ~6, Tai
  Xiu ~2-3). Keys persist across windows (only counters reset), so the map size is
  fixed per game — no unbounded growth, consistent with the shipped anti-leak
  invariant. No key eviction needed.
- **avg without a divide-by-zero**: a window with zero bets (a flush during
  PAYOUT) must render `amount min/avg/max: -` or omit the segment, not divide by 0.
- **Line width**: with a wide option set the one-line format (AD-5) can get long.
  If a game surfaces >~10 options the companion-line fallback (Open Item) applies.
- **Skip-gate demotions (#3,5,8) lose the per-tick skip *reason* at DEBUG.** The
  aggregate does not count skips; non-participation is inferred as `liveBots −
  bettorCount` on the flush line. If operators need skip-reason visibility at
  DEBUG, keep #3/#5/#8 at DEBUG — flagged in Open Items.
- **Tai Xiu** rides `BettingMiniGameBot.bet()` unchanged, so it gets the option
  histogram for free; verify its option ids render sensibly in the histogram.

## Open Items

- **One-line vs companion-line flush format** (AD-5). Recommended one line;
  needs sign-off, or a threshold rule for when to split on wide option sets.
- **Martingale escalation state in the aggregate** (#9,#10). "How many bots are in
  escalation vs base bet" is **not** on `BetDecision` — it lives in per-bot
  `MartingaleStrategySupport.currentBet`/`minBet`. Surfacing it requires new
  plumbing (e.g. a flag on the tap or a widened `BetDecision`). Deferred as a
  data-plumbing item; do not build it into Phases 1-3. Needs user decision on
  whether the escalation signal is worth the invasive change.
- **Slot bet-size histogram format** (AD-6) — `bets: [100]x30` vs percentage vs
  distinct-value list; needs product input on what a meaningful slot decision
  summary is.
- **Skip-gate lines #3/#5/#8** — demote to TRACE (recommended, cleaner volume) vs
  keep DEBUG (retain skip-reason drill-in at the production default). Needs
  operator preference.
- **`SlotMachineBot.java:255` spin-result line** — per-spin, high-volume, but
  carries balance (balance-classified DEBUG). Demote to TRACE (win is now in
  SlotWindow) vs keep DEBUG. Needs decision.

## Verification

On-server checks the Releaser runs on Bot-1 staging after deploy. Assumes a
betting group (BauCua/Tai Xiu) and a slot group are startable; use their
`<groupId>` / a live `<sid>`. Log source is container stdout / `console.log`.

1. **Flush line now carries the decision distribution + amount summary**, at the
   DEBUG default, over a 30s window of one active betting session:
   ```bash
   timeout 30 grep "UpdateBet #" console.log | grep <sid> | tail -1
   ```
   Expect: one line matching
   `^.*UpdateBet #[0-9]+ | .* | options: (\[[0-9]+\]x[0-9]+ ?)+ | amount min/avg/max: [0-9]+/[0-9]+/[0-9]+`,
   with the summed histogram counts ≈ the group's bets in that 5s window and
   `min ≤ avg ≤ max`.

2. **Slot window line carries the bet-size histogram**, over 30s of a slot group:
   ```bash
   timeout 30 grep "SlotWindow" console.log | grep <slotGroupId> | tail -1
   ```
   Expect: one line containing `bets: [<value>]x<count>` segments summing ≈ the
   `spins since last` count.

3. **Per-bot decision lines are silent at DEBUG.** Over 60s with both groups
   running at the DEBUG default:
   ```bash
   timeout 60 grep -cE "sending bet option=|strategy parked decision|\.decide: bet |chooseBet: amount=|parked spin bet=|sending spin gid=" console.log
   ```
   Expect: `0`.

4. **Per-bot decision lines reappear at TRACE (drill-in preserved):**
   ```bash
   curl -s -X POST localhost:8080/actuator/loggers/com.vingame.bot \
     -H 'Content-Type: application/json' -d '{"configuredLevel":"TRACE"}'
   timeout 20 grep -cE "sending bet option=|\.decide: bet " console.log
   curl -s -X POST localhost:8080/actuator/loggers/com.vingame.bot \
     -H 'Content-Type: application/json' -d '{"configuredLevel":"DEBUG"}'
   ```
   Expect: HTTP 204 on both POSTs; `> 0` decision lines within 20s at TRACE.

5. **Balance/status lines still present at DEBUG (not over-demoted):**
   ```bash
   timeout 30 grep -cE "session balance|below spin cost|below minimum" console.log
   ```
   Expect: `> 0` (these remain DEBUG per CLAUDE.md norms).

6. **Total volume dropped toward the aggregate floor.** Compare a 60s total line
   count for the running groups before (pre-deploy jar) vs after, at the DEBUG
   default:
   ```bash
   timeout 60 wc -l < console.log
   ```
   Expect: post-deploy count is a fraction of pre-deploy — the per-bet decision
   classes (Motivation table, ~24k+ lines/min) collapse to the aggregate floor
   (StartGame + ~12 UpdateBet/min + EndGame per session), i.e. the remaining ~75%
   volume is removed at the DEBUG default.

If a betting-group and slot-group are both unavailable on staging, this feature
has no on-server verification beyond the universal smoke test plus the build/test
gates in each phase.
