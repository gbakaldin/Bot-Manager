# Release — SLOT_STRATEGY_OVERRIDE

Mode: bot
Branch: feat/bot-group-config-validation
Image: vingame-bot:latest (built at 2026-06-23T11:42:51Z)
HEAD: fd5f03c
Date: 2026-06-23T11:55Z
Target: Bot-1 (staging)

Behavior under test: SLOT groups no longer take a client-selectable strategy.
`GET /api/v1/strategy/?gameType=SLOT` returns an empty list, and slot bots are
ALWAYS assigned the basic FIXED slot strategy. Any client-provided
`slotStrategyId` is silently overridden to FIXED (no 400).

## Build

- `mvn clean install`: PASS (~22s, 828 tests, 0 failures) — Java 21.0.2
- `docker build --no-cache --platform linux/amd64`: PASS (sha 228807ee9611)
- `docker save -o bot.tar`: PASS (392787968 bytes)

## Ship

- `sftp put bot.tar`: PASS (remote size 392787968 == local, verified)
- infra-images.tar.gz: NOT shipped (mode=bot, by design)

## Deploy

- `docker compose down`: PASS (all 6 containers + network removed)
- `docker image rm vingame-bot:latest`: PASS (prior image removed)
- `docker load -i bot.tar`: PASS
- `docker compose up -d`: PASS (mongo healthy → bot-manager started → full stack up)
- Startup retry: NOT needed (no auto-start wedge; 4 groups running in <3s)

## Smoke test

- `docker ps` shows bot-manager healthy: PASS (healthy ~33s after up)
- Spring Boot ready log: PASS — `Started Starter in 6.603 seconds`
- Tomcat on port 8085: PASS — `Tomcat started on port 8085 (http)`
- Host reaches app at localhost:8080: PASS — `/actuator/health` → 200, status UP, mongo UP
- Auto-start log: PASS — `Bot Manager startup complete. 4 bot groups running`
  (b1e80470, 40fa3749, 4d7f6ac9, ec759951)
- GameConfigValidatorFactory init: PASS — `registered 5 validators —
  [BETTING_MINI, SLOT, TAI_XIU, CARD_GAME, UP_DOWN]`
- ERROR/DEAD on startup: 0
- Observability re-verified (single-compose layout): Grafana `/api/health` 200,
  Prometheus `/-/healthy` 200, Loki `/ready` 200 (after ~13s warmup; 503 transient),
  promtail Up. ALL UP.

## Plan verification — slot strategy override

### Step 5a: SLOT strategy listing is empty
Command: `GET http://localhost:8080/api/v1/strategy/?gameType=SLOT`
Expected: HTTP 200, body `[]`
Actual: HTTP 200, body `[]`
Result: PASS

### Step 5b: BETTING_MINI / no-param listing unchanged
Command: `GET .../strategy/?gameType=BETTING_MINI` and `GET .../strategy/`
Expected: HTTP 200, full betting-strategy list
Actual: HTTP 200 for both — 9 strategies returned (RANDOM, MARTINGALE_CLASSIC_CAUTIOUS/AGGRESSIVE,
  PAROLI_CAUTIOUS/AGGRESSIVE, DALEMBERT_CAUTIOUS/AGGRESSIVE, FIBONACCI_CAUTIOUS/AGGRESSIVE)
Result: PASS
(Note: `GET .../strategy` without trailing slash returns 404 "No static resource" — the
controller is mapped on the trailing-slash path; cosmetic, pre-existing, not in scope.)

### Step 5c: Override proof — RANDOM request runs FIXED (constant bet)
Command:
  - `POST /api/v1/bot-group/` with `{"id":"204","name":"SlotOverrideProof204",
    "environmentId":"ad4e7948-... (116/TIP)","gameId":"c5a22c44-... (SlotTipTest, SLOT)",
    "namePrefix":"slotovr","password":"123123","botCount":2,"existingGroup":true,
    "slotStrategyId":"RANDOM"}` → 200 (group persisted with slotStrategyId=RANDOM, no 400)
  - `POST /api/v1/bot-group/204/start` → 200
Expected: bots run FIXED (constant per-line bet b), not the {500,1000,2000,5000,10000}
  spread that RANDOM would produce.
Actual — proven two independent ways:

  (i) Assignment-time, group 204 (the throwaway, requested RANDOM):
      `[204//] Bot slotovr1: assigned strategy RANDOM`
      `[204//] Bot slotovr1: assigned slot strategy FIXED (slot strategy is not selectable)`
      (same pair for slotovr2). The RANDOM request was overridden to FIXED before AUTH.
      The throwaway bots could NOT complete AUTH (504 "Tài khoản không tồn tại" — usernames
      were never registered because the group was created with existingGroup:true), so they
      produced no spin frames. This is a test-data limitation of the throwaway group, NOT a
      code regression — the override is logged independently of AUTH outcome.

  (ii) Runtime SENT-frame proof, group ec759951 (SlotRandomGroup5, a REAL registered slot
      group persisted with slotStrategyId=RANDOM, auto-started on this redeploy):
      `assigned strategy RANDOM` → `assigned slot strategy FIXED (slot strategy is not selectable)`
      Bots slotrnd1/slotrnd2 AUTH'd, connected, subscribed, and spun.
      Distinct spin-result bet values across ALL spins:
          170 spins, b=500 only (100%). Zero spins at 1000/2000/5000/10000.
      The cmd:1300 subscribe response explicitly offered allowedBetValues=[500,1000,2000,5000,10000];
      the FIXED bot bets the single lowest tier (500) on every spin. RANDOM would have varied
      across all five tiers — it did not. This is the constant-bet override evidence.
Result: PASS

### Step 5d: Slot flow (AUTH → subscribe slotMachinePlugin → 1300 → spin)
Command: inspect bot-manager logs for the registered slot group (ec759951)
Expected: AUTH, subscribe via slotMachinePlugin, 1300 response, normal spins
Actual: PASS — observed full flow:
  - `[SENT] ["6","MiniGame","slotMachinePlugin",{"cmd":1300,"gid":204}]`
  - `[RECEIVED] [...,"cmd":1300,...]` with allowedBetValues=[500,1000,2000,5000,10000]
  - `subscribed — numLines=25, allowedBetValues=[500, 1000, 2000, 5000, 10000]`
  - `[SENT] ["6","MiniGame","slotMachinePlugin",{"cmd":1302,"aid":1,"b":500,"gid":204,"ls":[0..24]}]`
  - repeated spin results `b=500, winnings=..., sid=..., balance=...`
Result: PASS

### Step 5e: No regression
Expected: existing betting-mini groups still ACTIVE/spinning; no app ERROR/DEAD; observability UP.
Actual:
  - ACTIVE betting-mini groups unchanged and spinning: b1e80470 (116 Demo / Bau Cua),
    40fa3749 (XD / Xoc Dia), 4d7f6ac9 (Fruit Shop). 13,631 betting round-end/bet log lines.
  - ec759951 (slot) was ACTIVE and spinning during the test.
  - Zero ERROR/DEAD lines since deploy except the expected slotovr (group 204) AUTH 504s —
    throwaway unregistered usernames, not code.
  - 5a1cc162 "Auth test 22" shows targetStatus=DEAD, but it was ALREADY DEAD in the
    pre-deploy baseline and was not auto-started — pre-existing stale status, not a regression.
  - Observability: Grafana/Prometheus/Loki/promtail all UP.
Result: PASS

## Cleanup / final staging state

- Group 204 (SlotOverrideProof204): stopped (200) + deleted (200). Confirmed absent.
- Group ec759951 (SlotRandomGroup5 — leftover slot smoke group): stopped (200).
  Note: on this redeploy its bots were rebuilt to FIXED (RANDOM overridden); stopped per instruction.
- Other STOPPED slot smoke groups (74feeaed SlotSmokeGroup, 99a266f3 SlotSmokeGroup3):
  already STOPPED, left as-is.
- Final ACTIVE groups: b1e80470 (116 Demo), 40fa3749 (XD), 4d7f6ac9 (Fruit Shop) — all betting-mini.
- Final DEAD: 5a1cc162 (pre-existing, untouched).
- All 6 containers healthy; app /actuator/health → 200.

## Verdict

PASS

## Notes

- Both `slotStrategyId=RANDOM` slot groups (the new throwaway 204 and the registered
  ec759951) logged the override line `assigned slot strategy FIXED (slot strategy is not
  selectable)`. The registered group provided the live constant-bet SENT-frame evidence
  (170 spins, all b=500) because its usernames exist upstream; the throwaway group only
  proved the assignment-time override (it failed AUTH due to unregistered usernames, which
  is a test-data artifact of existingGroup:true, not a code defect).
- No git push/amend performed. bot-image only. Bot-1 only.
