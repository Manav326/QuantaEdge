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

# Sync both textbook registries before starting the API. The pull command copies
# only PDFs whose local SHA-256 is missing or changed; unchanged books stay on disk.
OWNER_LOWER="$(printf '%s' "$QUANTAEDGE_GHCR_OWNER" | tr '[:upper:]' '[:lower:]')"
export QUANTAEDGE_TEXTBOOK_CACHE_DIR="${QUANTAEDGE_TEXTBOOK_CACHE_DIR:-$PWD/source-pdfs/registry-cache}"
export APP_TEXTBOOK_CACHE_ONLY="${APP_TEXTBOOK_CACHE_ONLY:-true}"
python3 scripts/textbook_registry.py pull \
  --image "ghcr.io/$OWNER_LOWER/quantaedge-textbooks-hindi:latest" \
  --output-dir "$QUANTAEDGE_TEXTBOOK_CACHE_DIR/hindi"
python3 scripts/textbook_registry.py pull \
  --image "ghcr.io/$OWNER_LOWER/quantaedge-textbooks-english:latest" \
  --output-dir "$QUANTAEDGE_TEXTBOOK_CACHE_DIR/english"

docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d --remove-orphans
docker image prune -f
docker compose -f docker-compose.prod.yml ps
