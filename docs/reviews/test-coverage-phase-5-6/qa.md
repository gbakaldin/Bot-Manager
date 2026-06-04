# QA — test-coverage-phase-5-6

**Verdict:** PASS
**Build:** mvn test → 246 tests, 0 failures, 0 errors (baseline was 202 tests, 0 failures)

## Tests added / updated

### Phase 5A — Bot core (new file)

- `src/test/java/com/vingame/bot/domain/bot/core/BotTest.java` — 16 tests across 5 nested classes
  - `CreditBalanceTests` (2): `creditBalance(500)` decrements `expectedCurrentBalance`, increments `totalBetsPlaced` by 1, adds 500 to `totalBetAmount`; multiple calls accumulate.
  - `CheckBalanceTests` (3): cached return path (delta ≤ 1M, no `apiGatewayClient.getBalance` call), refetch path (delta > 1M, balance fetched and both `lastFetchedBalance` and `expectedCurrentBalance` updated), and initial-call path (`lastFetched=-1`, `expected=-100M` → refetch).
  - `DepositTests` (3): early-return when `lastFetchedBalance < 0` (no `gameMsClient.deposit` call), success-callback path (`gameMsClient.deposit` invoked with `agencyToken`, `1_000_000_000L`, callback; on `true` re-fetches balance), failure-callback path (on `false` balance is NOT updated).
  - `StatusTests` (4): initial status is `AUTHENTICATING`; after `initialize()` reaches `CONNECTING` (since `start()` isn't called); `markConnectionAuthenticated` transitions from `STARTED` to `CONNECTION_AUTHENTICATED`; no-op when already at target.
  - `ConnectionFlagsTests` (4): `isConnected` false when `client == null`, true when `client.isOpen()` returns true, false when `isOpen()` returns false; `isStopped` false initially, true after `cleanup()`.
  - Uses a minimal `TestBot extends Bot` inner class with no-op abstract methods and two exposed seams (`checkBalanceExposed`, `markConnectionAuthenticatedExposed`).

### Phase 5B — BettingMiniGameBot betting logic (new file)

- `src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotTest.java` — 21 tests across 7 nested classes
  - `ShouldBetTests` (3): `betSkipPercentage=0, maxBetsPerRound=3` → returns true 3 times then false (cap); `betSkipPercentage=100` always skips regardless of `Random.nextInt(100)` value (0/50/99); `betSkipPercentage=50` → false when `random.nextInt(100)=30`, true when `=70`.
  - `ResolveBetAmountTests` (2): `minBet=100,maxBet=1000,step=100` → `random.nextInt(10)=0→100`, `=9→1000`, `=5→600`; `minBet==maxBet=500` collapses (`random.nextInt(1)=0→500`).
  - `ResolveNextEntryToBetTests` (2): with `bettingOptions=[1,10,100]` and `random.nextInt(3)=1` → 10; with `bettingOptions=null, numberOfOptions=5` and `random.nextInt(5)=3` → 3 (falls back to `[0..5)`). Invokes the private method via reflection.
  - `CanBetTests` (4): all four gate combinations — no session, wrong phase (PAYOUT), not enough time, all-open → only all-open returns true.
  - `LifecycleHandlerTests` (6): `onSubscribe(timeForDecision=2000, timeForBetting=15000)` → `blockBetTime=2000`, `timeForBetting=15000`, status transitions to `CONNECTION_AUTHENTICATED`; `onStartGame(sessionId=42069)` → `sidStore.get()=42069`, `gameState=BET`, `numberOfBetsInCurrentSession=0`; `onUpdate(2)` → `BET`; `onUpdate(99)` → `PAYOUT`; `onUpdate(0)` leaves state unchanged; `onEndGame` → `PAYOUT`. All four handlers are private; invoked via reflection with real `ActionResponseMessage` instances wrapping mocked message classes.
  - `BeforeReconnectTests` (1): `beforeReconnect()` resets `sidStore` to 0, `gameState` to null, `remainingTime` to 0, `numberOfBetsInCurrentSession` to 0, and cancels/nulls `scheduler` and `watchdogTask`.
  - `WatchdogTests` (3): with `watchdogTimeoutSeconds=1`, watchdog fires `triggerFullReconnect` containing `"watchdog timeout"` within 3s; with 500ms reschedules via `onSubscribe → onStartGame → onEndGame` the watchdog does NOT fire within 800ms; after `cleanup()`, calling `onWatchdogExpired()` directly is a no-op (`triggerFullReconnect` not invoked). Tests use a subclass that overrides `triggerFullReconnect` to count down a `CountDownLatch`.
  - Uses the new `setRandom(Random)` seam (S1) for deterministic `shouldBet`/`resolveBetAmount`/`resolveNextEntryToBet`.
  - `@AfterEach` shuts down the watchdog and countdown schedulers to avoid leaking virtual threads between cases.

### Phase 6A — Bot reconnect & backoff (new file)

- `src/test/java/com/vingame/bot/domain/bot/core/BotReconnectTest.java` — 7 tests across 4 nested classes
  - `TriggerFullReconnectTests` (2): when the `reconnecting` CAS flag is already set, a second `triggerFullReconnect` short-circuits (no status transition, no client close); after `cleanup()` (sets `stopped=true`), `triggerFullReconnect` is a no-op.
  - `RunWsReconnectLoopTests` (2): success on attempt 1 — `clientFactory.newClient` returns a client whose `isOpen()=true`, loop returns after one iteration, `sleeps` contains exactly `[5000, 3000]` (first backoff + confirm), `reconnecting` flag clears, final status is `STARTED`. Exhaust-backoff-then-re-auth — `clientFactory.newClient` always throws; after 7 attempts `apiGatewayClient.authenticate` is invoked; the test's stubbed `authenticate` flips `stopped=true` so the loop exits cleanly; `sleeps` contains exactly the documented 7-step sequence `[5000, 10000, 30000, 60000, 60000, 60000, 60000]`.
  - `PerformReauthTests` (1): when `apiGatewayClient.authenticate` throws, `performReauth` transitions status to `DEAD`, clears `reconnecting`, returns false.
  - `RunAuthThenWsLoopTests` (2): happy path — re-auth + WS reconnect both succeed, `sleeps` contains exactly `[3000]` (confirm only, no backoff); WS-fails-after-reauth — re-auth succeeds but returned WS client's `isOpen()=false`, loop falls through to `runWsReconnectLoop` and both `3000` (confirm) and `5000` (first backoff) appear in `sleeps`.
  - Uses a `FastBot extends Bot` inner class that overrides the now-`protected` `sleep(long)` (seam S2) to record durations and return immediately. All reflective access to `private` methods (`runWsReconnectLoop`, `runAuthThenWsLoop`, `performReauth`).

### FOLLOWUPS.md updates

- S1 marked **RESOLVED** with note pointing to BettingMiniGameBotTest's use of `setRandom(...)`.
- S2 marked **RESOLVED** with note pointing to BotReconnectTest's `FastBot.sleep(long)` override.
- New S3 added: brittle reflection access to private hooks in `Bot.java` / `BettingMiniGameBot.java`. Recommends dropping to package-private.
- New S4 added: watchdog tests use real wall-clock time; suggests an injectable `ScheduledExecutorService` seam similar to `setRandom`.
- Original "Sample JSON fixtures" entry renumbered S3 → S5. No cross-references existed so the renumber is safe.

## Coverage of the diff

- `Bot.java` ← `BotTest.java`, `BotReconnectTest.java`
  - `creditBalance`: arithmetic + counters. (Bot.java:383–387)
  - `checkBalance`: ≤1M cache rule, >1M refetch, initial bootstrap. (Bot.java:197–216)
  - `deposit`: early-return guard, success-callback path, failure-callback path. (Bot.java:176–195)
  - `initialize`: status transitions AUTHENTICATING → AUTHENTICATED → CONNECTING. (Bot.java:88–120)
  - `markConnectionAuthenticated`: idempotent at target. (Bot.java:238–242)
  - `isConnected`, `isStopped`, `cleanup`. (Bot.java:124–134, 230–236)
  - `triggerFullReconnect`: CAS idempotence, stop guard. (Bot.java:275–286)
  - `runWsReconnectLoop`: first-attempt success, full backoff exhaustion + re-auth fallback. (Bot.java:288–313)
  - `runAuthThenWsLoop`: re-auth + WS happy path, WS-fail fallthrough to `runWsReconnectLoop`. (Bot.java:315–329)
  - `performReauth`: failure → DEAD + reconnecting=false. (Bot.java:331–344)

- `BettingMiniGameBot.java` ← `BettingMiniGameBotTest.java`
  - `initializeSubclass`: indirectly via setUp. (BettingMiniGameBot.java:82–102)
  - `shouldBet`: skip-percentage gates + maxBetsPerRound cap. (BettingMiniGameBot.java:241–254)
  - `resolveBetAmount`: bounds + step math. (BettingMiniGameBot.java:262–273)
  - `resolveNextEntryToBet`: indexes into `Game.getEffectiveBettingOptions()`. (BettingMiniGameBot.java:224–227)
  - `canBet`: 4-way gate combinations. (BettingMiniGameBot.java:233–238)
  - `onSubscribe` / `onStartGame` / `onUpdate` / `onEndGame`: state transitions, session-id updates, watchdog scheduling. (BettingMiniGameBot.java:146–185)
  - `beforeReconnect`: full state + timer reset. (BettingMiniGameBot.java:188–201)
  - `scheduleWatchdog` / `onWatchdogExpired`: indirectly via real-time watchdog tests. (BettingMiniGameBot.java:128–144)

## Gaps

- **`Bot.tryReconnectWs` not directly tested.** Covered transitively by `runWsReconnectLoop` (success and failure paths) and `runAuthThenWsLoop` (both paths). A dedicated test that asserts the `client.close()` → `clientFactory.newClient(tokens, name)` → `configureClient` → `transitionStatus(CONNECTING)` → `client.connect()` → `beforeReconnect()` → `start()` sequence is missing, but the behavior is observed via the integration test cases.
- **`Bot.onWsDisconnected` not directly tested.** This is the entry point the `VingameWebSocketClient.onDisconnect(...)` listener invokes. The CAS guard is tested via `triggerFullReconnect` (which uses the same `reconnecting` flag); the actual virtual-thread spawn path is exercised but the unit test calls the reconnect loop directly rather than through the listener to keep tests deterministic. Not a meaningful gap — the listener wiring is trivial.
- **`Bot.authenticate()` / `logout()` / `restart()` public methods not tested.** These are slightly different from `performReauth` (which is the reconnect-loop variant). `restart()` does `client.close → clientFactory.newClient → start()` with no backoff. Worth a future pass; low risk because they are short and mostly delegate.
- **`BettingMiniGameBot.botBehaviorScenario()` not directly tested.** Returns a complex `Scenario` built via the websocket-parser DSL. Would require either a sample mock pipeline or an integration test driving real WebSocket messages. Out of scope for unit tests; better suited to a future Phase 7 (JSON polymorphism) integration test.
- **`BettingMiniGameBot.onStart()` not directly tested.** Calls `onNewSession()` (covered transitively via `onEndGame`) and then registers two scenarios on the WebSocket client. The scenario-registration path is hard to verify without instantiating a real client.
- **Watchdog scheduling cancellation race not covered.** `scheduleWatchdog()` cancels the existing `watchdogTask` if not done, then schedules a new one. The reschedule test verifies the net effect (watchdog doesn't fire when messages keep arriving) but does not directly assert that `watchdogTask.cancel(false)` is called on the previous task. This is acceptable behavioural coverage but a tighter test could be added if needed.

## Observations / things worth flagging

1. **Reflection-heavy tests.** Phase 5B and 6A reach into `private` methods/fields via `Class.getDeclaredMethod` / `Class.getDeclaredField`. This is brittle — a rename or signature change will produce a `NoSuchMethodException` instead of a compile error. The new S3 follow-up in `FOLLOWUPS.md` suggests dropping these to package-private (default visibility) as a test-seam-only change.

2. **Watchdog tests rely on real timers.** `watchdogTimeoutSeconds = 1` means each watchdog test takes ~1–1.5 seconds wall-clock. Total test-suite runtime jumped from ~6s to ~10s primarily because of the three watchdog tests. They are deterministic locally but theoretically flaky on a heavily loaded CI runner. Mitigation suggested in new S4 follow-up.

3. **`BettingMiniGameBot.initializeSubclass()` does not seed balance state.** Tests had to set `lastFetchedBalance` and `expectedCurrentBalance` via reflection on the watchdog-test bot, otherwise `onEndGame → onNewSession → checkBalance` triggered a real `apiGatewayClient.getBalance` lookup through a null `getClient()`. This is fine for tests but suggests `onNewSession` could be more defensive (early-return when no balance has ever been fetched). Not raised as a follow-up because the production code path always sets a balance before `onEndGame` could fire.

4. **`triggerFullReconnect` short-circuit semantics.** When the `reconnecting` CAS flag is set, the second call returns immediately. The test asserts this by checking that no status transition occurs. However, the production code transitions to `RECONNECTING` only on the first successful CAS — if the caller observes a non-`RECONNECTING` state, they cannot tell whether their call was honored or short-circuited. Worth a doc comment on the production method. Low priority.

5. **Six `Tests run: 0` roll-up lines** in `mvn test` output, same cosmetic issue from earlier phases. Final summary is correct: 246/0/0/0.

## Failures (if any)

None.
