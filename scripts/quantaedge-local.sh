#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

ACTION="up"
ACTION_SET=false
BUILD_LOCAL=false
DOCKER_OLLAMA=false
AI_MODE=""
AI_MODEL=""
IMAGE_TAG="${QUANTAEDGE_IMAGE_TAG:-latest}"

# Flags can appear before or after the action.
while (($#)); do
  case "$1" in
    --local|--build-local)
      BUILD_LOCAL=true
      shift
      ;;
    --docker-ollama)
      DOCKER_OLLAMA=true
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
      echo "Usage: bash scripts/quantaedge-local.sh [--local] [--docker-ollama] [--tag TAG] [up|sync|ai|rebuild|clean-build|down|reset|logs|ps]" >&2
      exit 2
      ;;
  esac
done

if [[ "$ACTION" == "clean-build" && "$BUILD_LOCAL" != true ]]; then
  echo "'clean-build' is local-source only. Re-run with --local." >&2
  exit 2
fi

export APP_DEMO_SEED="${APP_DEMO_SEED:-true}"
# QuantaEdge owns API port 8080; do not let a stale shell/.env override it.
export API_PORT=8080
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

host_ai_compose() {
  local args=(-f docker-compose.yml)
  if [[ "$BUILD_LOCAL" != true ]]; then
    args+=(-f docker-compose.ghcr.yml)
  fi
  args+=(-f docker-compose.ai-host.yml)
  docker compose "${args[@]}" "$@"
}

detect_local_ollama_model() {
  python3 - <<'PYMODEL'
import json
import os
import sys
import urllib.request

url = "http://127.0.0.1:11434/api/tags"
try:
    with urllib.request.urlopen(url, timeout=4) as response:
        payload = json.load(response)
except Exception as exc:
    print("Cannot reach host Ollama at http://127.0.0.1:11434/api/tags. Start Ollama and confirm 'ollama list' works. To use the separate Docker model instead, pass --docker-ollama. Details: " + str(exc), file=sys.stderr)
    sys.exit(2)

models = [item.get("name", "") for item in payload.get("models", []) if item.get("name")]
if not models:
    print("Host Ollama has no installed models. Check 'ollama list', or pass --docker-ollama for the separate Qwen model.", file=sys.stderr)
    sys.exit(2)

requested = os.environ.get("QUANTAEDGE_OLLAMA_MODEL", "").strip()
if requested:
    if requested not in models:
        print("QUANTAEDGE_OLLAMA_MODEL=%r is not installed. Available models: %s" % (requested, ", ".join(models)), file=sys.stderr)
        sys.exit(2)
    print(requested)
else:
    preferred = next((name for name in models if name.lower().startswith("llama")), models[0])
    print(preferred)
PYMODEL
}

pull_published_images() {
  # Explicit sync refreshes app images only; base images are fetched only when missing.
  compose pull api web admin people
}

ensure_api_port_available() {
  local preferred="8080" existing_api_id existing_host_port
  if [[ ! "$preferred" =~ ^[0-9]+$ ]] || (( preferred < 1 || preferred > 65535 )); then
    echo "API_PORT must be an integer from 1 to 65535; received '$preferred'." >&2
    return 2
  fi

  # Pin Compose to the port checked here, rather than silently accepting a .env override.
  export API_PORT="$preferred"

  # Repeated startup is allowed when QuantaEdge API already owns this exact port.
  existing_api_id="$(compose ps -q api 2>/dev/null | head -n 1 || true)"
  if [[ -n "$existing_api_id" ]]; then
    existing_host_port="$(docker inspect --format '{{(index (index .NetworkSettings.Ports "8080/tcp") 0).HostPort}}' "$existing_api_id" 2>/dev/null || true)"
    if [[ "$existing_host_port" == "$preferred" ]]; then
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

  echo "QuantaEdge requires API host port $preferred and will not switch to another port." >&2
  echo "Port $preferred is occupied, reserved, or unavailable. Identify and stop/disable the conflicting service, then retry." >&2
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
    if [[ "$DOCKER_OLLAMA" == true ]]; then
      if [[ "$BUILD_LOCAL" == true ]]; then ai_compose build --parallel; fi
      ensure_api_port_available
      ai_compose up -d --no-build --remove-orphans
      AI_MODE="docker"
      AI_MODEL="qwen2.5:0.5b"
    else
      AI_MODEL="$(detect_local_ollama_model)"
      export QUANTAEDGE_OLLAMA_MODEL="$AI_MODEL"
      if [[ "$BUILD_LOCAL" == true ]]; then host_ai_compose build --parallel; fi
      ensure_api_port_available
      host_ai_compose up -d --no-build --remove-orphans
      AI_MODE="host"
    fi
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
      if [[ "$AI_MODE" == "host" ]]; then
        echo "AI tutor mode: existing host Ollama model '$AI_MODEL' via host.docker.internal:11434."
        echo "If tutor requests fail with connection refused, see docs/ai/local-ai-tutor.md. Do not expose port 11434 to untrusted networks."
        echo "AI tutor logs: docker compose -f docker-compose.yml -f docker-compose.ghcr.yml -f docker-compose.ai-host.yml logs --tail=200 api"
      else
        echo "AI tutor mode: separate Docker Ollama / qwen2.5:0.5b (first model pull may take several minutes)."
        echo "AI tutor logs: docker compose -f docker-compose.yml -f docker-compose.ghcr.yml -f docker-compose.ai.yml logs --tail=200 ollama ollama-model-init redis api"
      fi
      echo "Open a published lesson and select the tutor dock. Local model answers still require teacher review."
    fi
    exit 0
  fi
  sleep 2
done

echo "API did not become healthy within 60 seconds." >&2
compose ps >&2
exit 1
