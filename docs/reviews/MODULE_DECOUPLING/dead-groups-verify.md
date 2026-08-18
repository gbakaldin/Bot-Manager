# Dead-Groups Verify — MODULE_DECOUPLING

Diagnostic (NOT a deploy). Target: Bot-1 (staging). Prod untouched, no redeploy, no commit, no push.
Build under test: module-decoupling `8c10186` (5-module split: `bot-app` / `bot-engine` / …), deployed earlier.
Container `bot-java-bot-manager-1`, this boot started `2026-08-04T12:17:20Z`, `Up 21h (healthy)`.
Date: 2026-08-05.

## Question

UI shows **all bot groups as "Dead"**. Is this a **regression from the refactor deploy**, or **pre-existing persisted state that a restart merely surfaced**?

## Verdict

**Refactor regression = NO.**

"All dead" is **pre-existing persisted DB state surfaced by the restart**, plus a UI/runtime-absent artifact — not something the deploy wrote and not a broken run path. The refactored build boots cleanly and the bot **auth + WebSocket-connect path works**: a started 097/BOM group authenticated and connected **15/15 bots**, with one bot actively betting live rounds.

## Evidence

### 1. Clean boot — the deploy killed nothing

`docker logs bot-java-bot-manager-1` (this boot) is clean:

- `Started Starter in 4.359 seconds`
- `BotGroupBehaviorService - Bot Manager startup complete. 0 bot groups running`
- Strategy/validator factories wired normally (9 betting strategies, 2 slot, 5 validators).
- **No** ERROR, **no** exception, **no** bean-creation / `NoSuchBeanDefinition` / `UnsatisfiedDependency`, **no** health-monitor line marking any group DEAD during or after startup.

Auto-start correctly started **0** groups because **no group has `targetStatus=ACTIVE`** — DEAD is terminal by design (RESILIENCE_HARDENING) and a restart only auto-starts ACTIVE. This is exactly the expected-after-restart behavior.

### 2. DB truth — statuses are persisted, pre-existing, none ACTIVE

`db.botGroups` — 30 groups. Distribution of `targetStatus`:

- `DEAD`: 13 (e.g. `5a1cc162` Auth test 22, `b1e80470` 116 Demo group, `40fa3749` XD game test, `4d7f6ac9` Fruit shop Bots, `75899bb9` RIK114 Jackpot 100, `ab81f9e6` BOM flow test 100, the six `Slot group 11x/12x`, `66cfc12c`/`401f4c63` Tai Xiu, `4342888f` Slot test 204)
- `STOPPED`: 11 (e.g. `8a4b9f60` Auth test with socket, `85dfa0c3` Bot Group Demo, `9b54e101` BC Mini, the RIK114 smoke trio, `74feeaed`/`99a266f3`/`ec759951` Slot smoke)
- `null` / absent: 6 (`111`, `222`, `1c1f2081`, `f20a0fcc`, `7a3716ed`, `eea208a8`)
- **`ACTIVE`: 0**

**No group has `targetStatus=ACTIVE` while showing Dead** — that is the pattern that *would* implicate the deploy, and it does not occur. Several groups were already `DEAD` from prior sessions (documented in memory: `75899bb9` set STOPPED earlier but re-DEAD, `ab81f9e6` "kept running"). These states predate this boot.

### 3. How the UI derives "Dead" — persisted status + runtime-absent, not a deploy write

Two read surfaces, confirmed live:

- `GET /{id}/status` returns **`targetStatus`** (persisted, from DB) and **`actualStatus`** (computed from the in-memory `BotGroupRuntime`).
- `getActualStatus()` (`BotGroupBehaviorService:1379`) maps **runtime-absent → `STOPPED`**, never DEAD.

Live samples right after the restart:

| Group | DB targetStatus | actualStatus (runtime) | health status |
|---|---|---|---|
| `9b54e101` BC Mini | STOPPED | STOPPED | STOPPED |
| `5a1cc162` Auth test 22 | **DEAD** | STOPPED | STOPPED |
| `ab81f9e6` BOM flow 100 | **DEAD** | STOPPED | STOPPED |
| `111` Test group 097 | null | STOPPED | — |

So after a restart every group's **runtime is absent → actualStatus=STOPPED**, while the **persisted targetStatus (DEAD for 13 groups) is unchanged**. The UI's "Dead" badge is driven by the persisted `targetStatus=DEAD` (dominant, 13 groups) and/or the runtime-absent (no live runtime) condition — i.e. **pre-existing state + the restart clearing the in-memory runtime**, not a value the deploy wrote. Nothing in the run path re-marked anything DEAD.

### 4. Definitive run-path test — auth + connect WORK on the refactored build

Picked a **STOPPED** group on the connectable **097/BOM** environment (`3cda38f9-…-207768`, P_097, brand G2 — connects without whitelisting; deliberately avoided P_116/TIP WS-DNS-blocked and tx7):

- Group `8a4b9f60-b342-4c6f-9dff-11023fcd215a` "Auth test with socket", 15 bots, `namePrefix=authtestws971`.
- `POST /{id}/start` → **HTTP 200**.

Result within seconds (all 15 bots):

- `AUTHENTICATING → AUTHENTICATED` — **15/15**
- `AUTHENTICATED → CONNECTING`, `Authenticated with token 29-…` — **15/15**
- `Connected to server` — **15/15**
- Live `SessionAggregationService … UpdateBet #3/#4 … total staked 2.19M/2.66M … options histogram` — a bot is **actively betting real rounds**, so game-round play works too, not just auth.

Negative checks (all clear on the refactored build):

- **No** `ValidationException: Authentication configuration is required` (the known pre-existing restart-lifecycle bug did **not** fire on this `/start`).
- **No** `Auth handshake timed out`.
- **No** bean-creation / wiring errors.
- The only WARN/"error" lines captured were my own probing `curl`s (`GET /api/v1/bot-group/` method-not-supported, `/filter/` no-static-resource) — harmless.

**Post-connect churn is pre-existing, not the refactor.** After connecting, some bots showed `Channel became inactive` / `Connection closed (was connected: true)` / `reconnect attempt … did not hold` / `Cannot send message, not connected`. This is the **documented** server-side subscriber pruning (~10-subscriber cap; this group has 15) plus the known no-clean-reconnect behavior (CLAUDE.md § Known Bugs). It is orthogonal to the module split and unrelated to auth. `was connected: true` confirms the WS handshake+AUTH succeeded before the server closed the socket.

### 5. Cleanup

`POST /{id}/stop` on `8a4b9f60` → **HTTP 200**; final status `targetStatus=STOPPED, actualStatus=STOPPED`. **Staging left exactly as found** (group STOPPED, no ACTIVE groups, no other changes).

## Conclusion

- Boot on `8c10186` is clean; nothing marks groups DEAD at/after startup.
- DEAD/STOPPED/null statuses are **persisted, pre-existing** DB state; **zero** groups are ACTIVE, so the restart auto-started none — expected.
- The UI "Dead" is persisted `targetStatus=DEAD` (13 groups) plus the runtime-absent-after-restart artifact (actualStatus falls back to STOPPED); no deploy write.
- The refactored build's **bot creation + authentication + WS connect path works** — 15/15 bots authenticated and connected on 097/BOM, with live betting.

**The "all dead" state is pre-existing persisted state, and the run path works on the new build. No refactor regression.**
