# Compliance — LOGGING_AUDIT

Branch: `feat/logging-audit`
Plan reviewed: `docs/plans/LOGGING_AUDIT.md` (git-ignored on disk; not in tree)
Diff reviewed: `git diff 68ad8e2^..2149061` (two-commit Phase 2 + Phase 3 scope per request)

## Verdict

PASS-with-notes

## Phase-by-phase

### Phase 1 — Document the guidelines (docs only)
Status: missing (out of stated scope)
Notes: User scoped this verification to two commits (Phase 2 + Phase 3). Phase 1's deliverable is "Add a `Logging Guidelines` section to `CLAUDE.md`". `CLAUDE.md` on the branch has no such section (no occurrence of "Logging Guidelines" / "Level Guidelines" / normative INFO/DEBUG block). Calling it out as a follow-up — does not block the verdict for the two commits requested.

### Phase 2 — Reclassify the top-5 noisy classes (commit `68ad8e2`)
Status: implemented
Notes: Demotions verified line-by-line against the plan's per-file tables.

Per-file counts (plan → diff):
- `BotGroupBehaviorService.java` — plan listed 6 demotions (lines 458, 760, 857, 861, 873, 881); diff has exactly 6. Match.
- `Bot.java` — plan listed 9 demotions (lines 146, 159, 187, 209, 222, 230, 289, 402, 431); diff has exactly 9. Match.
- `ApiGatewayClient.java` — plan listed 12 demotions (lines 137, 145, 257, 263, 300, 310, 341, 353, 364, 401, 422, 435); diff has exactly 12. Match.
- `GameMsClient.java` — plan listed 5 demotions in the non-deprecated path (lines 78, 106, 131, 134, 152); diff has exactly 5. Deprecated methods (lines 176, 204, 229, 232, 250) are untouched — verified by reading post-commit file; the five `log.info` calls in `deposit(long, Consumer)` and `fetchTokenDetails()` are still INFO. Match.
- `RestExceptionHandler.java` — plan explicitly says "No changes". Diff has none. Match.

Total demotions: 6 + 9 + 12 + 5 = **32**. Dev's claim of 32 vs. plan headline of "~25" — dev is right; the per-file lists sum to 32, the headline was an undercount. Not a drift.

Untouched-INFO sanity checks (must-stay-INFO group-level lines):
- `ApiGatewayClient.java` :180 (registration started) and :230 (registration completed) — not in diff. OK.
- `BotGroupBehaviorService.java` :140/:145/:156/:161/:169/:223/:247/:495/:523/:562/:570/:804/:833 — not in diff. OK.
- All WARN/ERROR lines in scope (Bot:164/202/211/232/353/367/447, BotGroupBehaviorService:183/278/286/353/365/468/740/772/782/810/826/851/886/889, ApiGatewayClient:140/214/219/265/268/359/386/393/404, GameMsClient:110) — none touched. OK.

No semantic changes — every diff hunk in Phase 2 is a single-token `info` → `debug` change.

### Phase 3 — Curated new log lines (commit `2149061`)
Status: implemented (4 of 5 — item 4 deferred per the plan itself)
Notes: Each new line verified against the plan:

1. `Bot.restart()` entry — INFO. Diff: `log.info("Bot {}: restart requested", userName);` at the head of `restart()`. Match.
2. `Bot.authenticate()` success — DEBUG. Diff: `log.debug("Bot {}: authentication succeeded", userName);` immediately after the `apiGatewayClient.authenticate(credentials)` call, before the catch. Match.
3. `ApiGatewayClient.registerSingleUser` per-call result — DEBUG. Diff: `log.debug("Registered user {} with sessionId={} (fingerprint={})", username, data.getSessionId(), fingerprint);` after the `data` extraction and before `return`. Match (location around the plan-specified line 323, fields match the plan's "sessionId=… (fingerprint=…)" template).
4. `BotGroupBehaviorService` group-level RUNNING-after-DEAD recovery — **deferred**. This matches the plan's own explicit instruction: "Items 1–3 and 5 ship in Phase 3. Item 4 is filed as an Open Item." (LOGGING_AUDIT.md plan body, end of Phase 3 section). The Open Items section even calls out the underlying reason — there is no DEAD→RUNNING code path. So the user's framing "4 new log lines (1 deferred)" is correct, and the deferral matches the plan exactly. Not drift.
5. `BotGroupRuntime.markAsDead()` — WARN. Diff: `log.warn("Bot group {} entering DEAD state", groupId);` as the first statement of `markAsDead()`. Match. Note the plan justification — the WARN at the state-change boundary is intentionally additive to (not replacing) the existing ERROR at the caller (`BotGroupBehaviorService:772`), which carries the "due to repeated failures" diagnostic context. Dev's commit message acknowledges this. OK.

Plan headline "5 new lines" vs. dev's 4 — accurate; item 4 was filed as an Open Item in the plan itself before Phase 3 even shipped.

## Drift

None within the two-commit scope. All demotions hit exactly the lines listed in the plan; all four new lines are at the levels and locations specified; deferred item is deferred per the plan's own instructions.

## Out-of-scope changes

None in the two commits under review. The remainder of the branch contains unrelated features (OBSERVABILITY, RESTART_LIFECYCLE_FIX, API_ERROR_FORWARDING, TIP, ENDGAME_METRICS) that landed on the same branch but are outside the scope of this verification.

## Notes / follow-ups (do not block verdict)

- **Phase 1 docs missing.** `CLAUDE.md` does not yet contain the normative "Logging Guidelines" section that Phase 1 of the plan calls for. The plan declares this Phase 1 (docs only, single commit) and the level guidelines are otherwise authored only in the (git-ignored) plan file itself — meaning the normative source-of-truth is currently nowhere in the tree. Recommend a separate small commit adding the §"Level Guidelines" block to `CLAUDE.md` before the plan is closed out.
- **Headline vs. tabulated counts.** Plan headline says "~25 demotions" but the per-file tables sum to 32. Worth fixing the headline in the plan if it's kept around, so future readers don't get confused. Dev is correct on the count.
- **Verification step (§Verification in plan) not executed here.** The plan's verification is a staging-time exercise (toggle `com.vingame.bot` to INFO via Actuator and confirm lifecycle is reconstructible). Compliance only checks plan-vs-diff faithfulness; the operator smoke-test is QA's / Releaser's territory.

## Amendments to the plan

None.
