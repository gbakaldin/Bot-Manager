# Release — WIN79_119_ENABLEMENT

Mode: bot
Branch: `feature/dead-group-auto-recovery` (working tree, uncommitted — intentional)
Image: `vingame-bot:latest` — `sha256:d33496525c1c...`, built 2026-09-10 15:05 +04
Target: **Bot-1 (staging)**, `/home/sgame/bot-java`
Date: 2026-09-10T11:03:22Z → 2026-09-10T11:10Z (UTC)

## Payload

Two changes enabling a P_119 / WIN79 Tai Xiu bot group; both were previously hard
failures on the bot thread at group start.

| File | Change |
|---|---|
| `bot-engine/.../domain/bot/auth/Win79LoginRequest.java` | new — emits the `ip`/`os`/`device`/`browser`/`fg` body the gwms gateway requires |
| `bot-app/.../infrastructure/auth/AuthStrategyFactory.java` | modified — dedicated `P_119` arm (was falling through to `DefaultLoginRequest`, which omits `ip`) |
| `bot-messages/.../message/taixiu/Win79TaiXiuMessageTypes.java` | new — `@MessageTypesImpl(gameType = TAI_XIU, products = "119")` |
| 5 test files | inventories updated (`MessageTypesCoverageTest`, `TaiXiuMessageTypesTest`, `MessageTypesRegistryTest`, `AuthStrategyFactoryTest`, `LoginRequestSerializationTest`) |

Also present in the working tree and **inert for the image**: `CLAUDE.md`,
`Aviator.js`, deleted `TaiXiuMessages/*.js`, `docs/**`, and `deploy.sh`
(+11 lines, never committed by design and **not used by this deploy** — see Deploy).

Nothing was committed, pushed or amended.

## Build

- `mvn clean install`: **PASS** (55.9 s wall, 2026-09-10T11:03:22Z → 11:04:19Z; JDK 21.0.2)
  - All 6 reactor modules SUCCESS
  - **2090 tests, 0 failures, 0 errors** (counted from `target/surefire-reports/*.xml`):
    bot-api 138 · bot-strategies 126 · bot-messages 159 · bot-engine 431 · bot-app 1236
  - Matches the counts reported at handover.
- `docker build --no-cache --platform linux/amd64`: **PASS** (24 s) → `sha256:d33496525c1c…`
  - Docker Desktop was not running at first invocation; started it and re-ran. No other deviation.
- `docker save -o bot.tar`: **PASS** — **409,359,872 bytes**, sha256 `6be0c6c4efab…13fe9`

## Ship

- `sftp put bot.tar`: **PASS** (~32 s)
- Integrity verified rather than assumed: remote `sha256sum` = `6be0c6c4efabc306ae8d7edfaf944d8a35afba5c64baa9e8fb3af1e774413fe9`, **identical to local**, size 409,359,872 bytes.

## Pre-deploy baseline (captured before `compose down`)

10/10 containers `Up 2 days`; bot-manager `(healthy)`.
Registry line read `BETTING_MINI 4 products [097, 098, 116, 118], TAI_XIU **2** products [114, 116]` —
so verification step 1 below is a genuine before/after, not a tautology.

## Deploy

Single `ssh` block, exit code **0**. Standard pipeline used — **`deploy.sh` was deliberately
not run**: it overwrites `.env` with a freshly generated block and only re-appends
`secrets.env` if that file exists on the host, so running it risks silently dropping the
VipTalk bot token. The plain `docker compose up -d` path does not touch `.env`.

- `docker compose down`: **PASS**
- `docker image rm vingame-bot:latest`: **PASS** (untagged + 12 layers deleted)
- `docker load -i bot.tar`: **PASS** — `Loaded image: vingame-bot:latest`
- `docker compose up -d`: **PASS** — all 10 containers recreated and started; mongo gated on
  its healthcheck before bot-manager started, as configured.
- **Image identity confirmed:** running container's image is
  `sha256:d33496525c1cc44f500fad5062c35dc917147773f4e7a8d69a659ef7bd8dbb24`, byte-identical
  to the locally built image ID `d33496525c1c`. The container is running *this* build.

## Smoke test

- `docker ps` shows healthy: **PASS** — `bot-java-bot-manager-1  Up 36 seconds (healthy)`
  (reached `healthy` ~30 s after start, well inside the start period)
- Spring Boot ready log: **PASS** — `Started Starter in 6.165 seconds (process running for 7.288)`
- Auto-start log: **PASS** — `Bot Manager startup complete. 2 bot groups running`

## Plan verification

There is no plan file for this feature; the four steps supplied in the release brief were
used in place of a `## Verification` section.

### Step 1: The registry came up with 119
Command: `ssh Bot-1 'docker logs bot-java-bot-manager-1 2>&1 | grep "MessageTypesRegistry initialized"'`
Expected: `TAI_XIU 3 products [114, 116, 119]`; `BETTING_MINI 4 products [097, 098, 116, 118]` unchanged.
Actual:
```
MessageTypesRegistry initialized: BETTING_MINI 4 products [097, 098, 116, 118], TAI_XIU 3 products [114, 116, 119], SLOT provider SlotMessageTypesImpl
```
Exactly one such line (one per JVM). TAI_XIU went 2 → 3 products with `119` appended in sorted
position; BETTING_MINI is character-for-character identical to the pre-deploy baseline.
Result: **PASS**

### Step 2: The 119 environment survived the restart
Command: `ssh Bot-1 'docker exec bot-java-mongo-1 mongosh botmanager --quiet --eval "JSON.stringify(db.environments.findOne({productCode:\"P_119\"}))"'`
Expected: the `119 Staging` record, `_id` `d005157f-4dfb-479d-a39a-36a3892439b7`.
Actual: present, `_id` `d005157f-4dfb-479d-a39a-36a3892439b7`, `name` `119 Staging`,
`type` `STAGING`, `brandCode` `G4`, `productCode` `P_119`, `appId` `w79.club`,
`hostUrl` `https://w79.stgame.win`, `apiGatewayUrl` `https://apigw-w79.sgame.us`,
`webSocketMiniUrl` `wss://w79.sgame.club/websocket_mini`.
Result: **PASS**

### Step 3: No new ERROR at startup
Command: `ssh Bot-1 'docker logs bot-java-bot-manager-1 2>&1 | grep -E "ERROR|Exception" | head -30'`
Expected: report anything present; pre-existing noise acceptable.
Actual: **zero matches.** `grep -c` returns `0` across all **81** log lines emitted since start.
No ERROR, no exception, no stack trace — not even pre-existing noise.
(81 lines is the expected post-Phase-4 volume: the ws-parser flood no longer reaches `docker logs`.)
Result: **PASS**

### Step 4: The observability stack came back
Command: `ssh Bot-1 'docker compose -f /home/sgame/bot-java/docker-compose.yml ps'`
Expected: every container `Up`.
Actual: **10/10 `Up`**, matching the pre-deploy baseline count exactly.

| Container | Status |
|---|---|
| bot-java-bot-manager-1 | Up (healthy) |
| bot-java-mongo-1 | Up (healthy) |
| bot-java-grafana-1 | Up |
| bot-java-prometheus-1 | Up |
| bot-java-loki-1 | Up |
| bot-java-promtail-1 | Up |
| bot-java-alertmanager-1 | Up |
| bot-java-node-exporter-1 | Up |
| bot-java-evidence-shim-1 | Up (healthy) |
| bot-java-viptalk-shim-1 | Up (healthy) |

`Up` alone is weak evidence for grafana/prometheus/loki/promtail/alertmanager, which carry no
healthcheck, so each was probed directly:

| Service | Probe | Result |
|---|---|---|
| grafana | `GET :3000/api/health` | **200** |
| prometheus | `GET :9090/-/healthy` | **200** |
| loki | `GET :3100/ready` | **`ready`** |
| alertmanager | `GET alertmanager:9093/-/healthy` and `/-/ready` | **200 / 200** |
| promtail | `GET promtail:9080/ready` | **200** |
| node-exporter | `GET node-exporter:9100/metrics` | **200** |
| bot-manager | `GET :8080/actuator/health` | **UP** (mongo UP, ping UP, disk UP — 70.6 GB free) |

Two probe artefacts worth recording so they are not misread as faults later:
- **Loki answered `Ingester not ready: waiting for 15s after being ready` on the first probe**
  and `ready` moments later. Normal ingester warm-up, not a failure.
- **alertmanager and promtail returned `000` from the host.** Not a fault: `docker ps` shows
  alertmanager publishes `9093/tcp` to the compose network only and promtail publishes no
  ports at all, so they are unreachable from `localhost` **by design**. Re-probed from inside
  the network via `docker exec bot-java-bot-manager-1 curl` — both **200**.

Prometheus scrape targets additionally confirmed, since that is the link that breaks silently:
`bot-manager` **up**, `node` **up**, no `lastError` on either.

Result: **PASS** — observability is fully back, verified at the HTTP layer, not just by container state.

## Out of scope — confirmed not done

No Game record created, no bot group created or started, no deposit, `w79probe1` untouched,
no WebSocket connection or probe attempted. The Tai Xiu plugin name is still pending from the user.

## Verdict

**PASS** — 4 of 4 verification steps passed; smoke passed; build green (2090 tests, 0 failures).

The build is deployed and running on Bot-1. P_119 is now resolvable at both of the points that
previously threw: `AuthStrategyFactory` has a dedicated arm, and `MessageTypesRegistry` reports
`119` under TAI_XIU. Note this proves **registration**, not **gameplay** — no P_119 group has
been started and no WS connection attempted, by design. The first real exercise of
`Win79LoginRequest` against the live gateway will be at group start, once the plugin name arrives.

## Logs

Not applicable — no failures. Post-start log was 81 lines with zero ERROR/Exception.

---

# Release 2 — WIN79_119_ENABLEMENT (BETTING_MINI provider)

Mode: bot
Branch: `feature/dead-group-auto-recovery` (working tree, uncommitted — intentional)
Image: `vingame-bot:latest` — `sha256:e853b44196cc…`, built 2026-09-10T13:32Z
Target: **Bot-1 (staging)**, `/home/sgame/bot-java`
Date: 2026-09-10T13:31:07Z → 2026-09-10T13:37Z (UTC)

## Payload — what is new since Release 1

Release 1 shipped the P_119 auth arm (`Win79LoginRequest`) and the Tai Xiu provider
(`Win79TaiXiuMessageTypes`). **Both are still present and unchanged** — confirmed in the
working tree and re-confirmed on the box by verification step 1 below. New in this build is a
complete **BETTING_MINI** provider for P_119.

| File | Change |
|---|---|
| `bot-messages/.../message/g4/win79/Win79GameMessageTypes.java` | new — `@MessageTypesImpl(gameType = BETTING_MINI, products = "119")` |
| `…/win79/Win79SubscribeMessage.java` | new — CMD 5000 |
| `…/win79/Win79StartGameMessage.java` | new — CMD 5005 |
| `…/win79/Win79UpdateBetMessage.java` | new — CMD 5002 |
| `…/win79/Win79EndGameMessage.java` | new — CMD 5006 |
| `…/win79/Win79BetInfo.java`, `…/win79/Win79PlayerResult.java` | new — payload types |
| `MessageTypesCoverageTest`, `MessageTypesRegistryTest` | inventories updated |
| `Win79GameMessageTypesTest` + 4 fixtures under `bot-messages/src/test/resources/messages/win79/` | new — 8 tests over real `gourdCrabPlugin` (Bau Cua) frames |

Built at **offset 2000**, **not md5**.

Also present in the working tree and **inert for the image**: `CLAUDE.md`, `Aviator.js`,
`bc.js`, deleted `TaiXiuMessages/*.js`, `docs/**`, and `deploy.sh` (never committed by design
and **not used by this deploy** — see Deploy).

**Nothing was committed, pushed or amended.** The dirty tree (27 entries) is the payload and
was pre-approved by the user; this is the documented exception to the clean-tree rule, and the
image was built from the working tree, not from `HEAD` (`c49bd42`).

## Build

- `mvn clean install`: **PASS** (55.3 s wall, 2026-09-10T13:31:07Z → 13:32:03Z; JDK 21.0.2)
  - All 6 reactor modules SUCCESS
  - **2098 tests, 0 failures, 0 errors** (counted from `target/surefire-reports/*.xml`):
    bot-api 138 · bot-strategies 126 · bot-messages **167** · bot-engine 431 · bot-app 1236
  - Matches the handover counts exactly. bot-messages is 167 vs Release 1's 159 — **+8**, which
    is precisely `Win79GameMessageTypesTest` (verified in its own surefire XML: `tests="8"
    failures="0" errors="0"`). The new tests genuinely ran; they were not silently skipped.
- `docker build --no-cache --platform linux/amd64`: **PASS** (~20 s) → `sha256:e853b44196cc…`
- `docker save -o bot.tar`: **PASS** — **409,370,624 bytes**, sha256 `97403c2bb3d6…6ad45d`

## Ship

- `sftp put bot.tar`: **PASS** (30 s)
- Integrity verified rather than assumed: remote `sha256sum` =
  `97403c2bb3d63544eb859a50ea9add68ca5ac91ec0124d36ff956eb5026ad45d`, **identical to local**,
  size 409,370,624 bytes on both ends.

## Pre-deploy baseline (captured before `compose down`)

10/10 containers `Up 2 hours`; bot-manager `(healthy)`. Registry line read:

```
BETTING_MINI 4 products [097, 098, 116, 118], TAI_XIU 3 products [114, 116, 119]
```

So verification step 1 is a genuine before/after on the BETTING_MINI side (4 → 5), and a
genuine **no-regression** check on the TAI_XIU side (3 → 3), not a tautology.

## Deploy

Single `ssh` block, exit code **0**. Standard pipeline — **`deploy.sh` was deliberately not
run**: it overwrites `.env` before re-merging `secrets.env`, which risks silently dropping the
VipTalk bot token. The box's `.env` is already correct and `docker compose up -d` does not
touch it.

- `docker compose down`: **PASS**
- `docker image rm vingame-bot:latest`: **PASS** — untagged the **Release 1** image
  `sha256:d33496525c1c…` (+12 layers deleted), so there was no chance of the old image being
  reused.
- `docker load -i bot.tar`: **PASS** — `Loaded image: vingame-bot:latest`
- `docker compose up -d`: **PASS** — all 10 containers recreated and started; mongo gated on its
  healthcheck before bot-manager started, as configured.
- **Image identity confirmed:** the running container's image is
  `sha256:e853b44196cc61f4dc8c0620a6f482e14deb74ab9355554228afe17e9742847b`, byte-identical to
  the locally built image ID. The container is running *this* build, not Release 1's.

## Smoke test

- `docker ps` shows healthy: **PASS** — `bot-java-bot-manager-1  Up About a minute (healthy)`
- Spring Boot ready log: **PASS** — `Started Starter in 8.511 seconds (process running for 9.612)`
- Auto-start log: **PASS** — `Bot Manager startup complete. 2 bot groups running`

## Plan verification

There is no plan file for this feature (`docs/plans/WIN79_119_ENABLEMENT.md` does not exist);
the four steps supplied in the release brief were used in place of a `## Verification` section.

### Step 1: Both 119 providers are registered
Command: `ssh Bot-1 'docker logs bot-java-bot-manager-1 2>&1 | grep "MessageTypesRegistry initialized"'`
Expected: `BETTING_MINI 5 products [097, 098, 116, 118, 119]` (the change) **and**
`TAI_XIU 3 products [114, 116, 119]` (unchanged — must not regress to 2).
Actual:
```
13:33:58.298 [main] INFO  MessageTypesRegistry [//] - MessageTypesRegistry initialized: BETTING_MINI 5 products [097, 098, 116, 118, 119], TAI_XIU 3 products [114, 116, 119], SLOT provider SlotMessageTypesImpl
```
Exactly one such line (one per JVM). BETTING_MINI went **4 → 5** with `119` appended in sorted
position. TAI_XIU is **character-for-character identical** to the pre-deploy baseline at
3 products — **no regression**; the restored Tai Xiu provider survived this deploy.
Result: **PASS**

### Step 2: The 119 environment survived
Command: `ssh Bot-1 'docker exec bot-java-mongo-1 mongosh botmanager --quiet --eval "JSON.stringify(db.environments.findOne({productCode:\"P_119\"}))"'`
Expected: `_id` `d005157f-4dfb-479d-a39a-36a3892439b7`, `appId` `w79.club`.
Actual: present and unchanged — `_id` `d005157f-4dfb-479d-a39a-36a3892439b7`, `appId`
`w79.club`, `name` `119 Staging`, `type` `STAGING`, `brandCode` `G4`,
`hostUrl` `https://w79.stgame.win`, `apiGatewayUrl` `https://apigw-w79.sgame.us`,
`webSocketMiniUrl` `wss://w79.sgame.club/websocket_mini`, `binaryFrame` false,
`useJwtAuth` false. Identical to the Release 1 record.
Result: **PASS**

### Step 3: No new ERROR at startup
Command: `ssh Bot-1 'docker logs bot-java-bot-manager-1 2>&1 | grep -E "ERROR|Exception" | head -30'`
Expected: report whatever is there.
Actual: **zero matches.** `grep -c` returns `0` across all **82** log lines emitted since start.
No ERROR, no exception, no stack trace — not even pre-existing noise. (82 lines is the expected
post-Phase-4 volume: the ws-parser flood no longer reaches `docker logs`.)
Result: **PASS**

### Step 4: Observability fully back
Command: `ssh Bot-1 'cd /home/sgame/bot-java && docker compose ps'` plus a direct HTTP probe of
every service.
Expected: every container `Up`; each probed at the HTTP layer, since none of the observability
containers carry a healthcheck and `Up` is therefore weak evidence.
Actual: **10/10 `Up`**, matching the pre-deploy baseline count exactly.

| Container | Status | Probe | Result |
|---|---|---|---|
| bot-java-bot-manager-1 | Up (healthy) | `GET :8080/actuator/health` | **UP** (mongo UP, ping UP, ssl UP, disk 70.6 GB free) |
| bot-java-mongo-1 | Up (healthy) | (compose healthcheck) | healthy |
| bot-java-grafana-1 | Up | `GET :3000/api/health` | **200** |
| bot-java-prometheus-1 | Up | `GET :9090/-/healthy` | **200** |
| bot-java-loki-1 | Up | `GET :3100/ready` | **`ready`** |
| bot-java-promtail-1 | Up | `GET promtail:9080/ready` (in-network) | **200** |
| bot-java-alertmanager-1 | Up | `GET alertmanager:9093/-/healthy` + `/-/ready` (in-network) | **200 / 200** |
| bot-java-node-exporter-1 | Up | `GET node-exporter:9100/metrics` (in-network) | **200** |
| bot-java-evidence-shim-1 | Up (healthy) | (compose healthcheck) | healthy |
| bot-java-viptalk-shim-1 | Up (healthy) | (compose healthcheck) | healthy |

Two probe artefacts recorded so they are not misread as faults later — **both reproduce
Release 1 exactly**:
- **Loki answered `Ingester not ready: waiting for 15s after being ready`** on the first two
  probes and `ready` on the third. Normal ingester warm-up, not a failure.
- **alertmanager and promtail return `000` from the host.** By design — alertmanager publishes
  `9093/tcp` to the compose network only and promtail publishes no ports at all. Re-probed from
  inside the network via `docker exec bot-java-bot-manager-1 curl` — both **200**.

Two links that break silently were checked beyond liveness:
- **Prometheus scrape targets:** `bot-manager` **up**, `node` **up**, `lastError` empty on both.
- **promtail → Loki ingest:** `GET /loki/api/v1/labels` returns the full MDC label set
  (`botGroupId`, `environmentId`, `gameType`, `level`, `job`, `service_name`, `filename`), and
  `count_over_time({job=~".+"}[5m])` returns live post-deploy streams from
  `/logs/console.log`. Logs are flowing end to end, not merely being written.

Result: **PASS** — observability is fully back, verified at the HTTP layer and through to
actual ingest, not just by container state.

## Out of scope — confirmed not done

No Game record created, no bot group created or started, no deposit, `w79probe1` untouched, no
WebSocket connection opened or probed. Nothing committed, pushed or amended; `deploy.sh` was
neither run nor committed.

## Verdict

**PASS** — 4 of 4 verification steps passed; smoke passed; build green (2098 tests, 0 failures).

P_119 is now resolvable at **three** points that previously threw: `AuthStrategyFactory` has a
dedicated arm (Release 1), and `MessageTypesRegistry` reports `119` under **both** `TAI_XIU`
(Release 1, confirmed not regressed) and `BETTING_MINI` (this release). The box is ready for the
119 probe group.

Note the same limit as Release 1, and it still binds: this proves **registration**, not
**gameplay**. The Win79 BETTING_MINI codecs have been exercised only against recorded
`gourdCrabPlugin` fixtures in `Win79GameMessageTypesTest`; no P_119 frame has been parsed off a
live socket. The first real exercise is at group start.

## Logs

Not applicable — no failures. Post-start log was 82 lines with zero ERROR/Exception.

---

# Probe — 119 Xoc Dia (shakeDiskPlugin, offset 0)

Mode: **operational probe, not a release** — no `mvn`, no `docker build`/`save`/`sftp`, no
`docker compose down/up`, no container restart. The already-running image on Bot-1
(`sha256:e853b44196cc…`, built 2026-09-10T13:32Z) was used as-is. Nothing was committed,
pushed or amended.

Branch: `feature/dead-group-auto-recovery` (working tree, unrelated to this probe)
Target: **Bot-1 (staging)**, `/home/sgame/bot-java`
Date: 2026-09-11T10:03:17Z (Game created) → 2026-09-11T10:12:35Z (final health check, T+8m36s)

## Step 0 — proved no deploy needed

```
ssh Bot-1 'docker logs bot-java-bot-manager-1 2>&1 | grep "MessageTypesRegistry initialized"'
```
```
13:33:58.298 [main] INFO MessageTypesRegistry [//] - MessageTypesRegistry initialized:
BETTING_MINI 5 products [097, 098, 116, 118, 119], TAI_XIU 3 products [114, 116, 119],
SLOT provider SlotMessageTypesImpl
```
`119` present in `BETTING_MINI`. Container: `bot-java-bot-manager-1  Up 20 hours (healthy)`,
`IMAGE vingame-bot:latest`. **PASS** — proceeded without touching the build/deploy pipeline.

## Step 1 — Game created

`POST /api/v1/game/G4/P_119/d005157f-4dfb-479d-a39a-36a3892439b7`

Request body used exactly the load-bearing values from the brief: `md5:false`, `offset:0`,
`numberOfOptions:7`.

Response (200):
```json
{"id":"43de294d-ef0a-41c0-b3c6-da780e22c391","brandCode":"G4",
 "productCode":{"code":"119","name":"WIN79",...},
 "environmentId":"d005157f-4dfb-479d-a39a-36a3892439b7",
 "name":"Xoc Dia","gameType":"BETTING_MINI","pluginName":"shakeDiskPlugin",
 "offset":0,"md5":false,
 "optionAffinities":{"0":1,"1":1,"2":1,"3":1,"4":1,"5":1,"6":1}}
```
`optionAffinities` expanded to exactly `{0..6}` — 7 entries, as expected. Game id
**`43de294d-ef0a-41c0-b3c6-da780e22c391`**.

## Step 2 — probe group created

**Deviation found and worked around:** `POST /api/v1/bot-group` (no trailing slash, as the
CLAUDE.md endpoint table has it) returned `404 {"type":"Bad request","msg":"No static
resource api/v1/bot-group."}` — Spring resolved it as a static-resource lookup, not the
controller. `POST /api/v1/bot-group/` (trailing slash) resolved correctly, returning `400`
against an empty body (`environmentId must not be blank; ...`) and then `200` against the
full payload. This is a documentation gap in `CLAUDE.md`'s REST table, not a code defect for
this probe's purposes — noting it here rather than editing that file, since this task's
deliverable is the release log only.

`POST /api/v1/bot-group/` with the brief's payload (mirroring the Bau Cua probe group,
`gameId` pointed at the new Xoc Dia game) → **200**, group id
**`c93c82a5-0f1a-465c-8a8c-d878277834c0`**, `strategyMix [{"strategyId":"RANDOM","weight":1.0}]`.

## Step 3 — started

`POST /api/v1/bot-group/c93c82a5-0f1a-465c-8a8c-d878277834c0/start` → **200** at
`2026-09-11T10:03:59Z`. 20 s later, all **10/10** users had registered, authenticated and
connected (`GroupLifecycleAggregator`: `group c93c82a5... (119 Xoc Dia probe): 10/10 bots
initialized, game=Xoc Dia, type=BETTING_MINI, strategy=RANDOM`; `10 bots auto-deposited,
total 1000000000`). Ran for **8m36s** before final judgement (well over the required 5 min).

## Step 4 — verification

### 1. Health

Final `GET /api/v1/bot-group/c93c82a5-0f1a-465c-8a8c-d878277834c0/health` at T+8m36s:

- `connectedBots: 10`, `reconnectingBots: 0`, `deadBots: 0`, `disconnectedBots: 0`
- All 10 bots `status: "CONNECTION_AUTHENTICATED"`, `connected: true`
- `stats.roundsSinceRestart: 20`, `activeTimeSeconds: 516`
- Bet counts: every bot at `totalBetsPlaced: 40` (2 bets/round × 20 rounds, matching
  `maxBetsPerRound: 2`), `totalBetAmount` 101,000–135,000 range per bot, `lastRoundWinnings`
  0–17,820 (local, see Settlement below)

**Result: PASS** — clean, fully connected, no dead/reconnecting bots the entire run.

### 2. The subscribe frame — **not obtainable at the current staging log config; reported honestly**

The brief's instruction assumed raw ws-parser frame dumps (`"cmd":3000` etc.) would be present
in `logs/detail/detail.log` under `BOT_LOG_LEVEL=DEBUG`. Checked directly:

```
ssh Bot-1 'curl -s localhost:8080/actuator/loggers/com.vingame.bot'
→ {"configuredLevel":"DEBUG","effectiveLevel":"DEBUG"}
ssh Bot-1 'curl -s localhost:8080/actuator/loggers/com.vingame.websocketparser'
→ {"configuredLevel":"INFO","effectiveLevel":"INFO"}
ssh Bot-1 'docker exec bot-java-bot-manager-1 env | grep -E "BOT_LOG_LEVEL|WSPARSER_LOG_LEVEL"'
→ LOGGING_LEVEL_COM_VINGAME_WEBSOCKETPARSER=INFO
→ LOGGING_LEVEL_COM_VINGAME_BOT=DEBUG
```

Per this repo's own `CLAUDE.md` Logging Guidelines, raw WS frame dumps are **TRACE**, gated
under `com.vingame.bot`'s `OutputPrinter`/`debugOutputPrinter` — not reached by `DEBUG`. And
`com.vingame.websocketparser` (the library that logs the `AUTH [...]` frame at INFO) is pinned
to `INFO` on this box via `WSPARSER_LOG_LEVEL` and does **not** dump inbound game-message JSON
at that level — confirmed by exhaustive grep:

```
grep -c "\"cmd\":" logs/detail/detail.log        → 0   (whole file, all groups)
grep -c "OutputPrinter" logs/detail/detail.log   → 0
```

So **no literal `"cmd":3000` frame or `bs` array exists in any reachable log on this box for
this probe** — this is a genuine gap between the brief's assumption and the deployed logging
configuration, not a probe failure. It was **not** worked around by escalating to TRACE
(global `/actuator/loggers` TRACE is exactly the flood this repo's log-volume work exists to
avoid, and scoped per-group DEBUG explicitly "cannot reach TRACE" per `CLAUDE.md`) or by
restarting the container with a different `WSPARSER_LOG_LEVEL` (forbidden — no restarts).

**Strongest available substitute evidence**, all confirming 7 live, server-accepted options
(0–6), gathered from `SessionAggregationService`'s per-round `UpdateBet` aggregate lines
(`grep -h "\[c93c82a5" logs/detail/detail.log | grep "UpdateBet #1 "`):

```
options: [0]x1 [1]x2 [2]x3 [3]x4 [4]x5 [5]x2 [6]x3 | amount min/avg/max: ...
options: [0]x3 [1]x1 [2]x7 [3]x1 [4]x3 [5]x3 [6]x2 | amount min/avg/max: ...
options: [0]x4 [1]x3 [2]x4 [3]x1 [4]x4 [5]x4 | amount min/avg/max: ...
```
Option ids **0 through 6** appear across the 20-round sample (never 7+), and — critically —
**every one of those bets was accepted by the server**: `totalBetsPlaced` on every bot
advanced in lock-step with placed bets and no bet-rejection WARN/ERROR appears anywhere in the
group's log window (see Errors below). If the live `eid` range were actually 0–5 (the 116
under-configuration the brief warned about), a bet on eid 6 would have been rejected upstream
and would show up as a WARN or a stuck bet count — neither occurred. This is indirect, not the
literal `bs` array the brief asked for, and is reported as such.

**Result: could not produce the literal artifact requested; substitute evidence supports 0–6
(7 entries), consistent with the `numberOfOptions: 7` configuration.**

### 3. Round flow

`"cmd":3005`/`"cmd":3006` are likewise not present as raw JSON (see above), but the typed,
deserialized equivalents are logged at DEBUG by `SessionAggregationService` and `BotMemory`,
naming the exact message classes — proving `Win79GameMessageTypes` correctly resolved both
CMDs for every one of 20 consecutive rounds with **zero** `Could not resolve type id` errors:

StartGame (`"cmd":3005`, CODE 3005 = 3005+0):
```
10:04:13.809 DEBUG SessionAggregationService [c93c82a5.../6/BETTING_MINI] -
  BotGroup Xoc Dia/c93c82a5-0f1a-465c-8a8c-d878277834c0 entered session 238580 |
  sample: com.vingame.bot.domain.bot.message.g4.win79.Win79StartGameMessage@4e705d6e
```

EndGame (`"cmd":3006`, CODE 3006 = 3006+0):
```
10:04:33.844 DEBUG SessionAggregationService [c93c82a5.../7/BETTING_MINI] -
  BotGroup Xoc Dia/c93c82a5-0f1a-465c-8a8c-d878277834c0 session 238580 ended |
  total staked: 47000 | total win: 0 | bettors: 10 | confirmed staked: 0 |
  sample: com.vingame.bot.domain.bot.message.g4.win79.Win79EndGameMessage@6ea18728
```

Outbound bets (`"cmd":3002`) — again not visible as raw JSON, but the per-round aggregate
proves they went out and were accepted:
```
10:11:38.571 DEBUG SessionAggregationService [c93c82a5.../7/BETTING_MINI] -
  UpdateBet #1 | new bettors since last: 10 | total bettors this round: 10 |
  total staked: 31000 | options: [1]x1 [2]x1 [3]x2 [4]x1 [5]x1 [6]x4 |
  amount min/avg/max: 1000/3100/5000
```
20 distinct `session <sid> entered` / `session <sid> ended` pairs observed
(sids 238580–238599 range), `roundsSinceRestart: 20` in the final health call agrees exactly.

**Result: PASS** — full round cadence (StartGame → bets → EndGame) running cleanly for the
entire 8m36s window, offset-0 CMD derivation (3000/3002/3005/3006) working as designed.

### 4. Settlement

From the EndGame `session ... ended` lines (sample above and others across the run):
`total staked` 31,000–60,000/round, `total win` mostly **0**, occasionally non-zero
(e.g. `total win: 9900` for session 238581) — but **`confirmed staked: 0` on every single
round observed, no exception**. Per-bot `BotMemory.completeRound` lines show non-zero
`payout` values in some cases (e.g. `payout=79200, staked=9000, delta=70200`), which is the
bot's own **locally simulated** outcome bookkeeping (used to update `lastFetchedBalance`
estimates), independent of `confirmed staked`.

**This is the already-diagnosed condition documented in this repo's `CLAUDE.md`** ("Bets
Never Settle — Host IP Not Whitelisted With Back Office"): `confirmed staked` reads 0 on
every EndGame while local bet/round bookkeeping proceeds normally. Flagged per instructions,
**not** investigated further — this is a per-brand whitelisting fact, not a Xoc Dia or
offset-0 defect, and P_119 whitelisting status is already tracked separately
(`project_win79_119_bringup` memory: "Open: confirmed staked=0" — this probe reproduces
exactly that, now also on Xoc Dia in addition to Bau Cua).

**Result: reported as-is. `confirmed staked: 0` fleet-wide-consistent, known cause, no action
taken.**

### 5. Errors

```
ssh Bot-1 'grep -hE "ERROR|Exception" logs/detail/detail.log logs/detail/detail-2026-09-11-09.log \
  | grep -i "shakeDiskPlugin\|NamedType\|resolve type id\|win79\|Xoc Dia\|c93c82a5"'
→ (zero matches)

ssh Bot-1 'docker logs bot-java-bot-manager-1 --since 2026-09-11T10:03:00Z 2>&1 | grep -E "ERROR|Exception"'
→ (zero matches; only two pre-existing WARN lines from this probe's own trailing-slash
   discovery in Step 2 — 404/400 on my own malformed requests, nothing group- or
   plugin-related)
```

**Result: PASS** — no ERROR, no Exception, no `NamedType`/`shakeDiskPlugin`/"Could not
resolve type id" anywhere in the group's log window across either log track.

## Group left running

**Healthy** (10/10 connected, 0 dead, 0 reconnecting, 20 clean rounds) — left **running** per
instructions, not stopped.

## Verdict

**PASS**, with one caveat: `md5:false`/`offset:0`/`numberOfOptions:7` all confirmed correct
(no NPE, no type-resolution failure, 20 clean rounds, 7 accepted option ids). The one thing
this probe could **not** produce is the literal requested artifact — the raw `bs` array from
the `"cmd":3000` frame — because that requires TRACE-level `com.vingame.bot` logging or a
`WSPARSER_LOG_LEVEL` above `INFO`, neither of which is present on this box and neither of
which this task was authorized to change. The substitute evidence (all 20 rounds' worth of
accepted bets spanning eids 0–6, zero rejections, zero type-resolution errors) is strong but
indirect. `confirmed staked: 0` is the known whitelisting condition, not a new finding.

No build, no image, no container restart were part of this probe. Nothing committed or
pushed.

---

# Probe — 119 Tai Xiu (taixiuPlugin)

Mode: **operational probe, not a release** — no `mvn`, no `docker build`/`save`/`sftp`, no
`docker compose down/up`, no container restart, no commit. Same running image as the Xoc Dia
probe above (`sha256:e853b44196cc…`).

Branch: `feature/dead-group-auto-recovery` (working tree, unrelated to this probe)
Target: **Bot-1 (staging)**, `/home/sgame/bot-java`
Date: 2026-09-11T10:15:59Z (Game created) → 2026-09-11T10:25:19Z (final health check, T+9m06s)

## Step 0 — reconfirmed

```
ssh Bot-1 'docker logs bot-java-bot-manager-1 2>&1 | grep "MessageTypesRegistry initialized" | tail -1'
→ 13:57:43.600 [main] INFO MessageTypesRegistry [//] - MessageTypesRegistry initialized:
  BETTING_MINI 5 products [097, 098, 116, 118, 119], TAI_XIU 3 products [114, 116, 119], ...
```
`119` still present under `TAI_XIU`. The Xoc Dia probe group
(`c93c82a5-0f1a-465c-8a8c-d878277834c0`) was re-checked and confirmed still `10/10 connected,
0 dead` before starting this second probe, and was **left running** throughout and after.

## Step 1 — Game created

`POST /api/v1/game/G4/P_119/d005157f-4dfb-479d-a39a-36a3892439b7` with exactly the four
load-bearing deviations from the brief: no `offset` field, `optionAffinities` set explicitly
to `{"1":1,"2":1}` (not `numberOfOptions`), `md5:false`.

Response (200):
```json
{"id":"ac557f61-bebc-49e9-ad9b-620ece52ba00","brandCode":"G4",
 "productCode":{"code":"119","name":"WIN79",...},
 "name":"Tai Xiu","gameType":"TAI_XIU","pluginName":"taixiuPlugin","md5":false,
 "optionAffinities":{"1":1,"2":1}}
```
No `offset` key present in the response at all — confirmed the record does not lie about a
field Tai Xiu never reads. Game id **`ac557f61-bebc-49e9-ad9b-620ece52ba00`**.

## Step 2 — probe group created

`POST /api/v1/bot-group/` (trailing slash, per the fix found in the Xoc Dia probe) → **200**,
group id **`57b99074-9304-4230-844e-eaa0db06c3f6`**, `strategyMix
[{"strategyId":"RANDOM","weight":1.0}]`.

## Step 3 — started

`POST /api/v1/bot-group/57b99074-9304-4230-844e-eaa0db06c3f6/start` → **200** at
`2026-09-11T10:16:13Z`. All **10/10** users registered, authenticated and connected by T+25s
(`connectedBots: 10`, all `CONNECTION_AUTHENTICATED`). Ran **9m06s** before final judgement
(exceeds the requested 6 min).

## Step 4 — which world are we in

**World 1 — it works.** Not world 2 (rounds do arrive), not world 3/4 in the "nothing
acknowledged" sense (bets are placed, staked amounts are non-zero every round, and — see
Settlement below — real server-confirmed balance growth was observed, which rules out "eid
1/2 wrong and nothing accepted").

### Health (final, T+9m06s)

```
connectedBots: 10, reconnectingBots: 0, deadBots: 0, disconnectedBots: 0
stats: roundsSinceRestart: 18, activeTimeSeconds: 546, activeBots: 10
```
Every bot `status: "CONNECTION_AUTHENTICATED"`, `totalBetsPlaced: 35` (uniform across all 10 —
2 bets/round × up to `maxBetsPerRound`, clamped by round timing), `totalBetAmount` 96,000–
115,000 per bot. Three bots (`w79tx1`, `w79tx2`, `w79tx10`) show `balance` **> 1,000,000,000**
against a 1,000,000,000 deposit — see Settlement.

### `BotMemory.completeRound` — real staking and real payout

```
BotMemory.completeRound: sessionId=46974, payout=19800, staked=5000, delta=14800, lastResults.size=4
BotMemory.completeRound: sessionId=46974, payout=7920,  staked=4000, delta=3920,  lastResults.size=4
BotMemory.completeRound: sessionId=46980, payout=0,     staked=6000, delta=-6000, lastResults.size=...
```
Non-zero `staked` and a mix of zero/non-zero `payout` on every round, consistent with a
genuine win/lose outcome per bet rather than a stuck or ignored bet stream.

**Result: PASS** — Tai Xiu is playing cleanly end to end on 119; `taixiuPlugin`'s CMD block
(subscribe 1005, startGame 1002, endGame 1004, bet 1000) is correctly wired for this game.

## An unexpected finding: two interleaved session-id series

`entered session` / `session ... ended` lines show **two concurrently running, independently
incrementing sessionId sequences** for the same group, interleaved in time:

```
10:16:59 entered session 46973                       (46xxx series)
10:17:03 entered session 296884                       (296xxx series)
10:17:39 session 296884 ended | staked 64000
10:17:51 entered session 46974
10:17:53 session 46974 ended  | staked 56000 | confirmed staked: 6000
10:18:09 entered session 296885
10:18:31 session 296885 ended | staked 64000
10:18:43 entered session 46975
10:19:00 session 46975 ended  | staked 60000 | total win: 1980 | confirmed staked: 1000
...
```
Both series advance by exactly 1 each time they recur (46973→46974→...→46981; 296884→...→
296890) and both run the same ~20-40s round cadence, but interleaved rather than sequential.
Reported as observed, not investigated further — plausibly two concurrent Tai Xiu
tables/instances broadcasting onto the same subscription channel (Tai Xiu commonly runs
multiple parallel rooms), but this probe did not confirm a mechanism.

**Side effect on `BotMemory`:** since a bot's `BotMemory` tracks one "in-flight" round at a
time, a bot that entered one series' StartGame and then receives the *other* series' EndGame
sees a WARN and discards the mismatched EndGame:
```
BotMemory.completeRound: sessionId mismatch (EndGame sessionId=46976, in-flight sessionId=0)
— in-flight round discarded
```
**50 such WARNs** occurred across the 9-minute window, at 4 distinct timestamps (not
constant/every round, and not clustered only at start-up). This is a **benign local
bookkeeping loss** — it discards that bot's own `payout`/`staked` tally for the missed round —
and caused **no** reconnects, **no** dead bots, and **no** ERROR/Exception. Flagged for
awareness, not treated as a defect of this probe's configuration.

## Settlement — genuinely different from Xoc Dia

Same per-round summary field as before (`SessionAggregationService`): `confirmed staked` is
**mostly 0**, matching the known IP-whitelisting condition —

```
session 296884 ended | total staked: 64000 | total win: 0 | confirmed staked: 0
session 296885 ended | total staked: 64000 | total win: 0 | confirmed staked: 0
session 296888 ended | total staked: 48000 | total win: 0 | confirmed staked: 0
```

— **but not always.** Several rounds show non-zero `confirmed staked` and non-zero `total win`:

```
session 46974 ended | total staked: 56000 | total win: 0     | confirmed staked: 6000
session 46975 ended | total staked: 60000 | total win: 1980  | confirmed staked: 1000
session 46976 ended | total staked: 71000 | total win: 0     | confirmed staked: 6000
session 46979 ended | total staked: 59000 | total win: 1980  | confirmed staked: 1000
session 46980 ended | total staked: 63000 | total win: 31680 | confirmed staked: 16000
```

**Stronger evidence than the per-round field:** `checkBalance()` in `Bot.java` only performs a
real server round-trip when local drift exceeds 1% of deposit (threshold 10,000,000 here). At
`10:20:06` three independent bots crossed that threshold and their **server-fetched** (not
locally simulated) balances came back **above the 1,000,000,000 deposit**:

```
checkBalance() ENTRY. lastFetched: 1000000000, expected: 1020001139, delta: 20001139, threshold: 10000000
checkBalance() fetching from server (delta > 10000000)
checkBalance() fetched: 1020018139                              (w79tx1: +20,018,139)
checkBalance() fetched: 1016673289                              (w79tx2: +16,673,289)
checkBalance() fetched: 1013339219                              (w79tx10: +13,339,219)
```
This is the game server itself reporting a higher balance than what was deposited — not a
locally-computed `payout` figure. It directly demonstrates **real, server-side settlement
occurring** for at least some Tai Xiu play on P_119, which is a materially different (better)
result than the Xoc Dia probe, where `confirmed staked` was 0 on every single round with no
comparable server-confirmed balance growth observed.

**`confirmed staked: 0` here should not be read as "nothing settled"** — exactly the caveat
the brief raised. It coexists with real, server-verified settlement on this group; it appears
to reflect only a portion of the round's `total staked`, not an all-or-nothing state.

## `gi` array / raw subscribe response — not obtainable, and not needed here

As with the Xoc Dia probe, no raw `"cmd":1005` frame or `gi`/`tU`/`tB` JSON appears anywhere
in `logs/detail/detail.log`: `com.vingame.websocketparser` is pinned to `INFO` on this box and
does not dump inbound game-message payloads at that level, and `com.vingame.bot`'s own raw
frame dumps are TRACE-only (staging runs `DEBUG`). This was checked and is the same
box-wide limitation as before — not re-litigated in depth here because it doesn't matter for
this probe's verdict: we are unambiguously in world 1, not world 4, so the wire-level
discriminator the amendment asked for was not needed to resolve the reading. It would still be
useful to have for a future probe that *does* land in world 4.

## Errors

```
ssh Bot-1 'grep -hE "ERROR|Exception" logs/detail/detail.log \
  | grep -i "57b99074\|taixiuPlugin\|TaiXiuMessageTypes\|resolve type id"'
→ (zero matches)

ssh Bot-1 'docker logs bot-java-bot-manager-1 --since 2026-09-11T10:16:00Z 2>&1 | grep -E "ERROR|Exception"'
→ (zero matches)
```
**Result: PASS** — no ERROR, no Exception, no `NamedType`/`taixiuPlugin`/"Could not resolve
type id", no null-type NPE anywhere in the group's log window.

## Disposition

**Healthy** (10/10 connected, 0 dead, 0 reconnecting, 18 clean rounds, real server-confirmed
settlement on 3 of 10 accounts) — left **running**, not stopped. The Xoc Dia probe group
(`c93c82a5-0f1a-465c-8a8c-d878277834c0`) remains running as well, reconfirmed healthy
immediately before this probe started.

## Verdict

**PASS — World 1.** `taixiuPlugin`'s plain CMD block (1000/1002/1004/1005, no offset, no
auto-bet flag) is correctly wired for P_119: 18 clean rounds, real bets, real per-round
outcomes, and — uniquely among the two 119 probes run so far — **genuine server-confirmed
settlement**, not just the known-zero `confirmed staked` condition. Two things are flagged for
awareness rather than fixed: the interleaved dual sessionId series (benign, causes discarded
local bookkeeping only) and the still-unexplained partial nature of `confirmed staked` (0 on
most rounds, non-zero and matched by real balance growth on others).

No build, no image and no container restart were part of this probe. Nothing committed or
pushed.
