# BOT_PROVISIONING — release to Bot-1 (staging), 2026-10-05

Verdict: **PASS** — all 11 plan verification steps green.

- Branch `feature/bot-provisioning` @ 8183957 (Phases 1+2, both fix rounds, review/QA/compliance PASS,
  merged with f6f5280 so the stock single-entry fix and 114/119 VipTalk rooms are preserved).
  `mvn clean install`: 2877 tests, 0 failures, 0 skipped.
- Image `b5710416bdd2` (bot.tar md5 `99281e02f0cf91a20a99d5bdf6f2a909`). Bot-manager-only recreate;
  observability stack untouched. Rollback tag `vingame-bot:rollback-20261005` = `bd20322f1225`.
  `ssh Bot-1 'cd /home/sgame/bot-java && docker tag vingame-bot:rollback-20261005 vingame-bot:latest && docker compose up -d bot-manager'`
- Smoke 07:57:52Z: Started Starter, 6 groups queued for daisy-chained start, VipTalk rooms [P_114, P_116, P_119].

| Step | Result |
|---|---|
| 1 health | 200 |
| 2 funded create (097, prefix prov0759, 3 bots, initialDeposit 1,000,000, autoDeposit off) | REGISTRATION_PENDING, initialDeposit 1000000, depositedCount 0 |
| 3 completion | registered 3 / named 3 / deposited 3, no in-flight marker, < 1 min |
| 4 log | `registration complete, 3/3 accounts (3 named), 3 funded x 1000000 = 3000000` |
| 5 metrics | credited 3; unknown / refused / not_sent / skipped_existing all 0.0 (pre-registered) |
| 6 validation | initialDeposit -1 → 400; 1,000,000,001 → 400 |
| 7 money landed | all 3 bots `lastFetchedBalance` = 1,000,000 from the server, betting |
| 8 grow while running 3→5 | registered/named/deposited 5; `2 funded x 1000000` (no retro-funding); `attached 2/2 bots (4-5), 5 bots in the group`; 0 `restart requested`; new bots at 1,000,000 |
| 9 unfunded create | depositedCount 0, deposit metric sum unchanged (5 → 5) |
| 10 username cap on raise | TIP, `existingGroup=true` prefix `provcaptest` ×9 → PATCH 10 = 400 naming the 12-char cap (existingGroup used so no TIP accounts were registered) |
| 11 cleanup | 3 test groups deleted (200, then 404) |

- 0 `com.vingame.bot` ERROR lines since deploy. Coins / Zic Zac 100/100, 119 probes and Slot 120 all connected.
- Money moved on 097 staging: 5 × 1,000,000 to `prov07591..5`. The accounts remain upstream.
- Not exercised on staging (unit-tested only): unknown-outcome hand-off + `/registration/retry?depositOutcome&depositIndex`,
  EXISTED → skipped_existing, real-Mongo duplicate-key refusal on concurrent markInFlight (no Testcontainers).
- Open advisory (review re-check): operator answer during a live two-JVM overlap; not reachable with a single
  bot-manager container recreated stop-then-start.
