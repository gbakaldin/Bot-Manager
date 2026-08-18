# Compliance — VIPTALK_ALERTING_V2

> Two review passes, appended in order. **Part 1** (below) covers Phases 1–2 and
> is closed — verdict PLAN_AMENDED, its fix pass has landed, do not re-litigate.
> **Part 2** (`## Part 2 — Phases 3, 4, 5 and 6`, at the bottom) covers the rest.

---

# Part 1 — Phases 1 and 2 (2026-08-17)

Branch: `staging`
Plan reviewed: `docs/plans/VIPTALK_ALERTING_V2.md` (untracked working-tree copy, as amended below)
Diff reviewed: `git diff 9dfd1d8..HEAD` — commits `56c461a`, `3c02ef4` (Phase 1),
`d0f07d6`, `adb099e`, `c841c32` (Phase 2). Phases 3–6 deliberately not implemented.
Build: `JAVA_HOME=…/openjdk-21.0.2 mvn clean install` → **BUILD SUCCESS, 873 tests, 0 failures, 0 errors.**

## Verdict

PLAN_AMENDED

The **code is accepted as faithful** — every Phase 1 and Phase 2 step is delivered
as specified, and AD-V1 through AD-V7 are honoured. The amendment is entirely
plan-side: three sections of the plan were factually wrong or stale (two of them
named in the review brief, one found here), and one forward-looking decision that
AD-V6 and AD-V9 leave unspecified is now recorded as a Phase 6 open item.

No code drift was found, so no send-back.

## Phase-by-phase

### Phase 0 — Spike
Status: implemented
Notes: `docs/reviews/VIPTALK_ALERTING_V2/spike.md` answers Q1 (VipTalk ignores
query params → AD-V8 dead) and correctly defers Q2 (node-exporter) to Phase 5.
The plan's Open Items recorded the Q1 result but its Phase 6 *steps* did not —
amended, see below.

### Phase 1 — `product` on every routable meter, plus the per-environment gauges
Status: implemented (8/8 steps)

| Step | Where | Verdict |
|---|---|---|
| 1 `BotMdc.PRODUCT`, `set(...)` param, `clear()`, 3-arg `setGroupContext` overload | `bot-api/.../common/logging/BotMdc.java` | done; 2-arg form delegates with `null`, so all 9 group-level call sites are byte-for-byte unchanged |
| 2 `Bot.initialize()` passes the product | `bot-engine/.../bot/core/Bot.java:219`, helper `productCode()` `:257` | done; null-tolerant as the plan required |
| 3 `BotMetrics.mdcTags()` + javadoc cardinality note | `BotMetrics.java:105`, javadoc `:40-59` | done, including the functional-dependency argument |
| 4 `BotMdcTagsMeterFilter.map` | `:79` | done; aggregate exclusion list otherwise unchanged (AD-V2) |
| 5 `BotGroupRuntime` gains `groupName` + `product` | `:57-68`, 6-arg ctor `:152`, 4-arg delegates | done; `groupName` is Phase 4's dependency and is already threaded in |
| 6 gauge-support records + two new accessors | `BotGroupBehaviorService.java:1342-1382`, `countManagedBotsByEnv()` `:1470`, `countOpenWsByEnv()` `:1489` | done; all four records extended exactly as specified, `EnvKey(environmentId, product)` added |
| 7 `InfoGaugeRefresher` — new tags on four builders + two new MultiGauges | `:157-204`; names added to `AGGREGATE_METER_NAMES` | done |
| 8 test updates | `BotMdcTest`, `BotMetricsTest`, `BotMdcTagsMeterFilterTest`, `InfoGaugeRefresherTest`, `InfoGaugePrometheusScrapeTest`, `BotGroupBehaviorServiceTest` | done |

Resulting label sets match the plan's "Resulting label sets" block exactly.

**AD-V1 zero-new-time-series contract — holds.** Checked three ways:
- *Source of the label.* `product` is read from `Game.getProductCode()` for
  bot-scoped and game-scoped rows and from `Environment.getProductCode()` (via
  `BotGroupRuntime.product`) for env-scoped rows. Both are functionally
  determined by labels already on those series (`gameId` → `environmentId` →
  `product`), so existing series gain a label rather than splitting.
- *No new split from partial labelling.* `addIfPresent`/`addTagIfPresent` skip a
  null or empty MDC value rather than emitting `product=""`. A bot whose `Game`
  predates `productCode` therefore keeps its exact pre-change identity. The
  group-level `setGroupContext(2-arg)` path stays product-less and remains the
  only caller today, so `bot_creation_failures_total` is unchanged — this mirrors
  the pre-existing `gameId`/`gameName` split shape and introduces no new one.
- *Pinned by test.* `BotMetricsTest.productLabel_addsNoNewTimeSeries_beyondTheExistingIdentitySet`
  asserts N increments under one bot's MDC yield exactly one counter, plus
  `absentProductInMdc_omitsTheLabelRatherThanEmittingEmpty`.

**AD-V2 — holds.** `ObservabilityConfig` is untouched; the six fleet aggregates
remain unlabelled. Both new gauge names were added to `AGGREGATE_METER_NAMES`
(belt-and-braces — neither matches the `bot_` prefix the filter keys on) and
`alert_dispatch_total` is likewise outside the prefix, exactly as AD-V5 requires.

### Phase 2 — Audience routing in the app
Status: implemented (9/9 steps)

| Step | Where | Verdict |
|---|---|---|
| 1 `AlertAudience` + `fromLabel` defaulting to INTERNAL | `model/AlertAudience.java` | done (AD-V4) |
| 2 `AlertRegister` | `model/AlertRegister.java` | done |
| 3 `Alert` grows `audience` + `publicSummary`; 5-arg ctor defaults `(PRODUCT, null)` | `model/Alert.java:24-52` | done, verbatim to the plan |
| 4 `format(Alert, AlertRegister)`; `format(Alert)` delegates to TECHNICAL | `AlertMessageFormatter.java:56-112` | done; CUSTOMER renders marker + product + instance, then `publicSummary` verbatim — no severity word, no body, no bullet list, no `— via` trailer, no environment id |
| 5 `AlertRouter` pure function | `service/AlertRouter.java` | done; zero-Spring-context test class |
| 6 `.or(rooms::opsRoom)` deleted; group by identical text, one POST per distinct text | `AlertService.send` `:79-108` | done; the catch-all fallback is gone |
| 7 `alert_dispatch_total{outcome, reason, product}` | registered in `AlertService.count` `:155` | done |
| 8 webhook reads `audience` + `public_summary`, batching re-keyed on `(product, audience)` | `AlertmanagerWebhookService:97-181` | done |
| 9 `viptalk.customer-notices-enabled=false` in properties, compose, `secrets.env.example` | `adb099e` | done |

**AD-V3 — holds.** Routing is by explicit audience; the ops room is no longer a
fallback for anything unroutable. The two-hop product resolution (`product` label
→ `environmentId` → Mongo) is preserved; only the third hop was removed.

**AD-V4 — holds** for the part in scope. `fromLabel` returns `INTERNAL` for null,
blank *and* unrecognised values, and `Alert`'s compact constructor normalises a
null audience the same way. The `alerts.yml`-parsing test that stops the default
becoming a loophole is a **Phase 6 step 5** item and is correctly absent here.

**AD-V5 (as amended) — holds, including the subtle part.** `PRODUCT` with no room
misroutes to ops prefixed with
`↪️ MISROUTED — no VipTalk room for BOM (097); delivered here instead of a product room.`,
logs WARN, and counts `outcome="misrouted"` with `reason=no_room|no_product`.
The AD's dedupe requirement — "a misrouted product register must not produce a
second ops message for the same alert" — is implemented as `dedupeByRoom`
(first decision wins, the suppressed one keeps its outcome for the counter but
carries no room) and pinned by both
`AlertRouterTest.bothWithAnUnwiredProductNeverProducesTwoOpsMessages` and
`AlertServiceTest.send_bothAudienceWithUnwiredProductProducesExactlyOneOpsMessage`
(which asserts exactly one `VipTalkClient.send` call). The counter name does not
start with `bot_`, as the AD requires.

**AD-V6 — holds.** The customer text is never derived from the technical text; it
is the `public_summary` annotation verbatim. A missing `public_summary` on an
`audience: both` alert **suppresses** the product-room copy (`customerCopy` gates
on `Alert.hasPublicSummary()` and `formatCustomer` returns `""` as a backstop).
Fail-closed in both places.

**AD-V7 — holds.** One flag, `viptalk.customer-notices-enabled`, default `false`,
wired through `application.properties`, `docker-compose.yml`
(`VIPTALK_CUSTOMER_NOTICES_ENABLED=${…:-false}`) and `secrets.env.example`. When
false, `BOTH` degrades to the ops-room technical copy only.

**AD-3 totality (Implementation Note 6) — holds.** `AlertRouter.route` is total
over null alert, null audience, null product, null `publicSummary` and a blank
ops room; `AlertServiceTest.send_neverThrowsOnANullAlert` and
`AlertRouterTest.routerIsTotal_nullAlertAndNullAudienceNeverThrow` pin it.

### The three user requirements

**(a) All of a product's alerts converge on one room regardless of environment —
genuinely satisfied.** Two independent paths now carry the product: the
`product` metric label (Phase 1) rides the rule labels straight through
Alertmanager, and `AlertmanagerWebhookService.resolveProduct` resolves an
environment-only alert via `environmentId` → Mongo → `Environment.productCode`.
Both land on `AlertRoomRegistry.roomFor(product)`, which is a per-product lookup
on `ProductCode.vipTalkRoomId` with **no environment or instance dimension at
all** — so P_116's staging environments, prod environments and the three app
instances all resolve to the same single room, distinguished only by the
`viptalk.instance-label` header segment (AD-7 / AD-V15). Nothing in the routing
path can split one product across two rooms.

**(b) The internal room receives only fundamental infrastructure problems —
mechanism satisfied, outcome gated on Phase 3 and on room creation, as planned.**
The one line that made B2 impossible (`.or(rooms::opsRoom)`) is gone. What
remains reaching ops is exactly what the plan sanctions: `audience: internal`,
plus tagged AD-V5 misroutes. Two caveats, both deliberate and both plan-side, not
drift:
- Until Phase 3 labels the rules, AD-V4's default sends everything to ops — the
  plan explicitly wants this ("deployable on its own … today's behaviour, byte
  for byte").
- With 9 of 10 products unwired, their `audience: product` alerts land in ops
  under the misroute marker. That is the amended AD-V5's declared rollout
  posture, is visually distinguishable in the room, and is measurable via
  `alert_dispatch_total{outcome="misrouted"}`. It self-retires per product as
  room IDs are filled in.

**(c) Product rooms receive serious outages in a distinct non-technical register —
satisfied.** `AlertRegister.CUSTOMER` is a separate rendering path, not a
transform of the technical one. It emits severity **marker** + product + instance
and then the operator-authored `public_summary`, and structurally cannot leak the
title, body, environment id, metric values, severity word or `— via prometheus`
trailer. I checked for the inverse leak too: a CUSTOMER-register message can only
be produced by the `BOTH` branch, and the only way it could reach the ops room is
the AD-V5 misroute — which in that branch is always deduped away because the ops
room already holds the technical copy (or, if the ops room is unset, is dropped).
So neither register can appear in the other's room.

### Phases 3–6
Status: out of scope (not implemented, as instructed)
Notes: `prometheus/alerts.yml`, `alertmanager/`, `deploy.sh` and
`prometheus/prometheus.yml` remain uncommitted/untracked in the working tree —
correctly withheld from these five commits.

## Drift

None in the code. The deviations from the literal plan text are all ones the plan
itself (or the user's AD-V5 amendment) called for:

- `AlertRouter.RoutedMessage` is a 5-component record `(roomId, text, register,
  outcome, reason)` where the plan sketched `(roomId, text)`. The extra fields
  exist to carry the AD-V5 misroute outcome to the counter without giving the
  pure router a `MeterRegistry`. This is an elaboration in service of the AD, not
  a departure from it.
- `alert_dispatch_total` carries `outcome="misrouted"` (required by the amended
  AD-V5, which the plan's step-7 list predates) and `reason="channel_disabled"`
  (so a send skipped because `viptalk.enabled=false` is not miscounted as
  `sent`). Both stay well inside the "~6 × 4 × 11 worst case" bound the plan set.
- The plan's Phase 2 note "the old *no product room → ops room* expectation is now
  *dropped*" was itself superseded by the AD-V5 amendment; `AlertServiceTest`
  correctly asserts the misroute instead.

## Out-of-scope changes

- **The VipTalk V1 base was absorbed into `d0f07d6` / `c841c32`.** Accounted for
  in the brief and unavoidable: the base files were untracked, Phase 2's edits are
  interleaved inside them, and an untracked file has no hunks to split — a
  new-files-only commit would leave HEAD unable to compile. I verified the
  absorption is clean: every file in those two commits belongs to either the V1
  base or Phase 2, with no unrelated work riding along. In particular the
  in-flight `bot.deposit.amount` change (`BotBehaviorConfig`, `Bot`,
  `BotGroupBehaviorService`, `application.properties`) and the Phase 3 deploy-side
  files were correctly left in the working tree.

- **One loose end from that absorption, worth fixing before the next commit.**
  `secrets.env.example` was committed in `adb099e` and its own header states
  *"`secrets.env` is gitignored"* — but the `.gitignore` hunk that adds
  `secrets.env` is still **uncommitted** in the working tree, so at HEAD the claim
  is false. A `git add -A` on a host holding a real `secrets.env` would stage
  `VIPTALK_BOT_TOKEN`, against VIPTALK_ALERTING AD-2 / AD-V14. Not a plan-compliance
  failure (the plan never asked for the `.gitignore` line, and the fix already
  exists in the tree), so it does not change the verdict — but it should land with
  the Phase 3 commit rather than being left to drift.

## Amendments to the plan

Four changes to `docs/plans/VIPTALK_ALERTING_V2.md`, all additive or clearly
marked, plus a new `## Amendment — 2026-08-17` section at the bottom recording
them. The plan file remains **uncommitted** — every doc under `docs/plans/` and
`docs/reviews/` is untracked in this tree, and what lands in git is the user's
call.

1. **Phase 1 step 1 — wrong module path.** `BotMdc.java` is in `bot-api`, not
   `bot-engine`. Cosmetic; the implementation used the real path. *(Flagged in the
   brief.)*

2. **Phase 2 verification block — stale, contradicted the amended AD-V5.** It
   still expected `SKIPPED` and `outcome="dropped",reason="no_room"` for the
   unwired-P_097 probe. Under the amended AD-V5 the correct expectation is
   `SENT`, the message in the ops room only, prefixed with the `↪️ MISROUTED`
   marker, and `outcome="misrouted",reason="no_room",product="097"`. I confirmed
   `SENT` is what the endpoint actually returns: `AlertDispatch.aggregate` reports
   the transport outcome, and a misroute is a successful send. Left as written,
   this block would have failed a *correct* implementation. *(Flagged in the
   brief; amended under my stated authority.)*

3. **AD-V8 marked dead; Phase 6 steps 1–3 and its verification block rewritten
   onto AD-V8b.** The Open Items already recorded Phase 0's result, but the Phase 6
   *steps* still specified the query-string static receiver, so Phase 6 as written
   would have built the approach the spike falsified. This is the archetypal plan
   amendment: a falsifiable claim about an external system ("VipTalk reads query
   params on `sendMessage`") that the spike disproved with a `400
   M_CANNOT_SEND_EMPTY_MESSAGE`. Two consequences are now stated explicitly, both
   flowing from the spike's unplanned finding that VipTalk accepts a JSON body:
   the shim is a JSON→JSON field remap rather than a JSON→form re-encode, and —
   because the bot token moves into the shim's environment — `alertmanager.yml`
   holds no secret, so the `.yml.template` + `envsubst` render that the original
   Phase 6 step 1 mandated is no longer needed and the file stays committed.
   *(Flagged in the brief.)*

4. **New Phase 6 open item — the resolved half of an `audience: both` alert.**
   Found during this review; not a Phase 1–2 defect and not something Phase 2
   forecloses, but a decision AD-V6 and AD-V9 together leave unspecified. AD-V9
   has the app deliver the `BotManagerDown` **resolved** notification. That payload
   still carries `audience: both` and the rule's `public_summary`. `AlertRouter`
   branches on audience only — it has no firing-vs-resolved notion — and
   `formatCustomer` renders the marker plus `public_summary` verbatim. So on prod
   with customer notices on, recovery would put `✅ TIP (116) · prod` above
   *"ALERT! Bot Management application is experiencing issues…"* into the product
   room: a green tick over text saying the app is broken. The technical register
   is unaffected. Recorded with three candidate shapes (suppress the customer copy
   on RESOLVED / add `public_summary_resolved` / word `public_summary` to survive
   both markers); choosing one is Architect-1's call in Phase 6. Unreachable today
   — `customer-notices-enabled` is prod-only and Phase 6 is not built.

## Does Phase 2 foreclose any Phase 3–6 decision?

No. Checked each downstream dependency:

- **Phase 3 `GameNoRounds`.** The `unless` operands line up: after
  `sum by (product, environmentId, gameId, gameName, gameType)`,
  `bots_by_game_status` now carries all five, and `bot_messages_total` already
  did. The one asymmetry — the gauge renders a null product as `""` via
  `nullSafe` while the counter omits the label — is benign, because PromQL's
  `sum by(product)` over a series lacking `product` also yields `product=""`, so
  the label sets still match.
- **Phase 3 `EnvironmentSocketDown`.** `ws_connections_open_by_env` emits a
  **zero row** for an environment whose bots all lost the socket
  (`countOpenWsByEnv` iterates every runtime with a non-null environment id, and
  `InfoGaugeRefresherTest.wsConnectionsOpenByEnvGauge_carriesEnvironmentIdAndProduct_andTracksZero`
  pins it). Omitting that row is the one way the rule could silently never fire;
  it is not omitted.
- **Phase 3 `audience` in `group_by`.** Webhook batching is already keyed on
  `(product, audience)`, so Alertmanager grouping and app batching agree.
- **Phase 4.** `BotGroupRuntime.groupName` is already threaded in at group start,
  which was Phase 4's only Phase 1 dependency. `depositAmount` on
  `BotBehaviorConfig` is arriving separately via the in-flight
  DEPOSIT_AMOUNT_CONFIG work and does not conflict.
- **Phase 5.** Untouched; node-exporter is purely additive.
- **Phase 6.** `AlertRulesAudienceTest` (AD-V4's tripwire) is a Phase 6 step and
  its absence here is correct. `VipTalkClient` was deliberately left on
  form-urlencoded per the spike, which is what AD-V8b now assumes. The one open
  question that Phase 2's shape *raises* — the resolved customer copy — is
  changeable inside `AlertRouter`/`AlertMessageFormatter` with no schema or
  contract change, so it is deferred, not foreclosed.

## Notes for the Reviewer (not compliance findings)

- `AlertRoomRegistry.opsRoom()`'s javadoc still calls it "the catch-all ops room",
  which the class-level javadoc four lines above now explicitly denies. Stale
  comment only.
- One full-reactor run hit a transient
  `NoClassDefFound …TaiXiuGameBotDispatchTest$1` in `bot-engine` (7 errors) while
  the corresponding surefire report recorded 0. Two subsequent runs, including a
  full `clean install`, were green at 873/873. The affected tests are untouched by
  this diff; it reads as a surefire/classloading flake, but it is worth a second
  pair of eyes if it recurs.

---

# Part 2 — Phases 3, 4, 5 and 6 (2026-08-18)

Branch: `staging`
Plan reviewed: `docs/plans/VIPTALK_ALERTING_V2.md` (untracked working-tree copy,
as amended by the 2026-08-17 and 2026-08-18 amendment sections)
Diff reviewed: `git diff 46e7a38..HEAD` — commits `e435e7a`, `8d2944f`, `48ffc21`
(Phase 3), `6508373`, `624048b` (Phase 4), `64166f9`, `ce4deaf` (Phase 5),
`3d84647`, `4302869`, `6fde162`, `f6322c0`, `24bf77b` (Phase 6).
Phases 1–2 and their fix pass (`a1576b3`…`46e7a38`) were **not** re-verified.
Build: `JAVA_HOME=…/openjdk-21.0.2 mvn clean install` → **BUILD SUCCESS,
940 tests, 0 failures, 0 errors** (second run; see the flake note at the end).

## Verdict

PLAN_AMENDED

The **code is accepted as faithful**. Every phase is delivered, and all seven of
the deviations Dev flagged are ones where the plan was wrong or self-contradictory
— six of them turn on a falsifiable claim about an external system (PromQL's `and`
semantics, Alertmanager's consume/continue routing, node-exporter's
`--collector.filesystem.mount-points-exclude` override semantics), which is the
bar for a plan amendment rather than a send-back. The seventh (the shim's shape)
is a case where two of the plan's own steps contradict each other and Dev's
resolution is the one that satisfies the plan's *verification* block.

Nine items amended in the plan under `## Amendment — 2026-08-18`. No code drift
found, so no send-back.

## Phase-by-phase

### Phase 3 — Product-routed rules for A1, A2a, A2b; audience labels everywhere
Status: drifted (plan wrong) — accepted

| Step | Where | Verdict |
|---|---|---|
| 1 label every existing rule | `alerts.yml:76-119`, `:324-332` | done — `BotManagerDown` → `both` + `public_summary`, `JvmThreadsHigh` → `internal` |
| 2 retire the three fleet rules | `alerts.yml:35-51` header; `AlertRulesAudienceTest.retiredFleetRulesStayRetired` | done, and the retirement is enforced by the build, not just by the diff |
| 3 new rules | `EnvironmentSocketDown/Degraded`, `EnvironmentAuthDown`, `EnvironmentLoginFailing`, `GameNoRounds` | done; two rules added beyond the block (below) |
| 4 `audience` in `group_by` | `alertmanager.yml:37` | done |

**Deviation 1 — operand order. Dev is right; plan amended (item 1).** `a and b`
yields *a's* values, so the plan's `bots_managed_by_env > 0 and (ratio) < 0.5`
would have rendered `{{ $value | humanizePercentage }}` as the managed-bot count —
`2000%` for a 20-bot environment. The delivered form puts the ratio on the left.
Vector matching is unaffected either way (`and` matches on all labels but
`__name__`, and the division preserves the label set), so this was purely a
`$value` defect — invisible until the first real outage rendered a nonsense
percentage into a product room.

**Deviation 2 — `EnvironmentDeadBotRatioHigh` added. Dev is right; plan amended
(item 2).** The plan's step 2 retires *three* fleet rules and says they are
"replace[d] … with per-environment equivalents", then supplies a replacement for
one. Implemented literally, Phase 3 would have deleted all dead-bot and dead-group
alerting and put nothing back. Adding the rule implements the plan's own stated
intent; the omission was in the plan's rule block. The un-aggregated-divisor
defect Dev describes is real and is worth recording even though the rule was not
in the plan: `sum by (product, environmentId)(…) / bots_managed_by_env` compares a
2-label set against the 4-label set Prometheus stamps (`job`, `instance`) and
matches **nothing** — a rule that can never fire, which is the worst failure mode
an alert has. Both operands are aggregated in the delivered rule.

**Verified independently:** `BotStatus.DEAD` and `CONNECTION_AUTHENTICATED` both
exist and are both reachable (`Bot.java:654,700` / `Bot.markConnectionAuthenticated`,
called from **both** `BettingMiniGameBot.onSubscribe:379` and
`SlotMachineBot.onSubscribe:185`), and `incBotMessage` is called with exactly
`"startGame"` (`BettingMiniGameBot:387`) and `"spin"` (`SlotMachineBot:213`). So
`GameNoRounds` covers all three implemented game types — `CARD_GAME`/`UP_DOWN`
throw at `BotFactory:177`, so they cannot produce a false positive.

### Phase 4 — Low balance on non-auto-deposit groups
Status: implemented, plus a scope addition — accepted

| Step | Where | Verdict |
|---|---|---|
| 1 `listGroupBalances()` | `BotGroupBehaviorService.java:1608` | done; filters `!isAutoDepositEnabled()` and `activeBots > 0`, averages `getExpectedBalance()` over `isConnected()` bots (AD-V12/V13), `depositAmount` mirrors `resolveDepositAmount()` |
| 2 two MultiGauges + `AGGREGATE_METER_NAMES` | `InfoGaugeRefresher.java:150-160,236-247`, `BotMdcTagsMeterFilter.java:66-73` | done; both rows come from **one** `listGroupBalances()` snapshot, so the absolute and the ratio can never be read from different instants |
| 3 `GroupBalanceLow` rule | `alerts.yml:308-316` | done, verbatim |

**Deviation 3 — `groups_dead_by_env` + `EnvironmentGroupDead`. Consistent with
AD-V2; plan amended (item 3).** I checked this specifically because it is the one
addition that could have violated an AD. It does not:

- `ObservabilityConfig` is **untouched** in this diff — `groups_dead_currently`
  and the other five fleet aggregates remain unlabelled, which is exactly what
  AD-V2 protects. `groups_dead_by_env` is a *new, separate* MultiGauge, not a
  relabelling of an existing series.
- The predicate is `runtime.getGroupDeadSince() != null`, byte-identical to
  `countGroupsDeadCurrently()` (`:1364-1370`), so the AD-V2-friendly invariant
  `sum(groups_dead_by_env) == groups_dead_currently` actually holds.
- The name is on `AGGREGATE_METER_NAMES`, like the Phase 1 per-env pair, so the
  10 s refresher thread's MDC cannot leak onto it.
- AD-V2's own prescription for a retired fleet rule is "replaced by their
  per-environment equivalents" — which is precisely this.

It also closes a real gap that Phase 3 opened and nothing else covers: a 5-bot
group dying inside a 100-bot environment moves `EnvironmentDeadBotRatioHigh` by
0.05 and never trips 0.2, so without this rule a DEAD group could go unannounced.
Given the standing "a DEAD group must be one-click restartable" requirement, that
gap was not survivable for a phase.

### Phase 5 — node-exporter, and host CPU / RAM / storage rules
Status: drifted (plan wrong) — accepted

| Step | Where | Verdict |
|---|---|---|
| 1 node-exporter service | `docker-compose.yml` (`pid: host`, `/:/host:ro,rslave`, no host port) | done |
| 2 `job_name: node` scrape | `prometheus/prometheus.yml` | done |
| 3 five host rules, all `audience: internal` | `alerts.yml:349-448` | done |

**Deviation 4 — the exclusion regex. Dev is right; plan amended (item 4).**
`--collector.filesystem.mount-points-exclude` **overrides** node-exporter's
default wholesale. The plan's `^/(sys|proc|dev|host|etc)($$|/)` therefore
re-admits `/var/lib/docker/…` — one `node_filesystem_*` series set per container
layer, permanently near-full, which is exactly the noise the `mountpoint="/"`
selector exists to avoid. The delivered regex restates the v1.8.2 defaults
(`dev|proc|sys|run/credentials/.+|var/lib/docker/.+|var/lib/kubelet/pods/.+`) and
adds `etc|host`. The symmetric `fstype!~` across the division is a smaller point —
it does not fix a live defect on a one-series-per-mount host — but it is free and
it stops the pairing depending on a property the rule never states. Accepted, and
recorded.

Phase 0 question 2 (does node-exporter see the filesystem that filled on
2026-06-30?) remains **open by construction** — it can only be answered on Bot-1.
The rule file carries that as an explicit release-time check
(`alerts.yml:371-373`), which is the right place for it.

### Phase 6 — Out-of-band app-down delivery, and app-restarted
Status: drifted (plan wrong / self-contradictory) — accepted

| Step | Where | Verdict |
|---|---|---|
| 1 `viptalk-shim` container | `viptalk-shim/shim.py`, `docker-compose.yml` | done, with two deviations ruled on below |
| 2 `secrets.env.example` | `+VIPTALK_DOWN_ROOM_IDS`, `+VIPTALK_DOWN_TEXT`, `+VIPTALK_DOWN_OPS_TEXT` | done, and marked PROD ONLY |
| 3 `alertmanager.yml` fan-out | `:59-66` | done — **corrected**, see deviation 5 |
| 4 `BotManagerDown` annotations + `BotManagerRestarted` | `alerts.yml:76-119` | done; `public_resolved_summary` added beyond the plan (closes the 2026-08-17 open item) |
| 5 `AlertRulesAudienceTest` | `bot-app/src/test/.../AlertRulesAudienceTest.java` | done — SnakeYAML, `Assumptions.assumeTrue` skip, audience + `public_summary` + retired-rules assertions |

**Deviation 5 — the `continue: true` single route. Dev is right, and this is the
most consequential finding of the pass; plan amended (item 5).** Alertmanager's
rule is that a matching child route **consumes** the alert and the parent's own
receiver applies only when *no* child matched; `continue: true` continues to the
next **sibling**, not up to the parent. With the plan's single child,
`BotManagerDown` reaches `viptalk-static-down` and **never** reaches `viptalk` —
so the RESOLVED half, the entire reason AD-V9 pairs the two receivers, would
never have been sent. The delivered file uses two sibling routes. I am satisfied
this is proven rather than asserted: `AlertmanagerRoutingTest` walks the tree with
Alertmanager's own consume/continue semantics (not a YAML-shape assertion) and
asserts both receivers are reached for `BotManagerDown` and that
`EnvironmentSocketDown` still reaches `viptalk` **exactly once** — i.e. the new
route steals nothing. It also pins `send_resolved` false/true on the two receivers
and that the file names no VipTalk host or token (AD-2).

**Deviation 6 — two registers, and the shim runs everywhere. Dev is right; plan
amended (item 6).** Both halves are forced by contradictions inside the plan:

- *Runs everywhere.* Step 1 says "not started when `VIPTALK_DOWN_ROOM_IDS` is
  empty", but step 3 keeps `alertmanager.yml` committed and identical on all three
  instances, so the `viptalk-static-down` receiver exists everywhere. An absent
  container means Alertmanager retries a connection-refused webhook *during the
  outage it is reporting*. The delivered shim self-disables on blank config and
  answers **200** (not 502) in that state, so Alertmanager does not retry
  something that can never succeed. That is the correct polarity.
- *Two registers.* The plan's own Phase 6 verification demands "a technical
  `bot-manager is not scrapeable` message in the **ops room** … delivered while
  bot-manager is down". A shim with one fixed customer string posting only to
  `VIPTALK_DOWN_ROOM_IDS` cannot produce that. With two registers, staging (no
  product rooms) emits only the ops technical copy — so AD-V7 still holds off prod
  *and* the verification block is satisfiable.

I ran `python3 viptalk-shim/selftest.py`: all checks pass, including "customer
copy never reaches a non-prod instance's audience" and "the static outage text is
never sent on recovery".

**Residual on AD-V7, recorded as a plan open item, not drift.** AD-V7 says the
customer register is gated by *one* config flag; the app's is
`viptalk.customer-notices-enabled` and the shim's is "`VIPTALK_DOWN_ROOM_IDS` is
non-empty". Two gates for one decision — an operator who fills
`VIPTALK_DOWN_ROOM_IDS` on staging gets customer copy from the shim while the app
flag still reads `false`. This is **not** implementation drift: the plan itself
chose the room-list gate for the shim. Cheap hardening (recorded in the
amendment): have the shim also require `VIPTALK_CUSTOMER_NOTICES_ENABLED=true`.

**Deviation 7 — the `grep -c viptalk-static-down == 0` expectation. Dev is right;
plan amended (item 7).** Under AD-V8b the file is committed and identical
everywhere, so that count is `1` on every instance forever. The amendment replaces
it with `≥ 1` everywhere plus a `GET /health` check on the shim, which is the
thing that actually differs per instance (`productRooms: 0` off prod).

**Beyond the plan, and correct:** `public_resolved_summary` on `BotManagerDown`,
with the `viptalk.public-resolved-summary` fallback, closes the open item Part 1
raised (a ✅ marker over "the application is experiencing issues"). It picks the
second of the three shapes Part 1 offered, which was Architect-1's call to make;
`AlertRulesAudienceTest.everyBothRuleCarriesItsOwnRecoveryCopy` additionally
asserts the recovery copy is **not equal** to the firing copy, which is the defect
itself rather than a proxy for it.

## The four alert cases and the three routing rules, end to end

This is the part I was asked to be hardest on: with all six phases present, does
each case actually reach a room, or does each phase assume another one covers it?

**A1 — game dead. Delivered.** `GameNoRounds`, both `unless` operands reducing to
`(product, environmentId, gameId, gameName, gameType)`. Left operand exists for
all three implemented game types (verified at the `markConnectionAuthenticated`
call sites); right operand's `cmd` values verified against the two
`incBotMessage` call sites. `unless` (not `and`) means the tx7 "0 sessions ever"
case — no `bot_messages_total` series at all — still fires, which was the point.
The gauge's `product=""` for a null `Game.productCode` matches PromQL's
`sum by(product)` over a counter that lacks the label, so the two sides still
pair.

**A2 — environment dead, socket and login. Delivered, both halves.** Socket:
`EnvironmentSocketDown/Degraded` on the Phase 1 gauge pair, which emits a **zero
row** for a fully-disconnected environment (pinned by
`InfoGaugeRefresherTest.wsConnectionsOpenByEnvGauge_…_andTracksZero`) — the one
way this rule could have silently never fired. Login: `EnvironmentAuthDown` on the
continuous `bot_verify_token_total` (written on every authoritative balance read),
corroborated by `EnvironmentLoginFailing` on the start-only `bot_login_total`.
`outcome="failure"` matches the tag value actually emitted (`BotMetrics:159,282,291`).

**A3 — low balance on non-auto-deposit groups. Delivered, with a release-ordering
caveat (below).** `group_balance_ratio < 0.10`; rows exist only for
`autoDeposit == false` groups with ≥ 1 connected bot, so the "expect zero series
for auto-deposit groups" verification is achievable.
`InfoGaugePrometheusScrapeTest.phase4Gauges_renderToPrometheusScrape_…` pins the
exact exposition line including `groupName`, which `GroupBalanceLow`'s summary
interpolates.

**A4 — storage high. Delivered**, plus RAM and CPU, all from node-exporter rather
than actuator (AD-V11), with `NodeExporterDown` as the fail-silent guard —
without which the three host rules would go quiet exactly when the host died.

**B1 — product rooms get everything for their product, across environments.
Delivered.** Every `audience: product` rule preserves `product` on its **output**
series: the socket rules through the division (identical label sets both sides),
the auth/dead-bot rules through `sum by (product, environmentId)`, `GameNoRounds`
through its `sum by`, and the two bare-gauge rules (`EnvironmentGroupDead`,
`GroupBalanceLow`) trivially. Where `product` is empty, `environmentId` is present
on every one of those series, so `AlertmanagerWebhookService`'s second hop
(Mongo → `Environment.productCode`) resolves it. `AlertRoomRegistry.roomFor` has
no environment or instance dimension, so one product converges on one room.

**B2 — the internal room gets only fundamentals. Delivered as designed.**
`audience: internal` is exactly `JvmThreadsHigh`, `BotManagerRestarted`,
`HostDiskSpaceLow/Critical`, `HostMemoryLow`, `HostCpuHigh`, `NodeExporterDown`,
plus `BotManagerDown`'s technical half — i.e. CPU / RAM / storage / app-down /
app-restarted, which is the user's list, with the JVM thread-leak guard as the one
addition. Enforced, not merely intended: `AlertRulesAudienceTest` fails the build
if any rule omits `audience`, so a future rule cannot land unlabelled and default
its way into the ops room. The known leak into ops is the AD-V5 misroute for the
9 unwired products, which is the declared, self-retiring rollout posture.

**B3 — serious outages reach product rooms in a non-technical register.
Delivered, and now genuinely two-path.** `BotManagerDown` is `audience: both`
with `public_summary`; the shim delivers the customer copy while the app is dead
and the ops technical copy at the same time; the app delivers the recovery copy.
The shim's `DEFAULT_DOWN_TEXT` is byte-identical to the rule's `public_summary`,
which is what keeps one incident reading one way regardless of which path
delivered it — and `alerts.yml:73-75` says so out loud, so the coupling is
documented at both ends.

### Gaps between phases — what I looked for and what I found

Four checked and clean; three real and recorded.

- *Clean:* the dead-**group** gap Phase 3 opened is closed by Phase 4
  (`EnvironmentGroupDead`), not left to `EnvironmentDeadBotRatioHigh`'s ratio.
- *Clean:* Phase 3's `audience` in `group_by` agrees with Phase 2's webhook
  batching key `(product, audience)`, so Alertmanager grouping and app batching
  cannot merge two registers into one message.
- *Clean:* `GameNoRounds` does not fire for an environment whose bots never
  subscribed — those bots are not `CONNECTION_AUTHENTICATED`, so the case belongs
  to `EnvironmentSocketDown` and is not double-reported.
- *Clean:* AD-V2's fleet aggregates are untouched, so Grafana's existing panels
  (`bots_managed`, `ws_connections_open`, `bots_dead_currently`,
  `groups_dead_currently`) are unaffected by Phases 3–6.

- **Recorded (plan item 9): the customer FIRING copy can arrive twice, the second
  time after the outage ended.** AD-V9 assumes the app receiver's firing
  notification "fails (app down, Alertmanager retries)" and that only its
  *resolved* notification lands. Alertmanager retries, and `BotManagerDown` stays
  firing until Prometheus completes a scrape and re-evaluates — tens of seconds
  *after* the app is back. A retry landing in that window delivers the **firing**
  payload, which still carries `audience: both` + `public_summary`, so a product
  room can see: shim outage copy → app outage copy (duplicate, post-recovery) →
  recovery copy. Nothing in Phases 1–6 dedupes across two independent delivery
  paths, and AD-6 forbids an app-side state machine to merge them. Not drift — the
  plan mandates this fan-out — but the plan's premise about it is incomplete.
  Prod-only and gated behind `customer-notices-enabled`, so nothing is broken now.

- **Recorded (observation): Alertmanager's `BotManagerDown` inhibit no longer
  covers the aggregated rules.** `inhibit_rules` matches `equal: ['job']`, but
  `sum by (product, environmentId)` **drops** `job`, so `EnvironmentAuthDown`,
  `EnvironmentLoginFailing`, `EnvironmentDeadBotRatioHigh` and `GameNoRounds`
  carry no `job` and are not suppressed while the app is down, whereas the
  bare-gauge rules (`EnvironmentSocketDown`, `EnvironmentGroupDead`,
  `GroupBalanceLow`) still are. Phase 3 weakened this relative to the retired
  fleet rules, which all carried `job`. It fails *open* (more messages, not fewer)
  and is largely masked in practice — a dead app means no scrapes, so those series
  go stale within ~5 min and the rules resolve rather than fire. Worth one
  `equal: []`-vs-`job` decision after the first real outage; not worth blocking on.

- **Recorded (observation): the shim has no coverage in the Maven build.**
  `viptalk-shim/selftest.py` is thorough and passes, but nothing runs it — unlike
  `alerts.yml` and `alertmanager.yml`, which are both parsed and asserted by
  JUnit. The one delivery path that cannot be exercised in production without
  causing an outage is also the one whose tests are manual.

## Release-ordering hazard — confirmed, and it was **not** recorded anywhere durable

Confirmed empirically, not inferred:

```
git show HEAD:bot-api/.../config/bot/BotBehaviorConfig.java | grep -i deposit
  → only `autoDepositEnabled`. No `depositAmount`.
```

`Bot.DEFAULT_DEPOSIT_AMOUNT` likewise exists only in the working tree
(`Bot.java:404`). Phase 4's `listGroupBalances()` references both, so **this
branch does not compile against `HEAD` alone** — it compiles only because the
uncommitted DEPOSIT_AMOUNT_CONFIG changes are present in the tree. I grepped
`docs/plans/`, `docs/reviews/` and `FOLLOWUPS.md`: the hazard appears **nowhere**.
The closest prior mention is Part 1's remark that `depositAmount` "is arriving
separately … and does not conflict", which is now stale — it does not conflict,
but it is a hard build prerequisite.

Two further prerequisites are in the same uncommitted state, and they are
load-bearing for the *deploy* rather than the build:

- **`deploy.sh`'s `secrets.env` → `.env` merge** (`+11` lines, uncommitted).
  Without it **no** VipTalk variable reaches any container: the app self-disables
  and the shim reports `enabled: false`. The entire feature would deploy inert
  while every container reported healthy — the worst possible failure shape for an
  alerting system.
- **`.gitignore`'s `secrets.env` entry** (`+4` lines, uncommitted). Part 1 flagged
  this and it did **not** land with Phases 3–6. `secrets.env.example` is committed
  and asserts the real file is gitignored; at the branch tip that is false, so a
  `git add -A` on a host holding a real `secrets.env` stages `VIPTALK_BOT_TOKEN`.

All three are now recorded in the plan (`## Amendment — 2026-08-18`, item 8) with
the required landing order: DEPOSIT_AMOUNT_CONFIG's symbols → this branch, and
`deploy.sh` + `.gitignore` **with or before** the alerting commits, never after.
The user is landing everything in one go, which makes all three moot *if that
holds* — the record exists for the case where it does not.

## Drift

None in the code. Every deviation is either the plan being wrong (items 1, 2, 4,
5, 6, 7 of the amendment) or an addition the plan's own prose required but its
rule block omitted (items 2, 3).

## Out-of-scope changes

- **`alertmanager/alertmanager.yml` and the `alertmanager` compose service** land
  in this range although they belong to the VipTalk V1 base. Same unavoidable
  absorption Part 1 documented for the V1 app files: they were untracked, and
  Phase 3/6 edits are interleaved inside them. I checked the content — it is the
  V1 file plus exactly the Phase 3 (`group_by`) and Phase 6 (fan-out routes,
  second receiver) changes, plus two inhibit rules for the overlapping
  socket and disk pairs. The inhibit rules are not in the plan; they are the
  standard remedy for two thresholds on one expression (`EnvironmentSocketDown`
  vs `Degraded`, `HostDiskSpaceCritical` vs `Low`) and they reduce room volume
  rather than suppressing a distinct condition. Accepted.
- **`prometheus/alerts.yml` and the `alerts.yml` bind-mount / `rule_files` /
  `alerting` stanzas** are likewise V1-base work absorbed here. Unavoidable for
  the same reason, and Phase 3 could not otherwise exist.
- **`viptalk-shim/selftest.py`** (297 lines) is beyond the plan. Harmless and
  useful; see the observation above about it not being wired into any build.
- The in-flight `bot.deposit.amount` change and the `.gitignore` / `deploy.sh`
  edits remain correctly *outside* these commits — see the hazard section for why
  that is a two-edged decision.

## Amendments to the plan

Nine items appended to `docs/plans/VIPTALK_ALERTING_V2.md` as
`## Amendment — 2026-08-18`. Nothing in the plan body was rewritten; the section
names the defective blocks and gives the corrected form beside each.

1. Phase 3 rule block — `and` operand order (`$value` would render the managed-bot
   count as a percentage).
2. Phase 3 step 2 — promised three replacements, specified one; adds
   `EnvironmentDeadBotRatioHigh` (with both operands aggregated, since dividing a
   `sum by (…)` by a raw series matches nothing).
3. Phase 4 — adds `groups_dead_by_env` + `EnvironmentGroupDead`, with the argument
   for why a new labelled sibling honours AD-V2 rather than bending it.
4. Phase 5 — `--collector.filesystem.mount-points-exclude` overrides upstream's
   defaults wholesale; corrected regex, plus the symmetric `fstype!~`.
5. Phase 6 step 3 — Alertmanager's consume/`continue` semantics; two sibling
   routes, not one with `continue: true`.
6. Phase 6 step 1 — the shim runs everywhere and self-disables, and carries two
   registers; with the residual "two gates for AD-V7's one flag" recorded as an
   open item and a one-line hardening.
7. Phase 6 verification — `viptalk-static-down` is present on every instance;
   check the shim's `/health` instead.
8. **New: release ordering.** Phase 4 does not build on this branch alone
   (`depositAmount` / `DEFAULT_DEPOSIT_AMOUNT`), and `deploy.sh` + `.gitignore`
   must land with or before the alerting commits.
9. **New open item:** the customer FIRING copy can be delivered twice, the second
   time after recovery, because Alertmanager's retry of the app webhook can
   succeed inside the window before `BotManagerDown` resolves.

Also closed the Part 1 open item in place (one bracketed line): Architect-1 chose
the `public_resolved_summary` shape and it is implemented.

The plan and this file are both **uncommitted**, per the brief.

## Notes for the Reviewer / QA (not compliance findings)

- **The full-reactor flake recurred, and it is bigger than last time.** The first
  `mvn clean install` of this pass failed with **36 errors** across
  `RestExceptionHandlerTest` (20) and `MetricsControllerTest` (16), all
  `Failed to load ApplicationContext … class path resource
  [com/vingame/bot/common/exception/RestExceptionHandlerTest.class] cannot be
  opened because it does not exist` — a test class failing to find *its own*
  `.class` file. Re-running those classes alone: 148/148 green. A second full
  `clean install`: **940/940 green, BUILD SUCCESS**. Same signature family as the
  `NoClassDefFound …TaiXiuGameBotDispatchTest$1` flake Part 1 recorded. Two
  independent sightings on unrelated test classes now; it is worth someone
  establishing whether it is surefire forking, the four-module reactor, or a
  stale-target race, before it costs a real debugging session.
- `alerts.yml:280` — `GameNoRounds`' summary says "for 20 minutes" (`[10m]`
  range + `for: 10m`), which is right, but it is derived arithmetic in a string
  that will silently go stale if either window is tuned. The file's own
  "THRESHOLDS ARE FIRST GUESSES" header says they will be tuned.
- `alerts.yml:110` correctly notes that `BotManagerDown` can inhibit
  `BotManagerRestarted` (both carry `job="bot-manager"`) and argues the delay is
  acceptable because the expression stays true for 15 minutes. That reasoning
  holds only while `group_interval` (5m) is well under the 15m window — worth a
  comment cross-reference if either is ever changed.
