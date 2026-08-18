# Release — THREAD_LEAK (websocket-parser-core 2.3.10 → 3.0.5 + connect-semantics fix)

Mode: bot
Branch: main (uncommitted working tree — `pom.xml`, `docker-compose.yml`, `ClientFactory.java`; NOT committed)
Final image: vingame-bot:latest (built 2026-08-03T10:59:26Z, sha256:4455c5da5297)
Date: 2026-08-03T11:04Z

Scope: regular deploy + **V0 smoke + confirm the auth breakage is gone**. Multi-hour
thread-leak verification (V1–V5) explicitly deferred to a later user request — NOT run.

This release took two attempts. **Attempt 1 FAILED** (3.0.5 broke WS auth for every bot).
**Attempt 2 (fix-forward) PASSED.** Both are recorded below; final verdict is **PASS**.

---

## Attempt 2 — FIX-FORWARD — PASS (final)

Working-tree changes deployed:
1. `pom.xml`: `websocket-parser-core` 2.3.10 → 3.0.5 (thread-leak fix — virtualized per-client
   executors + self-sufficient `close()`). **Unchanged / kept.**
2. `docker-compose.yml`: observability hardening (json-file rotation 50m×5 + `restart:
   unless-stopped` on all services + parameterized Grafana password). **Already on Bot-1 from
   attempt 1; unchanged — not re-synced.**
3. `ClientFactory.buildClient()`: `.awaitServerReady(false)` (NEW — the fix). 3.0.5 defaulted
   `awaitServerReady=true`, blocking `connect()` up to 30 s waiting for a hardcoded AUTH-ACK +
   `cmd:100` handshake our servers don't emit. Opting out restores 2.3.10 connect semantics;
   the thread-leak fix is independent of this flag and stays in effect.

### Build
- `mvn clean install`: **PASS** (~28s, tests green against 3.0.5 + the fix).
- `docker build --no-cache --platform linux/amd64`: **PASS** (sha256:4455c5da5297).
- `docker save`: **PASS** (393,036,288 bytes).

### Ship / Deploy
- `sftp put bot.tar`: **PASS** (remote size 393,036,288 — byte-identical).
- docker-compose.yml: **not re-shipped** (unchanged from attempt 1; verified already present).
- `docker compose down` / `image rm` (untagged prior sha256:967eb61ed607) / `docker load` /
  `docker compose up -d`: **all PASS**.

### Smoke test — PASS
- Container health: **PASS** — reached `Up (healthy)` in ~40 s (vs. never in attempt 1).
  RestartCount 0.
- `curl :8080/actuator/health` (host): **PASS = 200**.
- Spring Boot ready log: **PASS** — `Started Starter in 13.646 seconds`;
  `Bot Manager startup complete. 12 bot groups running`.
- **Auth breakage gone (key fix signal): PASS** — `grep -c "Auth handshake timed out"` = **0**
  on the new instance (attempt 1 had 270+ and climbing). 342 `→ AUTHENTICATED` transitions;
  1329 `Connected to server`.
- **Previously-failing group spot-check: PASS (substituted).** The specific BOM/097 group from
  attempt 1 (`ab81f9e6` / `bomflowtest*` / env `3cda38f9`) is **not in this run's roster** —
  12 groups auto-started, none BOM/097 — so it could not be checked directly. Substituted two
  groups that DID fail with 30 s auth timeouts in attempt 1 and now authenticate cleanly:
  - `4d7f6ac9` "Fruit shop" (`fru1tsh0p*`, TIP): 40 `→ AUTHENTICATED`, then
    `AUTHENTICATED → CONNECTING` and `Client ws-fru1tsh0p2: Authenticated with token 189-...`.
  - `b1e80470` "116 Demo group" (`demob0t1a*`): bots `AUTHENTICATING → AUTHENTICATED`.
  These span TIP + the other auto-started products (XD, Slot ×6, Tai Xiu ×2, RIK114), so the
  fix is not product-specific.

### V0 baseline + coordinator checks
- **`jvm_threads_live_threads` baseline (Prometheus): `58`** (stable across two samples,
  container up ~2.7 min; series `application="bot-manager"`, `instance="bot-manager:8085"`).
  Actuator is reachable and being scraped (attempt 1 returned no sample). **Record this 58 as
  the baseline for the later over-time verification.** The low value is expected and is the
  point of the 3.0.5 bump: per-client message-processor executors are now **virtual** threads,
  which are excluded from `jvm_threads_live_threads` (platform threads only) — i.e. per-bot
  platform-thread cost is now ~0, so total ≈ fixed pools only (Netty IO 4 + FJP carriers +
  Tomcat + JVM + HttpClient). (Directional evidence for the thread-leak fix; the authoritative
  over-time V1–V5 checks are deferred per scope.)
- **Loki `:3100/ready`: PASS (resolved).** Returned 503 on the first check (~1 min in) then
  flipped to `ready` by ~2.5 min — warm-up, same as attempt 1. No longer 503.
- **Observability stack: PASS.** grafana `:3000/api/health` 200, prometheus `:9090/-/healthy`
  200, mongo Up (healthy), promtail Up, loki Up + ready. All recreated by the single-compose
  `up -d` and healthy.
- **Log rotation** (verified attempt 1, unchanged): bot-manager + all obs containers
  `json-file map[max-file:5 max-size:50m]`, `restart=unless-stopped`.

### Flagged anomaly (does NOT gate this release)
- **`"Cannot send message, not connected"` WARN = 21,692 occurrences in ~2 min.** This is the
  documented **WS AUTH-race / no-reconnect known bug** (CLAUDE.md § Known Bugs) — WARN-level,
  pre-existing, and **out of THREAD_LEAK scope** (log volume is explicitly out of scope in the
  plan). It is not part of the smoke gate and does not affect health/auth/startup (all green).
  However, at this volume it is **saturating Loki's ingest**: Loki logs
  `Ingestion rate limit exceeded for user fake (limit: 4194304 bytes/sec)` and is **dropping
  log lines**. Recommend the coordinator look at this separately — it predates this release but
  is loud right now. The already-applied Docker json-file rotation (50m×5) caps host disk
  regardless; the Loki drop is an ingest-rate limit, not disk.

### Attempt-2 verdict: **PASS**

---

## Attempt 1 — FAILED (superseded by attempt 2)

Image sha256:967eb61ed607 (pom 3.0.5 + compose hardening only; no ClientFactory fix).

- Build / ship / deploy: all PASS (mvn 1485 tests green; sftp of bot.tar + updated
  docker-compose.yml; down/rm/load/up all OK).
- **Smoke: FAIL.** Container went `Up (unhealthy)` and never bound the web server; host
  `curl :8080` and `:8085` both `000`; `Started Starter` never logged (`Tomcat initialized with
  port 8085` present but accept phase never reached).
- **Root cause:** 3.0.5 defaulted `awaitServerReady=true` → `connect()` blocked 30 s per bot
  waiting for an AUTH-ACK/`cmd:100` handshake the servers don't emit, so **every** bot across
  all groups (TIP `fru1tsh0p*` AND BOM `bomflowtest*` alike) logged
  `Auth handshake timed out after 30000ms (authAck=false, cmd100=false)` (270+). Because boot
  auto-start runs on `[main]` during context refresh, these 30 s blocks wedged the boot thread
  before Tomcat's accept phase → web unreachable → `unhealthy`. Prometheus therefore could not
  scrape → no `jvm_threads_live_threads` sample.
- Fixed forward (not rolled back) via `ClientFactory` `.awaitServerReady(false)` — see attempt 2.

---

## Overall verdict

**PASS** (attempt 2). App healthy, web/actuator reachable (200), 0 auth timeouts, bots
authenticate and connect across products, jvm_threads baseline = 58 captured, obs stack
healthy. One out-of-scope pre-existing WARN flood flagged (WS AUTH-race → Loki ingest-limit
drops). Multi-hour thread-leak verification deferred per scope. Prod untouched.

## Logs — evidence
Attempt-1 500-line tail (for the FAIL record):
`/private/tmp/claude-501/-Users-gleb-IdeaProjects-Bot/438b72c4-d1ea-49af-b31b-cd7448b3ea15/scratchpad/bot-log-tail-500.txt`

Attempt-2 key lines:
```
11:01:17.320 [main] INFO Starter - Started Starter in 13.646 seconds (process running for 14.793)
11:01:15.954 [main] INFO BotGroupBehaviorService - Bot Manager startup complete. 12 bot groups running
11:01:09.316 [bot-creation-51] DEBUG Bot [4d7f6ac9-.../2/BETTING_MINI] - Bot fru1tsh0p2: AUTHENTICATING → AUTHENTICATED
11:01:09.317 [bot-creation-51] INFO VingameWebSocketClient - Client ws-fru1tsh0p2: Authenticated with token 189-32dd...
grep -c "Auth handshake timed out" = 0
jvm_threads_live_threads = 58  (application=bot-manager, instance=bot-manager:8085)
Loki /ready = ready  (503 at ~1min, ready by ~2.5min)
```
