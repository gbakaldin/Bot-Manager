# Logging Pipeline Fix

Branch: `feat/logging-pipeline-fix`

Source diagnosis: `/Users/gleb/IdeaProjects/Bot/docs/reviews/OBSERVABILITY/diagnosis.md`

---

## 1. Goal

Two independent fixes to make staging logs usable for the operator (`sgame`) and queryable in Grafana/Loki.

**Fix A — Bind-mount the log directory, no-root approach.** Today logs land in a Docker-managed named volume that only root can read on the Bot-1 host. Switch to a bind-mount at a host path readable/writable by `sgame` without breaking Promtail's tail. **Hard constraint discovered after Phase 1 shipped:** the `sgame` operator on Bot-1 has no sudo. Phase 1's original design — `chown 1006:1007` + `chmod 2775` + `usermod -aG 1007 sgame` — assumed one-time root invocation we cannot get. Phase 1b replaces that with a no-root strategy: run the container as the host user (`user: "${HOST_UID}:${HOST_GID}"` in compose, written by `deploy.sh` into `.env`). The container's baked-in `botmanager` UID/GID becomes irrelevant for the bind-mount path; the process writes as `sgame`'s UID directly, no privileged ops anywhere.

**Fix B — Propagate MDC into every thread that emits log lines for a bot.** Today only ~7.1% of bot lines carry `botGroupId` (diagnosis E8). Steady-state lines come from threads that never had MDC set: the per-client Netty message-processor pool, the per-bot countdown/watchdog schedulers, library-internal Netty I/O and virtual reconnect threads, our own reconnect virtual threads, and the WS callback handlers we register. Promtail promotes `botGroupId/environmentId/gameType` to Loki labels, so the missing-MDC lines fall into a catch-all `{level=…}` stream and disappear from any per-group Grafana panel. After this fix at least 90% of bot log lines must carry `botGroupId`.

The two fixes are independent and can ship in any order; this plan sequences the volume fix first because it's config-only and the verification of every later phase depends on the operator being able to read `console.log` themselves.

---

## 2. Findings — Current State

### 2.1 Logging configuration (Java side)

- `/Users/gleb/IdeaProjects/Bot/src/main/resources/log4j2.properties:22` — rolling file at `/app/logs/console.log`, hourly rotation, JSON layout.
- `/Users/gleb/IdeaProjects/Bot/src/main/resources/log4j2-json-template.json:32-51` — emits MDC keys `botGroupId`, `botId`, `environmentId`, `gameType`, `botUserName` as top-level JSON fields. If the MDC slot is empty, the field is omitted (so Promtail's `labels` stage drops the line into a catch-all stream).
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/common/logging/BotMdc.java:15-58` — the only helper. `set(...)` populates all five keys; `setGroupContext(...)` populates only `botGroupId` and `environmentId`; `clear()` removes all five. All operations write directly to SLF4J's `MDC` (ThreadLocal-backed; works on virtual threads — see file's own javadoc line 11-14).

### 2.2 Where MDC is set today

- `Bot.initialize()` at `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/Bot.java:88-120` — `set(...)` on entry, `clear()` in finally. Only covers the synchronous initialization call. **Nothing snapshots the MDC for later use.**
- `BotGroupBehaviorService.createBotsInParallel(...)` at `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:273-288` — `setGroupContext(...)` on each `CompletableFuture.supplyAsync` bot-creation virtual thread, `clear()` in finally. Fine for the creation phase only.
- `BotGroupBehaviorService.startHealthMonitoring(...)` at `BotGroupBehaviorService.java:539-548` — group-level MDC on the health-monitor scheduled task. Fine.
- `BotGroupBehaviorService.startPeriodicLogoutScheduler(...)` at `BotGroupBehaviorService.java:625-635` — group-level MDC on the logout scheduled task. Fine.

### 2.3 Threads that emit log lines WITHOUT MDC today

Confirmed by reading code and matching against diagnosis E8's thread-name breakdown (2,000 Netty-processor lines + 750 `pool-N-thread-1` lines):

| Thread family | Spawn site | Logs MDC-less? |
| --- | --- | --- |
| `netty-ws-message-processor-ws-<userName>` (per-client fixed pool) | `VingameWebSocketClient.java:140-145` (library) | Yes — runs every scenario `onMessage`, every `OutputPrinter.peek`, every `sendAsync` execution |
| `multiThreadIoEventLoopGroup-2-N` (Netty I/O loop, shared) | library, set by `EventLoopGroup` config | Yes — library's own `Client {}: ...` logs at lines 269, 274, 293, 306, 400, 461, 482, 1014, 1029, 1033, 1039 |
| `reconnect-<userName>` (library-spawned virtual thread for disconnect dispatch) | `VingameWebSocketClient.java:708` (library) | Yes — runs our `onDisconnect` listener and `onWsStatusChange` listener |
| `reconnect-<userName>` (our own virtual threads) | `Bot.java:271` and `Bot.java:285` | Yes — runs `runWsReconnectLoop` / `runAuthThenWsLoop`, calls `transitionStatus`, `log.warn/error`, `log.info` |
| `countdown-<userName>` (our virtual scheduler) | `BettingMiniGameBot.java:116-118` | Currently no log lines, but adding any would inherit no MDC |
| `watchdog-<userName>` (our virtual scheduler) | `BettingMiniGameBot.java:95-97` | Yes — `onWatchdogExpired` logs at line 141 |
| Per-stage scenario timeout schedulers `pool-N-thread-1` | `PipelineStage.java:90` in the library (`Executors.newSingleThreadScheduledExecutor()`) | Yes — picks up scenario timeouts and the `SendAsync` betting loop |
| `bot-creation-N` (virtual pool) | `BotGroupBehaviorService.java:130-132` | Covered (sets group context, line 274) |
| `health-monitor-<groupId>` (virtual) | `BotGroupBehaviorService.java:534-536` | Covered (line 540) |
| `logout-scheduler-<groupId>` (virtual) | `BotGroupBehaviorService.java:618-622` | Covered (line 626) |
| Tomcat `http-nio-8085-exec-N` | Spring Boot defaults | Out of scope — Spring/HTTP lines do not need bot MDC |

### 2.4 WS callback sites we register from this repo

All in `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/Bot.java`:

- `Bot.java:251-257` — `wsClient.onWsStatusChange(...)` lambda. Fires on the Netty I/O thread via `fireWsStatus` (`VingameWebSocketClient.java:748-753`), and on the library's `reconnect-<name>` virtual thread (line 708-711) when DISCONNECTED is fired.
- `Bot.java:258-260` — `wsClient.onDisconnect(...)` lambda. Always fires on the library's `reconnect-<name>` virtual thread.

In `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java`:

- `BettingMiniGameBot.java:305-314` — `onMessage(...)` scenario handlers (`onSubscribe`, `onStartGame`, `onUpdate`, `onEndGame`). Fire on `netty-ws-message-processor-ws-<userName>`.
- `BettingMiniGameBot.java:308-313` — `sendAsync` with `bet()` supplier and `resolveBetCondition()` predicate. Both run on a scenario-owned `pool-N-thread-1` (created at `PipelineStage.java:90` in the library).
- `BettingMiniGameBot.java:333-337` — `OutputPrinter.debugOutputPrinter(...)` scenario. The `peek(printer)` consumer runs in the message-processor pool. This is the source of every `User test_bcmini_XXX: ...` log line.

### 2.5 OutputPrinter

`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/util/OutputPrinter.java` — own-repo utility. `debugOutputPrinter` (line 35-37) builds a pipeline that emits one `log.info("User {}: {}", name, s)` per matched RECEIVED message. Logger name is `OutputPrinter`. Runs on `netty-ws-message-processor-ws-<userName>`. **In scope** — same MDC problem as everything else on that thread.

### 2.6 websocket-parser library

- Source lives at `/Users/gleb/IdeaProjects/WebSocket Parser/websocket-parser-core/`. Same author/org as this repo.
- `pom.xml:160-161` declares `com.vingame:websocket-parser-core:2.3.10` (not `1.0-SNAPSHOT` as the prompt states — the prompt and `CLAUDE.md` are stale; the actual dep is `2.3.10`).
- Library has no MDC awareness: `grep -rn "MDC\|ThreadContext" websocket-parser-core/src/main` returns nothing.
- Threads the library spawns that we cannot reach from our code:
  - `netty-ws-message-processor-<name>` — `VingameWebSocketClient.java:140-145` (`Executors.newFixedThreadPool` with our own `ThreadFactory`-equivalent inline lambda).
  - `reconnect-<name>` virtual thread — `VingameWebSocketClient.java:708` (`Thread.ofVirtual().name(...).start(...)`).
  - Scenario timeout schedulers — `PipelineStage.java:90` (`Executors.newSingleThreadScheduledExecutor()` — no thread name customisation, so they show up as `pool-N-thread-1`).
  - `SendAsync` executor — same pattern, registered in `PipelineContext.executors`.
- The library does NOT expose any hook for "decorate every thread I spawn" or "wrap every callback I invoke." The closest reachable surfaces are the public listener registrations (`onDisconnect`, `onWsStatusChange`) and the `Scenario` / `PipelineStage` builder DSL we use from `BettingMiniGameBot`.

### 2.7 Docker / volume layout

- `/Users/gleb/IdeaProjects/Bot/docker-compose.yml:27-28` — `bot-manager.volumes: [logs-data:/app/logs]`.
- `/Users/gleb/IdeaProjects/Bot/docker-compose.yml:46-47` — `promtail.volumes: [logs-data:/logs:ro, ./promtail-config.yml:/etc/promtail/config.yml:ro]`.
- `/Users/gleb/IdeaProjects/Bot/docker-compose.yml:71` — `logs-data:` declared as a named volume (no driver_opts).
- `/Users/gleb/IdeaProjects/Bot/Dockerfile:4` — `RUN groupadd -r -g 1007 botmanager && useradd -r -u 1006 -g botmanager botmanager` — fixed numeric IDs.
- `/Users/gleb/IdeaProjects/Bot/Dockerfile:8` — `RUN mkdir -p /app/logs && chown -R botmanager:botmanager /app`.
- `/Users/gleb/IdeaProjects/Bot/deploy.sh:6-7` — already does `mkdir -p logs && docker-compose up -d`. The `logs` directory is created on the host but currently unused because the volume mapping is named.

### 2.8 Promtail config (Bot-1, not in this repo)

Diagnosis E4 confirms `__path__: /logs/*.log` and `pipeline_stages: - labels: { level, botGroupId, environmentId, gameType }`. No change to Promtail config is required for either fix — switching to a bind-mount keeps the in-container path `/logs/`, and adding MDC keys is what Promtail is already waiting for.

---

## 3. Per-aspect readiness / mapping

| Aspect | Status | Notes |
| --- | --- | --- |
| Bind-mount the log dir (Phase 1, shipped) | Done | Commits `129ef20`, `13e8f24`. Compose switched to `./logs:/app/logs` and `./logs:/logs:ro`. Verification superseded by Phase 1b. |
| No-root container UID (Phase 1b) | Ready | Add `user: "${HOST_UID}:${HOST_GID}"` to compose; `.env` written by `deploy.sh`. Removes the chown/sudo branch and rewrites `host-prep-logs.sh` as no-root cleanup. |
| MDC snapshot helper on `Bot` | Ready | Trivial — `Map<String,String>` field captured in `initialize()`. |
| Wrap `Bot.java:271` reconnect virtual thread | Ready | Direct edit. |
| Wrap `Bot.java:285` reconnect virtual thread | Ready | Direct edit. |
| Wrap our `onWsStatusChange` lambda (`Bot.java:251-257`) | Ready | Wrap the lambda body. |
| Wrap our `onDisconnect` lambda (`Bot.java:258-260`) | Ready | Same. |
| Wrap `onSubscribe/onStartGame/onUpdate/onEndGame` handlers in `BettingMiniGameBot` | Ready | Wrap the four `onMessage(...)` callbacks at `BettingMiniGameBot.java:305-314`. |
| Wrap `sendAsync` betting loop (`BettingMiniGameBot.java:308-313`) | Ready | Wrap `bet()` supplier and `resolveBetCondition()` predicate. |
| Wrap `OutputPrinter` peek consumer | Partial | `OutputPrinter.debugOutputPrinter` builds the pipeline internally; either change its `Consumer<String>` to MDC-aware (preferred, single edit in `OutputPrinter.java`) or replace it from `BettingMiniGameBot.onStart` with an inline MDC-wrapped variant. |
| Wrap `watchdog-<userName>` task in `BettingMiniGameBot.java:132-136` | Ready | Wrap the `Runnable` passed to `schedule(...)`. |
| Wrap `countdown-<userName>` task in `BettingMiniGameBot.java:121-126` | Ready (defensive) | No logs today; wrap anyway for future-proofing — cheap. |
| Library-internal Netty `multiThreadIoEventLoopGroup-2-N` lines (`Client {}: ...`) | Blocked on decision | Cannot wrap from our repo. Three options: (a) patch `websocket-parser-core`, (b) accept these lines stay MDC-less (they're not per-bot-attributable on the IO loop anyway — the loop is shared across all clients), (c) parse the `Client {}: ws-<userName>` log message in Promtail and re-derive `botGroupId`. See Architecture Decision 4. |
| Library `netty-ws-message-processor-<name>` thread lines (library-internal, not scenario callbacks) | Same | The processor thread name embeds `<name>` = `ws-<userName>`. Library's own log lines from this thread (lines 171, 189, 192, 219, 225, 232, 243) are infrequent. |
| Bot-creation parallelism MDC (`BotGroupBehaviorService.java:273-288`) | Already done | `setGroupContext` set + `clear` in finally. No change needed. |

---

## 4. Architecture Decisions

1. **Bind-mount path is `/home/sgame/bot-java/logs/` on the host, mapped to `/app/logs` in the bot-manager container and `/logs:ro` in promtail.** Same host path the operator has been (incorrectly) inspecting. The stale `polkitd`-owned files in that directory (May 12-13) must be moved aside as part of the cut-over so the new mount is clean. **Ownership is no longer a concern** — see Decision 2 — because the container process runs as the same UID/GID as the `sgame` host user, so files it writes are by definition sgame-readable. The host directory is already `drwxrwxrwx sgame:sgame` (per diagnosis E2), which is sufficient for any UID to read/write/rotate inside it.

2. **No-root strategy: run the container as the host user.** Add `user: "${HOST_UID}:${HOST_GID}"` to the `bot-manager` service in `docker-compose.yml`. `deploy.sh` writes `HOST_UID=$(id -u)` and `HOST_GID=$(id -g)` into a `.env` file at deploy time; Compose auto-substitutes from `.env`. The image's baked-in `USER botmanager` (UID 1006 / GID 1007) is overridden at runtime by the compose `user:` directive — the image stays as-is for standalone runs. The bind-mount shadows `/app/logs`, so the Dockerfile's `chown -R botmanager:botmanager /app` is moot for the logs path. Promtail's mount stays read-only and needs no `user:` override (it runs as root inside the container by default, which is fine for read on a world-readable bind-mount). **Zero sudo, zero `chown`, zero `chmod`, zero `usermod` anywhere in the cut-over.**

   **Why not change the Dockerfile.** The compose `user:` directive overrides the image's `USER` line for this deployment; the image's ownership of `/app/logs` is shadowed by the bind-mount. Leaving the Dockerfile unchanged keeps the image self-contained for any standalone run (e.g., local `docker run` without compose) — in that case it falls back to writing as `botmanager` into the named/anonymous volume, which is the legacy behaviour and still works.

   **Fallback if `HOST_UID`/`HOST_GID` are unset.** Compose will substitute the empty string and the `user:` directive becomes `user: ":"`, which Docker rejects with a clear error at `up` time. This is the desired failure mode — we hard-fail loud rather than silently fall back to UID 1006 and recreate the original ownership problem. `deploy.sh` is the only supported entry point and it always writes `.env`; if an operator runs `docker-compose up -d` manually without going through `deploy.sh`, the deploy aborts and they get told to run `deploy.sh`.

3. **Use Log4j2 `ThreadContext` indirectly via SLF4J `MDC`.** Already in use (`BotMdc.java`). Do not introduce Java 21 `ScopedValue` — the existing ThreadLocal approach works on virtual threads (confirmed by `BotMdc.java:11-14`) and switching paradigms is out of scope. Stay with `MDC.put/remove` and add a snapshot/restore pattern.

4. **For library-internal log lines that originate on shared Netty IO threads (`multiThreadIoEventLoopGroup-2-N`) and library-spawned `reconnect-<name>` virtual threads: do nothing in Phase 4.** Rationale:
   - The shared Netty event-loop processes frames for many clients on the same thread; setting MDC on entry is wrong (cross-bot contamination) unless we add a per-channel `ChannelHandler` that pushes MDC keyed off the channel's attached client name. Doable but invasive.
   - The library's `reconnect-<name>` virtual thread runs **our** `onDisconnect` listener; we can wrap our listener so its inner log lines have MDC. The library's own `Client {}: ...` lines on that thread will remain MDC-less.
   - Net coverage after Phases 2–3 + the `OutputPrinter` wrap is projected at ~90–95% (the ~2,000 message-processor lines and ~750 scheduler lines from diagnosis E8 are all reachable via callback/handler wrapping; remaining gap is the handful of `Client {}: ...` lines per session).
   - If the >90% acceptance criterion in Phase 5 is missed, escalate to Phase 4 with option (a): patch `websocket-parser-core` to add a `clientMdcSupplier` API. Architect's call deferred to that point. Document the tradeoff in this plan but do not commit to a library patch upfront — it would block on a release of `websocket-parser-core` which is outside this repo's control.

5. **`OutputPrinter.debugOutputPrinter` will be changed in-place** to accept an MDC snapshot and apply it around the `Consumer<String>` invocation. This keeps the callsite in `BettingMiniGameBot.java:333-337` clean and avoids duplicating the wrap logic.

6. **`Bot.mdcSnapshot` is captured once at the end of `initialize()` (just before the `clear()` in finally)** and stored as a `protected final Map<String, String>` (or `volatile` if Lombok forces a field-init order quirk). It must include all five MDC keys (`botGroupId`, `botId`, `environmentId`, `gameType`, `botUserName`). Using `MDC.getCopyOfContextMap()` is the canonical snapshot call.

7. **`mdcWrap(Runnable)` and `mdcCall(Callable<T>)` live on `Bot`** (not in `BotMdc`), because they need the bot's own snapshot. They follow this contract:
   - On entry: stash the current MDC via `MDC.getCopyOfContextMap()`, then `MDC.setContextMap(snapshot)`.
   - On exit (finally): if the stashed map was null, `MDC.clear()`; otherwise `MDC.setContextMap(stashed)`. This makes the wrap re-entrant and safe on threads that already had a different MDC.

8. **No changes to log4j2 JSON template or properties.** Both already emit the five keys; the fix is upstream.

9. **No changes to Promtail config in this repo.** Promtail config lives on Bot-1 (`/home/sgame/bot-java/promtail-config.yml`); diagnosis E4 confirms it already promotes the right labels. The bind-mount keeps the in-container scrape path identical.

---

## 5. Plan

### Phase 1 — Bind-mount the logs volume (compose + host prep) — SHIPPED

**Status:** Shipped on `feat/logging-pipeline-fix` at commits `129ef20` (initial) and `13e8f24` (polish). Superseded by Phase 1b below — DO NOT re-run Phase 1's verification.

**What it shipped (for the record):**
- `docker-compose.yml`: switched both `bot-manager` and `promtail` from named volume `logs-data` to bind-mount `./logs`. Removed the `logs-data:` top-level volume declaration.
- `deploy.sh`: added `mkdir -p logs` plus a chown/chmod branch guarded by `id -u` / `sudo -n`, with WARNING messages pointing to `scripts/host-prep-logs.sh` when no root is available.
- `scripts/host-prep-logs.sh`: one-time root script doing `chown 1006:1007`, `chmod 2775`, `usermod -aG 1007 sgame`, and moving stale `polkitd`-owned files aside.

**Why it's superseded:** The shipped design assumed root access on Bot-1 for one-time host prep. After Phase 1 shipped, the operator confirmed `sgame` has no sudo, period. The chown/chmod/usermod branch is dead code on Bot-1 and the warning messages it prints are the only outcome of `deploy.sh`'s ownership branch — the container then crashes on EACCES against an unprivileged host dir. Phase 1b removes the dependency on root entirely.

---

### Phase 1b — Run container as host user (no-root cut-over)

**Scope:** Make the bind-mount work without any privileged host operation by running the bot-manager container as the host user (`sgame`'s UID/GID) instead of as `botmanager:1006`. The image is unchanged. Phase 1b is the new contract; Phase 1's verification is replaced by Phase 1b's.

**Diff vs. shipped Phase 1 — what Dev removes:**
- `deploy.sh`: delete the entire `if [ "$(id -u)" -eq 0 ]; then ... elif command -v sudo ... else ... fi` block (lines 16-30 of the current file) including the WARNING messages.
- `scripts/host-prep-logs.sh`: rewrite (see below). Old version required root and did chown/chmod/usermod.

**Diff vs. shipped Phase 1 — what Dev adds:**
- `docker-compose.yml`: add `user: "${HOST_UID}:${HOST_GID}"` to the `bot-manager` service. No other service changes. The bind-mount lines stay as Phase 1 shipped them.
- `deploy.sh`: write `.env` with `HOST_UID` and `HOST_GID` lines on every invocation (overwrite, not append — so a re-deploy by a different host user gets fresh values).
- `.gitignore`: add `.env` if not already present (current file has no `.env` entry — confirmed at `/Users/gleb/IdeaProjects/Bot/.gitignore`).
- `scripts/host-prep-logs.sh`: rewrite as a non-root, cleanup-only, idempotent one-shot. Specifically:
  - `set -euo pipefail`.
  - Do NOT refuse to run as anyone; do NOT require root.
  - `mkdir -p /home/sgame/bot-java/logs`.
  - Move any `console.log` and `console-*.log` in `/home/sgame/bot-java/logs/` aside into `/home/sgame/bot-java/logs.bak.<timestamp>/`. Use the same timestamp pattern as the current script (`$(date +%Y%m%d-%H%M%S)`). The directory is world-writable per diagnosis E2, so `sgame` can move polkitd-owned files inside it even without owning them.
  - Print final `ls -la /home/sgame/bot-java/logs/`.
  - No `chown`, no `chmod`, no `usermod`.
  - Keep the script as a separate one-shot rather than inlining into `deploy.sh` — the file rollover is a deliberate cut-over event, not implicit in every deploy. After cut-over the script is unused but kept for emergencies.

**Files touched:**
- `/Users/gleb/IdeaProjects/Bot/docker-compose.yml` — one-line add inside the `bot-manager` service.
- `/Users/gleb/IdeaProjects/Bot/deploy.sh` — strip the chown/sudo branch; add the `.env` write.
- `/Users/gleb/IdeaProjects/Bot/scripts/host-prep-logs.sh` — rewrite.
- `/Users/gleb/IdeaProjects/Bot/.gitignore` — append `.env`.
- Dockerfile is NOT touched (see Architecture Decision 2).

**Acceptance criteria:**
- After `./deploy.sh` on Bot-1, `.env` exists in the repo root with the running operator's `HOST_UID` and `HOST_GID` (for `sgame` on Bot-1, that's whatever `id -u sgame` returns — NOT 1006).
- `docker inspect bot-java-bot-manager-1 --format '{{.Config.User}}'` returns `"<HOST_UID>:<HOST_GID>"` and matches `id -u`/`id -g` for `sgame`.
- The container writes to `/app/logs/console.log` and the file appears at `/home/sgame/bot-java/logs/console.log` owned by the host `sgame` user (NOT 1006).
- `tail -f /home/sgame/bot-java/logs/console.log` as `sgame` (no sudo) streams the live log.
- `host-prep-logs.sh` runs cleanly as `sgame` with no errors and no privilege escalation.
- Promtail still tails the file via the in-container path `/logs/console.log`.
- Loki query `{job="bot-manager"}` over the last 5 min returns nonzero count after a bot group is started.
- Re-running `./deploy.sh` is idempotent — `.env` is rewritten with the same values, the container restarts cleanly, no `chown`/`chmod`/`sudo` is invoked anywhere.

**Verification commands** (executed by the operator on Bot-1):
```bash
# 1. .env was written with the host user's UID/GID
ssh sgame@Bot-1 'cat /home/sgame/bot-java/.env'
# Expect: lines HOST_UID=<sgame uid> and HOST_GID=<sgame gid>

# 2. Container running as host user, not botmanager
ssh sgame@Bot-1 'docker inspect bot-java-bot-manager-1 --format "{{.Config.User}}"'
# Expect: a string like "1001:1001" matching `id -u sgame`:`id -g sgame` on Bot-1.
# Expect NOT "botmanager", NOT "1006:1007".

# 3. Bind-mount in place, files written by host user
ssh sgame@Bot-1 'ls -la /home/sgame/bot-java/logs/console.log && stat -c "%U:%G" /home/sgame/bot-java/logs/console.log'
# Expect: file exists, owner shows as sgame:sgame (or numeric sgame uid:gid).

# 4. Bind-mount confirmed via Docker inspect
ssh sgame@Bot-1 'docker inspect bot-java-bot-manager-1 --format "{{json .Mounts}}" | python3 -m json.tool | head -20'
# Expect: "Type":"bind", "Source":"/home/sgame/bot-java/logs", "Destination":"/app/logs"

# 5. Live tail with no sudo
ssh sgame@Bot-1 'tail -n 3 /home/sgame/bot-java/logs/console.log'
# Expect: 3 JSON log lines, exit 0.

# 6. Promtail position file shows the bind-mount path
ssh sgame@Bot-1 'docker exec bot-java-promtail-1 cat /tmp/positions.yaml'
# Expect: entry for /logs/console.log with a byte offset close to the file size.

# 7. host-prep-logs.sh is no-root and idempotent
ssh sgame@Bot-1 'bash /home/sgame/bot-java/scripts/host-prep-logs.sh && echo OK'
# Expect: prints ls -la output, ends with OK, exit 0. No sudo prompt, no permission errors.

# 8. Loki ingest healthy
curl -s 'http://Bot-1:3100/loki/api/v1/query?query=count_over_time({job="bot-manager"}[5m])'
# Expect: positive integer after a bot group has been running for >1 min.
```

**Independence:** Pure config / shell change. Zero Java change. Compatible with Phases 2–4 in any order.

---

### Phase 2 — `mdcSnapshot` + `mdcWrap` helpers on `Bot`

**Scope:** Add MDC capture + wrap utilities. No callsites changed yet. Phase 2 is a pure refactor that adds dead code; Phase 3 will start calling it.

**Files touched:**
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/Bot.java`:
  - Add `protected volatile Map<String, String> mdcSnapshot;` field.
  - At the end of `initialize()`, just before the `BotMdc.clear()` in the finally block, do `this.mdcSnapshot = MDC.getCopyOfContextMap();` (note: the snapshot must be captured **inside the try block** before the clear runs — easiest is to grab it right after `client.connect()` returns successfully).
  - Add `protected Runnable mdcWrap(Runnable r)` that returns a Runnable whose `run()` stashes the current MDC, calls `MDC.setContextMap(mdcSnapshot)`, runs `r`, then restores (or clears if the stash was null) in a finally block.
  - Add `protected <T> Callable<T> mdcCall(Callable<T> c)` with the same contract.
  - Add `protected <T> Supplier<T> mdcSupplier(Supplier<T> s)` — needed for the `sendAsync(messageSupplier(...))` callsite in `BettingMiniGameBot.java:309`.
  - Add `protected <T> Consumer<T> mdcConsumer(Consumer<T> c)` — needed for `OutputPrinter`.
- Add a unit test `/Users/gleb/IdeaProjects/Bot/src/test/java/com/vingame/bot/domain/bot/core/BotMdcWrapTest.java`:
  - Subclass `Bot` with no-op abstract methods.
  - Set a known snapshot map.
  - Call `mdcWrap(...)` from a fresh thread that has empty MDC; assert the snapshot is visible inside the wrapped Runnable and that MDC is cleared after.
  - Call `mdcWrap(...)` from a thread that already has a different MDC; assert the inner MDC is the bot's snapshot and the outer MDC is restored after.
  - Repeat for `mdcCall`, `mdcSupplier`, `mdcConsumer`.

**Acceptance criteria:**
- `mvn test -Dtest=BotMdcWrapTest` passes.
- No other test regresses.
- `mdcSnapshot` is non-null on every `Bot` instance after `initialize()` returns successfully.

**Verification commands:**
```bash
JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home \
  mvn -pl . test -Dtest=BotMdcWrapTest
# Expect: BUILD SUCCESS, 6+ tests pass
```

**Independence:** No production callsite changed → no behaviour change at runtime. Safe to merge alone.

---

### Phase 3 — Apply MDC wrapping at in-repo callsites

**Scope:** Wire `mdcWrap` / `mdcCall` / `mdcSupplier` / `mdcConsumer` into every place the bot hands a `Runnable`, `Callable`, `Supplier`, or `Consumer` to a thread/executor that may not have MDC. Cover the four families enumerated in Section 2.3 that are reachable from this repo.

**Files touched:**
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/Bot.java`:
  - Line 271 — change `Thread.ofVirtual().name(...).start(this::runWsReconnectLoop)` to `Thread.ofVirtual().name(...).start(mdcWrap(this::runWsReconnectLoop))`.
  - Line 285 — same treatment for `this::runAuthThenWsLoop`.
  - Lines 251-257 — wrap the `onWsStatusChange(...)` lambda body with MDC set/clear (or refactor to `wsClient.onWsStatusChange(mdcConsumer(wsStatus -> { ... }))`).
  - Lines 258-260 — wrap the `onDisconnect(...)` lambda using `mdcWrap`.

- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java`:
  - Lines 95-97 — `watchdogScheduler` thread factory and the `schedule(this::onWatchdogExpired, ...)` at line 132: wrap with `mdcWrap`.
  - Lines 116-118 — `countdown` scheduler: wrap the `Runnable` passed to `scheduleAtFixedRate(...)` at line 121 with `mdcWrap` (defensive — no logs today, but cheap).
  - Lines 305-314 — every `.onMessage(class, handler)` callback (`onSubscribe`, `onStartGame`, `onUpdate`, `onEndGame`): wrap the handler with a MDC-aware adapter. The scenario DSL takes a `Consumer<ActionResponseMessage<...>>`; use `mdcConsumer`.
  - Lines 308-313 — `sendAsync(buildMessage().messageSupplier(bet()).condition(resolveBetCondition()))`: wrap `bet()` with `mdcSupplier` and wrap `resolveBetCondition()` with a MDC-aware `Supplier<Boolean>`.
  - Lines 333-337 — `OutputPrinter.debugOutputPrinter(...)`: see next file.

- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/util/OutputPrinter.java`:
  - Change `debugOutputPrinter` to accept a `Map<String, String>` MDC snapshot argument and wrap the internal `s -> log.info("User {}: {}", name, s)` consumer to apply that snapshot. Callsite in `BettingMiniGameBot.java:333` passes `mdcSnapshot`.

**Acceptance criteria:**
- All existing tests still pass.
- New test `/Users/gleb/IdeaProjects/Bot/src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotMdcTest.java`:
  - Instantiate a `BettingMiniGameBot`, manually set `mdcSnapshot` via a test seam (package-private setter), spawn a fresh thread that has empty MDC, invoke the scheduled `Runnable` produced by the watchdog/countdown wrap directly, and assert `MDC.get("botGroupId")` matches the snapshot inside the wrapped callback (use a `CountDownLatch` and a captured `Map` from inside the wrap).
- New test for the reconnect virtual threads in `BotMdcWrapTest` or a new `BotReconnectMdcTest`:
  - Subclass `Bot`, stub `runWsReconnectLoop` to capture `MDC.get("botGroupId")` into a `CompletableFuture<String>`, trigger `onWsDisconnected` (may need a reflection-only test seam to invoke the private method or simply call `triggerFullReconnect("test")`), and assert the captured value equals the snapshot's `botGroupId`.

**Verification commands:**
```bash
JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home \
  mvn test -Dtest='BotMdcWrapTest,BettingMiniGameBotMdcTest,BotReconnectMdcTest'
# Expect: BUILD SUCCESS
```

**Independence:** Behaviour change is additive (MDC fields populated where they weren't). No business logic affected. Ships independently of Phases 1, 4, 5.

---

### Phase 4 — Cover websocket-parser library callbacks (decision gate)

**Scope:** Decide whether to extend coverage to the library-internal log lines and act accordingly. This phase exists as a contingency keyed off the Phase 5 acceptance criterion (>90% MDC coverage).

**Decision tree:**

1. After Phase 3 ships and runs for at least one 5-minute reproduction (see Phase 5 verification), measure MDC coverage exactly as diagnosis E8 did: `grep -c botGroupId /app/logs/console.log; wc -l /app/logs/console.log`.
2. **If coverage ≥ 90%:** declare Phase 4 unnecessary and skip. Document the actual percentage in this file as a closing note.
3. **If coverage < 90%:** the residual gap is in `VingameWebSocketClient`-internal lines (Netty IO loop and library-spawned `reconnect-<name>` virtual thread). Pick exactly one option:

   - **Option A — Patch `websocket-parser-core`** (lives at `/Users/gleb/IdeaProjects/WebSocket Parser/websocket-parser-core/`):
     - Add to `VingameWebSocketClient`: `private Supplier<Map<String,String>> mdcSupplier;` plus a `public void setMdcSupplier(Supplier<Map<String,String>>)` API.
     - At every `log.*` call site inside the class, wrap with `try { if (mdcSupplier != null) MDC.setContextMap(mdcSupplier.get()); log.*(...); } finally { MDC.clear(); }`.
     - Bump library version to `2.3.11`, publish to the artifactory at `https://artifactory.facilities.vip/artifactory/gb-libs-release-local` (per `pom.xml:21`), bump dependency in this repo's `pom.xml:161` and rebuild.
     - **Tradeoff:** correct and complete fix, but couples the rollout of this feature to a library release in a separate repository.

   - **Option B — Netty `ChannelHandler` MDC injector in this repo**:
     - Cannot easily intercept the library's `Client {}: ...` slf4j calls because they happen inside the library's own methods, not on the channel pipeline. Option B is therefore weaker than it sounds: it can only set MDC on the Netty IO thread *for the duration of an inbound frame*, not for the library's lifecycle log lines (e.g., `Client {}: Authenticated with token ...` at lib line 293 fires during `connect()` on the bot-creation thread anyway, so it's already covered by Phase 3's snapshot — confirm in Phase 5 evidence). The library's IO-thread log lines that remain uncovered are at lib lines 1014, 1029, 1033, 1039 (handshake handler) — these are infrequent and one-shot per bot.
     - **Reject Option B unless Phase 5 evidence shows a thread-name distribution that makes it worth it.**

   - **Option C — Promtail-side re-derivation**:
     - Add a Promtail pipeline stage on Bot-1's `promtail-config.yml` that runs a regex against the `thread` field: `^netty-ws-message-processor-ws-(?P<userName>.+)$` and joins back to a Redis/file map of `userName → botGroupId`. Heavy infra, brittle, **reject**.

**Files touched (only if Option A is chosen):**
- `/Users/gleb/IdeaProjects/WebSocket Parser/websocket-parser-core/src/main/java/com/vingame/websocketparser/VingameWebSocketClient.java` — add field + setter + log-wrap.
- `/Users/gleb/IdeaProjects/WebSocket Parser/websocket-parser-core/pom.xml` — version bump.
- `/Users/gleb/IdeaProjects/Bot/pom.xml:161` — version bump from `2.3.10` to `2.3.11`.
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/infrastructure/client/ClientFactory.java:72-106` — call `client.setMdcSupplier(() -> mdcSnapshot)` after `build()`. But `mdcSnapshot` lives on `Bot`, not `ClientFactory`. Restructure: have `Bot.initialize()` (post-snapshot) call `client.setMdcSupplier(() -> this.mdcSnapshot)`.

**Acceptance criteria:**
- If skipped: documented as skipped with measured coverage.
- If Option A: Phase 5's coverage measurement repeated and now >95%. New library version published.

**Verification commands:** identical to Phase 5 below.

**Independence:** This phase is conditionally entered. It cannot ship in parallel with Phase 5 because Phase 5's measurement determines whether it runs at all.

---

### Phase 5 — Verification on Bot-1 (live)

**Scope:** Validate both fixes against the live staging stack. This phase has no code; it is a verification protocol the operator runs after Phases 1–3 are deployed.

**Steps:**

1. Deploy the new image and compose file to Bot-1 (operator's standard `./deploy.sh` flow).
2. As `sgame`, confirm `/home/sgame/bot-java/logs/console.log` is being written and owned by the host `sgame` user (NOT 1006):
   ```bash
   ls -la /home/sgame/bot-java/logs/
   stat -c "%U:%G %a" /home/sgame/bot-java/logs/console.log
   # Expect: owner sgame:sgame (or numeric sgame uid/gid), mode 0644.
   wc -l /home/sgame/bot-java/logs/console.log
   ```
3. Start a small bot group (recommended: 5 bots) for 5 minutes via the REST API:
   ```bash
   curl -X POST http://Bot-1:8080/api/v1/bot-group/<id>/start
   sleep 300
   curl -X POST http://Bot-1:8080/api/v1/bot-group/<id>/stop
   ```
4. Measure MDC coverage in the file written during the test window:
   ```bash
   grep -c botGroupId /home/sgame/bot-java/logs/console.log
   wc -l /home/sgame/bot-java/logs/console.log
   # Compute ratio. Target ≥ 90%.
   ```
5. Confirm thread-name breakdown of any remaining MDC-less lines:
   ```bash
   jq -r 'select(.botGroupId == null) | .thread' /home/sgame/bot-java/logs/console.log | sort | uniq -c | sort -rn | head
   ```
   Expected residue: `multiThreadIoEventLoopGroup-2-N` lines from the library's handshake handler. Anything else means a callsite was missed.
6. Confirm Loki carries `botGroupId` on `OutputPrinter`-emitted lines:
   ```bash
   curl -s --data-urlencode 'query={job="bot-manager"} |= "User test_"' \
        --data 'start='"$(date -u -d '10 min ago' +%s%N)" \
        --data 'end='"$(date -u +%s%N)" \
        --data 'limit=10' \
        http://Bot-1:3100/loki/api/v1/query_range \
     | jq '.data.result[0].stream'
   ```
   Expect the stream's labels to include `botGroupId`, `environmentId`, `gameType`.
7. Confirm `{job="bot-manager", botGroupId="<id>"}` returns approximately the same count as `{job="bot-manager"}` (within 10%) during the test window:
   ```bash
   curl -s 'http://Bot-1:3100/loki/api/v1/query?query=count_over_time({job="bot-manager"}[5m])'
   curl -s 'http://Bot-1:3100/loki/api/v1/query?query=count_over_time({job="bot-manager",botGroupId="<id>"}[5m])'
   ```
   Pre-fix ratio (from diagnosis): ~7%. Post-fix target: ≥ 90%.
8. Confirm Promtail position file shows the bind-mounted path:
   ```bash
   docker exec bot-java-promtail-1 cat /tmp/positions.yaml | grep -c '/logs/console.log'
   # Expect: ≥ 1
   ```
9. If any acceptance criterion misses, go to Phase 4 with the measured coverage and thread-name distribution as input.

**Acceptance criteria:**
- `sgame` can `tail -f` and `grep` the live log without sudo.
- Files in `/home/sgame/bot-java/logs/` are owned by the host `sgame` UID (NOT 1006), mode `0644`. This is the Phase 1b-correct ownership; the original Phase 1 expectation of `1006:1007` is obsolete.
- MDC coverage in `console.log` ≥ 90%.
- `{job="bot-manager", botGroupId="<id>"}` line count within 10% of `{job="bot-manager"}` line count during the test window.
- All RECEIVED lines (matched by `|= "RECEIVED"` or `|= "User test_"`) carry `botGroupId` in their Loki stream labels.

**Verification:** see numbered shell snippets above; each has explicit expected output.

---

## 6. Implementation Notes / Concerns

- **MDC keys must be in sync with `BotMdc` and the JSON template.** If a key is added to one and not the others, it'll be set in ThreadLocal but never emitted (or emitted as a never-promoted label). The snapshot+restore pattern uses `getCopyOfContextMap()` so it captures *whatever was set*, but tests should pin the exact key set.

- **MDC inheritance for virtual threads.** Log4j2's `ThreadContext` and SLF4J's `MDC` are ThreadLocal — virtual threads get their own slot at creation. They do **not** inherit MDC from the parent thread automatically. The snapshot+restore pattern bypasses this entirely; that's the point.

- **The `pool-N-thread-1` scheduler threads are created by `Executors.newSingleThreadScheduledExecutor()` inside `PipelineStage.java:90` of the library.** They don't have a custom thread factory we can plug into. The wrap must happen at the `Runnable` boundary (in our `sendAsync` supplier), not at the thread factory.

- **The library's `reconnect-<name>` virtual thread (`VingameWebSocketClient.java:708`)** is where our `onDisconnect` listener fires. Wrapping the listener (Phase 3) covers our listener's log lines but not the library's own log lines on that thread. Acceptable per Architecture Decision 4.

- **Don't snapshot too early.** The snapshot must be taken **after** `BotMdc.set(...)` populates all five MDC keys. Best location: at the end of `initialize()`'s try block, right before the finally runs. If snapshotting from `setConfiguration` or `setClients`, the `gameType` key may be missing.

- **Thread-name in JSON output.** The `log4j2-json-template.json` already emits `thread` (line 17-20). Phase 5 step 5 relies on this — do not remove.

- **Bind-mount and host file ownership (Phase 1b).** Ownership is no longer an issue because the container runs as the host user via `user: "${HOST_UID}:${HOST_GID}"` in compose. The image's baked-in `botmanager` (UID 1006) is overridden at runtime. `deploy.sh` writes `.env` with `HOST_UID=$(id -u)` and `HOST_GID=$(id -g)`; Compose substitutes from `.env` automatically. If `.env` is missing or `HOST_UID`/`HOST_GID` are empty, Compose substitutes the empty string and Docker rejects `user: ":"` at `up` time — that's the desired hard-fail. Operators must always go through `deploy.sh`, not raw `docker-compose up`. The `scripts/host-prep-logs.sh` rewrite is now a non-root cleanup-only one-shot — no privileged ops anywhere in the deploy path.

- **Legacy GID 1007 group on Bot-1.** Reviewer's Phase 1 finding flagged that the host GID 1007 group may not even exist on a fresh Bot-1 host, making `usermod -aG 1007 sgame` a no-op or error. Phase 1b sidesteps the question entirely — no group memberships are touched.

- **Promtail config on Bot-1 lives outside this repo.** No change needed, but be aware: if anyone edits `/home/sgame/bot-java/promtail-config.yml` to add/remove label promotions, MDC coverage as measured in Phase 5 won't change but Grafana behaviour will.

- **The `polkitd`-owned stale files** in `/home/sgame/bot-java/logs/` predate this work and are not the bot-manager's. They must be moved aside (not deleted) per the Phase 1 host-prep script for audit.

- **CLAUDE.md's "Known Bugs / WebSocket AUTH Race Condition"** mentions an AUTH log line on the Netty IO thread (`multiThreadIoEventLoopGroup-2-N`). After Phase 3 that line will still be MDC-less because the AUTH log fires inside the library. This is consistent with Architecture Decision 4.

- **`pom.xml:161` says `2.3.10`, not `1.0-SNAPSHOT`.** The Architect's prompt and CLAUDE.md both reference the wrong version. Plan uses the real one. Don't fight the discrepancy — just note it.

- **`OutputPrinter` is project-owned**, not library-owned. The prompt asked us to investigate; confirmed at `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/util/OutputPrinter.java`. Phase 3 changes it directly.

---

## 7. Open Items

- **`promtail-config.yml` is not in this repo.** If we ever want to version-control the Promtail label set alongside the application, that's a follow-up — out of scope for this plan.
- **Loki retention** (diagnosis recommendation #7) — not addressed here, deferred to a separate plan.
- **Library patch (Phase 4 Option A)** — explicitly deferred to a decision gate at Phase 5. If chosen, it spawns its own release in the `websocket-parser-core` repository and a dependency bump here. That release is outside this plan's scope.
- **Grafana panel without per-bot filters** (diagnosis recommendation #5) — out of scope; pure dashboards work, no code change.
- **The shared Netty `multiThreadIoEventLoopGroup-2-N` thread serves all clients.** Any per-bot MDC there would need to be channel-attribute-driven. Architecturally tricky and explicitly out of scope unless Phase 5 forces Phase 4.
- **`bot-creation-N` virtual thread MDC** — already set via `setGroupContext` (`BotGroupBehaviorService.java:274`). Not changed. Group-level MDC during creation is intentional (the per-bot `botId` is set inside `Bot.initialize()` once the creation thread starts working on a specific bot).

- **Abandoned `chown 1006:1007` / `chmod 2775` / `usermod -aG 1007 sgame` strategy** — replaced by Phase 1b's no-root approach. If a future scenario requires a dedicated container UID distinct from the host user (e.g., multi-tenant Bot-N hosts where the operator must not be able to tamper with logs as a regular shell user), revisit by either (a) requesting root on the target host and bringing back the host-prep flow, or (b) using a rootless Docker daemon with subuid mapping. Both are out of scope for this plan.

---

## Verification

**Phase 1b's verification SUPERSEDES the original Phase 1 verification.** The shipped Phase 1 commits (`129ef20`, `13e8f24`) remain on the branch; Phase 1b is an additive follow-up that strips the chown/sudo branch out of `deploy.sh`, rewrites `scripts/host-prep-logs.sh` as a no-root cleanup-only script, and adds `user: "${HOST_UID}:${HOST_GID}"` to `docker-compose.yml`.

The full live verification protocol is documented in **Phase 5** above (with Phase 1b's no-root expectations baked in) and is the canonical post-deploy checklist the Releaser must run. Each step has an explicit shell command and expected result. Phases 1b, 2, and 3 each carry their own local acceptance commands (also above) that can be run before the Phase 5 live run on Bot-1.

In summary, post-deploy on Bot-1 the Releaser must:

1. `ssh sgame@Bot-1 'cat /home/sgame/bot-java/.env'` — expect `HOST_UID` and `HOST_GID` lines matching `id -u sgame` and `id -g sgame`. (Validates Phase 1b's `.env` write.)
2. `ssh sgame@Bot-1 'docker inspect bot-java-bot-manager-1 --format "{{.Config.User}}"'` — expect a string like `"1001:1001"` matching `id -u sgame`:`id -g sgame`, NOT `1006:1007`.
3. `ssh sgame@Bot-1 'ls -la /home/sgame/bot-java/logs/ && tail -n 1 /home/sgame/bot-java/logs/console.log'` — expect listing with files owned by host `sgame` user and one JSON log line. (Validates Fix A.)
4. `ssh sgame@Bot-1 'docker inspect bot-java-bot-manager-1 --format "{{json .Mounts}}"'` — expect `"Type":"bind"` and `"Source":"/home/sgame/bot-java/logs"`.
5. Start a 5-bot group via `POST /api/v1/bot-group/<id>/start`, wait 5 minutes, stop it. Then on Bot-1 compute the ratio `grep -c botGroupId /home/sgame/bot-java/logs/console.log` over `wc -l /home/sgame/bot-java/logs/console.log` — expect ratio ≥ 0.90. (Validates Fix B.)
6. `curl http://Bot-1:3100/loki/api/v1/query?query=count_over_time({job="bot-manager",botGroupId="<id>"}[5m])` should return within 10% of `count_over_time({job="bot-manager"}[5m])` over the same window. (Validates the end-to-end pipeline.)
7. `jq -r 'select(.botGroupId == null) | .thread' /home/sgame/bot-java/logs/console.log | sort | uniq -c | sort -rn | head` — expect residue to be dominated by `multiThreadIoEventLoopGroup-2-N` only (library-internal); any other thread name in the top entries means a callsite was missed in Phase 3.
