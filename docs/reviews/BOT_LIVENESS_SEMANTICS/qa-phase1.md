# QA — BOT_LIVENESS_SEMANTICS Phase 1 ("stop leaking orphan clients")

**Verdict:** PASS
**Build:** `mvn clean install -DskipTests` then `mvn test` → bot-api 98, bot-strategies 111,
bot-messages 136, bot-engine **354**, bot-app 808 = **1,507 tests, 0 failures, 0 errors**.
Baseline on the committed tree before my tests: 347 in bot-engine, 1,500 total, also green.

Diff under review: `6103e4a` (fix) + `2e98e4e` (tests), scope Phase 1 only.

> Tested against a `git worktree` at `2e98e4e`
> (`…/scratchpad/qa-head`) and a second worktree at the pre-fix parent `91ab7d8`
> (`…/scratchpad/qa-prefix`). The repo working tree is dirty with an unrelated
> deposit-amount change and was left untouched; the new test file was additionally run
> there (bot-engine 359, green) to confirm it does not collide with that in-flight work.

---

## Tests added / updated

- `/Users/gleb/IdeaProjects/Bot/bot-engine/src/test/java/com/vingame/bot/domain/bot/core/BotClientCloseAccountingTest.java`
  — 7 tests in 4 nested classes, covering what Dev's suite left open:
  - **`CloseErrorsAreSwallowed`** (3) — `restart()` and `cleanup()` survive a `close()` that
    throws and still do their job (`restart` must still build/connect/start a fresh client).
    This is a real behavior change: pre-fix `restart()` called a bare `client.close()` whose
    exception propagated and aborted the restart. Third test pins that a client whose
    `close()` threw is not retried by a later terminal close, and documents why that is
    correct against the library rather than merely convenient.
  - **`PeriodicLogoutPath`** (1) — `logout()` → `restart()`, the one production sequence
    (`BotGroupBehaviorService.performPeriodicLogout`) in which a single client instance is
    reachable from two close sites in a row. Exactly one `close()`, fresh client untouched,
    bot not marked stopped.
  - **`SingleSlotDedup`** (1) — pins the safety argument for `lastClosedClient`: it is a
    one-entry memo, so eviction by a different instance causes a *redundant* close, never a
    *missed* one. This is the property that makes the new field safe; it had no test.
  - **`UnmigratedTriggerFullReconnectSite`** (2) — pins both halves of the fifth,
    un-migrated `isOpen()`-guarded close (see Gaps).

Dev's `BotOrphanClientCloseTest` (8 tests) is unchanged and correct.

---

## Coverage of the diff

| Production site | Test | Covered |
|---|---|---|
| `Bot.cleanup()` `:278-297` | `BotOrphanClientCloseTest#cleanupClosesDeadChannelClient`, `#cleanupWithoutClientDoesNotThrow`; `BotClientCloseAccountingTest#cleanupSwallowsThrowingCloseAndStaysStopped` | close on dead channel, null-safety, throwing close |
| `Bot.restart()` `:299-311` | `BotOrphanClientCloseTest#restartClosesDeadChannelClient`; `BotClientCloseAccountingTest#restartSwallowsThrowingCloseAndStillRebuilds` | close-before-overwrite, throwing close no longer aborts the restart |
| `Bot.stop()` `:313-322` (new `lastClosedClient` stamp) | `BotClientCloseAccountingTest#logoutThenRestartClosesOldClientExactlyOnce`, `#clientThatFailedToCloseIsNotRetried`; `BotOrphanClientCloseTest#cleanupThenTerminalCloseClosesOnce` | write-before-close, dedup across logout/restart |
| `Bot.closeClientQuietly()` `:649-651` | `BotOrphanClientCloseTest#terminalPathClosesDeadChannelClient` | terminal DEAD path closes a dead channel |
| `Bot.closeQuietly(client)` `:663-674` (new) | `BotOrphanClientCloseTest$OneClosePerInstance` (3); `BotClientCloseAccountingTest#evictedInstanceIsClosedAgainNotSkipped` | one close per instance; single-slot memo cannot skip |
| `Bot.tryReconnectWs()` `:676-703` — happy path | `BotOrphanClientCloseTest#reconnectClosesDeadChannelClient`, `#secondReconnectDoesNotRecloseTheFirstClient` | outgoing client closed before `this.client` is overwritten |
| `Bot.tryReconnectWs()` — exception path `:694-701` | `BotOrphanClientCloseTest#failedAttemptClosesItsOwnClientOnceOnly` | half-built client closed, and not double-closed by the retry |
| `Bot.triggerFullReconnect()` `:523-525` — **not migrated** | `BotClientCloseAccountingTest$UnmigratedTriggerFullReconnectSite` (2) | skipped client is still closed downstream; open client is closed twice |

### Do the 8 new tests genuinely fail pre-fix?

Verified by copying `BotOrphanClientCloseTest` verbatim onto `91ab7d8` and running it:
**7 of 8 fail** (4 in `ClosesDeadChannelClient`, 3 in `OneClosePerInstance`).

The eighth, `cleanupWithoutClientDoesNotThrow`, passes pre-fix — it is a null-safety
assertion with no `close()` verification at all, and the pre-fix guard
`client != null && client.isOpen()` was equally null-safe. So the commit message's
"pre-fix each of these verifies would have been `times(0)`" is right for every test that
verifies a close, and overstated by one. Not a defect; correcting the record.

My 7 added tests: **6 of 7 fail pre-fix** (one is a `NoSuchMethodException` since
`closeQuietly` did not exist). The seventh,
`openClientIsClosedTwiceBecauseSiteBypassesAccounting`, passes pre-fix by design — it pins
the un-migrated fifth site, whose behavior Phase 1 did not change.

---

## Focus-area findings

### 1. `lastClosedClient` — no leak, no harmful race, retention is real but bounded

**It does not defeat the fix.** The flood was produced by *live* `SendAsync` schedulers
still calling `send()`; reachability was never the mechanism — the orphans were already
unreachable from the bot and flooded anyway, because their own scheduler threads kept them
alive. `close()` shuts those schedulers down, after which the retained object graph is
inert. So holding one closed client per bot costs heap, not behavior.

**Retention:** exactly one already-closed client per `Bot`, held for the bot's lifetime.
`cleanup()` never nulls it (nor `client`), and `BotGroupRuntime.botInstances` keeps stopped
bots until the runtime is discarded, so a stopped 100-bot group pins 100 inert client
graphs. Bounded and small next to the unbounded orphan population it prevents; nulling both
fields in `cleanup()` would be a one-line improvement for a later phase.

**Race:** `closeQuietly` is a non-atomic check-then-act on a volatile
(`c == lastClosedClient`, then assign). Two threads closing the same instance concurrently —
plausible: `cleanup()` on the group executor vs. the `reconnect-<name>` virtual thread in
`tryReconnectWs` — can both pass the guard. The consequence is a double `close()`, which the
library's one-shot guard makes a no-op. So the race is benign, but the "exactly one close per
instance" invariant the field advertises is **best-effort, not guaranteed**. Not worth a CAS.

**The property that actually makes it safe** (now pinned by
`evictedInstanceIsClosedAgainNotSkipped`): a single-slot memo can only suppress a close for
the instance currently in the slot — an instance that was, by construction, just closed.
Eviction by a different instance loses the suppression, so the worst case is a redundant
close, never a missed one. Since a missed close is precisely the bug Phase 1 exists to
prevent, the failure mode falls on the harmless side by construction.

**Write-before-close in `stop()`** (`lastClosedClient = client;` then `client.close()`):
correct. `VingameWebSocketClient.close()` flips `isClosing.getAndSet(true)` as its **first**
statement, so a throw anywhere after that leaves nothing a retry could still do. Dev's
justification checks out; pinned by `clientThatFailedToCloseIsNotRetried`.

Minor asymmetry: `stop()` writes the field by hand and calls `client.close()` directly rather
than routing through `closeQuietly`, so it can double-close an instance `closeQuietly`
already closed. Benign for the same reason.

### 2. Double-close safety — verified, not assumed

Read from `~/.m2/repository/com/vingame/websocket-parser-core/3.0.5/websocket-parser-core-3.0.5-sources.jar`,
`com/vingame/websocketparser/VingameWebSocketClient.java`:

- `:502-507` — `close()` opens with `if (isClosing.getAndSet(true)) { return; }`. One-shot and
  idempotent, confirmed at the source rather than from the javadoc alone.
- `:488-501` javadoc — "idempotent and one-shot"; "runs even if the client never connected, so
  a never-connected client (e.g. a failed reconnect attempt) never leaks its
  scenario/ping/processing threads."
- `:509-558` — teardown covers scenarios (and with them every `SendAsync` executor), the ping
  scheduler, the channel, `shutdownProcessingExecutor()`, and the `EventLoopGroup` when owned.
- `:752` — `isOpen()` is `isConnected.get() && channel != null && channel.isActive()`, so the
  plan's premise (dead channel ⇒ `isOpen()==false` ⇒ pre-fix close skipped) is exact.

The claim holds. Dropping the guard is safe.

### 3. Regression risk on existing reconnect coverage — none observed

Full reactor green. `BotReconnectTest`, `BotDeadSecondsTest`, `BotTest`, `BotReconnectMdcTest`,
`BotOrphanClientCloseTest` and the new class were re-run together **3×**: 60/60 every time,
no flakes (the two `Mockito.timeout()`-based tests I added ran in ~20 ms each).

Two notes Dev's summary didn't mention:

- `BotReconnectTest:92` (`shouldShortCircuitWhenAlreadyReconnecting`) — confirmed as stated:
  CAS short-circuit, no client attached.
- `BotReconnectTest:109` (`shouldNoOpWhenStopped`) is a **second** close-count assertion —
  `verify(wsClient, times(1)).close()` with `isOpen()==true`. It survives because `cleanup()`
  closes once and `triggerFullReconnect`'s `stopped` guard returns before its own close. Worth
  knowing it exists before Phase 3 touches this area.

### 4. Fifth `isOpen()`-guarded close at `Bot.java:523-525` — leaving it is safe

`triggerFullReconnect` still does `if (client != null && client.isOpen()) client.close();`.
Assessment: **safe to leave, but incoherent.**

*Safe*, because every route out of the worker it spawns closes the client unconditionally:
`runAuthThenWsLoop` → `performReauth()` failure → `closeClientQuietly()`, or →
`tryReconnectWs()`, which closes `this.client` as its very first action. The only way to
reach neither is `stopped` turning true, which happens via `cleanup()` → `stop()` → `close()`.
So no reference is dropped un-closed on this path. Pinned by
`deadChannelClientSkippedBySiteIsClosedByTheSpawnedWorker`.

*Incoherent*, because when the channel **is** open the bare `client.close()` does not stamp
`lastClosedClient`, so the next site sees an unrecorded instance and closes it a second time —
the invariant the new field was added to maintain is violated on exactly the watchdog path
that motivated it. Harmless (library one-shot), but it means the accounting is not the
property the field's javadoc claims. Pinned at `times(2)` by
`openClientIsClosedTwiceBecauseSiteBypassesAccounting`, with an explicit comment that
migrating the site turns this into `times(1)` and the test must then be updated on purpose.
Recommend migrating it in Phase 2/3 for coherence, not for correctness.

---

## Gaps

- **New tail-latency exposure on the group stop/restart REST path — not covered by any test
  or by the plan's Phase 1 verification.** `close()`'s javadoc (`:499-500`) warns it "may block
  up to `BackpressureConfig.getShutdownTimeoutMillis()`"; that default is **5000 ms**
  (`BackpressureConfig:74`). No new call site is on the Netty I/O thread (all four are on the
  group executor or the `reconnect-<name>` virtual thread), so the library's hard constraint is
  respected. But `shutdownProcessingExecutor()` runs in a `finally` and does
  `awaitTermination(5000 ms)`, and `BotGroupRuntime.stopAllBots` (`:274-280`) closes every
  bot's client **serially** in a `forEach`. Pre-fix, a dead-channel client skipped `close()`
  entirely (0 ms); post-fix it always pays that path. Normal case is sub-millisecond — workers
  park on `messageQueue.take()` and exit on `shutdownNow()`'s interrupt — but a worker inside a
  handler doing a blocking HTTP call (`deposit()` / `getBalance()` do exactly that) will burn
  the full 5 s, so a 100-bot group has a theoretical worst case of ~500 s on `POST /{id}/stop`.
  Tail risk, not expected cost, and not a reason to hold the phase — but it is new, it is on a
  user-facing endpoint, and nothing measures it. **Recommend the Releaser time
  `POST /api/v1/bot-group/{id}/restart` on the largest staging group and compare against the
  pre-deploy baseline.** (The channel-close wait, also up to 5 s, is *not* new exposure: it is
  skipped when `channel.isActive()` is false, i.e. in exactly the dead-channel case Phase 1
  adds.) Not unit-testable — mocked `close()` returns instantly.
- **Heap retention of `lastClosedClient` is not testable at unit level.** Bounded at one inert
  client per bot; see finding 1. Covered instead by the plan's verification step 6.
- **The concurrent-close race is deliberately not tested** — non-deterministic, and benign by
  the library's one-shot guard. Documented above rather than pinned.
- **Phase 1 does not remediate the 136 live staging zombies**, and the plan says so
  (`:501-517`): those bots never processed a disconnect, so they never reach any of these close
  sites. Phase 2's sweep is what reaches them. Flagged here only so the phase is not
  over-credited.
- **Verification step 4 will pass trivially and proves nothing.** An orphan can only arise from
  the reconnect / restart / cleanup paths, and deploying Phase 1 restarts the JVM, which
  destroys every existing orphan. So the "post-deploy flood < 1 % of pre-deploy" comparison is
  satisfied by the redeploy alone, fix or no fix. Step 5 (restart a group, expect 0 flood lines
  under that group id) is the load-bearing check, and even it only exercises `cleanup()`.
  **Recommend adding:** after the group restart, induce a WS drop on one bot (the Phase 2 /
  `DEAD_GROUP_RESTART` recipe) and confirm no `"Cannot send message, not connected"` appears
  under that bot's `ws-<username>` afterwards — that is the only staging check that exercises
  `tryReconnectWs`, the site the incident actually came from.
- **Fixture note for Phase 3.** `BotReconnectTest$ReconnectCycleCapTests` stubs
  `clientFactory.newClient(...)` to return the *same* mock for all ~70 attempts. With the new
  dedup that instance is now closed exactly once across the whole loop, where production
  (distinct instances) closes ~70. No current assertion depends on it, but anyone adding
  close-count assertions to those tests during the Phase 3 rewrite will read a misleading
  number unless the stub is changed to return distinct mocks.

## Failures (if any)

None. 1,507 tests, 0 failures, 0 errors.
