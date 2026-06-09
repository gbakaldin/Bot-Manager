# Round Data Collection Findings — Tip vs Bom/B52/Nohu

**Date:** 2026-06-09
**Status:** Findings only. No implementation. Architect to investigate before wiring.

## TL;DR

Tip's protocol is **substantially richer** than Bom/B52/Nohu. Where Bom/B52/Nohu's `EndGameMessage` carries only per-position aggregates and recipient-personalized jackpot fields, Tip's carries explicit per-user winnings + a full player roster + per-position breakdowns of "this user's bet" vs "total of all users' bets." Tip can fully implement `HasBotWinnings`, `HasBetTotals`, and `HasJackpot`, and could even resurrect the deliberately-dropped `HasRoundTotals` / real-player share feature *for Tip only*.

This document captures what was learned while transforming `TipSubscribeMessage` / `TipStartGameMessage` / `TipEndGameMessage` from raw JSON to POJOs. The capability differences are large enough that the marker-interface dispatch design from `docs/plans/ENDGAME_METRICS.md` should be revisited before per-game implementations land.

## Source

- Raw JSON pasted by the user in the body comments of `src/main/java/com/vingame/bot/domain/bot/message/g3/tip/Tip*Message.java` (now replaced by the deserialized POJOs).
- User stated verbally:
  > `wm` in end game (root) is win money of the user, that's how we can calculate it. And `bs` is array of bets per option, `b` = total user bet, `v` = total bet of all users on that option. We can at least infer bot share % from total user bet.

## What Tip's `EndGameMessage` carries (`TipEndGameMessage`)

| Field | Type | Meaning (confirmed / inferred) |
|---|---|---|
| `wm` (root) | `long` | **This bot's gross winnings** for the round. CONFIRMED by user. |
| `iJp` (root) | `boolean` | This bot won jackpot. (Same semantics as Bom/B52/Nohu `iJp`.) |
| `jpV` (root) | `long` | Jackpot value awarded this round. **Distinct from `tJpV`**. Open question: when `iJp=true`, is the jackpot payout `jpV` or `tJpV`? Sample shows `tJpV=200000`, `jpV=1603000`, `iJp=true`. Need a second sample to disambiguate. |
| `tJpV` (root) | `long` | Possibly "total jackpot value across all winners" or "jackpot pool size carried forward." Needs verification. |
| `bs[].b` | `long` | **This user's bet on this option.** CONFIRMED. |
| `bs[].v` | `long` | **Total of all users' bets on this option.** CONFIRMED. |
| `bs[].bc` | `int` | Likely bet count (on this option, for this user — symmetric with `b`). |
| `bs[].eid` | `int` | Option / position id. |
| `ps[]` | `List<PlayerSummary>` | Roster of all players in the round, each with `{uid, wm, m}`. |
| `ps[].wm` | `long` | That player's winnings this round. |
| `ps[].m` | `long` | Unknown — likely balance (money after round?) or stake. Needs confirmation. |
| `iJ` (root) | `boolean` | Unknown semantics. Distinct from `iJp`. Possibly "is jackpot round" (game-level) vs `iJp` ("this user won jackpot")? Open. |
| `eIn.iBp` | `boolean` | Unknown. Open. |
| `d1/d2/d3`, `sDi` | int / object | Round outcome (dice). Same shape as Bom. |

## What Bom/B52/Nohu's `EndGameMessage` carries

| Field | Per-user? | Notes |
|---|---|---|
| `iJp` + `tJpV` | recipient-personalized | Already wired via `HasJackpot` impls landed 2026-06-09 (commit `85f1744`). |
| `bs[]` | **NO** — per-position aggregate across all players | Cannot derive per-user winnings or per-user bet count from this. |
| (no `wm` field, no `ps[]`) | — | No way to read this bot's payout from the EndGame payload. |

This is why `docs/plans/ENDGAME_METRICS.md` left `HasBotWinnings` and `HasBetTotals` unimplemented on Bom/B52/Nohu and why Phase B (move bet counters from `Bot.creditBalance` to `HasBetTotals` dispatch) is currently a no-ship: there's no implementer.

## Per-interface implementability — Tip

| Interface | Implementable on `TipEndGameMessage`? | Body |
|---|---|---|
| `HasJackpot` | ✅ | `iJp ? jpV : 0L` (pending jpV vs tJpV disambiguation) |
| `HasBotWinnings` | ✅ | root `wm` — single-field read |
| `HasBetTotals` | ✅ | `betAmountFor(_) = sum(bs[].b)`; `betCountFor(_) = sum(bs[].bc)` |
| (dropped) `HasRoundTotals` | ✅ if revived | `totalBetAmount = sum(bs[].v)`; `totalWinnings = sum(ps[].wm)` |

All four can ignore the `userName` argument: the message is delivered personalized to its recipient (the root `wm` is this bot's payout, `bs[].b` is this bot's bet). The roster `ps[]` is the only structurally cross-user array on the payload, and it's only needed for the dropped `HasRoundTotals` feature.

## Implications for `docs/plans/ENDGAME_METRICS.md`

1. **Phase B may be viable after all — for Tip.** The plan's "Phase B is destructive because no game implements `HasBetTotals`" assumption was based on Bom/B52/Nohu. If Tip ships a `HasBetTotals` impl before Phase B lands, Tip-game bots get server-authoritative bet counters and Bom/B52/Nohu-game bots get zeroed counters. Mixed-state metric until Bom/B52/Nohu either ship their own impl or accept the gap. Architect should decide whether to (a) ship Phase B once Tip implements, (b) hold Phase B until all four ship, or (c) restructure to a per-game opt-in.

2. **The `HasRoundTotals` deletion (Phase A.5) was made under the Bom/B52/Nohu assumption that bot-side inference would require tracking every other player's per-position bets at 2 Hz.** Tip changes that calculus — `ps[]` is broadcast on EndGame, so the data is just there. If the demo audience or stakeholders ever want real-player share metrics for Tip specifically, the interface and dispatch can be reintroduced for Tip alone (Bom/B52/Nohu remain incapable). Doc this as "available cheaply if needed" rather than a roadmap commitment.

3. **The marker-interface design holds up well.** The Bom/B52/Nohu poverty isn't a design flaw in the interfaces — it's a data-availability constraint on those specific protocols. Tip will exercise all three surviving interfaces end-to-end.

## Open questions for architect investigation

1. **`jpV` vs `tJpV` semantics on `TipEndGameMessage`.** Sample shows both non-zero with `iJp=true`. Likely `jpV` = "amount paid to this user" and `tJpV` = "remaining jackpot pool" or "total jackpot tier." Need a second sample with `iJp=false` to test the hypothesis.
2. **`iJ` (root, boolean) meaning.** Not `iJp`. Possibly game-level "is jackpot round" or "is jackpot enabled."
3. **`ps[].m` meaning.** Likely user balance after settlement, but could be bet stake or carryover. Affects whether `ps[].m` is useful for any future metric.
4. **`eIn.iBp` meaning.** Single boolean on a nested object — typically signals a settlement edge case. Worth a 5-minute log dive.
5. **Whether Tip ever emits `EndGame` to non-betting subscribers** (spectators, late joiners). Affects whether `wm == 0` is "I bet and lost" vs "I wasn't betting this round." Matters for RTP calculations.
6. **`TipStartGameMd5Message` and `TipGameMessageTypes` are still stub files** (`public class X {}`). The user provided no Md5 fixture; this doc does not extrapolate. Architect should decide whether to mirror `BomStartGameMd5Message`'s shape (`sid + md5`) or wait for a real sample.

## Recommended next steps

1. Architect reads this doc and the three `Tip*Message.java` POJOs.
2. Architect disambiguates `jpV` vs `tJpV` (one fixture suffices) and clarifies the open questions above.
3. Architect amends `docs/plans/ENDGAME_METRICS.md` to:
   - Add Tip to the implementer list for `HasJackpot`, `HasBotWinnings`, `HasBetTotals`.
   - Reassess Phase B's "no implementer → destructive" framing in light of Tip's coverage.
   - Optionally re-document the dropped `HasRoundTotals` as "Tip-only revival path, deferred" if there's stakeholder interest.
4. Implementation is then a small Dev pass: ~3 interface implementations on `TipEndGameMessage` + tests.

Do not start implementation until step 2 (disambiguation) is done. Wiring `HasJackpot` on Tip with the wrong field (`jpV` vs `tJpV`) would publish bad data into the demo dashboard.
