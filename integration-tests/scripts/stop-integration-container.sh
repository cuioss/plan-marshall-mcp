#!/bin/bash
# Stops the integration-test containers. With --clean, also removes volumes and built images.
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
source "${SCRIPT_DIR}/lib-docker-compose.sh"

cd "${PROJECT_DIR}"
COMPOSE_CMD="$(resolve_compose_cmd || true)"
if [[ -z "$COMPOSE_CMD" ]]; then
    echo "ℹ️  Docker Compose not available - nothing to stop."
    exit 0
fi
if ! docker_daemon_up; then
    echo "ℹ️  Docker daemon not running - nothing to stop."
    exit 0
fi

if [ "$1" = "--clean" ]; then
    echo "🧹 Stopping containers, removing volumes and images"
    $COMPOSE_CMD down --remove-orphans --volumes --rmi all
else
    echo "🛑 Stopping containers"
    $COMPOSE_CMD down --remove-orphans
fi
