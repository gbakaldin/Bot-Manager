# Release — LOG_VOLUME_TIERING

Mode: bot **+ config payload** (not a plain `mode=bot` deploy — see Ship)
Branch: `feature/log-volume-tiering` @ `5350b12`
Image: `vingame-bot:latest` (built 2026-08-19T12:07:47Z–12:08:29Z UTC)
Target: **Bot-1 staging** (`/home/sgame/bot-java`) — explicitly not prod
Date: 2026-08-19T12:06:40Z – 2026-08-19T14:04Z UTC (build 12:06Z, deploy 12:11Z, verification through the 14:00Z rollover boundary)

## Verdict

**PASS, with two findings that are not defects in this branch and one plan step
that fails as literally written.**

Everything the branch ships works on the box: the externalised log config is in
effect, the async appender is live, the console is INFO+ only while DEBUG still
lands in the JSON file, `LOGGING_LEVEL_COM_VINGAME_BOT` takes, the tier-1 folding
and tier-2 rollup are emitting, scoped DEBUG auto-escalated **twice on real
triggers without being forced**, and the evidence shim promoted files by hardlink
on its first boot and routed correctly through Alertmanager.

The four things to read before shipping this anywhere else:

1. **P1-3 FAILS as written** (2022 INFO lines / 300 s against a `< 100`
   expectation) — but **59 of those lines are `com.vingame.bot.*`** and 4402 are
   `com.vingame.websocketparser.*` at INFO under the root logger, which this
   feature's tier model does not govern. The feature's own tier is quiet. The
   per-bot INFO flood this plan exists to kill is still present, one logger
   prefix outside its scope. See Finding 1.
2. **Promtail re-ingests the whole log directory on every redeploy** — `loki-data`
   went 1.02 GB → 3.24 GB in the 17 minutes after this deploy. Pre-existing gap,
   but the 168 h → 720 h change **amplifies it 4.3×** and it corrupts P0-9's
   measurement method. See Finding 2.
3. **Round-completion-dependent checks could not be exercised**: the staging fleet
   emits **zero `endGame` messages**, so no round ever completes. P1-8's EndGame
   half, AD-8's compensating `rounds`/`staked` drain, and P2-4 are unproven here —
   not failed, unexercised.
4. **P0-5's acceptance criterion is wrong in the plan.** The 2-hour rollover works
   and fires on even-hour boundaries, but log4j2 names the archive
   `rollover − 1 hour`, so the suffix is **odd** (`-13`), not even. As written the
   step would fail on a correctly-working system every time. Filenames stay unique,
   which is what AD-20 actually requires.

## Disk gate (P0-6) — the number the rollout is calibrated on

**Decision: shipped `retention_period: 720h` as written.** Recorded in full because
this is the figure everything downstream depends on.

| Measurement | Value |
|---|---|
| Root filesystem (`/dev/nvme0n1p1`) size | **107,362,627,584 B = 100 GiB** |
| Available at deploy time | **71,897,219,072 B = 66.96 GiB** (34% used) |
| `loki-data` volume at deploy time | **1,016,059,871 B = 1.016 GB** (at the old 168 h) |
| `logs/` at deploy time | **4,580,631,588 B = 4.58 GB** |

The brief's gate was "if root is under ~100 GB, drop `retention_period` back to
`168h`". Root is **exactly at** that line, not under it, so the size proxy alone
does not decide it. The direct measurement does:

> `loki-data` holds **1.016 GB at a 168 h horizon**. Raising the horizon 4.3× to
> 720 h projects to **~4.4 GB**, which is **6.5% of the 66.96 GB available** —
> an order of magnitude inside P0-9's own "abort if the 30-day projection exceeds
> 50% of available disk" gate, and nowhere near the **33.7 GB** of `loki-data`
> that caused the 2026-06-30 ENOSPC.

`retention_period: 720h` therefore ships. **P0-9 at T+7 d remains the live gate** —
this projection is a projection, not a fitted number, and Finding 2 below gives a
concrete mechanism by which it could be wrong. The rollback is one line in the
**bind-mounted** `loki/loki-config.yaml` (`720h` → `168h`) plus
`docker compose restart loki`: no rebuild, no image, no application change.

### P0-9 baseline (for the T+24 h and T+7 d growth checks)

| Metric | T+0 (12:10:39Z, pre-deploy) | 12:27:26Z (post-deploy) |
|---|---|---|
| `loki-data` | 1,016,059,871 B | **3,240,742,886 B** |
| `logs/` | 4,580,631,588 B | 4,591,813,182 B |
| `logs/evidence/` | (did not exist) | 42,592,771 B |
| Root available | 71,897,219,072 B | 69,434,818,560 B |
| Root used | 34% | 36% |

**Use the pre-deploy 1,016,059,871 B as the T+0 baseline, not the post-deploy
figure.** The 2.2 GB jump inside 17 minutes is the promtail re-ingest of Finding 2,
not organic growth, and it will recur on every redeploy. Any T+7 d − T+24 h delta
that spans a redeploy is invalid.

Commands for the deferred checks:

```bash
docker run --rm -v bot-java_loki-data:/d alpine du -sb /d   # loki-data bytes
du -sb logs logs/evidence                                    # log + evidence bytes
df -B1 /                                                     # available
```

## Build

- `mvn clean install`: **PASS** (45.2 s, JDK 21.0.2)
- Test totals — **1838 tests, 0 failures, 0 errors, 0 skipped**, matching the
  briefed figure exactly:

  | Module | Tests |
  |---|---|
  | bot-api | 125 |
  | bot-strategies | 111 |
  | bot-messages | 136 |
  | bot-engine | 416 |
  | bot-app | 1050 |
  | **Total** | **1838** |

  **0 skipped matters here**: `EvidenceShimSelfTestRunnerTest` and
  `VipTalkShimSelfTestRunnerTest` both `assumeTrue(python3Available())` and would
  silently no-op on a box without `python3`. They ran.
- `docker build --no-cache --platform linux/amd64`: **PASS** (34.9 s), image
  `sha256:3032833a411c9b3cb3ce0c9c2f05c77b0391a6875d7e4d88eb49d5377e33a18d`
- `docker save`: **PASS** — **393,112,576 bytes**

Pre-build git hygiene: committed only the reviewer's
`docs/reviews/LOG_VOLUME_TIERING/review.md` (commit `5350b12`) as instructed.
`deploy.sh` left uncommitted and shipped from the working tree;
`docs/reviews/VIPTALK_ALERTING_V2/release.md` left untouched. **Nothing pushed, no
merge to `staging` or `main`, no `git add -A`.**

## Ship — this was not a plain image-only deploy

A default `mode=bot` deploy would have **broken the box**: `docker-compose.yml` now
hard-references `logging/log4j2.properties` and `evidence-shim/shim.py`, neither of
which existed on Bot-1. Docker would have created *directories* at those mount
points, failing closed (bot-manager wouldn't start; evidence-shim would crash-loop
on `IsADirectoryError`).

Mitigated by creating both directories **before** `docker compose up`:

```bash
mkdir -p logging evidence-shim
```

Backups taken first, tagged `20260819-190838` (box-local): `docker-compose.yml`,
`alertmanager/alertmanager.yml`, `loki/loki-config.yaml`, `promtail-config.yml`,
`secrets.env`.

All eight config artefacts + the image transferred and **verified by SHA-256 on
both ends — all eight matched**:

| File | SHA-256 (first 16) | Result |
|---|---|---|
| `bot.tar` | `1bed02d85ab0bc0d` | PASS |
| `docker-compose.yml` | `db57b866591af9cf` | PASS |
| `deploy.sh` (working-tree copy) | `8eccc6bffb87286c` | PASS |
| `promtail-config.yml` | `af8a52de81afb629` | PASS |
| `logging/log4j2.properties` (**new dir**) | `77a61d4e64e5af79` | PASS |
| `evidence-shim/shim.py` (**new dir**) | `a9a66cac0e54f980` | PASS |
| `alertmanager/alertmanager.yml` | `9e0ed8e3b45be6b5` | PASS |
| `loki/loki-config.yaml` | `42e168a2e15404c7` | PASS |

`secrets.env.example` also shipped. **`BOT_LOG_LEVEL=DEBUG` appended to the host
`secrets.env`** (staging keeps DEBUG; prod leaves it unset so the shipped INFO
default applies). `deploy.sh`'s generic grep-append merge picked it up with no
change, as briefed — confirmed by `LOGGING_LEVEL_COM_VINGAME_BOT=DEBUG` appearing
in pid 1's environment.

## Deploy

- `docker compose down`: **PASS** (12:10:50Z)
- `docker image rm vingame-bot:latest`: **PASS** (5 layers deleted)
- `docker load -i bot.tar`: **PASS** (12:11:04Z)
- `./deploy.sh` (used instead of bare `compose up -d` so the `secrets.env` → `.env`
  merge runs): **PASS** (12:11:25Z)

**Downtime: ~35 s of container downtime** (12:10:50Z → 12:11:25Z), with the app
fully up and all 4 bot groups re-started by **12:11:34Z — ~44 s total**. Grafana,
Prometheus, Loki, Promtail and Alertmanager cycled with it, as expected on Bot-1's
single-Compose layout. The escape hatch (`LOGGING_CONFIG=` empty) was **not needed**.

## Smoke test — whole stack

`docker compose ps` at 12:13:04Z — **all 10 services Up**, including the new one:

| Service | Status |
|---|---|
| bot-manager | Up **(healthy)** |
| mongo | Up **(healthy)** |
| viptalk-shim | Up **(healthy)** |
| **evidence-shim** | Up **(healthy)** — new |
| alertmanager, grafana, loki, node-exporter, prometheus, promtail | Up |

- Spring Boot ready: **PASS** — `Started Starter in 9.267 seconds`
- Auto-start: **PASS** — `Bot Manager startup complete. 4 bot groups running`
- **U-1** all services up incl. observability stack: **PASS**
- **U-2** `/actuator/health` → **200**: **PASS**
- **U-3**: **N/A — the plan's command is stale.** `GET /api/v1/bot-group/` returns
  **405** and does so *pre-deploy too* (verified before touching the box), because
  `BotGroupController` exposes `POST /{envId}/filter`, not a collection `GET`.
  Not a regression. Substituted `GET /api/v1/environment/` → **200** with a JSON
  array, and `POST /api/v1/bot-group/{envId}/filter` → **200** listing groups
  across all 5 environments. Both pass. *Worth correcting in the plan and in
  `CLAUDE.md`'s REST table, which also documents the non-existent `GET /`.*

## Plan verification — box-only steps

17 of the plan's 34 steps need the box (QA proved the other 17 in the build; those
were not re-run). Run in the briefed priority order.

### P0-6 — disk headroom (PRE-RAMP GATE)

Command:
```bash
df -B1 / ; docker run --rm -v bot-java_loki-data:/d alpine du -sb /d ; du -sb logs
curl -sG 'http://localhost:9090/api/v1/query' --data-urlencode 'query=node_filesystem_size_bytes{...}'
```
Expected: non-empty result, cross-checked with `df -h /`; record both numbers.
Actual: see the **Disk gate** table above — 100 GiB total, 66.96 GiB available,
`loki-data` 1.016 GB, `logs/` 4.58 GB.
**Result: PASS** — measured, recorded, and the 720 h decision derived from it.

### P0-1 — the mounted config is the one in effect

Command:
```bash
docker compose exec bot-manager sh -c 'ls -l /app/config/log4j2.properties && grep -c "^appender.async" /app/config/log4j2.properties'
docker compose exec bot-manager sh -c 'tr "\0" "\n" < /proc/1/environ | grep LOGGING_CONFIG'
```
Expected: file exists, count > 0, `LOGGING_CONFIG=/app/config/log4j2.properties`.
Actual:
```
-rw-r--r-- 1 botmanager botmanager 6535 Aug 19 12:08 /app/config/log4j2.properties
6
LOGGING_LEVEL_COM_VINGAME_BOT=DEBUG
LOGGING_CONFIG=/app/config/log4j2.properties
```
6535 B matches the shipped file byte-for-byte. **Result: PASS** — AD-2's bind
mount works; subsequent log tuning on this box is now deploy-free.

### P0-2 — the async appender thread exists

Command: `docker compose exec bot-manager sh -c 'cat /proc/1/task/*/comm | grep -i async'`
Expected: at least one line beginning `AsyncAppender` (15-char truncation).
Actual: `Log4j2-AsyncApp`
**Result: PASS.** Name differs from the plan's guess (`AsyncAppender-R`) because
log4j2 2.24.1 names the thread `Log4j2-AsyncAppender...`; it is the same thread and
its existence proves the appender graph built — i.e. the
`appender.async.appenderRef.type = AppenderRef` line, flagged in the plan as *"the
single most likely silent failure in Phase 0"*, is correct. Minor plan wording fix.

### P0-3 — no events lost, file still growing

Command: `A=$(wc -l < logs/console.log); sleep 60; B=$(wc -l < logs/console.log)`
Expected: `B > A`.
Actual: `19010 -> 22859` (**+3849 lines in 60 s**).
**Result: PASS.**

### P0-4 — console no longer carries DEBUG

Command:
```bash
docker compose logs --since 10m bot-manager | grep -cE '^bot-manager-1  \| [0-9:.]+ \[[^]]*\] DEBUG'
grep -c '"level":"DEBUG"' logs/console.log
```
Expected: `0` on the console; `> 0` in the file (staging runs at DEBUG). Both must hold.
Actual:

| Stream | Count |
|---|---|
| console at DEBUG | **0** |
| console at INFO | 2438 |
| console at WARN/ERROR | 807 |
| JSON file at DEBUG | **14310** |

A naive `grep -c ' DEBUG '` first returned `1`; the hit was a false positive — an
**INFO** line whose text contains the word: `ScopedDebugInstaller - Scoped
per-group DEBUG armed on logger com.vingame.bot (max 50 concurrent scopes, sweep
30s)`. Level-position matching gives a true `0`.
**Result: PASS** — AD-5's `ThresholdFilter` halves the double-write for the DEBUG
tier without losing it. (That false-positive line is also independent confirmation
the Phase 2 installer attached at startup.)

### P0-5 — rollover is on even hours — **cadence PASS; the plan's filename expectation is WRONG**

Deploy at 12:11:30Z. Two observations either side of the odd-hour mark, then one
after the even-hour boundary.

**13:03:35Z and 13:38:30Z — must not have rolled:**
```
logs/console-2026-08-19-12.log  -> No such file or directory
logs/console-2026-08-19-13.log  -> No such file or directory
-rw-r--r-- 3 sgame sgame 33106743 logs/console.log   (13:03Z)
-rw-r--r-- 3 sgame sgame 51575333 logs/console.log   (13:38Z)
```
The live file grew unbroken through 13:00Z. Under the previous `interval = 1`
config it would have rolled there — every earlier file on the box is hourly
(`-00` … `-11`). **No odd-hour rollover: the `interval = 2` change took.**

**14:02:11Z — after the even-hour boundary:**
```bash
find logs -maxdepth 1 -name 'console-*.log' -newermt '2026-08-19 12:11:30 UTC' -printf '%f\n'
```
```
console-2026-08-19-13.log
```
```
-rw-r--r-- 4 sgame sgame 62320922 Aug 19 20:59 logs/console-2026-08-19-13.log   (= 13:59Z)
-rw-r--r-- 3 sgame sgame   112842 Aug 19 21:02 logs/console.log                 (fresh)
```
Exactly one rollover since deploy, at **14:00:00Z**, closing a file holding
1 h 49 m of data (62.3 MB, vs ~26 MB for the old hourly files).

**Result: the rollover *cadence* is correct — every 2 h, boundary on the even hour
(14:00Z), no roll at 13:00Z. The plan's stated acceptance criterion is not.**

The plan says: *"expect every filename's trailing `-HH` to be an **even** hour"*.
The observed suffix is **13 — odd**. This is not a defect; the plan's expectation is
simply wrong about log4j2's naming rule. The archive is named for
**`rollover time − 1 hour`**, not for the start of the interval:

| Config | Rollover at | Archive named | Rule |
|---|---|---|---|
| old `interval = 1` | 08:00Z | `-07` | 08:00 − 1 h |
| old `interval = 1` | 12:00Z | `-11` | 12:00 − 1 h |
| new `interval = 2` | 14:00Z | **`-13`** | 14:00 − 1 h |

Under `interval = 2` the boundaries are even, so the names will **always be odd**:
`-13`, `-15`, `-17`, `-19`, `-21`, `-23`, `-01`, … AD-20's substantive requirement —
that `%d{yyyy-MM-dd-HH}` still yields **unique** filenames under a 2-hour interval —
**holds**: one name per 2 h, no collisions. Only the parity in the plan's assertion
is inverted.

**Recommended plan fix:** P0-5 should assert *"exactly one new `console-*.log` per
2 h, with rollovers occurring on even-hour boundaries"* (checkable from mtimes, as
done here) rather than asserting the parity of the filename suffix. As written, P0-5
would fail on a correctly-working system every single time.

### P3-8 — promoted files survive log4j2's `Delete` — **mechanism PASS; the deletion event has not yet occurred**

*(Reported here, out of numeric order, because it was verified by the same 14:00Z
rollover event as P0-5 above.)*

The rollover above gave the natural test of AD-14's central claim. State at 14:03:33Z:

```
=== inode / link count ===
4 138590082 logs/console-2026-08-19-13.log
4 138590082 logs/evidence/console-2026-08-19-13.log
4 138590082 logs/evidence/console-live-boot-20260819T121113Z.log
4 138590082 logs/evidence/console-live-alertname_…_116-20260819T122035Z.log
3 138610395 logs/console.log                                    (new inode)
3 138610395 logs/evidence/console-live-boot-20260819T121113Z-2.log
```

**This is AD-14 working exactly as documented.** Before the rollover, inode
`138590082` was the live `console.log` and the shim had hardlinked it twice (boot +
the synthetic incident). log4j2 rolled over by **renaming** that inode to
`console-2026-08-19-13.log` and creating a fresh `console.log` at inode `138610395`.
Our links followed the original inode, so they now hold the *closed* file — precisely
the post-incident tail the plan predicts, which stops growing at rollover. Link count
4 accounts for all four names on that inode; zero bytes were copied.

The shim's **pass 3 (AD-16's tail pass) fired on schedule**: rollover 14:00:00Z,
tail pass recorded at **14:02:00Z** — the specified "next rollover boundary + 120 s":
```json
"lastPromotion":{"at":"2026-08-19T14:02:00Z","tag":"tail",
 "linked":["console-live-…-20260819T122035Z-2.log"],
 "skipped":["console-2026-08-19-13.log"],"errors":[]}
"pending":{}
```
It promoted the newly-closed file, re-linked the new live inode under a `-2` name,
skipped what was already pinned, and cleared all pending deadlines. All three AD-16
passes (immediate, +5 min deferred, rollover+120 s tail) are now observed.

**Result: PASS on the mechanism — but the literal step is not yet satisfiable.** P3-8
asks for a name present in `evidence/` and **absent** from `logs/`. Both
`console-2026-08-19-11.log` and `console-2026-08-19-13.log` are currently present in
*both*, because log4j2's `Delete` has not fired: it triggers on `age = 7d` or
`ifAccumulatedFileSize = 10GB` and `logs/` is at 4.6 GB with the oldest file hours
old. The escape is proved structurally (evidence is a subdirectory; `Delete` uses
`maxDepth = 1` with a `console-*.log` glob relative to `basePath`) and the hardlink
semantics guarantee the inode survives unlinking — but **the survival-after-deletion
event itself has not been observed and cannot be without waiting ~7 days or forcing
the cap.** Recorded as unexercised rather than passed.

### P0-7 — Loki accepted the retention split

Command: `curl -s http://localhost:3100/ready` and `curl -s http://localhost:3100/config | grep -A 12 retention_stream`
Expected: `ready`; two selectors with `24h` and `720h`.
Actual — first poll returned `Ingester not ready: waiting for 15s after being
ready` (normal post-restart); retried → `ready`. Effective config:
```yaml
retention_period: 30d
retention_stream:
- period: 30d
  priority: 1
  selector: '{level=~"WARN|ERROR"}'
- period: 1d
  priority: 1
  selector: '{level=~"DEBUG|TRACE"}'
```
**Result: PASS.** Loki normalises `720h`→`30d` and `24h`→`1d`; the split is loaded
exactly as AD-6 specifies. (Behavioural proof is P0-8 at T+25 h, still deferred.)

### P1-1 — the env-driven level took (AD-7 gate) — *no test can prove this*

Command: `curl -s http://localhost:8080/actuator/loggers/com.vingame.bot`
Expected: on staging with `BOT_LOG_LEVEL=DEBUG`, `DEBUG`/`DEBUG`.
Actual: `{"configuredLevel":"DEBUG","effectiveLevel":"DEBUG"}`
**Result: PASS.** This is the AD-7 gate and the headline box-only result: compose
really does pass `LOGGING_LEVEL_COM_VINGAME_BOT`, Spring Boot's relaxed binding
applies it over `log4j2.properties`, and the `${BOT_LOG_LEVEL:-INFO}` default means
**prod with the variable unset gets INFO**. The `${env:BOT_LOG_LEVEL:-info}`
fallback in AD-7 is **not needed**.

### P1-2 — appenders survived the level override

Command: as P1-1, plus watching `grep -c '"level":"INFO"' logs/console.log` grow.
Expected: 200 from the endpoint **and** the file appender still attached.
Actual: endpoint 200; the JSON file grew by **2022 INFO lines over 300 s** and
14310+ DEBUG lines overall.
**Result: PASS** — `setLogLevel` mutated the existing LoggerConfig in place rather
than replacing it; `additivity=false` and both appenderRefs survived.

### P1-3 — INFO line rate at the new floor — **FAIL as written**

Command:
```bash
A=$(grep -c '"level":"INFO"' logs/console.log); sleep 300; B=$(grep -c '"level":"INFO"' logs/console.log)
```
Expected: `delta < 100` over 300 s.
Actual: **`delta=2022` over 300 s** (window 12:16:38Z → 12:21:38Z). DEBUG delta
4775 over the same window.
**Result: FAIL against the literal threshold — but see the breakdown.**

Post-deploy INFO lines by logger:

| Logger | INFO lines |
|---|---|
| `com.vingame.websocketparser.VingameWebSocketClient` | **3385** |
| `com.vingame.websocketparser.auth.AuthClient` | **1000** |
| `com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService` | 23 |
| `com.vingame.bot.infrastructure.observability.GroupLifecycleAggregator` | 5 |
| `com.vingame.bot.infrastructure.observability.FleetRollupLogger` | 5 |
| all other `com.vingame.bot.*` | ~26 |
| **`com.vingame.bot.*` total** | **59** |
| **non-`com.vingame.bot`** | **4402** |

**The feature's own INFO tier is 59 lines over ~11 minutes on a 155-bot fleet** —
comfortably inside the plan's intent. The threshold is missed entirely by
third-party loggers this feature does not govern. See Finding 1.

### P1-4 — the per-bot INFO classes are gone

Command:
```bash
tail -n +<first-post-deploy-line> logs/console.log | grep '"level":"INFO"' \
  | grep -cE 'initialized: game=|triggering deposit|restart requested|Successfully created bot|Bot starting in virtual thread|assigned strategy|assigned slot strategy|Setting shared EventLoopGroup'
```
Expected: `0`.
Actual: **`0` at INFO.** Per-pattern, with the DEBUG control:

| Pattern | INFO | DEBUG |
|---|---|---|
| `initialized: game=` | 0 | **155** |
| `Successfully created bot` | 0 | **155** |
| `Bot starting in virtual thread` | 0 | **155** |
| `assigned strategy` | 0 | **155** |
| `assigned slot strategy` | 0 | **20** |
| `Setting shared EventLoopGroup` | 0 | **446** |
| `triggering deposit` | 0 | 0 — *not exercised* |
| `restart requested` | 0 | 0 — *not exercised* |

**Result: PASS**, and a strong one: `155` is exactly the fleet size
(15 + 20 + 100 + 20), so the demotion is confirmed per-bot rather than by absence.
`Setting shared EventLoopGroup` at 446/0 closes the specific trap the plan calls
out (it fires on every restart and re-auth and would have cancelled the
`restart requested` demotion).

**Methodology note:** an initial run of this step returned **370** INFO hits. That
was my error, not the build's — `logs/console.log` is appended to across
deployments, so the count included pre-deploy lines from the old image. Re-scoped
from the `Starting Starter` line of the new JVM (line 12269). Anyone re-running
these greps must scope to the current JVM's region of the file.

**Honest gap:** `triggering deposit` and `restart requested` had **zero
occurrences at either level** in the observation window — no bot crossed the
low-balance threshold and the 60-minute periodic-logout cycle had not fired. Their
demotion is unproven on the box, exactly as QA's Gaps section predicted. They are
correct in the source (both `log.debug`).

### P1-5 — the aggregated replacements are present

Command: `grep -E 'bots initialized|bots auto-deposited|strategy mix' logs/console.log`
Expected: exactly one `bots initialized` line per group naming the full count; one
`strategy mix` line per group start; one `auto-deposited` line on an auto-deposit group.
Actual — **exactly one line per group, 4 groups, 4 lines**, all from
`GroupLifecycleAggregator` at INFO:
```
group 7a3716ed… (Auth test with socket): 15/15 bots initialized, game=TaiXiu Seven, type=BETTING_MINI, strategy=RANDOM
group 40fa3749… (XD game test): 20/20 bots initialized, game=Xoc Dia, type=BETTING_MINI, strategy=RANDOM
group ab81f9e6… (BOM flow test 100 (kept running)): 100/100 bots initialized, game=Xoc Dia mini, type=BETTING_MINI
group 2bf237bd… (Slot group 120): 20/20 bots initialized, game=Slot 120 (clone of 204), gid=120, strategy=FIXED
```
plus one `strategy mix` line per group (`{RANDOM=15}`, `{RANDOM=20}`,
`{RANDOM=100}`, `{RANDOM=20}`).
**Result: PASS.** 155 per-bot INFO lines replaced by 4 group lines + 4 strategy
lines — the tier-1 fold working as designed.
**Not exercised:** `bots auto-deposited` — no auto-deposit group was started.

### P1-6 — tier-2 rollup is emitting

Command: `grep 'env .* groups=' logs/console.log | tail -3`
Expected: at least one line per running environment within the last 6 minutes.
Actual — one INFO line per environment per 5-minute cycle from `FleetRollupLogger`:
```
env 3cda38f9… (097 Staging, product 097): groups=2, bots=115, connected=115, dead=0, deadGroups=0, rounds=0, staked=0
env ad4e7948… (116 Staging, product 116): groups=2, bots=40,  connected=40,  dead=0, deadGroups=0, rounds=0, staked=0
```
**Result: PASS** for cadence, coverage and shape (2 lines / 5 min for 2 active
environments — rate is a function of environment count, not fleet size, as step 5
requires).
**Caveat:** `rounds=0, staked=0` on every cycle. This is **correct**, not a drain
bug — the fleet emits **zero `endGame` messages** (see Finding 3), so no round ever
completes. It does mean **AD-8's compensating mechanism is unproven on this box**:
the justification for demoting session summaries to DEBUG is partly that tier-2
carries downsampled rounds-and-stake figures, and that has never been observed
carrying a non-zero value. Re-check on a fleet that completes rounds.

### P1-7 — tier-2 is quiet about healthy groups

Command: `grep -cE '"message":"group .* playing=' logs/console.log`
Expected: `0` while all groups are healthy; `> 0` for an unhealthy group.
Actual: **`0`**, consistent with `dead=0, deadGroups=0` in both environments.
**Result: PASS (healthy direction).** The unclean direction was **not forced** — it
would mean deliberately killing a staging group's bots, which was outside the
brief. QA covers both directions in the build (`FleetRollupLoggerTest`).

### P1-8 — session summaries demoted

Command: `grep '"level":"INFO"' logs/console.log | grep -c 'session'` vs the same at DEBUG.
Expected: `0` at INFO; `> 0` at DEBUG on staging.
Actual:

| | Count |
|---|---|
| Session summaries at INFO | **0** |
| Session summaries at DEBUG | **12** |
| `UpdateBet #N` 5 s aggregates at DEBUG | ~169 |
| `SessionAggregationService` at INFO | **1** (`flush scheduler started` — correct lifecycle) |

Sample: `BotGroup Xoc Dia/40fa3749… entered session 3205015 | sample: …TipStartGameMd5Message@…` at `"level":"DEBUG"`.
Source confirms all summary emissions are `log.debug` (`SessionAggregationService`
lines 349/351 and 471/473); only the scheduler start/stop remain INFO.
**Result: PASS** for the session-**entry** half, with a real positive control.
**Not exercised:** the session-**end** / EndGame-results half — zero EndGame
messages fleet-wide (Finding 3).

### P2-5 — auto-escalation fires — *the "one genuine observation"*

Command: `grep -E 'auto-enabled DEBUG|scoped debug escalat' logs/console.log` and
`curl -s http://localhost:8080/api/v1/logging/debug`
Expected: one INFO line naming the trigger, the `botGroupId` and the expiry; the
group present in the scope list.
Actual — **two real escalations on two different triggers, entirely unforced** (no
blackholing, no synthetic stimulus):
```
12:11:32.520 INFO ScopedDebugEscalator - scoped debug escalated for group ab81f9e6-…
             — trigger: 5 reconnects in 5m, expires 2026-08-19T12:26:32.520Z
12:14:32.645 INFO ScopedDebugEscalator - scoped debug escalated for group 7a3716ed-…
             — trigger: watchdog expiry, expires 2026-08-19T12:29:32.645Z
```
```json
{"enabled":true,"defaultMinutes":15,"maxMinutes":120,"maxScopes":50,
 "scopes":[{"botGroupId":"ab81f9e6-…","expiresAt":"2026-08-19T12:26:32.520Z"},
           {"botGroupId":"7a3716ed-…","expiresAt":"2026-08-19T12:29:32.645Z"}]}
```
**Result: PASS.** This closes QA's most substantive gap — the wiring from a real
watchdog expiry / real reconnect-rate breach into the registry, which was only
structurally covered in the build. Both trigger paths fired, each emitted exactly
one INFO line naming trigger + group + expiry, and both scopes appeared over REST
with the correct 15-minute TTL. (`bot_watchdog_expired_total` reached 200 on
`ab81f9e6` and 30 on `7a3716ed`, so the triggers were plentiful.)

### Bonus observations (not required, obtained free)

**P2-3 / sweeper cadence — observed.** `ab81f9e6`'s scope expired at 12:26:32Z and
was gone from `GET /api/v1/logging/debug` by 12:27:02Z, leaving only `7a3716ed`.
QA listed the 30 s sweeper cadence as unobservable in the build; it works.


### P2-6 — no escalation storm / the 25% duty cycle (Compliance A1) — **PASS, measured**

This was classified "Build" by QA and treated as corroboration only in an earlier
draft. It was then run to completion on the box, and it is the sharpest result of
the release.

Command — poll the scope list once a minute across the re-arm window:
```bash
P=0; A=0; N=0
while [ "$(date -u +%H%M)" -lt "1316" ]; do
  N=$((N+1))
  curl -s http://localhost:8080/api/v1/logging/debug | grep -q "ab81f9e6" && P=$((P+1)) || A=$((A+1))
  sleep 60
done
```
Expected (per the plan's amended P2-6): on a persistently sick group, ≤ 1
escalation per 60 min, and the group **absent** for at least 45 of 60 samples —
the `ttl / (ttl + cooldown)` = 15/(15+45) = **25%** duty cycle.

Actual:
```
samples=34 present=5 absent=29
escalation lines for ab81f9e6: 2
final scopes: [{"botGroupId":"ab81f9e6-…","expiresAt":"2026-08-19T13:26:33.762Z"},
               {"botGroupId":"7a3716ed-…","expiresAt":"2026-08-19T13:29:40.617Z"}]
```

| Quantity | Predicted | Observed |
|---|---|---|
| Re-arm instant | `12:11:32.520 + 15 m + 45 m` = **13:11:32.5Z** | **13:11:33.7Z** (new expiry 13:26:33.762Z) |
| Escalations in 60 min | 1 | **1** (12:11:32, then 13:11:33 — 60 m 01 s apart) |
| Duty cycle over the hour | 25.0% | **25.0%** (open 12:11:32–12:26:32, closed until 13:11:33) |
| Sampled presence | ≤ 25% | **14.7%** (5 of 34) |

**Result: PASS.** The re-arm fired within **1.2 seconds** of the predicted gate,
which is about as direct a confirmation as this mechanism admits: the cooldown is
demonstrably measured from **scope expiry**, not from the escalation. `ab81f9e6`
was flapping hard throughout (200 watchdog expiries, 32 ws-disconnect reconnects)
and still could not exceed the bound.

This is the defect Compliance amendment A1 was written about. Under the superseded
"one escalation per group per 15 min" rule the cooldown would have lapsed at the
same instant the scope did, and this exact group — parked mid-band and never
declared DEAD — would have re-escalated on the next 30 s health tick and held
scoped DEBUG open ~96% of the time, unattended, indefinitely. Observed instead:
**25.0%**. The amendment's stated property is real in the shipped artefact, and
the group-count cap (`maxScopes: 50`) is never approached.

### P3-11 — Alertmanager routing intact (AD-14)

Command: `docker compose exec alertmanager amtool --alertmanager.url=http://localhost:9093 config routes test alertname=<X>`
Expected: `EnvironmentGroupDead` → both `evidence` and `viptalk`; `BotManagerDown`
→ `evidence`, `viptalk-static-down`, `viptalk`; `EnvironmentSocketDown` → `viptalk` only.
Actual:
```
EnvironmentGroupDead  -> evidence,viptalk
BotManagerDown        -> evidence,viptalk-static-down,viptalk
EnvironmentSocketDown -> viptalk
```
**Result: PASS — all three exactly as specified.** This is the one that mattered
most: the **real Alertmanager implementation agrees with the route-walker model**
in `AlertmanagerRoutingTest`. The AD-14 consumed-alert trap — which has bitten this
repo before, and which would silently stop `EnvironmentGroupDead` reaching VipTalk
— is genuinely avoided, and the mandatory sibling route is doing its job.

### P3-1 — the shim is up and healthy

Command: `docker compose ps evidence-shim` and `GET /health`
Expected: `Up (healthy)`; JSON reporting the evidence dir, its size, `pending: {}`.
Actual: `Up 8 minutes (healthy)`. `/health` (abridged):
```json
{"logsDir":"/logs","evidenceDir":"/logs/evidence","sameFilesystem":true,
 "maxAgeDays":14.0,"maxBytes":5368709120,"sweepIntervalSeconds":3600.0,
 "rolloverHours":2.0,"deferredDelaySeconds":300.0,"tailDelaySeconds":120.0,
 "newestCount":2,"startedClean":false,"evidenceFiles":2,"evidenceBytes":37742493,
 "pending":{"boot":{"tag":"boot","firstSeenAt":…,"passes":1,"tailInSeconds":6115.9}},
 "pendingCap":64,"pendingEvicted":0,"schedulerAlive":true,
 "lastPromotion":{"at":"2026-08-19T12:16:13Z","key":"boot","tag":"deferred",
                  "linked":[],"skipped":["console-live-boot-…log","console-2026-08-19-11.log"],"errors":[]},
 "lastSweep":{"at":"2026-08-19T12:16:13Z","removed":[],"freedBytes":0,"remaining":2,"bytes":36159246}}
```
**Result: PASS**, including the specifically-requested **`schedulerAlive: true`** —
the reviewer's `next_deadline()` scheduler-kill bug is fixed and observable in
production. `sameFilesystem: true` confirms hardlinking is possible, `pendingCap:
64` / `pendingEvicted: 0` show the bounded-pending fix, and `rolloverHours: 2.0`
agrees with the log4j2 config.

`pending` is not `{}` as the plan predicts, because the shim correctly
retro-promoted on an unclean start — see the P3-9 note below.

### P3-2 — the evidence dir exists and is writable by the shim

Command: `ls -ld logs/evidence; stat -c '%u:%g' logs/console.log logs/evidence`
Expected: a directory owned by the same uid:gid as `logs/console.log`.
Actual:
```
drwxr-xr-x 2 sgame sgame 128 Aug 19 19:16 logs/evidence
1006:1007 logs/console.log
1006:1007 logs/evidence
```
**Result: PASS.** Created by the shim itself via `os.makedirs`, as AD-13 requires —
`deploy.sh` was **not** modified to create it. Ownership matches, so no `chown` and
no sudo were needed (there is none on Bot-1).

### P3-5 — promoted files are not re-ingested by promtail

Command:
```bash
curl -sG 'http://localhost:3100/loki/api/v1/query_range' \
  --data-urlencode 'query={job="bot-manager",filename=~"/logs/evidence/.*"}' --data-urlencode 'limit=1'
```
Expected: `"result":[]`.
Actual: `{"status":"success","data":{"resultType":"streams","result":[],…}}`
Control (same query for `filename="/logs/console.log"`): returns a populated stream
with `level`/`botGroupId`/`environmentId` labels.
**Result: PASS.** AD-18 holds — promtail's non-recursive `__path__: /logs/*.log`
excludes the subdirectory, so promoted evidence costs **zero** additional Loki
bytes. The control proves the query itself is sound rather than vacuously empty.

### P3-7 — the deferred pass runs

Expected: at T+6 min a file count `≥` the earlier count with no duplicate names,
and `/health` showing the +5 min pass recorded.
Actual — observed on the shim's own boot incident: promotion at **12:11:13Z**
(tag `boot`), deferred pass recorded at **12:16:13Z** (tag `deferred`) — exactly
+5 min — with `"linked":[]` and `"skipped":["console-live-boot-…","console-2026-08-19-11.log"]`.
**Result: PASS.** The deferred pass ran on schedule and was correctly idempotent:
nothing re-linked, nothing duplicated, both already-pinned files skipped.

### P3-3 — a synthetic alert promotes files *(accepted substitute)*

No live incident occurred, so a synthetic Alertmanager POST was used — explicitly
sanctioned in the brief and the same method used to verify locally.

Command:
```bash
docker compose exec evidence-shim python -c '…POST {"status":"firing","groupLabels":
 {"alertname":"EnvironmentGroupDead","product":"116","environmentId":"test-env"},…}
 to http://127.0.0.1:8080/alertmanager'
```
Expected: `200`, then `≥ 2` files in `logs/evidence/`.
Actual: **HTTP 200**; file count 2 → **3**, the new one named
`console-live-alertname_EnvironmentGroupDead_environmentId_test-env_product_116-20260819T122035Z.log`.
**Result: PASS.** The incident key is the `groupLabels` map rendered canonically,
exactly as AD-17 specifies, and the live file is linked under a distinct
per-incident name so it does not collide across incidents.

### P3-4 — they are HARDLINKS, not copies *(build-covered; re-proved on the box)*

Command: `stat -c '%h %i %n' logs/console.log logs/evidence/console-live-*.log …`
Actual:
```
2 138590082 logs/console.log
2 138590082 logs/evidence/console-live-boot-20260819T121113Z.log
2 138589493 logs/console-2026-08-19-11.log
2 138589493 logs/evidence/console-2026-08-19-11.log
```
**Result: PASS** — identical inodes, link count 2 on both pairs. `os.link`, not
`cp`; **zero additional blocks** at exactly the moment disk is the constraint.
(After the synthetic alert the live inode's link count rose to 3, as expected.)

### P3-6 — promotion is idempotent and coalesced *(build-covered; re-proved)*

Command: repeat the P3-3 POST three more times, then compare counts and `pending`.
Actual: three further `200`s; file count **unchanged at 3**; `/health` shows
**exactly one** pending entry for that key with `"passes": 4` and
`"deferredInSeconds": 297.5` — the deadline **refreshed**, not stacked, and
`pendingEvicted: 0`.
**Result: PASS.** AD-17's "one pending deadline per incident key" confirmed against
a real redelivery pattern.

### P3-9 — unclean start retro-promotes (AD-21) *(observed naturally)*

Not run as scripted (no `docker compose kill` needed): `logs/evidence/` did not
exist before this deploy, so the shim's **first ever boot was by definition
unclean**. `/health` reports `"startedClean": false`, and it promoted the newest
closed file plus the live file at 12:11:13Z tagged `boot` — 2 files, before any
alert had ever been received.
**Result: PASS** — AD-21's full-stack-failure path works, verified by the shim's
own genuine cold start rather than by simulation.

### Deferred / not run

- **P0-8** (retention split actually bites) — T+25 h by design. Run after
  2026-08-20 13:00Z.
- **P0-9** (Loki growth) — T+24 h and T+7 d by design. Baseline recorded above.
  **Read Finding 2 before trusting the delta.**
- **P3-8's deletion event** — the mechanism is proved (see above); log4j2's
  `Delete` has not fired, and will not for ~7 days at current volume.
- **P2-1 / P2-2 / P2-4, P1-4's deposit+restart patterns, P1-7's unclean
  direction, P3-10** — QA proved these in the build; the box-side deltas need a
  fleet state that did not occur or a deliberate mutation of staging that was
  outside the brief. Specifically:
  - **P2-4 could not be exercised: no bot was below spin cost.**
  - **P1-4's `triggering deposit` / `restart requested`**: zero occurrences at
    either level — no low-balance crossing, and the 60-minute periodic-logout
    cycle had not fired.
  - **P1-7's unclean direction** would have meant killing a staging group.
  - **P1-8's EndGame half and AD-8's `rounds`/`staked` drain**: no round ever
    completes on this fleet (Finding 3).
  (**P2-3 and P2-6 were *not* left to the build — both were observed on the box;
  see above.**)
- **P3-12** — green in this build (`AlertmanagerRoutingTest` ran as part of the
  1838).

## Findings

### Finding 1 — the per-bot INFO flood survives, one logger prefix outside the feature's scope

`rootLogger.level = info` governs `com.vingame.websocketparser.*`, and the
ws-parser library logs **per bot, at INFO**. On a 155-bot fleet over ~11 minutes:

| Message (normalised) | Count |
|---|---|
| `AUTH [...]` | 590 |
| `Client ws-<bot>: WebSocket handshake completed` | 474 |
| `Client ws-<bot>: Connected to server` | 474 |
| `Client ws-<bot>: Resolving authentication token` | 425 |
| `User <bot>: Agency token: <n>-<id>` | 400 |
| `Client ws-<bot>: Closed connection` | 374 |
| `Authenticating user <bot> at https://apigw-…` | 360 |
| `Client ws-<bot>: Authenticated with token <n>-<n>` | 170 |

This is precisely the message class the plan's Findings section enumerates and
Phase 1 folds — same per-bot shape, same fleet-size-proportional rate — but the
tier model, `logger.app.level` and `LOGGING_LEVEL_COM_VINGAME_BOT` are all scoped
to `com.vingame.bot`, so none of them touch it. At the plan's stated target of
20–30k bots this extrapolates to roughly **190× the observed rate**, which is the
same order as the 46–124 GB/day the plan set out to eliminate.

Two secondary observations on the same lines:
- `User <bot>: Agency token: <n>-<id>` logs **token material at INFO**.
- The `AUTH` lines carry raw **ANSI colour escapes** (`[36m`) into the JSON
  `message` field, so the library is emitting terminal formatting into structured logs.

**I did not fix this** — it is a design decision for the architect, not a release
action, and the branch is not wrong for having scoped itself to `com.vingame.bot`.
The fix is config-only and needs no rebuild: a `logger.wsparser` entry at WARN in
the **bind-mounted** `logging/log4j2.properties`. Recommend an architect pass before
prod, since prod is where INFO becomes the default and this becomes the dominant tier.

### Finding 2 — every redeploy re-ingests the log directory into Loki, and 720 h retains it 4.3× longer

`loki-data` grew **1,016,059,871 B → 3,240,742,886 B (+2.2 GB) in 17 minutes**
after the deploy. Cause: `promtail-config.yml:5-6` sets
`positions.filename: /tmp/positions.yaml`, and the promtail service in
`docker-compose.yml` mounts **only** `./logs:/logs:ro` and the config file — there
is **no volume for the positions file**. Every `docker compose down` discards it,
so promtail re-reads all ~12 retained `console-*.log` files (~26 MB each) from byte
0 and re-pushes them. Loki's log confirms it, both as rejections and as accepted
duplicates:
```
msg="write operation failed" details="entry for stream '{…filename=\"/logs/console-2026-08-12-12.log\"…}'
  has timestamp too old: 2026-08-12T12:12:50Z…"
msg="write operation failed" details="Ingestion rate limit exceeded for user fake
  (limit: 4194304 bytes/sec) while attempting to ingest '2577' lines totaling '1041935' bytes…"
```
This is **pre-existing and not introduced by this branch**, but it interacts badly
with what this branch ships, in two ways:

1. **It corrupts P0-9's method.** The gate takes `T+7 d − T+24 h` as the daily
   rate. Any redeploy inside that window injects ~2 GB of non-organic growth and
   inflates the projection.
2. **The 168 h → 720 h change amplifies it 4.3×.** That duplicate data used to age
   out in a week; it now persists for a month, and accumulates once per redeploy.

Recommended follow-up (one line, not applied by me): give promtail a named volume
for its positions file, e.g. `- promtail-positions:/tmp`. This is cheap, removes
the duplication entirely, and should land **before** P0-9 is used to judge the 720 h.

### Finding 3 — the staging fleet completes no rounds, so several checks are unexercisable

`bot_messages_total` across the whole fleet: `subscribe` (3 series), `startGame`
(1 series, 200 messages), `updateBet` (1 series, 5201 messages), and **zero
`endGame`**. Rounds start and take bets but never settle — consistent with the
known "Xoc Dia never ends rounds" anomaly on TIP.

Consequences, all recorded above as *not exercised* rather than passed:
- P1-8's EndGame-results half has no data.
- AD-8's compensating tier-2 `rounds`/`staked` drain is permanently `0`, so the
  stated justification for demoting session summaries is **unverified in
  production conditions**.
- P2-4 (spin-cost spam) had no bot below spin cost.

None of these is evidence of a defect in the branch; all need a fleet that
completes rounds.

## Expected behaviour changes — sanity-checked, all as predicted

- `docker logs` now shows **INFO+ only** (0 DEBUG on console, 14310 DEBUG in the
  JSON file). Confirmed.
- Rollover moved to **every 2 h on even-hour boundaries**. Confirmed — one roll at
  14:00:00Z, none at 13:00:00Z. (Archive name is the odd hour; see P0-5.)
- **`logs/evidence/` appeared** on the shim's first run, containing 2 hardlinked
  files before any alert was received.
- Log volume on Bot-1 is broadly unchanged, as expected: staging sets `DEBUG`, and
  the per-bot decision flood was already gone before this release.

## Notes for the plan / docs (no code implications)

1. **U-3's command is stale** — `GET /api/v1/bot-group/` does not exist (405);
   `BotGroupController` exposes `POST /{envId}/filter`. `CLAUDE.md`'s REST table
   documents the same non-existent endpoint.
2. **P0-2's expected thread name** is `Log4j2-AsyncApp…`, not `AsyncAppender-R`,
   on log4j2 2.24.1.
2b. **P0-5 asserts the wrong filename parity.** Archives are named
   `rollover − 1 h`, so a 2-hour interval yields **odd** suffixes (`-13`, `-15`, …).
   Assert "one new file per 2 h on even-hour boundaries" (from mtimes) instead.
3. **P1-4 needs a scoping caveat** — `logs/console.log` spans deployments; the grep
   must be scoped to the current JVM's region or it reports pre-deploy lines.
4. **P1-3's `< 100` threshold is unachievable** while the root logger leaves
   ws-parser at INFO (Finding 1). Either scope the step to `com.vingame.bot` or
   make bounding the root logger part of the feature.

## State left on the box

- 10 containers up; bot-manager, mongo, viptalk-shim, evidence-shim healthy.
- 4 bot groups running (155 bots), same as before the deploy.
- `logs/evidence/` holds **6 promoted hardlinks** (216.7 MB by `du`, but see AD-19:
  hardlinks double-count against the live files) + `.pending.json` (now `{}`) +
  `.promoted.json`. **Three are synthetic-test artefacts** from P3-3/P3-6 under the
  incident key `alertname=EnvironmentGroupDead,environmentId=test-env,product=116`;
  they are harmless (hardlinks consume no extra blocks) and age out on the 14-day
  sweep, but delete them if a clean evidence dir is wanted.
- One scoped-DEBUG scope may still be live from the genuine auto-escalations; both
  carry a 15-minute TTL and expire on their own.
- Config backups on the box tagged `20260819-190838`.
- `secrets.env` now contains `BOT_LOG_LEVEL=DEBUG` (staging only).
