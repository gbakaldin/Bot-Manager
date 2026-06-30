# Code Review — OBSERVABILITY Phases 4 + 5

Branch: `feat/observability-metrics`
Reviewed diff: `git diff 207851f..07d6e28`
Commit under review: `07d6e28`

## Verdict

**APPROVE WITH NITS** — PASS.

No `bug` or `security` findings. Two `smell`-level observations and one `style` nit.

---

## Findings

### 1. [smell] Redundant `if (metrics != null)` guards in `onEndGame`
`src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java:188,194`

```java
private void onEndGame(...) {
    if (metrics != null) metrics.incBotMessage("endGame");

    // Phase 4 ...
    if (metrics != null) {
        long winnings = getWinnings();
        ...
    }
    ...
}
```

Two adjacent null-checks on the same field. The pre-existing single-line guard at line 188 and the new block-level guard at line 194 read as two unrelated blocks. Behavior is correct (`metrics` is `volatile`-by-virtue-of-final-field-write-once, see Bot.java), but a single guard wrapping the whole block would be cleaner.

Severity: **minor**. Fix shape: merge into one guarded block, ordering the `incBotMessage("endGame")` call first inside it.

### 2. [smell] Allow-list not alphabetized after Phase 5 additions
`src/main/java/com/vingame/bot/infrastructure/observability/BotMdcTagsMeterFilter.java:53-62`

```java
private static final Set<String> AGGREGATE_METER_NAMES = Set.of(
        "bots_managed",
        "bot_groups_running",
        "ws_connections_open",
        "bots_by_status",
        "bots_dead_currently",
        "groups_dead_currently",
        "game_total_winnings_total",
        "game_total_bet_amount_total"
);
```

The original list was not strictly alphabetical either, but new entries appended at the bottom (without grouping by `bots_*` / `groups_*` / `game_total_*`) make the list hard to scan as it grows. The comment block above already calls out the Phase-5-amendment intent — grouping by name family in the literal would reinforce that.

Severity: **nit**. Pure ordering.

### 3. [style] `BOT_JACKPOTS_TOTAL` plural is inconsistent with sibling names
`src/main/java/com/vingame/bot/infrastructure/observability/BotMetrics.java:61`

Existing per-bot count counters are singular-event-style: `bot_bets_placed_total`, `bot_messages_total`, `bot_reconnects_total`, `bot_ws_connections_total`. The new `bot_jackpots_total` (jackpot wins count) follows the same plural-event-noun pattern (`bets_placed`, `messages`, `reconnects`). The companion `bot_jackpot_amount_total` (singular `jackpot`) reads slightly inconsistently next to `bot_jackpots_total`, but matches `bot_bet_amount_total` (singular `bet`). The naming is internally consistent with the established prior pattern.

Severity: **nit**. No change recommended — calling it out only so the reviewer record is explicit.

---

## Wiring correctness — `onEndGame` order of operations

| Step | Line | Operation | Reads round state? | State mutation? |
|------|------|-----------|-------------------|-----------------|
| 1 | 188 | `metrics.incBotMessage("endGame")` | no | no |
| 2 | 195 | `long winnings = getWinnings()` | **yes** | no |
| 3 | 196 | `metrics.incBotWinnings(winnings)` | no | no |
| 4 | 197 | `lastRoundWinnings = winnings` | no | yes (volatile long on `Bot`) |
| 5 | 199 | `long jackpot = getJackpot()` | **yes** | no |
| 6 | 200-202 | `if (jackpot > 0) metrics.incBotJackpot(...)` | no | no |
| 7 | 208 | `if (canCheckTotalWinnings())` capability check | **yes** | no |
| 8 | 209 | `metrics.incGameTotalWinnings(getTotalWinnings())` | **yes** | no |
| 9 | 210 | `metrics.incGameTotalBetAmount(getRoundTotalBetAmount())` | **yes** | no |
| 10 | 214 | `gameState = BettingMiniGameState.PAYOUT` | no | yes (volatile) |
| 11 | 215-218 | `scheduler.shutdownNow(); scheduler = null` | no | yes |
| 12 | 219 | `scheduleWatchdog()` | no | yes |
| 13 | 220 | `onNewSession()` | no | yes (resets session state) |

All payload reads (steps 2/5/7-9) occur **before** any state mutation that could affect them (steps 10-13). Order is correct.

`mdcConsumer(this::onEndGame)` wraps the handler at `BettingMiniGameBot.java:400`, so MDC is populated for all counter increments — the MDC-driven tag enrichment in `BotMetrics.mdcTags()` / `gameTypeTagOnly()` fires correctly on the per-client `netty-ws-message-processor-ws-<userName>` thread.

`metrics` field is `null`-safe: tested explicitly in `shouldNoOpWhenMetricsNull`. Even with `metrics == null`, the state mutations at lines 214-220 still run — bot remains functional with metrics absent.

---

## Cardinality matrix

| Metric | botGroupId | environmentId | gameType | Other | Expected series |
|--------|------------|---------------|----------|-------|----------------|
| `bot_winnings_total` | ✓ | ✓ | ✓ | — | groups × envs × games |
| `bot_jackpots_total` | ✓ | ✓ | ✓ | — | groups × envs × games |
| `bot_jackpot_amount_total` | ✓ | ✓ | ✓ | — | groups × envs × games |
| `game_total_winnings_total` | — | — | ✓ | — | distinct gameTypes (~5-10) |
| `game_total_bet_amount_total` | — | — | ✓ | — | distinct gameTypes (~5-10) |

No `botId`, `botUserName`, `username`, `userId`, or other per-bot tags. AD 5 cardinality cap respected for all five new meters.

`game_total_*` meters: both layers of defense in place — `BotMetrics.gameTypeTagOnly()` strips at register-time, and `BotMdcTagsMeterFilter` allow-list prevents MDC re-stamping via the filter path. Verified by `BotMdcTagsMeterFilterTest.map_gameTotalMeter_withGameTypePreTagged_keepsGameTypeButRejectsBotIdentity`.

Empty-MDC edge case: `gameTypeTagOnly()` returns `Tags.empty()` (line 99-104), counter registers with no `gameType` tag, increment still succeeds. Verified by `incGameTotalWinnings_emptyMdc_omitsGameTypeTag`. Note this means counters can register an untagged series if called from a non-bot thread — acceptable since the call sites are all inside `onEndGame` under `mdcConsumer`, but worth tracking. Not a finding.

---

## Naming-deviation verdict

`getRoundTotalBetAmount()` rename vs. the plan's literal `getTotalBetAmount()`: **correct call**.

`Bot.java:72` declares `protected final AtomicLong totalBetAmount` annotated with `@Getter`, which Lombok exposes as `public AtomicLong getTotalBetAmount()`. A subclass `protected long getTotalBetAmount()` would compile to an unrelated method (different return type triggers a compile error on attempting to override), or — if it had matched the signature — would shadow the lifetime accumulator that `BotHealthDTO`/`BotGroupBehaviorService.java:488` depends on.

Semantically distinct: `Bot.getTotalBetAmount()` = per-bot lifetime cumulative `AtomicLong`; `BettingMiniGameBot.getRoundTotalBetAmount()` = per-round game-wide `long` read from `EndGameMessage`. Renaming the new hook is the right call. Javadoc at `BettingMiniGameBot.java:259-264` documents the rationale.

The plan should be amended to record this rename so per-game subclass authors (Bom/B52/Nohu) implement against the right method name.

---

## Dashboard panel correctness

**bots.json** (panels 13-18):

| ID | Title | PromQL | Verdict |
|----|-------|--------|---------|
| 13 | Bot RTP overall (5m) | `(sum(rate(bot_winnings_total[5m])) / sum(rate(bot_bet_amount_total[5m]))) or vector(0)` | OK. Cold-start guard via `or vector(0)`. |
| 14 | Bot RTP per game | `sum by (gameType) (rate(bot_winnings_total[5m])) / sum by (gameType) (rate(bot_bet_amount_total[5m]))` | OK. No `or vector(0)` here — per-game series go away when both rates are 0, which is acceptable for a per-game breakdown. |
| 15 | Bot winnings rate per game | `sum by (gameType) (rate(bot_winnings_total[5m]))` | OK. |
| 16 | Bot money drain per game | `sum by (gameType) (rate(bot_bet_amount_total[5m]) - rate(bot_winnings_total[5m]))` | OK. Subtraction of rates over the same window is dimensionally consistent. |
| 17 | Bot jackpot wins rate | `sum by (gameType) (rate(bot_jackpots_total[5m]))` | OK. |
| 18 | Bot jackpot amount total | `sum by (gameType) (rate(bot_jackpot_amount_total[5m]))` | OK. Stacked time series, unit `short`. |

**game-server.json** (panels 8-10):

| ID | Title | PromQL | Verdict |
|----|-------|--------|---------|
| 8 | Bot bet share per game | `sum by (gameType) (rate(bot_bet_amount_total[5m])) / clamp_min(sum by (gameType) (rate(game_total_bet_amount_total[5m])), 1)` | OK. `clamp_min(...,1)` guards cold-start NaN. Note: when `game_total_*` denominator is 0, result is `bot_rate / 1` not `0` — that's the bot rate, which could read as a non-zero share against an absent total. In practice this is fine because numerator is also 0 until bots actually start betting, but worth noting for the demo. |
| 9 | Real-player bet share per game | `1 - <panel 8>` | OK as algebraic complement of panel 8. |
| 10 | Real-player RTP per game | `(sum by (gameType) (rate(game_total_winnings_total[5m])) - sum by (gameType) (rate(bot_winnings_total[5m]))) / clamp_min(sum by (gameType) (rate(game_total_bet_amount_total[5m])) - sum by (gameType) (rate(bot_bet_amount_total[5m])), 1)` | OK. Same clamp_min guard. Mathematically correct: subtract bot series from game-aggregate series. Will display correctly only for games whose subclass enables `canCheckTotalWinnings()`. |

All targets reference `{"type": "prometheus", "uid": "prometheus"}`. JSON validates.

**Layout check (bots.json):**
- Pre-existing panels occupy rows y=0..32 (with the Phase-6 dead-currently stats at y=4-8 between top stats row and y=8 timeseries).
- New panel 13 starts at y=32 (h=4, full width).
- Panels 14/15 at y=36 (h=8, half width each).
- Panels 16/17 at y=44 (h=8, half width each).
- Panel 18 at y=52 (h=8, full width).

No overlap with existing panels. No stranded panels.

---

## Scope check

| Touched? | File / area | Expected? |
|----------|-------------|-----------|
| No | `BotMdc.java` | yes, no new MDC keys per plan |
| No | `ObservabilityConfig.java` | yes, no new aggregate gauges per plan |
| No | `Bot.java` | yes; `lastRoundWinnings` field unchanged, now written from `BettingMiniGameBot.onEndGame` |
| No | log4j / Promtail / Dockerfile / pom / compose / `user:` directive | yes, out of scope per plan |
| No | per-game subclasses (`BomBot`, `B52Bot`, etc.) | yes, plan defers these to a follow-up |
| No | LOGGING_PIPELINE_FIX area | yes, separate workstream |
| No | `transitionStatus`, `markAsDead`, `cleanup`, reconnect, watchdog, schedulers | yes, state machine untouched (AD 14) |

`lastRoundWinnings` repurposing: was declared at `Bot.java:74` and never written prior to this branch. Now written from `BettingMiniGameBot.onEndGame:197`. Read sites: `BotHealthDTO.lastRoundWinnings` (field), `BotGroupBehaviorService.java:488` (`bot.getLastRoundWinnings()`), tests in `BotGroupControllerTest`. The DTO contract is preserved — what used to always return `0L` now returns the per-round value (or `0L` for unscaled base-class bots). This is a strict improvement, not a regression.

Scope clean.

---

## Test quality

- `BettingMiniGameBotTest.OnEndGameMetricsTests` (4 tests):
  - Default base class: asserts `incBotWinnings(0)` fires AND `incBotJackpot`/`incGameTotal*` do NOT fire. Uses `InOrder` to assert `incBotMessage("endGame")` precedes `incBotWinnings`.
  - Subclass with non-zero winnings + jackpot: asserts both fire with exact values, `lastRoundWinnings` reflects the read.
  - Subclass with `canCheckTotalWinnings=true`: asserts game-total counters fire AFTER bot-total counters via `InOrder`.
  - `metrics == null`: asserts no NPE and state transitions still occur.
- `BotMetricsTest` (4 new tests): assert tag shape (per-bot tags vs. gameType-only tags), counter sum semantics, empty-MDC behavior.
- `BotMdcTagsMeterFilterTest`: new test asserts `game_total_*` keeps pre-tagged `gameType` and rejects MDC bot-identity bleed-through.

Test contracts are strong (`InOrder`, exact values, negative assertions). Test quality is not a finding per QA scope, but worth noting that the test layer fully exercises the wiring.

---

## Notes

- The capability-flag pattern (5 protected hooks defaulting to no-op) is a clean way to defer per-game protocol parsing without touching every subclass on the same branch. New panels render zero values cleanly today, will pick up real values when subclasses opt in.
- The Javadoc on the new hooks calls out which thread they run on (`netty-ws-message-processor` pool) and the cheap/non-blocking requirement. Good for future per-game implementers.
- AD 5 (cardinality cap) is enforced twice for `game_total_*`: at register-time via `gameTypeTagOnly()` and at filter-time via allow-list. Defense-in-depth is appropriate given the historical pain around Micrometer's pre-filter id caching documented in `BotMetrics.java:23-32`.
- Counter registration on the first 0-value increment (`incBotWinnings(0)` in the base case) is intentional and required so the Grafana RTP panels render `0` instead of "No data" — documented at `BotMetrics.java:196-199`. Good.

---

Review verdict: **PASS** (APPROVE WITH NITS)
Findings: 0 bug, 0 security, 2 smell, 1 style
