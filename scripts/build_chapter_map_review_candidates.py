#!/usr/bin/env python3
"""Convert the existing QuantaEdge PDF-split report into source-bound chapter-map review candidates.

This helper never parses PDF boundaries, invents page ranges, marks a map reviewed, calls an API,
or publishes content. A human must review each candidate before the existing extraction builder accepts it.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

SHA_RE = re.compile(r"^[0-9a-f]{64}$")
CODE_RE = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
OFFICIAL_HOSTS = {"ncert.nic.in", "ncert.ncert.org.in", "scert.bihar.gov.in", "bstbpc.gov.in"}


def language_code(value: Any) -> str:
    token = str(value or "").strip().casefold()
    if token in {"hi", "hindi"}:
        return "hi"
    if token in {"en", "english"}:
        return "en"
    raise ValueError("The source manifest must explicitly identify Hindi or English.")


def subject_code(value: Any, book_id: str) -> str:
    token = str(value or "").strip().casefold()
    if "math" in token or "गणित" in token:
        return "maths"
    if "science" in token or "विज्ञान" in token:
        return "science"
    source_id = book_id.casefold()
    if "-fegp" in source_id or "-fhgp" in source_id:
        return "maths"
    if "-fecu" in source_id or "-fhcu" in source_id:
        return "science"
    raise ValueError("Could not resolve the source book's Maths/Science subject.")


def required_text(value: Any, field: str, limit: int = 2000) -> str:
    if not isinstance(value, str) or not value.strip() or len(value.strip()) > limit:
        raise ValueError(f"{field} must be non-empty text no longer than {limit} characters.")
    return value.strip()


def validate_source_url(value: Any) -> str:
    url = required_text(value, "manifest.source_url", 2000)
    parsed = urlparse(url)
    host = (parsed.hostname or "").casefold().rstrip(".")
    if parsed.scheme != "https" or parsed.username or parsed.password or parsed.port not in (None, 443):
        raise ValueError("Source URL must be HTTPS without embedded credentials.")
    if not any(host == allowed or host.endswith("." + allowed) for allowed in OFFICIAL_HOSTS):
        raise ValueError("Source URL must use an approved official textbook host.")
    return url


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_pages(manifest: dict[str, Any], manifest_path: Path, book_id: str,
                   source_hash: str, page_count: int, language: str) -> None:
    filename = required_text(manifest.get("text_jsonl"), "manifest.text_jsonl", 300)
    if Path(filename).name != filename or not filename.endswith(".jsonl"):
        raise ValueError("manifest.text_jsonl must name a sibling JSONL file.")
    page_path = manifest_path.parent / filename
    if not page_path.is_file():
        raise FileNotFoundError(f"Committed page JSONL is missing: {page_path}")
    seen: set[int] = set()
    with page_path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"Invalid JSON in page JSONL line {line_number}.") from exc
            if not isinstance(row, dict):
                raise ValueError(f"Page JSONL line {line_number} must be an object.")
            number = row.get("source_book_page_number")
            row_hash = str(row.get("original_book_sha256") or row.get("asset_pdf_sha256") or "").lower()
            if (row.get("book_id") != book_id or row_hash != source_hash
                    or language_code(row.get("language")) != language
                    or not isinstance(number, int) or isinstance(number, bool)
                    or not 1 <= number <= page_count or number in seen
                    or not isinstance(row.get("text"), str)):
                raise ValueError(f"Source identity, page number, or page schema mismatch at JSONL line {line_number}.")
            seen.add(number)
    expected = set(range(1, page_count + 1))
    if seen != expected:
        raise ValueError(f"Page JSONL is not a complete contiguous book; missing={sorted(expected-seen)[:12]}.")


def chapter_title(row: dict[str, Any]) -> str:
    title = str(row.get("title") or "").strip()
    match = re.search(r"(?:chapter|अध्याय)\s*\d+\s*[:：]\s*(.+)$", title, re.IGNORECASE)
    if match and match.group(1).strip():
        return match.group(1).strip()[:200]
    return required_text(row.get("chapter_name"), "split report chapter_name", 200)


def build_candidate(manifest_path: Path, report_path: Path, course_id: int) -> dict[str, Any]:
    if course_id < 1:
        raise ValueError("course_id must be a positive numeric course ID.")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    report = json.loads(report_path.read_text(encoding="utf-8"))
    if not isinstance(manifest, dict) or not isinstance(report, dict):
        raise ValueError("Manifest and splitter report must be JSON objects.")
    if report.get("schema") != "quantaedge.official-ncert-book-chapter-import-report.v1":
        raise ValueError("Expected a report from the existing official NCERT PDF splitter.")
    if report.get("result") not in {"PREFLIGHT_OK", "COMPLETED"}:
        raise ValueError("The splitter report must show PREFLIGHT_OK or COMPLETED.")
    if report.get("errors"):
        raise ValueError("The splitter report contains errors; candidate creation stopped.")
    if int(report.get("course_id") or 0) != course_id:
        raise ValueError("The splitter report course_id does not match the requested numeric course ID.")
    if report.get("program_code"):
        raise ValueError("Stable numeric course imports must not mix course_id with program_code.")

    book_id = required_text(manifest.get("book_id"), "manifest.book_id", 240)
    source_title = required_text(manifest.get("source_title") or manifest.get("title"), "manifest.source_title", 500)
    source_url = validate_source_url(manifest.get("source_url"))
    edition = required_text(manifest.get("edition"), "manifest.edition", 160)
    original_hash = str(manifest.get("original_book_sha256") or manifest.get("source_sha256") or "").lower()
    asset_hash = str(manifest.get("asset_pdf_sha256") or "").lower()
    if not SHA_RE.fullmatch(original_hash) or original_hash != asset_hash:
        raise ValueError("Manifest original-book and extracted-PDF SHA-256 values must match.")
    page_count = manifest.get("page_count")
    if not isinstance(page_count, int) or isinstance(page_count, bool) or not 1 <= page_count <= 2000:
        raise ValueError("manifest.page_count must be an integer from 1 through 2,000.")
    if manifest.get("completed_pages") != page_count:
        raise ValueError("Manifest page extraction is incomplete.")
    grade = str(manifest.get("class") or "").strip()
    if grade not in {"6", "7", "8"}:
        raise ValueError("The manifest must explicitly identify source curriculum grade 6, 7, or 8.")
    language = language_code(manifest.get("language"))
    subject = subject_code(manifest.get("subject"), book_id)
    validate_pages(manifest, manifest_path, book_id, original_hash, page_count, language)

    rows = report.get("files")
    if not isinstance(rows, list):
        raise ValueError("Splitter report must contain a files array.")

    def in_scope(row: dict[str, Any]) -> bool:
        try:
            row_course = int(row.get("course_id") or 0)
        except (TypeError, ValueError):
            return False
        return (row_course == course_id
                and str(row.get("class_code") or "") == grade
                and str(row.get("subject_code") or "").casefold() == subject
                and str(row.get("language") or "").casefold() == str(manifest.get("language") or "").casefold())

    book_rows = [r for r in rows if isinstance(r, dict) and in_scope(r)
                 and r.get("scope") == "SUBJECT_BOOK"
                 and str(r.get("sha256") or "").lower() == original_hash
                 and r.get("page_count") == page_count]
    if len(book_rows) != 1:
        raise ValueError(f"Expected one matching complete-book row in the splitter report; found {len(book_rows)}.")
    chapter_rows = [r for r in rows if isinstance(r, dict) and in_scope(r)
                    and r.get("scope") == "CHAPTER_PDF"
                    and str(r.get("parent_book_sha256") or "").lower() == original_hash]
    if not chapter_rows:
        raise ValueError("No matching chapter extracts found in the splitter report.")
    chapter_rows.sort(key=lambda r: (int(r.get("chapter_order") or 0), int(r.get("source_page_start") or 0)))

    chapters: list[dict[str, Any]] = []
    codes: set[str] = set()
    ids: set[int] = set()
    previous_end = 0
    for row in chapter_rows:
        code = str(row.get("chapter_code") or "").strip()
        try:
            chapter_id = int(row.get("chapter_id"))
            order = int(row.get("chapter_order"))
            start = int(row.get("source_page_start"))
            end = int(row.get("source_page_end"))
        except (TypeError, ValueError) as exc:
            raise ValueError("A splitter row is missing its chapter ID, order, or source page range.") from exc
        if not CODE_RE.fullmatch(code) or code in codes or chapter_id < 1 or chapter_id in ids:
            raise ValueError(f"Invalid or duplicate course chapter identity in splitter report: {code!r}.")
        if order < 1 or start < 1 or end < start or end > page_count:
            raise ValueError(f"Invalid source page range for {code}: {start}-{end}.")
        if start <= previous_end:
            raise ValueError(f"Chapter source ranges overlap or are out of order near {code}.")
        split_hash = str(row.get("sha256") or "").lower()
        if not SHA_RE.fullmatch(split_hash):
            raise ValueError(f"Missing split-PDF checksum for course chapter {code}.")
        chapters.append({
            "code": code, "title": chapter_title(row), "page_start": start, "page_end": end,
            "course_chapter_id": chapter_id, "chapter_order": order,
            "split_pdf_sha256": split_hash, "split_report_label": str(row.get("title") or ""),
        })
        codes.add(code)
        ids.add(chapter_id)
        previous_end = end

    return {
        "schema": "quantaedge.ghcr-extraction-chapter-map-review-candidate.v1",
        "book_id": book_id, "source_sha256": original_hash,
        "review_status": "DRAFT_REQUIRES_EDITOR_REVIEW", "reviewer": "", "reviewed_at": "",
        "class_code": grade, "subject_code": subject, "course_id": course_id,
        "source": {
            "title": source_title, "url": source_url, "edition": edition,
            "language": language, "page_count": page_count,
            "manifest_path": manifest_path.as_posix(), "split_report_path": report_path.as_posix(),
            "split_report_sha256": sha256_file(report_path),
            "chapter_ranges_derived_from": "scripts/import_official_ncert_books_and_chapters.py",
        },
        "requires_human_review": True, "student_delivery_allowed": False,
        "review_instructions": [
            "Check each chapter title against this exact source PDF and source language.",
            "Check every original-PDF start/end page, including front and back matter.",
            "Check each chapter code and course chapter ID against the intended course curriculum.",
            "Only after review, set review_status to EDITOR_REVIEWED and fill reviewer/reviewed_at (YYYY-MM-DD).",
        ],
        "chapters": chapters,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path, help="Exact committed source-book manifest.")
    parser.add_argument("--split-report", required=True, type=Path, help="Report emitted by the existing PDF splitter.")
    parser.add_argument("--course-id", required=True, type=int, help="Stable numeric course ID.")
    parser.add_argument("--output", required=True, type=Path, help="New review-candidate JSON; never overwritten by default.")
    parser.add_argument("--overwrite", action="store_true", help="Explicitly replace the selected candidate.")
    args = parser.parse_args()
    try:
        candidate = build_candidate(args.manifest, args.split_report, args.course_id)
        if args.output.exists() and not args.overwrite:
            raise FileExistsError(f"Refusing to overwrite candidate: {args.output}. Use a versioned path or --overwrite.")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("w" if args.overwrite else "x", encoding="utf-8") as handle:
            json.dump(candidate, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"Chapter-map candidate generation stopped safely: {exc}", file=sys.stderr)
        return 2
    print(json.dumps({
        "output": str(args.output), "course_id": candidate["course_id"],
        "book_id": candidate["book_id"], "source_sha256": candidate["source_sha256"],
        "chapter_candidates": len(candidate["chapters"]),
        "review_status": candidate["review_status"],
        "requires_human_review": True, "student_delivery_allowed": False,
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
