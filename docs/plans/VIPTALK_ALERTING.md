# VIPTALK_ALERTING

Outbound alerting / announcements from bot-manager into **VipTalk** (Matrix-backed
internal messenger), routed **per product** plus fleet-wide broadcast, and wired to
**Prometheus Alertmanager** as a webhook receiver.

Reference implementation read (not depended on): `vingame/beg-common-tools`
`notification-engine`. We take only the VipTalk wire format and the
"channel never throws" discipline — no Mail, no Jira, no YAML channel registry,
no library dependency.

---

## Goal

1. One VipTalk bot, invited into one room per product (P_097, P_098, P_116, …).
2. Alerts land in the room of the product they concern.
3. Announcements (maintenance windows etc.) broadcast to **every** room.
4. Prometheus alerts reach the same rooms without app code per alert type.

---

## Architecture Decisions

**AD-1 — Room IDs live in `ProductCode`, not config.**
One room per product, products change rarely. A `vipTalkRoomId` field on the
existing `ProductCode` enum keeps the mapping in exactly one place and makes
"which products are wired" a compile-time-visible fact. The getter is
`@JsonIgnore`d: `ProductCode` serializes as an object (`@JsonFormat(shape=OBJECT)`)
and is returned by `/api/v1/brand`, so without it the room IDs would leak into a
public unauthenticated response.

**AD-2 — Bot token is config, never code.**
`viptalk.bot-token` (env `VIPTALK_BOT_TOKEN`). A token in the repo is exactly the
mistake `notification-engine` made — its `notification.yml.example` and integration
test both carry a live-looking prod token.

**AD-3 — Alerting can never take the bot manager down.**
`viptalk.enabled=false` by default. Enabled with a blank token → log ERROR and
self-disable at startup rather than failing the context. Send failures return a
result object; nothing propagates to a caller.

**AD-4 — One POST per room-set, not per room.**
The VipTalk API accepts a repeated `roomIds` form parameter, so a broadcast to
all products is a single HTTP request.

**AD-5 — Prometheus routing is by label, resolved server-side.**
Alertmanager posts the standard webhook payload to
`POST /api/v1/alerts/alertmanager`. bot-manager resolves the target room from
(in order) the `product` label → the `environmentId` label (looked up in Mongo,
`Environment.productCode`) → the ops fallback room. Our metrics carry
`environmentId`, not `product`, so the second hop is what makes existing
bot-* alert rules routable without re-tagging every meter.

**AD-6 — Dedup/grouping/repeat is Alertmanager's job, not ours.**
No in-app throttling, no alert state machine. That is the entire reason for
putting Alertmanager in front rather than calling `send()` from app code.

**AD-7 — Instance label in every message.**
Three instances (prod / loadtest / staging) share one artifact and would share
one room. `viptalk.instance-label` is stamped into the message header. Blank →
segment omitted.

**AD-8 — HTTP failure → 502 on the webhook endpoint.**
Alertmanager retries 5xx. Disabled/skipped → 200 (nothing to retry).

---

## Phases

### Phase 1 — VipTalk transport + routing
- `ProductCode.vipTalkRoomId` (+ `@JsonIgnore` getter).
- `infrastructure/notification/VipTalkClient` — form-urlencoded POST to
  `{baseUrl}/v1/bot/{botToken}/sendMessage`, fields `text` + repeated `roomIds`.
- `domain/alert/model/{Alert, AlertSeverity, AlertDispatch}`.
- `domain/alert/service/{AlertRoomRegistry, AlertMessageFormatter, AlertService}`.

**Verify:** unit tests on form-body encoding, disabled/blank-token guard, room
resolution, message formatting.

### Phase 2 — REST surface
- `POST /api/v1/alerts/product/{product}` — one product room.
- `POST /api/v1/alerts/broadcast` — every configured room.
- `POST /api/v1/alerts/alertmanager` — Alertmanager webhook receiver.
- `GET  /api/v1/alerts/rooms` — which products are wired (room IDs masked).

**Verify:** `POST /api/v1/alerts/broadcast` with a real token lands in every room.

### Phase 3 — Prometheus → Alertmanager → VipTalk
- `prometheus/alerts.yml` — starter rules (scrape target down, groups DEAD,
  dead-bot ratio, JVM thread growth).
- `alertmanager/alertmanager.yml` — single `viptalk` webhook receiver,
  `send_resolved: true`.
- `prometheus/prometheus.yml` — `rule_files` + `alerting` blocks.
- `docker-compose.yml` — `alertmanager` service.

**Verify:** stop a scrape target / force a rule → message appears in the ops room
within `group_wait`; resolve → RESOLVED message follows.

---

## Out of scope (deliberate)

- In-app call sites (group DEAD, deploy events). Prometheus already exposes
  `groups_dead_currently`; alerting on the metric keeps AD-6 intact. Add direct
  `AlertService` calls only for events with no metric behind them.
- Auth on `/api/v1/alerts/**`. Same posture as the existing public
  `/api/v1/metrics/**` and `/bot-group/{id}/health`. Tracked with those.
- Per-room templating / rich formatting. VipTalk `sendMessage` takes plain text.
