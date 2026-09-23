#!/bin/bash
# Shared helpers for the integration-test container scripts.

# Prints the Docker Compose command ("docker compose" or "docker-compose"); fails if neither exists.
resolve_compose_cmd() {
    if docker compose version >/dev/null 2>&1; then
        echo "docker compose"
        return 0
    fi
    if command -v docker-compose >/dev/null 2>&1; then
        echo "docker-compose"
        return 0
    fi
    return 1
}

# Succeeds if the Docker daemon is reachable.
docker_daemon_up() {
    docker info >/dev/null 2>&1
}
