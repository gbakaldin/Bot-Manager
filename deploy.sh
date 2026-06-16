#!/usr/bin/env bash
set -euo pipefail

# Run from the repo root so relative paths (./logs, docker-compose.yml) resolve
# to the same place regardless of where the operator invoked the script.
cd "$(dirname "$(readlink -f "$0")")"

docker ps --filter "name=bot-" --format "{{.ID}}" | xargs -r docker kill

mkdir -p logs prometheus grafana/provisioning/dashboards

# Write .env with the invoking host user's UID/GID so docker-compose can
# substitute them into the bot-manager service's `user:` directive. This makes
# the container write logs as the host user (e.g., sgame on Bot-1) directly,
# avoiding any need for chown/chmod/sudo on the bind-mounted ./logs directory.
# Overwritten on every deploy so a re-deploy by a different host user gets
# fresh values.
cat > .env <<EOF
HOST_UID=$(id -u)
HOST_GID=$(id -g)
EOF

docker compose up -d
