# THREAD_LEAK — Eliminate the platform-thread leak and reach 15× scale (~6,600 bots)

## 1. Goal

Bot Manager is in a slow crash-loop: **17 abrupt JVM deaths in 21 days** (~1 every
1.3 days), each an inability to create native threads — **platform-thread
exhaustion, not heap**. `jvm_threads_live_threads` (platform threads only; virtual
threads are excluded) sawtooths from ~2,750 at boot to ~20,000–21,733, at which
point the JVM can no longer spawn a native thread and dies mid-log (ExitCode=0,
OOMKilled=false). Docker `restart: unless-stopped` silently revives it in ~15s and
`/actuator/health` stays 200 throughout, so product teams never see it.

We will **eliminate the leak** and re-architect the per-bot thread cost so the app
survives ~6,600 concurrent bots (15× current staging). The dominant contributor —
~80% of all platform threads — is a **`close()` vs `cleanUp()` mismatch** against the
`websocket-parser-core` library: the Bot repo tears clients down with the method that
does **not** reclaim the per-client message-processor pool. This is fixable **entirely
in the Bot repo** with a small, reversible change (Phase 1), which by itself stops the
crash-loop. Reaching true 15× headroom additionally requires reducing the per-bot
platform-thread count (Phase 2, in-repo) and finally making message-processing
thread cost independent of bot count (Phase 3, library).

Out of scope: the log-volume problem (~200 MB/hr → must be <500 GB/month at 15×). The
user has deprioritized it; it is tracked separately and no logging changes are made here.

## 2. Findings — Current State

### 2a. The library spawns 4 platform threads per client and `close()` never reclaims them

`websocket-parser-core:2.3.10` (pinned at `/Users/gleb/IdeaProjects/Bot/pom.xml:169`).
Decompiled/source evidence from `websocket-parser-core-2.3.10-sources.jar`:

- `BackpressureConfig.DEFAULT_PROCESSING_THREADS = 4` (`BackpressureConfig.java:69`).
- `VingameWebSocketClient` **constructor** creates a fixed pool of 4 **platform, daemon**
  threads named `netty-ws-message-processor-<clientName>` and starts them immediately —
  before `connect()` (`VingameWebSocketClient.java:140-145,150`). Every `newClient(...)`
  call therefore spawns 4 platform threads at construction time, whether or not the
  connection ever succeeds. Each worker blocks forever on `messageQueue.take()` and only
  exits on interrupt (`VingameWebSocketClient.java:160-163`).
- **`close()` (`VingameWebSocketClient.java:411-457`)** shuts down scenarios, the ping
  scheduler, the WS channel, and (only if owned) the EventLoopGroup — but **never touches
  `processingExecutor`.** The 4 message-processor threads survive `close()` forever.
- **`close()` early-returns** when `!isConnected.get() || isClosing.get()`
  (`VingameWebSocketClient.java:412-414`). A client that was built but never connected
  (a failed reconnect attempt) skips scenario/ping shutdown entirely.
- **`cleanUp()` (`VingameWebSocketClient.java:589-622`)** calls `close()` **and then**
  shuts down `processingExecutor` (`shutdown()` → `awaitTermination(5000ms)` →
  `shutdownNow()`). This is the **only** method that reclaims the 4 message-processor
  threads. Because it runs the `processingExecutor` block unconditionally (outside the
  early-return path), it reclaims those threads even for a never-connected orphan client.

### 2b. The Bot repo calls `close()` everywhere and `cleanUp()` nowhere

All five teardown callsites in the repo use `client.close()`:
`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/Bot.java:278`
(`restart`), `:290` (`stop`), `:493` (`triggerFullReconnect`), `:619`
(`closeClientQuietly`), `:630` (`tryReconnectWs`). `grep` for `cleanUp` in
`src/main/java` returns nothing. **Every stop, restart, and reconnect leaks 4 platform
threads per client rebuilt, permanently.**

### 2c. The reconnect loop orphans never-connected clients

`Bot.tryReconnectWs()` (`Bot.java:627-643`) closes the previous client only
`if (client != null && client.isOpen())` (`:629`), then overwrites `this.client` with a
fresh `clientFactory.newClient(...)` (`:632`). A prior attempt whose `connect()` failed
leaves `isOpen()==false`, so it is **not** closed before being overwritten — its 4
message-processor threads (and its scenario pipeline executors) are orphaned. The backoff
loop can build up to `BACKOFF_SECONDS.length` (7) × `MAX_RECONNECT_CYCLES` (10) = 70
clients for a fully-failing bot (`Bot.java:34,43,521-565`). The same `isOpen()`-guarded
pattern appears in `restart()` (`:277`), `triggerFullReconnect()` (`:492`), and
`closeClientQuietly()` (`:617`). This is why the observed ratio is ~24.5
message-processor threads per active bot (≈6 client rebuilds/bot on average), not ~4.

### 2d. Live evidence (Bot-1, 2026-07-30, verified during this analysis)

`jvm_threads_live_threads{application="bot-manager"} = 13481`. `/proc/1/task/*/comm`
histogram:

| Thread name (masked) | Count | Source |
|---|---|---|
| `netty-ws-message-processor-*` | 10,852 | Library per-client pool, leaked (§2a–2c) — **~80%** |
| `pool-#-thread*` (sum) | ~2,420 | Library scenario pipeline scheduled executors (`PipelineStage.java:90`, `SendAsync.java:109`, `PingScheduler.java:19`) — default-named platform threads, leaked from orphaned/never-connected clients (§2c) |
| `ForkJoinPool-#-*` | 94 | Virtual-thread carrier pool — correct, O(cores) |
| `HttpClient-#-*` | ~77 | JDK HttpClient — fixed |
| `multiThreadIoEventLoopGroup-*` | 4 | Shared Netty IO group — correct (§2e) |
| `http-nio-*`, GC, misc | ~30 | Tomcat / JVM baseline — fixed |

### 2e. What is already correct

- The Netty IO `EventLoopGroup` is a shared singleton bean injected into every
  `ClientFactory` (`BotFactory.java:66,141`; `ClientFactory.java:76-81`) and the client
  only shuts it down when it owns it (`VingameWebSocketClient.java:445-451`), so it stays
  at 4 threads regardless of bot count. **Not a leak.** (The
  `EventLoopGroup is NULL!` WARN in `ClientFactory.java:80` fires only when the group is
  not propagated; on the current reconnect path it always is, because the same
  `freshClientFactory` instance — with the shared group already set — is reused. Confirm
  during Phase 1, but it is not the leak.)
- All Bot-repo `Executors.new*` schedulers pass `Thread.ofVirtual().factory()`, so their
  workers are **virtual**, not platform — `BotGroupRuntime` bot executor
  (`BotGroupRuntime.java:146-150`), per-group health/logout monitors
  (`BotGroupBehaviorService.java:1398-1399,1482-1483`), per-bot watchdog/countdown
  (`BettingMiniGameBot.java:188-189,340-341`), activation/session/gauge schedulers. These
  do **not** appear in the platform-thread histogram and are **not** the `pool-#-thread`
  source. The `pool-#-thread` threads come from the **library's** default-named executors
  (§2d), which use no factory.
- Per-bot schedulers are shut down on stop: `BettingMiniGameBot.cleanup()`
  (`:585-591`) shuts down `watchdogScheduler` and `scheduler`; `BotGroupRuntime.stopAllBots`
  (`:259-305`) cleans up each bot and shuts the group executor + monitors. These paths are
  correct except that the underlying `bot.stop()`/`cleanup()` chain calls `client.close()`,
  not `cleanUp()` (§2b).

## 3. Per-aspect readiness / mapping

| Aspect | State | Notes |
|---|---|---|
| Message-processor pool reclaimed on teardown | **blocked → in-repo fix** | Switch all `close()` → `cleanUp()` (§2b). Fully in Bot repo. |
| Orphaned never-connected clients reclaimed | **blocked → in-repo fix** | Drop `isOpen()` guard; always `cleanUp()` outgoing client before overwrite (§2c). |
| Per-bot platform-thread count (steady state) | **partial** | 4/bot after Phase 1. In-repo `processingThreads(1)` → 1/bot (Phase 2). |
| Message-processing cost independent of bot count | **blocked → library fix** | Needs virtual or shared-bounded processor pool (Phase 3). |
| Shared EventLoopGroup | **ready** | Correct at 4 threads (§2e). |
| Virtual-thread schedulers | **ready** | Already virtual (§2e). |
| Controlled restart on threshold breach | **partial (optional)** | No thread-count liveness gate today; health stays 200 at the ceiling. Optional Phase 0. |
| Log volume at 15× | **out of scope** | Tracked separately; no changes here. |

## 4. Architecture Decisions

1. **`cleanUp()` is the canonical teardown for a `VingameWebSocketClient` in this repo.**
   Every site that today calls `client.close()` MUST call `client.cleanUp()` instead.
   Rationale: `close()` provably leaks the 4-thread message-processor pool (§2a). This is
   the single highest-impact change and is fully in-repo.
2. **A client reference is never overwritten without first tearing down the outgoing
   instance, unconditionally** (regardless of `isOpen()`). Reconnect/restart paths must
   `cleanUp()` the previous `this.client` before assigning a new one, because
   never-connected orphans hold live threads (§2c).
3. **Teardown runs on a virtual thread or a service worker thread, never on the Netty IO
   loop.** `cleanUp()` may block up to `shutdownTimeoutMillis` (5s) on
   `awaitTermination`. All current teardown callers already run off the IO loop
   (reconnect virtual threads, `BotGroupBehaviorService`/`stopAllBots` worker); Phase 1
   must preserve that. In practice the workers exit on interrupt immediately, so
   `awaitTermination` returns well under 5s.
4. **Per-bot platform-thread budget is driven to O(1) then to O(0).** Phase 2 sets
   `processingThreads(1)` via `BackpressureConfig` in `ClientFactory` (in-repo, 4×
   reduction). Phase 3 (library) makes the processor pool virtual or shared, taking the
   per-bot platform cost to zero.
5. **Design target ceiling:** total platform threads = **O(cores + fixed pools)**,
   independent of bot count. Fixed pools = shared Netty IO group (4) + FJP carriers
   (~cores) + JDK HttpClient (~77) + Tomcat/JVM baseline (~30). After Phase 3 the only
   bot-count-scaling platform threads (the message processors) become O(cores) shared.
6. **No big-bang rewrite.** Phases 1 and 2 are localized edits to `Bot.java` and
   `ClientFactory.java`. Phase 3 is an isolated library change behind a version bump. Each
   phase is independently buildable, deployable, and verifiable against
   `jvm_threads_live_threads`.
7. **The library fix must also make `close()` self-sufficient** (shut down
   `processingExecutor`; remove/relax the `!isConnected` early-return so scenario/ping
   executors are released for never-connected clients), so future callers cannot
   reintroduce the leak by calling `close()`. Until then, Decision 1 (always `cleanUp()`)
   holds in the Bot repo.

## 5. Plan

### Phase 0 — (OPTIONAL, INTERIM) Thread-count liveness gate

**Purpose:** turn a 3-day silent wedge at the ceiling into a controlled restart. This
**masks** the leak; it is not a fix and must not delay Phases 1–3. Ship only if the user
wants a safety net while the real fix rolls out.

Steps:
- Add a custom Spring Boot `HealthIndicator` (e.g. `ThreadCountHealthIndicator`) that
  reads `ManagementFactory.getThreadMXBean().getThreadCount()` and returns `DOWN` when it
  exceeds a configurable threshold (default e.g. `bot.health.max-platform-threads=16000`,
  well below the ~20k death point).
- Wire it into the liveness group so Docker's healthcheck (or an added
  `--health-cmd`) restarts the container on breach. Note: the current compose relies on
  `restart: unless-stopped` with no healthcheck gate — adding one is a compose change
  outside this plan's write scope; flag for the Releaser/compose owner.

Files: new indicator under
`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/infrastructure/observability/`;
one property in `application.properties`.

### Phase 1 — (IN-REPO, PRIMARY) Reclaim message-processor threads on every teardown

**This phase stops the crash-loop.** It converts the leak (unbounded growth ~24/bot) into
a flat steady state (~4/bot). Biggest risk reduction; ship first.

Steps (all in `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/Bot.java`):
1. `stop()` (`:287-291`): replace `client.close()` → `client.cleanUp()`.
2. `restart()` (`:275-285`): replace the guarded `client.close()` (`:277-279`) with an
   **unconditional** `cleanUp()` of the outgoing client before building the new one
   (Decision 2). Keep the null check; drop the `isOpen()` guard.
3. `triggerFullReconnect()` (`:492-494`): same — unconditional `cleanUp()` of the
   outgoing client before spawning the loop.
4. `tryReconnectWs()` (`:627-643`): replace the `isOpen()`-guarded `close()` (`:629-631`)
   with an unconditional `cleanUp()` of the previous `this.client` before
   `clientFactory.newClient(...)` overwrites it (`:632`).
5. `closeClientQuietly()` (`:615-625`): replace `c.close()` (`:619`) with `c.cleanUp()`
   and drop the `isOpen()` guard so orphaned never-connected clients are reclaimed on
   terminal-DEAD paths.
6. Extract a small private helper (e.g. `tearDownClientQuietly(VingameWebSocketClient c)`)
   that null-checks and calls `cleanUp()` inside try/catch, and route all five sites
   through it to keep the change uniform and reversible.
7. Verify the `EventLoopGroup is NULL!` WARN (`ClientFactory.java:80`) does not fire on the
   reconnect path (it should not — the same `freshClientFactory` with the shared group is
   reused). If it does, that is a second, smaller leak (per-client owned EventLoopGroup)
   to fold in here.

No test framework changes required, but add one isolated unit test (per CLAUDE.md
"small features get tests") asserting the reconnect path calls `cleanUp()` exactly once
per outgoing client (mock `VingameWebSocketClient`, drive `tryReconnectWs`/`restart`).

### Phase 2 — (IN-REPO) Reduce per-bot platform cost 4× via `processingThreads(1)`

**Purpose:** after Phase 1 the steady state is 4 platform threads/bot × 6,600 = 26,400,
which still exceeds the ~20k death ceiling. Cutting to 1/bost gives ~6,600 + overhead,
under the ceiling, purely in-repo (no library change).

Steps (in `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/infrastructure/client/ClientFactory.java`):
1. In `buildClient(...)` (`:50-84`), add
   `.backpressure(BackpressureConfig.builder().processingThreads(1).build())` (keep the
   other defaults: queueCapacity 1000, DROP_OLDEST). This is a supported builder option
   (`VingameWebSocketClient.java:913-915`).
2. Make the count a property (`bot.ws.processing-threads`, default 1) so it can be tuned
   without a rebuild if a single processor thread proves a throughput bottleneck per bot.
3. Validate that a single processor thread keeps up with per-bot message rate at target
   scale (betting/Tai Xiu round cadence is low; slot is the highest). If not, the correct
   answer is Phase 3 (shared/virtual pool), not raising the per-bot count.

### Phase 3 — (LIBRARY) Make message-processing cost independent of bot count

**Purpose:** achieve Decision 5 (O(cores) platform threads, independent of bot count) —
required for comfortable 15× headroom and beyond. Separate, later, behind a version bump.
Repo is on the library owner's side (`https://github.com/vingame/websocket-parser`).

Options (pick one; A preferred):
- **A — Virtual-thread processor pool.** In `VingameWebSocketClient` constructor
  (`:140-145`), build `processingExecutor` with
  `Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("ws-msg-proc-"+name+"-",0).factory())`
  and submit `processingThreads` virtual workers. Workers block on `messageQueue.take()` —
  the canonical virtual-thread use case. Per-client platform cost → 0; message processing
  rides the shared FJP carrier pool (O(cores)).
- **B — Shared bounded platform pool across all clients.** Replace the per-client fixed
  pool with an application-shared bounded executor (sized to cores), passed in like the
  shared `EventLoopGroup`. Bounded and O(cores) but requires per-client dispatch/fairness.

Also in Phase 3, regardless of option (Decision 7):
- Make `close()` shut down `processingExecutor` itself, and relax the `!isConnected`
  early-return so scenario/ping executors are released for never-connected clients — so a
  future `close()` caller cannot reintroduce the leak.

Then in the Bot repo: bump `websocket-parser-core` in `pom.xml:169` to the new version,
re-run Phase 1/2 verification. Keep the Decision-1 `cleanUp()` calls (harmless and
defensive) even after the library self-heals `close()`.

## 6. Implementation Notes / Concerns

- **`cleanUp()` blocking:** it awaits up to `shutdownTimeoutMillis` (5s default) per call.
  On group stop this runs sequentially over the roster (`BotGroupRuntime.stopAllBots`
  `:269-275`). Workers blocked on `take()` respond to `shutdownNow()` interrupt
  immediately, so real wait is milliseconds — but do not call `cleanUp()` on the Netty IO
  loop (Decision 3). All current callers are off-IO; keep it that way.
- **Idempotency / double-teardown:** `cleanUp()`→`close()` is guarded by `isClosing`
  (`:412`) and the processingExecutor block tolerates an already-shutdown executor. The new
  helper (Phase 1 step 6) should still null-check and swallow exceptions, matching the
  existing `closeClientQuietly` contract (`:615-625`).
- **The library also self-spawns a `reconnect-<name>` virtual thread on disconnect**
  (`VingameWebSocketClient.java:708`) that shuts scenarios down — this is virtual, not a
  platform leak, and is orthogonal to our reconnect loop. Do not confuse it with the
  Bot-repo `reconnect-<userName>` thread (`Bot.java:476,495`).
- **Reconnect churn is the leak's throttle, not its cause.** The known bugs (WS AUTH race,
  server-side subscriber pruning — see CLAUDE.md) drive rebuilds. Phase 1 makes each
  rebuild free of platform-thread cost, so churn no longer kills the JVM; fixing churn
  itself is a separate reliability task, not required for this goal.
- **Phase 2 single processor thread** must not silently drop messages under burst: the
  queue is 1000-deep with DROP_OLDEST. At target scale confirm no elevated drop metric
  (`MessageQueueMetrics`) before declaring done; escalate to Phase 3 if it drops.
- **Do not** try to "fix" the leak by reducing `MAX_RECONNECT_CYCLES` or backoff — that
  only slows the bleed and changes bot semantics. The fix is teardown, not fewer retries.

## 7. Open Items

- Phase 0 healthcheck wiring touches docker-compose (outside this plan's write scope) —
  hand to the compose/Releaser owner if adopted. Marked optional.
- Phase 3 requires a release of `websocket-parser-core` by the library owner (the user).
  Sequenced last; Phases 1–2 do not depend on it.
- Log-volume reduction (~200 MB/hr → <500 GB/month at 15×): **out of scope**, tracked
  separately.
- Reconnect-churn root causes (WS AUTH race, subscriber pruning): out of scope here;
  Phase 1 removes their fatal consequence but not their occurrence.

## Scale math — platform threads vs. bot count

Let `B` = active bots, `P` = `processingThreads` per client, `F` = fixed overhead
(shared Netty IO 4 + FJP carriers ~cores + HttpClient ~77 + Tomcat/JVM ~30 ≈ **~150–200**).
Message-processor threads dominate; each **live** client contributes `P` platform threads,
each **leaked** client also contributes `P` forever.

| State | Platform-thread model | At B=442 | At B=6,600 (15×) | Survives? |
|---|---|---|---|---|
| **Today (leak)** | `F + P × (clients ever built)` — unbounded | grows to ~21,733 then dies | dies far sooner | **No** — crash-loop |
| **After Phase 1** (cleanUp, no orphans) | `F + P×B`, P=4 | ~1,950 flat | ~26,400 | No at 15× (>~20k ceiling), **but crash-loop at current scale is fixed** |
| **After Phase 2** (P=1) | `F + 1×B` | ~640 flat | ~6,800 | **Yes** — under ceiling, but still O(B) |
| **After Phase 3** (virtual/shared processors) | `F + O(cores)` | ~200 | ~200 | **Yes** — independent of B (Decision 5) |

Death ceiling observed ~20,000–21,733 platform threads. Target ceiling (Decision 5):
**O(cores + fixed pools)**, i.e. low hundreds regardless of bot count — met only after
Phase 3. Phase 1 stops the dying; Phase 2 buys 15× under an O(B) model; Phase 3 removes
the O(B) term entirely.

## Verification

All steps run against staging (Bot-1) after each phase deploys. The authoritative signal
is the existing metric `jvm_threads_live_threads` (platform threads only) — after each
fix it must go **flat with a stable bot roster**, and the per-active-bot platform-thread
ratio must drop as designed.

**V0 — Universal smoke (every phase):** app is up and scraping.
```
ssh Bot-1 "curl -s -o /dev/null -w '%{http_code}' http://localhost:8085/actuator/health"
```
Expect `200`.
```
ssh Bot-1 "curl -s 'http://localhost:9090/api/v1/query?query=jvm_threads_live_threads' | grep -o '\"value\":\[[^]]*\]'"
```
Expect a single numeric sample (the current platform-thread count).

**V1 — Leak stopped (after Phase 1), primary acceptance gate.** With a stable roster
(no create/delete of groups), sample the growth over a multi-hour window that includes
reconnect activity:
```
ssh Bot-1 "curl -s 'http://localhost:9090/api/v1/query?query=delta(jvm_threads_live_threads%5B6h%5D)' | grep -o '\"value\":\[[^]]*\]'"
```
Expect the delta to be **≈ 0** (bounded by roster changes), not the pre-fix climb of
thousands per hour. Before the fix this 6 h delta was strongly positive on every window;
after Phase 1 it must be flat/sawtooth-free. Confirm no fresh JVM death:
```
ssh Bot-1 "docker inspect bot-java-bot-manager-1 --format '{{.RestartCount}} {{.State.StartedAt}}'"
```
Expect `RestartCount` to **stop incrementing** across ≥2 days (pre-fix it rose ~1/1.3 days).

**V2 — Per-bot thread ratio (after Phase 1, then Phase 2).** Histogram of the leaking
thread family:
```
ssh Bot-1 'docker exec bot-java-bot-manager-1 sh -c "cat /proc/1/task/*/comm" | grep -c "^netty-ws-message-processor"'
```
Divide by the active-bot count (from the group health API or the roster you started).
- After **Phase 1**: expect ratio **≈ 4** (P=4, one live client per bot), and — critically
  — expect this count to be **stable over time** at fixed roster (pre-fix it only grew;
  the live reading was 10,852 ≈ 24.5/bot).
- After **Phase 2**: expect ratio **≈ 1** (P=1), i.e. the `netty-ws-message-processor`
  count ≈ active bot count.

**V3 — Orphan/pool leak reduced (after Phase 1).** The default-named library executors:
```
ssh Bot-1 'docker exec bot-java-bot-manager-1 sh -c "cat /proc/1/task/*/comm" | grep -c "^pool-"'
```
Expect this to stop growing and settle to a low steady value proportional to active
scenario stages (pre-fix ~2,420, inflated by orphaned never-connected clients). It should
no longer trend upward on a stable roster.

**V4 — Message throughput not starved (after Phase 2 only).** Confirm a single processor
thread per bot is not dropping messages:
```
ssh Bot-1 "curl -s 'http://localhost:9090/api/v1/query?query=rate(bot_messages_total%5B5m%5D)' | grep -o '\"value\":\[[^]]*\]'"
```
Expect message rate `> 0` and steady (bots still receiving StartGame/EndGame). If a
library-side dropped-message metric is exposed, expect it flat at ~0; if drops appear,
escalate to Phase 3 rather than raising `processingThreads`.

**V5 — Scale headroom (after Phase 2, then Phase 3).** Ramp the roster toward target and
watch the model hold:
```
ssh Bot-1 "curl -s 'http://localhost:9090/api/v1/query?query=jvm_threads_live_threads' | grep -o '\"value\":\[[^]]*\]'"
```
- After **Phase 2** at ~6,600 bots: expect platform threads **≈ 6,800 and stable**,
  comfortably below the ~20k death ceiling.
- After **Phase 3** at ~6,600 bots: expect platform threads in the **low hundreds**, and
  **flat as bot count rises further** (V2 ratio → ~0; the `netty-ws-message-processor`
  count no longer tracks bot count).

If the feature has no on-server verification beyond the universal smoke test: not
applicable — every phase above has an explicit, metric-grounded on-server check.
