# Session state — RIK 114, updated 2026-09-18

Handoff note so this can be picked up after a reboot. Written because the local
machine hung mid-session; **nothing here is at risk from the reboot itself** — the
work is on disk and the staging box is independent.

## Resuming

```bash
cd /Users/gleb/IdeaProjects/Bot
claude --resume 7cee6cf9-b0c8-4794-bfab-73e6c2c8e8d9
# or `claude --continue` for the most recent session in this directory
```

Transcript: `~/.claude/projects/-Users-gleb-IdeaProjects-Bot/7cee6cf9-b0c8-4794-bfab-73e6c2c8e8d9.jsonl`

Note the session-output directory under `/private/tmp/claude-501/...` uses a
*different* id (`ccf7e1e8-…`) and is **not** the resume key. That tmp directory
(classpath dumps, serialization probes) is disposable and may be wiped on reboot;
nothing depends on it.

## Where things stand (end of 2026-09-18)

**Stock (Coins) is SOLVED and settling.** RTP 0.976 over 5,600+ confirmed positions, matching the
payout model. Phase 2 (`v`+`iAc`) was the complete client-side fix; the server-side restart the
user did for ziczac at ~14:00Z Sep 17 also un-wedged stock. **Phase 3 (`13022`) and 3b (`13012`)
are WITHDRAWN** — see `RIK_114_BETTING_MINI.md` Amendment A6. Phase 3 code is in the tree,
reviewed, **NOT deployed, do not deploy**. Deployed image is still Phase 2 (`c990784836bc`).
**Superseded 2026-10-01:** Phase 3 (`13022` = "commit to position", user-stated) was in fact deployed
with the 2026-09-18 ZicZac image, settles, and is KEPT — committed in `2a7d803`. See plan A6 correction.

**ZicZac is the next session's work.** The game is alive and feeding since the restart; our bets
are discarded because the body is wrong (`eid`, no `c`). **`RIK_114_ZICZAC.md` Amendment A1 is
the runbook**: what is known, the shrunk Phase 2 (`ZicZacBet` + `ZicZacRequest` +
`RikZicZacGameMessageTypes implements GameRequestFactory`, ~3 classes on the already-shipped
seam), and V-Z1…V-Z5. Start there: `architect` is optional (the amendment is the plan);
`dev` can take Amendment A1 directly, then the trio, then deploy on the user's go.

### Standing lessons (both plans now record them)
- **A game emitting rounds is not a healthy game.** Check the subscribe ack's `rmT` (negative or
  absurd = wedged server-side) before touching the client. Both RIK games were wedged the same
  day and a restart fixed both; a day was spent on frame hypotheses that were coincidences.
- **`<lower><UPPER>` keys (`iAc`, `sId`) need `@JsonProperty` + `@Getter(NONE)`.** Ziczac's body
  is all lowercase — not affected.
- **A logger raised individually must be cleared with `configuredLevel: null`, never set back to a
  level.** Cleared on Bot-1 14:37Z Sep 17; the TRACE recipe works again.

## Open items / decisions pending

- **Ziczac Phase 2** — next session (above).
- **Scale the coins group?** It is 2 bots pinned at 1,000/bet (diagnostic canary). Frontend shows
  exactly that. Options: PATCH to a legal 2-value grid (`5000/10000/5000` or
  `50000/100000/50000`) and raise `botCount`; or leave the canary and create a sized group beside
  it. User has not decided.
- **`EXISTED` display-name retry** — one-liner in `ApiGatewayClient.setDisplayName`; ~50% of new
  bots nameless until fixed. Not started.
- **Commit strategy** — whole RIK feature uncommitted, mixed on `feature/dead-group-auto-recovery`;
  stage selectively. Includes the withdrawn Phase 3 code — decide keep vs revert.
- **TaiXiu MD5 confirms only ~35% of what it sends** — unexplained; possibly its own denomination
  ladder.
- **Game team:** both RIK staging games wedged ~Sep 16; stock while still emitting rounds. They
  should know.

## IDs

```
env    114 staging   394301f4-6daf-4c55-a073-502a81c00731   (brand G3, product P_114)
game   Coins/stock   8a4e3c49-b8eb-494c-89f2-27c05cc5e586
game   TaiXiuMd5     37c23f9f-8260-471b-b4a7-0a415f466087
game   ZicZac        62901e13-7a76-49b5-a3a9-c43fcc95fc26
group  Coins Probe   cd77131c-7087-4eff-9a62-b50e3e674c91   (2 bots, ACTIVE, SETTLING, pinned 1000/bet)
group  TaiXiuMd5     1134449f-f040-4e95-a1e5-a2a618195cc1   (2 bots, ACTIVE, settling ~1:1)
group  ZicZac Probe  88d46075-dc8e-476c-b80d-1d0544b29c5c   (2 bots, ACTIVE, feed alive, 0 confirmed — wrong body)
legacy bots          ssh Staging-098  /home/sgame/bot/   (dev-rik-*.js = RIK staging, prd-rik-*.js = prod)
```

Reports: `docs/reviews/RIK_114_BETTING_MINI/{release,review,qa,compliance,display-name-check}.md`.
Captures (source of truth for every field decision): `bot-messages/src/test/resources/captures/`.
