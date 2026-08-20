# Compliance — LOG_VOLUME_TIERING

Branch: `feature/log-volume-tiering` (HEAD `25fedee`)
Plan reviewed: `docs/plans/LOG_VOLUME_TIERING.md` (added at `f7e8196`, amended at `b98ab61`,
amended again by this pass)
Diff reviewed: `git diff 03837a2..feature/log-volume-tiering` (31 commits; `main` is far
behind `staging`, so `main..HEAD` also carries the already-reviewed VIPTALK_ALERTING_V2 and
MODULE_DECOUPLING work — that is not this feature and is not assessed here). Remediation
range re-reviewed in detail: `562488d..HEAD` (11 commits).

Build: `mvn -o test` on JDK 21 — **BUILD SUCCESS** (exit 0).
`deploy.sh` is untouched by every commit on the branch — re-verified with
`git log 03837a2..HEAD --name-only -- deploy.sh` (empty).

## Verdict

PLAN_AMENDED

The send-back is closed. Drift 4's enumeration gap is fully remediated and I have verified
the sweep independently rather than accepting the third enumeration on trust. The three
amendments I specified last pass landed verbatim, including the duplicated wrong premise in
the Implementation Notes, and the Architecture Decisions are internally consistent
afterwards. P1-4 and P1-5 now fail on the state they previously passed — confirmed by
replaying them against the pre-remediation tree.

I have amended the plan myself for two remaining defects, both of which describe a defect as
the design rather than asking for different code (see **Amendments to the plan**): the
Phase 2 step 5 escalation rate limit that Dev correctly flagged and correctly declined to
amend, and the unmeasured 720 h Loki retention the Reviewer flagged. Neither amendment
invalidates any part of the diff.

## Phase-by-phase

### Phase 0 — Config only: async, level-aware retention, longer window
Status: implemented (drifted on `blocking`; plan was wrong, amended at `b98ab61`)

Unchanged since the previous pass and re-confirmed: externalized config + bind mount (AD-2),
`Async` appender with the load-bearing `appenderRef.type = AppenderRef` line (AD-3),
`log4j2.component.properties` with `Discard` / `discardThreshold = DEBUG` (AD-4), console
`ThresholdFilter` at `info` (AD-5), `interval = 2` (AD-20), `Delete` block deliberately
untouched, `retention_period: 720h` + both `retention_stream` selectors (AD-6), promtail
`drop` stage committed commented-out. The two `log4j2.properties` twins remain byte-identical
below their headers, guarded by `Log4j2TwinConfigTest`.

The one substantive addition this pass: **AD-6's 720 h is now gated**, not because anything
in the diff is wrong but because nothing in the plan could have caught it being wrong. See
Amendment A2.

### Phase 1 — A cheap, correct default level
Status: **implemented** (was `partial`; this was the send-back)

- Step 1 (AD-7) — unchanged, `LoggingLevelOverrideTest` still pins the override behaviour. ✔
- Step 2 — **now complete.** All four remaining per-bot INFO sites folded:
  - `ClientFactory:85` `Setting shared EventLoopGroup on client` → `log.debug`. The
    diagnostic is genuinely preserved rather than dropped: `NettyEventLoopConfig:45` now
    prints `System.identityHashCode(eventLoopGroup)` in its existing one-shot INFO line.
    There is exactly one such group per JVM and its identity never changes, so a DEBUG line
    carrying a *different* hash still means sharing is broken — the fact the INFO line
    existed to establish survives at one line per JVM instead of one per bot per
    start/restart/re-auth. This was the site that mattered: it cancelled the
    `restart requested` demotion three frames earlier.
  - `BotGroupRuntime:220` `Bot starting in virtual thread` → `log.debug`. ✔
  - `BotGroupBehaviorService:769/:782` `assigned strategy` / `assigned slot strategy` →
    `log.debug`, replaced by **one** group-level line at the assignment site,
    `Bot group {}: strategy mix {RANDOM=30, MARTINGALE=17}` (`:370`). Emitted in `start()`
    at the point the group-level decision is actually made, so it is one line per group
    start, not one per bot. ✔
  - The stale comment at `BotGroupBehaviorService:2001` — which justified the periodic-logout
    INFO line by reference to a `restart requested` **INFO** line that Phase 1 had already
    demoted — is corrected. ✔
- Step 3 — `FleetRollupLogger` unchanged. ✔ (as amended, Drift 3)
- Step 4 (AD-8) — unchanged. ✔
- Step 5 — `CLAUDE.md` now records the four late sites, names their group-level
  replacements, states explicitly that this **supersedes BETTING_STRATEGIES AD-14**, and
  documents `PerBotInfoLogGuardTest` as the enforcement mechanism. The guideline is now true
  of the code it documents, which is what failed last pass. ✔

**The sweep, verified independently.** Two enumerations had already come up short, so I did
not take the third on trust. I enumerated every `.info(` call in `src/main` across all five
modules (`bot-api`, `bot-strategies`, `bot-messages`, `bot-engine`, `bot-app`) — 71 sites —
and classified each by rate. I also checked for INFO emitted by forms the naive grep would
miss (`atInfo()`, `Level.INFO`, non-`log`-named loggers): none exist; the project is
uniformly Lombok `@Slf4j` + `log.info(`.

Result: **no per-bot INFO call site remains in main sources.** Every survivor is
startup-once (factories, `DisplayNameService`, `NettyEventLoopConfig`, `InfoGaugeRefresher`,
`AlertRouter`, `AlertRoomRegistry`, `VipTalkClient`), per-environment
(`EnvironmentClientRegistry`, `EnvironmentClients`), per-group
(`BotGroupService`, `BotGroupRuntime`, `ActivationScheduler`, `GroupLifecycleAggregator`,
`FleetRollupLogger`, the `BotGroupBehaviorService` lifecycle lines), per-operator-action
(`LogLevelController`), per-escalation and rate-limited (`ScopedDebugEscalator`,
`ScopedDebugInstaller`), or per-inbound-API-error (`RestExceptionHandler` — a function of
operator request volume, not bot count).

The one line I examined closely because it *names a bot* is
`BotGroupBehaviorService:2031` `Periodic logout starting for bot {} in group {}`. Dev kept
it at INFO. I confirmed this is correct by reading `performPeriodicLogout`: it is invoked
once per scheduler interval per group and calls `runtime.getNextBotForLogout()` to pick a
**single** bot round-robin, so its rate is groups × (1/interval) — a function of groups, not
of bot count. It does not breach the tier-1 invariant, and the reasoning is now recorded in
the comment above it.

**The guard test enforces what it claims — verified, including that it is discriminating.**
`PerBotInfoLogGuardTest` has three cases: a whole-file ban on `log.info(` in the six classes
whose logging is per-bot in its entirety; a per-message pin on the three demoted sites that
live in files legitimately retaining group-level INFO; and an explicit anti-vacuity case. I
checked it is not a test that passes trivially:

- It runs and does not skip: `mvn -pl bot-app test -Dtest=PerBotInfoLogGuardTest` →
  `Tests run: 3, Failures: 0, Errors: 0, **Skipped: 0**`. The `assumeTrue(root != null)`
  escape does not fire under Surefire.
- It **fails on the pre-remediation tree**. Replaying its exact logic against `562488d`:
  `ClientFactory.java:85` is a `log.info(` offender (case 1 fails); all three `DEMOTED_SITES`
  messages are `log.info(` there (case 2 fails); and neither anti-vacuity anchor exists —
  no `log.debug(` + `Setting shared EventLoopGroup`, no `log.info(` + `strategy mix` (case 3
  fails). All three cases discriminate.
- Case 1's file list covers all four core bot classes (`Bot`, `BettingMiniGameBot`,
  `SlotMachineBot`, `TaiXiuGameBot`) plus `ClientFactory` and `BotFactory` — which is
  exhaustive for classes that currently contain per-bot logging.

Residual gap, noted not blocking: the `PER_BOT_CLASSES` list is hardcoded, so a *new*
per-bot class is not covered until someone adds it. The most obvious uncovered family is
`bot-strategies/…/strategy/**` — strategy objects are per-bot instances. They are clean
today (only the two `*StrategyFactory` classes log at INFO, both startup-once, and per-bet
strategy lines are already TRACE by policy), so nothing is wrong now; but a future strategy
adding `log.info` would not fail the build. Case 1's own javadoc anticipates the maintenance
posture ("update this guard rather than deleting it"). Worth a follow-up, not a send-back —
the guard covers every class that has ever actually broken the rule.

### Phase 2 — Scoped per-group DEBUG with TTL and auto-escalation
Status: implemented (drifted on the attach point; plan was wrong, amended at `b98ab61`;
step 5's rate limit was also wrong in the plan — amended by this pass)

Steps 1–4, 6 and 7 unchanged from the previous pass and still verified. Step 5's three
triggers are still wired at the cited sites, and the escalation still emits one INFO line
naming the trigger and expiry.

**Step 5's bound changed, and the change is a correction, not drift.** `ScopedDebugEscalator`
now gates re-arming on `lastEscalation + ttl + cooldown` (`reArmIntervalMillis()`), with
`escalation.cooldown-minutes` defaulted **15 → 45**. The plan told Dev to "rate-limit
escalation to one per group per 15 min". Dev implemented that literally in the first pass and
then found it bounds nothing — with cooldown equal to TTL the two lapse at the same instant,
so a group parked mid-band re-escalates on the next 30 s health tick, ~96% duty cycle,
unattended. Dev flagged the plan wording rather than amending it, correctly treating it as
outside the three amendments authorised last pass. I have amended it (A1). The claim is
falsifiable and I checked it: `escalation.minutes=15` and the old
`cooldown-minutes=15` in `application.properties`, `dead/total` escalation band
`[0.40, 0.80)` never self-clearing, `monitorHealth` on a 30 s cadence — the arithmetic holds.

### Phase 3 — Evidence shim
Status: implemented (one compatible refinement — Drift 5, no amendment needed)

Unchanged in substance. The remediation pass added one correctness fix worth recording:
evidence age is now measured from the **promotion** (`logs/evidence/.promoted.json`), not
from file mtime. A hardlink shares the source inode, so mtime is the log's last-write time
and the older of a pinned pair can already be days old when pinned — sweeping on mtime
delivered roughly 7 days against an advertised 14. Documented in `docker-compose.yml` and
`secrets.env.example`, with a stated fallback to mtime (which errs early, the safe direction)
if `.promoted.json` is deleted. Consistent with AD-19's intent.

## Drift

Drifts 1, 2 and 3 from the previous pass are **closed** — see Amendments. Drift 4 is
**closed** — see Phase 1 above. Drift 5 and Drift 6 needed no action and are unchanged.

One new item, closed by amendment rather than by code:

### Drift 7 — Phase 2 step 5's rate limit: the plan describes the defect as the design
Dev is right; the plan is wrong; amended as A1. No code change required — the branch already
implements the correct bound and asserts it (`ScopedDebugEscalatorTest` drives four simulated
hours of a permanently sick group and measures the fraction of that time the scope was open,
rather than asserting that a re-arm "eventually" succeeds, which was true of the broken
version too).

## Out-of-scope changes

None material in the remediation range. Everything traces to the send-back, the QA verdict or
the Reviewer's findings. Specifically checked:

- `docker-compose.yml` (+7) and `secrets.env.example` (+8) — comment-only, documenting the
  promotion-time age measurement. No service, volume, image or variable changed.
- `bot-api/…/BotMdc.java`, `ScopedDebugRegistry` — the `withClock` seam the escalator's
  duty-cycle test needs, plus the `anyEnabled`/map serialization fix. Test-visibility and
  correctness, not new surface.
- `bot-app/…/testsupport/ShimSelfTest.java` — extracted so a missing `python3` fails the
  build instead of silently skipping both shim suites. Tightens an existing skip.
- `docs/reviews/LOG_VOLUME_TIERING/review.md` and `compliance.md` — process artifacts.
- **`deploy.sh` is not in the diff.** Re-confirmed across all 31 commits.

## Verification section — achievable?

Yes. The two corrections I flagged for the Releaser last pass have been folded into the plan,
and I have verified they now discriminate rather than merely being longer:

- **P1-4 now fails on the state it previously passed.** The widened pattern set adds
  `Successfully created bot|Bot starting in virtual thread|assigned strategy|assigned slot
  strategy|Setting shared EventLoopGroup`. Against the pre-remediation tree those messages
  were emitted at INFO from `ClientFactory:85`, `BotGroupRuntime:220` and
  `BotGroupBehaviorService:769/:782`, so the grep would have returned `> 0` and failed;
  against HEAD every one of them is `log.debug`. I also checked the widened pattern cannot
  **false-positive** on the new replacements: `NettyEventLoopConfig`'s one-shot line reads
  `Creating Netty MultiThreadIoEventLoopGroup … shared instance {}` and does **not** contain
  `Setting shared EventLoopGroup`; the group-level line reads `strategy mix` and does not
  contain `assigned strategy`. The step is sound in both directions.
- **P1-5's extension likewise.** `grep -E 'strategy mix'` matches
  `Bot group {}: strategy mix {…}` at `BotGroupBehaviorService:370`, which did not exist
  before this pass, so the step fails on the old state. Minor pre-existing weakness carried
  over from its siblings: `tail -1` displays the last line but does not *count* it, so
  "expect exactly one line per group start" is verified by eye rather than by the command.
  True of the `bots initialized` / `bots auto-deposited` checks too; not worth churning the
  step over, but the Releaser should read the surrounding lines rather than just the tail.
- **P2-6 was non-discriminating and is now fixed (A1).** Its `≤ 1 per 15 min` assertion is
  still true, but it also passed on the defective one-per-TTL cooldown — a version that
  re-armed every ~15 min still shows `≤ 1` in any 15-minute window. Extended with a
  60-minute window (the real re-arm interval, 15 + 45) and a duty-cycle poll of
  `GET /api/v1/logging/debug`.
- **P0-9 is new (A2)** and is the only step in the plan capable of failing on an
  over-optimistic volume projection before the 720 h horizon binds at day 30.
- P0-6 (disk size) and P0-8 (T+25 h retention bite) remain genuinely deferred to the box.
  Nothing in the diff can pre-empt them, and **P0-6 is still unmeasured** — it and P0-9 are
  both pre-ramp gates and both remain open for the Releaser.

## Amendments to the plan

Landed by Dev at `b98ab61` under the previous pass's authorisation — **all three confirmed
verbatim, and the ADs are internally consistent afterwards**:

1. **Drift 1** — Phase 0 step 2 `appender.async.blocking = false` → `true`, plus the
   "not optional" gotcha; AD-4's no-latency sentence qualified to the discardable tiers.
   Consistent with AD-3's `AsyncAppender`/`ArrayBlockingQueue` choice and with the shipped
   `logging/log4j2.properties`.
2. **Drift 2** — AD-9's `config.getLoggerConfig("com.vingame.bot").addFilter(filter)` →
   `ctx.getConfiguration().addFilter(filter)`; AD-10 rewritten around the Configuration
   filter and the prefix gate; Phase 2 step 2 now *states* the prefix-gate requirement
   rather than leaving it an undocumented necessity; step 3's snippet corrected; **and the
   Implementation Notes bullet that repeated the same wrong premise is corrected too** —
   this was the specific thing I asked to be confirmed, and it is. `grep -n getLoggerConfig`
   over the plan returns nothing.
3. **Drift 3** — AD-8's "a downsampled rounds-and-stake line per group" replaced with the
   env-line-plus-unclean-group-detail formulation, and the "must not emit a line per group
   per cycle" prohibition made explicit. AD-8 now agrees with Phase 1 steps 3 and 5 rather
   than contradicting them.

Made by this pass, recorded under `## Amendment — 2026-08-19 (Compliance Architect)` at the
bottom of the plan:

4. **A1 — Phase 2 step 5's escalation rate limit** (plus the matching Implementation Note
   and verification P2-6). *"Rate-limit escalation to one per group per 15 min so a flapping
   group cannot re-arm forever"* does not produce the property it claims: one per 15 min is
   one per TTL, and a cooldown equal to the TTL lapses at the same instant the scope does,
   so a mid-band group re-arms on the next 30 s health tick — ~96% duty cycle, unattended,
   which with `max-scopes=50` is scoped DEBUG reassembling the fleet-wide DEBUG this feature
   exists to prevent. The rule is self-defeating rather than merely weak: the closer the
   cooldown is set to the TTL, the closer the duty cycle gets to 100%. Amended to measure the
   quiet period from **scope expiry** (`lastEscalation + ttl + cooldown`, 15 + 45) and to
   state the bound as a **duty cycle** of `ttl / (ttl + cooldown)` = 25%. P2-6 extended to a
   60-minute window plus a duty-cycle poll, because its old assertion passed on the defect.
   Matches what the branch already implements; **no code change requested.**

5. **A2 — AD-6's `720h` staged behind a new verification step P0-9.** The plan raises
   `retention_period` 168 h → 720 h (4.3× the horizon) in the same change as a volume
   reduction that is *projected, not measured*, and no step measured Loki ingest or growth.
   P0-6 records disk **size**, not growth; P0-8 proves only that the DEBUG/WARN split bites
   at T+25 h. The interaction with the still-unmeasured P0-6 blocker is what makes this worth
   amending rather than noting: Loki's store is the named volume `loki-data`
   (`docker-compose.yml:98`) on the same root filesystem of unknown size that ENOSPC'd on
   2026-06-30 and took Mongo with it — and a 720 h horizon does not reach steady state for
   **30 days**, so an over-optimistic projection fails a month after the release report
   closes, whereas 168 h announced itself within a week. Added **P0-9** (T+24 h and T+7 d,
   pre-ramp): measure the `loki-data` volume, take the T+7 d − T+24 h delta as the daily
   rate, project 30 days against `node_filesystem_avail_bytes`, and if it exceeds 50% of
   available disk drop `retention_period` back to `168h` while leaving the WARN/ERROR
   `retention_stream` at `720h`. AD-6 and the Open Item now mark the 720 h provisional and
   name the rollback: one line in the bind-mounted `loki/loki-config.yaml` plus
   `docker compose restart loki` — no rebuild, no application change. **The shipped config is
   unchanged**; this closes a hole in the Verification section, it does not ask for different
   code.

Both amendments are additions or corrections to specific sentences, clearly marked, with the
original wording quoted and the reason for the change stated. Neither rewrites the plan and
neither invalidates any part of the diff.

## For the Releaser

- Two pre-ramp gates are **open and cannot be closed from the diff**: **P0-6** (disk size —
  still unmeasured, and it has been unmeasured since the plan was written) and the new
  **P0-9** (Loki growth at T+24 h and T+7 d). P0-9 is deferred by construction; P0-6 is one
  command and should be run and recorded on the first deploy.
- First-deploy requirement on every box: the AD-2 bind mount means a host where
  `logging/log4j2.properties` is absent gets a *directory* created at that path and the app
  fails to start. Intended loud failure, documented in both the compose comment and the
  mounted file's header — but new.
- `BOT_LOG_LEVEL` must be set to `DEBUG` in staging's `secrets.env`/`.env`, or staging loses
  its default-visible per-bot detail.

---
---

# Compliance — LOG_VOLUME_TIERING **Phase 4**

*Second compliance pass, 2026-08-20. Phases 0–3 were ruled on above (PLAN_AMENDED) and are
not re-litigated here.*

Branch: `feature/log-volume-tiering` (HEAD `68cc9dd`)
Plan reviewed: `docs/plans/LOG_VOLUME_TIERING.md`, Phase 4 as landed at `68cc9dd`
(amended again by this pass)
Diff reviewed: `git diff 5350b12..68cc9dd` — `a268929` (4a), `e97dcb2` (4b), `118a10b` (4c),
`68cc9dd` (docs). 17 files, +3,410/−124.

Build: `mvn -o -pl bot-app test` on JDK 21 for the logging suites — **BUILD SUCCESS**,
61 tests, 0 failures.
`deploy.sh` untouched by all four commits (`git log 5350b12..68cc9dd --name-only -- deploy.sh`
is empty). The staged `TaiXiuMessages/*.js` deletions were left exactly as found.

## Verdict

**PLAN_AMENDED**

The diff implements Phase 4 faithfully. Every one of AD-22…AD-32 is present in the tree,
each of the four guard-test extensions is anti-vacuous (verified by mutation — see below),
and the resulting configuration does achieve the stated goal: what reaches Loki is now only
the aggregate tier.

I amended the plan for five things, none of which asks for different code. Four are
corrections to text that asserts something false about the system — most importantly a
**~2.2× arithmetic error** in Phase 4's central measurement, which is wrong in the safe
direction but would have made a *correct* P4-6 reading look like a misroute. The fifth
rules in **Dev's favour** on the one deliberate deviation. Details in
**Amendments to the plan (Phase 4)**.

## Phase-by-phase

### Phase 4a — The two-track appender split (`a268929`)
Status: **implemented** (one deliberate deviation on step 2 — Dev is right, plan amended)

- **Step 1a–1f, both shipped twins.** `logging/log4j2.properties` and
  `bot-app/src/main/resources/log4j2.properties` carry the detail appenderRef on
  `rootLogger` and `logger.app`, the `logger.wsparser` block, the `AsyncRolling`
  `ThresholdFilter`, the full `appender.detail.*` / `appender.asyncdetail.*` pair, and
  `Delete` `age` `7d → 14d`. Confirmed **byte-identical below the header fence** by direct
  comparison as well as by `Log4j2TwinConfigTest`. Both `.type = AppenderRef` lines present.
  The `blocking = false` inline comment required by 1f is there, in a boxed banner.
- **Step 2, the test copy — deviated, correctly.** See Drift/ruling below.
- **Step 3, compose.** `LOGGING_LEVEL_COM_VINGAME_WEBSOCKETPARSER=${WSPARSER_LOG_LEVEL:-INFO}`
  present with the AD-32 comment; `WSPARSER_LOG_LEVEL` added to `secrets.env.example`.
- **Step 4, guard tests.** All four extended as specified, including the sub-clauses that are
  easy to skip: `AsyncQueuePolicyTest` asserts `logger.wsparser` has **no** console and **no**
  `async` appenderRef (not merely that it has `detail`), asserts track 1 keeps
  `JsonTemplateLayout` (`:303`), and carries the AD-25(4) reasoning inside the assertion
  message as instructed; `Log4j2TestConfigShapeTest` adds all fourteen new graph keys to
  `SHAPE_KEYS`; `EvidenceRetentionEscapeTest` adds the track-2 `Delete` anchoring, the
  subdirectory assertion and the **interval-equality** assertion; `LoggingComposeWiringTest`
  pins the new variable and its `INFO` default.
- **Step 5, `CLAUDE.md`.** Two-track table, the AD-24 three-grep operator path, the
  `blocking = false` warning, the "DEBUG signals are file-visible, not Grafana-visible"
  correction, and the stale REST row fixed — Dev went further and corrected the whole
  `BotGroupController` table; I checked it against the controller's thirteen mappings and it
  is now accurate.

**Anti-vacuity verified by mutation.** I restored the pre-Phase-4
`logging/log4j2.properties`, `bot-app/src/main/resources/log4j2.properties`,
`promtail-config.yml`, `docker-compose.yml` and `evidence-shim/shim.py` into the working
tree and re-ran the five suites: **13 failures across all five classes**
(`AsyncQueuePolicyTest` 4, `EvidenceRetentionEscapeTest` 4, `Log4j2TestConfigShapeTest` 3,
`LoggingComposeWiringTest` 1, `LogRetentionPipelineTest` 1), then restored with
`git checkout HEAD --`. Step 4's "each must fail on the pre-4a tree" is satisfied for all
of them, not just asserted.

### Phase 4b — promtail positions volume + Loki comments (`e97dcb2`)
Status: **implemented**

`positions.filename: /promtail-positions/positions.yaml` with the Finding 2 comment; the
`promtail-positions` named volume declared and mounted on promtail only; `loki-config.yaml`
comments-only with **no value changed** (verified — the diff is +23 lines, all comment);
`LogRetentionPipelineTest.promtailPositionsSurviveARestart` pins the path **and** the mount
together and fails on `/tmp/positions.yaml`, as step 4 required.

### Phase 4c — The evidence shim promotes both tracks (`118a10b`)
Status: **implemented**

All eight sub-steps land. The three that were easiest to get wrong are right:
- **4c.2's "a missing detail directory returns `[]`".** `candidates()` distinguishes
  "directory absent" (silent `[]`) from "directory present but unlistable" (logged error),
  so 4c really is safe to ship before 4a — and `test_a_missing_detail_directory_is_a_no_op`
  additionally asserts the shim does **not** create the directory.
- **4c.3's `load_pending` back-compat.** `_sane_entry` accepts the legacy single-`liveName`
  string, maps it to the aggregate track, and *generates* the missing detail name the same
  way `record()` would rather than falling back to the reused source name — which would have
  pinned the wrong inode after a rollover.
- **4c.4's ordering.** The sweep partitions on the `detail` filename prefix, applies the two
  ages independently, and drains **all** detail candidates before touching any aggregate;
  `st_size` over-estimation and the `.promoted.json` promotion-time basis are untouched, as
  instructed.

`summary()` reports `detailDir`, `detailDirPresent`, `evidenceByTrack` and
`sourceFilesByTrack` — which is what makes P4-9's "zero means the shim is not seeing
`logs/detail/`" answerable. `selftest.py` gains all five required cases, and
`EvidenceRetentionEscapeTest.theShimsDetailDirectoryMatchesLog4j2s` couples the shim's
`DETAIL` constant to `appender.detail.fileName`'s leaf.

## AD-22 … AD-32: does the configuration achieve the goal?

Each AD is present in the tree, and the containment chain is closed rather than merely
plausible. The claim "only the aggregate tier reaches Loki" rests on four independent
mechanisms, all of which I verified:

1. **AD-23** — `logger.wsparser` with `additivity = false` and a single `AsyncDetail` ref
   removes the library from the console and from track 1 at the source. Name spelled
   exactly `com.vingame.websocketparser`, asserted in all three copies.
2. **AD-24** — the `ThresholdFilter` sits on `AsyncRolling`, and I confirmed against
   log4j-core 2.24.1 that this is effective *and* cheap:
   `AppenderControl.callAppender0` (`:130-135`) calls `isFilteredByAppender(event)` **before**
   `tryCallAppender`, so the DEBUG event is rejected before `AsyncAppender.append` offers it
   to the 8,192-entry queue. This is also why Phase 2's `Configuration`-level `ACCEPT` cannot
   defeat it: that beats the *level* check, not an appender filter.
3. **AD-25(1)** — track 2 is one level below promtail's non-recursive `__path__`, guarded by
   two tests.
4. **AD-27** — root stays on the console and track 1, which is a deliberate, bounded
   widening (Spring startup + third-party WARN/ERROR), and P4-4's per-logger histogram is the
   named catch for a future chatty third-party INFO logger.

**AD-25(4) is not a regression of the Phase 0 defect — verified against the sources**, since
that was the specific risk. `AsyncAppender.start()` (`:124`) assigns
`AsyncQueueFullPolicyFactory.create()`, which reads `log4j2.AsyncQueueFullPolicy` /
`log4j2.DiscardThreshold` from `PropertiesUtil.getProperties()` and nothing else; the
`AsyncAppender` builder exposes `blocking`, `bufferSize`, `includeLocation`,
`shutdownTimeout`, `errorRef`, `ignoreExceptions`, `blockingQueueFactory` — and **no** policy
or threshold attribute. So the discard threshold is genuinely JVM-wide and cannot
discriminate between two `AsyncAppender`s, exactly as AD-25(4) states.
`DiscardingAsyncQueueFullPolicy.getRoute` (`:48-61`) discards only levels *less specific
than* the threshold, so a track-2 INFO event on a full queue under `blocking = true` would
take `EventRoute.ENQUEUE` — a blocking put on the calling bot thread. Both halves of
AD-25(4)'s justification hold. **Ruling: correct, not a regression.**

**AD-29** — no value in `loki-config.yaml` changed; the `{level=~"DEBUG|TRACE"}` selector is
documented in-file as a tripwire. **AD-30/AD-31/AD-32** — all present as specified.

## The arithmetic of Phase 4's motivation — **does not check out**

Checked against `release.md` rather than against the plan's prose, as asked. **The 98.7%
share is right; the line *rate* built on it is ~2.2× too high.**

`release.md:417-443` reports two different windows in adjacent paragraphs: the P1-3 delta is
**2,022 INFO lines over 300 s**, and the per-logger table beneath it is "**Post-deploy** INFO
lines by logger", closed by the prose "**59 lines over ~11 minutes**". Phase 4's Findings
table relabels 3,385 / 1,000 / 4,402 / 59 as "lines / **300 s**" — while the 4,775 DEBUG
figure in the same column genuinely *is* per 300 s, which is how the two windows got merged.

The original is falsified directly by the release's own numbers: if ws-parser alone had
emitted 4,402 lines in 300 s, the total INFO delta over that window could not have been
2,022. Two independent derivations of the true rate agree to within 1%:
`(3,385 + 1,000) / 660 s = 6.64 lines/s`, and `2,022 × (4,402/4,461) / 300 s = 6.65 lines/s`.
Per bot: **0.0429**, not 0.0947.

Everything downstream is therefore ~2.2× pessimistic — AD-26's whole table, AD-25(4)'s
"1,893 lines/s", AD-28's "3.4–5.1 GB per pinned file" and the Goal's 6.5/65/98 GB/day. **All
of it errs in the safe direction and no shipped value changes**, because both of track 2's
caps are absolute — AD-26 says this itself ("the design does not depend on this estimate").
Corrected, coverage is *better* than promised (~12 h to 20k bots, ~8.6 h at 30k, against the
claimed 5.9/3.9 h) and AD-28's worst-case pin (~4.6 GB at 20k, ~6.9 GB at 30k) sits **inside**
the 12 GB evidence cap at both scales rather than exceeding it.

The one real consequence is operational and I amended it: **P4-6 told the releaser to expect
`R ≈ 3,700 B/s`**, when a correctly-working staging box will read ~5,000–6,000 B/s (the
`du` covers our own DEBUG too) and a prod-like box ~1,650 B/s. A releaser comparing a correct
measurement against that figure could reasonably diagnose a misroute. P4-2's `> 1000`
threshold survives on the corrected rate (~2,000 in 300 s), so no verification step actually
breaks.

Since the plan's motivating claim — "98.7% of INFO volume is the library" — is a **ratio over
one window**, the window error cancels and Phase 4's justification is untouched.

## Plan self-consistency after eleven new ADs

Re-read top to bottom. **One AD contradicted another, now fixed; no others.**

- **AD-4 vs AD-25(4).** AD-4 states flatly that "INFO+ blocks rather than being lost". That is
  now true of track 1 only. AD-25(4) names AD-4, but AD-4 had no forward pointer — while AD-5
  and AD-6 both received one — so a reader arriving at AD-4 first would conclude the shipped
  `AsyncDetail` was Drift 1 recurring. This is precisely the failure the shipped file comment,
  the test message and `CLAUDE.md` all try to prevent, and the plan was the one place that did
  not. Forward note added in place.
- **AD-14/15/16/19 vs AD-28** — no contradiction: AD-28's own heading reads "AD-14/AD-15/AD-16
  are extended, not replaced" and it walks each one, so they are discoverable. Left alone.
- **AD-5 vs AD-27, AD-6 vs AD-24, AD-20 vs AD-22** — each already carries an in-place note or
  an explicit reaffirmation. Consistent.
- **AD-8's demotion** vs Phase 4 — the session summaries are DEBUG and therefore land in track
  2 only. `CLAUDE.md` says so explicitly. Consistent.

### The superseded-step banner — count wrong, one step missing, none wrongly marked

Both banners said **ten**. The Verification banner then listed **twelve**, and the Corrections
section corrects a **thirteenth** — **P0-9**, absent from the list. Amended to thirteen with
P0-9 added.

I checked all thirteen individually: each is genuinely superseded. U-3 and P0-5 are corrected
for reasons *independent* of Phase 4 (a pre-existing 405, and a criterion that never passed),
which the Corrections entries say themselves — marked, but not mis-marked. And I walked every
Phase 0–3 step that was **not** marked, to catch a still-live step wrongly banner-marked or a
broken step wrongly left alone: **P0-3, P1-2, P1-4, P1-5, P1-6, P1-7, P2-5, P2-6** all grep
`logs/console.log` and all read the **INFO** tier, which stays in track 1 — correctly
unmarked. P2-5's escalation lines staying in track 1 is load-bearing for AD-24's "the pointer
stays in Loki" contract, and it does. **P3-4 / P3-5 / P3-8** stay correct for track 1 and are
merely narrower than P4-9 / P4-5, which supply the track-2 half. **No still-live step was
wrongly banner-marked, and no broken step was missed.** The banner now enumerates the
still-live `console.log` steps so the distinction is explicit.

## P0-5's corrected criterion — verified correct

Verified against log4j-core 2.24.1 rather than from the observation alone. For an `HOURLY`
frequency, `PatternProcessor.getNextTime` (`:201-209`) computes the next boundary and then
sets `nextFileTime` by `cal.add(Calendar.HOUR_OF_DAY, -1)` — **hard-coded minus one hour,
independent of `increment`** — and `updateTime()` promotes it to `prevFileTime`, which
`formatFileName` (`:269`, `:303`) stamps into the archive name. With `interval = 2,
modulate = true` the boundaries are even and the names are therefore **odd**, twelve unique
per day. The release's `console-2026-08-19-13.log` from a 14:00Z rollover is exactly this.
The replacement criterion — one archive per track per 2 h, no duplicate names, no parity
assertion — is correct and **would now pass on a working system**.

**One clause tightened.** The replacement also asked for "mtimes on even-hour boundaries". An
archive is created by *rename*, which preserves the mtime of its last written line, so the
mtime lands shortly **before** the boundary — on a quiet track 1 at prod INFO, up to the
5-minute `FleetRollupLogger` gap. Read strictly that is the same never-passes shape the step
was corrected for. Amended to "at or shortly before the end of the period the name
identifies".

## P4-6 — is it genuinely a gate?

**Yes.** It clears the bar that P1-4 and P2-6 failed. It measures an unknown (the real byte
rate, via a `du` delta over 600 s), compares it against **absolute numeric thresholds**
(projected per-2 h file > 5 GB, or projected per-day > 60 GB), and a failure has a **stated,
executable consequence**: do not ramp until the `10GB` cap is lowered or
`WSPARSER_LOG_LEVEL=WARN` is set — both bind-mount edits plus a restart. It is cross-named as
a gate in AD-26, AD-28 and the Open Items, and it carries a recording obligation (`R` and `N`
into the release report) that feeds the "re-run before ramping past ~5,000 bots" item. This is
not an observation dressed as a gate.

Two caveats, recorded rather than amended:
- On the corrected B1 rate the thresholds do not bind until roughly **44,000 bots**
  (at 30k: 2.32 GB per 2 h file, 27.8 GB/day — both comfortably inside). Even on the plan's
  own inflated numbers it only binds at ~30k. So near-term it functions as a
  measure-and-record obligation rather than a likely blocker. That is acceptable for a gate
  whose job is to catch a projection being wrong, but it should not be mistaken for a check
  that will exercise itself.
- The remedy "lower the `10GB` cap" protects **disk**, not coverage — lowering it reduces the
  retained window further. `WSPARSER_LOG_LEVEL=WARN` is the remedy that addresses the cause.
  Worth knowing when the gate fires; not worth an amendment.

## Drift

### Drift 8 — Phase 4a step 2's "verbatim" applied to the `Delete` block (plan wrong, amended)

Plan 4a step 2 said to apply 1a–1d to `log4j2-test.properties` "verbatim except" the two
file paths. 1d hard-codes `appender.detail.strategy.delete.basePath = /app/logs/detail`, so
"verbatim" writes an `/app` path into the one file whose entire purpose is that no appender
in it names a container path a build machine cannot create.

**Ruling: Dev is right, the plan step was wrong, and the step is amended.** Three independent
reasons, all falsifiable:
1. The test copy's *existing* shape already contradicts "verbatim" — track 1's retention block
   was replaced there by `appender.rolling.strategy.max = 2` back in Phase 1, and the file's
   own header names retention as a documented reason to differ.
2. `Log4j2TestConfigShapeTest.SHAPE_KEYS` **deliberately excludes** the retention keys
   ("the file's documented reason to differ"), so the plan's own guard was never going to
   check what step 2 asked for.
3. Dev's replacement — `appender.detail.strategy.max = 2` plus a new
   `theTestCopyNeverNamesTheContainerPath` that walks **every** property value and rejects
   `/app/` — rules out the class rather than the key, which is strictly stronger than the step
   as written. `theOnlyDifferenceIsTheOutputPath` was correctly extended to enumerate the
   second intended difference, so a *third* difference still cannot slip in unremarked.

This is a plan defect, not code drift: a correct implementation of the step as written would
have been worse than what shipped.

## Out-of-scope changes

None that count against the diff.

- **`CLAUDE.md`'s `BotGroupController` table** was corrected more thoroughly than step 4a.5
  asked (which named only the stale `GET /` row). Dev added `/sort-keys`,
  `/{id}/schedule-restart`, `/{id}/health`, `/{id}/status` and fixed `PATCH /` → `PATCH /{id}`.
  I checked all thirteen mappings against the controller: the table is now accurate and was
  not before. In-scope-adjacent and correct.
- **`docs/reviews/LOG_VOLUME_TIERING/release.md`** (+911) landed in `68cc9dd` alongside the
  Phase 4 plan text. That is the Releaser's and Architect's artefact for the Phase 0–3 ship,
  not Dev output, and it is the evidence Phase 4 is built on. Not assessed as diff scope.
- The staged `TaiXiuMessages/*.js` deletions and the modified `deploy.sh` in the working tree
  are **not** from this pipeline and were left exactly as found.

## Observations (not drift, not blocking)

- **`evidence-shim/selftest.py` is wall-clock flaky.**
  `test_deferred_and_tail_passes_fire_at_their_deadlines` fails whenever it runs within
  ~300 s of a 2 h rollover boundary: it advances the clock by 300 s and expects only the
  `deferred` pass to be due, but `tail` fires at `next_rollover + 120 s`, which is under 300 s
  away for ~4% of wall-clock time. Reproduced live (4 failures) and reproduced identically at
  **`5350b12`**, i.e. **pre-existing and not Phase 4's**. It matters because
  `EvidenceShimSelfTestRunnerTest` runs this suite in the build and cannot swallow a failure,
  so ~4% of builds go red for no reason. QA/Reviewer territory; recorded here so it is not
  mistaken for Phase 4 breakage when it next fires.
- **`blocking = false` and the status logger.** With `blocking = false`, a full `AsyncDetail`
  queue takes `AsyncAppender.append`'s else-branch, which calls `error(...)` **per dropped
  event** — unlike `DiscardingAsyncQueueFullPolicy`, which warns once and then discards
  silently. At the volumes AD-25(4) contemplates, a sustained full queue would produce
  per-event StatusLogger output to stderr and thence to the capped docker json-file. The
  decision is still right; the noise profile under sustained saturation is a Reviewer-level
  detail nobody has costed.

## Amendments to the plan (Phase 4)

All five are in `## Amendment — 2026-08-20 (Compliance Architect, Phase 4)` at the bottom of
`docs/plans/LOG_VOLUME_TIERING.md`, with short in-place pointers at the affected lines so a
reader acting on those lines sees the correction. **None asks for different code and no
shipped value changes.**

- **B1 — the ws-parser line rate is derived from the wrong window (~2.2× too high).** 4,402
  and 59 are ~11-minute counts, not 300 s; only the 4,775 DEBUG figure is per 300 s. Corrected
  to 6.64 lines/s / 0.0429 per bot/s, with both derivations shown, a table of every downstream
  figure and its corrected value, and an explicit statement that the 98.7% share — a ratio
  over one window — is unaffected, as are both absolute caps. In-place notes on AD-26 and on
  **P4-6**, whose expected `R` was the one place the error had an operational consequence.
- **B2 — AD-4 narrowed by AD-25(4)**, with a forward pointer matching the convention already
  used on AD-5 and AD-6, and the log4j-core 2.24.1 verification recorded so the ruling is not
  re-opened a third time.
- **B3 — the superseded-step banner** corrected from "ten" to thirteen, `P0-9` added to the
  list, and the still-live `console.log` steps enumerated so the boundary is explicit.
- **B4 — Phase 4a step 2's "verbatim"** amended to exclude the `Delete` block, ruling in
  Dev's favour and recording the anti-`/app` test as the stronger guard.
- **B5 — P0-5's mtime clause** tightened from "on even-hour boundaries" to "at or shortly
  before the end of the period the name identifies", since rename preserves the last-write
  mtime.

## For the Releaser (Phase 4)

- **`mkdir -p logs/detail` on the box before `docker compose up`.** The plan's deploy note.
  If `AsyncDetail` cannot start, the JVM comes up with **no logger at all**.
- **P4-3 and P4-5 must be run on staging with `BOT_LOG_LEVEL=DEBUG`** — on a prod-like INFO
  instance they pass vacuously.
- **Expect one final Loki re-ingest** on the deploy that lands 4b (no saved positions yet),
  ~2 GB. Take P0-9's baseline *after* it settles.
- **P4-6's expected value is amended.** A correct staging reading is ~5,000–6,000 B/s at
  155 bots (the `du` includes our own DEBUG), not the ~3,700 the plan used to state. Do not
  read a lower-than-expected number as a misroute — use P4-5 and P4-2 to decide that.
- Pre-ramp gates still open and not closable from the diff: **P0-6 on `Prod-Bot`** (never
  measured), **P0-9** (re-baselined behind 4b), **P4-6**.

---
---

# Compliance — LOG_VOLUME_TIERING **Phase 4, remediation re-verification**

*Third compliance pass, 2026-08-20. Phases 0–3 (PLAN_AMENDED) and the first Phase 4 pass
(PLAN_AMENDED) are ruled on above and are not re-litigated.*

Branch: `feature/log-volume-tiering` (HEAD `3718a45`)
Plan reviewed: `docs/plans/LOG_VOLUME_TIERING.md` — **Dev's own amendments at `3718a45`**,
plus this pass's amendments
Diff reviewed: `git diff 68cc9dd..3718a45` — `588b664` (shim inode accounting), `e03029f`
(`AsyncQueueMetrics` + `LogQueueSaturated`), `f8bfd17` (alert-rule test), `74b0455` (config
comments + `bufferSize`), `3718a45` (plan/CLAUDE.md/promtail comments). 17 files,
+2,653/−117.

Build: `mvn -o -pl bot-app test` on JDK 21 — **BUILD SUCCESS**, exit 0. The six suites that
carry this pass's changes: `AlertRuleMetricsTest` 7, `AsyncQueueMetricsTest` 6,
`ShippedLog4j2ConfigRoutingTest` 7, `AsyncQueuePolicyTest` 8, `Log4j2TwinConfigTest` 3,
`Log4j2TestConfigShapeTest` 5 — 36 tests, 0 failures, 0 skipped.
`deploy.sh` is untouched by all five commits. The staged `TaiXiuMessages/*.js` deletions
were left exactly as found.

## Verdict

**PLAN_AMENDED**

The code in the remediation range is faithful and I am asking for **no code change**. The
work was in the plan, and the brief's instruction to assume a fourth contradiction was
correct — I found three, one of them serious:

- **Step 0c would have taken Loki down.** It instructs a config key that does not exist in
  the block it names, in an image where Loki parses config strictly. Verified against
  `grafana/loki:3.3.2`. It was also unnecessary, and its one command returns 400.
- **The reconciled AD-26 table has one cell still on the stale rate** (the 2,000-bot live
  file). The two cells that carry the argument, 20k and 30k, are correctly rebuilt.
- **"No Java anywhere" is no longer true** — `AsyncQueueMetrics` is a new production
  `@Component` in a phase whose Goal forbids Java and whose steps do not mention it.

Two of Dev's five amendment areas are correct as written (AD-30's substance, the doc
corrections); the caps-vs-gate reconciliation is directionally right but delegates to a
gate that cannot fail on it. All five are re-derived below.

**Process note, not a defect in the work:** amending the plan is this role's remit, not
Dev's. Dev's amendments were re-derived from primary sources rather than accepted, and are
ratified — with corrections — by C1–C5. Recording it because it is the second pipeline
convention this feature has bent, and because it is exactly why the 2,000-bot cell and the
"14 days" survived: an author correcting their own text checks the sentences they changed,
not the ones they carried over.

## Phase-by-phase (remediation range)

### Dev plan amendment 1 — AD-26's arithmetic, reconciled into one table
Status: **drifted (plan text), corrected by C1** — the conclusion is sound, one input is not

Re-derived independently at B1's corrected 0.0429 lines/s/bot × 250 B × 7,200 s:

| Cell | Plan | Re-derived | |
|---|---|---|---|
| live file @ 2,000 | ~0.35 GB | **0.154 GB** | ✗ stale rate (0.0947 × 2,000 × 250 × 7,200 = 341 MB) |
| live file @ 20,000 | ~1.55 GB | 1.544 GB | ✔ |
| live file @ 30,000 | ~2.32 GB | 2.317 GB | ✔ |
| total @ 30k | ~34.3 GB | 10 + 10 + 2.32 + 12 = 34.32 | ✔ |
| total @ ≤ 2k | ~32.4 GB | **32.2 GB** | ✗ follows the cell above |
| share of 66.96 GiB free | ~48% / ~51% | 48.0% / 51.2% | ✔ (and robust: all caps are **GiB**, so it is like-for-like) |
| reviewer's "37 GB / 55%" | correct pre-B1 | 10 + 10 + 5.1 + 12 = 37.1 = 55.4% | ✔ Dev's account of the delta is right |

So the table **is** built on the corrected rate except for the 2,000-bot row, which is the
pre-B1 table's `341 MB` carried over. It is in the safe direction and changes neither
percentage nor any conclusion — but it is the third generation of the same error, which is
why I corrected it in place rather than noting it.

Stale-rate residue deliberately **left**, per B1's scoping of comment corrections as a
follow-up: the `~17 ms / ~2.2 s` rows in the `immediateFlush` note (corrected values ~38 ms
at 20k, ~1.5 s on staging), `shim.py`'s `3.4-5.1 GB` docstring, and the shipped config
comments. Correcting the plan while the shipped comment says otherwise trades one
inconsistency for another; the magnitudes decide nothing.

### Dev plan amendment 2 — the caps-vs-gate reconciliation
Status: **drifted (plan text), corrected by C2** — the contradiction is real and sharper
than the brief's framing

Confirmed, and my own previous number was wrong too. Both P4-6 thresholds reduce to the
**same** rate — 5 GB ÷ 7,200 s = 60 GB ÷ 86,400 s = **694 KB/s** — which the corrected rate
does not reach until **~65,000 bots** prod-like (~20,000 with `BOT_LOG_LEVEL=DEBUG`, where
track 2 also carries our own DEBUG). My last pass said ~44,000; that was an arithmetic slip
and is corrected in the plan and here.

P4-6 **can** still fail, and its consequence **is** executable — both bind-mount edits plus
a restart, and I re-confirmed the caps are one line each. What does not hold is the
delegation. Two reasons, and they are opposite errors:

1. **The ceiling is nearly scale-free.** 32 of the ~32–34 GB are absolute caps; only the
   live-file term moves with bot count, by ~2 GB across the whole 2k→30k range. So "accepted
   at ≤ ~5,000 bots" states the acceptance on the wrong axis — the quantity that decides it
   is **free disk**, and on a 40 GiB box the caps alone exceed half the volume at *any* bot
   count.
2. **P4-6 measures a write rate and never looks at free disk**, so it returns PASS on a
   question it was never asked between 5,000 bots and ~65,000.

Also: of the three offered remedies only **cutting a cap** lowers the ceiling.
`WSPARSER_LOG_LEVEL=WARN` lowers realized usage and the live-file term — worth doing, and it
is what buys back coverage — but cannot make a 32 GiB nominal ceiling fit under half of a
40 GiB volume. As written the sentence offers a remedy that cannot reach its own stopping
condition.

**C2 adds "Gate 2" to P4-6**: `df` the volume, sum the caps plus the projected live file,
abort above 50% of free — the same rule P0-9 already applies to Loki, and the only form in
which AD-26's sentence is executable.

### Dev plan amendment 3 — AD-30 corrected
Status: **implemented** (one wrong input, corrected by C1)

The claim is now true as written, and I verified the containment rather than reading it.
All four mechanisms hold: `logger.wsparser` has `additivity = false` and a single
`AsyncDetail` ref, so it reaches neither the console nor track 1; promtail's
`__path__: /logs/*.log` is non-recursive and track 2 is one level down; `loki-config.yaml`
is comment-only; and `ShippedLog4j2ConfigRoutingTest` **starts** the shipped configuration
and asserts the routing rather than reading the file as text. `CLAUDE.md`,
`docker-compose.yml` and `promtail-config.yml` all now state the dated form, and
`docker-compose.yml`'s `EVIDENCE_DETAIL_MAX_AGE_DAYS` comment no longer says "only place".

The persistence half — already-ingested lines survive to `4b deploy + 30 d` — is right, and
is the correction that mattered.

**One input is wrong.** "Up to **14 days** of token material" is track 1's *post*-Phase-4
`age`, which lands in this same deploy; what is on the box when promtail starts was written
under the pre-Phase-4 `ifLastModified.age = 7d` (`git show 5350b12:logging/log4j2.properties`),
so **7 days is the ceiling** — and this plan's own Finding measured **~12 archives ≈ 24 h**.
The plan contradicted itself between those two numbers. Corrected in AD-30 and the
Implementation Note. It errs alarming, and no action changes; but AD-30 is now a *dated*
claim whose date the release report must state, so its inputs have to be right.
**`CLAUDE.md` repeats "up to 14 days" and is outside this role's write scope — it needs the
same one-word fix (see For the Releaser).**

### Dev plan amendment 4 — Phase 4b pre-deploy steps 0a–0c
Status: **0a/0b implemented; 0c drifted and not executable — rewritten by C4**

**0a — correct, ordered, executable.** Both escape claims verified in the shipped files:
`logs/pre-phase4/` is outside promtail's non-recursive `__path__`, and outside track 1's
`Delete` (`basePath = /app/logs`, `maxDepth = 1`, glob `console-*.log`). The warning not to
move `logs/console.log` is right, and "nothing will ever sweep it" follows from the same
two facts. One clause qualified: *"still hardlink-promotable"* is true only **by hand** —
the shim's aggregate track is `(logs_dir, "console*.log")` and `candidates()` never
recurses, so moved archives are invisible to automatic promotion.

**0b — correct.** One entry, `/logs/console.log`, is the right expectation, and the two
failure readings (more than one / empty) are the right diagnoses.

**0c — three independent defects, all verified against `grafana/loki:3.3.2` (the pinned
image) with the shipped `loki/loki-config.yaml`:**

1. **It kills Loki.** `deletion_mode` under the `compactor` block fails validation:
   `failed parsing config: field deletion_mode not found in type compactor.Config`. In
   Loki 3.x it is a **`limits_config`** setting (`-compactor.deletion-mode`). The step ends
   in `docker compose restart loki`, so following it verbatim leaves Loki dead — on a box
   where one Compose project holds the whole observability stack.
2. **It is unnecessary.** That flag's default *is* `filter-and-delete`, and
   `compactor.retention_enabled: true` is already shipped, so the delete API is live on the
   current config. Against the **unmodified** config a delete request returns `204` and
   appears in `GET /loki/api/v1/delete`.
3. **The command returns 400.** Busybox `wget` does not percent-encode the space in
   `|="Agency token"`; I captured the request it emits and the raw space lands in the HTTP
   request line. Loki answers `400 Bad Request`.

Consequently the "**revert `deletion_mode` afterwards**" advice is unfounded — there is
nothing to revert, and the fact it was hiding is that **this delete API has been reachable
by anything that can reach port 3100 since RESILIENCE_HARDENING P0c**.

C4 rewrites 0c to need **no config change and no restart**: host `curl` against the
published `3100:3100` (as P4-5 already does) with `--data-urlencode`, plus the cancellation
path (`DELETE …?request_id=` → `204`, valid until `delete_request_cancel_period`, default
**24 h**, which is also why nothing appears to happen on day one). Verified end to end:
`204` → listed → cancelled → empty.

### Dev plan amendment 5 — the doc corrections
Status: **implemented**

- **`immediateFlush = false` risks the last ≤ 8 KB** — correct, and the rate-inverse framing
  is the right one: the bound is bytes, so the *time* it spans grows as the log rate
  collapses. The **AD-21 interaction is the real find** and it is stated correctly: an
  unclean kill is precisely what the shim's boot retro-promotion triggers on, so the shim
  can pin a file whose final, quietest 8 KB never reached disk. (The `~17 ms / ~2.2 s` rows
  themselves are on the stale rate — see amendment 1.)
- **`appender.detail.bufferSize` declared, not inherited** — present in **both** shipped
  twins, byte-identical below the header fence (`Log4j2TwinConfigTest` green), and pinned by
  `AsyncQueuePolicyTest`. 8192 is log4j2's own `RollingFileAppender` default, so **no
  behaviour changes** — it converts an inherited default into a findable decision, which is
  exactly what the note claims.
- **16384 vs 8192 stated as deliberate** — verified in the shipped twins, in `CLAUDE.md`, and
  in the alert rule's reason for being a ratio rather than an absolute.

### New code — `AsyncQueueMetrics` + `LogQueueSaturated`
Status: **out-of-scope as the plan stood; ruled IN SCOPE and regularized by C5**

**Ruling: in scope, not scope creep — and the plan was the thing that was wrong.**

It closes a hole *this phase opened*. AD-25(4) chose `blocking = false` for track 2 in the
same change that made track 2 the sole home of wire-level forensics, and log4j2's own report
of a drop (three `stderr` lines via `DefaultErrorHandler`, then one per five minutes,
uncounted) reaches neither `console.log`, nor Loki, nor Alertmanager. An engineer reading
`detail.log` after an incident therefore had no way to learn it has holes in it. That is a
defect in the shipped design, not a feature request. It is also the finding my own previous
pass recorded as an un-costed observation, so it is remediation, not invention.

Proportionality checks, all of which it passes: ~270 lines in one class plus one rule; off
the hot path (10 s virtual-thread sampler); degrades to a single WARN when no
`AsyncAppender` exists rather than failing startup; gauges resolve from the **live**
configuration on each read, so a reconfiguration cannot leave them reporting a stopped queue
(the failure mode that looks most like health); meters deliberately not `bot_`-prefixed —
verified `BotMdcTagsMeterFilter` only rewrites names starting `bot_`, so the stated reason is
true; and it is honest that it measures **saturation, not an exact drop count**, for library
reasons that are the same ones AD-9 already established.

What was genuinely wrong is that **no AD and no step contained it**, while the Goal said
"config only — no Java anywhere". Dev documented it inside AD-25(4)'s amendment, which is the
right content in the wrong place: a reader of the phase plan would have found undeclared
production code. C5 corrects the Goal in place, adds **Phase 4d** describing what shipped,
adds the mapping row, and adds **verification P4-13** — every other Phase 4 artefact has a
step that fails if it did not ship, and this one had none.

### The two deliberate deviations
Status: **both ruled correct**

1. **`appender.detail.bufferSize` pinned in `AsyncQueuePolicyTest`, not added to
   `SHAPE_KEYS`.** Correct. `SHAPE_KEYS` compares the shipped file to
   `log4j2-test.properties`, which deliberately runs tiny buffers (1024) — adding the key
   would have forced the test copy to 8192 and defeated its purpose. The precedent is
   already in the file: `appender.async.bufferSize` is likewise absent from `SHAPE_KEYS`
   while the test copy runs 1024, so buffer sizes are excluded **by design**, exactly as Dev
   says. `AsyncQueuePolicyTest` asserts against the shipped file, which is the right home.
2. **`audience: internal` exempted in `AlertRuleMetricsTest`.** Correct and required.
   `AlertRouter` routes `INTERNAL` to the ops room by construction and never resolves a
   product, so AD-V3's product-label requirement is meaningless for such a rule; enforcing it
   would forbid application-sourced infrastructure alerts outright. **Mutation-verified that
   the exemption is load-bearing rather than a blanket loosening:** flipping
   `LogQueueSaturated` to `audience: product` makes the suite **fail** (7 tests, 1 failure),
   and the working tree was restored immediately after. The added `checked >= 5` floor stops
   the exemption ever emptying the loop — the anti-vacuity habit this feature has needed
   repeatedly.

### Shim inode accounting (`588b664`)
Status: **implemented** — matches AD-28 as Dev amended it

Per-`(st_dev, st_ino)` grouping with every name attached; `promoted` is the **oldest** stamp
among a group's names, so a second name added by a later pass cannot refresh its age;
eviction unlinks **all** names of the chosen inode, which is the only operation that frees
blocks; `total -= group.size` stays unconditional, so two concurrent sweeps converge rather
than over-evicting. The explicit three-deep `_eviction_order` key `(promoted, mtime, path)`
is the right fix for the stated defect — every file of one pass shares a promotion stamp, so
the tie-break decides the ordinary case, and the previous bare-tuple sort fell through to
**size ascending**, i.e. shed the live-file link, the post-incident tail, first. `st_size`
over-estimation and the `.promoted.json` basis are untouched, as the step required.

## Drift

### Drift 9 — Phase 4b step 0c is not executable and is destructive of Loki (plan wrong, amended C4)
Verified three ways against the pinned image. No code change; the plan was the artefact at
fault. See amendment 4 above.

### Drift 10 — the caps-vs-gate reconciliation delegates to a gate that cannot fail on it (plan wrong, amended C2)
P4-6 remains a genuine gate for what it does measure; it is now given the second half AD-26
delegates to it. No code change.

### Drift 11 — Phase 4 ships Java in a phase whose Goal forbids it (plan wrong, amended C5)
Ruled in scope; regularized as Phase 4d with a verification step. No code change.

## Out-of-scope changes

None that count against the diff.

- `AsyncQueueMetrics.java` / `AsyncQueueMetricsTest.java` / the `LogQueueSaturated` rule —
  assessed above and **ruled in scope**, now declared in the plan as Phase 4d.
- `CLAUDE.md` (+68/−) — tracks the corrections; accurate except the "up to 14 days" it
  inherits from AD-30 (C1).
- `docs/reviews/LOG_VOLUME_TIERING/qa.md` (`2a088c3`) — QA's artefact, not Dev output.
- **`deploy.sh` is not in the diff.** Re-confirmed across all five commits.
- Working-tree `deploy.sh`, `docs/reviews/LOG_VOLUME_TIERING/review.md` and
  `docs/reviews/VIPTALK_ALERTING_V2/release.md` modifications, and the staged
  `TaiXiuMessages/*.js` deletions, are **not** from this pass and were left exactly as found.

## Verification section — achievable?

Yes, with the two additions this pass makes.

- **P4-13 is new (C5)** and is the only step capable of failing if Phase 4d did not ship.
- **P4-6 gains Gate 2 (C2)**, the ceiling-vs-free-disk check, without which AD-26's
  acceptance sentence has no executable form.
- **Phase 4b 0a/0b are executable verbatim**; **0c was not and now is**, and it no longer
  requires a Loki restart, which removes the one step in this plan that could have taken the
  observability stack down while executing a mitigation.
- P4-2's stale parenthetical (`≈ 4,400 in 300 s`) is corrected in place to ≈ 2,000 — B1 had
  recorded it in a table but left the step itself saying the old number, which is precisely
  the shape that misleads a releaser at 3 a.m.
- Still open and not closable from the diff: **P0-6 on `Prod-Bot`**, **P0-9**, **P4-6**
  (now both gates).

## Amendments to the plan

All five are recorded in `## Amendment — 2026-08-20b (Compliance Architect, Phase 4
remediation pass)` at the bottom of `docs/plans/LOG_VOLUME_TIERING.md`, with short in-place
notes at each affected line. **None asks for different code and no shipped value changes.**

- **C1** — AD-26's 2,000-bot live-file cell corrected 0.35 → **0.15 GB** (total ≤ 2k
  32.4 → 32.2 GB; percentages unchanged), a note that all caps are **GiB**, and AD-30's
  "up to 14 days" corrected to **7 days** (~24 h measured) in AD-30, step 0a and the
  Implementation Note.
- **C2** — the caps-vs-gate sentence annotated in place, and **P4-6 Gate 2** added
  (`df` + caps + projected live file vs 50% of free); the remedy list corrected so that
  only cutting a cap is claimed to lower the ceiling; my own "~44,000 bots" corrected to
  **~65,000** (~20,000 with DEBUG).
- **C3** — AD-30's substance verified and left as Dev wrote it.
- **C4** — Phase 4b step 0c rewritten: no config change, no restart, host `curl` with
  `--data-urlencode`, the cancel path and the 24 h cancel period, and the standing
  availability of the delete API recorded as the fact the "revert" advice was hiding.
- **C5** — the Goal's "no Java anywhere" corrected in place, **Phase 4d** added describing
  the shipped `AsyncQueueMetrics` + `LogQueueSaturated`, a mapping row, and verification
  **P4-13**; both of Dev's deliberate deviations ruled correct, the `audience` one
  mutation-verified.

## For the Releaser (updated)

- **Step 0c in the plan you may have read before 2026-08-20 would have killed Loki.** Use
  the rewritten one. It needs **no `loki-config.yaml` change and no `docker compose restart
  loki`** — the delete API is already enabled on the shipped config.
- **0a is the step that matters and it must run before `docker compose up`.** 0c is
  optional, destructive, and the user's call.
- **P4-6 now has two gates.** Gate 2 (`df` + caps vs 50% of free) is the one likely to
  bind on a small-disk box; Gate 1's thresholds do not bind below ~65,000 bots prod-like.
- **State the AD-30 end date as `4b deploy + 30 days`**, and record how many archives 0a
  moved. The material at risk is **≤ 7 days**, ~24 h in the only measurement we have — not
  the 14 days the older text said.
- **One-line follow-up for Dev:** `CLAUDE.md`'s evidence-shim section still says the
  re-ingest carries "up to 14 days" of that material. Outside this role's write scope; the
  plan is corrected.
- Pre-ramp gates still open: **P0-6 on `Prod-Bot`** (never measured), **P0-9**, **P4-6**.
