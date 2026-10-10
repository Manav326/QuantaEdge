#!/usr/bin/env python3
"""Build a resumable, page-referenced draft text library from prepared textbook PDFs.

This tool never changes source PDFs and never publishes extracted text to QuantaEdge.
Each asset is keyed by its verified PDF SHA-256. Page records are checkpointed as JSONL,
so an interrupted run can continue without re-extracting already completed pages.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

EXTRACTOR_VERSION = "quantaedge-pymupdf-text-v1"
DEVANAGARI = re.compile(r"[\u0900-\u097f]")
MIN_REVIEW_CHARS = 40


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path}: expected a JSON object.")
    return value


def load_checkpoints(path: Path, asset_sha256: str, page_count: int) -> dict[int, dict[str, Any]]:
    """Load only complete, valid records; tolerate a truncated final line after a crash."""
    records: dict[int, dict[str, Any]] = {}
    if not path.is_file():
        return records
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                # The final line may have been interrupted during a previous write.
                continue
            if not isinstance(row, dict):
                continue
            # Records use the schema field asset_page_index; accept page_index for early drafts.
            page_index = row.get("asset_page_index", row.get("page_index"))
            if (row.get("asset_pdf_sha256") == asset_sha256
                    and row.get("extractor_version") == EXTRACTOR_VERSION
                    and isinstance(page_index, int) and 0 <= page_index < page_count
                    and isinstance(row.get("text"), str)):
                records[page_index] = row
    return records


def page_status(text: str) -> str:
    compact = "".join(text.split())
    if not compact:
        return "NO_EXTRACTABLE_TEXT_REVIEW"
    if len(compact) < MIN_REVIEW_CHARS:
        return "LOW_TEXT_REVIEW"
    if not DEVANAGARI.search(compact):
        return "NO_DEVANAGARI_REVIEW"
    return "TEXT_EXTRACTED_REQUIRES_CONTENT_REVIEW"


def write_record(handle: Any, record: dict[str, Any]) -> None:
    handle.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")
    handle.flush()


def process_bundle(bundle_path: Path, pdf_dir: Path, output_dir: Path, force: bool = False) -> dict[str, Any]:
    bundle = read_json(bundle_path)
    source = bundle.get("source")
    if not isinstance(source, dict):
        raise ValueError(f"{bundle_path}: missing source metadata.")
    filename = source.get("pdf_filename")
    expected_sha = source.get("pdf_sha256")
    expected_pages = source.get("pdf_page_count")
    if not isinstance(filename, str) or not filename or Path(filename).name != filename:
        raise ValueError(f"{bundle_path}: unsafe or missing source.pdf_filename.")
    if not isinstance(expected_sha, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", expected_sha):
        raise ValueError(f"{bundle_path}: invalid source.pdf_sha256.")
    if not isinstance(expected_pages, int) or expected_pages < 1 or expected_pages > 2000:
        raise ValueError(f"{bundle_path}: source.pdf_page_count must be in 1..2000.")

    pdf_path = pdf_dir / filename
    if not pdf_path.is_file():
        raise FileNotFoundError(f"PDF referenced by {bundle_path.name} is missing: {pdf_path}")
    actual_sha = sha256_file(pdf_path)
    if actual_sha.lower() != expected_sha.lower():
        raise ValueError(f"{pdf_path}: PDF SHA-256 does not match bundle metadata.")

    try:
        import pymupdf
    except ImportError as exc:
        raise RuntimeError("Missing PyMuPDF; install with python -m pip install pymupdf.") from exc

    with pymupdf.open(pdf_path) as document:
        if document.needs_pass:
            raise ValueError(f"{pdf_path}: password-protected PDFs are not supported.")
        page_count = len(document)
        if page_count != expected_pages:
            raise ValueError(f"{pdf_path}: expected {expected_pages} pages from bundle, found {page_count}.")
        if not 1 <= page_count <= 2000:
            raise ValueError(f"{pdf_path}: page count must be in 1..2000.")

        pages_dir = output_dir / "pages"
        pages_dir.mkdir(parents=True, exist_ok=True)
        output_path = pages_dir / (actual_sha.lower() + ".jsonl")
        manifest_path = pages_dir / (actual_sha.lower() + ".manifest.json")
        if force:
            output_path.unlink(missing_ok=True)
            manifest_path.unlink(missing_ok=True)

        checkpoints = load_checkpoints(output_path, actual_sha.lower(), page_count)
        # Normalize the file before appending, removing any interrupted/invalid trailing line.
        if output_path.exists():
            tmp = output_path.with_suffix(".jsonl.tmp")
            with tmp.open("w", encoding="utf-8", newline="\n") as handle:
                for page_index in sorted(checkpoints):
                    write_record(handle, checkpoints[page_index])
            tmp.replace(output_path)

        source_page_start = source.get("chapter_page_start")
        if not isinstance(source_page_start, int) or source_page_start < 1:
            source_page_start = 1
        completed_before = len(checkpoints)
        with output_path.open("a", encoding="utf-8", newline="\n") as handle:
            for page_index in range(page_count):
                if page_index in checkpoints:
                    continue
                text = document[page_index].get_text("text", sort=True) or ""
                compact = "".join(text.split())
                devanagari_count = len(DEVANAGARI.findall(text))
                record = {
                    "schema_version": 1,
                    "extractor_version": EXTRACTOR_VERSION,
                    "asset_pdf_sha256": actual_sha.lower(),
                    "original_book_sha256": source.get("original_book_sha256"),
                    "book_id": source.get("book_id"),
                    "title": source.get("title"),
                    "source_title": source.get("source_title"),
                    "subject": source.get("subject"),
                    "class": source.get("class"),
                    "language": source.get("language"),
                    "scope": source.get("scope"),
                    "chapter_title": source.get("chapter_title"),
                    "asset_page_index": page_index,
                    "asset_page_number": page_index + 1,
                    "source_book_page_number": source_page_start + page_index,
                    "text": text,
                    "non_whitespace_char_count": len(compact),
                    "devanagari_char_count": devanagari_count,
                    "has_extractable_text": bool(compact),
                    "has_devanagari": devanagari_count > 0,
                    "review_status": page_status(text),
                }
                write_record(handle, record)
                checkpoints[page_index] = record

    status_counts: dict[str, int] = {}
    for record in checkpoints.values():
        status = str(record.get("review_status", "UNKNOWN"))
        status_counts[status] = status_counts.get(status, 0) + 1
    manifest = {
        "schema_version": 1,
        "extractor_version": EXTRACTOR_VERSION,
        "created_at_utc": datetime.now(timezone.utc).isoformat(),
        "bundle_file": bundle_path.name,
        "asset_pdf_filename": filename,
        "asset_pdf_sha256": actual_sha.lower(),
        "original_book_sha256": source.get("original_book_sha256"),
        "book_id": source.get("book_id"),
        "title": source.get("title"),
        "source_title": source.get("source_title"),
        "subject": source.get("subject"),
        "class": source.get("class"),
        "language": source.get("language"),
        "scope": source.get("scope"),
        "chapter_title": source.get("chapter_title"),
        "source_page_start": source_page_start,
        "page_count": page_count,
        "completed_pages": len(checkpoints),
        "pages_newly_extracted": page_count - completed_before,
        "status_counts": status_counts,
        "content_state": "DRAFT_EXTRACTION_ONLY_REQUIRES_REVIEW",
        "text_jsonl": output_path.name,
        "published": False,
    }
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return manifest


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle-dir", required=True, type=Path)
    parser.add_argument("--pdf-dir", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--book-id", action="append", default=[], help="Limit to an exact book_id; may be repeated.")
    parser.add_argument("--force", action="store_true", help="Re-extract pages even if valid checkpoints exist.")
    args = parser.parse_args(argv)
    for label, path in (("bundle directory", args.bundle_dir), ("PDF directory", args.pdf_dir)):
        if not path.is_dir():
            parser.error(f"{label} does not exist: {path}")
    bundles = sorted(args.bundle_dir.glob("*.pdf.json"))
    if not bundles:
        parser.error(f"No *.pdf.json bundles found in {args.bundle_dir}")
    args.output_dir.mkdir(parents=True, exist_ok=True)

    results: list[dict[str, Any]] = []
    failures: list[dict[str, str]] = []
    for bundle_path in bundles:
        try:
            if args.book_id:
                metadata = read_json(bundle_path).get("source", {})
                if metadata.get("book_id") not in args.book_id:
                    continue
            result = process_bundle(bundle_path, args.pdf_dir, args.output_dir, args.force)
            results.append(result)
            print(f"OK {result['subject']} | {result['title']} | pages={result['completed_pages']} "
                  f"| new={result['pages_newly_extracted']} | {result['asset_pdf_sha256']}")
        except (OSError, ValueError, json.JSONDecodeError, RuntimeError) as exc:
            failures.append({"bundle": bundle_path.name, "error": str(exc)})
            print(f"ERROR {bundle_path.name}: {exc}", file=sys.stderr)
    report = {
        "scope": "Class 6 Hindi Mathematics and Science unless filtered by --book-id",
        "extractor_version": EXTRACTOR_VERSION,
        "assets_processed": len(results),
        "pages_processed": sum(item["completed_pages"] for item in results),
        "pages_newly_extracted": sum(item["pages_newly_extracted"] for item in results),
        "failures": failures,
        "publication": "NOT_PUBLISHED; extracted text is draft material requiring content review",
    }
    report_path = args.output_dir / "extraction-report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
