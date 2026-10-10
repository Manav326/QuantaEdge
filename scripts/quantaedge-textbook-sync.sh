#!/usr/bin/env bash
set -euo pipefail

# Sync fixed class/medium tags and prepare private draft assets; never publish automatically.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
LANGUAGE="${1:-both}"
CLASS_NO="${2:-0}"
case "$LANGUAGE" in both|hindi|english) ;; *) echo "Usage: $0 [both|hindi|english] [0|6..12]" >&2; exit 2 ;; esac
if [[ "$CLASS_NO" != "0" && ! "$CLASS_NO" =~ ^(6|7|8|9|10|11|12)$ ]]; then
  echo "Class must be 0 (all classes) or a number from 6 to 12." >&2; exit 2
fi
OWNER="${QUANTAEDGE_GHCR_OWNER:-Manav326}"
OWNER_LOWER="$(printf '%s' "$OWNER" | tr '[:upper:]' '[:lower:]')"
CACHE_ROOT="${QUANTAEDGE_TEXTBOOK_CACHE_DIR:-$ROOT/source-pdfs/registry-cache}"
DRAFT_ROOT="${QUANTAEDGE_TEXTBOOK_DRAFT_DIR:-$ROOT/private-review/textbook-library}"
if ! command -v docker >/dev/null 2>&1; then echo "Docker CLI is required." >&2; exit 2; fi
if ! command -v python3 >/dev/null 2>&1; then echo "Python 3 is required." >&2; exit 2; fi
python3 -c 'import fitz' >/dev/null 2>&1 || python3 -m pip install --disable-pip-version-check "PyMuPDF>=1.24,<2"
if [[ "$LANGUAGE" == "both" ]]; then MEDIA=(hindi english); else MEDIA=("$LANGUAGE"); fi
if [[ "$CLASS_NO" == "0" ]]; then GRADES=(6 7 8 9 10 11 12); else GRADES=("$CLASS_NO"); fi

for GRADE in "${GRADES[@]}"; do
  for MEDIUM in "${MEDIA[@]}"; do
    IMAGE="ghcr.io/${OWNER_LOWER}/quantaedge-textbooks-class-${GRADE}-${MEDIUM}:latest"
    REGISTRY_PATH="$CACHE_ROOT/class-$GRADE/$MEDIUM"
    DRAFT_PATH="$DRAFT_ROOT/class-$GRADE/$MEDIUM"
    echo "==> Pulling Class $GRADE ($MEDIUM) from $IMAGE"
    python3 scripts/textbook_registry.py pull --image "$IMAGE" --output-dir "$REGISTRY_PATH"
    echo "==> Preparing draft assets for Class $GRADE ($MEDIUM)"
    python3 scripts/textbook_registry.py prepare-library --registry-dir "$REGISTRY_PATH" --output-dir "$DRAFT_PATH"
  done
done
echo
echo "Draft preparation finished. Check every prepare-report.json."
echo "Import and publication remain separate, permission-protected review steps."
