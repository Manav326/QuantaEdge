#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

ACTION="up"
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
    up|ai|rebuild|clean-build|down|reset|logs|ps)
      if [[ "$ACTION" != "up" ]]; then
        echo "Only one action may be supplied." >&2
        exit 2
      fi
      ACTION="$1"
      shift
      ;;
    *)
      echo "Usage: bash scripts/quantaedge-local.sh [--local] [--tag TAG] [up|ai|rebuild|clean-build|down|reset|logs|ps]" >&2
      exit 2
      ;;
  esac
done

if [[ "$ACTION" == "clean-build" && "$BUILD_LOCAL" != true ]]; then
  echo "'clean-build' is local-source only. Re-run with --local, or use 'up' to pull GHCR images." >&2
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
  # Pull all images so first-time startup also has database/cache dependencies.
  compose pull
}

case "$ACTION" in
  up)
    if [[ "$BUILD_LOCAL" == true ]]; then
      compose up -d --build --remove-orphans
    else
      pull_published_images
      compose up -d --no-build --remove-orphans
    fi
    ;;
  ai)
    # Local Ollama tutor overlay; database and model volumes are preserved.
    if [[ "$BUILD_LOCAL" != true ]]; then
      pull_published_images
      ai_compose up -d --no-build --remove-orphans
    else
      ai_compose up -d --build --remove-orphans
    fi
    ;;
  rebuild)
    if [[ "$BUILD_LOCAL" == true ]]; then
      compose build --parallel
      compose up -d --no-build --remove-orphans
    else
      pull_published_images
      compose up -d --no-build --remove-orphans
    fi
    ;;
  clean-build)
    compose build --no-cache --parallel
    compose up -d --no-build --remove-orphans
    ;;
  down)
    compose down
    exit 0
    ;;
  reset)
    # WARNING: this intentionally deletes local database/cache volumes.
    compose down -v --remove-orphans
    if [[ "$BUILD_LOCAL" == true ]]; then
      compose up -d --build --remove-orphans
    else
      pull_published_images
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
