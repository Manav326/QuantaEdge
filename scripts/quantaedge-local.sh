#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

ACTION="${1:-up}"

case "$ACTION" in
  audit)
    curl -fsS "http://localhost:${API_PORT:-8080}/api/v1/curriculum/audit/strict" | tee /tmp/quantaedge-curriculum-audit.json
    grep -q '"status":"GREEN"' /tmp/quantaedge-curriculum-audit.json || { echo "Curriculum strict audit is RED. Do not ship."; exit 1; }
    echo "Curriculum strict audit: GREEN"
    exit 0
    ;;
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
    echo "Usage: bash scripts/quantaedge-local.sh [up|rebuild|down|reset|logs|ps|audit]" >&2
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
docker compose ps -a >&2
echo "===== API logs =====" >&2
docker compose logs --no-color --tail=200 api >&2
exit 1
