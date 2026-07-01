# Release — RESILIENCE_HARDENING

Mode: bot
Branch: main
Commit: ecc187e2c0b25c1dba5616cf3f37c244a0881d70
Image: vingame-bot:latest — sha256:4db01f43486184b9fede8ce50670f0d9ef499495f9809d57235ec12d717071d9 (built 2026-07-01T08:38:36Z)
Target: Bot-1 (`s009-bot-general-stag-01`), Compose project `bot-java` at `/home/sgame/bot-java/`
Date: 2026-07-01T08:57Z

## Verdict: CONCERNS

Deploy succeeded; the stack is healthy and every shipped code change (P0a, P1, P0b)
is live and verified in the running process. The single concern is **operational,
not a regression**: P0a's log-volume cut does not take effect at the current staging
log level (`com.vingame.bot` = DEBUG). See P0a below and Follow-ups.

Shipped (code phases only): **P0a** (OutputPrinter wire logging INFO→DEBUG/TRACE),
**P1** (bounded reconnect / MAX_RECONNECT_CYCLES → DEAD; the 2026-06-30 outage
root-cause fix), **P0b** (Dockerfile `-XX:+ExitOnOutOfMemoryError` + compose
`restart: unless-stopped`). Infra phases **P0c / P2 / P3 are NOT in this release**
(deferred follow-ups, see bottom).

## Build

- `mvn clean install`: PASS — 1100 tests, 0 failures/errors (25s). JDK 21.0.2.
- `docker build --no-cache --platform linux/amd64`: PASS — image sha256:4db01f43…
- Image content pre-flight: PASS
  - Entrypoint = `java -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -jar Bot.jar` (P0b flag baked in).
  - Source has `MAX_RECONNECT_CYCLES = 10` + `giving up after … reconnect cycles` WARN (P1).
  - OutputPrinter has no `log.info`/`log::info` (P0a).
- `docker save -o bot.tar`: PASS — 395,724,288 bytes.

(Note: local Docker Desktop daemon was down at start; started it and waited for readiness before building.)

## Ship

- `sftp put bot.tar` → Bot-1:/home/sgame/bot-java: PASS — remote size 395,724,288 (byte-exact match).

## Deploy (targeted, single-Compose-project safe)

- Compose edit — added `restart: unless-stopped` to `bot-manager` **in place** (backup taken as `docker-compose.yml.bak.*`): PASS
  - DNS guard (`dns: [1.1.1.1, 8.8.8.8]`) preserved — verified present after edit (2 lines).
  - `docker compose config` validates; exactly 1 `restart: unless-stopped` occurrence (on bot-manager).
- `docker load -i bot.tar`: PASS — loaded vingame-bot:latest = sha256:4db01f43… (old image renamed to dangling).
- `docker compose up -d --no-deps --force-recreate bot-manager` (targeted, no full `down`): PASS
  - bot-manager recreated on the new image; observability stack (loki/mongo/prometheus/grafana/promtail) **stayed Up** — no collateral teardown.
- HOST_UID/HOST_GID sourced from Bot-1 `.env` (1006/1007), auto-loaded by compose.

## Smoke test

- `docker ps` bot-manager `Up (healthy)`: PASS (healthy ~37s after start).
- `Started Starter` log: PASS — `Started Starter in 10.589 seconds`.
- `startup complete` log: PASS — `Bot Manager startup complete. 7 bot groups running`.
- `/actuator/health`: PASS — `status: UP` (mongo UP, diskSpace UP).
- Auto-start of ACTIVE groups: PASS — all groups authenticated and started
  (116 Demo 30 bots, XD game test 20, Fruit shop 40, Slot test 204 20,
  TaiXiuStagingVerifyGroup2 2, Tai Xiu test 30, RIK114 TaiXiu Jackpot 100).
- 0 DEAD / AUTH_FAILED bots: PASS (grep count 0 in console.log).
- Observability stack still Up: PASS.
- Disk not climbing: PASS — 78% → 68% used (33G free) over the deploy; log
  rotation + dangling-image replacement freed ~10G.

### Surprise (staging-state discrepancy, not caused by this deploy)
The brief stated **6 ACTIVE groups** and that RIK group `75899bb9` was set
`targetStatus=STOPPED`. Reality on Bot-1: **7 groups auto-started**, including
`75899bb9 RIK114 TaiXiu Jackpot 100` with 100 bots. console.log shows that group
was started via the REST API at **08:34:58** (thread `http-nio-8085-exec-2`),
**before** this deploy — i.e. its `targetStatus` was flipped back to ACTIVE in
Mongo earlier by someone/something. This deploy did **not** create or start it;
the app's normal auto-start brought it up because its persisted target is ACTIVE.
Left as-is (not in the Releaser's mandate to change staging state). **Operator
decision needed**: re-STOP `75899bb9` if it is still meant to be parked. It is
currently healthy (100/100 bots, `bot_reconnects_total=1`, no DEAD), and the P1
fix now caps any reconnect storm it could cause.

## Plan verification (docs/plans/RESILIENCE_HARDENING.md § Verification)

### Step 1: P0a — wire logging demoted  → CONCERNS (code correct; benefit gated on log level)
- Build-time static check: `grep -nE "log\.info|log::info" OutputPrinter.java`
  Expected: no matches. Actual: no matches. **PASS.**
- Staging, default level, 60s window: expected zero `User <name>:` frame lines.
  Actual: **8,352** `User <name>:` frame lines in 60s (~140/s), e.g.
  `User demob0t1a19: [SENT]…/[RECEIVED]…` from `OutputPrinter`. **Does not meet the
  literal check.**
  - Root cause: the committed default level for `com.vingame.bot` is **DEBUG**
    (`log4j2.properties: logger.app.level = debug`), and P0a demoted the per-frame
    printer INFO→**DEBUG** (raw/pretty → TRACE). At the DEBUG default the `User:`
    lines still flow. The plan/Javadoc assumed default = INFO.
  - Demotion mechanism proven correct: temporarily set `com.vingame.bot`=INFO via
    actuator → **0** new `com.vingame.bot` lines / 0 `User:` lines in a 25s window;
    restored DEBUG. So the lines "now only appear at DEBUG/TRACE" exactly as intended.
  - Net effect on staging: because only `debugOutputPrinter` is wired for these bots
    (no raw/pretty frames observed), moving it INFO→DEBUG yields ~no reduction at the
    DEBUG default — the cut is realized only by running at INFO. **The disk/Loki
    amplifier is not actually reduced on staging until `com.vingame.bot` is raised to
    INFO.**

### Step 2: P1 — reconnect is bounded  → PASS (live sim deferred, by design)
- Live broken-group simulation intentionally **not run** (brief: do not create a
  broken group now that the RIK groups are removed). Covered by unit tests
  `BotReconnectTest` and the green 1100-test suite at build.
- New (fixed) jar confirmed running: source/image carry `MAX_RECONNECT_CYCLES = 10`
  and the `giving up after … reconnect cycles → DEAD` path.
- `jvm_threads_live_threads`: **flat** — 1520 → 1520 → 1513 across samples
  (peak 1560 at startup) for ~242 live bots across 7 groups. Not climbing (the old
  platform-thread reconnect leak drove it unbounded). Sane, stable value.
- `bot_reconnects_total{botGroupId=75899bb9,reason="ws-disconnect"} = 1` — single-count
  per cycle (not per attempt), consistent with the reworked bounded loop.

### Step 3: P0b — restart policy + clean OOM  → PASS (kill-test methodology corrected)
- `docker inspect … RestartPolicy.Name` = **unless-stopped**. PASS.
- `cat /proc/1/cmdline` contains **`-XX:+ExitOnOutOfMemoryError`**. PASS.
- Brief's `docker kill; sleep 25; docker ps` sub-check: container exited 137 and did
  **not** auto-restart (RestartCount stayed 0, no restart event). This is **expected,
  correct behavior**, not a regression: `docker kill` from the host is flagged by the
  daemon as a *manual* stop, and `unless-stopped` (like `docker stop`) deliberately
  does not resurrect a manually killed/stopped container. An in-container
  `kill -9 1` is also a no-op (kernel protects PID 1 from in-namespace fatal signals),
  and `/actuator/shutdown` is disabled (404) — so PID 1 can't be crashed by hand.
- Real scenario proven instead: launched a throwaway `--restart unless-stopped`
  container whose **PID 1 exits on its own** (`sh -c 'sleep 4; exit 1'`). The Bot-1
  daemon auto-restarted it — RestartCount climbed 1→2→4→5→6→7. This is exactly the
  OOM path P0b defends: `-XX:+ExitOnOutOfMemoryError` makes the JVM **self-exit**
  (non-manual), which the restart policy **does** restart. Throwaway container removed.
- Service impact: the `docker kill` test took bot-manager down for ~4.5 min; restored
  via `docker compose up -d --no-deps bot-manager`, healthy again, all 7 groups
  re-auto-started, 0 DEAD.

### Step 4: DNS guard preserved  → PASS
- `dns: [1.1.1.1, 8.8.8.8]` still present in the Bot-1 compose after the in-place edit.

## Deferred follow-ups (explicitly NOT in this release)

- **P0a operational gap** — realize the log-volume cut: raise `com.vingame.bot` to
  INFO on Bot-1 (CLAUDE.md states the threshold "can be safely raised to INFO" post
  audit), or change committed `log4j2.properties: logger.app.level = debug → info`.
  Until then, promtail keeps shipping the ~8.4k frame-lines/min to Loki at the DEBUG
  default, so P0a does **not** slow Loki growth on staging yet. Not done by the
  Releaser (runtime-level and committed-config changes are operator/dev decisions).
- **P0c — Loki retention + Docker json-file rotation.** NOT shipped. Loki
  (`bot-java_loki-data`) still has **no retention cap / no compactor**, so it will
  slowly regrow (far slower once P0a's INFO cut is in effect; unchanged at the current
  DEBUG level). Docker stdout json-file rotation also still uncapped (needs
  daemon.json + sudo, or per-service `logging:` fallback).
- **P2 — Prometheus alerting** (DiskFree / BotManagerDown / JvmThreadClimb) + confirm
  node_exporter on Bot-1. NOT shipped.
- **P3 — structural hardening** (split observability into its own Compose project;
  protected mongo disk headroom). NOT shipped (optional).
- **Staging state** — decide whether to re-STOP RIK group `75899bb9` (currently ACTIVE
  and running 100 bots; see Surprise above).

## Notes

- Single-Compose-project constraint honored: targeted `--no-deps` recreate only;
  observability stack never torn down.
- DNS guard edit-in-place honored (no repo compose copied over the Bot-1 compose).
- No git changes made; nothing pushed. Bot-1 is the only host touched.
