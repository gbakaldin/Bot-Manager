# Release — RIK_STOCK_SINGLE_ENTRY

Mode: bot (bot-manager-only recreate, per the user's choice)
Target: Bot-1 (staging) only
Branch: feature/gateway-request-budget @ 970ddcf + **uncommitted** working tree (user approved "deploy as is", relayed by the coordinator from an AskUserQuestion in the parent session)
Image: vingame-bot:latest `2acbda6cc952` (built 2026-10-02T06:51:58Z), local only
Date: 2026-10-02T07:06Z

## Status: HELD TWICE. Only `bot.tar` was uploaded; the running service was not changed.

The build is done and `bot.tar` is ready locally. I did not sftp the image, retag anything or recreate a container on the box, because
the **114 Staging WS origin went down at 06:47:30Z**, before any deploy step. It was still down at 07:06Z when I stopped waiting.

## Build

- `mvn clean install` (JDK 21): PASS in 2m16s. 2735 tests run, 0 failures, 0 errors, 0 skipped.
- `docker build --no-cache --platform linux/amd64` (with `DOCKER_CONFIG` set to an empty-auths config, the known credential-helper workaround): PASS, image `2acbda6cc952`.
- `docker save -o bot.tar`: PASS, 396,200,960 B, md5 `d9e13102752e05dd82bd619be7daefc5`.

## Pre-flight (read-only on Bot-1)

| File | Local sha256 | Remote | Action needed |
|---|---|---|---|
| `deploy.sh` (working tree) | `8eccc6bf…` | identical | none |
| `logging/log4j2.properties` | `d2d51b36…` | identical | none |
| `docker-compose.yml` | `feb80e95…` | identical | none |

- Running image: `vingame-bot:latest` = `34d836ae5fc7` (2026-10-01 19:13 +07). This is what would be tagged `vingame-bot:rollback-20261002`.
- Existing rollback tags: `rollback-20261001b` = `b5da0f81efaa`, `rollback-20261001` = `f284ca5390c3`.
- Containers: all 10 up. bot-manager had been up 19 h (healthy) and the observability stack 21 h. Disk use was 43%.

## Why I held: the 114 upstream outage

- **06:46:48Z rollup**, env `394301f4` (114 Staging): `connected=224/224, rounds=28, staked=376,514,000` (healthy).
- **06:47:00–06:47:30Z**: mass `WS disconnected — starting retrial flow` across every 114 group (Coins, Zic Zac, rikslt, riktxm, rikcoin), plus
  the 116 slot group `2bf237bd`. `ScopedDebugEscalator` armed itself for several groups.
- **06:51:48Z rollup**: `connected=0, rounds=0, staked=0, gateway=751/900, queued=0/224/0`.
- From 06:55 to 07:06Z, Coins and Zic Zac `/health` read `connectedBots=0`, with all bots in `CONNECTING`. `logs/detail/detail.log` shows every
  reconnect failing with **`Invalid handshake response getStatus: 502 Bad Gateway`**: 1,803 such lines by 07:01, 2,465 by 07:06,
  in batches of ~200 per minute. Token resolution succeeds. The edge answers, but the origin behind it is gone (the AD-2 "edge synthesising a
  502" shape).
- The env's gateway window stayed at **750/900** throughout, because reconnect logins are being spent at about that rate.

Reasons for holding instead of deploying anyway:

1. **The verification steps can't tell us anything.** Steps 2–4 (Coins/Zic Zac coming back, rounds and staked in the rollup, the lock) all need
   the 114 game to be serving. A deploy now would end in "FAIL, upstream down" and say nothing about the change.
2. **A restart resets the gateway budget while Cloudflare's per-IP count doesn't reset.** The new JVM starts its per-env window at 0/900
   while about 750 requests from this IP are still inside Cloudflare's 5-minute window. The daisy-chain would then spend up to 900 more,
   against Cloudflare's limit of 1,000 requests per 5 minutes per IP. That risks 403s on top of the outage.
3. The running JVM is already handling the outage as designed: paced reconnects inside the budget, and scoped DEBUG armed.

Pre-deploy note for whoever resumes: when I captured them at about 06:53Z, both Coins and Zic Zac `/status` read `botsUp=100/100`. That differs
from the coordinator's "89/100, 11 DEAD" baseline; the groups had evidently been rebuilt since then. The outage followed minutes later.

## Pre-deploy ERROR-class baseline (`com.vingame.bot`, all `console*.log`)

For the step-5 comparison after the deploy happens. These are the existing classes, after normalising names, uuids and numbers:
- `BettingMiniGameBot`/`SlotMachineBot`: `initial session setup failed`, as `Failed to fetch balance …` or `User: …: Data array …`
- `BotGroupBehaviorService`: `Periodic logout failed for bot … : Task java.util.concurrent…` / `Invalid challenge…` / `Unexpected FullHttpResponse…`
- `Bot`: `re-authentication failed — marking DEAD`
- `BotGroupRuntime`: `Bot failed in virtual thread botgroup-…`

## Second attempt: gated wait (user said "continue"; the 114 outage is the 114 game server's own deploy)

The coordinator relayed the user's reply: the outage was the 114 game server's planned deploy, so continue, gated on (a) 502 lines in
`logs/detail/detail.log` stopping and (b) the 114 rollup showing `connected>0`. Poll about every 2 min, give up after about 45 min.

- `sftp put bot.tar`: PASS (07:08Z). Remote `md5sum` = `d9e13102752e05dd82bd619be7daefc5`, matching the local file. The tarball now sits in
  `/home/sgame/bot-java/bot.tar`, unloaded. `vingame-bot:latest` on the box is still `34d836ae5fc7`.
- Gate polling, 07:13Z → 07:53Z (cap). New 502 lines per ~2-min interval, Coins `connectedBots`:

| UTC | new 502 lines | Coins connected |
|---|---|---|
| 07:15 | 1341 | 0 |
| 07:17 | 222 | 0 |
| 07:19 | 1341 | 0 |
| 07:21 | 672 | 0 |
| 07:23 | 276 | 0 |
| 07:25 | 1287 | 0 |
| 07:27 | 225 | 0 |
| 07:29 | 1341 | 0 |
| 07:31 | 864 | 0 |
| 07:33 | 651 | 0 |
| 07:35 | 717 | 0 |
| 07:37 | 225 | 0 |
| 07:43 | 660 | 0 |
| 07:45 | 852 | 0 |
| 07:47 | 279 | 0 |
| 07:49 | 1104 | 0 |
| 07:51 | 678 | 0 |
| 07:53 | 852 | 0 |

- The gate never opened. 114 had been down for about 66 minutes (06:47 → 07:53Z) when I gave up.
- 07:51:48Z rollup, env 394301f4: `connected=0, dead=7, rounds=0, staked=0, gateway=750/900, queued=0/134/0, circuit=closed`.
  Coins `dead=4/100`, Zic Zac `dead=3/100`, `groupDead=false` for both. Individual bots are starting to use up their per-bot reconnect budget
  (about 51 min), so expect the DEAD count to keep rising the longer this lasts. Dead-group recovery covers whole groups that cross the threshold, not individual bots.

## Odd: `/status.botsUp` is not a connectivity signal

Throughout the outage `GET /{id}/status` returned `actualStatus=ACTIVE, botsUp=100` for both Coins and Zic Zac, while `/health`
returned `connectedBots=0` and the rollup returned `connected=0`. So `botsUp` counts live bot runtimes, not connected sockets. The 06:53Z
"100/100" I recorded earlier is therefore **not** evidence that the groups were connected. For step 2 after the deploy, use `/health`
`connectedBots` (or the rollup's `connected=`), not `botsUp`.

## Ship / Deploy / Smoke / Plan verification

- Ship: `bot.tar` uploaded and md5-verified (see above).
- Tag / `docker load` / `compose up -d bot-manager`: **not run** (gate closed at the cap).
- Smoke, and verification steps 1–5: not run.

## To resume (once 114 is serving again: rollup `connected>0`, no 502 in detail.log)

```bash
# local: bot.tar (md5 d9e13102…) is already built from this tree; rebuild only if the tree changes
# bot.tar is ALREADY on the box (md5 d9e13102…); just re-check it
ssh Bot-1 'cd /home/sgame/bot-java && md5sum bot.tar &&
  docker tag 34d836ae5fc7 vingame-bot:rollback-20261002 &&
  docker load -i bot.tar && docker compose up -d bot-manager'
```

Rollback after that:
`ssh Bot-1 'cd /home/sgame/bot-java && docker tag vingame-bot:rollback-20261002 vingame-bot:latest && docker compose up -d bot-manager'`

Wait for the 114 env's gateway window to drop well below 900 before recreating, because a new JVM's budget starts at 0.

## Verdict

**NOT DEPLOYED.** I held at 07:06Z, then gave up at the 45-min gate cap (07:53Z) with 114 still returning 502. The build passed, and `bot.tar` is
uploaded and verified on Bot-1. The running service is unchanged (`latest` = `34d836ae5fc7`, bot-manager not recreated, no rollback tag created).

---

## Outcome — DEPLOYED 2026-10-02 10:26Z (run from the parent session after 114 came back)

- 114 origin back by ~10:25Z (anonymous WS probe → 101). Gateway window drained to 5/900 before deploy.
- `md5 bot.tar` = `d9e13102752e05dd82bd619be7daefc5` (matches). Tagged `34d836ae5fc7` → `vingame-bot:rollback-20261002`; `docker load`; `docker compose up -d bot-manager`. Running `latest` = `2acbda6cc952`.
- Smoke: `Started Starter` 10:26:19, "4 bot groups queued for daisy-chained start", bot-manager healthy; Grafana/Prometheus/Loki/Alertmanager untouched (Up 25 h).
- Coins and Zic Zac had gone group-DEAD (100/100) during the 3.5 h 114 outage; staging runs with `BOT_RECOVERY_ENABLED=false`, so boot did not start them. Operator `/restart` on both at 10:27Z.
- 10:36Z: Coins 100/100 connected, 0 dead, 19 rounds; Zic Zac 100/100, 0 dead, 20 rounds. Env rollup `connected=200, dead=0, rounds=20, staked=461,564,000` (5 min, both groups). Gateway peaked 675/900 during the restarts, circuit closed.
- 0 `com.vingame.bot` ERROR lines since deploy.
- Lock check: not directly observable (remap line is TRACE; no global TRACE flip). Proof = drop in the 114 backend's `BETTING_INVALID` count — to be confirmed with the 114 team.

Release verdict: PASS
Rollback: `ssh Bot-1 'cd /home/sgame/bot-java && docker tag vingame-bot:rollback-20261002 vingame-bot:latest && docker compose up -d bot-manager'`
