#!/usr/bin/env python3
"""Create a page-addressable SCERT source review bundle; never publish extracted text directly.

Requires Python 3.10+ and PyMuPDF (pip install pymupdf). Optional OCR uses
PyMuPDF's Tesseract integration and requires Tesseract plus Hindi/English language data.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_chapter_map(path: Path | None, page_count: int) -> list[dict[str, Any]]:
    if path is None:
        return []
    data = json.loads(path.read_text(encoding="utf-8"))
    chapters = data.get("chapters") if isinstance(data, dict) else None
    if not isinstance(chapters, list):
        raise ValueError("Chapter map must be a JSON object with a 'chapters' array.")
    seen_codes: set[str] = set()
    ranges: list[tuple[int, int, str]] = []
    normalized: list[dict[str, Any]] = []
    for item in chapters:
        if not isinstance(item, dict):
            raise ValueError("Every chapter-map entry must be an object.")
        code = str(item.get("code", "")).strip()
        title = str(item.get("title", "")).strip()
        start = item.get("page_start")
        end = item.get("page_end")
        if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", code):
            raise ValueError(f"Invalid chapter code: {code!r}")
        if not title:
            raise ValueError(f"Chapter {code} needs a title.")
        if code in seen_codes:
            raise ValueError(f"Duplicate chapter code: {code}")
        if not isinstance(start, int) or not isinstance(end, int) or start < 1 or end < start or end > page_count:
            raise ValueError(f"Invalid page range for {code}; use 1-based PDF page numbers.")
        for old_start, old_end, old_code in ranges:
            if max(start, old_start) <= min(end, old_end):
                raise ValueError(f"Chapter page ranges overlap: {code} and {old_code}.")
        seen_codes.add(code)
        ranges.append((start, end, code))
        normalized.append({"code": code, "title": title, "page_start": start, "page_end": end})
    return normalized


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pdf", required=True, type=Path, help="Official source PDF downloaded for internal review")
    parser.add_argument("--class-code", required=True, choices=("6", "7", "8"))
    parser.add_argument("--subject-code", required=True, choices=("maths", "science"))
    parser.add_argument("--source-title", required=True)
    parser.add_argument("--source-url", required=True, help="Canonical SCERT source URL")
    parser.add_argument("--edition", default="unknown", help="Edition/session shown by the source")
    parser.add_argument("--publication-year", type=int)
    parser.add_argument("--language", default="hi")
    parser.add_argument("--chapter-map", type=Path, help="Reviewed JSON chapter/page map; ranges are 1-based PDF pages")
    parser.add_argument("--ocr", action="store_true", help="OCR pages with too little extractable text")
    parser.add_argument("--ocr-language", default="hin+eng")
    parser.add_argument("--ocr-min-chars", type=int, default=40)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    if not args.pdf.is_file():
        parser.error(f"PDF does not exist: {args.pdf}")
    if not args.source_url.startswith("https://"):
        parser.error("--source-url must be an HTTPS URL.")
    if args.ocr_min_chars < 0:
        parser.error("--ocr-min-chars must be non-negative.")

    try:
        import pymupdf
    except ImportError:
        print("Missing dependency: install PyMuPDF with 'python -m pip install pymupdf'.", file=sys.stderr)
        return 2

    try:
        document = pymupdf.open(args.pdf)
        if document.needs_pass:
            print("Password-protected PDFs are not supported by this extraction workflow.", file=sys.stderr)
            return 2
        chapter_map = load_chapter_map(args.chapter_map, len(document))
        pages: list[dict[str, Any]] = []
        ocr_pages: list[int] = []
        for page_index, page in enumerate(document):
            page_number = page_index + 1
            text = page.get_text("text").strip()
            if args.ocr and len(text) < args.ocr_min_chars:
                try:
                    text_page = page.get_textpage_ocr(language=args.ocr_language, dpi=200, full=True)
                    ocr_text = page.get_text("text", textpage=text_page).strip()
                    if ocr_text:
                        text = ocr_text
                    ocr_pages.append(page_number)
                except Exception as exc:
                    raise RuntimeError(
                        f"OCR failed on PDF page {page_number}. Check Tesseract and language data: {exc}"
                    ) from exc
            chapter = next(
                (entry for entry in chapter_map if entry["page_start"] <= page_number <= entry["page_end"]),
                None,
            )
            pages.append({
                "page_number": page_number,
                "chapter_code": chapter["code"] if chapter else None,
                "chapter_title": chapter["title"] if chapter else None,
                "text": text,
                "text_sha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
                "extraction_method": "OCR" if page_number in ocr_pages else "PDF_TEXT",
                "needs_human_review": True,
            })
        document.close()
    except (ValueError, json.JSONDecodeError, RuntimeError) as exc:
        print(str(exc), file=sys.stderr)
        return 2

    bundle = {
        "schema_version": 1,
        "bundle_status": "DRAFT_EXTRACTION_ONLY",
        "created_at": datetime.now(timezone.utc).isoformat(),
        "curriculum": {
            "board": "Bihar Board",
            "class_code": args.class_code,
            "subject_code": args.subject_code,
            "language": args.language,
        },
        "source": {
            "title": args.source_title.strip(),
            "url": args.source_url,
            "edition": args.edition,
            "publication_year": args.publication_year,
            "pdf_filename": args.pdf.name,
            "pdf_sha256": sha256_file(args.pdf),
            "pdf_page_count": len(pages),
        },
        "chapter_map": chapter_map,
        "extraction": {
            "ocr_enabled": args.ocr,
            "ocr_language": args.ocr_language if args.ocr else None,
            "ocr_pages": ocr_pages,
            "pages_with_no_text": [p["page_number"] for p in pages if not p["text"]],
            "requires_human_review": True,
            "student_delivery_allowed": False,
            "copyright_notice": (
                "Internal source-review material only. Do not publish extracted textbook text, "
                "page images, or copied questions to students without the required rights. "
                "Create original explanations and questions and retain accurate source references."
            ),
        },
        "pages": pages,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(bundle, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "output": str(args.output),
        "pdf_sha256": bundle["source"]["pdf_sha256"],
        "pages": len(pages),
        "mapped_pages": sum(1 for p in pages if p["chapter_code"]),
        "ocr_pages": ocr_pages,
        "unmapped_pages": sum(1 for p in pages if not p["chapter_code"]),
        "status": bundle["bundle_status"],
        "student_delivery_allowed": False,
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
