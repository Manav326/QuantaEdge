#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

ACTION="${1:-up}"

case "$ACTION" in
  up)
    export APP_DEMO_SEED="${APP_DEMO_SEED:-true}"
    docker compose up -d --build --remove-orphans
    ;;
  rebuild)
    export APP_DEMO_SEED="${APP_DEMO_SEED:-true}"
    docker compose build --no-cache
    docker compose up -d --remove-orphans
    ;;
  down)
    docker compose down
    exit 0
    ;;
  reset)
    export APP_DEMO_SEED="${APP_DEMO_SEED:-true}"
    docker compose down -v --remove-orphans
    docker compose up -d --build --remove-orphans
    ;;
  logs)
    docker compose logs -f --tail=200
    exit 0
    ;;
  ps)
    docker compose ps
    exit 0
    ;;
  *)
    echo "Usage: bash scripts/quantaedge-local.sh [up|rebuild|down|reset|logs|ps]" >&2
    exit 2
    ;;
esac

echo
docker compose ps

echo
echo "Waiting for API health..."
for i in $(seq 1 30); do
  if curl -fsS http://localhost:${API_PORT:-8080}/actuator/health >/dev/null 2>&1; then
    echo "API:   http://localhost:${API_PORT:-8080}/actuator/health"
    echo "Web:   http://localhost:${WEB_PORT:-3000}"
    echo "Admin: http://localhost:${ADMIN_PORT:-3001}"
    echo "Preview student: /student"
    exit 0
  fi
  sleep 2
done

echo "API did not become healthy within 60 seconds." >&2
docker compose ps >&2
exit 1
