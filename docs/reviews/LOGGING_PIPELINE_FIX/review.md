# Code Review — LOGGING_PIPELINE_FIX (Phase 1)

Branch: `feat/logging-pipeline-fix`
Reviewed diff: `git diff main...HEAD` (commit `129ef20` on top of `4d9d81e`)
Scope: Phase 1 only — `docker-compose.yml`, `deploy.sh`, `scripts/host-prep-logs.sh`.

## Verdict

APPROVE WITH NITS

No blockers. Two minor issues worth fixing before the next phase ships, plus a few defensive-style nits the author can take or leave.

## Findings

### 1. [minor] `deploy.sh` runs from the script's invocation cwd, not the repo root
`deploy.sh:6` (`mkdir -p logs`), `deploy.sh:13-25` (all `logs` references).

All operations use the bare relative path `logs`. If the operator runs the script from anywhere other than `/home/sgame/bot-java/`, this creates `./logs` in the wrong place, chowns the wrong directory, and `docker-compose up -d` then either fails (no compose file) or, worse, succeeds against a stale copy somewhere else. The plan's Architecture Decision 1 hard-codes the host path `/home/sgame/bot-java/logs/` — `host-prep-logs.sh` pins it absolutely, but `deploy.sh` does not, so the two can diverge.

Suggested fix: at the top of `deploy.sh`, anchor to the script directory:
```bash
cd "$(dirname "$(readlink -f "$0")")"
```
or refer to `logs` as `"$(dirname "$0")/logs"` throughout. Cheap and removes a foot-gun.

### 2. [minor] `host-prep-logs.sh` `mv` will fail mid-run if the backup dir already has same-named files
`scripts/host-prep-logs.sh:28` (`BACKUP_DIR=...$(date +%Y%m%d)`) + `:37` (`mv "$LOGS_DIR"/* "$BACKUP_DIR"/`).

`BACKUP_DIR` is dated by day, not by timestamp. If the script is re-run on the same day after the container has written new files (e.g., operator runs it, starts the stack, realizes something's off, stops, re-runs), the second `mv` will collide with files already in `logs.bak.YYYYMMDD/` and abort under `set -e`, leaving `$LOGS_DIR` half-emptied. Not catastrophic — the next steps (`mkdir -p`, `chown`, `chmod`) would still succeed — but the script exits non-zero mid-way and the operator has to manually clean up.

Two acceptable fixes:
- Use a second-precision timestamp: `BACKUP_DIR="...logs.bak.$(date +%Y%m%d-%H%M%S)"`.
- Or `mv -n` + a warning when something would have been clobbered. The `-n` option (no-clobber) keeps the script idempotent.

Either way, the intent of the script — "safe to re-run" — is documented at `:15` but isn't fully met today.

### 3. [nit] `usermod -aG 1007 sgame` will fail if no host group has GID 1007
`scripts/host-prep-logs.sh:47`.

`usermod -aG` resolves the group by name or GID. On a Linux box where no group with GID 1007 exists, this exits non-zero and the script aborts. The container's `botmanager` group only exists inside the image — it does not auto-materialize on the host. The diagnosis assumes the GID is already present on Bot-1 (it may well be, since the container has been running there), but if this script is ever run on a fresh host it'll fail at the very last step.

Defensive fix: `getent group 1007 >/dev/null || groupadd -g 1007 botmanager` before the `usermod`. Optional — if Bot-1 already has the group, current code is fine.

### 4. [nit] `chmod 2775` setgid bit only propagates GID, not write permission, to new files
`scripts/host-prep-logs.sh:44`, mirrored in `deploy.sh:14,17`.

Mode `2775` is correct for the directory: new files created inside inherit GID 1007 (good — sgame can read them) and the dir itself is rwx for owner/group, r-x for others. But the file-level mode of those new files is controlled by the container's `umask`. The Dockerfile (`Dockerfile:1-18`) doesn't set an explicit umask, so the JRE base image's default of `022` applies, which gives `0644` for new files — readable by group, which is what we want. So the chain works as designed, but it works *because* of an unstated assumption about the base image's umask. If a future Dockerfile change ever sets `umask 077`, sgame loses read access silently and Phase 5 verification fails in a confusing way.

Worth a one-line comment in `host-prep-logs.sh` near the `chmod 2775` noting "assumes container umask ≤ 022; if you change UMASK in the Dockerfile, revisit this". Not a code change.

### 5. [nit] `docker-compose up -d` is the legacy v1 syntax
`deploy.sh:28`.

Not introduced by this diff (the line predates Phase 1), but it's now versioned in git for the first time. Modern Docker installs ship `docker compose` (v2 plugin); the v1 standalone binary is EOL. On Bot-1 today it presumably works; on a future re-image it may not. Out of scope for this phase — flag for the backlog.

### 6. [nit] Pre-existing `mongo-data` / `loki-data` / `grafana-data` are still named volumes
`docker-compose.yml:67-71`.

Phase 1 only moves the `logs-data` volume to a bind-mount; the other three remain Docker-managed named volumes. Intentional and correct (they don't need host access), but worth noting for the operator: the diff doesn't accidentally affect Mongo/Loki/Grafana persistence. No action needed.

## Looks good

Things I specifically verified are correct:

- Compose bind-mount syntax `./logs:/app/logs` (rw) and `./logs:/logs:ro` (ro). Docker will create `./logs` on the host if absent. No `driver_opts` or propagation flags were ever set on the old named volume, so behaviour is identical except for the host-path visibility.
- Top-level `volumes:` block no longer lists `logs-data` — clean removal, no orphan reference.
- UID/GID `1006:1007` matches `Dockerfile:4` exactly.
- `deploy.sh` correctly distinguishes the three cases: root, passwordless sudo, neither. The `sudo -n true` probe is the right way to detect non-interactive sudo without prompting.
- `deploy.sh` chown/chmod are idempotent on re-run (same uid/gid/mode each time).
- The "neither" branch reads the current state via `stat` and only warns if it's actually wrong, so a correctly-prepped host running an unprivileged `deploy.sh` stays silent. Both Linux (`stat -c`) and BSD/macOS (`stat -f`) syntaxes are tried — good portability touch even though the script will only ever run on Linux.
- The three warning lines on the "neither" branch are actionable: they name the script the operator should run.
- `host-prep-logs.sh` has `set -euo pipefail` and refuses non-root invocation with a clean `exit 1`.
- `host-prep-logs.sh` uses absolute paths everywhere (`LOGS_DIR=/home/sgame/bot-java/logs`) — cannot accidentally chown the wrong directory based on cwd.
- The stale-file move uses `shopt -s dotglob nullglob` so dotfiles are caught and an empty glob doesn't expand to a literal `*`. Correct handling of both edge cases.
- `usermod -aG` (with `-a`) is the additive form — does not replace sgame's other group memberships. Idempotent re-run is safe.
- The `mkdir -p $BACKUP_DIR` only runs inside the "directory exists and is non-empty" branch, so the script doesn't pollute the host with empty `logs.bak.*` dirs on a clean re-run.
- The plan's Phase 1 "Files touched" bullet list is fully reflected in the diff: compose edit (both services + volume block), `deploy.sh` chown/chmod with sudo guard + warning, new `scripts/host-prep-logs.sh` with all five required behaviours (move, mkdir, chown, chmod, usermod, ls). Nothing from Phase 1 is missing.

## Notes

- Phase 1 is config-only and does what it says. The two `[minor]` findings (cwd-anchored deploy script + same-day backup collision) are both robustness issues, not correctness issues — the script works on a happy-path single run from the right directory.
- For the operator: the comment in `host-prep-logs.sh:6-9` says "stale polkitd-owned console.log from May 2026" — the date matches the diagnosis. Make sure that note stays accurate after the cut-over (i.e., it'll be confusing for someone reading this script in 2027). Worth genericizing to "any pre-existing files" without the date.
- No Java touched. Phases 2–4 not in scope for this review.
