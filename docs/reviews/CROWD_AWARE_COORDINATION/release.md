# Release — CROWD_AWARE_COORDINATION (WP#6)

Mode: bot
Branch: feat/crowd-aware-coordination (HEAD 31f6323daf6e8e93d72edb986b40c4b105c9bd39)
Image: vingame-bot:latest (built at 2026-07-08T14:40Z; local image id sha256:231eda14c414)
Date: 2026-07-08T11:00:00Z (Bot-1 local time; ~14:40–15:00 local build machine)
Target: Bot-1 (single-compose project — bot-manager + mongo + loki + promtail + prometheus + grafana)

Feature: adds the **crowd-aware tier** to the shipped group-scoped `BetCoordinator`.
When a group has BOTH `coordinationEnabled=true` AND the new `crowdAwareCoordination=true`,
the coordinator folds real players' per-option crowd stake (the `bs` array's `v` value,
minus the fleet's own `committed(o)` per AD-C4) into its per-round budget, steering the
**combined crowd+fleet** distribution toward the affinity target rather than the fleet
alone. v1 steers on VALUE (`v`) only; the count (`bc`) is observability-only, gated by a
new obs-only `Game.crowdCountSemantic` enum (BETS/PLAYERS/UNKNOWN). **Ships OFF by default**
— `crowdAwareCoordination` defaults false, requires `coordinationEnabled`, and NO existing
group has it. With crowd off (or crowd `v=0`), `B_crowd(o) == B(o)` — bit-for-bit the shipped
internal tier (AD-C2 special case, test-proven + observed live this release). This deploy
changes **zero** existing behavior. QA/reviewer/compliance all PASS; full suite green
(1471 tests). Git NOT merged/pushed per brief (user handles git after verdict).

Note: the orthogonal untracked `docs/reviews/BET_COORDINATION/release.md` was the only
pre-existing working-tree entry; ignored per brief. No production code / test / plan
modified by this release. Working tree clean at 31f6323 before and after (bot.tar gitignored).

## Build

- `mvn clean install`: PASS (~28s) — BUILD SUCCESS, Tests run: 1471, Failures: 0, Errors: 0, Skipped: 0 (Java 21 via JAVA_HOME=openjdk-21.0.2). `target/Bot-1.0.jar` produced (61,575,508 bytes). (Any stack trace visible in raw surefire tail is a captured INFO-level `RestExceptionHandler` log emitted by a passing negative-path test — the aggregate surefire summary is 0/0/0.)
- `docker build --no-cache --platform linux/amd64`: PASS (image sha256:231eda14c414, 380MB).
- `docker save -o bot.tar`: PASS (392,986,624 bytes).

## Ship

- `sftp put bot.tar` → `/home/sgame/bot-java/bot.tar`: PASS (remote size 392,986,624 bytes — exact match to local).
- (mode=bot — infra-images.tar.gz NOT shipped, per brief; no infra/observability changes in this diff.)

## Deploy

- `docker compose down`: PASS (whole stack removed cleanly, incl. all observability containers + network).
- `docker image rm vingame-bot:latest`: PASS (prior image sha256:26e570ba9dc6 — the AFFINITY_AWARE_PROPOSAL artifact — untagged + layers deleted; IMGRM_OK).
- `docker load -i bot.tar`: PASS ("Loaded image: vingame-bot:latest"; loaded image id 231eda14c414 — matches local build).
- `docker compose up -d`: PASS (mongo → Healthy, then bot-manager + loki/promtail/prometheus/grafana started).

## Smoke test

- `docker ps` shows healthy: PASS — `bot-java-bot-manager-1 Up (healthy)` reached ~34s after start (health: starting → healthy).
- Spring Boot ready log: PASS — `Started Starter in 12.942 seconds`; `Tomcat started on port 8085`.
- Auto-start log: PASS — `Bot Manager startup complete. 8 bot groups running`.

### Observability stack re-verification (Bot-1 single-compose — redeploy takes the whole stack down)

- All 6 containers back Up: `grafana`, `prometheus`, `bot-manager` (healthy), `promtail`, `mongo` (healthy), `loki`.
- Grafana `GET :3000/api/health` → HTTP 200: PASS.
- Prometheus `GET :9090/-/healthy` → HTTP 200: PASS.
- Loki `GET :3100/ready` → 503 immediately post-start (WAL replay), then HTTP 200 after warm-up: PASS.
- Promtail container Up: PASS.

### Off-path witness — live 100-bot group `ab81f9e6-764b-4785-9039-84add04e2fb0` (CRITICAL: do not disturb)

- Auto-restarted and returned to runtime `status=ACTIVE`, **0 dead** — untouched throughout.
- Config: `coordinationEnabled=false`, `crowdAwareCoordination=false`, `affinityWeightedProposal=false`; health block `coordination=null` (all feature blocks null).
- **0** crowd/`Crowd-aware` log lines for its id over the whole release window — byte-for-byte legacy off path.
- Bot count reads `totalBots=21` / `connectedBots=21` with `roundsSinceRestart=null` on the health top-level (its `.stats.roundsSinceRestart` cycled 2→14 during the window): this is the **known pre-existing subscriber-pruning / partial-connect condition on that BOM channel** flagged in the brief (last release it was similar), NOT caused by this deploy — noted, not chased. Left running, ACTIVE, untouched at the end.

Note: internal container port is 8085, published to host 8080 — API base for verification was `http://localhost:8080` on Bot-1.

## Plan verification — docs/plans/CROWD_AWARE_COORDINATION.md § Verification

**Test-vehicle selection.** The whole live crowd-shift proof (Step 6) needs a live-round
game with a **REAL human crowd**. Per brief + MEMORY: **TIP/P_116 is forbidden** (DNS-block
class — though its groups were oddly reaching live rounds this window, it is off-limits as
the crowd vehicle). The only reachable non-TIP BETTING_MINI vehicles are BOM/B52/Nohu. I
provisioned a throwaway skewed BOM Xoc-Dia-mini clone (`shakeDiskPlugin`, env `097`) — the
same product family the witness runs on. That channel **does reach live rounds** (the crowd
group hit `roundsSinceRestart=2+`, 6/6 bots betting), but carries **no real human crowd**
(`total bettors this round == our 6 bots`; raw `bs.v == 0` on every option). So the mechanism
is fully exercised live (observeCrowd firing, DEBUG crowd histogram, health fields, crowd-off
identity) but the *live crowd-shift contrast* (Step 6) has no external crowd to steer against
— marked **BEST-EFFORT/PARTIAL** exactly per the brief's explicit contingency. The steering
math is unit-tested (1471 green, incl. `BetCoordinatorCrowdTest` crowd-off identity + shift +
clamp + stale-drop + idempotency + count-never-affects-budget pins).

Throwaway resources (all deleted at end — see Cleanup):
- **Throwaway game** `8caea8b9-89f4-4cff-bc8b-e946f13a2754` ("CAC verify throwaway (skewed BOM XocDiaMini)") — a clone of the live BOM `shakeDiskPlugin` BETTING_MINI game on env `097`, with **skewed** `optionAffinities={"0":5,"1..6":1}` and `crowdCountSemantic` exercised across BETS/PLAYERS/UNKNOWN. `optionAffinities` is bot-side steering metadata only, so the server-side round flow is identical to the production BOM game — it reaches live rounds.
- **Throwaway crowd-aware group** `07a777aa-e2f1-4431-897d-d30f06ec474f` ("CAC verify crowd-aware"), 6 bots, RANDOM strategy, `coordinationEnabled=true` (cap `maxAggregateStakePerRound=500000`, `minBet=10000`, `betIncrement=10000`) AND `crowdAwareCoordination=true`.
- **Throwaway coord-only group** `934a52d1-ccf1-450e-a3f2-6f925797eb13` ("CAC verify coord-only crowd-off"), 6 bots, `coordinationEnabled=true`, `crowdAwareCoordination=false` (the crowd-off health/log contrast).
- **Default-check group** `59f8e8b1-c6f1-48a0-8ed4-21cf219db380` ("CAC default check"), 1 bot, flag omitted → default-false check.

### Step 1: Config round-trips + validation (Phase 1, AD-C5, AD-C6)
Command: PATCH crowd group `crowdAwareCoordination` false→true (on `coordinationEnabled=true`) with read-back; PATCH/CREATE the bad combo `crowdAwareCoordination=true`+`coordinationEnabled=false`; PATCH game `crowdCountSemantic` across BETS/PLAYERS/UNKNOWN with read-back; fresh group with flag omitted.
Expected: flag round-trips both ways; bad combo REJECTED with clean 400 "requires coordinationEnabled"; game enum round-trips all three; fresh group defaults false.
Actual:
- PATCH `crowdAwareCoordination=false` → read-back `{coordinationEnabled:true, crowdAwareCoordination:false}`; PATCH true → read-back `true`. Round-trips both ways.
- NEGATIVE (PATCH, on the group): `{"coordinationEnabled":false,"crowdAwareCoordination":true}` → **HTTP 400** `{"type":"Bad request","msg":"Invalid bot-group config: crowdAwareCoordination requires coordinationEnabled to be true"}`. Group state unchanged after the rejected PATCH (still coord=true/crowd=true).
- NEGATIVE (CREATE): same bad combo on POST → **HTTP 400**, same clean message.
- `Game.crowdCountSemantic` PATCH BETS→read BETS, PLAYERS→PLAYERS, UNKNOWN→UNKNOWN (all round-trip); create-time default is `UNKNOWN` (confirmed on every shared game).
- Fresh group `59f8e8b1` with the flag omitted → read-back `crowdAwareCoordination=false` (core safety guarantee at create path).
Result: PASS

### Step 2: Crowd-aware coordinator builds on start (Phase 3, AD-C10)
Command: start both coordination groups; grep the app log for the crowd INFO line + the internal-tier "Bet coordinator created" line.
Expected: one INFO "Crowd-aware coordination enabled … countSemantic=…" for the crowd group; **no** crowd line for the coord-only group (which still gets "Bet coordinator created").
Actual (from container log):
```
INFO BotGroupBehaviorService - Bet coordinator created for group CAC verify crowd-aware (DELETE ME) (7 options, aggregate cap 500000)
INFO BotGroupBehaviorService - Crowd-aware coordination enabled for group CAC verify crowd-aware (DELETE ME) (countSemantic=BETS)
INFO BotGroupBehaviorService - Bet coordinator created for group CAC verify coord-only crowd-off (DELETE ME) (7 options, aggregate cap 500000)
```
The crowd-aware group gets BOTH the coordinator-created AND the crowd-enabled INFO (with `countSemantic=BETS` matching the game); the coord-only group gets ONLY the internal-tier line — **zero** crowd lines. (The INFO identifies the group by name, not id, which is the shipped format.)
Result: PASS

### Step 3: Crowd observations flow intra-round / round-boundary (Phase 3, AD-C3, TRACE)
Command: raise `com.vingame.bot` to TRACE; grep `observeCrowd` / crowd-recompute lines for the crowd group.
Expected: per-window recompute lines showing per-option `v` (real-crowd-dependent).
Actual: TRACE (204) → the coordinator's `observeCrowd` hook **is firing live** off the BOM EndGame `bs` (the one-round-lag path this release fixed):
```
TRACE BetCoordinator [07a777aa…/3/BETTING_MINI] - BetCoordinator.observeCrowd: sid=2413566
  crowd={0=0, 1=0, …, 6=0} pureCrowd={0=0, …, 6=0}
  adjustedBudget={0=227272, 1=45454, …, 6=45454}
```
The hook reads `bs`, computes `pureCrowd = max(0, v − committed)`, recomputes the adjusted budget under the lock. Raw `v == 0` on every option because the only bettors on this staging channel are our own 6 bots (subtracted via `committed(o)` per AD-C4) — **no external human crowd present**. The wiring is proven end-to-end; the *values* are 0 for lack of a real crowd.
Result: PASS (mechanism observed flowing live; `v=0` reflects absence of human crowd, not a wiring gap)

### Step 4: Per-round crowd DEBUG summary (Phase 2/3, AD-C10)
Command: at DEBUG, grep the per-round `Coordination sid=… crowd=[…]` summary for the crowd group.
Expected: ~one line per group per round, with a `crowd=[opt=… v=… adj=…]` segment alongside the realized/target histogram.
Actual:
```
DEBUG BetCoordinator [07a777aa…/3/BETTING_MINI] - Coordination sid=2413565 realized=460000/500000
  target-histogram [opt=0 target=227272 realized=220000] … [opt=6 target=45454 realized=40000]
  crowd=[[opt=0 v=0 adj=227272] [opt=1 v=0 adj=45454] … [opt=6 v=0 adj=45454]]
  approve=6 trim=12 reject=318
```
Exactly one line per round with the `crowd=[…]` segment carrying per-option `v` and `adj`. With `v=0`, `adj == target` for every option — the **AD-C2 crowd-off identity holding live** (crowd tier == internal tier when no crowd).
Result: PASS

### Step 5: Health block carries the crowd view (Phase 4, AD-C10)
Command: `GET /api/v1/bot-group/$GID/health | jq '.coordination'` for crowd-aware, coord-only, and non-coordinated groups.
Expected: crowd-aware ⇒ `crowdAware=true`, `crowdCountSemantic`, per-option `observedCrowdStake`/`crowdAdjustedBudget`/`crowdStake`/`observedCrowdCount`; coord-only ⇒ `crowdAware=false` with those fields inert (0); non-coordinated ⇒ `.coordination=null`.
Actual:
- **Crowd-aware** `07a777aa`: `crowdAware=true`, `crowdCountSemantic="BETS"`, sample option `{optionId:0, targetWeight:5, targetBudget:227272, committedStake:130000, realizedFraction:0.57, crowdStake:0, observedCrowdStake:0, crowdAdjustedBudget:227272, observedCrowdCount:0}` — all crowd fields present; actively coordinating (aggregate 250000 ≤ cap). `crowdAdjustedBudget == targetBudget` because `observedCrowdStake=0` (AD-C2 special case).
- **Coord-only** `934a52d1`: `crowdAware=false`; per-option `crowdStake/observedCrowdStake/observedCrowdCount = 0` (inert), `crowdAdjustedBudget == targetBudget` — the internal tier (AD-C6).
- **Non-coordinated** witness `ab81f9e6`: `.coordination = null`.
Result: PASS

### Step 6: Fleet counter-balances the crowd (AD-C2, best-effort, real crowd) — **BEST-EFFORT / PARTIAL**
Command: on a skewed-affinity game with a live human crowd, over sustained rounds inspect `.coordination.options` — expect the crowd-over-filled option's `crowdAdjustedBudget` to drop (fleet backs off) and under-filled options to rise, contrasted vs a crowd-off run.
Expected: crowd-driven budget shift away from crowd-heavy options.
Actual: **NOT OBSERVABLE — no external human crowd on any reachable non-TIP live channel.** The throwaway crowd-aware BOM group reached live rounds (`roundsSinceRestart=2+`, 6/6 betting) but every round showed `total bettors this round == our 6 bots` and raw `bs.v == 0` on all options (after `committed(o)` subtraction, pure crowd = 0). TIP/P_116 (the only channel with organic traffic, and the ideal `bs`-on-UpdateBet signal) is forbidden per brief. This is precisely the brief's stated contingency ("If no healthy BOM/B52/Nohu channel with real player crowd is reaching live rounds … mark this step BEST-EFFORT/PARTIAL — the mechanism is unit-tested; config/validation/off-path/health still PASS"). The steering itself is proven: (a) unit-pinned in `BetCoordinatorCrowdTest` (crowd-off identity, one-option shrink + others grow, over-crowd→0 clamp, stale-drop, N-frame idempotency, committed-survives-swap, count-never-affects-budget); (b) the live `observeCrowd`/DEBUG-crowd-histogram/health-fields all confirmed wired and firing on the real BOM EndGame `bs` path this release. The single missing piece is a non-zero human `v` to steer against.
Result: BEST-EFFORT / PARTIAL (mechanism live-verified + unit-proven; no real crowd available on a permitted channel — does not affect the overall verdict per brief)

### Step 7: Count semantic never corrupts steering (AD-C5)
Command: snapshot the crowd group's `crowdAdjustedBudget` with game `crowdCountSemantic=BETS`; switch the game to `UNKNOWN`, restart, re-read; the budgets must be identical (v1 steers on `v`, never `bc`).
Expected: `crowdAdjustedBudget` unchanged across the semantic change.
Actual:
- BETS run: `crowdAdjustedBudget = [227272, 45454, 45454, 45454, 45454, 45454, 45454]` (opt 0..6).
- After PATCH game → `UNKNOWN` + group restart, settled round: **identical** `[227272, 45454×6]`, `targetBudget` identical, `observedCrowdCount=0` (inert under UNKNOWN). `crowdAware` stayed true.
Byte-for-byte identical budgets across BETS↔UNKNOWN — the count semantic is provably out of the steering math (AD-C5 fail-safe: a mis-set semantic is observability-only, never a steering error).
Result: PASS

### Step 8: Off path unchanged (AD-C6) — the regression-sensitive surface
Command: inspect the live off-default witness `ab81f9e6` (`coordinationEnabled=false`) and the coord-only throwaway (`crowdAwareCoordination=false`).
Expected: `coordinationEnabled=false` ⇒ `.coordination=null`; `crowdAwareCoordination=false` (coord on) ⇒ `crowdAware=false`, internal-tier budgets, no crowd DEBUG/TRACE lines.
Actual: witness `ab81f9e6` → `coordinationEnabled=false`, `crowdAwareCoordination=false`, `.coordination=null`, `status=ACTIVE`, 0 dead, **0** crowd log lines for its id — byte-for-byte legacy. Coord-only `934a52d1` → `crowdAware=false`, crowd per-option fields inert (0), `crowdAdjustedBudget == targetBudget`, no crowd INFO/DEBUG/TRACE lines for its id. Zero change to the off path.
Result: PASS

## Cleanup

- Throwaway crowd-aware group `07a777aa-e2f1-4431-897d-d30f06ec474f`: stopped + deleted (200); GET → 404. Removed.
- Throwaway coord-only group `934a52d1-ccf1-450e-a3f2-6f925797eb13`: stopped + deleted (200); GET → 404. Removed.
- Default-check group `59f8e8b1-c6f1-48a0-8ed4-21cf219db380`: deleted (200); GET → 404. Removed.
- Throwaway BOM game `8caea8b9-89f4-4cff-bc8b-e946f13a2754`: deleted (200); GET → 404. Removed.
- No lingering throwaway bot threads in the container after deletion (0 `cacwd`/`caccoord`/`cacdef` lines).
- Temp payloads on Bot-1 (`/tmp/cac_*.json`): removed.
- No shared production game modified — the throwaway was a fresh clone; the live BOM game `52fd334c` and witness config were only READ, never written.
- Logging level on `com.vingame.bot` restored to DEBUG (production default; raised to TRACE only during Step 3, then reverted — confirmed 204).
- Local `bot.tar` artifact: removed. (Remote `/home/sgame/bot-java/bot.tar` left in place as the standard deployment staging artifact.)
- Live 100-bot witness `ab81f9e6`: left running, `status=ACTIVE`, 0 dead (untouched throughout).
- Git: working tree clean at 31f6323; nothing merged or pushed.

## Verdict

PASS

Summary: Universal smoke PASS (bot-manager healthy + Spring ready + 8 groups auto-started;
full observability stack — Grafana/Prometheus/Loki/promtail/mongo — re-verified healthy
after the single-compose redeploy). **7 of 8 plan-verification steps PASS; Step 6 (live
crowd-shift) is BEST-EFFORT/PARTIAL** — no reachable non-TIP channel carried a real human
crowd (our BOM channel reached live rounds but only our own fleet bet, raw `bs.v=0`; TIP is
forbidden). The crowd tier is nonetheless proven wired and firing **live** end-to-end on the
real BOM EndGame `bs` path (the release's one-round-lag fix): `observeCrowd` TRACE, the
per-round DEBUG `crowd=[…]` histogram, the health `crowdAware/crowdCountSemantic/observedCrowdStake/
crowdAdjustedBudget/observedCrowdCount` fields, and the config INFO line all confirmed — and
the steering math is unit-pinned (1471 green incl. `BetCoordinatorCrowdTest`). The core safety
guarantee HOLDS: ships **OFF by default** (fresh group reads `crowdAwareCoordination=false`;
bad combo `crowdAware=true`+`coord=false` rejected with a clean 400 at BOTH create and PATCH),
with crowd off/`v=0` the coordinator is **bit-for-bit** the internal tier (`crowdAdjustedBudget
== targetBudget` observed live on both the crowd-off and the crowd-on-but-no-crowd paths), and
the count semantic is provably out of the steering math (BETS↔UNKNOWN gave byte-identical
budgets, AD-C5). The untouched live 100-bot off-path witness `ab81f9e6` shows `.coordination=null`,
zero crowd log lines, byte-for-byte legacy bet flow. Zero existing behavior changed.

Environment note: the only channel with organic human traffic on staging is TIP/P_116 (both
forbidden per brief AND the ideal `bs`-on-UpdateBet crowd signal). No BOM/B52/Nohu channel had
a real player crowd during the window — so the live crowd-*shift* contrast could not be
demonstrated end-to-end, matching the same environment constraint noted in the AFFINITY_AWARE_PROPOSAL
release (zombie/fleet-only BOM channels). The mechanism, config, validation, health shape,
off-path, and count-semantic fail-safe all PASS; the missing piece is exclusively an external
human crowd to steer against.
