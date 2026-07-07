# Betting Intelligence Roadmap

Parent roadmap. Each work package below is planned separately (its own
`docs/plans/<FEATURE>.md`) — this document only records **what** we're building
and **why**, not where in the code it lands.

## Origin

Assessed a reference Tai Xiu bot module (`vingame/log` @ `dev/taixiu-bot`,
head `21f24e26`, 2025-05-22, path `taixiu-bot/`) — a centralized, house-side
market-making engine. We are porting a **small set of ideas from it, cleanly
reimplemented and generalized**, not the module itself.

Guiding decisions from the design discussion:

- **Generalize, don't specialize.** Nothing here is Tai Xiu-specific. It lives
  at the betting-mini / group level and generalizes over N betting options
  (Tai Xiu big/small is just N=2; BauCua is N=6). The reference's binary
  big/small imbalance is the special case of "steer toward a target
  distribution over N options."
- **Live, not pre-computed.** The reference plans a whole round up front from a
  single mid-round snapshot (open-loop, goes stale). We do live, closed-loop
  reaction instead. Pre-round batch allocation is explicitly *not* ported.
- **Coordinate by gating, not by central allocation.** Bots keep proposing bets
  autonomously via their existing strategies. A coordinator holds the aggregate
  source of truth and **approves / rejects / trims** proposals. This preserves
  the autonomous-bot model. Consequence: bots must *over-propose* so the gate
  has steering headroom — it steers by suppression, it never manufactures bets.
- **Reuse existing lifecycle.** New scheduling sits on top of the current
  target/actual dual-status machinery by flipping `targetStatus`; it does not
  re-implement bot lifecycle.

## Explicitly out of scope

- Pre-round batch bet planning / central allocator.
- Gift-code / promo chat spam.
- Anything Tai Xiu-only.
- Copy-pasting reference code. The value is the *algorithm + tuning intent*, not
  the (2020-era, untested) source.

## The Fleet abstraction (centerpiece)

Time-of-day behavior is the real driver. Rejected: per-time-bracket config
fields on a group (UI bloat, ~10 fields × N brackets). Chosen: compose multiple
**bot groups** — each with one behavior config and its own username pool — into
a **Fleet**.

- A **Fleet** is a thin container of bot groups **plus an activation predicate**
  ("what condition must hold for this fleet to be active").
- **Behavior lives 100% on the group; time lives 100% on the fleet condition.**
  No inheritance/override between the two levels.
- Fleets are independent activators — several can be active at once, so
  realistic traffic "swell" is emergent (no additive-vs-exclusive special case).
- A group belongs to exactly one fleet (or none). 1:N ownership, matching the
  distinct-username-pool intent.
- The 24h intensity curve is expressed as fleets with time-window conditions
  (e.g. night-low / day-med / peak), each holding groups of the matching
  intensity.

**Activation model:**

- The condition is a *sustained predicate* (active *while* it holds), evaluated
  on a tick — not a cron fire-at trigger.
- Implemented as a **reconciler**: each tick, `active = evaluate(condition, now)`
  sets each member group's `targetStatus`; the existing runtime reconciles
  actual → target. Restart-safe by construction.
- A fleet `mode = SCHEDULED | MANUAL_ON | MANUAL_OFF`; manual suppresses
  reconciliation so a manual stop isn't undone by the loop.
- Condition expressiveness ceiling: typed conditions
  (`ALWAYS`, `TIME_OF_DAY`, `DAYS_OF_WEEK`, `DATE_RANGE`) combinable with a
  single `AND` (e.g. "weekends AND 18:00–24:00"). No full AND/OR rule engine
  until something demands it. Calendar oddities (e.g. Lunar New Year date drift)
  are handled by manual date entry, not lunar computation.

The Fleet is also the natural **scope for aggregate coordination** later — the
whole logical footprint hitting a game at once — which is a second reason to
build it first.

## Feature set & dependency map

Three and a half of the four features are feed-free and buildable now. Only the
crowd-aware half of coordination depends on a game-server feed we do not
currently ingest (per-side aggregate volume/user counts).

| Feature | Needs crowd feed? | Notes |
|---|---|---|
| Precise time control (via Fleet) | No | The Fleet work above. |
| Bet ramp-up (intra-round timing shape) | No | Bets ramp over the window vs a flat tick. |
| Jackpot-based scale | No | Jackpot value already arrives at round end. |
| Coordination — internal tier | No | Gate on the fleet's *own* aggregate state. |
| Coordination — crowd-aware tier | **Yes** | Gated on the feed spike below. |

## Candidate work packages (each planned separately)

1. **Fleet entity + activation reconciler.** The container, the predicate model,
   the tick-reconciler over group `targetStatus`, manual-override mode, and UI.
   Foundation — delivers precise time control. Do first.
2. **Bet ramp-up.** Intra-round temporal shaping of bet timing. Feed-free,
   independent.
3. **Jackpot-based scale.** Scale next round's bot volume by jackpot balance.
   Feed-free, independent.
4. **Coordination gate — internal tier.** Fleet-scoped source of truth that
   approves / rejects / trims bot-proposed bets against aggregate targets over N
   options. Requires bots to over-propose. Feed-free.
5. **Feed feasibility spike.** Does the game server broadcast per-side aggregate
   volume/user counts to subscribers, and can we ingest them? Yes/no gates #6.
6. **Coordination gate — crowd-aware tier.** Extend #4 to steer relative to real
   players' imbalance. Gated on #5.

Suggested order: 1 → (2, 3, 4 in parallel) → 5 → 6. #5 can run any time; run it
early so #6 is unblocked when #4 lands.
</content>
</invoke>
