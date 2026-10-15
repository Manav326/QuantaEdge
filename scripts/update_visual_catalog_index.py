#!/usr/bin/env python3
"""Build the discoverable review-only index of generated V3 visual catalogs."""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any

CATALOG_SCHEMA = "quantaedge.visual-attachment-catalog.v3"
INDEX_SCHEMA = "quantaedge.visual-catalog-index.v1"
SHA_RE = re.compile(r"^[0-9a-f]{64}$")


def build_catalog_index(catalog_dir: Path) -> dict[str, Any]:
    if not catalog_dir.is_dir():
        raise ValueError(f"Catalog directory does not exist: {catalog_dir}")
    entries: list[dict[str, Any]] = []
    seen_slugs: set[str] = set()
    for path in sorted(catalog_dir.glob("*.v3.json"), key=lambda item: item.name.casefold()):
        catalog = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(catalog, dict) or catalog.get("schema_version") != CATALOG_SCHEMA:
            raise ValueError(f"Unsupported visual catalog schema: {path.name}")
        slug = catalog.get("book_slug")
        if not isinstance(slug, str) or not re.fullmatch(r"[a-z0-9]+(?:[.-][a-z0-9]+)*", slug):
            raise ValueError(f"Invalid book_slug in {path.name}")
        if path.name != f"{slug}.v3.json":
            raise ValueError(f"Catalog filename must match book_slug: {path.name}")
        if slug in seen_slugs:
            raise ValueError(f"Duplicate catalog slug: {slug}")
        seen_slugs.add(slug)
        source = catalog.get("source")
        if not isinstance(source, dict):
            raise ValueError(f"Missing source metadata in {path.name}")
        required = ("book_id", "class", "subject", "language", "pdf_sha256", "page_count")
        if any(not str(source.get(key, "")).strip() for key in required):
            raise ValueError(f"Incomplete source metadata in {path.name}")
        pdf_hash = str(source["pdf_sha256"]).lower()
        if not SHA_RE.fullmatch(pdf_hash):
            raise ValueError(f"Invalid source PDF SHA-256 in {path.name}")
        summary = catalog.get("summary")
        candidates = catalog.get("candidates")
        if not isinstance(summary, dict) or not isinstance(candidates, list) or not candidates:
            raise ValueError(f"Missing catalog summary or candidate list in {path.name}")
        if summary.get("candidate_count") != len(candidates):
            raise ValueError(f"Candidate count mismatch in {path.name}")
        if summary.get("publication_allowed") is not False or summary.get("review_required") is not True:
            raise ValueError(f"Catalog must remain review-only: {path.name}")
        for candidate in candidates:
            if not isinstance(candidate, dict):
                raise ValueError(f"Invalid candidate record in {path.name}")
            if candidate.get("source_pdf_sha256") != source["pdf_sha256"]:
                raise ValueError(f"Candidate source hash mismatch in {path.name}")
            if candidate.get("publication_allowed") is not False or candidate.get("human_review_required") is not True:
                raise ValueError(f"Candidate is not review-only in {path.name}")
        entries.append({
            "book_slug": slug,
            "catalog_path": f"/content/visual-candidates/{path.name}",
            "source_book_id": str(source["book_id"]),
            "title": str(source.get("title") or source["book_id"]),
            "class": str(source["class"]),
            "subject": str(source["subject"]),
            "language": str(source["language"]),
            "edition": str(source.get("edition") or ""),
            "source_pdf_sha256": pdf_hash,
            "page_count": int(source["page_count"]),
            "candidate_count": len(candidates),
            "publication_allowed": False,
            "review_required": True,
        })
    if not entries:
        raise ValueError(f"No *.v3.json visual catalogs found in {catalog_dir}")
    entries.sort(key=lambda row: (str(row["class"]), str(row["subject"]).casefold(),
                                  str(row["language"]).casefold(), row["book_slug"]))
    return {
        "schema_version": INDEX_SCHEMA,
        "catalogs": entries,
        "summary": {
            "catalog_count": len(entries),
            "candidate_count": sum(row["candidate_count"] for row in entries),
            "publication_allowed": False,
            "review_required": True,
        },
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog-dir", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        result = build_catalog_index(args.catalog_dir)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"output": str(args.output), **result["summary"]}, indent=2))
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"Visual catalog index stopped safely: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
