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

---
---

# Release — LOG_VOLUME_TIERING **Phase 4**

Mode: bot **+ full config payload** (not a plain `mode=bot` deploy — see Ship)
Branch: `feature/log-volume-tiering` @ `09a09ae` (built directly, **no merge, no push**)
Image: `vingame-bot:latest`, `sha256:99681eb09dfd49e35837f693132be349fefe83badaeb12c6a05cf6b98c76d7e2` (built 2026-08-20T11:43:02Z–11:43:31Z UTC)
Target: **Bot-1 staging** (`/home/sgame/bot-java`) — explicitly not prod
Date: 2026-08-20T11:41:52Z – 12:25Z UTC

## Verdict

**PASS.** The two-track split does what Phase 4 was created to do.

**The headline, P1-3 (as P4-4): INFO reaching Loki fell from 2,022 lines / 300 s to
`3` lines / 300 s.** Zero of them are ws-parser. The `< 100` threshold that was
unachievable on 2026-08-19 now passes with three orders of magnitude of margin, and
it passes for the stated reason — ws-parser moved to track 2, where it produced
3,052 lines in the same 300 s.

Everything else in Phase 4 verified on the box: both appender graphs built (2 async
threads), track 1 carries no DEBUG/TRACE even with `BOT_LOG_LEVEL=DEBUG`, Loki holds
track 1 and only track 1, the promtail positions volume eliminated the redeploy
re-ingest **completely** (`loki-data` *shrank* 524,724 B across a full
`down && ./deploy.sh`), the evidence shim promotes both tracks by hardlink with
independent per-track ages, and the Phase 4d queue meters and `LogQueueSaturated`
rule are live.

**0a–0c:** 0a and 0b ran clean. **0c was deliberately skipped** (see below).

Four things to read before shipping this anywhere else:

1. **Gate 2 (P4-6) is the operative pre-ramp gate, and it binds much earlier than
   the plan's own worked example suggests** — at **~6,300 bots** on this staging
   profile, ~17,700 prod-like, versus Gate 1's ~17,000 / ~47,800. The plan's claim
   that the ceiling "passes narrowly" at 30k bots does not hold against measurement:
   at 30k it is **51.8% of free disk prod-like and 59.6% staging-like**, i.e. a
   narrow *fail* on both. Details and arithmetic below.
2. **0a bounded, but did not eliminate, the re-ingest exposure.** The 157 archives
   were moved out of reach. The **live `console.log`** could not be moved (the JVM
   holds it; it is track 1's live file) and it still carried **42,358 ws-parser
   lines and 76,895 DEBUG lines** of pre-Phase-4 content, which promtail re-read.
   Net cost measured at **+199,311 bytes** of `loki-data`, because Loki deduplicated
   entries it already held. See Finding P4-A.
3. **Finding 3 persists, unchanged: the staging fleet still emits zero `endGame`.**
   `StartGame` fires (43 in track 2) and `UpdateBet` aggregates fire (647), but no
   round completes. P1-8's EndGame half, AD-8's drain and P2-4 are **still
   unexercised — not passed**.
4. **P2-6's 60-minute duty cycle could not be measured this session** because I
   restarted the JVM four times for other verification steps and the escalation
   cooldown is in-memory. The per-JVM evidence is clean (≤ 1 escalation per group
   per JVM lifetime) but that is a weaker statement.

## Build

- `mvn clean install`: **PASS** (46.4 s, JDK 21.0.2)
- Test totals — **1862 tests, 0 failures, 0 errors, 0 skipped**, matching the
  briefed figure exactly (+24 over Phase 0–3's 1838):

  | Module | Tests |
  |---|---|
  | bot-api | 125 |
  | bot-strategies | 111 |
  | bot-messages | 136 |
  | bot-engine | 416 |
  | bot-app | **1074** (was 1050) |
  | **Total** | **1862** |

  0 skipped again matters: `EvidenceShimSelfTestRunnerTest` and
  `VipTalkShimSelfTestRunnerTest` `assumeTrue(python3Available())` and would
  silently no-op. They ran.
- `docker build --no-cache --platform linux/amd64`: **PASS** (29 s)
- `docker save`: **PASS** — **395,984,384 bytes**

Git hygiene: **nothing committed, nothing pushed, no merge.** Built from the working
tree at `09a09ae`. `deploy.sh` left uncommitted and shipped from the working tree.
The staged `TaiXiuMessages/*.js` deletions were **left exactly as found** —
untouched, not committed, not restored. `docs/reviews/VIPTALK_ALERTING_V2/release.md`
left untouched.

## Ship

Backups taken on the box first, tagged **`20260820-114356`**: `docker-compose.yml`,
`promtail-config.yml`, `logging/log4j2.properties`, `evidence-shim/shim.py`,
`alertmanager/alertmanager.yml`, `loki/loki-config.yaml`, `prometheus/alerts.yml`,
`secrets.env`, `deploy.sh`.

All ten artefacts transferred and **verified by SHA-256 on both ends — all ten
matched**:

| File | SHA-256 (first 16) | Result |
|---|---|---|
| `bot.tar` | `dcbcbdb8bbe29e85` | PASS |
| `docker-compose.yml` | `d76d6ec1db01a4c8` | PASS |
| `deploy.sh` (working-tree copy) | `8eccc6bffb87286c` | PASS |
| `promtail-config.yml` | `0aca0a53c3b95ea0` | PASS |
| `logging/log4j2.properties` | `d2d51b36ecabf8a4` | PASS |
| `evidence-shim/shim.py` | `4d62d8d7d93b798a` | PASS |
| `alertmanager/alertmanager.yml` | `9e0ed8e3b45be6b5` | PASS (unchanged from Phase 3) |
| `loki/loki-config.yaml` | `352306427802b1c5` | PASS |
| `prometheus/alerts.yml` | `9080446871225a51` | PASS (new `LogQueueSaturated`) |
| `secrets.env.example` | `fc1a5efc4d4ff39f` | PASS |

`secrets.env` on the box was **not** modified for the deploy: `BOT_LOG_LEVEL=DEBUG`
was already present from 2026-08-19, and `WSPARSER_LOG_LEVEL`,
`EVIDENCE_DETAIL_MAX_AGE_DAYS`, `EVIDENCE_MAX_BYTES` were all left absent so the
shipped defaults apply. Confirmed in pid 1's environment:
`LOGGING_LEVEL_COM_VINGAME_BOT=DEBUG`, `LOGGING_LEVEL_COM_VINGAME_WEBSOCKETPARSER=INFO`.

## Pre-deploy steps 0a–0c

### 0a — relocate the pre-Phase-4 archives — **RAN CLEAN**

Run with the stack **down**, which is stricter than the plan's ordering (it
guarantees no promtail was running during the move, rather than merely starting
after it).

```
before: console-*.log count = 157
after:  console-*.log count = 0
moved:  pre-phase4 count    = 157
moved bytes = 4,544,990,804   (4.54 GB)
```

- **157 archives moved**, `logs/console-*.log` confirmed **empty** afterwards.
- Range: `console-2026-08-13-10.log` → `console-2026-08-20-09.log` — **exactly 7
  days**, which independently confirms amendment **C1** (the original text said 14).
- `logs/console.log` (51,806,508 B) correctly **left in place** — the JVM holds it
  open and it is track 1's live file.
- `logs/pre-phase4/` verified invisible to promtail post-deploy: a Loki query for
  `filename=~"/logs/pre-phase4/.*"` returns `"result":[]`.

**What 0a prevented, concretely:** 4.54 GB across 157 files of ws-parser INFO —
agency-token material included — would have been re-ingested into Loki on a fresh
720 h clock. It was not offered to promtail at all.

### 0b — positions file holds one entry — **PASS**

```
positions:
  /logs/console.log: "51839175"
```

Exactly **one** entry, the live file, non-zero offset. Not empty (volume is
mounted), not more than one (0a held).

### 0c — Loki delete request — **DELIBERATELY SKIPPED**

Skipped on instruction. Recording what it would have covered so the decision stays
cheap to make later:

- **Scope it would have covered:** `{job="bot-manager"} |= "Agency token"` over the
  pre-Phase-4 window. The material still in Loki is the pre-deploy content of the
  live `console.log` plus everything ingested before 2026-08-20T11:46:12Z.
- **The standing exposure ages out on its own:** Loki's `retention_period` is
  **720 h (30 d)**, and the `retention_stream` overrides are `WARN|ERROR → 30d`,
  `DEBUG|TRACE → 1d`. ws-parser's material is **INFO**, so it falls under the
  default 720 h. The newest such lines were written at **2026-08-20T11:46:12Z**,
  so **the last of it expires on 2026-09-19**. DEBUG/TRACE residue expires
  **2026-08-21**.
- **Nothing needs reverting and nothing needs configuring.** Per amendment C4,
  `compactor.retention_enabled: true` is already shipped and `deletion_mode`
  defaults to `filter-and-delete` under `limits_config`, so the delete API is live
  on the current config. Verified in passing: Loki's `/config` shows the two
  `retention_stream` entries and the compactor is demonstrably running (P0-8 below).
  0c can therefore be run at any later date with no deploy and no restart.

### `mkdir -p logs/detail` — **RAN**

`drwxrwxr-x 2 sgame sgame` — created as the host `sgame` uid before
`docker compose up`, so Docker never got the chance to create a root-owned
directory at the bind source.

## Deploy

- `docker compose down`: **PASS** (11:46:12Z)
- `docker image rm vingame-bot:latest`: **PASS**
- `docker load -i bot.tar`: **PASS**
- `./deploy.sh` (used instead of bare `compose up -d` so the `secrets.env` → `.env`
  merge runs): **PASS** (11:46:27Z → 11:46:41Z)

**Downtime: ~29 s** (11:46:12Z → 11:46:41Z). App fully up with all 4 bot groups
restarted by **11:46:49Z**, healthy by **11:47:15Z**. `ConfigurationException` count
in the bot-manager log: **0** — the `.type = AppenderRef` trap was avoided.

Four further `deploy.sh` cycles were run later as *part of verification*
(P4-8b, P4-10 set, P4-10 restore) — each ~25–30 s.

## Smoke test — whole stack

`docker compose ps` — **all 10 services Up** at every check, including the final
state at 12:24:30Z:

| Service | Status |
|---|---|
| bot-manager | Up **(healthy)** |
| mongo | Up **(healthy)** |
| viptalk-shim | Up **(healthy)** — `/health` → 200 |
| evidence-shim | Up **(healthy)** — `/health` → 200, **per-track** body |
| alertmanager | Up — `/-/healthy` → `OK` |
| grafana | Up — `/api/health` → 200 |
| loki | Up — `/ready` → `ready` (5/5 polls), `/metrics` → 200 |
| prometheus | Up — 2/2 active targets `up` |
| promtail | Up — positions file advancing |
| node-exporter | Up — feeding P0-6 |

- Spring Boot ready: **PASS** — `Started Starter in 8.027 seconds`
- Auto-start: **PASS** — `Bot Manager startup complete. 4 bot groups running`
- **U-1**: **PASS** — all services, observability stack re-checked per the
  single-Compose caveat
- **U-2** `/actuator/health` → **200**: **PASS**
- **U-3 (corrected form)** `GET /api/v1/environment/` → **200**: **PASS**. The
  plan's original `GET /api/v1/bot-group/` is still 405 and still stale.

> **One cosmetic note, not a fault.** Loki's `/ready` returns
> `Ingester not ready: waiting for 15s after being ready` for a short window after
> each restart. Polled 5× at the end: `ready` every time. Queries served correctly
> throughout. This is Loki's own readiness debounce, not a Phase 4 effect.

### evidence-shim `/health` — per-track, as required

```json
"detailDir": "/logs/detail", "detailDirPresent": true,
"maxAgeDays": 14.0, "detailMaxAgeDays": 3.0, "maxBytes": 12884901888,
"evidenceByTrack": { "aggregate": {"files": 13, "bytes": 202126705},
                     "detail":    {"files": 5,  "bytes": 16501140} },
"sourceFilesByTrack": { "aggregate": 2, "detail": 2 }
```

`detailMaxAgeDays: 3` and `maxBytes: 12 GiB` confirm the Phase 4c defaults landed.

## Plan verification — Phase 4 steps

### P4-1 — both tracks exist and both appender graphs built — **PASS**

Command: `ls -l logs/console.log logs/detail/detail.log`; async thread count; config grep
Expected: both files present and non-empty; thread count **2**; config grep `> 0`
Actual:
```
-rw-r--r-- logs/console.log        51839175
-rw-r--r-- logs/detail/detail.log   1571733
async thread count: 2      (both named "Log4j2-AsyncApp", truncated at 15 chars)
asyncdetail cfg lines: 6
ConfigurationException count: 0
```
Re-confirmed after all four later redeploys: still `2`.
Result: **PASS**

### P4-2 — ws-parser is in track 2 and nowhere else — **PASS**

Window: **300 s, 11:48:44Z → 11:53:44Z**, N=155 bots.
Command: mark both files, wait 300 s, classify only the new lines
Expected: track1 `0`, track2 `> 1000`, console `0`
Actual:
```
track1 new ws-parser lines: 0
track2 new ws-parser lines: 3052
docker-logs ws-parser:      0
```
Result: **PASS**

> **Rate note, stated with its window.** 3,052 lines / **300 s** = **10.17
> lines/s** at 155 bots. Amendment B1's corrected expectation is 6.6 lines/s
> (≈ 2,000 per 300 s). Measured is **1.54× B1**. The most likely reason is on the
> box and visible below: this fleet is in continuous watchdog-reconnect churn
> (230 reconnect cycles in the same 300 s), and reconnects are exactly what
> ws-parser logs at INFO. B1 is not wrong for a settled fleet; it under-predicts a
> churning one. The `> 1000` threshold clears either way.

### P4-3 — track 1 carries no DEBUG even with `BOT_LOG_LEVEL=DEBUG` — **PASS**

Same 300 s window. Run on **staging with the logger at DEBUG**, so not vacuous —
confirmed `com.vingame.bot` = `{"configuredLevel":"DEBUG","effectiveLevel":"DEBUG"}`.
Expected: `0` and `> 0`
Actual:
```
track1 new DEBUG: 0
track1 new TRACE: 0
track2 new DEBUG|TRACE: 5055
```
Result: **PASS** — this is the assertion that keeps Loki clean, and both halves hold.

### P4-4 — INFO reaching Loki, by logger. **REPLACES P1-3.** — **PASS**

**This is the headline check. Raw numbers, window stated explicitly.**

Window: **300 s, 11:48:44Z → 11:53:44Z**. Fleet: **155 bots, 4 running groups**
(BOM flow test 100 = 100, Auth test with socket = 15, Slot group 120 = 20,
XD game test = 20). Instance: **staging, `BOT_LOG_LEVEL=DEBUG`,
`WSPARSER_LOG_LEVEL` unset → INFO**.

```
INFO delta over 300 s = 3          (expectation: < 100)

per-logger histogram of those 3 INFO lines:
      2 com.vingame.bot.infrastructure.observability.FleetRollupLogger
      1 com.vingame.bot.infrastructure.observability.ScopedDebugEscalator

ws-parser INFO among them = 0      (hard assertion: exactly 0)
```

Comparison against the 2026-08-19 release, **both normalised to 300 s**:

| Measure | 2026-08-19 | 2026-08-20 | Change |
|---|---|---|---|
| INFO lines / 300 s reaching track 1 | **2,022** | **3** | −99.85% |
| of which `com.vingame.bot.*` | 59 | 3 | — |
| of which `com.vingame.websocketparser.*` | 1,963 | **0** | eliminated |
| ws-parser lines / 300 s (track 2, new) | n/a | 3,052 | relocated, not lost |

> The 2026-08-19 report quoted "4,402 ws-parser lines" next to a 300 s figure; that
> 4,402 was measured over a **~11-minute** window, and Phase 4's B1 amendment was
> built on the mix-up. The 1,963 above is that same data re-expressed on the 300 s
> basis (4,402 ÷ 660 s × 300 s). **Every rate in this report names its window.**

**Reverse-misroute half (AD-27), same window:** `FleetRollupLogger` in
`logs/detail/detail.log` = **3** (expected `> 0`). Our own INFO appears in **both**
files, so track 2 is self-contained. `logger.app.appenderRef.detail.ref` is wired.

Result: **PASS**

> **What actually dominates Loki now, which P4-4 does not measure.** Of the **463**
> total new track-1 lines in that 300 s, **460 were WARN** and 3 INFO; 0 ERROR.
> All 460 are two paired messages from a fleet in permanent watchdog churn:
> ```
>     230 com.vingame.bot.domain.bot.core.Bot
>     230 com.vingame.bot.domain.bot.core.BettingMiniGameBot
>     ...  "Bot <name>: no game message in <n>s — triggering full reconnect"
>     ...  "Bot <name>: full reconnect triggered — watchdog timeout"
> ```
> This is **pre-existing and outside Phase 4's scope** — it is the same
> zero-`endGame` condition as Finding 3 — but it is now the *whole* Loki bill, and
> it is 153× the INFO tier. Total track-1 volume is still tiny (194,306 B / 300 s =
> **648 B/s**), so nothing needs doing today. Worth knowing that Phase 4 has made
> this the next thing that would matter.

### P4-5 — Loki holds track 1 and only track 1 — **PASS (scoped post-deploy)**

**First run, over the plan's default 1-hour window, returned hits on queries 1 and
2.** Inspected rather than reported as a failure: the timestamps were
`11:46:07.681Z` — **before** the 11:46:12Z `down`. Those are pre-Phase-4 lines from
the live `console.log`'s history, re-read by the new promtail. See Finding P4-A.

Re-run scoped to **11:48:00Z → now** (post-deploy, new build only):
```
1. {job="bot-manager"} |= "websocketparser"        -> "result":[]     PASS
2. {job="bot-manager", level="DEBUG"}              -> "result":[]     PASS
2b.{job="bot-manager", level="TRACE"}              -> "result":[]     PASS
3. {job="bot-manager", filename=~"/logs/detail/.*"}-> "result":[]     PASS
4. {job="bot-manager", level="INFO"}               -> NON-EMPTY       PASS (control)
5. {job="bot-manager", filename=~"/logs/evidence/.*"} -> "result":[]  PASS (P3-5)
6. {job="bot-manager", filename=~"/logs/pre-phase4/.*"} -> "result":[] PASS (0a proof)
```
The non-vacuity control (4) is non-empty, so the four empties are real.
Result: **PASS**

### P4-6 — track 2's real write rate — **PRE-RAMP GATE, MEASURED, PASSES NOW**

Window: **600 s, 11:55:48Z → 12:05:48Z**. **N = 155 bots.**
Instance profile: **staging** — `BOT_LOG_LEVEL=DEBUG`, `WSPARSER_LOG_LEVEL=INFO`.
The plan requires this be recorded before comparing: staging expects
≈ 5,000–6,000 B/s, prod-like INFO ≈ 1,650 B/s.

```
track2 delta = 3,791,033 B over 600 s
R = 6,318 B/s
per-2h-file = 45,489,600 B   (43.38 MiB)
per-day     = 545,875,200 B  (520.6 MiB)
API control: /api/v1/environment/ -> 200
```

**R = 6,318 B/s is just above the plan's 5,000–6,000 staging band**, consistent
with the same reconnect churn that lifted P4-2 to 1.54× B1. Not a misroute: P4-5
proves nothing is being written to the wrong track.

> Track 1's rate could **not** be taken from this window — `console.log` rolled over
> mid-window, so the naive delta is negative (−86,403 B/s). Use the 300 s window
> figure instead: **194,306 B / 300 s = 648 B/s** (55,987,200 B/day, 53.4 MiB/day).

**Gate 1 (write rate) — does not bind.**

| Threshold | Value | Factor over measured | Binds at |
|---|---|---|---|
| per-2h file > 5 GB | 45.49 MB now | 109.9× | **~17,036 bots** |
| per-day > 60 GB | 545.9 MB now | 109.9× | **~17,036 bots** |

Both thresholds reduce to the same rate — **6,318 × 109.9 = 694,300 B/s ≈ 694 KB/s**
— which **confirms compliance's correction exactly**. Prod-like INFO: ws-parser is
3,052 of 8,570 track-2 lines (35.6%), so ≈ 2,249 B/s → Gate 1 binds at
**~47,800 bots**. (Compliance projected ~65,000 prod-like / ~20,000 with DEBUG;
measured is stricter on both because this fleet's churn inflates ws-parser.)
**Gate 1: PASS, not binding.**

**Gate 2 (reconciled ceiling vs this box's free disk) — added by C2, run as asked.**

```
df -B1 --output=avail .  ->  72,412,446,720 B  = 67.44 GiB free
ceiling = 10 GiB (track 1 cap) + 10 GiB (track 2 archive cap)
        + 12 GiB (evidence, EVIDENCE_MAX_BYTES=12884901888)
        + one projected live 2 h detail file
```

| Scale | live 2 h detail file | Ceiling | % of free | Gate |
|---|---|---|---|---|
| **155 bots (now)** | 43.38 MiB | **32.04 GiB** | **47.5%** | **PASS** |
| 6,293 bots (staging profile) | 1.72 GiB | 33.72 GiB | 50.0% | **binds here** |
| 17,678 bots (prod INFO profile) | 1.72 GiB | 33.72 GiB | 50.0% | **binds here** |
| 30,000 bots, prod INFO | 2.92 GiB | 34.92 GiB | **51.8%** | **FAIL** |
| 30,000 bots, staging DEBUG | 8.20 GiB | 40.20 GiB | **59.6%** | **FAIL** |

**Gate 2 passes today at 47.5% and is the binding gate — it trips ~2.7× earlier
than Gate 1.** That is exactly why C2 added it.

> **Correction candidate for the plan.** P4-6's Gate 2 text says the ceiling is
> "~34.3 GiB at 30k, i.e. 48–51%: it passes, and it passes narrowly at the top".
> Against measurement it is **51.8% prod-like and 59.6% staging-like at 30k** — a
> narrow **fail**, not a narrow pass. The 155-bot end of the plan's range is
> confirmed (32.04 vs the plan's ~32.2 GiB); it is the 30k end that is optimistic,
> because the plan's live-file term (~2.3 GiB) is derived from B1's rate and the
> measured rate is higher. Remedy is unchanged and cheap: **cut a cap** (track 2's
> `10GB` and/or evidence's `12GB`, one line each in bind-mounted files). Note again
> that `WSPARSER_LOG_LEVEL=WARN` lowers *realized* usage and the live-file term but
> **not** the nominal ceiling.
>
> `Prod-Bot` remains **unmeasured** (P0-6 never run there), so this gate is
> **unevaluated on prod**.

### P4-7 — track 2's retention is anchored where it cannot reach anything else — **PASS**

```
appender.detail.strategy.delete.basePath = /app/logs/detail
appender.detail.strategy.delete.maxDepth = 1
appender.detail.strategy.delete.ifFileName.glob = detail-*.log
appender.detail.strategy.delete.ifAny.ifLastModified.age = 12h
appender.detail.strategy.delete.ifAny.ifAccumulatedFileSize.exceeds = 10GB
```
All five as specified. Track 1's `Delete` confirmed at **`age = 14d`** (AD-26's
7d → 14d landed). Both rollover intervals confirmed **equal** (`interval = 2`,
`modulate = true` on both), which AD-22 and AD-28 depend on.

Archive count after the first boundary: **1** (expected 1; steady state ≤ 7 needs
12 h and cannot be reached in this session). Track 1 archive count: **1** — 0a
emptied that directory, so this is a fresh, correct start.
Result: **PASS** (the ≤ 7 steady state remains unobservable within one session)

### P4-8 — promtail no longer re-ingests on restart (AD-31) — **PASS, both halves**

**Half 1 — `docker compose restart promtail`** (11:58:50Z, +180 s):
```
positions before: /logs/console.log: "52133468"     (non-zero, as required)
loki-data before: 454,886,612
loki-data after:  468,453,529
growth = 13,566,917 B  (12.9 MiB, expected < 50 MB)      PASS
"timestamp too old" count: 0                             PASS
positions after:  /logs/console.log: "52229997"    (advanced, not reset)
```

**Half 2 — full `docker compose down && ./deploy.sh`** (12:07:02Z → 12:07:25Z), which
the plan calls "the case that actually bit":
```
loki-data before: 511,366,863
loki-data after:  510,842,139
growth = -524,724 B      i.e. loki-data SHRANK                PASS
"timestamp too old" count: 0                                  PASS
positions after:
  /logs/console-2026-08-20-11.log: "52229997"
  /logs/console.log: "644917"        (carried forward, NOT reset to 0)
```

Against Finding 2's **+2.2 GB in 17 minutes per redeploy**, the redeploy re-ingest
is **gone**. Result: **PASS**

### P4-9 — evidence promotes both tracks, still by hardlink — **PASS**

First run (11:56:07Z) gave console = 7, **detail = 1** against a `≥ 2` expectation.
Diagnosed rather than reported as a failure: `sourceFilesByTrack` was
`{aggregate: 1, detail: 1}` — only the live file existed on each track, because 0a
had just emptied the console archives and track 2's first rollover had not happened.
"Newest two" of one file is one file. Correct behaviour.

Re-run at 12:13:23Z, after the 12:00Z rollover gave each track an archive:
```
console files in evidence: 10     (>= 2)   PASS
detail files in evidence:   4     (>= 2)   PASS
sourceFilesByTrack: {aggregate: 2, detail: 2}

hardlink proof:
  3 8700777 logs/detail/detail.log
  3 8700777 logs/evidence/detail-live-...P49Recheck...log     same inode, nlink 3
  3 138610388 logs/console.log
  3 138610388 logs/evidence/console-live-...log               same inode
```
`/health` reports a **non-zero per-track detail count**. No copies anywhere — the
3 GB-per-file disk-doubling failure mode is not live.
Result: **PASS**

### P4-10 — the two evidence ages are independent (AD-28) — **PASS**

Set `EVIDENCE_DETAIL_MAX_AGE_DAYS=0` in `secrets.env`, `./deploy.sh`, confirmed the
shim picked it up (`maxAgeDays 14.0 detailMaxAgeDays 0.0`), triggered a sweep via a
promotion:
```
detail files:   4 -> 0        (expected 0)                    PASS
console files: 10 -> 12       (expected unchanged and > 0)    PASS

lastSweep.removed = [ "detail-2026-08-20-11.log",
                      "detail-live-alertname_P410Sweep-...log" ]
freedBytes = 12,525,661 ; remaining = 12 ; remainingDetail = 0
```
The sweep removed **only** detail files. The aggregate count rose to 12 because the
triggering promotion added two aggregate links — it was not swept. This is the step
P3-10's single-age form cannot prove.

`secrets.env` **restored** and redeployed; shim re-confirmed at
`maxAgeDays 14.0 detailMaxAgeDays 3.0 maxBytes 12884901888`.
Result: **PASS**

> **Checked because AD-16 made it worth checking:** the shim's eviction "unlinks
> every name of the chosen inode". It does **not** reach back into the source
> directories. After the sweep, `logs/detail/detail-2026-08-20-11.log` is still
> present with `nlink 2`, and `logs/console-2026-08-20-11.log` with `nlink 3`. The
> unlink is correctly scoped to `logs/evidence/`.

### P4-11 — the new scoped-DEBUG operator path (AD-24) — **PASS**

```
POST /api/v1/logging/debug/ab81f9e6-...?minutes=5   -> HTTP 200
   {"botGroupId":"ab81f9e6-...","expiresAt":"2026-08-20T12:01:48.345Z"}

after 70 s:
  detail payload  grep "[<GID>/" logs/detail/detail.log  -> 12,225      (> 0)  PASS
  console pointer grep "scoped debug"  logs/console.log  ->      9      (> 0)  PASS
  Loki  {job="bot-manager"} |= "scoped debug"            -> NON-EMPTY          PASS
```
Pointer line in Loki, verbatim: `"logger":"com.vingame.bot.domain.logging.controller.LogLevelController"`,
`"message":"scoped debug enabled for group ab81f9e6-... by operator request"`.

**AD-24's contract holds end to end: Grafana tells you a drill-in exists and when;
the box holds the 12,225-line payload.** PatternLayout MDC renders as
`[40fa3749-.../15/BETTING_MINI]`, as designed.
Result: **PASS**

### P4-12 — `docker logs` is clean — **PASS**

```
docker compose logs --since 5m bot-manager | grep -cE 'websocketparser|Agency token|ANSI'  -> 0
docker compose logs --since 30m bot-manager | grep -cE 'Started Starter|bot groups running' -> 2
```
No library flood, no token material, no ANSI on stdout; Spring startup and the
auto-start summary still visible (AD-27's reason for keeping root wired to console).
Re-confirmed at the end of the session: still **0**.
Result: **PASS**

### P4-13 — the queue meters exist and the alert can read them — **PASS**

```
log4j2_async_queue_capacity{appender="AsyncDetail"}   16384.0
log4j2_async_queue_capacity{appender="AsyncRolling"}   8192.0
log4j2_async_queue_remaining{appender="AsyncDetail"}  16384.0
log4j2_async_queue_remaining{appender="AsyncRolling"}  8192.0
log4j2_async_queue_full_samples_total{AsyncDetail}         0.0
log4j2_async_queue_full_samples_total{AsyncRolling}        0.0
log4j2_async_queue_pressure_samples_total{AsyncDetail}     0.0
log4j2_async_queue_pressure_samples_total{AsyncRolling}    0.0

Prometheus rules matching LogQueueSaturated: 1
"Logging-queue metrics started" in bot-manager log:  1
```
- Four gauge lines, both appenders. **No `-1`**, so both appenders are in the
  running configuration — P4-1's assertion confirmed from the other side.
- **`capacity` 8192 on AsyncRolling and 16384 on AsyncDetail** confirms the
  deliberate pair AD-25(4) describes.
- Counters at `0` — the expected healthy reading, non-vacuous because they are
  registered eagerly.
- Rule loaded and `"state":"inactive"`, query
  `log4j2_async_queue_remaining / log4j2_async_queue_capacity < 0.1`, `for: 5m`,
  labels `audience: internal`, `severity: warning`.
- `amtool config routes test alertname=LogQueueSaturated` → **`viptalk`** (ops room
  via AD-V3, correct for an `audience: internal` rule).
- Throttled WARN never fired: `grep -c 'async logging queue' logs/console.log` = 0,
  consistent with zero saturation samples.
Result: **PASS**

## Plan verification — corrected Phase 0–3 steps

### P0-5 (corrected form) — one archive per track per 2 h, names unique — **PASS**

**This is the step that "fails forever on a working system" in its original form.
On the corrected form it passes.**

The 12:00Z boundary produced exactly one archive on each track:
```
track 1: console-2026-08-20-11.log   52,229,997 B   mtime 11:59:29Z
track 2: detail-2026-08-20-11.log     5,541,988 B   mtime 11:59:59Z
duplicate names, track 1: 0
duplicate names, track 2: 0
```
- **Suffix is `-11`, i.e. odd**, for a rollover at 12:00Z — `rollover − 1 h`,
  exactly as the correction states. The original "even hour" criterion would have
  failed here on a perfectly working system.
- **Both mtimes land just *before* the boundary** (11:59:29Z and 11:59:59Z), which
  confirms the 2026-08-20 amendment about rename preserving the last-write mtime.
  Track 1's is 30 s earlier than track 2's precisely because track 1 is now sparse.
- Names unique on both tracks. **AD-20's substantive requirement holds.**
Result: **PASS**

> Track 1 did not roll at the instant of the boundary — at 12:00:25Z there was still
> no archive, and it appeared shortly after. This is `TimeBasedTriggeringPolicy`
> firing on the *next event*, and track 1 now emits ~1.5 lines/s. Expected, not a
> fault, but worth knowing: **the quieter track 1 gets, the more its rollover lags
> the boundary.**

### P0-9 — Loki growth baseline — **RE-ESTABLISHED CLEANLY**

The 2026-08-19 baseline was corrupted by the redeploy re-ingest. With the positions
volume in place (P4-8), that mechanism is gone and a clean baseline is possible.

**The new T+0 is `loki-data` = 457,543,926 B, taken 2026-08-20T12:25Z**, which is
**39 minutes after this deploy's single re-ingest began (11:46Z) and after it had
demonstrably settled** — the P4-8b cycle at 12:07Z proved no further re-ingest
occurs, and three later `deploy.sh` cycles produced none.

| Metric | Pre-deploy 11:43:56Z | Post re-ingest 12:11:25Z | **T+0 (settled) 12:25Z** |
|---|---|---|---|
| `loki-data` | 457,344,615 | 510,842,139 | **457,543,926** |
| `logs/` total | 4,595,603,047 | 4,608,463,752 | 4,614,696,460 |
| `logs/detail/` | (did not exist) | 10,592,773 | 16,501,196 |
| `logs/evidence/` | 148,934,396 | 212,402,616 | 218,635,324 |
| `logs/pre-phase4/` | (did not exist) | 4,544,990,804 | 4,544,990,804 |
| Root available | 72,140,201,984 | 72,375,488,512 | 72,412,446,720 |
| Root used | 33% | 33% | 33% |

**Net `loki-data` change across the entire deploy: +199,311 bytes.** Compare
**+2,224,683,015 B** on 2026-08-19.

> **On the compactor, as asked.** `loki-data` was **453.6 MB** (`docker system df`)
> at the start of this session, down from the **3.24 GB** left by the last deploy —
> the compactor had been reclaiming all day. It **does not distort this baseline**:
> the pre-deploy reading (457.3 MB) and the settled T+0 (457.5 MB) are taken on the
> same already-reclaimed store 41 minutes apart and agree to within 0.04%. The T+0
> above is a settled figure, not a mid-reclaim one.
>
> That near-zero net is also **why** the re-ingest was so cheap: promtail re-read
> content Loki already held, and Loki **deduplicated** the identical entries. The
> intermediate 510.8 MB reading at 12:11Z is the pre-dedup/pre-compaction peak.

Expected magnitude per the plan is now **single-digit MB/day**. Track 1 writes
648 B/s = **53.4 MiB/day** raw; after Loki's compression the store should grow well
under that. **The T+24 h and T+7 d readings remain the live gate**, and the 50%-of-
available-disk abort is unchanged. A figure an order of magnitude above expectation
should be investigated as a misroute (re-run P4-5) before being treated as disk.

### P0-6 — disk headroom (PRE-RAMP GATE) — **PASS**

```
node_filesystem_size_bytes{mountpoint="/"}  = 107,362,627,584   (100 GiB)
node_filesystem_avail_bytes{mountpoint="/"} =  72,375,431,168   (67.41 GiB)
df -h /: /dev/nvme0n1p1  100G  33G  68G  33% /
```
Both Prometheus results non-empty and agreeing with `df`. Available is **up** from
66.96 GiB on 2026-08-19 (Loki compaction). Host disk rules are not blind.
Result: **PASS**

### P0-7 — Loki accepted the retention split — **PASS**

`/ready` → `ready` (5/5 polls). `/config`:
```
retention_stream:
- period: 30d   priority: 1   selector: '{level=~"WARN|ERROR"}'
- period: 1d    priority: 1   selector: '{level=~"DEBUG|TRACE"}'
```
`1d` and `30d` are `24h` and `720h` rendered in different units — the plan's expected
values. Result: **PASS**

### P0-8 — the retention split actually bites — **PASS (WARN/ERROR half)**

Window 26–30 h old:
```
{job="bot-manager",level=~"WARN|ERROR"} -> NON-EMPTY
   (from /logs/console-2026-08-19-10.log, ts 1787134318141000000)
{job="bot-manager",level="DEBUG"}       -> "result":[]
```
The WARN/ERROR half is the proof the compactor runs and the 30 d tier is retained.
The DEBUG half is **vacuous by design now** (DEBUG never reaches Loki at all) and is
recorded only for completeness; the real DEBUG assertion is P4-5. Result: **PASS**

### P0-3 — no events lost, files growing — **PASS**

`console.log` 959 lines and `detail.log` 22,499 lines and both advancing at every
check; final sizes 966,878 B and 10,959,152 B. Result: **PASS**

### P1-1 — the env-driven level took (AD-7 gate) — **PASS**

```
com.vingame.bot             -> {"configuredLevel":"DEBUG","effectiveLevel":"DEBUG"}
com.vingame.websocketparser -> {"configuredLevel":"INFO","effectiveLevel":"INFO"}
```
Both `LOGGING_LEVEL_*` routes work. The second is new in Phase 4 and is the AD-32
escape hatch — it resolves, so `logger.wsparser` was **not** renamed and did not
silently become an appender-less LoggerConfig. Result: **PASS**

### P1-2 — appenders survived the level override — **PASS**

`/actuator/loggers/com.vingame.bot` → 200, and `grep -c '"level":"INFO"'
logs/console.log` grew **84 → 86 over 60 s**. The file appender is still attached.
Result: **PASS**

### P1-3 — **replaced by P4-4.** See above. **PASS** (3 / 300 s vs `< 100`).

### P1-4 — the per-bot INFO classes are gone — **PASS**

Full message-set grep (all eight patterns, not the narrow three), over the 300 s
window scoped to post-deploy lines only:
```
0
```
Result: **PASS**. Scoped to the window, so the 2026-08-19 caveat about
`console.log` spanning deployments does not apply.

### P1-5 — the aggregated replacements are present — **PARTIAL PASS**

```
"bots initialized" -> GroupLifecycleAggregator: "group 2bf237bd-... (Slot group 120): ..."   PASS
"strategy mix"     -> BotGroupBehaviorService: "Bot group 2bf237bd-...: strategy mix {RANDOM=20}"  PASS
"bots auto-deposited" -> (no line)
```
The auto-deposit line is **N/A, not failed**: no auto-deposit group started during
the session. The plan scopes that half to "on an auto-deposit group".

### P1-6 — tier-2 rollup is emitting — **PASS**

`FleetRollupLogger` lines for both running environments within the last 6 minutes
(12:06:49Z, 12:12:33Z ×2, thread `fleet-rollup-logger`). Result: **PASS**

### P1-7 — tier-2 is quiet about healthy groups — **PASS**

`grep -cE 'group .* playing=' logs/console.log` → **0**. Result: **PASS**

### P1-8 (corrected) — session summaries demoted — **PASS on the half that can run**

```
INFO half:  grep '"level":"INFO"' logs/console.log | grep -c 'session'  -> 0     PASS
DEBUG half (moved to track 2): grep -c 'session' detail.log             -> 3,916 PASS
```
**The EndGame half remains unexercisable** — see Finding 3 below. Not marked passed.

### P2-1 — enable a scope — **PASS** (200 + `expiresAt`; GID listed). Covered under P4-11.

### P2-2 (corrected) — DEBUG flows for that group — **PASS on the half that is meaningful**

```
tail -5000 detail.log | grep ' (DEBUG|TRACE) ' | grep -c  "[<GID>/"  -> 2,423
tail -5000 detail.log | grep ' (DEBUG|TRACE) ' | grep -vc "[<GID>/"  ->   674
```
The first half passes. **The exclusion half is meaningless on this instance** and
the plan says so: with `BOT_LOG_LEVEL=DEBUG` every group emits DEBUG legitimately,
so the 674 are expected, not a leak. Only a prod-like INFO instance can test it.

### P2-3 (corrected) — the TTL expires — **PASS, with a caveat**

The manual scope set at 11:56:48Z with `expiresAt 12:01:48.345Z` is **gone**: at
12:16:02Z that expiry is absent from `/api/v1/logging/debug`.

**Caveat worth stating plainly:** the same GID *is* present again, with a **different,
later** expiry (`12:30:13.807Z`) from a **different trigger** — a fresh
auto-escalation at 12:15:13.807Z. On a fleet in permanent watchdog churn the
escalator re-arms groups continuously, so "GID absent from the list" is not a clean
assertion here. Expiry of the *specific* scope is confirmed; a clean run needs a
quiet fleet.

### P2-4 (corrected) — the spin-cost spam is bounded — **UNEXERCISED**

```
grep -c 'below spin cost' logs/detail/detail.log (+ archives) -> 0
grep -c 'below spin cost' logs/console.log                    -> 0
```
No bot fell below spin cost in this session. **Unexercised, not passed** — the same
status as 2026-08-19.

### P2-5 — auto-escalation fires — **PASS, on genuine triggers, unforced**

Six escalations across the session, all from real triggers (`reconnect-ws-*` and
`watchdog-*` threads), naming the group and the trigger, at INFO in track 1:
```
11:46:48.345Z  ab81f9e6-...  reconnect-ws-bomflowtest33
11:49:52.282Z  7a3716ed-...  watchdog-authtestws97810
12:07:32.132Z  ab81f9e6-...  reconnect-ws-bomflowtest2
12:10:35.906Z  7a3716ed-...  watchdog-authtestws97812
12:14:05.731Z  40fa3749-...  reconnect-ws-xdt3st24
12:14:06.071Z  ab81f9e6-...  reconnect-ws-bomflowtest1
12:15:13.315Z  40fa3749-...  reconnect-ws-xdt3st25
12:15:13.807Z  ab81f9e6-...  reconnect-ws-bomflowtest15
```
Result: **PASS**

### P2-6 — no escalation storm / the 25% duty cycle — **NOT MEASURABLE THIS SESSION**

At first reading this looks like a violation: group `ab81f9e6` escalated **3 times in
30 minutes** against a documented re-arm interval of
`escalation.minutes + cooldown-minutes` = 15 + 45 = **60 minutes**.

It is not. Correlating against JVM starts:

| Escalation | JVM start |
|---|---|
| 11:46:48Z (ab81f9e6), 11:49:52Z (7a3716ed) | JVM 1 (11:46) |
| 12:07:32Z (ab81f9e6), 12:10:35Z (7a3716ed) | JVM 2 (`Started Starter` 12:07:33Z) |
| 12:14:05Z (40fa3749), 12:14:06Z (ab81f9e6) | JVM 3 (12:14:07Z) |
| 12:15:13Z (40fa3749), 12:15:13Z (ab81f9e6) | JVM 4 (12:15:15Z) |

**Exactly ≤ 1 escalation per group per JVM lifetime.** The cooldown is in-memory and
resets on restart, and **I restarted the JVM four times** for P4-8b and P4-10. The
duty cycle is therefore **unmeasured**, not failed — measuring it needs 60
uninterrupted minutes. The per-JVM behaviour is consistent with a working cooldown.

### P3-1 — the shim is up and healthy — **PASS**
`Up (healthy)`; `/health` → 200 with the full per-track body quoted above.

### P3-2 — the evidence dir exists and is writable — **PASS**
`drwxr-xr-x sgame sgame`, same uid:gid as `logs/console.log`; `sameFilesystem: true`.

### P3-3 / P4-9 — a synthetic alert promotes files — **PASS** (superseded by P4-9).

### P3-4 — HARDLINKS, not copies — **PASS** (inode + nlink proof under P4-9).

### P3-5 — promoted files are not re-ingested by promtail — **PASS**
`{job="bot-manager", filename=~"/logs/evidence/.*"}` → `"result":[]`.

### P3-6 — promotion is idempotent and coalesced — **PASS**

Three rapid identical POSTs under key
`alertname=IdemTest,environmentId=idem-env,product=116`:
```
evidence file count: 14 -> 16     (+2 = one console-live + one detail-live, not +6)
pending entries for that key: exactly 1, with "passes": 3
```
Result: **PASS** — and this is the two-track version of the check: +2, one per track.

### P3-7 — the deferred pass runs — **PASS**
`lastPromotion` at 12:20:34Z with `"tag": "deferred"` for the IdemTest key, `linked: []`
and four entries in `skipped` (already-pinned names), `errors: []`. No duplicate names.

### P3-8 — promoted files survive log4j2's `Delete` — **PASS**
Two names present in `logs/evidence/` and absent from `logs/`:
`console-2026-08-19-11.log`, `console-2026-08-19-13.log`. (Their absence from
`logs/` is now also partly due to 0a, which does not weaken the check — the
hardlinks kept the *content* alive either way.)

### P3-9 — unclean start retro-promotes (AD-21) — **PASS, observed naturally**
A `boot`-tagged pending entry with `"passes": 2` appeared across the redeploys, and
`startedClean` read `false` after the kill-and-restart cycles. Boot-tagged promotions
present in `logs/evidence/` (`console-live-boot-20260820T121348Z.log`,
`detail-live-boot-20260820T121348Z.log`) — **both tracks**.

### P3-10 — **superseded by P4-10.** PASS.

### P3-11 — Alertmanager routing is intact (AD-14) — **PASS**
```
EnvironmentGroupDead   -> evidence,viptalk
BotManagerDown         -> evidence,viptalk-static-down,viptalk
EnvironmentSocketDown  -> viptalk
LogQueueSaturated      -> viptalk            (new, audience: internal -> ops room)
```

### P3-12 — the guard test passes in the build — **PASS** (part of the 1862, run on the build machine).

## Findings

### Finding P4-A — 0a bounds the re-ingest exposure but cannot eliminate it

**The live `logs/console.log` cannot be moved by 0a** (the JVM holds it open; it is
track 1's live file, and the plan explicitly forbids moving it). At deploy time it
was **51,806,508 B** of *pre-Phase-4* output and it contained:

```
com.vingame.websocketparser lines: 42,358
"level":"DEBUG" lines:            76,895
```

The new promtail, with a fresh positions volume, re-read that file from offset 0.
This is why P4-5's first run returned hits with pre-11:46:12Z timestamps.

**Severity is low, and measured rather than assumed:**

- **Net `loki-data` cost: +199,311 bytes** across the whole deploy, because Loki
  deduplicated entries it already held from before the deploy. Nothing meaningfully
  new was stored, and `timestamp too old` was `0` throughout.
- The DEBUG residue expires on the **1 d** `retention_stream` — **2026-08-21**.
- The ws-parser INFO residue falls under the default **720 h** — **2026-09-19**.
- **0a prevented the large case entirely**: 4.54 GB across 157 files was never
  offered to promtail.

**This is a one-time event.** P4-8b proves subsequent redeploys re-ingest nothing.

*Optional follow-up, not done:* if the residue matters, 0c would remove it, and
per amendment C4 it needs no config change and no restart.

### Finding P4-B — Gate 2 is stricter than the plan's worked example

Covered in full under P4-6. Short form: the plan's "48–51%, passes narrowly at 30k"
is **51.8% prod-like / 59.6% staging-like at 30k** against measurement — a narrow
fail. It passes comfortably at today's 155 bots (47.5%). Remedy is a one-line cap
cut in a bind-mounted file.

### Finding 3 (carried forward, unchanged) — the fleet completes no rounds

**Zero `endGame` messages across both tracks for the entire session**, exactly as on
2026-08-19. This is not a Phase 4 effect and Phase 4 does not touch it.

```
track1 endGame (any case):                0
track2 endGame (any case, incl. archives): 0
track1 "EndGame results" summaries:        0
track1 "StartGame" summaries:              0
track2 StartGame lines:                   43
track2 UpdateBet 5 s aggregates:         647
track2 session lines:                  3,916
```

Rounds **start** and **update** but never **complete**. Consequences, stated plainly
rather than marked passed:

- **P1-8's EndGame half — still unexercisable.**
- **AD-8's compensating `rounds`/`staked` drain — still unexercised**, because it is
  driven off EndGame.
- **P2-4 — still unexercised** (0 bots below spin cost).
- **P1-4 — this one did run and passed** (0 per-bot INFO lines), so it is not in this
  list.

It also explains the 460 WARN/300 s watchdog churn that is now the bulk of Loki's
intake, and the ws-parser rate running 1.54× B1.

## Expected behaviour changes — sanity-checked, all as predicted

| Predicted | Observed |
|---|---|
| `logs/detail/` appears and grows fast | Yes — 0 → 16.5 MB in 38 min; 6,318 B/s vs track 1's 648 B/s (**9.75×**) |
| `docker logs` quieter | Yes — P4-12 noise count 0 |
| Loki quieter | Yes — INFO 2,022 → 3 per 300 s; net `loki-data` +199 KB vs +2.22 GB |
| Scoped per-group DEBUG no longer visible in Grafana (AD-6 reversed by AD-24) | Yes — pointer reaches Loki (P4-11), 12,225-line payload is box-only |
| One promtail re-ingest on this deploy only | Yes — and P4-8b proves it does not recur |

None of these were treated as regressions.

## Verdict

**PASS**

- Smoke: **PASS** (all 10 services, including the observability stack and both shims)
- Phase 4 steps P4-1 … P4-13: **13 of 13 PASS**
- Corrected Phase 0–3 steps run: **21 attempted — 18 PASS, 3 could not be exercised**
  (P2-4 unexercised, P1-8's EndGame half unexercisable, P2-6 unmeasurable this
  session), **0 FAIL**
- Pre-deploy 0a: **RAN CLEAN** (157 archives / 4.54 GB relocated, source dir empty)
- Pre-deploy 0b: **PASS** (one positions entry)
- Pre-deploy 0c: **SKIPPED by instruction**; standing exposure ages out 2026-09-19

## State left on the box

- 10 containers up; bot-manager, mongo, viptalk-shim, evidence-shim healthy.
- 4 bot groups running (155 bots), same as before the deploy.
- **`logs/pre-phase4/` holds 157 files / 4.54 GB and nothing will ever sweep it** —
  not promtail, not log4j2's `Delete` (both are non-recursive / `maxDepth 1`), not
  the evidence shim (`candidates()` does not recurse). Per the plan this is
  deliberate. **Delete it by hand once the window it covers is past**; it is the
  single largest item in `logs/`. Promote by hand first if an incident inside
  2026-08-13 → 2026-08-20 is still open: `ln logs/pre-phase4/<f> logs/evidence/<f>`.
- `logs/detail/` holds the live `detail.log` plus `detail-2026-08-20-11.log`.
- `logs/evidence/` holds **18 promoted hardlinks** (218.6 MB by `du`, but AD-19:
  hardlinks double-count against live files), of which **13 aggregate / 5 detail**.
  **Several are synthetic-test artefacts** from this session under the keys
  `P49Recheck`, `P410Sweep`, `IdemTest` and `EnvironmentGroupDead/test-env`. Harmless
  (hardlinks consume no extra blocks) and they age out on the 14 d / 3 d sweeps, but
  delete them if a clean evidence dir is wanted.
- Scoped-DEBUG scopes may still be live from genuine auto-escalations; all carry a
  15-minute TTL and expire on their own.
- Config backups on the box tagged **`20260820-114356`** (plus the older
  `20260819-190838` set).
- `secrets.env` **restored to its pre-session content** after P4-10 — verified
  `EVIDENCE_DETAIL_MAX_AGE_DAYS` absent, `BOT_LOG_LEVEL=DEBUG` present.
- Helper scripts left in `/tmp` on the box (`win300.sh`, `p46.sh`, `p48.sh`,
  `p48b.sh`, `p411.sh`, `final.sh`, `gid.txt`, `secrets.env.p410bak`) — `/tmp`, so
  self-clearing.

## Notes for the plan / docs (no code implications)

1. **P4-6 Gate 2's 30k worked example is optimistic** — see Finding P4-B. The
   155-bot end (~32.2 GiB) is confirmed; the 30k end should read ~34.9 GiB / 51.8%
   prod-like, i.e. binding.
2. **P4-9's `≥ 2` per track has a start-up precondition** worth stating: it needs at
   least one closed archive per track, so it cannot pass on the first deploy after
   0a until the first rollover boundary. Suggest "≥ 2 per track once each track has
   an archive; otherwise ≥ 1 and `sourceFilesByTrack` = 1".
3. **P2-3's "GID absent" assertion is not clean on a churning fleet** — the
   escalator re-arms the same group with a new expiry. Suggest asserting the
   *specific* `expiresAt` is gone rather than the GID.
4. **P2-6 needs 60 uninterrupted minutes** and is therefore incompatible with a
   verification run that redeploys for other steps. Suggest scheduling it last, or
   on a separate day.
5. **P0-5's corrected form works** — recorded here as confirmation, since the
   previous release could only report that the original form was wrong.
6. **U-3 is still stale** in the plan and in `CLAUDE.md`'s REST table
   (`GET /api/v1/bot-group/` → 405). Unchanged from the 2026-08-19 note.
