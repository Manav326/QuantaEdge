#!/usr/bin/env python3
"""Select class-wise current NCERT Science and Maths sources for review-only visual processing.

A single-medium index is the normal GHCR input. Pass --language to select only that
medium; the helper can also select both languages from a combined index for tests.
The selector fails closed on missing/ambiguous current sources and never publishes lessons.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from typing import Any

try:
    from scripts.validate_textbook_extraction_artifacts import (
        class_matches, detect_maths_part, is_ncert_ganita_prakash,
        normalize_language, normalize_subject,
    )
except ModuleNotFoundError:
    from validate_textbook_extraction_artifacts import (
        class_matches, detect_maths_part, is_ncert_ganita_prakash,
        normalize_language, normalize_subject,
    )


def select_sources(
    index: dict[str, Any], grade: int, language: str | None = None
) -> list[dict[str, Any]]:
    if grade not in (6, 7, 8):
        raise ValueError("Visual source selection is limited to Class 6, Class 7 or Class 8.")
    if language is not None:
        language = normalize_language(language)
        if language not in {"english", "hindi"}:
            raise ValueError("Language must be English or Hindi.")
        languages = (language,)
    else:
        languages = ("english", "hindi")

    books = index.get("books")
    if not isinstance(books, list):
        raise ValueError("GHCR index must contain a books array.")
    selected: list[dict[str, Any]] = []
    slugs: set[str] = set()

    for medium in languages:
        raw_candidates: list[tuple[dict[str, Any], str]] = []
        for item in books:
            if not isinstance(item, dict) or not class_matches(item, grade):
                continue
            if normalize_language(item.get("medium") or item.get("language")) != medium:
                continue
            subject = normalize_subject(item.get("subject"))
            if subject not in {"maths", "science"}:
                continue
            availability = item.get("content_availability") or {}
            if str(availability.get("status") or "complete").casefold() != "complete":
                continue
            raw_candidates.append((item, subject))

        maths_rows = [item for item, subject in raw_candidates if subject == "maths"]
        if any(is_ncert_ganita_prakash(item) for item in maths_rows):
            raw_candidates = [
                (item, subject) for item, subject in raw_candidates
                if subject != "maths" or is_ncert_ganita_prakash(item)
            ]

        candidates: dict[str, dict[str, Any]] = {}
        for item, subject in raw_candidates:
            book_id = str(item.get("book_id") or "").strip()
            if not book_id or book_id in candidates:
                raise ValueError(
                    f"Missing or duplicate complete-book ID in Class {grade} {medium} index: {book_id!r}"
                )
            filename = item.get("file")
            if filename != f"books/{book_id}.pdf":
                raise ValueError(
                    f"{book_id}: expected the indexed whole-book file books/{book_id}.pdf; found {filename!r}."
                )
            digest = str(item.get("sha256") or "").lower()
            if not re.fullmatch(r"[0-9a-f]{64}", digest):
                raise ValueError(f"{book_id}: the GHCR index has no valid whole-book SHA-256.")
            candidates[book_id] = {**item, "_normalized_subject": subject}

        maths = [item for item in candidates.values() if item["_normalized_subject"] == "maths"]
        science = [item for item in candidates.values() if item["_normalized_subject"] == "science"]
        expected_maths_count = 1 if grade == 6 else 2
        if len(maths) != expected_maths_count:
            raise ValueError(
                f"Class {grade} {medium} requires exactly {expected_maths_count} complete current Maths book(s); "
                f"found {len(maths)}: {[x.get('book_id') for x in maths]}"
            )
        if len(science) != 1:
            raise ValueError(
                f"Class {grade} {medium} requires exactly one complete Science book; "
                f"found {len(science)}: {[x.get('book_id') for x in science]}"
            )

        part_by_id = {str(item["book_id"]): detect_maths_part(item) for item in maths}
        expected_parts = {1} if grade == 6 else {1, 2}
        if set(part_by_id.values()) != expected_parts:
            expected_text = "gp1" if grade == 6 else "Part 1 and Part 2"
            raise ValueError(
                f"Class {grade} {medium} Maths source IDs/titles must identify {expected_text}; "
                f"found {part_by_id}. No part labels will be guessed."
            )

        ordered = [(item, "science", None) for item in science]
        ordered += [(item, "maths", part_by_id[str(item["book_id"])]) for item in maths]
        for item, subject, part_no in ordered:
            book_id = str(item["book_id"])
            key_hash = hashlib.sha256(book_id.encode("utf-8")).hexdigest()[:8]
            lang_code = "en" if medium == "english" else "hi"
            role = "science" if subject == "science" else ("maths" if grade == 6 else f"maths-part{part_no}")
            slug = f"class{grade}-{lang_code}-{role}-{key_hash}"
            if slug in slugs:
                raise ValueError(f"Duplicate visual candidate slug generated: {slug}")
            slugs.add(slug)
            selected.append({
                "class": grade,
                "language": medium,
                "book_id": book_id,
                "book_slug": slug,
                "title": str(item.get("title") or item.get("book_title") or book_id),
                "subject": subject,
                "maths_part": part_no,
                "file": str(item["file"]),
                "sha256": str(item["sha256"]).lower(),
            })
    return selected


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--index", required=True, type=Path)
    parser.add_argument("--class-no", required=True, type=int, choices=(6, 7, 8))
    parser.add_argument("--language", required=True, choices=("english", "hindi"))
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)
    index = json.loads(args.index.read_text(encoding="utf-8"))
    sources = select_sources(index, args.class_no, args.language)
    expected_count = 2 if args.class_no == 6 else 3
    result = {
        "schema_version": "quantaedge.visual-source-selection.v1",
        "class": args.class_no,
        "language": args.language,
        "language_count": 1,
        "expected_source_count": expected_count,
        "sources": sources,
        "publication_allowed": False,
        "review_required": True,
    }
    if len(sources) != expected_count:
        raise ValueError(
            f"Expected {expected_count} current source books for Class {args.class_no} {args.language}, "
            f"found {len(sources)}."
        )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "class": args.class_no,
        "language": args.language,
        "sources": [
            {"language": s["language"], "subject": s["subject"], "maths_part": s["maths_part"],
             "book_id": s["book_id"], "slug": s["book_slug"]}
            for s in sources
        ],
        "publication_allowed": False,
        "review_required": True,
        "output": str(args.output),
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
