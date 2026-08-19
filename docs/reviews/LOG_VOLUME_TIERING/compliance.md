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
