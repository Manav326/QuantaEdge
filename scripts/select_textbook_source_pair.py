#!/usr/bin/env python3
"""Build a validated whole-class or English/Hindi book-pair source handoff."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any


def _csv_values(value: str, label: str) -> list[str]:
    values = [item.strip() for item in value.split(",") if item.strip()]
    if value.strip() and len(values) != len(value.split(",")):
        raise ValueError(f"{label} contains an empty CSV value.")
    return values


def _validate_language_payload(
    payload: dict[str, Any], *, grade: int, language: str
) -> list[dict[str, Any]]:
    if payload.get("schema_version") != "quantaedge.visual-source-selection.v1":
        raise ValueError(f"Class {grade} {language}: source-selection schema mismatch.")
    if payload.get("class") != grade or payload.get("language") != language:
        raise ValueError(
            f"Expected Class {grade} {language} source selection; got "
            f"class={payload.get('class')!r}, language={payload.get('language')!r}."
        )
    if payload.get("publication_allowed") is not False or payload.get("review_required") is not True:
        raise ValueError(f"Class {grade} {language}: source handoff must remain review-only.")
    sources = payload.get("sources")
    if not isinstance(sources, list):
        raise ValueError(f"Class {grade} {language}: sources must be a JSON array.")
    expected_count = 2 if grade == 6 else 3
    if len(sources) != expected_count:
        raise ValueError(
            f"Class {grade} {language}: expected {expected_count} whole-book sources, got {len(sources)}."
        )

    seen_ids: set[str] = set()
    seen_slugs: set[str] = set()
    for source in sources:
        if not isinstance(source, dict):
            raise ValueError(f"Class {grade} {language}: every source must be a JSON object.")
        if source.get("class") != grade or source.get("language") != language:
            raise ValueError(f"Class {grade} {language}: a source has a mismatched class or language.")
        book_id = source.get("book_id")
        slug = source.get("book_slug")
        digest = str(source.get("sha256") or "").lower()
        if not isinstance(book_id, str) or not book_id.strip() or book_id in seen_ids:
            raise ValueError(f"Class {grade} {language}: missing or duplicate book_id {book_id!r}.")
        if not isinstance(slug, str) or not slug.strip() or slug in seen_slugs:
            raise ValueError(f"Class {grade} {language}: missing or duplicate book_slug {slug!r}.")
        if not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise ValueError(f"{book_id}: source selection is missing a valid whole-book SHA-256.")
        if source.get("subject") not in {"maths", "science"}:
            raise ValueError(f"{book_id}: unsupported normalized subject {source.get('subject')!r}.")
        if source.get("subject") == "science" and source.get("maths_part") is not None:
            raise ValueError(f"{book_id}: Science must not carry a Maths part number.")
        if source.get("subject") == "maths" and source.get("maths_part") not in ({1} if grade == 6 else {1, 2}):
            raise ValueError(f"{book_id}: invalid Maths part for Class {grade}.")
        seen_ids.add(book_id)
        seen_slugs.add(slug)
    return sources


def build_selection(
    english_payload: dict[str, Any],
    hindi_payload: dict[str, Any],
    *,
    grade: int,
    book_ids_csv: str = "",
    source_slugs_csv: str = "",
    book_pair_id: str = "",
    book_label: str = "",
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    """Validate both language handoffs and select exactly one source per paired edition."""
    if grade not in (6, 7, 8):
        raise ValueError("Textbook source handoff supports only Class 6, 7 or 8.")

    english = _validate_language_payload(english_payload, grade=grade, language="english")
    hindi = _validate_language_payload(hindi_payload, grade=grade, language="hindi")
    all_sources = english + hindi

    all_ids = [source["book_id"] for source in all_sources]
    all_slugs = [source["book_slug"] for source in all_sources]
    if len(all_ids) != len(set(all_ids)) or len(all_slugs) != len(set(all_slugs)):
        raise ValueError(f"Class {grade}: cross-language source IDs/slugs must be globally unique.")

    expected_ids = _csv_values(book_ids_csv, "book_ids_csv")
    expected_slugs = _csv_values(source_slugs_csv, "source_slugs_csv")
    if bool(expected_ids) != bool(expected_slugs):
        raise ValueError("Pair handoff must provide both book_ids_csv and source_slugs_csv.")
    if expected_ids:
        if len(expected_ids) != 2 or len(set(expected_ids)) != 2:
            raise ValueError("A bilingual subject pair must contain exactly two distinct book IDs.")
        if len(expected_slugs) != 2 or len(set(expected_slugs)) != 2:
            raise ValueError("A bilingual subject pair must contain exactly two distinct source slugs.")
        if not book_pair_id or not book_label:
            raise ValueError("A paired worker must receive book_pair_id and book_label.")

        english_matches = [source for source in english if source["book_id"] in set(expected_ids)]
        hindi_matches = [source for source in hindi if source["book_id"] in set(expected_ids)]
        if len(english_matches) != 1 or len(hindi_matches) != 1:
            raise ValueError(
                f"{book_pair_id}: expected exactly one assigned English and one assigned Hindi source; "
                f"found English={len(english_matches)}, Hindi={len(hindi_matches)}."
            )
        selected = [english_matches[0], hindi_matches[0]]
        expected_mapping = dict(zip(expected_ids, expected_slugs, strict=True))
        actual_mapping = {source["book_id"]: source["book_slug"] for source in selected}
        if actual_mapping != expected_mapping:
            raise ValueError(
                f"{book_pair_id}: book ID to source slug handoff mismatch; "
                f"expected {expected_mapping}, received {actual_mapping}."
            )

        english_source, hindi_source = selected
        english_signature = (english_source["subject"], english_source["maths_part"])
        hindi_signature = (hindi_source["subject"], hindi_source["maths_part"])
        if english_signature != hindi_signature:
            raise ValueError(
                f"{book_pair_id}: English/Hindi editions do not describe the same subject/book part: "
                f"{english_signature} vs {hindi_signature}."
            )

        subject, part = english_signature
        expected_pair_id = (
            f"class{grade}-science-bilingual"
            if subject == "science"
            else f"class{grade}-maths-bilingual"
            if grade == 6
            else f"class{grade}-maths-part{part}-bilingual"
        )
        expected_label = (
            "Science" if subject == "science"
            else "Maths" if grade == 6
            else f"Maths Part {'I' if part == 1 else 'II'}"
        )
        if book_pair_id != expected_pair_id or book_label != expected_label:
            raise ValueError(
                f"Pair metadata does not match its sources: expected "
                f"{expected_pair_id!r}/{expected_label!r}, got {book_pair_id!r}/{book_label!r}."
            )
        scope = {
            "mode": "bilingual_book_pair",
            "book_pair_id": book_pair_id,
            "book_label": book_label,
            "source_book_ids": [source["book_id"] for source in selected],
            "source_slugs": [source["book_slug"] for source in selected],
        }
    else:
        if book_pair_id or book_label:
            raise ValueError("Class-wide mode must not carry pair-only metadata.")
        selected = all_sources
        scope = {
            "mode": "class_wide",
            "book_pair_id": None,
            "book_label": None,
            "source_book_ids": all_ids,
            "source_slugs": all_slugs,
        }

    full_selection = {
        "class": grade,
        "sources": all_sources,
        "publication_allowed": False,
        "review_required": True,
    }
    worker_selection = {
        "class": grade,
        "sources": selected,
        "publication_allowed": False,
        "review_required": True,
    }
    return full_selection, worker_selection, scope


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--english", required=True, type=Path)
    parser.add_argument("--hindi", required=True, type=Path)
    parser.add_argument("--class-no", required=True, type=int, choices=(6, 7, 8))
    parser.add_argument("--book-ids-csv", default="")
    parser.add_argument("--source-slugs-csv", default="")
    parser.add_argument("--book-pair-id", default="")
    parser.add_argument("--book-label", default="")
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args(argv)

    full_selection, worker_selection, scope = build_selection(
        json.loads(args.english.read_text(encoding="utf-8")),
        json.loads(args.hindi.read_text(encoding="utf-8")),
        grade=args.class_no,
        book_ids_csv=args.book_ids_csv,
        source_slugs_csv=args.source_slugs_csv,
        book_pair_id=args.book_pair_id,
        book_label=args.book_label,
    )
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for filename, value in (
        ("selected-sources-all.json", full_selection),
        ("selected-sources.json", worker_selection),
    ):
        target = args.output_dir / filename
        temporary = target.with_suffix(target.suffix + ".tmp")
        temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(target)
    print(json.dumps({
        "class": args.class_no,
        "selection_scope": scope,
        "full_class_source_count": len(full_selection["sources"]),
        "selected_worker_source_count": len(worker_selection["sources"]),
        "languages": ["english", "hindi"],
        "publication_allowed": False,
        "review_required": True,
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
