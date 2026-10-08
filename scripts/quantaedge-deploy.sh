#!/usr/bin/env bash
set -euo pipefail

cd "${QUANTAEDGE_DIR:-/opt/quantaedge}"

REF="${1:-main}"
export QUANTAEDGE_GHCR_OWNER="${QUANTAEDGE_GHCR_OWNER:-Manav326}"

git fetch --prune origin
git checkout "${REF}"
git reset --hard "origin/${REF}"

export QUANTAEDGE_IMAGE_TAG="sha-$(git rev-parse HEAD)"

if [[ -z "${QUANTAEDGE_GHCR_TOKEN:-}" ]]; then
  echo "QUANTAEDGE_GHCR_TOKEN is required for GHCR login." >&2
  exit 1
fi

echo "$QUANTAEDGE_GHCR_TOKEN" | docker login ghcr.io -u "$QUANTAEDGE_GHCR_OWNER" --password-stdin
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d --remove-orphans
docker image prune -f
docker compose -f docker-compose.prod.yml ps
