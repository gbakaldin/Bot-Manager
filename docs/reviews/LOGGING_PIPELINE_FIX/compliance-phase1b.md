# Compliance — LOGGING_PIPELINE_FIX Phase 1b

Branch: `feat/logging-pipeline-fix`
Plan reviewed: `docs/plans/LOGGING_PIPELINE_FIX.md`, Section 5 Phase 1b
Diff reviewed: `git diff e0da939..6ee86f0`

## Verdict

COMPLIANT (PASS)

## Per-deliverable

| # | Deliverable | Status | Notes |
|---|---|---|---|
| 1 | `docker-compose.yml`: add `user: "${HOST_UID}:${HOST_GID}"` to bot-manager only | PRESENT | Line 18, inside `bot-manager` service. Promtail/grafana/loki/mongo untouched. |
| 2a | `deploy.sh`: remove chown/chmod/sudo branches | PRESENT | Entire `if [ "$(id -u)" -eq 0 ]; then ... fi` block deleted. |
| 2b | `deploy.sh`: write `.env` with `HOST_UID`/`HOST_GID` (overwrite) | PRESENT | `cat > .env <<EOF` heredoc, lines 18-21, uses `$(id -u)`/`$(id -g)`. |
| 2c | `deploy.sh`: preserve cwd anchor from Phase 3 polish | PRESENT | Line 6: `cd "$(dirname "$(readlink -f "$0")")"`. |
| 2d | `deploy.sh`: preserve `docker ps ... xargs ... kill` step | PRESENT | Line 8 intact. |
| 2e | `deploy.sh`: keep `docker-compose up -d` | PRESENT | Line 23. |
| 3a | `scripts/host-prep-logs.sh`: non-root cleanup-only | PRESENT | Root-required check deleted; no privilege escalation anywhere. |
| 3b | `scripts/host-prep-logs.sh`: no chown/chmod/usermod | PRESENT | All three removed. |
| 3c | `scripts/host-prep-logs.sh`: mkdir/backup/ls-la preserved | PRESENT | `mkdir -p "$LOGS_DIR"` line 30; backup block lines 33-41; `ls -la` line 45. |
| 3d | `scripts/host-prep-logs.sh`: `set -euo pipefail` | PRESENT | Line 23. |
| 3e | `scripts/host-prep-logs.sh`: second-precision timestamp `+%Y%m%d-%H%M%S` | PRESENT | Line 28. |
| 4 | `.gitignore`: add `.env` | PRESENT | Lines 44-45 (with comment header). |

## Architecture Decision 2 — clause coverage

| Clause | Status | Evidence |
|---|---|---|
| Container runs as host user via compose `user:` directive | RESPECTED | `docker-compose.yml:18` |
| `.env` written by `deploy.sh` at deploy time with `HOST_UID`/`HOST_GID` | RESPECTED | `deploy.sh:18-21`, overwrite via `>` not `>>` |
| Image / Dockerfile unchanged | RESPECTED | Dockerfile not in diff |
| Promtail not given a `user:` override | RESPECTED | `docker-compose.yml:45-52` unchanged |
| Zero sudo, zero chown, zero chmod, zero usermod in deploy/prep paths | RESPECTED | `grep` of diff confirms only `chown`/`chmod`/`usermod`/`sudo` occurrences are in deletions |
| Fail-loud if HOST_UID/HOST_GID unset — Docker rejects `user: ":"` at `up` time | RESPECTED | Literal `"${HOST_UID}:${HOST_GID}"` with no default; substitution yields `":"` if vars missing; fails at `up`, not `config`. Dev's observational note (Section 7 of prompt) confirmed compliant with plan. |

## Scope check

Files changed in `e0da939..6ee86f0`:
- `.gitignore`
- `deploy.sh`
- `docker-compose.yml`
- `scripts/host-prep-logs.sh`

Exactly the four expected files. No Java sources, no `log4j2.properties`, no `log4j2-json-template.json`, no `pom.xml`, no `Dockerfile`, no `promtail-config.yml`. Scope clean.

## Phase 1 polish carry-over check

| Carry-over | Status |
|---|---|
| `deploy.sh` cwd anchor (`cd "$(dirname "$(readlink -f "$0")")"`) | INTACT (line 6) |
| `host-prep-logs.sh` `BACKUP_DIR` uses `+%Y%m%d-%H%M%S` | INTACT (line 28) |
| `deploy.sh` `set -euo pipefail` | INTACT (line 2) |
| `host-prep-logs.sh` `set -euo pipefail` | INTACT (line 23) |

## Drift

None.

## Out-of-scope changes

None.

## Plan amendments

None. No technical oversight surfaced; all plan assumptions held.
