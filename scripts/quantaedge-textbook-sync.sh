#!/usr/bin/env bash
set -euo pipefail

# Pull each chosen language cache from GHCR, then prepare draft library PDFs.
# This never imports, approves, or publishes content to students.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

LANGUAGE="${1:-both}"
case "$LANGUAGE" in both|hindi|english) ;; *) echo "Usage: $0 [both|hindi|english]" >&2; exit 2 ;; esac
OWNER="${QUANTAEDGE_GHCR_OWNER:-Manav326}"
OWNER_LOWER="$(printf '%s' "$OWNER" | tr '[:upper:]' '[:lower:]')"
CACHE_ROOT="${QUANTAEDGE_TEXTBOOK_CACHE_DIR:-$ROOT/source-pdfs/registry-cache}"
DRAFT_ROOT="${QUANTAEDGE_TEXTBOOK_DRAFT_DIR:-$ROOT/private-review/textbook-library}"

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker CLI is required to pull the GHCR textbook images." >&2
  exit 2
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "Python 3 is required. Install Python 3.10+ and retry." >&2
  exit 2
fi
python3 -c 'import fitz' >/dev/null 2>&1 || python3 -m pip install --disable-pip-version-check "PyMuPDF>=1.24,<2"

for MEDIUM in hindi english; do
  if [[ "$LANGUAGE" != "both" && "$LANGUAGE" != "$MEDIUM" ]]; then
    continue
  fi
  IMAGE="ghcr.io/${OWNER_LOWER}/quantaedge-textbooks-${MEDIUM}:latest"
  echo "==> Pulling ${MEDIUM} registry from ${IMAGE}"
  python3 scripts/textbook_registry.py pull --image "$IMAGE" --output-dir "$CACHE_ROOT/$MEDIUM"
  echo "==> Preparing draft library assets for ${MEDIUM}"
  python3 scripts/textbook_registry.py prepare-library \
    --registry-dir "$CACHE_ROOT/$MEDIUM" \
    --output-dir "$DRAFT_ROOT/$MEDIUM"
done

echo
echo "Draft preparation finished. Check each prepare-report.json before importing."
echo "Import is deliberately separate and requires an authorized reviewer session:"
for MEDIUM in hindi english; do
  if [[ "$LANGUAGE" == "both" || "$LANGUAGE" == "$MEDIUM" ]]; then
    echo "  python scripts/import_existing_textbook_pdfs.py --bundle-dir \"$DRAFT_ROOT/$MEDIUM/bundles\" --pdf-dir \"$DRAFT_ROOT/$MEDIUM/pdfs\" --api-base-url http://localhost:8080 --apply"
  fi
done
