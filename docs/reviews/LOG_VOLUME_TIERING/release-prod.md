# Release (PRODUCTION) — LOG_VOLUME_TIERING

Mode: bot (app + logging config only)
**Target: `Prod-Bot` / `s009-botgame-general-01` / `43.199.58.254` — `/home/sgame/bot-java`**
Branch: `staging` @ `1e359a1`
Image: `vingame-bot:latest` = `b9a49bcf45b4` (built 2026-08-24T10:40:51Z)
Rollback image: `vingame-bot:rollback-20260824` = `0d53ed7b7a2f`
Date: 2026-08-24T10:45:18Z (deploy) — verification through 11:00Z
Deploy **1 of 2**. Deploy 2 (Alertmanager, viptalk-shim, evidence-shim, node-exporter) was
deliberately **not** started.

## Verdict

**PASS.**

Track 1 log volume fell from a **15.7 MB/hour** baseline to **0.339 MB/hour** — a **46x
reduction** — with the fleet fully recovered (4 groups, 65 authenticated bots) and zero
container restarts. See "What I did not verify" for the honest limits of this result.

---

## Pre-flight

| Check | Result |
|---|---|
| Target is Prod-Bot, not Bot-1 | PASS — every command in this run went to `Prod-Bot` (port 22000). Bot-1 untouched. |
| HEAD | PASS — `1e359a1`, matches brief |
| Working tree | Dirty (waived). `deploy.sh`, `docs/reviews/VIPTALK_ALERTING_V2/release.md`, staged `TaiXiuMessages/*.js` deletions. **None under `src/`, `pom.xml`, `logging/`, `Dockerfile`** → artifact is exactly `1e359a1`. Nothing committed, restored, or staged. |
| `deploy.sh` shipped? | **No** — prod has none and the brief forbids creating one |
| Prepared compose preserves prod-only vars | PASS — `diff` against the live prod file dropped **zero** lines |

## Build

- `mvn clean install`: **PASS** (46.0 s, **1074 tests, 0 failures**), `JAVA_HOME` = openjdk-21.0.2
- `docker build --no-cache --platform linux/amd64`: **PASS** → `sha256:b9a49bcf45b4…`
- `docker save`: **PASS** — 393,085,952 bytes
- Image hash on box after `docker load` matches the local build hash: **PASS**

## Ship

All six payload files uploaded by sftp; every one verified on the box as a **regular file**
with a byte size identical to the local copy.

| File | Bytes | Destination |
|---|---|---|
| `bot.tar` | 393,085,952 | `~/bot-java/bot.tar` |
| `logging/log4j2.properties` | 20,619 | `~/bot-java/logging/log4j2.properties` |
| `docker-compose.prod.yml` (prepared) | 22,302 | `~/bot-java/docker-compose.yml` |
| `loki/loki-config.yaml` | 4,135 | `~/bot-java/loki/loki-config.yaml` |
| `promtail-config.yml` | 3,157 | `~/bot-java/promtail-config.yml` |
| `prometheus/alerts.yml` | 31,330 | `~/bot-java/prometheus/alerts.yml` |

- **`prometheus/prometheus.yml` NOT shipped** — confirmed untouched on the box (972 bytes,
  Jun 16 19:16), as required.
- **`.env` NOT modified** — still exactly `HOST_UID=1005` / `HOST_GID=1006`.
- No `secrets.env` created. `BOT_LOG_LEVEL` left unset.

## Pre-deploy on the box

- Backups (dated `.bak-20260824`, `cp -p`): `docker-compose.yml`, `loki/loki-config.yaml`,
  `promtail-config.yml` — **PASS**
- `mkdir -p logs/detail` — **PASS**
- Phase 4b step 0a archive relocation — **deliberately NOT run**, per brief
- **Rollback tag** `vingame-bot:rollback-20260824` created *before* `docker load` — **PASS**

### The mount hazard (the one that fails hard)

- `logging/log4j2.properties` verified as `REGULAR-FILE` before `up` — **PASS**
- Post-start, inside the container: `-rw-r--r--+ 1 1005 1006 20619 /app/config/log4j2.properties`,
  16 `appender.async*` lines — **PASS**
- `LOGGING_CONFIG=/app/config/log4j2.properties` confirmed in the container env — **PASS**
- log4j2 async thread `Log4j2-AsyncApp` present — **PASS**
- No directory was created at the mount path; the escape hatch (`LOGGING_CONFIG=`) was
  **not needed**.

### The mandatory gate — prod-only settings

`docker compose config` on the box, under `bot-manager`:

```
BOT_DEPOSIT_AMOUNT: "5000000"     ✅
BOT_IP: 43.199.58.254             ✅
LOGGING_CONFIG: /app/config/log4j2.properties
LOGGING_LEVEL_COM_VINGAME_BOT: INFO          ← BOT_LOG_LEVEL unset → INFO, intended prod posture
LOGGING_LEVEL_COM_VINGAME_WEBSOCKETPARSER: INFO
user: 1005:1006
```

**Gate PASS.** Both prod-only values survived. Re-confirmed post-start from
`docker inspect` on the running container.

## Loki re-ingest — suppressed (coordinator's amended option d)

Took the **copy-the-live-positions** path, not hand-seeding.

- `docker cp bot-java-promtail-1:/tmp/positions.yaml ./promtail-positions.seed.yaml` —
  **PASS**, 7,771 bytes, **169 entries** matching the 169 archives exactly.
- Seeded byte-for-byte into `bot-java_promtail-positions` as `positions.yaml`
  (root:root 644; promtail runs as uid 0).
- **Confirmed effective**: promtail logged `Seeked /logs/console-2026-08-23-22.log -
  &{Offset:17856228 …}` — it resumed at saved offsets rather than reading from byte 0.
- **`loki-data` grew 306.3 MB → 309.6 MB (+3.3 MB)**, not the ~2.5 GB a full re-ingest
  would have cost. Fallback path not needed.

## Deploy

- `docker compose down`: **PASS** (all 6 containers + network removed)
- `docker image rm`: **SKIPPED BY DESIGN** — see "Defect in the standard pipeline" below
- `docker load -i bot.tar`: **PASS** → `b9a49bcf45b4`; rollback tag survived
- `docker compose up -d mongo bot-manager loki promtail grafana prometheus`: **PASS**
- Bare `docker compose up -d` **not** run. Deploy-2 services confirmed absent from
  `docker ps -a`.

## Smoke test

| Check | Result |
|---|---|
| 6 services running | **PASS** — mongo, bot-manager, loki, promtail, grafana, prometheus |
| `bot-manager` healthy | **PASS** — healthy 30 s after start |
| RestartCount not climbing | **PASS** — **0** on all six, re-checked at +8 min |
| Spring Boot ready log | **PASS** — `Started Starter in 4.823 seconds` |
| Auto-start log | **PASS** — `Bot Manager startup complete. 4 bot groups running` |
| `/actuator/health` | **PASS** — `{"status":"UP"}`, HTTP 200; mongo/ping/ssl/diskSpace all UP |
| Deploy-2 services absent | **PASS** |

## Brief's verification list

### 3. Level check — `console.log` is JSON, INFO+, no DEBUG

**PASS.**

A trap worth recording: `logs/console.log` was **not** rotated by the deploy, so a naive
`grep -c '"level":"DEBUG"'` over the whole file returns **25,248** and looks like a hard
FAIL. Those lines are timestamped from `10:00` — pre-deploy output from the *old* build in
the same hourly file. Scoped to post-deploy (`timestamp >= 2026-08-24T10:45:18`):

| Level | Post-deploy count |
|---|---|
| INFO | 74 |
| WARN | 57 |
| **DEBUG** | **0** |
| **TRACE** | **0** |

Layout is `JsonTemplateLayout` (verified per-line). **Anyone re-running this check must
filter by timestamp or they will get a false FAIL.**

### 4. Track 2 exists and is growing

**PASS.** `logs/detail/detail.log` created at 10:45:18, `PatternLayout`, MDC rendering as
`[botGroupId/botId/gameType]`. Grew 136,843 → 154,992 bytes over the observation window.

**Track split proven under load:**
- ws-parser lines in track 2: **502**
- ws-parser lines in track 1 (post-deploy): **0**
- `AUTH [` frames in `docker logs`: **0** (the flood is gone from stdout, as designed)

### 5. Volume delta — the point of the exercise

| Measure | Track 1 (`console.log`) | Track 2 (`detail.log`) |
|---|---|---|
| **Pre-deploy baseline** | **15.7 MB/hour** (376.8 MB/day) | n/a (did not exist) |
| Cumulative since deploy (487 s) | **0.339 MB/hour** (8.14 MB/day) | 1.093 MB/hour (26.2 MB/day) |
| Fixed 300 s window | 0.004 MB/hour (384 bytes/300 s) | 0.003 MB/hour (272 bytes/300 s) |

**Headline: 15.7 → 0.339 MB/hour on track 1 = 46x reduction.**

I am quoting the **cumulative** figure as the honest one. It is *conservative*: it includes
the one-off startup burst (127 lines in the first seconds, incl. 50 startup WARNs), so
steady state is lower. The 300 s window figure (0.004 MB/h, a ~4000x reduction) is real but
came from a quiet interval and should **not** be quoted as the headline — track 1 at INFO
now emits roughly one tier-2 rollup line per 5 minutes, so a short window can easily
capture near-zero.

Independent baseline cross-check from the box's own hourly archives (each file *is* an
hourly rate): last 8 archives ranged 14.6–17.2 MB, mean ~16.2 MB/hour — consistent with the
brief's 15.7.

Track 2's number is **inflated** by an active scoped-DEBUG scope (below) and is understated
by `immediateFlush = false` buffering; treat it as indicative only.

**Tier-2 rollup confirmed working** — exactly the designed line:
```
env 78b6eefb-… (TIP Production, product 116): groups=4, bots=65, connected=65,
dead=0, deadGroups=0, rounds=25, staked=843500
```
No per-group detail lines, because all 4 groups are clean. That is the design ("volume
scales with sickness, not fleet size"), not missing output.

### 6. Fleet recovered — the `BOT_IP` check

**PASS.**

| Metric | Pre-deploy | Post-deploy |
|---|---|---|
| `bot_groups_running` | 4 | **4** |
| `bots_by_env_status{status="CONNECTION_AUTHENTICATED"}` | 65 | **65** |

Bots authenticated normally, so `BOT_IP` reached the auth strategies intact. No sign of the
whitelisting/settlement failure mode.

Note: metrics are served at **`/actuator/prometheus`**. `/api/v1/metrics/prometheus` returns
**404** on this build — worth correcting wherever that path is documented.

### 7. `jvm_threads_live_threads` stable

**PASS.** 53 (pre) → 54 → 56 over ~15 min. Flat; no sawtooth.

### 8. Disk

**PASS.** `df -h /` = `99G total, 11G used, 89G avail, 11%` — **identical before and after**.
App-reported free space rose slightly (94.65 → 94.76 GB).

## Loki retention change — 168h → 720h (coordinator's added item)

**Recorded explicitly, as requested.**

| | Before | After |
|---|---|---|
| `limits_config.retention_period` | **168h** (7 d) | **720h** (30 d) |
| `retention_stream` per-level overrides | absent | present (WARN/ERROR split) |
| `compactor.retention_enabled` | true | true (unchanged) |
| `retention_delete_delay` | 2h | 2h (unchanged) |

**Baseline for measuring the 30-day projection:**

| Metric | Value at 2026-08-24T11:00Z |
|---|---|
| `loki-data` volume | **309.6 MB** |
| `df -h /` avail | **89 GB** (11% used) |
| Track 1 ingest rate (all Loki gets) | **0.339 MB/hour** ≈ 8.1 MB/day |

The re-ingest was suppressed, so this baseline is clean. At the measured post-deploy rate a
30-day retention window projects to roughly **0.25 GB** of new data — against 89 GB free, on
a box whose 4x retention increase is applied to a stream whose volume fell 46x. Net risk is
**lower** than before the deploy. This is safe, but it is a real prod change on a box that
has died on ENOSPC before, so it should be re-measured once the fleet has run a full day.

## Plan-driven verification (`docs/plans/LOG_VOLUME_TIERING.md` § Verification)

Most of the plan's steps target Bot-1 staging or deploy-2 services. Applicable steps:

| Step | Expected | Actual | Result |
|---|---|---|---|
| U-1 | every service `Up`, bot-manager/mongo `(healthy)` | 6/6 Up; both healthy | **PASS** (narrowed — viptalk-shim/evidence-shim are deploy 2) |
| U-2 | `/actuator/health` → 200 | 200 | **PASS** |
| U-3 | `GET /api/v1/bot-group/` JSON array | not run — this endpoint is a known **405** (no such route) | **SKIPPED** (stale step) |
| P0-1 | mounted config in effect | file present, 16 async lines, `LOGGING_CONFIG` correct | **PASS** |
| P0-2 | async appender thread exists | `Log4j2-AsyncApp` | **PASS** |
| P0-3 | log file still growing | console.log and detail.log both growing | **PASS** |
| P0-4 | `docker compose logs` has 0 DEBUG | 0 real DEBUG (see note) | **PASS** |
| P0-5 | rollover on **even** hours | next boundary 12:00Z, after observation window | **NOT VERIFIED** |
| P0-6 | `node_filesystem_*` from Prometheus | node-exporter is deploy 2; used `df -h` instead | **NOT VERIFIABLE YET** |

**P0-4 note:** a raw `grep -c ' DEBUG '` on `docker logs` returns **1**. It is a false
positive — an **INFO** line whose *message text* contains the word: `Scoped per-group DEBUG
armed on logger com.vingame.bot`. Zero actual DEBUG events. Match on the level field, not
the word.

## Notable observations

### Scoped-debug auto-escalation fired, and proved AD-24 in production

`ScopedDebugEscalator` armed itself on **watchdog expiry** for group
`0241ac89-…` (expected: bots reconnecting after a redeploy) —
```
scoped debug escalated for group 0241ac89-… — trigger: watchdog expiry, expires 2026-08-24T11:07:06.993Z
```
This was an unplanned but valuable test. With a **live DEBUG scope open**:
- the arming/escalation announcements appear at **INFO in track 1** (so they reach Loki) ✅
- the DEBUG **payload** appeared in **track 2 only** ✅
- **track 1 still measured 0 DEBUG lines** ✅

That is the `ThresholdFilter` on `AsyncRolling` doing exactly its job under real conditions —
no DEBUG reached `console.log` or Loki despite an active scope. Confirmed via
`GET /api/v1/logging/debug` (1 scope, TTL to 11:07:06Z, cap 50).

### 50 `BotMemory` WARNs at startup — transient, not a rate-scaling WARN

`BotMemory.completeRound: sessionId mismatch (EndGame sessionId=0, in-flight sessionId=0) —
in-flight round discarded`, x50. All 50 fired inside a **10-millisecond window** at
`10:45:56`, and **zero since**. It is the expected artifact of a JVM restart mid-round
(in-flight rounds discarded), not an ongoing per-round WARN. No action needed, but worth
knowing it will recur on every restart and should not be mistaken for a leak.

### One expected config WARN

`viptalk.instance-label is not set — alert headers will not say which instance produced
them.` Correct for deploy 1: VipTalk is `enabled=false` with a blank token on prod. Deploy 2
should set `VIPTALK_INSTANCE_LABEL`.

### `prometheus/alerts.yml` is inert, as intended

Shipped so the bind mount resolves to a **file** rather than compose creating a directory.
Prod's existing `prometheus.yml` does not reference it — confirmed via
`GET /api/v1/rules` → `{"groups":[]}`. Prometheus healthy, 1 active target up.

### Defect in the standard releaser pipeline (worth fixing)

The documented pipeline runs `docker image rm vingame-bot:latest` before `docker load`. On a
prod box **that destroys the only rollback image**. `docker load` confirmed the risk with:

> `The image vingame-bot:latest already exists, renaming the old one with ID sha256:0d53ed7b… to empty string`

i.e. the prior image survives only as an untagged dangling layer, easy to prune and hard to
identify under pressure. Tagging `vingame-bot:rollback-<date>` first and skipping the `rm` is
strictly better and cost nothing. The box already carried `rollback-20260811`,
`rollback-20260806`, `rollback-phase1`, so this is established local practice — the
**standard pipeline doc should be amended to match.**

## What I did NOT verify

Stated explicitly, per the brief:

1. **Rollover on even hours (P0-5).** Deploy landed 10:45Z; the next 2 h boundary is 12:00Z,
   past the observation window. The 2 h interval is set in the shipped config but **no
   rolled file has been observed on prod**.
2. **Steady-state volume over a representative period.** All figures come from a ~15 min
   window immediately after restart, which includes startup burst and an active scoped-DEBUG
   scope. **Re-measure after a full day.** The 46x figure is directionally solid (three
   independent measurements agree it is a 1–2 order-of-magnitude drop) but is not a 24 h
   number.
3. **Loki retention actually deleting at 720h.** Config is loaded; the compactor enforces on
   its own interval plus a 2 h delay. Only the configured values were verified, not eviction
   behaviour.
4. **Everything belonging to deploy 2** — Alertmanager routing, VipTalk delivery, evidence
   promotion / hardlinking, node-exporter metrics, `EVIDENCE_ROLLOVER_HOURS` agreement.
   Those services were never started.
5. **`prometheus/alerts.yml` rule validity.** The file is on the box and is inert. Its rules
   have **never been parsed by prod Prometheus** — a syntax error would only surface in
   deploy 2 when `prometheus.yml` starts referencing it. **Worth a `promtool check rules`
   before deploy 2.**
6. **Grafana dashboards / Loki queries end to end.** Grafana returns 200 and Loki ingests
   track 1 with correct JSON labels, but no dashboard was opened and no saved query run.
7. **Settlement / RTP correctness.** Bots authenticate and stake (`rounds=25,
   staked=843500`), which clears the `BOT_IP` concern, but I did not verify money actually
   settles — that needs a longer horizon than this window.
8. **Track 2 volume without a scoped-DEBUG scope.** The one measurement had a live scope for
   one of four groups.

## Rollback

Not needed. Had it been:

```bash
cd /home/sgame/bot-java
cp -p docker-compose.yml.bak-20260824 docker-compose.yml
cp -p loki/loki-config.yaml.bak-20260824 loki/loki-config.yaml
cp -p promtail-config.yml.bak-20260824 promtail-config.yml
docker compose down
docker tag vingame-bot:rollback-20260824 vingame-bot:latest
docker compose up -d mongo bot-manager loki promtail grafana prometheus
```

All three backups and the rollback image are in place on the box. The old positions volume
seed (`promtail-positions.seed.yaml`) also remains.

## Constraints honoured

- Nothing pushed, merged, or committed. No source file modified. `TaiXiuMessages` staged
  deletions left exactly as found.
- **Bot-1 never contacted.** All commands targeted `Prod-Bot`.
- Deploy-2 services never started.
- No `deploy.sh` or `secrets.env` created on prod; `.env` unchanged.
- Only file written in the repo: this report.

---
---

# Release (PRODUCTION) — LOG_VOLUME_TIERING — **DEPLOY 2 of 2**

Target: `Prod-Bot` / `s009-botgame-general-01` — `/home/sgame/bot-java`
Scope: Alertmanager, viptalk-shim, evidence-shim, node-exporter + Prometheus reconciliation
Date: 2026-08-24T11:05:49Z (`up`) — verification through 11:10:46Z
No rebuild: image unchanged at `vingame-bot:latest` = `b9a49bcf45b4`. Rollback tag
`vingame-bot:rollback-20260824` = `0d53ed7b7a2f` retained; **no image removed.**

## Verdict

**PASS.** All 10 services running, 0 restarts, fleet back to 4 groups / 65 authenticated,
17 alert rules loaded, both shims healthy and correctly configured.

**Two things you need to know**, both detailed below:
1. I had to ship **one file the payload list omitted** (`viptalk-shim/shim.py`) — without it
   viptalk-shim would have hit the deploy-1 directory-mount hazard and failed to start.
2. **A real VipTalk message was delivered to the ops room** as an organic consequence of
   this deploy. Not synthetic, not fired by me — but a message did appear.

---

## Payload gap I caught before `up`

The brief listed four payload items. Compose requires **five**. I enumerated every
`./`-relative bind source in the compose file and tested each on the box:

```
MISSING ./alertmanager/alertmanager.yml   <-- in payload
MISSING ./evidence-shim/shim.py           <-- in payload
MISSING ./viptalk-shim/shim.py            <-- NOT in payload  ⚠
```

`docker-compose.yml` mounts `./viptalk-shim/shim.py:/app/shim.py:ro`. Had it been absent at
`up`, Docker would have created a **directory** at that path and viptalk-shim would have
crash-looped on `python: can't open file '/app/shim.py': [Errno 21] Is a directory` —
**exactly the failure class as deploy 1's `log4j2.properties` hazard**, and it would have
silently disabled the alert-delivery path this deploy exists to enable.

I shipped it (`4887d0e7…`, hash parity with local). Two other `./` matches
(`./logs/evidence/`, `./secrets.env`) were **comment text, not mounts** — `logs/evidence/`
is created by the shim itself. After shipping, all 11 real bind sources resolve to the
correct type.

**Recommendation:** make "enumerate every bind source and assert its type" a standing
pre-`up` step. It has now caught a hard-fail defect on both deploys.

## Pre-work verified (not redone)

| Item | Result |
|---|---|
| `.env` merged, `0600` | PASS — 8 keys; `HOST_UID`/`HOST_GID` preserved (`user: 1005:1006` still resolves) |
| `secrets.env` | PASS — present, `0600` |
| `.env.bak-20260824` | PASS — present |
| `VIPTALK_BOT_TOKEN` | PASS — **56 chars** (reported by length only; never printed) |
| `VIPTALK_ENABLED` | `true` |
| `VIPTALK_INSTANCE_LABEL` | `prod` |
| `VIPTALK_CUSTOMER_NOTICES_ENABLED` | `false` — left alone as instructed |
| `VIPTALK_DOWN_ROOM_IDS` | **empty** — see observations |
| `prometheus/alerts.yml` | hash on box **already identical** to local (`908044…`) → not re-shipped |

**Deploy-1 regression check after the `.env` rewrite** — `docker compose config` still
resolves `BOT_IP: 43.199.58.254`, `BOT_DEPOSIT_AMOUNT: "5000000"`,
`LOGGING_CONFIG: /app/config/log4j2.properties`, `LOGGING_LEVEL_COM_VINGAME_BOT: INFO`.
Nothing was lost in the merge.

## Ship

Backed up `prometheus/prometheus.yml` → `prometheus.yml.bak-20260824` first. All four files
verified by sha256 parity against local:

| File | sha256 | Note |
|---|---|---|
| `prometheus/prometheus.yml` | `3a675f2e…` | replaced (box had `da5193a0…`) |
| `alertmanager/alertmanager.yml` | `9e0ed8e3…` | new |
| `evidence-shim/shim.py` | `4d62d8d7…` | new |
| `viptalk-shim/shim.py` | `4887d0e7…` | **new — the omitted file** |
| `prometheus/alerts.yml` | `908044687…` | unchanged, not re-shipped |

`prometheus.yml` delta: adds `rule_files: /etc/prometheus/alerts.yml`, `alerting →
alertmanager:9093`, and the `node` scrape job. Existing `bot-manager` job untouched.

## Deploy

`docker compose up -d` (bare) — **PASS**, exit 0. bot-manager `Recreated` as predicted
(gained the `VIPTALK_*` env block); healthy again in **30 s**.

### Prometheus did NOT reconcile on its own — manual reload required

The brief expected `up -d` to "reconcile prometheus with its new config". **It did not.**
Compose reported `Container bot-java-prometheus-1 Running` and left it alone: only a
*bind-mounted file* changed, not the service definition, so compose saw nothing to do.
Verified it was still on the old config:

```
/api/v1/rules        → {"groups":[]}          (expected 17 rules)
/api/v1/targets      → bot-manager only        (no node job)
/api/v1/alertmanagers→ activeAlertmanagers: [] (no alertmanager)
```

The image's `command:` does not include `--web.enable-lifecycle`, so `POST /-/reload` is
unavailable. Reloaded with `docker kill -s HUP bot-java-prometheus-1` — clean, no restart,
no TSDB interruption:

```
11:06:51 level=info msg="Completed loading of configuration file" ... rules=3.528949ms
```

**This is a real gap in the deploy-2 procedure** — without the SIGHUP, every alert rule and
the entire alertmanager path would have been silently inert while all 10 containers looked
perfectly healthy. Worth adding to the runbook.

## Verification

| Check | Expected | Actual | Result |
|---|---|---|---|
| Services running | 10 | **10** | **PASS** |
| bot-manager healthy | healthy | healthy (30 s) | **PASS** |
| RestartCount | not climbing | **0 on all 10** | **PASS** |
| `bot_groups_running` | 4 | **4** | **PASS** |
| `CONNECTION_AUTHENTICATED` | ~65 | **65** | **PASS** |
| `jvm_threads_live_threads` | stable | 53 (was 53→56) | **PASS** |
| Prometheus rules | 17 | **17 in 6 groups** | **PASS** |
| Scrape targets `node` + `bot-manager` | both up | **both `up`** | **PASS** |
| Alertmanager `/-/ready` | ready | `OK` | **PASS** |
| Alertmanager config | loads clean | loaded, no errors | **PASS** |
| evidence-shim healthcheck | passing | healthy | **PASS** |
| `logs/evidence/` created | yes | yes | **PASS** |
| viptalk-shim healthcheck | passing | healthy | **PASS** |
| viptalk-shim not self-disabled | enabled | **`enabled: true`, `masterSwitch: true`** | **PASS** |
| `VIPTALK_INSTANCE_LABEL` WARN | gone | **gone** | **PASS** |
| `/actuator/health` | UP | UP | **PASS** |

Rule groups: `bot-manager-availability` (2), `bot-manager-balance` (1),
`bot-manager-environment` (6), `bot-manager-game` (1), `bot-manager-jvm` (2), `host` (5)
= **17**, matching the coordinator's `promtool` count exactly.

### The instance-label WARN is provably gone

Same JVM, two consecutive starts, visible in track 2:

```
10:45:20  INFO  VipTalkClient      - VipTalk alerting disabled (...)
10:45:20  INFO  AlertRoomRegistry  - ... ops room not configured
10:45:20  WARN  AlertMessageFormatter - viptalk.instance-label is not set — ...
11:05:53  INFO  VipTalkClient      - VipTalk alerting enabled (baseUrl=https://api.viptalk.org)
11:05:53  INFO  AlertRoomRegistry  - VipTalk rooms: 1 product room(s) wired [P_116], ops room configured
11:05:53  INFO  AlertRouter        - VipTalk customer-facing notices disabled
```

### viptalk-shim state (token never printed)

`enabled: true`, `baseUrlWellFormed: true`, `instanceLabel: prod`, `opsRooms: 1`,
`productRooms: 0`, `masterSwitch: true`, customer notices `false`, token length **56**.
**Not** the blank-token self-disable ERROR path.

### evidence-shim — AD-21 retro-promotion fired, correctly

First run ever, so no `.clean-shutdown` marker existed and the shim did exactly what AD-21
specifies:

```
no clean-shutdown marker in /logs/evidence - the previous run did not stop cleanly;
promoting the newest logs (tagged boot)
promotion key='boot' tag=boot linked=['console-live-boot-...log',
  'console-2026-08-24-09.log', 'detail-live-boot-...log'] skipped=- errors=-
```

Config read back: `sameFilesystem: true` (hardlinks viable), `detailDirPresent: true`,
`maxAgeDays 14.0`, `detailMaxAgeDays 3.0`, `maxBytes 12884901888` (12 GB),
`rolloverHours 2.0`, `newestCount 2` — all matching spec.

**Hardlinks confirmed, not copies (AD-14):** promoted files show a **link count of 2**
(`-rw-r--r--. 2 sgame sgame …`). Zero extra blocks consumed — `df` is unchanged. Both
tracks were promoted (console *and* detail), confirming AD-28.

Absence of `.clean-shutdown` on a first run is correct behaviour, not a fault.

### P0-6 — now verifiable (was blocked in deploy 1)

node-exporter exists, so the plan step that could not run in deploy 1 now passes:

```
node_filesystem_size_bytes {/}  = 98.7 GiB
node_filesystem_avail_bytes{/}  = 88.2 GiB
df -h /                         = 99G size, 89G avail, 11% used   ← cross-checks
```

### Final disk / volume state

| Metric | Deploy 1 end | Deploy 2 end |
|---|---|---|
| `df -h /` avail | 89 GB (11%) | **89 GB (11%)** |
| `loki-data` | 309.6 MB | **305.5 MB** (compactor retention active) |
| `alertmanager-data` | — | 0 B (new) |
| `promtail-positions` | 7.771 kB | 7.771 kB |

`loki-data` **shrank**, indicating the compactor is enforcing retention.

### Logging posture preserved

Track 1 since 11:05:50: **73 INFO / 70 WARN / 0 DEBUG / 0 TRACE**. Deploy 2 did not
regress the deploy-1 tiering. (The WARN count is another transient `BotMemory` restart
burst, same one-shot signature as deploy 1.)

---

## A real VipTalk message was delivered — read this

I did **not** fire a synthetic or test alert; that instruction was honoured strictly. But
recreating bot-manager legitimately tripped the `BotManagerRestarted` rule, and with the
alerting path newly live it delivered. This is confirmed, not inferred:

```
prometheus  /api/v1/alerts                        → BotManagerRestarted, firing
alertmanager                                       → receivers: ['viptalk'], not silenced/inhibited
alertmanager_notifications_total{integration="webhook"}        = 1
alertmanager_notifications_failed_total{...}                   = 0  (all reasons)
bot-manager POST /api/v1/alerts/alertmanager       → HTTP 200, 103 ms, count 1
alert_dispatch_total{outcome="sent",product="none",reason="ok"} = 1.0
```

`product="none"` means it resolved to the **ops room** via fallback, stamped `prod`.

So: **one `BotManagerRestarted` notice, labelled `prod`, appeared in the ops room at
~11:07:24Z.** Unavoidable given the container recreation this deploy required. The silver
lining is that it constitutes a genuine, un-staged **end-to-end delivery proof** —
Prometheus → Alertmanager → app → VipTalk — so a separate delivery test may now be
unnecessary.

Routing note: the `viptalk` receiver points at the **app**
(`bot-manager:8085/api/v1/alerts/alertmanager`); the shim is `viptalk-static-down`, wired
only for `BotManagerDown` (the case where the app itself is dead). That is why
viptalk-shim's own `lastSend` is still `null` and its log shows only health checks — it is
correctly idle, **not** broken.

## Other observations

- **Compose warning, harmless:** `volume "bot-java_promtail-positions" already exists but
  was not created by Docker Compose. Use 'external: true'`. Expected — I created it by hand
  in deploy 1 to suppress the re-ingest. Compose still mounts it correctly (positions
  intact at 7.771 kB, no re-ingest: `loki-data` did not jump). Optional tidy-up: declare it
  `external: true`.
- **`VIPTALK_DOWN_ROOM_IDS` is empty.** viptalk-shim reports `opsRooms: 1, productRooms: 0`,
  so the static-down path has an ops room to fall back on. Worth a conscious decision about
  whether `BotManagerDown` should reach product rooms.
- **`alertmanager` and `node-exporter` are not scraped by Prometheus** — `prometheus.yml`
  defines only `bot-manager` and `node`. Alertmanager's own metrics (which I used to prove
  delivery) are reachable only from inside the container. Consider adding an `alertmanager`
  scrape job so notification failures are alertable.

## What I did NOT verify (deploy 2)

1. **Alert delivery for any rule other than `BotManagerRestarted`.** One rule's path is
   proven end to end; the other 16 are loaded and syntactically valid but unexercised.
2. **`BotManagerDown` → viptalk-shim static path.** This is the one that matters when the
   app is dead, and it is precisely the one that cannot be tested without either killing
   bot-manager or firing a synthetic alert. **viptalk-shim has never sent a message**
   (`lastSend: null`). Its config and health are good; its delivery path is **unproven**.
3. **evidence-shim's webhook path.** Only the AD-21 boot promotion ran. The Alertmanager-
   triggered promotion, the +5 min and rollover+120 s passes, and the byte-guard eviction
   ordering are all unexercised.
4. **`.clean-shutdown` marker written on a clean stop.** I observed only its *absence*
   driving retro-promotion. The write-on-shutdown half needs a clean `docker compose stop`.
5. **Inhibition rules** (3 defined) — never triggered.
6. **Grafana dashboards** against the new alert data. Grafana was not restarted and no
   dashboard was opened.
7. **Even-hour rollover** — still unobserved (carried over from deploy 1; next boundary
   12:00Z).
8. **24 h steady-state volume** — carried over from deploy 1; re-measure after a full day.
9. **Loki actually evicting at 720 h.** `loki-data` shrinking is consistent with retention
   working, but no eviction was observed at the new horizon.

## Rollback (deploy 2)

Not needed. If required:

```bash
cd /home/sgame/bot-java
cp -p prometheus/prometheus.yml.bak-20260824 prometheus/prometheus.yml
cp -p .env.bak-20260824 .env          # drops VIPTALK_*, restores HOST_UID/HOST_GID
docker compose stop alertmanager viptalk-shim evidence-shim node-exporter
docker compose rm -f alertmanager viptalk-shim evidence-shim node-exporter
docker kill -s HUP bot-java-prometheus-1
docker compose up -d mongo bot-manager loki promtail grafana prometheus
```

Note the app-side rollback needs the `.env` restore **and** a bot-manager recreate to drop
the `VIPTALK_*` block. All dated backups from both deploys remain on the box.

## Constraints honoured (deploy 2)

- **No synthetic or test alert fired.** The one delivered message was organic — see above.
- Token never printed; `.env`/`secrets.env` reported by key and length only, all log
  excerpts regex-masked.
- Nothing pushed, merged, or committed. No source file modified. `TaiXiuMessages/*.js`
  staged deletions and the dirty `deploy.sh` left exactly as found.
- **Bot-1 never contacted.**
- No image removed; `vingame-bot:rollback-20260824` intact.
- `VIPTALK_CUSTOMER_NOTICES_ENABLED=false` unchanged.
