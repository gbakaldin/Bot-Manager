# QA — RESILIENCE_HARDENING

**Verdict:** PASS
**Build:** `mvn clean install` → 1099 tests, 0 failures, 0 errors (1097 pre-existing + 2 added)

## Scope reviewed

`git diff main..feat/resilience-hardening` — three code phases:

- **P0a** — `OutputPrinter.java`: `defaultOutputPrinter`/`prettifiedOutputPrinter` INFO→TRACE, `debugOutputPrinter` INFO→DEBUG.
- **P1** — `Bot.java`: `MAX_RECONNECT_CYCLES = 10` absolute reconnect-cycle cap; `runWsReconnectLoop(int startCycle)`; `runAuthThenWsLoop` re-enters at `cycle=1`.
- **P0b** — `Dockerfile` `+ -XX:+ExitOnOutOfMemoryError`; `docker-compose.yml` `+ restart: unless-stopped`.

## Tests added / updated

- `src/test/java/com/vingame/bot/domain/bot/core/BotReconnectTest.java`
  - `ReconnectCycleCapTests#watchdogReentryCannotBypassCap` — the watchdog route (`runAuthThenWsLoop` → `runWsReconnectLoop(1)`) converges to DEAD after exactly 9 `authenticate()` calls (1 initial + 8 in-loop). Pins that the initial re-auth counts as cycle 1; a cycle-0 re-entry regression would show 10 and is caught.
  - `ReconnectCycleCapTests#shouldRecoverWithinCapWithoutDying` — a bot that fails a full back-off round + one re-auth cycle then holds recovers cleanly (not DEAD, guard cleared, one re-auth), proving the cap does not prematurely kill a recoverable bot.

## Coverage of the diff

- `Bot.java` cap (Gap A: WS-drops/re-auth-succeeds) ← `ReconnectCycleCapTests#shouldGiveUpAndDieWhenWsNeverHoldsButReauthSucceeds` (existing) — DEAD within cap, guard cleared, exactly 9 re-auths prove the cap (not an auth failure) terminated.
- `Bot.java` watchdog re-entry cannot bypass cap ← `watchdogReentryCannotBypassCap` (added).
- `Bot.java` recovery within cap not prematurely DEAD ← `shouldRecoverWithinCapWithoutDying` (added); immediate-success case ← `shouldSucceedOnFirstAttempt` (existing).
- `Bot.java` `performReauth()` throw still DEAD immediately ← `PerformReauthTests#shouldMarkDeadOnFailure` (existing).
- `Bot.java` `reconnecting` guard cleared on give-up ← asserted in all three cap tests.
- `OutputPrinter.java` P0a demotion ← static check: `grep -nE "log\.info|log::info" OutputPrinter.java` returns no matches (plan P0a verification). Not unit-tested — log-level assertions are low-value/brittle; the grep is the intended and sufficient check.

## Determinism / robustness assessment of the existing test

Sound. `FastBot` overrides `sleep(long)` to record-only (no real sleep), so the `{5,10,30,60...}s` back-off never actually blocks — the full cap run (10 cycles × 7 attempts) executes in single-digit milliseconds. `@Timeout(10, SECONDS)` on the cap tests fails fast if the pre-fix infinite loop ever regresses. No real network (all clients Mockito-mocked), no threads spawned (private loop methods invoked directly via reflection, bypassing the `Thread.ofVirtual()` dispatch), no shared state between tests (fresh `FastBot` per `@BeforeEach`). The cap test's `times(9)` assertion is a precise discriminator: it proves termination is driven by the cycle counter reaching the cap, not by an incidental auth failure.

## Gaps

- **P0b (Dockerfile / docker-compose)** — infra-only, not unit-testable in this suite. Correctness verified by inspection: `-XX:+ExitOnOutOfMemoryError` added to ENTRYPOINT; `restart: unless-stopped` (not `always`, per Decision 4) on `bot-manager`. Deferred to Releaser's on-host checks (plan Verification §3: `docker inspect RestartPolicy`, `/proc/1/cmdline`, kill-test).
- **P0a** — verified by static grep only (see above); no behavioural test.
- **Group circuit-breaker (Gap B)** — no code change (Decision 3: capped bots become DEAD and feed the existing `deadBotGroupThreshold` unchanged); nothing new to test here. The cap tests assert the DEAD transition + guard clear that make a capped bot countable.

## Failures

None.
