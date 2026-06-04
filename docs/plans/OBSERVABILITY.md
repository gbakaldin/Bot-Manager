# Observability Plan — Grafana Metrics for Bot Manager

Owner: gleb · Target demo: Friday

## Goal

Surface a full set of bot-health and game-server-health metrics in Grafana for the upcoming demo. Pair the existing Loki/Promtail log pipeline with a Prometheus + Micrometer metrics pipeline driven from the bot-manager itself.

---

## Findings — Current State

### Already wired

| Component | Version | Status |
|---|---|---|
| Grafana | 11.4 | Running in `docker-compose.yml`; only Loki datasource provisioned |
| Loki | 3.3.2 | Running, log aggregation working |
| Promtail | 3.3.2 | Reads `/logs/*.log`, parses JSON, extracts labels `level`, `botGroupId`, `environmentId`, `gameType` |
| Log4j2 JSON layout | — | Writes structured logs to `/app/logs/console.log` |
| Spring Boot Actuator | 3.4.0 | Exposes `health, info, metrics, loggers` on `:8085` (main port) |
| `BotMdc` | — | Puts `botGroupId`, `environmentId`, `gameType`, `botId`, `userName` into MDC |

### Not yet wired

- **No `micrometer-registry-prometheus` dependency** → `/actuator/prometheus` does not exist.
- **No Prometheus container** in `docker-compose.yml`.
- **Grafana** has only Loki as a datasource.
- **No Micrometer counters/gauges** in code. Existing per-bot counters (`totalBetsPlaced`, `totalBetAmount` on `Bot.java:54-56`) are in-memory `AtomicLong` only — not exported.
- **`lastRoundWinnings` is declared but never written** (`Bot.java:58`). RTP and drain cannot be computed today.
- **Game-server totals** (total user bets, total winnings, total players) are not collected. Per the new design, some games carry these in their messages; others (slots) do not.

---

## Per-Metric Readiness

### Bot metrics

| Metric | Source | Status | Notes |
|---|---|---|---|
| Total running bot groups | `BotGroupBehaviorService.runningGroups.size()` | ✅ trivial gauge | |
| Total managed bots | `sum(BotGroupRuntime.botInstances.size())` | ✅ trivial gauge | |
| Messages per second | Scenario `onMessage` callbacks | ⚠️ add counter | Tag by `cmd` (subscribe / startGame / updateBet / endGame) |
| Failure rate | `transitionStatus(DEAD)` callsite | ⚠️ add counter | Increment once per DEAD transition |
| Restart on failure rate | `Bot.triggerFullReconnect()` (`Bot.java:275`) and `runWsReconnectLoop` | ⚠️ add counter | Tag by `reason` (watchdog vs ws-disconnect vs reauth-cycle) |
| Downtime | timestamp on entering DEAD per bot / group | ⚠️ add counter | Per user clarification: count time bots/groups spend in DEAD state. Group STOPPED is intentional and excluded |
| RTP per game | `bot_bet_amount_total` and new `bot_winnings_total` | ⚠️ needs winnings refactor | RTP = winnings / bets, grouped by `gameType` |
| Auto-deposit frequency | `Bot.deposit()` callback (`Bot.java:181`) | ✅ easy counter | Tag by `outcome` (success/failure) |
| Daily money drain | `bot_bet_amount_total - bot_winnings_total` over 24h | ⚠️ derived | Falls out from the above once winnings work |

### Game-server metrics

| Metric | Source | Status | Notes |
|---|---|---|---|
| Login health | `ApiGatewayClient.authenticate()` | ✅ easy counter | Tag `outcome=success|failure`, dimension `gateway` |
| Verify token health | `ApiGatewayClient.verifyToken()` + `getBalance()` | ✅ easy counter | Same shape |
| WebSocket health | `Bot.isConnected()` + connect/disconnect events | ⚠️ add counter + gauge | `bot_ws_connections_total`, gauge of currently-open |
| Game health (stream activity) | `BettingMiniGameBot.onWatchdogExpired` | ✅ easy counter | Watchdog already exists; fires when no game messages for N seconds |
| Bot bet share | bot total ÷ (user-reported total) | ⚠️ partial | Only games whose messages include user totals contribute (see new design below) |
| Bot win share | same | ⚠️ partial | |
| Bot jackpot win share | bot jackpot count ÷ user jackpot count (or value) | ⚠️ partial | EndGame messages have `iJp` (is jackpot) and `tJpV` (jackpot value) per-message; need bot-vs-user attribution |
| Bot user share | unique bots ÷ unique players this round | ⚠️ partial | Same source-availability constraint |

---

## Architecture Decisions (locked in)

1. **Stack:** Add Prometheus alongside Loki. Micrometer Prometheus registry on `/actuator/prometheus`; Grafana gets a second datasource. Loki stays for log search.
2. **Winnings capture:** After each `onEndGame`, refetch balance, compute `win = newBalance - (oldBalance - betAmountThisRound)`. The bet amount snapshot has to be taken at round start (not end) because `expectedCurrentBalance` is decremented eagerly per bet — see implementation note below.
3. **Downtime semantics:** Count bot-DEAD-seconds and group-DEAD-seconds. Group STOPPED is intentional and excluded. A group cannot enter DEAD without first being deliberately started — so any DEAD time is a real incident.
4. **Real-player share metrics:** Some games (betting mini-game type) carry `totalBet` and `totalWinnings` for the whole round in their messages. Others (slots) do not, because each player has their own session. Design:
   - Add `updateTotalUserBet(long)` and `updateTotalUserWinnings(long)` to `Bot` base class.
   - Each game-specific bot script calls these from its `onEndGame` handler **only if** the totals are present in that game's protocol.
   - Slot-type bots never call them — their share metrics simply won't be reported, which is the correct outcome.
   - Non-bot share = total − bot-side counters (computed in Grafana via PromQL).

---

## Plan

### Phase 1 — Infrastructure

1. Add to `pom.xml`:
   ```xml
   <dependency>
     <groupId>io.micrometer</groupId>
     <artifactId>micrometer-registry-prometheus</artifactId>
   </dependency>
   ```
2. `application.properties`:
   ```
   management.endpoints.web.exposure.include=health,info,metrics,loggers,prometheus
   management.metrics.tags.application=bot-manager
   ```
3. Add `prometheus` service to `docker-compose.yml` scraping `bot-manager:8085/actuator/prometheus`.
4. Add Grafana provisioning: `grafana/provisioning/datasources/prometheus.yml`.

### Phase 2 — Bot-side instrumentation

Common tags on every metric: `groupId`, `environmentId`, `gameType`.

**Counters:**
- `bot_messages_total{cmd}` — incremented in `BettingMiniGameBot.onSubscribe / onStartGame / onUpdate / onEndGame`
- `bot_failures_total` — incremented in `Bot.transitionStatus` when next == DEAD
- `bot_reconnects_total{reason}` — incremented in `Bot.triggerFullReconnect` (reason="watchdog" or "ws-disconnect") and `runWsReconnectLoop`
- `bot_auto_deposits_total{outcome}` — incremented in `Bot.deposit()` callback
- `bot_bets_placed_total` and `bot_bet_amount_total` — replace or wrap the existing `AtomicLong`s in `Bot.creditBalance`
- `bot_winnings_total` — populated by new winnings path (Phase 3)
- `bot_login_total{outcome}`, `bot_verify_token_total{outcome}` — in `ApiGatewayClient`
- `bot_watchdog_expired_total` — incremented in `BettingMiniGameBot.onWatchdogExpired` (game-stream silence)
- `bot_ws_connections_total{event=opened|closed}` — `Bot.configureClient` callbacks

**Gauges:**
- `bot_groups_running` — backed by `runningGroups.size()`
- `bots_managed` — backed by sum of `botInstances`
- `bots_by_status{status}` — backed by stream over runtime instances
- `ws_connections_open` — count of `bot.isConnected()`

**Downtime accumulators:**
- On `transitionStatus(DEAD)`: stamp `deadSince`. On any transition out of DEAD (including stop), increment `bot_dead_seconds_total` by `now − deadSince`.
- Same pattern for `BotGroupRuntime.markAsDead` and group stop, exposed as `group_dead_seconds_total`.
- For "currently dead" we also expose a gauge — but the counter is what Grafana queries for cumulative downtime windows.

### Phase 3 — Winnings (touches bot logic)

- Snapshot `betAmountThisRound` at `onStartGame` (reset to 0), accumulate as `creditBalance` is called.
- In `onEndGame`, schedule a short-delayed task on the bot's existing scheduler to refetch balance.
- Compute `winnings = newBalance - (oldBalance - betAmountThisRound)`. Update `lastRoundWinnings`, increment `bot_winnings_total` by max(winnings, 0). (Note: winnings could be 0 for a losing round — that's fine; drain comes from bets.)
- Cost analysis: ~1 verify-token call per bot per round. At 100 bots × 30s rounds → 3.3 req/s. Acceptable. If load testing pushes this higher, expose a `bot.winnings.sample-rate` property and sample.

### Phase 4 — Real-player share metrics (partial coverage by design)

- Add to `Bot.java`:
  ```java
  protected final AtomicLong totalUserBetThisRound = new AtomicLong(0);
  protected final AtomicLong totalUserWinningsThisRound = new AtomicLong(0);
  protected void updateTotalUserBet(long v) { totalUserBetThisRound.set(v); }
  protected void updateTotalUserWinnings(long v) { totalUserWinningsThisRound.set(v); }
  ```
- Expose counters `game_total_bet_amount_total{gameType}` and `game_total_winnings_total{gameType}` — incremented at `onEndGame` only if the per-game script populated the values.
- Each game-specific bot script (e.g. BomBot) extracts totals from its `onEndGame` payload and calls the setters. Games without totals (slots) skip this — their share metrics legitimately do not exist.
- Grafana computes `non_bot_bets = game_total_bet_amount_total - bot_bet_amount_total`, ratios, etc.

### Phase 5 — Grafana dashboards (provisioned)

`grafana/provisioning/dashboards/`:
1. **Bots** — running groups, managed bots, msg/s, failure rate, restart rate, downtime, RTP per game, daily drain, auto-deposit frequency
2. **Game server** — login/verify/WS/game health, watchdog firings, bot vs user shares (only games with totals available)

---

## Implementation Notes / Concerns

- **`expectedCurrentBalance` is decremented eagerly on bet placement.** The winnings calc must snapshot `oldBalance` *before* the first bet of the round, and snapshot `betAmountThisRound` independently. Don't try to derive bets from balance delta.
- **Cardinality:** do not tag metrics by `username` or `botIndex`. `groupId × environmentId × gameType` is the right granularity.
- **Game health gauge:** the watchdog already triggers on stream silence — make sure the metric increment happens *before* `triggerFullReconnect`, otherwise restart events overwrite the signal.
- **Auto-start interaction:** `BotGroupBehaviorService.@PostConstruct onStartup` auto-starts groups. Metrics registration must happen before that so first-round events aren't lost.

---

## Open Items

- **Game server endpoint** for total-user metrics on games where the bot message protocol *doesn't* expose them — not in scope for Friday. Per current design, those metrics will simply be unreported until the game protocol surfaces them or we get a server endpoint.
- **Drift between actual balance and `expectedCurrentBalance`** — the existing 1M-delta refresh logic in `checkBalance()` already covers correctness. The winnings refetch is independent and uses an explicit pre-bet snapshot.
- **Watchdog reason taxonomy** — currently a single `triggerFullReconnect(reason)` string. We'll normalise these to a small enum for the `reason` tag.

---

## Verification

Run after deploy on Bot-1.

1. **Prometheus endpoint exists.**
   `curl -fsS http://Bot-1:8080/actuator/prometheus | head -5`
   → expect Prometheus text-format output (lines beginning `# HELP` / `# TYPE`).

2. **Key bot-side metrics are registered.**
   `curl -fsS http://Bot-1:8080/actuator/prometheus | grep -E '^(bot_groups_running|bots_managed|bot_messages_total|bot_failures_total|bot_reconnects_total|bot_bets_placed_total|bot_winnings_total|bot_auto_deposits_total|bot_watchdog_expired_total) '`
   → expect each of those metric names to appear at least once.

3. **Start a bot group and watch counters move.**
   - `curl -X POST http://Bot-1:8080/api/v1/bot-group/<id>/start` → expect HTTP 200.
   - Wait 60 s.
   - `curl -fsS http://Bot-1:8080/actuator/prometheus | grep -E '^(bot_groups_running|bots_managed|bot_messages_total)'`
     → expect `bot_groups_running >= 1`, `bots_managed > 0`, `bot_messages_total > 0`.

4. **Server-side health log shows the group active.**
   `ssh Bot-1 'docker logs --tail 500 bot-manager 2>&1 | grep -E "Group .* health"' | tail -3`
   → expect at least one line, `dead: 0/<N>`.

5. **Grafana dashboards render.**
   - `http://Bot-1:3000/d/bots` — every panel populates within 30 s of opening.
   - `http://Bot-1:3000/d/game-server` — login / verify / WS / game-health panels show recent data.

6. **Winnings path actually fires.**
   After the bot group has run for at least one full game round, `curl ... | grep '^bot_winnings_total '` → expect a non-zero value (or, on a losing round, a `bot_bet_amount_total > 0` with `bot_winnings_total >= 0` and `lastRoundWinnings` populated in the `BotGroupHealthDTO` response from `GET /api/v1/bot-group/<id>/health`).

Stop the bot group at the end of verification:
`curl -X POST http://Bot-1:8080/api/v1/bot-group/<id>/stop`.
