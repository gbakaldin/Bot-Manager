# QA — LOGGING_PIPELINE_FIX (Phase 1)

**Verdict:** PASS
**Branch:** `feat/logging-pipeline-fix` @ `129ef20` (on top of main `4d9d81e`)
**Build:** `mvn test` → 293 tests, 0 failures, 0 errors, 0 skipped

## Test suite

Full Maven suite ran clean.

- Tests run: **293** | Failures: **0** | Errors: **0** | Skipped: **0**
- No regressions vs prior baseline. Phase 1 is config + shell only, so no behavioral test impact was expected.

## Diff coverage

Phase 1 has **no Java production diff** — `git diff main...HEAD --stat -- 'src/main/java/'` is empty. No new Java tests were added or required.

Files changed in this phase:
- `docker-compose.yml` — swapped `logs-data` named volume for `./logs` bind-mount on bot-manager and promtail; removed top-level `logs-data` entry.
- `deploy.sh` — newly tracked; adds a guarded chown/chmod step (root / passwordless sudo / warn-and-continue fallback) before `docker-compose up -d`.
- `scripts/host-prep-logs.sh` — new one-time root script: backs up stale `/home/sgame/bot-java/logs/*` to dated `.bak` dir, recreates dir as `1006:1007` mode `2775`, adds `sgame` to GID 1007.

## Shell / compose checks

| Check | Result |
|---|---|
| `bash -n scripts/host-prep-logs.sh` | OK (no syntax errors) |
| `bash -n deploy.sh` | OK (no syntax errors) |
| `test -x scripts/host-prep-logs.sh` | executable bit set |
| `test -x deploy.sh` | executable bit set |
| `docker compose config` — bot-manager mount | `type: bind`, source = `…/logs`, target = `/app/logs` — correct |
| `docker compose config` — promtail mount | `type: bind`, source = `…/logs`, target = `/logs`, `read_only: true` — correct |
| `docker compose config` — top-level `volumes:` | `mongo-data`, `loki-data`, `grafana-data` only; `logs-data` removed — correct |

shellcheck not run (not installed locally); no obvious shellcheck-class issues spotted on manual reading. Both scripts use `set -euo pipefail`, quote variables, and use portable stat fallbacks for Linux/macOS in `deploy.sh`.

## Concerns / follow-ups

Minor, non-blocking:

1. **`deploy.sh` runs on the local dev machine when invoked here.** The `chown 1006:1007` / `chmod 2775` target is host-specific (Bot-1). On a dev mac with passwordless sudo, running `./deploy.sh` would chown the working-tree `logs/` to a UID that doesn't exist locally. The script is presumably only meant to run on Bot-1, but nothing in the script asserts that. A `hostname` or env-var guard would make this safer; the warn-and-continue fallback already protects the no-sudo case.
2. **`scripts/host-prep-logs.sh` hard-codes path `/home/sgame/bot-java/logs`** and UID/GID `1006:1007`. If the Dockerfile's botmanager UID/GID ever drifts from `1006:1007`, both this script and `deploy.sh` silently break the mount permissions. Worth a comment cross-referencing `Dockerfile:4` (already present in the prep script, not in `deploy.sh`).
3. **`docker-compose.yml`** uses relative `./logs` — fine on Bot-1 where the compose file lives under `/home/sgame/bot-java/`, but means `deploy.sh` must be invoked from that directory. Not a defect, just an implicit cwd contract.

None of these affect Phase 1 correctness; they're observations for Phase 2 or later hardening.
