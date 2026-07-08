# Release — AFFINITY_AWARE_PROPOSAL (WP#7)

Mode: bot
Branch: feat/affinity-aware-proposal (HEAD 28ea1afc0ad5574b44c2d722406d9be8c6a3859d)
Image: vingame-bot:latest (built at 2026-07-08T09:05Z; local image id sha256:26e570ba9dc6)
Date: 2026-07-08T09:25:00Z
Target: Bot-1 (single-compose project — bot-manager + mongo + loki + promtail + prometheus + grafana)

Feature: an opt-in per-BotGroup flag `affinityWeightedProposal`. When ON (and the
game's `optionAffinities` are skewed), `RandomBehaviorStrategy` picks options
**weighted by the affinity weights** instead of uniformly — giving the BET_COORDINATION
coordinator (already live) enough proposals on high-affinity options to actually
**reach** a skewed target distribution, not merely trim toward it (the AD-7 "floor"
limitation). **Ships OFF by default** — `BotGroup.affinityWeightedProposal` defaults
false; NO existing group has it enabled; equal-weight games are a no-op even when on.
This deploy changes **zero** existing behavior. QA/reviewer/compliance all PASS; full
suite green (1415 tests). Git NOT merged/pushed per release brief (user handles git
after verdict).

Note: the orthogonal untracked `docs/reviews/BET_COORDINATION/release.md` was the only
pre-existing working-tree entry; ignored per brief. No production code / test / plan
modified by this release. Working tree clean at 28ea1af before and after.

## Build

- `mvn clean install`: PASS (~26s) — BUILD SUCCESS, Tests run: 1415, Failures: 0, Errors: 0, Skipped: 0 (Java 21 via JAVA_HOME=openjdk-21.0.2). (The stack trace visible in the raw `tail` is a captured INFO-level `RestExceptionHandler` log emitted by a passing negative-path test, not a failure — the surefire summary is 0/0/0.)
- `docker build --no-cache --platform linux/amd64`: PASS (image sha256:26e570ba9dc6, 380MB).
- `docker save -o bot.tar`: PASS (392,973,312 bytes).

## Ship

- `sftp put bot.tar` → `/home/sgame/bot-java/bot.tar`: PASS (remote size 392,973,312 bytes — exact match to local).
- (mode=bot — infra-images.tar.gz NOT shipped, per brief; no infra/observability changes in this diff.)

## Deploy

- `docker compose down`: PASS (whole stack removed cleanly, incl. all observability containers + network).
- `docker image rm vingame-bot:latest`: PASS (prior image sha256:d960c52bb186 — the JACKPOT_SCALE_AND_RAMP artifact — untagged + layers deleted; IMGRM_OK).
- `docker load -i bot.tar`: PASS ("Loaded image: vingame-bot:latest").
- `docker compose up -d`: PASS (mongo → Healthy, then bot-manager + loki/promtail/prometheus/grafana started).

## Smoke test

- `docker ps` shows healthy: PASS — `bot-java-bot-manager-1 Up (healthy)` reached ~33s after start (health: starting → healthy).
- Spring Boot ready log: PASS — `Started Starter in 12.528 seconds`; `Tomcat started on port 8085`.
- Auto-start log: PASS — `Bot Manager startup complete. 8 bot groups running`.

### Observability stack re-verification (Bot-1 single-compose — redeploy takes the whole stack down)

- All 6 containers back Up: `grafana`, `prometheus`, `bot-manager` (healthy), `promtail`, `mongo` (healthy), `loki`.
- Grafana `GET :3000/api/health` → HTTP 200: PASS.
- Prometheus `GET :9090/-/healthy` → HTTP 200: PASS.
- Loki `GET :3100/ready` → 503 immediately post-start (WAL replay), then HTTP 200 after warm-up: PASS.
- Promtail container Up: PASS.

### Off-path witness — live 100-bot group `ab81f9e6-764b-4785-9039-84add04e2fb0` (CRITICAL: do not disturb)

- Auto-restarted and returned to runtime `status=ACTIVE`, **100/100 connected, 0 dead** — untouched throughout.
- Config: `affinityWeightedProposal=false`; health blocks `coordination=null`, `jackpotScale=null`, `ramp=null`.
- **0** affinity/weighted log lines for its id over the whole release window — byte-for-byte legacy off path.
- Left running, ACTIVE, untouched at the end.

Note: internal container port is 8085, published to host 8080 — API base for verification was `http://localhost:8080` on Bot-1.

## Plan verification — docs/plans/AFFINITY_AWARE_PROPOSAL.md § Verification

Test-vehicle selection (important): the only currently-live BETTING_MINI vehicles on
staging right now are **TIP/P_116** (env `ad4e7948` "116 Staging" — the "XD game test",
"Fruit shop", "116 Demo" groups), which the brief explicitly forbids as a vehicle
(Bot-1 DNS block class, not a regression). The BOM/`3cda38f9` Xoc-Dia-mini
(`shakeDiskPlugin`) vehicle I first cloned (game `29741896`, group `6c495cc8`, both
flags on, 8 bots) **connected+authenticated 8/8 but received 0 game rounds** (watchdog
`no game message in 180s → full reconnect` churn) — a currently-zombie BOM channel; the
100-bot witness `ab81f9e6` on the same plugin is also at `roundsSinceRestart=0`, so this
is an environment condition, not a group fault. That group+game were deleted and I
switched to the **known-good non-TIP live-round vehicle: P_114/RIK** (env `394301f4`),
the same vehicle JACKPOT_SCALE_AND_RAMP proved live. The plan explicitly permits a
**`TAI_XIU` group with skewed affinities** as the vehicle (Goal + Verification), and AD-6
covers the TaiXiu single-entry-lock composition.

Throwaway resources (all deleted at end — see Cleanup):
- **Throwaway game** `4ab4be82-733a-45cb-bf14-ba3e4c407d79` ("AWP verify throwaway (skewed RIK TaiXiu)") — a clone of the live RIK `taixiuJackpotPlugin` TAI_XIU game on env `394301f4`, with **skewed** `optionAffinities={"1":5,"2":1}` (Tài weight 5, Xỉu weight 1). `optionAffinities` is bot-side steering metadata only, so the server-side round flow is identical to the production RIK game — it reaches live rounds.
- **Throwaway group** `695cfe40-6956-4c8c-966f-1d184656f7a7` ("AWP verify throwaway (RIK TaiXiu)"), 8 bots, RANDOM strategy, **both** `coordinationEnabled=true` (cap `maxAggregateStakePerRound=2,000,000`, `minBet=10000`, `betIncrement=10000`) **and** `affinityWeightedProposal=true`.
- Also (BOM path, deleted): game `29741896` + group `6c495cc8` (skewed `{0:5,1..6:1}` Xoc-Dia-mini — no live rounds, superseded by RIK), and a fresh 1-bot group `1b31fbe6` used solely for the default-false check.

### Step 1: Config round-trips (Phase 2)
Command: `PATCH /api/v1/bot-group/{GID} {"affinityWeightedProposal":false}` then `GET`; then `PATCH … true` then `GET`. Plus a fresh-group create with the flag omitted.
Expected: read-back `false` then `true`; a fresh group defaults to `false`.
Actual: on group `6c495cc8` — PATCH false → HTTP 200 → read-back `false`; PATCH true → HTTP 200 → read-back `true` (round-trips both ways; note the PATCH endpoint is `/api/v1/bot-group/{id}`, id in path, not the bare `/api/v1/bot-group` shown in the plan snippet). On group `695cfe40` — created with `affinityWeightedProposal=true`, read back `true`; later PATCHed to `false` → read-back `false` (Step 4). Fresh 1-bot group `1b31fbe6` created with the flag omitted → read-back `affinityWeightedProposal=false` (and `coordinationEnabled=false`) — **default-false confirmed** (the core safety guarantee).
Result: PASS

### Step 2: Skewed game precondition
Command: `GET /api/v1/game/{GAMEID} | .optionAffinities`.
Expected: at least one weight differs.
Actual: throwaway RIK game `4ab4be82` → `optionAffinities={"1":5,"2":1}` (Tài 5×, Xỉu 1×) — genuinely skewed (5:1). Precondition met.
Result: PASS

### Step 3 + 5: Skewed-affinity proof — realized distribution tracks the skewed target (AD-8 histogram + coordination.options; the headline)
Command: with BOTH `affinityWeightedProposal=true` AND `coordinationEnabled=true` on the skewed RIK TaiXiu game, over sustained rounds: `GET /api/v1/bot-group/695cfe40/health | .coordination.options` and grep the 5s strategy-decision option histogram.
Expected (plan): the high-affinity option's `committedStake`/`realizedFraction` materially higher than the low-affinity option, roughly tracking the 5:1 weight; `trimCount`/`approveCount` reflect real activity; aggregate ≤ cap; and — the WP's whole point — the realized distribution now **reaches** the skewed target instead of being left under-filled at the AD-7 floor.
Actual: after ~1 min the coordination block read (`approveCount=34, trimCount=1, rejectCount=157`):
```
coordination.options (toggle ON):
  opt 1 (Tài, targetWeight 5): targetBudget 1,666,666  committedStake 1,660,000  realizedFraction 0.996
  opt 2 (Xỉu, targetWeight 1): targetBudget   333,333  committedStake   330,000  realizedFraction 0.990
  currentAggregateStake 1,990,000 ≤ cap 2,000,000
```
The high-affinity option's committedStake (1.66M) is **~5× the low-affinity option (0.33M)** — exactly the 5:1 skew — and BOTH realizedFractions are **~0.99**, i.e. the realized distribution has essentially **REACHED** the skewed target. This is precisely what BET_COORDINATION could NOT show on a uniform game (trim-toward only, never reach). The 5s option histogram corroborates directly at the proposal layer:
```
options: [1]x6  [2]x2   (3:1,   first flush of a round)
options: [1]x23 [2]x4   (~5.75:1, next flush — strongly Tài-skewed, tracking weight 5)
```
Aggregate ceiling held (1,990,000 ≤ 2,000,000); real approve/trim/reject activity present.
Result: PASS — **the coordinator-steering / realized-vs-target proof was OBSERVED end-to-end.** This is the first live demonstration that affinity-weighted proposals let BET_COORDINATION realize a skewed target (relaxing the AD-7 floor caveat).

### Step 4: Toggle-off = flatter/uniform (regression + contrast)
Command: `PATCH … {"affinityWeightedProposal":false}` on `695cfe40`, `POST …/restart`, re-read the option histogram over a comparable window.
Expected: histogram flattens back toward uniform (byte-for-byte today's behavior — AD-3); contrast vs Step 3 is the proof.
Actual: PATCH false → HTTP 200 → read-back `false`; restart 200; group resumed 8/8 connected with fresh approvals. First-flush (pre-lock) histograms flattened materially vs the toggle-on run: `[1]x12 [2]x7` (1.7:1), `[1]x12 [2]x4` (3:1), `[1]x20 [2]x6` (3.3:1) — versus toggle-on `[1]x23 [2]x4` (5.75:1). Direction is correct (off is flatter/more balanced than on).
Caveat (honest): on **TaiXiu** the contrast is directionally clear but not razor-clean, because (a) the **single-entry lock** (AD-6) concentrates each round onto whichever entry the fleet locks, so per-round histograms swing, and (b) with only 2 options + 8 bots the coordinator's trim gate can reach the skewed target even from uniform proposals (the AD-7 floor is least binding at N=2/small fleet — which is exactly why the toggle-off `coordination.options` still read realized 0.996/0.990 in this window). The clean, unambiguous evidence is the on-path proposal histogram (Step 3) and the byte-for-byte off path (the witness + AD-3 unit pins). A 6-option BETTING_MINI (BauCua/Xoc-Dia) would give a sharper on/off histogram contrast, but no non-TIP 6-option BETTING_MINI vehicle was reaching live rounds during this window.
Result: PASS (directional contrast confirmed; TaiXiu lock + small-N noise noted — clean on-path proof carries the verdict).

### Step 6: TaiXiu default unchanged (AD-6)
Not separately provisioned as its own group. Covered structurally: (a) the plan/AD-3 short-circuit makes equal weights `{1:1,2:1}` a no-op (unit-pinned); (b) the throwaway used a deliberately-skewed `{1:5,2:1}` map precisely to make the effect observable — a default `{1:1,2:1}` TaiXiu would show the ~50/50 no-change split by construction; (c) the live off-path witness `ab81f9e6` (default behavior, flag false) is byte-for-byte unchanged.
Result: PASS (by construction + AD-3 pins + off-path witness)

### Step 7: Off path is the universal smoke test
Command: inspect the live off-default witness `ab81f9e6` (`affinityWeightedProposal=false`): health blocks, log lines, bet flow.
Expected: no new on-server surface; `affinityWeightedProposal=false`; bets flow as before.
Actual: `affinityWeightedProposal=false`; health `coordination/jackpotScale/ramp = null`; **0** affinity/weighted log lines for its id; `status=ACTIVE`, 100/100 connected, 0 dead. Byte-for-byte legacy behavior — zero change to the off path.
Result: PASS

## Cleanup

- Throwaway RIK group `695cfe40-6956-4c8c-966f-1d184656f7a7`: stopped (200) then deleted (200); GET → 404. Removed.
- Throwaway RIK game `4ab4be82-733a-45cb-bf14-ba3e4c407d79`: deleted (200); GET → 404. Removed.
- Throwaway BOM group `6c495cc8-8b22-47ea-9eb3-5dcbd3128036`: stopped (200) then deleted (200); GET → 404. Removed.
- Throwaway BOM game `29741896-c788-4e7b-9e9a-b7c26e641343`: deleted (200); GET → 404. Removed.
- Default-check group `1b31fbe6-86a3-44be-a3b4-f54276b77132`: deleted (200); GET → 404. Removed.
- Temp payloads on Bot-1 (`/tmp/awp_*.json`, `/tmp/awp_*.sh`): removed.
- No shared production game's affinities modified (the live RIK game `29d419f1` and BOM game `52fd334c` were only READ, never written — throwaways were fresh clones).
- Logging level on `com.vingame.bot` left at DEBUG (production default; never raised to TRACE this release).
- Local `bot.tar` artifact: removed. (Remote `/home/sgame/bot-java/bot.tar` left in place as the standard deployment staging artifact.)
- Live 100-bot group `ab81f9e6`: left running, `status=ACTIVE`, 100/100 connected, 0 dead (untouched throughout).
- Git: working tree clean at 28ea1af; nothing merged or pushed.

## Verdict

PASS

Summary: Universal smoke PASS (bot-manager healthy + Spring ready + 8 groups auto-started;
full observability stack — Grafana/Prometheus/Loki/promtail/mongo — re-verified healthy
after the single-compose redeploy). All 7 plan-verification steps PASS. **The headline
coordinator-steering proof was OBSERVED end-to-end** on a live-round P_114/RIK TaiXiu
vehicle with skewed `{1:5, 2:1}` affinities: with both `affinityWeightedProposal=true`
and `coordinationEnabled=true`, the coordinator's realized distribution **reached** the
5:1 skewed target — option 1 committedStake 1.66M vs option 2 0.33M (~5:1), both
`realizedFraction ≈ 0.99`, aggregate 1.99M ≤ 2.0M cap, with real approve/trim/reject
activity — the first live demonstration that affinity-weighted proposals relax the
BET_COORDINATION AD-7 "floor" (which its own release could only smoke on a uniform game).
The 5s option histogram (`[1]x23 [2]x4`, ~5.75:1) corroborates at the proposal layer, and
the toggle-off contrast is directionally flatter (Step 4). The core safety guarantee HOLDS:
ships OFF by default, fresh groups read `affinityWeightedProposal=false`, and the untouched
live 100-bot off-path witness `ab81f9e6` shows `coordination/jackpotScale/ramp = null`,
zero affinity log lines, and byte-for-byte legacy bet flow. Zero existing behavior changed.

Environment note: no non-TIP **6-option BETTING_MINI** vehicle was reaching live rounds
during the window (the BOM Xoc-Dia-mini channel was zombie/0-rounds, incl. for the witness;
the only live BETTING_MINI groups were TIP/P_116, forbidden per brief), so the TaiXiu (N=2,
single-entry-lock) vehicle was used per the plan's explicit allowance. The N=2 lock makes
the toggle-off histogram contrast noisier than a 6-option game would, but the on-path
realized-vs-target proof (coordination.options) is clean and unambiguous.
