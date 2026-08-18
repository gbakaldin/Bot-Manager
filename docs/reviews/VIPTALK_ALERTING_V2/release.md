# Release — VIPTALK_ALERTING_V2

Mode: bot (+ hand-shipped infra config; no `infra-images.tar.gz`)
Target: **Bot-1 / STAGING** (`s009-bot-general-stag-01`, user `sgame`). Prod-Bot NOT touched.
Branch: `staging`
HEAD: `1ebe438`
Image built: `vingame-bot:latest` @ 2026-08-18T11:52Z (sha256:c0f3868a36a0)
Date: 2026-08-18T12:10Z

## Verdict up front

**FAIL** — bot-manager could not start on the new image (Spring context failure,
`VipTalkClient` has no usable constructor). Rolled back to the previously-running
image; staging is serving normally. The *infrastructure* half of the release
(Alertmanager, node-exporter, viptalk-shim, 16 rules) is deployed and verified.
The *application* half is not deployed.

Two blocking defects found, both requiring a code change:

1. **`VipTalkClient` has two constructors and neither is `@Autowired`** → Spring
   falls back to the non-existent no-arg constructor → context fails → crash loop.
2. **The shim cannot deliver to VipTalk**: Cloudflare rejects `Python-urllib/3.12`
   with Error 1010. Proven by controlled User-Agent experiment.

## Pre-deploy recon

| Item | Result |
|---|---|
| Working tree clean | PASS — only ` M deploy.sh` (the deliberate, uncommitted merge-block divergence) |
| Disk headroom | PASS — 100G total, 68G avail (33% used) before deploy |
| Registry egress | PASS — `registry-1.docker.io` → 401 (expected unauth challenge) |
| VipTalk egress | PASS — `api.viptalk.org` reachable |
| Remote compose baseline | **Had no `alertmanager` service at all** — so this is the first deploy of the entire alerting pipeline, not an update. Three new containers, not two. |
| Rollback image | Existing `vingame-bot:rollback-staging` was Aug 5 — stale by 8 days. Re-tagged the running Aug 10 image as `vingame-bot:rollback-20260818` before removal. |

## Build

- `mvn clean install`: **PASS** (35.6 s, 975 tests, 0 failures/errors)
- `docker build --no-cache --platform linux/amd64`: **PASS** (sha256:c0f3868a36a0)
- `docker save -o bot.tar`: **PASS** (417,278,464 bytes)

## Ship

All sizes verified identical on the host after transfer.

| File | Result |
|---|---|
| `bot.tar` | PASS (417,278,464 B, matches local exactly) |
| `docker-compose.yml` | PASS (12,433 B) |
| `deploy.sh` (**working-tree copy, with merge block**) | PASS (1,440 B; `grep "Merging secrets.env into .env"` = 1) |
| `prometheus/prometheus.yml` | PASS (2,270 B) |
| `prometheus/alerts.yml` | PASS (27,743 B) |
| `alertmanager/alertmanager.yml` | PASS (7,364 B) |
| `viptalk-shim/shim.py` | PASS (19,405 B) |
| `viptalk-shim/selftest.py` | PASS (27,570 B) |
| `secrets.env.example` | PASS (4,308 B) |
| `secrets.env` (created on host) | PASS — 252 B, mode `0600`, 6 expected keys, values never echoed |

Remote `docker-compose.yml` and `deploy.sh` backed up to `.bak.<timestamp>` first.

## Pre-outage validation (run with one-off containers, zero downtime)

| Check | Result |
|---|---|
| Pre-pull `prom/node-exporter:v1.8.2` | PASS (23.3 MB) |
| Pre-pull `python:3.12-alpine` | PASS (48.9 MB) |
| Pre-pull `prom/alertmanager:v0.27.0` | PASS (70.3 MB) |
| `promtool check rules alerts.yml` | PASS — **16 rules found** |
| `promtool check config prometheus.yml` | PASS — valid, 1 rule file |
| `amtool check-config` | PASS — **3 inhibit rules, 2 receivers**, 0 templates |
| `docker compose config --services` | PASS — 9 services |
| `python -m py_compile shim.py` under `python:3.12-alpine` | PASS |

## Deploy

- `docker tag vingame-bot:latest vingame-bot:rollback-20260818`: PASS
- `docker compose down` (no `-v`; named volumes preserved): PASS
- `docker image rm vingame-bot:latest`: PASS
- `docker load -i bot.tar`: PASS
- `./deploy.sh`: PASS — printed **`Merging secrets.env into .env`**, all 9 containers created and started

## Smoke test

| Check | Result |
|---|---|
| All 9 containers created | PASS |
| `viptalk-shim` came up (bind-mount path correct) | **PASS** — healthy, no missing-directory failure |
| `node-exporter` came up | PASS |
| `alertmanager` came up | PASS |
| **bot-manager healthy on new image** | **FAIL — crash loop** |

### bot-manager failure

```
BeanInstantiationException: Failed to instantiate
  [com.vingame.bot.infrastructure.notification.VipTalkClient]: No default constructor found
Caused by: java.lang.NoSuchMethodException:
  com.vingame.bot.infrastructure.notification.VipTalkClient.<init>()
```

**Root cause.** `bot-app/src/main/java/com/vingame/bot/infrastructure/notification/VipTalkClient.java`
declares two constructors:

- line 54 — `public VipTalkClient(...)` with five `@Value` parameters (the Spring one)
- line 96 — `VipTalkClient(boolean, String, String, HttpPoster)`, package-private test seam

Spring only auto-selects a constructor when there is **exactly one**. With two
candidates and no `@Autowired` on either, it falls back to the no-arg constructor,
which does not exist.

**Why the build did not catch it.** The 975 unit tests construct `VipTalkClient`
directly through the package-private seam; nothing boots the full Spring context,
so the wiring is never exercised until runtime. A `@SpringBootTest` context-load
smoke test would have caught this at build time.

**Fix (one line, for the developer — NOT applied by this release):** annotate the
public constructor at line 54 with `@Autowired`.

### Rollback

- `docker tag vingame-bot:rollback-20260818 vingame-bot:latest` + `compose up -d --force-recreate bot-manager`: **PASS**
- bot-manager healthy after ~40 s; `Started Starter in 7.832 seconds`
- `actuator/health` → 200, `{"status":"UP"}`, Mongo UP
- Existing bot fleet **unaffected**: 154 bots — 134 `CONNECTION_AUTHENTICATED`, 20 `STARTED`, **0 DEAD**
- Grafana → 200, Prometheus → 200, Loki `/ready` → 200 (503 immediately after start, ready shortly after)

## Plan verification

Numbering follows the coordinator's brief; plan §Verification phases noted.

### 1. Config validity (plan Phase 3 / Phase 6)
Command: `docker compose exec prometheus promtool check rules /etc/prometheus/alerts.yml`; `docker compose exec alertmanager amtool check-config`
Expected: 16 rules OK; valid Alertmanager config
Actual: `SUCCESS: 16 rules found`; `SUCCESS — 3 inhibit rules, 2 receivers`
Result: **PASS** (verified both pre-outage in one-off containers and inside the running containers)

### 2. node-exporter sees the real host filesystem — **plan Phase 0 Q2, now CLOSED**
Command: `node_filesystem_size_bytes{mountpoint="/"}` from node-exporter vs `df -B1 /` and `free -b`
Expected: agreement with the host to within a percent

Actual — **exact, byte-for-byte match**:

| Series | node-exporter | host | Match |
|---|---|---|---|
| `node_filesystem_size_bytes{mountpoint="/"}` | 107,362,627,584 | `df -B1 /` = 107,362,627,584 | exact |
| `node_filesystem_avail_bytes{mountpoint="/"}` | 70,283,710,464 | `df -B1 /` avail = 70,283,710,464 | exact |
| `node_memory_MemTotal_bytes` | 16,169,422,848 | `free -b` = 16,169,422,848 | exact |

Device/fstype resolve correctly as `device="/dev/nvme0n1p1", fstype="xfs", mountpoint="/"` —
the `--path.rootfs=/host` prefix is stripped as designed, and `pid: host` gives the
host mount table. RAM is host RAM (~16 GB), not a container limit.

Result: **PASS**. `--path.rootfs` and `pid: host` are correct. **All five host rules
(`HostDiskSpaceLow`, `HostDiskSpaceCritical`, `HostMemoryLow`, `HostCpuHigh`,
`NodeExporterDown`) are live, not silently blind.** `up{job="node"}` = 1.

### 3. The Alertmanager → shim HTTP hop — **PARTIAL: hop PASS, delivery FAIL**
Command: `docker compose exec alertmanager wget --post-file=<alertmanager webhook JSON, alertname=BotManagerDown> http://viptalk-shim:8080/alertmanager`
Expected: ops room receives a message stamped `[staging]`
Actual: shim responded **HTTP 502** (its documented "VipTalk delivery failed, retry me" code). No message reached the ops room.

Shim log:
```
ERROR VipTalk rejected the message: HTTP 403 {"title":"Error 1010: Access denied",
"detail":"The site owner has blocked access based on your browser's signature.",
"error_code":1010,...}
technical register -> 1 room(s): FAILED
```

**Two things this proves, and they are separable:**

- **The in-network hop works.** Alertmanager reached the shim, the shim parsed the
  webhook, selected the technical register, resolved 1 ops room, and answered with a
  correct 502 so Alertmanager retries. This path was never exercised before; it is sound.
- **The shim cannot reach VipTalk.** Cloudflare blocks it at the edge on User-Agent.

**Bonus real-world exercise:** during the crash loop, `BotManagerDown` fired *for
real* and Alertmanager drove the shim on its own schedule (repeated webhook calls
11:59–12:03). So Prometheus rule → Alertmanager → shim is proven end-to-end against
a genuine outage, not just a synthetic POST. Only the final VipTalk leg fails.
`BotManagerDown` resolved cleanly once bot-manager recovered; no retry loop left behind.

**Root cause, isolated by controlled experiment** (same URL, same token, same room,
same host — only the User-Agent varied):

| User-Agent | Result |
|---|---|
| curl default | **HTTP 200** — `"Request send message successful"` |
| `Python-urllib/3.12` | **HTTP 403** — Cloudflare error 1010 |
| `Java-http-client/21.0.2` | **HTTP 200** — `"Request send message successful"` |

Consequences:
- The host, token, ops-room ID and egress are all **good** — two probe messages
  did land in the ops room.
- The block is specifically the `Python-urllib/*` UA signature.
- **The Java app path is not affected** — `VipTalkClient` uses
  `java.net.http.HttpClient`, whose default UA passes. Fixing defect #1 should give
  a working app-side channel.
- **Fix for the shim (one line):** `shim.py` line ~267 builds
  `urllib.request.Request(url, data=body, method="POST", headers={"Content-Type": ..., "Accept": ...})`
  with **no `User-Agent`**. Add an explicit `"User-Agent"` header.

Result: **FAIL** (delivery), with the cause fully isolated and a one-line fix identified.

### 4. Shim health
Command: `GET http://viptalk-shim:8080/health`
Expected: enabled, masked token, `baseUrlUsable: true`
Actual:
```json
{"enabled": true, "baseUrl": "https://api.viptalk.org", "baseUrlUsable": true,
 "token": "QGJv…(56 chars)", "instanceLabel": "staging", "opsRooms": 1,
 "productRooms": 0, "customerNoticesEnabled": false, "masterSwitch": true}
```
Result: **PASS** — enabled, token masked, `instanceLabel: staging`.
`productRooms: 0` and `customerNoticesEnabled: false` are the **correct AD-V7 staging
posture** (no customer-facing copy can reach a product room from staging).

This also independently proves the `secrets.env` → `.env` merge reached a container.

> **Caveat worth recording:** `baseUrlUsable` is a *string* check —
> `urlsplit(base_url).scheme in ("http","https")` — not a reachability probe. It
> reported `true` while every send was failing with 403. `/health` looking good is
> therefore not evidence the channel works; that is precisely the "healthy but inert"
> shape this release was supposed to avoid.

### 5. App path — `POST /api/v1/alerts/product/116`, `GET /api/v1/alerts/rooms`
Expected: message in TIP room; P_116 wired, rest not
Actual: both endpoints return **404** — the rolled-back image predates the alerting controller
Result: **NOT VERIFIABLE** (blocked by defect #1)

### 6. bot-manager reports VipTalk **enabled** at startup
Expected: `VipTalk alerting enabled (baseUrl=…)` at INFO
Actual: zero `VipTalk` lines in the rolled-back image's log — the feature is not in that build
Result: **NOT VERIFIABLE** (blocked by defect #1)

**Important:** this is *not* the secrets-merge failure mode the brief warned about.
The merge demonstrably worked: `deploy.sh` printed `Merging secrets.env into .env`,
the shim received full config, and **6 `VIPTALK_*` variables are present in the
bot-manager container's environment** (`printenv | grep -c VIPTALK` = 6). The config
is in place and waiting; only the code cannot start.

### 7. Normal smoke
| Check | Result |
|---|---|
| bot-manager healthy | PASS (on rolled-back image) |
| Existing bot groups unaffected | PASS — 154 bots, 0 DEAD |
| Grafana back | PASS (`/api/health` 200) |
| Prometheus back | PASS (`/-/healthy` 200) |
| Loki back | PASS (`/ready` 200) |
| `up{job="bot-manager"}` / `up{job="node"}` | PASS — both `1` |

### Additional — rules loaded and audience-labelled (plan Phase 3)
All **16** alerting rules loaded in the running Prometheus; **zero** with a missing
`audience` label:

- `audience=both`: `BotManagerDown`
- `audience=internal`: `BotManagerRestarted`, `JvmThreadsHigh`, `HostDiskSpaceLow`, `HostDiskSpaceCritical`, `HostMemoryLow`, `HostCpuHigh`, `NodeExporterDown`
- `audience=product` (all carry product routing): `GroupBalanceLow`, `EnvironmentSocketDown`, `EnvironmentSocketDegraded`, `EnvironmentDeadBotRatioHigh`, `EnvironmentGroupDead`, `EnvironmentAuthDown`, `EnvironmentLoginFailing`, `GameNoRounds`

Result: **PASS**

### Additional — secret hygiene in `alertmanager.yml`
Plan expects `grep -ci 'viptalk.org\|sendMessage\|token'` = 0; actual = 1.
The single match is line 20, a **comment**: `# VipTalk token is the shim container's
environment variable, from secrets.env.` No secret is present; both receivers are
in-network compose names (`bot-manager:8085`, `viptalk-shim:8080`).
Result: **PASS on substance** (the grep in the plan is over-strict — it matches its
own explanatory comment).

### Additional — rollback separation (brief's explicit request)
Command: `docker compose stop viptalk-shim node-exporter`, wait, check bot-manager
Actual: bot-manager stayed `healthy`, `actuator/health` 200, metrics still served
(9 `bots_by_status` series). Both containers restarted cleanly afterwards.
Result: **PASS — the separation holds; stopping the alerting sidecars does not affect bot-manager.**

The reverse direction was also demonstrated involuntarily: bot-manager crash-looping
for ~6 minutes left `alertmanager`, `viptalk-shim`, `node-exporter`, Grafana,
Prometheus and Loki all running normally.

## Current state of Bot-1

- **bot-manager**: running the **previous** image (`sha256:5e5d3df89246`, built Aug 10), healthy, 154 bots, 0 DEAD.
- **New compose file is live** and drives all 9 services. The old image ignores the
  `VIPTALK_*` env vars harmlessly.
- **Alertmanager, node-exporter, viptalk-shim**: deployed, running, healthy.
- **16 alert rules**: loaded and evaluating against real series.
- **`secrets.env`**: on the host, `0600`, correct staging posture.
- Rollback images available: `vingame-bot:rollback-20260818` (Aug 10, currently live)
  and `vingame-bot:rollback-staging` (Aug 5).

### Known degradations while the app image is rolled back

1. Alertmanager's default `viptalk` receiver posts to
   `http://bot-manager:8085/api/v1/alerts/alertmanager`, which **404s** on the old
   image. Product-routed and internal alerts will not be delivered until the new
   image ships. `BotManagerRestarted` is firing right now and will not be delivered.
2. The `viptalk-static-down` receiver reaches the shim, but the shim's VipTalk send
   fails (403) until the User-Agent fix lands.
3. `bots_by_game_status` on the old image carries **no `product` label**, so Phase 1
   product labelling is not in effect and `GameNoRounds` (3 currently `pending`)
   would misroute if it fired.

**Net: alerting is deployed but not yet delivering. No alert can reach a room today.**

## Required before a re-attempt

1. Annotate `VipTalkClient`'s public constructor (line 54) with `@Autowired`.
2. Add an explicit `User-Agent` header in `shim.py`'s `urllib.request.Request(...)`.
3. Recommended: a `@SpringBootTest` context-load test, so a DI break fails the build
   rather than the deploy.
4. Recommended: make the shim's `/health` `baseUrlUsable` reflect reachability, or
   rename it — it currently reads healthy while delivery is 100% failing.

## Notes

- Two probe messages **were delivered** to the ops room during User-Agent isolation,
  both marked `[staging] release probe … — please ignore`. They are expected noise.
- The Cloudflare 1010 here is a **real, directly-observed** block of the
  `Python-urllib` UA against the VipTalk REST API. It is unrelated to — and must not
  be confused with — the previously-disproven "Cloudflare" theory about bot WebSocket
  drops, whose actual cause was the PING-before-AUTH race.
- No git operations performed. Working tree still shows only ` M deploy.sh`; the
  uncommitted merge block was shipped as-is and neither committed nor reverted.
- The bot token was never echoed to logs, command output, or this report.
