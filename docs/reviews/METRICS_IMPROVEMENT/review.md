# Code Review — METRICS_IMPROVEMENT

Branch: feat/metrics-improvement
Reviewed diff: `git diff main..feat/metrics-improvement`

## Verdict

PASS

PASS = no `bug` or `security` findings. One `smell` (API-doc drift) and two advisory
notes below; none block.

## Scope reviewed

Production code only (per role): `Bot.java`, `BotMetrics.java`, `MetricKey.java`,
`MetricsQueryService.java`, `MetricSeriesDTO.java`, `application.properties`, and the
two Grafana dashboard JSONs. The large number of unrelated `docs/**`, `.claude/**`,
`seed.js`, `TaiXiuMessages/**`, and `PLUGIN_PLAN.md` files in the branch diff are
non-code artifacts and out of scope for code-quality review.

## Correctness checks performed (all passed)

- **Drain anchor (`recordFetchedBalance`) — no double-count, no missed fetch.**
  Both server-fetch sites (`Bot.java:269` `checkBalance`, `Bot.java:248` post-deposit
  re-fetch) route through the single helper; nothing else writes `lastFetchedBalance`
  from a server value. The `checkBalance → deposit` sequence in `BettingMiniGameBot`
  (300-304) / `SlotMachineBot` (159-163) is correct: the pre-deposit burn is captured
  by `checkBalance`'s fetch, then the deposit top-up's large negative delta floors to 0,
  so the top-up never registers and the burn is never double-counted. First-ever fetch
  (`lastFetchedBalance < 0`) anchors only. `delta == 0` and net-gain deltas pass 0 to
  `incMoneyDrained`, which no-ops, so no phantom-zero series is created.
- **Concurrency on `lastFetchedBalance` — safe.** `GameMsClient.deposit` (line 122-127)
  spawns a thread and immediately `t.join()`s, so the deposit callback's
  `recordFetchedBalance` is effectively synchronous w.r.t. the caller, and `join()`
  establishes happens-before for the `volatile long` write. Per-bot balance fetches are
  serialized on the bot's own scenario thread; there is no concurrent read-modify-write
  of `lastFetchedBalance`. The read-then-write in the helper is therefore not a race in
  practice, and even a hypothetical stale read would only perturb a floored burn metric.
- **`$__range` substitution — safe and bounded.** `MetricsQueryService.applyWindow`
  uses `String.replace` (non-regex; literal `$` is safe). Only the three RTP templates
  contain `$__range`; every non-RTP key passes through untouched (no-op), so applying
  the summary window to all scalar keys is harmless. The edge tests assert no dispatched
  PromQL (instant or range) ever contains the raw token. A scope id cannot smuggle the
  token in — `MetricScope.selector` injection-guards the id against an allow-list.
- **Parity preserved byte-for-byte, not loosened.** `MetricKey` templates and dashboard
  `expr` both store the literal `$__range` / `[24h]`, so `MetricKeyDashboardParityTest`
  still string-equals them with zero substitution. The test change is name-only
  (`RTP_5M`→`RTP`, `RTP_PER_GAME_5M`→`RTP_PER_GAME`); the byte-equality contract is
  intact. The new `MONEY_DRAIN_PER_DAY` GAME/ENV exprs match the dashboard panels
  exactly (`bots_by_game_status` / `bots_by_env_status` divisor per scope).
- **Dashboards.** Both new "Money drain / day" panels use a unique panel `id` (12) within
  their dashboard; no id collision. RTP titles/descriptions updated to drop the "(5m)"
  label, matching the semantic change.

## Findings

### [smell] API contract doc still advertises the removed `*_5m` keys
`docs/api/METRICS_API.md:97,115,137`

AD-7 is a deliberately breaking rename with no back-compat alias: the wire keys become
`rtp` / `rtp_per_game`, and `money_drain_per_day` is added. The dashboard side is pinned
by `MetricKeyDashboardParityTest`, but the human-facing API contract — which the separate
UI repo reads to switch keys "in lockstep" — was added on this branch still documenting
`rtp_5m` (line 97), `rtp_per_game_5m` (line 115), and the `rtp_5m` example payload
(line 137), and it never mentions `rtp`, `rtp_per_game`, or `money_drain_per_day`. Nothing
enforces this doc against the catalog, so it will silently rot and can mislead the UI team
into shipping the now-removed keys (which 400). Fix shape: update the three lines to the
new key names, add a `money_drain_per_day` row to the cross-scope table, and refresh the
example `metrics` block.

## Notes

- **Grafana unit `currencyVND` vs `short` (dev's open question).** `currencyVND` is the
  correct choice: `bot_money_drained_total` is denominated in VND currency units, and the
  panel is "money drain / day," so a currency unit reads correctly and `min: 0` matches the
  floored-at-0 semantics. The only reason to prefer `short` would be if the VND symbol/locale
  formatting is visually undesirable in the stat panel; that is cosmetic and reversible
  without code. No change recommended.
- **Documented upward bias is sound and consistently surfaced.** The floor-at-0 bias (AD-2)
  is explained identically in the `BotMetrics.incMoneyDrained` javadoc, the `Bot`
  helper javadoc, the `MetricKey.MONEY_DRAIN_PER_DAY` javadoc, and both panel descriptions.
  Good single-source discipline; a consumer cannot misread this as net P&L.
- **Test quality is high and not loosened.** `BotTest.MoneyDrainTests` exercises the four
  real paths end-to-end through a `SimpleMeterRegistry` (drop, net-gain, first-fetch,
  deposit top-up) and asserts the full identity-label set; `MetricsQueryServiceEdgeTest`
  adds explicit negative assertions that `$__range` never reaches the client on both the
  instant and range paths. The summary stub correctly substitutes the window before
  matching, mirroring production dispatch.
- **`@Value` field injection** on `rtpSummaryWindow`/`rtpTimeseriesWindow` is the reason the
  tests need `ReflectionTestUtils`. Acceptable and consistent with Spring norms; not worth
  changing, but constructor injection would make the service trivially unit-constructable.
