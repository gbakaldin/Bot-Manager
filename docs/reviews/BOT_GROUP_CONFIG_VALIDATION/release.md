# Release — BOT_GROUP_CONFIG_VALIDATION

Mode: bot
Branch: feat/bot-group-config-validation (tip fc1d131)
Image: vingame-bot:latest (built at 2026-06-23T11:19:12Z)
Date: 2026-06-23T11:30:00Z
Target: Bot-1 (staging). Host port 8080 → container port 8085 (`0.0.0.0:8080->8085/tcp`).

Note: working tree had no modified/staged production source — only untracked docs/reviews,
plans, and build artifacts. Clean for deploy. Single Compose project (bot-manager +
mongo + grafana + prometheus + loki + promtail); a bot redeploy cycles the whole stack,
so observability is re-verified below.

## Build

- `mvn clean install`: PASS (21.4s) — 828 tests, 0 failures/errors.
- `docker build --no-cache --platform linux/amd64`: PASS (sha256:2c11175f25fe…).
- `docker save -o bot.tar`: PASS (392,787,968 bytes).

## Ship

- `sftp put bot.tar`: PASS — remote size 392,787,968 bytes, exact match to local.
- (mode=infra) `sftp put infra-images.tar.gz`: N/A (mode=bot; infra image NOT shipped).

## Deploy

- `docker compose down`: PASS (DOWN_EXIT=0) — all 6 containers + network removed.
- `docker image rm vingame-bot:latest`: PASS (RM_EXIT=0) — prior image untagged/deleted.
- `docker load -i bot.tar`: PASS (LOAD_EXIT=0) — "Loaded image: vingame-bot:latest".
- `docker compose up -d`: PASS (UP_EXIT=0) — all 6 containers started; mongo Healthy gate passed.
- No betting-mini auto-start hang observed; no down/up retry needed.

## Smoke test

- `docker ps` shows healthy: PASS — `bot-java-bot-manager-1  Up About a minute (healthy)`.
- Spring Boot ready log: PASS — `Started Starter in 6.775 seconds`.
- Tomcat on port 8085: PASS — `Tomcat started on port 8085 (http) with context path '/'`.
- Auto-start log: PASS — `Bot Manager startup complete. 4 bot groups running`.
- **GameConfigValidatorFactory init: PASS** —
  `GameConfigValidatorFactory initialized: registered 5 validators — [BETTING_MINI, SLOT, TAI_XIU, CARD_GAME, UP_DOWN]`
- Observability re-verified UP:
  - Grafana `/api/health` → HTTP 200
  - Prometheus `/-/healthy` → HTTP 200
  - Loki `/ready` → HTTP 503 at first poll (transient ingester warmup), HTTP 200 ("ready") on re-poll ~20s later.
  - `docker compose ps`: all 6 containers Up; mongo + bot-manager Healthy.

## Resolved staging identifiers

- TIP env (G3, "116 Staging"): `ad4e7948-fe24-4ef3-bd73-81f8956a94f0`
- SLOT gid=204 ("SlotTipTest", TIP): gameId `c5a22c44-a848-4ec7-9136-342ed8e5cdea`
- BETTING_MINI ("Fruit Shop", TIP): gameId `45d42867-b3ff-4dd6-9a0c-1b371a394774`
- Existing ACTIVE groups (no-regression set): 116 Demo group `b1e80470…`, XD game test
  `40fa3749…`, Fruit shop Bots `4d7f6ac9…`, SlotRandomGroup5 `ec759951…`.

## Plan verification

All API calls via host `http://localhost:8080` (→ container 8085). Throwaway creates used
`existingGroup:true` to skip upstream registration; all deleted afterward (see Cleanup).

### Step 1: Framework booted (Phase 1)
Command: `grep "GameConfigValidatorFactory initialized" <app-log>`
Expected: a line listing all 5 GameType values.
Actual: `GameConfigValidatorFactory initialized: registered 5 validators — [BETTING_MINI, SLOT, TAI_XIU, CARD_GAME, UP_DOWN]`
Result: PASS

### Step (a) / Step 8: Universal required-field layer (Phase 4)
Command: `POST /api/v1/bot-group/` body `{"botCount":0}` (all basics absent/zero).
Expected: 400 with `{type:"Bad request", msg}` listing the missing fields.
Actual: HTTP 400 —
`{"type":"Bad request","msg":"botCount must be >= 1; environmentId must not be blank; gameId must not be blank; namePrefix must not be blank; password must not be blank"}`
Also: POST otherwise-valid body with `botCount:0` → HTTP 400 `{"type":"Bad request","msg":"botCount must be >= 1"}`.
Result: PASS

### Step (b1) / Steps 2-3: BETTING_MINI strict-grid invalid, aggregated
Command: `POST` BETTING_MINI (Fruit Shop gid) `minBet:100,maxBet:50,betIncrement:0,minBetsPerRound:3,maxBetsPerRound:1,maxTotalBetPerRound:10`.
Expected: 400 listing all behavior violations (not first-fail).
Actual: HTTP 400 —
`{"type":"Bad request","msg":"Invalid bot-group config: betIncrement (0) must be > 0; minBet (100) must be <= maxBet (50); minBetsPerRound (3) must be <= maxBetsPerRound (1); maxTotalBetPerRound (10) must be >= maxBet (50); maxTotalBetPerRound (10) must be >= minBet * minBetsPerRound (100 * 3 = 300)"}`
(All independent violations aggregated in one exception — confirms accumulate-not-first-fail. Grid divisibility check correctly omitted because betIncrement=0 makes `% betIncrement` undefined.)
Result: PASS

### Step (b2) / Step 4: BETTING_MINI valid config accepted
Command: `POST` BETTING_MINI `minBet:100,maxBet:500,betIncrement:10,minBetsPerRound:1,maxBetsPerRound:3,maxTotalBetPerRound:1000`.
Expected: 2xx.
Actual: HTTP 200, created id `33948260-138c-462c-9ae1-6679dfe8ce2a`.
Result: PASS

### Step 9: BETTING_MINI zero-min boundary accepted (AD-3)
Command: `POST` BETTING_MINI `minBet:0,minBetsPerRound:0,maxBet:100,betIncrement:10,maxBetsPerRound:5,maxTotalBetPerRound:1000`.
Expected: HTTP 200 (zero valid for minimum fields; grid (100-0)%10==0 holds).
Actual: HTTP 200, created id `dedc0f16-7de3-4936-9f51-3b018f1c9403`.
Result: PASS

### Step (c) / Step 5: SLOT no-op validator
Command: `POST` SLOT (gid=204) `minBet:999,maxBet:1,betIncrement:0,minBetsPerRound:9,maxBetsPerRound:1,maxTotalBetPerRound:1` (betting fields BETTING_MINI would reject).
Expected: HTTP 200 (validator ignores betting fields).
Actual: HTTP 200, created id `7e03472c-d5a1-401c-a412-6a07300aa94a`.
Result: PASS

### Step 6: Unknown gameId → 400 (AD-7)
Command: `POST` with `gameId:"does-not-exist"`.
Expected: HTTP 400 (not 404), msg referencing the game/gameId.
Actual: HTTP 400 — `{"type":"Bad request","msg":"Game not found: does-not-exist"}`
Result: PASS

### Step (d1) / Step 7: PATCH post-merge validation (AD-6)
Command: `PATCH /api/v1/bot-group/33948260…` body `{"maxBet":50}` (below persisted minBet:100).
Expected: HTTP 400.
Actual: HTTP 400 — `{"type":"Bad request","msg":"Invalid bot-group config: minBet (100) must be <= maxBet (50)"}`
(Proves validation runs on the merged entity, not the partial DTO.)
Result: PASS

### Step (d2) / Step 8 (PATCH arm): partial PATCH not subject to @Valid
Command: `PATCH /api/v1/bot-group/33948260…` body `{"chatEnabled":true}` (omits all universal basics).
Expected: HTTP 200 (PATCH path not under OnCreate @Valid group).
Actual: HTTP 200; response shows `chatEnabled:true`, `maxBet:500` preserved (PATCH did not corrupt config).
Result: PASS

### Step (e): No regression — existing groups unaffected
Command: status poll of the 4 pre-existing ACTIVE groups + recent log inspection.
Expected: existing betting-mini groups (116 Demo, Fruit Shop, XD) and the slot group still running; existing-group start path not broken by validation.
Actual:
- 116 Demo group `b1e80470…`: targetStatus=ACTIVE, actualStatus=ACTIVE.
- XD game test `40fa3749…`: ACTIVE/ACTIVE.
- Fruit shop Bots `4d7f6ac9…`: ACTIVE/ACTIVE.
- SlotRandomGroup5 `ec759951…`: ACTIVE/ACTIVE — and actively spinning, e.g.
  `Bot slotrnd1: spin result b=10000, winnings=100000, sid=57395` (carried slot feature live).
- All 4 auto-started on boot ("4 bot groups running") — validation does NOT block
  existing-group startup.
- Whole-stack log: 0 ERROR, 0 DEAD, 0 WARN in the recent (2000-line) window.
- playingStatus reads IDLE during the poll window (between-round timing); ACTIVE status +
  live spin log lines confirm bots are genuinely connected and acting.
Result: PASS

## Cleanup

Three throwaway groups created for verification (all `existingGroup:true`, never started):
- `33948260-138c-462c-9ae1-6679dfe8ce2a` (vtest-bm-valid) → DELETE 200
- `dedc0f16-7de3-4936-9f51-3b018f1c9403` (vtest-bm-zeromin) → DELETE 200
- `7e03472c-d5a1-401c-a412-6a07300aa94a` (vtest-slot-noop) → DELETE 200

Post-cleanup group count = 16 (matches pre-deploy baseline). Zero `vtest` groups remain.
Nothing left behind.

## Final staging state

- bot-manager: Up (healthy), Tomcat on 8085, 4 bot groups ACTIVE and operating.
- Observability: Grafana / Prometheus / Loki all UP (200).
- mongo: Healthy.
- No app ERROR / DEAD post-deploy.

## Verdict

PASS
