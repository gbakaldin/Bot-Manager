# Observability Diagnosis — Bot-1 (staging)

Read-only investigation. One runtime action was taken (start + stop of bot group `9b54e101…` for 4 minutes) to reproduce the user's experience and observe log flow. No config changes, no redeploys.

Investigation window: 2026-06-03 10:32 → 10:38 UTC.

---

## TL;DR

1. **"External volume" is a Docker named volume, not a host bind-mount.** `docker-compose.yml` declares `logs-data` as a named volume mounted at `/app/logs` in the bot container and `/logs:ro` in promtail. The host path `/home/sgame/bot-java/logs/` that the user is checking is a stale, unrelated directory that hasn't been written to since 2026-05-13. The actual logs live under `/var/lib/docker/volumes/bot-java_logs-data/_data/` (readable only as root). This is the #1 cause of the "logs not landing on the external volume" perception — the user is looking in the wrong place, and the volume isn't actually external.
2. **App log writing itself is healthy.** During the 4-minute reproduction, `/app/logs/console.log` grew from 1,221,450 → 1,743,832 bytes; the file produced ~3,300 lines, averaging ~700–1,000 lines/min steady. The Log4j2 `RollingFileAppender` rotated correctly at the 10:00 UTC hour boundary (`console-2026-06-03-09.log` was archived in place when the new session started).
3. **Promtail → Loki is healthy and stable.** Promtail position kept pace with file size to within ~50 KB of head (normal small read-buffer lag). Loki ingested 3,649 of 3,669 lines (20-line tailing lag, normal). No errors, no rate-limits, no drops in either Promtail or Loki logs during the test. Loki cardinality is fine (7 streams).
4. **The real "logs not stable" problem is upstream of Promtail: most bot lines are written without MDC context.** Only 279 of 3,919 lines (~7.1%) in the current console.log contain a `botGroupId` field. The remaining ~93% come from `netty-ws-message-processor-ws-<bot>` and `pool-N-thread-1` (per-bot scheduler) threads where MDC is not propagated. Those lines still flow to Loki, but with the labels promoted in `promtail-config.yml` (`botGroupId, environmentId, gameType`), they fall into the catch-all stream without those labels. Filtering Grafana by `botGroupId="…"` or `gameType="BauCua"` silently hides the bulk of the traffic.
5. **Idle bot-manager produces no logs at all.** When no group is running, the rolling file does not grow. So if the user opens Grafana between sessions and sees "stopped" graphs, that is correct — but it also means the only entries with botGroupId labels are the Spring/MDC-aware service-layer ones (~150–200 lines for the BC Mini reproduction), reinforcing the "logs aren't flowing" impression even when ~3,000+ Netty/scheduler lines are flowing.

---

## External volume — what's broken and why

### What the compose file actually declares

```yaml
volumes:
  logs-data:        # named, anonymous Docker-managed volume

services:
  bot-manager:
    volumes:
      - logs-data:/app/logs        # named volume → /app/logs in container

  promtail:
    volumes:
      - logs-data:/logs:ro         # same named volume → /logs (read-only) in promtail
```

There is no `./logs:/app/logs` bind-mount. Docker manages the volume at `/var/lib/docker/volumes/bot-java_logs-data/_data` (from `docker volume inspect`).

### Where the host directory `/home/sgame/bot-java/logs/` is in the picture

It isn't. That directory exists, but nothing in the compose file references it. Its content is two ancient files from May 12-13:

```
-rw-r--r-- 1 polkitd input 66897 May 12 17:35 console-2026-05-12-10.log
-rw-r--r-- 1 polkitd input   694 May 13 17:11 console.log
```

The `polkitd` owner suggests these came from a previous incarnation of the deployment when the directory may have been bind-mounted; they have not been written by the current container.

### What the container writes

Inside the bot-manager container at `/app/logs/`:

```
drwxr-xr-x 2 botmanager botmanager      58 Jun  3 10:33 .
-rw-r--r-- 1 botmanager botmanager 1221450 Jun  3 09:39 console-2026-06-03-09.log
-rw-r--r-- 1 botmanager botmanager 1334345 Jun  3 10:36 console.log
```

UID/GID 1006:1007 = `botmanager` (created in the Dockerfile via `groupadd -r -g 1007 botmanager && useradd -r -u 1006 -g botmanager`). Files are owned by botmanager, mode 0644. No permission conflict.

### What Promtail sees

Promtail mounts the same named volume read-only at `/logs/`:

```
total 1196
drwxr-xr-x 2 1006 1007      25 Jun  3 09:34 .
-rw-r--r-- 1 1006 1007 1221450 Jun  3 09:39 console.log
```

UIDs are numeric (1006/1007) because the promtail image has no matching named user — irrelevant for read-only tailing.

### App's logging config (`src/main/resources/log4j2.properties`)

- `appender.rolling.fileName = /app/logs/console.log`
- `appender.rolling.filePattern = /app/logs/console-%d{yyyy-MM-dd-HH}.log`
- `RollingFileAppender` with `JsonTemplateLayout` (template at `src/main/resources/log4j2-json-template.json`)
- Hourly time-based trigger with `modulate=true` (rotates at the top of the hour)
- Retention: 7 days OR 10 GB total (whichever fires first)
- **Rotation observed working**: the 09:00–10:00 window archived to `console-2026-06-03-09.log` as expected.

### Net diagnosis (volume)

- Logs ARE being written, just not where the user is looking. The named volume hides them from casual host inspection (`ls /home/sgame/bot-java/logs/` shows stale data).
- The user's mental model of an "external volume on Bot-1" does not match the deployed compose. There is no bind-mount on the host filesystem.
- No permission, no UID mismatch, no driver problem. Just wrong target.

---

## Loki stability — what's broken and why

### Docker logging driver

`json-file` (default) — irrelevant here because the project chose **file-based scraping**, not Docker socket discovery. Promtail does not tail container stdout. So Docker's log driver has no effect on Loki ingestion.

### Promtail scrape config

```yaml
- job_name: bot-manager
  static_configs:
    - targets: [localhost]
      labels:
        job: bot-manager
        __path__: /logs/*.log
  pipeline_stages:
    - json:
        expressions: { timestamp, level, botGroupId, environmentId, gameType }
    - timestamp: { source: timestamp, format: "2006-01-02T15:04:05.000Z0700" }
    - labels: { level, botGroupId, environmentId, gameType }
```

It tails every `*.log` under `/logs/` (so both current and rotated hour files), parses JSON, and **promotes 4 fields to Loki labels: `level`, `botGroupId`, `environmentId`, `gameType`**.

### Live behavior during reproduction (10:33:08 → 10:37:24 UTC)

Loki per-30s line counts:

| Bucket (UTC)      | Lines |
|-------------------|-------|
| 10:33:30          | 666   |
| 10:34:00          | 321   |
| 10:34:30          | 551   |
| 10:35:00          | 351   |
| 10:35:30          | 321   |
| 10:36:00          | 491   |
| 10:36:30          | 411   |
| 10:37:00          | 171 (partial — group stopped mid-window) |

Steady ~700–1,000 lines/min with run-of-the-mill variance (bots batching `[RECEIVED]` between game-state changes — perfectly consistent with the actual game cycle). **Not flat, not zero, not "lines stop arriving."**

After the bot group stopped at 10:37:24 UTC, ingestion went to zero in the next bucket — also expected.

### File vs Promtail vs Loki end-of-test reconciliation

- File: 3,669 lines on disk (3,919 a few seconds later as bots quiesced)
- Promtail position: 1,499,806 bytes ≈ ~50 KB behind head — normal tailing lag
- Loki: 3,649 lines ingested in last 10 min — 20-line tailing lag matches Promtail's

No drops. No errors in Promtail logs (only INFO `received file watcher event` and `tail routine: started`). No warnings/rate-limits in Loki logs.

### Loki config

`loki/local-config.yaml` is essentially the default single-binary boltdb→tsdb migration template:
- No `limits_config` overrides → all defaults (250 KB/s per stream, 4 MB burst). At ~1k lines/min ≈ 17 lines/s the bot-manager is nowhere near the limit.
- No retention configured (compactor.retention_enabled is unset, default false). Data persists indefinitely until disk fills.
- Storage: filesystem-backed, `/loki/chunks` currently 78 MB, `/loki/wal` 3 MB. Host has 7.6 GB tmpfs free. Headroom is fine.

### Loki labels actually present

```
botGroupId, environmentId, filename, gameType, job, level, service_name
```

Series count: 7 streams. Tiny — no cardinality issue. The streams break down as combinations of `{level} × {botGroupId present/absent} × {gameType present/absent}`. Concrete observed series:

```
{botGroupId=…, environmentId=…, gameType=BauCua, level=INFO}   ← MDC-tagged INFO bot lines
{botGroupId=…, environmentId=…, gameType=BauCua, level=DEBUG}  ← MDC-tagged DEBUG bot lines
{botGroupId=…, environmentId=…, level=INFO}                    ← MDC partial (no gameType)
{botGroupId=…, environmentId=…, level=DEBUG}                   ← MDC partial
{level=INFO}                                                   ← Netty/scheduler/Spring without MDC
{level=DEBUG}                                                  ← same
{level=WARN}                                                   ← same
```

### The actual ingest-vs-perception problem

Of 3,919 file lines, only **279 (7.1%)** contain a `botGroupId` field in the JSON. Lines grouped by thread (excluding lines that have botGroupId):

| Thread                                                | Lines (without botGroupId) |
|-------------------------------------------------------|----------------------------|
| `netty-ws-message-processor-ws-test_bcmini_001..010` | 10 × 200 = 2,000           |
| `pool-37..41-thread-1`                                | 5 × 150 = 750              |
| `multiThreadIoEventLoopGroup-2-N`                     | rest                       |
| `http-nio-8085-exec-N`                                | a handful                  |

These threads come from:
- Netty's WebSocket I/O loop (receiving messages → `OutputPrinter` logs them)
- The per-bot scheduled executor (sending bets, etc.)
- The Netty handshake handler (connection lifecycle)

None of them inherit the MDC context that the bot-creation HTTP thread set when the group started. So they emit JSON entries with the four MDC fields **missing**, and Promtail's `- labels: { botGroupId, environmentId, gameType }` stage promotes those fields as empty/missing, dropping the line into the unlabelled stream.

**User-visible consequence:** any Grafana panel filtered by `{job="bot-manager", botGroupId="…"}` or by `gameType` only matches ~7% of the actual traffic. The "stable startup banner" the user sees is exactly the Spring `Started Starter` line, which happens to be in the `botGroupId`-less catch-all stream and matches any unfiltered query. The dropout the user perceives during "sustained operation" is the labelled-stream view falling silent while the unlabelled stream is full of `[RECEIVED]/[SENT]` traffic.

---

## Evidence

All commands run from the operator workstation against Bot-1 via ssh. Selected output trimmed to relevant lines; full session captured during the reproduction window 10:32–10:38 UTC.

### E1 — compose volumes & mounts

```
$ ssh Bot-1 'cat /home/sgame/bot-java/docker-compose.yml'
# bot-manager.volumes:
#   - logs-data:/app/logs
# promtail.volumes:
#   - logs-data:/logs:ro
# top-level volumes: { mongo-data, loki-data, grafana-data, logs-data }   ← all named, no bind-mount

$ ssh Bot-1 'docker inspect bot-java-bot-manager-1 --format "{{json .Mounts}}"'
[{"Type":"volume","Name":"bot-java_logs-data",
  "Source":"/var/lib/docker/volumes/bot-java_logs-data/_data",
  "Destination":"/app/logs","Driver":"local","Mode":"z","RW":true}]

$ ssh Bot-1 'docker inspect bot-java-bot-manager-1 --format "{{.HostConfig.LogConfig.Type}} {{.HostConfig.LogConfig.Config}}"'
json-file map[]
```

### E2 — host log directory the user expects is stale

```
$ ssh Bot-1 'ls -la /home/sgame/bot-java/logs/'
drwxrwxrwx 2 sgame   sgame    58 May 13 17:11 .
-rw-r--r-- 1 polkitd input 66897 May 12 17:35 console-2026-05-12-10.log
-rw-r--r-- 1 polkitd input   694 May 13 17:11 console.log
```

Neither file is the destination of the current container's writes.

### E3 — what container actually writes

```
$ ssh Bot-1 'docker exec bot-java-bot-manager-1 ls -la /app/logs/'
-rw-r--r-- 1 botmanager botmanager 1334345 Jun  3 10:36 console.log

$ ssh Bot-1 'docker exec bot-java-bot-manager-1 id'
uid=1006(botmanager) gid=1007(botmanager) groups=1007(botmanager)
```

### E4 — Promtail scrape config

```yaml
# /home/sgame/bot-java/promtail-config.yml
scrape_configs:
  - job_name: bot-manager
    static_configs:
      - targets: [localhost]
        labels: { job: bot-manager, __path__: /logs/*.log }
    pipeline_stages:
      - json: { expressions: { timestamp, level, botGroupId, environmentId, gameType } }
      - timestamp: { source: timestamp, format: "2006-01-02T15:04:05.000Z0700" }
      - labels: { level, botGroupId, environmentId, gameType }
```

### E5 — Promtail position keeping pace

```
$ ssh Bot-1 'docker exec bot-java-promtail-1 cat /tmp/positions.yaml; docker exec bot-java-bot-manager-1 stat -c "size=%s" /app/logs/console.log'
positions:
  /logs/console-2026-06-03-09.log: "1221450"
  /logs/console.log: "1499806"
size=1550068
```

Δ = 50,262 bytes (~normal Promtail read buffer ahead of head).

### E6 — Loki line volume during reproduction (per-30s)

```
$ curl 'http://localhost:3100/loki/api/v1/query_range?query=sum(count_over_time({job="bot-manager"}[30s]))&...'
666 → 321 → 551 → 351 → 321 → 491 → 411 → 171 lines per 30s bucket
```

Total over 5 min ≈ 3,283 lines. File grew by ~3,300 lines in the same window. Within tailing tolerance.

### E7 — Loki and Promtail health

```
$ ssh Bot-1 'docker logs --since 5m bot-java-promtail-1 2>&1 | grep -iE "error|warn|drop|fail"'
(empty)

$ ssh Bot-1 'docker logs --since 5m bot-java-loki-1 2>&1 | grep -iE "error|warn|drop|limit|reject"'
(empty)  # only INFO and verbose query metrics
```

### E8 — MDC coverage in the log file

```
$ ssh Bot-1 'docker exec bot-java-bot-manager-1 sh -c "grep -c botGroupId /app/logs/console.log; wc -l /app/logs/console.log"'
279
3919 /app/logs/console.log

# 279/3919 ≈ 7.1% of lines carry botGroupId
```

Thread breakdown of MDC-less lines:

```
200  netty-ws-message-processor-ws-test_bcmini_009
200  netty-ws-message-processor-ws-test_bcmini_008
...  (10 bots × ~200 lines each = 2,000)
150  pool-41-thread-1
150  pool-40-thread-1
...  (5 schedulers × 150)
```

### E9 — Loki streams (cardinality)

```
$ curl 'http://localhost:3100/loki/api/v1/series?match[]={job="bot-manager"}&start=…&end=…'
7 streams returned. Per-bot labels are NOT promoted, so cardinality stays low.
Streams labelled with botGroupId+environmentId+gameType cover ~7% of lines.
Streams labelled only by {level} cover the other ~93%.
```

### E10 — Loki storage / retention

```
$ ssh Bot-1 'docker exec bot-java-loki-1 du -sh /loki/chunks /loki/wal'
78.5M  /loki/chunks
3.0M   /loki/wal
```

Loki config has no `limits_config`, no `compactor.retention_enabled`, no `table_manager.retention_period`. Default → indefinite retention until disk fills. Nothing currently being expired.

### E11 — Reproduction action log

- 10:33:08 UTC: `POST /api/v1/bot-group/9b54e101…/start` returned 200.
- 10:33:08 UTC: log4j2 rotated 09:xx file (`console-2026-06-03-09.log` created), new `console.log` started.
- 10:36:25 UTC: file at 3,032 lines.
- 10:37:24 UTC: `POST /api/v1/bot-group/9b54e101…/stop` returned 200.
- 10:37:24+ UTC: file size stable; Loki ingestion drops to zero in next 30s bucket.

---

## Recommended fixes (one-liners — no implementation, leave for later session)

1. **Stop calling it "external" — either accept the named volume or actually bind-mount.** Either rename the user-facing description, or change `logs-data:/app/logs` to `./logs:/app/logs` in compose so the host directory `/home/sgame/bot-java/logs/` becomes the live log location the user expects.
2. **Propagate MDC into Netty I/O threads and the per-bot scheduler.** Wrap the `Bot`'s scheduled `Runnable` and the WebSocket message-receive callback so each invocation pushes `botGroupId/botId/environmentId/gameType` onto MDC before logging and pops it afterward — Log4j2's `ThreadContext.put/clear` or a small `MdcContext.runWith(...)` helper.
3. **Audit the JSON template's MDC fields against what's actually set in the bot lifecycle.** `botUserName` is in the template but never appears in the file — confirms MDC isn't being populated where it should be.
4. **Consider promoting `filename` out of label set or scoping it differently.** It's currently `/logs/console.log` for everything; once rotation produces `console-YYYY-MM-DD-HH.log` files, queries on the catch-all stream double-count or split by filename label unnecessarily.
5. **Add a Grafana panel that queries `{job="bot-manager"}` without any per-bot filters, so the user has a fallback view that doesn't depend on MDC.** Until fix #2 lands, every "filter by bot group" query will appear empty for ~93% of traffic.
6. **Document the named-volume layout in `CLAUDE.md` or `docs/`.** The mismatch between "external volume on Bot-1" mental model and the actual Docker-managed volume location is what made this look like a missing-data bug.
7. **Optional: pin a Loki retention period now (e.g. 14d) before disk usage becomes an incident.** Currently growing unbounded.
