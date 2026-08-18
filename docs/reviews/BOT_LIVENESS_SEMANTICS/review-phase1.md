# Code Review — BOT_LIVENESS_SEMANTICS (Phase 1)

Branch: `staging`
Reviewed diff: `git diff 91ab7d8..2e98e4e` (commits `6103e4a`, `2e98e4e`)

Files in scope:
- `bot-engine/src/main/java/com/vingame/bot/domain/bot/core/Bot.java` (+82 / −22)
- `bot-engine/src/test/java/com/vingame/bot/domain/bot/core/BotOrphanClientCloseTest.java` (new, 253 lines)

## Verdict

PASS

No `bug` and no `security` findings. The core change — dropping the `isOpen()` guard, closing
the outgoing client before the field is overwritten, and closing the half-built client on the
failure path — is correct against the library contract, and I verified it against the 3.0.5
sources rather than taking the commit message on trust (`VingameWebSocketClient:502-559`:
one-shot via `isClosing.getAndSet(true)`, runs regardless of connection state, reclaims
scenarios → `SendAsync` schedulers → ping → channel → processor pool). Targeted run of
`BotOrphanClientCloseTest`, `BotReconnectTest`, `BotTest`, `BotDeadSecondsTest` is green.

The findings below are all advisory, but #1 is a strong recommendation to remove production
state before this ships to a money-adjacent reconnect path.

## Findings

### [smell] `lastClosedClient` is production state added for a test assertion, and it does not hold the invariant it documents
`bot-engine/src/main/java/com/vingame/bot/domain/bot/core/Bot.java:79-98`, `:314-323`, `:710-721`

The field's javadoc states the invariant *"exactly one `close()` call per client instance"*. The
implementation does not enforce it, in three independent ways:

1. **`stop()` writes the memo but never reads it** (`:321-322`). Sequence:
   `closeQuietly(A)` from the reconnect loop → `lastClosedClient = A`, `A.close()`. Later
   `cleanup()` → `stop()` → `A.close()` again, unconditionally. Two calls, same instance.
   The new test pins only the opposite order (`cleanupThenTerminalCloseClosesOnce`), which is
   the direction that happens to work.
2. **`triggerFullReconnect` closes without touching the memo** (`:570-572`, see finding #3).
   On the watchdog path — the common production reconnect trigger — the channel is typically
   still open, so `:571` closes the client, and the subsequent
   `runAuthThenWsLoop` → `tryReconnectWs` → `closeQuietly(this.client)` closes the *same*
   instance a second time because the memo was never updated. So the invariant is falsified on
   precisely the path the field was introduced to account for.
3. **The check-then-act is not atomic.** `if (c == lastClosedClient) return; lastClosedClient = c;`
   is a plain read-modify-write on a `volatile`. `client` is closed from the reconnect virtual
   thread, the watchdog thread, the group teardown thread and the periodic-logout thread; a
   `closeQuietly(A) | closeQuietly(B) | closeQuietly(A)` interleaving double-closes A. It is
   also a single-slot memo, so even single-threaded `A, B, A` double-closes A.

Because the library's `close()` is one-shot, none of the above causes a runtime problem — which
is the point: **the field buys nothing at runtime.** It is a test affordance, and only two
assertions actually depend on it (`failedAttemptClosesItsOwnClientOnceOnly:206` and
`cleanupThenTerminalCloseClosesOnce:219`); the other six close assertions pass without it. Both
of those assert "our code does not call an idempotent library method twice", which is not a
property worth carrying permanent shared mutable state for.

Costs it does carry:

- **It retains a hard reference to the very object the change is trying to orphan-proof.** One
  closed `VingameWebSocketClient` per `Bot`, for the life of the bot — each still referencing its
  Netty channel, scenario list and processor-pool objects, and the scenarios still close over the
  `Bot`. Bounded at 1 per bot, so not a leak in the incident sense, but the plan's own framing of
  the bug is "a heap + log-volume leak", and this hands part of the heap half back.
- It is a fourth piece of close-related state (`client`, `lastClosedClient`, the library's
  `isClosing`, and the `isOpen()` derivation) in a file where lifecycle state is already the
  hard part.
- It makes `closeQuietly` conditional again — the same shape as the `isOpen()` guard just
  removed, differing only in the predicate. A future reader has to re-derive why one conditional
  close is safe and the other was the bug.

**Preferred fix shape:** delete `lastClosedClient`, revert `stop()` to its previous two lines,
and let `closeQuietly` be an unconditional null-guarded close. Express the test intent as
"every client instance is closed" (`verify(x, atLeastOnce()).close()`), which is the property
with runtime meaning. If the exactly-once accounting is genuinely wanted, make it real:
`AtomicReference<VingameWebSocketClient>` with `getAndSet(c) != c` as the gate, route `stop()`
and `triggerFullReconnect` through the same helper, and store a
`WeakReference` so the closed client stays collectable.

### [smell] Close failures are swallowed at DEBUG without the throwable, three levels below the sibling handler for the same failure
`bot-engine/src/main/java/com/vingame/bot/domain/bot/core/Bot.java:715-720`

`closeQuietly` catches `Exception` and logs `e.getMessage()` at DEBUG. A `close()` that throws
means scenario executors, the ping scheduler, the channel and/or the processor pool were not
reclaimed — i.e. exactly the leak this commit exists to prevent, silently. Two problems:

- **Level.** `cleanup()` at `:288-290` handles the identical failure (a throw out of `stop()` →
  `client.close()`) with `log.error("Error stopping bot {} during cleanup", userName, e)`. The
  new helper reports the same event three levels quieter, so the same failure is ERROR or DEBUG
  depending on which entry point reached it. Per the CLAUDE.md table a failed close is a
  "recoverable anomaly that warrants investigation if it persists" = WARN.
- **No stack trace.** `e.getMessage()` on a close failure originating inside Netty or an
  executor shutdown is frequently null or uninformative, and there is no other record. Pass `e`
  as the last argument.

Note the library itself catches almost everything inside `close()` (`:509-558`), so a throw
escaping it is genuinely exceptional and will not be noisy at WARN. Suggested:
`log.warn("Bot {}: error closing WS client {}", userName, System.identityHashCode(c), e);`

Also minor: the catch is on `Exception`, so an `Error` from executor shutdown propagates out of
`restart()` (a public method invoked from the periodic-logout path). `Throwable` would match the
"quietly" contract the method name advertises.

### [smell] The fifth close site is left guarded, making the accounting inconsistent and delaying the close on the watchdog path
`bot-engine/src/main/java/com/vingame/bot/domain/bot/core/Bot.java:570-572`

Dev's reasoning — "no longer a leak, because every exit path from `triggerFullReconnect` now
closes unconditionally" — holds for *leakage*, and I traced every exit to confirm it:
`runAuthThenWsLoop` → `performReauth` failure → `closeClientQuietly()` (now unconditional), or
→ `tryReconnectWs()` → `closeQuietly(this.client)` (now unconditional); the `if (stopped) return`
exit at `:650` is covered by `cleanup()`, which closes unconditionally as of this diff. So this
is not a leak.

It is still worth fixing, for two reasons the leak argument does not cover:

1. **Latency of the close.** When the channel is dead (`isOpen() == false`) the guard skips, and
   the client is not closed until `performReauth()` returns — an HTTP round trip to the auth
   gateway that, in the failure mode this plan is written against, is the *slow or hanging* one.
   For that window the orphan's `sendAsync` pipeline is still scheduled and still emitting
   `"Cannot send message, not connected"`. Phase 1's whole objective is to kill that emitter
   promptly; here it survives for the duration of a gateway timeout, per reconnect, per bot.
2. **It is the site that breaks the new invariant** (finding #1, item 2). Keeping one guarded
   close alongside a field whose javadoc claims universal single-close accounting is worse than
   either fixing it or not having the field: the file now documents a rule and visibly violates
   it two hundred lines away, and nothing in the test suite covers this path.

The change is one line — `closeQuietly(this.client);` — with no new risk: `triggerFullReconnect`
runs on the watchdog virtual thread (`BettingMiniGameBot:362-366`), never on the Netty I/O loop,
so the library's "must not be called on the Netty I/O thread" constraint (`close()` javadoc
`:499-501`) is satisfied there as it is at the other four sites.

### [smell] `restart()` got the unconditional close but not the failure-path close its structural twin received
`bot-engine/src/main/java/com/vingame/bot/domain/bot/core/Bot.java:300-312`

`restart()` and `tryReconnectWs()` are the same operation (close old → build → configure →
connect → start). `tryReconnectWs` was hardened in this diff so that a throw from
`connect()`/`start()` closes the client it just built, with a comment explaining that "the next
attempt may never come". `restart()` has no try/catch: if `connect()` (`:310`) or `start()`
(`:311`) throws, the freshly built client is left un-closed with its message-processor workers
already running (started in the library constructor).

It is milder than the reconnect case — `this.client` still points at it, so the next
`restart()`/`cleanup()` will close it, and no scenarios were attached yet so it is not a flood
source. But this is the hourly periodic-logout path (`BotGroupBehaviorService:1630`), the caller
only logs the exception (`:1639-1641`), and CLAUDE.md's backlog already records restart throwing
in production ("Restart lifecycle bug", `ValidationException` on the same code path). Given the
diff explicitly set out to leave "no reference dropped un-closed on either path", the asymmetry
looks like an oversight rather than a decision. Either mirror the try/catch or add one line
saying why restart does not need it.

### [smell] `cleanup()` now performs a real, potentially blocking close on a serial teardown loop held under the group lock
`bot-engine/src/main/java/com/vingame/bot/domain/bot/core/Bot.java:279-291`
(consumer: `bot-app/.../infrastructure/runtime/BotGroupRuntime.java:274-280`)

Correct change, worth being deliberate about the consequence. Before the diff, a bot whose
channel was dead skipped `stop()` entirely and cleaned up in microseconds. Now every bot performs
a full `close()`, whose cost is bounded by `BackpressureConfig.DEFAULT_SHUTDOWN_TIMEOUT_MILLIS`
= 5000 ms (`shutdownProcessingExecutor` → `awaitTermination`, plus a second 5 s bound on
`channel.closeFuture().await()` when the channel is still active).

`BotGroupRuntime.stopAllBots` iterates `botInstances.forEach(bot -> bot.cleanup())` **serially**,
and `BotGroupBehaviorService.stop(id)` runs it synchronously on the HTTP request thread while
holding the per-group `ReentrantLock` (`:772-787`). Normally the processor workers are parked in
`messageQueue.take()` and exit on `shutdownNow()`'s interrupt immediately, so this is
milliseconds. The pathological case is a worker mid-handler in a blocking HTTP call —
`onNewSession` → `checkBalance()` / `deposit()` (`BettingMiniGameBot:328-337`) — which does not
necessarily abort on interrupt; then it is up to 5 s per bot, serially, on a 100-bot group.

That is precisely the fleet state operators will be tearing down right after this deploy (plan
Verification step 5 tells the Releaser to `POST /restart` a group). Worth either parallelizing
the cleanup loop over the group's virtual-thread executor, or accepting it knowingly and telling
the Releaser that `/stop` and `/restart` on a wedged group may take noticeably longer than before.
No change requested in this file — flagging so the decision is explicit rather than discovered.

### [smell] The memo is `volatile` but the field it shadows is not
`bot-engine/src/main/java/com/vingame/bot/domain/bot/core/Bot.java:77` vs `:98`

`client` is a plain `protected` field, written by `initialize()`, `restart()` and
`tryReconnectWs()` and read from the Netty I/O loop, the watchdog scheduler, the countdown
scheduler, the health-monitor thread (`isConnected()`), the periodic-logout thread
(`BotGroupBehaviorService:1603`) and the reconnect virtual threads. The diff does not introduce
that race, but it does add a `volatile` memo *of* that field, which draws attention to the
asymmetry: the bookkeeping about the client is safely published while the client reference itself
is not. Concretely, a teardown thread that reads a stale `client` can close the previous instance
and leave the current one running — the exact failure class this phase is closing, via the one
route it does not cover.

`volatile` on `:77` is a one-word change, has no meaningful cost on a reference read, and matches
the treatment the codebase just gave the analogous field in `91ab7d8`
("make `BotGroupRuntime.actualStatus` volatile"). Phase 2 adds another cross-thread reader of
this field (the 30 s liveness sweep), so it will only get more load-bearing.

### [style] `closeClientQuietly()` and `closeQuietly(c)` are one edit away from each other
`bot-engine/src/main/java/com/vingame/bot/domain/bot/core/Bot.java:696-698`, `:710`

Two methods whose names differ by one word, one taking the current client implicitly and one
explicitly, one a pure delegate to the other. `closeClientQuietly()` now has two callers
(`:636`, `:681`) and a ten-line javadoc for a one-line body. Either inline it at both callsites
as `closeQuietly(this.client)`, or rename the pair so the distinction is visible at the callsite
(`closeCurrentClientQuietly()` / `closeQuietly(client)`).

### [style] The same incident narrative is restated five times in one file
`bot-engine/src/main/java/com/vingame/bot/domain/bot/core/Bot.java:79-97`, `:282-284`, `:302-305`, `:692-694`, `:700-708`, `:724-729`

This codebase is deliberately comment-dense and the explanations are accurate and well written,
so this is a low-priority note — but the "isOpen() was false → close skipped → orphan sendAsync
→ `Cannot send message, not connected` flood" story appears in the field javadoc, in `cleanup`,
in `restart`, in `closeClientQuietly`, in `closeQuietly`, in `tryReconnectWs`, and again in the
test class javadoc, at roughly 60 lines of prose for a four-line behavioural change. Five copies
drift; one does not. Suggest keeping the full account on `closeQuietly` (the shared helper) and
reducing the others to a one-line pointer plus the plan reference they already carry.

## Notes

- **The `tryReconnectWs` reordering is safe.** The publication point of `this.client` is
  unchanged (`:734`, still before `configureClient`/`connect`), and the field is never assigned
  `null`, so no reader can observe a null. The genuinely new window is that `this.client` can
  now reference a *closed* client — between `:730` and `:734`, and for the whole backoff sleep
  after a failed attempt (`:747` closes the client `:734` published). I walked every concurrent
  reader: `isConnected()` → `isOpen()` returns `false`, identical to the dead-channel value it
  returned before; `performPeriodicLogout` (`BotGroupBehaviorService:1603`) reads the same
  predicate and skips; `checkBalance()`/`deposit()` read `getClient().getAuthToken()`, and
  `close()` does not clear `authToken` (`VingameWebSocketClient:337-339` sets it, nothing in
  `close()` unsets it), so no NPE; and the closed client's own scenarios and processor pool are
  shut down, so the handlers that would call those methods no longer run. Net effect of the
  reorder is a strictly *shorter* orphan window, not a new hazard.
- **Library contract verified independently.** `close()` is one-shot
  (`isClosing.getAndSet(true)`, `:505`), runs for never-connected clients, and shuts scenarios
  first so every `SendAsync` scheduler dies with them (`:510-527`). `Scenario.shutdown()` →
  `PipelineContext.shutdown` calls `executor.shutdown()` without awaiting (`:247-258`), so the
  per-scenario cost is not additive — only `shutdownProcessingExecutor` and the channel await
  block. The commit message's characterisation is accurate.
- **Test file conventions:** matches the repo closely — same package, same fixture construction
  as `BotReconnectTest.setUp`, `@Nested` + `@DisplayName` phrasing in the same voice, and a
  minimal `TestBot` subclass mirroring `FastBot`. Reflection to reach `tryReconnectWs` /
  `closeClientQuietly` is the established idiom in this package (`BotReconnectTest.invokePrivate`),
  so no objection. Two nits, both cosmetic: `invokeTryReconnectWs` and `invokePrivate` duplicate
  the same `InvocationTargetException` unwrap and could share one helper, and the `isOpen()`
  stubs on the `fresh`/`second`/`next` mocks are now unused by production code (harmless here
  because the class does not use `MockitoExtension` strict stubs).
- Logging in the diff uses the SLF4J `{}` form throughout, logs no tokens, and keeps the
  `System.identityHashCode(...)` convention already used by `stop()`. No security surface is
  touched by this change.
- One question for the author, tied to finding #1: was the "exactly once per client" assertion a
  requirement from the plan, or an artefact of how the test was written? Plan step 3 says
  "the library's one-shot guard makes double-close harmless, but assert our call count is 1 per
  client" — which reads as a test-design instruction, not a production-invariant requirement. If
  that is the reading, the field can go and the plan text is satisfied by the seven assertions
  that do not need it.
