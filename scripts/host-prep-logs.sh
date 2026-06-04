#!/usr/bin/env bash
#
# Non-root host cleanup script for the Bot-1 logging bind-mount cut-over.
#
# What this does:
#   1. Ensures /home/sgame/bot-java/logs/ exists.
#   2. Moves any pre-existing files in that directory (notably the stale
#      polkitd-owned console.log from May 2026) aside into a dated backup
#      directory so the new bind-mount starts clean. Files are NOT deleted —
#      they are preserved for audit. The host directory is world-writable
#      (per diagnosis E2) so `sgame` can move polkitd-owned files inside it
#      without owning them.
#   3. Prints the final `ls -la` so the operator can confirm.
#
# No `chown`, `chmod`, or `usermod` — the bot-manager container runs as the
# host user's UID/GID via `user: "${HOST_UID}:${HOST_GID}"` in docker-compose.yml
# (set by deploy.sh's `.env` write), so files it writes are by definition owned
# by the host operator. This script does not require root and does not perform
# any privilege escalation.
#
# Safe to re-run.

set -euo pipefail

LOGS_DIR="/home/sgame/bot-java/logs"
# Second-precision timestamp so a same-day re-run doesn't collide on `mv` and
# abort under `set -e`.
BACKUP_DIR="/home/sgame/bot-java/logs.bak.$(date +%Y%m%d-%H%M%S)"

mkdir -p "$LOGS_DIR"

# Move stale files aside (if any). Preserve, do not delete.
if [ -n "$(ls -A "$LOGS_DIR" 2>/dev/null || true)" ]; then
  echo "Moving stale contents of $LOGS_DIR to $BACKUP_DIR ..."
  mkdir -p "$BACKUP_DIR"
  # Move both regular files and any rotated console-*.log files. Glob includes
  # dotfiles via shopt so nothing is left behind.
  shopt -s dotglob nullglob
  mv "$LOGS_DIR"/* "$BACKUP_DIR"/
  shopt -u dotglob nullglob
fi

echo
echo "Final state of $LOGS_DIR:"
ls -la "$LOGS_DIR"
