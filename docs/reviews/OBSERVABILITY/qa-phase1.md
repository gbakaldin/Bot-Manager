# QA — OBSERVABILITY Phase 1

**Verdict:** PASS
**Build:** `mvn test` → 313 tests, 0 failures, 0 errors, 0 skipped (matches 313/0/0/0 baseline).
**Commit reviewed:** `1d53796` on `feat/observability-metrics`.

## Java diff check

`git diff main..1d53796 --stat -- src/main/java/` → empty. No production Java touched.
`git diff main..1d53796 --stat -- src/test/` → empty. No new Java tests expected or written (Phase 1 is infra-only).

## YAML sanity

| File | `yaml.safe_load` | Structure |
|---|---|---|
| `prometheus/prometheus.yml` | OK | `global` + `scrape_configs` with single job `bot-manager` targeting `bot-manager:8085`, path `/actuator/prometheus`, 10s interval. |
| `grafana/provisioning/datasources/prometheus.yml` | OK | `apiVersion: 1`, single datasource pointing at `http://prometheus:9090`, `isDefault: false`. |
| `grafana/provisioning/dashboards/dashboards.yml` | OK | Dashboards provider, scans `/etc/grafana/provisioning/dashboards`, 30s update interval. Empty dir is fine (Phase 6 drops JSON). |
| `docker-compose.yml` | OK | See compose section. |

## Compose sanity (`HOST_UID=… HOST_GID=… docker compose config`)

- `prometheus` service present, image `prom/prometheus:v2.55.0`, port `9090:9090`.
- `prometheus.yml` bind-mount source resolves to `/Users/gleb/IdeaProjects/Bot/prometheus/prometheus.yml` (host repo path), read-only.
- `/prometheus` mounted from named volume `prometheus-data`.
- `prometheus-data` declared at top-level under `volumes:`.
- Grafana service has `depends_on: [loki, prometheus]`. Provisioning bind-mount source `/Users/gleb/IdeaProjects/Bot/grafana/provisioning` → `/etc/grafana/provisioning`.
- `bot-manager` service unchanged: still `user: "${HOST_UID}:${HOST_GID}"`, still bind-mounts `./logs`. No regression from LOGGING_PIPELINE_FIX.
- `logs-data` named volume is absent — no regression.

## `application.properties` sanity

- `management.endpoints.web.exposure.include=health,info,metrics,loggers,prometheus` — `prometheus` appended, other values preserved.
- `management.metrics.tags.application=bot-manager` added with explanatory comment.
- `management.prometheus.metrics.export.enabled=true` added (correct property name for Spring Boot 3.x / Micrometer 1.x; parent POM is `spring-boot-starter-parent:3.4.0`).
- No other properties removed or altered.

## `pom.xml` sanity

- New `<dependency>` for `io.micrometer:micrometer-registry-prometheus`, no `<version>` (BOM-managed via `spring-boot-dependencies`).
- Positioned immediately after `spring-boot-starter-actuator` — sensible.
- No duplicate or conflicting Micrometer dependencies (`grep -n "micrometer" pom.xml` returns the single entry).

## `deploy.sh` sanity

- Phase 1b polish intact: `cd "$(dirname "$(readlink -f "$0")")"` anchor + `.env` write block with `HOST_UID`/`HOST_GID`.
- `mkdir -p` extended to `logs prometheus grafana/provisioning/dashboards` — pre-creates both new bind-mount source dirs in one line.
- Final step still `docker compose up -d` (V2 syntax from prior fix).

## Plan acceptance coverage (Phase 1, plan lines 81–96)

| Item | Status | Notes |
|---|---|---|
| 1. `pom.xml` adds `micrometer-registry-prometheus` | YES | No version, BOM-managed. |
| 2a. `application.properties` exposure list includes `prometheus` | YES | `health,info,metrics,loggers,prometheus`. |
| 2b. `application.properties` sets `management.metrics.tags.application=bot-manager` | YES | Plus the bonus explicit `management.prometheus.metrics.export.enabled=true` guard. |
| 3. `prometheus` service in `docker-compose.yml` scraping `bot-manager:8085/actuator/prometheus` | YES | Compose config confirms target, path, port. |
| 4. Grafana provisioning: `grafana/provisioning/datasources/prometheus.yml` | YES | Plus the dashboards provider (`dashboards.yml`) staged for Phase 6 — additive, not breaking. |
| Live `/actuator/prometheus` returns text format | CAN'T-VERIFY-LOCALLY | Requires running app inside compose; falls to Phase 7 verification. |
| Prometheus scrapes successfully | CAN'T-VERIFY-LOCALLY | Same — Phase 7. |
| Grafana datasource provisioned and reachable | CAN'T-VERIFY-LOCALLY | Same — Phase 7. |

## Concerns / follow-ups

- **Minor (pre-existing, not introduced by Phase 1):** Grafana `provisioning` bind-mount in `docker-compose.yml` line 64 is `./grafana/provisioning:/etc/grafana/provisioning` without `:ro`. The QA checklist said the expected form was `…:ro`. This mount was already non-`:ro` on `main` and Phase 1 did not touch that line, so it's not a regression. Worth noting because Phase 6 will drop dashboard JSON into the same tree; if dashboards should be read-only inside Grafana, lock it down in a future phase.
- **Confirmed bonus, not a concern:** Dev added `grafana/provisioning/dashboards/dashboards.yml` ahead of schedule (plan technically only required the datasource file in Phase 1). The provider scans an empty directory until Phase 6 — harmless.
- **Confirmed bonus, not a concern:** Dev added `management.prometheus.metrics.export.enabled=true` even though it defaults to `true` when the registry is on the classpath. Comment justifies it as a guard against future default changes. Acceptable.
- **No Java tests added.** Phase 1 has zero production-code diff. No regression risk visible from inside `src/main/java`. Phase 2 (bot-side instrumentation) is where Micrometer counters/gauges will need unit coverage.
