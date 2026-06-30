# Compliance — OBSERVABILITY Phase 1

Branch: `feat/observability-metrics`
Plan reviewed: `docs/plans/OBSERVABILITY.md` (Section 5 Phase 1, at commit `61b2545`)
Diff reviewed: `git diff a9ccb2d..1d53796`

## Verdict

COMPLIANT

## Per-deliverable

| # | Deliverable | Status | Evidence |
|---|---|---|---|
| 1 | `pom.xml` adds `io.micrometer:micrometer-registry-prometheus` (BOM-managed, no `<version>`) | PRESENT | `pom.xml` lines 110-113 — no version pin |
| 2a | `application.properties`: `prometheus` added to exposure list | PRESENT | `management.endpoints.web.exposure.include=health,info,metrics,loggers,prometheus` |
| 2b | `management.metrics.tags.application=bot-manager` | PRESENT | property added |
| 2c | `management.prometheus.metrics.export.enabled=true` (Spring Boot 3.x property) | PRESENT | property added with explanatory comment |
| 3 | `docker-compose.yml` `prometheus` service: image `prom/prometheus:v2.55.0`, bind-mount config ro, named volume `/prometheus`, port 9090 published, `depends_on: bot-manager`, joined to same network as bot-manager | PRESENT | lines 69-82; default compose network handles DNS for `bot-manager:8085` |
| 4 | `prometheus/prometheus.yml`: scrape target `bot-manager:8085/actuator/prometheus`, scrape interval present | PRESENT | `metrics_path: /actuator/prometheus`, `targets: [bot-manager:8085]`, `scrape_interval: 10s` (global `15s`) |
| 5 | `grafana/provisioning/datasources/prometheus.yml`: prometheus datasource, `isDefault: false` | PRESENT | url `http://prometheus:9090`, `isDefault: false` |
| 6 | `grafana/provisioning/dashboards/dashboards.yml`: provider config only, dashboards deferred to Phase 6 | PRESENT | provider `bot-manager-dashboards`, scans `/etc/grafana/provisioning/dashboards` |
| 7 | `deploy.sh` `mkdir -p` covers new bind-mount source dirs | PRESENT | `mkdir -p logs prometheus grafana/provisioning/dashboards` |

## Architecture Decisions

| AD | Topic | Status | Notes |
|---|---|---|---|
| 1 | Prometheus + Loki, separate datasources | RESPECTED | Existing Loki datasource untouched; new Prometheus datasource with `isDefault: false` so Loki remains default |
| 12 | `/actuator/prometheus` on main port 8085 (not a separate management port) | RESPECTED | Scrape target is `bot-manager:8085/actuator/prometheus`; no `management.server.port` introduced |

AD 10 (MDC-as-tags) and AD 11 (`bot_*` naming) are Phase 2 territory — Phase 1 registers no meters, nothing precludes either decision.

## Scope check

Diff touches exactly the expected files:
- Modified: `pom.xml`, `application.properties`, `docker-compose.yml`, `deploy.sh` (4 expected).
- New: `prometheus/prometheus.yml`, `grafana/provisioning/datasources/prometheus.yml`, `grafana/provisioning/dashboards/dashboards.yml` (3 expected).
- No Java sources, no Dockerfile, no `BotMdc`, no log4j config, no promtail config touched. Clean.

## Phase 1b carry-over check (LOGGING_PIPELINE_FIX)

| Item | Status | Line |
|---|---|---|
| `bot-manager` has `user: "${HOST_UID}:${HOST_GID}"` | INTACT | 18 |
| bot-manager `./logs:/app/logs` bind-mount | INTACT | 29 |
| promtail `./logs:/logs:ro` | INTACT | 48 |
| No `logs-data` top-level volume | INTACT | 84-88 list only `mongo-data`, `loki-data`, `grafana-data`, `prometheus-data` |

## Deviation verdict

| Flagged deviation | Verdict | Reasoning |
|---|---|---|
| Spring Boot version mismatch — CLAUDE.md says 4.0.0, actual `spring-boot-starter-actuator` reports 3.4 → Dev used the 3.x property name `management.prometheus.metrics.export.enabled` | APPROVED | Property name must match the actual runtime, not the docs. CLAUDE.md drift is out of scope for this review. |
| No second `grafana/provisioning/dashboards/` bind-mount added | APPROVED | The existing `./grafana/provisioning:/etc/grafana/provisioning` parent mount covers the new subdir at `/etc/grafana/provisioning/dashboards`, which is exactly the path the new `dashboards.yml` provider scans. A second bind-mount would shadow it. |
| `prometheus` added to grafana's `depends_on` | APPROVED | Harmless ordering hint; ensures Grafana starts after Prometheus is resolvable so datasource health checks pass on first boot. Not required, not contradicted. |

None of the deviations indicate a plan oversight. No plan amendment warranted.

## Plan amendments

None.
