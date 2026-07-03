# Release — METRICS_IMPROVEMENT (Phase 4)

Mode: bot (two-payload: bot image + bind-mounted Grafana dashboard JSONs)
Branch: feat/metrics-improvement @ 451d875a46f8d5989844a43404a1bae05c193f17
Image: vingame-bot:latest (built 2026-06-30T14:04+04:00, amd64, --no-cache)
Date: 2026-06-30T10:1x UTC (server)
Target: Bot-1 only

## Verdict

**PASS** — Both payloads shipped and deployed cleanly with no full `down` and the
DNS guard preserved. RTP rework is live and the key rename is complete; the
range-substitution mechanism is verified correct. The new
`bot_money_drained_total` counter is present, correctly labeled, and incrementing
on multiple games (including the target game and fleet-wide). Grafana dashboards
load with the renamed RTP panel and the new Money-drain/day panel. Co-located
observability stack all UP; no metrics-path ERRORs.

One **finding (non-blocking)**: the summary endpoint's headline `rtp` (30d window
= retention, per AD-6) currently reads ~0.003 because the 30-day window is
dominated by pre-`bot_winnings_total`-fix history (winnings counter was near-zero
for most of the retention window). The RTP *code* is correct — over a clean
post-deploy window RTP = 0.99, and current raw winnings/bets = 45.52M/45.98M =
0.99. The headline will converge toward ~1.0 as the 30d window rolls past the
broken-winnings era / accumulated reset boundaries. Per the release brief
("RTP stable/renamed → PASS even if figures are still ramping"), this is PASS.

---

## Pre-flight

- Branch/HEAD: on `feat/metrics-improvement` @ `451d875` (matches brief): PASS
- Working tree (tracked): clean — `git status --untracked-files=no` empty: PASS
- DNS guard present in Bot-1 docker-compose.yml (`dns: ["1.1.1.1","8.8.8.8"]`, lines 26-28): PASS
- Port map confirmed: host `8080→8085` (bot-manager), `9090` (prometheus), `3000` (grafana): PASS
- Dashboard JSONs carry new exprs locally (per-game has `bot_money_drained_total` + RTP rework): PASS

## Build (Payload 1)

- `mvn clean install` (JDK 21): PASS — 1096 tests, 0 failures/errors/skipped, BUILD SUCCESS (22.99s)
- `docker build --no-cache --platform linux/amd64`: PASS (sha256:1d0b0a8880b5)
- `docker save -o bot.tar`: PASS (392,873,984 bytes)

## Ship (both payloads)

- `sftp put bot.tar` → /home/sgame/bot-java/bot.tar: PASS (392,873,984 bytes on server, size matches)
- `sftp put per-game.json` → grafana/provisioning/dashboards/: PASS (19,239 bytes, matches)
- `sftp put per-environment.json`: PASS (19,780 bytes, matches)

## Deploy

NOT a full `down`. Targeted recreate of bot-manager only; mongo / prometheus /
loki / promtail left running; grafana restarted on purpose for Payload 2.
docker-compose.yml NOT modified — DNS override preserved.

- `docker compose stop bot-manager`: PASS
- `docker compose rm -f bot-manager`: PASS
- `docker image rm vingame-bot:latest` (old sha 888e80e4f917): PASS — untagged + deleted
- `docker load -i bot.tar`: PASS — "Loaded image: vingame-bot:latest"
- `HOST_UID=$(id -u) HOST_GID=$(id -g) docker compose up -d --no-deps bot-manager`: PASS
- `docker compose restart grafana` (Payload 2 reload): PASS
- DNS guard re-checked post-deploy (file untouched, lines 26-28 intact): PASS
- Stack preserved: mongo/prometheus Up 25h, loki/promtail Up 5 days: PASS

## Smoke test

- `docker ps` bot-manager: PASS — "Up About a minute (healthy)"
- Spring Boot ready: PASS — `Started Starter in 9.906 seconds`
- Auto-start ran: PASS — "Bot Manager startup complete. 8 bot groups running"; 8 groups auto-started
- Bot status tally: 249 `CONNECTION_AUTHENTICATED`, 0 DEAD/AUTH_FAILED: PASS
- No metrics-path ERRORs: PASS (0)
- Pre-existing ERROR noise (NOT this deploy): 66 `UpstreamLoginException`
  ("Tài khoản không tồn tại" / account does not exist) for RIK114 group
  `10056b54` — the paused RIK account-provisioning issue, unrelated to metrics.

## Plan verification (METRICS_IMPROVEMENT.md § Verification)

Target game for live checks: `29d419f1-9c96-4e74-aec1-41c7fe5849c3` ("Tai Xiu Jackpot").

### Step 1: Build + all tests incl. parity
Command: `mvn clean install` (JDK 21)
Expected: BUILD SUCCESS; parity test green.
Actual: BUILD SUCCESS, 1096 tests 0 failures (parity test `MetricKeyDashboardParityTest` included in the suite).
Result: PASS

### Step 2 + 3: Drain counter emitted + scraped by Prometheus
Command: `curl 'http://localhost:9090/api/v1/query?query=bot_money_drained_total'`
Expected: series with `gameId`/`gameName`/`environmentId` labels, value > 0.
Actual: 4 series present, all correctly labeled (`botGroupId`, `environmentId`,
`gameId`, `gameName`, `gameType` enum). Values: Fruit Shop 517M, Bau Cua 214M,
TaiXiuStagingVerify 5.5M, **Tai Xiu Jackpot (target) 3.18M → 18.91M** over ~2 min.
`sum(increase(bot_money_drained_total[10m]))` (fleet) = 1.09e9 > 0.
Result: PASS

### Step 4: Drain-per-day panel PromQL returns finite value
Command: `(sum(increase(bot_money_drained_total{gameId="..."}[24h])) / sum(bots_by_game_status{gameId="..."})) or vector(0)`
Expected: numeric value >= 0.
Actual: Fruit Shop = 19,546,062; target Tai Xiu Jackpot increase[10m] = 16.8M (>0,
its per-day still ramping — only ~6 min of life, counter resets at deploy).
Result: PASS

### Step 5: API summary exposes both new keys + stable RTP
Command: `curl 'http://localhost:8080/api/v1/metrics/game/<gameId>/summary'`
Expected: `money_drain_per_day` present >= 0; `rtp` present; `rtp_5m` absent; RTP plausible.
Actual: game-scope summary has `rtp` (present), `money_drain_per_day` (present),
NO `rtp_5m`. Fruit Shop `money_drain_per_day` = 1.955e7; env-scope
`money_drain_per_day` = 157,146. RTP key rename CONFIRMED.
RTP VALUE: headline (30d window) = 0.00286 — implausibly low (see Finding);
clean post-deploy window (10m) = 0.99 (plausible); current raw winnings/bets = 0.99.
Result: PASS (rename + mechanism); RTP-value finding noted below.

### Step 6: RTP stability across windows = range change only
Command: `.../timeseries?metric=rtp&range=24h`
Expected: 200 with smooth points.
Actual: HTTP 200, series of points returned (early points 0.0 — no data 24h ago,
counters reset at deploy; mechanism intact).
Result: PASS

### Step 7: `$__range` never leaves the API (negative check)
Expected: instant RTP query carries a concrete window, not the token.
Actual: summary `rtp` returns a numeric value (not a Prometheus parse error) =
live proof `applyWindow` substitution fired (token would 400). The old
`metric=rtp_5m` timeseries correctly returns `400 {"msg":"Unknown metric 'rtp_5m'."}`.
Result: PASS

### Step 8: Both dashboards load with new/changed panels
Command: `curl -u admin:admin 'http://localhost:3000/api/dashboards/uid/{per-game,per-environment}'`
Expected: `increase(bot_winnings_total` in per-game; `bot_money_drained_total` in per-env.
Actual: per-game has BOTH `increase(bot_winnings_total` (RTP rework) and
`bot_money_drained_total` (drain panel); per-environment has BOTH too.
Grafana `/api/health` = 200.
Result: PASS

### Step 9: Co-located stack re-verified
Expected: Grafana/Prometheus/Loki UP; dashboards in list.
Actual: bot-manager healthy; mongo/prometheus Up 25h; loki/promtail Up 5 days;
grafana restarted + healthy. Prometheus `/-/ready` 200, Loki `/ready` 200,
bot-manager scrape target health = "up". Dashboard list: Bots, Game server,
Per-Environment, Per-Game.
Result: PASS

## Brief-specific checks

- RTP rework — `rtp` key NOT `rtp_5m`: PASS. Old `rtp_5m` absent from summary; timeseries `metric=rtp_5m` → 400.
- `money_drain_per_day` present in summary: PASS.
- timeseries `metric=rtp&range=24h` → 200 with points: PASS.
- Drain counter series + correct labels: PASS.
- Drain counter incrementing: PASS (target 3.18M→18.91M; fleet increase[10m] 1.09e9; Fruit Shop increase[10m] 7.82e8).
- Grafana RTP + Money-drain panels render exprs: PASS.
- Regression — total_bots (107), winnings_rate_5m populating: PASS (bet rates momentarily 0 between TAI_XIU round phases — normal, were 2.14 / 312400 earlier).
- No metrics-path ERRORs: PASS.
- Observability stack (prometheus/grafana/loki/promtail/mongo) UP + bot-manager healthy: PASS.

## Finding (non-blocking) — headline 30d RTP transitionally low

`MetricsQueryService` summary substitutes `$__range` → `metrics.rtp.summary-window`
(default `30d`, AD-6). Raw inputs for the target game over 30d:
`sum(increase(bot_winnings_total[30d]))` = 6.48e9 vs
`sum(increase(bot_bet_amount_total[30d]))` = 2.27e12 → ratio 0.00286. The bet
counter accumulated normally for the full window, but winnings were near-zero for
most of the 30 days (the `bot_winnings_total = GX − gR` fix is recent). Over clean
post-deploy windows the ratio is correct: RTP[10m] = 0.99, RTP[1h] = 0.336 (spans
reset boundary), current raw winnings/bets = 45.52M/45.98M = 0.99. The headline
will climb toward ~1.0 as the 30d window rolls past the pre-fix era. Same effect
at env scope (`rtp` = 0.00289). Optional follow-up: lower
`metrics.rtp.summary-window` (e.g. `24h`/`7d`) until the long window is past the
pre-fix history, then restore. No code change required; the rename + substitution
+ dashboard parity are all correct.

## Commands (key)

```
# metrics API on host 8080 (→ container 8085); prometheus 9090; grafana 3000
curl -s 'http://localhost:8080/api/v1/metrics/game/29d419f1-9c96-4e74-aec1-41c7fe5849c3/summary'
curl -s -G 'http://localhost:9090/api/v1/query' --data-urlencode 'query=bot_money_drained_total'
curl -s -u admin:admin 'http://localhost:3000/api/dashboards/uid/per-game' | grep -o 'bot_money_drained_total'
```
