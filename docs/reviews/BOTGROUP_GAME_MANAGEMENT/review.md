# Code Review — BOTGROUP_GAME_MANAGEMENT (Phase 1)

Branch: feat/botgroup-game-management
Reviewed diff: `git diff main..feat/botgroup-game-management`

Scope: Phase 1 only (Game env-scoping — model/DTO/mapper/filter/service, routes, and
`scripts/migrations/001_game_env_scope.js`). Plan compliance, test coverage, and deploy
mechanics are out of scope for this review.

## Verdict

CHANGES_REQUESTED

## Findings

### [bug] List route drops unmigrated (null-env) games; only `filter` carries the fallback
`src/main/java/com/vingame/bot/domain/game/service/GameService.java:44` (`findByBrandProductEnv`)
and `src/main/java/com/vingame/bot/domain/game/repository/GameRepository.java:15`.

The two env-scoped read paths use different env-matching semantics:

- `filter(...)` builds an explicit `orOperator(environmentId == envId, environmentId is null)`
  — the intended defensive fallback so a game with a null/absent `environmentId` stays
  visible during the deploy window (before the backfill script runs).
- `findByBrandProductEnv(...)` (backing `GET /api/v1/game/{brand}/{product}/{envId}`) uses the
  derived query `findByBrandCodeAndProductCodeAndEnvironmentId`, which compiles to a strict
  `environmentId == envId` match with **no** null-env fallback.

At runtime, in the deploy window every game still has `environmentId == null` (the script is
Releaser-run *after* the app is up). During that window the list route returns an **empty
array** for every scope while the filter route returns the same games correctly. This directly
contradicts the stated fallback intent (Game.java Javadoc: "Read-side has a defensive fallback
treating a null value as matching any env"; plan Implementation Note 2: unmigrated docs must
"stay visible"). It is an env-scoping inconsistency between two read paths that should behave
identically.

Fix shape: give the list path the same null-env OR fallback as `filter` — either drop the
derived-query method and hand-build the `Query` with the same `orOperator` block, or add a
custom `@Query` on the repository. Both read paths should share one env-criteria helper so they
cannot drift again.

Note also that verification step #2's `jq 'all(.[]; .environmentId==null or ...)'` will pass
*vacuously* on an empty array, so the smoke test as written will not catch this — it needs a
non-empty precondition.

## Notes

- **toEntity is correctly closed to the write surface (good).** `GameMapper.toEntity`
  (`GameMapper.java:70`) is a hand-written `default` method that does not copy
  `environmentId`/`createdAt`/`updatedAt` from the DTO, so a create body cannot inject them;
  the controller sets `environmentId` from the path and `GameService.save` owns the timestamps.
  `updateEntityFromDTO` likewise leaves all three untouched with an explanatory comment. The
  write-surface protection is airtight — worth calling out because it is easy to get wrong with
  auto-generated MapStruct (auto-mapping would have leaked all three).

- **Timestamp stamping is not bypassed on update (verified).** `GameService.update` now routes
  through `save(existing)` (`GameService.java:88`), so `updatedAt` is re-stamped on every
  mutation and `createdAt` is preserved (set-only-when-null). No other persistence path in this
  diff calls `repository.save` directly.

- **Null-env fallback query is semantically correct.** `Criteria.where("environmentId").is(null)`
  emits `{environmentId: null}`, which in MongoDB matches both explicit-null and missing fields,
  so the `orOperator` fallback covers both the "field absent" (pre-field docs) and "field
  present but null" cases. The inline comment is accurate.

- **Migration mapping key matches Spring Data's persisted form (verified).** `Environment` stores
  `brandCode`/`productCode` as `BrandCode`/`ProductCode` enums, which Spring Data MongoDB
  persists as their enum *name* strings ("G2", "P_097"). The script keys its map on
  `env.brandCode + "|" + env.productCode` and looks up games by the same raw fields, so the
  join is consistent. `Environment` does carry both fields
  (`environment/model/Environment.java:44-45`), so the map is populated (not empty).

- **Migration is idempotent and fails loud on ambiguity.** `environmentId` is recomputed from the
  map every run, `createdAt` is set only when absent, `updatedAt` is always re-stamped, and a
  `(brand,product)` combo resolving to more than one env aborts with a thrown error rather than
  picking arbitrarily. Unmapped games are logged (not silently skipped) and still get their
  timestamps. This is solid.

- **Minor (not blocking): no existence check on the `envId` path param.** The create route
  (`GameController.java:save`) accepts any `envId` string and persists it without verifying an
  `Environment` with that `_id` exists, so a typo produces a game pinned to a non-existent env
  that no scoped read will ever return. Brand/product get enum-parse validation for free; env
  does not. Consider a light existence check if this becomes a support burden — acceptable to
  defer.

---

# Code Review — BOTGROUP_GAME_MANAGEMENT (Phase 2)

Branch: feat/botgroup-game-management
Reviewed diff: `git diff 16a84d6..HEAD`

Scope: Phase 2 only — BotGroup env-in-path filter (`POST /{envId}/filter`), hard-removal of the
unscoped `GET /` list, and the `gameId ∈ env` create-time validation. Code-quality only; plan
compliance, test adequacy, and deploy mechanics are out of scope.

## Verdict

PASS

## Findings

### [smell] AD-7 validation throws an undocumented 404 for an unknown `gameId`
`src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupService.java:248`

`validateGameEnvironmentMatch` calls `gameService.findById(gameId)`, which throws
`ResourceNotFoundException` (→ HTTP 404) when the referenced game does not exist. Two small issues:

- The method's Javadoc documents only the `BadRequestException` (400) mismatch throw; the 404 path
  is invisible to a reader. A `POST /api/v1/bot-group/` create with a bogus `gameId` now surfaces
  as a 404, which reads oddly for a create (the *requested* resource isn't missing — a body
  reference is). A 400 would be more semantically honest for a client-supplied bad reference.
- Functionally this is fine and arguably an improvement (a group can no longer be created pointing
  at a non-existent game). Not a bug — the behavior is safe and fails fast. Fix shape (optional):
  note the 404 in the Javadoc, or catch `ResourceNotFoundException` and re-wrap as a
  `BadRequestException` with a "referenced game does not exist" message so the create path returns
  a consistent 400 for all bad-`gameId` inputs.

## Notes

- **Env scoping cannot be bypassed (verified).** `BotGroupService.filter`
  (`BotGroupService.java:83`) applies `Criteria.where("environmentId").is(environmentId)`
  *unconditionally* before any body-derived criteria, and `environmentId` comes from the mandatory
  `@PathVariable` (`BotGroupController.java:69-71`) — not from the body. `BotGroupFilter` no longer
  carries `environmentId`, so there is no field a crafted/empty body could use to widen or escape
  the scope. An empty body yields exactly `{environmentId: <path>}` and the service test asserts
  `containsExactly("environmentId")`. Airtight.

- **AD-7 guard runs before fan-out and persist (verified).** `validateGameEnvironmentMatch` is
  invoked at `BotGroupService.java:136`, immediately after `configValidation.validate` and *before*
  `validateUsernameLength`, the `clientRegistry.getClients` / `registerUsers` registration fan-out
  (`:144-152`), and `repository.save` (`:183`). It is inside the `isNewGroup` branch only, matching
  AD-7's "new-group path only". A mismatch therefore costs zero wasted auth calls and leaves no
  partial state.

- **Null-guard logic is exactly right (verified).** `gameId == null` → no-op return;
  `gameEnvironmentId == null` → skipped (the migration-window fallback, so an unmigrated game never
  spuriously blocks creation); only both-non-null-and-differ throws. It neither over-blocks a valid
  create nor lets a real cross-env mismatch through. `botGroup.getEnvironmentId()` being null would
  make a non-null game env "not equal" and correctly trip the 400.

- **The `gameId == null` no-op is a sound judgment call.** A group created without a `gameId` has
  nothing to validate, so the early return is correct rather than a gap. If a `gameId` is in fact
  mandatory for a group, that invariant belongs to `configValidation` / DTO bean-validation (which
  runs first at `:133`), not to this env-matching guard — so this method correctly stays
  single-responsibility. No action needed.

- **`BotGroup.gameId` correctly treated as the Game Mongo `_id` (verified).**
  `gameService.findById(gameId)` resolves via `GameRepository.findById` (`GameService.java:34-37`),
  i.e. the Mongo `_id`, consistent with the plan's Implementation Note 1 and the existing
  `BotGroupBehaviorService` join. No confusion with the numeric channel `Game.gameId`.

- **`GET /` removal did not strand `findAll()` (verified).** `BotGroupService.findAll()`
  (`BotGroupService.java:63`) is still consumed by `EnvironmentController`
  (`EnvironmentController.java:68,89`) for env stat enrichment, so the service method correctly
  stays. Only the HTTP endpoint was removed. All other routes are intact: `GET /{id}`, `POST /`
  create, `PATCH /{id}`, `DELETE /{id}`, and the `/{id}/{action}` lifecycle endpoints. No routing
  collision between the new `POST /{envId}/filter` and the `POST /{id}/start|stop|restart|
  schedule-restart` family — the second path segment (`filter` vs the action names) disambiguates.

---

# Code Review — BOTGROUP_GAME_MANAGEMENT (Phase 3)

Branch: feat/botgroup-game-management
Reviewed diff: `git diff 930001f..HEAD`

Scope: Phase 3 only — BotGroup statistics. The per-bot `cumulativeWinnings` / `roundsObserved`
accumulators (`Bot`, `BettingMiniGameBot`, `SlotMachineBot`), `BotGroupStatsDTO`, the
`computeStats` enrichment on `BotGroupBehaviorService`, and the DTO wiring into `findById` /
filter-list / health responses. Code-quality only; plan compliance, test adequacy, and deploy
mechanics are out of scope.

## Verdict

PASS

## Findings

None.

## Notes

- **`cumulativeWinnings` mirrors `bot_winnings_total` value-for-value (verified — AD-8).** In
  `BettingMiniGameBot.onEndGame` (`:439-440`) the accumulator adds the exact same `w` under the
  exact same `w > 0` guard as `metrics.incBotWinnings(w)` on the very next line — the only
  difference being the intended de-coupling from `metrics != null`, so the stat is populated even
  when Prometheus is unwired. `SlotMachineBot.onSpinResult` (`:232-237`) does the same: both
  `cumulativeWinnings.addAndGet(winnings)` and `incBotWinnings(winnings)` sit inside the single
  `if (winnings > 0)` block with the identical value. There is no site where the metric increments
  and the accumulator does not (or vice-versa), so no drift is possible short of a duplicate
  server frame — which would double-count both counters identically, keeping them in lockstep.

- **`roundsObserved` increments exactly once per completed round/spin (verified — AD-9).**
  Betting/Tai Xiu: a single `roundsObserved.incrementAndGet()` at `BettingMiniGameBot:483`, once
  per `onEndGame`, unconditional and outside any winnings guard — so a zero-winnings round still
  counts. Slot: a single increment at `SlotMachineBot:242`, placed *after* the foreign-gid
  defensive early-return (`:219-224`), so a stray/foreign frame that bails out does not inflate the
  count; every accepted spin result counts exactly once. No path double-increments within a single
  handler invocation.

- **`computeStats` returns N/A correctly for a non-running group (verified).** `runningGroups.get`
  miss → `BotGroupStatsDTO.builder().build()`, i.e. every boxed field null → all N/A. Averages are
  computed only when `activeCount > 0`, so `averageBalance`/`averageWinning` stay null (never `0`)
  for a live runtime whose bots are all reconnecting/disconnected — matching Implementation Note 5.
  `balanceSum / activeCount` and `winningSum / activeCount` are unreachable when `activeCount == 0`,
  so there is no divide-by-zero. The `activeBots`/`connectedBots` health count and the stats
  `activeBots` both use the same `Bot.isConnected()` predicate (`client != null && client.isOpen()`),
  so the two numbers in a health response share one definition and cannot semantically disagree.

- **Active-time and thread-safety are sound.** `activeTimeSeconds` derives from
  `runtime.getStartedAt()` with an explicit null guard (`:runtime.startedAt` is set once in
  `BotGroupRuntime.start`), so a runtime that exists but has not stamped `startedAt` yields null
  rather than a bogus duration. All cross-thread reads are safe: `roundsObserved` /
  `cumulativeWinnings` / `expectedCurrentBalance` are `AtomicLong` (read via `.get()` /
  `Bot::getExpectedBalance`), and `runtime.getBotInstances()` is a `CopyOnWriteArrayList`, so the
  stats-thread stream iterates a stable snapshot while bot IO threads keep incrementing. No shared
  mutable non-atomic state is touched.

- **Enrichment stays off the write surface and is not Phase-4-ish (verified — AD-13).** `stats` is
  set only in the controller read paths (`findById`, filter list) and `getHealth`, never in the
  mapper and never on create/update. The filter-list lambda does exactly one thing beyond mapping —
  `dto.setStats(computeStats(group.getId()))` — with no sorting, ordering, or filtering on the stat
  keys; the "Phase 4 will additionally sort" comment correctly defers that. `BotGroupStatsDTO`
  carries no persistence annotations and is never read back from Mongo.

- **Minor (not a finding): the health path iterates the bot list twice.** `getHealth` builds
  `botDtos` (one pass, computing the connected count) and then calls `computeStats(id)`, which
  re-fetches the runtime and re-streams the same bots for max/sum/active-count. Given
  `CopyOnWriteArrayList` and group sizes in the tens-to-~100 range this is negligible, and keeping
  `computeStats` self-contained (so `findById`/filter can call it standalone) is a reasonable
  trade. No change needed.

- **Minor (not a finding): integer-division averages truncate toward zero.** `balanceSum /
  activeCount` and `winningSum / activeCount` drop the fractional part. For a whole-currency
  display average this is fine and intentional; flagging only so the UI author is not surprised the
  mean is floored rather than rounded.

---

# Code Review — BOTGROUP_GAME_MANAGEMENT (Phase 4)

Branch: feat/botgroup-game-management
Reviewed diff: `git diff d0d667a..HEAD`

Scope: Phase 4 only — BotGroup sorting. New `sort/` package (`BotGroupSorter`,
`BotSortKey`, `SortDirection`, `BotGroupSortRow`), `filterSorted` wiring in
`BotGroupBehaviorService`, `createdAt`/`updatedAt` on `BotGroup`, the
`update()`→`save()` reroute in `BotGroupService`, and the timestamp migration. Test
coverage is QA's concern; plan compliance is Architect-2's — both out of scope here.

## Verdict

PASS

## Findings

### [smell] STATUS "not running" is inferred from `activeTimeSeconds` rather than the runtime directly
`src/main/java/com/vingame/bot/domain/botgroup/sort/BotSortKey.java:38`

`STATUS` extracts `row.stats().getActiveTimeSeconds() != null ? row.actualStatus() : null`,
i.e. it decides whether the group counts as running by reading a *different* runtime
key's null-ness. For a genuinely inactive group this is correct — `computeStats` returns
an all-null DTO, so `activeTimeSeconds == null` and STATUS resolves to N/A, matching the
other runtime-only keys.

The brittleness is the transient window where a runtime exists but `startedAt` has not yet
been stamped: `computeStats` returns `activeTimeSeconds == null` while still setting
`activeBots` to a present (possibly `0`) value. In that window STATUS/ACTIVE_TIME/BALANCE
read N/A but ACTIVE_BOTS reads present — the "runtime-only keys move together" invariant the
Javadoc implies is briefly violated. It is not a correctness bug (the window is narrow and
`startedAt` is set in `BotGroupRuntime.start`), and it never affects a truly inactive group,
so this is advisory. A more direct derivation (`actualStatus != STOPPED`, or gating on the
runtime's presence) would remove the cross-field coupling. If the current shape is kept, it
is worth a one-line comment that STATUS deliberately piggybacks on `activeTimeSeconds` so a
future change to that field's semantics doesn't silently move STATUS.

## Notes

- **Comparator is a valid total order — no contract-violation risk.** `compareNaLast` is
  antisymmetric and transitive: nulls (N/A) always compare after present values regardless of
  direction (`a==null → +1`, `b==null → −1`), present values compare by `compareTo` negated for
  DESC, and the equal/both-null case (`0`) falls through to the `NAME` (nullsLast) then `id`
  (nullsLast) tie-breaks. The N/A block forms one equivalence class parked at the bottom and
  broken by the same secondary keys, so `sorted()` cannot throw
  `IllegalArgumentException: Comparison method violates its general contract`. The DESC negation
  `-cmp` is also safe from `Integer.MIN_VALUE` overflow: every key type in the catalog
  (`Instant`, `Integer`, `Long`, `String`, enum) returns bounded `compareTo` values (−1/0/1 for
  the numeric/temporal ones; char/length-bounded for `String`), none of which is `MIN_VALUE`.

- **N/A-to-bottom holds for inactive groups across all runtime-only keys.** All
  `BotGroupStatsDTO` fields are boxed (`Long`/`Integer`), and a not-running group yields
  `builder().build()` (every field null), so BALANCE / ACTIVE_BOTS / ACTIVE_TIME / AVG_WINNING
  each extract null → N/A. A *running* group with zero active bots correctly reads `activeBots=0`
  (present, a real zero) while the averages stay null — the intended AD-10 distinction is
  preserved by the sort.

- **`filterSorted` enriches once per group and once per distinct gameId — no N+1, no drift.**
  `resolveGameTypes` memoizes `gameService.findById` in a `HashMap` keyed by gameId with a
  `containsKey` guard, so N groups sharing a game hit Mongo once. `computeStats(group.getId())`
  is called exactly once per group and the resulting `BotGroupStatsDTO` instance is carried on
  the row; the controller now embeds `row.stats()` instead of recomputing, so the values used
  for sorting and the values returned in the DTO are guaranteed identical (the prior double-call
  drift is gone). The sort is fully in-memory (`rows.stream().sorted(...).toList()`) — no
  Mongo-side aggregation and no persisted derived fields.

- **`resolveGameTypes` catch is correctly narrow.** It catches only `ResourceNotFoundException`
  (game deleted out from under the group) and maps to null → GAME_TYPE N/A; it does not broaden
  to `Exception` or swallow unrelated failures.

- **`BotSortKey.resolve` is genuinely case-insensitive with unknown→400.** `equalsIgnoreCase`
  on the trimmed input, null/blank → `CREATED_TIME`, unrecognised → `BadRequestException`
  (HTTP 400 via `RestExceptionHandler`), and the message enumerates valid keys.
  `SortDirection.resolve` is deliberately lenient (unknown → DESC) per AD-11 — only an unknown
  *key* is a 400, matching the documented contract.

- **`update()`→`save()` reroute preserves update semantics.** `existing` always has a non-null
  id, so `save(existing, false)` takes the `isNewGroup == false` branch: no registration, no
  `configValidation.validate` re-run (validation already ran in `update()` on the merged entity),
  just the new `createdAt`-if-null / `updatedAt`-always stamping plus the persist. The only
  behavioural delta versus the old `repository.save(existing)` is the timestamp stamp and an added
  DEBUG "Updating existing bot group" log line — both intended. `createdAt` is write-once
  (guarded by null-check) so an update cannot clobber the original creation instant.

- **Timestamps are runtime-only derived state persisted on write, not recomputed on read** —
  consistent with the "no persisted derived fields for sort" intent, since createdAt/updatedAt
  are first-class group metadata, not aggregates. Existing docs are backfilled by
  `scripts/migrations/002_botgroup_timestamps.js` so nothing sorts as N/A on CREATED_TIME/
  UPDATED_TIME after deploy (migration content itself is Releaser's concern).

---

# Code Review — BOTGROUP_GAME_MANAGEMENT (Phase 5)

Branch: feat/botgroup-game-management
Reviewed diff: `git diff 379eeb7..HEAD` (Phase 5 — game sorting only)

Scope: the shared-comparator extraction (`SortComparators`), the game sort surface
(`GameSortKey` / `GameSortRow` / `GameSorter`), the `filterGamesSorted` + `enrichGame`
aggregate enrichment in `BotGroupBehaviorService`, `BotGroupService.findByGameId`, and the
`GameController.filter` rewire. Code quality only — plan compliance, test coverage, and deploy
mechanics are out of scope.

## Verdict

PASS

No `bug` or `security` findings. Two advisory findings below.

## Findings

### [smell] `enrichGame` sums `activeBotCount` with a looser "running" definition than `active()`
`src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:855-867`

`active()` (and thus whether `ACTIVE_GROUP_COUNT`/`ACTIVE_BOT_COUNT` extract N/A) is gated on
`activeGroupCount`, which increments only via `isGroupRunning` — runtime present **and**
`actualStatus == ACTIVE`. But `activeBotCount` accumulates `getRunningBotCountForGroup`, which
checks only `runtime != null` (any group still in `runningGroups`, regardless of status) and
counts non-done bot futures. The two runtime aggregates therefore key off different
"running" predicates. Consequences are edge-only and masked, not wrong output:

- A game referenced solely by a group that is in the map but not `ACTIVE` (transient STARTING,
  or a not-yet-evicted DEAD runtime) has `activeGroupCount == 0` → `active() == false` → both
  active keys N/A, so any non-zero `activeBotCount` is discarded rather than shown. Fine.
- A game with one `ACTIVE` group plus a co-referencing non-`ACTIVE`-but-in-map group would fold
  the latter's still-open futures into `activeBotCount` while excluding it from
  `activeGroupCount`. In practice a DEAD group's futures are done (count 0) and stopped groups
  are removed from the map, so this rarely materialises — hence smell, not bug.

Fix shape: gate the `activeBotCount += ...` on the same `isGroupRunning(group.getId())` check
already used for `activeGroupCount`, so both aggregates share one definition of "running".

### [style] `com.vingame` import placed mid-`java.util` block in GameController
`src/main/java/com/vingame/bot/domain/game/controller/GameController.java:12-14`

The new `import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService;` was inserted
immediately after `import java.util.Arrays;` and before the other `com.vingame` imports, so a
project import now sits inside the `java.*` group. Cosmetic; move it up with the other
`com.vingame.bot.domain.*` imports to match the file's existing ordering.

## Notes

- **`compareNaLast` extraction is behaviour-preserving and remains a valid total order for both
  sorters.** The moved body is byte-for-byte the Phase 4 logic (nulls → +1/−1 to the bottom
  regardless of direction, present values by `compareTo` negated for DESC, both-null → 0), only
  the visibility widened `private → public` and the package-qualified call site changed. Both
  `BotGroupSorter.comparator` and `GameSorter.comparator` call it identically and append the same
  `NAME`-then-`id` `nullsLast` tie-breaks, so each is antisymmetric + transitive with the N/A
  block as one equivalence class parked at the bottom — no `Comparison method violates its
  general contract` risk. Every `GameSortKey` type (`Instant`, `Integer`, `String`) returns
  bounded `compareTo` values, so the `-cmp` DESC negation cannot overflow `Integer.MIN_VALUE`.

- **`GameSortKey.resolve` matches the audited Phase 4 contract.** Case-insensitive
  `equalsIgnoreCase` on the trimmed input, null/blank → `CREATED_TIME` (`DEFAULT`), unknown →
  `BadRequestException` (HTTP 400) enumerating valid keys. `GameSorter.sort` pairs it with the
  deliberately-lenient `SortDirection.resolve` (null/blank/unknown → DESC), so only an unknown
  *key* is a 400 — consistent with the bot-group sorter.

- **`filterGamesSorted` is O(games) with one `findByGameId` per game — no N+1 over groups, no
  Mongo aggregation.** `enrichGame` issues exactly one `BotGroupRepository.findByGameId` per
  game and then folds `botCount`/`activeGroupCount`/`activeBotCount` over the returned groups
  using in-memory `runningGroups` reads (`isGroupRunning`, `getRunningBotCountForGroup` — both
  O(1)/O(bots) `ConcurrentHashMap` lookups, no DB). Sort is fully in-memory
  (`rows.stream().sorted(...).toList()`). No persisted derived fields, no Prometheus/gauge
  coupling. The runtime reads hit a `ConcurrentHashMap`, so the enrichment loop is safe from the
  virtual-thread lifecycle mutating the map concurrently.

- **`BotGroup.gameId` is correctly the Game Mongo `_id`.** `findByGameId(game.getId())` keys the
  lookup on the persisted Game `_id`, matching `BotGroup.gameId` (Implementation Note 1) — the
  same identifier `resolveGameTypes` already uses in Phase 4, so the two enrichment paths agree.

- **Placement in `BotGroupBehaviorService` is sound and does not leak responsibility.** The
  active-* aggregates are runtime-sourced and this service owns `runningGroups`; `GameService`
  depends only on `GameRepository`/`GameMapper`/`MongoTemplate` (no back-edge to the behavior
  service), so hosting `filterGamesSorted` here — rather than injecting the runtime map into
  `GameService` — avoids the Spring cycle without `GameService` gaining a bot-group dependency.
  `GameController` now depends on both `GameService` and `BotGroupBehaviorService`; no cycle,
  since neither service references the controller.

- **Controller maps sorted rows without recomputation.** `filter` maps `row -> mapper.toDTO(
  row.game())` over the already-sorted, already-enriched rows — the DTO is built from the same
  `Game` instance the aggregates were computed against, and the aggregates themselves are used
  only for ordering (not surfaced in `GameDTO`), so there is no sort-vs-response drift.

---

# Code Review — BOTGROUP_GAME_MANAGEMENT (Phase 6)

Branch: feat/botgroup-game-management
Reviewed diff: `git diff ea589d5..HEAD` (Phase 6 — two `/sort-keys` lookup endpoints only)

Scope: Phase 6 only — `GET /api/v1/bot-group/sort-keys` and `GET /api/v1/game/sort-keys`,
each returning `List<String>` of enum names. Code quality only; plan compliance, test
coverage, and deploy mechanics are out of scope for this review.

## Verdict

PASS

## Findings

### [style] GameController still carries a split `java.util` import block
`src/main/java/com/vingame/bot/domain/game/controller/GameController.java:13-29`

`import java.util.Arrays;` (line 15) sits above the `io.*`/`org.*` groups while
`import java.util.List;` (line 29) sits below them, so the two `java.util` imports the
new endpoint relies on are split across the block. This is **pre-existing** — Phase 6
only added `import com.vingame.bot.domain.game.sort.GameSortKey;` (line 13, correctly
grouped with the other `com.vingame` imports) and did not introduce or worsen the split
(it is the same disordering already flagged in the Phase 5 finding above). Purely
cosmetic; consolidating the `java.util` imports into one group would tidy it. Advisory —
does not affect the verdict. Note the sibling `BotGroupController` addition is clean:
`java.util.Arrays` and `java.util.List` are adjacent (lines 30-31).

## Notes

- **Both endpoints are driven off the enums — no drift possible.** `getSortKeys` returns
  `Arrays.stream(BotSortKey.values()).map(Enum::name).toList()` and the game sibling the
  same over `GameSortKey.values()`. The keys are materialised from the exact enum the
  filter resolves against (`BotSortKey.resolve` / `GameSortKey.resolve`, both
  `key.name().equalsIgnoreCase(raw.trim())`). Since the lookup emits canonical uppercase
  `name()` values and the resolver accepts case-insensitively, every returned key is an
  accepted `sortBy` value, and adding/removing an enum constant automatically flows to the
  lookup — the returned catalog cannot silently diverge from what the filter honors.

- **No route-shadowing conflict with `/{id}`.** In both controllers the literal
  `/sort-keys` mapping and the `/{id}` path-variable mapping coexist. Spring's path matcher
  ranks a literal segment above a `{var}` capture regardless of declaration order, so
  `GET .../sort-keys` binds to `getSortKeys()` and never reaches `findById(String id)`.
  Same reasoning that already keeps the pre-existing `GET /api/v1/game/types` literal safe
  next to `/{id}`. Declaration order (sort-keys placed before `/{id}` in bot-group, and
  between `/types` and `/{id}` in game) is tidy but not load-bearing here.

- **DTO shape matches Verification step 9.** Both endpoints return a bare `List<String>`
  of enum names, which is exactly what the plan's Verification asserts against
  (`curl .../bot-group/sort-keys | jq -e 'index("BALANCE") ...'` and
  `.../game/sort-keys | jq -e 'index("BOT_GROUP_COUNT") ...'`) — a JSON string array, not a
  `{key,label}` object. The plan offered the label-DTO as an alternative; the simpler
  string-array form was chosen and is internally consistent with the verification contract.

---

# Code Review — BOTGROUP_GAME_MANAGEMENT (Phase 7)

Branch: feat/botgroup-game-management
Reviewed diff: `git diff 966448e..HEAD` (Phase 7 — cascading deletes only)

Scope: Phase 7 only — `BotGroupBehaviorService.stopAndLogout`, the `BotGroupService.delete`
→ `stopAndLogout` wiring, the `GameService.delete` (→ groups) and `EnvironmentService.delete`
(→ games → groups) cascades, `GameRepository.findByEnvironmentId`, and the three `@Lazy`
back-references added to break the resulting constructor cycles. Code quality only — plan
compliance, test coverage, and deploy mechanics are out of scope.

## Verdict

CHANGES_REQUESTED

One `bug` finding (spurious reconnect storm on the teardown path). The cascade ordering,
idempotency, `@Lazy` cycle-breaking, and no-user-deregistration aspects are all sound — see
Notes.

## Findings

### [bug] `stopAndLogout` closes each WS before `stopped` is set, deterministically firing a spurious reconnect + WARN + metric per bot
`src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:637-643`

The teardown calls `bot.logout()` for every bot **before** `runtime.stopAllBots(...)`.
`Bot.logout()` is just `stop()` → `client.close()` (`Bot.java:267-274,248-252`) and does
**not** set the bot's `stopped` flag. Closing the channel fires the wired `onDisconnect`
handler, whose guard is `if (!stopped) onWsDisconnected()` (`Bot.java:425-426`). Because
`stopped` is still `false` at that point, `onWsDisconnected()` runs for each bot and
unconditionally (once past the `reconnecting` CAS):

- `transitionStatus(BotStatus.RECONNECTING)`,
- logs `WARN "Bot {}: WS disconnected — starting retrial flow"`,
- `metrics.incBotReconnect("ws-disconnect")`,
- spawns a `reconnect-<name>` virtual thread.

This is the exact scenario the normal `stop()`/`cleanup()` path is written to avoid:
`BotGroupRuntime.stopAllBots` → `Bot.cleanup()` sets `stopped = true` **before** calling
`stop()`/`close()` (`Bot.java:218-227`) specifically so the `onDisconnect` guard suppresses
the reconnect. `stopAndLogout` defeats that ordering by closing (via `logout()`) while
`stopped` is still false.

Runtime effect on every cascade delete of an N-bot group: N false `bot_reconnects_total{reason=
ws-disconnect}` increments and N `WARN "…starting retrial flow"` lines for a *deliberate* admin
delete. That counter/WARN pair is the codebase's flapping/instability signal (`incBotReconnect`
is the reconnect-event meter, and CLAUDE.md ties reconnect volume to health alerting), so an
ordinary delete now manufactures a false instability spike. The increments and log lines are
**deterministic**, not timing-dependent — they happen synchronously inside `logout()` before
`stopped` is ever set. The docstring's claim "the same server-side logout … *minus the reconnect*"
is therefore inaccurate: the reconnect loop *is* spawned; it merely bails.

An actual reconnection is avoided only by timing: `runWsReconnectLoop` sleeps
`BACKOFF_SECONDS[0]` = 5 s before its first attempt (`Bot.java:499-503,32`), and
`stopAllBots` → `cleanup()` sets `stopped = true` within milliseconds, so each loop hits
`if (stopped) return` after the 5 s sleep and never opens a socket. So there is no client leak
today — but the teardown is relying on a 5 s head-start to neutralise a reconnect it should
never have triggered, which is fragile (any future shortening of the first backoff, or a slow
`stopAllBots`, narrows that margin).

Note also that the per-bot `logout()` loop is **functionally redundant** with the teardown that
follows it: `logout()` and `cleanup()` both reduce to `client.close()`, and there is no separate
server-side logout API (AD-15). So the loop adds only the spurious reconnect/WARN/metric noise
and a second close.

Fix shape: drop the explicit `logout()` loop and rely on `runtime.stopAllBots(...)`, whose
`cleanup()` already closes each WS gracefully *with `stopped` set first* (no reconnect, no false
metric). If an explicit "logout" step must be retained for symmetry with the periodic-logout
path, set the bot's `stopped` flag before closing (e.g. a `Bot.shutdown()`/`markStopped()` that
closes without arming the reconnect), so `onWsDisconnected` is suppressed the same way the normal
stop path suppresses it.

## Notes

- **Cascade ordering is correct — no orphaned-runtime window (verified).** In all three delete
  paths the runtime is fully torn down and dropped from `runningGroups` *before* the Mongo doc is
  removed: `BotGroupService.delete` calls `stopAndLogout(id)` (which ends with
  `sessionAggregationService.evictGroup(id)` + `runningGroups.remove(id)`) and only then
  `repository.deleteById(id)` (`BotGroupService.java:301-303`). `GameService.delete` deletes every
  referencing group (`findByGameId(id)`) before `repository.deleteById(id)`
  (`GameService.java:133-138`); `EnvironmentService.delete` deletes all games (each cascading to
  its groups) then any residual groups (`findByEnvironmentId(id)`) before removing the env doc
  (`EnvironmentService.java:151-158`). There is no point at which a doc is gone from Mongo while its
  runtime is still running — the dangerous direction is structurally impossible. The only failure
  residue (if `deleteById` throws after teardown) is a doc with no runtime, which is the safe
  direction and self-heals on a delete retry.

- **Idempotency / partial-failure tolerance is sound (verified).** `stopAndLogout` is a no-op when
  the group is not in `runningGroups` (`BotGroupBehaviorService.java:618-621`), so deleting an
  already-stopped group and re-running a delete are both safe. A single `bot.logout()` throwing is
  caught and logged (`:639-642`) so one bad bot cannot abort the loop — and `Bot.logout()` already
  swallows internally, so this is defence in depth. In the env cascade a group reachable both via
  its game and via `findByEnvironmentId` cannot double-fault: the second query runs *after* the
  games loop has already deleted those docs, so they no longer appear; and even if one did,
  `stopAndLogout` (not-running → no-op) + `deleteById` (absent → no-op) are individually idempotent.
  No orphan is left for a group whose game/env is already gone.

- **`@Lazy` breaks all three cycles without a first-call surprise (verified).** The new edges are
  `BotGroupService → @Lazy BotGroupBehaviorService`, `GameService → @Lazy BotGroupService`, and
  `EnvironmentService → @Lazy BotGroupService`; the reverse edges (`BehaviorService → BotGroupService`,
  `BotGroupService → GameService`, `BotGroupService → EnvironmentService`) stay eager, so each cycle
  has exactly one lazy proxy and Spring can instantiate the singletons. The lazily-injected beans are
  used only inside `delete(...)` (runtime, admin-triggered), never in a constructor or
  `@PostConstruct` — in particular `BehaviorService`'s `@PostConstruct` auto-start does not touch the
  lazy `behaviorService` handle held by `BotGroupService` — so by first use every singleton is fully
  built and the proxy just forwards. No bootstrap-order hazard.

- **No user deregistration attempted (verified — AD-15).** Neither `stopAndLogout` nor any delete
  path calls a bot-server user-deregister API; teardown stops at closing the WS session. Both
  `BotGroupService.delete` and `EnvironmentService.delete` Javadocs state leftover accounts are
  expected and there is no such API. Correct per AD-15.

- **Thread-safety of the `runningGroups` mutation is consistent with the existing `stop()` path.**
  `runningGroups` is a `ConcurrentHashMap` and `botInstances` is a `CopyOnWriteArrayList`, so the
  per-bot iteration and the `remove(id)` are individually safe against the Prometheus scrape / health
  monitor iterating `values()`. The `get(id)`…`remove(id)` is a non-atomic check-then-act, so two
  concurrent `delete`s of the *same* id could both enter `stopAllBots` — but that mirrors the
  pre-existing `stop(id)` shape, deletes are low-concurrency admin operations, and `stopAllBots`
  guards on `isShutdown()`, so a double teardown is benign. Not introduced by this phase; noting for
  completeness rather than as a finding.

- **Minor (not a finding): the `ACTIVE → STOPPED` gate relies on a non-volatile field.**
  `stopAndLogout` sets `runtime.setActualStatus(STOPPED)` on the caller thread so a concurrent
  `performPeriodicLogout` tick bails at its `getActualStatus() != ACTIVE` gate
  (`BotGroupBehaviorService.java:1266-1270`). `BotGroupRuntime.actualStatus` is a plain (non-volatile)
  Lombok field read on the logout-scheduler thread, so the STOPPED write is not guaranteed
  immediately visible there. In practice this is a pre-existing property of the field (only
  `groupDeadSince` is volatile) and is backstopped by `stopAllBots` calling
  `logoutScheduler.shutdownNow()`, plus the tick's own re-check of status after the reconnect delay —
  so a leaked reconnect is bounded and self-limiting. Worth a volatile on `actualStatus` eventually,
  but out of scope for this phase and not relied upon for correctness here.
