# Code Review — LOGGING_AUDIT

Branch: `feat/logging-audit`
Reviewed diff: `git diff main..feat/logging-audit` (focused on commits `68ad8e2` and `2149061`; the rest of the diff is merged-forward unrelated work outside scope)

## Verdict

PASS

No `bug` or `security` findings. Two `smell` notes worth recording for future work; both are minor and intentional per the plan's deferred items.

## Findings

### [smell] Demoted GameMsClient deposit response makes WARN at caller opaque at INFO
`src/main/java/com/vingame/bot/infrastructure/client/GameMsClient.java:106` and caller `src/main/java/com/vingame/bot/domain/bot/core/Bot.java:234`

When `gameMsClient.deposit(...)` receives a non-200 HTTP response, the consumer callback fires with `success = false` and `Bot` logs `log.warn("Bot {}: Deposit failed", userName)`. The corresponding status code + response body line at `GameMsClient:106` is now DEBUG, so once production moves to INFO the operator sees a WARN with no upstream context — they cannot tell whether the gateway returned 401, 500, a structured business error, or simply timed out. The demotion itself is correct per the plan (HTTP bodies are debug-level), but the WARN at the caller is now a one-liner that pushes the operator straight to DEBUG to diagnose. Consider, in a follow-up, attaching the statusCode + first ~200 chars of the body to the failure callback (or to a sibling `log.warn` inside `GameMsClient`'s 200-vs-non-200 branch) so the WARN is self-contained. Not in scope for this audit.

### [smell] Bot.restart() INFO is the only signal at INFO that periodic logout fired
`src/main/java/com/vingame/bot/domain/bot/core/Bot.java:176` (and caller `BotGroupBehaviorService:879`)

`Bot.restart()` is presently invoked from exactly one place — `performPeriodicLogout` in `BotGroupBehaviorService`. After this audit, the per-cycle `"Periodic logout starting for bot X"` (`:861`) and `"Periodic logout completed for bot X"` (`:881`) are DEBUG, while the new `"Bot X: restart requested"` line at `Bot.java:176` is INFO. At INFO the operator sees a restart-requested line with no contextual frame ("why is this bot being restarted?"). Today the answer is unambiguous (only caller is periodic-logout), but the INFO/DEBUG split inverts the usual outer-context-at-INFO pattern. If a second caller of `Bot.restart()` is added later, this will degrade further. Either keep the scheduler's per-cycle starting-line at INFO (one per interval per group is not noisy) or, alternatively, log "Bot X: restart requested (reason: periodic-logout)" at the call-site of `performPeriodicLogout` instead of the bot method. Borderline call — flagged for visibility, not blocking.

## Notes

- **Demotions verified against plan.** All 32 INFO→DEBUG demotions across `Bot.java`, `BotGroupBehaviorService.java`, `ApiGatewayClient.java`, and `GameMsClient.java` match the line-by-line table in `docs/plans/LOGGING_AUDIT.md` §Phase 2. Spot-checked via `git show feat/logging-audit:<file>` on each — every line in the plan table is now `log.debug`, every line tagged "keep at INFO" is still `log.info`, and every line tagged "Keep at WARN/ERROR" is unchanged.

- **WARN/ERROR untouched.** Grep across the diff for the two commits shows zero WARN→other or ERROR→other changes. Specifically verified: `Bot:164` ERROR, `Bot:204` ERROR (newly added is DEBUG on the success path — the existing ERROR on the failure path is unchanged), `Bot:213` ERROR, `Bot:234` WARN, `Bot:355/:369` WARN, `Bot:449` ERROR, `BotGroupBehaviorService:740/:772/:782/:810/:826/:851/:886/:889` WARN/ERROR all preserved, `ApiGatewayClient:140/:214/:219/:265/:268/:359/:393/:406` WARN/ERROR preserved, `GameMsClient:110` ERROR preserved.

- **Group lifecycle at INFO is reconstructible.** The set of remaining INFO lines in `BotGroupBehaviorService` (`:140`, `:145`, `:156`, `:161`, `:169`, `:223`, `:247`, `:495`, `:523`, `:562`, `:570`, `:804`, `:833`) plus `ApiGatewayClient:180/:230` (registration start/summary) covers: app startup banner, shutdown, auto-start scan, per-group auto-start trigger, "creating N bots" + "started successfully with N", stop-success, restart, scheduled restart, periodic-logout scheduler started/disabled, registration totals. An operator at INFO can identify create → start → run-with-N-bots → stop or → DEAD (via new WARN + existing ERROR) without consulting DEBUG.

- **The four new lines are appropriately levelled.**
  - `Bot.restart()` INFO — rationale stands (operator-triggered lifecycle); see smell above for a small caveat.
  - `Bot.authenticate()` success DEBUG — symmetric with the existing ERROR on failure (`:204`). DEBUG is the right tier since it's per-bot and the group-level "registration completed" line at `ApiGatewayClient:230` is the operator's signal.
  - `ApiGatewayClient.registerSingleUser` per-call result DEBUG — same justification, per-bot. Message is well-formed: `"Registered user {} with sessionId={} (fingerprint={})"`. The `sessionId` is short-lived per-bot; not a long-term secret.
  - `BotGroupRuntime.markAsDead()` WARN — actionable as written. The message `"Bot group {} entering DEAD state"` tells the operator what happened and on which group; the ERROR at the caller in `BotGroupBehaviorService:772` ("due to repeated failures") adds the cause when invoked from the health monitor path. The combination is appropriate: the boundary log (WARN, no cause) catches future callers; the caller log (ERROR, with cause) handles the only current caller. This is intentional per the commit message and is the right pattern for a state-change boundary.

- **No log lines carry secrets in cleartext that they didn't already carry.** The demoted `[Login]`, `[Register]`, `[VerifyToken]`, and `[UpdateFullname]` lines included `X-TOKEN`, register password (request body), and raw `agencyToken` / `authToken` (response body) before this audit; they still do, just at DEBUG now. The plan explicitly defers redaction (`LOGGING_SECRET_REDACTION`) — out of scope here. No newly added line introduces a secret leak; specifically the new `Registered user … sessionId={}` log includes the sessionId, which is the new `authToken` shape — same as existing DEBUG-level `[Login] response` line — consistent with current code.

- **MDC propagation intact for new lines.** `Bot.restart()` is invoked on the logout-scheduler virtual thread which sets `BotMdc.setGroupContext` before dispatch; the new INFO inherits `botGroupId` + `environmentId` MDC. `Bot.authenticate()` success is inside `Bot.start()` whose existing flow already sets `BotMdc`. `ApiGatewayClient.registerSingleUser` runs on the registration virtual thread which has `BotMdc` set per-task. `BotGroupRuntime.markAsDead()` is called from `handleBotGroupDeath` on the health-monitor virtual thread which sets `BotMdc.setGroupContext` in `startHealthMonitoring`. All four new lines carry expected MDC.

- **Test impact: none.** Grep across `src/test/java` for `Level.INFO|Level.DEBUG|Level.WARN`, `assertThat.*log`, and string-contains assertions on the specific demoted messages (`Created bot`, `Bot initialized`, `Logged out`, `Deposit`, `[Login]`, `[Register]`, `[VerifyToken]`, `Cleaning up`, `periodic logout`, `health`) returned only the captive-appender filters in `BotGroupBehaviorServiceRestartTest` at `:344` and `:481`, both of which filter on `Level.ERROR` only. The 32 demotions plus 4 additions don't touch any asserted line. Dev's note ("445/445 passing, no test changes") is consistent with what the test suite would do.

- **Out-of-scope diff noise.** `git diff main..feat/logging-audit --stat` shows 85 changed files — the logging-audit work itself is 4 main-source files + 0 test files. The remainder is merged-forward work (TIP_ENDGAME, RESTART_LIFECYCLE_FIX, API_ERROR_FORWARDING, OBSERVABILITY) that landed on `main` while this branch was open. Not reviewed here; presumed previously reviewed under their own PRs.
