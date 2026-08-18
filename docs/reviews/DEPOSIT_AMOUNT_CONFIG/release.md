# Release — DEPOSIT_AMOUNT_CONFIG

Mode: bot
Target host: **Prod-Bot** (PRODUCTION) — `/home/sgame/bot-java`
Branch: `staging` @ `91ab7d8` **plus uncommitted working-tree changes** (intentional, see below)
Image: `vingame-bot:latest` = `4a6af5b0b52b` (built 2026-08-06 15:04:27 +04)
Previous image: `f07d4170d34d` (preserved as `vingame-bot:rollback-20260806`)
Date: 2026-08-06T11:08:13Z

## Authorized deviations from standing releaser procedure

All four were explicitly authorized by the user for this deploy:

1. **Target is `Prod-Bot`, not `Bot-1`.** Bot-1/staging was not contacted at any
   point in this release.
2. **Dirty working tree deployed without committing.** `docs/plans/PROD_DEPLOYMENT.md`
   AD-1 requires the prod image be built from the exact current working tree. The
   clean-tree guard was skipped. Nothing was committed or pushed.
3. **No `deploy.sh`, no compose-file overwrite.** Only `bot.tar` was shipped. The
   hand-edited remote `docker-compose.yml` was left untouched (md5 verified
   identical before and after — `40629c35e5b6bf9349a5d743261d275e`).
4. **No `docker compose down`.** Only the `bot-manager` service was recreated; the
   other five services in the shared compose project were left running.

### Working tree shipped

```
 M bot-api/.../config/bot/BotBehaviorConfig.java
 M bot-app/.../botgroup/service/BotGroupBehaviorService.java
 M bot-app/src/main/resources/application.properties
 M bot-engine/.../domain/bot/core/Bot.java
 M bot-engine/src/test/.../BotTest.java
```

Change under release: the auto-deposit top-up amount, previously hardcoded to
`1_000_000_000L` in `Bot.deposit()`, now reads `bot.deposit.amount` (default
`1000000000`) plumbed through `BotBehaviorConfig.depositAmount`; `0` falls back to
`Bot.DEFAULT_DEPOSIT_AMOUNT`. Prod overrides to `5000000` via `BOT_DEPOSIT_AMOUNT`.

## Build

- `mvn clean install -DskipTests -Dmaven.javadoc.skip=true`: **PASS** (10.9 s, 6/6 modules)
  - Tests skipped by instruction; the full suite (808 tests, 0 failures) had already
    passed locally before this release.
- `docker build --no-cache --platform linux/amd64`: **PASS** (4 m 13 s) → `4a6af5b0b52b`
  - Docker daemon was not running at first attempt; started Docker Desktop and retried.
    No impact on the artifact.
- `docker save -o bot.tar`: **PASS** (417,212,416 bytes)

### Artifact content verification (extra check, not in the standard pipeline)

Because a dirty tree was deployed, the built jar was inspected to confirm the change
actually landed in the shipped artifact:

- `BOOT-INF/classes/application.properties` line 71 → `bot.deposit.amount=1000000000` ✅
- `bot-engine-1.0.jar` → `Bot.class` contains symbols `DEFAULT_DEPOSIT_AMOUNT` and
  `resolveDepositAmount` ✅

## Ship

- `sftp put bot.tar`: **PASS** (1 m 13 s)
- Remote size check: `417212416` == local `417212416` — byte-count identical ✅

## Deploy

- `docker compose down`: **SKIPPED** (authorized — would have taken down the whole stack)
- `docker tag vingame-bot:latest vingame-bot:rollback-20260806`: **PASS** (run *before* the rm)
- `docker image rm vingame-bot:latest`: **PASS**
- `docker load -i bot.tar`: **PASS**
- `docker compose up -d bot-manager`: **PASS**

Remote command executed (single chained invocation, exit 0):

```bash
cd /home/sgame/bot-java && \
  docker tag vingame-bot:latest vingame-bot:rollback-20260806 && \
  docker image rm vingame-bot:latest && \
  docker load -i bot.tar && \
  docker compose up -d bot-manager
```

### Rollback

Rollback tag confirmed present **after** the deploy:

```
vingame-bot   latest              4a6af5b0b52b   2 minutes ago   404MB
vingame-bot   rollback-20260806   f07d4170d34d   22 hours ago    404MB
```

One-command revert:

```bash
docker tag vingame-bot:rollback-20260806 vingame-bot:latest && docker compose up -d bot-manager
```

### Collateral check — other services untouched

| Container | Status after deploy |
|---|---|
| `bot-java-bot-manager-1` | Up (healthy) — **recreated, intended** |
| `bot-java-mongo-1` | Up 2 days (healthy) — untouched |
| `bot-java-loki-1` | Up 2 days — untouched |
| `bot-java-promtail-1` | Up 2 days — untouched |
| `bot-java-grafana-1` | Up 2 days — untouched |
| `bot-java-prometheus-1` | Up 2 days — untouched |

Avoiding `docker compose down` worked as intended: uptime on the other five is
unbroken, so the usual "bot redeploy takes down Grafana/Loki too" side effect did
not occur on this release.

## Smoke test

- `docker ps` shows healthy: **PASS** — `health: starting` at 10 s/20 s, `(healthy)` at 32 s
- Spring Boot ready log: **PASS** — `Started Starter in 3.814 seconds (process running for 4.589)` @ 11:06:33.532
- Auto-start log: **PASS** — `Bot Manager startup complete. 1 bot groups running` @ 11:06:32.992

## Plan verification

**Note on substitution:** there is no `docs/plans/DEPOSIT_AMOUNT_CONFIG.md`, so no
plan `## Verification` section exists for this feature. The user supplied five
explicit verification steps with the release request; those are used as the
authoritative verification list and are recorded below. (`docs/plans/PROD_DEPLOYMENT.md`
does have a `## Verification` section, but it covers the *initial* prod bring-up, not
this deploy; two of its checks that remained relevant are folded in as supplementary.)

### Step 1: Container healthy
Command: `docker ps --filter name=bot-manager`
Expected: `(healthy)`, allowing ~75 s
Actual: `Up 32 seconds (healthy)` — reached health well inside the window
Result: **PASS**

### Step 2: Spring Boot started
Command: `docker logs bot-java-bot-manager-1 2>&1 | grep -E "Started Starter"`
Expected: log line present
Actual: `11:06:33.532 [main] INFO Starter [//] - Started Starter in 3.814 seconds (process running for 4.589)`
Result: **PASS**

### Step 3: Environment endpoint returns TIP Production
Command: `curl -s http://localhost:8080/api/v1/environment/`
Expected: the `TIP Production` environment
Actual: one environment returned — `id=78b6eefb-8930-4e2d-ba56-2c245ffc8551`,
`name="TIP Production"`, `type=PRODUCTION`, `productCode=116/TIP`,
`totalBotGroups=1`, `totalBots=9`
Result: **PASS**

### Step 4: Both env vars survived
Command: `docker inspect bot-java-bot-manager-1 --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -E 'BOT_IP|BOT_DEPOSIT_AMOUNT'`
Expected: `BOT_IP=43.199.58.254` and `BOT_DEPOSIT_AMOUNT=5000000`
Actual:
```
BOT_IP=43.199.58.254
BOT_DEPOSIT_AMOUNT=5000000
```
Both present in the **running** container. Compose file md5 also unchanged
(`40629c35e5b6bf9349a5d743261d275e` before and after).
Result: **PASS**

### Step 5: Group `b44b1cc9-1eea-41ba-a125-1f876d2dcb9e` auto-start + auth count
Command: `curl -s .../api/v1/bot-group/b44b1cc9-.../health` and log grep for `CONNECTION_AUTHENTICATED`
Expected: group auto-starts (targetStatus ACTIVE); report how many of 9 bots authenticated
Actual: auto-start fired at 11:06:32.085 —
`Auto-starting bot group: TIP Prod - Xoc Dia test (ID: b44b1cc9-...)`.
Health endpoint: `status=ACTIVE`, `totalBots=9`, `connectedBots=9`,
`reconnectingBots=0`, `deadBots=0`, `disconnectedBots=0`.
**9 of 9** bots at `CONNECTION_AUTHENTICATED` (`bottest01`–`bottest09`, all distinct).
Bots were already betting within ~60 s (5 bets each).
No group was started or stopped by the releaser.
Result: **PASS**

### Step 5b (critical): no auto-deposit on a group with `autoDepositEnabled: false`
Command: `docker logs bot-java-bot-manager-1 2>&1 | grep -iE "deposit" | wc -l`
Expected: zero deposit activity — the group has `autoDepositEnabled: false`
Actual: **0** — not one line matching `deposit` (case-insensitive) anywhere in the
post-deploy logs. No `[BotDeposit] POST`, no `Deposit of … successful`, nothing.
Result: **PASS** — no auto-deposit fired where it shouldn't have.

This is a meaningful negative result rather than a vacuous one: at the time of the
check every bot's balance had already fallen **below** `Bot.getMinBalance()`
(`5_000_000`) — see the observation below — so the balance precondition for a top-up
was satisfied and the deposit was suppressed by the `autoDepositEnabled` flag alone.

### Supplementary (from `PROD_DEPLOYMENT.md` § Verification, still relevant)

- Auth-handshake regression check: `grep -c 'Auth handshake timed out'` → **0** — PASS
- Thread baseline: `jvm.threads.live` → **51** (low tens, no sawtooth) — PASS
- ERROR count: **0** — PASS
- WARN count: **0** — PASS

## Verdict

**PASS**

All five user-supplied verification steps passed, plus the critical no-deposit check
and four supplementary checks. Zero ERROR and zero WARN lines since startup.

## Observations for follow-up (non-blocking, no action taken)

1. **The Xoc Dia group is draining with no way to top up.** Every bot's
   `lastFetchedBalance` is exactly `5,000,000` and current balances are already
   below it (4,949,777 average at T+101 s, falling steadily as they bet). Because
   `autoDepositEnabled: false`, these accounts can only go down — the group will
   eventually bet itself to a standstill. Correct behavior for this deploy, and the
   suppression is exactly what step 5b verified, but worth a decision: either enable
   auto-deposit now that the top-up is a sane `5,000,000` rather than
   `1,000,000,000`, or accept that this is a finite-runway smoke group.
   Note the interaction if it is enabled: `getMinBalance()` is hardcoded `5_000_000`
   and the prod deposit amount is also `5_000_000`, so a top-up would land the bot at
   ~10 M and it would re-trigger after ~5 M of drain. That is a workable cycle, but
   the two constants are now numerically coupled by coincidence, not by design.
2. **Stale `lastFailureReason` on the group.** The record still carries
   `"Started 0/10 bots — all bot creations failed"` from an earlier run, while
   `botCount` is now 9 and this start succeeded 9/9. The field is not cleared on a
   successful start, so it reads as a live failure to anyone inspecting the group.
   Cosmetic but actively misleading on a prod group.
3. **`Connected to server` count is 10 against 9 bots.** Off by one versus the nine
   distinct `CONNECTION_AUTHENTICATED` usernames. Most likely one reconnect during
   bring-up. Not affecting health (`reconnectingBots=0`, `disconnectedBots=0`), but
   it means the plan's step-6 heuristic ("count == botCount") is not a reliable
   equality check.
4. **`docker-compose.yml.bak-20260806-170853` sits next to the live compose file** on
   the host. Harmless, but a stray `.bak` adjacent to a hand-edited prod compose file
   is a footgun for whoever next runs a tool that globs the directory.

## Logs

Not applicable — no failures. Full stack post-deploy:

```
NAMES                    STATUS
bot-java-bot-manager-1   Up About a minute (healthy)
bot-java-grafana-1       Up 2 days
bot-java-prometheus-1    Up 2 days
bot-java-promtail-1      Up 2 days
bot-java-mongo-1         Up 2 days (healthy)
bot-java-loki-1          Up 2 days
```
