# Release — TECH_DEBT_PASS_2

Mode: bot
Branch: chore/tech-debt-pass-2
Image: vingame-bot:latest (built at 2026-07-15T12:56:56+04:00, sha256:7bc7ee8368d2)
Date: 2026-07-15T09:01:22Z

Scope: API/service-internal only — deprecated-code removal, exception typing,
Swagger doc examples, dead-method deletion. NO infra/Grafana/observability
config changes. Staging target Bot-1.

Pre-deploy git guard: source tree clean (only untracked docs — plan + reviews).
No production code, test, plan, or other-review files modified.

## Build

- `mvn clean install`: PASS (~26s, Java 21 openjdk-21.0.2 — 1485 tests, 0 failures/errors/skipped)
- `docker build --no-cache --platform linux/amd64`: PASS (image sha256:7bc7ee8368d2538642c5c485957a1de79d44b7c2c7ed2a161b4b6903560514ab)
- `docker save`: PASS (395839488 bytes)

## Ship

- `sftp put bot.tar`: PASS (remote size 395839488 == local, byte-for-byte)

## Deploy

Pre-deploy state (single Compose project — bot-manager + observability stack):
bot-manager Up 25h (healthy); grafana/loki/mongo/prometheus/promtail all Up 4d.

- `docker compose down`: PASS (all 6 containers + network removed cleanly)
- `docker image rm vingame-bot:latest`: PASS (prior image existed, untagged + deleted)
- `docker load -i bot.tar`: PASS (Loaded image: vingame-bot:latest)
- `docker compose up -d`: PASS (all 6 containers created + started; mongo reached Healthy before bot-manager start, per depends_on)

## Smoke test

- `docker ps` shows healthy: PASS — `bot-java-bot-manager-1  Up About a minute (healthy)`
- Spring Boot ready log: PASS — `08:58:37.446 [main] INFO Starter - Started Starter in 14.844 seconds (process running for 15.943)`
- Auto-start log: PASS — `08:58:36.415 [main] INFO BotGroupBehaviorService - Bot Manager startup complete. 13 bot groups running`
  - Note: the initial `--tail 200/300` grep returned empty because bots log heavily at the production DEBUG default and the startup lines had already scrolled out of the tail window. A full-log grep confirmed both signatures. Bots observed actively betting/spinning across BETTING_MINI / TAI_XIU / SLOT groups (verifytoken 200, balances, spin results) — runtime is live, not merely started.

### Observability stack re-verification (staging caveat — single Compose project)

All five came back Up after the compose bounce:

- bot-java-grafana-1: Up About a minute
- bot-java-prometheus-1: Up About a minute
- bot-java-loki-1: Up About a minute
- bot-java-promtail-1: Up About a minute
- bot-java-mongo-1: Up About a minute (healthy)

Additionally, actuator health reports mongo component UP (maxWireVersion 25).

## Plan verification

From `docs/plans/TECH_DEBT_PASS_2.md` § Verification. Checks run inside the
container against `http://localhost:8085` (mapped app port per Tomcat startup log).
No test data persisted — post-check env list confirms no leaked `smoke` environment.

### Step 1: App comes up healthy
Command: `curl -s -o /dev/null -w '%{http_code}' http://localhost:8085/actuator/health`
Expected: HTTP 200 and body status UP
Actual: HTTP 200; body `{"status":"UP",...mongo UP, ping UP, ssl UP, diskSpace UP}`
Result: PASS

### Step 2: Swagger doc renders the new BOM examples
Command: `curl -s http://localhost:8085/v3/api-docs | grep -c '"097"'`
Expected: count >= 1 (BOM productCode example string appears in OpenAPI JSON)
Actual: `grep -c '"097"'` = 0. HOWEVER the doc IS served (HTTP 200, 37724 bytes)
  and the BOM example the check intends to confirm IS present — 13 occurrences of
  `097`, rendered as `"example":"P_097"` on the productCode param (enum
  `[P_066,P_097,...,P_222]`) and `"example":"097-staging"` on the envId param.
  The plan's grep looked for the bare literal `"097"`, but the served example is
  the fully-qualified `P_097` / `097-staging` form, so the literal substring is
  absent while the underlying documentation change is deployed and visible.
Result: FAIL (by the plan's literal grep) / intent SATISFIED (BOM example present in served OpenAPI)
  — this is a plan-check-literal mismatch, NOT a deployment defect. Recommend the
  plan's check be corrected to `grep -c 'P_097'` (would return >= 1).

### Step 3: Environment API still serves 400 on malformed WS-header create
Command: `curl -s -X POST http://localhost:8085/api/v1/environment/ -H 'Content-Type: application/json' -d '{"name":"smoke","headers":{"foo":"bar"}}'`
Expected: HTTP 400 (WS headers missing Host/Origin → BadRequestException)
Actual: HTTP 400; body `{"type":"Bad request","msg":"WebSocket headers must include both 'Host' and 'Origin' (case-sensitive)."}`
  Confirms `BadRequestException`→400 with the `{type,msg}` envelope end-to-end after the exception migration (Item 4).
Result: PASS

### Step 3b: Normal GET returns 200 (regression control)
Command: `curl -s -o /dev/null -w '%{http_code}' http://localhost:8085/api/v1/environment/`
Expected: HTTP 200
Actual: HTTP 200
Result: PASS
Cleanup: post-check env list grep for `smoke` = 0 — no persisted test data left behind.

## Verdict

PASS

Rationale: Build, ship, deploy, universal smoke, and the observability-stack
re-verification all PASS. Of the plan's verification checks, the two that exercise
runtime behavior (health 200/UP, exception-path 400 + GET 200) PASS. The one FAIL
is Step 2, and it is a literal-grep mismatch in the plan (`"097"` vs the served
`P_097`/`097-staging` example), not a deployment failure — the OpenAPI doc is
served (200) and the intended BOM example is present. This pass carries no new
user-facing runtime behavior beyond the universal smoke; the exception-handler and
environment-controller unit suites (green in the 1485-test build) lock in the HTTP
contract deterministically. Overall release is healthy and shippable.

## Logs

No failure-triggering excerpts required. Reference smoke signatures:

```
08:58:36.415 [main] INFO  BotGroupBehaviorService [//] - Bot Manager startup complete. 13 bot groups running
08:58:37.434 [main] INFO  TomcatWebServer [//] - Tomcat started on port 8085 (http) with context path '/'
08:58:37.446 [main] INFO  Starter [//] - Started Starter in 14.844 seconds (process running for 15.943)
```
