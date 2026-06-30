# Release log — BETTING_STRATEGIES

Mode: bot
Branch: feat/betting-strategies
Image: vingame-bot:latest (built 2026-06-17T09:50:25Z)
Image sha: sha256:5a9b685cf0e789e5987f7e86595677b9d7bcfa6d1673d4db1658c52a69543500
Date: 2026-06-17T09:50:25Z – 2026-06-17T10:00Z (≈10 min)
Plan: docs/plans/BETTING_STRATEGIES.md
Runbook: docs/reviews/BETTING_STRATEGIES/release.md

Pre-flight (handoff): build green (647/647 tests), QA PASS, Compliance PASS,
Reviewer PASS after fix pass. Working tree clean (untracked review notes
only; no modified files).

---

## Build

- `mvn clean install`: **PASS** (BUILD SUCCESS, 19.250s, 647/647 tests passing).
- `docker build --no-cache --platform linux/amd64 -t vingame-bot:latest .`:
  **PASS** (image sha256:5a9b68…543500).
- `docker save -o bot.tar vingame-bot:latest`: **PASS** (392 193 536 bytes).

## Ship

- `sftp Bot-1:/home/sgame/bot-java <<< "put bot.tar"`: **PASS**
  (remote size matches local: 392 193 536 bytes).

## Deploy (Bot-1)

- `docker compose down`: **PASS** (bot-manager, mongo, loki, promtail,
  prometheus, grafana all stopped).
- `docker image rm vingame-bot:latest`: **PASS** (prior image deleted —
  3 layers removed).
- `docker load -i bot.tar`: **PASS** ("Loaded image: vingame-bot:latest").
- `docker compose up -d`: **PASS for bot deploy** (bot-manager, mongo,
  loki, promtail started successfully). Prometheus and Grafana failed to
  start because host port 9090 was already bound to a prior prometheus
  process — **infra container failure unrelated to bot-manager**; bot
  deploy is not blocked, the Phase 6 release does not depend on those
  containers, and bot-manager scrapes its own /actuator/prometheus endpoint
  internally for the smoke checks.

## Smoke test

- `docker ps` shows bot-manager healthy after 75s start_period: **PASS**
  (`Up 55 seconds (healthy)` at first check; `Up 6 minutes (healthy)` at
  final check).
- Spring Boot ready log: **PASS** —
  `09:53:22.362 [main] INFO Starter [//] - Started Starter in 10.741 seconds (process running for 12.035)`.
- Auto-start log: **PASS** —
  `09:53:21.151 [main] INFO BotGroupBehaviorService [//] - Bot Manager startup complete. 3 bot groups running`.
- ERROR count over startup window: **0**.
- WARN observations: a burst of `BotMemory.completeRound: sessionId
  mismatch (EndGame sessionId=…, in-flight sessionId=0)` WARNs at
  09:53:36 across the Fruit Shop bots — these are the
  documented benign WARNs introduced by Phase 2 (bots that join a game
  mid-round receive the EndGame before any StartGame, so the in-flight
  round is discarded). No reconnect cascade, no scheduler spin, all bots
  resumed normal bet flow within the same second.

## Plan verification (§Verification / Phase 6 runbook)

### §A — Pre-migration sanity

| Step | Command | Expected | Actual | Result |
|---|---|---|---|---|
| A.1 | `curl http://localhost:8080/actuator/health` | HTTP 200, `"status":"UP"` | `{"status":"UP","components":{"diskSpace":"UP","mongo":"UP","ping":"UP","ssl":"UP"}}` | **PASS** |
| A.2 | `GET /api/v1/bot-group/` | HTTP 200; `strategyMix` absent (fallback in effect pre-migration) | 13 groups returned; first group `Test group 097` has no `strategyMix` field | **PASS** |
| A.3 | `POST /api/v1/game/filter/` body `{}` (no list-all GET exists on `GameController`; runbook had wrong path) | `optionAffinities` present, no `numberOfOptions` / `bettingOptions` on the wire | 7 games returned; every game shows `optionAffinities` (e.g. TaiXiu Seven → `{"0":1,…,"9":1}`); no legacy fields | **PASS** |
| A.4 | `GET /api/v1/bot-group/40fa3749-…/health` (XD test group) | Every bot shows `strategyId: "RANDOM"` | 20/20 bots, all `strategyId=RANDOM` (`{"RANDOM": 20}`) | **PASS** |
| A.5 | Logs show one INFO "assigned strategy" per bot | N bots → N INFO lines | 90 `BotGroupBehaviorService - Bot …: assigned strategy RANDOM` lines (90 = total bot count across the 3 auto-started groups) | **PASS** |

### §B — `games` collection migration

| Step | Query | Expected | Actual | Result |
|---|---|---|---|---|
| B.1.1 | `db.games.countDocuments({optionAffinities:{$exists:false}})` | record as N_GAMES_BEFORE | **7** | recorded |
| B.1.2 | sanity — both `numberOfOptions` and `optionAffinities` | 0 | **0** | **PASS** |
| B.1.3a | Phase A: `updateMany` with `bettingOptions` list | matched/modified for docs with explicit bettingOptions | matched 0, modified 0 (no docs have explicit bettingOptions on this env) | **PASS** |
| B.1.3b | Phase B: `updateMany` with `numberOfOptions` | matched/modified sum to N_GAMES_BEFORE (7) | matched 7, modified 7 — total 0+7 = 7 ✓ | **PASS** |
| **B.1.4** | `db.games.countDocuments({optionAffinities:{$exists:false}})` | **0** | **0** | **PASS** |
| **B.1.5** | `db.games.countDocuments({numberOfOptions:{$exists:true}})` | **0** | **0** | **PASS** |
| **B.1.6** | `db.games.countDocuments({bettingOptions:{$exists:true}})` | **0** | **0** | **PASS** |
| B.1.7 | `db.games.findOne({}, {name:1, optionAffinities:1})` | non-empty object, int-string keys, value 1 | `{_id:"3cda…7761", name:"TaiXiu Seven", optionAffinities:{"0":1,"1":1,…,"9":1}}` | **PASS** |

### §C — `botGroups` collection migration

| Step | Query | Expected | Actual | Result |
|---|---|---|---|---|
| C.2.1 | `db.botGroups.countDocuments({strategyMix:{$exists:false}})` | record as M_GROUPS_BEFORE | **13** | recorded |
| C.2.2 | `updateMany` set `strategyMix = [(RANDOM,1.0)]` | matched/modified = 13 | matched 13, modified 13 ✓ | **PASS** |
| **C.2.3** | `db.botGroups.countDocuments({strategyMix:{$exists:false}})` | **0** | **0** | **PASS** |
| **C.2.4** | non-empty `strategyMix` count == total botGroups count | 13 == 13 | 13 == 13 ✓ | **PASS** |
| C.2.5 | `db.botGroups.findOne({}, {name:1, strategyMix:1})` | `[{strategyId:"RANDOM", weight:1.0}]` | `{_id:"111", name:"Test group 097", strategyMix:[{strategyId:"RANDOM", weight:1}]}` | **PASS** |

### §D — Post-migration smoke

| Step | Command | Expected | Actual | Result |
|---|---|---|---|---|
| D.3.1 | `GET /api/v1/bot-group/40fa…/health \| .bots[0].strategyId` | `"RANDOM"` | `RANDOM` (20/20 bots) | **PASS** |
| D.3.2 | `POST /api/v1/game/filter/ {}` (instead of nonexistent `GET /api/v1/game/`) → keys | include `optionAffinities`, exclude `numberOfOptions` / `bettingOptions` | keys = `[id, brandCode, productCode, name, description, gameType, pluginName, offset, md5, optionAffinities]` — no legacy fields | **PASS** |
| D.3.3 | `PATCH /api/v1/bot-group/{id}` (runbook had `PATCH /api/v1/bot-group` — actual route is `/{id}`) with `{strategyMix:[…RANDOM,1.0…]}` → HTTP 200 → GET → strategyMix | HTTP 200, GET returns the same shape | PATCH HTTP 200, GET `strategyMix = [{strategyId:"RANDOM", weight:1.0}]` | **PASS** |
| D.3.4 | `/actuator/prometheus \| grep bot_bets_placed_total` | at least one non-zero value | 2 series with non-zero counters (`Fruit Shop`=353 200, `Bau Cua`=194 400) | **PASS** |

### Five required zero-count verifications

All five returned 0 (or the documented equality in C.2.4):

1. B.1.4 (`games` missing `optionAffinities`): **0** ✓
2. B.1.5 (`games` with legacy `numberOfOptions`): **0** ✓
3. B.1.6 (`games` with legacy `bettingOptions`): **0** ✓
4. C.2.3 (`botGroups` missing `strategyMix`): **0** ✓
5. C.2.4 (non-empty `strategyMix` count == total botGroups): **13 == 13** ✓

## Verdict

**PASS**

Bot-manager image deployed to Bot-1, smoke checks clean (Spring up in 10.7 s,
3 groups auto-started, 90 bots assigned RANDOM strategy, no ERRORs), Mongo
migration completed deterministically with all five required verification
queries at the expected value. Post-migration smoke confirms wire shape is
clean (`optionAffinities` only, no legacy fields), per-bot health DTO carries
`strategyId`, PATCH-update of `strategyMix` round-trips, and bots are
actively placing bets (non-zero `bot_bets_placed_total` counters).

## Notes / runbook drift

Three runbook commands needed adjustment on the day (recorded so the
runbook can be patched on the next release):

1. `GET /api/v1/bot-group/` works (with the trailing slash). `GET /api/v1/game/`
   returns 404 — `GameController` exposes `/types`, `/{id}`,
   `/{brand}/{product}`, and `POST /filter/`. The §A.3 and §D.3.2 commands
   in the runbook are wrong; `POST /api/v1/game/filter/` body `{}` returns
   the equivalent list.
2. `PATCH /api/v1/bot-group` (no id in path) returns 404. Correct route is
   `PATCH /api/v1/bot-group/{id}`. The §D.3.3 command in the runbook is
   wrong.
3. No `jq` installed on Bot-1; substituted `python3 -m json.tool` / inline
   python on the local Mac, fed by a curl-to-file over ssh.

None of these affect the verdict — all four pre-flight, the two-phase Mongo
migration, and all four post-migration smoke checks completed successfully.

The prometheus / grafana infra containers failed to start because host port
9090 was already bound. This is **not** a bot-manager issue and does not
affect the release (bot-manager exposes its own /actuator/prometheus on
8080, which is what the §D.3.4 check uses). The prior prometheus instance
appears to have survived `docker compose down` because it was started
outside the compose project. Out of scope for this release; flagged for
the infra owner.
