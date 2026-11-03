#!/usr/bin/env python3
"""Select and validate one exact English/Hindi whole-book pair for visual processing."""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


def _csv_values(raw: str, label: str) -> list[str]:
    values = [item.strip() for item in raw.split(",") if item.strip()]
    if len(values) != 2 or len(set(values)) != 2:
        raise ValueError(f"{label} must contain exactly two distinct comma-separated values.")
    return values


def _normalize_subject(value: Any) -> str:
    normalized = str(value or "").strip().lower()
    return "maths" if normalized in {"maths", "mathematics"} else normalized


def _normalize_class(value: Any) -> str:
    normalized = str(value or "").strip().lower()
    if normalized.startswith("class "):
        normalized = normalized[6:].strip()
    if normalized.startswith("class-"):
        normalized = normalized[6:].strip()
    return normalized


def select_bilingual_pair(
    english: dict[str, Any],
    hindi: dict[str, Any],
    *,
    class_no: str,
    book_ids_csv: str,
    source_slugs_csv: str,
) -> dict[str, Any]:
    book_ids = _csv_values(book_ids_csv, "book_ids_csv")
    source_slugs = _csv_values(source_slugs_csv, "source_slugs_csv")
    expected_class = int(str(class_no))

    if english.get("class") != expected_class or hindi.get("class") != expected_class:
        raise ValueError("Both source selections must belong to the requested class.")
    if str(english.get("language", "")).lower() not in {"english", "en"}:
        raise ValueError("The English source selection has the wrong language identity.")
    if str(hindi.get("language", "")).lower() not in {"hindi", "hi"}:
        raise ValueError("The Hindi source selection has the wrong language identity.")

    selected: list[dict[str, Any]] = []
    for selection, language, expected_id, expected_slug in (
        (english, "english", book_ids[0], source_slugs[0]),
        (hindi, "hindi", book_ids[1], source_slugs[1]),
    ):
        sources = selection.get("sources")
        if not isinstance(sources, list):
            raise ValueError(f"{language} selection has no source list.")
        matches = [row for row in sources if isinstance(row, dict) and row.get("book_id") == expected_id]
        if len(matches) != 1:
            raise ValueError(
                f"{language} selection must contain exactly one assigned book_id={expected_id}; found {len(matches)}."
            )
        source = dict(matches[0])
        if source.get("book_slug") != expected_slug:
            raise ValueError(
                f"{language} source slug mismatch for {expected_id}: expected {expected_slug}, found {source.get('book_slug')}."
            )
        source_class = source.get("class_no") or source.get("class") or expected_class
        if _normalize_class(source_class) != str(expected_class):
            raise ValueError(f"{language} source {expected_id} is bound to another class.")
        subject = _normalize_subject(source.get("subject"))
        if not subject:
            raise ValueError(f"{language} source {expected_id} has no subject.")
        source["subject"] = subject
        source["source_language"] = language
        selected.append(source)

    english_subject = _normalize_subject(selected[0].get("subject"))
    hindi_subject = _normalize_subject(selected[1].get("subject"))
    if english_subject != hindi_subject:
        raise ValueError(
            f"Bilingual pair subject mismatch: English={english_subject!r}, Hindi={hindi_subject!r}."
        )
    if selected[0]["book_id"] == selected[1]["book_id"]:
        raise ValueError("English and Hindi source IDs must be different.")

    return {
        "class": expected_class,
        "sources": selected,
        "book_pair_id": f"class{expected_class}-{english_subject}-bilingual",
        "publication_allowed": False,
        "review_required": True,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--class-no", required=True, choices=("6", "7", "8"))
    parser.add_argument("--book-ids-csv", required=True)
    parser.add_argument("--source-slugs-csv", required=True)
    parser.add_argument("--english-selection", type=Path, required=True)
    parser.add_argument("--hindi-selection", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        english = json.loads(args.english_selection.read_text(encoding="utf-8"))
        hindi = json.loads(args.hindi_selection.read_text(encoding="utf-8"))
        result = select_bilingual_pair(
            english,
            hindi,
            class_no=args.class_no,
            book_ids_csv=args.book_ids_csv,
            source_slugs_csv=args.source_slugs_csv,
        )
        args.output.parent.mkdir(parents=True, exist_ok=True)
        temporary = args.output.with_suffix(args.output.suffix + ".tmp")
        temporary.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(args.output)
        print(json.dumps({
            "class_no": args.class_no,
            "book_pair_id": result["book_pair_id"],
            "selected_book_ids": [item["book_id"] for item in result["sources"]],
            "selected_source_slugs": [item["book_slug"] for item in result["sources"]],
            "languages": [item["source_language"] for item in result["sources"]],
            "subject": result["sources"][0]["subject"],
            "publication_allowed": False,
        }, ensure_ascii=False))
        return 0
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"Bilingual source selection stopped safely: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
