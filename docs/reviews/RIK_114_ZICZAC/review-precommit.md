# Pre-commit review: RIK_114 (stock + txmd5 + ziczac) onto `feature/gateway-request-budget`

Branch: `feature/gateway-request-budget` @ `59997d1`
Reviewed: the uncommitted RIK/114 working-tree delta (9 modified + 64 untracked code/test/fixture
paths, plus RIK plan and review docs), as it would land on this branch's tip. Date: 2026-10-01.

The question is whether this work can be committed on top of gateway Phases 1-5, so that one image
(budget + RIK) goes to Bot-1. Bot-1 currently runs RIK ziczac built from this dirty tree on
2026-09-18, so a clean build without RIK would remove it.

## Verdict

**APPROVE**, with one decision for the user before staging (D1, about the `13022` commit frame) and
one finding outside the diff (G1) that should be tracked against the gateway branch.

The RIK diff has no `bug` and no `security` finding. It makes no gateway call and adds no `connect()`.
Its per-bot logging stays within the tiers. It builds and passes on the gateway tip. No Aviator hunk
is mixed into any in-scope file.

## Build and tests (scratch worktree at `59997d1` + in-scope files only)

`git worktree add --detach … 59997d1`. The 73 in-scope `bot-*` paths were copied in and nothing
else. Ran `JAVA_HOME=…/openjdk-21.0.2 mvn -o test`. Result: **BUILD SUCCESS, 0 failures, 0 errors,
0 skipped.** The worktree was removed afterwards and the main tree is untouched.

| Module | Baseline (tip, no RIK) | With RIK | Delta |
|---|---|---|---|
| bot-api | 148 | 148 | 0 |
| bot-strategies | 126 | 126 | 0 |
| bot-messages | 167 | **308** | +141 |
| bot-engine | 634 | **671** | +37 |
| bot-app | 1,437 | **1,446** | +9 |
| **Total** | **2,512** | **2,699** | **+187** |

Guards that ran green with RIK in place:
- `GatewayCallSiteGuardTest` (9)
- `PerBotInfoLogGuardTest` (4)
- `BettingMiniLookupCallSiteGuardTest` (2)
- `BotFactoryForGameResolutionTest` (7)
- `MessageTypesRegistryTest` (12; exact product set now includes `114`)
- `MessageTypesCoverageTest` (4)
- `RikFixtureProvenanceTest` (5)
- `ApplicationContextLoadsTest` nested contexts

The tests do not depend on `scripts/`, `docs/` or `~/Downloads`. Every fixture they read is under
`bot-messages/src/test/resources`.

## Q1: the `EXISTED` overlap

- **No `EXISTED` handling remains in the uncommitted tree.** `git diff` shows no code hunk that
  touches it. The only untracked-file hit is the English word "existed" in a javadoc
  (`GameMessageTypesForGameContractTest.java:25`). The display-name fix (`isDisplayNameTaken`) and
  its test are already committed (`888d890`, `d947c09`), and Phase 4 / fix round `5e46af5` made it
  case-insensitive. Committing RIK neither adds nor changes any `EXISTED` handling.
- **The two meanings cannot be confused in code.**
  - `registerOne` (`ApiGatewayClient.java:590-668`) parses `register.aspx` into
    `UserRegistrationResponse` and maps `EXISTED` to `ALREADY_EXISTED`.
  - `setDisplayName` (`:695-740`) parses `update-fullname.aspx` with its own `readTree`, and
    `isDisplayNameTaken` (`:763`) maps `INVALID`/`EXISTED` to `false` (re-roll).
  - The two methods share only the `STATUS_EXISTED` string constant. Neither calls the other's
    parser, and neither reads the other's result.
  - A register `EXISTED` is recognised whatever the HTTP status, because the body is parsed
    regardless. A 409 and a 200 envelope therefore both resume.
  - The register body has no `fullname`, so a RIK "name taken" answer cannot come back from
    `register.aspx`.
- **One semantic residual (advisory, gateway branch, not RIK).** A register `EXISTED` proves that
  *a* user with that name exists, not that the bot owns it.
  - If a `namePrefix` collides with a real player or another group's accounts, Phase 4 counts the
    index as registered.
  - The mismatch then shows up only at `/start`, as a per-bot `auth` failure (wrong password).
  - Before Phase 4, the same case failed group creation loudly.
  - Worth one sentence in the Phase 4 docs. It is not a RIK blocker.
- **Running out of name retries is not handled well. See G1.** In short:
  - When a name is not set, the worker logs one WARN per account and then still advances
    `namedCount`.
  - So `namedCount` never shows a nameless account.
  - `/registration/retry` cannot reach that account.
  - The group completes and can be started, and on RIK ziczac a nameless account stalls the room.

## Q2: integration with gateway Phases 1-5

- **No new gateway call sites.** RIK adds no HTTP request, no `ApiGatewayClient` call, no `connect()`
  and no `RequestTier`. The only new outbound traffic is one WebSocket frame (`13022`, stock only)
  on a connection that is already open. That is not gateway work and the budget does not count it.
  `GatewayCallSiteGuardTest` is green.
- **`BotFactory` edit** (`BotFactory.java:172`): `.forGame(game)` is a pure resolution step inside
  the existing BETTING_MINI arm. Nothing else changes: not the budget wiring, not the async start
  path, not `connectUnderBudget`, and not circuit refusal.
- **`BettingMiniGameBot` edits:**
  - The commit is parked in the bet supplier (`:852`) and popped in the same `SendAsync` runnable's
    `onSent` (`:890`, wired at `:1078`).
  - `SendAsync` 3.0.5 calls `onSent` synchronously after `client.send(...)`, on its single scheduler
    thread. So bet-then-commit ordering is structural (checked against the
    `websocket-parser-core-3.0.5` sources).
  - The frame goes through the client the scenario captured, never the mutable `Bot.client` field,
    so a reconnect swap (`tryReconnectWs` / `restart` under Phase 5) cannot split a bet and its
    commit across two sockets.
  - A failure to serialize is caught, so the fixed-rate bet task cannot be cancelled by it.
  - A stale parked commit can never be sent. It is only ever popped right after a supplier call
    that overwrote it.
  - `beforeReconnect` clears it, which is harmless.
- **Log tiers.** One WARN per bot, latched (`commitFailureWarned`), and only on a commit
  serialization failure that should never happen. Everything else is DEBUG. A WARN for a per-bot
  anomaly is allowed by CLAUDE.md. Nothing is logged at INFO, and `PerBotInfoLogGuardTest` is green.

## Q3: ready to commit?

- **No correctness bugs found** in the RIK message classes:
  - Every `@JsonCreator` field is a primitive or a null-guarded list.
  - Every `crowdBets` / `winningsFor` / `betAmountFor` / `betCountFor` path handles a `null` or
    empty `mbs`/`bs`/`obs`.
  - `RikZicZacGameMessageTypes.requestFor` dereferences `game` without a check, but `forGame` can
    only return that provider for a non-null game.
- **No debug or dead code.** `COMMIT_CODE` is used. The `offset + 3022` print-filter entry matches
  nothing on other products, or matches only at TRACE.
- **Fixtures** (`captures/*.jsonl`, `messages/rik/*.json`):
  - No AUTH frame (`cmd 1`; 0 hits), no access/agency/JWT token, no password, no IP.
  - `_meta.href` is the public staging site URL.
  - The capturing staging account's display name `lalalala` appears (`dn`, and `uN` ×3 in the
    stock capture).
  - Other staging players' chat handles appear in `cH[].fu`.
  - Low sensitivity, and it is staging. Advisory: scrub `cH` if these fixtures ever go anywhere
    public.
- **Docs.** `display-name-check.md` truncates every `session_id`/`token`/`token2`/`X-TOKEN`. Full
  `fg` device fingerprints and the staging bot IP `16.162.36.69` are present. The IP is already in
  `application.properties`, and the fingerprints are staging-only. Acceptable.
- **Plan open items** (ziczac OI-1, 3-6; stock OI-11, 13, 14) are about observability or scope.
  Multi-ball, jackpot discharge, `mbs[].p` and `obs` semantics are all inert or explicitly out of
  scope. None is a reason to hold a staging deploy. **D1 is the exception.**

## Findings

### [decision] D1: the withdrawn Phase 3 `13022` commit frame ships with this commit
`bot-messages/.../request/RikStockRequest.java` (`commit()`), `BettingMiniGameBot.java:852,890,1078`

`RIK_114_BETTING_MINI.md` Amendment A6 and `SESSION_STATE.md:27` say Phase 3 (`13022`) is
"WITHDRAWN … NOT deployed, **do not deploy**". That is no longer accurate:
- The 2026-09-18 ziczac image (`fe476c05…`, built from this same tree) already contains it.
- `RIK_114_ZICZAC/compliance.md:406` and `release.md` V-Z4 show that stock sends
  `RikStockBet` + `{"cmd":13022,"sId":…}` and **keeps settling**.

Committing preserves what Bot-1 already runs. Reverting it now would be an untested change of its
own.

**Recommendation:** keep `13022` as it is (status quo), but the user should say so explicitly, and
A6 / `SESSION_STATE.md` should get a one-line correction ("Phase 3 has been live on Bot-1 since
2026-09-18 11:06Z via the ziczac image; stock settles with it"). To drop it instead, the narrow
change is to remove the `commit()` override in `RikStockRequest`. Then:
- `RikStockRequestTest` and `BettingMiniGameBot{Commit,RikRequest}DispatchTest` must be updated.
- The engine seam can stay inert.

### [bug, outside the diff: gateway Phase 4, `13b0084`] G1: a name that runs out of retries is counted as named
`bot-app/.../botgroup/service/RegistrationWorker.java:505` (`named = index;` runs whether or not
`displayName` is null)

**What the docs claim:**
- `BotGroupStatusDTO.namedCount` and `BotGroup.namedCount` say `registeredCount=500,
  namedCount=499` means one nameless account.
- `BotGroupService.java:397` says "/registration/retry is the way to finish that".

**What the code does:**
- When retries run out, the worker logs one WARN (`:513`) and advances `namedCount` anyway.
- So `namedCount` cannot show a nameless account at all.
- `/registration/retry` resumes from `namedCount + 1`, so it never revisits that account.
- The group then completes and can be started.

**Why it matters on RIK:**
- The per-attempt collision rate is ~43%, so P(5 collisions) ≈ 1.5% per account.
- That gives a 100-bot RIK group a ~77% chance of at least one nameless bot, and on ziczac one
  nameless bot stalls the room for every player in it (the 2026-09-18 freeze).
- The only signal is a per-account WARN in Loki.
- In effect this is not a regression: the pre-Phase-4 path also logged and moved on. The bug is
  that the new counter and the javadoc promise a signal and a repair path that do not exist.

**Fix shape (gateway branch, not this commit):** on `displayName == null`, do not advance `named`.
Charge the index through `recordFailure` with a "display-name pool exhausted" reason. The next tick
then retries with fresh names, and when attempts run out the group goes `REGISTRATION_FAILED`, which
the operator can see. The other option is to keep advancing but correct both javadocs and the
retry claim. This does not block the RIK commit, but it should be tracked before RIK groups are
created through the async worker.

### [smell] S1: `RikGameMessageTypes` javadoc points at an uncommitted script
`bot-messages/.../g3/rik/RikGameMessageTypes.java:68`

The OI-2 procedure says "re-run `scripts/capture/infer_schema.py`", but `scripts/` is out of scope
for this commit, so the reference dangles in git. Either commit `scripts/capture/` separately (it is
RIK capture tooling: `ws-capture.js`, `infer_schema.py`, `README.md`), or accept the dangling
reference.

### [style] S2: `@Setter` on a class whose fields are all `final`
`bot-messages/.../request/RikStockBet.java:101`

Lombok generates nothing for final fields, so the annotation is noise copied from `Bet`. Harmless.

## Files to stage (explicit paths, RIK only)

Do **not** use `git add -A` / `git add .`. Several paths must not be committed:
- `Aviator.js`, `bc.js`, `TaiXiuMessages/*` deletions
- `deploy.sh`
- `docs/plans/AVIATOR_BOT.md`
- DEAD_GROUP / VIPTALK / GATEWAY doc edits
- `docs/ENVIRONMENT_BRINGUP.md`, `docs/FLEET_STABILITY.md`
- `docs/reviews/WIN79_119_PROD_ACCOUNTS/`, `PLUGIN_HOT_RELOAD/review-2d.md`
- `scripts/`

None of the files below contains an Aviator hunk. The 9 modified files were diffed in full, and every
hunk is RIK (`forGame`, `GameRequestFactory`, `commit`, the 114 inventory entries).

```bash
git add -- \
  bot-api/src/main/java/com/vingame/bot/domain/bot/message/GameMessageTypes.java \
  bot-app/src/main/java/com/vingame/bot/domain/bot/service/BotFactory.java \
  bot-app/src/test/java/com/vingame/bot/ApplicationContextLoadsTest.java \
  bot-app/src/test/java/com/vingame/bot/domain/bot/service/BettingMiniLookupCallSiteGuardTest.java \
  bot-app/src/test/java/com/vingame/bot/domain/bot/service/BotFactoryForGameResolutionTest.java \
  bot-engine/src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java \
  bot-engine/src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotCommitDispatchTest.java \
  bot-engine/src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotCommitLoopSurvivalTest.java \
  bot-engine/src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotRikDispatchTest.java \
  bot-engine/src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotRikRequestDispatchTest.java \
  bot-engine/src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotZicZacDispatchTest.java \
  bot-engine/src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotZicZacRequestDispatchTest.java \
  bot-messages/src/main/java/com/vingame/bot/domain/bot/message/g3/rik/RikBetInfo.java \
  bot-messages/src/main/java/com/vingame/bot/domain/bot/message/g3/rik/RikEndGameMessage.java \
  bot-messages/src/main/java/com/vingame/bot/domain/bot/message/g3/rik/RikGameMessageTypes.java \
  bot-messages/src/main/java/com/vingame/bot/domain/bot/message/g3/rik/RikMainBetSummary.java \
  bot-messages/src/main/java/com/vingame/bot/domain/bot/message/g3/rik/RikStartGameMd5Message.java \
  bot-messages/src/main/java/com/vingame/bot/domain/bot/message/g3/rik/RikStartGameMessage.java \
  bot-messages/src/main/java/com/vingame/bot/domain/bot/message/g3/rik/RikSubscribeMessage.java \
  bot-messages/src/main/java/com/vingame/bot/domain/bot/message/g3/rik/RikUpdateBetMessage.java \
  bot-messages/src/main/java/com/vingame/bot/domain/bot/message/g3/rik/RikZicZacBallResult.java \
  bot-messages/src/main/java/com/vingame/bot/domain/bot/message/g3/rik/RikZicZacEndGameMessage.java \
  bot-messages/src/main/java/com/vingame/bot/domain/bot/message/g3/rik/RikZicZacGameMessageTypes.java \
  bot-messages/src/main/java/com/vingame/bot/domain/bot/message/g3/rik/RikZicZacUpdateBetMessage.java \
  bot-messages/src/main/java/com/vingame/bot/domain/bot/message/request/GameRequest.java \
  bot-messages/src/main/java/com/vingame/bot/domain/bot/message/request/GameRequestFactory.java \
  bot-messages/src/main/java/com/vingame/bot/domain/bot/message/request/RikStockBet.java \
  bot-messages/src/main/java/com/vingame/bot/domain/bot/message/request/RikStockCommit.java \
  bot-messages/src/main/java/com/vingame/bot/domain/bot/message/request/RikStockRequest.java \
  bot-messages/src/main/java/com/vingame/bot/domain/bot/message/request/ZicZacBet.java \
  bot-messages/src/main/java/com/vingame/bot/domain/bot/message/request/ZicZacRequest.java \
  bot-messages/src/test/java/com/vingame/bot/domain/bot/message/GameMessageTypesForGameContractTest.java \
  bot-messages/src/test/java/com/vingame/bot/domain/bot/message/GameRequestFactoryCapabilityTest.java \
  bot-messages/src/test/java/com/vingame/bot/domain/bot/message/MessageTypesCoverageTest.java \
  bot-messages/src/test/java/com/vingame/bot/domain/bot/message/MessageTypesRegistryTest.java \
  bot-messages/src/test/java/com/vingame/bot/domain/bot/message/g3/rik/RikCrossGameShapeToleranceTest.java \
  bot-messages/src/test/java/com/vingame/bot/domain/bot/message/g3/rik/RikEndGamePayoutSemanticsTest.java \
  bot-messages/src/test/java/com/vingame/bot/domain/bot/message/g3/rik/RikFixtureProvenanceTest.java \
  bot-messages/src/test/java/com/vingame/bot/domain/bot/message/g3/rik/RikGameMessageTypesRoutingTest.java \
  bot-messages/src/test/java/com/vingame/bot/domain/bot/message/g3/rik/RikGameMessageTypesTest.java \
  bot-messages/src/test/java/com/vingame/bot/domain/bot/message/g3/rik/RikProviderRegistrationTest.java \
  bot-messages/src/test/java/com/vingame/bot/domain/bot/message/g3/rik/RikTaiXiuMd5GameShapeTest.java \
  bot-messages/src/test/java/com/vingame/bot/domain/bot/message/g3/rik/RikZicZacCaptureArithmeticTest.java \
  bot-messages/src/test/java/com/vingame/bot/domain/bot/message/g3/rik/RikZicZacEndGameSemanticsTest.java \
  bot-messages/src/test/java/com/vingame/bot/domain/bot/message/g3/rik/RikZicZacGameMessageTypesRequestForTest.java \
  bot-messages/src/test/java/com/vingame/bot/domain/bot/message/g3/rik/RikZicZacGameShapeTest.java \
  bot-messages/src/test/java/com/vingame/bot/domain/bot/message/request/RequestTest.java \
  bot-messages/src/test/java/com/vingame/bot/domain/bot/message/request/RikStockBetCaptureFidelityTest.java \
  bot-messages/src/test/java/com/vingame/bot/domain/bot/message/request/RikStockBetTest.java \
  bot-messages/src/test/java/com/vingame/bot/domain/bot/message/request/RikStockCommitTest.java \
  bot-messages/src/test/java/com/vingame/bot/domain/bot/message/request/RikStockRequestTest.java \
  bot-messages/src/test/java/com/vingame/bot/domain/bot/message/request/TaiXiuRequestTest.java \
  bot-messages/src/test/java/com/vingame/bot/domain/bot/message/request/ZicZacBetCaptureFidelityTest.java \
  bot-messages/src/test/java/com/vingame/bot/domain/bot/message/request/ZicZacBetTest.java \
  bot-messages/src/test/java/com/vingame/bot/domain/bot/message/request/ZicZacRequestTest.java \
  bot-messages/src/test/resources/captures/rik-stockPlugin-13000.jsonl \
  bot-messages/src/test/resources/captures/rik-taixiuMd5Plugin-7000.jsonl \
  bot-messages/src/test/resources/captures/rik-ziczacPlugin-12000.jsonl \
  bot-messages/src/test/resources/messages/rik/endGame-loss.json \
  bot-messages/src/test/resources/messages/rik/endGame.json \
  bot-messages/src/test/resources/messages/rik/startGame.json \
  bot-messages/src/test/resources/messages/rik/subscribe.json \
  bot-messages/src/test/resources/messages/rik/txmd5-endGame-noBet.json \
  bot-messages/src/test/resources/messages/rik/txmd5-endGame.json \
  bot-messages/src/test/resources/messages/rik/txmd5-startGame.json \
  bot-messages/src/test/resources/messages/rik/txmd5-subscribe.json \
  bot-messages/src/test/resources/messages/rik/updateBet.json \
  bot-messages/src/test/resources/messages/rik/ziczac-endGame-loss.json \
  bot-messages/src/test/resources/messages/rik/ziczac-endGame-noBet.json \
  bot-messages/src/test/resources/messages/rik/ziczac-endGame.json \
  bot-messages/src/test/resources/messages/rik/ziczac-startGame.json \
  bot-messages/src/test/resources/messages/rik/ziczac-subscribe.json \
  bot-messages/src/test/resources/messages/rik/ziczac-updateBet.json
```

That is 73 code/test/fixture paths, exactly the set that was built and tested above. Docs can go in
a second commit:

```bash
git add -- \
  docs/plans/RIK_114_BETTING_MINI.md \
  docs/plans/RIK_114_ZICZAC.md \
  docs/reviews/RIK_114_BETTING_MINI/SESSION_STATE.md \
  docs/reviews/RIK_114_BETTING_MINI/compliance.md \
  docs/reviews/RIK_114_BETTING_MINI/display-name-check.md \
  docs/reviews/RIK_114_BETTING_MINI/qa.md \
  docs/reviews/RIK_114_BETTING_MINI/release.md \
  docs/reviews/RIK_114_BETTING_MINI/review.md \
  docs/reviews/RIK_114_ZICZAC/compliance.md \
  docs/reviews/RIK_114_ZICZAC/qa.md \
  docs/reviews/RIK_114_ZICZAC/release.md \
  docs/reviews/RIK_114_ZICZAC/review.md \
  docs/reviews/RIK_114_ZICZAC/review-precommit.md
```

Before staging, check with `git diff --cached --stat`: 9 modified, 64 new under `bot-*`, 13 new
under `docs/`, and nothing else.

## Notes

- After the commit, `RIK_114_ZICZAC/release.md`'s statement that the build is "not reproducible from
  any commit" becomes historical. The next release can cite a SHA.
- `MessageTypesRegistryTest`'s exact set already listed `119` at the tip, so the only inventory
  change is `114`. The boot line becomes `BETTING_MINI 6 products [097, 098, 114, 116, 118, 119]`.
- The `forGame` default method and the single-call-site guard are a clean way to add the game
  dimension without widening the registry key. It is worth keeping as the pattern for the next
  multi-game product.
