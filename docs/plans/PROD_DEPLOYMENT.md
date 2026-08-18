# First Production Deployment — Bot Manager, scoped to P_116/TIP

## Goal

Stand up the Bot Manager stack on the production host `s009-botgame-general-01`
(alias `Prod-Bot`) for the **first time**, running the **current working-tree
artifact** (verified green on Bot-1/staging today) and the **full existing
`docker-compose.yml` stack** (bot-manager + Mongo + Loki + Promtail + Grafana +
Prometheus). Bring up **only P_116/TIP**, create all domain objects fresh via the
UI against an **empty prod DB**, and **iterate on whatever breaks** — the known
unknowns (prod gamems URL, prod TIP apiGateway/WS URLs, `bot.ip` whitelisting) are
discovered live during bring-up. This is money-making production infrastructure:
every on-box phase is small, reversible, and gated by an explicit user-runnable
check. **Claude does not deploy.** This document is a runbook the **user** executes
inside ticket-gated 12h `Prod-Bot` access windows; the `releaser` agent is
staging/Bot-1 only and has no part here.

## Findings — Current State

### The artifact = current working tree (three uncommitted changes)

All three claimed changes are present and verified against the repo:

1. **ws-parser bump** — `/Users/gleb/IdeaProjects/Bot/pom.xml:170`
   `websocket-parser-core` `2.3.10 → 3.0.5` (thread-leak fix). Confirmed via
   `git diff pom.xml`.
2. **`awaitServerReady(false)`** —
   `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/infrastructure/client/ClientFactory.java:67`,
   with the explanatory comment at `:60-66`. Opts out of the 3.0.x blocking
   AUTH-ACK gate that our servers don't emit; restores 2.3.10 connect semantics.
   The 3.0.5 thread-leak fix is independent of this flag and stays in effect.
3. **Compose obs hardening** — `/Users/gleb/IdeaProjects/Bot/docker-compose.yml`:
   the `x-logging` anchor (json-file, `max-size: 50m`, `max-file: 5` = 250 MB hard
   cap/container) applied to **all six** services; `restart: unless-stopped` added
   to `mongo`, `loki`, `promtail`, `grafana`, `prometheus` (bot-manager already had
   it); `GF_SECURITY_ADMIN_PASSWORD=${GRAFANA_ADMIN_PASSWORD:-admin}`.

These are **uncommitted** (`git status`: `M pom.xml`, `M docker-compose.yml`,
`M ClientFactory.java`). The user controls commits. The image that ships to prod
must be **built from this exact working tree** (see AD-1).

### Deploy mechanism

- **`/Users/gleb/IdeaProjects/Bot/deploy.sh`** — kills existing `bot-*` containers,
  `mkdir -p logs prometheus grafana/provisioning/dashboards`, writes `.env` with
  **only** `HOST_UID`/`HOST_GID` (from `id -u`/`id -g`), then `docker compose up -d`.
  Sudo-free. **Gotcha:** it **overwrites `.env` on every run** — anything else you
  want in `.env` (e.g. `GRAFANA_ADMIN_PASSWORD`) is clobbered. See AD-4.
- **`/Users/gleb/IdeaProjects/Bot/Dockerfile`** — `eclipse-temurin:21-jre`, copies
  `target/Bot-1.0.jar`, runs as an in-image `botmanager` user but compose overrides
  with `user: "${HOST_UID}:${HOST_GID}"`. Compose references `image: vingame-bot:latest`
  (no `build:` stanza) — the image is built/loaded out of band (`bot.tar` is a
  `docker save` of it, 393 MB in the repo root).
- **`/Users/gleb/IdeaProjects/Bot/scripts/host-prep-logs.sh`** — non-root cleanup of
  the `./logs` bind-mount target. Written for Bot-1's `/home/sgame/bot-java/logs`;
  the path is Bot-1-specific and must be re-pointed for the prod checkout dir.

### Config surface — globals vs per-Environment (verified)

**Globals that are NOT reachable from the UI** and need a prod value:

| Property | Consumed at | Current (staging) value |
|---|---|---|
| `gamems.url` | `EnvironmentClientRegistry.java:50` (`@Value`) | `http://gamems.dev:5007` (`application.properties:25`) |
| `bot.ip` | `AuthStrategyFactory.java:16` **and** `ApiGatewayClient.java:68` (both `@Value`) | `16.162.36.69` (`application.properties:65`) |
| compose `extra_hosts` | `docker-compose.yml:40-41` | `gamems.dev:10.30.1.104` (dev gamems) |

`bot.ip` is injected into `AuthStrategyFactory` and baked into every product login
body — for P_116 via `TipLoginRequest(username, password, fingerprint, botIp)`
(`AuthStrategyFactory.java:44-45`). It is also read independently by
`ApiGatewayClient`. **Both** must see the prod value.

`gamems.url` currently points at hostname `gamems.dev`, resolved only by the compose
`extra_hosts` entry to the dev gamems IP. On prod there is no such host — so the prod
value should be a **direct `http://IP:PORT`** (see AD-3), which also lets us drop the
`extra_hosts` dependency entirely.

**Per-Environment fields (set via UI, per strategy)** live on the `Environment`
entity: `apiGatewayUrl`, `webSocketMiniUrl`/`webSocketCardUrl`, `hostUrl`, `appId`,
`headers`, `encryptionKey`/`encryptionIv`, zones, `useJwtAuth`, `alertOnLowBalance`,
periodic-logout fields. `productCode` (P_116) is also on the Environment and drives
`AuthStrategyFactory`.

### Spring relaxed binding precedent

Compose already overrides `spring.data.mongodb.uri` via env var
`SPRING_DATA_MONGODB_URI` (`docker-compose.yml:36`). The same relaxed-binding path
maps `GAMEMS_URL → gamems.url` and `BOT_IP → bot.ip` — so prod globals can be
injected as **container env vars with no rebuild**. `application-loadtest.properties`
exists as a profile precedent but only sets `websocket.eventloop.threads`; there is
**no** `application-prod.properties`.

### P_116/TIP specifics (verified)

- **appId is brand-level, not env-specific — and it is already correct for prod.**
  `TipLoginRequest.java` hardcodes `app_id = "bc115116"`. `ProductCode.P_116`
  (`ProductCode.java:15`) is `P_116("116","TIP","bc115116",12)` — **same** value.
  Prod P_116 is the same TIP brand, so `bc115116` is correct on prod. The backlog
  "hardcoded appId" item is a **cleanliness/duplication** concern, **not a
  correctness blocker** for this deploy. (Correction to the task brief: this needs
  no attention before TIP bots can auth.)
- **Username pre-flight cap is already implemented** (correction to the CLAUDE.md
  backlog, which lists it unchecked). `BotGroupService.validateUsernameLength`
  (`BotGroupService.java:230-247`) throws `BadRequestException` → HTTP 400 when
  `namePrefix.length() + digits(botCount) > cap`; cap for P_116 = 12
  (`ProductCode.getUsernameMaxLength()`). It runs on create when **not**
  `skipRegistration` — i.e. exactly the UI-create-fresh path this deploy uses. So a
  too-long prefix fails cleanly, not as N forwarded upstream errors.
- **Staging TIP WS DNS block is staging-only.** Bot-1 cannot resolve
  `tipclubgw-sock.stgame.win`; prod TIP uses a different (prod) gateway discovered at
  bring-up. Likely N/A on prod, but WS-connect for TIP is an explicit bring-up gate
  (the failure signature is **auth succeeds → WS-connect fails**).

### Storage layout (correction to task brief)

The persistent stores are **named Docker volumes**, not `./loki`/`./prometheus`
bind-mounts: `mongo-data`, `loki-data`, `grafana-data`, `prometheus-data`
(`docker-compose.yml:116-120`). The only **bind-mounts** are `./logs` (rw for
bot-manager `:43`, ro for promtail `:67`), `./seed.js` (ro `:20`), and read-only
**config files** `./loki/loki-config.yaml`, `./promtail-config.yml`,
`./prometheus/prometheus.yml`, plus `./grafana/provisioning`. Consequences:
- SELinux (Permissive today) is relevant to the **`./logs` bind-mount and the config
  files**, not to a `./loki` data dir (there isn't one — Loki data is the named
  volume `loki-data`).
- Disk-fill risk is still real: named volumes live under `/var/lib/docker/volumes`
  on the **single shared partition**. Loki retention (`retention_period: 168h` = 7d,
  `loki/loki-config.yaml`) and the new 250 MB/container log cap are what bound it.

### Prometheus scrape target

`prometheus/prometheus.yml` scrapes only `bot-manager:8085/actuator/prometheus`
(compose-internal). Host node metrics (`:9100` node_exporter already on the box) are
**not** scraped and are **OPTIONAL for v1** (AD-7).

## Per-aspect readiness / mapping

| Aspect | Status | Notes |
|---|---|---|
| App artifact (3 working-tree changes) | **ready** | Build from current tree; verified green on Bot-1 today |
| Full-stack compose | **ready** | Ships as-is; obs hardening already in it |
| Prod host provisioning (Docker/Compose, ports, disk) | **ready** | Clean slate, docker without sudo, all ports free — but recheck in-window (shared box) |
| `gamems.url` prod value | **blocked (discovery)** | Unknown; fill live. Inject as env var |
| `bot.ip` prod value / TIP whitelisting | **partial** | Value ≈ NAT egress `43.199.58.254`; whitelisting for TIP UNKNOWN — a bring-up gate |
| Prod TIP apiGateway / WS URLs | **blocked (discovery)** | Unknown; set on the UI Environment at bring-up |
| Prod gamems URL/IP | **blocked (discovery)** | Unknown; inject as `GAMEMS_URL` |
| P_116 appId | **ready** | `bc115116` correct for prod; no action |
| Username cap enforcement | **ready** | Implemented; 400 on violation |
| Grafana admin password | **partial** | Overridable, but `deploy.sh` clobbers `.env` (AD-4) |
| SELinux / bind-mounts | **ready** | Permissive → mounts work; add `:z` only if it flips to Enforcing |
| Host metrics in Grafana | **out of scope v1** | Optional (AD-7) |
| Rollback | **ready (as stop)** | No prior prod image → rollback = stop-stack (AD-8) |

## Architecture Decisions

- **AD-1 — Artifact provenance.** Prod ships the **exact current working tree**
  (the three uncommitted changes above), built with
  `JAVA_HOME=<jdk-21> mvn package -DskipTests -Dmaven.javadoc.skip=true` producing
  `target/Bot-1.0.jar`, imaged as `vingame-bot:latest`, transferred to `Prod-Bot`
  as a `docker save`/`docker load` tar (same mechanism as `bot.tar`). **No rebuild
  on the box.** Tag the image additionally as `vingame-bot:prod-v1-<date>` before
  transfer so a future rollback has a named known-good (AD-8). The user should
  commit these three changes before building so the deployed artifact is traceable
  to a SHA (recommended, not required by this plan).
- **AD-2 — Prod globals injected as container env vars, NOT a profile file.**
  Use Spring relaxed binding: `GAMEMS_URL` and `BOT_IP` set in the bot-manager
  `environment:` block. Rationale: strategy mandates the **same artifact** (a baked
  `application-prod.properties` means a rebuild), and the endpoints are unknown at
  build time — env vars let them be filled live. No `application-prod.properties`
  is created.
- **AD-3 — `gamems.url` is a direct `http://IP:PORT`, dropping `extra_hosts`.**
  Set `GAMEMS_URL=http://<prod-gamems-ip>:<port>` so no hostname resolution is
  needed; the dev `extra_hosts: gamems.dev:10.30.1.104` entry is removed/overridden
  for prod. Eliminates a whole class of "works on staging via extra_hosts" bugs.
- **AD-4 — Prod overrides live in a `docker-compose.prod.yml` overlay, invoked
  explicitly; `deploy.sh` is not used unmodified on prod.** Because `deploy.sh`
  overwrites `.env` (only UID/GID) and runs plain `docker compose up -d`, prod uses
  a small overlay carrying `GAMEMS_URL`, `BOT_IP`, `GRAFANA_ADMIN_PASSWORD`, and the
  `extra_hosts` removal, brought up with
  `docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d`. UID/GID
  still come from a hand-written `.env` (`HOST_UID`/`HOST_GID` = `sgame`'s ids).
  The overlay is a prod-host-only file, never committed to a staging branch.
- **AD-5 — Empty prod DB; all domain objects created via UI.** No `seed.js`, no
  clone from staging. First objects: one P_116 Environment, one TIP Game, one small
  BotGroup. Mongo starts empty on the fresh `mongo-data` volume.
- **AD-6 — TIP-only, small first group.** First BotGroup is deliberately tiny
  (≈3–5 bots) with a short `namePrefix` (≤ ~9 chars so prefix+digits ≤ 12) to walk
  auth→WS→subscribe→bet before any scale-up. Scale is a later, separate action.
- **AD-7 — Host metrics in Grafana are OPTIONAL for v1 and deferred.** Do not add
  our own node_exporter (host already runs one on `:9100` + Zabbix on `:10050`). If
  wanted later, add a Prometheus scrape target for the host's `:9100` reachable via
  the docker0 gateway `172.17.0.1` (RHEL has no `host.docker.internal`) or an
  `extra_hosts` alias — verified then, not now.
- **AD-8 — Rollback = stop-stack, not image-swap.** There is no prior prod image;
  for v1 the known-good IS this artifact. Rollback of the app = `docker compose down`
  (bots stop, nothing external depends on them yet). A future second version rolls
  back by redeploying the `vingame-bot:prod-v1-<date>` tag. Data rollback = drop the
  Mongo volume (the DB is expendable during bring-up).
- **AD-9 — Claude never touches the prod box.** All on-box steps are user-run inside
  a ticket window. No agent (including `releaser`) acts on `Prod-Bot`.

## Plan

### Phase 0 — Pre-deploy prep (off-box + one short access window)

Off-box (no window needed):
1. **Build the artifact** from the current tree per AD-1; tag `vingame-bot:latest`
   **and** `vingame-bot:prod-v1-<date>`; `docker save` both to a tar for transfer.
2. **Author `docker-compose.prod.yml`** (overlay, AD-4) with, under
   `services: bot-manager:`, an `environment:` adding `GAMEMS_URL`, `BOT_IP`
   (placeholders for now — real values filled in Phase 3) and `extra_hosts: []` (or
   the prod gamems host if a hostname is preferred), and under `services: grafana:`
   an `environment:` adding `GRAFANA_ADMIN_PASSWORD`. Keep placeholders obvious
   (e.g. `BOT_IP: "REPLACE_AT_BRINGUP"`).
3. **Prepare the checkout dir layout** the compose expects on the box: the repo dir
   containing `docker-compose.yml`, the overlay, `loki/loki-config.yaml`,
   `promtail-config.yml`, `prometheus/prometheus.yml`, `grafana/provisioning`, and an
   empty `./logs`. Re-point `scripts/host-prep-logs.sh`'s `LOGS_DIR` to the prod
   checkout's `logs` dir (or just `mkdir -p logs` by hand — it is a plain empty dir).

On-box (short window):
4. **Fresh port + host recheck** (shared box — other tenants `lua/sym/tom` +
   `oz-*`). Confirm our six ports are still free and Docker is usable without sudo.

**Verification (Phase 0, on-box):**
```bash
docker version && docker compose version           # expect: client+server print, no permission error
for p in 8080 8085 27017 3000 9090 3100; do \
  ss -ltn "sport = :$p" | grep -q ":$p" && echo "PORT $p BUSY" || echo "PORT $p free"; done
# expect: all six print "free"
getenforce                                         # expect: Permissive
df -h / | tail -1                                  # expect: Use% well under, e.g. ~4%, ample free
docker ps -a                                       # expect: no containers (clean slate)
```
If any port is BUSY, stop and reconcile before Phase 1 (do not proceed).

### Phase 1 — First full-stack deploy + stack-health smoke (one window)

User, on-box, in the prod checkout dir:
1. Write `.env` by hand with `sgame`'s UID/GID:
   `printf 'HOST_UID=%s\nHOST_GID=%s\n' "$(id -u)" "$(id -g)" > .env`
   (Do **not** run `deploy.sh` — it would clobber `.env` and skip the overlay, AD-4.)
2. `docker load -i <artifact-tar>` (loads `vingame-bot:latest` + the `prod-v1` tag).
3. Bring up the stack with the overlay:
   `docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d`
   (Endpoints are still placeholders — bots are not started yet, so the app boots
   fine; only bot **auth** needs the real globals, which come in Phase 3.)

**Verification (Phase 1):**
```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml ps
# expect: 6 services, all "running"/"healthy" (mongo healthy gate satisfied)

curl -fsS http://localhost:8080/actuator/health           # expect: HTTP 200, {"status":"UP"}
curl -fsS http://localhost:8080/actuator/prometheus | head # expect: 200, jvm_* / process_* metrics text
curl -fsS 'http://localhost:9090/api/v1/targets' \
  | grep -o '"health":"up"'                                 # expect: at least one "health":"up" (bot-manager)
curl -fsS -o /dev/null -w '%{http_code}\n' http://localhost:3000/login   # expect: 200 (Grafana up)
curl -fsS http://localhost:3100/ready                      # expect: "ready"
docker exec $(docker compose -f docker-compose.yml -f docker-compose.prod.yml ps -q mongo) \
  mongosh --quiet --eval 'db.runCommand({ping:1}).ok'      # expect: 1

# Grafana password actually took (not the default):
curl -fsS -u admin:"$GRAFANA_ADMIN_PASSWORD" http://localhost:3000/api/org | grep -q '"id"' \
  && echo GRAFANA_AUTH_OK                                   # expect: GRAFANA_AUTH_OK

# Thread baseline sane (thread-leak fix in effect):
curl -fsS http://localhost:8080/actuator/metrics/jvm.threads.live \
  | grep -o '"value":[0-9]*'                                # expect: low tens (staging baseline was 58)
```
If the window closes after `up -d` but before smoke passes: the stack has
`restart: unless-stopped`, so it survives the disconnect; resume smoke next window.

### Phase 2 — Create P_116 prod Environment / Game / BotGroup via UI (one window)

The app UI is reachable on the box at `http://localhost:8080` (tunnel per the access
ticket; external ingress is SG/NAT-gated). Create, in order:
1. **Environment** (P_116/TIP): set `productCode=P_116`, and the per-env fields
   (`apiGatewayUrl`, `webSocketMiniUrl`/`webSocketCardUrl`, `hostUrl`, `appId`,
   `headers`, `encryptionKey`/`encryptionIv`, zones, `useJwtAuth`,
   `alertOnLowBalance`, periodic-logout). Some of these (apiGateway/WS URLs) are the
   **discovery unknowns** — enter best-known values; they get corrected in Phase 3.
2. **Game** (a TIP game, e.g. Tai Xiu / a betting-mini variant enabled for P_116),
   scoped to that Environment.
3. **BotGroup** — tiny per AD-6: `botCount` 3–5, short `namePrefix` (≤ ~9 chars).
   Leave it **not started** (or `MANUAL_OFF`) — Phase 3 starts it deliberately.

**Verification (Phase 2):**
```bash
curl -fsS http://localhost:8080/api/v1/environment/ | grep -o '"productCode":"P_116"'   # expect: present
curl -fsS http://localhost:8080/api/v1/game/       | grep -q '"id"' && echo GAME_OK      # expect: GAME_OK
curl -fsS http://localhost:8080/api/v1/bot-group/  | grep -q '"namePrefix"' && echo GROUP_OK # expect: GROUP_OK
```
A deliberately-too-long prefix here is a good negative check: creating a group with
`namePrefix` long enough that `prefix+digits(botCount) > 12` must return **HTTP 400**
(`validateUsernameLength`), not fan out. Optional but recommended:
```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/api/v1/bot-group/ \
  -H 'Content-Type: application/json' \
  -d '{"environmentId":"<id>","gameId":"<id>","namePrefix":"waytoolongprefix","botCount":10,"password":"x"}'
# expect: 400
```

### Phase 3 — Bring up TIP bots; walk auth → WS-connect → subscribe → bet (one+ windows)

This is where the discovery unknowns resolve. Expected failure points, in order:
`bot.ip`/whitelisting, then the prod TIP gateway/WS URLs.

1. **Set the real prod globals** in `docker-compose.prod.yml`: `BOT_IP` (start with
   the NAT egress `43.199.58.254`, AD-3 note below) and `GAMEMS_URL=http://<prod-gamems-ip>:<port>`
   once discovered. Re-apply: `docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d`
   (recreates only bot-manager). Correct the Environment's `apiGatewayUrl`/WS URLs via
   UI PATCH as the prod TIP gateway is confirmed.
2. **Start the BotGroup** via `POST /api/v1/bot-group/{id}/start`.
3. Watch the walk in logs (raise to DEBUG/TRACE if needed via
   `POST /actuator/loggers/com.vingame.bot {"configuredLevel":"DEBUG"}`).

**Expected failure signatures & the fix loop:**
- **Auth 4xx / "account does not exist" / IP-reject** → `bot.ip` not whitelisted for
  TIP against `43.199.58.254`. Get the egress IP whitelisted (external team) or set
  `BOT_IP` to whatever the TIP gateway expects; re-apply and restart the group.
- **Auth succeeds → WS-connect fails** (the staging DNS-block signature, but here it
  means wrong/unreachable prod WS URL) → correct `webSocketMiniUrl`/`webSocketCardUrl`
  on the Environment; restart group.
- **WS connect → no subscribe / no game messages** → wrong game offset/plugin or
  subscriber pruning (known bug) — check the Game config and the watchdog.

**Verification (Phase 3) — run against the live log stream:**
```bash
CID=$(docker compose -f docker-compose.yml -f docker-compose.prod.yml ps -q bot-manager)

# Auth handshake NOT timing out (the 3.0.5 regression the awaitServerReady(false) fixes):
docker logs "$CID" 2>&1 | grep -c 'Auth handshake timed out'      # expect: 0

# Successful auth for TIP bots (adjust to the group's namePrefix):
docker logs "$CID" 2>&1 | grep -Ei 'Successfully registered all|authenticat' | tail   # expect: success lines, no upstream-login errors

# WS connect confirmed:
docker logs "$CID" 2>&1 | grep -i 'Connected to server' | wc -l    # expect: == botCount (e.g. 3–5)

# Subscribe + first round frames observed (session summaries are INFO):
docker logs "$CID" 2>&1 | grep -Ei 'StartGame|EndGame|session' | tail   # expect: session-entry / results summaries appear

# First successful bet / settlement (BettingMini/TaiXiu emit per-round summaries at INFO;
# raise com.vingame.bot to DEBUG for the 5s UpdateBet histogram if you need the bet detail):
docker logs "$CID" 2>&1 | grep -Ei 'EndGame results|payout|winnings' | tail   # expect: a settled round with the bots present
```
A **round the TIP bots both bet in and receive EndGame for**, with a non-error
settlement, is the Phase-3 success gate. If a window closes mid-walk, the group keeps
its `targetStatus` and the stack restarts itself; resume the fix loop next window from
the last failing signature.

### Phase 4 — Steady-state verification

See `## Verification`.

### Phase 5 — Rollback (AD-8)

No prior prod image exists, so rollback is **stop**, not swap:
1. **App-only rollback / abort bring-up:**
   `docker compose -f docker-compose.yml -f docker-compose.prod.yml down`
   (stops all six; nothing external depends on the bots yet). Bots cease; no player
   impact.
2. **Config-only revert:** if a bad `GAMEMS_URL`/`BOT_IP`/URL change wedged auth,
   restore the prior overlay values and `up -d` again — no image change needed.
3. **Data reset** (DB is expendable during bring-up): `down`, then
   `docker volume rm <project>_mongo-data`, then `up -d` for a clean empty DB;
   recreate objects via UI (Phase 2).
4. **Future second-version rollback:** redeploy the retained
   `vingame-bot:prod-v1-<date>` tag (retag to `:latest`, `up -d`).

## Implementation Notes / Concerns

- **`deploy.sh` must not be used verbatim on prod** — it clobbers `.env` and omits
  the overlay (AD-4). Bring up with the explicit two-file `docker compose` command.
- **Grafana password propagation** — it comes from the overlay's `environment:`
  block (container env), which is robust regardless of `.env`. Do not rely on shell
  `export` + `deploy.sh` (the `.env` rewrite path is fragile). Confirm with the
  `GRAFANA_AUTH_OK` check.
- **`bot.ip` has two independent readers** (`AuthStrategyFactory` **and**
  `ApiGatewayClient`) — a single `BOT_IP` env var feeds both; no per-reader override.
- **`gamems.url` prod = direct IP:PORT** (AD-3) — do not carry the `gamems.dev`
  hostname to prod; there is no resolver for it there.
- **Named volumes, not bind data dirs** — a `docker compose down -v` (note the `-v`)
  wipes Mongo/Loki/Grafana/Prometheus data. Use plain `down` for rollback unless a
  data reset is intended.
- **SELinux Permissive today** — bind-mounts (`./logs`, config files) work. If the
  box is ever flipped to Enforcing, add `:z`/`:Z` labels to the bind-mount lines;
  named volumes are unaffected.
- **Shared box** — recheck ports every window (Phase 0 check); another tenant could
  bind one of ours between windows.
- **No swap, 15 GiB RAM** — the app runs with `-XX:MaxRAMPercentage=75.0`
  `-XX:+ExitOnOutOfMemoryError`. With a tiny first group this is a non-issue, but do
  not scale bot count in this deploy without a separate memory review.
- **Ticket-gated 12h windows** — every on-box phase above fits well within a window;
  the stack's `restart: unless-stopped` means an interrupted phase survives the
  disconnect. Never leave a phase in a state that needs the window to stay open.
- **Username cap** — keep `namePrefix` short (AD-6); a violation is a clean 400, but
  it still costs a round-trip and confusion during bring-up.

## Open Items

- **Prod gamems URL/IP** — unknown; discovered in Phase 3, injected as `GAMEMS_URL`.
- **Prod TIP apiGateway + WS URLs** — unknown; set on the UI Environment in Phase 2–3.
- **`bot.ip` whitelisting for TIP** against `43.199.58.254` — unverified; external
  dependency, the primary Phase-3 gate.
- **Host metrics in Grafana** — deferred/optional (AD-7).
- **Committing the three working-tree changes** — user-controlled; recommended before
  the build for traceability but not required by this plan.
- **Scale-up beyond the 3–5 bot smoke group** — explicitly out of scope; separate
  action after v1 is proven, with a memory review (no swap).
- **`application-prod.properties`** — intentionally NOT created (AD-2); revisit only
  if env-var injection proves insufficient.

## Verification

The universal per-phase checks are inline above. The consolidated steady-state
verification the user runs on `Prod-Bot` after Phase 3 succeeds:

```bash
# 0. All six services up and staying up (restart policy holding, no crash-loop)
docker compose -f docker-compose.yml -f docker-compose.prod.yml ps
# expect: 6 rows, all State=running, bot-manager Health=healthy, RestartCount not climbing

# 1. App health
curl -fsS http://localhost:8080/actuator/health
# expect: HTTP 200, body {"status":"UP"}

# 2. No auth-handshake regression (awaitServerReady(false) working)
docker logs $(docker compose -f docker-compose.yml -f docker-compose.prod.yml ps -q bot-manager) 2>&1 \
  | grep -c 'Auth handshake timed out'
# expect: 0

# 3. Thread baseline stable (thread-leak fix; not the sawtooth climb)
curl -fsS http://localhost:8080/actuator/metrics/jvm.threads.live | grep -o '"value":[0-9]*'
# expect: low tens (~<100), and NOT growing across repeated calls over minutes

# 4. Prometheus scraping the app
curl -fsS 'http://localhost:9090/api/v1/targets' | grep -o '"health":"up"'
# expect: at least one "health":"up"

# 5. Loki ingesting app logs (promtail → loki pipeline alive)
curl -fsS 'http://localhost:3100/loki/api/v1/labels' | grep -q 'container\|job' && echo LOKI_LABELS_OK
# expect: LOKI_LABELS_OK

# 6. TIP bots authenticated + connected (adjust namePrefix)
docker logs $(docker compose -f docker-compose.yml -f docker-compose.prod.yml ps -q bot-manager) 2>&1 \
  | grep -ci 'Connected to server'
# expect: == the group's botCount

# 7. A settled TIP round with the bots present (money path works end to end)
docker logs $(docker compose -f docker-compose.yml -f docker-compose.prod.yml ps -q bot-manager) 2>&1 \
  | grep -Ei 'EndGame results' | tail
# expect: at least one EndGame results summary line naming the group after bring-up

# 8. Disk not filling (log caps + Loki 7d retention holding)
df -h / | tail -1
# expect: Use% still low, not climbing window-over-window
```

This deploy has meaningful on-server verification beyond the universal smoke test —
steps 6–7 (TIP auth→connect→settled round) are the feature-specific gates and must
pass before the deploy is considered live.
