#!/bin/bash
# Starts the plan-marshall-mcp container via docker compose and waits until it reports ready.
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
source "${SCRIPT_DIR}/lib-docker-compose.sh"

cd "${PROJECT_DIR}"
COMPOSE_CMD="$(resolve_compose_cmd)" || { echo "❌ Docker Compose not available"; exit 1; }

IMAGE="plan-marshall-mcp:jvm"
if ! docker image inspect "${IMAGE}" >/dev/null 2>&1; then
    echo "❌ Image ${IMAGE} not found - run 'docker compose build' first"
    exit 1
fi

MANAGEMENT_PORT="${TEST_MANAGEMENT_PORT:-19000}"
READY_URL="http://localhost:${MANAGEMENT_PORT}/q/health/ready"

echo "🚀 Starting plan-marshall-mcp container"
$COMPOSE_CMD up -d

echo "⏳ Waiting for ${READY_URL}"
for _ in $(seq 1 60); do
    if curl -fsS "${READY_URL}" >/dev/null 2>&1; then
        echo "✅ plan-marshall-mcp is ready"
        exit 0
    fi
    sleep 1
done

echo "❌ plan-marshall-mcp did not become ready in time"
$COMPOSE_CMD logs --no-color
exit 1
