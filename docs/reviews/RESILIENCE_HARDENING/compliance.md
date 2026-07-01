# Compliance — RESILIENCE_HARDENING

Branch: `feat/resilience-hardening`
Plan reviewed: `docs/plans/RESILIENCE_HARDENING.md`
Diff reviewed: `git diff main..feat/resilience-hardening`

Commits on branch (oldest first):
- `6c53f9d` fix(logging): demote OutputPrinter wire logging from INFO to DEBUG/TRACE (P0a)
- `23ec252` fix(reconnect): cap reconnect cycles so a wedged bot dies instead of looping (P1)
- `f1ad299` feat(resilience): P0b restart policy + clean OOM exit

Files touched (whole branch): `Dockerfile`, `docker-compose.yml`,
`src/main/java/com/vingame/bot/domain/bot/core/Bot.java`,
`src/main/java/com/vingame/bot/domain/bot/util/OutputPrinter.java`,
`src/test/java/com/vingame/bot/domain/bot/core/BotReconnectTest.java`.

## Verdict

PASS (COMPLIANT)

The diff faithfully implements every CODE phase the branch claims (P0a, P1, P0b).
The infra-only phases (P0c, P2, P3) are correctly absent — deferred to the
operator, not silently dropped. No scope creep, no missed sub-steps, no drift
requiring send-back, and no technical error in the plan that the implementation
revealed (no PLAN_AMENDED needed).

## Phase-by-phase

### Phase P0a — Logging amplifier (CODE)
Status: implemented

`OutputPrinter.java` changed exactly as specified:
- `defaultOutputPrinter` `log::info` → `log::trace` (raw wire frame → TRACE, Decision 9).
- `prettifiedOutputPrinter` `log::info` → `log::trace`.
- `debugOutputPrinter` `log.info("User {}: {}", …)` → `log.debug(...)` (per-message → DEBUG).
- Javadoc sub-step honored: the block that described the `User <name>:` line was
  updated to state the frames now emit at DEBUG and only surface under a
  `com.vingame.bot` DEBUG drill-in.

Plan's static check reproduced: `git grep -nE "log\.info|log::info"` against
`OutputPrinter.java` on the branch returns **no matches** (exit 1) — matches the
plan's "expect no matches" gate.

Caller check (plan sub-step) done: the only callers of the demoted printers are
`BettingMiniGameBot.java:715` and `SlotMachineBot.java:397`, both using
`debugOutputPrinter` for per-frame wire output. Neither relies on these lines
appearing at INFO as an operational signal, so no signal needed relocating to an
explicit INFO log. Consistent with CLAUDE.md logging norms.

### Phase P1 — Bound the reconnect / close the leak (CODE)
Status: implemented

Matches Decisions 2 and 3 and the plan's step list:
- `private static final int MAX_RECONNECT_CYCLES = 10;` added in the constants
  block of `Bot.java` (alongside `BACKOFF_SECONDS` / `RECONNECT_CONFIRM_SECONDS`),
  with a comment tying it to Decision 2/3 and Gap A/B.
- `runWsReconnectLoop` now carries a `cycle` counter. At the point where the code
  previously did an unconditional `attempt = 0` after `performReauth()`, it first
  does `cycle++`; once `cycle >= MAX_RECONNECT_CYCLES` it logs
  WARN `"Bot {}: giving up after {} reconnect cycles — marking DEAD"`, calls
  `transitionStatus(BotStatus.DEAD)`, `reconnecting.set(false)`, and returns —
  exactly the terminal behavior the plan prescribed.
- `performReauth()`'s existing DEAD-on-throw is preserved unchanged (auth-fail case
  still terminates); the new cap covers the WS-keeps-dropping / pruning case (Gap A).
- Decision 3 (wedged-RECONNECTING must become DEAD so it feeds
  `deadBotGroupThreshold`) is satisfied purely by the cap; `BotGroupBehaviorService`
  is correctly left untouched. The plan explicitly said the group circuit-breaker
  hardening was optional and unneeded given Decision 2 — the dev documented this in
  the constant's comment rather than adding code. Correct call.

**Cap is un-bypassable, including via the watchdog re-entry path the plan flagged.**
Both entry points into the worker are covered:
- WS-disconnect path: `onWsDisconnected` → `runWsReconnectLoop()` → `runWsReconnectLoop(0)`.
- Watchdog path: `triggerFullReconnect` → `runAuthThenWsLoop()` performs one
  immediate re-auth, then tail-calls `runWsReconnectLoop(1)` — it enters the loop at
  `cycle=1` so the re-auth it already consumed counts against the cap. This is the
  plan's "mirror the cap in `runAuthThenWsLoop`" sub-step, realized by parameterizing
  the loop rather than duplicating cap logic — cleaner and functionally equivalent,
  within plan intent (not drift).

Re-entry during an active loop cannot reset the cycle budget: both `onWsDisconnected`
and `triggerFullReconnect` short-circuit on the `reconnecting` CAS guard, which is
only cleared on genuine reconnect success or on DEAD. A wedged bot (post-connect
`isOpen()` false) never clears the guard, so `cycle` climbs monotonically to the cap.
Resetting the budget after a *successful* reconnect is correct recovery behavior, not
a bypass.

Metric semantics preserved (plan step 2): no `bot_reconnects_total` increment was
added in the loop or the cap branch; the cap marks the bot DEAD, which is not a
reconnect event. The existing "one increment per reconnect event" comments were
extended to note the cap does not re-increment.

Test coverage delivered as the plan anticipated ("Dev adds this test"):
`BotReconnectTest.java` includes `ReconnectCycleCapTests` asserting a bot whose WS
never holds but whose re-auth always succeeds converges to `DEAD`, clears the guard,
and calls `authenticate()` exactly 9 times (cycles 1–9 re-auth; cycle 10 gives up
before re-auth) — proving termination is the cap, not an auth failure. A `@Timeout`
guards against the pre-fix infinite loop. (Depth/quality of tests is QA's call; noted
here only as evidence the P1 behavior is exercised.)

### Phase P0b — Restart policy + clean OOM exit (CODE portion)
Status: implemented

- `Dockerfile` ENTRYPOINT gains `"-XX:+ExitOnOutOfMemoryError",` alongside the
  existing `-XX:MaxRAMPercentage=75.0` (Decision 5).
- `docker-compose.yml` `bot-manager` service gains `restart: unless-stopped`
  (Decision 4 — `unless-stopped`, not `always`, as specified).
- The operator redeploy step (P0b.3) is correctly not a repo change.

**Sequencing constraint honored.** Commit timestamps/order confirm P0b
(`f1ad299`, 12:24) landed *after* P1 (`23ec252`, 12:21), which landed after P0a
(`6c53f9d`, 12:15). The restart policy is introduced only in a commit that already
contains the bounded reconnect loop — satisfying the plan's load-bearing rule that
`restart: unless-stopped` must not precede P1.

### Phases P0c, P2, P3 — Infra (deferred)
Status: out-of-scope for this branch — correctly deferred, not dropped

No Loki config file, no `prometheus/alert.rules.yml`, no `daemon.json` /
per-service `logging:` blocks, no observability-project decoupling appear in the
diff. This matches the plan, which labels these operator/infra steps (P0c sudo-flagged,
P2 alerting, P3 optional). Their absence from a CODE branch is expected and compliant.

## Drift

None.

## Out-of-scope changes

None. The five touched files are exactly the P0a/P1/P0b targets plus the P1 test the
plan called for. No unrelated production code, no other planning docs, no infra files.

## Amendments to the plan

None. The plan's stated file:line targets, decisions, sequencing, and sub-steps
(Javadoc update, `runAuthThenWsLoop` cap mirroring, no-double-count metric, no
`BotGroupBehaviorService` change) are all borne out by the implementation. No
falsifiable technical assumption in the plan was contradicted by the codebase, so no
PLAN_AMENDED is warranted.
