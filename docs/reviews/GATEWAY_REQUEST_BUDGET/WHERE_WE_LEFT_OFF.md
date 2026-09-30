# Where we left off — GATEWAY_REQUEST_BUDGET, 2026-09-29

Branch `feature/gateway-request-budget` (off the still-unmerged
`feature/dead-group-auto-recovery`). **Nothing pushed, nothing deployed.** Plan:
`docs/plans/GATEWAY_REQUEST_BUDGET.md` + amendments **A1-A29** at the bottom (the
amendments win over the original text).

## State

| Phase | State |
|---|---|
| 1 — facade in observe mode, every call site tiered, metrics | **done, signed off** (QA / reviewer / compliance PASS) |
| 2 — async `/start` `/restart`, `STARTING`, startup daisy-chain | **done, signed off** after one fix round (two blockers found and fixed) |
| 3 — enforcement behind `mode=enforce` | **committed**; compliance PASS, QA PASS, reviewer CHANGES_REQUESTED; **fix round unfinished** |
| 4 — async registration | not started |
| 5 — Cloudflare detection + circuit trigger | not started |
| 6 — compiled-default flip | not started |

Branch tip **`7326eb7`** is the last verified-green state: 2,356 tests, 0 failures,
checked in a detached worktree (never the working tree).

## The unfinished bit — read this first

The Phase 3 fix round is **uncommitted in the working tree** (~20 modified gateway
files + new `BotSessionBudgetWaitTest`), mixed in with ~58 unrelated dirty
RIK/Aviator entries that are **not ours** — never `git add -A`/`-a`/`stash`.

It compiles (`mvn install -DskipTests`) but **4 tests error**: the agent added a
`maxWait` parameter to `ApiGatewayClient.setDisplayName`, and
`ApiGatewayClientSetDisplayNameWithRetryTest`'s fixture does not match the new call
path, so calls fall into the `IOException | InterruptedException` catch. F1's fix
looks present; F2-F5 are partial (the agent's last words: "now tests for F2 and
F3/F4/F5, then the full run"). Do not guess the intent at that seam — re-read
`review-phase3.md` and finish it deliberately.

Findings the fix round was addressing (all in `review-phase3.md` / `qa-phase3.md`):
1. **F1, the important one** — `onNewSession` runs on ws-parser's message-processor
   thread and `BettingMiniGameBot:647` arms the watchdog on the line *before* it, so
   under `enforce` a 10 m `prioritized.max-wait` beats the 180 s watchdog and converts
   a **healthy** bot into a reconnect: the storm shape that can *cause* a Cloudflare
   block. Invariant to pin: any wait on that thread < `bot.watchdog.timeout.seconds`.
2. **F2** — registration reports our own pacing as an upstream **502** and fans out
   ERROR per user. Phase 4 deletes the method but *inherits* the classification.
3. `runAfterUnlock` has no per-action isolation (one throw strands stamped ESSENTIAL
   waiters); `admitWaitersLocked` can throw before re-arming the wake-up.
4. `Duration.ZERO` means "forever" from settings and "never" from a caller.
5. **`essential.ceiling=850` lowers the cap instead of reserving headroom** — the
   hard-cap check in `hasRoomLocked` is unreachable. A5.4 documents it as an escape
   hatch, so fix it or correct the docs.
6. Record corrections: the "84 arrivals in a 60-request window" was not evidence of
   the stale-clock defect (the IT's own bound is `60 + 24`); the in-flight caveat
   cites the two `parallelism` semaphores, which do not bound the first-read burst;
   the circuit 503 body does not carry the "may outlive a day" caveat its javadoc
   claims; `GatewayBudgetSustainedQueue`'s annotation contradicts its own arithmetic.
7. QA's flake warning: the escalation IT's `observedMax <= 84` has zero headroom
   against a measured 79-84 and is machine-dependent.

## Decisions already made (do not re-litigate)

Tiers by call-site intent; budget keyed **per `Environment`** (WARN when two share a
gateway host); hard cap **900**; `essential.ceiling` = cap; ceilings in config, no
magic numbers; **200 + DTO** for accept-then-poll on start/restart/registration;
`STARTING` appended to the enum and **never persisted**; registration fully async
(the 429 stopgap is deleted); no account-factory groups; **no test may ever touch a
real gateway** — a block has no tolerable cooldown (~24 h or manual).

## Next session, in order

1. **Trim `CLAUDE.md` aggressively** and write a one-page branch digest — every agent
   re-reads the long file plus 29 amendments, which is where the ~6M tokens for three
   phases went. Compliance once before release, not per phase; re-check only what
   failed.
2. Finish the Phase 3 fix round (above), then review it.
3. Phase 4 — async registration. Its inheritance list is **A28** (ordered), and the
   gateway envelope it depends on is captured in `gwms-register-envelope.md`:
   `status=EXISTED`, `code=409`, at **HTTP 200**, and a re-register returns **no
   tokens**, so a resumed index costs 3 DEFAULT requests.
4. Phase 5's obligations are **A29** — including that `EnvironmentWsProbe` reads a
   Cloudflare block page as healthy, and that Phase 3 shipped three open-circuit
   branches nothing can execute until the trigger exists.

## Open with the user

- Whether to commit the unfinished fix round as a labelled WIP or finish it first.
- The RIK `EXISTED` + WARN→DEBUG hunk still rides in this branch (test committed in
  `d947c09`) — decide before Phase 4 touches display-name handling.
- Two questions for SA are answered (same hosts; no tolerable cooldown). Nothing else
  is blocked on anyone.
