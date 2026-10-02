# Compliance — BOT_PROVISIONING

Branch: feature/bot-provisioning
Plan reviewed: `docs/plans/BOT_PROVISIONING.md` (at commit d4d01fb, amended in this commit)
Diff reviewed: `git diff 970ddcf..bbb5381`

## Verdict

SEND_BACK_TO_DEV — for one small item (D1 below). The ledger deviation and the Phase 1
fix-round decisions are **accepted** and recorded in the plan's `## Amendment — 2026-10-02`
(the orchestrator asked for them to be recorded whatever the verdict). Once D1 is fixed the
branch is PASS; it does not need another full compliance pass.

## Phase-by-phase

### Phase 1 — Grow a running group
Status: implemented (with accepted additions, see Amendment §2)
- Step 1 (AD-4): `BotGroupService.update` calls `validateUsernameLength` iff post-merge
  `botCount` rose. Tests: raise past cap 400, within cap, no-raise, lowering.
- Step 2: `BotGroupRuntime.builtUpTo` (volatile, constructor = `botCount`).
- Step 3: `RegistrationWorker.recordCompletion` publishes `RegistrationCompletedEvent` only
  after a matched completion, via `ApplicationEventPublisherAware` (no new constructor bean —
  consistent with "no new bean dependency").
- Step 4 (AD-12): `@EventListener onRegistrationCompleted` → `attachIfRunning` →
  `submitLifecycle(…, StartOrigin.ATTACH, …)`; `attachLocked` re-reads under the group lock,
  builds `builtUpTo+1..botCount` through `createBotsInParallel(…, from, to)` (start passes
  `(1, botCount)`), reserves `ESSENTIAL × 3 × added`, injects coordinator/jackpot scaler,
  `startBot`s, advances `builtUpTo`, tier-1 line `attaching <n> bots (<from>-<to>) to running
  group` + aggregator expectation. Strategy slice over `1..botCount`. No runtime / DEAD /
  non-ACTIVE target / PENDING: no-op. `/start` during attach stays the in-flight no-op.
- Step 5 tests: all listed cases present in `BotGroupBehaviorServiceAttachTest` /
  `BotGroupServiceTest`.

### Phase 2 — Registration-time deposit
Status: drifted (ledger — accepted, plan amended) + one verification mismatch (D1)
- Step 1: `BotGroup.initialDeposit/depositedCount/depositInFlight`, DTO fields (read-only for
  the two counters), `BotGroupStatusDTO.depositedCount`, mapper.
- Step 2 (AD-3): range 400, `existingGroup`+deposit 400, PATCH of `initialDeposit` 400 while
  `PENDING`/`FAILED`; `bot.provisioning.max-initial-deposit=1000000000`.
- Step 3 (AD-7): `depositForRegistration` returns `DepositOutcome`; `onAdmitted` runs inside
  the budget callable after admission and the stream permit, before the send. Row mapping
  matches the table, `StreamWaitTimeoutException` handled before generic `IOException`.
  The legacy `deposit(...)` is untouched. DEFAULT tier, registration wait (AD-10).
- Step 4 (AD-5/6/13/14): third stage, index = funded+1, completion requires funded >= target;
  marker-on-selection → FAILED without sending, one ERROR naming account/index/amount and the
  retry URL; REFUSED → refusal attempt, NOT_SENT → transport attempt, budget → defer, UNKNOWN →
  FAILED; completion line `, <d> funded x <amount> = <total>`; `registration_deposits_total`
  {credited,refused,not_sent,unknown} + `registration_deposit_amount_total` pre-registered at
  zero. `persistProgress` does **not** gain `depositedCount` — superseded by the ledger.
- Step 5 (AD-8/AD-9): seeding on a raise of a complete group only (monotonic `$max`);
  `retryRegistration(id, depositOutcome)` — required iff marker set, rejected otherwise,
  conditional resolve; controller `?depositOutcome=credited|not-credited`.
- Step 6 tests: every listed case present (`RegistrationWorkerDepositTest`,
  `BotGroupServiceTest`, `ApiGatewayClientRegistrationDepositTest`, controller test).

## Drift

### Ruling 1 — `DepositLedger` (bbb5381): ACCEPTED, plan amended
Falsifiable and verified: `BotGroup` has no `@Version`, and the group is persisted by
whole-document `repository.save` from at least seven sites (PATCH in `BotGroupService.update`,
start/stop/DEAD/scheduled-restart writes in `BotGroupBehaviorService`). A save that read the
group before a targeted credit `$set` and wrote after reverts `depositedCount` and clears the
marker, and the next pass deposits the same index again. The plan's AD-6 assumed targeted
`$set`s on the group document were sufficient; they are not while other writers replace the
document. A separate collection written only by conditional updates is the minimal correct
fix, keeps every AD-5/AD-6/AD-8/AD-9 semantic, and does not touch other planned surfaces. This is
a structural constraint the plan missed, not a preference.

### Ruling 2 — Phase 1 fix-round decisions: CONSISTENT with plan intent, recorded
- `/restart` during an attach → 409: AD-12 intended `StartAttemptRegistry` reuse; a 200 that
  does nothing violated the async "200 = accepted" contract. Consistent.
- Scheduled restart deferred behind an attach: AD-12's goal is not to lose work; a restart
  covers `1..botCount` anyway. Consistent.
- Death check cancels an in-flight attach (`cancelIf`, atomic): AD-12 says a DEAD runtime gets
  no attach; an attach must not block DEAD marking. Consistent.
- `builtUpTo` over the contiguous started prefix: AD-12 makes `builtUpTo` the record of what is
  owed; advancing past never-sent indices would strand them until a restart. Consistent.
- Follow-up attach queued on the open attempt: closes the "event refused as already in flight"
  hole that AD-12's text claimed `builtUpTo` covered but did not on its own. Consistent.

### D1 — Verification steps 2 and 9 cannot pass as written: SEND BACK
`BotGroupDTO` is `@JsonInclude(NON_NULL)` and `BotGroupMapper.toDTO` maps
`depositedCount == 0 → null` unconditionally. So step 2 (funded create, `PENDING`, expect
`depositedCount:0`) and step 9 (unfunded create after completion, expect `depositedCount:0`)
both read `null` under `jq`. The plan's expectation is reasonable (a progress counter, and the
sibling `registeredCount` does read `0` while `PENDING`); nothing makes `0` impossible.
**Fix:** emit `depositedCount` as-is in `BotGroupMapper.toDTO` (drop the zero→null), and pin it
in a mapper/controller test. `BotGroupStatusDTO.depositedCount` may keep its null-when-zero
(not covered by Verification). One line plus a test.

## Out-of-scope changes

None beyond what the rulings cover. `ConflictException` + its 409 handler,
`StartAttemptRegistry.defer/cancelIf/FollowUp`, and
`GroupLifecycleAggregator.expectAdditional` all serve Ruling 2. Test-fixture constructor updates
(`DepositLedger` parameter, `InMemoryDepositLedger`) are mechanical. Open items 1-5 were not
silently built (no accounts-only path, no `POST /{id}/bots`, no auto-resolution, no idempotency
key, cap left at 1e9).

Note: `docs/reviews/BOT_PROVISIONING/review.md` is untracked in the worktree and is not part of
this commit.

## Amendments to the plan

`docs/plans/BOT_PROVISIONING.md` — short inline notes on AD-5/AD-6 and one
`## Amendment — 2026-10-02` section: (1) ledger as the authoritative funded count + marker,
group fields as display mirrors, `persistProgress` unchanged; (2) the five attach decisions
above plus the reservation-release / additive-aggregator details; (3) AD-7 details — Cloudflare
edge block = REFUSED, marker-write failure = not sent, attempt charged.
