# Code Review — VIPTALK_ALERTING_V2 (Phases 1–2)

Branch: `staging`
Reviewed diff: `git diff main..staging`, scoped to `9dfd1d8..c841c32`
(`56c461a`, `3c02ef4`, `d0f07d6`, `adb099e`, `c841c32`)

Build check: `mvn -pl bot-api,bot-engine,bot-app -am test -Dtest='Alert*Test,VipTalkClientTest,BotMdcTest,BotMetricsTest,BotMdcTagsMeterFilterTest,InfoGauge*Test'`
→ BUILD SUCCESS, 124 tests, 0 failures.

## Verdict

CHANGES_REQUESTED

## Findings

### [bug] The customer register re-announces the outage when the alert RESOLVES
`bot-app/src/main/java/com/vingame/bot/domain/alert/service/AlertMessageFormatter.java:99-112`
(with `AlertmanagerWebhookService.java:210-229`)

`worstSeverity` returns `AlertSeverity.RESOLVED` for an all-resolved batch, and the
audience is still whatever the rule declared — so a resolving `audience: both` alert
takes the full `BOTH` path: ops gets the technical `✅ RESOLVED …` copy, and the product
room gets `formatCustomer`, which branches only on `hasPublicSummary()` and never on
severity. The product room therefore receives:

```
✅ TIP (116) · prod
ALERT! Bot Management application is experiencing issues, backend team is aware and will deliver fixes soon.
```

i.e. a green tick glued to the *firing* copy, in the future tense, at the exact moment
the incident ended. Every `audience: both` alert resolves eventually, so this is not an
edge case — it is the second half of every customer-facing incident, and it is the one
message in the system whose audience is outside the team. It is also load-bearing for
AD-V9, which deliberately routes the *resolved* half through the app path.

Fix shape: make the customer register severity-aware. Either suppress `CUSTOMER` when
`severity == RESOLVED` (leaving the ops room to record the recovery) or render a
distinct resolved line — a `public_resolved_summary` annotation, or a fixed neutral
string — rather than replaying `publicSummary`. Whichever is chosen, the gate belongs
next to the existing `hasPublicSummary()` check in `AlertRouter.customerCopy` so the
decision is visible in the routing table and countable on `alert_dispatch_total`.

### [security] `secrets.env.example` is committed, but neither the `.gitignore` entry nor the `deploy.sh` merge that make it safe are on the branch
`secrets.env.example:1-14` (whole file)

The committed template instructs the operator to `cp secrets.env.example secrets.env`
and fill in `VIPTALK_BOT_TOKEN`. On the branch as committed:

- `git show c841c32:.gitignore` has **no** `secrets.env` entry — the file the operator
  is told to create, containing a live bot token, is an untracked file in a repo where
  `git add -A` is routine. That is how the token gets committed.
- `git show c841c32:deploy.sh` contains **no** `secrets.env` handling — so the header
  comment "deploy.sh appends this file to the .env it generates" is false for this
  branch, and every `VIPTALK_*` value falls through to the `docker-compose.yml`
  defaults. Alerting deploys permanently disabled, silently.

Both halves exist only in the uncommitted working tree (`.gitignore:47-49`,
`deploy.sh:23-31`). Given the project's prior "uncommitted-files provenance hazard",
the safe shape is: the `.gitignore` entry lands **in the same commit as** the template
(ideally before it), and `deploy.sh`'s merge lands with it. Do not ship the template
alone.

### [security] `POST /api/v1/alerts/product/{product}` is unauthenticated and now writes into a live product room
`bot-app/src/main/java/com/vingame/bot/domain/alert/controller/AlertController.java:68-90`

The plan lists auth on `/api/v1/alerts/**` as out of scope, and the class javadoc
(`:38-41`) is honest about it — so this is a known gap, not an oversight. It is flagged
here because *this diff is what changes its blast radius*: before `c841c32` no product
had a room, so the worst case was noise in an internal ops room. `P_116` now carries a
real Matrix room ID, and `8080:8085` is published in `docker-compose.yml`, so anyone who
can reach the container's port can post arbitrary operator-chosen text — with a
`🔴 CRITICAL` marker — into the TIP room. Once `VIPTALK_CUSTOMER_NOTICES_ENABLED=true`
on prod, the same surface reaches customer-register copy.

Fix shape (does not need to block Phase 2, but should precede the first prod send):
bind `/api/v1/alerts/**` to the internal-only path used for actuator, or require a
shared secret header on the two publish endpoints. `/rooms` and `/alertmanager` can stay
as they are (`/rooms` already masks room IDs).

### [smell] `AlertRouter.route`'s switch has no `default`, so a fourth audience would silently drop the alert
`bot-app/src/main/java/com/vingame/bot/domain/alert/service/AlertRouter.java:101-108`

This is a switch *statement*, not an expression, so the compiler does not enforce
exhaustiveness. Adding a constant to `AlertAudience` compiles clean and produces an
empty `decisions` list → `AlertService` returns `SKIPPED` → the controller answers
200 → Alertmanager marks it delivered → the alert is gone, with no WARN and no
`alert_dispatch_total` row. That is precisely the failure mode AD-V4 was written to
prevent ("an unlabelled rule must not silently vanish").

Fix shape: make it a switch *expression* returning `List<RoutedMessage>` (the compiler
then rejects any new constant until it is handled), or add
`default -> { log.error(...); decisions.add(toOpsRoom(alert)); }`.

### [smell] An unrecognised `audience` value is swallowed with no log
`bot-app/src/main/java/com/vingame/bot/domain/alert/model/AlertAudience.java:41-50`

`fromLabel("prodcut")` returns `INTERNAL` identically to `fromLabel(null)`. Fail-safe in
routing terms, but fail-silent diagnostically: a typo'd label in `alerts.yml` produces a
product alert that lands in ops forever and looks exactly like a correctly-labelled
internal one. The compensating control (AD-V4's `AlertRulesAudienceTest` over
`alerts.yml`) is Phase 6 and does not exist yet, so right now nothing catches this.

Fix shape: split the null/blank case (silent, that is the documented default) from the
unrecognised-non-blank case (`log.warn("Unrecognised audience label {} — defaulting to
internal", label)`). One line, and it makes the AD-V4 test a backstop rather than the
only detector.

### [smell] The `product` label has two sources of truth
`bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:1500-1507`
vs `:319-324`

`bot_*` counters, `game_join` and `bots_by_game_status` take `product` from
`Game.getProductCode()`; `environment_join`, `bots_by_env_status`, `bots_managed_by_env`
and `ws_connections_open_by_env` take it from `BotGroupRuntime.product`, which is
`Environment.getProductCode()`. Nothing enforces that a `Game` and the `Environment` it
belongs to agree, and Implementation Note 4 says `Game.productCode` is null on older
documents — so it is entirely possible for `bots_managed_by_env{product="116"}` and
`bot_messages_total` (no `product` at all) to describe the same bots. Any future rule
that joins across the two families, or any `sum by (product)` dashboard panel, will
quietly split.

The `GameNoRounds` rule planned for Phase 3 happens to be safe (both of its operands are
Game-sourced), which is why this is a smell rather than a bug today.

Fix shape: pick one source. `Environment.productCode` is the more reliable of the two
(the runtime always has an `Environment`, and Note 4 only calls out `Game`), so
resolving the game rows through the runtime's product — or falling back to it when
`Game.productCode` is null — would make the label single-valued per environment by
construction.

### [smell] `BotGroupRuntime.startBot` re-implements `Bot.productCode()`
`bot-app/src/main/java/com/vingame/bot/infrastructure/runtime/BotGroupRuntime.java:208-209`

```java
config.getGame().getProductCode() != null
        ? config.getGame().getProductCode().getCode() : null,
```

is character-for-character the body of `Bot.productCode()`
(`bot-engine/.../core/Bot.java:390-394`), which was added in the same commit and is
`protected` so this site cannot reach it. Two copies of a null-guard that must stay in
step with a nullable Mongo field. Either promote `Bot.productCode()` to `public` and
call it (the `Bot` is in hand here), or put a `static String productCode(Game)` helper
on `Game` / a small util and have all three sites — here, `Bot`, and
`BotGroupBehaviorService.product(Game)` — use it.

### [smell] The 3-arg `BotMdc.setGroupContext` overload is dead code, and the counter it was added for still has no `product`
`bot-api/src/main/java/com/vingame/bot/common/logging/BotMdc.java:77-81`

No production call site passes the third argument — all nine still use the 2-arg form
(`BotGroupBehaviorService:438,487,537,567,817,877,1558,1644`, `ActivationScheduler:116`).
The consequence is concrete at `BotGroupBehaviorService.java:567-586`: the group MDC
established there exists *specifically* so `bot_creation_failures_total` is tagged (see
the comment at `:560-566`), and it now tags `botGroupId` + `environmentId` but not
`product` — even though `runtime.getProduct()` is available a few lines away.

`BotMetricsTest.productLabel_isEmittedAcrossTheRoutableCounters` asserts that
`bot_creation_failures_total` carries `product` ("every counter an audience:product
Prometheus rule can be built on must carry `product`"), which passes only because the
test sets MDC by hand. In production that counter is unroutable. Either wire the
overload in at the group-scoped sites or drop it and correct the test's claim.

### [smell] A suppressed customer copy counts as `misrouted` and logs at DEBUG
`bot-app/src/main/java/com/vingame/bot/domain/alert/service/AlertRouter.java:185-201`

For an `audience: both` alert whose product has no room, the customer copy misroutes to
the ops room, collides with the technical copy already there, and is suppressed. The
dedupe itself is right and is what AD-V5 asks for. Two things around it are not:

1. The suppressed decision keeps `outcome="misrouted"`, so `alert_dispatch_total`
   reports a delivery for content that reached nobody. The javadoc's justification
   ("the content did reach ops, via its sibling") is not accurate — the *technical*
   copy reached ops; the `public_summary` was never rendered to anyone.
2. The only trace is `log.debug("Suppressed a duplicate message for room {} …")`. A
   customer notice being dropped is the exact loss AD-V5's amendment exists to avoid,
   and it is invisible at the production default.

Fix shape: give the suppressed decision `outcome="dropped"` with a distinct reason
(`reason="deduped"` or `"no_room"`), and log it at WARN when the register is `CUSTOMER`.

### [smell] `resolveProduct` catches `Exception` and logs the failure at DEBUG
`bot-app/src/main/java/com/vingame/bot/domain/alert/service/AlertmanagerWebhookService.java:255-265`

The stated justification — a deleted environment must not fail the webhook — is sound
for a not-found. It does not extend to what `catch (Exception)` actually covers here: a
Mongo outage, a connection-pool exhaustion, a serialization fault. In those cases *every*
product resolution fails, every `audience: product` alert misroutes to ops, and the only
evidence is a DEBUG line carrying `e.getMessage()` with no stack trace — during an
incident, which is when the alert path matters most.

Fix shape: keep the swallow (AD-3 is right), but log at WARN with the exception, or
narrow the catch and let anything that is not a not-found log at WARN. Bonus: this is
one of the few places where a `bot_*`-independent counter would pay for itself.

### [smell] `viptalk.instance-label` defaults to `staging` in three places
`bot-app/src/main/resources/application.properties:70`, `docker-compose.yml:43`,
`secrets.env.example:34`

AD-V15 makes the instance label the *only* thing distinguishing three instances that
share the same rooms and run the same artifact. A prod box that copies
`secrets.env.example` and forgets one line therefore publishes prod outages stamped
`prod`… no: stamped `staging`, which is worse than an absent label — an operator reading
the ops room will correctly ignore it. The failure is silent and its symptom is
"nobody responded to a prod page".

Fix shape: default to blank (the formatter already omits an empty segment, so the
mislabel becomes a visible absence) or to `unset`, and leave
`VIPTALK_INSTANCE_LABEL=` empty in the example with the comment doing the explaining.

### [smell] `AlertmanagerWebhookService.handle` fabricates a transport result from a dispatch result
`bot-app/src/main/java/com/vingame/bot/domain/alert/service/AlertmanagerWebhookService.java:104-110`

```java
results.add(new VipTalkSendResult(dispatch.outcome(), dispatch.roomCount(), 0, dispatch.detail()));
```

`VipTalkSendResult` documents `statusCode` as "HTTP status returned by VipTalk, or 0 when
no request was made" — here it is hardcoded 0 for results that *did* make requests, and
the record is being used purely as an argument tuple for `AlertDispatch.aggregate`. It
works, but it makes a transport-layer type lie about the transport. Cleaner:
`AlertDispatch.aggregateDispatches(List<AlertDispatch>)`, or have `send` return something
`aggregate` already consumes.

### [smell] The batch `public_summary` is the first one found across a heterogeneous batch
`bot-app/src/main/java/com/vingame/bot/domain/alert/service/AlertmanagerWebhookService.java:159-168`

The batch key is `(product, audience)`, not `(product, audience, alertname)` — the
javadoc's "a batch is one alertname in practice" holds only while `group_by` contains
`alertname`. When it does not, two different `audience: both` rules for one product
collapse into one message whose customer copy speaks for whichever alert came first,
while the technical copy lists both. Not currently reachable (no rule carries
`audience: both` yet), but it is a silent content substitution when it becomes so.

Fix shape: either add `alertname` to the batch key for `BOTH` batches, or refuse to
render a customer copy when a batch contains more than one distinct `public_summary`.

### [style] `outcome` / `reason` are loose `String` constants
`bot-app/src/main/java/com/vingame/bot/domain/alert/service/AlertRouter.java:55-73`

Seven `public static final String`s split across two classes' responsibilities
(`OUTCOME_FAILED` and `REASON_CHANNEL_DISABLED` are declared on `AlertRouter` but only
ever set by `AlertService`). They are metric label values, so they must stay strings on
the wire, but a small enum with a `label()` accessor would keep the vocabulary in one
place and stop a future call site inventing `"drop"` alongside `"dropped"`. Low
priority — flagged only because the counter's cardinality bound in the plan is stated as
a product of these two sets, and that bound is currently enforced by convention.

## Notes

- **`AlertRouter` genuinely is a pure function.** No I/O, no mutable state, three final
  fields, `route` reads only its argument and the registry (which itself reads a final
  string and a compile-time enum). It is safe to call from any thread, including the
  webhook's request thread, and the 11 unit tests need no Spring context. This is the
  right shape and worth keeping as the boundary — the counter deliberately living in
  `AlertService` rather than the router is what makes it possible.
- **Dedupe ordering is deterministic.** The concern in the brief checks out: `route`
  builds decisions in a fixed order (ops-technical first, customer second) from a
  `switch` over a compile-time enum, and `dedupeByRoom` iterates that list, so "first
  decision wins" always means "the internal register wins". There is no map iteration or
  set ordering in the path.
- **No operator text reaches the customer register.** `formatCustomer` builds strictly
  from `severity.getMarker()`, `product.getDisplayName()/getCode()`, `instanceLabel` and
  `publicSummary` — no title, body, `— via` trailer, environment id or metric value can
  reach it, and `contextSegments` is the only shared helper. The `RESOLVED` bug above is
  a *tense* problem, not a leak. Note separately that `audience: product` deliberately
  sends the **technical** register to a product room (AD-V3's own table), so product
  rooms do see titles, bodies, `(env <uuid>)` suffixes and the `— via prometheus`
  trailer. That is the design, but it is worth confirming with whoever owns the TIP room
  relationship before the first product-routed rule ships in Phase 3.
- **Cardinality claims hold.** `product` is functionally determined by `environmentId`
  and by `gameId`, so the Phase 1 changes add a label to existing series rather than new
  series; `bots_managed_by_env` / `ws_connections_open_by_env` are bounded by the
  environment count; `alert_dispatch_total` is bounded at ~5 × 7 × 11. Both new gauge
  names were correctly added to `BotMdcTagsMeterFilter.AGGREGATE_METER_NAMES`, and
  `ObservabilityConfig` is untouched, so AD-V2's unlabelled fleet gauges stay unlabelled.
  One asymmetry worth knowing about: a null product renders as `""` on the MultiGauges
  (`nullSafe`) but is omitted entirely from the MDC-tagged counters. Prometheus treats an
  empty label value as absent, so this is harmless — but it does mean the two families
  will look different in `/actuator/prometheus` output during the Phase 1 verification.
- **`sum(bots_managed_by_env) == bots_managed` is not quite an invariant.**
  `countManagedBotsByEnv` skips runtimes with a null `environmentId`; `getTotalManagedBots`
  counts them. Consistent with the pre-existing `listRunningEnvironmentInfo`, and no
  production path constructs such a runtime — but the plan's Phase 1 verification asserts
  the equality outright, so if that check ever reads non-zero this is the first place to
  look, not a metric bug.
- **`ProductCode.getVipTalkRoomId()` / `hasVipTalkRoom()` are correctly `@JsonIgnore`d.**
  With `@JsonFormat(Shape.OBJECT)` on an enum returned by the unauthenticated
  `/api/v1/brand`, omitting either annotation would have published the room ID (and a
  `vipTalkRoom` boolean). Both are present, and the javadoc says why.
- **The bot token is never logged.** `VipTalkClient` logs `baseUrl` only, and the token
  rides in the URL *path* which is never in a log statement. The one residual exposure is
  `log.warn("VipTalk send … failed in transport: {}", e.getMessage())` — JDK `HttpClient`
  does not put the request URI in `IOException` messages, so this is fine today, but it
  is worth remembering that any future move to a client that does would leak the token
  into WARN-level logs shipped to Loki.
- **Question for the author:** `Alert.announcement(...)` routes through the 5-arg
  constructor and so is stamped `audience = PRODUCT` with a `null` product. `broadcast`
  ignores the audience, so it is inert today — but an announcement that ever reached
  `send()` would take the `no_product` misroute path. Would `INTERNAL` (or a dedicated
  factory that passes the audience explicitly) be more honest?

---

# Code Review — VIPTALK_ALERTING_V2 (Phases 3–6)

Branch: `staging`
Reviewed diff: `git diff main..staging`, scoped to `e435e7a..24bf77b`
(`e435e7a`, `8d2944f`, `48ffc21`, `6508373`, `624048b`, `64166f9`, `ce4deaf`,
`3d84647`, `4302869`, `6fde162`, `f6322c0`, `24bf77b`)

Phases 1–2 are reviewed above and are **not** re-reviewed here.

Checks run:

- `mvn -pl bot-api,bot-engine,bot-app -am test -Dtest='Alert*Test,InfoGauge*Test,BotGroupBehaviorServiceTest,BotMdcTagsMeterFilterTest'`
  → **BUILD SUCCESS, 184 tests, 0 failures** (against the working tree, per the brief).
- `python3 viptalk-shim/selftest.py` → **all checks passed** (30 assertions).
- `promtool check rules prometheus/alerts.yml` → **SUCCESS, 16 rules found**.
- `amtool check-config alertmanager/alertmanager.yml` → **SUCCESS**, 3 inhibit rules, 2 receivers.
- `amtool config routes test alertname=BotManagerDown` → `viptalk-static-down,viptalk`;
  `alertname=EnvironmentSocketDown` → `viptalk`. The two-sibling replacement for the
  plan's `continue: true` is **correct** — see Notes.
- `promtool test rules` against four hand-built series sets, used to prove finding 1
  and finding 6 rather than assert them.

## Verdict

CHANGES_REQUESTED

## Findings

### [bug] `GameNoRounds` fires permanently, and falsely, for any game whose Mongo document has no `environmentId`
`prometheus/alerts.yml:264-281`, with
`bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:1466-1467`
vs `:756`

The rule's two operands must reduce to an identical label set — the plan says so twice,
and the file's own comment says so a third time ("one stray label on either side silences
the rule instead of misrouting it"). `product` was given a single authority in `101064e`
and now agrees. **`environmentId` was not, and does not.**

- left operand, `bots_by_game_status`: `game.getEnvironmentId()` (`:1467`)
- right operand, `bot_messages_total`: MDC, set from `configuration.getEnvironmentId()`,
  which is `group.getEnvironmentId()` (`:756`)

`BotGroupService:273-278` only rejects a game whose `environmentId` *disagrees* with the
group's — it explicitly tolerates `null` (`if (gameEnvironmentId != null && ...)`). A game
document without `environmentId` is therefore a supported state, and in it the gauge row
carries `environmentId=""` (`nullSafe`, which Prometheus treats as absent) while the
counter carries the real UUID. The label sets differ, `unless` matches nothing, and the
left operand passes through untouched.

Confirmed, not inferred — `promtool test rules` with a game emitting 10 `startGame` per
minute and 5 authenticated bots:

```
input: bots_by_game_status{gameId="g1",environmentId="",product="116",status="CONNECTION_AUTHENTICATED"} 5
       bot_messages_total{cmd="startGame",gameId="g1",environmentId="env-1",product="116"} 0+10x40
→ GameNoRounds FIRING at t=25m,  summary: "No rounds on game BauCua (env ) for 20 minutes"
```

The control case (same `environmentId` on both sides) correctly produces no alert. So the
symptom is a **permanent `severity: critical`, `audience: product`** alert, re-sent every
`repeat_interval` (4 h), for a game that is perfectly healthy — into a product room, with
an empty `(env )` in the text. It also destroys the rule's real signal: a reader who sees
`GameNoRounds` firing constantly for a working game will not act on the one that is true.

This is the same class of defect as the two-sources-of-truth smell raised on Phase 1 and
fixed for `product`; the fix was simply not carried to the other label.

Fix shape: mirror `product(runtime, game)`. Add `environmentId(runtime, game)` — prefer
`runtime.getEnvironmentId()` (which the group always has and which `bots_by_env_status`
already uses), fall back to `game.getEnvironmentId()` — and use it in `GameStatusKey` and
`GameInfo`. That makes the label single-valued per group by construction, exactly as
`product` now is. A cheap follow-up is an `AlertRulesAudienceTest`-style guard, or a
`promtool test rules` fixture, pinning that `GameNoRounds` does *not* fire when messages
are flowing.

### [bug] `shim.send()` does raise, contradicting its contract — and the failure produces no HTTP response at all
`viptalk-shim/shim.py:170-195`

The docstring says "Never raises: a failure here must become a 502 to Alertmanager (which
retries), not a stack trace that leaves the notification in an unknown state." The
`urllib.request.Request(...)` construction on `:178-180` is **outside** the `try`, and
that constructor is what parses the URL. Reproduced:

```
>>> c = shim.Config({"VIPTALK_BASE_URL":"api.viptalk.org", "VIPTALK_BOT_TOKEN":"SUPERSECRET-TOKEN-123", ...})
>>> shim.send(c, "hello", ["!ops:x"])
ValueError: unknown url type: 'api.viptalk.org/v1/bot/SUPERSECRET-TOKEN-123/sendMessage'
```

A `VIPTALK_BASE_URL` without a scheme is an entirely ordinary `secrets.env` typo, and it
is one nobody will catch, because `/health` reports `enabled: true` for it (`enabled`
checks only that the token and a room are non-blank — it never validates `base_url`). The
exception escapes `send` → `deliver` → `do_POST`, `socketserver` prints the traceback and
closes the connection **without writing a response**, so Alertmanager sees a transport
error and retries — re-raising, re-printing, forever, during the outage. Neither the 200
nor the 502 branch is reachable, and the `_respond` path never runs.

Fix shape: move the `Request(...)` construction inside the `try` (one indent level), and
add a belt-and-braces `try/except` around the body of `do_POST` that answers 502 on
anything unexpected — the handler is the last line of defence for a process whose whole
job is to answer during an incident. Validating `base_url` at startup (`urlsplit(...).scheme
in {"http","https"}`, else log ERROR and self-disable) would turn this into the same clean
"not configured" state the file already handles well.

### [security] The VipTalk bot token is written to the shim's logs on the two most likely failure paths
`viptalk-shim/shim.py:176`, `:185-195` (and `VipTalkClient.java:154-155` — see below)

The token is placed in the URL **path**, which is fine in itself. What is not fine is that
two log statements print things that contain that path.

**1. VipTalk's own error body echoes the request path.** `spike.md:20` records this
explicitly: "The server echoed the full query string back in the `path` field of the
error." `:188-191` reads up to 300 bytes of that body and logs it at ERROR. Reproduced
against a stub shaped like the spike's 400:

```
ERROR VipTalk rejected the message: HTTP 400 {"statusCode": 400, "error": "Cannot send empty message",
  "path": "/v1/bot/SUPERSECRET-TOKEN-123/sendMessage"}
```

The trigger for a 4xx is, most obviously, **a wrong or expired token** — so the one
condition that guarantees this branch runs is also the condition under which the token is
most sensitive.

**2. The catch-all logs the exception's `repr`, and some of those carry the URL.**
`:193-194` is `log("ERROR VipTalk unreachable: %r" % (error,))`. Reproduced with a token
containing a stray control character (a paste artefact in `secrets.env`):

```
ERROR VipTalk unreachable: InvalidURL("URL can't contain control characters.
  '/v1/bot/SECRET\x01TOKEN/sendMessage' (found at least '\x01')")
```

Both land on stderr → the container's `json-file` log → the Loki stack that ships
everything on this box. The token is then in a log store that is not treated as a secret
store, indefinitely, and rotating it requires noticing first. The repo's own convention
(`tokens.getAuthToken().substring(0, 10) + "..."`) exists precisely to stop this.

**The same defect exists in the app**, at
`bot-app/src/main/java/com/vingame/bot/infrastructure/notification/VipTalkClient.java:154-155`:
`log.warn("VipTalk returned HTTP {} for {} room(s): {}", ..., response.body())`. My Phase
1–2 note said "the bot token is never logged" — that conclusion was reached before the
spike documented the path echo, and it is **wrong**. Both sites need the same fix and it
should land together.

Fix shape: never log a VipTalk response body verbatim. Log `statusCode` plus a redacted
body (`body.replace(token, "***")` is one line and is exact, since the token is a known
string at that point), or extract only `errcode`/`error` from the JSON and log those. For
the `%r` line, log `type(error).__name__` and a redacted `str(error)` rather than the repr.

### [bug] `VIPTALK_ENABLED=false` does not disable the shim
`docker-compose.yml` (viptalk-shim `environment:` block) with
`viptalk-shim/shim.py:126-135`, `secrets.env.example:18-19`

`secrets.env.example` presents `VIPTALK_ENABLED` as the **"Master switch. Leave false
until the bot token and at least one room are set."** The shim is not given that variable
and does not read it. Its only gate is `enabled` = token present **and** at least one room
present.

So an operator on the prod box who sets `VIPTALK_ENABLED=false` — to mute alerting during
a migration, or because something is misfiring — silences the app path and leaves the shim
armed. The next `BotManagerDown` still publishes to the ops room and, if
`VIPTALK_DOWN_ROOM_IDS` is populated, still publishes customer-facing copy into a live
product room. That is the single most sensitive message in the system escaping the switch
documented to stop it.

The same disconnect applies to `VIPTALK_CUSTOMER_NOTICES_ENABLED`: AD-V7 makes it the one
flag governing customer-facing copy, but the shim's customer register is gated by a third,
independent variable (`VIPTALK_DOWN_ROOM_IDS`). Gating on the room list is a defensible
reading of AD-V7 (and is argued for in the compose comment), but it means one policy now
has two switches that can disagree, with no cross-check anywhere.

Fix shape: pass `VIPTALK_ENABLED` and `VIPTALK_CUSTOMER_NOTICES_ENABLED` into the shim and
fold them into `Config.enabled` / `Config.registers()` respectively — about four lines,
and `selftest.py` already has the shape (`test_unconfigured_is_a_quiet_no_op`) to cover
them. If the room-list gate is kept as the customer gate, say so in
`secrets.env.example` next to `VIPTALK_CUSTOMER_NOTICES_ENABLED`, because today that
variable's comment claims a completeness it does not have.

### [smell] `/health` discloses a 4-character token prefix and the token's exact length
`viptalk-shim/shim.py:104-105`, `:154-162`

`_mask` renders `tok-…(20 chars)`. `selftest.py:273` checks only that the *full* token is
absent, which it is — but a prefix plus an exact length is more than a health endpoint
needs, and both are useful to an attacker who has one of the two halves. The endpoint is
unauthenticated and answers to anything on the compose network (Grafana, Loki, Prometheus,
Mongo and the app all sit there).

`enabled`, `opsRooms` and `productRooms` are what the endpoint is *for* — "is the delivery
path wired" — and none of them needs any part of the token. Fix shape: report
`"token": "set"|"unset"` (or a truncated SHA-256, if an operator genuinely needs to
distinguish two candidate tokens without revealing either).

### [smell] BotManagerDown's inhibit rule covers half the new product rules and not the other half, arbitrarily
`alertmanager/alertmanager.yml:96-103`

`equal: ['job']` makes inhibition depend on whether a rule's expression happens to
aggregate `job` away. Confirmed with `promtool test rules` on the alerts' output labels:

| rule | keeps `job`? | inhibited by `BotManagerDown`? |
|---|---|---|
| `EnvironmentSocketDown` / `Degraded` (raw-gauge division) | yes (`job="bot-manager"`, `instance=…`) | **yes** |
| `EnvironmentGroupDead`, `GroupBalanceLow` (bare gauge) | yes | **yes** |
| `EnvironmentDeadBotRatioHigh`, `EnvironmentAuthDown`, `EnvironmentLoginFailing`, `GameNoRounds` (`sum by (…)`) | no | **no** |

Nothing in the design intends that split — it is a side effect of which rules needed
aggregation to line their operands up. The comment above the rule states a purpose ("every
fleet gauge goes stale and its alerts are noise… Suppress them while BotManagerDown is
firing") that it only half achieves, so a reader will trust it further than it goes.

Most of the practical impact is absorbed by the fact that a dead app stops producing the
series entirely, so those alerts resolve rather than fire. What is left is genuine though:
`GroupBalanceLow` and `EnvironmentGroupDead` are silently withheld from product rooms
whenever `BotManagerDown` is active, and their aggregated siblings are not.

Fix shape: decide the intent and encode it once. Either drop `equal: ['job']` and use
`equal: []` with an explicit `target_matchers` list that names the app-scoped alerts
(keeping `job="node"` out by name rather than by accident), or put an explicit
`job: bot-manager` label on the app-scoped rules so the `equal` has something uniform to
match. The current `alerts.yml:437-439` comment about `job="node"` staying visible is
correct either way and worth preserving.

### [smell] The shim publishes "the application is down" for *any* alert routed to it
`viptalk-shim/shim.py:198-221`, `:265-275`

`_describe` extracts the alertnames from the payload, logs them, and then `deliver` throws
them away — the only thing consulted is `status`. The shim's fixed text says the
application is down, and it will say that for whatever arrives. The sole thing keeping it
honest is the `alertname = "BotManagerDown"` matcher in `alertmanager.yml`, i.e. a
different file, editable independently, with no test asserting that the shim's route
matches exactly one alert.

Given that this text can reach customer-facing product rooms, "permissive about the body"
is the right posture for *parsing* but the wrong one for *subject matter*. The file's own
reasoning supports the distinction: it already makes an exception for `status` for exactly
this reason ("flipping `send_resolved` on by mistake cannot publish …").

Fix shape: same shape as the `status` guard — if the payload names alerts and
`BotManagerDown` is not among them, log and skip (still 200). Absent/unparseable
alertnames should still deliver, preserving the "a schema change must not drop an outage
notice" property.

### [smell] A partial-register failure answers 502, so Alertmanager's retry re-delivers the register that already succeeded
`viptalk-shim/shim.py:214-221`

`deliver` sends the technical register, then the customer register, and ANDs the results.
If the ops-room POST succeeds and the product-room POST fails (one bad room ID, a
per-room permission problem, a transient 5xx on the second call), the shim answers 502,
Alertmanager retries the whole notification, and the ops room receives the outage message
again — once per retry — while the product room still receives nothing. Delivery is
per-register; the retry is all-or-nothing.

Fix shape: track per-register success across the retry (a tiny `dict` keyed by register
name, cleared once everything has gone out) so a retry only re-attempts what failed; or,
if that state is unwelcome in a process that prides itself on having none, at least log
loudly which register is being re-sent so the duplicates in the room are explicable.

### [smell] `viptalk-shim/selftest.py` runs in no build
`viptalk-shim/selftest.py`

The file's own opening argues the case better than I can: "The shim sits on a path that
only executes during an outage, which is the worst possible place for an untested bug."
It is a good test — a real socket round trip in both directions, seven scenarios, and it
passes. But nothing invokes it: no `pom.xml` reference, no `exec-maven-plugin`, no
`deploy.sh` step. `grep -rn 'selftest' pom.xml bot-app/pom.xml deploy.sh` is empty.

Meanwhile the two adjacent guarantees *did* get build enforcement
(`AlertRulesAudienceTest`, `AlertmanagerRoutingTest`), which makes the omission look like
an oversight rather than a decision. A Python test that only runs when someone remembers
is a Python test that will be stale the first time it matters.

Fix shape: an `exec-maven-plugin` execution bound to `test` in `bot-app` (skippable with
`-DskipTests`), or — since the JVM is already running the other two file-level tests — a
JUnit test that shells out to `python3 viptalk-shim/selftest.py` and asserts exit 0,
`Assumptions.assumeTrue` on `python3` being present, matching the CWD-tolerant pattern the
other two tests already use.

### [smell] A malformed numeric environment variable crash-loops the shim, against the posture the file argues for
`viptalk-shim/shim.py:118-119`

```python
self.timeout = float(get("VIPTALK_TIMEOUT_SECONDS", "10") or "10")
self.port = int(get("VIPTALK_SHIM_PORT", "8080") or "8080")
```

`VIPTALK_TIMEOUT_SECONDS=10s` (or `PORT=8O80`) raises before `serve()` is ever reached.
With `restart: unless-stopped` that is a crash loop, and the docstring on `enabled` is
explicit that this is the outcome to avoid: "a crash-looping container would be one more
thing broken during the outage this is meant to report." The blank case is handled (`or
"10"`); the malformed case is not, and it is the more likely of the two given these are
hand-edited on the host.

Fix shape: a `_number(name, default)` helper that catches `ValueError`, logs a WARN naming
the variable, and returns the default. Two lines, and it makes the config layer as total
as the request layer already is.

### [smell] `listGroupBalances` re-implements the private `Bot.resolveDepositAmount()`
`bot-app/.../BotGroupBehaviorService.java:1617-1619` vs
`bot-engine/.../core/Bot.java:412-418`

```java
long depositAmount = behavior.getDepositAmount() > 0
        ? behavior.getDepositAmount()
        : Bot.DEFAULT_DEPOSIT_AMOUNT;
```

is the body of `Bot.resolveDepositAmount()`, which is `private` and so unreachable from
here. This is the third instance of the pattern the Phase 1–2 review raised for
`Bot.productCode()` — a resolution rule copied because the original is not visible.

It matters more than the earlier copies, because the two must agree *numerically*: the
whole argument for the `0.10` threshold (AD-V13, restated in `alerts.yml:293-297`) is that
the metric's denominator is the same figure the bot tops up against, so the alert fires
exactly where an auto-deposit group would have topped up. If the two drift, the rule
quietly stops meaning what its comment says. And they are drifting right now — this branch
does not compile against `HEAD` alone precisely because `bot.deposit.amount` is being
changed underneath both copies.

Fix shape: promote `resolveDepositAmount()` to package-private/public on `Bot` and call it
(the `Bot` instances are already in hand in this loop), or lift it to a
`static long resolveDepositAmount(BotBehaviorConfig)` that both sites call.

### [smell] The shim's customer text and `public_summary` must stay byte-identical, and nothing enforces it
`viptalk-shim/shim.py:75-81` and `prometheus/alerts.yml:85`

Three separate comments assert the invariant (`shim.py:75-77`, `alerts.yml:73-75`,
`secrets.env.example:66-70`). They are correct today — I diffed them, the strings match
exactly. But an invariant stated three times and checked zero times is a comment, not an
invariant, and the failure mode it guards against is "the same outage reads two different
ways depending on which path delivered it" — which is only observable during an outage, by
whoever is least able to act on it.

`AlertRulesAudienceTest` already parses `alerts.yml` and already knows how to find
`BotManagerDown`'s `public_summary`. Fix shape: read `DEFAULT_DOWN_TEXT` out of `shim.py`
(a regex over the file, or a `python3 -c "import shim; print(shim.DEFAULT_DOWN_TEXT)"`)
and assert equality — the same file-level-contract argument the test class already makes
for `audience`.

### [smell] Nothing suppresses the customer-facing outage notice for planned maintenance
`prometheus/alerts.yml:76-92`, `deploy.sh:8`, `alertmanager/alertmanager.yml`

`BotManagerDown` is `audience: both` and, on prod with `VIPTALK_CUSTOMER_NOTICES_ENABLED=true`,
publishes "ALERT! Bot Management application is experiencing issues" to a live product
room. There is no silence mechanism: `deploy.sh` creates no Alertmanager silence, and the
`for: 2m` window is the only thing standing between a planned deploy and a customer-facing
incident notice.

Today this is accidentally survivable: `deploy.sh:8` kills every container matching
`name=bot-`, which under the single-compose layout includes Prometheus and Alertmanager
themselves, so nothing observes the failed scrapes. That is luck, not design, and it
evaporates the moment the observability stack is separated from the app's compose project
— which is a stated goal, and is already logged as a hazard ("bot redeploys take down
Grafana/Prometheus/Loki too").

Fix shape: have `deploy.sh` post a short silence to Alertmanager (`amtool silence add
alertname=BotManagerDown --duration=10m`, or the equivalent `curl` to
`/api/v2/silences`) before the kill, and let it expire. Cheap, explicit, and it makes the
"was this deploy or an incident?" question answerable from the room history.

### [smell] The shim reads the request body with no size limit and no request limits
`viptalk-shim/shim.py:241-249`

`self.rfile.read(length)` allocates whatever `Content-Length` claims. `ThreadingHTTPServer`
spawns a thread per connection with no cap. The exposure is limited — no host port
mapping, so only the compose network reaches it — but this is a process whose defining
property is that it must be alive when everything else is not, and it currently has no
defence against a single misdirected large POST or a connection flood from a compromised
sibling container.

Fix shape: cap at something generous but finite (`min(length, 1 << 20)`) and drain the
rest; the body is discarded anyway, so nothing is lost.

### [style] `_read_body`'s stated rationale does not apply — the server speaks HTTP/1.0
`viptalk-shim/shim.py:245-249`

The comment justifies the drain with "leaving bytes on the socket breaks keep-alive and
makes Alertmanager's next POST look like a transport error". `BaseHTTPRequestHandler`
defaults `protocol_version = "HTTP/1.0"` and is not overridden here, so every response
closes the connection and there is no keep-alive to break. Draining is still the right
thing to do — the reason is just wrong, and a wrong reason in a comment is what stops the
next person from setting `protocol_version = "HTTP/1.1"` (which *would* make the comment
true, and which is probably worth doing given Alertmanager's Go client will try to reuse
connections).

### [style] Python 2-isms in a file that only ever runs on 3.12
`viptalk-shim/shim.py:108`, `:191`, `:194`, `:203-204`, `:217`, `:232`; same in `selftest.py:61`

`class Config(object)` (explicit `object` base), `%`-formatting throughout, and
`"a" + str(b) + "c"` concatenation in `_mask`. The image is pinned to `python:3.12-alpine`
and the file already uses f-string-era stdlib (`ThreadingHTTPServer`, `urllib.error`), so
`class Config:` and f-strings would be idiomatic and shorter. Lowest priority — flagged
only because the rest of the repo is uniformly modern-Java and this file is the one place
where a reader has no house style to lean on.

## Notes

- **The `continue: true` → two-sibling-routes replacement is right, not merely different.**
  I verified it two ways. Alertmanager's `Route.Match` appends a matching child and only
  falls back to the node's own receiver when `len(all) == 0`, so a lone `continue: true`
  child consumes the alert and the parent receiver is never reached — exactly as the
  commit message and `AlertmanagerRoutingTest`'s javadoc claim. Empirically,
  `amtool config routes test alertname=BotManagerDown` prints
  `viptalk-static-down,viptalk`, and `alertname=EnvironmentSocketDown` prints `viptalk`
  alone. `AlertmanagerRoutingTest.receiversFor` reimplements that walk faithfully,
  including the `matched.isEmpty()` fallback. This was the highest-risk item in the phase
  and it is correct.
- **The other two inhibit rules are sound.** `EnvironmentSocketDown` → `Degraded` on
  `equal: ['product','environmentId']`: both alerts carry both labels (the division keeps
  the LHS label set), and Alertmanager's two-sided-match exclusion means neither rule can
  inhibit itself. `HostDiskSpaceCritical` → `Low` on `equal: ['instance','mountpoint']`:
  both labels survive because the plan's asymmetric `fstype` selector was fixed — the
  selector is now identical on both sides of the division (`alerts.yml:376-377`,
  `:391-392`), so the series pair one-to-one and carry `device`/`fstype`/`mountpoint`
  through. That was a real plan defect and it is properly closed.
- **The rest of the PromQL checks out.** `EnvironmentSocketDown` puts the ratio on the
  *left* of `and`, so `$value | humanizePercentage` renders the percentage and not the
  bot count — the plan's draft had that backwards and the fix is correct and commented.
  `EnvironmentDeadBotRatioHigh` aggregates *both* sides, which is what makes the division
  match at all (a `sum by (…)` result cannot pair with a raw series that still carries
  `job`/`instance`) — also a plan defect, also fixed, also commented. `clamp_min(…, 1)`
  guards the divide-by-zero on all four ratio rules. `1 - avg by (instance) (rate(...))`
  correctly keeps `instance` through scalar-vector arithmetic. `GameNoRounds`' `for: 10m`
  over `increase(...[10m])` really does mean ~20 minutes of silence, as its summary says.
  `promtool check rules` passes on all 16.
- **`ws_connections_open_by_env` cannot go missing when it reads zero.**
  `countOpenWsByEnv` emits a row for every runtime with a non-null `environmentId`
  regardless of how many sockets are open, so its key set is identical to
  `countManagedBotsByEnv`'s. The division therefore always pairs, and a total socket
  outage produces `0/N` rather than an empty result. This is the failure mode that would
  have silently disabled `EnvironmentSocketDown` and it is handled — worth calling out
  because it is invisible from the rule alone.
- **`EnvironmentAuthDown`'s `>= 5` sample floor is more deposit-size-sensitive than the
  comment suggests.** `bot_verify_token_total` is written only by `ApiGatewayClient.getBalance`,
  which `Bot.checkBalance()` calls **only when drift exceeds 1% of the deposit amount**
  (`Bot.java:332-341`) — not once per round. At the `Bot.java:444-448` worked example
  (5,000,000 deposit → 50,000 band) a bot re-reads every few rounds and an environment
  clears 5 easily; at `DEFAULT_DEPOSIT_AMOUNT` (1,000,000,000 → 10,000,000 band) the same
  bot re-reads roughly once per *thousand* rounds and a small environment may never clear
  it in a 10-minute window. The rule is probably saved in the case that matters, because
  once fetches start failing `lastFetchedBalance` stops advancing, drift stays over the
  threshold, and the counter then moves once per round per bot — but that is a
  second-order argument, not the "moves continuously while bots play" the comment asserts.
  Worth one `increase(bot_verify_token_total[10m])` query on Bot-1 before trusting the
  floor; if it reads below ~5 on a healthy environment, drop the floor to 2–3.
- **Phase 4's Java is clean.** `listGroupBalances` materialises `activeBots` before
  averaging, so the `isEmpty()` guard genuinely prevents the divide-by-zero (a live
  `isConnected()` re-evaluation between filter and divide would not have);
  `botInstances` is a `CopyOnWriteArrayList`, so iterating it from the 10 s refresher
  thread cannot throw `ConcurrentModificationException`; both new balance gauges are
  built from **one** `listGroupBalances()` snapshot, so the absolute and the ratio can
  never describe different instants; every `register` passes `overwrite=true`, so a group
  that stops or re-enables auto-deposit has its row *removed* and the alert resolves
  rather than freezing; `refreshQuietly` still catches, so a throw in the new code cannot
  kill the scheduler; and all three new names are on `BotMdcTagsMeterFilter.AGGREGATE_METER_NAMES`,
  so the refresher thread's MDC cannot leak onto them. `GroupBalance.ratio()`'s
  `depositAmount > 0` guard is unreachable given the `continue` above it — harmless, but
  note it returns `0d`, which would render as a firing alert if it ever became reachable;
  returning `Double.NaN` would fail safe instead.
- **`AlertRulesAudienceTest` is the right test and slightly under-reaches.** Pinning
  `BotManagerDown` *by name* is exactly right — two pieces of infrastructure are keyed on
  that string. The one guarantee it does not cover is the routing precondition: an
  `audience: product` rule is only routable if its output series carries `product` or
  `environmentId`. All seven current ones do, but that is a property of their expressions,
  and the next rule someone adds with a stray `sum by (gameId)` would pass the audience
  test and then misroute to ops forever. A `assertThat(expr).contains("product")`-grade
  check would be crude; a `promtool test rules` fixture asserting the label set of each
  `audience: product` alert would be the real thing, and would also have caught finding 1.
- **node-exporter's setup is correct for the thing it was bought for.** `pid: host` gives
  it the host mount table via `/proc/1/mounts`, `--path.rootfs=/host` is stripped back off
  before the `mountpoint` label is emitted (so the host root really does read back as
  `mountpoint="/"`, which is what the disk rules select on), and the exclusion regex is
  applied after the strip so `/host` never appears. The restated default exclusion set is
  right to restate — naming the flag replaces the defaults wholesale. Two caveats for the
  release check that is already flagged in the file: `node_filesystem_avail_bytes` excludes
  the root-reserved blocks (typically 5%), so "7% free" trips at roughly 12% actually-free — a
  conservative direction, but it means the threshold is tighter than it reads; and if
  Bot-1 ever gets a separate `/var/lib/docker` mount, `mountpoint="/"` stops covering the
  Loki volume, which is the exact thing this rule exists for.
- **Security posture of the two new containers.** `node-exporter` mounts `/:/host:ro` —
  the upstream-recommended posture, but it does mean anything that can execute in that
  container can read every host file including `secrets.env` and `~/.ssh`. `viptalk-shim`
  accepts an unauthenticated POST from anything on the compose network and will publish
  customer-facing text in response to it. Both are consistent with the existing posture
  (the app's own `/api/v1/alerts/**` is unauthenticated and *is* host-published on 8080),
  so neither is a regression — but the shim's blast radius is a live product room, which
  is a new kind of target on this network. Worth naming when the `/api/v1/alerts/**` auth
  item is eventually picked up.
- **`secrets.env` handling is still uncommitted**, and Phase 6 now depends on it harder
  than Phase 2 did. `git show HEAD:.gitignore | grep secrets` and
  `git show HEAD:deploy.sh | grep secrets` are both empty; the working tree has both
  (`.gitignore:47-49`, `deploy.sh:23-32`). The shim reads `VIPTALK_BOT_TOKEN`,
  `VIPTALK_OPS_ROOM_ID` and `VIPTALK_DOWN_ROOM_IDS` through the `.env` that merge
  produces, so on the branch as committed the shim deploys permanently unconfigured —
  it would log its "shim is NOT configured" WARN and answer 200 to every outage. This is
  the Phase 1–2 security finding, not a new one; noting it only because its consequence
  now extends to the out-of-band path.
- **Question for the author:** `deliver()` sends the two registers sequentially with a
  10 s timeout each, so a hung VipTalk holds the request ~20 s. Alertmanager's webhook
  notification runs under the group's context and will give up before that in some
  configurations, then retry — producing a second in-flight send while the first is still
  blocked. Sending the two registers concurrently (two threads, join with a deadline), or
  dropping `VIPTALK_TIMEOUT_SECONDS` to ~5, would keep the worst case inside a single
  notification attempt. Not urgent, but it is the one place where the shim's blocking
  behaviour is visible to Alertmanager.
