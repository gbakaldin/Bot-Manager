#!/usr/bin/env bash
#
# One-time host-side root script for the Bot-1 logging bind-mount cut-over.
#
# What this does:
#   1. Moves any pre-existing files in /home/sgame/bot-java/logs/ (notably the
#      stale polkitd-owned console.log from May 2026) aside into a dated backup
#      directory so the new bind-mount starts clean. Files are NOT deleted —
#      they are preserved for audit.
#   2. Recreates /home/sgame/bot-java/logs/ with owner 1006:1007 (matches the
#      botmanager UID/GID baked into the container image, see Dockerfile:4) and
#      mode 2775 (group-writable + setgid so new files written by the container
#      inherit GID 1007).
#   3. Adds the sgame user to the host group with GID 1007 so it can read and
#      tail the live console.log without sudo. Idempotent — safe to re-run.
#   4. Prints the final `ls -la` so the operator can confirm.
#
# Must be run as root on Bot-1. Refuses to run otherwise.

set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "ERROR: this script must be run as root (current uid=$(id -u))." >&2
  exit 1
fi

LOGS_DIR="/home/sgame/bot-java/logs"
BACKUP_DIR="/home/sgame/bot-java/logs.bak.$(date +%Y%m%d)"

# Move stale files aside (if any). Preserve, do not delete.
if [ -d "$LOGS_DIR" ] && [ -n "$(ls -A "$LOGS_DIR" 2>/dev/null || true)" ]; then
  echo "Moving stale contents of $LOGS_DIR to $BACKUP_DIR ..."
  mkdir -p "$BACKUP_DIR"
  # Move both regular files and any rotated console-*.log files. Glob includes
  # dotfiles via shopt so nothing is left behind.
  shopt -s dotglob nullglob
  mv "$LOGS_DIR"/* "$BACKUP_DIR"/
  shopt -u dotglob nullglob
fi

# (Re)create the directory and set ownership + mode.
mkdir -p "$LOGS_DIR"
chown 1006:1007 "$LOGS_DIR"
chmod 2775 "$LOGS_DIR"

# Add sgame to the GID 1007 group (idempotent — usermod -aG is safe to re-run).
usermod -aG 1007 sgame

echo
echo "Final state of $LOGS_DIR:"
ls -la "$LOGS_DIR"
