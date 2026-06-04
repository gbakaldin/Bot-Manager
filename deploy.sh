#!/usr/bin/env bash
set -euo pipefail

docker ps --filter "name=bot-" --format "{{.ID}}" | xargs -r docker kill

mkdir -p logs

# Ensure the host logs/ dir is owned by the container's botmanager user (uid 1006, gid 1007)
# and group-writable + setgid so the sgame operator (added to gid 1007 by host-prep) can
# read and rotate. Soft fallback only — the canonical one-time setup is
# scripts/host-prep-logs.sh, which must be run as root on Bot-1.
if [ "$(id -u)" -eq 0 ]; then
  chown 1006:1007 logs
  chmod 2775 logs
elif command -v sudo >/dev/null 2>&1 && sudo -n true >/dev/null 2>&1; then
  sudo chown 1006:1007 logs
  sudo chmod 2775 logs
else
  current_owner="$(stat -c '%u:%g' logs 2>/dev/null || stat -f '%u:%g' logs 2>/dev/null || echo 'unknown')"
  current_mode="$(stat -c '%a' logs 2>/dev/null || stat -f '%Lp' logs 2>/dev/null || echo 'unknown')"
  if [ "$current_owner" != "1006:1007" ] || [ "$current_mode" != "2775" ]; then
    echo "WARNING: cannot chown/chmod logs/ without root (current owner=$current_owner mode=$current_mode)." >&2
    echo "WARNING: container UID 1006 may fail to write to /app/logs and sgame may not be able to read it." >&2
    echo "WARNING: an operator with root must run scripts/host-prep-logs.sh before the next deploy." >&2
  fi
fi

docker-compose up -d
