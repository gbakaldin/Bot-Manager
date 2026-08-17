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
