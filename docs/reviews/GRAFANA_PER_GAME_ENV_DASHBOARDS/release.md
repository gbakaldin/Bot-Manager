# Release — GRAFANA_PER_GAME_ENV_DASHBOARDS

Mode: bot (two-payload: bot image + bind-mounted Grafana dashboard JSONs)
Branch: main @ dc2ed49a36e88293db10041c7dd8eb28e21f0f42
Image: vingame-bot:latest (built 2026-06-26T10:47Z, amd64, --no-cache)
Date: 2026-06-26T10:55Z
Target: Bot-1 only

## Verdict

**PARTIAL FAIL** — Infrastructure deploy fully succeeded (both payloads shipped,
all containers healthy, DNS override intact, all three dashboards loaded, bot
metric labels correct). However, **one verification requirement failed**: the
`game_info` and `environment_info` join gauges are NOT present in Prometheus
(2 of the 3 required info/breakdown series are missing from the scrape endpoint).
This is a defect in the deployed application code, not a deploy error. See
"New metric labels" below.

---

## Pre-flight

- Working tree: only untracked docs/build artifacts; tracked dashboard JSONs clean (committed): PASS
- DNS override present in Bot-1 docker-compose.yml (`dns: ["1.1.1.1","8.8.8.8"]`, lines 26-28): PASS
- Grafana volume mount confirmed: `./grafana/provisioning:/etc/grafana/provisioning` → host dashboards dir `/home/sgame/bot-java/grafana/provisioning/dashboards/`: PASS

## Build (Payload 1)

- `mvn clean install` (JDK 21): PASS — 916 tests, 0 failures/errors/skipped, BUILD SUCCESS (~23s)
- `docker build --no-cache --platform linux/amd64`: PASS
- `docker save -o bot.tar`: PASS (392,812,544 bytes)

## Ship

- `sftp put bot.tar` → /home/sgame/bot-java/bot.tar: PASS (392,812,544 bytes on server, size matches)
- `sftp put per-game.json` → grafana/provisioning/dashboards/: PASS (17,241 bytes, matches)
- `sftp put per-environment.json`: PASS (17,652 bytes, matches)
- `sftp put bots.json` (overwrite/repoint): PASS (24,993 bytes, matches)

## Deploy

NOT a full `down`. Targeted recreate of bot-manager only; Prometheus / Loki /
promtail / mongo / grafana left running (grafana later restarted on purpose).
docker-compose.yml NOT modified — DNS override preserved.

- `docker compose stop bot-manager`: PASS
- `docker compose rm -f bot-manager`: PASS
- `docker image rm vingame-bot:latest` (old fa22f9e406fd): PASS — old image removed
- `docker load -i bot.tar`: PASS — "Loaded image: vingame-bot:latest"
- `HOST_UID=$(id -u) HOST_GID=$(id -g) docker compose up -d --no-deps bot-manager`: PASS — container recreated
- `docker compose restart grafana` (Payload 2 reload, deterministic): PASS

## Smoke test

- `docker ps` bot-manager: PASS — "Up About a minute (healthy)"
- Spring Boot ready: PASS — `Started Starter in 7.058 seconds`
- Auto-start ran: PASS — bots across SLOT / BETTING_MINI / TAI_XIU reach
  `STARTED → CONNECTION_AUTHENTICATED`
- Other stack containers untouched (mongo, prometheus, loki, promtail healthy/up): PASS

## Guard verification

### DNS override intact
- `grep -n -A2 dns docker-compose.yml`: PASS — lines 26-28 `dns: ["1.1.1.1","8.8.8.8"]` still present (file never written)
- `docker exec bot-java-bot-manager-1 getent hosts tipclubgw-sock.stgame.win`: PASS — resolves
  (2606:4700:440c::6812:24b6 / 2a06:98c1:3105::ac40:974a)
- `UnknownHostException` count in bot-manager logs: PASS — 0

### Stack not taken down
- mongo, prometheus, loki, promtail uptime preserved across deploy (Up 2 days / 47 hours): PASS

## Payload 2 — Grafana dashboards

- Grafana health: PASS — `http://localhost:3000/api/health` → HTTP 200
- Provisioning ran cleanly this restart: PASS —
  `provisioning.dashboard ... "starting to provision dashboards"` →
  `"finished to provision dashboards"`, no dashboard-provisioning errors
- Dashboards loaded (Grafana `/api/search` + `/api/dashboards/uid`):
  - `per-game` (title "Per-Game"): PASS
  - `per-environment` (title "Per-Environment"): PASS
  - `bots` (repointed bots.json): PASS — present via `/api/dashboards/uid/bots`
- Pre-existing benign grafana log noise (NOT caused by this deploy, NOT dashboard
  errors): grafana.com / stats.grafana.org DNS lookup failures (air-gapped plugin
  /update/usage-stats checks), `xychart already registered`, missing
  `provisioning/plugins` and `provisioning/alerting` dirs. None affect dashboard
  provisioning.

## New metric labels (Payload 1 feature)

Verified against the actual scrape endpoint `/actuator/prometheus` (app listens on
**8085** inside the container, not 8080) AND against the Prometheus server
(`/api/v1/query`), which is what the dashboards read.

PASS items:
- `bot_*` series now carry `gameId` and `gameName`
  (e.g. `bot_bets_placed_total{... gameId="c5a22c44-...", gameName="SlotTipTest", gameType="SLOT"}`)
- `gameType` carries the ENUM: observed `BETTING_MINI`, `SLOT`, `TAI_XIU` (not raw code/offset)
- `bots_by_game_status` present in Prometheus (per gameId/gameName/status)
- `bots_by_env_status` present in Prometheus (per environmentId/status)

**FAIL items:**
- `game_info` — **absent from Prometheus** (`/api/v1/query?query=game_info` → empty
  vector; not present in `/actuator/prometheus`).
- `environment_info` — **absent from Prometheus** (same; empty vector).

### Defect detail (game_info / environment_info)

The two AD-2 "join" MultiGauges are registered and ARE populated when read via the
actuator JSON view:
`/actuator/metrics/game_info` returns value 5.0 with all five gameId / gameName /
gameType (SLOT, BETTING_MINI, TAI_XIU) tags — i.e. the upstream data
(`BotGroupBehaviorService.listRunningGameInfo()` / `listRunningEnvironmentInfo()`)
is correct and the refresher is running (`InfoGaugeRefresher` started, interval=10s,
no refresh failures in logs).

But those same two MultiGauges emit **nothing** on `/actuator/prometheus`, so
Prometheus never scrapes them. Their three sibling MultiGauges built and refreshed
in the identical code path (`bots_by_game_status`, `bots_by_env_status`) DO render
to the Prometheus endpoint and ARE in Prometheus. Only the two value-1 "_info"
join gauges fail to render to the Prometheus exposition.

Impact: per-game / per-environment dashboard panels that resolve human-readable
names via a join on `game_info` / `environment_info` will not get those names from
Prometheus. The breakdown panels driven by `bots_by_game_status` /
`bots_by_env_status` and the `bot_*` series (which already carry `gameName`
directly) are unaffected. Recommend a dev follow-up to fix the `_info` MultiGauge
rendering to the PrometheusMeterRegistry. This is a code defect, not a deploy
issue — no redeploy of the current image will change it.

## Commands (key)

```
# scrape endpoint is on 8085 inside the container
docker exec bot-java-bot-manager-1 curl -s http://localhost:8085/actuator/prometheus
# prometheus server query
docker exec bot-java-prometheus-1 wget -qO- 'http://localhost:9090/api/v1/query?query=game_info'
```

## Evidence excerpts

```
# Prometheus server — join gauges MISSING:
query=game_info        -> {"status":"success","data":{"resultType":"vector","result":[]}}
query=environment_info -> {"status":"success","data":{"resultType":"vector","result":[]}}

# Prometheus server — breakdown gauge PRESENT:
bots_by_game_status{gameId="45d42867-...",gameName="Fruit Shop",status="CONNECTION_AUTHENTICATED"} 40
bots_by_env_status{environmentId="ad4e7948-...",status="CONNECTION_AUTHENTICATED"} 142

# Prometheus server — bot_* labels PRESENT:
bot_bets_placed_total{botGroupId="4342888f-...",environmentId="ad4e7948-...",
  gameId="c5a22c44-...",gameName="SlotTipTest",gameType="SLOT", ...}

# Actuator JSON view — data exists but never reaches the scrape endpoint:
/actuator/metrics/game_info -> measurements VALUE 5.0;
  gameId[5], gameType[SLOT,BETTING_MINI,TAI_XIU], gameName[Xoc Dia,Fruit Shop,Bau Cua,SlotTipTest,TaiXiuStagingVerify]
```
