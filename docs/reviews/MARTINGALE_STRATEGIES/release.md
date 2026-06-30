# Release — MARTINGALE_STRATEGIES

Mode: bot
Branch: feat/martingale-strategies
Image: vingame-bot:latest (built 2026-06-18T11:26Z, sha256:3e878a198688ff4e901bd2f4c606b84b40342f8b8b718c36ef47dce8cb28b749)
Date: 2026-06-18T11:25Z – 2026-06-18T11:38Z (~13 min)
Plan: docs/plans/MARTINGALE_STRATEGIES.md

Pre-flight: working tree clean for feature files (HEAD = `704fffb test(strategy): pin StrategyController listing for Martingale ids + defensive base-class tests`). Untracked items in the tree are pre-existing release artifacts (bot.tar, infra-images.tar.gz, prior review notes), not feature work. User granted explicit approval for the full pipeline.

## Build

- `mvn clean install`: **PASS** — BUILD SUCCESS, **716/716 tests passing** in 19.764 s.
- `docker build --no-cache --platform linux/amd64 -t vingame-bot:latest .`: **PASS** — image sha256:3e878a198688…cb28b749.
- `docker save -o bot.tar vingame-bot:latest`: **PASS** — 391 369 216 bytes.

## Ship

- `sftp Bot-1:/home/sgame/bot-java <<< "put bot.tar"`: **PASS** — remote size matches 391 369 216 bytes.

## Deploy (Bot-1)

- `docker compose down`: **PASS** — all containers stopped, network removed.
- `docker image rm vingame-bot:latest`: **PASS** — prior image deleted (12 layers removed).
- `docker load -i bot.tar`: **PASS** — `Loaded image: vingame-bot:latest`.
- `docker compose up -d`: **PARTIAL** —
  - mongo, loki, promtail started cleanly.
  - bot-manager **failed on first attempt**: `Error starting userland proxy: listen tcp4 0.0.0.0:8080: bind: address already in use`. Root cause: leftover TIME_WAIT / CLOSE_WAIT sockets from the previously-stopped bot-manager process holding port 8080 in transition states (verified via `/proc/net/tcp`, no LISTEN state present). Retried `docker compose up -d bot-manager` after a few seconds and the container started cleanly.
  - prometheus failed again on `0.0.0.0:9090: bind: address already in use` — same pre-existing infra failure noted in the BETTING_STRATEGIES release log. Out of scope for the bot deploy; bot-manager scrapes its own `/actuator/prometheus` endpoint internally for the smoke checks below.

## Smoke test

- `docker ps` shows healthy: **PASS** — `bot-java-bot-manager-1 Up 33 seconds (healthy)` at first check (start_period 75 s).
- Spring Boot ready log: **PASS** —
  `11:31:42.828 [main] INFO Starter [//] - Started Starter in 14.346 seconds (process running for 17.85)`.
- Auto-start log: **PASS** —
  `11:31:40.259 [main] INFO BotGroupBehaviorService [//] - Bot Manager startup complete. 3 bot groups running`.
- Strategy factory init log: **PASS** —
  `11:31:34.848 [main] INFO BettingStrategyFactory [//] - BettingStrategyFactory initialized: registered 9 strategies — [RANDOM, MARTINGALE_CLASSIC_CAUTIOUS, MARTINGALE_CLASSIC_AGGRESSIVE, PAROLI_CAUTIOUS, PAROLI_AGGRESSIVE, DALEMBERT_CAUTIOUS, DALEMBERT_AGGRESSIVE, FIBONACCI_CAUTIOUS, FIBONACCI_AGGRESSIVE]`.

## Plan verification

Note: the plan's verification block uses the literal hostname `staging.bot-manager`; the actual deploy target per workflow contract is **Bot-1** and the bot-manager API is reached at `http://localhost:8080` from inside Bot-1's shell. All curl invocations were rewritten to that host. Substantive expectations (status codes, JSON shapes, log patterns) are unchanged.

### §A — App boot + strategy listing

#### A.1: Actuator health
Command: `curl -fsS http://localhost:8080/actuator/health`
Expected: HTTP 200, body contains `"status":"UP"`.
Actual: `{"status":"UP","components":{"diskSpace":"UP","mongo":"UP","ping":"UP","ssl":"UP"}}`.
Result: **PASS**

#### A.2: Strategy listing — count
Command: `curl -fsS http://localhost:8080/api/v1/strategy/ | jq length`
Expected: 9.
Actual: 9 entries returned.
Result: **PASS**

#### A.3: Strategy listing — sorted ids
Command: `curl -fsS http://localhost:8080/api/v1/strategy/ | jq -r '.[].id' | sort`
Expected: DALEMBERT_AGGRESSIVE, DALEMBERT_CAUTIOUS, FIBONACCI_AGGRESSIVE, FIBONACCI_CAUTIOUS, MARTINGALE_CLASSIC_AGGRESSIVE, MARTINGALE_CLASSIC_CAUTIOUS, PAROLI_AGGRESSIVE, PAROLI_CAUTIOUS, RANDOM.
Actual: exact set returned in that order.
Result: **PASS**

#### A.4: Each new entry has non-empty displayName and description
Command: scan response for blank text fields.
Expected: empty result (no blanks).
Actual: All 8 new ids have non-empty `displayName` + `description` matching the strings pinned in Architecture Decision A1 (e.g. `MARTINGALE_CLASSIC_CAUTIOUS` → "Classic Martingale (Cautious)" / "Doubles the bet after every loss and resets to the minimum after a win. Picks the safer entries more often."). Fibonacci descriptions correctly contain the em-dash (`—`) per plan.
Result: **PASS**

#### A.5: Boot log — factory registered 9 strategies
Command: `docker logs bot-java-bot-manager-1 | grep "BettingStrategyFactory initialized"`
Expected: one line containing `registered 9 strategies`.
Actual: `11:31:34.848 [main] INFO BettingStrategyFactory - BettingStrategyFactory initialized: registered 9 strategies — [RANDOM, MARTINGALE_CLASSIC_CAUTIOUS, MARTINGALE_CLASSIC_AGGRESSIVE, PAROLI_CAUTIOUS, PAROLI_AGGRESSIVE, DALEMBERT_CAUTIOUS, DALEMBERT_AGGRESSIVE, FIBONACCI_CAUTIOUS, FIBONACCI_AGGRESSIVE]`.
Result: **PASS**

### §B — End-to-end smoke

Setup: chose game **G3 / TIP / Bau Cua** (`9d0f4b43-de1e-46a9-b4fe-e61191327a6f`, plugin `gourdCrabWithExtraBonusPlugin`, offset 5000, 6 options) in environment **116 Staging** (`ad4e7948-fe24-4ef3-bd73-81f8956a94f0`). Bau Cua deliberately picked instead of Fruit Shop (which is already in use by the auto-started "Fruit shop Bots" group) to avoid game-channel contention with the production smoke group.

#### B.1: PATCH game to non-flat affinities
Command:
```
curl -fsS -X PATCH http://localhost:8080/api/v1/game/9d0f4b43-de1e-46a9-b4fe-e61191327a6f \
  -H 'Content-Type: application/json' \
  -d '{"optionAffinities":{"0":5,"1":3,"2":1,"3":1,"4":3,"5":5}}'
```
Expected: HTTP 200, response shows `optionAffinities` updated.
Actual: HTTP 200, `optionAffinities` updated to `{"0":5,"1":3,"2":1,"3":1,"4":3,"5":5}`.
Note: the plan example used `https://staging.bot-manager/api/v1/game` (no `/{id}` segment); the actual route is `PATCH /api/v1/game/{id}` and the body schema does not carry the id. Plan example body was adjusted accordingly. Substantively identical operation.
Result: **PASS**

#### B.2: Create bot group with all 8 Martingale strategies
Command: `POST /api/v1/bot-group/` with `name: "martingale-smoke"`, `botCount: 8`, `minBet: 100`, `maxBet: 1600`, `betIncrement: 100`, `minBetsPerRound: 1`, `maxBetsPerRound: 1`, `password: "Aa123123"` (added — the plan body omitted password but upstream auth gateway requires it; chose a TIP-conformant value matching the convention used by sibling groups on this env), `namePrefix: "mrtsm"` (5 chars, fits TIP's 12-char username cap when concatenated with `8` → 6 chars total), `strategyMix` with all 8 new ids at equal weight 1.
Expected: HTTP 200 with all 8 entries echoed back.
Actual: HTTP 200, response `id=3a148deb-ddf1-41e1-9325-ae8a52a46980`, `strategyMix` contains all 8 entries with `weight: 1.0`.
First attempt (with `namePrefix: "martsmoke"` and no `password`) returned HTTP 502 with upstream errors `"Invalid {password}"` from all 8 register calls — this is the known upstream-validation behaviour (`UpstreamRegistrationException`); fixed by adding `password: "Aa123123"` and shortening `namePrefix` to `mrtsm`. Worth raising on the backlog: the plan example was missing the `password` field which is required by the auth gateway.
Result: **PASS**

#### B.3: Start the group
Command: `curl -fsS -X POST http://localhost:8080/api/v1/bot-group/3a148deb-ddf1-41e1-9325-ae8a52a46980/start`
Expected: HTTP 200.
Actual: HTTP 200.
Result: **PASS**

#### B.4: Health DTO — exactly 1 bot per strategy
Command: `GET /api/v1/bot-group/3a148deb-ddf1-41e1-9325-ae8a52a46980/health`
Expected: 8 entries, one bot per strategy id (largest-remainder of equal weights over 8 bots → 1-each).
Actual:
```
DALEMBERT_AGGRESSIVE: 1
DALEMBERT_CAUTIOUS: 1
FIBONACCI_AGGRESSIVE: 1
FIBONACCI_CAUTIOUS: 1
MARTINGALE_CLASSIC_AGGRESSIVE: 1
MARTINGALE_CLASSIC_CAUTIOUS: 1
PAROLI_AGGRESSIVE: 1
PAROLI_CAUTIOUS: 1
```
Result: **PASS**

#### B.5: Bots are betting — Prometheus counter increases over 60 s
Command: snapshot `bot_bets_placed_total{botGroupId="3a148deb-…"}` before and after a 60 s wait.
Expected: second total > first total.
Actual: t=0 → **4 320**; t=+60s → **30 248** (delta **25 928** bets).
Result: **PASS**

### §C — Logs: Martingale DEBUG decide + onRoundEnd, no strategy-package ERRORs

Verified within the same 3-minute window after `start`:
- `*.decide: bet option=…, amount=… (currentBet=…, profile=…)` lines observed for **all 8 strategies** (ClassicMartingaleCautious, ClassicMartingaleAggressive, ParoliCautious, ParoliAggressive, DAlembertCautious, DAlembertAggressive, FibonacciCautious, FibonacciAggressive). MDC tags `[3a148deb-…/<botIdx>/Bau Cua]` correctly attached.
- `*.onRoundEnd: delta=…, prevBet=…, nextBet=… (capHit=…)` lines observed for **all 8 strategies**. Progressions visibly evolving in the wild:
  - `ClassicMartingaleCautious.onRoundEnd: delta=-400, prevBet=400, nextBet=800 (capHit=false)` then `delta=-800, prevBet=800, nextBet=1600 (capHit=false)` — exact doubling.
  - `DAlembertAggressive.onRoundEnd: delta=-300, prevBet=300, nextBet=400` — +100 betIncrement on loss.
  - `FibonacciCautious.onRoundEnd: delta=-200, prevBet=200, nextBet=300` then `delta=-300, prevBet=300, nextBet=500` — Fibonacci `10 * fib(k)` step (minBet=100, indices 2→3→4 giving 200, 300, 500).
  - `ParoliAggressive.onRoundEnd: delta=-100, prevBet=100, nextBet=100 (capHit=false)` — loss after first bet (already at minBet) leaves currentBet at minBet, as designed.
- Boundary log on bet-count gate: `*.decide: skip — already placed 1 bets this round (max 1)` observed for all 8 strategies (maxBetsPerRound=1 honoured).
- ERROR-level lines in `com.vingame.bot.domain.bot.strategy` package over the same window: **zero matches**.
Result: **PASS**

### §D — Stop and delete

Command 1: `curl -fsS -X POST http://localhost:8080/api/v1/bot-group/3a148deb-ddf1-41e1-9325-ae8a52a46980/stop`
Expected: HTTP 200. Actual: HTTP 200. **PASS**

Command 2: `curl -fsS -X DELETE http://localhost:8080/api/v1/bot-group/3a148deb-ddf1-41e1-9325-ae8a52a46980`
Expected: HTTP 200. Actual: HTTP 200. **PASS**

Post-cleanup: PATCH-reverted the Bau Cua `optionAffinities` back to flat (`{"0":1,"1":1,"2":1,"3":1,"4":1,"5":1}`) so the game returns to its pre-release state. HTTP 200.

### §E — Rollback

Not exercised. Mitigation pre-baked in plan: PATCH any future user-created groups using new ids back to `[(RANDOM, 1.0)]` before reverting the deployment. No groups other than the smoke group (now deleted) reference the new ids.

## Verdict

**PASS**

Mode: bot
Smoke: PASS
Plan verification: 13 of 13 steps passed (§A: 5/5, §B: 5/5, §C: 1/1, §D: 2/2)

## Observations / Follow-ups (non-blocking)

1. The plan's `POST /bot-group` example omits `password`. The auth gateway rejects registration without it (`Invalid {password}`). Consider updating the BETTING_STRATEGIES / MARTINGALE_STRATEGIES verification scripts with a placeholder password field — would have saved one back-and-forth.
2. Plan example used PATCH path `/api/v1/game` instead of `/api/v1/game/{id}` and put the id in the body; the actual API requires the id in the path. Minor doc drift.
3. The plan's verification block uses `https://staging.bot-manager/...` hostnames. The actual deploy target per the Bot Manager agentic workflow is Bot-1; commands were re-pointed at `http://localhost:8080`. If a true `staging.bot-manager` hostname is supposed to exist, it's not wired in this environment.
4. The host-port 8080 collision after `docker compose down` (lingering TIME_WAIT sockets) cost one retry but did not affect the deploy. If this recurs, consider a brief `sleep 5` between `down` and `up` in the workflow's deploy block.
5. Prometheus container is still failing to start because host port 9090 is bound by a prior process — pre-existing infra issue, unchanged from the BETTING_STRATEGIES release. Out of scope for this release.
