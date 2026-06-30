# Release — TIP_ENDGAME

Mode: bot
Branch: feat/tip-endgame
HEAD: 77c797c97247081bf4e12051c88a04e9fc30a1bc
Image: vingame-bot:latest (built 2026-06-11 ~15:21 local, deployed ~11:23 UTC)
Date: 2026-06-11T11:32:32Z

## Shipping

6 commits on top of main:

- `b5a8d52` test: Tip Jackson fixture coverage (5 fixtures + TipGameMessageTypesTest)
- `4180bc0` feat: capability interfaces on TipEndGameMessage (HasBotWinnings, HasJackpot, HasBetTotals)
- `0a029b9` refactor: ENDGAME_METRICS Phase B — move bet counters to HasBetTotals dispatch
- `17c8d50` refactor: ENDGAME_METRICS Phase C — delete obsolete capability hooks
- `3439a8b` test: QA — dispatch-level integration tests pinning jpV vs tJpV
- `77c797c` fix: reviewer findings (lastRoundWinnings local, Javadoc, incBetsPlaced split guards)

No schema or dependency changes.

## Pre-deploy

- Working tree: docs-only dirty (`.gitignore`, `CLAUDE.md`, `docs/plans/OBSERVABILITY.md`, `docs/reviews/OBSERVABILITY/release.md`, `src/main/java/com/vingame/bot/common/Stub.java` untracked) — confirmed acceptable per pre-confirmation.
- Pre-existing image timestamp on Bot-1: 2026-06-11T10:44:55Z
- Bot groups running pre-deploy: `116 Demo group` (30 bots, ACTIVE, Tip/P_116 env, Bau Cua game) + `XD game test` (20 bots, ACTIVE, Xoc Dia).

## Build

- `mvn clean install`: PASS — 445 tests run, 0 failures, 0 errors, 0 skipped (~30s)
- `docker build --no-cache --platform linux/amd64 -t vingame-bot:latest .`: PASS (10s)
- `docker save -o bot.tar vingame-bot:latest`: PASS (392,165,888 bytes / 374 MiB)

## Ship

- `sftp put bot.tar` to `Bot-1:/home/sgame/bot-java/bot.tar`: PASS (~31s)

## Deploy

- `docker compose down`: PASS
- `docker image rm vingame-bot:latest`: PASS (Deleted: sha256:0c3db79307ab…)
- `docker load -i bot.tar`: PASS (Loaded image: vingame-bot:latest)
- `docker compose up -d`: PASS — all containers started (bot-manager, mongo, loki, promtail, prometheus, grafana)

## Smoke test

- `docker ps` shows healthy: PASS — `bot-java-bot-manager-1   Up 35 seconds (healthy)` ~110s after `up -d`.
- Spring Boot ready log: PASS
  - `11:23:47.204 [main] INFO  Starter [//] - Started Starter in 12.569 seconds (process running for 13.598)`
- Auto-start log: PASS
  - `11:23:37.588 BotGroupBehaviorService - Auto-starting bot group: 116 Demo group (ID: b1e80470-…)`
  - `11:23:46.039 BotGroupBehaviorService - Auto-starting bot group: XD game test (ID: 40fa3749-…)`
  - `11:23:46.440 BotGroupBehaviorService - Bot Manager startup complete. 2 bot groups running`

## Post-deploy verification

### Group recovery

`116 Demo group` (Tip/P_116, Bau Cua):
- targetStatus = ACTIVE
- 30/30 connected (`connectedBots:30, deadBots:0, disconnectedBots:0`)
- Bots showing non-zero `totalBetsPlaced`, `totalBetAmount`, and `lastRoundWinnings` after several rounds — `BettingMiniGameBot` runtime healthy.

`XD game test` (Xoc Dia, non-Tip / non-HasBetTotals game):
- targetStatus = ACTIVE
- 20/20 connected (`connectedBots:20, deadBots:0, disconnectedBots:0`)
- Bots active and placing bets.

### Prometheus counters spot check

`curl /actuator/prometheus | grep -E "bot_(winnings|jackpots|bets_placed|bet_amount)_total"`

For Tip-env group `b1e80470-…` (Bau Cua game, MiniGame `gourdCrabWithExtraBonusPlugin`):
```
bot_bet_amount_total   {gameType="Bau Cua"} 4.135E8
bot_bets_placed_total  {gameType="Bau Cua"} 48570.0
bot_jackpots_total     {gameType="Bau Cua"} 57.0
bot_winnings_total     {gameType="Bau Cua"} 3.42029003E8
```

All four counters are non-zero and incrementing. EndGame dispatch path (`HasBotWinnings` / `HasJackpot` / `HasBetTotals`) is firing correctly.

For non-Tip-env group `40fa3749-…` (Xoc Dia, `shakeDiskPlugin`): no rows emitted, consistent with ENDGAME_METRICS plan AD-8 (no HasBetTotals implementer for non-Tip pipelines). Not a regression.

### Caveat — TipEndGameMessage path not exercised end-to-end

The new capability interfaces on `TipEndGameMessage` are deployed but **not exercised** by any live group: the only Tip-env group (`116 Demo group`) runs the Bau Cua plugin, whose frames are deserialized as `BomEndGameMessage`, not `TipEndGameMessage`. Confirmed by grep — zero `TipEndGame` / `TipGameMessage` references in 7,047 lines of post-startup logs.

The dispatch-level integration tests (`3439a8b`) pinning `jpV` vs `tJpV` cover that code path in CI. End-to-end staging exercise requires a group with a native Tip game (not Bau Cua over Tip auth) — flagged in user-supplied staging follow-ups.

## Verdict

SHIPPED

Build green, deploy clean, both target groups recovered, dispatch metrics non-zero on the Tip-env group. The `TipEndGameMessage`-specific dispatch path is wired but unexercised in staging; flagged for follow-up but not a release blocker since BomEndGame dispatch (same plumbing, exercised here) works as expected.

## Notes for follow-up (operator-level, deferred)

- Capture a real Tip UpdateBet frame for `gS`/`rmT` check (verifies `TipUpdateBetMessage.getGameState()` hardcoded 0).
- Capture a Tip EndGame frame with `iJp=false` to confirm `jpV` (per-user) vs `tJpV` (pool) decision.
- Configure a native-Tip-game bot group to exercise the new `TipEndGameMessage` capability dispatch in staging.
