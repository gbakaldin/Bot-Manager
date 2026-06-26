# Release — TAI_XIU_114_JACKPOT

Mode: bot
Branch: main
Commit: fe5f2cc2a72b3de324c0847b773cffa89fca9237
Image: vingame-bot:latest (built 2026-06-26 ~16:14 +04:00)
Date: 2026-06-26T16:1x:00+04:00
Target: Bot-1 (only)

## Scope of this deploy

Second Tai Xiu product **P_114 / RIK**, plugin `taixiuJackpotPlugin`. CMDs are
the 116 CMDs +100 (subscribe 1105 / start 1102 / end 1104 / bet 1100), outbound
bet carries `a:false`, inbound message classes reused, 3000ms default late-bet
cutoff for 114. P_116 Tai Xiu unchanged. Pure bot-image change — no
Grafana/dashboard files touched, single-payload mode=bot deploy. Targeted
recreate of bot-manager only (no full `down`).

## Build

- `mvn clean install`: PASS (23s) — Tests run: 942, Failures: 0, Errors: 0, Skipped: 0; BUILD SUCCESS
- `docker build --no-cache --platform linux/amd64`: PASS (12s) — image sha256:88d348fb4121…
- `docker save -o bot.tar`: PASS (392816128 bytes)

## Ship

- `sftp put bot.tar`: PASS (54s) — remote size 392816128 == local 392816128 (exact match)

## Critical guards (pre-deploy)

- DNS override on bot-manager: PASS — `grep -n dns` matched line 26; surrounding
  block confirmed `dns: ["1.1.1.1","8.8.8.8"]` (YAML list form). docker-compose.yml
  NOT modified/overwritten.
- Targeted recreate only: PASS — `docker compose up -d bot-manager` recreated only
  bot-manager; mongo/prometheus/loki/grafana/promtail all left running.

## Deploy

Pre-deploy running containers (before): bot-manager (Up ~1h healthy), grafana,
prometheus (2d), promtail (2d), mongo (2d healthy), loki (2d).

- `docker load -i bot.tar`: PASS — "Loaded image: vingame-bot:latest" (old image
  ID renamed by docker load; no explicit `docker image rm` in targeted-recreate flow)
- `HOST_UID=$(id -u) HOST_GID=$(id -g) docker compose up -d bot-manager`: PASS —
  bot-manager Recreated → Started; mongo dependency waited+healthy; other 5
  containers untouched.

## Smoke test

- `docker ps` bot-manager healthy: PASS — "Up 36 seconds (healthy)" (healthy within ~36s)
- Spring Boot ready log: PASS — `Started Starter in 7.732 seconds (process running for 8.803)`
- Auto-start log: PASS — `Bot Manager starting up - checking for bot groups to
  auto-start`; auto-started "116 Demo group", "XD game test", and others.
- DNS override intact (getent inside container): PASS —
  `getent hosts gamems.dev` → `10.30.1.104` (rc=0, via extra_hosts);
  `getent hosts google.com` → resolved (rc=0, via 1.1.1.1/8.8.8.8).
- UnknownHostException count: 0
- ERROR / "marking DEAD" lines: none
- Observability stack (single-compose smoke note): PASS — Grafana /api/health 200,
  Prometheus /-/healthy 200, Loki /ready 200; all 6 containers Up.

## Plan verification (docs/plans/TAI_XIU_114_JACKPOT.md § Verification)

### Step 1: App boots; no resolver regression.
Command: `curl -s http://localhost:8080/actuator/health`
Expected: HTTP 200, body `{"status":"UP"}`
Actual: HTTP 200, `{"status":"UP", mongo UP, diskSpace UP, ping UP, ssl UP}`
Result: PASS

### Step 2: P_114 Environment (appId set) and a taixiuJackpotPlugin TAI_XIU Game can be created via REST.
Command: (inspection instead of create — config already present; do not fabricate)
`curl -s -X POST http://localhost:8080/api/v1/game/filter/ -d '{}'` and `GET /api/v1/environment/`
Expected: a P_114 Environment with appId, and a TAI_XIU game with pluginName=taixiuJackpotPlugin
Actual:
  - P_114 TAI_XIU Game EXISTS: id `29d419f1-9c96-4e74-aec1-41c7fe5849c3`, name "Tai Xiu Jackpot",
    gameType TAI_XIU, **pluginName `taixiuJackpotPlugin`**. PASS for the game.
  - P_114 Environment EXISTS: id `394301f4-6daf-4c55-a073-502a81c00731`, name "114 Staging",
    productCode 114/RIK, wss://api-rikstaging.stgame.win, **but `appId` is null**
    (and `ProductCode.P_114.appId` is also null). Per the plan Config section, P_114
    requires the Environment's appId to be set for auth. **This env is NOT auth-ready.**
Result: PARTIAL — game config present and correct; **Environment appId missing (ops config gap)**.

### Step 3: A P_114 Tai Xiu bot group passes validation, starts, bots reach CONNECTION_AUTHENTICATED.
Command: (not executed)
Expected: bots CONNECTION_AUTHENTICATED; subscribe 1105 sent + 1105 response matched.
Actual: **No P_114 bot group exists** (P_114 env shows 0 groups; jackpot game referenced
  by no group). Starting one is blocked by the missing Environment appId (Step 2) — auth
  would fan out to the RIK gateway and is expected to fail without appId. Per releaser
  policy ("Do NOT fabricate config"), no group was created. **PENDING ops config.**
Result: PENDING (config-dependent) — not a code failure.

### Step 4: Rounds drive bets at the +100 CMDs with the `a` flag.
Command: (not executed — depends on Step 3)
Expected: bot_bets_placed_total{TAI_XIU}>0 for P_114; outbound bet cmd 1100 with `"a":false`;
  inbound StartGame/EndGame 1102/1104.
Actual: No P_114 traffic (`grep -c taixiuJackpotPlugin` in logs = 0). PENDING config.
Result: PENDING (config-dependent).

### Step 5: Refund-aware accounting holds for 114 (winnings counter populated).
Command: (not executed — depends on Step 3)
Expected: bot_winnings_total{TAI_XIU} >= 0 for P_114, plausible RTP.
Actual: No P_114 rounds. PENDING config.
Result: PENDING (config-dependent).

### Step 6: No 116 regression, no wedged 114 bots; observability stack UP.
Command:
  `curl .../bot-group/66cfc12c-.../health`,
  `docker logs … | grep taixiuPlugin | grep -oE '"cmd":1[0-9]{3}'`,
  observability health endpoints.
Expected: no Tai Xiu ERROR/DEAD; 116 still subscribes/bets at 1005/1002/1004/1000 with
  no `a` field; observability UP.
Actual:
  - 116 Tai Xiu group "Tai Xiu test" (66cfc12c…, 30 bots): status ACTIVE, **30/30
    CONNECTION_AUTHENTICATED, 0 dead, 0 reconnecting**; bots actively betting
    (44 bets/bot in health DTO). No wedged bots.
  - 116 cmd map from live logs: SENT subscribe **1005**; RECEIVED subscribe-resp **1005**,
    StartGame **1002**, EndGame **1004** — the **unchanged offset-0 CMDs**. The
    Phase-1 offset refactor did NOT regress 116.
  - No `a` field on any 116 Tai Xiu frame (`grep -c '"a":'` on 116 bet/frames = 0).
    The `a:false` change is correctly scoped to 114 only.
  - TAI_XIU metrics (116): bot_bets_placed_total = 6+, bot_bet_amount_total = 2.644e7,
    tagged to the 116 game (9d05c039 "TaiXiuStagingVerify") and 116 env (ad4e7948).
    No P_114 tags present (expected).
  - No Tai Xiu ERROR / "marking DEAD" lines.
  - Observability: Grafana 200, Prometheus 200, Loki 200.
Result: PASS

## Verdict

PASS (deploy + smoke + 116 regression).
P_114 live verification (Steps 3-5): **PENDING ops config** — the P_114 Environment
"114 Staging" has a null `appId` and no P_114 bot group exists. The code path is
deployed and the `taixiuJackpotPlugin` TAI_XIU game is present; live 1105/1100
confirmation requires ops to (a) set `appId` on the P_114 Environment, then
(b) create + start a P_114 Tai Xiu group. This is config, not a code defect, and
per releaser policy config was not fabricated.

## Notes

- The release file format's `docker image rm` step is N/A for the targeted-recreate
  flow used here (no full `down`); `docker load` handled the image swap.
- The 116 bet (cmd 1000) SENT frames were not in the live `docker logs` buffer at
  verification time (high-volume app, early bets rotated out); the 116 health DTO and
  TAI_XIU bet metrics confirm betting is active. The subscribe/start/end cmd map
  (1005/1002/1004) was directly observed, confirming the offset-0 path is intact.
