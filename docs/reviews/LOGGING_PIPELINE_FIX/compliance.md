# Compliance — LOGGING_PIPELINE_FIX (Phase 1)

Branch: `feat/logging-pipeline-fix`
Plan reviewed: `docs/plans/LOGGING_PIPELINE_FIX.md`
Diff reviewed: `git diff main..feat/logging-pipeline-fix` (HEAD = `129ef20`, base = `4d9d81e`)
Scope under review: Phase 1 only.

## Verdict

PASS (Phase 1 COMPLIANT)

## Per-deliverable

| # | Deliverable | Status | Notes |
|---|---|---|---|
| 1a | `docker-compose.yml`: `bot-manager` volume `logs-data:/app/logs` → `./logs:/app/logs` | PRESENT | line 28 |
| 1b | `docker-compose.yml`: `promtail` volume `logs-data:/logs:ro` → `./logs:/logs:ro` | PRESENT | line 47 |
| 1c | `docker-compose.yml`: remove top-level `logs-data:` | PRESENT | volumes block ends at `grafana-data:` (line 70); `logs-data:` deleted |
| 2a | `deploy.sh` already does `mkdir -p logs` | PRESENT | line 6 |
| 2b | `deploy.sh` adds `chown 1006:1007 logs` + `chmod 2775 logs`, guarded | PRESENT | lines 12-26. Dev added a stronger guard than the plan literally requested: branches for (root) / (sudo available + passwordless) / (no privilege) instead of the plan's literal "`chown ... \|\| true` guarded by `command -v sudo`". This is a strict superset of the plan's intent and respects Architecture Decision 2. |
| 2c | If chown fails, print clear warning pointing at the host-prep script | PRESENT | lines 22-24 — three explicit WARNING lines, last one names `scripts/host-prep-logs.sh` |
| 3a | New `scripts/host-prep-logs.sh` exists | PRESENT | 51 lines, executable (`+x`) |
| 3b | Move pre-existing `/home/sgame/bot-java/logs/*` to `/home/sgame/bot-java/logs.bak.<date>/`, preserve | PRESENT | lines 27-39. Plan named specific files (`console.log`, `console-2026-05-12-10.log`); Dev generalised to "all contents" via dotglob+nullglob. Strict superset — captures any rotated `console-*.log` the plan didn't enumerate. |
| 3c | `mkdir -p /home/sgame/bot-java/logs` | PRESENT | line 42 |
| 3d | `chown 1006:1007 /home/sgame/bot-java/logs` | PRESENT | line 43 |
| 3e | `chmod 2775 /home/sgame/bot-java/logs` (setgid) | PRESENT | line 44 |
| 3f | `usermod -aG 1007 sgame` (idempotent) | PRESENT | line 47; `usermod -aG` is natively idempotent |
| 3g | Print final `ls -la` | PRESENT | lines 49-51 |
| 3h | `set -euo pipefail` | PRESENT | line 20 |
| 3i | Refuse to run if not root | PRESENT | lines 22-25, explicit exit 1 with error message |

## Architecture Decision coverage

| # | Decision | Status | Evidence |
|---|---|---|---|
| 1 | Bind-mount path is `/home/sgame/bot-java/logs/` on host | RESPECTED | `LOGS_DIR="/home/sgame/bot-java/logs"` in host-prep (line 27); compose uses `./logs` which the operator runs from `/home/sgame/bot-java/` — same effective path |
| 2 | Host ownership `1006:1007`, mode `2775` setgid, `usermod -aG 1007 sgame` one-time root | RESPECTED | All three primitives present in host-prep (lines 43, 44, 47); `deploy.sh` mirrors the chown/chmod as soft fallback per the plan's "canonical setup is host-prep, deploy.sh is fallback" framing |

## Scope check

No scope creep. The diff touches exactly the three files the plan calls out:
- `docker-compose.yml`
- `deploy.sh`
- `scripts/host-prep-logs.sh` (new)

No Java sources, no log4j config, no Promtail config, no `pom.xml`, no Dockerfile changes. Clean Phase 1 cut.

## Minor observations (non-blocking)

- `deploy.sh` is a **new file** in this diff (mode 100755). The plan's Section 2.7 stated the file already exists at `/Users/gleb/IdeaProjects/Bot/deploy.sh` with `mkdir -p logs && docker-compose up -d`. The pre-branch `git status` confirms `deploy.sh` was untracked on `main` — i.e. the file existed locally as an untracked script but had not been committed yet. Dev committed the whole script (`docker kill`, `mkdir`, ownership block, `docker-compose up -d`) as one new file, which is the correct way to land an untracked-on-main script alongside its plan-required edits. No drift, just an artifact of the script never having been in the repo before.
- Dev's deploy.sh additionally kills running `bot-` containers (line 4) before redeploying. This pre-existed in the local untracked file and is unrelated to Phase 1; treating it as carried-over rather than scope creep is the correct read.

## Drift

None.

## Plan amendments

None required. No technical oversight found.
