# Grafana Followup — MARTINGALE_STRATEGIES Release

Date: 2026-06-18
Trigger: User reported Grafana UI unreachable via SSH tunnel after the
Martingale bot-manager redeploy (commit `704fffb`).

## TL;DR

- Grafana **was down** after the redeploy. Now **fixed** — restarted, healthy,
  reachable on `localhost:3000` from the host. SSH tunnel works again.
- Prometheus **is still down**. Pre-existing port conflict on `9090` that I
  could not diagnose without sudo. Grafana works without it (Loki datasource
  still functions; only the Phase-2 bot-side metrics dashboards are blind).
- Root cause: the bot-manager and the observability stack share **one
  docker-compose project** (not separate projects as I had assumed). My
  `docker compose down` during the redeploy tore down all six services
  including grafana/prometheus, and Compose's recreate hit a port-binding
  race for `9090`.

## What I found

### Initial state on Bot-1

```
NAMES                    STATUS                       PORTS
bot-java-bot-manager-1   Up About an hour (healthy)   0.0.0.0:8080->8085/tcp
bot-java-grafana-1       Created                      (no ports bound)
bot-java-prometheus-1    Created                      (no ports bound)
bot-java-loki-1          Up About an hour             0.0.0.0:3100->3100/tcp
bot-java-promtail-1      Up About an hour
bot-java-mongo-1         Up About an hour (healthy)   0.0.0.0:27017->27017/tcp
```

Grafana and Prometheus stuck in `Created` (never transitioned to `Running`).

Port check (`netstat -ltn`):
- `:3000` — not listening (Grafana down)
- `:9090` — not listening from userspace perspective
- `:3100` — listening (Loki up)
- `:8080` — listening (bot-manager up)

### Why both were stuck

`docker inspect bot-java-prometheus-1` revealed:

```
ExitCode: 128
Error: driver failed programming external connectivity on endpoint
       bot-java-prometheus-1: Error starting userland proxy:
       listen tcp4 0.0.0.0:9090: bind: address already in use
StartedAt: 0001-01-01T00:00:00Z   (never started)
```

Created timestamp: `2026-06-18T11:27:44Z`. Bot-manager started 3m40s later
at `11:31:24Z`, suggesting Compose ordering retried but Prometheus stayed
stuck.

Grafana stuck in `Created` because `depends_on: [loki, prometheus]` —
Compose refused to start Grafana once Prometheus had failed.

### Compose topology — important discovery

`/home/sgame/bot-java/docker-compose.yml` contains **all six services in
one compose project (`bot-java`)**:

```
services:
  mongo:
  bot-manager:
  loki:
  promtail:
  grafana:
  prometheus:
```

This means:
- The releaser's `docker compose down` step during a bot redeploy
  **takes down the entire observability stack** alongside the bot.
- A clean `docker compose up -d` is supposed to bring it all back, but
  any flake (port race, dependency failure) leaves observability containers
  stranded with no signal to the operator that they didn't come back —
  because the releaser only smoke-tests the bot-manager.
- There is **no separate "infra" compose project** on Bot-1 today. The
  `infra-images.tar.gz` file in `/home/sgame/bot-java/` exists but is for
  loading pre-built images into this same project.

## Fix applied

```bash
# Removed the stuck prometheus + grafana containers
ssh Bot-1 'docker rm bot-java-prometheus-1 bot-java-grafana-1'

# Tried `docker compose up -d prometheus grafana` — still failed on 9090.
# So skipped prometheus and brought up grafana alone (--no-deps):
ssh Bot-1 'cd /home/sgame/bot-java && docker compose up -d --no-deps grafana'
```

### Result

```
NAMES                STATUS              PORTS
bot-java-grafana-1   Up About a minute   0.0.0.0:3000->3000/tcp
```

`curl localhost:3000/api/health` returns:
```json
{"database":"ok","version":"11.4.0","commit":"b58701869e..."}
```

Grafana log confirms a user already logged in (`userId=1 uname=admin
... handler=/api/live/ws`), so the SSH tunnel is alive again.

## Why Prometheus is still down

`docker compose up -d prometheus` keeps failing with:
```
Error starting userland proxy: listen tcp4 0.0.0.0:9090: bind: address
already in use
```

But:
- `netstat -ltn | grep 9090` shows **nothing listening**.
- `curl localhost:9090` returns **Connection refused**, not a hung response.
- No other container in `docker ps -a` claims port 9090.
- No `docker-proxy` process for 9090 visible (`ps -ef | grep docker-proxy`).
- No systemd unit named `prometheus*` (only `node_exporter.service`,
  which owns `:9100`).

The Docker daemon is convinced something owns `:9090` but no unprivileged
view of the system corroborates it. The most likely candidates require
sudo to confirm:

1. **A host-level Prometheus process running as root** that doesn't show
   in unprivileged `netstat -ltn` output (only `netstat -ltnp` with root
   would reveal it). The presence of a systemd `node_exporter.service`
   strongly suggests there is a sibling Prometheus on this host outside
   of the bot-java compose, possibly installed before this project's
   observability stack was added.
2. **Stale iptables/NAT rules** from a prior crashed Prometheus container
   reserving the port at the kernel level. Fixable with a docker daemon
   restart, but that would briefly impact all other containers.

Per the briefing — "don't blindly `docker compose up` the infra stack
without first understanding why it's down" — I stopped here and did not
attempt a docker daemon restart or any privileged action.

## Recommendation for Prometheus

When you next have sudo access on Bot-1, run:

```bash
sudo netstat -ltnp | grep 9090     # identify the real holder
sudo lsof -iTCP:9090 -sTCP:LISTEN  # cross-check
sudo systemctl list-units | grep -i prom  # is there a host-level service?
```

Decision tree:
- If a host-level Prometheus is running and intentional → remove
  `prometheus` from `docker-compose.yml` and point Grafana's datasource at
  the host Prometheus instead.
- If it's a stale iptables rule → `sudo systemctl restart docker` (this
  bounces all containers; coordinate with anyone running bots).
- If it's a forgotten test process → kill it, then
  `docker compose up -d prometheus`.

## Was this caused by the Martingale release?

Partially. The release didn't *break* anything Prometheus-specific — the
underlying 9090 conflict almost certainly existed before. But:

- The release **exposed** it, because `docker compose down` tore down
  the previously-healthy Prometheus container (which must have started
  successfully at some point in the past, possibly when the conflicting
  process wasn't there yet), and the recreate attempt is what failed.
- The release **broke Grafana** as collateral damage, because Grafana's
  `depends_on: prometheus` prevented Compose from starting it when
  Prometheus failed.

This pattern will repeat on every future bot-manager redeploy until
either (a) the 9090 conflict is resolved, or (b) the observability stack
is moved to a separate compose project that the bot redeploy doesn't
touch.

## Proposed release-checklist addition

To prevent silent observability outages on future bot redeploys, add a
post-deploy smoke check that asserts the observability containers are
healthy and their ports are listening. This is one extra `ssh Bot-1`
call after the existing smoke checks.

### Where to add it

The releaser playbook lives in the agent's system prompt (the "Universal
smoke test" section of the Releaser role), not in a checked-in file —
so I cannot edit a "global playbook" file directly. But the per-release
log template in the system prompt has a `## Smoke test` section, and
the same checks should be reflected there.

**Proposed file to add this guidance to:** create
`docs/process/RELEASER_SMOKE_CHECKLIST.md` (new file) capturing the
extended smoke contract. The agent prompt can then reference it. Until
the prompt is updated, every releaser run should manually run the
checks below after the existing smoke phase and record them in
`release.md`.

### Proposed text for the new section

> ### Observability smoke test (post-redeploy)
>
> The bot-manager and the observability stack share one
> `docker-compose.yml` on Bot-1. A bot redeploy tears down all six
> services and recreates them — flakes in the recreate path can leave
> grafana/prometheus stranded in `Created` state without any signal in
> the bot-manager logs.
>
> After the bot-manager smoke checks pass, run:
>
> ```bash
> # 1. All compose services are Up (not Created, not Exited)
> ssh Bot-1 'cd /home/sgame/bot-java && docker compose ps --format "table {{.Service}}\t{{.Status}}"'
> # Expect: every row shows "Up ..." — no "Created", no "Exited"
>
> # 2. Observability ports are bound on the host
> ssh Bot-1 'netstat -ltn 2>/dev/null | grep -E ":3000|:3100|:9090"'
> # Expect: three lines, one each for grafana (3000), loki (3100),
> # prometheus (9090). Missing any one is a FAIL.
>
> # 3. Grafana HTTP healthcheck
> ssh Bot-1 'curl -s -o /dev/null -w "%{http_code}\n" http://localhost:3000/api/health'
> # Expect: 200
> ```
>
> Record each check in `release.md` § Smoke test:
> - `docker compose ps` all Up: PASS | FAIL
> - Observability ports 3000/3100/9090 bound: PASS | FAIL
> - Grafana `/api/health` returns 200: PASS | FAIL
>
> If any of these fail, do **not** auto-restart anything. Capture
> `docker logs --tail 100 <failed-container>` into the release log
> and stop — the same compose project drives the bot, and a blind
> recreate could disrupt it.

### Optional follow-up: split the compose projects

The deeper fix is structural: move the observability stack into a
separate compose project under `/home/sgame/observability/` (or similar)
with its own `docker-compose.yml`. Then bot redeploys only touch the bot
project, and the observability stack is independently lifecycle-managed.
This eliminates the entire class of "bot redeploy broke Grafana"
incidents. Out of scope for this followup — flagging as a planning item.

## Final state on Bot-1

```
NAMES                    STATUS                       PORTS
bot-java-grafana-1       Up                           0.0.0.0:3000->3000/tcp
bot-java-bot-manager-1   Up About an hour (healthy)   0.0.0.0:8080->8085/tcp
bot-java-loki-1          Up About an hour             0.0.0.0:3100->3100/tcp
bot-java-mongo-1         Up About an hour (healthy)   0.0.0.0:27017->27017/tcp
bot-java-promtail-1      Up About an hour
```

Prometheus: not running. Grafana's Prometheus datasource will be marked
unhealthy in the UI until it's restored, but Loki datasource (logs) is
fully functional.

## Verdict

- User-reported issue (Grafana unreachable): **FIXED**.
- Pre-existing port-9090 conflict for Prometheus: **needs operator with
  sudo** to identify the real holder. Documented for followup.
- Smoke checklist addition: **proposed above**.
