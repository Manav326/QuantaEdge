#!/usr/bin/env python3
"""Import root-level Class 6 NCERT English/Hindi textbook PDFs into QuantaEdge.

Default behavior is a dry run: scan the project root, validate PDF metadata, map every
chapter range, check existing assignments, and write an audit report. Add --apply to
upload each source PDF once and attach complete-book plus chapter-range assignments as
DRAFT. Publishing remains a separate human review action in Admin.

Recommended filenames:
  Class_6_Science_English.pdf
  Class_6_Mathematics_English.pdf
An NCERT downloaded filename such as ncert-c6-english-fecu1.pdf is also recognized.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
from pathlib import Path
from typing import Any
from urllib.parse import urlencode

try:
    import fitz
except ImportError as exc:
    raise SystemExit("PyMuPDF is required. Install with: python -m pip install 'PyMuPDF>=1.24,<2'") from exc

from import_quantaedge_learning_pdfs import api_call, api_url_valid, verify_session

ROOT = Path(__file__).resolve().parents[1]
MAX_PDF_BYTES = 500 * 1024 * 1024
MAX_PDF_PAGES = 2000

# Exact English source titles mapped to the canonical Class 6 curriculum codes.
CLASS6_CHAPTERS: dict[str, list[dict[str, Any]]] = {
    "science": [
        {"chapter_code": "wonderful-world-science", "title": "The Wonderful World of Science", "aliases": ["The Wonderful World of Science"]},
        {"chapter_code": "diversity-living-world", "title": "Diversity in the Living World", "aliases": ["Diversity in the Living World"]},
        {"chapter_code": "mindful-eating", "title": "Mindful Eating: A Path to a Healthy Body", "aliases": ["Mindful Eating: A Path to a Healthy Body"]},
        {"chapter_code": "exploring-magnets", "title": "Exploring Magnets", "aliases": ["Exploring Magnets"]},
        {"chapter_code": "measurement-length-motion", "title": "Measurement of Length and Motion", "aliases": ["Measurement of Length and Motion"]},
        {"chapter_code": "materials-around-us", "title": "Materials Around Us", "aliases": ["Materials Around Us"]},
        {"chapter_code": "temperature-measurement", "title": "Temperature and its Measurement", "aliases": ["Temperature and its Measurement", "Temperature and Measurement"]},
        {"chapter_code": "states-of-water", "title": "A Journey through States of Water", "aliases": ["A Journey through States of Water"]},
        {"chapter_code": "separation-methods", "title": "Methods of Separation in Everyday Life", "aliases": ["Methods of Separation in Everyday Life"]},
        {"chapter_code": "living-characteristics", "title": "Living Creatures: Exploring their Characteristics", "aliases": ["Living Creatures: Exploring their Characteristics"]},
        {"chapter_code": "natures-treasures", "title": "Nature's Treasures", "aliases": ["Nature's Treasures", "Natures Treasures"]},
        {"chapter_code": "beyond-earth", "title": "Beyond Earth", "aliases": ["Beyond Earth"]},
    ],
    "maths": [
        {"chapter_code": "patterns-in-mathematics", "title": "Patterns in Mathematics", "aliases": ["Patterns in Mathematics"]},
        {"chapter_code": "lines-and-angles", "title": "Lines and Angles", "aliases": ["Lines and Angles"]},
        {"chapter_code": "number-play", "title": "Number Play", "aliases": ["Number Play"]},
        {"chapter_code": "data-handling", "title": "Data Handling and Presentation", "aliases": ["Data Handling and Presentation", "Data Handling"]},
        {"chapter_code": "prime-time", "title": "Prime Time", "aliases": ["Prime Time"]},
        {"chapter_code": "perimeter-area", "title": "Perimeter and Area", "aliases": ["Perimeter and Area"]},
        {"chapter_code": "fractions", "title": "Fractions", "aliases": ["Fractions"]},
        {"chapter_code": "constructions", "title": "Playing with Constructions", "aliases": ["Playing with Constructions"]},
        {"chapter_code": "symmetry", "title": "Symmetry", "aliases": ["Symmetry"]},
        {"chapter_code": "integers-beyond-zero", "title": "The Other Side of Zero", "aliases": ["The Other Side of Zero"]},
    ],
}


def normalize_title(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", "", str(value or "").casefold())


def parse_textbook_filename(path: Path | str, default_language: str = "english") -> dict[str, str] | None:
    filename = Path(path).stem
    class_match = re.search(r"(?:class|grade|std|c)[\s_-]*(\d{1,2})", filename, re.I)
    if not class_match:
        return None
    class_code = class_match.group(1)
    tokens = re.findall(r"[a-z0-9]+", filename.casefold())
    if any(token in {"science", "sci"} or token.startswith(("fecu", "fhcu", "fesc", "fhsc")) for token in tokens):
        subject = "science"
    elif any(token in {"math", "maths", "mathematics"} or token.startswith(("fem", "fhgp", "fegp", "gegp", "hegp")) for token in tokens):
        subject = "maths"
    else:
        raise ValueError(f"Cannot infer the subject from {Path(path).name!r}. Include 'Science' or 'Mathematics'/'Maths' in the filename (or a recognized NCERT book code).")
    has_hindi = any(token in {"hindi", "hin", "hi"} for token in tokens) or "हिंदी" in filename
    has_english = any(token in {"english", "eng", "en"} for token in tokens)
    if has_hindi and has_english:
        raise ValueError(f"{Path(path).name!r} contains conflicting Hindi and English filename markers.")
    language = "hindi" if has_hindi else "english" if has_english else default_language
    return {"class_code": class_code, "subject_code": subject, "language": language, "filename": Path(path).name}


def title_appears(page_text: str, chapter: dict[str, Any]) -> bool:
    normalized = normalize_title(page_text)
    return any(normalize_title(alias) in normalized for alias in chapter["aliases"])


def _page_has_multiple_chapter_titles(page_text: str, chapters: list[dict[str, Any]]) -> bool:
    return sum(1 for chapter in chapters if title_appears(page_text, chapter)) > 1


def detect_chapter_ranges(pdf_path: Path | str, chapters: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Detect all chapter boundaries; fail closed if a title/page cannot be verified."""
    pdf_path = Path(pdf_path)
    with fitz.open(pdf_path) as pdf:
        page_count = pdf.page_count
        if not 1 <= page_count <= MAX_PDF_PAGES:
            raise ValueError(f"{pdf_path.name}: page count {page_count} is outside 1–{MAX_PDF_PAGES}.")
        page_texts = [page.get_text("text") or "" for page in pdf]
        start_by_index: dict[int, int] = {}
        for _level, outline_title, page_number in pdf.get_toc(simple=True):
            match = re.fullmatch(r"\s*chapter\s*0*(\d+)\s*", str(outline_title), re.I)
            target_index = int(match.group(1)) - 1 if match else None
            if target_index is None or not 0 <= target_index < len(chapters) or not 1 <= int(page_number) <= page_count:
                continue
            if title_appears(page_texts[int(page_number) - 1], chapters[target_index]):
                start_by_index.setdefault(target_index, int(page_number))

        for index, chapter in enumerate(chapters):
            if index in start_by_index:
                continue
            for page_number, page_text in enumerate(page_texts, start=1):
                if not title_appears(page_text, chapter):
                    continue
                if _page_has_multiple_chapter_titles(page_text, chapters):
                    continue
                normalized_prefix = normalize_title(page_text[:1800])
                if "contents" in normalized_prefix or "tableofcontents" in normalized_prefix:
                    continue
                if "chapter" not in page_text[:1800].casefold():
                    continue
                start_by_index[index] = page_number
                break

        missing = [chapter["title"] for i, chapter in enumerate(chapters) if i not in start_by_index]
        if missing:
            raise ValueError(f"{pdf_path.name}: could not verify chapter start page(s): {', '.join(missing)}. No chapter assignments were created.")
        starts = [start_by_index[i] for i in range(len(chapters))]
        if starts != sorted(set(starts)):
            raise ValueError(f"{pdf_path.name}: chapter start pages are duplicated or out of source order: {starts}")
        result = []
        for index, chapter in enumerate(chapters):
            start = starts[index]
            end = starts[index + 1] - 1 if index + 1 < len(starts) else page_count
            if start < 1 or end < start or end > page_count:
                raise ValueError(f"{pdf_path.name}: invalid range for {chapter['title']}: {start}–{end}.")
            result.append({"chapter_no": index + 1, "chapter_code": chapter["chapter_code"], "chapter_title": chapter["title"], "page_start": start, "page_end": end})
        return result


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def inspect_pdf(path: Path) -> dict[str, Any]:
    if not path.is_file():
        raise ValueError(f"PDF does not exist: {path}")
    size = path.stat().st_size
    if size <= 0 or size > MAX_PDF_BYTES:
        raise ValueError(f"{path.name}: PDF size {size} is outside 1 byte–500 MiB.")
    with path.open("rb") as handle:
        if handle.read(5) != b"%PDF-":
            raise ValueError(f"{path.name}: file does not have a valid PDF signature.")
    try:
        with fitz.open(path) as pdf:
            pages = pdf.page_count
            if not 1 <= pages <= MAX_PDF_PAGES:
                raise ValueError(f"{path.name}: page count {pages} is outside 1–{MAX_PDF_PAGES}.")
    except Exception as exc:
        if isinstance(exc, ValueError):
            raise
        raise ValueError(f"{path.name}: PDF could not be opened ({exc}).") from exc
    return {"path": str(path.resolve()), "filename": path.name, "size_bytes": size, "page_count": pages, "sha256": sha256_file(path)}


def source_title_for(class_code: str, subject: str) -> str:
    if class_code == "6" and subject == "science":
        return "NCERT Curiosity: Textbook of Science for Grade 6"
    if class_code == "6" and subject == "maths":
        return "NCERT Ganita Prakash: Mathematics Textbook for Grade 6"
    return f"NCERT Class {class_code} {subject.title()} textbook"


def student_title_for(meta: dict[str, str], scope: str, chapter: dict[str, Any] | None = None) -> str:
    subject_label = "Science" if meta["subject_code"] == "science" else "Mathematics"
    language_label = "English" if meta["language"] == "english" else "Hindi"
    base = f"Class {meta['class_code']} {subject_label} ({language_label})"
    if scope == "SUBJECT_BOOK":
        return f"{base} — Complete Book"
    assert chapter is not None
    return f"{base} — Chapter {int(chapter['chapter_no']):02d}: {chapter['chapter_title']}"


def import_plan_for_file(path: Path, meta: dict[str, str], args: argparse.Namespace) -> dict[str, Any]:
    info = inspect_pdf(path)
    if args.book_only:
        chapters: list[dict[str, Any]] = []
    else:
        specs = CLASS6_CHAPTERS.get(meta["subject_code"]) if meta["class_code"] == "6" else None
        if not specs:
            raise ValueError(f"{path.name}: chapter title mappings are currently verified for Class 6 Science and Mathematics. Use --book-only for a whole-book-only assignment, or add reviewed chapter aliases first.")
        chapters = detect_chapter_ranges(path, specs)
    return {**info, "class_code": meta["class_code"], "subject_code": meta["subject_code"], "language": meta["language"], "source_title": source_title_for(meta["class_code"], meta["subject_code"]), "chapter_ranges": chapters}


def slot_key(class_code: Any, subject: Any, language: Any, scope: Any, chapter_id: Any) -> tuple[str, str, str, str, int | None]:
    return (str(class_code), str(subject), str(language), str(scope), int(chapter_id) if chapter_id is not None else None)


def run(args: argparse.Namespace) -> int:
    root = Path(args.root).expanduser().resolve()
    if not root.is_dir():
        raise ValueError(f"Root directory does not exist: {root}")
    selected_pdfs = [Path(p).expanduser().resolve() for p in args.pdf] if args.pdf else sorted(root.glob("*.pdf"))
    pdfs: list[tuple[Path, dict[str, str]]] = []
    warnings: list[str] = []
    for path in selected_pdfs:
        try:
            meta = parse_textbook_filename(path, args.language)
            if meta is None:
                warnings.append(f"Skipped unrecognized filename (no Class/Grade number): {path.name}")
                continue
            if meta["language"] != args.language:
                warnings.append(f"Skipped {path.name}: filename identifies {meta['language']}, not requested {args.language}.")
                continue
            if args.class_no and meta["class_code"] != str(args.class_no):
                warnings.append(f"Skipped {path.name}: this run is restricted to Class {args.class_no}.")
                continue
            pdfs.append((path, meta))
        except ValueError as exc:
            warnings.append(str(exc))
    if not pdfs:
        raise ValueError(f"No supported Class {args.class_no or 'selected'} {args.language} textbook PDFs were found. Place files named like Class_6_Science_English.pdf and Class_6_Mathematics_English.pdf in the root.")

    plans = [import_plan_for_file(path, meta, args) for path, meta in pdfs]
    keys = [(plan["class_code"], plan["subject_code"], plan["language"]) for plan in plans]
    if len(keys) != len(set(keys)):
        raise ValueError("More than one PDF was selected for the same Class/subject/language slot. Keep one canonical root PDF or pass --pdf explicitly.")

    base = args.api_url.rstrip("/")
    api_url_valid(base)
    cookie = os.environ.get("QUANTAEDGE_ADMIN_SESSION", "")
    me = verify_session(base, cookie)
    print(f"Authenticated staff session: role={me.get('role')}; permission=CONTENT_EDIT")

    tracks: dict[tuple[str, str], list[dict[str, Any]]] = {}
    slots: dict[tuple[str, str, str, str, int | None], list[dict[str, Any]]] = {}
    assets = api_call(base, cookie, "/api/v1/admin/learning-pdfs")
    assets_by_sha = {str(row.get("sha256") or "").lower(): row for row in (assets if isinstance(assets, list) else []) if isinstance(row, dict) and row.get("sha256")}
    for plan in plans:
        track = (plan["class_code"], plan["subject_code"])
        if track not in tracks:
            query = urlencode({"classCode": track[0], "subjectCode": track[1]})
            chapter_rows = api_call(base, cookie, "/api/v1/admin/class-chapters?" + query)
            if not isinstance(chapter_rows, list):
                raise ValueError(f"Curriculum chapter response is invalid for Class {track[0]} / {track[1]}.")
            tracks[track] = chapter_rows
            existing = api_call(base, cookie, "/api/v1/admin/learning-documents?" + query + "&status=ALL")
            for row in existing if isinstance(existing, list) else []:
                if isinstance(row, dict):
                    key = slot_key(row.get("class_code"), row.get("subject_code"), row.get("language"), row.get("scope"), row.get("chapter_id"))
                    slots.setdefault(key, []).append(row)
        if not args.book_only:
            by_code: dict[str, list[dict[str, Any]]] = {}
            for row in tracks[track]:
                by_code.setdefault(str(row.get("chapter_code") or ""), []).append(row)
            for chapter in plan["chapter_ranges"]:
                matches = [row for row in by_code.get(chapter["chapter_code"], []) if row.get("chapter_id") is not None and row.get("active", True) is not False]
                if len(matches) != 1:
                    raise ValueError(f"No unique active curriculum chapter for Class {track[0]} / {track[1]} / {chapter['chapter_code']}. No PDFs were uploaded.")
                chapter["chapter_id"] = int(matches[0]["chapter_id"])
                chapter["curriculum_title"] = str(matches[0].get("chapter_name") or "")

    conflicts: list[str] = []
    for plan in plans:
        expected_slots = [slot_key(plan["class_code"], plan["subject_code"], plan["language"], "SUBJECT_BOOK", None)]
        expected_slots.extend(slot_key(plan["class_code"], plan["subject_code"], plan["language"], "CHAPTER_PDF", ch.get("chapter_id")) for ch in plan["chapter_ranges"])
        for slot in expected_slots:
            for row in slots.get(slot, []):
                existing_sha = str(row.get("sha256") or "").lower()
                if existing_sha and existing_sha != plan["sha256"]:
                    conflicts.append(f"{plan['filename']}: {slot[3]} slot for Class {slot[0]} / {slot[1]} / {slot[2]} already contains a different PDF (document #{row.get('document_id')}, status {row.get('status')}). Archive/review that assignment before replacing it.")
    if conflicts:
        raise ValueError("Existing textbook assignments need review; no files were uploaded:\n - " + "\n - ".join(conflicts))

    records: list[dict[str, Any]] = []
    for plan in plans:
        records.append({"filename": plan["filename"], "class_code": plan["class_code"], "subject_code": plan["subject_code"], "language": plan["language"], "page_count": plan["page_count"], "size_bytes": plan["size_bytes"], "sha256": plan["sha256"], "scope": "SUBJECT_BOOK", "chapter_code": None, "title": student_title_for(plan, "SUBJECT_BOOK"), "page_start": 1, "page_end": plan["page_count"]})
        for chapter in plan["chapter_ranges"]:
            records.append({"filename": plan["filename"], "class_code": plan["class_code"], "subject_code": plan["subject_code"], "language": plan["language"], "page_count": plan["page_count"], "size_bytes": plan["size_bytes"], "sha256": plan["sha256"], "scope": "CHAPTER_PDF", "chapter_code": chapter["chapter_code"], "chapter_id": chapter.get("chapter_id"), "title": student_title_for(plan, "CHAPTER_PDF", chapter), "page_start": chapter["page_start"], "page_end": chapter["page_end"], "chapter_no": chapter["chapter_no"], "chapter_title": chapter["chapter_title"]})
    for record in records:
        key = slot_key(record["class_code"], record["subject_code"], record["language"], record["scope"], record.get("chapter_id"))
        record["action"] = "SKIP_EXISTING" if any(str(row.get("sha256") or "").lower() == record["sha256"] for row in slots.get(key, [])) else ("READY_TO_CREATE_DRAFT" if args.apply else "WOULD_CREATE_DRAFT")

    report: dict[str, Any] = {
        "result": "PREFLIGHT_OK" if not args.apply else "APPLY_IN_PROGRESS",
        "mode": "apply" if args.apply else "dry_run",
        "root": str(root), "class_filter": args.class_no, "language_filter": args.language,
        "book_only": args.book_only, "authenticated_role": me.get("role"),
        "pdfs": plans, "assignments": records, "warnings": warnings,
        "created_assets": 0, "created_draft_assignments": 0, "skipped_existing": 0,
        "publish_actions_taken": 0,
        "note": "Dry-run writes no application data. Apply creates only DRAFT assignments; Admin must review and publish them separately."
    }
    report_path = Path(args.report).expanduser()
    if not report_path.is_absolute():
        report_path = root / report_path
    report_path.parent.mkdir(parents=True, exist_ok=True)

    if not args.apply:
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({k: v for k, v in report.items() if k not in {"pdfs", "assignments"}}, ensure_ascii=False, indent=2))
        for plan in plans:
            print(f"BOOK: Class {plan['class_code']} {plan['subject_code']} | {plan['filename']} | {plan['page_count']} pages | {len(plan['chapter_ranges'])} mapped chapters")
            for chapter in plan["chapter_ranges"]:
                print(f"  {chapter['chapter_no']:02d}. {chapter['chapter_title']}: pages {chapter['page_start']}–{chapter['page_end']}")
        print(f"Dry-run report: {report_path.resolve()}")
        return 0

    # Persist before the first write, then after each upload/assignment, for resumable imports.
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for plan in plans:
        sha = plan["sha256"]
        asset = assets_by_sha.get(sha)
        if asset is None:
            source_reference = ";".join(["asset_origin=ROOT_TEXTBOOK_IMPORT", "class=" + plan["class_code"], "subject=" + plan["subject_code"], "language=" + plan["language"], "filename=" + plan["filename"], "sha256=" + sha, "chapters=" + str(len(plan["chapter_ranges"]))])[:500]
            data = Path(plan["path"]).read_bytes()
            asset = api_call(base, cookie, "/api/v1/admin/learning-pdfs", method="POST",
                multipart=(f"{plan['source_title']} | Class {plan['class_code']} | {plan['subject_code']} | {plan['language']}", source_reference, plan["filename"], data),
                timeout=240)
            assets_by_sha[sha] = asset
            if not asset.get("duplicate"):
                report["created_assets"] += 1
        asset_id = asset.get("pdf_asset_id", asset.get("id"))
        if not asset_id:
            raise RuntimeError(f"Upload returned no PDF asset ID for {plan['filename']}. Check the checkpoint report before retrying.")
        plan["pdf_asset_id"] = int(asset_id)
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        for record in [r for r in records if r["filename"] == plan["filename"]]:
            if record["action"] == "SKIP_EXISTING":
                report["skipped_existing"] += 1
                report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
                continue
            body = {"pdfAssetId": int(asset_id), "scope": record["scope"], "classCode": record["class_code"], "subjectCode": record["subject_code"], "language": "en" if record["language"] == "english" else "hi", "chapterId": record.get("chapter_id"), "title": record["title"], "sourceTitle": plan["source_title"], "sourceUrl": "https://ncert.nic.in/textbook.php", "edition": "NCERT edition to be confirmed from source PDF", "pageStart": record["page_start"], "pageEnd": record["page_end"]}
            doc = api_call(base, cookie, "/api/v1/admin/learning-documents", method="POST", body=body)
            if str(doc.get("status", "")).upper() != "DRAFT":
                raise RuntimeError(f"Assignment {record['title']} was not returned as DRAFT. Stop and inspect the checkpoint report.")
            record["action"] = "CREATED_DRAFT"
            record["document_id"] = doc.get("document_id", doc.get("id"))
            report["created_draft_assignments"] += 1
            report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    report["result"] = "COMPLETED"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k not in {"pdfs", "assignments"}}, ensure_ascii=False, indent=2))
    print(f"Import report: {report_path.resolve()}")
    print("Complete-book and chapter assignments remain DRAFT. Review and publish each in Admin → Textbook library.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=str(ROOT), help="Project root to scan for root-level PDFs.")
    parser.add_argument("--pdf", action="append", default=[], help="Import one explicit PDF path; may be repeated.")
    parser.add_argument("--class-no", default="6", help="Class to scan (default: 6).")
    parser.add_argument("--language", choices=("english", "hindi"), default="english", help="Language filter; filenames without an explicit medium use this default.")
    parser.add_argument("--api-url", default=os.environ.get("QUANTAEDGE_API_BASE_URL", "http://localhost:8080"))
    parser.add_argument("--book-only", action="store_true", help="Create only a complete-book assignment; do not detect chapter ranges.")
    parser.add_argument("--apply", action="store_true", help="Upload PDFs and create DRAFT assignments. Default is a no-write dry run.")
    parser.add_argument("--report", default="root-textbook-pdf-import-report.json", help="JSON audit report path (relative paths are saved under --root).")
    args = parser.parse_args()
    try:
        return run(args)
    except Exception as exc:
        print(f"STOPPED SAFELY: {exc}", file=sys.stderr)
        print("No additional writes were started. Fix the reported source/mapping issue before retrying.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
