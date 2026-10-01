# Code Review — RIK_114_BETTING_MINI (Phase 3 — send the per-bet commit `13022`)

Branch: `feature/dead-group-auto-recovery` (the RIK work is uncommitted in the working tree)
Reviewed diff: **not** `git diff main..HEAD` — `main` predates the `bot-messages` split and
the feature is uncommitted. Scope was bound by mtime (files written in the last ~60 minutes
before 17:30 on 2026-09-17), then cross-checked against `git diff HEAD` for the two tracked
files. Phases 1 / 1b / 2 are deployed and were read only for damage this phase does to them.

| File | sha1 as reviewed |
|---|---|
| `bot-messages/.../message/request/GameRequest.java` (edited, +35) | `d73023d48f50b6605f57a4e6105c31e87553dd1a` |
| `bot-messages/.../message/request/RikStockCommit.java` (new) | `dc49125549fa3c2cc0e7a9f1ca11d53bca51ddaf` |
| `bot-messages/.../message/request/RikStockRequest.java` (edited) | `2fd7cd6b68c526f776a0b98fa568c40b23435b38` |
| `bot-messages/.../message/g3/rik/RikGameMessageTypes.java` (edited) | `db875fb5ed0f50becebaf444177923275657b8ab` |
| `bot-engine/.../bot/core/BettingMiniGameBot.java` (edited) | `3e5055e4c64c01197ed204ea9309297b4397c6e0` |

Build: `mvn -o -pl bot-messages,bot-engine -am test` for the Phase 3 selection under JDK 21 —
`RikStockCommitTest` 6/6, `RikStockRequestTest` 6/6, `RequestTest` / `TaiXiuRequestTest`
(incl. the new `CommitTests` nests) green, `BettingMiniGameBotCommitDispatchTest` 6/6 green.
`bot-engine` compiles against ws-parser 3.0.5 (`SentMessageContext` import resolves).

Library facts below were read from `websocket-parser-core-3.0.5-sources.jar`
(`SendAsync.java`, `OutboundMessage.java`, `VingameWebSocketClient.java`,
`PipelineContext.java`, `ActionRequestMessage.java`, `VirtualThreads.java`), not from the
plan's paraphrase of them.

## Verdict

PASS

No `bug` and no `security` finding. Two smells (one brittleness in the send path, one
failure-mode log volume), two styles (javadocs this phase made stale). Nothing blocks the
deploy; smell 1 is the one I would want fixed before the *next* phase builds on this seam.

### Dev's claims, verified rather than accepted

| Claim | Verdict | How |
|---|---|---|
| `Request.java` untouched | **true** | absent from `git status --porcelain`; last commit `1189a2f`; `grep commit` over the file returns nothing |
| `TaiXiuRequest.java` untouched | **true** | same three checks |
| `RikGameMessageTypes` javadoc only | **true** | 254 → 263 lines; the +9 are exactly the new `<p>` paragraph at `:110-118`; `requestFor` body at `:220-224` is character-identical to the code quoted in the Phase 2 review |
| `BettingMiniGameBot` code changes are the seven listed | **true** | `git diff HEAD` non-comment lines: `COMMIT_CODE`, `pendingCommit`, the `beforeReconnect` clear, `pendingCommit.set(...)` in the supplier, `afterBetSent`, `.onSent(...)`, the `cmdList` entry — plus the (already deployed) Phase 2 `instanceof GameRequestFactory` hunk. Nothing else |
| `onSent` runs on the same thread as the send, immediately after | **true** | `SendAsync.processInternal:113-139`: one runnable does condition → `messageSupplier.get()` → `client.send(serialize)` → `onSent.accept(...)`, sequentially |
| each `SendAsync` owns its own single-thread scheduler | **true** | `SendAsync:100`: `VirtualThreads.newScheduler("ws-send-async-")` → `Executors.newSingleThreadScheduledExecutor(...)` |
| `interval == 0` is a one-shot regardless of mode | **true** | `SendAsync:143-147` |
| `client.send` swallows everything | **true** | `VingameWebSocketClient.send:587-610`: not-connected → WARN + return; body in `catch (Exception)` → ERROR |
| `serialize` can only throw unchecked | **true** | `MessageParsingException extends WebSocketParserException extends RuntimeException` |
| `sId` javadoc carries the *corrected* B1 reasoning | **true** | see Notes 5 |

## Findings

### [smell] The commit is sent through `this.client`, not through the channel the bet left on — "same ordered Netty channel" is true only while nobody has swapped the field
`bot-engine/src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java:868`
(claim at `:840-843` and in the AD-31 comment at `:1032-1033`)

```java
client.send(frame);          // Bot.client — the field
```

`SendAsync` sends the bet through the client it captured at construction
(`this.client = context.getClient()`, `SendAsync:96`, i.e. what `buildContext(...).client(client)`
held when `botBehaviorScenario()` ran). `afterBetSent` sends the commit through the **bot's
mutable field** `Bot.client` (`Bot.java:94`, non-volatile), which `Bot.restart()` (`:392`) and
`Bot.tryReconnectWs()` (`:854`) reassign. The two are the same object for the life of one
connection — and different during the hand-off, which is exactly the window in which this
codebase has repeatedly found its races.

Concretely: `tryReconnectWs` does `closeQuietly(old)` → `this.client = fresh` →
`fresh.connect()` → `beforeReconnect()`. `closeQuietly` reaches `PipelineContext.shutdown`,
which calls `executor.shutdown()` — graceful, so a bet runnable already past its
`context.isActive()` check runs to completion. Its `client.send(bet)` goes to the *old* client
(closed → dropped with the library's WARN), then its `onSent` → `afterBetSent` →
`this.client.send(commit)` goes to the **fresh** client, which `isConnected` from handshake
completion on (`VingameWebSocketClient:449`), before the AUTH ack when `awaitServerReady` is
false. Result: a `13022` for a bet that never left, carrying the previous connection's sid, as
possibly the first frame on a socket the server has not authenticated. `beforeReconnect`'s
clear cannot help — it runs *after* `connect()` returns.

The window is microseconds per 1 s tick and requires the reconnect to land inside it, so this
is a smell, not a bug: at most one stray frame per reconnect, on a bot that is already
reconnecting, and the same class of stray *bet* already exists on this path pre-Phase 3. But the
javadoc states the same-channel property unconditionally, the design argument for `onSent`
over a second stage rests on it, and Phase 3b is about to add a second frame to this seam.

Fix shape, one line each: capture the client once in `botBehaviorScenario()` and hand it to the
callback — `VingameWebSocketClient c = client; ... .onSent(mdcConsumer(afterBetSent(mapper, c)))`
— so the commit is bound to the same channel as its bet by construction, and a post-close
commit is dropped by the same closed client that dropped its bet. Then the "same ordered
channel" sentence becomes true without qualification.

### [smell] The failure-mode WARN in `afterBetSent` is per bet, per bot, unthrottled — the one shape the logging rule exists to keep out of track 1
`bot-engine/src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java:869-873`

```java
} catch (RuntimeException e) {
    log.warn("Bot {}: commit frame not sent: {}", getUserName(), e.toString());
}
```

The `try/catch` itself is right and necessary (an escape cancels the fixed-rate task —
`ScheduledExecutorService` semantics, the javadoc says so correctly). What is off is the shape
of what it emits. `RuntimeException` here is reachable only through
`ActionRequestMessage.serialize` → `MessageParsingException`, which on a two-field body is a
deterministic property of the class and the mapper: if it fails once it fails on **every**
bet. `resolveIntervalBetweenBets()` is `1_000L` (`:971`), so a persistent failure on a 300-bot
stock group is ~300 WARN/s into `console.log` and Loki — the CLAUDE.md rule is stated for INFO
and its rationale ("rate a function of bot count or round rate") applies with more force one
level up. AD-32's own justification — "if it ever fires persistently, that WARN *is* the
signal" — needs one line to be a signal, not one per bet.

Two secondary points in the same three lines: `e.toString()` discards the stack of what would
be a genuinely unexpected Jackson failure (the rest of this class passes `e` or
`e.getMessage()`; `Bot.closeQuietly` passes the throwable for the same "should never happen"
class of WARN); and the catch does not distinguish "serialize threw" from anything a future
edit puts inside the block, so the comment "guards only `serialize`" is true of the code today
and enforced by nothing.

Fix shape: WARN **once per bot** with the throwable (an `AtomicBoolean commitFailureWarned`
alongside `pendingCommit`, or a per-bot `warnedOnce` helper if one exists), DEBUG thereafter.
The one WARN line is still Loki-visible and still per-bot-scoped enough to find the group. Not
a bug: unreachable by construction today, and `callbackNeverThrows` pins the never-throw part
that matters.

### [style] The commit TRACE line logs `sidStore.get()`, not the sid the frame carries — and the frame is already TRACE-printed by `OutputPrinter`
`bot-engine/src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java:867`

```java
log.trace("Bot {}: sending commit sid={}", getUserName(), sidStore.get());
```

The commit's sid was fixed in the supplier (`request.commit(currentSid)`, `:833`); this line
re-reads the store at callback time. `sidStore` is written on the netty thread
(`onStartGame`, `beforeReconnect` → `set(0L)`), so a StartGame landing between supplier and
callback, or the reconnect race in smell 1, prints a sid the frame does not contain — and sid is
the one field V-19 will correlate on. Meanwhile `VingameWebSocketClient.send` feeds every SENT
frame back through the scenarios (`:601-607`), so with `offset + COMMIT_CODE` now in the
`OutputPrinter` list the same TRACE window already shows the full `13022` frame verbatim, with
its real `sId`. Either drop the line (the printer is the authoritative record) or, if a
bot-attributed line is wanted, park the sid next to the frame (a two-field record in
`pendingCommit`) and log that. Cosmetic in effect, filed because it can mislead the exact
verification this phase is for.

### [style] Phase 3 made two earlier javadocs false, in the two files a reader opens first
`bot-messages/src/main/java/com/vingame/bot/domain/bot/message/g3/rik/RikSubscribeMessage.java:26-29`
and `bot-messages/src/main/java/com/vingame/bot/domain/bot/message/g3/rik/RikGameMessageTypes.java:120-127`

- `RikSubscribeMessage:26-29`: *"The real client also sends a bare `13012` right afterwards
  and a `{"cmd":13022,"sId":…}` once per round; **we send neither**."* As of this phase a
  stock bot sends `13022` after every bet. One clause: "we send `13022` (after every bet,
  Phase 3) and not `13012` (Phase 3b, AD-34)".
- `RikGameMessageTypes:120-127`, the paragraph **immediately after** the new Phase 3
  paragraph: *"A rival hypothesis survives … the real client also sends a bare `13012` … the
  extra frame is **Phase 3**, gated on the same verification reading zero again."* Under
  Amendment A4 that frame is Phase **3b** (AD-34) and the gate is **V-17**, not the V-6/V-12
  reading this sentence was written against. Two adjacent paragraphs now assign "Phase 3" to
  two different frames.

Same failure mode the Phase 1b and Phase 2 reviews each flagged once (a confidently-worded
rationale left standing after the decision under it moved), and the brief asked for exactly
this. Both are one-line edits. I grepped `g3/rik/*`, `request/*` and `GameRequestFactory` for
`13012` / `13022` / `Phase 3`: these two are the only stale sites; `RikStockCommit`,
`RikStockRequest`, `GameRequest` and the new `RikGameMessageTypes:110-118` paragraph are all
correct and mutually consistent.

## Notes

**The five questions the brief asked, answered.**

1. **The `onSent` route — the explanation is correct, and durable in the way that matters.**
   Every load-bearing claim checks out against the 3.0.5 source (table above). The rejection
   of a second `sendAsync` stage is correctly argued: a second stage is a second
   single-thread scheduler (`SendAsync:100`), so "supplier returned" on thread A and
   `client.send(bet)` on thread B have no ordering relation at all, and an `INFINITE` stage
   with `interval == 0` would be a one-shot (`:143-147`), forcing a second fixed-rate timer
   per bot on every product. `onSent` is the only hook the library offers that runs *after*
   `client.send` returns and *on the same thread*. Durability: this is a property of the
   library, not of our code, and a future ws-parser that dispatched `onSent` to a callback
   executor would silently break the ordering — but
   `BettingMiniGameBotCommitDispatchTest.stockSendsBetThenCommitInOrder` runs the **real**
   `SendAsync` path (the test name says so and the test body confirms it), so a library bump
   that changes the contract fails the build rather than reordering frames in prod. That is the
   right place for the pin. The corrected threading comment at `:995-1001` is now **true, not
   merely different**: "onMessage handlers run on the per-client
   `netty-ws-message-processor-ws-<userName>` pool" (unchanged, still right — that is the
   processing executor in `VingameWebSocketClient:196-222`); "each sendAsync STAGE owns its
   own single-thread scheduler … this pipeline has exactly one such stage, and its condition,
   supplier and onSent callback run on that one thread, in that order" — verified line by
   line. The old text ("pool-N-thread-1") named a thread that no longer exists under 3.0.5's
   virtual-thread schedulers; the new text names the actual prefix (`ws-send-async-`) via the
   AD. The one caveat is smell 1: the *send* in the callback is on that thread, but the
   *channel* is whichever `this.client` points at.

2. **`pendingCommit` as a field — set/read on one thread is guaranteed, not assumed; a
   stale-sid commit cannot go out in steady state; a closure local was not available, but a
   scenario-scoped holder would have been tighter.** Guaranteed: the set (`:833`) is inside
   `messageSupplier.get()` and the pop (`:861`) inside `onSent.accept(...)`, both called from
   the one runnable in `SendAsync.processInternal`, sequentially, with only `client.send`
   between them — and `client.send` never touches bot state. The scheduler is
   single-threaded, so no second tick can interleave. Stale sid: between set and pop, the only
   other writer is `beforeReconnect` (`:651`), which *clears*; nothing can replace the parked
   value with a different sid, and an empty pop is a no-op. The AtomicReference is therefore
   doing "one writer, one reader, memory visibility across a netty-thread clear" — correct and
   the same shape as `pendingDecision`, which is a reasonable consistency argument. What a
   scenario-scoped holder would buy: `bet()` and `afterBetSent()` are two separate lambdas, so
   no single closure local can span them — but a holder created in `botBehaviorScenario()`
   and handed to both would live and die with the scenario, so the old scenario's in-flight
   runnable and the new scenario's could never see one another's parked value, and the
   `beforeReconnect` clear (which currently runs *after* `connect()`, i.e. late for smell 1's
   window anyway) would become unnecessary rather than "for symmetry". Advisory — the field
   version is correct — but it would also remove the AD-32 sentence about a clear being cargo
   cult, which is the kind of sentence that later gets argued with.

3. **`afterBetSent` swallowing `RuntimeException`** — smell 2. The catch is necessary and
   correctly scoped to the fixed-rate-task hazard; the emitted level and rate are the issue.
   The `callbackNeverThrows` test pins the property that matters.

4. **The `OutputPrinter` list change is a print filter and nothing else — confirmed.**
   `OutputPrinter.filter` (`bot-api/.../util/OutputPrinter.java:24-31`) maps the list through
   `Qualifier::cmd` and OR-reduces it; `debugOutputPrinter` (`:66-71`) logs at `log.trace`
   through the MDC wrapper. Nothing deserializes on that scenario (`ObjectMapperProvider
   .getDefault()` is used only to pretty-print the raw frame). On a non-stock product the
   entry is `offset + 3022` — 5022 (BauCua), 11022 (ziczac), 7022 (txmd5), 3022 (Tai Xiu: the
   `offset` field is the 0-fallback of a `null` `Game.offset`, `:175-176`, and a grep of the
   three `taixiu/*MessageTypes` for `3022` returns nothing) — and no such CMD exists on the
   wire for any of them, so the qualifier matches nothing. It is one integer in a `List.of`.
   One useful consequence the comment does not mention: because `VingameWebSocketClient.send`
   feeds the SENT frame back through every scenario (`:601-607`), the printer will show our
   own outbound `13022` as well as the reply, which is what makes the V-19 capture possible at
   all — and what makes style 1's trace line redundant.

5. **The `sId` javadoc carries the corrected B1 reasoning, not the original.** The class
   javadoc (`RikStockCommit:32-48`) says the getter is suppressed because "the shipped shape is
   the only one correct under **every** Lombok configuration … with no getter at all the
   annotated field is the single property whatever the repo-root `lombok.config` says" — that
   is B1's corrected conclusion (independence from `copyableAnnotations`), and it cites
   `RikStockBet`'s measured table rather than re-asserting the withdrawn "otherwise it emits
   two keys" claim. It also records a detail `RikStockBet` did not need to: `CommitData` has
   **no class-level `@Getter`/`@Setter`**, so the field-level `@Getter(AccessLevel.NONE)` is
   a no-op today and is kept as the guard against someone adding the class-level annotation
   back (B1 row 2 territory). I verified the shape empirically rather than by reading:
   `RikStockCommitTest.sIdIsNotFolded` and `keySetIsExact` pass on this module's classpath
   (jackson-databind 2.15.2), and the `Body` superclass contributes exactly `cmd` through
   Lombok's `@Getter`, so the emitted key set is `{cmd, sId}` and nothing else. AD-30's rule
   (design-time naming of the folded form, `@JsonProperty` on a getter-less field, serialized
   key-set test with `doesNotContain` of the fold, and a note when the fold collides with a real
   key) is followed on all four points — the test's comment names `sid` as the bet frame's
   session key.

**Things I checked that came back clean.** `GameRequest.commit`'s default and the two
inheriting classes: `Request` and `TaiXiuRequest` have no `commit` text and are pinned empty by
the new `CommitTests` nests. `RikStockRequest.commit` is reached only via
`RikGameMessageTypes.requestFor:220-222`'s `stockPlugin` allowlist, so the scoping claim in
its javadoc is structural, not asserted. The `mapper` handed to `afterBetSent(mapper)` is the
same instance `buildContext(..., mapper)` gives the `PipelineContext`, so bet and commit
serialize through one mapper. `MessageParsingException` is unchecked, so the `catch
(RuntimeException)` is not silently narrower than what `serialize` can throw. No new executor,
scheduled task, WebSocket client or MDC-carrying thread; `mdcConsumer` wraps the callback, so
the TRACE line and any WARN carry the bot's MDC. No token-shaped material anywhere in the new
code — the commit body is `{cmd, sId}`. `PerBotInfoLogGuardTest`'s no-`log.info(` rule for
this class is respected (TRACE + WARN only). `COMMIT_CODE` is a `private static final int` next
to `RAMP_P_MIN`, named and commented consistently with its neighbours.

**On the MDC wrapper cost, not a finding.** `mdcConsumer(afterBetSent(mapper))` does a
`MDC.getCopyOfContextMap()` + `setContextMap` + restore on **every bet tick of every bot on
every product**, to guard a TRACE line and a WARN that only stock can reach. `mdcSupplier`
already does this twice per tick (condition + supplier), so it is a 50% increase on a cost that
is already accepted; and without the wrapper a stock WARN would be unattributed. Right call,
worth knowing.

**One question for the author, not a finding.** AD-29 records the once-per-round fallback
("commit on the first bet of each sid"). If V-19 shows the server rejecting the second and
later commits in a round, the natural place to implement that is the supplier (`:833`, key the
`commit(...)` call on a sid change), and smell 1's fix (client captured per scenario) plus the
scenario-scoped holder from note 2 would make that a local change with no new cross-thread
state. Worth deciding the holder question before 3b rather than after.

---

## Appendix — Phase 2 review (2026-09-17), preserved verbatim

The file below is the earlier review of Phase 2, kept because it is the only record of that
verdict, with its headings demoted two levels so this file has one set of top-level sections
(its own Phase 1 appendix is demoted with it). **Its verdict line applies to Phase 2 only**;
the Phase 3 verdict is the one at the top of this document.

### Code Review — RIK_114_BETTING_MINI (Phase 2)

Branch: `feature/dead-group-auto-recovery` (the RIK work is uncommitted in the working tree)
Reviewed diff: **not** `git diff main..HEAD` — that shows none of this feature. Scope was
bound by working-tree state + mtime, to the five production files carrying a 2026-09-17
mtime:

| File | sha1 as reviewed |
|---|---|
| `bot-messages/.../message/request/GameRequestFactory.java` (new) | `d1825365b309292a27b3079891e4fe4e33b8ca3a` |
| `bot-messages/.../message/request/RikStockBet.java` (new) | `734c629b1616557b651fe8e76cfbd23d0264d7e9` |
| `bot-messages/.../message/request/RikStockRequest.java` (new) | `3b1997fb7da3bfa7782bb2dced739777765415ba` |
| `bot-messages/.../message/g3/rik/RikGameMessageTypes.java` (edited) | `177201f4e5f8fff9b992035daa842a54f9f5547b` |
| `bot-engine/.../bot/core/BettingMiniGameBot.java` (edited) | `38c9cf17578ae3fd0de909a606f9e7af905ce39b` |

Phase 1 / 1b files (`g3/rik/*`, `GameMessageTypes.forGame`, `BotFactory`) were read for
**supersession damage only**, per the brief. Unrelated working-tree modifications
(`deploy.sh`, `Aviator.js`, `bc.js`, `TaiXiuMessages/*`, `docs/plans/*`,
`docs/reviews/DEAD_GROUP_AUTO_RECOVERY/*`, `scripts/*`) were not reviewed.

Build: `mvn -o test-compile` clean across the whole reactor under JDK 21.
`RikStockBetTest`, `RikStockRequestTest`, `RikGameMessageTypesRoutingTest`,
`GameRequestFactoryCapabilityTest`, `RequestTest`, `RikGameMessageTypesTest` green;
`bot-engine` `BettingMiniGameBotRik*DispatchTest` + `...ZicZacDispatchTest` 21/21 green.

#### Verdict

PASS

No `bug` and no `security` finding. The four findings below are advisory: two are
brittleness, two are stale cross-references. Nothing here should block the deploy.

##### Dev's claims, verified rather than accepted

| Claim | Verdict | How |
|---|---|---|
| `Request.java` untouched | **true** | mtime `Aug 4 16:10`, absent from `git status` |
| `Bet.java` untouched | **true** | same; `AutoBet.java` likewise |
| `TaiXiuGameBot` untouched | **true** | mtime `Sep 16 15:47` (Phase 1b era) but content == HEAD — absent from `git status`, and it overrides `buildRequest` so the new seam cannot reach it |
| Only `RikGameMessageTypes` implements the capability | **true** | `GameRequestFactoryCapabilityTest` boots a real `AnnotationConfigApplicationContext` scan and asserts it over every registered provider *and* every `forGame`-resolved one — the javadoc's "a test over the real component scan pins that" is not an empty claim |
| `@JsonProperty("iAc")` + suppressed getter is the only shape that emits `iAc` once | **true, re-verified independently** | see Notes |
| Capture says `v` + `iAc`, no `b` | **true** | 8/8 exported outbound `13002` frames are exactly `{cmd,v,sid,aid,eid,iAc}` with `iAc:true`; envelope `plugin=stockPlugin`, `zone=MiniGame` — matches the allowlist constant and the zone the bot sends |

**One thing moved under me mid-review.** `RikGameMessageTypes.java` (13:39:48) and
`RikStockBet.java` (13:39:25) were re-saved *after* I first read them at 13:2x. Re-read in
full: line counts identical (254 / 98) and I could find no content difference. The test
selection above was **re-run at 13:40 against the post-change files** and is green, and the
hashes in the table are the post-change ones. Flagging it because on this feature a
"byte-for-byte unchanged" claim has already gone stale once; a later reader should compare
against the hashes, not against the claim.

#### Findings

##### [smell] The one production construction of `RikStockRequest` passes two same-typed Strings positionally, and the envelope it produces is asserted nowhere
`bot-messages/src/main/java/com/vingame/bot/domain/bot/message/g3/rik/RikGameMessageTypes.java:213`

```java
return new RikStockRequest(game.getPluginName(), zoneName, offset);
```

`RikStockRequest` is `@AllArgsConstructor` over `(String pluginName, String zoneName, int
cmdPrefix)` — copied from `Request`, and correct as written. But the two `String`s are
adjacent, same-typed, and in the *opposite* order to the method's own parameter list
(`requestFor(Game game, String zoneName, int offset)`), so writing
`new RikStockRequest(zoneName, game.getPluginName(), offset)` compiles, type-checks, and
ships `["6","stockPlugin","MiniGame",{…}]` — a frame with zone and plugin transposed, which
this server will simply not route. The body would be byte-perfect, which is the worst
possible shape for a bug on *this* feature.

What makes it a smell rather than a nit: that mistake survives the whole suite. The only
assertions that read the envelope build their object directly with literal arguments
(`RikStockBetTest.envelopeElementZeroIsTheStringSix`,
`RikStockRequestTest.betReturnsTheStockBody`), and the two tests that go *through*
`requestFor` (`RikGameMessageTypesRoutingTest.requestRoutingMatrix`,
`BettingMiniGameBotRikRequestDispatchTest.stockBotBuildsTheStockRequest`) assert the type
and the body keys and never the zone/plugin slots. So the single line where the 114 fork is
actually wired is the one line with no envelope coverage.

Fix shape (cheapest first): assert `serialize(...)` starts with
`["6","MiniGame","stockPlugin",` in the existing bot-level dispatch test — it already holds
a fully built bot, so it costs one line; or give `RikStockRequest` an explicit constructor
with a javadoc naming the order; or have the dispatch build both and compare envelopes
against the `Request` it replaces. The identical hazard exists on `Request` itself and is
pre-existing — I am not asking for that to change.

##### [smell] `GameRequestFactory` mandates null-`Game` tolerance that the seam's own fallback does not have
`bot-messages/src/main/java/com/vingame/bot/domain/bot/message/request/GameRequestFactory.java:47-49`
and `bot-engine/src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java:274-283`

The interface tells implementers they "must tolerate a `null` game and a `null` plugin
name"; `RikGameMessageTypes.requestFor` duly does
(`game != null ? game.getPluginName() : null`), and a routing-test row pins it. But the
caller's own fallback three lines below the `instanceof` dereferences `game.getPluginName()`
unconditionally, so a `null` game NPEs for every provider that does *not* implement the
capability — i.e. all six but one. The tolerance is therefore unreachable in production and
the contract reads stronger than the seam is.

Harmless at runtime (the production call site passes a non-null `Game`, and
`initializeSubclass` would have NPE'd earlier anyway), but it is a contract that cannot be
relied on and will mislead the next implementer — the ziczac capability is due on this exact
interface next phase. Fix shape: either state the truth (`@param game never null at the
production call site; implementations need not be defensive`) and drop the null rows, or
make the fallback null-safe so the sentence becomes true end to end. The routing test's own
comment already concedes the asymmetry ("buildRequest's fallback would have dereferenced
`game.getPluginName()` anyway"), which is a good sign the javadoc, not the code, is what is
off.

##### [style] Two Phase-1 javadocs still name the class that stock stopped using today
`bot-messages/src/main/java/com/vingame/bot/domain/bot/message/g3/rik/RikSubscribeMessage.java:23-26`
and `bot-messages/src/main/java/com/vingame/bot/domain/bot/message/g3/rik/RikBetInfo.java:16`

- `RikSubscribeMessage`: *"The outbound subscribe needed no work — the real client sends a
  bare `{"cmd":13000}`, byte-identical to what `Request.subscribe()` already emits."* As of
  Phase 2 a `stockPlugin` bot's subscribe comes out of `RikStockRequest.subscribe()`. The
  *fact* survives — the two are byte-identical and
  `RikStockRequestTest.subscribeIsIdenticalToTheSharedRequest` pins it — but the class named
  is no longer the one stock uses, and this is the file a reader opens to answer "did
  subscribe change for stock?".
- `RikBetInfo`: *"The same id space as the outbound `Bet.eid`."* Stock's outbound `eid` now
  rides `RikStockBet.eid`.

Both are one-word fixes ("the outbound bet's `eid`"; "…what `Request`/`RikStockRequest`
emit"). Filing them because a confidently-stale rationale left in place is this feature's
established failure mode (Amendment A1), and these two are the only survivors I found — I
grepped the whole `g3/rik` package and the `request` package for outbound-bet claims;
`RikZicZacGameMessageTypes:69-79` ("the outbound bet body is NOT supplied here yet") is
still **correct** under AD-24 and should not be touched.

##### [style] An unqualified `AD-9` one file away from a differently-numbered `AD-9`
`bot-messages/src/main/java/com/vingame/bot/domain/bot/message/request/RikStockBet.java:28`

> Fidelity over hedging, the rule AD-9 set.

That is `RIK_114_BETTING_MINI` AD-9 (plan:456-462 — "…ships `RikBet` emitting the real
client's frame **verbatim** … fidelity over hedging"), and it is right. But its sibling file
`GameRequestFactory.java:8` cites *`RIK_114_ZICZAC` AD-9* for something else entirely (the
capability seam), and the two classes are in the same package, written in the same session,
both about this frame. A reader who carries one citation into the other file lands on the
wrong decision. Qualify it: `RIK_114_BETTING_MINI AD-9`. Every other AD reference in the
Phase-2 files resolves correctly (AD-20…AD-27 all exist; Amendment A3, OI-2, OI-3, V-14 all
exist).

#### Notes

**The three questions the brief asked, answered.**

1. **Two `pluginName` switches in one class — safe, and the disjointness is real, not
   asserted.** It is structural, not conventional: `forGame` hands ziczac to
   `RikZicZacGameMessageTypes`, that class does not implement `GameRequestFactory`, so
   `requestFor` is *unreachable* for ziczac rather than merely unused — and
   `BettingMiniGameBot.buildRequest`'s `instanceof` is what enforces it, not a comment.
   Both switches read the same two `private static final` constants, use the same
   `CONSTANT.equalsIgnoreCase(game.getPluginName())` null-safe idiom, and
   `RikGameMessageTypesRoutingTest` holds the full matrix in one method
   (`requestRoutingMatrix`) plus an explicit `theTwoDispatchPointsAreDisjoint`. A 114 game
   that is neither — an un-captured offset 14000-18000 game, an unknown name, `""`, `null`
   name, `null` game — gets `new Request(...)`, i.e. today's behaviour bit for bit, and each
   of those rows is pinned. That is the allowlist AD-21 asks for, implemented as an
   allowlist. The residual exposure (a new 114 game silently inheriting a body nobody has
   seen it accept) is OI-2's, not this diff's, and the code says so at the point of
   dispatch.
2. **`@Getter(AccessLevel.NONE)` + `@JsonProperty("iAc")` — correct, and the code explains
   it well enough.** I re-derived the table independently against this module's Jackson
   (2.15.2 core / 2.20 annotations) rather than trusting the javadoc:

   | shape | emitted |
   |---|---|
   | annotated private field, no getter (**shipped**) | `{"iAc":true}` |
   | annotated field **plus** a *hand-written* `isIAc()` | `{"iac":true,"iAc":true}` |
   | annotated field **plus** a *Lombok-generated* `isIAc()` | `{"iAc":true}` — see correction |
   | getter only, no annotation | `{"iac":true}` |
   | `@JsonProperty` on the getter | `{"iAc":true}` |

   > **Correction (2026-09-17, post-review).** The "simplify it back" row above was measured
   > with a hand-written getter and does **not** generalise to the Lombok-generated one. The
   > repo root carries a tracked `lombok.config` with
   > `lombok.copyableAnnotations += com.fasterxml.jackson.annotation.JsonProperty`, so Lombok
   > stamps the annotation onto the getter it generates and Jackson merges the accessors into a
   > single correct `iAc`. Lombok cannot do that for a getter it did not generate, which is why
   > the hand-written row still doubles. This does not change the verdict or the finding — but
   > it changes *why* `@Getter(AccessLevel.NONE)` is right: not "otherwise the frame carries two
   > keys", rather "it is the only shape correct independent of a two-line file two directories
   > up". Found by QA, re-derived under both configurations by Dev; the plan's Amendment B1 and
   > `RikStockBet`'s javadoc were corrected the same way.

   So the javadoc's two load-bearing claims — that no getter-naming trick reaches `iAc`, and
   that leaving both in place emits *two* keys — are both true. More important, the
   protection it asserts is one the test actually provides:
   `RikStockBetTest.iAcIsNotMangled` asserts `doesNotContain("\"iac\"")`, which fails on the
   both-in-place shape and on the getter-only shape, and `keySetIsExact` fails on either.
   The defence is three-deep (class javadoc section, field javadoc, named regression test),
   the annotation is *subtractive* so a Lombok cleanup pass trips the test rather than
   silently winning, and the test's own comment names the failure. I do not think a careless
   reader can reintroduce this quietly.
   One supporting claim I checked because the test leans on it: *"the mapper the bot actually
   serializes outbound frames with: a bare one"* is accurate —
   `BettingMiniGameBot.botBehaviorScenario():924-926` builds `new ObjectMapper()`, sets only
   a **de**serialization feature, and calls `registerSubtypes` (no `@JsonTypeInfo` on any
   outbound body, so no type id is emitted); ws-parser's
   `ActionRequestMessage.serialize(ObjectMapper)` uses the `PipelineContext` mapper. No
   naming strategy or visibility config anywhere on the outbound path.
3. **`RikStockRequest` standalone — the duplication is small and `subscribe()`'s identity is
   guaranteed, not coincidental.** What is duplicated is one method body and three fields.
   `RikStockRequestTest.subscribeIsIdenticalToTheSharedRequest` is a **differential** test —
   it serializes both classes' frames and compares them, rather than pinning a literal — so a
   future change to `Request.subscribe()` that is not mirrored fails the build. And because
   `GameRequest` is a two-method interface, a *new* shared outbound frame would be a compile
   error in `RikStockRequest`, not a silent divergence. The omitted `chat` / `autoBet` are
   genuinely dead: `grep` over every `bot-*/src/main` finds **zero** call sites of either
   (`Request.chat` is called by nothing at all), so the javadoc's "neither has a production
   call site" holds. The AD-25 compile argument also checks out: `Request.bet` narrows its
   return to `Bet` and `RequestTest.overrideReturnsConcreteBet` pins that covariance.
   One nit not worth a finding: `RikStockRequest:44-45` says the narrowed return type is
   "documentation, not a contract anyone depends on" — `RikStockRequestTest` does depend on
   it at compile time (`RikStockBet bet = rik.bet(...)`). Harmless.

**Things I checked that came back clean.** No cast of the built `GameRequest` to `Request`
or `Bet` anywhere in the engine (`bet()` at `BettingMiniGameBot:810` returns it straight
into the scenario supplier), so a non-`Bet` body cannot surprise a downstream reader. No
new shared mutable state, no new executor, no new scheduled task, no thread hand-off: the
provider singleton stays stateless and `requestFor` allocates per bot. No new logging at all,
hence no token-shaped or per-bot INFO risk. `BotFactory:172` remains the only
`bettingMini(...)` call site. `bot-api` really does depend on no other module, so
`GameRequestFactory`'s "a `default` method returning `GameRequest` would not compile"
justification is true rather than plausible. Field-ordering aside (finding 1), the CMD
arithmetic is derived, not hardcoded: `offset + 3002` = 13002 at offset 10000, 7002 at 4000.

**One question for the author, not a finding.** The deploy is unverifiable from Grafana.
Nothing logs which body a group took — the only difference visible outside the box is a
TRACE frame dump — and there is no `bot_bets_placed_total == 0` rule in
`prometheus/alerts.yml`, so the exact failure class this phase exists to end (frame ships,
looks healthy, server ignores it, 156 rounds) still has no automated detector; V-14 stays a
manual read. A per-bot log line would be the wrong fix (the tier rules forbid it, and
`buildRequest` is per bot); the group-level place would be `GroupLifecycleAggregator`'s
existing per-start line, or a `BotMetrics` gauge tagged with the request class. Worth a
follow-up either way, not this diff's job.

**Provenance, for the record.** Phases 1, 1b and 2 are all uncommitted, on a branch named
for an unrelated feature, and Phase 1/1b are described as already deployed. That is the
exact condition under which the earlier "unchanged" claim went stale, and it is why this
review is scoped by mtime and pinned by sha1 above. Not a code finding — but a reviewer has
no stronger lever than those hashes, and a `git stash` or an editor autosave between now and
the deploy would be invisible.

---

### Appendix — Phase 1 review (2026-09-16), preserved verbatim

The file below is the earlier review of Phases 1/1b, kept because it is the only record of
those verdicts, with its headings demoted two levels so this file has one set of top-level
sections. **Its verdict line applies to Phase 1 only**; the Phase 2 verdict is the one
at the top of this document.

#### Code Review — RIK_114_BETTING_MINI

Branch: `feature/dead-group-auto-recovery` (the RIK work is uncommitted in the working tree)
Reviewed diff: `git status` + working-tree contents, restricted to the RIK scope —
`bot-messages/src/main/java/com/vingame/bot/domain/bot/message/g3/rik/` (7 classes),
`bot-messages/src/test/java/com/vingame/bot/domain/bot/message/g3/rik/` (3 tests),
`bot-messages/src/test/resources/{messages/rik,captures}/`,
the two inventory edits in `MessageTypesCoverageTest` / `MessageTypesRegistryTest`,
and `scripts/capture/`.
The unrelated pre-existing modifications (Aviator.js, bc.js, deploy.sh, `docs/plans/*`,
`docs/reviews/DEAD_GROUP_AUTO_RECOVERY/*`, `TaiXiuMessages/*`, `scripts/bulk-create-accounts.py`)
were not reviewed.

Build: `mvn -pl bot-api,bot-messages -am test` green under JDK 21.
`RikGameMessageTypesTest` 10, `RikTaiXiuMd5GameShapeTest` 5, `RikFixtureProvenanceTest` 3,
all passing; boot line reads
`BETTING_MINI 6 products [097, 098, 114, 116, 118, 119], TAI_XIU 3 products [114, 116, 119]`.

##### Verdict

PASS

No `bug` and no `security` finding. Everything below is advisory.

##### Findings

###### [smell] `RikSubscribeMessage`'s `m` javadoc asserts a meaning the evidence contradicts — and contradicts its own sibling class
`bot-messages/src/main/java/com/vingame/bot/domain/bot/message/g3/rik/RikSubscribeMessage.java:66-67`
(and, more weakly, `:63-65`)

```java
/** This bot's balance-shaped figure; {@code 0} at a clean subscribe. */
private long m;
```

`RikMainBetSummary`'s javadoc says the opposite about the same key: *"It equals `b` in 3/3
captured rounds, so it is **not** a balance; its meaning is unknown and it is modelled
without interpretation."* On Subscribe, `m` was `const 0` in the single frame that carries
it — i.e. there is no evidence for "balance-shaped" at all, only for "zero". The same
applies one line up to `wm` ("this bot's win on the round in progress"), which is an
extrapolation from a single `0` onto a field whose EndGame twin *is* known.

This matters more than a normal doc nit on this feature specifically: Phase 1b existed
solely to delete a confidently-worded rationale that turned out to be false, and these two
lines are the same habit in a quieter register — a reader wiring a future "balance drift"
check would take the sentence at face value. Fix shape: use `RikMainBetSummary.m`'s wording
— state the observation (`const 0` in the only capture that carries it), not an
interpretation — and keep the two classes saying the same thing about `m`.

###### [smell] Five Subscribe fields are modelled with no javadoc, nothing reads them, and each is a small liveness liability
`RikSubscribeMessage.java:69-73` (`tTU`, `tSv`, `tLv`, `tLp`, `tSp`); same shape at
`RikEndGameMessage.java:117-119` (`iJp`, `tJpV`, `tJpv2`)

AD-6's own argument is *"the engine reads none of them, and not creating the field is what
disposes of two hazards for free"* — applied to `cH`/`htr`/`bH`, but not to these. They are
`const 0` in the only capture that carries them, absent entirely from the `taixiuMd5Plugin`
subscribe, undocumented, and read by nothing. Their types are therefore pure guesses, and a
guess costs something: a modelled field whose real type is a fraction or a string makes the
**whole Subscribe frame fail to deserialize**, which means `onSubscribe` never runs, the bot
never reaches `markConnectionAuthenticated()`, and the group dies with no obvious cause. An
unmodelled field cannot do that. Same reasoning for the jackpot trio, which AD-19 explicitly
does not wire.

Fix shape: drop them, or box them (`Long`/`Boolean`) so a surprise is survivable, or at
minimum say what they are. One decoding is worth recording while it is cheap — on a stock
up/down game `tLv`/`tSv` and `tLp`/`tSp` read as **L**ong-side / **S**hort-side volume and
percent, which the `13007` broadcast supports (`tLv` walks 2000 → 3000 → 4000, tracking the
stake, while `tSv` stays 0). If that is right they are money, and `long` is the right type
for a reason rather than by luck.

###### [smell] `crowdBets()` falls back on null only, while the payout accessors check null-or-empty
`RikEndGameMessage.java:169`

```java
List<RikBetInfo> entries = obs != null ? obs : bs;
```

versus `winningsFor` / `betAmountFor` / `betCountFor`, which all use
`mbs != null && !mbs.isEmpty()`. A frame carrying `"obs":[]` together with a populated `bs`
therefore reports an empty crowd — the exact case the AD-4 fallback exists to cover, missed
because the server sent an empty array instead of omitting the key. `ps` on the stock EndGame
proves this server does emit empty arrays rather than omitting them. One character of
asymmetry for no reason: `obs != null && !obs.isEmpty() ? obs : bs`.

###### [smell] `RikFixtureProvenanceTest` promises "every `/messages/rik/*.json`" and enforces a hand-maintained list
`bot-messages/src/test/java/com/vingame/bot/domain/bot/message/g3/rik/RikFixtureProvenanceTest.java:48-52`
(claim in the class javadoc, `:18-30`)

`STOCK_FIXTURES` / `TXMD5_FIXTURES` are two literal lists. A ninth fixture dropped into the
directory and used by a new test is bound to nothing — which is precisely the failure this
test was written to make impossible ("a fixture hand-edited to make an assertion pass …
fails here"), and the javadoc will keep reading as if it were covered. The per-fixture
capture mapping is the right call and should stay; what should change is the enumeration.
Fix shape: list the classpath directory and assert over what is actually there, keeping the
`txmd5-` prefix as the mapping rule and failing loudly on a fixture that matches neither
capture.

###### [smell] The committed captures carry ~32 real staging player display names and ~100 lines of their chat
`bot-messages/src/test/resources/captures/rik-stockPlugin-13000.jsonl`,
`.../rik-taixiuMd5Plugin-7000.jsonl`, and `messages/rik/subscribe.json` /
`txmd5-subscribe.json` (the verbatim 50-entry `cH`)

**Deliberately not tagged `security`:** I checked for credential material and there is none.
The capture window is the game's cmd block and the AUTH frame carries no `cmd`, so no
`accessToken` / `session_id` / agency token could ever have matched; the only identifiers
present are self-chosen in-room nicknames (`fu`, `uN`, `dn`), one server uid (`15_6447`) and
public room chat. That is third-party user-generated content from a live staging room
(`Ăn shit rồi`, `Đỏ hơn đít khỉ`, …) entering git permanently, which is worth naming once
even though nothing here is a secret.

If it is judged worth removing, the cheap route preserves every claim built on these files:
substitute same-shaped placeholder strings in **both** the capture and the fixture. The
provenance test compares fixture to capture (node equality), so a consistent substitution
stays green, and AD-6's tolerance claim depends only on the *shape* of `cH`/`bH`, not on the
text. If it is judged not worth removing, say so in the plan — the decision should be on
record either way, because it is irreversible once pushed.

###### [smell] `infer_schema.py` recommends `int` for money that is 82% of the way to overflow
`scripts/capture/infer_schema.py:158`

```python
is_long = node.num_max_abs > INT_MAX
```

Only a value that has **already** exceeded int range earns the `LONG` note. Run against the
committed stock capture, the tool reports `mB` (500 000 000) as `int`/`int` and would report
`13007.ps[].m` (1 759 134 614 — 82% of `Integer.MAX_VALUE`) as `int` too. Step 2's stated job
is to stop money being typed `int` (`README.md:244`, *"an `int` here silently truncates
money"*), and AD-3 exists because this very environment has carried `tJpV = 1 846 444 000`
through this app. The author overrode the suggestion correctly by hand everywhere; the next
reader working from the report may not. Fix shape: add a `NEAR INT RANGE` note and suggest
`long` above a headroom threshold (e.g. `num_max_abs > 2**28`), so a money-shaped field has
to be argued *down* to `int` rather than silently left there.

###### [smell] `__wscap.stop()` does not stop the `JSON.parse` hook, and nothing is ever un-patched
`scripts/capture/ws-capture.js:377-391`, `:161-162`, `:231`, `:393-398`

The `S.stopped` guard lives in `ingest()` (`:162`), but `hookJsonParse`'s wrapper calls
`accept()` directly (`:384`), bypassing it — so after `stop()` the page's every `JSON.parse`
is still being recorded into `S.records`. There is also no uninstall path at all:
`installed.set(win, {origSend, origWS})` (`:231`) stores exactly what a restore would need
and nothing ever reads it, so `WebSocket.prototype.send` and `win.WebSocket` stay patched for
the life of the tab. Low stakes for a console tool on a staging page, but `stop()` reads as
"it stopped", and a half-stopped capture is the kind of thing that later gets blamed on the
game. Fix shape: gate `accept()` on `S.stopped` inside the `JSON.parse` wrapper, and have
`stop()` restore `origSend` / `origWS` / `JSON.parse`.

###### [smell] The MutationObserver re-walks every realm on every DOM mutation
`scripts/capture/ws-capture.js:263-267`

The 3 s interval rescan is sensible. The observer next to it fires `rescan()` on
`{childList: true, subtree: true}` over `document.documentElement` — on a live game UI that
is a depth-4 recursive frame walk, with `try/catch` per frame and a rebuilt `frameReport`,
potentially many times a second. A capture tool that makes the page it is measuring stutter
can distort the very round timings the capture is used to assert (the plan reads cadence off
frame timestamps). Debounce it to ~1 s, or drop it and keep the interval.

###### [smell] The route that actually produced the committed evidence is an unpinned tool in another repo
`scripts/capture/README.md:7`, `:12-18`, `:26-29`, `:170-171`

`_meta.source` on both committed captures is `"ws-inspector extension"`, and the README says
the console script *cannot* work on these Cocos clients ("it cannot work; the bundle already
holds its own `WebSocket` reference"). So the committed `ws-capture.js` is not the tool that
produced the committed evidence, and the tool that did lives at
`../WebSocket-Parser-Chrome-Extension/ws-inspector` with no version, commit or manifest
pinned anywhere. That matters because OI-2 makes "capture first" a **mandatory** gate before
any other 114 mini game is enabled, i.e. a required step depends on an out-of-repo artifact
whose behaviour is undocumented here (its `record(lo, hi)` window is thousand-wide, while
`ws-capture.js` derives a hundred-block window — a difference the README explains for the
script but not for the extension). One line naming the extension's repo URL and the
commit/version used for these two captures closes it.

###### [style] The two test classes read fixtures with different charsets
`RikGameMessageTypesTest.java:60` (`new String(in.readAllBytes())`) vs
`RikTaiXiuMd5GameShapeTest.java:63` (`new String(..., StandardCharsets.UTF_8)`)

`subscribe.json` is the one fixture containing raw non-ASCII bytes, and it is read by the
class that omits the charset. Java 18+ defaults to UTF-8 (JEP 400) so this is correct today,
and the project sets no `project.build.sourceEncoding` to make it so; a `-Dfile.encoding`
override would silently change what one of the two classes parses while the other is
unaffected. Use `StandardCharsets.UTF_8` in both.

##### Notes

**Everything the brief asked me to check specifically, checks out.**

- *No residue of the wrong Phase 1 rationale anywhere.* `MaxBet`, "Do NOT wire", "room
  maximum", "metric-forbidden" and `mW`-as-evidence return nothing across `bot-messages/src`
  and `scripts/`. The rename is complete: `RikMainBetSummary` in main, in both references
  from `RikEndGameMessage`, in the tests and in the test *method* name
  (`endGameExposesOwnWinningsAndStake`), and the comment on that method carries the corrected
  reasoning (backend constants) rather than a struck-through version of the old one.
- *Money typing.* Every monetary field is `long` — `RikBetInfo.b/v`, `RikMainBetSummary.b/wm/m`,
  `RikEndGameMessage.wm/tJpV/tJpv2`, `RikSubscribeMessage.mB/wm/m/tFB/tFD/tFP/rmT` and the five
  undocumented ones, plus `sid` everywhere. `int` is confined to `eid`, `bc`, `gS`, `d1..d3`,
  exactly as AD-3 states. The `tJpV` / `tJpv2` casing trap (capital `V`, lowercase `v`) is
  correct in both the field names and the `@JsonProperty` values.
- *`wm` as a gross return.* Nothing nets the stake anywhere: `winningsFor` returns the value
  verbatim, the name is "gross return including stake" in both javadocs, and the two tests
  assert the raw server numbers (4704 = 3000 × 1.60 × 0.98 on a *net-losing* game's winning
  round; 198 000 = 100 000 × 1.98). `betAmountFor` and `winningsFor` are independent paths, so
  there is no place a subtraction could hide.
- *`obs` never feeds own-stake.* `obs` is read by `crowdBets()` alone. `betAmountFor` reads
  `mbs[].b` then `bs[].b` and has no `obs` branch at all, and both the method javadoc and the
  no-bet test say why. The empty-`bs[].b` absence case (`txmd5-endGame-noBet.json`) is pinned
  to `0` on all three accessors.
- *Provider honesty about claiming all of 114.* The "What the captures do NOT prove" section
  is explicit, unhedged and names the un-captured offsets (14000–18000), the reason a second
  provider is impossible, and the widening path. The failure-mode paragraph
  ("degraded observability, not a crash or a wrong bet") is supported by the code: every
  modelled field is optional, `crowdBets()` returns `List.of()` rather than throwing, and the
  one fatal accessor (`getSessionId`) reads a field present in both captures.
- *Phase 2's absence is documented where a reader lands* — "The outbound bet frame is an open
  question" in `RikGameMessageTypes`' javadoc, naming the divergence, V-6 and the "looks
  healthy, stakes nothing" failure shape. The one gap is placement, not content: nothing
  outside `docs/plans/` and that javadoc warns the **operator** who creates a 114 game, and
  `CLAUDE.md` gained no 114 betting-mini entry. That is a doc-routing call for the compliance
  / release pass, not a code defect.

**One thing worth adding to V-9 on staging.** The `mbs`-is-mine claim is the load-bearing
assumption of the whole payout wiring, and it is not falsifiable by anything committed here —
both captures had exactly one bettor, so "mine" and "the room's" are numerically identical in
every sample. Note that the plan's own V-9 anchor cannot detect the error either: if `mbs`
were room-wide, `bot_winnings_total` and `bot_bet_amount_total` would *both* scale by the
room, and the ratio would still land at ~0.98. The cheap discriminator is a **multi-bot**
group on one 114 game: per-bot `bot_bet_amount_total` should differ between bots and sum to
the room's stake. Identical values across N bots, or a per-bot figure ≈ N × its own sends, is
the room-wide reading and means `HasBetTotals` must come back out.

**Good patterns worth calling out.** `RikFixtureProvenanceTest` (whatever the enumeration
nit) is the strongest thing in this diff — it converts Win79's "these fixtures are real
frames" javadoc claim into a build-enforced property, and the per-capture mapping plus the
`doesNotContain(7002)` pin are both the stricter choice where a weaker one was available.
The two synthetic-JSON tests are labelled `SYNTHETIC` in-body with the reason, so the
"fixtures are real frames only" rule survives contact with the cases the wire never produced.
And `RikEndGameMessage`'s `winningsFor` javadoc arguing *precedence, never a sum* — with the
double-count consequence spelled out — is the right level of detail for a method whose bug
would be silent.
