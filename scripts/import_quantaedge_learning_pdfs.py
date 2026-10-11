#!/usr/bin/env python3
"""Import QuantaEdge-authored chapter and complete-book PDFs as Admin DRAFT assignments.

Reads the generated class 6–8 Hindi package ZIPs; it never downloads or uploads the
original NCERT/SCERT PDFs. Chapterwise PDFs become CHAPTER_PDF assignments tied to an
exact canonical curriculum chapter. Complete-book PDFs become SUBJECT_BOOK assignments.
All created assignments stay DRAFT. This script never calls a publish endpoint.

Dry run (default):
  python scripts/import_quantaedge_learning_pdfs.py --package Class6.zip --package Class7.zip
Apply:
  set QUANTAEDGE_ADMIN_SESSION to a fresh QE_SESSION cookie first, then add --apply.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from dataclasses import asdict, dataclass
from pathlib import Path, PurePosixPath
from typing import Any

MAX_BYTES = 50 * 1024 * 1024
MAX_PAGES = 2000
ALLOWED_CLASSES = {"6", "7", "8"}
ALLOWED_SUBJECTS = {"maths", "science"}
BOUNDARY_PREFIX = "----QuantaEdgeDraftPdfImport"


@dataclass
class PdfRecord:
    package: str
    archive_path: str
    sha256: str
    filename: str
    title: str
    class_code: str
    subject_code: str
    subject_name: str
    language: str
    scope: str
    chapter_code: str | None
    chapter_title: str | None
    page_count: int
    size_bytes: int
    source_title: str
    source_url: str
    edition: str
    source_reference: str
    chapter_id: int | None = None
    document_id: int | None = None
    action: str = "PENDING"
    note: str = ""


def norm(value: Any) -> str:
    text = str(value or "").casefold().strip()
    text = re.sub(r"[\W_]+", " ", text, flags=re.UNICODE)
    return re.sub(r"\s+", " ", text).strip()


def norm_code(value: Any) -> str:
    return re.sub(r"[^a-z0-9]+", "-", str(value or "").casefold()).strip("-")


def json_bytes(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, indent=2).encode("utf-8")


def read_pdf_page_count(data: bytes, label: str) -> int:
    if len(data) < 5 or not data.startswith(b"%PDF-"):
        raise ValueError(f"{label}: not a valid PDF file signature.")
    if len(data) > MAX_BYTES:
        raise ValueError(f"{label}: {len(data) / 1048576:.1f} MiB exceeds the Admin API 50 MiB limit.")
    try:
        import fitz  # PyMuPDF
    except ImportError as exc:
        raise RuntimeError("PyMuPDF is required for page-count validation. Run: python -m pip install 'PyMuPDF>=1.24,<2'") from exc
    try:
        with fitz.open(stream=data, filetype="pdf") as pdf:
            count = pdf.page_count
    except Exception as exc:
        raise ValueError(f"{label}: PDF cannot be opened ({exc}).") from exc
    if not 1 <= count <= MAX_PAGES:
        raise ValueError(f"{label}: page count {count} is outside the supported 1–{MAX_PAGES} range.")
    return count


def find_one(names: list[str], suffix: str) -> str | None:
    matches = [name for name in names if name.endswith(suffix)]
    if len(matches) > 1:
        raise ValueError(f"Archive has multiple files ending with {suffix!r}; refusing ambiguous import.")
    return matches[0] if matches else None


def csv_rows(raw: bytes) -> list[dict[str, str]]:
    text = raw.decode("utf-8-sig")
    return list(csv.DictReader(io.StringIO(text)))


def title_for(subject: str) -> str:
    return "गणित" if subject == "maths" else "विज्ञान"


def source_metadata(source_title: str, source_url: str, class_code: str, subject: str,
                    language: str, file_name: str, digest: str, scope: str,
                    chapter_code: str | None) -> tuple[str, str, str, str]:
    # Do not imply that QuantaEdge-authored PDFs are copies of official source books.
    original = f"QuantaEdge original instructional content; curriculum reference: {source_title or 'pending human review'}"
    if not source_url.lower().startswith("https://"):
        source_url = "https://ncert.nic.in/textbook.php"
    edition = f"QuantaEdge editorial draft; Class {class_code}; source alignment pending human review"
    reference = ";".join([
        "asset_origin=QUANTAEDGE_AUTHORED_PDF",
        "asset_scope=" + scope,
        "class=" + class_code,
        "subject=" + subject,
        "language=" + language,
        "package_pdf=" + file_name,
        "sha256=" + digest,
        "source_alignment=HUMAN_REVIEW_REQUIRED",
        *( ["chapter_code=" + chapter_code] if chapter_code else [] ),
    ])[:500]
    return original[:300], source_url[:2000], edition[:160], reference


def extract_records(package_path: Path) -> tuple[list[PdfRecord], list[str]]:
    if not package_path.is_file():
        raise ValueError(f"Package does not exist: {package_path}")
    records: list[PdfRecord] = []
    warnings: list[str] = []
    with zipfile.ZipFile(package_path) as zf:
        names = [n for n in zf.namelist() if not n.endswith("/")]
        class6_inv_path = find_one(names, "content/authoring-inventory.json")
        newer_inv_path = find_one(names, "coverage/CHAPTER_INVENTORY.csv")
        if bool(class6_inv_path) == bool(newer_inv_path):
            raise ValueError(f"{package_path.name}: expected exactly one supported package inventory (Class 6 authoring inventory OR Class 7–8 chapter inventory).")

        # Class 6 package uses a JSON chapter inventory and /pdf/ paths.
        if class6_inv_path:
            inv = json.loads(zf.read(class6_inv_path).decode("utf-8-sig"))
            class_code = str(inv.get("class_code", ""))
            language = str(inv.get("medium", "hi")).lower()
            catalog_path = find_one(names, "content/quantaedge-class6-hindi-catalog.json")
            catalog = json.loads(zf.read(catalog_path).decode("utf-8-sig")) if catalog_path else {}
            catalog_subjects = catalog.get("subjects") or {}
            if class_code not in ALLOWED_CLASSES or language not in {"hi", "en"}:
                raise ValueError(f"{package_path.name}: unsupported class/medium metadata.")
            chapter_map: dict[tuple[str, str], dict[str, Any]] = {}
            for subject, chapters in (inv.get("subjects") or {}).items():
                if subject not in ALLOWED_SUBJECTS:
                    continue
                for ch in chapters:
                    chapter_map[(subject, norm_code(ch.get("chapter_code")))] = ch
            for name in names:
                lower = name.lower()
                if not lower.endswith(".pdf"):
                    continue
                p = PurePosixPath(name)
                subject = p.parent.name.casefold() if "/pdf/chapterwise/" in lower else ""
                if "/pdf/chapterwise/" in lower:
                    if subject not in ALLOWED_SUBJECTS:
                        continue
                    match = re.search(r"_Ch\d+_(.+?)_Concepts(?:_DRAFT)?\.pdf$", p.name, re.I)
                    if not match:
                        warnings.append(f"Skipped chapter PDF with unrecognized filename: {name}")
                        continue
                    code = norm_code(match.group(1))
                    ch = chapter_map.get((subject, code))
                    if not ch:
                        raise ValueError(f"{package_path.name}: {name} has no matching chapter in authoring-inventory.json.")
                    scope, chapter_code, chapter_title = "CHAPTER_PDF", str(ch["chapter_code"]), str(ch["title"])
                    catalog_track = catalog_subjects.get(subject) or {}
                    source_title = str(catalog_track.get("book") or "NCERT-aligned curriculum reference")
                    source_url = str(catalog_track.get("url") or "https://ncert.nic.in/textbook.php")
                    student_title = f"QuantaEdge | Class {class_code} | {title_for(subject)} | अध्याय {int(ch.get('chapter_no') or 0):02d}: {chapter_title}"
                elif "/pdf/complete-book/" in lower:
                    sm = re.search(r"_(maths|science)_Complete_Book(?:_Hindi)?(?:_DRAFT)?\.pdf$", p.name, re.I)
                    if not sm:
                        warnings.append(f"Skipped complete-book PDF with unrecognized filename: {name}")
                        continue
                    subject = sm.group(1).lower()
                    if subject not in ALLOWED_SUBJECTS:
                        continue
                    chapter_no = None
                    scope, chapter_code, chapter_title = "SUBJECT_BOOK", None, None
                    catalog_track = catalog_subjects.get(subject) or {}
                    source_title = str(catalog_track.get("book") or "NCERT-aligned curriculum reference")
                    source_url = str(catalog_track.get("url") or "https://ncert.nic.in/textbook.php")
                    student_title = f"QuantaEdge | Class {class_code} | {title_for(subject)} | पूरी पुस्तक ({'हिन्दी' if language == 'hi' else 'English'})"
                else:
                    continue
                data = zf.read(name)
                pages = read_pdf_page_count(data, f"{package_path.name}:{name}")
                digest = hashlib.sha256(data).hexdigest()
                st, su, edition, reference = source_metadata(source_title, source_url, class_code, subject, language, p.name, digest, scope, chapter_code)
                records.append(PdfRecord(str(package_path), name, digest, p.name, student_title,
                    class_code, subject, title_for(subject), language, scope, chapter_code,
                    chapter_title, pages, len(data), st, su, edition, reference))
        # Class 7–8 packages use CHAPTER_INVENTORY.csv and /pdfs/ paths.
        else:
            inv_rows = csv_rows(zf.read(newer_inv_path))
            indexed: dict[str, dict[str, str]] = {}
            declared = re.search(r"QuantaEdge_Class([678])", package_path.name, re.I)
            package_class = declared.group(1) if declared else None
            if package_class is None:
                # Fall back to the sole class whose chapter PDF folder exists in the archive.
                present_classes = {str(n.split("/chapterwise/class-", 1)[1].split("/", 1)[0]) for n in names if "/chapterwise/class-" in n and ".pdf" in n.lower()}
                if len(present_classes) == 1 and next(iter(present_classes)) in ALLOWED_CLASSES:
                    package_class = next(iter(present_classes))
            class_seen: set[str] = set()
            for row in inv_rows:
                code = str(row.get("chapter_code", "")).strip()
                subject = str(row.get("subject", "")).strip().lower()
                class_code = str(row.get("class", "")).strip()
                language = str(row.get("language", "hi")).strip().lower()
                if class_code not in ALLOWED_CLASSES or subject not in ALLOWED_SUBJECTS or language not in {"hi", "en"}:
                    raise ValueError(f"{package_path.name}: unsupported inventory row {row!r}")
                if package_class and class_code != package_class:
                    # The packaged coverage CSV is shared across Class 7 and 8 builds;
                    # each ZIP itself contains only the chapter assets for its named class.
                    continue
                if not code or not row.get("chapterwise_pdf_path"):
                    raise ValueError(f"{package_path.name}: inventory row has no chapter code/PDF path: {row!r}")
                indexed[str(row["chapterwise_pdf_path"]).lstrip("./")] = row
                class_seen.add(class_code)
            if len(class_seen) != 1 or (package_class and class_seen != {package_class}):
                raise ValueError(f"{package_path.name}: expected chapter inventory for one available class; found {sorted(class_seen)} (package class={package_class}).")
            for relative_path, row in indexed.items():
                name = find_one(names, relative_path)
                if not name:
                    raise ValueError(f"{package_path.name}: inventory PDF is missing from ZIP: {relative_path}")
                data = zf.read(name)
                pages = read_pdf_page_count(data, f"{package_path.name}:{name}")
                class_code = str(row["class"])
                subject = str(row["subject"]).lower()
                language = str(row.get("language", "hi")).lower()
                chapter_code = str(row["chapter_code"])
                chapter_title = str(row["chapter_title"])
                digest = hashlib.sha256(data).hexdigest()
                source_title = str(row.get("source_title") or "Curriculum reference pending editor verification")
                source_url = str(row.get("source_url") or "https://ncert.nic.in/textbook.php")
                st, su, edition, reference = source_metadata(source_title, source_url, class_code, subject, language, PurePosixPath(name).name, digest, "CHAPTER_PDF", chapter_code)
                student_title = f"QuantaEdge | Class {class_code} | {title_for(subject)} | अध्याय {int(row.get('track_order') or 0):02d}: {chapter_title}"
                records.append(PdfRecord(str(package_path), name, digest, PurePosixPath(name).name,
                    student_title, class_code, subject, title_for(subject), language, "CHAPTER_PDF",
                    chapter_code, chapter_title, pages, len(data), st, su, edition, reference))

            # Add complete-book PDFs from each package's /pdfs/complete-book directory.
            for name in names:
                lower = name.lower()
                if not lower.endswith(".pdf") or "/pdfs/complete-book/" not in lower:
                    continue
                sm = re.search(r"Class[_ -]?(\d+)[_ -]+(Maths|Science)[_ -]+Complete[_ -]+Book(?:[_ -]+Hindi)?(?:[_ -]+DRAFT)?\.pdf$", PurePosixPath(name).name, re.I)
                if not sm:
                    warnings.append(f"Skipped complete-book PDF with unrecognized filename: {name}")
                    continue
                class_code, subject = sm.group(1), sm.group(2).lower()
                if class_code not in ALLOWED_CLASSES or class_code not in class_seen or subject not in ALLOWED_SUBJECTS:
                    continue
                language = "en" if re.search(r"(?:^|[_ -])English(?:[_ -]|$)", PurePosixPath(name).stem, re.I) else "hi"
                data = zf.read(name)
                pages = read_pdf_page_count(data, f"{package_path.name}:{name}")
                digest = hashlib.sha256(data).hexdigest()
                st, su, edition, reference = source_metadata("QuantaEdge authored curriculum-aligned learning content", "https://ncert.nic.in/textbook.php", class_code, subject, language, PurePosixPath(name).name, digest, "SUBJECT_BOOK", None)
                student_title = f"QuantaEdge | Class {class_code} | {title_for(subject)} | पूरी पुस्तक ({'हिन्दी' if language == 'hi' else 'English'})"
                records.append(PdfRecord(str(package_path), name, digest, PurePosixPath(name).name,
                    student_title, class_code, subject, title_for(subject), language, "SUBJECT_BOOK",
                    None, None, pages, len(data), st, su, edition, reference))

    if not records:
        raise ValueError(f"No supported QuantaEdge chapterwise/complete-book PDFs found in {package_path.name}.")
    keys = [(r.class_code, r.subject_code, r.scope, r.chapter_code, r.language, r.sha256) for r in records]
    if len(keys) != len(set(keys)):
        raise ValueError(f"{package_path.name}: duplicate PDF entries found within the package.")
    return records, warnings


def api_call(base_url: str, cookie: str, path: str, method: str = "GET", body: Any | None = None,
             multipart: tuple[str, str, str, bytes] | None = None, timeout: int = 90) -> Any:
    boundary = BOUNDARY_PREFIX + hashlib.sha256((str(time.time_ns()) + path).encode()).hexdigest()[:20]
    headers = {"Accept": "application/json", "Cookie": "QE_SESSION=" + cookie}
    data: bytes | None = None
    if multipart is not None:
        title, source_reference, filename, pdf_bytes = multipart
        buf = bytearray()
        def field(name: str, value: str) -> None:
            buf.extend((f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n").encode("utf-8"))
        field("title", title)
        field("sourceReference", source_reference)
        # SourceKind defaults to ADMIN_UPLOAD. This uses the normal Admin upload permission,
        # and creates no published learning_document assignment.
        filename = PurePosixPath(filename).name.replace('"', "'").replace("\\", "_")
        buf.extend((f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\nContent-Type: application/pdf\r\n\r\n").encode("utf-8"))
        buf.extend(pdf_bytes)
        buf.extend(f"\r\n--{boundary}--\r\n".encode("ascii"))
        data = bytes(buf)
        headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
    elif body is not None:
        data = json_bytes(body)
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(base_url.rstrip("/") + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            raw = response.read()
            if not raw:
                return {}
            try:
                return json.loads(raw.decode("utf-8"))
            except json.JSONDecodeError:
                return {"raw": raw.decode("utf-8", errors="replace")[:1500]}
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")[:1500]
        if exc.code in {401, 403}:
            raise RuntimeError(f"Admin API rejected this session/permission for {path} (HTTP {exc.code}). Sign in again, refresh QUANTAEDGE_ADMIN_SESSION, and confirm CONTENT_EDIT. Response: {detail}") from exc
        raise RuntimeError(f"Admin API request failed for {path} (HTTP {exc.code}): {detail}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"Could not connect to {base_url}: {exc}") from exc


def api_url_valid(value: str) -> None:
    parsed = urllib.parse.urlsplit(value)
    local = parsed.hostname in {"localhost", "127.0.0.1", "::1"}
    if parsed.scheme != "https" and not (parsed.scheme == "http" and local):
        raise ValueError("Use HTTPS for a non-local API URL; HTTP is allowed only for localhost development.")


def resolve_chapter(record: PdfRecord, chapters: list[dict[str, Any]]) -> int:
    rows = [row for row in chapters if row.get("chapter_id") is not None]
    code = norm_code(record.chapter_code)
    matched = [r for r in rows if norm_code(r.get("chapter_code")) == code]
    if not matched and record.chapter_title:
        wanted = norm(record.chapter_title)
        matched = [r for r in rows if norm(r.get("chapter_name")) == wanted]
    # For 7/8, some packaged part-II translation titles can diverge from the canonical curriculum.
    # Do not use fuzzy matching or infer a chapter ID from a chapter number.
    matched = [r for r in matched if r.get("chapter_active") is not False]
    ids = {int(r["chapter_id"]) for r in matched}
    if len(ids) != 1:
        detail = [f"{r.get('chapter_code')}:{r.get('chapter_name')} (id={r.get('chapter_id')})" for r in matched[:5]]
        if not ids:
            raise ValueError(f"No exact canonical chapter match for Class {record.class_code} / {record.subject_code} / {record.chapter_code} / {record.chapter_title!r}. No title/number guessing is allowed.")
        raise ValueError(f"Ambiguous canonical chapter mapping for {record.chapter_code}: {detail}")
    return ids.pop()


def verify_session(base: str, cookie: str) -> dict[str, Any]:
    if not cookie.strip():
        raise RuntimeError("Set QUANTAEDGE_ADMIN_SESSION to a fresh QE_SESSION cookie value before import.")
    me = api_call(base, cookie, "/api/v1/auth/me")
    if not isinstance(me, dict) or me.get("authenticated") is not True:
        raise RuntimeError("Admin session is not authenticated. Sign in at the Admin portal and copy a fresh QE_SESSION cookie.")
    permissions = set(me.get("permissions") or [])
    role = str(me.get("role") or "")
    if role != "ADMIN" and "CONTENT_EDIT" not in permissions:
        raise RuntimeError("Signed-in account lacks CONTENT_EDIT; no changes were made.")
    return me


def run(args: argparse.Namespace) -> int:
    base = args.api_url.rstrip("/")
    api_url_valid(base)
    all_records: list[PdfRecord] = []
    warnings: list[str] = []
    for item in args.package:
        rows, w = extract_records(Path(item).expanduser().resolve())
        all_records.extend(rows)
        warnings.extend(w)

    # Duplicate bytes in overlapping/repacked packages must be assigned only once.
    de_duped: dict[tuple[str, str, str, str | None, str], PdfRecord] = {}
    for r in all_records:
        key = (r.sha256, r.class_code, r.subject_code, r.chapter_code, r.scope, r.language)
        if key not in de_duped:
            de_duped[key] = r
        else:
            warnings.append(f"Same PDF is included more than once; kept {de_duped[key].package} and skipped duplicate in {r.package}: {r.filename}")
    records = list(de_duped.values())
    if not records:
        raise RuntimeError("No valid PDFs found.")

    # A dry run still authenticates and preflights every mapping; it performs no writes.
    me = verify_session(base, os.environ.get("QUANTAEDGE_ADMIN_SESSION", ""))
    print(f"Authenticated staff session: role={me.get('role')}; staffId={me.get('staffId')}; permission=CONTENT_EDIT")

    needed_tracks = sorted({(r.class_code, r.subject_code) for r in records})
    curriculum_rows = api_call(base, os.environ.get("QUANTAEDGE_ADMIN_SESSION", ""), "/api/v1/curriculum")
    if not isinstance(curriculum_rows, list):
        raise RuntimeError("Curriculum API response was not a list; no changes were made.")
    active_tracks = {(str(r.get("class_code")), str(r.get("subject_code"))) for r in curriculum_rows}
    for key in needed_tracks:
        if key not in active_tracks:
            raise RuntimeError(f"No active curriculum track exists for Class {key[0]} / {key[1]}; no changes were made.")

    chapters_by_track: dict[tuple[str, str], list[dict[str, Any]]] = {}
    existing_by_track: dict[tuple[str, str], list[dict[str, Any]]] = {}
    library = api_call(base, os.environ.get("QUANTAEDGE_ADMIN_SESSION", ""), "/api/v1/admin/learning-pdfs")
    assignments_by_sha_slot: set[tuple[str, str, str, str, str, int | None]] = set()
    for class_code, subject in needed_tracks:
        query = urllib.parse.urlencode({"classCode": class_code, "subjectCode": subject})
        content = api_call(base, os.environ.get("QUANTAEDGE_ADMIN_SESSION", ""), "/api/v1/admin/content?" + query)
        chapters_by_track[(class_code, subject)] = [r for r in content if isinstance(r, dict)] if isinstance(content, list) else []
        existing = api_call(base, os.environ.get("QUANTAEDGE_ADMIN_SESSION", ""), "/api/v1/admin/learning-documents?" + query + "&status=ALL")
        existing_by_track[(class_code, subject)] = existing if isinstance(existing, list) else []
        for row in existing_by_track[(class_code, subject)]:
            digest = str(row.get("sha256") or "").lower()
            if digest:
                assignments_by_sha_slot.add((digest, str(row.get("class_code")), str(row.get("subject_code")), str(row.get("scope")), str(row.get("language")), int(row["chapter_id"]) if row.get("chapter_id") is not None else None))

    # Resolve all chapter IDs and fail the entire preflight before any PDF uploads if one is ambiguous.
    errors: list[str] = []
    for r in records:
        try:
            if r.scope == "CHAPTER_PDF":
                r.chapter_id = resolve_chapter(r, chapters_by_track[(r.class_code, r.subject_code)])
            duplicate_slot = (r.sha256, r.class_code, r.subject_code, r.scope, r.language, r.chapter_id)
            if duplicate_slot in assignments_by_sha_slot:
                r.action = "SKIP_EXISTING_ASSIGNMENT"
                r.note = "Same PDF is already assigned to this class/subject/language/chapter; preserved existing status."
            else:
                r.action = "WOULD_CREATE_DRAFT" if not args.apply else "READY_TO_CREATE_DRAFT"
        except ValueError as exc:
            errors.append(str(exc))
            r.action = "BLOCKED_MAPPING"
            r.note = str(exc)
    if errors:
        print("\nPREFLIGHT STOPPED — no writes were performed because one or more chapter mappings need human resolution:")
        for item in errors:
            print("  - " + item)
        print("\nFix the curriculum mapping/content slug, then rerun the same command. No chapter number guessing is performed.")
        return 2

    assets_by_sha = {str(row.get("sha256") or "").lower(): row for row in library if isinstance(row, dict)} if isinstance(library, list) else {}
    # Page/size/source checks already passed during archive parsing. Limit all action to authenticated staff.
    created_assets = created_docs = skipped = 0
    report_rows: list[dict[str, Any]] = []
    for index, r in enumerate(records, 1):
        if r.action == "SKIP_EXISTING_ASSIGNMENT":
            skipped += 1
            print(f"[{index}/{len(records)}] SKIP assigned: Class {r.class_code} {r.subject_code} {r.chapter_title or 'complete book'}")
            report_rows.append(asdict(r))
            continue
        if not args.apply:
            print(f"[{index}/{len(records)}] DRAFT PLAN: Class {r.class_code} {r.subject_code} {r.chapter_title or 'complete book'} | {r.scope} | {r.page_count} pages | {r.size_bytes/1048576:.2f} MiB")
            report_rows.append(asdict(r))
            continue
        try:
            asset = assets_by_sha.get(r.sha256)
            if asset is None:
                with zipfile.ZipFile(r.package) as zf:
                    data = zf.read(r.archive_path)
                asset = api_call(base, os.environ["QUANTAEDGE_ADMIN_SESSION"], "/api/v1/admin/learning-pdfs", method="POST",
                                 multipart=(r.title, r.source_reference, r.filename, data), timeout=180)
                assets_by_sha[r.sha256] = asset
                if not asset.get("duplicate"):
                    created_assets += 1
            asset_id = asset.get("pdf_asset_id", asset.get("id"))
            if not asset_id:
                raise RuntimeError("PDF upload returned no pdf_asset_id.")
            # Re-check before assignment in case a prior partial run already created it.
            slot = (r.sha256, r.class_code, r.subject_code, r.scope, r.language, r.chapter_id)
            if slot in assignments_by_sha_slot:
                r.action = "SKIP_EXISTING_ASSIGNMENT"
                skipped += 1
                report_rows.append(asdict(r))
                continue
            body = {
                "pdfAssetId": int(asset_id),
                "scope": r.scope,
                "classCode": r.class_code,
                "subjectCode": r.subject_code,
                "language": r.language,
                "chapterId": r.chapter_id,
                "title": r.title,
                "sourceTitle": r.source_title,
                "sourceUrl": r.source_url,
                "edition": r.edition,
                "pageStart": 1,
                "pageEnd": r.page_count,
            }
            doc = api_call(base, os.environ["QUANTAEDGE_ADMIN_SESSION"], "/api/v1/admin/learning-documents", method="POST", body=body)
            if str(doc.get("status", "")).upper() != "DRAFT":
                raise RuntimeError(f"API did not return DRAFT status for document {doc.get('document_id')}.")
            r.document_id = int(doc.get("document_id", doc.get("id", 0)) or 0) or None
            r.action = "CREATED_DRAFT"
            r.note = "Created as DRAFT; not published. Review this resource in Admin → Textbook library."
            assignments_by_sha_slot.add(slot)
            created_docs += 1
            print(f"[{index}/{len(records)}] CREATED DRAFT #{r.document_id}: Class {r.class_code} {r.subject_code} | {r.scope} | {r.chapter_title or 'complete book'}")
        except Exception as exc:
            r.action = "ERROR"
            r.note = str(exc)
            report_rows.append(asdict(r))
            # Preserve resumability: earlier entries remain idempotently skipped on retry.
            report = {
                "mode": "apply", "result": "PARTIAL_FAILURE", "package_count": len(args.package),
                "pdf_count": len(records), "created_assets": created_assets, "created_draft_assignments": created_docs,
                "skipped_existing": skipped, "failed_at": r.archive_path, "rows": report_rows + [asdict(x) for x in records[index:]],
                "warnings": warnings,
            }
            Path(args.report).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
            print(f"\nSTOPPED after an error at {r.archive_path}: {exc}\nReport: {Path(args.report).resolve()}\nRerunning is safe; existing identical assignments are detected.")
            return 1
        report_rows.append(asdict(r))

    report = {
        "mode": "apply" if args.apply else "dry_run",
        "result": "COMPLETED" if args.apply else "PREFLIGHT_OK",
        "package_count": len(args.package), "pdf_count": len(records),
        "chapter_pdfs": sum(r.scope == "CHAPTER_PDF" for r in records),
        "complete_book_pdfs": sum(r.scope == "SUBJECT_BOOK" for r in records),
        "created_assets": created_assets if args.apply else 0,
        "created_draft_assignments": created_docs if args.apply else 0,
        "skipped_existing": skipped,
        "publish_actions_taken": 0,
        "warnings": warnings,
        "rows": report_rows,
        "review_note": "Chapter PDFs should contain concepts/QuantaEdge Advanced only. Complete-book PDFs must contain the current full question bank before a human publisher publishes them. The currently supplied expanded-practice ZIPs carry complete-book PDF bytes unchanged from the earlier packages. This script attaches supplied PDFs; it does not generate or rebuild PDF contents.",
    }
    Path(args.report).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print("\n" + json.dumps({k: v for k, v in report.items() if k not in {"rows"}}, ensure_ascii=False, indent=2))
    print(f"Report: {Path(args.report).resolve()}")
    if warnings:
        print("\nWarnings:")
        for warning in warnings:
            print(" - " + warning)
    if not args.apply:
        print("\nDRY RUN ONLY — no PDF was uploaded and no assignment was created. Review the report, then repeat with --apply.")
    else:
        print("\nDone. All newly created assignments remain DRAFT. No publish action was called.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", action="append", required=True, help="Generated QuantaEdge package ZIP; repeat for Classes 6, 7 and 8.")
    parser.add_argument("--api-url", default=os.environ.get("QUANTAEDGE_API_BASE_URL", "http://localhost:8080"))
    parser.add_argument("--apply", action="store_true", help="Upload PDFs and attach them as DRAFT assignments. Default is no-write dry run.")
    parser.add_argument("--report", default="quantaedge-pdf-import-report.json", help="JSON audit report output path.")
    args = parser.parse_args()
    try:
        return run(args)
    except (ValueError, OSError, zipfile.BadZipFile, json.JSONDecodeError, RuntimeError, KeyError) as exc:
        print(f"STOPPED SAFELY: {exc}", file=sys.stderr)
        print("No further writes were performed. Check the error/report before retrying.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())