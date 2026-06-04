# Release — LOGGING_PIPELINE_FIX

Mode: bot
Branch: feat/logging-pipeline-fix
Commit: 6ee86f0 (Phase 1b: run bot-manager as host user, drop root host-prep path)
Image: vingame-bot:latest (built 2026-06-04 ~15:48 +04, image digest d3d84c0ebc5d)
Bot.tar: 372 MB (uncompressed 389 759 488 B on Bot-1)
Date: 2026-06-04T19:00–19:10 +04 (12:00–12:10 UTC)

## Build

- `mvn clean install`: PASS (313 tests, 0 failures, 0 errors, 0 skipped — 14 s total)
- `docker build --no-cache --platform linux/amd64`: PASS (~15 s, image `d3d84c0ebc5d`)
- `docker save -o bot.tar`: PASS (389 757 440 bytes, 372 MB)

## Ship

- `sftp put bot.tar`: PASS (389 759 488 B on Bot-1, mtime 19:00)
- `sftp put deploy.sh`: PASS
- `sftp put docker-compose.yml`: PASS
- `sftp put -r scripts/` (incl. `host-prep-logs.sh`): PASS
- `infra-images.tar.gz`: not shipped (mode=bot)

## Pre-deploy cleanup (no-root host-prep)

- `bash scripts/host-prep-logs.sh` (run as sgame, no sudo): PASS, exit 0
  - Stale `polkitd:input` files (`console-2026-05-12-10.log`, `console.log` from May 12-13) moved to `/home/sgame/bot-java/logs.bak.20260604-190018/`
  - Resulting `logs/` directory empty, owned `sgame:sgame`, mode `0777` (pre-existing)
- Confirmed on disk: `ls -la /home/sgame/bot-java/logs/` → empty after cleanup; `logs.bak.20260604-190018/` exists alongside.

## Deploy

- `docker compose down`: PASS (all 5 containers — bot-manager, mongo, loki, grafana, promtail — stopped and removed; network removed)
- `docker image rm vingame-bot:latest`: PASS (12 layers untagged/deleted)
- `docker load -i bot.tar`: PASS (`Loaded image: vingame-bot:latest`)
- `bash deploy.sh`: FAIL — `docker-compose: command not found` (line 23). **Bot-1 only has Docker Compose V2 plugin (`docker compose`), not the V1 standalone binary.** This is an environment mismatch in `deploy.sh`, NOT a code issue. **Worked around** by writing `.env` manually with `HOST_UID=$(id -u)` / `HOST_GID=$(id -g)` and running `docker compose up -d`. The semantic effect is identical to what `deploy.sh` intended: `.env` is written with the host UID/GID, Compose interpolates them into the bot-manager `user:` directive. Recommend a small follow-up to change deploy.sh line 23 from `docker-compose up -d` to `docker compose up -d`.
- `docker compose up -d` (manual): PASS — all 5 services Created and Started; mongo Healthy gate cleared; bot-manager Started.

## Smoke test

- `docker ps` shows bot-manager healthy: PASS — `Up About a minute (healthy)` ~75 s after start
- Spring Boot ready log: PASS — `12:01:13.619 [main] INFO Starter - Started Starter in 3.35 seconds (process running for 4.171)`
- Auto-start log: PASS — `12:01:12.999 [main] INFO BotGroupBehaviorService - Bot Manager startup complete. 0 bot groups running`
- `docker inspect ... Config.User`: PASS — `1006:1007` (host sgame's UID:GID, picked up from `.env`). Note: on Bot-1 sgame happens to share the same UID/GID as the container's baked-in `botmanager` user — the change is architecturally meaningful (explicit, host-driven) even though the numeric values coincide.
- `docker inspect ... .Mounts`: PASS — `[{"Type":"bind","Source":"/home/sgame/bot-java/logs","Destination":"/app/logs","Mode":"rw","RW":true,"Propagation":"rprivate"}]`. Bind mount confirmed, NOT a named volume.
- `.env` contents: `HOST_UID=1006\nHOST_GID=1007` — correct, matches `id -u`/`id -g` for sgame.
- `ls -la /home/sgame/bot-java/logs/` after first writes: `-rw-r--r-- 1 sgame sgame 8199 Jun  4 19:01 console.log` — owned by sgame, mode 0644. PASS.
- `tail -n 3 /home/sgame/bot-java/logs/console.log` as sgame WITHOUT sudo: PASS — 3 valid JSON log lines returned.

## Phase 5 verification (canonical 7-step list from plan § 5 Phase 5)

### Step 1: Files readable by sgame
Command: `ls -la /home/sgame/bot-java/logs/`
Expected: files owned by sgame's UID, mode 0644, readable without sudo.
Actual:
```
-rw-r--r-- 1 sgame sgame 2600734 Jun  4 19:08 console.log
```
Owner sgame, mode 0644. Verified `tail` without sudo works (see Smoke test above).
Result: PASS

### Step 2: Bind-mount confirmed
Command: `docker inspect bot-java-bot-manager-1 --format '{{json .Mounts}}'`
Expected: `Type: bind`, `Source: /home/sgame/bot-java/logs`.
Actual: `[{"Type":"bind","Source":"/home/sgame/bot-java/logs","Destination":"/app/logs","Mode":"rw","RW":true,"Propagation":"rprivate"}]`
Result: PASS

### Step 3: Run a small bot group for ~5 min
Command:
- Listed groups: `curl -s http://localhost:8080/api/v1/bot-group/` → picked `9b54e101-2640-40db-a367-36e088d23cd8` ("BC Mini bot group", 10 bots, was STOPPED).
- Truncated `/home/sgame/bot-java/logs/console.log` to start a clean measurement window.
- Started: `POST /api/v1/bot-group/9b54e101-…/start` at `2026-06-04T12:03:07Z`.
- Slept 300 s.
- Stopped: `POST /api/v1/bot-group/9b54e101-…/stop` at `2026-06-04T12:08:16Z`.
Expected: group runs cleanly for ~5 min, generates a representative log volume.
Actual: 2 600 734 bytes / 4 433 lines written in the window. No exceptions surfaced in tail. Group stopped cleanly.
Result: PASS

### Step 4: MDC coverage measurement
Commands:
```
wc -l /home/sgame/bot-java/logs/console.log       # total
grep -c botGroupId /home/sgame/bot-java/logs/console.log   # with MDC
```
Expected: ≥ 90 % (pre-fix baseline 7.1 %).
Actual:
- total = **4433**
- with botGroupId = **4323**
- coverage = **97.52 %**
Result: PASS (target 90 %, actual 97.52 %)

### Step 5: Residual thread-name distribution
Command: `grep -v botGroupId logs/console.log | grep -oE '"thread":"[^"]+"' | sort | uniq -c | sort -rn | head -20`
(jq not installed on Bot-1, fell back to grep extraction; equivalent semantics.)
Expected: residue dominated by `multiThreadIoEventLoopGroup-2-N` (Netty IO loop — AD 4 intentionally out of scope).
Actual (110 lines total without botGroupId):
```
     35 "thread":"http-nio-8085-exec-4"
     15 "thread":"http-nio-8085-exec-10"
     12 "thread":"multiThreadIoEventLoopGroup-2-4"
     12 "thread":"multiThreadIoEventLoopGroup-2-1"
      8 "thread":"multiThreadIoEventLoopGroup-2-3"
      8 "thread":"multiThreadIoEventLoopGroup-2-2"
      2 "thread":"bot-creation-9"  (×10 — one per bot)
```
Breakdown:
- 50 lines `http-nio-8085-exec-*` — Spring HTTP request threads (REST API calls including the `/start` and `/stop` themselves). Not bot lines. Out of scope.
- 40 lines `multiThreadIoEventLoopGroup-2-*` — Netty library IO loop. Exactly the residue category named in AD 4 as intentionally out of scope.
- 20 lines `bot-creation-*` — 2 lines per bot, emitted by `bot-creation-N` virtual threads *before* `Bot.initialize()` runs (i.e., before the MDC snapshot is captured). These are bot-creation lines, not steady-state. Tiny, bounded, expected.

No surprise residue (e.g., no high-volume scheduler or callback threads without MDC). All categories accounted for by the plan.
Result: PASS

### Step 6: Loki labelled-stream coverage
Command:
```
sum(count_over_time({job="bot-manager"}[10m]))
sum(count_over_time({job="bot-manager",botGroupId="9b54e101-…"}[10m]))
```
Expected: within 10 % of each other.
Actual:
- All bot-manager streams in window: **4434** lines
- With `botGroupId` label set to this group: **4300** lines
- Ratio: 4300/4434 = **96.98 %**
Loki label inventory confirms `botGroupId`, `environmentId`, `gameType`, `level`, `job`, `filename`, `service_name` are all promoted.
Result: PASS (within 3.02 %, well under 10 % tolerance)

### Step 7: Promtail position keeping pace
Command: `docker exec bot-java-promtail-1 cat /tmp/positions.yaml`
Expected: references `/logs/console.log`, position within tail tolerance.
Actual:
```
positions:
  /logs/console.log: "2587619"
```
File size at the moment of inspection: 2 600 734 bytes. Promtail is at 2 587 619 bytes — 99.50 % tailed, lagging by ~13 KB (typical buffer / not-yet-flushed batch). Path `/logs/console.log` matches the read-only bind mount.
Result: PASS

## Phase 4 decision gate

Coverage = 97.52 % (file) / 96.98 % (Loki). Both ≥ 90 % target.

**Decision: SKIP Phase 4.** No escalation needed. The residue is fully explained by:
- REST HTTP threads (not bot lines, not in scope for the fix)
- Netty library IO loop (AD 4 — out of scope by design, would require library patch / channel handler / rejection)
- Pre-`initialize()` bot-creation lines (2 per bot, bounded, expected)

No call sites appear to have been missed.

## Verdict

**PASS**

Notes for future:
1. `deploy.sh` should use `docker compose up -d` (V2 syntax) not `docker-compose up -d` (V1). Bot-1 does not have V1 installed. Worked around in this deploy by manual `.env` write + `docker compose up -d`. Recommend a follow-up patch.
2. The dirty working tree at deploy time consisted only of the plan document `docs/plans/LOGGING_PIPELINE_FIX.md` (Phase 1b documentation updates) — no uncommitted code. The deployed image is built from committed tip `6ee86f0`.
3. On Bot-1 sgame's UID/GID (`1006:1007`) happens to numerically match the container's baked-in `botmanager` user. The architectural change (explicit `user:` directive driven by `.env`) is the meaningful one — on any other host with a different sgame UID, the same compose file + deploy flow produces the right ownership without code change.
