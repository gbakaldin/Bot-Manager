# Release — BOT_DEPOSIT_GWMS

Mode: bot
Branch: main (working tree DIRTY — built straight from uncommitted change, NOT committed, NOT pushed)
Image: vingame-bot:latest (built at 2026-07-08T13:07:57Z, sha256:637f1ea0a645…)
Date: 2026-07-08T13:16Z

Change: deposit-path rewrite for ALL products — `Bot.deposit()` now calls the new
`ApiGatewayClient.deposit(username, amount)` → `POST <per-env gwms>/gwms/v1/bot/deposit.aspx`
(header `X-TOKEN` = per-env auth-profile admin token; body `{"username","amount"}`),
replacing the legacy `GameMsClient` agency-transfer path.

## Build

- `mvn clean install`: PASS (26.3 s, 1471 tests, 0 failures / 0 errors)
- `docker build --no-cache --platform linux/amd64`: PASS
- `docker save -o bot.tar`: PASS (392,987,136 bytes)

## Ship

- `sftp put bot.tar`: PASS (remote size 392,987,136 == local — byte-exact)

## Deploy

- `docker compose down`: PASS (all 6 containers + network removed)
- `docker image rm vingame-bot:latest`: PASS (prior image untagged + deleted)
- `docker load -i bot.tar`: PASS (Loaded image: vingame-bot:latest)
- `docker compose up -d`: PASS (all 6 containers created + started; mongo Healthy gate passed)

## Smoke test

- `docker ps` shows healthy: PASS — all 6 UP; bot-manager + mongo `(healthy)`;
  loki/promtail/prometheus/grafana `Up` (no healthcheck defined — normal)
- Spring Boot ready log: PASS — `Started Starter in 14.992 seconds`
- Auto-start log: PASS — `Bot Manager startup complete. 13 bot groups running`

## Plan verification (coordinator 4-point checklist)

Authoritative signal = `checkBalance() fetched` (server-side balance) from
`~/bot-java/logs/console.log`. Post-restart window ~13:09:46–13:14Z.

### Step 1: 097 BOM — PRIMARY (was FAIL). Group ab81f9e6, bomflowtest, env 3cda38f9, Xoc Dia.
Expected: server balance decrements on bets / no frozen-1B; no BALANCE_NOT_ENOUGH;
`[BotDeposit] POST …/gwms/v1/bot/deposit.aspx` HTTP 200.
Actual:
- `checkBalance() fetched` = `1000000000` for ALL 1900+ post-restart fetches — the ONLY
  distinct value. Server balance STILL FROZEN AT EXACTLY 1B.
- Local model diverges but server does not: e.g. `checkBalance() ENTRY. lastFetched:
  1000000000, expected: 995995000, delta: 4005000` (bot thinks it spent; server unchanged).
- Every EndGame: `total staked: ~428M | total win: 0 | bettors: 100 | confirmed staked: 0`.
- No `BALANCE_NOT_ENOUGH` anywhere (0 occurrences) — bets are ACCEPTED but NOT DEBITED.
- `[BotDeposit]` log line: ABSENT (0 across all envs) — the new deposit endpoint was
  NEVER called: server keeps reporting 1B, so no bot ever crossed the deposit threshold.
Result: FAIL — original frozen-1B symptom persists. The deposit rewrite does not touch it;
the freeze is at the bet-DEBIT layer, and the new deposit path was never exercised.

### Step 2: 114 RIK — REGRESSION WATCH (was PASS). Group 75899bb9, riktxjp, env 394301f4.
Expected: still settles on the new endpoint.
Actual: server balance MOVES — many distinct `fetched` values (1.018B, 1.022B, 1.024B,
1.145B, 0.114B, …). Bets settle server-side.
Result: PASS — no regression.

### Step 3: 116 TIP — REGRESSION WATCH (was PASS). Group b1e80470, demob, env ad4e7948.
Expected: still settles (DNS-block caveat).
Actual: server balance MOVES (10.19M, 10.48M, 11.08M, 11.66M, 120.99M, …). No
UnknownHost / tipclubgw-sock DNS-block errors this cycle.
Result: PASS — no regression, DNS block did not recur.

### Step 4: Other active groups (098/B52, slots) — spot-check deposits didn't regress.
Actual: All remaining groups share env ad4e7948 and show server-balance movement
(many distinct `fetched` values per group: fru 1360, xdt 20, sl 20, slots 18, etc.).
One anomaly: 66cfc12c (txb, Tai Xiu) reports `fetched: 0` (bots unfunded) — a single
distinct value, pre-existing 0-balance state unrelated to this deposit change.
Result: PASS — no deposit regression on 098/slots/other.

## [BotDeposit] non-200 list (decisive for global-vs-localize)

NONE. Zero `[BotDeposit] non-200` WARN lines on ANY env.
IMPORTANT CAVEAT: this is because the new deposit endpoint was called ZERO times in the
observation window (0 `[BotDeposit]` lines total) — not because it was called and
succeeded. The go-global-vs-localize question CANNOT be answered from this run: the new
path is UNTESTED in production. Bots start at 1B and only deposit when the server-reported
balance drops below threshold; on the envs where balance moves it hadn't dropped far enough
yet, and on 097 it never drops (frozen). A longer soak (or a group deliberately drained
below threshold) is required to exercise `POST /gwms/v1/bot/deposit.aspx`.

## Verdict

FAIL — 097 BOM, the primary target, remains frozen at exactly 1B (`confirmed staked: 0`,
server balance never decrements). 114/116/098/slots did NOT regress (all still settle).
The deposit-path rewrite is deployed and healthy but did not fix the 097 symptom and was
not actually exercised (0 `[BotDeposit]` calls). The 097 freeze is at the bet-debit layer,
which this change does not address.

## Logs

097 BOM frozen server balance vs moving local model (13:12:48Z):
  checkBalance() ENTRY. lastFetched: 1000000000, expected: 995995000, delta: 4005000  (bomflowtest17)
  checkBalance() ENTRY. lastFetched: 1000000000, expected: 996260000, delta: 3740000  (bomflowtest40)
  checkBalance() fetched: 1000000000  (× all 1900+ post-restart 097 fetches; only value)

097 BOM EndGame settlement (server confirms nothing):
  session 2413780 ended | total staked: 428965000 | total win: 0 | bettors: 100 | confirmed staked: 0

114 RIK server balance moving:  fetched: 1018889102 / 1022258702 / 1145987398 …
116 TIP server balance moving:  fetched: 10192580 / 11082580 / 120995733 …

Deposit path: grep -cE 'BotDeposit|deposit.aspx|Deposit successful|Deposit failed' console.log = 0
BALANCE_NOT_ENOUGH: 0 across all groups.
