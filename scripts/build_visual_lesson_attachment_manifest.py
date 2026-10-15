#!/usr/bin/env python3
"""Build a review-gated, student-renderable media catalog from a v3 candidate pack.

The source PDF and candidate pack remain immutable. This helper copies only the
selected previews needed by the admin/media UI into content-addressed static paths
and writes a compact catalog with complete source provenance. It never approves
or publishes any visual.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
from pathlib import Path
from typing import Any

SLUG_RE = re.compile(r"^[a-z0-9]+(?:[.-][a-z0-9]+)*$")
SHA_RE = re.compile(r"^[0-9a-f]{64}$")
RAW_ROOT = "https://raw.githubusercontent.com/Manav326/QuantaEdge/main/"


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as handle:
        for part in iter(lambda: handle.read(1024 * 1024), b""):
            h.update(part)
    return h.hexdigest()


def safe_file(root: Path, relative: Any) -> Path:
    if not isinstance(relative, str) or not relative:
        raise ValueError("Candidate asset path is missing.")
    candidate = (root / relative).resolve()
    if root.resolve() not in candidate.parents or not candidate.is_file():
        raise ValueError(f"Candidate asset is missing or escapes the pack root: {relative!r}")
    return candidate


def build_catalog(pack_path: Path, pack_root: Path, repo_root: Path, book_slug: str, chapter_map_path: Path | None = None) -> dict[str, Any]:
    if not SLUG_RE.fullmatch(book_slug):
        raise ValueError("book_slug must be lowercase letters, numbers, dots or hyphens.")
    pack = json.loads(pack_path.read_text(encoding="utf-8"))
    if not isinstance(pack, dict) or pack.get("schema_version") != "quantaedge.visual-candidate-pack.v3":
        raise ValueError("Expected a quantaedge.visual-candidate-pack.v3 candidate pack.")
    if pack.get("publication_allowed") is not False and pack.get("summary", {}).get("publication_allowed") is not False:
        raise ValueError("Candidate pack must remain non-publishable.")
    source = pack.get("source")
    if not isinstance(source, dict) or not SHA_RE.fullmatch(str(source.get("pdf_sha256", "")).lower()):
        raise ValueError("Candidate pack must contain a source PDF SHA-256.")
    chapter_map = None
    if chapter_map_path is not None:
        chapter_map = json.loads(chapter_map_path.read_text(encoding="utf-8"))
        if not isinstance(chapter_map, dict) or chapter_map.get("schema_version") != "quantaedge.visual-chapter-map.v1":
            raise ValueError("Chapter map must use quantaedge.visual-chapter-map.v1.")
        if (chapter_map.get("source_book_id") != source.get("book_id")
                or str(chapter_map.get("source_pdf_sha256", "")).lower() != str(source.get("pdf_sha256", "")).lower()):
            raise ValueError("Chapter map source book ID or whole-book PDF SHA-256 does not match the candidate pack.")
        if chapter_map.get("publication_allowed") is not False or chapter_map.get("human_review_required") is not True:
            raise ValueError("Chapter-map suggestions must remain review-only.")
        if not isinstance(chapter_map.get("chapters"), list):
            raise ValueError("Chapter map must contain a chapters array.")
    chapter_ranges = chapter_map.get("chapters", []) if chapter_map else []
    candidates = pack.get("candidates")
    if not isinstance(candidates, list) or not candidates:
        raise ValueError("Candidate pack must contain at least one candidate.")

    static_root = repo_root / "apps" / "web" / "public" / "content" / "visual-assets" / book_slug
    records: list[dict[str, Any]] = []
    copied: set[str] = set()
    for row in candidates:
        if not isinstance(row, dict):
            raise ValueError("Every candidate must be a JSON object.")
        candidate_id = str(row.get("candidate_id", ""))
        if not re.fullmatch(r"[a-z0-9-]{5,120}", candidate_id):
            raise ValueError(f"Invalid candidate id: {candidate_id!r}")
        if row.get("source_pdf_sha256") != source["pdf_sha256"]:
            raise ValueError(f"Source PDF hash mismatch for {candidate_id}.")
        page = row.get("source_pdf_page_number")
        if not isinstance(page, int) or isinstance(page, bool) or page < 1 or page > int(source.get("page_count", 2000)):
            raise ValueError(f"Invalid source page for {candidate_id}.")

        display = safe_file(pack_root, row.get("display_path"))
        display_hash = digest(display)
        ext = ".jpg"
        relative_asset = Path("candidates") / f"{candidate_id}-{display_hash[:12]}{ext}"
        destination = static_root / relative_asset
        destination.parent.mkdir(parents=True, exist_ok=True)
        if destination.exists() and digest(destination) != display_hash:
            raise ValueError(f"Refusing to overwrite content-addressed visual asset: {destination}")
        if not destination.exists():
            shutil.copyfile(display, destination)
        copied.add(destination.relative_to(repo_root).as_posix())

        page_preview = safe_file(pack_root, row.get("source_page_preview_path"))
        page_hash = digest(page_preview)
        relative_page = Path("pages") / f"page-{page:04d}-{page_hash[:12]}.jpg"
        page_destination = static_root / relative_page
        page_destination.parent.mkdir(parents=True, exist_ok=True)
        if page_destination.exists() and digest(page_destination) != page_hash:
            raise ValueError(f"Refusing to overwrite content-addressed page preview: {page_destination}")
        if not page_destination.exists():
            shutil.copyfile(page_preview, page_destination)
        copied.add(page_destination.relative_to(repo_root).as_posix())

        def public_url(path: Path) -> str:
            return RAW_ROOT + path.relative_to(repo_root).as_posix()

        bbox = row.get("bbox_pdf_points")
        if bbox is not None and (not isinstance(bbox, list) or len(bbox) != 4
                                 or any(not isinstance(v, (int, float)) for v in bbox)):
            raise ValueError(f"Invalid source bounding box for {candidate_id}.")
        suggested_chapter = next((chapter for chapter in chapter_ranges
                                  if isinstance(chapter, dict)
                                  and isinstance(chapter.get("source_pdf_page_start"), int)
                                  and isinstance(chapter.get("source_pdf_page_end"), int)
                                  and chapter["source_pdf_page_start"] <= page <= chapter["source_pdf_page_end"]), None)
        records.append({
            "suggested_chapter_number": suggested_chapter.get("chapter_number") if suggested_chapter else None,
            "suggested_chapter_title": suggested_chapter.get("chapter_title") if suggested_chapter else None,
            "chapter_map_review_status": chapter_map.get("mapping_status") if chapter_map else None,
            "candidate_id": candidate_id,
            "candidate_kind": row.get("candidate_kind"),
            "source_book_id": row.get("source_book_id"),
            "source_pdf_sha256": source["pdf_sha256"],
            "source_pdf_page_number": page,
            "source_asset_sha256": row.get("source_asset_sha256"),
            "original_asset_sha256": row.get("original_asset_sha256"),
            "bbox_pdf_points": bbox,
            "page_text_context": str(row.get("page_text_context", ""))[:1200],
            "selection_reason": str(row.get("selection_reason", ""))[:500],
            "image_url": public_url(destination),
            "source_page_url": public_url(page_destination),
            "display_sha256": display_hash,
            "page_preview_sha256": page_hash,
            "visual_review_status": "NEEDS_REVIEW",
            "rights_status": "SOURCE_REFERENCE_ONLY",
            "publication_allowed": False,
            "human_review_required": True,
        })

    catalog_source = dict(source)
    catalog_source["title"] = str(source.get("title") or pack.get("source_title") or (chapter_map.get("source_title") if chapter_map else None) or book_slug)

    return {
        "schema_version": "quantaedge.visual-attachment-catalog.v3",
        "book_slug": book_slug,
        "source": catalog_source,
        "summary": {
            "candidate_count": len(records),
            "copied_static_assets": len(copied),
            "publication_allowed": False,
            "review_required": True,
        },
        "visual_kinds": [
            {"id": "PHOTO", "label": "Photo"},
            {"id": "ILLUSTRATION", "label": "Illustration"},
            {"id": "DIAGRAM", "label": "Diagram"},
            {"id": "CHART", "label": "Chart / graph"},
            {"id": "TABLE", "label": "Table / comparison"},
            {"id": "INFOGRAPHIC", "label": "Infographic"},
            {"id": "SEQUENCE", "label": "Sequence / process"},
            {"id": "TEXTBOOK_FIGURE", "label": "Textbook figure (unclassified)"},
        ],
        "candidates": records,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate-pack", required=True, type=Path)
    parser.add_argument("--candidate-root", required=True, type=Path)
    parser.add_argument("--repo-root", type=Path, default=Path.cwd())
    parser.add_argument("--book-slug", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--chapter-map", type=Path, help="Optional source-hash-matched, review-only chapter-to-page map.")
    args = parser.parse_args(argv)
    try:
        result = build_catalog(args.candidate_pack, args.candidate_root, args.repo_root, args.book_slug, args.chapter_map)
        if args.output.exists():
            previous = json.loads(args.output.read_text(encoding="utf-8"))
            if previous.get("source", {}).get("pdf_sha256") != result["source"]["pdf_sha256"]:
                print("The catalog source edition changed; new visual paths are content-addressed.", file=sys.stderr)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"output": str(args.output), **result["summary"]}, indent=2))
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"Visual attachment catalog stopped safely: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
