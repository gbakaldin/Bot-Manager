# Resilience Hardening — Prevent the 2026-06-30 Bot-1 Total Outage

## Goal

On 2026-06-30 the entire Bot-1 staging stack went down — every API request (all
products) returned connection-refused. A single broken bot group (accounts that
could not authenticate) drove an unbounded reconnect loop that exhausted the OS
thread limit and killed the JVM; the container had no restart policy so the crash
became permanent; reconnect/log spam filled the Loki volume to 33.7 GB and the
root filesystem hit ENOSPC; the full disk then crash-looped mongod. This plan
installs defense-in-depth across four layers so that **no single failure can
again cascade to total outage** — any one layer (bounded reconnect, restart
policy, log/Loki caps, disk/thread alerting) should be sufficient to keep the
stack up.

This is a **plan only**. No production code, compose, or infra is modified by the
Architect. Phases are sequenced so the P0 restart policy is NOT enabled before the
P1 leak fix lands (otherwise the container crash-loops roughly every two hours).

## The failure chain this defends

```
broken group (UpstreamLoginException "account does not exist")
  → per-disconnect reconnect loop, unbounded, no back-off, PLATFORM threads
    → 30,717 reconnect attempts → OS thread limit
      → OutOfMemoryError: unable to create native thread → SIGSEGV (exit 139)   [heap was fine; 12G free]
        → container RestartPolicy=no → permanent downtime
   ‖ (in parallel)
  reconnect spam + INFO wire logging → Loki volume 33.7 GB, no retention cap
    → root fs 96% / ENOSPC
      → mongod FTDC cannot write metrics.interim.temp → fatal assert → mongo crash-loop
```

Each tier below breaks a different link.

## Findings — Current State

The reconnect subsystem has **already been partially reworked in this repo since
the deployed version** that crashed. The plan distinguishes what is fixed in
source (and only needs deploying/verifying) from what is genuinely still missing.

- **Reconnect path** — `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/Bot.java`
  - Reconnect events now spawn **virtual** threads, not platform threads:
    `Thread.ofVirtual().name("reconnect-" + userName).start(...)` at `Bot.java:404`
    (`onWsDisconnected`) and `Bot.java:421` (`triggerFullReconnect`).
  - A `reconnecting` `AtomicBoolean` CAS guard prevents a second concurrent loop
    per bot: `Bot.java:102`, checked at `Bot.java:396` and `Bot.java:410`.
  - Bounded back-off schedule `{5,10,30,60,60,60,60}` seconds at `Bot.java:31`,
    consumed in `runWsReconnectLoop` at `Bot.java:435-464`.
  - `performReauth()` at `Bot.java:486-499` marks the bot **DEAD and clears the
    guard** when re-auth throws — this is the terminating condition for the exact
    "account does not exist" case from the incident.
  - **Net:** the platform-thread-per-disconnect leak (incident root cause #1) is
    fixed in source. The threads are now virtual (cheap, not OS-thread-bound) and
    a failed re-auth ends the loop. This almost certainly is NOT the jar running
    on Bot-1 on 2026-06-30 (the incident shows `new Thread("reconnect-…")` platform
    threads). **Deploying current `main` is itself a large part of the fix.**
  - **Remaining gap A:** `runWsReconnectLoop` (`Bot.java:441` `while (!stopped)`)
    has **no absolute attempt cap**. If re-auth keeps *succeeding* but the WS keeps
    dropping (e.g. server-side subscriber pruning, see CLAUDE.md known bug), the
    loop resets `attempt = 0` at `Bot.java:461` and retries **forever** — a bot
    stuck in `RECONNECTING` indefinitely, spamming logs and auth calls. Bounded in
    thread count now, unbounded in time.
- **Watchdog** — `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java:83-86,326-345`
  fires `triggerFullReconnect` on game-message silence; routes into the same loop.
- **Group lifecycle / circuit breaker** —
  `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java`
  - `monitorHealth` (`:930-946`) counts DEAD bots and marks the **group** DEAD via
    `handleBotGroupDeath` (`:951-964`) once the DEAD fraction ≥ `deadBotGroupThreshold`.
  - Initial-start auth failures are caught per-bot, logged, metered
    (`incBotCreationFailure`, classified `auth` via `UpstreamLoginException` at
    `:404-406`), and the bot is simply omitted (`:368-385`). A group whose accounts
    all fail starts with **0 bots** — no loop on the create path.
  - `BotGroupRuntime.consecutiveFailures` exists (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/infrastructure/runtime/BotGroupRuntime.java:70`)
    but `monitorHealth` overwrites it with the raw DEAD count each tick (`:938`) —
    it is a snapshot, not a consecutive-failure counter.
  - **Remaining gap B:** a bot wedged in `RECONNECTING` (gap A) is never DEAD, so it
    never contributes to `deadBotGroupThreshold` — a whole group can churn forever
    without ever tripping the group circuit breaker.
- **OutputPrinter** — `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/util/OutputPrinter.java`
  logs every matched WS frame at **INFO**: `defaultOutputPrinter` → `log::info`
  (`:34`), `debugOutputPrinter` → `log.info("User {}: {}", …)` (`:52`),
  `prettifiedOutputPrinter` → `log::info` (`:105`). This is per-message wire
  detail at INFO — violates the CLAUDE.md logging norms (per-message = DEBUG,
  wire-level = TRACE) and is the disk-fill amplifier.
- **App file logging is already capped** —
  `/Users/gleb/IdeaProjects/Bot/src/main/resources/log4j2.properties:33-45`
  deletes `/app/logs/console-*.log` older than 7d OR once total exceeds 10 GB.
  **The incident disk-fill was NOT these files** — it was the **Loki named volume**
  (`bot-java_loki-data`, 33.7 GB) plus uncapped Docker `json-file` container stdout.
  Neither has a retention/rotation cap today.
- **Compose** — `/Users/gleb/IdeaProjects/Bot/docker-compose.yml`
  - `bot-manager` (`:16-35`) has **no `restart:` policy** (defaults to `no`).
  - `loki` (`:37-43`) runs the image default `-config.file=/etc/loki/local-config.yaml`
    — **no retention, no compactor**; `loki-data` named volume (`:93`) grows unbounded.
  - No service sets Docker `logging:` options → container stdout uses the daemon
    default (uncapped unless daemon.json sets it).
  - Observability (loki/promtail/grafana/prometheus) shares **one Compose project**
    with bot-manager → a bot redeploy tears the whole stack down (see MEMORY.md
    "Bot-1 single-compose layout").
- **Dockerfile** — `/Users/gleb/IdeaProjects/Bot/Dockerfile:16-18` ENTRYPOINT sets
  `-XX:MaxRAMPercentage=75.0` only. **No `-XX:+ExitOnOutOfMemoryError`.** With OOM
  the JVM may limp instead of dying cleanly, defeating any restart policy.
- **Metrics already exposed** —
  `/Users/gleb/IdeaProjects/Bot/src/main/resources/application.properties:52,60`
  exposes `/actuator/prometheus`; Micrometer's `JvmThreadMetrics` publishes
  `jvm_threads_live_threads`. Prometheus scrapes `bot-manager:8085` every 10s
  (`/Users/gleb/IdeaProjects/Bot/prometheus/prometheus.yml`). **No alerting rules,
  no node_exporter target in the committed config** — must confirm node_exporter on
  Bot-1.
- `bot_reconnects_total` already exists (`reason` tag: `watchdog|ws-disconnect`) —
  `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/infrastructure/observability/BotMetrics.java`
  — usable as an alert signal without new code.

## Per-aspect readiness / mapping

| Aspect | State | Notes |
|---|---|---|
| Platform-thread reconnect leak (root cause) | **partial — fixed in source, not deployed** | Bot.java:404/421 now virtual threads + guard + back-off. Deploy current `main`. |
| Absolute reconnect attempt cap → DEAD | **blocked / missing** | `runWsReconnectLoop` resets `attempt=0` forever (Bot.java:461). Gap A. |
| Group auth-failure circuit breaker | **partial** | monitorHealth marks group DEAD on DEAD-fraction; misses wedged-RECONNECTING bots (Gap B). |
| Container restart policy | **missing** | docker-compose.yml bot-manager has no `restart:`. Enable only after P1. |
| ExitOnOutOfMemoryError | **missing** | Dockerfile:16-18. |
| Docker json-file log rotation | **missing** | needs daemon.json (sudo) or per-service `logging:`. |
| Loki retention + compactor | **missing** | needs custom loki config bind-mount. |
| OutputPrinter INFO → DEBUG/TRACE | **ready to change** | OutputPrinter.java:34,52,105. |
| App file-log cap | **already done** | log4j2.properties:33-45 (10 GB / 7d). Not the incident cause. |
| Disk/thread/target-down alerts | **missing** | prometheus.yml has no rules; confirm node_exporter on Bot-1. |
| Observability/bot Compose decoupling | **missing (P3, optional)** | single shared project. |

## Architecture Decisions

1. **Deploying current `main` is treated as the primary remediation of root cause
   #1.** The plan does not re-implement the virtual-thread rework — it verifies it
   and closes the two residual gaps (A absolute cap, B wedged-RECONNECTING).
2. **Reconnect is bounded in time, not only in thread count.** Introduce an absolute
   cap on reconnect *cycles* per bot; on exhaustion the bot is marked **DEAD**
   (terminal), never retried forever. Default cap: a constant in `Bot.java`
   (proposed `MAX_RECONNECT_CYCLES = 10`, i.e. ~10 full back-off rounds). DEAD bots
   then feed the existing group circuit breaker.
3. **A wedged-RECONNECTING bot must eventually become DEAD** so it counts toward
   `deadBotGroupThreshold`. Decision 2's cap delivers this without new group logic.
4. **Restart policy is `unless-stopped`, enabled only in the same release as (or
   after) P1.** `unless-stopped` (not `always`) so an operator `docker compose stop`
   stays stopped.
5. **`-XX:+ExitOnOutOfMemoryError` is mandatory** so OOM produces a clean exit code
   that the restart policy acts on, instead of a half-dead JVM.
6. **Loki retention is enforced at Loki, not only at promtail/app** — a custom Loki
   config with `compactor` + `limits_config.retention_period` (proposed 168h/7d) on
   the `loki-data` volume. App-side file caps already exist and are left as-is.
7. **Docker `json-file` rotation is global via daemon.json** (`max-size=50m`,
   `max-file=5`) — an operator/sudo step on Bot-1. Per-service `logging:` blocks in
   compose are the fallback if daemon.json cannot be changed.
8. **Alerting reuses the running Prometheus+Grafana stack** (no new exporters in
   this repo beyond confirming node_exporter). Rule files are infra artifacts living
   alongside `prometheus.yml`.
9. **OutputPrinter wire logging drops to DEBUG** for the `User <name>:` per-frame
   lines; the raw `defaultOutputPrinter`/`prettifiedOutputPrinter` go to **TRACE**
   (wire-level). Production default level for `com.vingame.bot` stays DEBUG today but
   wire frames at DEBUG are gated behind the operator drill-in, not emitted at INFO.
10. **Code vs infra is explicitly labelled per step.** Architect/Dev own repo files;
    operator owns Bot-1 host (compose deploy, daemon.json, loki config, prometheus
    rules). Sudo-required steps are flagged — **Bot-1 has no passwordless sudo.**

---

## Plan

### Phase P0a — Logging amplifier (CODE, ship first, safe anytime)

Demote the OutputPrinter wire logging. This is independent of every other phase and
immediately cuts the disk-fill amplifier.

- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/util/OutputPrinter.java`
  - `:52` `debugOutputPrinter` printer: `log.info("User {}: {}", …)` → `log.debug(...)`.
  - `:34` `defaultOutputPrinter`: `log::info` → `log::trace` (raw wire frame).
  - `:105` `prettifiedOutputPrinter`: `log::info` → `log::trace`.
  - Update the class/method Javadoc that says "every `User <name>: ...` line in
    `console.log`" to reflect DEBUG/TRACE.
- Check callers of `defaultOutputPrinter`/`debugOutputPrinter` (grep `OutputPrinter`
  in `domain/bot`) to confirm none rely on these lines appearing at INFO for an
  operational signal; if one does, that signal moves to an explicit INFO log at the
  call site, not the wire printer.

**Verification (P0a):**
```
# Build
JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home mvn -q -DskipTests package
# Static check: no INFO-level wire printer remains
grep -nE "log\.info|log::info" src/main/java/com/vingame/bot/domain/bot/util/OutputPrinter.java   # expect: no matches
```
Expected: grep prints nothing. After deploy, a running group at default level emits
no `User <name>:` frame lines in `/app/logs/console.log` (expect zero matches for a
60s window); they reappear only when `com.vingame.bot` is set to DEBUG/TRACE.

### Phase P1 — Bound the reconnect / close the leak for good (CODE)

Pre-req for enabling the P0b restart policy.

1. **Absolute reconnect-cycle cap (Decision 2/3)** in
   `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/Bot.java`:
   - Add `private static final int MAX_RECONNECT_CYCLES = 10;` near `:31`.
   - In `runWsReconnectLoop` (`:435-464`): track completed full back-off cycles. At
     `:458-462`, instead of unconditionally `attempt = 0` after `performReauth()`,
     increment a `cycle` counter; once `cycle >= MAX_RECONNECT_CYCLES`, log WARN
     "giving up after N reconnect cycles", call `transitionStatus(BotStatus.DEAD)`,
     `reconnecting.set(false)`, and `return`.
   - Mirror the cap in `runAuthThenWsLoop` (`:466-484`) since it tail-calls the loop.
   - Keep `performReauth()`'s existing DEAD-on-throw (`:494-496`) — the auth-fail case
     already terminates; this cap covers the WS-keeps-dropping / pruning case (Gap A/B).
2. **Confirm the metric is incremented once per cycle, not per attempt** — the
   existing comment at `:436-439` documents single-count semantics; the cap must not
   re-increment `bot_reconnects_total`. A new gauge/counter is optional; reuse
   `bot_reconnects_total{reason=...}` for alerting.
3. **(Optional, low-risk) group circuit-breaker hardening** in
   `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:930-946`:
   include `RECONNECTING` bots that have exceeded the cap in the DEAD tally — but
   with Decision 2 they are already transitioned to DEAD, so `monitorHealth` needs no
   change. Document this rather than add code unless QA shows wedged bots persist.

**Verification (P1):**
```
# Unit/behaviour: a bot whose WS connect always fails and whose re-auth always
# succeeds must reach DEAD within MAX_RECONNECT_CYCLES, not loop forever.
JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home mvn -q -Dtest=BotReconnectTest test   # Dev adds this test
```
Staging simulation (Releaser, after deploy):
- Create a group with credentials that authenticate but whose WS subchannel is
  pruned (or point at a closed WS). Watch:
```
# Thread count must plateau, never climb unbounded
curl -s http://localhost:8080/actuator/prometheus | grep '^jvm_threads_live_threads'     # expect a flat value over 10 min, < a few hundred
# Bots converge to DEAD instead of perpetual RECONNECTING
curl -s http://localhost:8080/api/v1/bot-group/<id>/health | jq '.bots[].status' | sort | uniq -c   # expect DEAD, no growing RECONNECTING set after ~10 cycles
# Reconnect counter stops climbing once bots are DEAD
curl -s http://localhost:8080/actuator/prometheus | grep '^bot_reconnects_total'          # expect to stabilise
grep -c 'giving up after' /app/logs/console.log    # expect > 0 for the broken group
```
Expected: `jvm_threads_live_threads` stable; broken bots end DEAD; group trips
`deadBotGroupThreshold` → group DEAD (existing `handleBotGroupDeath` log line
`Bot group <id> has been marked as DEAD`).

### Phase P0b — Restart policy + clean OOM exit (INFRA + CODE, AFTER P1)

> Do not merge/deploy this before P1 is live — with the unbounded loop a restart
> policy crash-loops ~every 2h.

1. **CODE** — `/Users/gleb/IdeaProjects/Bot/Dockerfile:16-18`: add
   `"-XX:+ExitOnOutOfMemoryError",` to the ENTRYPOINT JVM args (Decision 5).
2. **CODE** — `/Users/gleb/IdeaProjects/Bot/docker-compose.yml` `bot-manager`
   service (`:16-35`): add `restart: unless-stopped` (Decision 4).
3. **OPERATOR (Bot-1)** — redeploy the compose project at `/home/sgame/bot-java/`
   with the new image + compose. No sudo required for compose itself.

**Verification (P0b):**
```
# Policy is in effect
docker inspect -f '{{.HostConfig.RestartPolicy.Name}}' bot-java-bot-manager-1     # expect: unless-stopped
# JVM flag present
docker exec bot-java-bot-manager-1 sh -c 'cat /proc/1/cmdline | tr "\0" " "'       # expect: ...-XX:+ExitOnOutOfMemoryError...
# Kill test: container comes back by itself
docker kill bot-java-bot-manager-1 ; sleep 20 ; docker ps --filter name=bot-manager   # expect: status "Up" again
# operator stop stays stopped (unless-stopped semantics)
docker compose stop bot-manager ; docker ps -a --filter name=bot-manager           # expect: Exited, not restarting
```

### Phase P0c — Loki retention + Docker log rotation (INFRA, sudo-flagged)

1. **OPERATOR + repo** — replace Loki's default config with a committed custom file
   (e.g. `loki/loki-config.yaml` in repo, bind-mounted like `prometheus.yml`):
   - `compactor: { retention_enabled: true, ... }`
   - `limits_config: { retention_period: 168h }`
   - update `docker-compose.yml` loki service (`:37-43`) to
     `-config.file=/etc/loki/loki-config.yaml` + the bind mount (Decision 6).
   - No sudo (bind-mount edit, like prometheus.yml).
2. **OPERATOR (Bot-1), SUDO REQUIRED** — `/etc/docker/daemon.json`:
   ```json
   { "log-driver": "json-file", "log-opts": { "max-size": "50m", "max-file": "5" } }
   ```
   then `sudo systemctl restart docker`. **Bot-1 has no passwordless sudo — this is a
   human operator step, schedule it.** Fallback if sudo is unavailable: add a
   `logging:` block (`driver: json-file`, `options: {max-size, max-file}`) to each
   service in `docker-compose.yml` (Decision 7) — repo change, no sudo.

**Verification (P0c):**
```
# Loki retention is configured
docker exec bot-java-loki-1 sh -c 'grep -E "retention_period|retention_enabled" /etc/loki/loki-config.yaml'   # expect both present
# Loki volume size is bounded over time (observe across days)
docker system df -v | grep loki-data        # expect it to plateau, not grow past the retention window
# Docker json-file rotation active (daemon.json path)
docker inspect -f '{{.HostConfig.LogConfig}}' bot-java-bot-manager-1   # expect max-size:50m max-file:5
# Or, fallback per-service path:
docker compose config | grep -A3 'logging:'  # expect options present on services
```

### Phase P2 — Detection / alerting (INFRA)

Reuse the running Prometheus + node_exporter + Grafana stack. Overlaps with the
CLAUDE.md "Health Diagnostics" backlog (Game/Auth/Bot/Balance down, RTP anomaly) —
this phase delivers the infra-down subset (disk, target-down, thread climb).

1. **Confirm node_exporter is scraped** — the committed
   `/Users/gleb/IdeaProjects/Bot/prometheus/prometheus.yml` has only the
   `bot-manager` job. If node_exporter is not a target on Bot-1, add it (operator).
2. **Add a rules file** (e.g. `prometheus/alert.rules.yml`, bind-mounted; reference
   it via `rule_files:` in `prometheus.yml`):
   - **DiskFree** — `node_filesystem_avail_bytes / node_filesystem_size_bytes < 0.15`
     for 5m (defends incident #3/#4 before ENOSPC).
   - **BotManagerDown** — `up{job="bot-manager"} == 0` for 2m (defends #1/#2).
   - **JvmThreadClimb** — `jvm_threads_live_threads > 800` for 10m, and/or
     `rate(bot_reconnects_total[5m]) > <threshold>` (early signal of the leak).
3. **Wire to Grafana alerting** — point a contact channel (the operator's existing
   notification path) at these rules.

**Verification (P2):**
```
# node metrics are scrapeable
curl -s 'http://localhost:9090/api/v1/query?query=node_filesystem_avail_bytes' | jq '.data.result | length'   # expect > 0
# rules loaded
curl -s http://localhost:9090/api/v1/rules | jq '.data.groups[].rules[].name'    # expect DiskFree, BotManagerDown, JvmThreadClimb
# fire-test BotManagerDown
docker stop bot-java-bot-manager-1 ; sleep 150
curl -s 'http://localhost:9090/api/v1/alerts' | jq '.data.alerts[].labels.alertname'   # expect "BotManagerDown" firing
docker start bot-java-bot-manager-1
# thread metric present (the leak signal)
curl -s http://localhost:8080/actuator/prometheus | grep '^jvm_threads_live_threads'   # expect a value
```

### Phase P3 — Structural hardening (INFRA, OPTIONAL / lower priority)

1. **Decouple observability from bot-manager** — split Grafana/Prometheus/Loki/
   Promtail into their own Compose project so a bot redeploy no longer takes the
   monitoring stack down (MEMORY.md "Bot-1 single-compose layout"; smoke currently
   must re-verify them). Keep shared network for scraping.
2. **Mongo disk headroom it cannot be starved out of** — give `mongo-data` (and at
   minimum its `diagnostic.data`) protected space, e.g. a dedicated volume/partition
   or a reserved-space filesystem, so a future log/Loki runaway cannot ENOSPC mongod
   (defends incident #4 even if P0c is misconfigured).

**Verification (P3):**
```
docker compose -p observability ps   # expect monitoring stack in its own project
# bot redeploy does not stop grafana/prometheus/loki:
docker compose -p bot-java up -d bot-manager ; docker ps --filter name=grafana --filter name=prometheus   # expect still Up
```

---

## Implementation Notes / Concerns

- **Sequencing is load-bearing.** Ship P0a (logging) and P1 (bound reconnect)
  first; only then P0b (restart policy + ExitOnOOM). Enabling restart before P1 turns
  an outage into a crash-loop.
- **The biggest single win is simply deploying current `main`** — the platform-thread
  leak is already gone in source. Confirm the Bot-1 jar predates the virtual-thread
  rework (look for `new Thread("reconnect-…")` vs `Thread.ofVirtual().name("reconnect-…")`).
- **Do not relax the existing app file-log cap** (log4j2.properties:33-45); it is
  correct and was not the disk-fill source. The Loki volume and Docker stdout are.
- **`unless-stopped` vs `always`:** use `unless-stopped` so deliberate operator stops
  persist; `always` would resurrect a container the operator just stopped.
- **OutputPrinter callers:** verify nothing downstream greps these frames at INFO as
  an operational check before demoting; move any such signal to an explicit INFO log.
- **Loki config drift:** overriding `-config.file` means the image default is no
  longer used — restate any defaults you still need (schema, storage paths), same
  gotcha already documented for the Prometheus `command:` override in
  docker-compose.yml:73-79.
- **node_exporter assumption:** the committed prometheus.yml does not scrape it; if
  it is not present on Bot-1 the DiskFree alert silently never fires. Confirm first.
- **Sudo reality:** the daemon.json change is the only hard sudo dependency. The
  per-service `logging:` fallback removes that dependency at the cost of repeating the
  block per service — acceptable.

## Open Items

- Confirm whether `node_exporter` is running and scraped on Bot-1 (blocks P2 DiskFree
  rule). Operator input needed.
- Confirm the exact host path / volume layout for `mongo-data` to scope P3.2.
- Decide final numeric thresholds: `MAX_RECONNECT_CYCLES` (proposed 10), Loki
  `retention_period` (proposed 168h), JvmThreadClimb threshold (proposed 800),
  daemon.json `max-size/max-file` (proposed 50m/5). These are Dev/operator-tunable.
- P3 is explicitly optional and may be deferred to the Grafana/observability roadmap
  item rather than this hotfix train.

## Cross-references — supersedes / closes

- **CLAUDE.md Known Bugs → "WebSocket AUTH Race Condition / No reconnection logic
  exists for any bot… scheduler threads keep trying to send messages in a tight
  loop."** P1 (+ the already-landed virtual-thread rework) closes the unbounded-retry
  half of this. The AUTH-race set-`connected`-on-IO-thread fix is separate and out of
  scope here.
- **CLAUDE.md Known Bugs → "Server-Side Subscriber Pruning (Silent Zombie Bots)."**
  The watchdog (BettingMiniGameBot:326-345) already turns zombies into reconnects;
  P1's cap ensures pruned bots that cannot recover end DEAD instead of looping.
- **CLAUDE.md Backlog → Health Diagnostics table** (Game/Auth/Bot down, Disk via
  infra). P2's DiskFree / BotManagerDown / JvmThreadClimb rules deliver the
  infrastructure-down subset; the in-app diagnostic checks remain a separate backlog
  item.
- **MEMORY.md "Bot-1 single-compose layout"** — P3.1 addresses it.

## Verification

Consolidated post-deploy checks for the Releaser on Bot-1 staging, in deploy order.
Beyond the universal smoke test (stack up, `/actuator/health` UP, Grafana/Prometheus/
Loki reachable), run:

1. **P0a — wire logging demoted.**
   `grep -nE "log\.info|log::info" src/.../OutputPrinter.java` → expect no matches at
   build; on staging, a running group emits zero `User <name>:` frame lines in
   `/app/logs/console.log` over a 60s window at default level.
2. **P1 — reconnect is bounded.** With a deliberately broken group (WS unreachable),
   `curl -s localhost:8080/actuator/prometheus | grep '^jvm_threads_live_threads'`
   stays flat (< a few hundred) over 10 min; `curl -s
   localhost:8080/api/v1/bot-group/<id>/health | jq '[.bots[].status]'` shows bots
   reaching DEAD (no growing RECONNECTING set); `grep -c 'giving up after'
   /app/logs/console.log` > 0; group log line `Bot group <id> has been marked as DEAD`
   appears once threshold is crossed.
3. **P0b — restart policy + clean OOM.**
   `docker inspect -f '{{.HostConfig.RestartPolicy.Name}}' bot-java-bot-manager-1`
   → expect `unless-stopped`. `docker exec … cat /proc/1/cmdline` contains
   `-XX:+ExitOnOutOfMemoryError`. `docker kill bot-java-bot-manager-1; sleep 20;
   docker ps` → container is `Up` again.
4. **P0c — log caps.**
   `docker exec bot-java-loki-1 grep retention_period /etc/loki/loki-config.yaml`
   → present; `docker inspect -f '{{.HostConfig.LogConfig}}' bot-java-bot-manager-1`
   → `max-size:50m max-file:5` (or `docker compose config | grep -A3 logging:` for the
   fallback path).
5. **P2 — alerts loaded and fire.**
   `curl -s localhost:9090/api/v1/rules | jq '.data.groups[].rules[].name'` lists
   `DiskFree`, `BotManagerDown`, `JvmThreadClimb`. Stop bot-manager 150s →
   `curl -s localhost:9090/api/v1/alerts` shows `BotManagerDown` firing; restart it.
6. **P3 (if shipped).** `docker compose -p observability ps` shows the monitoring
   stack in its own project; redeploying bot-manager leaves grafana/prometheus/loki
   `Up`.

If a phase ships code-only with no on-server behaviour beyond the above, the
universal smoke test plus the relevant numbered check above is sufficient — there is
no additional hidden server-side verification.
