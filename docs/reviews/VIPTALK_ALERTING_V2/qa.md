# QA — VIPTALK_ALERTING_V2 (Phases 1 & 2)

**Verdict:** PASS
**Build:** `mvn test` → 899 tests, 0 failures, 0 errors (reactor green; baseline before my
changes was also green, 866 tests)
**Scope:** commits `56c461a`, `3c02ef4` (Phase 1) and `d0f07d6`, `adb099e`, `c841c32`
(Phase 2), on branch `staging`. Phases 0/3–6 not reviewed.

Dev's own suite for these two phases is unusually good — the three user requirements,
the AD-V5 misroute, the dedupe guard and the fail-closed customer register all had at
least one honest test before I started. My additions attack the same properties from
angles the existing tests do not cover: composition (webhook → router → rooms, all real),
adversarial content in the customer path, and an A/B verification of the Phase 1
cardinality claim rather than a restatement of it.

---

## Tests added / updated

- `bot-app/src/test/java/com/vingame/bot/domain/alert/service/AlertRoutingRequirementsTest.java`
  (new, 12 tests) — the three user requirements asserted **at the room**, through the real
  `AlertmanagerWebhookService` → `AlertService` → `AlertRouter` → `AlertRoomRegistry` →
  `AlertMessageFormatter` chain, with only the transport and the Mongo lookup stubbed.
  Covers: a product room receiving two different environments of its product (both by
  `product` label and by the `environmentId` → Mongo hop); ops and product rooms not
  crossing when one Alertmanager group carries both an `internal` and a `product` alert;
  an `internal` rule carrying a `product` label *and* a `public_summary` still not
  reaching the product room; the two-register outage; fail-closed suppression; AD-V7
  degradation; the AD-V5 misroute marker; the one-ops-message dedupe end to end; and the
  HTTP-visible outcome of the unwired-product probe.
- `bot-app/src/test/java/com/vingame/bot/domain/alert/service/CustomerRegisterFailClosedTest.java`
  (new, 10 tests) — adversarial cover for the only path that can put operator wording in a
  product room. Hostile technical content (`OutOfMemoryError`, an environment UUID, an
  actuator URL) in title/body/source with a valid `public_summary`; all four empty forms
  of `public_summary`; a blank customer render being non-deliverable; the operator HTTP
  path (`Alert.forProduct` / `withProduct`) being incapable of producing a customer
  message; `audience: product` + a stray `public_summary` staying technical; the
  ops-room-equals-product-room collision; and the webhook never promoting
  `summary`/`description`/`alertname` into `public_summary`.
- `bot-engine/src/test/java/com/vingame/bot/infrastructure/observability/ProductLabelCardinalityTest.java`
  (new, 3 tests) — the AD-V1 zero-new-time-series claim verified by **A/B replay**: a
  simulated fleet (4 games × 3 groups × 20 bots × 5 counters, 3 environments, 3 products)
  is replayed twice, once with the `product` MDC key and once without, and the resulting
  `bot_*` series counts must be equal. Plus: no identity tag is dropped in the process,
  and a documented negative showing what a non-functionally-determined source would cost.
- `bot-engine/src/test/java/com/vingame/bot/domain/bot/core/BotProductLabelTest.java`
  (new, 4 tests) — `Bot.productCode()`, the source that makes the dependency hold: numeric
  `ProductCode.getCode()` (not enum name, not display name), null for a `Game` document
  without `productCode`, and every bot on one `Game` agreeing on the product.
- `bot-app/src/test/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorServiceTest.java`
  (+1 nested class, 4 tests) — the same cardinality claim over the **real** gauge
  aggregation: game rows stay one per `(gameId, status)` across two groups on one game;
  env rows stay one per environment; `sum(bots_managed_by_env) == bots_managed` holds even
  when two runtimes on one environment disagree on the product; and the product provenance
  of the game-scoped vs env-scoped gauges is pinned (see Gaps).

## Coverage of the diff

| Production file | Test file(s) | What is covered |
|---|---|---|
| `alert/service/AlertRouter.java` | `AlertRouterTest` (dev), `AlertRoutingRequirementsTest`, `CustomerRegisterFailClosedTest` | Every audience × (wired / unwired / unresolvable product) × (ops room set / unset) × (customer notices on / off) × (summary present / absent). Dedupe by room, including the collision case. Totality on null alert / null audience. |
| `alert/service/AlertService.java` | `AlertServiceTest` (dev), both new suites | One POST per distinct text; `alert_dispatch_total{outcome,reason,product}` for sent / misrouted / dropped / failed / channel-disabled; skipped-not-failed when nothing routes. |
| `alert/service/AlertMessageFormatter.java` | `AlertMessageFormatterTest` (dev), `CustomerRegisterFailClosedTest` | Both registers; customer render carries marker + product + instance + `public_summary` and nothing else; verbatim (incl. non-ASCII) summary; blank on absent summary; null register ⇒ technical. |
| `alert/service/AlertmanagerWebhookService.java` | `AlertmanagerWebhookServiceTest` (dev), `AlertRoutingRequirementsTest` | `audience` off the rule then `commonLabels` then INTERNAL; `public_summary` never synthesised; batching re-keyed on `(product, audience)`; per-payload memoisation; malformed payloads inert. |
| `alert/model/{Alert,AlertAudience,AlertRegister}.java` | `AlertAudienceTest` (dev), `CustomerRegisterFailClosedTest` | Label parsing incl. typo → INTERNAL; the 5-arg constructor's PRODUCT/null defaults; `withProduct` preserving the (absent) summary. |
| `alert/service/AlertRoomRegistry.java`, `brand/model/ProductCode.java` | used as the source of truth in every new test (no literal room IDs) | `roomFor` empty for unwired products; wiring a new room cannot break the suites. |
| `common/logging/BotMdc.java`, `bot/core/Bot.java` | `BotMdcTest` (dev), `BotProductLabelTest` | `product` set / skipped / cleared; derivation from the bot's own `Game`. |
| `observability/BotMetrics.java`, `BotMdcTagsMeterFilter.java` | `BotMetricsTest`, `BotMdcTagsMeterFilterTest` (dev), `ProductLabelCardinalityTest` | Label on all five routable counters, numeric form, omitted when absent, excluded from fleet aggregates, **and** costing no series. |
| `botgroup/service/BotGroupBehaviorService.java`, `observability/InfoGaugeRefresher.java`, `runtime/BotGroupRuntime.java` | `BotGroupBehaviorServiceTest`, `InfoGaugeRefresherTest`, `InfoGaugePrometheusScrapeTest` (dev) + new nested class | New tuple fields, the two per-env gauges (incl. the load-bearing zero row and the `isConnected()` predicate), scrape-level label sets, row-count invariance, fleet-sum invariant. |

## Gaps

Behaviour the diff changes that tests do not (or cannot) pin, and what I found while
trying to break it. None of these blocks Phase 1 or Phase 2.

1. **The plan's Phase 2 verification block is stale, not the code.** It expects the P_097
   probe to answer `SKIPPED` with `outcome="dropped",reason="no_room"`. The amended AD-V5
   behaviour — `SENT`, one room, `outcome="misrouted"`, `↪️ MISROUTED` in the body — is
   what the code does and what `AlertRoutingRequirementsTest.unwiredProductProbeAnswersSent`
   now pins. The Releaser should compare against the test, not against the plan text.
2. **Requirement B is deliberately bent while 9 of 10 products are unwired.** The ops room
   *does* receive product-scoped alerts today, tagged as misrouted. That is AD-V5 as
   amended, and it is tested as such. B2 only holds strictly for products that are wired,
   which is exactly what `productGoesToItsOwnRoomAndNeverToOps` and my
   `opsRoomTakesInfrastructureOnlyAndProductNoiseStaysOut` assert. Watch
   `alert_dispatch_total{outcome="misrouted"}` in production; it is the retirement signal.
3. **Two different product provenances (worth a follow-up, now pinned).**
   `bots_by_game_status` / `game_join` take `product` from `Game.productCode`;
   `bots_by_env_status` / `bots_managed_by_env` / `ws_connections_open_by_env` /
   `environment_join` take it from `Environment.productCode` (threaded through
   `BotGroupRuntime` at group start). A `Game` whose `productCode` contradicts its
   `Environment`'s routes its game-scoped alerts (Phase 3 `GameNoRounds`) and its
   env-scoped alerts (`EnvironmentSocketDown`) to **two different product rooms**, with no
   warning anywhere. `gameAndEnvRowsHaveDifferentProductProvenance` pins the current
   behaviour so the divergence is a visible decision. Cheap mitigations: a startup
   consistency log, or a one-off Mongo backfill/validation before Phase 3 ships rules that
   depend on it.
4. **`Environment.productCode` is read once, at group start.** Editing it under a running
   fleet leaves two runtimes on one environment carrying different products — an extra
   series row until the group restarts. Transient and self-healing; the plan's verification
   invariant `sum(bots_managed_by_env) - bots_managed == 0` still holds (pinned).
5. **Room-collision configuration is silently lossy.** If `viptalk.ops-room-id` is set to a
   product's room ID, an `audience: both` alert dedupes down to the technical copy and the
   customer notice is dropped with only a DEBUG line. Pinned as behaviour; consider raising
   that log to WARN, since it is a misconfiguration rather than a routine suppression.
6. **Mixed-batch `public_summary`.** Batching keys on `(product, audience)`, so a batch of
   two `audience: both` alerts where only the first declares a `public_summary` publishes
   that one summary as the notice for the whole batch. Fail-closed still holds — the copy
   is always a *declared* summary and never synthesised from technical text — but the
   notice can under-describe the batch. Pinned as documented behaviour
   (`batchedCustomerCopyIsOneOfTheDeclaredSummaries`); in practice Alertmanager groups by
   `alertname`, so a mixed batch is rare.
7. **`AlertController` has no test at all** (V1 base code, carried into git by Phase 2's
   commits because it was untracked). Nothing pins AD-8's load-bearing status mapping —
   502 on VipTalk failure so Alertmanager retries, 200 when the channel is disabled — nor
   the `BadRequestException` on an unknown product. Pre-existing, but the endpoint can now
   write customer-facing wording into a product room, so it deserves a MockMvc slice test
   in a later pass.
8. **Tests that assert implementation rather than behaviour** (dev's suite; not fixed by
   me, since they are green and harmless):
   - `BotMetricsTest.productLabel_addsNoNewTimeSeries_beyondTheExistingIdentitySet` — "10
     increments under one constant MDC yield one counter" is true of *any* label and does
     not test the AD-V1 claim. `ProductLabelCardinalityTest` now tests the claim properly;
     I left the original in place as a cheap smoke test.
   - Several `AlertRouterTest` cases index the decision list positionally
     (`routed.get(1)`), coupling them to the router's internal ordering rather than to a
     room or a register. They would go red on a pure reordering. My suites assert by room
     and register instead.
   - `InfoGaugeRefresherTest` asserts label values that it also supplies through a mocked
     `BotGroupBehaviorService`, so it pins the refresher's tag mapping, not where the
     labels come from. The new nested class in `BotGroupBehaviorServiceTest` covers the
     source side.
9. **Not testable here, deferred to the release check:** the actual `/actuator/prometheus`
   scrape on Bot-1 (`grep -c 'product="'`, `sum(bots_managed_by_env) - bots_managed`), and
   any real VipTalk delivery. No network in tests, by policy.
10. **Out of scope by instruction:** Phases 3–6 (rule labelling, `AlertRulesAudienceTest`
    over `alerts.yml`, node-exporter, the out-of-band app-down receiver, `group_balance_ratio`).

## Failures (if any)

None. Full reactor: `Tests run: 899, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS.
All 33 new tests pass; no existing test needed modification.

---

# QA — VIPTALK_ALERTING_V2 (Phases 3, 4, 5 & 6)

**Verdict:** PASS (with one product defect to fix and one release-ordering hazard to
respect — neither blocks the phases as designed)
**Build:** `mvn test` → 1697 tests, 0 failures, 0 errors — bot-api 103, strategies 111,
messages 136, bot-engine 383, bot-app 964. Baseline before my changes was green at
1668 (bot-engine 378, bot-app 940).
**Scope:** commits `e435e7a`…`24bf77b` on `staging` (Phase 3 rules + audience labels,
Phase 4 balance/dead-group gauges, Phase 5 node-exporter + host rules, Phase 6 shim +
Alertmanager fan-out + `BotManagerRestarted`). Phases 0–2 not re-reviewed.
**My commit:** `55ea729`.

The four known deviations from the plan are all improvements on it and are handled
cleanly; I re-derived each rather than taking them on trust, and added standing guards
so none can silently regress:

- **The `$value` operand order.** `a and b` yields *a*'s values, so the plan's
  `bots_managed_by_env > 0 and (ratio) < 0.5` would have rendered "2000%" for 20 bots.
  Dev's swap is correct and is now enforced for every percentage-rendering rule by
  `AlertRuleMetricsTest.percentageRenderingRulesPutTheRatioFirst`, with a
  `bareValueRulesAreNotRatios` mirror so `EnvironmentGroupDead` can never become a
  fraction.
- **The un-aggregated divisor.** `sum by (product, environmentId)(…) / bots_managed_by_env`
  compares a 2-label set against a 4-label set (Prometheus stamps `job` and `instance`)
  and matches nothing. Wrapping both sides is right.
- **The mount-point exclusion regex.** Naming
  `--collector.filesystem.mount-points-exclude` *replaces* upstream's defaults; the
  plan's version dropped `/var/lib/docker/.+`, which would have added one near-full
  filesystem series per container layer. `AlertPipelineWiringTest` now evaluates the
  committed regex directly: it must not match `/`, and must still match
  `/var/lib/docker/overlay2/…`, `/proc/…` and `/host`.
- **The two sibling routes.** Dev is right and the plan was wrong: a matching child route
  *consumes* the alert, and `continue: true` continues to the next **sibling**, not to
  the parent's receiver — so the plan's single-route form would have delivered the FIRING
  half via the shim and killed the RESOLVED half entirely. `AlertmanagerRoutingTest`
  walks the tree the way Alertmanager does and pins both receivers.
- **The shim running everywhere and self-disabling.** Better than the plan's "absent off
  prod": the receiver lives in the committed `alertmanager.yml`, so an absent container
  means Alertmanager retries a connection-refused webhook *during the outage*. Off-prod
  it still delivers the technical register to the ops room, labelled `[staging]`.

## The three declared gaps — what I closed and what I could not

| Gap | Status | Why |
|---|---|---|
| **1. The shim has never talked to the real `api.viptalk.org`** | **Not closable locally — release-time on the host.** | I closed the shim-side half: `selftest.py` now pins the exact request the shim emits (`POST /v1/bot/<token>/sendMessage`, `Content-Type: application/json`, body keys *exactly* `{text, roomIds}`), which is byte-for-byte the shape Phase 0 proved returns 200. What no test here can cover is the transport from inside `python:3.12-alpine`: TLS trust store, egress, any WAF that treats `Python-urllib/3.12` differently from `curl`. That is a host fact. |
| **2. Alertmanager has never POSTed to the shim over the compose network** | **Mostly closed statically; the TCP hop is release-time.** | `AlertPipelineWiringTest.theShimReceiverUrlMatchesTheShimService` proves the receiver URL's host is a declared compose service and its port equals `shim.py`'s own default *with no compose override* — the three ways this hop can be misconfigured in the repository. What remains is only whether the packet arrives. |
| **3. `process_start_time_seconds` not confirmed present** | **Closed, as far as is possible off-host.** | `RuntimeMetricsExposedToAlertsTest` boots the same actuator metrics auto-configurations the app does, scrapes the resulting `PrometheusMeterRegistry`, and asserts the series exists under that exact name with a plausible epoch value — plus that `application.properties` does not disable the `process`/`jvm` meter families. The `job="bot-manager"` half is a `prometheus.yml` fact, now pinned by `AlertPipelineWiringTest.ruleJobSelectorsMatchScrapeJobs`. `BotManagerRestarted` is no longer reasoned-about. |

**Three commands close what is left, and none of them needs an outage:**

```bash
# gap 2 — the hop, then gap 1 — the delivery, in one go. Watch the ops room.
docker compose exec alertmanager wget -qO- --post-data \
  '{"version":"4","status":"firing","alerts":[{"status":"firing","labels":{"alertname":"BotManagerDown"}}]}' \
  --header 'Content-Type: application/json' http://viptalk-shim:8080/alertmanager

docker compose exec viptalk-shim python -c \
  "import urllib.request,json;print(urllib.request.urlopen('http://127.0.0.1:8080/health').read())"

# gap 3 — one query, no restart needed.
curl -fsS 'http://localhost:9090/api/v1/query?query=process_start_time_seconds{job="bot-manager"}' | jq -r '.data.result[0].value[1]'
```

The first is the important one: a 200 means hops 2 *and* 1 both work and a real message
is in the ops room, which is the whole Phase 6 path minus Prometheus. A 502 means the
hop works and VipTalk refused — check `docker compose logs viptalk-shim`, which prints
the HTTP status and body.

## Tests added / updated

- `bot-app/src/test/java/com/vingame/bot/infrastructure/observability/AlertRuleMetricsTest.java`
  (new, 7 tests) — the rules held against the metrics. Renders the exposition the way
  production does (`InfoGaugeRefresher` gauges + `BotMetrics` counters under a bot's MDC
  + `BotMdcTagsMeterFilter`), then for every rule in `alerts.yml`: every application
  metric it selects exists; every product-routed rule keeps `product` and `environmentId`
  (whether by aggregation or off the raw series); every `{{ $labels.X }}` survives the
  rule's own `by(...)`; both sides of `GameNoRounds`' `unless` reduce to the *identical*
  label set and every label in it exists on both metrics; a rule rendering
  `$value | humanizePercentage` computes the ratio in the operand `and` keeps; a rule
  rendering a bare `{{ $value }}` is not a fraction.
- `bot-app/src/test/java/com/vingame/bot/domain/alert/AlertPipelineWiringTest.java`
  (new, 11 tests) — the five files that must agree. Rule `job=` selectors vs scrape jobs;
  the `node` job vs the node-exporter service (`pid: host`, the `/:/host:ro` mount,
  `--path.rootfs`, no `depends_on`); the exclusion regex evaluated against real mount
  paths; `rule_files` vs the compose bind mount; Prometheus → alertmanager; the shim
  receiver URL vs the compose service *and* `shim.py`'s default port; shim independence
  from bot-manager; compose env-var names vs the ones `shim.py` reads; `DEFAULT_DOWN_TEXT`
  byte-identical to `BotManagerDown`'s `public_summary`; `secrets.env.example` blank for
  every secret-bearing key; and the `VIPTALK_ENABLED` asymmetry (see Findings).
- `bot-app/src/test/java/com/vingame/bot/infrastructure/observability/RuntimeMetricsExposedToAlertsTest.java`
  (new, 3 tests) — `process_start_time_seconds` and `jvm_threads_live_threads` present in
  a real Prometheus exposition under the app's actuator auto-configuration, and
  `application.properties` not disabling them.
- `bot-app/src/test/java/com/vingame/bot/domain/alert/VipTalkShimSelfTestRunnerTest.java`
  (new, 1 test) — runs `viptalk-shim/selftest.py` inside `mvn test`, asserting exit 0,
  `all checks passed` and no `FAIL` line. Skips (does not fail) without `python3`.
- `viptalk-shim/selftest.py` (+8 cases, 7 → 15; ~30 → ~52 checks) — the outage path the
  original suite did not reach: VipTalk **unreachable** (`URLError`, a different branch
  from a rejection and the likelier one in a real incident); **partial delivery** (ops
  accepted, product rooms rejected → 502, every register still attempted, and the
  already-sent copy re-sent on retry); token-with-no-rooms; comma-separated and padded
  room lists; blank instance label; the ops room listed twice; a non-numeric
  port/timeout; an unknown GET path. Also dropped the serve-loop poll interval from
  0.5 s to 0.02 s — the suite went from 9.7 s to 0.7 s, which is what makes it viable
  inside the build.
- `bot-engine/src/test/java/com/vingame/bot/domain/bot/core/BalanceGaugeSemanticsTest.java`
  (new, 5 tests) — AD-V12 against a real `Bot`: the uninitialised sentinel (the defect
  below); the first `checkBalance()` replacing it; the 1%-of-deposit bound holding over
  200 rounds *against a server that settles nothing*; the frozen-when-rounds-stop case
  AD-V12 hands to `GameNoRounds`; and `getMinBalance()` agreeing with the denominator
  `listGroupBalances` computes.
- `bot-app/.../BotGroupBehaviorServiceTest.java` (+2 tests in the existing Phase 4 nested
  class) — the sentinel reaching `group_balance_ratio` as `-20.0`; and a group vanishing
  from the gauge the moment its last bot disconnects (the source side of dev's
  row-removal test).

## Coverage of the diff

| Production file | Test file(s) | What is covered |
|---|---|---|
| `prometheus/alerts.yml` (Phases 3–6) | `AlertRulesAudienceTest` (dev), `AlertRuleMetricsTest`, `AlertPipelineWiringTest` | Audience on every rule + `public_summary`/`public_resolved_summary` on `both` + retired rules staying retired (dev); metric existence, routing-label preservation, annotation-label preservation, `unless` label symmetry, `$value` operand order (mine). |
| `alertmanager/alertmanager.yml` | `AlertmanagerRoutingTest` (dev), `AlertPipelineWiringTest` | The two-sibling-route walk, `send_resolved` split, no secret in the file (dev); receiver URLs vs compose services and the shim's port (mine). |
| `docker-compose.yml` (alertmanager, node-exporter, viptalk-shim) | `AlertPipelineWiringTest` | Service presence, `pid: host`, rootfs mount, exclusion regex semantics, shim independence, env-var name agreement, bind mounts matching every `rule_files` / config path. |
| `prometheus/prometheus.yml` | `AlertPipelineWiringTest` | Both jobs exist and cover every `job=` a rule selects; the `node` job targets the exporter, not the app. |
| `viptalk-shim/shim.py` | `selftest.py` (dev + mine), run by `VipTalkShimSelfTestRunnerTest` | Both registers, exact VipTalk request shape, instance stamp, 502-on-failure (rejection **and** unreachable), partial delivery, disabled states, resolved-suppression, malformed input, room-list parsing, `/health` masking, 404. |
| `BotGroupBehaviorService.countDeadGroupsByEnv` / `listGroupBalances` | `BotGroupBehaviorServiceTest` (dev + mine) | Per-env dead counts incl. the healthy zero and the `sum == groups_dead_currently` invariant; averaging over connected bots only; auto-deposit and no-active-bot exclusions; default-deposit fallback; null-safety; the sentinel; row disappearance. |
| `InfoGaugeRefresher` Phase 4 gauges | `InfoGaugeRefresherTest`, `InfoGaugePrometheusScrapeTest` (dev), `AlertRuleMetricsTest` | Label sets, ratio values, row add/update/remove, MDC-exclusion, scrape-level exposition — and now that the rules actually select what is exposed. |
| `BotMdcTagsMeterFilter` (+3 names) | `BotMdcTagsMeterFilterTest` (dev) | The new gauges never inherit the refresher thread's MDC. (Belt-and-braces: none of the three starts with `bot_`, so the filter would pass them through regardless.) |
| Actuator-sourced series the rules depend on | `RuntimeMetricsExposedToAlertsTest` | `process_start_time_seconds`, `jvm_threads_live_threads`. |

## Findings

### 1. DEFECT — `group_balance_ratio` publishes the uninitialised sentinel, and `GroupBalanceLow` fires on it

`Bot.expectedCurrentBalance` is constructed as **`-100_000_000`** (`Bot.java:106`) and is
only ever written from a server read inside `checkBalance()` / `deposit()` — both of which
run from `onNewSession()` (betting/Tai Xiu) or the spin path (slot), i.e. from the **first
round**, not from connecting. `listGroupBalances()` filters on `isConnected()`, which is
true well before that. So a non-auto-deposit group publishes

```
group_balance_ratio{groupName="…"} -20.0     # -100,000,000 / 5,000,000
```

and `group_balance_ratio < 0.10` fires after its 5 m `for:` window. The plan's own Phase 4
verification says to expect every value in `(0, ~1.0]`, so this is out of contract by the
plan's own statement.

Usually harmless — rounds arrive every 30–60 s, inside `for: 5m`. It bites in exactly the
case that matters: **a game delivering no rounds at all** (the tx7 "0 sessions ever"
shape) freezes every connected bot on the sentinel forever, so `GroupBalanceLow` fires
alongside `GameNoRounds` and publishes *"average balance at -2,000% of its deposit"* into
a product room. It also fires on any group started while its game is between sessions for
more than five minutes.

Pinned, not fixed (QA does not touch `src/main`):
`BalanceGaugeSemanticsTest.expectedBalanceIsANegativeSentinelBeforeTheFirstServerRead`
and `BotGroupBehaviorServiceTest.listGroupBalances_publishesTheUninitialisedSentinel`.
Both pass today and will go red the moment it is fixed — read the `DEFECT:` display names
as the marker.

**Cheapest fix:** in `listGroupBalances()`, count only bots that have completed a server
read (`getLastFetchedBalance() >= 0` — its own sentinel is `-1`) and skip the group when
none qualify. Same shape as the existing `activeBots` guard, same reason. One line each.

### 2. AD-V12 itself is sound — the bound holds, including in the pathological case

Worth stating plainly because it is the load-bearing claim under the whole of Phase 4, and
it survives adversarial reading. `expectedCurrentBalance` cannot run away from server
truth: `checkBalance()` re-reads *and re-synchronises* whenever the two diverge by more
than 1% of the deposit, so the very failure mode that looks most dangerous — the server
settling **nothing** (the IP-not-whitelisted shape in CLAUDE.md) — snaps `expected` back
to the server figure within one 1% band. `expectedNeverDriftsMoreThanOneSyncBandFromTheServer`
runs 200 rounds of that scenario and asserts the worst observed drift is ≤ 1% of the
deposit, against a 10% alert threshold. AD-V12's reasoning is correct **once the first
read has happened**; finding 1 is the only hole in it, and it is at the start, not in
steady state.

### 3. `selftest.py` — was happy-path-plus-two, now covers the outage path

Judgement as asked. The original 30 checks were well written but concentrated: seven of
them were shape assertions on the *same* successful pair of requests, and of the three
non-happy cases (`500` rejection, unconfigured, resolved) only the first is a genuine
outage branch. Not covered at all: **VipTalk unreachable** — a *different* code path
(`URLError`/timeout, not `HTTPError`) and by far the likelier one when the box is already
in trouble; **partial delivery**, where the ops room is served and the product rooms are
not; a token set with no rooms; the comma-separated room form `secrets.env.example`
promises. Those are added. Bigger than any of them: **nothing ran the file**. It was a
test suite outside the reactor, which is documentation that rots silently. It now runs in
`mvn test`.

Two things it surfaced:

- **Partial delivery re-sends.** One register failing returns 502 for the whole webhook,
  so Alertmanager retries and the register that succeeded is delivered **twice**. That is
  the right trade (a duplicate outage notice beats a missing one) but it is real
  behaviour, now written down in the test rather than in nobody's head.
- **A crash-loop on malformed numeric config.** A non-numeric `VIPTALK_SHIM_PORT` or
  `VIPTALK_TIMEOUT_SECONDS` raises `ValueError` out of `Config()` *before the server
  binds*. With `restart: unless-stopped` that is a crash loop, so the shim is down for
  exactly the outage it exists to report, with the only trace in
  `docker compose logs viptalk-shim`. Inconsistent with the file's own posture
  everywhere else ("a payload-schema change is not a reason to drop an outage notice")
  and with `enabled`'s deliberate self-disable-rather-than-crash. Cheap fix: fall back to
  the default on `ValueError`, log it. Pinned in
  `test_bad_numeric_config_fails_loudly_at_startup`.

### 4. `VIPTALK_ENABLED` does not gate the shim

`secrets.env.example` calls it the "master switch" and it is — for the app. The shim has
its own notion of enabled (token + at least one room) and never reads it. On a host with
the token and ops room filled in but alerting deliberately switched off,
`BotManagerDown` still reaches VipTalk through the shim while every other alert is
silent, and every bot-manager restart lasting past `for: 2m` announces itself. Defensible
(the app-down notice is the one you least want a stale switch to suppress) but a surprise
— "alerting is off" is not true while it holds. Pinned in
`AlertPipelineWiringTest.theMasterSwitchDoesNotReachTheShim` so it stays a decision.

### 5. RELEASE-ORDERING HAZARD — the branch does not compile at `HEAD`

Restating as asked, because it is the most likely way this release goes wrong. At `HEAD`,
`BotGroupBehaviorService.listGroupBalances()` (Phase 4, commit `624048b`) makes **three**
references to `BotBehaviorConfig.getDepositAmount()` and `Bot.DEFAULT_DEPOSIT_AMOUNT` —
neither of which exists in any committed file:

```
$ git show HEAD:bot-api/.../BotBehaviorConfig.java | grep -c depositAmount        → 0
$ git show HEAD:bot-engine/.../Bot.java | grep -c DEFAULT_DEPOSIT_AMOUNT          → 0
$ git show HEAD:bot-app/.../BotGroupBehaviorService.java | grep -c '…'            → 3
```

A clean `git clone && git checkout staging && mvn package` **fails to compile**. Every
result in this verdict was produced against the working tree, which carries the
uncommitted `bot.deposit.amount` hunks. Anyone building from the branch alone — a CI job,
a colleague, a rollback to this SHA — gets a broken build with an error that points at
Phase 4 rather than at the missing dependency. The fix is ordering, not code: the deposit
work must be committed **before or with** Phase 4. Given the provenance hazard already on
record from the TIP prod incident (an unvalidated build reaching prod out of uncommitted
files), this deserves handling before the deploy, not after.

## Gaps

Things the diff changes that tests do not (or cannot) pin.

1. **Genuinely host-only, and no test can substitute:** that node-exporter's
   `mountpoint="/"` series exists and matches `df -h /`; that `node_memory_MemTotal_bytes`
   reports the host's RAM and not a container limit; that Alertmanager can open a socket
   to the shim; that the shim's TLS/egress to `api.viptalk.org` works from inside
   `python:3.12-alpine`. The plan's Phase 5/6 verification blocks cover all four and
   should be run as written.
2. **The plan's Phase 6 verification check is wrong and should be reworded** —
   `amtool config show | grep -c viptalk-static-down` will be **1 everywhere**, not `0` on
   staging, because the shim now runs on every instance and the receiver is in the
   committed `alertmanager.yml`. The staging-specific expectation to check instead is
   `docker compose exec viptalk-shim ... /health` reporting `"productRooms": 0` with
   `"enabled": true`. The Releaser should follow this file, not that block.
2b. Same for the Phase 4 block's *"expect each value in `(0, ~1.0]`"* — true only after
   the first round; see finding 1. Until it is fixed, a negative value on a freshly
   started group is expected behaviour, not a broken balance read.
3. **Threshold values are untested by construction.** Every number in `alerts.yml`
   (0.5/0.8 socket, 0.5 auth, ≥5 floors, 0.2 dead-bot, 0.15/0.07 disk, 0.10 RAM, 0.90 CPU,
   every `for:`) is a first guess, as the file says. Nothing here validates that they fire
   when they should or stay quiet when they should — only that the expressions compute
   what the annotations claim. That is a "run it for a week" question.
4. **`promtool check rules` is still not in the build.** `AlertRulesAudienceTest` and my
   suites parse the YAML and reason about the expressions, but nothing proves the PromQL
   *parses*. A syntax error would pass every test here and be rejected by Prometheus at
   load time — visible, but only on the host. Adding `promtool` to the release script (or
   a container-based test) would close it; not worth a Java-side PromQL parser.
5. **Alertmanager's own behaviour is out of reach.** `AlertmanagerRoutingTest` re-implements
   the routing walk; it does not run Alertmanager. Dev's `amtool config routes test` is the
   real evidence and should be re-run on the host after any route change. Same for
   inhibition, grouping, `repeat_interval` and retry-on-502.
6. **`EnvironmentDeadBotRatioHigh` is silent when there are no DEAD bots at all** —
   `bots_by_env_status` emits no `status="DEAD"` row, so the division has no left operand
   and the rule produces nothing. Correct behaviour (no alert when healthy), but it means
   the rule cannot be smoke-tested by observing a zero; it needs a real DEAD bot.
7. **`BotManagerRestarted` is inhibited by `BotManagerDown`** (`equal: ['job']`, and this
   rule carries `job="bot-manager"`). The comment in `alerts.yml` says this delays the
   notification to the next `group_interval` rather than dropping it, since the expression
   stays true for 15 minutes. That is right, but it is a claim about Alertmanager's
   inhibition timing that only the host can confirm — worth a look on the first redeploy
   after this ships.
8. **Not re-reviewed:** Phases 0–2 and the seven-finding fix pass (`a1576b3`…`f683043`),
   per instruction. I did verify one thing that Phase 3 now depends on: `product` has a
   single authority (the Environment, falling back to the Game), which is what makes
   `GameNoRounds`' `unless` able to pair its two sides at all. The Phase 1–2 finding about
   two provenances is fixed and covered by dev's `one authority` test.

## Failures (if any)

None. `Tests run: 1697, Failures: 0, Errors: 0, Skipped: 0` across the reactor — BUILD
SUCCESS. All 29 new JUnit tests and all 15 shim self-test cases pass; no existing test
needed modification. The two `DEFECT:`-prefixed tests pass because they pin current
behaviour; they are the ones that should go red when finding 1 is fixed.
