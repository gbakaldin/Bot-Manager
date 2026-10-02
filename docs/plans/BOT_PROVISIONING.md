# BOT_PROVISIONING

Branch base: `feature/gateway-request-budget` (`RegistrationWorker` and the request budget do
not exist on `main`). Staging only — see AD-11.

## 1. Goal

Two operator actions, built on what GATEWAY_REQUEST_BUDGET Phase 4 already shipped:

1. **Create a group, register its bots and optionally fund them** in one call:
   `{botCount, namePrefix, password, initialDeposit}` against an environment, where
   `initialDeposit` defaults to `0` (no money moves).
2. **Grow an existing group**: raise `botCount` from 10 to 20 and `testbot11..20` are registered
   (and funded, if the group has an `initialDeposit`) and **join the running group without
   stopping the ten that are already playing**.

Both reuse the serial, resumable `RegistrationWorker` and the existing PATCH-raise path. There is
no second pipeline.

## 2. Findings — current state

All paths under `/Users/gleb/IdeaProjects/Bot/`.

- **Create is already async and game-bound.** `bot-app/.../botgroup/service/BotGroupService.java:156-178`
  persists `REGISTRATION_PENDING` at `0/botCount` and enqueues the worker (`:199-201`).
  `gameId` is `@NotBlank(groups = OnCreate)` at `bot-app/.../botgroup/dto/BotGroupDTO.java:45`.
- **The worker is a per-index state machine with two stages.**
  `bot-app/.../botgroup/service/RegistrationWorker.java:446-559`: index =
  `(names ? named : registered) + 1` (`:482`); stage 1 `registerOne` → `persistProgress`
  (`:486-500`); stage 2 `setDisplayNameWithRetry` → `persistProgress` (`:502-527`).
  Completion is conditional on the document's own `botCount` (`:592-623`). Budget refusal defers
  (`:532-547`), gateway refusal / transport failure charge separate attempt budgets (`:641-698`).
  Registration retries are safe because a re-register answers `EXISTED`. **A deposit has no such
  property** — this is the whole design problem of item 1.
- **Raise = re-arm.** `BotGroupService.applyRegistrationTargetChange` (`:404-432`) flips a raised
  group to `PENDING`; untracked groups (`registrationState == null && registeredCount == 0`) are
  seeded to `botCountBefore` (`:407-416`).
- **Username pre-flight runs on create only.** `validateUsernameLength` (`:226-246`) is called
  from `save`'s new-group branch (`:157`); `update` (`:328-347`) never calls it. Raising a TIP
  group from 99 to 100 with a 10-char prefix passes PATCH and fails at the gateway on index 100.
- **A running group never learns about new accounts.** The runtime is sized once in
  `BotGroupBehaviorService.startLocked`: identifiers `1..botCount` (`:994-997`),
  `new BotGroupRuntime(id, botCount, …)` (`:1007`), `createBotsInParallel` loops
  `1..botCount` (`:1317-1325`). The worker has no reference to the runtime. Today, new bots play
  only after `/restart`, and `/restart` is refused while `PENDING` (`validateStartable`, `:701-707`).
- **Adding a bot to a live runtime is already mechanically possible.** `BotGroupRuntime.startBot`
  (`bot-app/.../infrastructure/runtime/BotGroupRuntime.java:216-262`) submits to the group's
  executor and appends to `CopyOnWriteArrayList`s (`:168-169`). Health monitoring and periodic
  logout iterate those lists, so they pick new bots up for free.
- **Strategy assignment is deterministic over the identifier list**
  (`bot-api/.../strategy/StrategyAssignment.java:152`), so assigning over `1..newTotal` and taking
  the new slice is reproducible.
- **Deposit today.** `ApiGatewayClient.deposit` (`bot-engine/.../client/ApiGatewayClient.java:1052-1094`)
  posts `{username, amount}` to `/gwms/v1/bot/deposit.aspx` — **no idempotency key** — and folds
  every outcome into a `boolean`: non-200 → `false`, any `IOException` → `false` (`:1082-1091`).
  That cannot tell "refused" from "sent, answer lost". `send(tier, scope, req, maxWait)`
  (`:299-312`) runs the HTTP call as a callable inside `gatewayBudget.execute`, i.e. **after**
  admission. `StreamWaitTimeoutException extends HttpTimeoutException` (`:456`) and means
  "never sent".
- **Metrics pattern to copy.** `BotMetrics.initRegistrationSeries` / `incRegistrationAccount`
  (`bot-engine/.../observability/BotMetrics.java:630-657`) — pre-registered at zero.
  `RegistrationStalled` (`prometheus/alerts.yml:496`) fires on `registration_failed_groups > 0`.

## 3. Readiness

| Piece | Status | Notes |
|---|---|---|
| Create + register, game-bound | ready | `POST /` today |
| Resume / retry / never skip an index | ready | worker invariant |
| Add N by raising `botCount` | ready | PATCH, `applyRegistrationTargetChange` |
| Username cap on raise | partial | create-only — Phase 1 |
| New bots join a running group | blocked | no hook — Phase 1 |
| Registration-time deposit | blocked | no stage, no marker, client can't classify outcome — Phase 2 |
| Accounts-only (no game) | out of scope | Open item 1 |

## 4. Architecture decisions

- **AD-1 — Item 1 is one new field on the existing `POST /`**, `initialDeposit` (`Long`,
  JSON-optional, default `0`). Persisted on `BotGroup` as `long initialDeposit` (absent in old
  documents → `0` → no deposit; no migration). Named `initialDeposit`, not `depositAmount`, so it
  cannot be confused with the global auto top-up `bot.deposit.amount`. No new endpoint.
- **AD-2 — Item 2 is `PATCH /{id} {botCount: <newTotal>}`.** No `POST /{id}/bots` sugar
  (Open item 2). The raise semantics are unchanged; Phases 1-2 only add what happens after it.
- **AD-3 — Validation.** `0 <= initialDeposit <= bot.provisioning.max-initial-deposit`
  (new property, default `1000000000`, equal to today's `bot.deposit.amount`); out of range is a
  400. `existingGroup=true` with `initialDeposit > 0` is a 400 (nothing is registered, so nothing
  is funded). `initialDeposit` may be PATCHed **only while registration is complete**
  (`registrationState == null`); during `PENDING`/`FAILED` it is a 400, so one job never mixes
  two amounts.
- **AD-4 — Username pre-flight on raise.** `update` calls `validateUsernameLength` against the
  post-merge `botCount` whenever it rose. Same 400.
- **AD-5 — Deposit is the third per-index stage, after register and name.** Order per index:
  register → name (if pool) → deposit (if `initialDeposit > 0`). Progress marker
  `int depositedCount` with the same meaning as the others: "indices `1..k` are done". Invariant
  `depositedCount <= (names ? namedCount : registeredCount)`. Index selection becomes
  `(deposit ? depositedCount : names ? namedCount : registeredCount) + 1`; completion adds
  `!deposit || depositedCount >= target`. Never skips an index.
  *(Amended 2026-10-02: the authoritative `depositedCount`/`depositInFlight` live in
  `DepositLedger`, not on `BotGroup` — see the Amendment section.)*
- **AD-6 — Write-ahead marker for the money call.** A nullable `Integer depositInFlight` on the
  document (amended: on the `DepositLedger` entry; the group field is a display mirror):
  1. The worker calls a new `ApiGatewayClient.depositForRegistration(username, amount, scope,
     maxWait, Runnable onAdmitted)`. `onAdmitted` runs **inside the budget's callable, after
     admission, immediately before the HTTP send**, and persists `depositInFlight = index`
     (targeted `$set`, like `persistProgress`).
  2. On a credited answer, one `$set`: `depositedCount = index, depositInFlight = null`.
  3. On a definite non-credit, one `$set`: `depositInFlight = null`, then the usual
     attempt/deferral handling.
  4. On an unknown outcome, `depositInFlight` **stays set** and the group goes `FAILED`.

  **If the process dies between the send and the step-2 write, the marker is still set and
  `depositedCount` is `index - 1`.** The worker, on selecting any group whose
  `depositInFlight != null`, does **not** send: it sets `FAILED` with
  `registrationError = "deposit outcome unknown for <username> (index k, amount A) — check its
  balance, then POST /{id}/registration/retry?depositOutcome=credited|not-credited"` and logs
  one ERROR. The money is therefore at worst *missing* for one account, never *doubled*. A crash
  while parked in the budget or before admission leaves no marker (step 1 never ran) and resumes
  normally. Crash after the marker but before the send is also reported as unknown — a false
  positive bounded by the time to open a stream, accepted.
- **AD-7 — Deposit outcome classification** (new `DepositOutcome` enum returned by
  `depositForRegistration`; the existing `deposit(...)` used by `Bot` is untouched):

  | Observed | Outcome | Worker action |
  |---|---|---|
  | `GatewayBudgetException` (before admission) | NOT_SENT | defer, no attempt (as today) |
  | `StreamWaitTimeoutException`, `ConnectException` | NOT_SENT | clear marker, transport attempt |
  | HTTP 200 | CREDITED | advance `depositedCount` |
  | HTTP 4xx | REFUSED | clear marker, refusal attempt → `FAILED` after `max-attempts-per-user` |
  | HTTP 5xx, any other `IOException` (incl. `HttpTimeoutException`), `InterruptedException` after admission | UNKNOWN | keep marker, `FAILED` immediately, no retry |

  `StreamWaitTimeoutException` must be tested **before** `HttpTimeoutException` (it is a
  subclass). 5xx is UNKNOWN, not REFUSED, because nothing in the contract says a 5xx did not
  credit (Open item 4).
- **AD-8 — Retry resolution.** `POST /{id}/registration/retry` gains an optional
  `depositOutcome=credited|not-credited`. Required (400 otherwise) iff `depositInFlight != null`;
  rejected (400) otherwise. `credited` → `depositedCount = depositInFlight`; `not-credited` →
  leave `depositedCount`; both clear the marker, then the normal retry. Logged at INFO with the
  operator's choice.
- **AD-9 — No retro-funding.** When a raise lands on a group whose registration is complete,
  `applyRegistrationTargetChange` first seeds `depositedCount = max(depositedCount,
  botCountBefore)`. This covers pre-feature groups (field absent → 0), groups created with
  `initialDeposit = 0`, and untracked groups. Accounts that existed before the PATCH are never
  funded by it. Seeding does **not** happen on a raise during `PENDING` (those indices genuinely
  still owe a deposit).

  **Existing accounts are never funded (Phase 2 fix round, orchestrator decision).** If an index
  this job registers (one not already covered by `registeredCount`) answers `EXISTED`, the account
  was not created by this job — another group's under an overlapping or reused prefix, or a
  previous incarnation of a deleted-and-recreated group. The worker records the index as done
  without funding it (`DepositLedger.skip`, before `registeredCount` is written), counts it as
  `registration_deposits_total{outcome="skipped_existing"}`, and logs one group-level WARN with
  the count at completion. This errs to under-funding: an own account whose register succeeded but
  whose `registeredCount` write was lost before a crash re-registers as `EXISTED` and stays
  unfunded. Accepted cost; it is visible in the WARN and the metric, and is fixed by hand.
- **AD-10 — Deposit tier is `DEFAULT` with the registration wait**
  (`bot.gateway.budget.registration.max-wait`), exactly like `registerOne`. Never PRIORITIZED.
  Cost: up to 3 DEFAULT requests per account; a 100-account funded create is ~300 requests
  against DEFAULT's 500 ceiling, paced by the budget, not by the worker.
- **AD-11 — Staging only.** Prod-Bot runs a pre-budget build under code freeze. This feature
  depends on `RegistrationWorker`, so it cannot reach prod without the budget; it must not be
  promoted to prod before GATEWAY_REQUEST_BUDGET is live there.
- **AD-12 — New bots join a running group by attachment, not restart.** On registration
  completion the worker publishes a Spring `RegistrationCompletedEvent(groupId)` (no new bean
  dependency from the worker). `BotGroupBehaviorService` listens and, if the group has an
  `ACTIVE` runtime whose `builtUpTo < botCount`, runs `attachBots` through the existing
  `submitLifecycle` path with a new `StartOrigin.ATTACH` and a lifecycle body that, **under the
  group lock**: re-reads the group, builds indices `builtUpTo+1 .. botCount` via the same
  `createSingleBot` (refactor `createBotsInParallel` to take an index range), reserves
  `ESSENTIAL × 3 × added` exactly as `startLocked` does (`:1079-1080`), injects the coordinator
  and jackpot scaler, `runtime.startBot`s each, and advances `runtime.builtUpTo`.
  - `BotGroupRuntime` gains `int builtUpTo`, set to `botCount` by the constructor. It is what
    makes attach idempotent (a duplicate event is a no-op) and what covers a raise that landed
    while a start was building with the old count.
  - Strategy: assign over `1..botCount`, take the slice for the new indices. Existing bots keep
    their assignment; the group mix may drift by the rounding of one slice. Accepted.
  - No runtime, a `DEAD` runtime, or a `STOPPED`/out-of-window group: do nothing — the next
    start builds `1..botCount`.
  - Reusing `StartAttemptRegistry` means `/stop` cancels an attach in flight and a second
    `/start` during it is the existing "already in flight" no-op. `GET /status` reports
    `STARTING` while the attach runs; accepted.
  - Tier-1 log: one `group <id> (<name>): attaching <n> bots (<from>-<to>) to running group`
    plus `GroupLifecycleAggregator.expectInitialized(id, name, n)`.
- **AD-13 — Logging.** Per-account deposit lines DEBUG. One INFO per group per job: the existing
  completion line extended with `, <d> funded x <amount> = <total>` when `initialDeposit > 0`.
  Unknown outcome = one ERROR (hand-off). Refusal retry = existing WARN shape.
- **AD-14 — Metrics.** `registration_deposits_total{outcome=credited|refused|not_sent|unknown}`
  and `registration_deposit_amount_total`, both pre-registered at zero in the same call as
  `initRegistrationSeries`. No new alert: an unknown outcome makes the group `FAILED`, which
  `RegistrationStalled` already pages on.

## 5. Plan

### Phase 1 — Grow a running group (item 2 complete)

1. `BotGroupService.update`: if post-merge `botCount > botCountBefore`, call
   `validateUsernameLength` (AD-4).
2. `BotGroupRuntime`: add `builtUpTo` (constructor sets it; getter/setter, volatile).
3. `RegistrationWorker.recordCompletion`: after a matched completion, publish
   `RegistrationCompletedEvent(groupId)` via an injected `ApplicationEventPublisher`.
4. `BotGroupBehaviorService`: `@EventListener` → `attachIfRunning(id)`; `StartOrigin.ATTACH`;
   `attachLocked(id)` per AD-12. Refactor `createBotsInParallel(group, env, game, assignment,
   circuitRefusal)` to take `(fromIndex, toIndex)`; `startLocked` passes `(1, botCount)`.
5. Tests (small, isolated): username pre-flight on raise (400) and on no-raise PATCH (no check);
   attach builds exactly the delta and sets `builtUpTo`; duplicate event is a no-op; no attach for
   absent/DEAD runtime; strategy slice equals the full-assignment slice.

### Phase 2 — Registration-time deposit (item 1 complete)

1. Model/DTO/mapper: `BotGroup.initialDeposit` (long), `depositedCount` (int),
   `depositInFlight` (Integer); `BotGroupDTO.initialDeposit` (rw), `depositedCount` and
   `depositInFlight` (read-only, ignored on input); `BotGroupStatusDTO.depositedCount`.
2. Validation per AD-3 (`BotGroupConfigValidationService` or `BotGroupService.save/update`),
   property `bot.provisioning.max-initial-deposit=1000000000` in `application.properties`.
3. `ApiGatewayClient.depositForRegistration(...)` → `DepositOutcome` per AD-7, with the
   `onAdmitted` hook run inside the `gatewayBudget.execute` callable before `httpCall`.
4. `RegistrationWorker`: third stage per AD-5/AD-6; in-flight-on-selection → FAILED per AD-6;
   counters/metrics per AD-14; completion line per AD-13. `persistProgress` gains
   `depositedCount`.
5. `BotGroupService.applyRegistrationTargetChange`: AD-9 seeding.
   `retryRegistration(id, depositOutcome)` + controller query param per AD-8.
6. Tests: each AD-7 row maps to the right worker action; marker persisted only after admission
   (budget refusal leaves none); crash simulation — worker selecting a group with
   `depositInFlight` set never calls the client and goes FAILED; retry `credited` advances,
   `not-credited` re-sends exactly once; AD-9 seeding on a complete group, none on a PENDING one;
   `initialDeposit = 0` makes zero deposit calls; validation 400s; `existingGroup` + deposit 400.

## 6. Implementation notes

- The deposit stage must not reuse `isTransportFailure`'s "IOException ⇒ retry" rule — for money
  that rule is wrong. Classify inside `depositForRegistration` and branch on `DepositOutcome`.
- `onAdmitted` runs inside the budget callable; keep it a single indexed Mongo `$set` and never
  take a lock in it (the budget may hold its own lock around admission bookkeeping).
- The attach listener must not run on the registration worker thread (the worker's single thread
  is the serialisation of every group's registration). `submitLifecycle` already spawns a virtual
  thread.
- `attachLocked` must re-read the group under the lock and recompute the range: a `/stop`, a
  lowered `botCount` or a restart may have landed since the event.
- A running group that is `PENDING` (raised, registering) and then dies cannot be recovered or
  restarted until registration completes (`validateStartable`). Pre-existing; unchanged.
- `initialDeposit` and `autoDepositEnabled` are independent: an auto-deposit group will still top
  up a bot whose balance falls below the minimum.
- Do not add a parallel worker or a per-stage thread; the high-water marks mean nothing without
  the serial loop.

## 7. Open items

1. **Accounts-only provisioning (no `gameId`)** — out of scope. Does the user want it (today:
   `scripts/bulk-create-accounts.py`)?
2. **`POST /{id}/bots {count}` sugar** — not built (AD-2). It is ~15 lines over the raise; say
   if the UI wants "add N" rather than "new total".
3. **Unknown-outcome auto-resolution** — could log in and read the balance (2 more calls) instead
   of a human check. Deferred.
4. **Does `deposit.aspx` accept an idempotency key, and can a 5xx have credited?** If gwms offers
   a key, UNKNOWN becomes safely re-sendable and AD-6/AD-8 shrink. Ask the gwms team.
5. **Cap value** — 1e9 is in brand currency units and products differ; confirm or set per box.

## Verification

Run on the staging host (Bot-1) after deploy. `B=http://localhost:8080`;
`ENV` = a staging environment with deposit working (097/BOM), `GAME` = a playable game in it.
Use a fresh prefix each run: `P=prov$(date +%H%M)` (check the brand's username cap).

1. Smoke: `curl -s -o /dev/null -w '%{http_code}' $B/actuator/health` → expect `200`.
2. Funded create:
   `curl -s -XPOST $B/api/v1/bot-group/ -H 'Content-Type: application/json' -d '{"name":"'$P'","environmentId":"'$ENV'","gameId":"'$GAME'","namePrefix":"'$P'","password":"Abc12345","botCount":3,"initialDeposit":1000000}' | jq '{id,targetStatus,initialDeposit,depositedCount}'`
   → expect `targetStatus:"REGISTRATION_PENDING"`, `initialDeposit:1000000`, `depositedCount:0`.
   Save `ID`.
3. Within 5 min: `curl -s $B/api/v1/bot-group/$ID | jq '{registeredCount,depositedCount,depositInFlight,targetStatus}'`
   → expect `registeredCount:3, depositedCount:3, depositInFlight:null`, targetStatus not
   `REGISTRATION_*`.
4. `grep "group $ID" logs/console.log | grep 'registration complete'` → expect one line ending
   `3 funded x 1000000 = 3000000`.
5. `curl -s $B/actuator/prometheus | grep '^registration_deposits_total' | grep credited`
   → expect value `>= 3`; `grep 'outcome="unknown"'` → expect `0.0`.
6. Validation: same body as step 2 with `"initialDeposit":-1` → expect HTTP `400`; with
   `"initialDeposit":1000000001` → expect `400`.
7. Start and check money landed: `curl -s -XPOST $B/api/v1/bot-group/$ID/start`; after ~60 s
   `curl -s $B/api/v1/bot-group/$ID/health | jq '[.bots[].lastFetchedBalance]'` → expect three
   values `>= 1000000` (or the brand's settled drift from it).
8. Grow while running: `curl -s -XPATCH $B/api/v1/bot-group/$ID -H 'Content-Type: application/json' -d '{"botCount":5}' | jq .botCount`
   → expect `5`. Within 5 min: `GET /$ID` → expect `registeredCount:5, depositedCount:5`;
   `grep "group $ID" logs/console.log | grep 'attaching 2 bots (4-5)'` → expect one line;
   `GET /$ID/health | jq '.bots | length'` → expect `5`, and the three original bots' uptime
   did not reset (no `restart requested` for them: `grep "\[$ID/" logs/detail/detail.log | grep -c 'restart requested'` → expect `0`).
9. Unfunded create: step 2 without `initialDeposit` → after completion expect `depositedCount:0`,
   and `registration_deposits_total` unchanged from step 5.
10. Username cap on raise (TIP env, 12 chars): create with an 11-char prefix and `botCount:9`,
    then PATCH `{"botCount":10}` → expect HTTP `400` naming the cap.
11. Cleanup: `curl -s -XDELETE $B/api/v1/bot-group/$ID` → expect `200`/`204`.

## Amendment — 2026-10-02

Recorded by the compliance check (`docs/reviews/BOT_PROVISIONING/compliance.md`). Supersedes the
text above where they disagree.

1. **Funded count + marker live in `DepositLedger` (AD-5/AD-6, Phase 2 steps 1 and 4).** The
   plan put `depositedCount`/`depositInFlight` on the `BotGroup` document and relied on targeted
   `$set`s. That is unsafe: `BotGroup` has no `@Version`, and every PATCH / start / stop / DEAD
   write is a `repository.save` — a full-document `replaceOne` of what that caller read. A save
   that read the group before the worker credited index `k` and wrote after reverts the count to
   `k-1` and clears the marker, and the next pass deposits index `k` twice. (`persistProgress`
   tolerates the same race for `registeredCount` only because re-register answers `EXISTED`.)
   So the authoritative state is a separate collection `botGroupDepositLedger`, keyed by group
   id, written only by conditional targeted updates: `markInFlight` (upsert conditioned on
   `depositedCount == index-1 && depositInFlight == null`; a mismatch throws and nothing is
   sent), `credit` / `clearInFlight` / `resolve` (conditioned on the marker naming the index),
   `seedAtLeast` (`$max`, for AD-9), `delete` (on group delete). The worker and the retry
   endpoint decide only from the ledger. `BotGroup.depositedCount`/`depositInFlight` remain as
   best-effort display mirrors (a stale save may lag them; they never drive a deposit).
   `persistProgress` does not gain `depositedCount`.
2. **Attach (AD-12) — behaviour beyond the original text, accepted:**
   - `POST /{id}/restart` while an `ATTACH` attempt is open is **409** (`ConflictException`),
     not a silent 200. `/start` during an attach stays the "already in flight" no-op.
   - A scheduled restart that lands during an attach is **deferred** and runs after it (a
     restart behind a start/restart is still the pre-existing no-op).
   - An attach refused because another attempt is in flight is **queued on that attempt**
     (`StartAttemptRegistry.defer`, atomic with `finish`) and run by its closer; a successful
     start, and each attach pass, also catch up (`attachRemainderLocked`, capped at 5 passes,
     remainder handed to a fresh attempt). Follow-ups of a **cancelled** attempt are dropped, so
     a `/stop` never resurrects a group.
   - `handleBotGroupDeath` **cancels an in-flight attach** (`cancelIf(id, ATTACH)`, atomic
     compare-and-cancel) so a long attach cannot block DEAD marking; it never cancels a
     start/restart.
   - `builtUpTo` advances only over the **contiguous started prefix** below the first index the
     gateway budget/circuit refused (never sent); bots built past the gap are cleaned up and the
     remainder stays pending for the next trigger. Other failures (auth, upstream) count as
     attempted, as in a start.
   - The catch-up after a start releases the start's ESSENTIAL reservation before reserving its
     own, and uses `GroupLifecycleAggregator.expectAdditional` (adds to an open expectation).
3. **AD-7 detail:** a Cloudflare edge block on the deposit response (403/429 page) is `REFUSED`;
   a failure of the `onAdmitted` marker write is not an outcome — nothing is sent, the worker
   charges an attempt and the next selection decides from the ledger.
