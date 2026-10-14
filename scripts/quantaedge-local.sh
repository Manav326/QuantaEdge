#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

ACTION="up"
ACTION_SET=false
BUILD_LOCAL=false
IMAGE_TAG="${QUANTAEDGE_IMAGE_TAG:-latest}"

# Flags can appear before or after the action.
while (($#)); do
  case "$1" in
    --local|--build-local)
      BUILD_LOCAL=true
      shift
      ;;
    --tag)
      if (($# < 2)) || [[ -z "$2" ]]; then
        echo "--tag requires a non-empty GHCR image tag." >&2
        exit 2
      fi
      IMAGE_TAG="$2"
      shift 2
      ;;
    up|sync|ai|rebuild|clean-build|down|reset|logs|ps)
      if [[ "$ACTION_SET" == true ]]; then
        echo "Only one action may be supplied." >&2
        exit 2
      fi
      ACTION="$1"
      ACTION_SET=true
      shift
      ;;
    *)
      echo "Usage: bash scripts/quantaedge-local.sh [--local] [--tag TAG] [up|sync|ai|rebuild|clean-build|down|reset|logs|ps]" >&2
      exit 2
      ;;
  esac
done

if [[ "$ACTION" == "clean-build" && "$BUILD_LOCAL" != true ]]; then
  echo "'clean-build' is local-source only. Re-run with --local." >&2
  exit 2
fi

export APP_DEMO_SEED="${APP_DEMO_SEED:-true}"
export QUANTAEDGE_GHCR_OWNER="$(printf '%s' "${QUANTAEDGE_GHCR_OWNER:-manav326}" | tr '[:upper:]' '[:lower:]')"
export QUANTAEDGE_IMAGE_TAG="$IMAGE_TAG"

# Ensure local browser origins remain in the allow-list, even when .env also has production origins.
local_cors_origins="http://localhost:3000,http://localhost:3001,http://localhost:3002,http://127.0.0.1:3000,http://127.0.0.1:3001,http://127.0.0.1:3002"
if [[ -n "${APP_CORS_ALLOWED_ORIGINS:-}" ]]; then
  export APP_CORS_ALLOWED_ORIGINS="${APP_CORS_ALLOWED_ORIGINS},${local_cors_origins}"
else
  export APP_CORS_ALLOWED_ORIGINS="$local_cors_origins"
fi

COMPOSE=(docker compose -f docker-compose.yml)
if [[ "$BUILD_LOCAL" != true ]]; then
  COMPOSE+=(-f docker-compose.ghcr.yml)
fi

compose() {
  "${COMPOSE[@]}" "$@"
}

ai_compose() {
  local args=(-f docker-compose.yml)
  if [[ "$BUILD_LOCAL" != true ]]; then
    args+=(-f docker-compose.ghcr.yml)
  fi
  args+=(-f docker-compose.ai.yml)
  docker compose "${args[@]}" "$@"
}

pull_published_images() {
  # Explicit sync refreshes app images only; base images are fetched only when missing.
  compose pull api web admin people
}

ensure_api_port_available() {
  local preferred="${API_PORT:-8080}" candidate existing_api_id existing_host_port
  if [[ ! "$preferred" =~ ^[0-9]+$ ]] || (( preferred < 1 || preferred > 65535 )); then
    echo "API_PORT must be an integer from 1 to 65535; received '$preferred'." >&2
    return 2
  fi
  # Reuse an already-running QuantaEdge API mapping on repeated invocations.
  existing_api_id="$(compose ps -q api 2>/dev/null | head -n 1 || true)"
  if [[ -n "$existing_api_id" ]]; then
    existing_host_port="$(docker inspect --format '{{(index (index .NetworkSettings.Ports "8080/tcp") 0).HostPort}}' "$existing_api_id" 2>/dev/null || true)"
    if [[ "$existing_host_port" =~ ^[0-9]+$ ]] && (( existing_host_port >= 1 && existing_host_port <= 65535 )); then
      export API_PORT="$existing_host_port"
      return 0
    fi
  fi
  if python3 - "$preferred" <<'PYPORT' >/dev/null 2>&1
import socket, sys
s = socket.socket()
try: s.bind(("0.0.0.0", int(sys.argv[1])))
finally: s.close()
PYPORT
  then
    return 0
  fi
  for ((candidate=preferred+1; candidate<=preferred+20 && candidate<=65535; candidate++)); do
    if python3 - "$candidate" <<'PYPORT' >/dev/null 2>&1
import socket, sys
s = socket.socket()
try: s.bind(("0.0.0.0", int(sys.argv[1])))
finally: s.close()
PYPORT
    then
      export API_PORT="$candidate"
      echo "WARNING: API port $preferred is unavailable; using $API_PORT for this run. Existing process was not stopped." >&2
      return 0
    fi
  done
  echo "API port $preferred is unavailable and no free port was found in the next 20 ports. Set API_PORT to a free port." >&2
  return 1
}

case "$ACTION" in
  sync)
    if [[ "$BUILD_LOCAL" == true ]]; then echo "'sync' cannot be combined with --local." >&2; exit 2; fi
    pull_published_images
    echo "Published QuantaEdge application images synced. Run: bash scripts/quantaedge-local.sh up"
    exit 0
    ;;
  up)
    if [[ "$BUILD_LOCAL" == true ]]; then
      compose build --parallel
      ensure_api_port_available
      compose up -d --no-build --remove-orphans
    else
      ensure_api_port_available
      compose up -d --no-build --remove-orphans
    fi
    ;;
  ai)
    # Local Ollama tutor overlay; database and model volumes are preserved.
    # Cache-first: explicitly run the sync action when you want newer GHCR images.
    if [[ "$BUILD_LOCAL" == true ]]; then ai_compose build --parallel; fi
    ensure_api_port_available
    ai_compose up -d --no-build --remove-orphans
    ;;
  rebuild)
    if [[ "$BUILD_LOCAL" == true ]]; then
      compose build --parallel
      ensure_api_port_available
      compose up -d --no-build --remove-orphans
    else
      pull_published_images
      ensure_api_port_available
      compose up -d --no-build --remove-orphans
    fi
    ;;
  clean-build)
    compose build --no-cache --parallel
    ensure_api_port_available
    compose up -d --no-build --remove-orphans
    ;;
  down)
    compose down
    exit 0
    ;;
  reset)
    # WARNING: this intentionally deletes local database/cache volumes.
    ensure_api_port_available
    compose down -v --remove-orphans
    if [[ "$BUILD_LOCAL" == true ]]; then
      compose build --parallel
      ensure_api_port_available
      compose up -d --no-build --remove-orphans
    else
      pull_published_images
      ensure_api_port_available
      compose up -d --no-build --remove-orphans
    fi
    ;;
  logs)
    compose logs -f --tail=200
    exit 0
    ;;
  ps)
    compose ps
    exit 0
    ;;
esac

echo
compose ps

echo
echo "Waiting for API health..."
for i in $(seq 1 30); do
  if curl -fsS "http://localhost:${API_PORT:-8080}/actuator/health" >/dev/null 2>&1; then
    echo "API:    http://localhost:${API_PORT:-8080}/actuator/health"
    echo "Web:    http://localhost:${WEB_PORT:-3000}"
    echo "Admin:  http://localhost:${ADMIN_PORT:-3001}"
    echo "People: http://localhost:${PEOPLE_PORT:-3002}"
    echo "Student preview: http://localhost:${WEB_PORT:-3000}/student"
    if [[ "$BUILD_LOCAL" == true ]]; then
      echo "Image source: local source build."
    else
      echo "Image source: GHCR (${QUANTAEDGE_GHCR_OWNER}/quantaedge-*:$IMAGE_TAG)."
    fi
    if [[ "$ACTION" == "ai" ]]; then
      echo "AI tutor mode: Ollama / qwen2.5:0.5b (first launch may take several minutes)."
      echo "Open a published lesson and select the tutor dock. The model is a local MVP and may make mistakes."
      echo "AI tutor logs: docker compose -f docker-compose.yml -f docker-compose.ai.yml logs --tail=200 ollama ollama-model-init api"
    fi
    exit 0
  fi
  sleep 2
done

echo "API did not become healthy within 60 seconds." >&2
compose ps >&2
exit 1
