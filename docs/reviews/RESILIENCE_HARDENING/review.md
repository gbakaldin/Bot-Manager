# Code Review — RESILIENCE_HARDENING

Branch: feat/resilience-hardening
Reviewed diff: `git diff main..feat/resilience-hardening`

## Verdict

CHANGES_REQUESTED

PASS = no `bug` or `security` findings, smells/styles are advisory.
CHANGES_REQUESTED = at least one `bug` or `security` finding.

## Findings

### [bug] Capped/DEAD bot can be resurrected by a late WS disconnect (or watchdog), re-arming the reconnect machinery
`src/main/java/com/vingame/bot/domain/bot/core/Bot.java:483` (cap path), `:402` (`onWsDisconnected`), `:415` (`triggerFullReconnect`)

The cap path marks the bot DEAD and clears the guard:

```java
transitionStatus(BotStatus.DEAD);
reconnecting.set(false);
return;
```

Two problems combine here:

1. It does **not** close the current `client`. `tryReconnectWs` only closes the *previous* client at the start of the *next* attempt (`if (client != null && client.isOpen()) client.close();` at :536). On the final failed attempt no next attempt runs, so the last WebSocket client — with its `onDisconnect` handler wired via `configureClient` (:540 → :395) — is left attached and possibly half-open.

2. `onWsDisconnected` (:402) and `triggerFullReconnect` (:415) guard only on `stopped` and the `reconnecting` CAS. Neither checks `status == BotStatus.DEAD`.

Consequence: after the cap sets `reconnecting=false`, if that lingering socket's `onDisconnect` fires (Netty callbacks are async; the `isOpen()==false` that ended the loop means the socket is closing/flapping), the wrapper `if (!stopped) onWsDisconnected()` runs, CAS-flips `reconnecting` back to `true`, and spawns a **fresh** `runWsReconnectLoop(0)` — reviving a bot the fix declares terminal. The same revive is reachable via a still-running `BettingMiniGameBot` watchdog calling `triggerFullReconnect` (it also only checks `stopped`). This directly contradicts the invariant documented at `transitionStatus` (:352: "BotStatus.DEAD is terminal in current code (no revive path exists)") and the plan's "wedged bot dies instead of looping" goal.

It is not the original unbounded platform-thread leak (each fresh episode re-caps after ~47 min and virtual threads are cheap), but it (a) defeats the terminal-DEAD guarantee that feeds `deadBotGroupThreshold`, (b) re-increments `bot_reconnects_total` on each revival and `incBotFailure` on each re-cap, and (c) leaks the Netty client/channel of a sub-threshold DEAD bot until the whole group is stopped. The revival window is only closed promptly when the group circuit breaker trips and calls `cleanup()` (sets `stopped=true`); a single capped bot below the group threshold stays DEAD-but-revivable indefinitely.

Fix shape: early-return `if (stopped || status == BotStatus.DEAD) return;` in both `onWsDisconnected` and `triggerFullReconnect`, and close `client` on the cap path (and ideally on the `performReauth`-failure DEAD path at :528 too) before returning.

### [style] Javadoc/CLAUDE.md drift on the TRACE vs DEBUG choice
`src/main/java/com/vingame/bot/domain/bot/util/OutputPrinter.java:43-45`

The added Javadoc on `debugOutputPrinter` states the per-message wire frames "are emitted at DEBUG (per CLAUDE.md logging norms)". That is true for `debugOutputPrinter` (:54, DEBUG), but its two siblings `defaultOutputPrinter` (:34) and `prettifiedOutputPrinter` (:107) now emit the same class of wire frames at **TRACE**. The comment reads as if all wire frames are DEBUG. Minor: either scope the sentence to this method or note the raw/pretty variants sit at TRACE. Separately, CLAUDE.md still says "TRACE — ... Currently unused; do not adopt as a verbose-DEBUG junk drawer" — this branch now legitimately populates TRACE with exactly the "wire-level / packet-level detail" TRACE is reserved for, so that CLAUDE.md line is now stale (doc-only, out of scope to edit here, flagged for the author).

## Notes

Things that were checked and are correct — worth recording since they were the stated high-stakes concerns:

- **Cap is genuinely bounded and cannot be bypassed via the entry paths.** `cycle`/`attempt` are method-local (thread-confined, no shared-state race). Max work per episode is `MAX_RECONNECT_CYCLES * BACKOFF_SECONDS.length = 10 * 7 = 70` WS attempts. The no-arg trampoline enters at `cycle=0`; `runAuthThenWsLoop` (which already performed one re-auth) tail-calls `runWsReconnectLoop(1)`, so the watchdog route consumes its re-auth against the same budget — the cap counts total re-auth rounds regardless of entry path. Good.
- **No metric double-count.** The loop never increments `bot_reconnects_total`; the reconnect event is counted once at the originating `onWsDisconnected`/`triggerFullReconnect`. The cap's DEAD transition increments `incBotFailure` (a distinct series), not the reconnect counter. Verified against the comment at :455-459.
- **A genuinely-recovering bot is not wrongly killed.** Every successful reconnect calls `reconnecting.set(false)` and returns; a later disconnect starts a brand-new episode at `cycle=0`. The cap only kills a bot that fails 10 consecutive full back-off cycles (~47 min of uninterrupted failure) within a single episode. That matches the intended "WS keeps dropping but re-auth keeps succeeding" (Gap A) target.
- **Concurrency guard is sound for the single-loop case.** The `reconnecting` CAS in both `onWsDisconnected` and `triggerFullReconnect` guarantees only one reconnect loop runs at a time. `status` and `reconnecting` are `volatile`/`AtomicBoolean`. The only concurrency gap is the post-DEAD revive race in the [bug] above.
- **Test quality is good.** `shouldGiveUpAndDieWhenWsNeverHoldsButReauthSucceeds` pins the exact `authenticate()` count (9 → proves the cap, not an auth failure, terminated) and uses `@Timeout` to catch the pre-fix infinite loop. Consider adding a companion test asserting a post-cap `onWsDisconnected()` does **not** restart the loop, which would lock in the fix for the finding above.
- **P0a demotion loses no operational signal.** All three demoted sites emitted per-frame `User <name>: ...` / raw wire `toString()` lines — pure per-message detail that CLAUDE.md explicitly forbids at INFO. Nothing group-level/lifecycle was at INFO in this file. DEBUG for the MDC-tagged per-bot printer and TRACE for the raw/pretty dumps are both consistent with CLAUDE.md's level definitions.
- **P0b is correct.** `-XX:+ExitOnOutOfMemoryError` is placed as a JVM flag before `-jar` (correct position) and pairs with `restart: unless-stopped`: the JVM exits non-zero on OOM and Docker restarts it, while `unless-stopped` (correctly chosen over `always`) still honors an explicit `docker stop`. Only the intended single line was added to each of Dockerfile and docker-compose.yml; no unintended side effects. Trivial pre-existing nit (not introduced here): Dockerfile still has no trailing newline.
