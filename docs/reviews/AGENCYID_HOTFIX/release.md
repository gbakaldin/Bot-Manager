# Release — AGENCYID_HOTFIX (deposit agencyId=1)

Mode: bot
Branch: main (working-tree change deployed uncommitted; NOT committed, NOT pushed)
Image: vingame-bot:latest (built 2026-07-08 ~16:40 local / deployed 12:42 UTC on Bot-1)
Date: 2026-07-08

Change under test: `GameMsClient.deposit(...)` — `int agencyId = 1;` (was
`Integer.parseInt(agencyToken.substring(0,1))`). Hypothesis: BOM deposits were
targeting the wrong wallet partition; forcing agencyId=1 should make the game
server see the deposited balance.

Note: working tree was on branch `main` (not `feat/crowd-aware-coordination` as
briefed) — crowd-aware is already merged to main. Deployed from main with the
uncommitted hotfix intact.

## Build

- `mvn clean install`: PASS (27.5s, 1471 tests, 0 failures/errors)
- `docker build --no-cache --platform linux/amd64`: PASS
- `docker save`: PASS (392,986,624 bytes)

## Ship

- `sftp put bot.tar`: PASS (remote size 392,986,624 bytes, exact match)

## Deploy

- `docker compose down`: PASS (all 6 containers removed)
- `docker image rm vingame-bot:latest`: PASS (prior image untagged/deleted)
- `docker load`: PASS (Loaded image: vingame-bot:latest)
- `docker compose up -d`: PASS (all 6 containers created + started)

## Smoke test

- `docker ps` shows healthy: PASS — bot-manager `Up (healthy)` at ~42s; full
  co-located stack back UP: grafana, prometheus, promtail, loki all Up;
  mongo `(healthy)`.
- Spring Boot ready log: PASS — `Started Starter in 14.351 seconds`
- Auto-start log: PASS — `Bot Manager startup complete. 13 bot groups running`;
  all 13 groups auto-started incl. BOM `ab81f9e6` and RIK `75899bb9`.

## Plan verification (3-group live check)

### Group 1 — 097 BOM (PRIMARY TARGET): FAIL
Group `ab81f9e6-764b-4785-9039-84add04e2fb0`, bots `bomflowtest`, gateway
apigw-bomwin.sgame.us, Xoc Dia mini. Fresh deposits post-restart use agency
token `29-…` (agencyId now forced to 1 in code).

The hotfix did NOT fix the problem. The authoritative server balance is frozen
at exactly 1,000,000,000 for every BOM bot, every round — the wallet the game
reads is untouched despite the bots staking millions per round.

- Command: `grep bomflowtest console.log | grep 'checkBalance() fetched:'` (post-restart)
- Expected (if fixed): server-fetched balance decrements below 1B after bets settle
- Actual: **800 of 800** server fetches returned `fetched: 1000000000` (zero decrements)
- Command: `grep bomflowtest console.log | grep completeRound | grep 'payout='`
- Actual: **900 of 900** rounds `payout=0` — not a single win, i.e. no bet ever landed on the server ledger

Concrete evidence (bomflowtest3, sessionId 2413736):
```
BotMemory.completeRound: sessionId=2413736, payout=0, staked=4105000, delta=-4105000   (local model only)
checkBalance() ENTRY. lastFetched: 1000000000, expected: 995895000, delta: 4105000
checkBalance() fetching from server (delta > 1M)
checkBalance() fetched: 1000000000        <-- server balance UNCHANGED after staking 4.1M
```
Next round (2413737): stakes 4,595,000, expects 995,405,000, server again returns
`fetched: 1000000000`. The `staked=/delta=` values are the local BotMemory model
(known trap — not proof of acceptance); the authoritative server fetch stays
pinned at the full 1B round after round. No `BALANCE_NOT_ENOUGH` / `không đủ`
frames appear (0 genuine matches), but the pass/fail signal defined for this
release — server-side balance actually decrements on bets — is negative.

### Group 2 — 114 RIK (regression control): PASS
Group `75899bb9-5507-415a-8e83-2d9250ac46c5`, bots `riktxjp`, api-gwrik.sgame.us.
Still betting and settling live rounds. Balances vary across bots and drift over
rounds (real, moving server-side wallets), unlike BOM's frozen 1B.
- Evidence: `riktxjp` session balances span e.g. 1004812351, 1004914551,
  1005005451 … 1023389602 … (many distinct, drifting values on live
  sessionId=10806) — server ledger is live and moving. No regression.

### Group 3 — 116 TIP (regression control): PASS (not BLOCKED this run)
Gateway apigw-tipclub.sgame.us, token prefix `189-`. Two active P_116 groups
("Fruit shop Bots" `4d7f6ac9`/`fru1tsh0p`, "116 Demo group" `b1e80470`/`demob0t1a`).
Both reach live rounds and settle bidirectionally — the known
`tipclubgw-sock.stgame.win` DNS block did NOT manifest this run (0 DNS-failure
lines post-restart).
- Evidence (demob0t1a, sessionId 2858833): real mixed settlements
  `payout=9107424 (delta +3837424, a WIN)`, `payout=4213883`, `payout=1631180`;
  480 (fruit-shop) + 458 (demo) completeRounds post-restart. Server accepts and
  settles TIP bets — no regression.

## Verdict

FAIL — deploy + smoke + regression controls all PASS, but the PRIMARY TARGET
(097 BOM) fix did NOT work. agencyId=1 did not make the game server see the BOM
deposit; server-side balance stays frozen at 1B and every BOM bet still fails to
land on the ledger (payout=0, no wins, no decrement). The wrong-wallet-partition
hypothesis is not resolved by forcing agencyId=1.

## Logs (FAIL evidence — 097 BOM)

BOM server-fetched balance, post-restart aggregate: `800 x "fetched: 1000000000"`, 0 decrements.
BOM payout distribution, post-restart aggregate: `900 x "payout=0"`, 0 wins.
Per-bot trace (bomflowtest3): stakes 4.1M → server balance still 1000000000.

Contrast on the SAME deploy (proves server-side settlement works elsewhere):
- TIP demob0t1a: `payout=1345308 / 6165993 / 6670483 / 1513471` (real wins/losses)
- RIK riktxjp: distinct drifting balances across bots/rounds

BOM is the only group of the three where the server ledger is untouched.
