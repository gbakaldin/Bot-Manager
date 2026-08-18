# VIPTALK_ALERTING_V2

Extends the (uncommitted, branch `staging`) VipTalk alerting feature from
"everything lands in the ops room" to **audience-aware routing**: product rooms get
everything for their product across all environments, the ops room gets only
fundamental infrastructure, and a serious outage renders two different messages to
two different audiences. Adds four new alert cases (dead game, dead environment,
low balance, host storage).

Read alongside `docs/plans/VIPTALK_ALERTING.md` (AD-1..AD-8). This document
**honours AD-1, AD-2, AD-3, AD-4, AD-6, AD-7, AD-8** and **supersedes AD-5**
(see AD-V3).

---

## Goal

Make the two VipTalk audiences actually distinct. Today every rule routes to the
ops room because no metric carries a product or environment label, and
`AlertService.send` falls back to the ops room for anything unroutable — so the
ops room is a catch-all and the product rooms are empty. We want: (1) product
rooms receive every product-scoped alert regardless of which instance
(prod/loadtest/staging) produced it, distinguished only by the instance label;
(2) the ops room receives only CPU / RAM / storage / app-down-restarted; (3) a
serious outage reaches product rooms in a non-technical register while reaching
the ops room in full technical detail; and (4) the "app is down" message is
delivered by something other than the app.

---

## Findings — Current State

### Alerting feature (Phases 1–3, uncommitted)

- `AlertRoomRegistry` — product rooms from `ProductCode.getVipTalkRoomId()`, ops
  room from `viptalk.ops-room-id`.
  `/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/alert/service/AlertRoomRegistry.java:50` (`roomFor`),
  `:60` (`opsRoom`), `:69` (`broadcastRooms`).
- **The ops room is a hard-coded catch-all**:
  `AlertService.send` does `rooms.roomFor(product).or(rooms::opsRoom)` —
  `/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/alert/service/AlertService.java:52-54`.
  This is the single line that makes requirement B2 impossible today.
- `AlertmanagerWebhookService.resolveProduct` — two hops: `product` label, then
  `environmentId` → Mongo → `Environment.getProductCode()`, memoised per payload.
  `/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/alert/service/AlertmanagerWebhookService.java:184-215`.
  Batches per-room into one message (`:106-130`).
- `AlertMessageFormatter.format(Alert)` — one register only, header
  `marker · LABEL · Product (code) · instanceLabel`.
  `/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/alert/service/AlertMessageFormatter.java:38-62`.
- `Alert` is a 5-component record; no audience concept.
  `/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/alert/model/Alert.java:18`.
- `VipTalkClient` — `HttpClient`, form-urlencoded `text` + repeated `roomIds`,
  never throws. `/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/infrastructure/notification/VipTalkClient.java:120-158`.
- Only `P_116` has a room:
  `/Users/gleb/IdeaProjects/Bot/bot-api/src/main/java/com/vingame/bot/domain/brand/model/ProductCode.java:17`.
- Alertmanager posts to `http://bot-manager:8085/api/v1/alerts/alertmanager`
  (`/Users/gleb/IdeaProjects/Bot/alertmanager/alertmanager.yml:36`) — **the app
  receives its own down-alert**, which is the B3 bootstrap problem.
- `alertmanager.yml` is bind-mounted read-only and **committed**
  (`/Users/gleb/IdeaProjects/Bot/docker-compose.yml:141`). Alertmanager does
  **not** expand environment variables in its config, so any secret placed there
  must be rendered at deploy time. `deploy.sh` already renders `.env` from
  `secrets.env` (`/Users/gleb/IdeaProjects/Bot/deploy.sh:18-32`) — same trick applies.

### Metrics — what already carries which labels

| Meter | Kind | Labels today | Registered at |
|---|---|---|---|
| `bot_messages_total{cmd}` | counter | `botGroupId, environmentId, gameType, gameId, gameName` | `BotMetrics.java:116` via `mdcTags()` `:94-102` |
| `bot_login_total{outcome}` | counter | same MDC set | `BotMetrics.java:269`; called from `ApiGatewayClient.java:148,153` |
| `bot_verify_token_total{outcome}` | counter | same MDC set | `BotMetrics.java:278`; called from `ApiGatewayClient.java:492-506` |
| `bot_watchdog_expired_total` | counter | same MDC set | `BotMetrics.java:170`; fired at `BettingMiniGameBot.java:373` |
| `bot_creation_failures_total{reason}` | counter | same MDC set | `BotMetrics.java:161`; fired at `BotGroupBehaviorService.java:578` |
| `bots_by_env_status` | MultiGauge | `environmentId, status` | `InfoGaugeRefresher.java:111,157` |
| `bots_by_game_status` | MultiGauge | `gameId, gameName, status` | `InfoGaugeRefresher.java:108,149` |
| `environment_join` | MultiGauge | `environmentId, environmentName` | `InfoGaugeRefresher.java:105` |
| `game_join` | MultiGauge | `gameId, gameName, gameType` | `InfoGaugeRefresher.java:102` |
| `bots_managed`, `ws_connections_open`, `bots_dead_currently`, `groups_dead_currently`, `bot_groups_running`, `bots_by_status` | Gauge | **none** (fleet aggregates) | `ObservabilityConfig.java:43-86` |

**No metric anywhere carries `product`.** Confirmed by grep across
`bot-api`/`bot-engine`/`bot-app`.

### The decisive cardinality finding

`product` is **functionally determined** by the labels already present:

- `Environment.productCode` — `/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/environment/model/Environment.java:44`
- `Game.productCode` **and** `Game.environmentId` —
  `/Users/gleb/IdeaProjects/Bot/bot-api/src/main/java/com/vingame/bot/domain/game/model/Game.java:32,47`

So `gameId → environmentId → product` is a chain of functional dependencies.
Adding `product` (and `environmentId`/`gameType` to the game-scoped gauges) adds
**zero new time series** — the same series gain an extra label. The only cost is
series *identity churn* at the deploy that introduces the label, which is
irrelevant for counters (they reset on restart anyway) and harmless for
`sum by(...)` dashboard queries and `metric{a="x"}` selectors.

Every bot already has `configuration.getGame().getProductCode()` in hand
(`BotConfiguration.game` — `/Users/gleb/IdeaProjects/Bot/bot-api/src/main/java/com/vingame/bot/config/bot/BotConfiguration.java:46`)
and MDC is set once per bot at `Bot.initialize()`
(`/Users/gleb/IdeaProjects/Bot/bot-engine/src/main/java/com/vingame/bot/domain/bot/core/Bot.java:215-223`).

### Signals available for the four new cases

- **Game rounds arriving** — `bot_messages_total{cmd="startGame"}` fired from the
  *server's* StartGame frame (`BettingMiniGameBot.java:386-387`), and
  `cmd="spin"` fired from the *server's* SpinResult frame
  (`SlotMachineBot.java:212-213`). Both are inbound-message counters, so both are
  genuine game-liveness signals. The watchdog (`BettingMiniGameBot.java:355-375`)
  already exists and emits `bot_watchdog_expired_total` — it is the per-bot
  recovery mechanism, and its counter is a good *corroborating* signal but a poor
  primary one (it only fires on `BETTING_MINI`/`TAI_XIU`, and a game that never
  delivered a first round produces watchdog churn indistinguishable from a
  network problem). `SessionAggregationService` is **log-only — it registers no
  meters** (`/Users/gleb/IdeaProjects/Bot/bot-engine/src/main/java/com/vingame/bot/infrastructure/observability/SessionAggregationService.java`).
- **Socket health per environment** — `bots_by_env_status` exists, but the fleet
  rule uses `ws_connections_open` (`bot.isConnected()`,
  `BotGroupBehaviorService.java:1298-1306`), which is a *different* predicate from
  `BotStatus`. There is **no per-environment analogue** of `ws_connections_open`
  or `bots_managed`.
- **Login health** — `bot_login_total` is only written at group start /
  re-auth. `bot_verify_token_total` is written on **every** authoritative balance
  fetch (`ApiGatewayClient.getBalance` → `verifytoken.aspx`, `:492-506`), so it is
  the continuous auth-gateway-reachability signal.
- **Balance** — **no metric exists.** `Bot.getExpectedBalance()` (`Bot.java:456`)
  and `Bot.getLastFetchedBalance()` (`:460`) are exposed to the REST health DTO
  only (`BotGroupBehaviorService.java:993-994`) and to `computeStats`
  (`:1160-1171`). `autoDepositEnabled` lives on `BotGroup`
  (`BotGroup.java:126`) and is copied into `BotBehaviorConfig`
  (`BotGroupBehaviorService.java:671`); it gates the top-up at
  `BettingMiniGameBot.java:331` / `SlotMachineBot.java:162`.
  `Environment.alertOnLowBalance` (`Environment.java:65`) exists in the model and
  the mapper but is **read by nothing** — dead field.
- **Host resources** — Prometheus scrapes exactly one target,
  `bot-manager:8085/actuator/prometheus`
  (`/Users/gleb/IdeaProjects/Bot/prometheus/prometheus.yml:29-38`). No
  node-exporter, no cAdvisor.

---

## Per-aspect readiness / mapping

| Aspect | Readiness | Notes |
|---|---|---|
| **A1** Game dead | **partial** | Signal exists (`bot_messages_total{cmd="startGame"\|"spin"}`, has `gameId`+`environmentId`). Blocked on: `bots_by_game_status` lacking `environmentId`/`product`/`gameType`, needed as the "this game is supposed to be live" left operand. Phase 1 + 3. |
| **A2a** Env socket down | **partial** | `bots_by_env_status` exists but is status-based, not `isConnected()`-based. Needs two new per-env gauges. Phase 1 + 3. |
| **A2b** Env login down | **ready** | `bot_login_total` + `bot_verify_token_total` already carry `environmentId`; need `product` for routing. Phase 1 + 3. |
| **A3** Low balance, non-auto-deposit | **blocked → new metric** | No balance metric of any kind. New `group_balance_ratio` MultiGauge. Phase 4. |
| **A4** Storage / CPU / RAM | **blocked → new scrape target** | Nothing host-level is scraped. node-exporter. Phase 5. |
| **B1** Product rooms get everything for the product | **blocked → the big work item** | No `product` label anywhere. Phase 1. |
| **B2** Ops room = infrastructure only | **partial** | Requires deleting the `.or(rooms::opsRoom)` fallback and adding an audience concept. Phase 2. |
| **B3** Dual-register outage message | **partial** | Formatter is single-register; and the app-down path cannot use the app. Phase 2 (fork) + Phase 6 (out-of-band delivery). |
| **App restarted** | **ready** | `process_start_time_seconds` is already exposed by actuator. Phase 6, delivered by the app (it is up by definition). |
| Multi-instance duplication | **partial** | `viptalk.instance-label` already in the header (AD-7). Needs a prod-only gate for customer-facing copy. Phase 2 + 6. |
| Unwired product rooms | **partial** | `roomFor` already returns empty; needs an explicit drop path + drop counter instead of the ops-room fallback. Phase 2. |

---

## Architecture Decisions

**AD-V1 — `product` becomes a first-class metric label, value = the numeric code.**
`ProductCode.getCode()` (`"116"`, `"097"`), not the enum name and not the display
name. `ProductCodes.parse` already accepts all three
(`/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/alert/service/ProductCodes.java`),
so the app side is unaffected; the numeric code is the shortest and is what
`/api/v1/brand` already publishes. The label is carried by MDC for `bot_*`
counters and by explicit `Tags` on the MultiGauges. **Zero cardinality cost** —
see the finding above.

**AD-V2 — Aggregate fleet gauges stay unlabelled.**
`bots_managed`, `ws_connections_open`, `bots_dead_currently`,
`groups_dead_currently`, `bot_groups_running`, `bots_by_status` remain on the
`BotMdcTagsMeterFilter` exclusion list
(`BotMdcTagsMeterFilter.java:47-61`). They are fleet facts with no owning
product. Rules built on them are `audience: internal` by definition — and B2 says
the ops room takes infrastructure only, so `BotGroupDead` / `DeadBotRatioHigh` /
`WsConnectionsBelowFleet` are **replaced** by their per-environment equivalents
rather than kept and re-routed (see Phase 3).

**AD-V3 — Supersedes AD-5: routing is by explicit `audience` label, and the ops
room is no longer a fallback.**
AD-5 made the ops room the terminal fallback for anything unroutable. That is
precisely what B2 forbids. Replacement contract — every Prometheus rule declares
its audience:

| `audience` label | Rooms | Register |
|---|---|---|
| `internal` | ops room only | technical |
| `product` | that product's room only | technical |
| `both` | ops room **and** the product room(s) | technical **and** customer |

Product resolution keeps AD-5's hop order (`product` label → `environmentId` →
Mongo), because it costs nothing and covers rules that legitimately have only an
environment. What is removed is the third hop (ops-room fallback).

**AD-V4 — Absent `audience` ⇒ `internal`, enforced by a test over `alerts.yml`.**
An unlabelled rule must not silently vanish, so the default is the room that is
guaranteed to exist. To stop that default becoming a loophole, a unit test parses
`../prometheus/alerts.yml` and fails if any rule omits `audience`. The default is
therefore only ever exercised by a hand-crafted webhook payload, never by our own
rules.

**AD-V5 — An unroutable product alert falls back to the ops room, tagged.**
*(Amended 2026-08-17 by user decision — supersedes the original "drop, never
redirect" form of this AD, which is preserved below as AD-V5-orig.)*

`audience: product` with no resolvable product, or a product whose
`vipTalkRoomId` is `null`: deliver to the **ops room** with an explicit
misroute marker in the message (so a reader can tell it apart from a genuine
internal alert), log WARN, and increment
`alert_dispatch_total{outcome="misrouted", reason="no_room"|"no_product", product}`.

**Rationale for the amendment.** Only P_116 is wired. Under the original form,
9 of 10 products — including P_097/BOM, the product the user owns end-to-end —
would silently discard every alert, with a counter as the only trace. Losing
real alerts during rollout is a worse failure than temporarily bending B2.

**This is explicitly a rollout posture, not the end state.** The fallback is
per-product and self-retiring: once a product's `vipTalkRoomId` is filled in,
its alerts route to its own room and stop reaching ops with no further change.
When every product is wired, the fallback becomes unreachable and B2 holds
strictly again. Keep the `misrouted` counter as the signal for how much is still
landing in the wrong place.

Only `audience: product` alerts take this path. `audience: internal` is
unaffected, and `audience: both` still delivers its internal register to ops
normally — a misrouted product register must not produce a second ops message
for the same alert; dedupe on (alert, room) before sending.

Meter name deliberately does not start with `bot_`, so `BotMdcTagsMeterFilter`
leaves it alone.

**AD-V5-orig (superseded, retained for when rooms are complete)** — drop the
alert, do not redirect: `alert_dispatch_total{outcome="dropped", ...}`, nothing
to the ops room, the drop itself alertable at `audience: internal`. Restore this
once all 10 products carry a room ID.

**AD-V6 — The two registers fork in the formatter, driven by a rule annotation.**
`AlertMessageFormatter.format(Alert, AlertRegister)` with
`AlertRegister ∈ {TECHNICAL, CUSTOMER}`. The customer text is **not** derived
from the technical text — it comes from a dedicated Prometheus annotation
`public_summary`. If an `audience: both` alert has no `public_summary`, the
product-room copy is **suppressed** (fail-closed: never leak internal wording, a
stack-trace-shaped summary, or an environment id into a product room). The
existing single-arg `format(Alert)` is kept, delegating to `TECHNICAL`, so
`AlertMessageFormatterTest` stays green.

**AD-V7 — Customer-facing copy is prod-only, gated by one config flag.**
`viptalk.customer-notices-enabled` (env `VIPTALK_CUSTOMER_NOTICES_ENABLED`),
default `false`. When false, `audience: both` degrades to `audience: internal`.
Without this, a staging restart would push "Bot Management application is
experiencing issues" into the live TIP room three times a day. Set `true` only on
the prod instance.

**AD-V8 — DEAD (falsified by Phase 0, 2026-08-17). Superseded by AD-V8b.**
The spike proved VipTalk **ignores query parameters** on `sendMessage`: it parses
the URL (it echoes the query string back in the error's `path` field) but sources
`text` only from the request body, answering `400 M_CANNOT_SEND_EMPTY_MESSAGE`.
The conditional this AD was written under therefore fails, and **Phase 6 uses
AD-V8b's shim container**. Results: `docs/reviews/VIPTALK_ALERTING_V2/spike.md`.
The original text is retained below for the record only — do not implement it.

*(original) The app-down path must not traverse the app: a second, static
Alertmanager receiver posting straight to VipTalk.*
Alertmanager's `webhook_configs` always POSTs a fixed `application/json` body with
no templating, so it cannot produce VipTalk's form-urlencoded body. The chosen
route is to put the message and room list in the **URL query string** of a second
receiver whose `url` points at `api.viptalk.org` directly:

```
url: https://api.viptalk.org/v1/bot/<TOKEN>/sendMessage?text=<static+message>&roomIds=<ops>&roomIds=<tip>
```

Alertmanager still sends its JSON body; VipTalk ignores it and reads the query
parameters. **This is conditional on Phase 0 confirming VipTalk reads query
params** (its API is Telegram-bot-shaped, `/v1/bot/{token}/sendMessage`, which
normally does). If the spike fails, fall back to AD-V8b.

**AD-V8b — CHOSEN (Phase 0, 2026-08-17): a `prom/alertmanager`-independent shim
container.**
Add a ~30-line single-file service to `docker-compose.yml` (own image, no shared
code with bot-manager, no Mongo, no JVM) that accepts the Alertmanager webhook
and re-emits it in the shape VipTalk wants. It must be a *separate container* —
putting it in bot-manager reintroduces the bootstrap problem.

*Amended 2026-08-17 with the spike's unplanned finding:* VipTalk **does** accept
`application/json` with `{"text": …, "roomIds": [ … ]}` (→ 200), so the shim is a
**JSON→JSON field remap**, not the JSON→form-urlencoded re-encode this AD
originally costed. No form-encoding library is needed. `VipTalkClient` stays on
form-urlencoded — that path is verified working and switching it would be churn
for no gain.

**AD-V9 — `send_resolved: false` on the static receiver; the resolved half comes
from the app.**
The static receiver's text is a fixed string and cannot say "recovered". Route
`BotManagerDown` to **both** receivers with `continue: true`:
- static receiver, `send_resolved: false` → delivers the outage message while the
  app is dead;
- app webhook receiver, `send_resolved: true` → its firing notification fails
  (app down, Alertmanager retries), and its **resolved** notification succeeds,
  because by definition the app is up again when `BotManagerDown` resolves.

**AD-V10 — "Application restarted" is a separate rule from "application down".**
`BotManagerRestarted`: `changes(process_start_time_seconds{job="bot-manager"}[15m]) > 0`,
severity `warning`, `audience: internal`. The app is up when this fires, so it
goes through the normal webhook. It is not `audience: both` — an already-recovered
restart is not something to tell a product room about.

**AD-V11 — node-exporter, not actuator `disk_free_bytes`.**
Actuator's `DiskSpaceMetricsAutoConfiguration` does expose
`disk_free_bytes{path="/app/."}` for free, and on a single-disk host that path
does share the filesystem with the Loki volume. It is rejected as the alerting
source for one decisive reason: **it is produced by the app, so a disk-full event
that kills the app also kills the metric** — exactly the 2026-06-30 shape, where
Loki's unbounded volume ENOSPC-killed Mongo. It also cannot see host RAM at all
(only JVM heap), and the user explicitly asked for CPU and RAM in the ops room.
node-exporter costs one ~25 MB container with read-only host mounts, no code, and
keeps reporting when the JVM is gone. Actuator's disk metrics are left enabled as
a free JVM-side cross-check; **no rule binds to them**.

**AD-V12 — The low-balance metric reads `expectedCurrentBalance`, not
`lastFetchedBalance`.**
`checkBalance()` re-reads the server only when
`|lastFetchedBalance − expectedCurrentBalance| > balanceSyncThreshold`, and
`balanceSyncThreshold = depositAmount × 1% ` (`Bot.java:335-355, 433-435`). The
check runs once per round, at `onNewSession()`
(`BettingMiniGameBot.java:328-337`). That bound is the reason `expected` is
trustworthy: **the two values can never diverge by more than 1% of the deposit
amount**, because crossing that gap is precisely what triggers the re-sync. At a
10%-of-deposit alert threshold, an alert on `expected` fires within one
1%-of-deposit band of when an alert on server truth would. `expected` is also the
value the app itself uses to decide whether to top up (`BettingMiniGameBot.java:331`)
and the value the existing UI stat reports (`BotGroupBehaviorService.java:1164`),
so alerting on it keeps alert, behaviour and UI consistent. The one case where it
is *not* trustworthy — a bot that stopped receiving rounds, so `onNewSession`
never runs and both values freeze — is covered by A1 (game dead).

**AD-V13 — Low balance is emitted as a ratio, and only for non-auto-deposit
groups with at least one active bot.**
`group_balance_ratio = avg(expectedBalance over isConnected() bots) / resolvedDepositAmount`.
A ratio makes the rule one threshold across every currency scale and deposit size
(`0.10`), matching the user's "10% of the last deposit value" verbatim and
matching `MIN_BALANCE_PERCENT_OF_DEPOSIT = 10` (`Bot.java:408`). Rows are emitted
**only** for groups where `autoDepositEnabled == false` — an auto-deposit group
dipping below 10% is normal operation, not an alert — and only when
`activeBots > 0`, so a stopped or fully-reconnecting group does not read 0 and
page someone.

**AD-V14 — AD-1 stands: product rooms in `ProductCode`, ops room in config.**
Nothing in the new routing needs a dynamic room set. The one addition is the
static room list for the out-of-band app-down receiver, which lives in
`secrets.env` (`VIPTALK_DOWN_ROOM_IDS`) because Alertmanager cannot read Java
enums. That is config-for-Alertmanager, not a second source of truth for the app.

**AD-V15 — Cross-instance duplication is accepted, not deduplicated.**
Three instances × one shared room means three Alertmanagers that cannot see each
other. This is correct: staging being down is a different event from prod being
down, and AD-6 forbids building an app-side state machine to merge them. The
mitigations are (a) `viptalk.instance-label` in the header (AD-7), already
implemented; (b) `repeat_interval: 4h` per instance, already set; (c) AD-V7,
which keeps non-prod instances out of the customer register entirely. `group_by`
and `inhibit_rules` remain per-instance and are only expected to work
within an instance.

---

## Plan

### Phase 0 — Spike (no code, no deploy)

Two questions to settle before Phase 5 and Phase 6 are written down as final.

1. **Does VipTalk read query parameters on `sendMessage`?** With the real token,
   from the Bot-1 host:
   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' -X POST \
     "https://api.viptalk.org/v1/bot/${VIPTALK_BOT_TOKEN}/sendMessage?text=queryparam+probe&roomIds=${VIPTALK_OPS_ROOM_ID}" \
     -H 'Content-Type: application/json' -d '{"ignored":true}'
   ```
   Expect `200` **and** the message visible in the ops room. If either fails →
   AD-V8b applies and Phase 6 grows a shim container.
2. **Does node-exporter see the filesystem that filled on 2026-06-30?** After
   Phase 5 is up: `docker compose exec prometheus wget -qO- 'http://node-exporter:9100/metrics' | grep node_filesystem_avail_bytes` and confirm a
   `mountpoint="/"` series whose `node_filesystem_size_bytes` matches `df -h /`
   on the host.

**Deliverable:** a one-line answer to each in `docs/reviews/VIPTALK_ALERTING_V2/spike.md`.

---

### Phase 1 — `product` on every routable meter, plus the per-environment gauges

Pure metrics work. No routing change, no rule change — deployable and verifiable
on its own by scraping `/actuator/prometheus`.

1. **`BotMdc`** (`/Users/gleb/IdeaProjects/Bot/bot-api/src/main/java/com/vingame/bot/common/logging/BotMdc.java`
   — *amended 2026-08-17: the file lives in `bot-api`, not `bot-engine`; it is a
   contract-module class shared by `bot-engine` and `bot-app`*)
   - Add `public static final String PRODUCT = "product";`
   - Add a `product` parameter to `set(...)` (after `environmentId`), `MDC.put`
     it when non-null, and `MDC.remove` it in `clear()`.
   - `setGroupContext(botGroupId, environmentId)` gains an overload
     `setGroupContext(botGroupId, environmentId, product)`; keep the 2-arg form
     delegating with `null` so the 9 existing call sites compile
     (`BotGroupBehaviorService.java:436,485,535,565,815,875,1483,1569`,
     `ActivationScheduler.java:116`, `BotGroupRuntime.java:171`).
2. **`Bot.initialize()`** (`Bot.java:215-223`) — pass
   `configuration.getGame().getProductCode() == null ? null : configuration.getGame().getProductCode().getCode()`.
   The MDC snapshot at `:229` picks it up unchanged.
3. **`BotMetrics.mdcTags()`** (`BotMetrics.java:94-102`) — `addIfPresent(tags, BotMdc.PRODUCT)`.
   Update the class javadoc cardinality note (`:42-49`) with the
   functional-dependency argument.
4. **`BotMdcTagsMeterFilter.map`** (`BotMdcTagsMeterFilter.java:73-79`) —
   `addTagIfPresent(extra, BotMdc.PRODUCT)`. Aggregate exclusion list unchanged
   (AD-V2).
5. **`BotGroupRuntime`** — add a `groupName` field alongside `environmentName`
   (`BotGroupRuntime.java:53-56`) and a `product` field, both threaded in at
   group start exactly like `environmentName` is today. Keep the existing
   constructors delegating.
6. **`BotGroupBehaviorService`** — extend the gauge-support records
   (`:1350-1367`):
   - `GameInfo(gameId, gameName, gameType)` → `+ environmentId, product`
   - `EnvInfo(environmentId, environmentName)` → `+ product`
   - `GameStatusKey(gameId, gameName, status)` → `+ environmentId, gameType, product`
   - `EnvStatusKey(environmentId, status)` → `+ product`
   Sources: `bot.getConfiguration().getGame()` for game rows (it carries
   `environmentId`, `gameType` and `productCode` directly), `runtime` for env rows.
   Add two new accessors mirroring `getTotalManagedBots` (`:1289`) and
   `getOpenWsConnectionCount` (`:1298`), grouped by environment:
   `Map<EnvKey,Integer> countManagedBotsByEnv()` and
   `Map<EnvKey,Integer> countOpenWsByEnv()` where `EnvKey(environmentId, product)`.
7. **`InfoGaugeRefresher`** (`InfoGaugeRefresher.java:101-163`) — add the new tags
   to all four existing row builders, and register two new MultiGauges:
   - `bots_managed_by_env{environmentId, product}`
   - `ws_connections_open_by_env{environmentId, product}` (uses `bot.isConnected()`,
     the same predicate as the fleet gauge — deliberately *not* `BotStatus`)
   Add both names to `BotMdcTagsMeterFilter.AGGREGATE_METER_NAMES` (`:47-61`) so
   the 10 s refresher thread's MDC can never leak onto them.
8. Update `BotMdcTagsMeterFilterTest`, `ObservabilityConfigTest`,
   `BotMetricsTest`, `InfoGaugeRefresher` tests for the new tags.

**Resulting label sets:**

```
bot_messages_total{cmd,botGroupId,environmentId,product,gameType,gameId,gameName}
bot_login_total{outcome,botGroupId,environmentId,product,gameType,gameId,gameName}
bot_verify_token_total{outcome,...same...}
bots_by_env_status{environmentId,product,status}
bots_by_game_status{gameId,gameName,gameType,environmentId,product,status}
environment_join{environmentId,environmentName,product}
game_join{gameId,gameName,gameType,environmentId,product}
bots_managed_by_env{environmentId,product}
ws_connections_open_by_env{environmentId,product}
```

**Verify:** `curl -s localhost:8080/actuator/prometheus | grep -c 'product="'` > 0;
`bots_by_game_status` lines carry `environmentId` and `product`;
`bots_managed_by_env` sums to `bots_managed`.

---

### Phase 2 — Audience routing in the app

No metric change, no rule change. Deployable on its own: with no rule carrying an
`audience` label yet, AD-V4's default sends everything to the ops room — i.e.
today's behaviour, byte for byte.

1. **New** `domain/alert/model/AlertAudience.java` — `INTERNAL, PRODUCT, BOTH`,
   with `fromLabel(String)` returning `INTERNAL` for null/blank/unknown (AD-V4).
2. **New** `domain/alert/model/AlertRegister.java` — `TECHNICAL, CUSTOMER`.
3. **`Alert`** (`Alert.java:18`) — canonical record grows two components:
   `AlertAudience audience`, `String publicSummary`. Add a **5-arg secondary
   constructor** delegating with `(AlertAudience.PRODUCT, null)` so every
   existing `new Alert(...)` in tests and `withProduct` compiles unchanged. The
   5-arg default is `PRODUCT` because that form is the hand-authored operator
   path (`AlertController.sendToProduct`), which is always product-scoped; the
   webhook path always passes `audience` explicitly.
4. **`AlertMessageFormatter`** — `format(Alert, AlertRegister)`; keep
   `format(Alert)` delegating to `TECHNICAL`. `CUSTOMER` renders:
   ```
   ⚠️ TIP (116) · prod
   ALERT! Bot Management application is experiencing issues, backend team is aware and will deliver fixes soon.
   ```
   i.e. marker + product + instance label on the header, then `publicSummary`
   verbatim. **No** severity word, no bullet list, no `— via prometheus` trailer,
   no environment id, no metric values.
5. **New** `domain/alert/service/AlertRouter.java` — a pure function
   `List<RoutedMessage> route(Alert)` where
   `RoutedMessage(String roomId, String text)`. Implements AD-V3/V5/V6/V7:
   - `INTERNAL` → ops room, TECHNICAL. Ops room unset → drop + WARN.
   - `PRODUCT` → `rooms.roomFor(product)`, TECHNICAL. Empty → drop + WARN +
     `alert_dispatch_total{outcome="dropped",reason=...}`.
   - `BOTH` → ops room TECHNICAL, **plus** product room CUSTOMER *iff*
     `viptalk.customer-notices-enabled` **and** `publicSummary` is present.
   Unit-testable with zero Spring context.
6. **`AlertService.send`** (`AlertService.java:51-66`) — **delete the
   `.or(rooms::opsRoom)` fallback**; delegate to `AlertRouter`, then group the
   routed messages by identical text and issue one `VipTalkClient.send(text,
   rooms)` per distinct text (preserves AD-4: at most two POSTs for a `BOTH`
   alert, one for everything else). `broadcast` is unchanged.
7. **New** `alert_dispatch_total{outcome, reason, product}` counter registered in
   `AlertService`/`AlertRouter`. `outcome ∈ sent|dropped|failed`,
   `reason ∈ ok|no_room|no_product|no_ops_room|customer_notices_disabled|no_public_summary`.
   Bounded, ~6 × 4 × 11 worst case.
8. **`AlertmanagerWebhookService`** — read `audience` and `public_summary` off the
   alert labels/annotations and set them on the constructed `Alert`
   (`toAlert`, `:106-130`). Batching currently keys on product only; re-key on
   `(product, audience)` so an `internal` and a `product` alert in one
   Alertmanager group are never merged into one message.
9. **`application.properties`** — `viptalk.customer-notices-enabled=false`;
   `docker-compose.yml` → `VIPTALK_CUSTOMER_NOTICES_ENABLED=${...:-false}`;
   document in `secrets.env.example`.

**Existing tests to keep green:** `VipTalkClientTest`, `AlertServiceTest`,
`AlertMessageFormatterTest`, `AlertmanagerWebhookServiceTest`. `AlertServiceTest`
will need new cases for the removed fallback; the old "no product room → ops
room" expectation is now "dropped", and that change is the point of the phase.

**Verify:** `POST /api/v1/alerts/alertmanager` with a hand-rolled payload for each
of the three audience values; then `curl localhost:8080/actuator/prometheus | grep alert_dispatch_total`.

---

### Phase 3 — Product-routed rules for A1, A2a, A2b; audience labels everywhere

`prometheus/alerts.yml` + `alertmanager/alertmanager.yml` only. No app change.
Requires Phases 1 and 2 deployed.

1. **Label every existing rule.** `BotManagerDown` → `audience: both` (with a
   `public_summary`); `JvmThreadsHigh` → `audience: internal`.
2. **Retire the three unroutable fleet rules** (`BotGroupDead`,
   `DeadBotRatioHigh`, `WsConnectionsBelowFleet`) and replace them with
   per-environment equivalents. AD-V2: they are built on unlabelled aggregates,
   so under B2 they would have to go to the ops room, which is exactly the
   product-level noise B2 forbids.
3. **New rules:**

```yaml
  - name: bot-manager-environment
    rules:
      # A2a — socket down. Mirrors the retired fleet rule's predicate
      # (isConnected()) but per environment, so it routes to the product room.
      - alert: EnvironmentSocketDown
        expr: |
          bots_managed_by_env > 0
          and (ws_connections_open_by_env / bots_managed_by_env) < 0.5
        for: 10m
        labels: {severity: critical, audience: product}
        annotations:
          summary: "Only {{ $value | humanizePercentage }} of bots hold a WebSocket on env {{ $labels.environmentId }}"
          description: "WS host unreachable, or the PING-before-AUTH race. Check the game server and the WS hostname for this environment."

      - alert: EnvironmentSocketDegraded
        expr: |
          bots_managed_by_env > 0
          and (ws_connections_open_by_env / bots_managed_by_env) < 0.8
        for: 10m
        labels: {severity: warning, audience: product}
        annotations:
          summary: "{{ $value | humanizePercentage }} of bots hold a WebSocket on env {{ $labels.environmentId }}"

      # A2b — login/auth gateway down. verifytoken runs on every authoritative
      # balance fetch, so this is continuous while bots play; bot_login_total only
      # moves at group start.
      - alert: EnvironmentAuthDown
        expr: |
          (
            sum by (product, environmentId) (increase(bot_verify_token_total{outcome="failure"}[10m]))
            / clamp_min(sum by (product, environmentId) (increase(bot_verify_token_total[10m])), 1)
          ) > 0.5
          and sum by (product, environmentId) (increase(bot_verify_token_total[10m])) >= 5
        for: 10m
        labels: {severity: critical, audience: product}
        annotations:
          summary: "Auth gateway failing {{ $value | humanizePercentage }} of balance reads on env {{ $labels.environmentId }}"
          description: "verifytoken.aspx is rejecting or unreachable. Bots keep their WS but cannot read balance or re-auth."

      - alert: EnvironmentLoginFailing
        expr: |
          (
            sum by (product, environmentId) (increase(bot_login_total{outcome="failure"}[15m]))
            / clamp_min(sum by (product, environmentId) (increase(bot_login_total[15m])), 1)
          ) > 0.5
          and sum by (product, environmentId) (increase(bot_login_total[15m])) >= 5
        for: 5m
        labels: {severity: critical, audience: product}
        annotations:
          summary: "{{ $value | humanizePercentage }} of logins failing on env {{ $labels.environmentId }}"
          description: "Only meaningful while logins are being attempted (group start / periodic re-auth). Silence here does not mean login is healthy."

  - name: bot-manager-game
    rules:
      # A1 — game configured and subscribed, but no server rounds arriving.
      # `unless` (not `and`) is load-bearing: a game that has NEVER delivered a
      # round has no bot_messages_total series at all, so `and` would never fire —
      # which is exactly the tx7 / "0 sessions ever" case we most need to catch.
      # cmd="startGame" covers BETTING_MINI/TAI_XIU, cmd="spin" is the server's
      # SpinResult for SLOT (SlotMachineBot.java:212).
      - alert: GameNoRounds
        expr: |
          (
            sum by (product, environmentId, gameId, gameName, gameType)
              (bots_by_game_status{status="CONNECTION_AUTHENTICATED"}) > 0
          )
          unless
          (
            sum by (product, environmentId, gameId, gameName, gameType)
              (increase(bot_messages_total{cmd=~"startGame|spin"}[10m])) > 0
          )
        for: 10m
        labels: {severity: critical, audience: product}
        annotations:
          summary: "No rounds on game {{ $labels.gameName }} (env {{ $labels.environmentId }}) for 20 minutes"
          description: "Bots are subscribed and authenticated but the server is sending no StartGame/SpinResult. Game is stopped server-side, the subchannel is wrong, or subscribers were pruned."
```

4. **`alertmanager.yml`** — add `audience` to `group_by` (defensive: an audience
   is constant per rule, but grouping must never mix registers).

**Verify:** `promtool check rules prometheus/alerts.yml`; then stop one game
server-side (or `docker compose stop` a group's environment) and confirm
`GameNoRounds` reaches the TIP room, not the ops room.

---

### Phase 4 — Low balance on non-auto-deposit groups

1. **`BotGroupBehaviorService`** — new accessor
   `Collection<GroupBalance> listGroupBalances()` returning
   `GroupBalance(botGroupId, groupName, environmentId, product, gameId, gameName, long avgExpectedBalance, long depositAmount)`.
   Built by iterating `runningGroups`, filtering
   `!behaviorConfig.isAutoDepositEnabled()` and `activeBots > 0`, and reusing the
   exact averaging shape already at `:1155-1171`
   (`bots.stream().filter(Bot::isConnected)`, `mapToLong(Bot::getExpectedBalance)`).
   `depositAmount` mirrors `Bot.resolveDepositAmount()` — `behaviorConfig.getDepositAmount()`
   when positive, else `Bot.DEFAULT_DEPOSIT_AMOUNT`.
   `groupName` comes from the `BotGroupRuntime.groupName` threaded in in Phase 1.
2. **`InfoGaugeRefresher`** — two new MultiGauges on the same 10 s cadence:
   - `group_avg_balance{botGroupId, groupName, environmentId, product, gameId, gameName}` — absolute, for dashboards.
   - `group_balance_ratio{...same labels...}` — `avgExpectedBalance / depositAmount`.
   Both added to `AGGREGATE_METER_NAMES`. Cardinality = number of running
   non-auto-deposit groups (single digits today).
3. **Rule:**

```yaml
  - name: bot-manager-balance
    rules:
      - alert: GroupBalanceLow
        expr: group_balance_ratio < 0.10
        for: 5m
        labels: {severity: warning, audience: product}
        annotations:
          summary: "Group {{ $labels.groupName }} average balance at {{ $value | humanizePercentage }} of its deposit"
          description: "Auto-deposit is OFF for this group, so it will not top itself up. Fund it or enable auto-deposit before the bots stop betting."
```

**Verify:** `curl -s localhost:8080/actuator/prometheus | grep group_balance_ratio`
returns one line per running non-auto-deposit group and none for auto-deposit
groups.

---

### Phase 5 — node-exporter, and host CPU / RAM / storage rules

Infrastructure only. Independent of Phases 1–4; can ship before or after them.

1. **`docker-compose.yml`** — new service:
   ```yaml
     node-exporter:
       image: prom/node-exporter:v1.8.2
       restart: unless-stopped
       logging: *default-logging
       pid: host
       command:
         - "--path.rootfs=/host"
         - "--collector.filesystem.mount-points-exclude=^/(sys|proc|dev|host|etc)($$|/)"
       volumes:
         - /:/host:ro,rslave
   ```
   No host port mapping — Prometheus reaches it on the compose network, matching
   the alertmanager service's posture.
2. **`prometheus/prometheus.yml`** — second scrape job:
   ```yaml
     - job_name: node
       static_configs:
         - targets: ['node-exporter:9100']
   ```
3. **Rules** — all `audience: internal` (B2):

```yaml
  - name: host
    rules:
      # The 2026-06-30 outage: Loki with no retention filled the disk and
      # ENOSPC-killed Mongo. 15% is roughly a day of headroom at observed growth.
      - alert: HostDiskSpaceLow
        expr: node_filesystem_avail_bytes{mountpoint="/",fstype!~"tmpfs|overlay|squashfs"} / node_filesystem_size_bytes{mountpoint="/"} < 0.15
        for: 10m
        labels: {severity: warning, audience: internal}
        annotations:
          summary: "Host disk {{ $value | humanizePercentage }} free"
          description: "Check the Loki volume and Docker image/container layers first — that is what filled it on 2026-06-30."

      - alert: HostDiskSpaceCritical
        expr: node_filesystem_avail_bytes{mountpoint="/",fstype!~"tmpfs|overlay|squashfs"} / node_filesystem_size_bytes{mountpoint="/"} < 0.07
        for: 5m
        labels: {severity: critical, audience: internal}
        annotations:
          summary: "Host disk {{ $value | humanizePercentage }} free — Mongo will be killed on ENOSPC"

      - alert: HostMemoryLow
        expr: node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes < 0.10
        for: 10m
        labels: {severity: warning, audience: internal}
        annotations:
          summary: "Host memory {{ $value | humanizePercentage }} available"

      - alert: HostCpuHigh
        expr: 1 - avg by (instance) (rate(node_cpu_seconds_total{mode="idle"}[5m])) > 0.90
        for: 15m
        labels: {severity: warning, audience: internal}
        annotations:
          summary: "Host CPU at {{ $value | humanizePercentage }} for 15 minutes"

      - alert: NodeExporterDown
        expr: up{job="node"} == 0
        for: 5m
        labels: {severity: warning, audience: internal}
        annotations:
          summary: "node-exporter is not scrapeable — host CPU/RAM/disk alerting is blind"
```

Note the existing `inhibit_rules` entry has `equal: ['job']`
(`alertmanager.yml:51`), so `BotManagerDown` (job `bot-manager`) correctly does
**not** suppress `job="node"` alerts. That is the desired behaviour: when the app
is down, host disk/RAM alerts are the most likely explanation and must stay
visible.

**Verify:** `curl -s localhost:9090/api/v1/targets | grep -o '"job":"node"'` and
`up{job="node"} == 1` in the Prometheus UI.

---

### Phase 6 — Out-of-band app-down delivery, and app-restarted

Phase 0 question 1 is **answered: AD-V8 is dead, this phase implements AD-V8b.**
*(Steps 1–3 amended 2026-08-17 — they previously specified the query-string
static receiver, which the spike falsified. See
`docs/reviews/VIPTALK_ALERTING_V2/spike.md`.)*

1. **A `viptalk-shim` container** (AD-V8b) — a ~30-line single-file service in
   `docker-compose.yml`, no shared code with bot-manager, no Mongo, no JVM. It
   accepts an Alertmanager `webhook_configs` POST and re-emits it to
   `${VIPTALK_BASE_URL}/v1/bot/${VIPTALK_BOT_TOKEN}/sendMessage` as
   `{"text": …, "roomIds": [ … ]}` — a **JSON→JSON field remap** (the spike
   proved VipTalk accepts a JSON body), so no form-encoding library is needed.
   The token and the room list are the shim's own environment variables, read
   from `secrets.env` through the `.env` `deploy.sh` already generates.
   The text is a fixed string held by the shim.
   No host port mapping — Alertmanager reaches it on the compose network.
   Not started when `VIPTALK_DOWN_ROOM_IDS` is empty (AD-V7 / AD-V15 — non-prod
   instances must not push customer notices).
2. **`secrets.env.example`** — one new variable (`VIPTALK_CUSTOMER_NOTICES_ENABLED`
   already landed in Phase 2):
   ```
   # Rooms that receive the out-of-band "application is down" notice, delivered
   # by the viptalk-shim container (never via the app). Space-separated Matrix
   # room IDs. Empty on staging/loadtest ⇒ the shim and its route are not wired.
   VIPTALK_DOWN_ROOM_IDS=
   ```
3. **`alertmanager/alertmanager.yml`** — a route that fans `BotManagerDown` out
   to both receivers. Because the token now lives in the shim's environment and
   not in this file, **it stays a plain committed file**: the `.yml.template` +
   `envsubst` render the original step 1 required is no longer needed, since
   there is no secret left to keep out of git.
   ```yaml
   route:
     receiver: viptalk
     group_by: ['alertname', 'product', 'environmentId', 'audience']
     group_wait: 30s
     group_interval: 5m
     repeat_interval: 4h
     routes:
       # Delivered by the shim, so it survives the app being dead.
       # continue: true ⇒ the alert ALSO goes to the normal app webhook below,
       # which is what eventually delivers the RESOLVED half (AD-V9).
       - matchers: [alertname = "BotManagerDown"]
         receiver: viptalk-static-down
         continue: true

   receivers:
     - name: viptalk
       webhook_configs:
         - url: http://bot-manager:8085/api/v1/alerts/alertmanager
           send_resolved: true
           max_alerts: 0
     - name: viptalk-static-down
       webhook_configs:
         # send_resolved:false — the shim's text is static and cannot say
         # "recovered"; the recovery message comes from the app receiver (AD-V9).
         - url: http://viptalk-shim:8080/alertmanager
           send_resolved: false
   ```
4. **`prometheus/alerts.yml`** — `BotManagerDown` gains
   `audience: both` and a `public_summary` annotation:
   ```yaml
        annotations:
          summary: "bot-manager is not scrapeable"
          description: "Prometheus has failed to scrape bot-manager for 2 minutes — the app is down, wedged, or the container was replaced."
          public_summary: "ALERT! Bot Management application is experiencing issues, backend team is aware and will deliver fixes soon."
   ```
   plus the new restart rule (AD-V10):
   ```yaml
      - alert: BotManagerRestarted
        expr: changes(process_start_time_seconds{job="bot-manager"}[15m]) > 0
        labels: {severity: warning, audience: internal}
        annotations:
          summary: "bot-manager restarted (JVM start time changed)"
          description: "An unplanned restart loses all in-memory bot runtimes; groups must be re-started. If this repeats, check for OOM/native-thread exhaustion."
   ```
5. **`AlertRulesAudienceTest`** (AD-V4) — parses `../prometheus/alerts.yml` with
   SnakeYAML (already on the classpath via Spring Boot), asserts every rule has a
   non-blank `labels.audience` in `{internal, product, both}`, and that every
   rule with `audience: both` also has a non-blank `annotations.public_summary`.
   Skip via assumption if the file is absent, so the test cannot break a
   module-only build.

**Verify:** stop bot-manager (`docker compose stop bot-manager`), wait ≥ 2 min +
`group_wait`, confirm the outage notice appears in the ops **and** TIP rooms;
start it again and confirm the RESOLVED message follows from the app path.

---

## Implementation Notes / Concerns

1. **The `unless` in `GameNoRounds` is not interchangeable with `and`.** A game
   that has never delivered a round has no `bot_messages_total` series, so `and`
   silently never fires — and that is the single most important case (tx7,
   "0 sessions ever"). Both sides must produce the **identical** label set after
   `sum by(...)`, which is why Phase 1 has to put `environmentId`, `gameType` and
   `product` on `bots_by_game_status`.
2. **`bots_by_game_status{status="CONNECTION_AUTHENTICATED"}` is the right left
   operand, not `STARTED`.** `BotStatus.STARTED` is set before the WS auth
   completes; `CONNECTION_AUTHENTICATED` is stamped in `onSubscribe`
   (`BettingMiniGameBot.java:379`), i.e. it means "the server acknowledged our
   subscription" — precisely "this game is supposed to be sending us rounds".
3. **Adding a label re-identifies every series.** After the Phase 1 deploy,
   Grafana panels using `sum by(...)` or `metric{label="x"}` keep working; any
   panel using `metric` with an exact full-label match would break. Grep
   `grafana/provisioning/dashboards/bots.json` before deploying — the expressions
   there (`bots_managed`, `ws_connections_open`, `bots_dead_currently`,
   `groups_dead_currently` at lines 139/197/661/727) are all on the *unlabelled*
   aggregates, which Phase 1 does not touch.
4. **`Game.productCode` may be null on older documents.** MDC and the gauge rows
   must tolerate it (`addIfPresent` already skips nulls). A null product means an
   `audience: product` alert falls to the `environmentId` → Mongo hop, and then to
   AD-V5's drop. Consider a one-off Mongo backfill; not a blocker.
5. **`AlertServiceTest` will legitimately change.** The existing assertion that a
   product with no room falls back to the ops room is the behaviour Phase 2
   deletes. That is not a regression — but Dev must update the test rather than
   preserve the old expectation.
6. **AD-3 tripwires in Phase 2.** `AlertRouter` must be total: no exception on
   null product, null audience, null publicSummary, blank ops room, or an empty
   routed list. `AlertService` already never throws; keep it that way. The new
   `alert_dispatch_total` counter is registered lazily by the Micrometer builder
   and cannot fail the context.
7. **Login-failure rules are only meaningful when logins happen.** `bot_login_total`
   moves at group start and periodic re-auth only. `EnvironmentLoginFailing` will
   be silent on a steady-state fleet — that is expected and is why
   `EnvironmentAuthDown` (built on the continuous `verifytoken` counter) is the
   primary auth signal and `EnvironmentLoginFailing` is the corroborating one.
8. **node-exporter needs `pid: host` and a `rslave` bind mount.** Neither needs
   root beyond what `docker compose` already has; there is still no passwordless
   sudo on Bot-1 and none is required. The `--collector.filesystem.mount-points-exclude`
   regex uses `$$` in compose YAML to escape a literal `$`.
9. **`repeat_interval: 4h` × 3 instances × shared rooms.** Worst case for a
   persistent product-wide outage is 3 messages per 4 h per room, each stamped
   with a different `instance-label`. Acceptable; if it proves noisy, raise
   `repeat_interval` on the non-prod instances rather than adding app-side
   throttling (AD-6).
10. **Do not add in-app throttling for the low-balance alert.** A group hovering
    at the 10% line will flap; the correct fix is `for: 5m` plus Alertmanager's
    `group_interval`, not a state machine in `InfoGaugeRefresher` (AD-6).
11. **Build:** `JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home mvn clean install -DskipTests=false -Dmaven.javadoc.skip=true`.

---

## Open Items

- **RESOLVED (Phase 0, 2026-08-17):** VipTalk **ignores** query parameters on
  `sendMessage` (400 `M_CANNOT_SEND_EMPTY_MESSAGE`). **AD-V8 is dead; Phase 6
  uses AD-V8b (shim container).** Unplanned finding: the API *does* accept an
  `application/json` body (`{"text":…,"roomIds":[…]}` → 200), so the shim is a
  JSON→JSON field remap rather than a JSON→form re-encode. `VipTalkClient` stays
  on form-urlencoded — verified working, no reason to churn it. Full results:
  `docs/reviews/VIPTALK_ALERTING_V2/spike.md`.
- **RESOLVED (user, 2026-08-17):** the swap in Phase 3 is **confirmed** —
  `BotGroupDead`, `DeadBotRatioHigh` and `WsConnectionsBelowFleet` are retired in
  favour of the per-environment, product-labelled rules. Do not keep both.
- **RESOLVED (user, 2026-08-17):** ops room ID is
  `!WfhvMgIVQUzZUoqDmm:matrix-uat.viptalk.org` — verified live (HTTP 200).
  It is **config, not code** (AD-V14): set `VIPTALK_OPS_ROOM_ID` in `secrets.env`
  on each host. Not committed; `secrets.env.example` stays a blank template.
- **Needs user decision before Phase 6:** the exact customer-facing wording. The
  plan uses the user's own example verbatim. It is a single string in
  `alerts.yml` (`public_summary`) and in `secrets.env` (`VIPTALK_DOWN_TEXT`), so
  it is trivially changeable — but it should be reviewed by whoever owns the
  product-room relationship before the first prod send.
- **9 of 10 products have no room** — P_066/P_097/P_098/P_103/P_105/P_114/P_118/
  P_119/P_222. Per the amended AD-V5 their `audience: product` alerts fall back
  to the **ops room, tagged as misrouted**, rather than being dropped. Watch
  `alert_dispatch_total{outcome="misrouted"}` — it measures how much is still
  landing in the wrong room. The fix remains: create the rooms, fill in
  `ProductCode.vipTalkRoomId`, and restore AD-V5-orig once all 10 are wired.
  P_097/BOM is the priority, being the product under active development.
- **Deferred: `Environment.alertOnLowBalance` is a dead field**
  (`Environment.java:65`) — mapped, seeded, read by nothing. It could become a
  per-environment opt-out for `GroupBalanceLow`, but AD-V13 already scopes the
  metric to non-auto-deposit groups, which is the sharper filter. Either wire it
  or delete it in a separate pass.
- **Out of scope: RTP-anomaly alerting.** Listed in CLAUDE.md's Health
  Diagnostics table and buildable from `bot_winnings_total / bot_bet_amount_total`
  once Phase 1 lands the `product` label, but it is a different class of alert
  (economic, not availability) and needs its own thresholds.
- **Out of scope: authentication on `/api/v1/alerts/**`.** Unchanged from
  VIPTALK_ALERTING's out-of-scope list. Note the endpoint can now write into
  product rooms with customer-facing wording, which strengthens the case.
- **Out of scope: cAdvisor / per-container resource metrics.** node-exporter
  covers the host; per-container attribution would be the next step if a host
  alert ever needs to name the guilty container.

---

## Verification

Run on the staging host (Bot-1) after each phase is deployed. Host ports:
bot-manager `8080`, Prometheus `9090`, Grafana `3000`; Alertmanager and
node-exporter are not host-exposed and are reached via `docker compose exec`.

### Universal smoke (every phase)

```bash
curl -fsS http://localhost:8080/actuator/health | jq -r .status
```
Expect `UP`.

```bash
docker compose ps --format '{{.Service}} {{.State}}'
```
Expect every service `running` — in particular `grafana`, `prometheus`, `loki`
(one compose project; a bot redeploy takes the observability stack down too).

### Phase 1 — product labels

```bash
curl -fsS http://localhost:8080/actuator/prometheus | grep -c 'product="'
```
Expect a count `> 0` (0 means MDC is not carrying the product).

```bash
curl -fsS http://localhost:8080/actuator/prometheus | grep '^bots_by_game_status'
```
Expect every line to carry `environmentId=`, `product=` and `gameType=`.

```bash
curl -fsS http://localhost:8080/actuator/prometheus | grep -E '^(bots_managed_by_env|ws_connections_open_by_env)'
```
Expect at least one series of each while a group is running.

```bash
curl -fsS 'http://localhost:9090/api/v1/query?query=sum(bots_managed_by_env)-bots_managed' | jq -r '.data.result[0].value[1]'
```
Expect `0` — the per-env gauge must sum to the fleet gauge.

```bash
curl -fsS http://localhost:8080/actuator/prometheus | grep -E '^(bots_managed|ws_connections_open|bots_dead_currently|groups_dead_currently) '
```
Expect these four to carry **no** `product` / `environmentId` label (AD-V2).

### Phase 2 — audience routing

```bash
curl -fsS -X POST http://localhost:8080/api/v1/alerts/alertmanager \
  -H 'Content-Type: application/json' -d '{
    "version":"4","status":"firing","alerts":[{"status":"firing",
    "labels":{"alertname":"RoutingProbeInternal","severity":"warning","audience":"internal"},
    "annotations":{"summary":"routing probe — internal"}}]}' | jq .
```
Expect HTTP 200 and the message in the **ops room only**.

```bash
curl -fsS -X POST http://localhost:8080/api/v1/alerts/alertmanager \
  -H 'Content-Type: application/json' -d '{
    "version":"4","status":"firing","alerts":[{"status":"firing",
    "labels":{"alertname":"RoutingProbeProduct","severity":"warning","audience":"product","product":"116"},
    "annotations":{"summary":"routing probe — product"}}]}' | jq .
```
Expect HTTP 200 and the message in the **TIP room only** — explicitly **not** in
the ops room.

```bash
curl -fsS -X POST http://localhost:8080/api/v1/alerts/alertmanager \
  -H 'Content-Type: application/json' -d '{
    "version":"4","status":"firing","alerts":[{"status":"firing",
    "labels":{"alertname":"RoutingProbeUnwired","severity":"warning","audience":"product","product":"097"},
    "annotations":{"summary":"routing probe — unwired product"}}]}' | jq -r .outcome
```
*(Amended 2026-08-17 — this block previously expected the AD-V5-orig drop
behaviour, which the AD-V5 amendment above superseded.)*
Expect `SENT`, and the message in the **ops room only**, prefixed with
`↪️ MISROUTED — no VipTalk room for BOM (097); delivered here instead of a
product room.` (P_097 has no room, amended AD-V5). Expect it **not** to appear
in the TIP room.

```bash
curl -fsS http://localhost:8080/actuator/prometheus | grep '^alert_dispatch_total'
```
Expect `outcome="sent",reason="ok"` series and one
`outcome="misrouted",reason="no_room",product="097"` series with value `≥ 1`.
`outcome="dropped"` should be absent unless the ops room is unconfigured
(`reason="no_ops_room"`) or a `both` alert was gated
(`reason="customer_notices_disabled"` / `"no_public_summary"`).

### Phase 3 — product-routed rules

```bash
docker compose exec prometheus promtool check rules /etc/prometheus/alerts.yml
```
Expect `SUCCESS` and a rule count matching the file.

```bash
curl -fsS 'http://localhost:9090/api/v1/rules' \
  | jq -r '.data.groups[].rules[] | select(.type=="alerting") | "\(.name) \(.labels.audience // "MISSING")"'
```
Expect **no** line ending in `MISSING`, and every `Environment*` / `Game*` /
`Group*` rule labelled `product`, every `Host*` / `Jvm*` / `BotManagerRestarted`
labelled `internal`, `BotManagerDown` labelled `both`.

```bash
curl -fsS 'http://localhost:9090/api/v1/query?query=bots_by_game_status' \
  | jq -r '.data.result[0].metric | keys | join(",")'
```
Expect the key list to include `environmentId,gameId,gameName,gameType,product,status`.

Then, to exercise `GameNoRounds` end to end: stop a running group's game
server-side (or point the group at a dead game), wait 20 minutes, and expect a
message matching `No rounds on game .* for 20 minutes` **in the TIP room**.

### Phase 4 — low balance

```bash
curl -fsS http://localhost:8080/actuator/prometheus | grep '^group_balance_ratio'
```
Expect exactly one series per running group with `autoDepositEnabled=false` and
at least one connected bot; expect **zero** series for auto-deposit groups.

```bash
curl -fsS 'http://localhost:9090/api/v1/query?query=group_balance_ratio' \
  | jq -r '.data.result[] | "\(.metric.groupName) \(.value[1])"'
```
Expect each value in `(0, ~1.0]` — a value of exactly `0` for a group with
connected bots means the balance read is broken, not that the group is broke.

### Phase 5 — host metrics

```bash
curl -fsS 'http://localhost:9090/api/v1/query?query=up{job="node"}' | jq -r '.data.result[0].value[1]'
```
Expect `1`.

```bash
curl -fsS 'http://localhost:9090/api/v1/query?query=node_filesystem_avail_bytes{mountpoint="/"}/node_filesystem_size_bytes{mountpoint="/"}' \
  | jq -r '.data.result[0].value[1]'
```
Expect a value in `(0,1)`, and confirm it agrees with `df -h /` on the host to
within a percent. A missing result means the `rootfs` mount is wrong.

```bash
curl -fsS 'http://localhost:9090/api/v1/query?query=node_memory_MemTotal_bytes' | jq -r '.data.result[0].value[1]'
```
Expect the host's real RAM (matches `free -b | awk "/Mem:/{print \$2}"`), not a
container limit.

### Phase 6 — app-down out-of-band path

```bash
docker compose exec alertmanager amtool --alertmanager.url=http://localhost:9093 config show \
  | grep -c 'viptalk-static-down'
```
Expect `≥ 1` on prod (and `0` on staging when `VIPTALK_DOWN_ROOM_IDS` is empty).

```bash
grep -ci 'viptalk.org\|sendMessage\|token' alertmanager/alertmanager.yml
```
*(Amended 2026-08-17 — this previously checked an `envsubst`-rendered,
gitignored `alertmanager.yml`, which AD-V8b makes unnecessary.)*
Expect `0`: under AD-V8b the file only ever names in-network receivers
(`bot-manager`, `viptalk-shim`), so it holds no secret, stays committed, and
`git status --porcelain alertmanager/alertmanager.yml` prints nothing because it
is unmodified. The token must appear only in `secrets.env` on the host and in
the shim container's environment:

```bash
docker compose exec viptalk-shim printenv VIPTALK_BOT_TOKEN | head -c 4
```
Expect a non-empty prefix on prod.

```bash
docker compose stop bot-manager && sleep 180
```
Expect a message matching
`^ALERT! Bot Management application is experiencing issues` in the **TIP room**
and a technical `bot-manager is not scrapeable` message in the **ops room**,
both delivered while bot-manager is down.

```bash
docker compose start bot-manager && sleep 120
curl -fsS http://localhost:8080/actuator/health | jq -r .status
```
Expect `UP`, followed within `group_interval` by a `✅ RESOLVED` message for
`BotManagerDown` in the ops room (delivered by the app path, AD-V9), and a
`BotManagerRestarted` message in the ops room only.

**Also expect nothing further in the TIP room** — see the Phase 6 open item in
the amendment below; if a second customer-register message arrives on recovery,
that is the AD-V6/AD-V9 interaction and must be settled before the first prod
send.

---

## Amendment — 2026-08-17

Issued by the Compliance Architect while reviewing Phases 1 and 2
(`docs/reviews/VIPTALK_ALERTING_V2/compliance.md`). The implementation was
accepted as faithful; these are corrections to the **plan**, not to the code.

1. **Phase 1 step 1 — wrong module path for `BotMdc`.** The plan located it in
   `bot-engine`; it is in `bot-api` (it is a contract-module class shared by
   `bot-engine` and `bot-app`). Cosmetic; the implementation used the real path.

2. **Phase 2 verification block — stale, contradicted the amended AD-V5.**
   AD-V5 was amended (by user decision, earlier the same day) from "drop an
   unroutable product alert" to "deliver it to the ops room tagged
   `↪️ MISROUTED`". The verification block further down the document was not
   updated with it and still expected `SKIPPED` /
   `outcome="dropped",reason="no_room"`. The implementation correctly followed
   the amended AD-V5, so the block would have failed a faithful build. It now
   expects `SENT` plus `outcome="misrouted",reason="no_room",product="097"`.

3. **AD-V8 is dead; Phase 6 rewritten onto AD-V8b.** Phase 0 falsified the
   conditional AD-V8 was written under: VipTalk **ignores query parameters** on
   `sendMessage` (it echoes the query string back in the error `path` but still
   answers `400 M_CANNOT_SEND_EMPTY_MESSAGE`). The Open Items already recorded
   this, but the Phase 6 *steps* still specified the query-string static
   receiver, so Phase 6 as written would have built the dead approach. Steps 1–3
   and the Phase 6 verification block now describe AD-V8b's shim container.
   Two consequences worth stating explicitly, both from the spike's unplanned
   finding that VipTalk **does** accept a JSON body:
   - the shim is a **JSON→JSON field remap**, not a JSON→form-urlencoded
     re-encode, so it is cheaper than AD-V8b originally costed;
   - the bot token moves into the shim's environment, which means
     `alertmanager.yml` holds no secret and the `.yml.template` + `envsubst`
     render the original Phase 6 step 1 mandated is **no longer needed**.
   `VipTalkClient` stays on form-urlencoded, per the spike.

### New open item for Phase 6 — the resolved half of an `audience: both` alert

Not a defect in Phases 1–2; a decision AD-V6 and AD-V9 together leave
unspecified, surfaced by reading the delivered code.

AD-V9 routes `BotManagerDown` to the app webhook with `send_resolved: true` so
the app delivers the recovery notice. That resolved payload still carries
`audience: both` and the rule's `public_summary` annotation. `AlertRouter.route`
branches on audience only — it has no notion of firing-vs-resolved — and
`AlertMessageFormatter.formatCustomer` renders marker + product + instance and
then the `public_summary` **verbatim**. So on prod, with
`viptalk.customer-notices-enabled=true`, recovery would put

```
✅ TIP (116) · prod
ALERT! Bot Management application is experiencing issues, backend team is aware and will deliver fixes soon.
```

into the product room: a green tick above text that says the application is
broken. The technical register is unaffected (it renders the RESOLVED label and
the alert body, which read correctly).

Three shapes are available, all cheap, none obviously right — Architect-1's call
in Phase 6, not the Compliance Architect's:

- suppress the customer copy on `AlertSeverity.RESOLVED` (product rooms hear the
  outage but never the recovery);
- add a `public_summary_resolved` annotation and render it instead;
- word `public_summary` so it survives both markers.

This is only reachable once `customer-notices-enabled` is turned on, which is
prod-only and gated behind Phase 6, so nothing is broken today.

*(Closed by the 2026-08-18 amendment below: the second shape was chosen —
`public_resolved_summary`, with a configured `viptalk.public-resolved-summary`
fallback — and is implemented and pinned by `AlertRulesAudienceTest`.)*

---

## Amendment — 2026-08-18

Issued by the Compliance Architect while reviewing Phases 3, 4, 5 and 6
(`docs/reviews/VIPTALK_ALERTING_V2/compliance.md`). The implementation was again
accepted as faithful; everything below is a correction to the **plan**. Six of
the seven items are places where the plan asserted something about an external
system (PromQL semantics, Alertmanager routing semantics, node-exporter flag
semantics) that is not true, or where two of its own steps contradict each other.

### 1. Phase 3 rule block — the `and` operand order is defective as written

The plan spells `EnvironmentSocketDown` / `EnvironmentSocketDegraded` as

```promql
bots_managed_by_env > 0
and (ws_connections_open_by_env / bots_managed_by_env) < 0.5
```

`a and b` returns **a's** samples, with **a's** values. So `$value` would be the
managed-bot count, not the ratio, and the summary
(`{{ $value | humanizePercentage }} of bots hold a WebSocket`) would render
`2000%` for a 20-bot environment. The guard must be the *right* operand:

```promql
(ws_connections_open_by_env / bots_managed_by_env) < 0.5
and bots_managed_by_env > 0
```

Vector matching is unaffected — `and` matches on all labels except `__name__`,
and the division preserves the full label set. The delivered
`prometheus/alerts.yml` uses the corrected form.

### 2. Phase 3 step 2 promised three replacements and specified one

Step 2 retires `BotGroupDead`, `DeadBotRatioHigh` and `WsConnectionsBelowFleet`
"and replace[s] them with per-environment equivalents", but the rule block that
follows only supplies the socket replacement. Taken literally, Phase 3 deletes
all dead-bot and dead-group alerting and puts nothing back — a regression against
the user's own standing requirement that a DEAD group is an operator-actionable
event.

Two rules therefore join Phase 3's block:

```yaml
      # Replaces DeadBotRatioHigh. BOTH operands are aggregated: Prometheus stamps
      # `job` and `instance` on every scraped series, so dividing a `sum by (…)`
      # result by a RAW series compares a 2-label set against a 4-label set and
      # matches nothing.
      - alert: EnvironmentDeadBotRatioHigh
        expr: |
          (
            sum by (product, environmentId) (bots_by_env_status{status="DEAD"})
            / clamp_min(sum by (product, environmentId) (bots_managed_by_env), 1)
          ) > 0.2
          and sum by (product, environmentId) (bots_managed_by_env) > 0
        for: 10m
        labels: {severity: warning, audience: product}
```

and, from Phase 4 (see item 3), `EnvironmentGroupDead`.

### 3. Phase 4 gains `groups_dead_by_env` and `EnvironmentGroupDead`

`EnvironmentDeadBotRatioHigh` alone does **not** pay back what retiring
`BotGroupDead` cost: a 5-bot group dying inside a 100-bot environment moves that
ratio by 0.05 and never trips 0.2. Phase 4 therefore also registers

- `groups_dead_by_env{environmentId, product}` — a new MultiGauge in
  `InfoGaugeRefresher`, fed by `BotGroupBehaviorService.countDeadGroupsByEnv()`,
  using the **same** `groupDeadSince != null` predicate as
  `countGroupsDeadCurrently()`, so `sum(groups_dead_by_env) == groups_dead_currently`;

- `EnvironmentGroupDead: groups_dead_by_env > 0`, `for: 5m`,
  `{severity: critical, audience: product}`.

**This is consistent with AD-V2, not an exception to it.** AD-V2 says the six
fleet aggregates *stay unlabelled* and that rules built on them cannot be
product-routed. `groups_dead_by_env` is a **new, separate, labelled sibling**;
`groups_dead_currently` and the rest of `ObservabilityConfig` are untouched, and
the new name is on `BotMdcTagsMeterFilter.AGGREGATE_METER_NAMES` like the other
refresher-thread gauges. AD-V2's own remedy for a retired fleet rule is exactly
this: "replaced by their per-environment equivalents".

### 4. Phase 5 — the mount-point exclusion regex replaces node-exporter's defaults

`--collector.filesystem.mount-points-exclude` **overrides** the upstream default
wholesale; it does not extend it. The plan's

```
^/(sys|proc|dev|host|etc)($$|/)
```

therefore silently re-admits `/var/lib/docker/…`, i.e. one `node_filesystem_*`
series set per container layer — permanently near-full, irrelevant, and the exact
noise the `mountpoint="/"` selector exists to avoid. The correct form restates
the v1.8.2 defaults **and** adds our bind target:

```
^/(dev|etc|host|proc|sys|run/credentials/.+|var/lib/docker/.+|var/lib/kubelet/pods/.+)($$|/)
```

Also: the `fstype!~"tmpfs|overlay|squashfs"` selector belongs on **both** sides of
the division, not just the numerator. Asymmetry does not break the current
one-series-per-mount case, but it makes the pairing depend on a property the rule
does not state; symmetry costs nothing.

### 5. Phase 6 step 3 — the Alertmanager route does **not** reach both receivers

This is the load-bearing correction. The plan's block

```yaml
    routes:
      - matchers: [alertname = "BotManagerDown"]
        receiver: viptalk-static-down
        continue: true
```

does not do what its own comment says. In Alertmanager a child route that
matches **consumes** the alert, and the parent's own receiver applies **only when
no child matched**; `continue: true` continues to the next *sibling*, not up to
the parent. With a single child there is no next sibling, so `BotManagerDown`
reaches the shim and **never** reaches `bot-manager` — which kills the RESOLVED
half, i.e. the entire purpose of pairing the two receivers in AD-V9. The correct
spelling is two sibling routes:

```yaml
    routes:
      - matchers: [alertname = "BotManagerDown"]
        receiver: viptalk-static-down
        continue: true
      - matchers: [alertname = "BotManagerDown"]
        receiver: viptalk
```

Pinned in the build by `AlertmanagerRoutingTest`, which walks the tree with
Alertmanager's own consume/continue semantics rather than asserting on the YAML
shape.

### 6. Phase 6 step 1 — the shim runs everywhere, self-disabling, and carries two registers

Step 1 as written contradicts step 3 and contradicts Phase 6's own verification:

- *Contradicts step 3.* "Not started when `VIPTALK_DOWN_ROOM_IDS` is empty", but
  step 3 keeps `alertmanager.yml` a **committed** file whose
  `viptalk-static-down` receiver is present on every instance. An absent
  container therefore leaves Alertmanager retrying a connection-refused webhook
  during the very outage it is reporting.
- *Contradicts the verification block.* That block expects "a technical
  `bot-manager is not scrapeable` message in the **ops room** … delivered while
  bot-manager is down". A shim holding one fixed customer string and posting only
  to `VIPTALK_DOWN_ROOM_IDS` cannot produce that; the technical half would arrive
  only *after* recovery, whenever Alertmanager's retry of the app webhook finally
  succeeds.

Corrected shape, as delivered:

- the shim runs on **every** instance and self-disables on blank config
  (no token, or no rooms) — answering `200` so Alertmanager does not retry
  something that can never succeed;
- it carries **two** fixed registers, mirroring AD-V6: `technical` →
  `VIPTALK_OPS_ROOM_ID`, `customer` → `VIPTALK_DOWN_ROOM_IDS`. A register with no
  rooms is simply not sent, so AD-V7 still holds off prod: staging has no product
  rooms wired, so staging emits only the ops-room technical copy — which is
  useful, and is what the verification block asks for.

**Residual, and deliberately left as an open item, not a defect:** AD-V7 says
customer copy is gated by *one* config flag, and the app's gate is
`viptalk.customer-notices-enabled` while the shim's is "is
`VIPTALK_DOWN_ROOM_IDS` non-empty". Two gates for one decision. The plan itself
chose the room-list gate for the shim (step 1), so this is not implementation
drift — but an operator who fills `VIPTALK_DOWN_ROOM_IDS` on staging gets the
customer register from the shim while the app-side flag still says `false`.
Cheapest hardening: have the shim also require
`VIPTALK_CUSTOMER_NOTICES_ENABLED=true` before emitting its customer register.

### 7. Phase 6 verification — `viptalk-static-down` is present everywhere

The block expects
`amtool config show | grep -c 'viptalk-static-down'` to be `0` on staging. Under
AD-V8b `alertmanager.yml` is committed and identical on all three instances, so
that count is `1` everywhere, always. Replace the expectation with:

```bash
docker compose exec alertmanager amtool --alertmanager.url=http://localhost:9093 config show \
  | grep -c 'viptalk-static-down'
```
Expect `≥ 1` on **every** instance. What differs per instance is not the route
but the shim's configuration — check that instead:

```bash
docker compose exec viptalk-shim python -c \
  "import urllib.request,json;print(json.load(urllib.request.urlopen('http://127.0.0.1:8080/health')))"
```
Expect `enabled: true` and `opsRooms: 1` everywhere, `productRooms: 0` off prod
and `productRooms ≥ 1` on prod. (The token is masked in that summary by design.)

### 8. Release ordering — Phase 4 does not build on this branch alone

**Phase 4 has an undeclared prerequisite.** `listGroupBalances()` reads
`BotBehaviorConfig.getDepositAmount()` and `Bot.DEFAULT_DEPOSIT_AMOUNT`, neither
of which exists at the branch tip — both arrive with the separate, still
**uncommitted** DEPOSIT_AMOUNT_CONFIG work. Verified: `git show
HEAD:bot-api/.../BotBehaviorConfig.java` has no `depositAmount` field. A checkout
of this branch without the working tree does not compile.

Two further Phase 2/6 prerequisites are in the same state — uncommitted, and
load-bearing for the deploy rather than the build:

- `deploy.sh`'s `secrets.env` → `.env` merge. Without it **no** VipTalk variable
  reaches any container: the app self-disables and the shim reports
  `enabled: false`, so the whole feature is inert while looking healthy.
- the `.gitignore` entry for `secrets.env`. `secrets.env.example` is committed
  and states that `secrets.env` is gitignored; at the branch tip that is false,
  so a `git add -A` on a host holding a real file would stage
  `VIPTALK_BOT_TOKEN` (against AD-2 / AD-V14).

**Landing order:** DEPOSIT_AMOUNT_CONFIG (or at minimum its `depositAmount` /
`DEFAULT_DEPOSIT_AMOUNT` symbols) → this branch → and `deploy.sh` + `.gitignore`
must land **with or before** the alerting commits, never after.

### 9. New open item — the customer FIRING copy can arrive twice, the second time after recovery

AD-V9 assumes the app receiver's *firing* notification simply "fails (app down,
Alertmanager retries)" and that only its *resolved* notification lands. That is
not guaranteed. Alertmanager retries a failed notification, and
`BotManagerDown` stays firing until Prometheus completes a successful scrape and
re-evaluates — a window of tens of seconds *after* the app is back. A retry that
succeeds inside that window delivers the **firing** payload, which still carries
`audience: both` and `public_summary`. On prod with customer notices enabled the
product room can then see:

1. the shim's customer outage copy (during the outage),
2. the app's customer outage copy (just after recovery — a duplicate that reads
   as a *new* outage),
3. the app's customer recovery copy (`public_resolved_summary`).

Nothing in Phases 1–6 dedupes (2) against (1): they are two independent delivery
paths, and AD-6 forbids an app-side state machine to merge them. Options, all
cheap, Architect-1's call: suppress the CUSTOMER register for a FIRING
`BotManagerDown` in the app (the shim owns that half by construction); or accept
the duplicate and word `public_summary` so a repeat reads sanely. Not reachable
today — `customer-notices-enabled` is prod-only.
