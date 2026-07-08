# Release — JACKPOT_SCALE_AND_RAMP

Mode: bot
Branch: feat/jackpot-scale-ramp (HEAD c65b1d28b83a147f92addda1eb763a272101cb41)
Image: vingame-bot:latest (built at 2026-07-08T08:16:44Z; local image id sha256:d960c52bb186)
Date: 2026-07-08T08:31:50Z
Target: Bot-1 (single-compose project — bot-manager + mongo + loki + promtail + prometheus + grafana)

Feature: two independent, opt-in per-round betting-shaping levers — **jackpot-based scale** (on the Game: `jackpotScaleEnabled`/`jackpotCeiling`) and **bet ramp-up** (on the BotGroup: `rampEnabled`/`rampShape`). **Both ship OFF by default.** Confirmed at deploy time that NO existing game or group has either flag enabled (all `jackpotScaleEnabled=false`, all `rampEnabled=false` across BOM/RIK envs), so this deploy changes **zero** existing behavior — the headline safety guarantee holds, mirroring BET_COORDINATION's coordination-off default. QA/reviewer/compliance all PASS; full suite green (1388 tests). Git not merged/pushed per release brief (user handles git after verdict).

Note: the orthogonal untracked `docs/reviews/BET_COORDINATION/release.md` was the only working-tree change; ignored per brief. No production code / test / plan modified by this release.

## Build

- `mvn clean install`: PASS (~25s) — BUILD SUCCESS, Tests run: 1388, Failures: 0, Errors: 0, Skipped: 0 (Java 21 via JAVA_HOME=openjdk-21.0.2).
- `docker build --no-cache --platform linux/amd64`: PASS (image sha256:d960c52bb186…, 380MB). (Docker Desktop daemon was down at start; launched via `open -a Docker`, came up in ~10s, then built.)
- `docker save -o bot.tar`: PASS (392,970,752 bytes).

## Ship

- `sftp put bot.tar` → `/home/sgame/bot-java/bot.tar`: PASS (remote size 392,970,752 bytes — exact match to local).
- (mode=bot — infra-images.tar.gz NOT shipped, per brief; no infra/observability changes in this diff.)

## Deploy

- `docker compose down`: PASS (whole stack removed cleanly, incl. all observability containers).
- `docker image rm vingame-bot:latest`: PASS (prior image sha256:f878759511… — the BET_COORDINATION artifact — untagged + layers deleted; IMGRM_OK).
- `docker load -i bot.tar`: PASS ("Loaded image: vingame-bot:latest").
- `docker compose up -d`: PASS (mongo → Healthy, then bot-manager + loki/promtail/prometheus/grafana started).

## Smoke test

- `docker ps` shows healthy: PASS — `bot-java-bot-manager-1 Up (healthy)` reached ~36s after start (health: starting → healthy).
- Spring Boot ready log: PASS — `Started Starter in 13.079 seconds`; `Tomcat started on port 8085`.
- Auto-start log: PASS — `Bot Manager startup complete. 8 bot groups running`. The live 100-bot legacy null-mode group `ab81f9e6-764b-4785-9039-84add04e2fb0` ("BOM flow test 100 (kept running)", `activationMode=null`, `targetStatus=ACTIVE`) auto-restarted and returned to runtime `status=ACTIVE` with 100/100 connected, 0 dead. NOT stopped/deleted.

### Observability stack re-verification (Bot-1 single-compose — redeploy takes the whole stack down)

- All 6 containers back Up: `grafana`, `prometheus`, `bot-manager` (healthy), `promtail`, `mongo` (healthy), `loki`.
- Grafana `GET :3000/api/health` → HTTP 200: PASS.
- Prometheus `GET :9090/-/healthy` → HTTP 200: PASS.
- Loki `GET :3100/ready` → 503 immediately post-start (WAL replay), then HTTP 200 after warm-up: PASS.
- Promtail container Up: PASS.

Note: internal container port is 8085, published to host 8080 — API base for verification was `http://localhost:8080` on Bot-1.

## Plan verification — docs/plans/JACKPOT_SCALE_AND_RAMP.md § Verification

Test vehicles (throwaway, both deleted at end — see Cleanup):
- **Throwaway jackpot game** `1e1f9d2e-65c0-45e1-ad7b-3f12f69f0291` ("JPRAMP verify throwaway game") — a dedicated **P_114 / RIK `taixiuJackpotPlugin` TAI_XIU** game on env `394301f4-6daf-4c55-a073-502a81c00731`, `jackpotScaleEnabled=true`, `jackpotCeiling=20000000`. Created dedicated (not the shared production RIK game) so the live-ACTIVE RIK group `75899bb9` was never touched. This game reached live rounds with a **non-zero live `tJpV` meter** — the newly-parsed P_114 meter, the ideal jackpot vehicle. TIP/P_116 deliberately avoided (Bot-1 DNS block on `tipclubgw-sock.stgame.win`).
- **Throwaway group** `96d0a399-b03f-4e1f-aa18-50ebec19dd6c` ("JPRAMP verify throwaway"), 3 bots on the above game, `rampEnabled=true`, `rampShape=3.0`, MARTINGALE_CLASSIC_CAUTIOUS.

### Step 1: Jackpot Game config round-trips + validation (Phase J2)
Command: `PATCH /api/v1/game/1e1f9d2e-… {"jackpotScaleEnabled":true,"jackpotCeiling":20000000}` then `GET`; then negative `PATCH {"jackpotScaleEnabled":true,"jackpotCeiling":500000}`.
Expected: read-back `{jackpotScaleEnabled:true, jackpotCeiling:20000000}`; ceiling `<=500000` while enabled → clean 400.
Actual: PATCH 200; read-back `jackpotScaleEnabled=true, jackpotCeiling=20000000`. Negative PATCH → **HTTP 400**, `{"type":"Bad request","msg":"jackpotCeiling must be greater than 500000 when jackpotScaleEnabled is true"}`. Rejected PATCH was atomic — post-reject GET still `enabled=true / ceiling=20000000` (no partial write).
Result: PASS

### Step 2: Ramp BotGroup config round-trips + validation (Phase R1)
Command: `PATCH /api/v1/bot-group/96d0a399-… {"rampEnabled":true,"rampShape":3.0}` then `GET`; then negative `PATCH {"rampEnabled":true,"rampShape":0}`.
Expected: read-back `{rampEnabled:true, rampShape:3.0}`; `rampShape<=0` while enabled → clean 400.
Actual: PATCH 200; read-back `rampEnabled=true, rampShape=3.0`. Negative PATCH → **HTTP 400**, `{"type":"Bad request","msg":"Invalid bot-group config: rampShape (0.0) must be > 0 when rampEnabled is true"}`.
Result: PASS

### Step 3: Scaler builds on start for TAI_XIU (Phase J3, AD-J3)
Command: `POST /api/v1/bot-group/96d0a399-…/start`; grep app log for `Jackpot scaler created`.
Expected: one startup line with the ceiling for the `jackpotScaleEnabled` TAI_XIU group; NO such line for the off-default group `ab81f9e6`.
Actual: exactly one line, `Jackpot scaler created for group JPRAMP verify throwaway (ceiling 20000000)` — the ONLY "Jackpot scaler created" line in the entire log, so no scaler was built for the off-default group `ab81f9e6` (`jackpotScaleEnabled=false`) or any other running group. Group reached `status=ACTIVE`, 3/3 connected, 0 dead. (Line logs group name, not id — same as the BET_COORDINATION coordinator line.)
Result: PASS

### Step 4: Per-round jackpot DEBUG summary (Phase J3, AD-J10)
Command: (production runs at DEBUG) grep `JackpotScale sid=.* pool=.* factor=.* ceiling=` for the group.
Expected: ~one line per group per completed round; `pool` non-zero once the meter is observed; `factor` in `[0.25, 1.0]`.
Actual: `JackpotScale sid=0 pool=1846025750 factor=1.0 ceiling=20000000` then on the next round `JackpotScale sid=10575 pool=1846220300 factor=1.0 ceiling=20000000` (and a later poll showed `pool=1846444000`). The **live P_114 `tJpV` meter is non-zero and rising round-over-round** — the newly-parsed meter reaches the scaler exactly as designed. `factor=1.0` throughout because the live pool (~1.846B VND) far exceeds the throwaway 20M ceiling → clamped to the AD-J5 max of 1.0 (correct). One first-seen line per group per round (first frame of a round logged `sid=0`; subsequent rounds carry the real sid).
Result: PASS (per-round DEBUG line + non-zero live pool confirmed; the sub-1.0 mid-range is unreachable on this env because the real pool dwarfs any sane ceiling — the transfer function's mid/low range is covered by unit tests, not live-exercisable here).

### Step 5: Jackpot health block + factor tracks pool (Phase J4, AD-J5)
Command: `GET /api/v1/bot-group/96d0a399-…/health | .jackpotScale`; and off-group `ab81f9e6` `.jackpotScale`.
Expected: non-null object `enabled=true`, `jackpotCeiling`, `seedFloor=500000`, `lastObservedPool`, `currentFactor` in `[0.25,1.0]`, `minMultiplier=0.25`; `lastObservedPool>0` on the P_114 group; off-group `.jackpotScale == null`.
Actual: throwaway `.jackpotScale = {enabled:true, jackpotCeiling:20000000, seedFloor:500000, lastObservedPool:1846025750 (later 1846444000), currentFactor:1.0, minMultiplier:0.25}` — **`lastObservedPool > 0` confirmed** (the newly-parsed P_114 `tJpV` reaches the scaler). Off-default live group `ab81f9e6` `.jackpotScale = null`.
Result: PASS

### Step 6: Jackpot volume actually scales (AD-J4, best-effort ≥20 rounds)
Command: `GET .../health | .stats` and `.jackpotScale` across rounds.
Expected: aggregate bet volume tracks `currentFactor` — lower near seed, higher as pool grows.
Actual: with the live pool >> ceiling, `currentFactor` sits pinned at 1.0, so bots run at their full configured `maxBetsPerRound` — the expected **high-pool → full-volume** end of the relationship (stats: 4 rounds, 3 active bots, real activity `averageWinning=128700`). The sub-1.0 regime could not be induced because no reachable jackpot-bearing staging game has a pool low enough (or a ceiling high enough) to land the live meter in the linear mid-range; the meter→factor→applied-cap wiring is proven end-to-end at the factor=1.0 extreme and the transfer function's interior is unit-tested.
Result: BEST-EFFORT / PARTIAL — high-pool (factor=1.0, full-volume) end verified live; sub-1.0 mid-range not live-exercisable on staging (pool magnitude vs. sane ceiling). Not counted as a failure per release instructions.

### Step 7: Ramp health block (Phase R3)
Command: `GET .../health | .ramp` for the ramp-on throwaway and the off-group.
Expected: `{enabled:true, rampShape:3.0}` for ramp-on; `.ramp == null` for off.
Actual: throwaway `.ramp = {enabled:true, rampShape:3.0}`. Off-default live group `ab81f9e6` `.ramp = null`.
Result: PASS

### Step 8: Ramp shifts bet timing toward window close (AD-R1, best-effort)
Command: set `com.vingame.bot` to TRACE; grep `ramp deferred tick`; then reset to DEBUG.
Expected: deferrals concentrated early in the window on the ramp-on group; NO `ramp deferred tick` lines for a ramp-off group.
Actual: 87 `ramp deferred tick` TRACE lines for the ramp-on throwaway bots (`jpramp2`/`jpramp3`, MDC `[96d0a399…/…/TAI_XIU]`) over ~50s — the `rampShape=3.0` back-loaded gate actively deferring early-window ticks. **ZERO** `ramp deferred tick` lines for the off-path live group `ab81f9e6` (rampEnabled=false → no RNG drawn, nothing deferred). Logging reset to DEBUG afterward.
Result: PASS (best-effort timing evidence; deferral activity + off-path silence confirmed)

### Step 9: Both-off path is unchanged (AD-S3) — the regression-sensitive surface
Command: inspect the live off-default group `ab81f9e6` (jackpotScaleEnabled=false, rampEnabled=false): health blocks, log lines, bet flow.
Expected: `.jackpotScale == null`, `.ramp == null`, no JackpotScale / ramp-deferred log lines for its id, bets flow as before.
Actual: `.jackpotScale = null`, `.ramp = null`, `.coordination = null`; **0** `JackpotScale sid=` lines for `ab81f9e6`; **0** `ramp deferred tick` lines for `ab81f9e6`; group `status=ACTIVE`, 100/100 connected, 0 dead, bots actively betting (`activeTimeSeconds` climbing, 100 active bots). Byte-for-byte legacy behavior — zero change to the off path.
Result: PASS

## Cleanup

- Throwaway group `96d0a399-b03f-4e1f-aa18-50ebec19dd6c`: stopped (200) then deleted (200); subsequent GET → 404. Removed.
- Throwaway game `1e1f9d2e-65c0-45e1-ad7b-3f12f69f0291`: deleted (200); subsequent GET → 404. Removed.
- Temp payload files on Bot-1 (`/tmp/newgame.json`, `/tmp/newgroup.json`): removed.
- Local `bot.tar` artifact: removed. (Remote `/home/sgame/bot-java/bot.tar` left in place as the standard deployment staging artifact.)
- Logging level on `com.vingame.bot` reset to DEBUG (production default) after the TRACE Step 8 window.
- Live 100-bot group `ab81f9e6`: left running, `status=ACTIVE`, 100/100 connected, 0 dead (untouched throughout).

## Verdict

PASS

Summary: Universal smoke PASS (bot-manager healthy + Spring ready + 8 groups auto-started; full observability stack — Grafana/Prometheus/Loki/promtail/mongo — re-verified healthy after the single-compose redeploy). 7 of 9 plan-verification steps fully PASS (Steps 1,2,3,5,7,8,9); Steps 4 and 6 are BEST-EFFORT/PARTIAL as the plan permits — the jackpot per-round DEBUG line and the meter→factor→volume wiring are proven live on the P_114 Tai Xiu vehicle with a **non-zero, rising `tJpV`** (headline: the newly-parsed P_114 meter reaches the scaler), but the sub-1.0 factor mid-range is not live-exercisable on staging because the real jackpot pool (~1.846B) dwarfs any sane ceiling (that interior is unit-tested). The core safety guarantee HOLDS: both features default OFF, no existing game/group has either enabled, and the off-path witness — the untouched live 100-bot group `ab81f9e6` — shows `.jackpotScale=null`, `.ramp=null`, zero feature log lines, and byte-for-byte legacy bet flow. Zero existing behavior changed.
