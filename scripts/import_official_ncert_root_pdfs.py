#!/usr/bin/env python3
"""Import exact local NCERT whole-book and chapter PDFs as QuantaEdge DRAFT assignments.

Files stay byte-for-byte unchanged. The script does not split, rewrite, OCR, or publish PDFs.
Default mode is a full dry run. It checks the active curriculum, every required file, PDF
structure/size/page count, edition/source metadata, existing assets, and assignments before
any writes. With --apply, it uploads each distinct PDF once and creates only DRAFT assignments.

Workflow:
  1. Sign in to QuantaEdge Admin and set QUANTAEDGE_ADMIN_SESSION to the QE_SESSION cookie.
  2. Run --write-template once; it queries the live Class 6-8 Maths/Science curriculum.
  3. Rename local PDFs to the filenames in ncert-pdf-manifest.csv and fill edition for the
     12 BOOK rows. Chapter rows inherit edition/source URL from their matching whole book.
  4. Run a dry run; fix every missing file or mapping error.
  5. Run again with --apply. Publication remains in Admin → Textbook library and requires
     CONTENT_PUBLISH. Existing published documents are not changed by this import.

Filename pattern examples:
  NCERT_Class_6_Maths_English_BOOK.pdf
  NCERT_Class_6_Maths_Hindi_BOOK.pdf
  NCERT_Class_6_Maths_English_CHAPTER_patterns-in-mathematics.pdf
  NCERT_Class_6_Science_Hindi_CHAPTER_beyond-earth.pdf
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import re
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import urlencode, urlsplit

try:
    import fitz
except ImportError as exc:
    raise SystemExit(
        "PyMuPDF is required. Install it with: "
        'python -m pip install "PyMuPDF>=1.24,<2"'
    ) from exc

from import_quantaedge_learning_pdfs import api_call, api_url_valid, verify_session

ROOT = Path(__file__).resolve().parents[1]
MANIFEST_NAME = "ncert-pdf-manifest.csv"
OFFICIAL_CATALOG_URL = "https://ncert.nic.in/textbook.php"
CLASSES = ("6", "7", "8")
SUBJECTS = ("maths", "science")
LANGUAGES = ("english", "hindi")
MAX_PAGES = 2000
ADMIN_MAX_BYTES = 500 * 1024 * 1024
STANDARD_MAX_BYTES = 50 * 1024 * 1024
MANIFEST_FIELDS = [
    "class_code", "subject_code", "language", "scope", "chapter_code",
    "chapter_title", "filename", "edition", "source_url", "document_title",
]
PLACEHOLDER_EDITION_TERMS = (
    "pending", "to be confirmed", "confirm from", "unknown", "unverified",
    "tbd", "todo", "placeholder", "enter edition",
)


def subject_label(subject: str) -> str:
    return "Mathematics" if subject == "maths" else "Science"


def filename_subject(subject: str) -> str:
    return "Maths" if subject == "maths" else "Science"


def filename_language(language: str) -> str:
    return "English" if language == "english" else "Hindi"


def normal_language(value: Any) -> str:
    token = str(value or "").strip().casefold()
    aliases = {"en": "english", "eng": "english", "english": "english",
               "hi": "hindi", "hin": "hindi", "hindi": "hindi"}
    if token not in aliases:
        raise ValueError(f"Unsupported language {value!r}; use english or hindi.")
    return aliases[token]


def normal_subject(value: Any) -> str:
    token = str(value or "").strip().casefold()
    aliases = {"math": "maths", "maths": "maths", "mathematics": "maths",
               "sci": "science", "science": "science"}
    if token not in aliases:
        raise ValueError(f"Unsupported subject {value!r}; use maths or science.")
    return aliases[token]


def safe_chapter_slug(chapter_code: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", str(chapter_code or "").casefold()).strip("-")
    if not slug or slug != str(chapter_code or "").casefold():
        raise ValueError(
            f"Curriculum chapter code {chapter_code!r} is not a safe filename slug. "
            "Do not rename it by guesswork; correct the code or script mapping first."
        )
    return slug


def expected_filename(
    class_code: str, subject: str, language: str, scope: str, chapter_code: str = ""
) -> str:
    prefix = (
        f"NCERT_Class_{class_code}_{filename_subject(subject)}_"
        f"{filename_language(language)}"
    )
    if scope == "SUBJECT_BOOK":
        if chapter_code:
            raise ValueError("A BOOK row must not have a chapter_code.")
        return prefix + "_BOOK.pdf"
    if scope != "CHAPTER_PDF" or not chapter_code:
        raise ValueError("A CHAPTER_PDF row needs an exact chapter_code.")
    return prefix + "_CHAPTER_" + safe_chapter_slug(chapter_code) + ".pdf"


def default_document_title(
    class_code: str, subject: str, language: str, scope: str, chapter_code: str = ""
) -> str:
    lang_label = filename_language(language)
    base = f"NCERT | Class {class_code} | {subject_label(subject)} | {lang_label}"
    if scope == "SUBJECT_BOOK":
        return base + " | Complete Book"
    readable_code = chapter_code.replace("-", " ").title()
    return base + " | Chapter: " + readable_code


def is_active_chapter(row: dict[str, Any]) -> bool:
    raw = row.get("active", row.get("chapter_active", True))
    return raw is not False and str(raw).strip().casefold() not in {"false", "0", "no"}


def query_chapters(base_url: str, cookie: str) -> dict[tuple[str, str], list[dict[str, Any]]]:
    """Get the currently active chapter IDs/codes from the live Admin curriculum API."""
    result: dict[tuple[str, str], list[dict[str, Any]]] = {}
    for grade in CLASSES:
        for subject in SUBJECTS:
            query = urlencode({"classCode": grade, "subjectCode": subject})
            rows = api_call(base_url, cookie, "/api/v1/admin/class-chapters?" + query)
            if not isinstance(rows, list):
                raise RuntimeError(
                    f"Class-chapters API returned an unexpected response for "
                    f"Class {grade} / {subject}; no changes were made."
                )
            active = [
                row for row in rows
                if isinstance(row, dict)
                and row.get("chapter_id") is not None
                and is_active_chapter(row)
            ]
            if not active:
                raise RuntimeError(
                    f"No active chapter mapping was returned for Class {grade} / {subject}. "
                    "Stop and fix/confirm the active curriculum before generating a manifest."
                )
            by_code: dict[str, dict[str, Any]] = {}
            for row in active:
                code = str(row.get("chapter_code") or "").strip()
                if not code:
                    raise RuntimeError(
                        f"An active chapter has no chapter_code in Class {grade} / {subject}."
                    )
                safe_chapter_slug(code)
                if code in by_code:
                    raise RuntimeError(
                        f"Duplicate active chapter_code {code!r} in Class {grade} / {subject}."
                    )
                by_code[code] = row
            result[(grade, subject)] = sorted(
                by_code.values(),
                key=lambda row: (
                    int(row.get("chapter_sort_order") or row.get("sort_order") or 0),
                    str(row.get("chapter_code") or ""),
                ),
            )
    return result


def make_manifest_rows(
    chapters_by_track: dict[tuple[str, str], list[dict[str, Any]]]
) -> list[dict[str, str]]:
    """Build 12 whole-book rows and one chapter row per active chapter/language."""
    rows: list[dict[str, str]] = []
    for grade in CLASSES:
        for subject in SUBJECTS:
            chapters = chapters_by_track.get((grade, subject))
            if not chapters:
                raise ValueError(f"No active curriculum chapters were loaded for Class {grade}/{subject}.")
            for language in LANGUAGES:
                rows.append({
                    "class_code": grade,
                    "subject_code": subject,
                    "language": language,
                    "scope": "SUBJECT_BOOK",
                    "chapter_code": "",
                    "chapter_title": "",
                    "filename": expected_filename(grade, subject, language, "SUBJECT_BOOK"),
                    "edition": "",
                    "source_url": OFFICIAL_CATALOG_URL,
                    "document_title": default_document_title(grade, subject, language, "SUBJECT_BOOK"),
                })
                for chapter in chapters:
                    code = str(chapter["chapter_code"])
                    chapter_title = str(chapter.get("chapter_name") or code)
                    rows.append({
                        "class_code": grade,
                        "subject_code": subject,
                        "language": language,
                        "scope": "CHAPTER_PDF",
                        "chapter_code": code,
                        "chapter_title": chapter_title,
                        "filename": expected_filename(grade, subject, language, "CHAPTER_PDF", code),
                        "edition": "",
                        "source_url": "",
                        "document_title": default_document_title(
                            grade, subject, language, "CHAPTER_PDF", code
                        ),
                    })
    return rows


def write_manifest_template(path: Path, rows: list[dict[str, str]]) -> None:
    if path.exists():
        raise FileExistsError(
            f"{path} already exists. It may contain your edition/source edits, so it was not "
            "overwritten. Rename/archive it manually before generating a fresh template."
        )
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=MANIFEST_FIELDS, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def slot_key(
    class_code: Any, subject_code: Any, language: Any, scope: Any, chapter_id: Any
) -> tuple[str, str, str, str, int | None]:
    return (
        str(class_code), str(subject_code), str(language), str(scope),
        int(chapter_id) if chapter_id is not None else None,
    )


def manifest_key(row: dict[str, str]) -> tuple[str, str, str, str, str]:
    return (
        row["class_code"], row["subject_code"], row["language"],
        row["scope"], row["chapter_code"],
    )


def read_manifest(path: Path) -> list[dict[str, str]]:
    if not path.is_file():
        raise FileNotFoundError(
            f"Manifest not found: {path}. First run this script with --write-template."
        )
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        missing_headers = set(MANIFEST_FIELDS) - set(reader.fieldnames or [])
        if missing_headers:
            raise ValueError(
                "Manifest is missing required columns: " + ", ".join(sorted(missing_headers))
            )
        rows = []
        for line_no, raw in enumerate(reader, 2):
            row = {key: str(raw.get(key) or "").strip() for key in MANIFEST_FIELDS}
            if not any(row.values()):
                continue
            try:
                grade = row["class_code"]
                if grade not in CLASSES:
                    raise ValueError("class_code must be 6, 7, or 8")
                subject = normal_subject(row["subject_code"])
                language = normal_language(row["language"])
                scope = row["scope"].upper()
                if scope not in {"SUBJECT_BOOK", "CHAPTER_PDF"}:
                    raise ValueError("scope must be SUBJECT_BOOK or CHAPTER_PDF")
                code = row["chapter_code"].strip()
                expected = expected_filename(grade, subject, language, scope, code)
                filename = row["filename"]
                if not filename or Path(filename).name != filename or "/" in filename or "\\" in filename:
                    raise ValueError("filename must be a root-level filename, not a path")
                if filename.casefold() != expected.casefold():
                    raise ValueError(f"filename must be exactly {expected}")
                if not filename.lower().endswith(".pdf"):
                    raise ValueError("filename must end in .pdf")
                row["subject_code"] = subject
                row["language"] = language
                row["scope"] = scope
                row["chapter_code"] = code
                row["source_url"] = row["source_url"].strip()
                row["edition"] = row["edition"].strip()
                row["document_title"] = row["document_title"].strip()
            except ValueError as exc:
                raise ValueError(f"{path.name}:{line_no}: {exc}") from exc
            rows.append(row)
    if not rows:
        raise ValueError(f"{path} has no data rows.")
    keys = [manifest_key(row) for row in rows]
    if len(keys) != len(set(keys)):
        raise ValueError("Manifest has duplicate book/chapter slots. No changes were made.")
    filenames = [row["filename"].casefold() for row in rows]
    if len(filenames) != len(set(filenames)):
        raise ValueError("Manifest assigns the same filename to more than one slot.")
    return rows


def validate_manifest_against_curriculum(
    rows: list[dict[str, str]],
    chapters_by_track: dict[tuple[str, str], list[dict[str, Any]]],
) -> tuple[list[dict[str, Any]], dict[tuple[str, str, str], dict[str, str]]]:
    """Require exact live curriculum coverage, canonical chapter mapping and book metadata."""
    book_slots = {
        (grade, subject, language, "SUBJECT_BOOK", "")
        for grade in CLASSES for subject in SUBJECTS for language in LANGUAGES
    }
    chapter_slots = {
        (grade, subject, language, "CHAPTER_PDF", str(chapter["chapter_code"]))
        for (grade, subject), chapters in chapters_by_track.items()
        for language in LANGUAGES for chapter in chapters
    }
    expected = book_slots | chapter_slots
    actual = set(manifest_key(row) for row in rows)
    missing = sorted(expected - actual)
    unexpected = sorted(actual - expected)
    if missing or unexpected:
        fragments = []
        if missing:
            fragments.append(f"{len(missing)} expected slot(s) missing; first few: {missing[:8]}")
        if unexpected:
            fragments.append(f"{len(unexpected)} slot(s) not in current curriculum; first few: {unexpected[:8]}")
        raise ValueError(
            "Manifest does not match the live active curriculum. No PDFs were uploaded. "
            + " ".join(fragments)
            + " Regenerate the template only after preserving your edited edition/source values."
        )

    chapter_lookup: dict[tuple[str, str, str], dict[str, Any]] = {}
    for (grade, subject), chapters in chapters_by_track.items():
        for chapter in chapters:
            chapter_lookup[(grade, subject, str(chapter["chapter_code"]))] = chapter

    book_metadata: dict[tuple[str, str, str], dict[str, str]] = {}
    prepared: list[dict[str, Any]] = []
    for row in rows:
        row = dict(row)
        grade, subject, language = row["class_code"], row["subject_code"], row["language"]
        key = (grade, subject, language)
        if row["scope"] == "SUBJECT_BOOK":
            edition = row["edition"].strip()
            if not edition:
                raise ValueError(
                    f"{row['filename']}: fill the printed edition/reprint/session in the BOOK row's edition column."
                )
            if len(edition) > 160 or any(term in edition.casefold() for term in PLACEHOLDER_EDITION_TERMS):
                raise ValueError(
                    f"{row['filename']}: edition {edition!r} looks like a placeholder. "
                    "Enter the edition/reprint printed in the actual NCERT PDF."
                )
            source_url = row["source_url"].strip()
            validate_https_url(source_url, f"{row['filename']} source_url")
            book_metadata[key] = {"edition": edition, "source_url": source_url}
            row["chapter_id"] = None
            row["chapter_title_live"] = ""
        else:
            chapter = chapter_lookup.get((grade, subject, row["chapter_code"]))
            if chapter is None:
                raise ValueError(
                    f"{row['filename']}: chapter code is not an active chapter in the live curriculum."
                )
            if row["chapter_title"] and row["chapter_title"] != str(chapter.get("chapter_name") or ""):
                raise ValueError(
                    f"{row['filename']}: chapter_title changed or is stale. Regenerate the template "
                    "or restore the curriculum chapter title; edit document_title for display wording."
                )
            row["chapter_id"] = int(chapter["chapter_id"])
            row["chapter_title_live"] = str(chapter.get("chapter_name") or row["chapter_code"])
        row["path"] = ""
        row["title"] = row["document_title"] or default_document_title(
            grade, subject, language, row["scope"], row["chapter_code"]
        )
        if not row["title"].strip() or len(row["title"]) > 240:
            raise ValueError(f"{row['filename']}: document_title must contain 1–240 characters.")
        prepared.append(row)

    # Chapters inherit the edition and official URL from their corresponding whole-book row.
    for row in prepared:
        key = (row["class_code"], row["subject_code"], row["language"])
        parent = book_metadata.get(key)
        if parent is None:
            raise ValueError(f"No complete-book metadata exists for {key}.")
        row["edition"] = row["edition"].strip() or parent["edition"]
        row["source_url"] = row["source_url"].strip() or parent["source_url"]
        validate_https_url(row["source_url"], f"{row['filename']} source_url")
        if len(row["edition"]) > 160 or any(
            term in row["edition"].casefold() for term in PLACEHOLDER_EDITION_TERMS
        ):
            raise ValueError(f"{row['filename']}: edition value is not a verified edition description.")
        row["source_title"] = (
            f"National Council of Educational Research and Training (NCERT) — "
            f"Class {row['class_code']} {subject_label(row['subject_code'])} textbook"
        )
    return prepared, book_metadata


def validate_https_url(value: str, label: str) -> None:
    parsed = urlsplit(value)
    if parsed.scheme != "https" or not parsed.netloc or len(value) > 2000:
        raise ValueError(f"{label} must be an official HTTPS URL (up to 2,000 characters).")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def inspect_pdf(path: Path, max_bytes: int) -> dict[str, Any]:
    if not path.is_file():
        raise FileNotFoundError(f"Required PDF is missing: {path.name}")
    size = path.stat().st_size
    if size <= 0 or size > max_bytes:
        raise ValueError(
            f"{path.name}: size {size:,} bytes exceeds this account's permitted "
            f"{max_bytes // (1024 * 1024)} MiB upload limit or is empty."
        )
    with path.open("rb") as handle:
        if handle.read(5) != b"%PDF-":
            raise ValueError(f"{path.name}: file does not have a PDF signature.")
    try:
        with fitz.open(path) as pdf:
            pages = int(pdf.page_count)
    except Exception as exc:
        raise ValueError(f"{path.name}: PDF cannot be opened/validated ({exc}).") from exc
    if not 1 <= pages <= MAX_PAGES:
        raise ValueError(f"{path.name}: {pages} pages is outside the allowed 1–{MAX_PAGES} range.")
    return {
        "path": str(path.resolve()),
        "filename": path.name,
        "size_bytes": size,
        "page_count": pages,
        "sha256": sha256_file(path),
    }


def find_report_path(root: Path, configured: str) -> Path:
    if configured:
        path = Path(configured).expanduser()
        return path.resolve() if path.is_absolute() else (root / path).resolve()
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    return root / "reports" / "ncert-pdf-imports" / f"ncert-root-pdf-import-{stamp}.json"


def write_report(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temp.replace(path)


def load_assets(base: str, cookie: str) -> dict[str, dict[str, Any]]:
    rows = api_call(base, cookie, "/api/v1/admin/learning-pdfs")
    if not isinstance(rows, list):
        raise RuntimeError("PDF library API returned an unexpected response; no changes were made.")
    return {
        str(row.get("sha256") or "").lower(): row
        for row in rows
        if isinstance(row, dict) and row.get("sha256")
    }


def current_assignments(
    base: str,
    cookie: str,
    tracks: set[tuple[str, str]],
) -> tuple[dict[tuple[str, str, str, str, int | None], set[str]], dict[str, int]]:
    """Existing assignments by slot/hash; different hashes may safely coexist as DRAFT versions."""
    hashes_by_slot: dict[tuple[str, str, str, str, int | None], set[str]] = {}
    status_by_hash: dict[str, int] = {}
    for grade, subject in sorted(tracks):
        query = urlencode({"classCode": grade, "subjectCode": subject, "status": "ALL"})
        rows = api_call(base, cookie, "/api/v1/admin/learning-documents?" + query)
        if not isinstance(rows, list):
            raise RuntimeError(
                f"Learning document list API returned an unexpected response for Class {grade}/{subject}."
            )
        for row in rows:
            if not isinstance(row, dict):
                continue
            key = slot_key(
                row.get("class_code"), row.get("subject_code"), row.get("language"),
                row.get("scope"), row.get("chapter_id"),
            )
            digest = str(row.get("sha256") or "").lower()
            if digest:
                hashes_by_slot.setdefault(key, set()).add(digest)
    return hashes_by_slot, status_by_hash


def prepare_records(
    root: Path,
    manifest_rows: list[dict[str, str]],
    chapters_by_track: dict[tuple[str, str], list[dict[str, Any]]],
    max_bytes: int,
) -> list[dict[str, Any]]:
    prepared, _ = validate_manifest_against_curriculum(manifest_rows, chapters_by_track)
    records: list[dict[str, Any]] = []
    missing: list[str] = []
    for row in prepared:
        path = (root / row["filename"]).resolve()
        if path.parent != root.resolve():
            raise ValueError(f"{row['filename']}: PDFs must be directly in the project root.")
        try:
            info = inspect_pdf(path, max_bytes)
        except FileNotFoundError as exc:
            missing.append(str(exc))
            continue
        records.append({**row, **info})
    if missing:
        preview = "\n - ".join(missing[:20])
        suffix = "" if len(missing) <= 20 else f"\n - ... and {len(missing) - 20} more missing files"
        raise FileNotFoundError(
            f"{len(missing)} required root-level PDFs are missing. Nothing was uploaded. "
            "Rename each original file exactly as shown in ncert-pdf-manifest.csv.\n - "
            + preview + suffix
        )
    file_names = [row["filename"].casefold() for row in records]
    if len(file_names) != len(set(file_names)):
        raise ValueError("Two manifest rows resolve to the same filename.")
    digests: dict[str, list[str]] = {}
    for row in records:
        digests.setdefault(row["sha256"], []).append(row["filename"])
    # Identical bytes can be referenced by more than one assignment; the library asset is uploaded once.
    for row in records:
        row["shared_sha_filenames"] = digests[row["sha256"]]
    return records


def make_assignments(
    records: list[dict[str, Any]],
    chapters_by_track: dict[tuple[str, str], list[dict[str, Any]]],
    hashes_by_slot: dict[tuple[str, str, str, str, int | None], set[str]],
) -> list[dict[str, Any]]:
    output = []
    for row in records:
        chapter_id = row.get("chapter_id")
        key = slot_key(
            row["class_code"], row["subject_code"],
            "en" if row["language"] == "english" else "hi",
            row["scope"], chapter_id,
        )
        existing_hashes = hashes_by_slot.get(key, set())
        row_action = "SKIP_IDENTICAL_ASSIGNMENT" if row["sha256"].lower() in existing_hashes else "WOULD_CREATE_DRAFT"
        output.append({
            "filename": row["filename"],
            "class_code": row["class_code"],
            "subject_code": row["subject_code"],
            "language": row["language"],
            "scope": row["scope"],
            "chapter_code": row["chapter_code"] or None,
            "chapter_id": chapter_id,
            "chapter_title": row.get("chapter_title_live") or None,
            "title": row["title"],
            "page_count": row["page_count"],
            "size_bytes": row["size_bytes"],
            "sha256": row["sha256"],
            "edition": row["edition"],
            "source_url": row["source_url"],
            "source_title": row["source_title"],
            "page_start": 1,
            "page_end": row["page_count"],
            "action": row_action,
            "asset_id": None,
            "document_id": None,
            "status": "PENDING",
            "note": (
                "Existing identical assignment found; no duplicate will be created."
                if row_action == "SKIP_IDENTICAL_ASSIGNMENT"
                else "A different existing version, including a published version, is left untouched. "
                     "The replacement will be a DRAFT until an authorised publisher selects it."
            ),
        })
    return output


def run(args: argparse.Namespace) -> int:
    root = Path(args.root).expanduser().resolve()
    if not root.is_dir():
        raise ValueError(f"Root directory does not exist: {root}")
    manifest_path = Path(args.manifest).expanduser()
    if not manifest_path.is_absolute():
        manifest_path = root / manifest_path
    base = args.api_url.rstrip("/")
    api_url_valid(base)
    cookie = os.environ.get("QUANTAEDGE_ADMIN_SESSION", "").strip()
    me = verify_session(base, cookie)
    role = str(me.get("role") or "")
    max_bytes = ADMIN_MAX_BYTES if role.casefold() == "admin" else STANDARD_MAX_BYTES
    print(f"Authenticated staff: role={role or 'unknown'}; CONTENT_EDIT confirmed.")
    print(f"Per-file upload limit for this staff role: {max_bytes // (1024 * 1024)} MiB.")

    chapters_by_track = query_chapters(base, cookie)
    if args.write_template:
        template = make_manifest_rows(chapters_by_track)
        write_manifest_template(manifest_path, template)
        book_count = sum(1 for row in template if row["scope"] == "SUBJECT_BOOK")
        chapter_count = len(template) - book_count
        print(f"Template created: {manifest_path}")
        print(f"Rows: {book_count} whole books + {chapter_count} chapter PDFs = {len(template)} PDF assignments.")
        print("Rename each unchanged source PDF to the corresponding filename in the CSV.")
        print("Fill the edition column on each of the 12 BOOK rows using the printed NCERT edition/reprint/session.")
        print("Chapter rows inherit edition and official source URL from their matching BOOK row.")
        print("This mode made no application-data changes and uploaded no PDFs.")
        return 0

    manifest_rows = read_manifest(manifest_path)
    records = prepare_records(root, manifest_rows, chapters_by_track, max_bytes)
    tracks = {(row["class_code"], row["subject_code"]) for row in records}
    assets_by_sha = load_assets(base, cookie)
    hashes_by_slot, _ = current_assignments(base, cookie, tracks)
    assignments = make_assignments(records, chapters_by_track, hashes_by_slot)

    # An identical asset already in the approved library can be reused. An identical PDF
    # present only as a REVIEW/REJECTED asset is not exposed by the library endpoint and
    # will be detected if the upload API returns duplicate=true without an approved result.
    for row in assignments:
        if row["action"] == "SKIP_IDENTICAL_ASSIGNMENT":
            row["status"] = "SKIPPED"
        elif row["sha256"].lower() in assets_by_sha:
            row["action"] = "REUSE_APPROVED_ASSET"
            row["asset_id"] = assets_by_sha[row["sha256"].lower()].get(
                "pdf_asset_id", assets_by_sha[row["sha256"].lower()].get("id")
            )
            row["status"] = "READY"
        else:
            row["action"] = "WOULD_UPLOAD_AND_CREATE_DRAFT" if not args.apply else "PENDING"
            row["status"] = "READY"

    unlisted_pdfs = sorted(
        p.name for p in root.glob("*.pdf")
        if p.name.casefold() not in {row["filename"].casefold() for row in records}
    )
    stamp = datetime.now(timezone.utc).isoformat()
    report_path = find_report_path(root, args.report)
    report: dict[str, Any] = {
        "schema": "quantaedge.official-ncert-root-pdf-import-report.v1",
        "started_at": stamp,
        "result": "PREFLIGHT_OK" if not args.apply else "APPLY_IN_PROGRESS",
        "mode": "apply" if args.apply else "dry_run",
        "root": str(root),
        "manifest": str(manifest_path),
        "api_url": base,
        "authenticated_role": role,
        "permissions_required": ["CONTENT_EDIT"],
        "publish_permission_required_later": "CONTENT_PUBLISH",
        "expected_book_slots": 12,
        "pdf_files_validated": len(records),
        "whole_book_rows": sum(1 for r in records if r["scope"] == "SUBJECT_BOOK"),
        "chapter_pdf_rows": sum(1 for r in records if r["scope"] == "CHAPTER_PDF"),
        "created_assets": 0,
        "created_draft_assignments": 0,
        "reused_assets": 0,
        "skipped_identical_assignments": sum(1 for r in assignments if r["action"] == "SKIP_IDENTICAL_ASSIGNMENT"),
        "publish_actions_taken": 0,
        "unlisted_root_pdfs_ignored": unlisted_pdfs,
        "files": [
            {k: v for k, v in r.items() if k not in {"path", "shared_sha_filenames"}}
            for r in records
        ],
        "assignments": assignments,
        "errors": [],
        "notes": [
            "Original PDF bytes are uploaded unchanged; no split/rebuild/recompression is performed.",
            "Every new assignment stays DRAFT. This script never publishes or archives a student-visible assignment.",
            "A new DRAFT can coexist with the current published version; the old version stays live until an authorised publisher publishes the replacement.",
        ],
        "credentials_recorded": False,
    }
    if unlisted_pdfs:
        print("Unlisted root-level PDFs will be ignored (not in the manifest):")
        for name in unlisted_pdfs[:20]:
            print("  " + name)
        if len(unlisted_pdfs) > 20:
            print(f"  ... plus {len(unlisted_pdfs) - 20} more")

    if not args.apply:
        write_report(report_path, report)
        print(f"Validated files: {len(records):,}")
        print(f"Whole books: {report['whole_book_rows']:,}; chapter PDFs: {report['chapter_pdf_rows']:,}")
        print(f"Identical existing assignments to skip: {report['skipped_identical_assignments']:,}")
        print(f"New DRAFT assignments that would be created: {sum(1 for r in assignments if r['action'] != 'SKIP_IDENTICAL_ASSIGNMENT'):,}")
        print("Existing published assignments remain untouched. No PDFs were uploaded.")
        print(f"Dry-run report: {report_path}")
        return 0

    write_report(report_path, report)
    assets_created_this_run: dict[str, dict[str, Any]] = {}
    try:
        for record in records:
            digest = record["sha256"].lower()
            row_actions = [
                row for row in assignments
                if row["filename"] == record["filename"]
            ]
            if all(row["action"] == "SKIP_IDENTICAL_ASSIGNMENT" for row in row_actions):
                continue

            asset = assets_by_sha.get(digest) or assets_created_this_run.get(digest)
            if asset is None:
                source_reference = ";".join([
                    "asset_origin=OFFICIAL_NCERT_ROOT_IMPORT",
                    "class=" + record["class_code"],
                    "subject=" + record["subject_code"],
                    "language=" + record["language"],
                    "scope=" + record["scope"],
                    "chapter_code=" + (record["chapter_code"] or "WHOLE_BOOK"),
                    "edition=" + record["edition"],
                    "sha256=" + digest,
                ])[:500]
                data = Path(record["path"]).read_bytes()
                asset = api_call(
                    base, cookie, "/api/v1/admin/learning-pdfs", method="POST",
                    multipart=(
                        record["source_title"], source_reference,
                        record["filename"], data,
                    ),
                    timeout=300,
                )
                asset_id = asset.get("pdf_asset_id", asset.get("id")) if isinstance(asset, dict) else None
                if not asset_id:
                    raise RuntimeError(
                        f"PDF upload returned no asset ID for {record['filename']}. "
                        "Check the report before retrying."
                    )
                if asset.get("duplicate") is True:
                    # Duplicate hashes only count as reusable if they are APPROVED and visible
                    # through the protected library list. A REVIEW/REJECTED asset must be resolved
                    # through Source ingestion and must not be assigned to students.
                    query = urlencode({"query": digest})
                    matching = api_call(base, cookie, "/api/v1/admin/learning-pdfs?" + query)
                    approved = [
                        item for item in matching if isinstance(item, dict)
                        and str(item.get("sha256") or "").lower() == digest
                    ] if isinstance(matching, list) else []
                    if not approved:
                        raise RuntimeError(
                            f"{record['filename']}: identical bytes already exist as a non-approved "
                            "PDF asset. Approve/review that asset in Admin → Source ingestion before "
                            "creating a student-library assignment. No assignment was created for it."
                        )
                    asset = approved[0]
                    asset_id = asset.get("pdf_asset_id", asset.get("id"))
                else:
                    report["created_assets"] += 1
                asset["pdf_asset_id"] = asset_id
                assets_created_this_run[digest] = asset
                report["reused_assets"] += 1 if asset.get("duplicate") is True else 0
                write_report(report_path, report)
            asset_id = asset.get("pdf_asset_id", asset.get("id"))
            if not asset_id:
                raise RuntimeError(f"PDF asset for {record['filename']} has no ID.")

            # Identical bytes may appear in multiple manifest slots. They share one approved asset.
            for assignment in row_actions:
                if assignment["action"] == "SKIP_IDENTICAL_ASSIGNMENT":
                    assignment["status"] = "SKIPPED"
                    continue
                assignment["asset_id"] = int(asset_id)
                if assignment["document_id"] is not None:
                    continue
                body = {
                    "pdfAssetId": int(asset_id),
                    "scope": assignment["scope"],
                    "classCode": assignment["class_code"],
                    "subjectCode": assignment["subject_code"],
                    "language": "en" if assignment["language"] == "english" else "hi",
                    "chapterId": assignment["chapter_id"],
                    "title": assignment["title"],
                    "sourceTitle": assignment["source_title"],
                    "sourceUrl": assignment["source_url"],
                    "edition": assignment["edition"],
                    "pageStart": 1,
                    "pageEnd": assignment["page_count"],
                }
                doc = api_call(
                    base, cookie, "/api/v1/admin/learning-documents",
                    method="POST", body=body, timeout=120,
                )
                returned_status = str(doc.get("status", "")).upper() if isinstance(doc, dict) else ""
                document_id = doc.get("document_id", doc.get("id")) if isinstance(doc, dict) else None
                if returned_status != "DRAFT" or not document_id:
                    raise RuntimeError(
                        f"{assignment['filename']}: API did not return a new DRAFT assignment. "
                        "Stop and inspect the checkpoint report; reruns are hash/slot-aware."
                    )
                assignment["document_id"] = int(document_id)
                assignment["status"] = "DRAFT"
                assignment["action"] = "CREATED_DRAFT"
                report["created_draft_assignments"] += 1
                write_report(report_path, report)

        report["result"] = "COMPLETED"
        report["finished_at"] = datetime.now(timezone.utc).isoformat()
        write_report(report_path, report)
        print(f"Import result: {report['result']}")
        print(f"Created PDF assets: {report['created_assets']:,}")
        print(f"Created DRAFT assignments: {report['created_draft_assignments']:,}")
        print(f"Reused approved assets: {report['reused_assets']:,}")
        print(f"Skipped identical assignments: {report['skipped_identical_assignments']:,}")
        print("Published or archived assignments: 0")
        print(f"Report: {report_path}")
        print("Next: open Admin → Textbook library and publish only the chosen PDFs with CONTENT_PUBLISH.")
        return 0
    except Exception as exc:
        report["result"] = "PARTIAL_FAILURE"
        report["finished_at"] = datetime.now(timezone.utc).isoformat()
        report["errors"].append(str(exc))
        write_report(report_path, report)
        print(f"IMPORT STOPPED SAFELY: {exc}", file=sys.stderr)
        print("The checkpoint report was saved. Existing published PDFs were not changed.", file=sys.stderr)
        print(f"Report: {report_path}", file=sys.stderr)
        return 2


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=str(ROOT), help="Project root containing the PDFs and manifest.")
    parser.add_argument("--manifest", default=MANIFEST_NAME, help=f"CSV manifest path (default: {MANIFEST_NAME}).")
    parser.add_argument("--api-url", default=os.environ.get("QUANTAEDGE_API_BASE_URL", "http://localhost:8080"))
    parser.add_argument("--write-template", action="store_true",
                        help="Create the manifest from the live curriculum; never overwrites an existing manifest.")
    parser.add_argument("--apply", action="store_true",
                        help="Upload verified PDFs and create DRAFT assignments. Default mode is dry-run only.")
    parser.add_argument("--report", default="",
                        help="Audit report path; default is a timestamped file under reports/ncert-pdf-imports.")
    args = parser.parse_args()
    try:
        if args.write_template and args.apply:
            raise ValueError("--write-template and --apply cannot be used together.")
        return run(args)
    except Exception as exc:
        print(f"STOPPED SAFELY: {exc}", file=sys.stderr)
        print("No publication or archive action was started.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
