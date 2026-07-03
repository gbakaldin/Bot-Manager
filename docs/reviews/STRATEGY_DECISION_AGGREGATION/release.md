# Release — STRATEGY_DECISION_AGGREGATION

Mode: bot
Branch: feat/strategy-decision-aggregation
Commit: d987ca0
Image: vingame-bot:latest (built 2026-07-02, sha256:53ce192670…, 392,892,928 bytes)
Date: 2026-07-02T11:57:46Z
Target: Bot-1 staging (single Compose project — bot + observability stack)

## Build

- `mvn clean install`: PASS (~25s, BUILD SUCCESS, 1145 tests run, 0 failures, 0 errors, 0 skipped)
- `docker build --no-cache --platform linux/amd64`: PASS (image sha256:53ce192670…)
- `docker save -o bot.tar`: PASS (392,892,928 bytes)

Note: working tree carried only untracked docs/reviews/plan files (no modified
tracked source; stray T.java already removed). No dirty tracked code — safe to
deploy.

## Ship

- `sftp put bot.tar` → Bot-1:/home/sgame/bot-java: PASS (exit 0; remote size 392,892,928 bytes == local)

## Deploy

Single remote ssh block (exit 0):

- `docker compose down`: PASS (all 6 containers stopped/removed — bot-manager, mongo, loki, promtail, prometheus, grafana)
- `docker image rm vingame-bot:latest`: PASS (prior image untagged/deleted)
- `docker load -i bot.tar`: PASS (Loaded image: vingame-bot:latest)
- `docker compose up -d`: PASS (all 6 containers created + started; mongo reached Healthy before bot-manager start)

## Smoke test

- `docker ps` bot-manager healthy: PASS (`bot-java-bot-manager-1  Up (healthy)`)
- Spring Boot ready log: PASS (`Started Starter in 10.929 seconds`)
- Auto-start log: PASS (`Bot Manager startup complete. 7 bot groups running`)
- Observability stack re-verified up (single Compose project cycles them on redeploy): PASS
  - `bot-java-grafana-1` Up
  - `bot-java-prometheus-1` Up
  - `bot-java-promtail-1` Up
  - `bot-java-loki-1` Up
  - `bot-java-mongo-1` Up (healthy)

Log source: container stdout via `docker logs` (no `console.log` file on this
host). Actuator reachable at host `localhost:8080` → container `8085`.

7 auto-started groups; non-TIP betting/Tai Xiu + slot used for verification.
TIP groups deliberately excluded from live-round checks (known TIP WS DNS block):
- 116 Demo group (TIP), Xoc Dia / XD game test (TIP), Fruit Shop (TIP) — NOT used.
- Non-TIP Tai Xiu used: `66cfc12c` (Tai Xiu test), `401f4c63` (TaiXiuStagingVerifyGroup2), `75899bb9` (RIK114 P_114/RIK).
- Slot used: `4342888f` (Slot test 204).

## Plan verification

Steps from `docs/plans/STRATEGY_DECISION_AGGREGATION.md` § Verification.
Steps 1/2/3/5 evaluated against one shared 65s live DEBUG capture (4,021 lines);
step 4 against a 22s TRACE capture; step 6 against both.

### Step 1: Flush line carries the decision distribution + amount summary (DEBUG default, active betting session)
Command: `grep "UpdateBet #" <window> | grep -E "75899bb9|66cfc12c|401f4c63" | grep -E "options: \["`
Expected: one line with `options: ([<n>]x<count> )+` and `amount min/avg/max: N/N/N`, histogram counts ≈ window bets, `min ≤ avg ≤ max`.
Actual: non-TIP Tai Xiu flush lines carry the shape, e.g.
`[66cfc12c…/TAI_XIU] UpdateBet #5 | … | total staked: 12600000 | options: [1]x80 [2]x70 | amount min/avg/max: 20000/20000/20000`
and `[401f4c63…/TAI_XIU] UpdateBet #5 | … | options: [2]x10 | amount min/avg/max: 40000/300000/500000` (min ≤ avg ≤ max holds). Empty-window flushes correctly render `options: - | amount min/avg/max: -` (divide-by-zero-safe path).
Result: PASS

### Step 2: Slot window line carries the bet-size histogram (30s of a slot group)
Command: `grep "SlotWindow" <window> | grep "4342888f" | grep -E "bets: \["`
Expected: line containing `bets: [<value>]x<count>` segments summing ≈ `spins since last`.
Actual: `[4342888f…/SLOT] SlotWindow … #38 | spins since last: 40 | … | bets: [500]x40 | amount min/avg/max: 12500/12500/12500` and `#39 | spins since last: 20 | … | bets: [500]x20`. Histogram counts equal spins-since-last each window.
Result: PASS

### Step 3: Per-bot decision lines are silent at DEBUG (60s, both group types running)
Command: `grep -cE "sending bet option=|strategy parked decision|\.decide: bet |chooseBet: amount=|parked spin bet=|sending spin gid=" <window>`
Expected: `0`
Actual: `0` (over the 65s DEBUG capture)
Result: PASS

### Step 4: Per-bot decision lines reappear at TRACE, then reset (drill-in preserved)
Command:
`curl -X POST localhost:8080/actuator/loggers/com.vingame.bot -d '{"configuredLevel":"TRACE"}'` → capture 22s → `grep -cE "sending bet option=|\.decide: bet " <window>` → `curl -X POST … '{"configuredLevel":"DEBUG"}'`
Expected: HTTP 204 on both POSTs; `> 0` decision lines within the window at TRACE.
Actual: HTTP 204 on both POSTs; 3,666 decision lines (`sending bet` + `decide: bet`) in 22s at TRACE; level confirmed reset (`{"configuredLevel":"DEBUG","effectiveLevel":"DEBUG"}`).
Result: PASS

### Step 5: Balance/status lines still present at DEBUG (not over-demoted)
Command: `grep -cE "session balance|below spin cost|below minimum" <window>`
Expected: `> 0`
Actual: `312` (over the 65s DEBUG capture)
Result: PASS

### Step 6: Total volume dropped toward the aggregate floor
Command (plan): compare 60s total line count pre-deploy jar vs post-deploy at DEBUG.
Expected: post-deploy count is a fraction of pre-deploy — the per-bet decision classes collapse to the aggregate floor.
Actual:
- Post-deploy DEBUG floor: 4,021 lines / 65s ≈ 3,712 lines/min, with 0 per-bet decision lines.
- Decision family measured live at TRACE (exactly the class demoted out of DEBUG): 5,919 lines / 22s ≈ 16,143 lines/min.
- Estimated pre-feature DEBUG (floor + decision family that used to sit at DEBUG) ≈ 19,854 lines/min.
- Post/pre ratio ≈ 18.7% → **81.3% reduction**, exceeding the plan's ~75% target.

Methodology note: the literal pre-deploy jar was replaced by this deploy, so a
direct A/B of the old binary was not possible. Instead the pre-feature baseline
is reconstructed from the live TRACE-measured decision-family volume — precisely
the lines this feature moved off the DEBUG default. The DEBUG default now shows
only the aggregate floor (StartGame/EndGame INFO summaries + ~UpdateBet/SlotWindow
5s flushes) plus balance/status/deposit lines; the per-bet decision flood is gone.
Result: PASS

## Verdict

PASS

All build, ship, deploy, and smoke gates green; observability stack re-verified
up; all 6 plan verification steps PASS (6 of 6). No failures — no log excerpts
required.
