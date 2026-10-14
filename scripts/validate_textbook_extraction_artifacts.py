#!/usr/bin/env python3
"""Validate complete-book extraction artifacts against the class-wise GHCR index.

Class 7 and 8 Maths must retain at least two distinct complete source books (the two
parts) per language. Partial books are never treated as complete or extracted.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from typing import Any


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def normalize_language(value: Any) -> str:
    token = str(value or "").strip().casefold()
    return {"hi": "hindi", "hindi": "hindi", "en": "english", "english": "english"}.get(token, token)


def normalize_subject(value: Any) -> str:
    token = re.sub(r"[^\w\u0900-\u097f]+", " ", str(value or "").casefold()).strip()
    if token in {"math", "maths", "mathematics", "गणित"}:
        return "maths"
    if token in {"science", "विज्ञान"}:
        return "science"
    return token


def class_matches(item: dict[str, Any], grade: int) -> bool:
    values = [item.get("class")]
    classes = item.get("classes")
    if isinstance(classes, list):
        values.extend(classes)
    else:
        values.append(classes)
    return any(re.search(rf"(?<!\d){grade}(?!\d)", str(value or "")) for value in values)


def validate(registry_index: Path, extraction_dir: Path, grade: int, language: str) -> dict[str, Any]:
    index = json.loads(registry_index.read_text(encoding="utf-8"))
    books = index.get("books")
    if not isinstance(books, list):
        raise ValueError("GHCR index must contain a books array.")

    selected: dict[str, dict[str, Any]] = {}
    for item in books:
        if not isinstance(item, dict) or not class_matches(item, grade):
            continue
        item_language = normalize_language(item.get("medium") or item.get("language"))
        if item_language != language:
            continue
        subject = normalize_subject(item.get("subject"))
        if subject not in {"maths", "science"}:
            continue
        availability = item.get("content_availability") or {}
        status = str(availability.get("status") or "complete").casefold()
        if status != "complete":
            continue
        book_id = str(item.get("book_id") or "").strip()
        if not book_id or book_id in selected:
            raise ValueError(f"Missing or duplicate complete-book ID in GHCR index: {book_id!r}")
        selected[book_id] = {**item, "_normalized_subject": subject}

    if not selected:
        raise ValueError(f"No complete Maths/Science source books found for Class {grade} {language}.")
    math_ids = sorted(book_id for book_id, item in selected.items() if item["_normalized_subject"] == "maths")
    science_ids = sorted(book_id for book_id, item in selected.items() if item["_normalized_subject"] == "science")
    if not math_ids or not science_ids:
        raise ValueError(f"Class {grade} {language} must have at least one complete Maths and one Science book.")
    if grade in (7, 8) and len(math_ids) < 2:
        raise ValueError(
            f"Class {grade} {language} Maths must contain both part books as separate complete source books; "
            f"found {len(math_ids)}: {math_ids}"
        )

    manifest_by_id: dict[str, tuple[Path, dict[str, Any]]] = {}
    for manifest_path in sorted((extraction_dir / "pages").glob("*.manifest.json")):
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        book_id = str(manifest.get("book_id") or "")
        if book_id in manifest_by_id:
            raise ValueError(f"Duplicate extraction manifests for book {book_id}.")
        manifest_by_id[book_id] = (manifest_path, manifest)

    verified = []
    missing = []
    for book_id, source in sorted(selected.items()):
        pair = manifest_by_id.get(book_id)
        if pair is None:
            missing.append(book_id)
            continue
        manifest_path, manifest = pair
        if normalize_language(manifest.get("language")) != language:
            raise ValueError(f"{book_id}: manifest language does not match selected language {language}.")
        if normalize_subject(manifest.get("subject")) != source["_normalized_subject"]:
            raise ValueError(f"{book_id}: manifest subject does not match GHCR index.")
        expected_hash = str(source.get("sha256") or "").lower()
        original_hash = str(manifest.get("original_book_sha256") or "").lower()
        if not re.fullmatch(r"[0-9a-f]{64}", expected_hash) or original_hash != expected_hash:
            raise ValueError(f"{book_id}: original PDF SHA-256 does not match GHCR index.")
        if manifest.get("scope") != "SUBJECT_BOOK" or manifest.get("content_state") != "DRAFT_EXTRACTION_ONLY_REQUIRES_REVIEW":
            raise ValueError(f"{book_id}: expected a complete-book draft-only manifest.")
        page_count = manifest.get("page_count")
        if not isinstance(page_count, int) or page_count < 1 or manifest.get("completed_pages") != page_count:
            raise ValueError(f"{book_id}: page extraction is incomplete.")
        jsonl_name = manifest.get("text_jsonl")
        if not isinstance(jsonl_name, str) or Path(jsonl_name).name != jsonl_name or not jsonl_name.endswith(".jsonl"):
            raise ValueError(f"{book_id}: unsafe page JSONL filename.")
        jsonl_path = manifest_path.parent / jsonl_name
        if not jsonl_path.is_file():
            raise ValueError(f"{book_id}: page JSONL file is missing.")
        seen: set[int] = set()
        with jsonl_path.open("r", encoding="utf-8") as handle:
            for line_no, line in enumerate(handle, 1):
                if not line.strip():
                    continue
                row = json.loads(line)
                page = row.get("source_book_page_number")
                if (row.get("book_id") != book_id
                        or str(row.get("original_book_sha256") or "").lower() != expected_hash
                        or normalize_language(row.get("language")) != language
                        or not isinstance(page, int) or not 1 <= page <= page_count
                        or page in seen):
                    raise ValueError(f"{book_id}: invalid/duplicate page metadata on JSONL line {line_no}.")
                seen.add(page)
        if seen != set(range(1, page_count + 1)):
            raise ValueError(f"{book_id}: missing page records; expected {page_count}, found {len(seen)}.")
        verified.append({"book_id": book_id, "subject": source["_normalized_subject"],
                         "page_count": page_count, "manifest": str(manifest_path.relative_to(extraction_dir.parent))})
    if missing:
        raise ValueError("Extraction missing complete source books: " + ", ".join(missing))
    report_path = extraction_dir / "extraction-report.json"
    if report_path.is_file():
        report = json.loads(report_path.read_text(encoding="utf-8"))
        if report.get("failures"):
            raise ValueError(f"Extractor reported {len(report['failures'])} failure(s).")
    return {"class": grade, "language": language, "complete_books_expected": len(selected),
            "maths_source_books": math_ids, "science_source_books": science_ids,
            "verified_books": verified, "verified_page_count": sum(item["page_count"] for item in verified),
            "publication_allowed": False, "review_required": True}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry-index", required=True, type=Path)
    parser.add_argument("--extraction-dir", required=True, type=Path)
    parser.add_argument("--class-no", required=True, type=int, choices=(6, 7, 8))
    parser.add_argument("--language", required=True, choices=("english", "hindi"))
    args = parser.parse_args()
    result = validate(args.registry_index, args.extraction_dir, args.class_no, args.language)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
