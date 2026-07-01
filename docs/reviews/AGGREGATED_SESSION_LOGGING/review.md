# Code Review — AGGREGATED_SESSION_LOGGING

Branch: `feat/aggregated-session-logging`
Reviewed diff: `git diff main..feat/aggregated-session-logging`

## Verdict

PASS

PASS = no `bug` or `security` findings; the smells below are advisory. The
highest-stakes property (bounded session map / no unbounded leak) holds under
every insert path I traced.

## Anti-leak audit (highest priority — verified)

Every path that inserts into `sessions` is covered by at least one eviction, and
the map returns to empty when idle:

| Insert path | Eviction that reclaims it |
|---|---|
| `onSessionStart` (`putIfAbsent`, betting/Tai Xiu) | EndGame → `ended=true` → grace-then-evict (`GRACE_NANOS`); or TTL sweep if EndGame never arrives (server pruning / disconnect / group stopped mid-round); or `evictGroup`; or `MAX_SESSIONS` cap |
| `recordSpin` (`computeIfAbsent`, slot sentinel `Long.MIN_VALUE`) | never marks `ended` (by design) → TTL sweep on 60s idle, `evictGroup` on stop, or cap. Exactly one entry per `(group,gameId)` while spinning — bounded |
| Rapid sid churn | one entry per live sid; bounded by rounds-within-TTL, cap backstop at 10k |

Stop/teardown wiring checked:
- `stop(id)` → `evictGroup(id)` after `stopAllBots`. ✓
- `start(...)` failed-start `finally` → `evictGroup(failedRuntime.getGroupId())`. ✓
- `restart(id)` → `stop(id)` (evicts) then `start(id)`. ✓
- `handleBotGroupDeath` does **not** evict, but a DEAD group's bots stop feeding,
  so its entries go idle and the 60s TTL sweep reclaims them. This is the correct
  backstop and is acceptable (see Notes).

Slot sentinel: `Long.MIN_VALUE` cannot collide with a server sid (always
positive), and `gameId` is part of the key, so distinct slot games stay in
distinct windows. ✓

`SessionAccumulator` footprint is O(distinct bettors) — a bounded key-set plus
fixed-size `LongAdder`s, no per-bot map. ✓

## Findings

### [smell] Flush loop has no per-session exception containment
`src/main/java/com/vingame/bot/infrastructure/observability/SessionAggregationService.java:172` (`flushOnce`)

`runFlush` wraps `flushOnce` in try/catch so a throw cannot kill the fixed-rate
task — good. But inside `flushOnce` the per-entry body (the evict decision and
`emitFlush`) is **not** individually guarded. If any single session's
`emitFlush`/`renderFlushLine` throws, the exception unwinds the whole
`for (entry : sessions.entrySet())` loop, so:

1. every session *after* the throwing one in iteration order is skipped for that
   tick — including its **TTL/grace eviction check**, which is the anti-leak sweep;
2. `enforceSizeCap()` at the end of the loop is also skipped for that tick.

The class javadoc (lines 144, 158–170) presents this loop as the load-bearing
anti-leak sweep and claims the task "never lets an exception kill" it — but the
containment stops at task-survival, not at per-session progress. In practice the
renderers only concatenate `long`s and null-tolerant strings, so a throw is
unlikely *today*, and growth is still bounded by the insert-time cap plus the
throwing entry self-healing after 60s TTL (its evict check runs before its
`emitFlush`). So this is a robustness/defensiveness gap, not an active leak —
hence `smell`, not `bug`. Still, it is the exact sweep this feature exists to
protect, and it is the explicit containment guarantee the review calls for.

Fix shape: wrap the per-entry work in a try/catch that logs at WARN and
`continue`s, so one poisoned session can neither skip its neighbours' eviction
nor the size-cap backstop:

```java
for (var entry : sessions.entrySet()) {
    try {
        // evict-or-emit for this entry
    } catch (Exception e) {
        log.warn("flush error for session {}: {}", entry.getKey(), e.getMessage(), e);
    }
}
enforceSizeCap();
```

### [smell] Baseline advance re-reads counters after render → lost-update undercount
`src/main/java/com/vingame/bot/infrastructure/observability/SessionAggregationService.java:210-217` (`emitFlush`)

`renderFlushLine` reads `bettorCount()`/`totalStaked()` (betting) or
`betEventCount()` (slots) to compute the "since last" delta, and then
`advanceBaseline(acc.bettorCount(), acc.totalStaked())` /
`advanceSpinBaseline(acc.betEventCount())` **re-read** the same counters. Between
the render read and the advance read, a concurrent `recordBet`/`recordSpin` on a
netty/virtual thread can bump the counter. The baseline is then advanced to the
higher value, so those in-between arrivals are reported in *neither* this tick's
delta (rendered before they arrived) *nor* the next tick's (baseline already
includes them) — a lost update.

This is a monitoring-accuracy issue only (cumulative "total staked/win/bettors"
stays correct; only the per-tick "since last" delta undercounts), so `smell`.
The single-flush-thread reasoning in the comment is valid for *writer*
exclusivity of the baseline, but it doesn't address the read/re-read skew against
the concurrent feed threads.

Fix shape: snapshot the counters once, before rendering, and advance the baseline
to that same snapshot (arrivals during render then fall into the next tick's
delta rather than vanishing):

```java
int bettors = acc.bettorCount();
long staked = acc.totalStaked();
long spins  = acc.betEventCount();
// render (ideally from the snapshot), then:
acc.advanceBaseline(bettors, staked);
acc.advanceSpinBaseline(spins);
```

### [style] Raw frame "sample" is logged at INFO
`src/main/java/com/vingame/bot/infrastructure/observability/SessionAggregationService.java:264,369`

`onSessionStart` and `onSessionEnd` emit `log.info("{} | sample: {}", line,
sample)` where `sample` is the raw StartGame/EndGame frame
(`String.valueOf(msg)`). This reintroduces raw wire content at INFP — the exact
content the feature demotes to TRACE in `OutputPrinter` — albeit as a single
sampled frame per round per group (lazy, first-seen only), not a per-bot flood.
It is deliberate and small, and the frames carry no secrets (game state, not
tokens), so this is a style note rather than a regression. Consider whether the
sample belongs at DEBUG so the default-INFO view stays purely semantic; if it
stays at INFO, it is within the (now updated) CLAUDE.md norm.

## Notes

- **CLAUDE.md was updated in this branch** to explicitly bless the per-round
  StartGame/EndGame summaries at INFO (~2 INFO lines/round/active group) as "the
  deliberate trade for killing the per-frame flood," so the INFO placement of the
  lifecycle lines is consistent with the (revised) norm rather than a violation.
  The 5s UpdateBet/slot flush at DEBUG and raw frames at TRACE match the norm.
- **Strategy design is clean**: `SessionAggregationStrategy` keeps all game-type
  knowledge out of the service with zero `instanceof`, the strategies are
  stateless singletons, and the slot vs round-based split (`hasRoundBoundary`,
  never-`ended` slot window) is cohesive. Magic numbers (TTL/grace/cap/interval,
  sentinel sid) are all named `static final` constants with rationale. Good.
- **First-seen guards are race-correct**: `putIfAbsent` elects the StartGame
  logger and `AtomicBoolean.compareAndSet` elects the single EndGame logger; the
  lazy `rawSample` supplier is only invoked on the winning path, so the 99 losers
  never serialize the frame. Value-guarded `sessions.remove(k, v)` is used on both
  the TTL/grace and cap-eviction paths, so a flush racing a re-insert can't drop a
  fresh entry.
- **Winnings feed is correct per game type**: betting/Tai Xiu pass the same
  `payout` value the metrics use (`HasBotWinnings.winningsFor`, which already
  encodes Tai Xiu's `GX−gR`), and `endGameSessionId(msg)` correlates Tai Xiu's
  sid-less frame against the tracked session. Slots feed gross winnings +
  `iJ` jackpot flag into the same window as the spin.
- **EndGame summary is "as of first close"** — the CAS-winning logger reads
  totals that reflect only the bots that recorded their EndGame before it fired.
  This is inherent to the single-logger election and is documented; the metrics
  remain the authoritative totals. Not a defect, but worth the author confirming
  the summary line is understood as approximate.
- **Scheduler lifecycle is clean**: `@PostConstruct` starts a single named
  virtual-thread scheduler, `@PreDestroy` `shutdownNow()`s it, `flushScheduler` is
  `volatile` and null-guarded on stop. No thread leak. Mirrors the existing
  `startHealthMonitoring` pattern.
- **DEAD path**: `handleBotGroupDeath` intentionally leaves the runtime in the map
  and does not call `evictGroup`; the group's entries are reclaimed by the 60s TTL
  sweep once the dead bots stop feeding, and a subsequent operator `/stop` or
  `/restart` evicts immediately. Bounded and correct.
