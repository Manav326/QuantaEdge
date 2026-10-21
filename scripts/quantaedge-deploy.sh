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

# Synchronize the fourteen permanent class/medium packages before the API starts.
# Local files are reused only after SHA-256 verification; deployment never hits NCERT/SCERT.
OWNER_LOWER="$(printf '%s' "$QUANTAEDGE_GHCR_OWNER" | tr '[:upper:]' '[:lower:]')"
export QUANTAEDGE_TEXTBOOK_CACHE_DIR="${QUANTAEDGE_TEXTBOOK_CACHE_DIR:-$PWD/source-pdfs/registry-cache}"
export APP_TEXTBOOK_CACHE_ONLY="${APP_TEXTBOOK_CACHE_ONLY:-true}"
for GRADE in 6 7 8 9 10 11 12; do
  for MEDIUM in hindi english; do
    IMAGE="ghcr.io/$OWNER_LOWER/quantaedge-textbooks-class-$GRADE-$MEDIUM:latest"
    TARGET="$QUANTAEDGE_TEXTBOOK_CACHE_DIR/class-$GRADE/$MEDIUM"
    echo "Syncing $IMAGE -> $TARGET"
    python3 scripts/textbook_registry.py pull --image "$IMAGE" --output-dir "$TARGET"
  done
done

docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d --remove-orphans
docker image prune -f
docker compose -f docker-compose.prod.yml ps
