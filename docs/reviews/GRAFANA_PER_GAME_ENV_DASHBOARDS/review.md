# Code Review — GRAFANA_PER_GAME_ENV_DASHBOARDS

Branch: feat/metrics-game-labels
Reviewed diff: `git diff main..feat/metrics-game-labels`

## Verdict

PASS

No `bug` or `security` findings. Two `smell` findings and one `style` finding,
all advisory.

## Findings

### [smell] Inconsistent null-guarding of `getGameType()` across the three call sites
`src/main/java/com/vingame/bot/domain/bot/core/Bot.java:142`
`src/main/java/com/vingame/bot/infrastructure/runtime/BotGroupRuntime.java:154`
`src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:814`

The new `listRunningGameInfo()` defensively guards `game.getGameType() != null`
(`String gameType = game.getGameType() != null ? game.getGameType().name() : "";`),
but the two MDC call sites added in this branch — `Bot.initialize()` and
`BotGroupRuntime.startBot()` — both call `configuration.getGame().getGameType().name()`
unguarded. `Game.gameType` carries no `@NotNull` enforcement at the model level
(`Game.java:35`), so the three paths disagree on whether `gameType` can be null.

In practice this is unlikely to fire on the production path because `getGame()`
itself was already dereferenced unguarded before this branch
(`...getGame().getName()` predates this change), so a malformed `Game` would have
NPE'd already. The new `.getGameType().name()` simply adds one more unguarded
dereference on a field the model does not guarantee. Either of these is fine in
isolation; having one path guard it and two not is the smell. Fix shape: pick one
policy — if `gameType` is an invariant, drop the defensive branch in
`listRunningGameInfo()` and document the invariant on `Game`; if it is genuinely
nullable, apply the same null-tolerant treatment at the two MDC sites (or extract
a small helper that yields `gameType.name()` or `""`).

### [smell] `bots_by_game_status` / `bots_by_env_status` re-walk all running bots independently of the existing `countBotsByStatus` walk
`src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:849`, `:867`

`countBotsByGameAndStatus()` and `countBotsByEnvAndStatus()` each do a full
nested `runningGroups → botInstances` iteration, and `listRunningGameInfo()` does
a third. The refresher fires all of these (plus `listRunningEnvironmentInfo`)
every 10s, so a single refresh cycle walks every running bot ~3 times. At the
documented scale (tens of groups × ~30 bots) this is trivially cheap and not
worth fixing now, so this is advisory only. Worth noting because the per-game and
per-env status maps differ only in their key, and `listRunningGameInfo` is a pure
projection of the same walk that backs `countBotsByGameAndStatus` (the
`game_info` rows are exactly the distinct `gameId/gameName` of the latter's
keyset). If this ever shows up under load, the four queries collapse cleanly into
one pass that builds all maps together. Correctness of the dedup/grouping itself
is fine: `LinkedHashMap.putIfAbsent` keyed on the Mongo `_id` for the info
gauges, and `merge(key, 1, Integer::sum)` on record keys for the counts, both
read off the `CopyOnWriteArrayList` snapshot, so there is no CME risk against
concurrent bot creation.

### [style] `gameId` tag carries a Mongo `_id` UUID, not the numeric `Game.gameId`
`src/main/java/com/vingame/bot/common/logging/BotMdc.java:36`
`src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:784`

The metric/MDC label named `gameId` is populated from `Game.getId()` (the Mongo
`_id` UUID string), while the entity also has a field literally named
`Game.gameId` (the env-scoped numeric channel id, `Game.java:48`). The naming
collision is a readability trap for anyone reading PromQL who assumes
`gameId == Game.gameId`. The choice itself is correct and deliberate (the numeric
gid collides across products — AD-8 is documented inline at both sites and the
javadoc explicitly calls it out), so this is style-only. No change required given
the documentation; flagged so a future reader does not "fix" it back to the
numeric field.

## Notes

- **`InfoGaugeRefresher` is sound.** The scheduled re-register-rows approach is a
  correct workaround for `MultiGauge` having no value-supplier hook.
  `MultiGauge.register(rows)` replaces the row set each cycle (meters absent from
  the new list are removed), so there is no row duplication or meter leak across
  refreshes, and a game/env that stops drops out on the next cycle as the javadoc
  claims. The refresher is the sole writer to all four MultiGauges (verified by
  grep), so no concurrent-mutation hazard exists. Lifecycle is correct:
  single-thread virtual scheduler created in `@PostConstruct`, `shutdownNow()` in
  `@PreDestroy`, exceptions isolated in `refreshQuietly` so one bad cycle does not
  kill the schedule. The 10s fixed-rate matched to the scrape interval is sensible.
  The ERROR-with-`{}`-and-`e.getMessage()` (no stack trace) matches the existing
  house style in this service (e.g. `BotGroupBehaviorService:920`,
  `:962`), so it is consistent rather than a finding.

- **`ObservabilityConfig` `strongReference(true)` is justified, not papering over.**
  The state object is the singleton `BotGroupBehaviorService`, which is strongly
  reachable for the whole application lifetime. Micrometer's default weak
  reference exists to avoid leaking short-lived state objects; here the object can
  never be collected in production, so a strong reference changes nothing on the
  happy path and removes the documented test-GC flakiness where the gauge could
  return NaN. This is the correct call and the inline comment explains it well.

- **4-arg / 3-arg `BotGroupRuntime` constructor.** The 4-arg delegates cleanly to
  the 3-arg-with-null and the only production caller
  (`BotGroupBehaviorService:241`) uses the 4-arg with `environment.getName()`. The
  3-arg is retained only for tests / ad-hoc tooling that construct a runtime
  without an Environment, and `listRunningEnvironmentInfo()` falls back to the
  env id when the name is null. This is a reasonable overload. Minor: if no
  production path uses the 3-arg, you could fold tests onto the 4-arg and delete
  the overload to keep one constructor; not required.

- **Cardinality discipline vs AD-5.** Adding `gameId` + `gameName` to the per-bot
  `bot_*` series is defensible: both are functionally dependent on `botGroupId`
  (one group → one game), so they are constant within a group's series and add
  effectively zero new cardinality, and the `BotMetrics` javadoc records this as
  AD-9 amending AD-5. The `game_info`/`environment_info`/`bots_by_*` gauges are
  correctly added to the `BotMdcTagsMeterFilter` allow-list so they do not also
  inherit MDC tags from the refresher thread — verified the filter short-circuits
  those names before appending MDC tags.

- **`bots.json` repointing is consistent with the bug fix.** The panels that were
  labelled "per game" but grouped by `gameType` (which previously wrongly held the
  game name) now group by `gameName`; the two genuinely per-`gameType` panels
  (bet rate / bet amount rate) correctly stay on `gameType` with descriptions
  noting the enum semantics. `version` bumped 3→4.

- **New dashboards wire the join gauges correctly.** Both `per-game` and
  `per-environment` use `label_values(game_info{...}, ...)` /
  `label_values(environment_info{...}, ...)` for their template variables and
  filter panels by `$gameId` / `$environmentId`. Panel 2 on per-game
  (`sum(bots_by_game_status{gameId="$gameId"})`) lacks an `or vector(0)` so it
  renders empty rather than 0 when the game has no running bots — cosmetic, not a
  finding.
