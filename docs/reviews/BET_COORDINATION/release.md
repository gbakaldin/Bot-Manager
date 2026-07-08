# Release — BET_COORDINATION

Mode: bot
Branch: main (HEAD 4afd19c — "docs(bet-coordination): reviewer + compliance verdicts (PASS)")
Image: vingame-bot:latest (built at 2026-07-07T14:02:08Z)
Date: 2026-07-07T14:14:00Z
Target: Bot-1 (single-compose project — bot-manager + mongo + loki + promtail + prometheus + grafana)

Merged features in this artifact: botgroup-game-management, TIMED_ACTIVATION (already live), BET_COORDINATION (new — primary verification target). Coordination is OFF by default; no existing group has `coordinationEnabled=true`, so this deploy changes no existing behavior.

## Build

- `mvn clean install`: PASS (26s) — BUILD SUCCESS, Tests run: 1328, Failures: 0, Errors: 0, Skipped: 0. (Stack traces seen in `-q` output were expected-exception controller-advice tests, all green.)
- `docker build --no-cache --platform linux/amd64`: PASS (image sha256:f878759511…)
- `docker save -o bot.tar`: PASS (392,955,392 bytes)

## Ship

- `sftp put bot.tar` → `/home/sgame/bot-java/bot.tar`: PASS (remote size 392,955,392 bytes — exact match to local)

## Deploy

- `docker compose down`: PASS (whole stack removed cleanly, incl. observability containers)
- `docker image rm vingame-bot:latest`: PASS (prior image untagged + layers deleted)
- `docker load -i bot.tar`: PASS ("Loaded image: vingame-bot:latest")
- `docker compose up -d`: PASS (mongo → healthy, then bot-manager + loki/promtail/prometheus/grafana started)

## Smoke test

- `docker ps` shows healthy: PASS — `bot-java-bot-manager-1 Up (healthy)` reached ~35s after start (health: starting → healthy).
- Spring Boot ready log: PASS — `Started Starter in 13.176 seconds`; `Tomcat started on port 8085`; `Bot Manager startup complete. 8 bot groups running`.
- Auto-start log: PASS — 8 groups auto-started. The live 100-bot legacy null-mode group `ab81f9e6-764b-4785-9039-84add04e2fb0` ("BOM flow test 100 (kept running)", `activationMode=null`, `targetStatus=ACTIVE`) auto-restarted and returned to runtime `status=ACTIVE` with 100/100 connected, 0 dead. NOT stopped/deleted.

Note: internal container port is 8085, published to host 8080 — API base for verification was `http://localhost:8080` on Bot-1.

## Regression spot-check — TIMED_ACTIVATION (already-live feature)

- `activation-reconciler` thread present: PASS —
  `ActivationScheduler - Activation reconciler started (zone: Asia/Ho_Chi_Minh, tick: 60s, first tick in 18646 ms)`.
  Full timed-activation suite intentionally not re-run.

## Plan verification — docs/plans/BET_COORDINATION.md § Verification

Test vehicle: a small throwaway coordination-enabled group `283a2d38-0a7b-4b8e-9fc2-90c397d9f8ac` ("COORD verify throwaway"), 5 bots, created on the live-round env `3cda38f9-2c3d-465f-a52a-18ce83207768` / game `52fd334c-3e74-4ec0-9afa-1c418eb0e4e7` (Xoc Dia mini, BETTING_MINI, N=7 options, uniform affinities `{0..6:1}`), `minBet=5000`, `betIncrement=5000`, `maxAggregateStakePerRound=50000`. Deleted at end (see Cleanup).

### Step 1: Config round-trips (Phase 1)
Command: `PATCH /api/v1/bot-group/283a2d38-… {"coordinationEnabled":true,"maxAggregateStakePerRound":50000}` then `GET /api/v1/bot-group/283a2d38-…`
Expected: `{"coordinationEnabled": true, "maxAggregateStakePerRound": 500000}` (plan value; used 50000 here to force trims on this tight grid)
Actual: read-back `coordinationEnabled=true`, `maxAggregateStakePerRound=50000`. (Note: plan's example PATCHes the base path with id in body; the actual controller mapping is `@PatchMapping("/{id}")` — id in path. Round-trip verified via the real endpoint.)
Result: PASS

### Step 2: Coordinator builds on start (Phase 3)
Command: `POST /api/v1/bot-group/283a2d38-…/start`; then grep app log for coordinator creation.
Expected: a startup line indicating a coordinator was created with option budgets matching `getEffectiveOptionAffinities().size()` and the cap; NO such line for a `coordinationEnabled=false` group.
Actual: `BotGroupBehaviorService - Bet coordinator created for group COORD verify throwaway (7 options, aggregate cap 50000)`. This was the ONLY "Bet coordinator created" line in the log — the off-path live group `ab81f9e6` produced none. (Line logs group name, not id.)
Result: PASS

### Step 3: Per-round coordination summary at DEBUG (Phase 3, AD-6)
Command: (production runs at DEBUG) grep `Coordination sid=` for the group id.
Expected: ~one summary line per group per completed round; realized-vs-target histogram + approve/trim/reject counts; counts non-zero once bets flow.
Actual: one `BetCoordinator` DEBUG line per round, e.g.
`Coordination sid=2413041 realized=35000/50000 target-histogram [opt=0 target=7142 realized=5000] … [opt=6 target=7142 realized=5000] approve=0 trim=7 reject=133` — observed for consecutive sids 2413041, 2413042, 2413043, 2413044 (one per round), cumulative trim/reject counts rising.
Result: PASS

### Step 4: Aggregate cap is respected (Phase 3, AD-5)
Command: `GET /api/v1/bot-group/283a2d38-…/health | jq '.coordination.currentAggregateStake, .coordination.maxAggregateStakePerRound'` polled across several rounds.
Expected: `currentAggregateStake <= maxAggregateStakePerRound` on every poll.
Actual: every poll and every round summary showed `currentAggregateStake` ∈ {30000, 35000} ≤ 50000. Never breached.
Result: PASS

### Step 5: Health block shape (Phase 4)
Command: `GET /api/v1/bot-group/283a2d38-…/health | jq '.coordination'`
Expected: non-null object with `enabled=true`, `maxAggregateStakePerRound`, `currentAggregateStake`, `approveCount`/`trimCount`/`rejectCount`, and an `options` array of length N with per-option `targetBudget`, `committedStake`, `realizedFraction`. Off-group → `.coordination == null`.
Actual: `enabled=true`, `maxAggregateStakePerRound=50000`, `currentAggregateStake=35000`, counts `approve=0 trim=21 reject=380`, `options` length 7, each `{optionId, targetWeight, targetBudget=7142, committedStake=5000, realizedFraction≈0.70}`. Live off-group `ab81f9e6` health → `.coordination = null`.
Result: PASS

### Step 6: Realized distribution tracks target (AD-5/AD-7, best-effort)
Command: `GET /api/v1/bot-group/283a2d38-…/health | jq '.coordination.options'` over sustained rounds.
Expected (plan): with a SKEWED affinity map, low-affinity options' `committedStake` sit at/below their smaller `targetBudget`, trims visible (`trimCount > 0`), realized pushed toward target shape.
Actual: the live game uses UNIFORM affinities (all 7 options weight 1) and editing a production game is out of scope, so the skew-specific assertion could not be exercised. For the uniform target, the mechanism is demonstrated: every option committed exactly 5000 ≤ its 7142 budget (a single grid-aligned bet per option; the second is rejected because 7142−5000=2142 < minBet 5000), realized per round steady at 35000/50000, and trims are visible and growing (`trimCount` 7→35 across rounds). Realized tracks the (uniform) target and the cap/floor behavior matches AD-5/AD-7.
Result: BEST-EFFORT / PARTIAL — mechanism verified on uniform target; skewed-affinity variant NOT verified (no skewed live game available; production game edit out of scope). Not counted as a failure per release instructions.

### Step 7: Off path is unchanged (AD-9)
Command: inspect the live `coordinationEnabled=false` group `ab81f9e6`: health `.coordination`, coordination log lines, bet flow.
Expected: `.coordination == null`, no coordination DEBUG summary lines for its id, bets flow as before.
Actual: `.coordination = null`; zero `Coordination sid=` lines for `ab81f9e6`; zero `Bet coordinator created` lines for it; bets flowing (`stats.roundsSinceRestart` 3→15 during the window, 100 active bots). Byte-for-byte legacy behavior.
Result: PASS

## Cleanup

- Throwaway group `283a2d38-0a7b-4b8e-9fc2-90c397d9f8ac`: stopped (200) then deleted (200); subsequent GET → 404. Removed.
- Temp payload files on Bot-1 (`/tmp/coordtest.json`, `/tmp/patch.json`): removed.
- Local `bot.tar` artifact: removed.
- Live 100-bot group `ab81f9e6`: left running, `status=ACTIVE`, 100/100 connected, 0 dead (untouched).

## Verdict

PASS

Summary: 6 of 7 plan-verification steps fully PASS; Step 6 is BEST-EFFORT/PARTIAL (skewed-affinity assertion not verifiable on the uniform live game — production game edit out of scope), which per the release brief is a noted non-verified caveat, not a failure. Universal smoke PASS; TIMED_ACTIVATION regression PASS; off-path (coordination default OFF) provably unchanged; the pre-existing live 100-bot group auto-restarted to ACTIVE.
