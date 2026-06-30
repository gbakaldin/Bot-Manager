# LOGGING_AUDIT — Reclassify log levels across the codebase

## Goal

Tighten the separation between INFO and DEBUG (and the other levels) in the
application's log output so that an operator running with the default level set
can reconstruct the lifecycle of a bot group from the log alone, without being
buried under per-bot or per-message detail. Today the production logger
`com.vingame.bot` is pinned at DEBUG in `src/main/resources/log4j2.properties:8`
which masks the level problem — every audit decision must be evaluated against
"what happens if we raise the threshold to INFO". This audit is about the
*content* of `log.*(...)` calls, not the appenders, layout, or pipeline (those
were handled in `LOGGING_PIPELINE_FIX`).

## Findings — Current State

### Inventory (counted across `src/main/java`, 127 files)

| Level | Statements |
|---|---|
| INFO  | 76 |
| DEBUG | 18 |
| WARN  | 26 |
| ERROR | 27 |
| TRACE | 0 |
| **Total** | **147** |

INFO is 52 % of all log statements. WARN+ERROR (53) outnumber DEBUG (18) 3:1,
which is unusual — the typical healthy ratio is heavy on DEBUG with a small INFO
core. The level skew matches what one would expect from a codebase that has been
running with `com.vingame.bot=debug` long enough that developers reached for
INFO when they wanted "always visible".

### Effective production log level

`src/main/resources/log4j2.properties:8` — `logger.app.level = debug`. So today
**DEBUG is on in production**. The audit therefore has two related goals:

1. Reclassify INFO→DEBUG for per-bot / per-message detail so an operator can
   safely raise the threshold to INFO without losing the lifecycle.
2. Validate the reclassification by toggling the logger to INFO and confirming
   the lifecycle is still reconstructible. The toggle is intended as a runtime
   verification step, not a production rollout — production stays at DEBUG
   until a separate decision is made to move it.

### Noisiest classes (top 5 by `log.*` statement count)

| Rank | Class | Count | Notes |
|---|---|---|---|
| 1 | `BotGroupBehaviorService` | 36 | Group lifecycle + health monitor + periodic logout — heavy mix of operator-relevant and per-bot detail. |
| 2 | `Bot` (core) | 27 | Per-bot lifecycle, reconnect loop, balance, deposit. Heavy INFO use for events that fire N times (once per bot). |
| 3 | `ApiGatewayClient` | 24 | HTTP request/response dumps at INFO including raw bodies (login, register, verifytoken, updateFullname). |
| 4 | `GameMsClient` | 11 | Deposit + token-detail dumps at INFO including full URLs and bodies. Contains deprecated methods that still log. |
| 5 | `RestExceptionHandler` | 10 | Per-arm exception handler logs — each at the level matching the response severity. Already well-tuned. |

### INFO-without-WARN/ERROR — informational-only classes

A grep across the codebase did not surface any class above the noise floor
(3 INFOs, 0 WARN/ERROR) that is purely informational. The shape of the codebase
is "noisy classes also have WARN+ERROR" (good) — the problem is overuse of
INFO inside those same classes, not unbalanced classes.

### Specific findings worth calling out

- **Health monitor INFO fires every 30 s × N groups**
  (`BotGroupBehaviorService.java:760`). With 10 groups running this produces
  ~20 lines/minute of `Group X health — playing: N, reconnecting: 0, dead: 0/N`
  whether anything changed or not. This is *the* dominant INFO source at idle.
- **HTTP request/response bodies dumped at INFO**
  (`ApiGatewayClient.java:137`, `:145`, `:300`, `:310`, `:341`, `:353`, `:422`,
  `:435`; `GameMsClient.java:78`, `:106`, `:131`, `:134`, `:152`, `:176`,
  `:204`, `:229`, `:232`, `:250`). Login bodies include `X-TOKEN` values;
  register bodies include passwords. These should be DEBUG at minimum, and the
  token-value dumps should be redacted entirely (separate concern — flag in
  Open Items, not fixed in this plan).
- **Per-bot lifecycle events at INFO** (`Bot.java:146`, `:159`, `:187`, `:209`,
  `:222`, `:230`, `:289`, `:402`, `:431`; `BotGroupBehaviorService.java:458`;
  `BotGroupRuntime.java:138`). With a 30-bot group, each lifecycle event
  produces 30 INFO lines (one per bot). Bots are already tagged with `botId`
  in MDC — operators who need per-bot detail can filter at the appender level.
- **DEBUG used appropriately for tight-loop detail**
  (`Bot.java:239`, `:244`, `:250`, `:253`, `:406`, `:441`, `:467`). The
  `checkBalance()` and reconnect-loop debug lines are correctly classified.
- **WARN/ERROR are mostly well-tuned** — `RestExceptionHandler` mirrors level
  to HTTP severity (4xx → WARN, 5xx → ERROR), `Bot` reconnect failures and
  watchdog expiry are WARN, terminal DEAD transition is ERROR. Don't touch
  these in Phase 1.
- **DisplayNameService overuses ERROR for fixable warnings**
  (`DisplayNameService.java:36`, `:52`). Missing file warns; failed load
  errors with stacktrace. The warn on missing file is fine; the error is fine
  (it's a startup failure of an optional feature, operator must know).

## Per-aspect readiness / mapping

| Aspect | Status | Notes |
|---|---|---|
| log4j2 pipeline + appenders | ready | Already structured JSON to file, pattern with MDC to console. No change. |
| MDC propagation (botGroupId, botId, gameType) | ready | `BotMdc` set at every async boundary post-`LOGGING_PIPELINE_FIX`. Audit can rely on MDC for per-bot disambiguation. |
| Level definitions | partial | No written guideline in the repo today. Phase 1 adds one in `CLAUDE.md` (this plan is the source). |
| Top-5 noisy classes | ready for reclassification | All read, specific lines targeted in Phase 2. |
| HTTP body dumps | partial | Reclassification covered here; secret redaction is a separate plan. |
| Spring / third-party loggers | out of scope | Audit limited to `com.vingame.bot.*`. |
| Tests | out of scope | Test-time log output stays as-is; audit is for `src/main/java` only. |

## Architecture Decisions

1. **Production logger stays at DEBUG for now.** The audit's value is realised
   only when we can raise the threshold to INFO without information loss. The
   actual switch is deferred to a follow-up — this plan delivers the
   reclassification work that *enables* that switch. The smoke check in
   §Verification toggles the logger to INFO temporarily to validate.
2. **No new logging library, no structured-logging migration in this plan.**
   log4j2 stays, MDC stays, appenders stay. JSON structured logging via fields
   beyond MDC is a separate concern.
3. **Level definitions are normative for the bot codebase.** They are written
   into this plan §Level Guidelines and Dev is expected to apply them
   consistently in future work. Conflicts with existing code are resolved in
   the existing code's favour only when the existing code is correct per the
   guideline (most existing INFO use is not).
4. **MDC tags (`botGroupId`, `botId`, `gameType`) are the primary
   per-bot/per-group disambiguator.** A reclassification from INFO→DEBUG is
   *not* a loss if the MDC tag is on the line — operators can re-enable
   per-bot DEBUG via the loggers actuator endpoint when they need it.
5. **No log line additions in Phase 2.** Phase 2 is exclusively level changes
   on existing lines so the diff is reviewable. New log lines (a small,
   curated set) ship in Phase 3 with their own justification per line.
6. **Reclassify INFO→DEBUG, not delete.** Even noisy lines have diagnostic
   value with `logger.app.level=debug`. The audit's job is to push them down,
   not remove them.
7. **WARN/ERROR are not in scope for level changes.** Phase 1 inventory found
   them well-tuned. Touching them invites regressions in alerting downstream
   (anyone watching ERROR-level for paging).

## Level Guidelines (normative for `com.vingame.bot.*`)

### INFO

Lifecycle events that an operator wants to see at default level when reading
the log of a healthy production system. Should not fire on per-bot or
per-message frequencies in steady state. A 30-bot group running for an hour
should produce in the low tens of INFO lines, not thousands. Examples that
qualify: application startup banner, bot group create / start / stop /
restart, group-level state transitions (RUNNING → DEAD), scheduled-restart
firing, periodic-logout scheduler started/stopped. Examples that do NOT
qualify: per-bot transitions, per-HTTP-call envelopes, balance checks,
per-message dispatch.

### DEBUG

Per-bot or per-message detail useful when diagnosing a specific bot or a
specific exchange, noisy at the group level. Today most "INFO" lines belong
here. Examples: per-bot status transitions, per-bot deposit success/failure,
HTTP request/response bodies (login, register, verifytoken, updateFullname,
deposit), reconnect attempt success, balance fetch, periodic-logout
per-bot completion. The MDC tag (`botId`, `gameType`) is on every line — an
operator who needs to drill into one bot can raise the level via
`/actuator/loggers/com.vingame.bot` without redeploying.

### WARN

Recoverable anomalies — the system is still functioning but something looks
off and warrants investigation if it persists. Examples that qualify: bot
WS disconnect (triggers retry), watchdog expiry, partial registration
result, display-name conflict, deposit failure for a single bot, periodic
logout interrupted. Should *not* be used for expected outcomes (e.g. an
expected 4xx from a validation endpoint — that's the caller's fault, but
the bot manager itself didn't malfunction — leave at INFO with the
exception handler).

### ERROR

Failures that affect functionality and require operator attention, paired
with a remediation hint where possible. Examples that qualify: bot group
marked DEAD, re-authentication failed (bot lost), 5xx response from advice,
failed to load display names (feature broken), executor interrupted during
shutdown. ERROR should be paged-on in any reasonable alerting setup — keep
the volume low.

### TRACE

Wire-level / packet-level detail mostly off in prod. Currently unused (0
statements). Reserve for future use — e.g. full WebSocket frame dumps if
ever needed. Do not adopt as a junk drawer for "even more verbose than
DEBUG".

## Plan

Three phases. Each is independently shippable. Phase 1 introduces no behavioural
risk (docs only). Phases 2 and 3 are level changes only, no semantic changes.

### Phase 1 — Document the guidelines (docs only, no code)

1. Add a `Logging Guidelines` section to `CLAUDE.md` next to the existing
   logging discussion, mirroring §"Level Guidelines" above. Single source of
   truth so future contributors don't reintroduce INFO-overuse.
2. Phase 1 is documentation only and produces one commit.

### Phase 2 — Reclassify the top-5 noisy classes (INFO → DEBUG)

Each item is a specific file:line with the proposed new level. The list is the
curated subset; non-listed lines stay as-is for this phase.

#### `BotGroupBehaviorService.java`

| Line | Current | Proposed | Rationale |
|---|---|---|---|
| 232 | DEBUG | DEBUG (keep) | Already correct — per-bot start. |
| 458 | INFO  | DEBUG | "Created bot X (i/N)" — N lines per group create. Group-level "Created N bots" stays at INFO from `:230` of `ApiGatewayClient` and from group-level lines `:223` `:247`. |
| 760 | INFO  | DEBUG | Health-monitor heartbeat — fires every 30 s × N groups. Group-level state *transitions* (DEAD at `:772`, recovery if/when added) stay loud. Heartbeat at steady state moves to DEBUG. |
| 844 | DEBUG | DEBUG (keep) | Per-group skip — already correct. |
| 857 | INFO  | DEBUG | "Bot X already disconnected, skipping periodic logout" — per-bot, recoverable. |
| 861 | INFO  | DEBUG | "Periodic logout starting for bot X" — per-bot. The scheduler-started INFO at `:833` stays. |
| 873 | INFO  | DEBUG | "Group X stopped during logout delay" — per-bot housekeeping. |
| 881 | INFO  | DEBUG | "Periodic logout completed for bot X" — per-bot. |

Keep at INFO: `:140`, `:145`, `:156`, `:161`, `:169`, `:223`, `:247`, `:495`,
`:523`, `:562`, `:570`, `:804`, `:833` — all genuine lifecycle / group-level
events. Keep at WARN/ERROR: `:183`, `:278`, `:286`, `:353`, `:365`, `:468`,
`:740`, `:772`, `:782`, `:810`, `:826`, `:851`, `:886`, `:889`.

#### `Bot.java`

| Line | Current | Proposed | Rationale |
|---|---|---|---|
| 146 | INFO  | DEBUG | "Bot initialized and connected. Client: …" — per-bot, N lines per start. The group-level "started N bots" line in `BotGroupBehaviorService` is the operator's signal. |
| 159 | INFO  | DEBUG | "Cleaning up bot X" — per-bot stop. Group-level stop INFO stays. |
| 187 | INFO  | DEBUG | "Bot X stopping. Closing client instance: …" — per-bot. |
| 209 | INFO  | DEBUG | "Bot X: Logged out" — per-bot. Periodic-logout INFO at `BotGroupBehaviorService:861` is also moving to DEBUG (above) — both together. |
| 222 | INFO  | DEBUG | "Bot X: Deposit successful, fetching new balance…" — per-bot. |
| 230 | INFO  | DEBUG | "Bot X: New balance: …" — per-bot. |
| 289 | INFO  | DEBUG | "Bot X: prev → next" — per-bot status transition. N×K lines per group lifetime. The operator's signal for "the group transitioned" is `BotGroupBehaviorService:760` (which is also going DEBUG) and the explicit group state events; per-bot transitions are debug-only. |
| 402 | INFO  | DEBUG | "Bot X: reconnected to WS (attempt …)" — per-bot, recoverable. The disconnect WARN at `:353` stays loud. |
| 431 | INFO  | DEBUG | "Bot X: reconnected after full re-auth" — per-bot. |

Keep at WARN/ERROR: `:164`, `:202`, `:211`, `:232`, `:353`, `:367`, `:447`.
Keep at DEBUG: `:106`, `:130`, `:141`, `:239`, `:244`, `:250`, `:253`,
`:406`, `:441`, `:467`, `:587` — already correct.

#### `ApiGatewayClient.java`

| Line | Current | Proposed | Rationale |
|---|---|---|---|
| 137 | INFO  | DEBUG | "[Login] POST … body: …" — per-bot HTTP dump. Body contains `X-TOKEN`. |
| 145 | INFO  | DEBUG | "[Login] response: agencyToken/authToken/jwtToken" — per-bot, contains raw tokens. |
| 257 | INFO  | DEBUG | "Successfully registered user i/N: …" — per-bot. Group-level totals at `:180` and `:230` stay at INFO. |
| 263 | INFO  | DEBUG | "Set display name … for user …" — per-bot. |
| 300 | INFO  | DEBUG | "[Register] POST … body: …" — per-bot HTTP dump. |
| 310 | INFO  | DEBUG | "[Register] response HTTP …" — per-bot. |
| 341 | INFO  | DEBUG | "[UpdateFullname] POST …" — per-bot. |
| 353 | INFO  | DEBUG | "[UpdateFullname] response HTTP …" — per-bot. |
| 364 | INFO  | DEBUG | "Display name set successfully to: …" — per-bot. |
| 401 | INFO  | DEBUG | "Retrying with different name (attempt n/N)" — per-bot retry. |
| 422 | INFO  | DEBUG | "[VerifyToken] GET …" — fires on every balance check. |
| 435 | INFO  | DEBUG | "[VerifyToken] response HTTP …" — same. |

Keep at INFO: `:180` ("Starting parallel user registration: N users…"),
`:230` ("Parallel user registration completed. Success: N, Failures: M") —
group-level summary. Keep WARN/ERROR: `:140`, `:214`, `:219`, `:265`,
`:268`, `:359`, `:386`, `:393`, `:404`.

#### `GameMsClient.java`

The deprecated methods (`deposit(long, Consumer)`, `fetchTokenDetails()`)
should *not* be modified — they are slated for deletion in a follow-up and
touching them now risks merge conflicts. The non-deprecated path:

| Line | Current | Proposed | Rationale |
|---|---|---|---|
| 78  | INFO  | DEBUG | "Depositing N for agency token …" — per-bot. |
| 106 | INFO  | DEBUG | "Response code … body …" — per-bot HTTP dump. |
| 131 | INFO  | DEBUG | "Fetching token details for agency token …" — per-bot. |
| 134 | INFO  | DEBUG | "URI …" — per-bot. |
| 152 | INFO  | DEBUG | "Token details: …" — per-bot, raw response body. |

Lines `:176`, `:204`, `:229`, `:232`, `:250` are in deprecated methods —
leave untouched, they're already on the deletion list per `CLAUDE.md` Backlog
("Replace all deprecated API usage, remove deprecated classes and methods").

Keep at ERROR: `:110`.

#### `RestExceptionHandler.java`

No changes. All ten lines are already correctly tuned (404 → INFO, 4xx →
WARN, 5xx → ERROR). This entry is in the table to make it explicit: the
audit reviewed and approved current levels.

### Phase 3 — Add a small curated set of missing logs

Five locations where a log line should exist but doesn't. Each is added at
the level appropriate per §Level Guidelines. Be conservative — every added
log is a maintenance cost.

1. **`Bot.restart()` at `Bot.java:175`** — INFO. Restart is a per-bot
   lifecycle event invoked from `BotGroupBehaviorService`; today the method
   tears down and reconnects silently. Add `log.info("Bot {}: restart
   requested", userName);` at method entry. (The auto-restart bug noted in
   `CLAUDE.md` Backlog — "Restart lifecycle bug — bots that authenticated
   cleanly on initial auto-start fail on /restart" — was diagnosable only
   from the auth error logs and not from the restart entry. This adds the
   missing breadcrumb.) Per §Level Guidelines, this is borderline INFO/DEBUG
   (it's per-bot, which usually means DEBUG); INFO is justified because the
   *only* reason this fires is an operator action (restart endpoint or
   reconnect loop fall-through), and the operator just took the action.
2. **`Bot.authenticate()` success at `Bot.java:198`** — DEBUG. Currently
   only the failure path logs (`:202`). Add a success-path `log.debug` so
   the auth/no-auth transition is symmetric in the log when running at
   DEBUG.
3. **`ApiGatewayClient.registerSingleUser` outer call result** — DEBUG. Add
   a per-call success log around line `:323` ("Registered user X with
   sessionId=… (fingerprint=…)"). Currently the success is logged at
   `:257` by the caller wrapper but the actual register call has no
   outcome log of its own; if the wrapper changes, we lose the trail.
4. **`BotGroupBehaviorService` group-level reconnect / DEAD-recovery
   transition** — INFO. Today `:772` logs ERROR on entering DEAD; there
   is no symmetric log if the group transitions back to RUNNING (no code
   path for this exists yet — flag in Open Items as a behaviour gap, not
   a log gap).
5. **`BotGroupRuntime.markAsDead()` at `BotGroupRuntime.java:174`** —
   WARN. Add `log.warn("Bot group {} entering DEAD state", groupId);`.
   Today the DEAD transition is logged once at ERROR from the *caller*
   (`BotGroupBehaviorService:772`), but `markAsDead()` itself is callable
   from elsewhere (e.g. test code, future reuse) and silent. Logging on
   the state-change boundary is more robust than logging on the call
   site.

Items 1–3 and 5 ship in Phase 3. Item 4 is filed as an Open Item.

## Implementation Notes / Concerns

- **Today's `logger.app.level=debug` masks the audit.** A reviewer reading
  the log of a Phase 2 build at the default level will see *no difference*
  from the pre-audit log, because everything still emits. The validation
  step (§Verification) toggles the logger to INFO via the loggers actuator
  endpoint to confirm the lifecycle is reconstructible — this is the
  acceptance test for Phase 2.
- **Don't fold in secret-redaction in this plan.** Login bodies and
  token-response logs contain `X-TOKEN`, password (register body), and raw
  `agencyToken` / `authToken` values. Moving them from INFO to DEBUG is a
  good first step but the underlying problem is "these strings should never
  be logged in cleartext at any level". File as separate plan
  (`LOGGING_SECRET_REDACTION`).
- **MDC propagation is a hard prerequisite.** The audit's argument for
  reclassifying per-bot INFO to DEBUG relies on the MDC tag being on every
  per-bot log line. If a future change loses MDC on a code path, the
  reclassified DEBUG line becomes unfindable. `LOGGING_PIPELINE_FIX`
  established the wrappers (`mdcWrap`, `mdcConsumer`) — touch with care.
- **Loggers actuator endpoint is the operator's escape hatch.** When
  diagnosing a specific bot, the operator should raise
  `com.vingame.bot.domain.bot.core.Bot` to DEBUG via `POST
  /actuator/loggers/com.vingame.bot.domain.bot.core.Bot {"configuredLevel":
  "DEBUG"}`. This works without redeploy because Actuator is wired on the
  main port (8080). Document in the Verification section so the operator
  has the command at hand.
- **Don't reclassify WARN/ERROR.** Phase 1 audit found them tuned. Any
  alerting downstream (PagerDuty, Slack on ERROR) is invisibly coupled to
  the current set. Touching them is out of scope.
- **Deprecated `GameMsClient` methods (lines `:176`, `:204`, `:229`,
  `:232`, `:250`)** are skipped. They will be deleted along with their
  callers per `CLAUDE.md` Backlog. Don't reclassify code on the way out.
- **Phase 2 produces a noisy diff** (~30 single-character changes across
  4 files). Group by file in the commit message and call out that there
  are no semantic changes; the reviewer can verify by running `git diff
  -G "log\.(info|debug)" --stat`.

## Open Items

- **Secret redaction in HTTP body logs** — see Implementation Notes.
  Separate plan, not this audit.
- **Move production `logger.app.level` from `debug` to `info`** — deferred.
  Phase 2 makes this safe; the actual switch is a separate small change
  with its own rollback story (one-line diff in `log4j2.properties`).
- **Structured-logging migration** (custom `StructuredArgument` /
  key-value fields beyond MDC) — explicitly out of scope.
- **Group-level RUNNING-after-DEAD recovery log** (Phase 3 item 4) — the
  log gap is real but the underlying behaviour gap is the bigger issue:
  there is no code path that transitions a group out of DEAD. File as
  feature work, not a logging task.
- **Audit of test-time log output** — out of scope. Test logs are noisy
  on purpose to aid debugging.
- **Recently-added features** (`RESTART_LIFECYCLE_FIX`, `API_ERROR_FORWARDING`,
  `TIP_ENDGAME`) were inspected as part of the noisiest-classes pass —
  their additions are already in `Bot`, `BotGroupBehaviorService`,
  `ApiGatewayClient`, and `RestExceptionHandler`. They are covered by
  Phase 2's reclassification. No standalone phase needed.

## Verification

The audit's outputs are docs and level changes — there is no externally
testable behaviour. The verification is "the log at INFO level is still
useful". Run on staging after Phase 2 and Phase 3 are deployed.

1. **Pre-check: baseline.** With the build deployed and `logger.app.level`
   still at DEBUG, capture 5 minutes of log:
   ```bash
   ssh staging "tail -F /app/logs/console.log" > /tmp/baseline-debug.log &
   sleep 300 && kill %1
   wc -l /tmp/baseline-debug.log
   ```
   Expect: non-empty, mixed INFO/DEBUG, includes per-bot lines.

2. **Raise the application logger to INFO via Actuator.** No redeploy:
   ```bash
   curl -sS -X POST -H "Content-Type: application/json" \
     -d '{"configuredLevel":"INFO"}' \
     http://staging:8080/actuator/loggers/com.vingame.bot
   ```
   Expect: HTTP 204. Verify with:
   ```bash
   curl -sS http://staging:8080/actuator/loggers/com.vingame.bot
   ```
   Expect: JSON body with `"effectiveLevel":"INFO"`.

3. **Create, start, and stop a bot group while capturing log at INFO only.**
   ```bash
   ssh staging "tail -F /app/logs/console.log" > /tmp/audit-info.log &
   curl -sS -X POST http://staging:8080/api/v1/bot-group/ -H "Content-Type: application/json" -d '{...staging fixture...}'
   # wait for auto-start (or call /start)
   sleep 120
   curl -sS -X POST http://staging:8080/api/v1/bot-group/<id>/stop
   sleep 10 && kill %1
   ```
   Expect: in `/tmp/audit-info.log`, the operator can identify every
   one of these events without consulting external state:
   - group created (INFO from `BotGroupService:113`)
   - registration started + completed (INFO from `ApiGatewayClient:180`, `:230`)
   - group start initiated (INFO from `BotGroupBehaviorService:223`)
   - group start success with bot count (INFO from `BotGroupBehaviorService:247`)
   - periodic-logout scheduler started, if enabled (INFO from `:833`)
   - group stop initiated (INFO from `:495`)
   Expect: zero per-bot lines (no `Bot bot1: …`, no `[Login] POST …`, no
   `Group X health — playing: N`).

4. **Count per-bot leakage.**
   ```bash
   grep -E "Bot [a-z]+[0-9]+:|\[Login\]|\[Register\]|\[VerifyToken\]|Group .* health" /tmp/audit-info.log
   ```
   Expect: empty. If non-empty, the matching line is a missed Phase 2
   reclassification — file as follow-up.

5. **Confirm a triggered WARN/ERROR still surfaces.** Force a failure (e.g.
   stop a group that isn't running):
   ```bash
   curl -sS -X POST http://staging:8080/api/v1/bot-group/does-not-exist/stop
   grep -E "WARN|ERROR" /tmp/audit-info.log | tail -5
   ```
   Expect: a WARN or ERROR line is present and identifies the failed
   operation.

6. **Restore application logger to DEBUG (the production default).**
   ```bash
   curl -sS -X POST -H "Content-Type: application/json" \
     -d '{"configuredLevel":null}' \
     http://staging:8080/actuator/loggers/com.vingame.bot
   ```
   `null` resets to the configured level from `log4j2.properties` (DEBUG).
   Expect: HTTP 204; subsequent
   `GET /actuator/loggers/com.vingame.bot` returns `effectiveLevel: DEBUG`.

7. **Universal smoke (always runs regardless of audit).** Start a bot
   group, let it run one round, stop it, confirm the operator can
   reconstruct the lifecycle from the INFO+ subset of the log. If step 3
   passed, this passes.

Plan written: docs/plans/LOGGING_AUDIT.md
Ready for user approval before Dev begins.
