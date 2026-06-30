# Compliance — OBSERVABILITY Phases 4 + 5

Branch: `feat/observability-metrics`
Plan reviewed: `docs/plans/OBSERVABILITY.md` (base commit `207851f`)
Diff reviewed: `git diff 207851f..07d6e28`
Single commit on branch: `07d6e28 — Phase 4+5 OBSERVABILITY: bot winnings, jackpot, real-player share`

## Verdict

PLAN_AMENDED

The Phase 4 + 5 implementation is faithful to the spirit of the plan. One real codebase constraint (Lombok-generated `Bot.getTotalBetAmount()` returning `AtomicLong`) makes the plan's literal method name `getTotalBetAmount()` impossible to override on `BettingMiniGameBot`. Dev's chosen rename to `getRoundTotalBetAmount()` is forced by the codebase, more semantically precise, and preserves the public `BotHealthDTO` contract. AD 11 and the Phase 4/5 method list are amended to canonicalize the new name. The `lastRoundWinnings` repurposing is also documented explicitly.

## Per-deliverable table

### Phase 4

| Deliverable | Status | Notes |
|---|---|---|
| `getWinnings()` default `0L` on `BettingMiniGameBot` | PRESENT | Lines 220–227 |
| `getJackpot()` default `0L` | PRESENT | Lines 233–235 |
| `BotMetrics.incBotWinnings(long)` | PRESENT | Uses `mdcTags()` — per-bot tag shape |
| `BotMetrics.incBotJackpot(long)` increments both `bot_jackpots_total` (by 1) and `bot_jackpot_amount_total` (by amount) | PRESENT | Matches plan exactly |
| `bot_winnings_total` counter constant | PRESENT | |
| `bot_jackpots_total` counter constant | PRESENT | |
| `bot_jackpot_amount_total` counter constant | PRESENT | |
| Wiring in `BettingMiniGameBot.onEndGame` | PRESENT (DEVIATED — winnings unconditional) | Winnings call is unconditional rather than gated on `> 0`; user's request explicitly endorses this. `lastRoundWinnings = winnings` also unconditional. Jackpot correctly gated on `> 0`. |
| Phase 6 panel: Bot RTP overall | PRESENT | bots.json id=13 |
| Phase 6 panel: Bot RTP per game | PRESENT | bots.json id=14 |
| Phase 6 panel: winnings rate | PRESENT | bots.json id=15 |
| Phase 6 panel: money drain | PRESENT | bots.json id=16 |
| Phase 6 panel: jackpot rate | PRESENT | bots.json id=17 |
| Phase 6 panel: jackpot amount | PRESENT | bots.json id=18 |

### Phase 5

| Deliverable | Status | Notes |
|---|---|---|
| `canCheckTotalWinnings()` default `false` | PRESENT | Lines 242–243 |
| `getTotalWinnings()` default `0L` | PRESENT | Lines 249–251 |
| `getRoundTotalBetAmount()` default `0L` (renamed from plan's `getTotalBetAmount()`) | PRESENT (DEVIATED — rename, see below) | Lines 263–267; docstring documents the rename |
| `BotMetrics.incGameTotalWinnings(long)` | PRESENT | Uses `gameTypeTagOnly()` |
| `BotMetrics.incGameTotalBetAmount(long)` | PRESENT | Uses `gameTypeTagOnly()` |
| `game_total_winnings_total` counter constant | PRESENT | |
| `game_total_bet_amount_total` counter constant | PRESENT | |
| `gameTypeTagOnly()` private helper | PRESENT | Returns `Tags.empty()` if MDC missing; matches plan |
| Wiring in `onEndGame` gated by `canCheckTotalWinnings()` | PRESENT | Calls unconditional inside the gate (no `> 0` guards on totals). Acceptable: `incGameTotal*(0)` is a no-op increment. |
| `BotMdcTagsMeterFilter` allow-list extended with both `game_total_*` names | PRESENT | Grew from 6 → 8 entries |
| Phase 6 panel: Bot bet share | PRESENT | game-server.json id=8 |
| Phase 6 panel: Real-player bet share | PRESENT | game-server.json id=9 |
| Phase 6 panel: Real-player RTP | PRESENT | game-server.json id=10 |

## AD coverage

| AD | Status | Notes |
|---|---|---|
| AD 5 — `gameType`-only on `game_total_*` | RESPECTED | `gameTypeTagOnly()` reads only `MDC.get(BotMdc.GAME_TYPE)`. Filter allow-list grew. Unit test asserts no `botGroupId` / `environmentId` tags on game-aggregate counters. |
| AD 10 — Per-callsite MDC read; filter is defense-in-depth | RESPECTED | All per-bot counters use `mdcTags()` on the builder. Filter still in place. |
| AD 11 — Naming convention (`bot_*`, `game_*`, `_total` suffix) | RESPECTED (NEEDS_AMENDMENT for method name) | All 5 new counter names match the convention. AD 11 also mandates method name `getTotalBetAmount()` — that name is unachievable due to Lombok's public `Bot.getTotalBetAmount()` returning `AtomicLong`. See plan amendment. |
| AD 13 — Capability-flag plain methods, not strategy/interface | RESPECTED | Five plain `protected` methods on `BettingMiniGameBot` with safe defaults. No `WinningsCapability` interface, no bean lookup. |
| AD 14 — STOPPED ≠ DEAD; no state-machine changes | RESPECTED | Wiring lives strictly inside `onEndGame`. `transitionStatus`, `markAsDead`, `stopAllBots`, `cleanup` untouched. |

## Scope check

PASS. Exactly the 8 expected files touched:
- `src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java`
- `src/main/java/com/vingame/bot/infrastructure/observability/BotMetrics.java`
- `src/main/java/com/vingame/bot/infrastructure/observability/BotMdcTagsMeterFilter.java`
- `grafana/provisioning/dashboards/bots.json`
- `grafana/provisioning/dashboards/game-server.json`
- `src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotTest.java`
- `src/test/java/com/vingame/bot/infrastructure/observability/BotMetricsTest.java`
- `src/test/java/com/vingame/bot/infrastructure/observability/BotMdcTagsMeterFilterTest.java`

No touches to: `Bot.java`, `BotMdc.java`, `ObservabilityConfig.java`, log4j, Promtail, Dockerfile, pom, compose, `user:` directive, per-game subclasses, `LOGGING_PIPELINE_FIX`. Clean.

## Phase 1 / 2 / 3 carry-over check

| Item | Status |
|---|---|
| `BotMetrics` per-bot counters still use `mdcTags()` (Phase 2 contract) | INTACT — 13 callsites verified |
| `ObservabilityConfig` registers aggregate gauges via `MeterBinder` (Phase 7 hotfix) | INTACT — `botAggregateGauges(...)` returns `MeterBinder` bean |
| `BotMdcTagsMeterFilter` `startsWith("bot_")` prefix check still excludes `game_*` (defense-in-depth) | INTACT — line 70 unchanged. Comment added to document Phase 5 nuance. |
| `AGGREGATE_METER_NAMES` allow-list includes Phase 3 `bots_dead_currently` / `groups_dead_currently` | INTACT — both still present, plus the two new Phase 5 entries |

## Naming deviation verdict — `getTotalBetAmount` → `getRoundTotalBetAmount`

AMEND THE PLAN. Verified at `Bot.java:71-72`:

```java
@Getter
protected final AtomicLong totalBetAmount = new AtomicLong(0);
```

Lombok generates a `public AtomicLong getTotalBetAmount()` on `Bot`. A `protected long getTotalBetAmount()` declared on `BettingMiniGameBot extends Bot` is not a valid Java override — the access modifier is more restrictive (protected vs public) AND the return type is incompatible (`long` vs `AtomicLong`). The compiler rejects it outright. The plan's literal name is structurally impossible.

This is a real Architect blind spot, not a Dev preference: the Architect did not check `Bot.java`'s Lombok-generated accessor surface before specifying the method name. Dev's chosen `getRoundTotalBetAmount()` is more semantically precise ("the round's total bet amount across all players" vs. ambiguous "total bet amount") and preserves the public health DTO contract (`Bot.getTotalBetAmount() → AtomicLong` is read by `BotGroupBehaviorService:488`).

Plan amendments below canonicalize the rename in AD 11 and in the Phase 5 method list.

## `lastRoundWinnings` repurposing verdict

DOCUMENT IN PLAN (low-stakes amendment). The original plan body for Phase 4 says:

> The existing `protected volatile long lastRoundWinnings` at line 74 is repurposed — `BettingMiniGameBot.onEndGame` writes to it when winnings > 0. The field already has a `@Getter` and is read by `BotHealthDTO`...

so repurposing is already in the plan. The deviation is that Dev writes `lastRoundWinnings = winnings` UNCONDITIONALLY (not gated on `> 0`). The user explicitly requested unconditional winnings ("winnings unconditionally, jackpot conditional on `> 0`") and that is what shipped. The behavioral consequence — `lastRoundWinnings` correctly reflects 0 in rounds where the bot didn't win — matches the field name's semantics ("last round's winnings") better than a sticky-max read would. No verdict change needed; the amendment below adds one clarifying line so future readers understand the gating choice.

## Plan amendments

1. **AD 11 — replace `getTotalBetAmount` with `getRoundTotalBetAmount`** in the method list, with rationale (Lombok-generated `Bot.getTotalBetAmount() → AtomicLong` collision).
2. **Phase 5 method signature** updated to `getRoundTotalBetAmount()`.
3. **Implementation Notes** — short bullet documenting (a) the rename, (b) the unconditional winnings write to `lastRoundWinnings` (matches field-name semantics).

See `## Amendment — 2026-06-04 (Phase 4 + 5 compliance review)` appended to `docs/plans/OBSERVABILITY.md`.
