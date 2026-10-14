#!/usr/bin/env python3
"""Build the existing draft-question input from a committed GHCR page manifest/JSONL.

Requires a human-reviewed chapter map bound to the exact source-book ID and SHA-256.
Chapters with low-quality, OCR-required, failed, or empty pages are excluded from
generation, while unaffected chapters can continue. This never publishes content.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import sys
from datetime import date, datetime
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

SHA_RE = re.compile(r"[0-9a-f]{64}")
CODE_RE = re.compile(r"[a-z0-9]+(?:-[a-z0-9]+)*")
OFFICIAL_HOSTS = {"ncert.nic.in", "ncert.ncert.org.in", "scert.bihar.gov.in", "bstbpc.gov.in"}
REVIEW_STATUS = {
    "TEXT_EXTRACTED_REQUIRES_CONTENT_REVIEW": "READY",
    "LOW_TEXT_REVIEW": "LOW_TEXT",
    "NO_DEVANAGARI_REVIEW": "LOW_TEXT",
    "NO_EXTRACTABLE_TEXT_REVIEW": "OCR_REQUIRED",
    "FAILED": "FAILED",
}


def required(value: Any, name: str, maximum: int = 2000) -> str:
    if not isinstance(value, str) or not value.strip() or len(value.strip()) > maximum:
        raise ValueError(f"{name} must be non-empty text no longer than {maximum} characters.")
    return value.strip()


def language_code(value: Any) -> str:
    token = str(value or "").strip().casefold()
    if token in {"hi", "hindi"}:
        return "hi"
    if token in {"en", "english"}:
        return "en"
    raise ValueError("Only explicitly identified Hindi or English source books are supported.")


def subject_code(value: Any) -> str:
    token = str(value or "").strip().casefold()
    if "math" in token or "गणित" in token:
        return "maths"
    if "science" in token or "विज्ञान" in token:
        return "science"
    raise ValueError("Only Maths and Science source books are supported.")


def official_url(value: Any) -> str:
    url = required(value, "manifest.source_url", 2000)
    parsed = urlparse(url)
    host = (parsed.hostname or "").casefold().rstrip(".")
    if (parsed.scheme != "https" or parsed.username or parsed.password
            or parsed.port not in (None, 443)
            or not any(host == allowed or host.endswith("." + allowed) for allowed in OFFICIAL_HOSTS)):
        raise ValueError("Source URL must be HTTPS on an approved official NCERT/Bihar source host.")
    return url


def canonical_codes(target_csv: Path, grade: str, subject: str) -> set[str]:
    if not target_csv.is_file():
        raise FileNotFoundError(f"Canonical curriculum target CSV not found: {target_csv}")
    result: set[str] = set()
    with target_csv.open("r", encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle):
            if str(row.get("class_code", "")).strip() != grade:
                continue
            try:
                actual_subject = subject_code(row.get("subject_code", ""))
            except ValueError:
                continue
            if actual_subject == subject and str(row.get("chapter_code", "")).strip():
                result.add(str(row["chapter_code"]).strip())
    if not result:
        raise ValueError(f"No canonical chapter codes found for Class {grade} {subject}.")
    return result


def load_chapter_map(path: Path, book_id: str, source_hash: str, page_count: int,
                     class_code: str, subject: str, known_codes: set[str]) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError("Chapter map must be a JSON object.")
    if data.get("book_id") != book_id:
        raise ValueError("Chapter map book_id does not match the selected source book.")
    if str(data.get("source_sha256", "")).lower() != source_hash:
        raise ValueError("Chapter map source_sha256 does not match the original book hash.")
    if data.get("review_status") != "EDITOR_REVIEWED":
        raise ValueError("Chapter map must use review_status=EDITOR_REVIEWED after its contents and PDF page ranges are checked.")
    reviewer = required(data.get("reviewer"), "chapter_map.reviewer", 160)
    reviewed_at = required(data.get("reviewed_at"), "chapter_map.reviewed_at", 40)
    try:
        date.fromisoformat(reviewed_at)
    except ValueError as exc:
        raise ValueError("chapter_map.reviewed_at must be YYYY-MM-DD.") from exc
    if str(data.get("class_code", "")).strip() != class_code or subject_code(data.get("subject_code")) != subject:
        raise ValueError("Chapter-map class/subject do not match the source book.")

    raw = data.get("chapters")
    if not isinstance(raw, list) or not raw:
        raise ValueError("Chapter map must contain a non-empty chapters array.")
    codes: set[str] = set()
    ranges: list[tuple[int, int, str]] = []
    chapters: list[dict[str, Any]] = []
    for index, item in enumerate(raw):
        if not isinstance(item, dict):
            raise ValueError("Every chapter-map entry must be an object.")
        code = str(item.get("code", "")).strip()
        title = required(item.get("title"), f"chapters[{index}].title", 200)
        start, end = item.get("page_start"), item.get("page_end")
        if not CODE_RE.fullmatch(code) or code in codes:
            raise ValueError(f"Invalid or duplicate canonical chapter code: {code!r}.")
        if code not in known_codes:
            raise ValueError(f"Chapter code {code!r} is not a canonical Class {class_code} chapter.")
        if not isinstance(start, int) or not isinstance(end, int) or start < 1 or end < start or end > page_count:
            raise ValueError(f"Invalid source PDF range for {code}; valid page range is 1-{page_count}.")
        for old_start, old_end, old_code in ranges:
            if max(start, old_start) <= min(end, old_end):
                raise ValueError(f"Chapter page ranges overlap: {code} and {old_code}.")
        codes.add(code)
        ranges.append((start, end, code))
        chapters.append({"code": code, "title": title, "page_start": start, "page_end": end})
    chapters.sort(key=lambda item: (item["page_start"], item["code"]))
    review = {
        "status": "EDITOR_REVIEWED",
        "reviewer": reviewer,
        "reviewed_at": reviewed_at,
        "source_book_id": book_id,
        "source_sha256": source_hash,
    }
    return chapters, review


def build_bundle(manifest_path: Path, chapter_map_path: Path, target_csv: Path) -> dict[str, Any]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if not isinstance(manifest, dict):
        raise ValueError("Extraction manifest must be a JSON object.")
    if manifest.get("scope") != "SUBJECT_BOOK":
        raise ValueError("Only complete-book extraction manifests are supported.")
    if manifest.get("content_state") != "DRAFT_EXTRACTION_ONLY_REQUIRES_REVIEW":
        raise ValueError("Only review-gated draft extraction manifests are supported.")

    book_id = required(manifest.get("book_id"), "manifest.book_id", 240)
    title = required(manifest.get("source_title") or manifest.get("title"), "manifest.source_title", 500)
    url = official_url(manifest.get("source_url"))
    edition = required(manifest.get("edition"), "manifest.edition", 160)
    original_hash = str(manifest.get("original_book_sha256") or manifest.get("source_sha256") or "").lower()
    asset_hash = str(manifest.get("asset_pdf_sha256") or "").lower()
    if not SHA_RE.fullmatch(original_hash) or not SHA_RE.fullmatch(asset_hash) or original_hash != asset_hash:
        raise ValueError("Original-book and extracted-PDF SHA-256 values must be valid and equal.")

    class_text = str(manifest.get("class") or manifest.get("classes") or "")
    grade_match = re.search(r"(?<!\d)(6|7|8)(?!\d)", class_text)
    if not grade_match:
        raise ValueError("Source book must be explicitly tagged for Class 6, 7, or 8.")
    grade = grade_match.group(1)
    medium = language_code(manifest.get("language"))
    subject = subject_code(manifest.get("subject"))
    page_count = manifest.get("page_count")
    if not isinstance(page_count, int) or not 1 <= page_count <= 2000:
        raise ValueError("manifest.page_count must be an integer from 1 through 2,000.")
    if manifest.get("completed_pages") != page_count:
        raise ValueError("Extraction is incomplete: completed_pages must equal page_count.")

    jsonl_name = required(manifest.get("text_jsonl"), "manifest.text_jsonl", 300)
    if Path(jsonl_name).name != jsonl_name or not jsonl_name.endswith(".jsonl"):
        raise ValueError("manifest.text_jsonl must be a sibling JSONL filename, not a path.")
    jsonl_path = manifest_path.parent / jsonl_name
    if not jsonl_path.is_file():
        raise FileNotFoundError(f"Extracted page JSONL is missing: {jsonl_path}")
    if jsonl_path.stat().st_size > 50 * 1024 * 1024:
        raise ValueError("Extracted page JSONL exceeds the current API import limit of 50 MiB.")

    known_codes = canonical_codes(target_csv, grade, subject)
    chapters, map_review = load_chapter_map(
        chapter_map_path, book_id, original_hash, page_count, grade, subject, known_codes
    )

    raw_pages: dict[int, dict[str, Any]] = {}
    with jsonl_path.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, start=1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"Invalid JSON at page JSONL line {line_no}.") from exc
            if not isinstance(row, dict):
                raise ValueError(f"Page JSONL line {line_no} must be an object.")
            page_no = row.get("source_book_page_number")
            text_value = row.get("text")
            status = str(row.get("review_status") or "").strip().upper()
            if (
                str(row.get("book_id") or "") != book_id
                or str(row.get("original_book_sha256") or "").lower() != original_hash
                or language_code(row.get("language")) != medium
                or str(row.get("subject") or "").strip().casefold() != str(manifest.get("subject", "")).strip().casefold()
                or not isinstance(page_no, int) or not 1 <= page_no <= page_count
                or page_no in raw_pages
                or not isinstance(text_value, str)
                or status not in REVIEW_STATUS
            ):
                raise ValueError(f"Inconsistent, duplicate, or unsupported page metadata at JSONL line {line_no}.")
            text_hash = hashlib.sha256(text_value.encode("utf-8")).hexdigest()
            if row.get("text_sha256") and str(row["text_sha256"]).lower() != text_hash:
                raise ValueError(f"Text SHA-256 mismatch on source PDF page {page_no}.")
            method = str(row.get("extraction_method") or "").strip().upper()
            if method not in {"PDF_TEXT", "OCR"}:
                method = "PDF_TEXT" if text_value.strip() else "EMPTY"
            raw_pages[page_no] = {
                "page_number": page_no,
                "text": text_value,
                "quality_status": REVIEW_STATUS[status],
                "extraction_method": method,
            }

    expected = set(range(1, page_count + 1))
    actual = set(raw_pages)
    if actual != expected:
        missing, extra = sorted(expected - actual), sorted(actual - expected)
        raise ValueError(f"Extracted pages are not a complete contiguous book. Missing: {missing[:20]}; extra: {extra[:20]}.")

    safe_chapters: list[dict[str, Any]] = []
    blocked_chapters: list[dict[str, Any]] = []
    page_assignment: dict[int, dict[str, Any]] = {}
    for chapter in chapters:
        blocked_pages = []
        for number in range(chapter["page_start"], chapter["page_end"] + 1):
            page = raw_pages[number]
            if page["quality_status"] != "READY" or not page["text"].strip():
                blocked_pages.append({
                    "page_number": number,
                    "quality_status": page["quality_status"],
                    "text_characters": len(page["text"].strip()),
                })
        if blocked_pages:
            blocked_chapters.append({
                **chapter,
                "reason": "Resolve low-quality, OCR-required, failed, or empty pages before generating this chapter.",
                "blocked_pages": blocked_pages,
            })
            continue
        safe_chapters.append(chapter)
        for number in range(chapter["page_start"], chapter["page_end"] + 1):
            page_assignment[number] = chapter

    if not safe_chapters:
        raise ValueError("No chapter is currently safe for generation. Resolve the low-text/OCR flags in the reviewed chapter ranges and rerun.")

    pages = []
    for number in range(1, page_count + 1):
        page = raw_pages[number]
        chapter = page_assignment.get(number)
        page_text = page["text"]
        pages.append({
            "page_number": number,
            "chapter_code": chapter["code"] if chapter else None,
            "chapter_title": chapter["title"] if chapter else None,
            "text": page_text,
            "text_sha256": hashlib.sha256(page_text.encode("utf-8")).hexdigest(),
            "extraction_method": page["extraction_method"],
            "quality_status": page["quality_status"],
            "needs_human_review": True,
        })

    return {
        "schema_version": 1,
        "bundle_status": "DRAFT_EXTRACTION_ONLY",
        "created_at": datetime.now().astimezone().isoformat(),
        "curriculum": {
            "board": "Bihar Board",
            "class_code": grade,
            "subject_code": subject,
            "language": medium,
        },
        "source": {
            "title": title,
            "url": url,
            "edition": edition,
            "pdf_filename": str(manifest.get("asset_pdf_filename") or manifest.get("bundle_file") or ""),
            "pdf_sha256": original_hash,
            "pdf_page_count": page_count,
            "book_id": book_id,
            "publisher": "NCERT" if "ncert" in book_id.casefold() else "SCERT/official textbook source",
        },
        "chapter_map": safe_chapters,
        "chapter_map_review": map_review,
        "extraction": {
            "extractor_version": manifest.get("extractor_version", "unknown"),
            "requires_human_review": True,
            "student_delivery_allowed": False,
            "quality_blocked_chapters": blocked_chapters,
            "quality_blocked_chapter_count": len(blocked_chapters),
            "quality_blocked_page_count": sum(len(item["blocked_pages"]) for item in blocked_chapters),
            "pages_with_no_text": [item["page_number"] for item in pages if not item["text"].strip()],
            "ocr_pages": [item["page_number"] for item in pages if item["extraction_method"] == "OCR"],
            "publication": "NOT_PUBLISHED; generated lessons and questions still require editorial review",
        },
        "pages": pages,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path, help="Committed GHCR extraction manifest.")
    parser.add_argument("--chapter-map", required=True, type=Path, help="Human-reviewed source-bound chapter map JSON.")
    parser.add_argument("--target-csv", type=Path, default=Path("docs/product/curriculum-question-targets.csv"))
    parser.add_argument("--output", required=True, type=Path, help="New draft-only extraction JSON; never overwritten by default.")
    parser.add_argument("--overwrite", action="store_true", help="Explicitly replace the selected output file.")
    args = parser.parse_args()
    try:
        bundle = build_bundle(args.manifest, args.chapter_map, args.target_csv)
        if args.output.exists() and not args.overwrite:
            raise FileExistsError(f"Refusing to overwrite existing output: {args.output}. Use a new versioned path or pass --overwrite.")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("w" if args.overwrite else "x", encoding="utf-8") as handle:
            json.dump(bundle, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"Extraction-bundle build stopped safely: {exc}", file=sys.stderr)
        return 2
    print(json.dumps({
        "output": str(args.output),
        "book_id": bundle["source"]["book_id"],
        "class": bundle["curriculum"]["class_code"],
        "subject": bundle["curriculum"]["subject_code"],
        "language": bundle["curriculum"]["language"],
        "pages": bundle["source"]["pdf_page_count"],
        "ready_mapped_chapters": len(bundle["chapter_map"]),
        "quality_blocked_chapters": bundle["extraction"]["quality_blocked_chapters"],
        "quality_blocked_page_count": bundle["extraction"]["quality_blocked_page_count"],
        "source_sha256": bundle["source"]["pdf_sha256"],
        "status": bundle["bundle_status"],
        "student_delivery_allowed": False,
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
