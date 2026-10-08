#!/usr/bin/env bash
set -euo pipefail

cd "${QUANTAEDGE_DIR:-/opt/quantaedge}"

REF="${1:-main}"
export QUANTAEDGE_GHCR_OWNER="${QUANTAEDGE_GHCR_OWNER:-Manav326}"

git fetch --prune origin
PREVIOUS_SHA="$(git rev-parse HEAD)"

if git rev-parse --verify "origin/${REF}^{commit}" >/dev/null 2>&1; then
  TARGET_SHA="$(git rev-parse "origin/${REF}^{commit}")"
else
  git fetch --tags origin
  TARGET_SHA="$(git rev-parse "${REF}^{commit}")"
fi

git checkout --detach "${TARGET_SHA}"
export QUANTAEDGE_IMAGE_TAG="sha-${TARGET_SHA}"

if [[ -z "${QUANTAEDGE_GHCR_TOKEN:-}" ]]; then
  echo "QUANTAEDGE_GHCR_TOKEN is required for GHCR login." >&2
  git checkout --detach "${PREVIOUS_SHA}" >/dev/null 2>&1 || true
  exit 1
fi

echo "$QUANTAEDGE_GHCR_TOKEN" | docker login ghcr.io -u "$QUANTAEDGE_GHCR_OWNER" --password-stdin

rollback() {
  echo "Deployment health gate failed; rolling back to ${PREVIOUS_SHA}." >&2
  export QUANTAEDGE_IMAGE_TAG="sha-${PREVIOUS_SHA}"
  git checkout --detach "${PREVIOUS_SHA}"
  docker compose -f docker-compose.prod.yml pull
  docker compose -f docker-compose.prod.yml up -d --remove-orphans
  exit 1
}
trap rollback ERR

docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d --remove-orphans

for i in {1..30}; do
  if curl -fsS --max-time 5 http://localhost:${API_PORT:-8080}/actuator/health >/dev/null &&
     curl -fsS --max-time 5 http://localhost:${WEB_PORT:-3000}/ >/dev/null &&
     curl -fsS --max-time 5 http://localhost:${ADMIN_PORT:-3001}/ >/dev/null; then
    break
  fi
  sleep 2
done

curl -fsS --max-time 10 http://localhost:${API_PORT:-8080}/actuator/health >/dev/null
curl -fsS --max-time 10 http://localhost:${WEB_PORT:-3000}/ >/dev/null
curl -fsS --max-time 10 http://localhost:${ADMIN_PORT:-3001}/ >/dev/null
docker compose -f docker-compose.prod.yml ps
docker image prune -f
trap - ERR
