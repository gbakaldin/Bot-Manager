# Code Review — RIK_114_ZICZAC

Branch: `feature/dead-group-auto-recovery`
Reviewed diff: **the working tree, not a commit range.** The ziczac work is uncommitted
(`bot-messages/.../g3/rik/` is untracked; `GameMessageTypes.java` and `BotFactory.java` are
modified-unstaged), so `git diff main..HEAD` is the DEAD_GROUP_AUTO_RECOVERY feature and shows
none of it.

**The tree moved under this review.** Three test files appeared between 15:35 and 15:38 while I was
reading (`BotFactoryForGameResolutionTest`, `GameMessageTypesForGameContractTest`,
`BettingMiniGameBotZicZacDispatchTest`), a fourth (`RikZicZacEndGameSemanticsTest`) after that, and
three production files were re-saved at 15:39-15:40. I re-read every production file after the last
write and confirmed the content I cite is current. The verdict is pinned to these bytes:

| file | md5 |
|---|---|
| `bot-api/.../message/GameMessageTypes.java` | `cecb7d2b6b0c9a0aa64c567e56cdb8cb` |
| `bot-app/.../service/BotFactory.java` | `a9a999848618428e422ececd2a2fa55c` |
| `bot-messages/.../g3/rik/RikGameMessageTypes.java` | `79beb602409d2abc006748f8960b781f` |
| `bot-messages/.../g3/rik/RikZicZacBallResult.java` | `0d3e02f56ca46143bb314f2e0a2e9431` |
| `bot-messages/.../g3/rik/RikZicZacEndGameMessage.java` | `0f945741ba7487db3b51cb5308e560f6` |
| `bot-messages/.../g3/rik/RikZicZacGameMessageTypes.java` | `ac7fe3d596ae41d65102d91330ff1ade` |
| `bot-messages/.../g3/rik/RikZicZacUpdateBetMessage.java` | `a370fbb854c0f03f5a747a173370b6bf` |

Also reviewed: the rewritten `RikFixtureProvenanceTest`, the new `RikZicZacGameShapeTest` /
`RikZicZacCaptureArithmeticTest` / `RikGameMessageTypesRoutingTest`, the committed capture
`captures/rik-ziczacPlugin-12000.jsonl` and the six `messages/rik/ziczac-*.json` fixtures.

Out of scope as briefed: the pre-existing working-tree modifications (`Aviator.js`, `bc.js`,
`deploy.sh`, `TaiXiuMessages/*`, DEAD_GROUP_AUTO_RECOVERY docs) and Phase 2's absence.

Build (JDK 21, after the concurrent test additions): **2211 tests, 0 failures, 0 errors, 0 skipped**
— bot-api 138, bot-strategies 126, bot-messages 242, bot-engine 446, bot-app 1259. An earlier run at
15:34, before those additions, gave exactly the briefed 2175 baseline.

## Verdict

PASS

No `bug` and no `security` finding. Everything below is advisory. I checked the four domain facts the
brief flagged as making code wrong on sight, and all four are right in the code:

- `RikZicZacBallResult.odd` is `double` (`:64`), and `RikZicZacCaptureArithmeticTest` makes the
  truncation falsifiable rather than merely commented — 54 of 81 balls carry a fractional `odd`,
  asserted as a count.
- `b`, `r`, `tJpV` are all `long`. I confirmed against the capture that `r` reaches 15 900 000 and
  `p.m` reaches 1 766 027 033 (82.3% of `Integer.MAX_VALUE`), so the choice is load-bearing, not
  cosmetic.
- `winningsFor` returns `sum(mbs[].r)` verbatim, gross of stake, with no netting anywhere
  (`RikZicZacEndGameMessage:157-162`), and the loss-round test pins `winnings > 0 && winnings < staked`.
  `mbs: []` → `0`, and `r == 0` for a centre-bucket ball is never treated as missing — I verified 13
  of the 81 captured balls have `r == 0` and none of them is special-cased.
- `p.wm` is not read and `p` is not modelled. I re-derived the arithmetic from the committed capture
  independently: 81/81 balls satisfy `r == b × odd`, `sum(mbs[].r) == p.wm` in 6/6 betting rounds,
  `p` absent in the 2 no-bet rounds, `obs` empty in 8/8, `iJp` false in 8/8, `tJpV` non-decreasing
  200 200 → 689 300. Every number stated in the javadoc is the number in the file.

## Findings

### [smell] "Exactly one call site" is now pinned for the site that exists, but not against a second one being added
`bot-api/.../message/GameMessageTypes.java:64-68`
`bot-app/.../service/BotFactory.java:163-172`
`bot-messages/src/test/.../g3/rik/RikGameMessageTypesRoutingTest.java:31-35`

I wrote this finding as a much larger one and the concurrently-added tests shrank it, so let me be
precise about what is and is not covered now.

**Covered.** `BotFactoryForGameResolutionTest` (new) spies the real `RikGameMessageTypes` and asserts
at the factory that the BETTING_MINI branch calls `forGame` with the bot's own `Game` instance
(`isSameAs`), that it happens inside the type switch ahead of authentication, and that the SLOT and
TAI_XIU branches never consult the betting-mini provider at all.
`GameMessageTypesForGameContractTest` (new) enumerates providers from the **real component scan**
rather than a hand-written list, so provider number seven is covered the day it is registered, and
asserts identity-return for every provider × every plugin name with exactly one declared
specialisation. Together these close the two gaps I was going to raise — the call site is now a
regression-tested fact, and the cross-product default is no longer a statement about the five
providers that existed when someone typed them out.

**Not covered, and this is the whole of what remains.** Nothing prevents a *second* lookup being
added later. The residual is stated most honestly by the code itself:

```java
// RikGameMessageTypesRoutingTest:31-35
 * The routing is pinned as <i>behaviour</i>, not as a call site. The one production
 * call site is {@code BotFactory}'s BETTING_MINI branch; a second
 * {@code bettingMini(...)} lookup that forgot {@code .forGame(game)} would still pass
 * this test … Reviewers watch for the second call site.
```

"Reviewers watch for it" is not a mechanism. The realistic vector is a future path that rebuilds a
bot's message layer outside `BotFactory` — a reconnect or re-auth path, say; `CLAUDE.md`'s backlog
already carries an open restart-lifecycle bug in roughly that area — and the failure it produces is
the worst-shaped one this codebase has. A forgotten `.forGame(game)` routes ziczac to
`RikEndGameMessage`, whose `winningsFor` reads `mbs[].wm`, a key ziczac's frames do not contain, so
`bot_winnings_total` reads a flat zero while the bots visibly stake — the exact "everything looks
healthy, confirmed staked reads 0" silhouette `CLAUDE.md` records as having been misdiagnosed as a
wallet-partition bug **twice**. I confirmed the claim is true today: `grep` over
`bot-app/src/main`, `bot-engine/src/main` and `bot-messages/src/main` finds exactly one production
`bettingMini(`, at `BotFactory:172`.

Fix shape, and I think it is worth doing even now: **delete the possibility instead of guarding it.**
Give the registry the game-aware overload — `bettingMini(String productCode, Game game)`, which does
the `forGame` internally — and make the one-argument form package-private or `@Deprecated`. The
two-step call *is* the hazard; a single call that cannot be made incorrectly removes it rather than
watching it, and it makes four paragraphs of prose across three files unnecessary. Failing that, the
cheap version is this codebase's established idiom for source-level invariants
(`PerBotInfoLogGuardTest`, `Log4j2TwinConfigTest`, `EvidenceRetentionEscapeTest`,
`AlertmanagerRoutingTest`): read `BotFactory.java` and assert every `bettingMini(` occurrence is
followed by `.forGame(`.

### [smell] The resolution hook takes a mutable Mongo `@Document` when it reads one `String`
`bot-api/.../message/GameMessageTypes.java:54-75`

`forGame(Game game)` is now part of the SPI that, by `PLUGIN_HOT_RELOAD`'s own end-state, third-party
plugin jars will implement. `Game` is a `@Document(collection = "games")` with `@Getter @Setter
@Builder` and roughly two dozen fields including `BrandCode`, `ProductCode`, `optionAffinities` and
`jackpotScaleEnabled` (`bot-api/.../domain/game/model/Game.java:20-27`). The contract the javadoc
actually states is one line — "dispatch on `Game#getPluginName()`, case-insensitively" — and the
single implementation reads exactly that one getter (`RikGameMessageTypes:139`).

So the seam hands a mutable persistence entity across a module boundary to satisfy a `String`
comparison. Nothing is wrong at runtime (the entity is per-bot, providers are stateless, no
implementer mutates it), which is why this is a smell and not a bug. What it costs is contract width:
an implementer may legitimately read or, with `@Setter` present, write anything on it, and narrowing
it later is a breaking change to a published interface.

The counter-argument is real and I weighed it: AD-9's Phase 2 signature is `requestFor(Game game,
String zoneName, int offset)`, so `Game` is arriving at this layer anyway, and `Game` already lives
in `bot-api` so no new module dependency was created. If Phase 2 is certain, keep the signature and
say in the javadoc that `getPluginName()` is the only field an implementer may read. If it is not,
`forGame(String pluginName)` is the seam the code actually uses.

### [smell] `RikGameMessageTypes`'s javadoc says the class did not change, in a paragraph that is part of the change
`bot-messages/.../g3/rik/RikGameMessageTypes.java:87-89`

> "It is therefore served by `RikZicZacGameMessageTypes`, reached through `forGame(Game)` rather than
> through the registry — see that method. **Nothing about this class changed to make that possible**,
> which was the requirement: the two games above keep their classes, their fixtures and their tests
> byte-for-byte."

This class gained a constant (`:112`), a static instance (`:118`), a `forGame` override (`:137-143`)
and the sentence itself. The intended meaning is obvious — *the two shipped games' handling* did not
change, which is true and is worth saying. But this feature's own history (`RIK_114_BETTING_MINI`
Amendment A1: a confidently-worded rationale shipped, was wrong, and had to be deleted from three
places) is why the brief treats over-claiming in a comment as a defect here. A sentence that is false
on its face three lines above the code that falsifies it is the same habit in a quieter register, and
it is the kind of sentence a later reader quotes rather than re-derives.

Fix shape: say the true thing. "The only edit to this class is the `forGame` override below; the two
shipped 114 games' message classes, fixtures and tests are unchanged."

### [smell] The `PLUGIN_HOT_RELOAD` A8 appeal in the bot-api javadoc overstates the coupling
`bot-api/.../message/GameMessageTypes.java:36-39`

> "Widening the registry key to `(gameType, product, game)` would touch every provider, the registry
> and five tests for one game, **and it is entangled with the version axis that
> `docs/plans/PLUGIN_HOT_RELOAD.md` Amendment A8 defers to step 5**."

I read A8. It is about a **version** dimension and a **collision policy** — "two live plugin versions
are not expressible under the current keys", "a duplicate key fails context refresh". Adding a *game*
dimension decides neither: `(GameType, productCode, pluginName)` with a wildcard entry for the
product-wide case leaves both A8 questions exactly where they were. The honest version of the
coupling is weaker and procedural — both edits rewrite the same key tuple and the same `registry.put`
line, so step 5 would have to carry a game dimension through if one existed by then.

This matters because the other two legs of the argument are strong and stand alone: the blast-radius
cost (every provider + registry + five tests for one game) is concrete and I confirmed it, and "a
`pluginName`-keyed registry is the natural end-state and `forGame` does not block it, since the
routing is one branch inside one provider" is correct. **The deferral is honestly argued on two of
its three legs.** Leaning on the third makes a deliberate, defensible scoping choice read as if it
were forced by an external constraint, which is how a reviewable decision quietly becomes an
unreviewable one. Fix shape: drop the clause, or demote it to what it is — "and step 5 will rewrite
this key anyway."

### [smell] The only thing stopping a money-visible misconfiguration is a javadoc paragraph
`bot-messages/.../g3/rik/RikZicZacGameMessageTypes.java:69-79`

> "Until it does, a ziczac group parses rounds correctly and whether the server reads a missing `c`
> as 0 balls or 1 is **unknown**; at 0 balls every payout metric reads `0` and the engine's local
> balance drifts down against a stake the server never took. **Do not run a ziczac group for metrics
> before Phase 2.**"

This is exactly the right warning and it is in the wrong place for its audience. Creating a ziczac
`Game` is a REST call (`/api/v1/game`); nothing on that path knows about Phase 2, nothing rejects a
`pluginName` of `ziczacPlugin`, and nothing logs a word when `forGame` routes to a provider that
cannot yet build the game's bet body. The person who can trip this is an operator creating a game in
the UI, and the warning is in a javadoc they will never open. `CLAUDE.md`, which carries every other
operator-facing "do not do this" of this exact shape — the `BOT_IP` trap, "never delete game impls",
"check whitelisting first" — currently says nothing about ziczac at all.

The consequence is money-shaped (local balance debits against a stake the server may not have taken),
and it is second-order indistinguishable from the "bets never settle" failure that has already cost
this project two misdiagnoses.

Fix shape, cheapest first: a line in `CLAUDE.md` under the RIK/114 material. Better, and
self-removing: one WARN at group start when the resolved provider is the ziczac one and no
`GameRequestFactory` capability is present — it disappears by construction when Phase 2 lands, it is
group-scoped so it is tier-1 admissible under the logging rules, and it reaches Loki.

### [style] Two capture readers, two import conventions, one duplicated parse loop
`bot-messages/src/test/.../g3/rik/RikFixtureProvenanceTest.java:73-82, 86-106`
`bot-messages/src/test/.../g3/rik/RikZicZacCaptureArithmeticTest.java:53-75`

`captureBodies` is duplicated near-verbatim between the two classes (open resource → assert non-null
→ read lines → skip blanks → `readTree` → keep nodes with a `body`), differing only in the `dir`/`cmd`
filter. And `RikFixtureProvenanceTest.fixturesOnDisk()` writes `java.net.URL`, `java.nio.file.Files`
and `java.nio.file.Path` fully qualified inline while the same file imports `java.io.BufferedReader`,
`java.io.InputStream` and `java.nio.charset.StandardCharsets` at the top; `RikZicZacGameShapeTest:83`
does the same with `java.nio.charset.StandardCharsets`. Both are small and neither affects behaviour
— flagged only because the surrounding RIK test classes are otherwise unusually consistent, so the
inconsistency reads as unfinished rather than intentional. A shared package-private `RikCaptures`
test helper would take the duplication with it.

## Notes

**The provenance test cannot silently bind a fixture to the wrong capture. I tried to break it.**
The prefix rules (`txmd5-`, `ziczac-`, else stock) are disjoint, and the loophole the rewrite had to
close — a new fixture that no rule claims — is closed from the other side, by
`everyFixtureOnDiskIsClaimedByExactlyOneCapture` asserting that the set of *un-prefixed* files is
exactly the five known stock fixtures (`:175-184`). A new game's fixture dropped in without a prefix
rule therefore fails loudly instead of being verified against stock's frames, which is the exact
behaviour AD-14 promised. Three further things I checked: the vacuity guard
(`hasSizeGreaterThanOrEqualTo(15)`, `:165`) means an empty or wrong directory cannot make the class
pass trivially; the three `*CaptureIsIntact` tests pin body counts (50 / 22 / 73) so a silently
truncated capture fails — I confirmed 73 bodies plus one `_meta` line in the ziczac file; and a
cross-copied fixture (a stock body saved under a `ziczac-` name, or vice versa) fails
`everyFixtureCameFromItsOwnCapture`, because node equality is checked against that fixture's *own*
capture only. One residual worth knowing rather than fixing: provenance binds a fixture to *some*
frame in the right capture, not to the frame *type* its name claims, so `ziczac-endGame-noBet.json`
would pass provenance if it held a `12007` room-feed body. `RikZicZacGameShapeTest` catches that by
asserting each fixture's `sid`, so the pair of tests together is sound. Also note
`Files.list(Path.of(dir.toURI()))` assumes exploded test resources; it would throw
`FileSystemNotFoundException` if these ever ran from a jar — loud, not silent, so not a finding.
One small inaccuracy: the javadoc says the rule is derived "not from hand-maintained lists", and one
literal list survives at `:175-177` — but it survives as a *negative* guard that fails loudly, which
is strictly better than what it replaced, so this is a wording nit and not a defect.

**The "byte-for-byte unchanged" premise in my brief is not true of the tree as it stands.** I could
not verify it from git — the whole `g3/rik/` directory is untracked, so there is no prior revision to
diff against — so I checked timestamps and content instead. The three RIK_114_BETTING_MINI review
documents were written at 12:50 (compliance), 12:52 (review) and 12:57 (qa) on 2026-09-16.
`RikSubscribeMessage.java` was modified at **13:01:12**, `RikMainBetSummary.java` at **13:01:27** and
`RikEndGameMessage.java` at **13:03:05** — after all three verdicts. The content confirms the edits
are real and identifies them: `review.md`'s first finding asked for the `m`/`wm` javadoc to state the
observation rather than an interpretation, and `RikSubscribeMessage:63-79` now does exactly that
("Observed as `0` in the single `stockPlugin` sample … **Meaning unknown, and deliberately not
interpreted**"); its third finding asked for `obs != null && !obs.isEmpty() ? obs : bs`, and
`RikEndGameMessage:178` now reads exactly that. So those files carry a post-verdict fix pass.

None of this is a defect — the fixes are the right fixes and they are improvements — and the timing
(13:0x, two hours before the ziczac work started at 15:15) says they are the betting-mini fix pass,
not ziczac spillover. I confirmed no pre-existing RIK class mentions `ziczac`, `Plinko` or `forGame`
except `RikGameMessageTypes`, which is in scope. What is inaccurate is the *claim*: `RIK_114_ZICZAC`
AD-2 states the earlier classes "stay byte-for-byte" and concludes "the three PASS verdicts and the
2158-test baseline therefore remain valid statements about the same artifacts". They are not
statements about the same artifacts. Whoever consumes those verdicts downstream should know that
three of the reviewed files changed after they were signed, and that the baseline quoted in them is
neither the briefed 2175 nor today's 2211. I did not re-review those classes, as briefed.

**What makes the one-call-site claim tractable, and it is worth recording.** `TaiXiuMessageTypes` and
`SlotMessageTypes` are independent interfaces — neither extends `GameMessageTypes`
(`TaiXiuMessageTypes:39`, `SlotMessageTypes:20`). So `forGame`'s blast radius is exactly the six
BETTING_MINI providers and there is no third lookup path silently discarding an override. Had they
shared a supertype, `messageTypesRegistry.taiXiu(...)` and `.slot()` would each have become a place
where a future `forGame` override is ignored without a word. They do not, and
`BotFactoryForGameResolutionTest` now pins that both branches stay out of it.

**The "a typo cannot mis-route silently" argument is sound, and I verified the mechanism rather than
taking it.** It rests entirely on `Game.pluginName` reaching the wire, which it does:
`BettingMiniGameBot:258-259` builds `new Request(game.getPluginName(), …)` and `Request.subscribe():19`
puts that string straight into `SubscribeToLobbyMessage`. So a misspelled plugin name subscribes to a
plugin that does not exist and the group receives no rounds at all — loud, and not `forGame`'s to
catch. Two details that make this better than it had to be: `equalsIgnoreCase` is locale-independent,
unlike `toLowerCase()`, so there is no Turkish-dotless-I hazard; and the match covers both directions
of case variance, so a `Game` created as `ZicZacPlugin` routes correctly here and fails (if at all) on
the wire, which is the right side to fail on. The routing test pins `"zicza cPlugin"`, `"ziczac"`, a
null plugin name and a null game.

**On the shape question overall.** A per-call resolution hook is the right call for this change, and I
would have argued for it against the alternative. The decisive fact is not in the plan: the resolved
provider is the *only* thing the bot ever consults — `BettingMiniGameBot:247` calls
`messageTypes.getTypeRegistrations(offset, md5)` on the instance `BotFactory` set, and there is no
second path that re-derives message classes from the registry. So resolving once at bot-build time
genuinely decides the classes, the Jackson registrations and (in Phase 2) the outbound request
together, which is what makes the seam one decision instead of three. The registry's inventory, the
boot line and the coverage tests are untouched, and I confirmed `RikZicZacGameMessageTypes` carries no
annotation at all — pinned by `RikGameMessageTypesRoutingTest:134-143`, which is a nice inversion:
asserting the *absence* of a bean is what keeps the registry's product set honest. The cost is the
narrow enforcement gap in the first finding; pay that and the shape is good.

**The capture contains no credential material.** I scanned all three `.jsonl` files for
token/session/password/JWT-shaped keys and for any string of 40 characters or more: the only long
strings are the two provably-fair md5 hashes in the txmd5 capture, which are game data. AD-15's
reasoning (the AUTH frame carries no `cmd` and so could never match the capture window) holds
empirically.

**AD-15's PII cost is accurately stated and slightly under-scoped.** The ziczac capture and
`messages/rik/ziczac-subscribe.json` both carry the subscribe frame's `cH` chat history verbatim:
**36 free-text messages from 14 distinct staging usernames** (`Deadpool`, `Richvip`, `tobirama`, …).
Only one display name (`rv_lala00`, our own capturing account) appears in the game frames themselves,
so "~14 in-room display names" is really 14 chat authors. I am not raising this as a finding — it is
an explicit, recorded decision, the two already-shipped captures have the same property and passed
review, it is staging, and it is outside the security categories I am asked to police. The one thing
AD-15 does not say explicitly is that the material is in the **fixture** as well as the capture, which
is the more visible of the two; it does correctly note that scrubbing must be a same-shape
substitution in both, and `RikFixtureProvenanceTest` now enforces exactly that coupling, so the door
AD-15 leaves open is genuinely still open.

**One residual of the truncation hazard the classes are careful about, noted for completeness.**
`odd` is correctly a `double`, but `r` is a `long`, and `r = b × odd` is not integral for every stake:
a stake of 12 345 in an `odd:0.3` bucket yields 3 703.5. If the server ever emits a fractional `r`,
Jackson's `ACCEPT_FLOAT_AS_INT` truncates it silently — the same mechanism, bounded to sub-unit
amounts and so economically irrelevant, and `long` is mandatory for `HasBotWinnings` anyway. Worth
knowing only because `RikZicZacCaptureArithmeticTest:97` uses `within(0.5d)`, which already
accommodates exactly this; that tolerance reads like an accident and is arguably the right answer for
the wrong reason.

**Phase 2's absence is documented where a developer would look, not where an operator would.**
`RikZicZacGameMessageTypes:69-79` is unambiguous and names the consequence precisely; the plan's
per-aspect table marks "Outbound bet — **blocked → Phase 2**"; `RikGameMessageTypesRoutingTest:57-58`
mentions it in passing. That is thorough for the code's audience. The gap is the operator's, and it is
the fifth finding above rather than a complaint about Phase 2 not existing.

**Good patterns worth calling out.** Four things here are better than the bar. Asserting the payout
model over the *whole committed capture* rather than over hand-picked fixtures, so narrowing `odd` to
an integral type fails a test instead of quietly corrupting a dashboard. Asserting the equality
`sum(mbs[].r) == p.wm` that the code deliberately does **not** rely on, with the javadoc explaining
that the equality is what makes the choice *costless* rather than what justifies it — that is the
correct epistemics for a single-bettor capture, and it is precisely the reasoning Amendment A1 had to
learn the hard way. `RikZicZacUpdateBetMessage` existing solely to *remove* a marker interface, with
the reason stated as "an empty crowd is inert; a fabricated one is not" — I checked where that could
still leak and it does not: `RikSubscribeMessage` does implement `HasCrowdBets`, but
`BettingMiniGameBot` consults the marker only at `onUpdate:483` and `onEndGame:580`, never at
subscribe, and neither ziczac class carries it, so no fabricated `CrowdOption` can reach
`BetCoordinator.observeCrowd` on this game. And `GameMessageTypesForGameContractTest` enumerating the
real component scan instead of a literal provider list, which is the difference between a test about
today's six providers and a test about the contract.

---

# Code Review — RIK_114_ZICZAC — Phase 2

Branch: `feature/dead-group-auto-recovery`
Reviewed diff: **the working tree, not a commit range** — as for Phase 1, nothing ziczac is
committed; `git diff main..HEAD` is DEAD_GROUP_AUTO_RECOVERY and shows none of this. Scope is
Amendment A1's "What Phase 2 now is" and the dev's `### Phase 2 — implementation record
(2026-09-18)` beneath it; the superseded `### Phase 2` section further down was not used.

Production files, pinned to the bytes I reviewed (all written 2026-09-18 13:58):

| file | md5 |
|---|---|
| `bot-messages/.../request/ZicZacBet.java` (new) | `284e36702051c9f1e1e7f20060c94d9d` |
| `bot-messages/.../request/ZicZacRequest.java` (new) | `59a391232ab016c5ed0c964da3300422` |
| `bot-messages/.../g3/rik/RikZicZacGameMessageTypes.java` (changed) | `276e0b7ecc432f321733d7548b925ab4` |
| `bot-messages/.../g3/rik/RikGameMessageTypes.java` (**not** changed by this phase) | `86fcf4edbbd8cd798ebc62c1b1d798c0` |

Compared against the seam they mirror: `RikStockBet`, `RikStockRequest`, `RikStockCommit`,
`GameRequest`, `GameRequestFactory`, `RikGameMessageTypes.requestFor` / `forGame`, `Request`, `Bet`,
`BettingMiniGameBot.buildRequest`, and the ws-parser 3.0.5 `Body` / `ActionRequestMessage` sources
from the local `.m2`. Tests read: `ZicZacBetTest`, `ZicZacRequestTest`,
`BettingMiniGameBotZicZacRequestDispatchTest`, the edited `GameRequestFactoryCapabilityTest`,
`RikGameMessageTypesRoutingTest`, `BettingMiniGameBotRikRequestDispatchTest`, and the two files
that appeared after the record was written (see Notes). Out of scope as briefed: the pre-existing
working-tree dirt and everything Phase 1 already reviewed.

## Verdict

PASS

No `bug` and no `security` finding. The one finding is advisory, and it is about prose that is now
false rather than about code. Every domain fact the brief asked me to check is right in the code:

- **Serialized key set is exactly `{cmd, b, c, sid, aid}`, and the Lombok/Jackson reasoning is
  sound.** Class-level `@Getter` on `BetData` yields `getB()`, `getC()`, `getSid()`, `getAid()`;
  `Body` (ws-parser 3.0.5) is `@Getter @Setter` on `cmd`. Jackson's default name mangling folds the
  leading upper-case run of a getter, and each of these has a run of length one, so `getB` → `b`,
  `getC` → `c`. Nothing else on `BetData` or its supertype is visible at default field visibility.
  The production mapper is `new ObjectMapper()` with only `FAIL_ON_UNKNOWN_PROPERTIES=false` and
  subtype registrations (`BettingMiniGameBot:1020-1022`), i.e. serialization-side identical to the
  bare mapper the tests use — so `ZicZacBetTest`'s "the mapper the bot actually serializes with: a
  bare one" is a true claim, not an assumption.
- **The A1 deviation is correct and the plan was wrong.** A1 item 1 says "mirror `RikStockBet`'s
  shape (`@Getter(AccessLevel.NONE)` fields)". `RikStockBet.BetData` does not have that shape: it is
  class-level `@Getter @Setter`, with `@Getter(AccessLevel.NONE)` **only** on `iAc`, paired with
  `@JsonProperty("iAc")` because `getIAc()` would mangle to `iac`. Read literally, A1's wording
  would suppress every getter and, absent `@JsonProperty`, ship `{"cmd":12002}` alone — exactly the
  failure `ZicZacBetTest.keySetIsExact` would catch. The dev mirrored the class, not the summary of
  it, and the javadoc's "why no `@JsonProperty` here, and why that says nothing about `RikStockBet`"
  is the right thing to have written down.
- **`aid` is the integer `1`.** `Bet.BetData`, `RikStockBet.BetData` and `ZicZacBet.BetData` all
  declare `private final int aid = 1`; the capture's eight exported outbound `12002` frames all
  carry `"aid":1` as a JSON number. Same value, same type, same origin.
- **`subscribe()` is byte-identical to `Request.subscribe()`.** Same expression
  (`new SubscribeToLobbyMessage(zoneName, pluginName, new Body(cmdPrefix + 3000))`), same field
  order in the `@AllArgsConstructor`; `ZicZacRequestTest.subscribeIsIdenticalToTheSharedRequest`
  pins it both as node equality and as string equality of the full serialized envelope, which is
  strictly stronger than `RikStockRequestTest`'s string-only pin.
- **Nothing changed for stock or txmd5.** `RikGameMessageTypes.java` (mtime 2026-09-17 18:38),
  `RikStockBet` (17 13:49), `RikStockRequest` (17 17:55), `RikStockCommit` (17 18:07),
  `GameRequestFactory` (17 13:49), `GameRequest` (17 17:47) and `BettingMiniGameBot` (17 18:42) all
  predate the phase; `Request` and `Bet` are tracked and clean. `RikGameMessageTypes.requestFor`
  still allowlists `stockPlugin` and falls through to `Request` for everything else, and
  `BettingMiniGameBotZicZacRequestDispatchTest` re-runs both controls on the real scenario.
- **The ziczac provider is still annotation-free** — the class declaration carries nothing, and
  `RikGameMessageTypesRoutingTest.ziczacProviderIsNotABean` still pins it.
- **`commit()` is inherited, not overridden**, and the "no `12022` on the wire" half of that claim
  is asserted on the real `botBehaviorScenario()`, not on the `Optional`.
- **No shared mutable state, no logging, no token material, no executors.** `ZicZacRequest` and
  `ZicZacBet` are immutable value objects; the provider is stateless and shared, as its predecessor
  is. There is nothing here for the concurrency, leak, logging or security lenses to bite on.

## Findings

### [smell] The javadoc a reader lands on when debugging ziczac routing now says the opposite of what the code does
`bot-messages/.../g3/rik/RikGameMessageTypes.java:211-215`
`bot-messages/.../request/GameRequestFactory.java:23-25`
`bot-messages/.../g3/rik/RikBetInfo.java:16-18` (marginal)

`RikGameMessageTypes.requestFor` is the *visible* dispatch point on `pluginName` — it is where anyone
asking "which bet body does a 114 bot send?" will read first — and its javadoc says:

> `ziczacPlugin` never reaches this method — `forGame(Game)` has already routed it to
> `RikZicZacGameMessageTypes`, which does **not** implement `GameRequestFactory` in this phase
> (AD-24), so a ziczac bot falls back to `Request` at the `buildRequest` seam. Its own body ships
> with `RIK_114_ZICZAC` Phase 2.

Since 13:58 today the first clause is true and the rest is false: the provider implements the
interface, a ziczac bot sends `ZicZacBet`, and "falls back to `Request`" describes the exact frame
that A1 §4 records as being discarded at settlement. `GameRequestFactory`'s own javadoc has the same
problem one module boundary up — "Today exactly one provider implements this
(`RikGameMessageTypes`); a test over the real component scan pins that" — where the test it names
(`GameRequestFactoryCapabilityTest`) now pins **two**. `RikBetInfo:16-18` ("`Bet.eid` on every 114
game but `stockPlugin`") is a third, much smaller instance: ziczac sends no `eid` at all.

The dev's record is explicit that `RikGameMessageTypes` was left alone on instruction, and I agree
with the *reason* for the instruction — keeping the stock control's class byte-identical is what
makes V-Z4 a clean control. But the instruction protects behaviour, and this is a comment; a
javadoc-only edit changes no bytecode, and the class is untracked so there is no git-level
"byte-for-byte" to preserve either way — the pin is the md5 in this review and in the compliance
pass, both of which can be re-taken after a comment edit and diffed to prove it was comment-only.
Against that, the cost of leaving it is the one this project has already paid twice: Phase 1's
review flagged "a sentence that is false on its face three lines above the code that falsifies it"
in this same file (its third smell, `:87-89`, still unfixed — see Notes), and
`RIK_114_BETTING_MINI` Amendment A1 exists because a confidently-worded rationale shipped, was wrong
and had to be deleted from three places. This one is worse in kind than either: it is wrong about
which frame goes on the wire in the money path, in the paragraph a debugger reads first.

**My judgment: fix it now, in this working tree, before anything is committed** — replace the four
lines at `:211-215` with the true statement ("`ziczacPlugin` never reaches this method:
`forGame(Game)` routes it to `RikZicZacGameMessageTypes`, which implements `GameRequestFactory` in
its own right and answers a `ZicZacRequest`; see that class"), and drop or update the "exactly one
provider" sentence in `GameRequestFactory`. Have the compliance pass verify the diff to
`RikGameMessageTypes.java` is comment-only. `GameRequestFactory` was **not** on the do-not-edit
list, so that one needs no dispensation at all. If the decision is nevertheless to hold both until
the next touch, the plan's A1 record should say so in as many words rather than "left for whoever
next touches that file", because the next person to touch it is most likely to be the one debugging
a ziczac bet, reading the sentence that tells them the bet is not shipped.

## Notes

**The tree moved under this review, again, and the implementation record is already stale by two
files.** The plan's record (written 14:06:22) lists three new test files and "+19 = the tests listed
above, nothing else moved" for a 2286 total. `ZicZacBetCaptureFidelityTest` (9 tests, 14:10:04) and
`RikZicZacGameMessageTypesRequestForTest` (6 tests, 14:10:36) were written after that sentence and
are not in it. Both are test-only and both are good — the first asserts our key set against every
exported outbound `12002` frame in the capture rather than against a typed literal, and rebuilds
each captured frame from its own values node-for-node including the 1 060 000 stake; the second
proves the three `requestFor` arguments each reach the wire and none is a constant, including the
"caller's offset wins over `Game.offset`" contract that `RikGameMessageTypes.requestFor` shares.
Neither changes my verdict. What it changes is the arithmetic anyone downstream quotes: the honest
count is 2286 + 15, and "nothing else moved" is not a statement about the tree as it stands. I
re-read all four production files after the last write and the md5s above are current as of
14:12:56; QA should re-take them.

**Phase 1's third smell is still open, and Phase 2 widened the gap it describes.**
`RikGameMessageTypes:91-93` still reads "Nothing about this class changed to make that possible …
byte-for-byte", in a class that has since also gained `implements GameRequestFactory`, a
`requestFor` override, an allowlist constant and eleven paragraphs of javadoc about the outbound
frame. Not a new finding — it was raised, with a fix shape, on 2026-09-16 — but it is the same
file, the same habit, and the same reason the finding above says "now".

**On the `@AllArgsConstructor` (String, String, int) hazard: verified correct, and pinned.**
`ZicZacRequest`'s field order is `(pluginName, zoneName, cmdPrefix)` — identical to `Request` and
`RikStockRequest` — while the interface it is built from takes `(game, zoneName, offset)`, so the
one-line body `new ZicZacRequest(game.getPluginName(), zoneName, offset)` is the place a
zone/plugin swap would compile silently. It is not swapped, and three tests would catch it if it
were: `ZicZacBetTest.envelopeCarriesZoneThenPlugin` pins `["6","MiniGame","ziczacPlugin",{…}]`
positionally, `RikZicZacGameMessageTypesRequestForTest.zoneNameIsPropagated` /
`pluginNameComesFromTheGame` pin slots 1 and 2 independently with non-default values
(`MiniGame3`, `ZicZacPlugin`), and the dispatch test asserts the same on the real scenario.

**`game.getPluginName()` without a null guard is consistent with the contract, not a deviation
from it.** `RikGameMessageTypes.requestFor` null-checks `game`; `RikZicZacGameMessageTypes.requestFor`
does not. `GameRequestFactory`'s `@param game` javadoc (rewritten in RIK Phase 2) says explicitly
that the `Game` is never null at the sole call site, that an implementation "need not be defensive
about the `Game`", and that the generic provider's guard is "incidental hardening, not as this
interface's contract". The ziczac javadoc adds the stronger structural reason — this instance is
only ever *obtained* by `forGame` matching a non-null plugin name on a non-null game — and
`BotFactoryForGameResolutionTest` (Phase 1) pins that `buildRequest` receives the same `Game`
instance `forGame` resolved on. I checked that chain rather than taking it. `pluginName` likewise
cannot be null here: `equalsIgnoreCase` against a literal has already returned true on it.

**The `c = 1` argument in `ZicZacBet`'s javadoc is right, and I checked the three call sites it
names.** `creditBalance(amount)` at `BettingMiniGameBot:825`, `memory.recordBetSent(currentSid,
optionId, amount)` at `:829`, and `BotMemory:180`'s `balanceDelta = payout - staked` all assume one
send debits exactly `amount`; with `c = 1` the server debits `b × 1`. A configurable `c` would
silently break all three by a factor of `c`, and the javadoc says so and points at the existing
`maxBetsPerRound` knob instead. That is the right shape for a first cut and the right place to
have written the warning.

**Two documentation nits I am not raising as findings.** (1) A1 item 4 says the probe game's
`optionAffinities` is `{1:1}`; `RikZicZacGameMessageTypes`' operator-facing javadoc says to use
`{"0": 1}`. Both are degenerate and the option is discarded, so neither is wrong, but an operator
copying the code's advice onto a group created from the plan's will wonder which is canonical.
(2) `ZicZacRequest.bet`'s javadoc says the narrowed `ZicZacBet` return type "is documentation, not a
contract anyone depends on"; `ZicZacRequestTest.betReturnsTheZicZacBody:71` assigns
`ZicZacBet bet = ziczac.bet(…)` and so compiles against it. Harmless, and the same is true of
`RikStockRequest`.

**The reflective `body()` helper now has eighteen copies across the test suite** (every request /
bet test in `bot-messages` and most bot dispatch tests in `bot-engine`, `grep
'getDeclaredField("body")'`). The three new test classes added three more. This is the established
pattern and not a Phase 2 defect — Phase 1's style finding on duplicated capture readers is the
same observation one level up — but a package-private `RequestBodies.of(ActionRequestMessage)` test
helper in `bot-messages` would remove all of them and is a fifteen-minute change whenever someone
next opens one of these files.

**The `RikGameMessageTypes.java` mtime is worth one sentence for whoever reconciles the
`RIK_114_BETTING_MINI` verdicts.** It was last written at 2026-09-17 18:38:27, nine minutes after
that feature's `review.md` (18:29:44) and eight after its `compliance.md` (18:30:10) — the same
post-verdict-fix-pass shape Phase 1's review recorded for three other RIK classes. Out of my scope
here; noted so it does not surprise anyone comparing that review's pins to today's md5.

**Good patterns worth calling out.** `ZicZacBetTest.noKeyNeedsAnExplicitJsonProperty` asserts the
*wire* property (every emitted key equals its own lower-casing) and only then the source property
(no field annotation), so it fails with the rule named if a mixed-case key is ever added — that is
the right order for a test whose purpose is to teach. `ZicZacRequestTest.commitIsAbsent` asserts
the method is *not declared* rather than merely that it returns empty, so a "commit everywhere"
change to the interface default is the only path that can flip it. And the dispatch test drives
the real `botBehaviorScenario()` and asserts *every* frame that leaves is one of two cmds, which is
how "no commit" becomes a statement about the socket instead of about an `Optional`.
