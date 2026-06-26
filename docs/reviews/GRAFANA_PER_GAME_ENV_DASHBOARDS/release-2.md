# Release-2 — GRAFANA_PER_GAME_ENV_DASHBOARDS (redeploy)

Mode: bot (two-payload: bot image + 2 dashboard JSONs)
Branch: main
Commit: dc30b93 (= origin/main, clean tracked tree)
Image: vingame-bot:latest (built 2026-06-26 ~15:07 +04)
Date: 2026-06-26T11:12:20Z
Target: Bot-1 only

## Purpose

Finish the dashboards feature. The prior deploy (`dc2ed49`) shipped the two
join gauges under the reserved Prometheus `_info` suffix; Micrometer strips
`_info` on scrape, so `game_info` / `environment_info` never reached the scrape
endpoint and the `$game` / `$environment` template variables came up empty.

Change since last deploy (commits `1d90abd`, `dc30b93`):
- Join gauges renamed `game_info`→`game_join`, `environment_info`→`environment_join`.
- `per-game.json` / `per-environment.json` `label_values(...)` queries repointed
  to the new gauge names. `bots.json` unchanged this redeploy.

## Preconditions

- `git status`: tracked tree clean; HEAD = main = dc30b93. PASS
- 917 tests green (verified by local build below). PASS
- DNS guard — `grep -n dns` on Bot-1 `/home/sgame/bot-java/docker-compose.yml`:
  line 26 `dns:` → `1.1.1.1`, `8.8.8.8` present. PASS
  (Local docker-compose.yml is stale/no-dns; never touched. Authoritative file
  is the one on Bot-1, which retains the override.)
- Dashboard JSONs verified to reference new gauges, no stale `_info`:
  - per-game.json: `label_values(game_join, gameName)`,
    `label_values(game_join{gameName=\"$game\"}, gameId)`
  - per-environment.json: `label_values(environment_join, environmentName)`,
    `label_values(environment_join{environmentName=\"$environment\"}, environmentId)`
  - No `game_info`/`environment_info` remaining. PASS

## Build (Payload 1)

- `mvn clean install`: PASS — Tests run: 917, Failures: 0, Errors: 0, Skipped: 0; BUILD SUCCESS (~21s)
- `docker build --no-cache --platform linux/amd64`: PASS
- `docker save -o bot.tar`: PASS (392,812,544 bytes)

## Ship

- `sftp put bot.tar`: PASS — remote size 392,812,544 == local (byte-exact)
- `sftp put per-game.json`: PASS — remote 17,241 == local
- `sftp put per-environment.json`: PASS — remote 17,652 == local
  (Destination: /home/sgame/bot-java/grafana/provisioning/dashboards/)

## Deploy

Payload 1 — bot image (targeted recreate, NOT full down):
- `docker load -i bot.tar`: PASS (Loaded image: vingame-bot:latest)
- `HOST_UID=… HOST_GID=… docker compose up -d --no-deps bot-manager`: PASS
- Targeted-recreate confirmed: bot-manager recreated (9s ago at check);
  grafana / loki / mongo / prometheus / promtail left running (2d / 19m
  uptimes untouched). Guard #2 satisfied.

Payload 2 — dashboards:
- `docker compose restart grafana`: PASS

## Smoke test

- `docker ps` bot-manager healthy: PASS — "Up 41 seconds (healthy)"
- Spring Boot ready log: PASS — "Started Starter in 8.162 seconds"; Tomcat on port 8085
- Auto-start log: PASS — "Bot Manager startup complete. 6 bot groups running"
  (116 Demo, XD game test, Fruit shop Bots, Slot test 204,
   TaiXiuStagingVerifyGroup2, Tai Xiu test)
- Bots reach CONNECTION_AUTHENTICATED: PASS — 142 bots authenticated across
  SLOT / TAI_XIU / BETTING_MINI; actively betting and receiving game frames.

## DNS override (post-deploy, inside container)

- `/etc/resolv.conf` shows `ExtServers: [1.1.1.1 8.8.8.8]` — override applied. PASS
- `getent hosts gamems.dev` → `10.30.1.104` (extra_hosts intact); WS host
  resolution working end-to-end (bots authenticated and exchanging messages). PASS

## Plan verification — the previously-failing checks

### Check 1: game_join / environment_join RENDER on the scrape endpoint
Command: `curl http://localhost:8085/actuator/prometheus` inside bot-manager,
grep `^game_join` / `^environment_join`.
Expected: non-empty series with gameId/gameName/gameType (game) and
environmentId/environmentName (environment) labels.
Actual:
- `environment_join{environmentId=ad4e7948…,environmentName="116 Staging"} 1.0`
- `game_join{…gameName="Fruit Shop",gameType="BETTING_MINI"} 1.0` (+ Xoc Dia,
  TaiXiuStagingVerify/TAI_XIU, Bau Cua, SlotTipTest/SLOT) — 5 series.
Result: PASS  ← this is the check that failed on the prior deploy.

### Check 2: bare `game` / `environment` metrics do NOT exist
Command: grep `^game[^_]` / `^environment[^_]` on the scrape output.
Expected: none. Actual: none. Also confirmed NO leftover `game_info`/`environment_info`.
Result: PASS

### Check 3: Prometheus actually scraped the gauges
Command: Prometheus `/api/v1/query?query=game_join` and `…=environment_join`.
Expected: non-empty vector, instance=bot-manager:8085.
Actual: `status:success`, 5 game_join series + 1 environment_join series,
all with full labels, value "1", instance "bot-manager:8085".
Result: PASS

### Check 4: Grafana up + dashboards provisioned
Command: Grafana `/api/health`; logs grep provisioning; `/api/search?type=dash-db`.
Expected: healthy, dashboards loaded without error.
Actual: health `database:ok`, v11.4.0. "finished to provision dashboards"
(no dashboard errors). Dashboards present: Bots, Game server, Per-Environment, Per-Game.
(Pre-existing unrelated provisioning warnings for optional plugins/alerting
dirs — present since 06-24, not introduced by this deploy.)
Result: PASS

### Check 5: $game / $environment template variables populate
Command: Grafana datasource proxy (uid=prometheus)
`label/gameName/values?match[]=game_join` and
`label/environmentName/values?match[]=environment_join`.
Expected: non-empty value lists (the lookup that returned empty before the rename).
Actual:
- $game → ["Bau Cua","Fruit Shop","SlotTipTest","TaiXiuStagingVerify","Xoc Dia"]
- $environment → ["116 Staging"]
Result: PASS

## Observability stack

All co-located services still up: prometheus, loki, mongo, grafana, promtail
(grafana intentionally restarted for dashboard reload; rest untouched). PASS

## Verdict

PASS — both payloads deployed; the previously-failing join-gauge render and
template-variable population now confirmed working end-to-end.
