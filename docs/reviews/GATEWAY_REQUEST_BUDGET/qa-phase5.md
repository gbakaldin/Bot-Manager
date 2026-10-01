# QA — GATEWAY_REQUEST_BUDGET Phase 5

**Verdict:** PASS
**Scope:** `f1cbc32..5b862bf` (14 commits: Phase 5 plus the review-phase4-fixround fixes) and the QA commit `82bdbc2`.
**Build:** I ran `mvn -o test` in a clean detached worktree at `5b862bf` plus the QA tests. Result: **2,495 tests, 0 failures, 0 errors, 0 skipped.**

| Module | Dev's count / my baseline at 5b862bf | After QA |
|---|---|---|
| bot-api | 148 | 148 |
| bot-strategies | 126 | 126 |
| bot-messages | 167 | 167 |
| bot-engine | 609 | **621** (+12) |
| bot-app | 1,431 | **1,433** (+2) |
| **Total** | **2,481** (matches Dev) | **2,495** |

`GatewayBudgetEscalationIT` (`-Dgroups=stub-gateway`): 4 tests, 0 failures, 37 s.

Every count here comes from the worktree. None comes from the working tree, which still has the uncommitted RIK and Aviator tests in it.

## Tests added (commit 82bdbc2, test files only)

- `bot-engine/src/test/java/com/vingame/bot/domain/bot/core/BotWsEdgeBlockLoopbackTest.java` (3 tests). A real ws-parser 3.0.5 `VingameWebSocketClient`, built by the real `ClientFactory`, upgrades against `StubGateway` in block mode.
  - It shows that the library really throws the edge's 403 with its status and headers, which `classifyHandshakeFailure` reads.
  - A non-block upgrade failure is not classified as a block.
  - End to end, `Bot.initialize` under enforce opens the circuit from a refused upgrade, with exactly one request sent.
  - Before this, the WS entry point had only a mocked `connect()` throwing a hand-built Netty exception.
- `bot-app/src/test/java/com/vingame/bot/infrastructure/probe/EnvironmentWsProbeEdgeBlockLoopbackTest.java` (2 tests). The JDK WebSocket probe runs against a loopback `HttpServer`.
  - A Cloudflare HTML 403 gives `EDGE_BLOCK`, which is unhealthy.
  - A JSON 403 that only passed through the edge stays `HTTP_4XX`, which is healthy.
  - Before this, only a mocked `HttpResponse` was tested.
- `bot-engine/src/test/java/com/vingame/bot/infrastructure/gateway/GatewayClearanceProbeBindingTest.java` (2 tests).
  - A budget created without a URL (the WS-probe-scheduler path) gets its clearance probe once the URL arrives.
  - An edited `apiGateway` re-binds the probe to the new host.
  - This closes mutation M18, which survived the whole suite.
- `bot-engine/src/test/java/com/vingame/bot/infrastructure/gateway/GatewayCircuitSignalsTest.java` (5 tests). Checks how often the circuit logs, which no test checked before:
  - one ERROR per open, even with 50 in-flight blocks after it;
  - under observe, one WARN per 5 minutes, carrying the folded count;
  - one INFO on close;
  - with no probe bound, the circuit stays open, WARNs once per interval and stamps nothing;
  - `server: cloudflare` with no `cf-ray` still counts as a block, and the ERROR prints `cf-ray -`. This closes mutation M05.
- `bot-engine/src/test/java/com/vingame/bot/infrastructure/client/ApiGatewayClientLogBodyGuardTest.java` (2 tests). A source guard: no INFO, WARN or ERROR call in `ApiGatewayClient` takes `responseBody` or `.body()` as an argument. It includes a check that the scanner actually catches violations.
  - `PerBotInfoLogGuardTest` does not cover `ApiGatewayClient`, so this rule needed its own guard.

## Coverage of the diff

- `CloudflareBlockDetector` ← `CloudflareBlockDetectorTest`, `GatewayCircuitSignalsTest`, both loopback tests
- `SlidingWindowGatewayBudget` (trigger, refusal, probe, waiter MDC) ← `GatewayCircuitBreakerTest`, `SlidingWindowGatewayBudgetCircuitRefusalTest`, `…WaiterMdcTest`, `…AdmissionTest`, `GatewayCircuitSignalsTest`
- `GatewayBudgetRegistry` (probe binding, `isCircuitOpen`) ← `GatewayClearanceProbeTest`, `GatewayClearanceProbeBindingTest`, `GatewayBudgetRegistryTest`
- `ApiGatewayClient` (in-repo login, classify-before-parse, `endpointOf`, `bodyForLog`) ← `ApiGatewayClientLoginTest`, `ApiGatewayClientBlockTest`, `ApiGatewayClientLogBodyGuardTest`
- `Bot.connectUnderBudget` ← `BotWsEdgeBlockTest`, `BotWsEdgeBlockLoopbackTest`
- `DeadGroupRecoveryScheduler` circuit skip ← `DeadGroupRecoverySchedulerTest`
- `EnvironmentProbeScheduler` suppression ← `EnvironmentProbeSchedulerTest`
- `EnvironmentWsProbe` `EDGE_BLOCK` ← `EnvironmentWsProbeClassificationTest`, `EnvironmentWsProbeEdgeBlockLoopbackTest`
- `RegistrationWorker` (B2, S3, cause cycle, deleted document, startup line) ← `RegistrationWorkerTest`
- `alerts.yml` (`RegistrationNotProgressing`, `GatewayEdgeBlocked*`) ← `AlertRuleMetricsTest`

No test touches a real gateway. Every HTTP and WS call goes to a loopback `StubGateway` or a loopback JDK `HttpServer` on an ephemeral port.

## Mutation results

For each mutation I applied one change in the worktree, ran the named tests offline, then restored the file with `git checkout`. The worktree was clean afterwards.

**Against the existing tests: 38 of 42 killed.**

| # | Mutation | Result |
|---|---|---|
| M01–M04 | Detector: drop the page conjunct / drop the edge conjunct / drop 429 / stop walking the cause chain | killed |
| M05 | Detector: require `cf-ray` and ignore `server: cloudflare` | **survived** → killed by `GatewayCircuitSignalsTest` |
| M06 | Queued waiter: `break` instead of refuse (reverts b8644ec) | killed (`aQueuedEssentialWaiterIsRefusedWhenTheCircuitOpens`) |
| M07 | Open does not walk the queues | killed |
| M08 | Soft waiter refused by the circuit throws instead of returning empty | killed |
| M09 | Arrival path never refuses on an open circuit (every tier, ESSENTIAL included) | killed |
| M10–M12 | Probe closes on a block / closes on no answer / ignores the interval | killed |
| M13 | Observe mode opens the circuit | killed |
| M14 | Probe not stamped | killed |
| M15 | Close skips its admission pass | survived — **equivalent**: an open circuit refuses every queued waiter and every arrival, so the queue is always empty when it closes |
| M16, M17 | Probe sends the wrong query / never classifies the answer | killed |
| M18 | Probe bound only when the budget is created | **survived** → killed by `GatewayClearanceProbeBindingTest` |
| M19 | `isCircuitOpen` always false | killed (recovery and probe-scheduler tests) |
| M20 | `httpCall` does not classify before parse | killed |
| M21 | `endpointOf` always returns LOGIN | killed |
| M22 | `bodyForLog` returns the raw page | killed |
| M23–M26 | Login wire shape: drop `Cache-Control` / always send `X-TOKEN` / pretty-printed body / no timeout | killed (byte-parity against the library's `AuthClient`) |
| M27 | Login counts an edge block as a login failure | killed |
| M28, M29 | Agency and auth tokens swapped / envelope dropped from the error message | killed |
| M30 | Bot WS path does not classify | killed |
| M31 | Recovery has no circuit skip | killed |
| M32, M33 | Probe scheduler ignores the circuit / live sibling beats the circuit | killed |
| M34 | WS probe has no `EDGE_BLOCK` check | killed |
| M35 | B2 deferral ordering reverted | killed (`severalStarvedGroupsDoNotStarveAHealthyOne`) |
| M36 | S3: a Jackson parse failure counts as a transport failure | killed |
| M37 | Deleted document keeps registering | killed |
| M38 | Startup line prints configured instead of effective values | killed |
| M39 | `recordFailure` resets the group's deferral position | survived — low value, see Gaps |
| M40 | Alert counts `failed` as progress | killed |
| M41 | Waiter MDC not restored | killed |
| M42 | Cause-cycle walk unbounded | killed (`SEPARATE_THREAD` timeout) |

**Against the new QA tests: all killed.** That covers M05, M18, M30, M34 and M01 (the JSON-403 case) re-run against the new tests, plus four new mutations:
- M43: a WARN logs `responseBody`
- M44: an ERROR on every block
- M45: the observe WARN is not throttled
- M46: an unbound probe closes the circuit

## Hunt for self-deceiving tests

None of the Phase 5 tests passes for the wrong reason. Three are weaker than they read:

- `SlidingWindowGatewayBudgetCircuitRefusalTest.anAlreadyQueuedWaiterIsNotAdmittedWhileTheCircuitIsOpen` uses a bounded wait, so it passes whether the pass refuses the waiter or only `break`s. The test's own comment says so. The unbounded companion test is the one that actually kills M06 and M07.
- `BotWsEdgeBlockTest` and `EnvironmentWsProbeClassificationTest`'s `EDGE_BLOCK` case build their exceptions and headers by hand. Both rested on an assumption about what the transport really throws. The two loopback tests now confirm that assumption with the real ws-parser 3.0.5 and the real JDK client.
- `GatewayClearanceProbeTest.theProbeIsAnonymous` asserts "still open", which would also hold if the probe threw an IOException. `anAnsweredProbeCloses` together with the M17 kill covers the gap.

## Findings (no blocking defects)

1. **WS-side detection only works if the block page is under 8 KB (not verified for other page types).** ws-parser installs `HttpObjectAggregator(8192)`. The captured block page is about 4.7 KB and is classified correctly. A larger Cloudflare page, such as a challenge or interstitial, would probably surface as `TooLongHttpContentException` with no response attached, and would not be classified on the WS path. The HTTP path has no such limit. Low risk: the 1015/1020 block pages are about 5 KB.
2. **The real library throws Netty's `WebSocketClientHandshakeException` unwrapped.** M04 (no cause walk) survives against the real-library test, so the cause walk is defensive only. This is not a problem.
3. **Each refused WS upgrade costs about 5 s inside `VingameWebSocketClient.close()`.** Observed in the loopback test timings; it comes from the library's shutdown timeout. This is informational: it is a cost on bot reconnect threads during a block, not a correctness issue.
4. **`GatewayBudgetRegistry.isCircuitOpen` calls `budgets.get(environmentId)` directly instead of going through `budgetKey`.** That is correct today because the key is the environment id. But the class javadoc says switching to a host key means changing `budgetKey`, `find` and `snapshotOrEmpty`; this is a fourth consumer that a key change would also have to touch.
5. **`group_recovery_skipped_total` is registered lazily**, so its first sample is 1. No alert reads it today. If one ever does, it needs zero pre-registration, as CLAUDE.md requires for the recovery counters.
6. **Pre-existing, not from this diff:** `getBalance` puts the raw `responseBody` into a RuntimeException message ("Data array is missing or empty: …"). An HTML page fails in `readTree` before reaching that line, so a block page cannot get there.

**On the logging requirement:** no response body reaches INFO or above in this diff.
- The only WARN carrying a body uses `bodyForLog`, and the new guard pins that.
- The circuit lines are at incident rate: one ERROR per open, a WARN per hour per probe, one INFO per close, and the observe WARN at most once per 5 minutes per environment.
- `PerBotInfoLogGuardTest` passes unchanged.

## Gaps

- M39 (`recordFailure` resetting `deferralSeq`) is not pinned. Its only effect is on ordering when a group alternates between being deferred and failing, which is not a starvation path. I left it untested.
- WS-side behaviour for a block page over 8 KB (Finding 1) is not tested. Faking it convincingly would mean building a synthetic oversized page.
- Manual check V4d (`StubGatewayMain` plus `POST /__stub/block` against a locally running app) and the staging deploy are outside what QA can run.

## Failures

None.
