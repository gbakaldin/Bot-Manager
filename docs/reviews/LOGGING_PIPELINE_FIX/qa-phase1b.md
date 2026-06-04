# QA Phase 1b — LOGGING_PIPELINE_FIX

**Verdict:** PASS
**Branch / commit:** `feat/logging-pipeline-fix` @ `6ee86f0` (on top of `e0da939`)

## Test suite

- `mvn test` → **313 tests, 0 failures, 0 errors, 0 skipped** — matches baseline.
- `git diff e0da939..6ee86f0 --stat -- 'src/main/java/'` → empty. No Java changed (config + shell only, as planned).

## Diff scope

```
.gitignore                |  3 +++
deploy.sh                 | 29 +++++-------
docker-compose.yml        |  1 +
scripts/host-prep-logs.sh | 44 +++++++++-----------
```

Four files only — matches Phase 1b scope.

## Shell sanity

- `bash -n deploy.sh` → OK
- `bash -n scripts/host-prep-logs.sh` → OK
- Both files `-rwxr-xr-x` (executable bit preserved).

## Compose sanity (`HOST_UID=$(id -u) HOST_GID=$(id -g) docker compose config`)

- `bot-manager.user: "501:20"` — substituted from host UID:GID as designed.
- `bot-manager` volumes: single bind `source: /Users/gleb/IdeaProjects/Bot/logs → /app/logs` (no `:ro`, writable).
- `promtail` volumes: bind `source: /Users/gleb/IdeaProjects/Bot/logs → /logs`, `read_only: true`. Config bind also `read_only`.
- Top-level `volumes:` contains only `grafana-data`, `loki-data`, `mongo-data`. **No `logs-data`** — Phase 1 carry-over intact.
- No other service has a `user:` directive. Only bot-manager got it. Confirmed by inspecting the rendered config for `mongo`, `loki`, `promtail`, `grafana` blocks.

## `.env` ignore

- `.gitignore:45` → `.env`.
- `git check-ignore .env` → prints `.env` (exit 0). Confirmed ignored.

## Polish carry-overs

- `deploy.sh:6` → `cd "$(dirname "$(readlink -f "$0")")"` — cwd anchor intact.
- `scripts/host-prep-logs.sh:28` → `BACKUP_DIR="/home/sgame/bot-java/logs.bak.$(date +%Y%m%d-%H%M%S)"` — second-precision timestamp intact.

## Concerns / follow-ups

None blocking. Two minor observations for the next phase or for Releaser awareness:

1. `host-prep-logs.sh` hardcodes `/home/sgame/bot-java/logs.bak.*` as `BACKUP_DIR` — the script itself works from any cwd, but the backup path is tied to the prod host layout. If anyone runs it locally for smoke-testing, the backup will land in the wrong dir. Not a regression (pre-existing).
2. `deploy.sh` rewrites `.env` on every deploy via heredoc — any extra keys an operator might add manually to `.env` will be clobbered. Acceptable for the current scope (only HOST_UID/HOST_GID live there), but worth flagging if `.env` ever grows.
