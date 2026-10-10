#!/usr/bin/env python3
"""Incremental per-language GHCR cache for official NCERT and SCERT Bihar textbooks.

One book is downloaded, checksummed, appended as an OCI image layer and pushed before
the next book starts. Successful uploads survive failures/timeouts and are skipped on rerun.
"""
from __future__ import annotations

import argparse, copy, hashlib, html, json, os, re, shutil, subprocess, sys, tempfile
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
import urllib.error, urllib.parse, urllib.request, zipfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

NCERT = "https://ncert.nic.in"
NCERT_CATALOG = NCERT + "/textbook.php?ln=en"
SCERT_CATALOG = "https://scert.bihar.gov.in/eresources?per_page=100"
OFFICIAL_HOSTS = {"ncert.nic.in", "ncert.ncert.org.in", "scert.bihar.gov.in", "bstbpc.gov.in"}
NCERT_CATALOG_FALLBACKS = (
    "https://ncert.nic.in/textbook.php?ln=en",
    "https://www.ncert.nic.in/textbook.php?ln=en",
    "https://ncert.ncert.org.in/textbook.php?ln=en",
)
NCERT_PDF_BASES = ("https://ncert.nic.in/textbook/pdf", "https://ncert.ncert.org.in/textbook/pdf")
LANGUAGES = {"hindi": "h", "english": "e"}
ROMAN_CLASSES = {"vi": 6, "vii": 7, "viii": 8, "ix": 9, "x": 10, "xi": 11, "xii": 12}
# Textbook binary downloads and official complete-book bundles have no artificial size ceiling.
MAX_BOOK_BYTES = None
MAX_ZIP_BYTES = None
DEFAULT_DOWNLOAD_WORKERS = 8
DEFAULT_PUSH_BATCH_SIZE = 8
MAX_RETRY_ROUNDS = 5
CHUNK = 1024 * 1024
IMAGE_PREFIX = "ghcr.io/manav326/quantaedge-textbooks"

CLASS_BRANCH = re.compile(r"document\.test\.tclass\.value==(\d+)\)")
SUBJECT_TEXT = re.compile(r'^\s*document\.test\.tsubject\.options\[(\d+)\]\.text\s*=\s*"([^"]*)"')
BOOK_BRANCH = re.compile(r'\(document\.test\.tclass\.value==(\d+)\)\s*&&\s*\(document\.test\.tsubject\.options\[sind\]\.text=="([^"]+)"\)')
BOOK_TEXT = re.compile(r'^\s*document\.test\.tbook\.options\[(\d+)\]\.text\s*=\s*"([^"]*)"')
BOOK_VALUE = re.compile(r'^\s*document\.test\.tbook\.options\[(\d+)\]\.value\s*=\s*"textbook\.php\?([a-z0-9]+)=\d+-(\d+)"')
SUBJECTS_MARKER = "//this function check the classthat you have selected"
BOOKS_MARKER = 'document.test.tbook.options[0].text="..Select Book Title..";'


def now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def log(value: str) -> None:
    print(value, flush=True)


def host_allowed(url: str) -> bool:
    try:
        parsed = urllib.parse.urlparse(url)
        host = (parsed.hostname or "").lower().rstrip(".")
        return (parsed.scheme == "https" and parsed.username is None and parsed.password is None
                and (host in OFFICIAL_HOSTS or any(host.endswith("." + item) for item in OFFICIAL_HOSTS))
                and parsed.port in (None, 443))
    except ValueError:
        return False


def _get_url_once(url: str, destination: Path | None = None, limit: int | None = None) -> bytes | Path:
    if not host_allowed(url):
        raise ValueError("Refusing non-official or non-HTTPS URL: " + url)
    req = urllib.request.Request(url, headers={
        "User-Agent": "QuantaEdge-TextbookRegistry/1.0",
        "Accept": "application/pdf,application/zip,text/html,*/*;q=0.8",
    })
    try:
        with urllib.request.urlopen(req, timeout=60) as response:
            if not host_allowed(response.geturl()):
                raise ValueError("Official source redirected to a non-approved host: " + response.geturl())
            content_type = str(response.headers.get("Content-Type", "")).lower()
            total = 0
            if destination is None:
                data = bytearray()
                while True:
                    block = response.read(CHUNK)
                    if not block:
                        break
                    total += len(block)
                    if limit is not None and total > limit:
                        raise ValueError("Response exceeds configured metadata limit.")
                    data.extend(block)
                result = bytes(data)
                if "text/html" not in content_type and not (result.startswith(b"%PDF-") or result.startswith(b"PK\x03\x04")):
                    raise ValueError("Expected official HTML, PDF or ZIP response.")
                return result
            destination.parent.mkdir(parents=True, exist_ok=True)
            part = destination.with_suffix(destination.suffix + ".part")
            with part.open("wb") as handle:
                while True:
                    block = response.read(CHUNK)
                    if not block:
                        break
                    total += len(block)
                    if limit is not None and total > limit:
                        raise ValueError("Download exceeds configured limit.")
                    handle.write(block)
            with part.open("rb") as handle:
                sig = handle.read(5)
            if not (sig == b"%PDF-" or sig.startswith(b"PK\x03\x04")):
                part.unlink(missing_ok=True)
                raise ValueError("Download is not a PDF or ZIP: " + url)
            part.replace(destination)
            return destination
    except urllib.error.HTTPError as exc:
        if exc.code == 404:
            raise FileNotFoundError(url) from exc
        raise RuntimeError("Official source returned HTTP " + str(exc.code) + ": " + url) from exc
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        raise RuntimeError("Download failed for " + url + ": " + str(exc)) from exc


def get_url(url: str, destination: Path | None = None, limit: int | None = None) -> bytes | Path:
    last_error: Exception | None = None
    for attempt in range(1, 5):
        try:
            return _get_url_once(url, destination, limit)
        except FileNotFoundError:
            raise
        except ValueError:
            raise
        except (RuntimeError, OSError, TimeoutError) as exc:
            last_error = exc
            if attempt < 4:
                delay = attempt * 2
                log("Transient source failure; retry " + str(attempt + 1) + "/4 in " + str(delay) + "s: " + url)
                time.sleep(delay)
    raise RuntimeError("Failed after four attempts: " + url + " (" + str(last_error) + ")")


def fetch_text(url: str) -> str:
    candidates = list(NCERT_CATALOG_FALLBACKS) if url == NCERT_CATALOG else [url]
    errors = []
    for candidate in candidates:
        try:
            data = get_url(candidate, None, 8 * 1024 * 1024)
            assert isinstance(data, bytes)
            return data.decode("utf-8", errors="replace")
        except Exception as exc:
            errors.append(candidate + ": " + str(exc))
            log("Official catalogue fallback warning: " + errors[-1])
    raise RuntimeError("Could not load an official catalogue. " + " | ".join(errors))


def parse_ncert_catalog(source: str) -> list[dict[str, Any]]:
    try:
        a, b = source.index(SUBJECTS_MARKER), source.index(BOOKS_MARKER)
    except ValueError as exc:
        raise ValueError("NCERT textbook catalogue layout changed; parser needs updating.") from exc
    subjects: dict[int, dict[int, str]] = {}
    current_class: int | None = None
    for line in source[a:b].splitlines():
        if line.lstrip().startswith(("//", "/*")):
            continue
        match = CLASS_BRANCH.search(line)
        if match:
            current_class = int(match.group(1))
            subjects.setdefault(current_class, {})
            continue
        match = SUBJECT_TEXT.match(line)
        if match and current_class and match.group(2) and "Select Subject" not in match.group(2):
            subjects[current_class][int(match.group(1))] = html.unescape(match.group(2)).strip()

    found: dict[tuple[int, str], list[tuple[str, str, int]]] = {}
    current: tuple[int, str] | None = None
    titles: dict[str, str] = {}
    for line in source[b:].splitlines():
        match = BOOK_BRANCH.search(line)
        if match:
            current = (int(match.group(1)), html.unescape(match.group(2)).strip())
            found.setdefault(current, [])
            titles = {}
            continue
        if line.lstrip().startswith(("//", "/*")):
            continue
        match = BOOK_TEXT.match(line)
        if match and current:
            titles[match.group(1)] = html.unescape(match.group(2)).strip()
            continue
        match = BOOK_VALUE.match(line)
        if match and current:
            title = titles.get(match.group(1), "")
            if title and title != "..Select Book Title..":
                found[current].append((title, match.group(2), int(match.group(3))))

    result: list[dict[str, Any]] = []
    seen: set[tuple[int, str, str]] = set()
    for class_no in sorted(subjects):
        if not 6 <= class_no <= 12:
            continue
        for _, subject in sorted(subjects[class_no].items()):
            for title, code, chapter_count in found.get((class_no, subject), []):
                key = (class_no, code, title)
                if key in seen:
                    continue
                seen.add(key)
                if len(code) < 2 or code[1] not in {"h", "e"}:
                    continue
                medium = "hindi" if code[1] == "h" else "english"
                result.append({
                    "book_id": "ncert-c" + str(class_no) + "-" + medium + "-" + code,
                    "publisher": "NCERT", "source_type": "NCERT",
                    "class": class_no, "classes": [class_no], "medium": medium,
                    "title": title, "subject": subject,
                    "language": "Hindi" if medium == "hindi" else "English",
                    "edition": "Current official catalogue; retain printed reprint year from the source PDF",
                    "source_url": NCERT_CATALOG,
                    "catalog_entry_url": NCERT + "/textbook.php?" + urllib.parse.urlencode({code: "0-" + str(chapter_count)}),
                    "bundle_url": NCERT + "/textbook/pdf/" + code + "dd.zip",
                    "pdf_url": NCERT + "/textbook/pdf/" + code + "dd.zip",
                    "code": code, "chapter_count": chapter_count,
                })
    return result


def strip_html(value: str) -> str:
    value = re.sub(r"(?is)<script\b.*?</script>|<style\b.*?</style>", " ", value)
    value = re.sub(r"(?s)<[^>]+>", " ", value)
    return re.sub(r"\s+", " ", html.unescape(value)).strip()


def parse_scert_classes(text: str) -> list[int]:
    value = text.casefold()
    classes: set[int] = set()
    for roman, number in ROMAN_CLASSES.items():
        if re.search(r"\bclass(?:\s*:)?\s*(?:class\s*)?" + roman + r"\b", value):
            classes.add(number)
    for match in re.finditer(r"\bclass(?:\s*:)?\s*(?:class\s*)?([6-9]|1[0-2])\b", value):
        classes.add(int(match.group(1)))
    return sorted(classes)


def parse_scert_medium(text: str) -> str | None:
    value = text.casefold().replace("–", "-")
    hindi = any(item in value for item in ("hindi", "हिंदी", "हिन्दी"))
    english = "english" in value or "अंग्रेजी" in value or "अंग्रेज़ी" in value
    if hindi and english:
        return "both"
    if hindi:
        return "hindi"
    if english:
        return "english"
    return None


def discover_scert_urls(max_pages: int = 40) -> list[str]:
    pending = [SCERT_CATALOG]
    visited: set[str] = set()
    detail: set[str] = set()
    while pending and len(visited) < max_pages:
        url = pending.pop(0)
        if url in visited:
            continue
        visited.add(url)
        try:
            page = fetch_text(url)
        except Exception as exc:
            log("SCERT listing warning: " + str(exc))
            continue
        for href in re.findall(r"""(?is)\bhref\s*=\s*['"]([^'"]+)['"]""", page):
            absolute = urllib.parse.urljoin(url, html.unescape(href.strip()))
            if not host_allowed(absolute):
                continue
            parsed = urllib.parse.urlparse(absolute)
            path = urllib.parse.unquote(parsed.path)
            if path.startswith("/eresources/") and path not in {"/eresources/", "/eresources/index.html"}:
                detail.add(absolute.split("#", 1)[0])
            elif path.rstrip("/") == "/eresources" and parsed.query:
                query = urllib.parse.parse_qs(parsed.query)
                if "page" in query or "per_page" in query:
                    normalized = urllib.parse.urlunparse((parsed.scheme, parsed.netloc, parsed.path, "", parsed.query, ""))
                    if normalized not in visited and normalized not in pending:
                        pending.append(normalized)
        if len(detail) >= 4000:
            break
    return sorted(detail)


def parse_scert_detail(url: str, page: str) -> list[dict[str, Any]]:
    title = ""
    for heading in re.findall(r"(?is)<h[1-6]\b[^>]*>(.*?)</h[1-6]>", page):
        text = strip_html(heading)
        if text and "latest e-content" not in text.casefold() and "popular e-content" not in text.casefold():
            title = text
            break
    if not title:
        title = re.sub(r"-\d{6,}$", "", urllib.parse.unquote(urllib.parse.urlparse(url).path.rsplit("/", 1)[-1])).replace("-", " ")
    plain = strip_html(page)
    match = re.search(r"(?is)class\s*:\s*(.{0,240}?)(?:subject\s*:|language\s*:)", page)
    classes = parse_scert_classes(strip_html(match.group(1))) if match else parse_scert_classes(plain)
    classes = [value for value in classes if 6 <= value <= 12]
    language_match = re.search(r"(?is)language\s*:\s*(.{1,120}?)(?:publisher\s*:|viewed\s*:|downloaded\s*:|formate\s*:|format\s*:|$)", page)
    medium = parse_scert_medium(strip_html(language_match.group(1)) if language_match else plain)
    if not classes or medium is None:
        return []
    pdf_urls = []
    for href in re.findall(r"""(?is)\bhref\s*=\s*['"]([^'"]+)['"]""", page):
        absolute = urllib.parse.urljoin(url, html.unescape(href.strip()))
        if host_allowed(absolute) and urllib.parse.urlparse(absolute).path.casefold().endswith(".pdf") and absolute not in pdf_urls:
            pdf_urls.append(absolute)
    if not pdf_urls:
        return []
    pdf_url = next((item for item in pdf_urls if "/public/uploads/eresources/" in urllib.parse.urlparse(item).path), pdf_urls[0])
    subject_match = re.search(r"(?is)subject\s*:\s*(.{1,180}?)(?:language\s*:|publisher\s*:|viewed\s*:|downloaded\s*:|$)", page)
    subject = strip_html(subject_match.group(1)) if subject_match else "Unspecified"
    id_match = re.search(r"(\d{6,})$", urllib.parse.urlparse(url).path)
    stable_id = id_match.group(1) if id_match else hashlib.sha256(url.encode()).hexdigest()[:12]
    result = []
    for lang in (["hindi", "english"] if medium == "both" else [medium]):
        result.append({
            "book_id": "scert-bihar-" + stable_id + "-" + lang,
            "publisher": "SCERT Bihar", "source_type": "SCERT_BIHAR",
            "class": classes[0], "classes": classes, "medium": lang,
            "title": title, "subject": subject, "language": "Hindi + English" if medium == "both" else lang.title(),
            "edition": "Official SCERT Bihar E-resources; verify edition/session from PDF",
            "source_url": url, "catalog_entry_url": url,
            "bundle_url": pdf_url, "pdf_url": pdf_url, "code": "", "chapter_count": None,
        })
    return result


def sort_catalog(books: list[dict[str, Any]]) -> list[dict[str, Any]]:
    unique = {item["book_id"]: item for item in books if item["medium"] in LANGUAGES}
    return sorted(unique.values(), key=lambda item: (
        item["medium"], min(item.get("classes") or [item["class"]]),
        0 if item["source_type"] == "SCERT_BIHAR" else 1,
        str(item["title"]).casefold(), item["book_id"],
    ))


def discover_ncert_books() -> list[dict[str, Any]]:
    log("Discovering current official NCERT catalogue...")
    books = sort_catalog(parse_ncert_catalog(fetch_text(NCERT_CATALOG)))
    log("NCERT entries: " + str(len(books)))
    return books


def discover_scert_books() -> list[dict[str, Any]]:
    log("Discovering SCERT Bihar E-resources...")
    urls = discover_scert_urls()
    log("SCERT detail pages found: " + str(len(urls)))
    books: list[dict[str, Any]] = []
    for index, url in enumerate(urls, start=1):
        try:
            books.extend(parse_scert_detail(url, fetch_text(url)))
        except Exception as exc:
            log("SCERT detail warning " + str(index) + ": " + url + " (" + str(exc) + ")")
    result = sort_catalog(books)
    log("SCERT Hindi/English Class 6–12 entries parsed: " + str(len(result)))
    return result


def discover_books() -> list[dict[str, Any]]:
    return sort_catalog(discover_ncert_books() + discover_scert_books())


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(CHUNK), b""):
            digest.update(block)
    return digest.hexdigest()


def download_ncert_merged(book: dict[str, Any], destination: Path) -> tuple[str, int, list[list[Any]], str]:
    """Download and validate a complete NCERT book bundle. No chapter fallback."""
    try:
        import fitz
    except ImportError as exc:
        raise RuntimeError("Missing PyMuPDF: install with python -m pip install pymupdf.") from exc

    code = str(book["code"])
    expected_chapters = int(book.get("chapter_count") or 0)
    errors: list[str] = []
    candidates = [str(book["bundle_url"])]
    alternate = candidates[0].replace("https://ncert.nic.in", "https://ncert.ncert.org.in")
    if alternate not in candidates:
        candidates.append(alternate)

    with tempfile.TemporaryDirectory(prefix="qe-textbook-fullbook-") as td:
        work = Path(td)
        archive_path = work / (code + "-complete-book.zip")
        staged_pdf = work / (code + "-complete-book.pdf")
        chosen_url: str | None = None
        members: list[tuple[int, str]] = []

        for bundle_url in candidates:
            try:
                get_url(bundle_url, archive_path, None)
                with zipfile.ZipFile(archive_path) as archive:
                    names: list[tuple[int, str]] = []
                    for item in archive.infolist():
                        if item.is_dir():
                            continue
                        basename = Path(item.filename).name
                        chapter_match = re.match(r"^" + re.escape(code) + r"(\d{2})\.+pdf$", basename, re.I)
                        prelims_match = re.match(r"^" + re.escape(code) + r"ps\.+pdf$", basename, re.I)
                        if chapter_match:
                            names.append((int(chapter_match.group(1)), item.filename))
                        elif prelims_match:
                            names.append((0, item.filename))
                    chapter_numbers = sorted({number for number, _ in names if number > 0})
                    if not names:
                        raise ValueError("Official bundle has no recognisable complete-book PDF members.")
                    if expected_chapters and chapter_numbers != list(range(1, expected_chapters + 1)):
                        missing = sorted(set(range(1, expected_chapters + 1)) - set(chapter_numbers))
                        raise ValueError(
                            "Official book bundle is incomplete; expected chapters 1 through "
                            + str(expected_chapters) + ", missing " + str(missing) + "."
                        )
                    members = sorted(names, key=lambda item: (item[0], item[1]))
                    chosen_url = bundle_url
                    break
            except (FileNotFoundError, RuntimeError, ValueError, zipfile.BadZipFile) as exc:
                errors.append(bundle_url + ": " + str(exc))
                archive_path.unlink(missing_ok=True)

        if chosen_url is None:
            raise RuntimeError(
                "Could not retrieve one complete NCERT book bundle; no individual-chapter fallback was attempted. "
                + " | ".join(errors)
            )

        merged = fitz.open()
        toc: list[list[Any]] = []
        try:
            with zipfile.ZipFile(archive_path) as archive:
                for chapter_no, member_name in members:
                    member_pdf = work / ("member-" + str(chapter_no) + ".pdf")
                    with archive.open(member_name, "r") as source, member_pdf.open("wb") as target:
                        shutil.copyfileobj(source, target, length=CHUNK)
                    with member_pdf.open("rb") as handle:
                        signature = handle.read(5)
                    if signature != b"%PDF-":
                        member_pdf.unlink(missing_ok=True)
                        raise ValueError("Book bundle member is not a valid PDF: " + member_name)
                    with fitz.open(member_pdf) as chapter:
                        start_page = len(merged) + 1
                        merged.insert_pdf(chapter)
                    if chapter_no:
                        toc.append([1, "Chapter " + str(chapter_no), start_page])
                    member_pdf.unlink(missing_ok=True)

            if not len(merged):
                raise ValueError("Complete NCERT book bundle contained no readable pages.")
            actual_chapters = len([row for row in toc if row[1].startswith("Chapter ")])
            if expected_chapters and actual_chapters != expected_chapters:
                raise ValueError(
                    "Merged PDF does not contain all " + str(expected_chapters) + " expected chapters."
                )
            merged.set_toc(toc)
            merged.set_metadata({
                "title": str(book["title"]), "author": "NCERT",
                "subject": str(book.get("subject", "")),
                "keywords": "QuantaEdge textbook cache; language=" + str(book["medium"]),
            })
            merged.save(staged_pdf, garbage=4, deflate=True)
        finally:
            merged.close()

        with fitz.open(staged_pdf) as complete:
            if complete.needs_pass or len(complete) < 1:
                raise ValueError("Merged NCERT book is unreadable or password-protected.")
            page_count = len(complete)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(staged_pdf, destination)
        book["pdf_url"] = chosen_url

    return sha256_file(destination), page_count, toc, "Merged from official complete-book ZIP; no chapter fallback"


def download_scert_pdf(book: dict[str, Any], destination: Path) -> tuple[str, int, list[list[Any]], str]:
    try:
        import fitz
    except ImportError as exc:
        raise RuntimeError("Missing PyMuPDF: install with python -m pip install pymupdf.") from exc
    get_url(book["pdf_url"], destination, None)
    with fitz.open(destination) as pdf:
        if pdf.needs_pass:
            raise ValueError("Password-protected SCERT PDF is unsupported.")
        pages = len(pdf)
        if pages < 1 or pages > 2000:
            raise ValueError("SCERT book must contain 1–2,000 pages.")
        toc = [[int(row[0]), str(row[1]), int(row[2])] for row in pdf.get_toc() if len(row) >= 3]
        if not str(pdf.metadata.get("title") or "").strip():
            pdf.set_metadata({"title": book["title"], "author": "SCERT Bihar", "subject": str(book.get("subject", ""))})
    with destination.open("rb") as handle:
        if handle.read(5) != b"%PDF-":
            raise ValueError("Downloaded SCERT resource does not have a PDF signature.")
    return sha256_file(destination), pages, toc, "Official SCERT Bihar PDF download"


def pull_index(image: str) -> tuple[bool, dict[str, Any]]:
    """Read the existing persistent image before deciding which books are missing."""
    try:
        subprocess.run(
            ["docker", "pull", image], check=True, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True,
        )
    except subprocess.CalledProcessError as exc:
        message = exc.stdout or ""
        lowered = message.casefold()
        # Only initialise if Docker proves the manifest does not exist. Never
        # mistake an auth/network failure for an empty cache and overwrite it.
        missing = ("manifest unknown" in lowered or "manifest not found" in lowered
                   or "no matching manifest" in lowered)
        if missing and "denied" not in lowered and "unauthorized" not in lowered:
            log("No existing manifest for " + image + "; initial registry push.")
            return False, {"schema_version": 1, "registry": image, "books": [], "download_status": {}}
        raise RuntimeError(
            "Could not read existing GHCR cache " + image
            + "; refusing to reset it. Docker said: " + message[-900:]
        ) from exc

    container = "qe-index-" + hashlib.sha1((image + now()).encode()).hexdigest()[:10]
    subprocess.run(
        ["docker", "create", "--name", container, image, "/__quantaedge_cache_inspection_only"],
        check=True, stdout=subprocess.DEVNULL,
    )
    try:
        with tempfile.TemporaryDirectory(prefix="qe-index-") as td:
            path = Path(td) / "index.json"
            subprocess.run(
                ["docker", "cp", container + ":/index.json", str(path)],
                check=True, stdout=subprocess.DEVNULL,
            )
            data = json.loads(path.read_text(encoding="utf-8"))
            if not isinstance(data, dict) or not isinstance(data.get("books"), list):
                raise ValueError("Existing GHCR cache has invalid index.json; refusing to reset it.")
            if not isinstance(data.get("download_status", {}), dict):
                data["download_status"] = {}
            return True, data
    finally:
        subprocess.run(["docker", "rm", container], check=False,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def summary(line: str) -> None:
    target = os.environ.get("GITHUB_STEP_SUMMARY")
    if target:
        with open(target, "a", encoding="utf-8") as handle:
            handle.write(line.rstrip() + "\n")


def push_batch(image: str, base_exists: bool, books_to_add: list[dict[str, Any]],
               files_by_id: dict[str, Path], index: dict[str, Any], context: Path) -> None:
    """Build one multi-book layer batch and update the same persistent image tag."""
    if context.exists():
        shutil.rmtree(context)
    books_dir = context / "books"
    books_dir.mkdir(parents=True, exist_ok=True)
    for book in books_to_add:
        book_id = str(book["book_id"])
        shutil.copyfile(files_by_id[book_id], books_dir / (book_id + ".pdf"))
    (context / "index.json").write_text(
        json.dumps(index, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )

    lines = [
        "FROM " + image if base_exists else "FROM scratch",
        "LABEL org.opencontainers.image.title=\"QuantaEdge textbook cache\"",
        "LABEL org.opencontainers.image.description=\"Persistent textbook cache; see /index.json\"",
        "COPY index.json /index.json",
    ]
    # Separate COPY instructions create content-addressed layers for each whole
    # book. Repeated pushes reuse prior layers; only new books add binary layers.
    for book in books_to_add:
        filename = str(book["book_id"]) + ".pdf"
        lines.append("COPY books/" + filename + " /books/" + filename)
    (context / "Dockerfile").write_text("\n".join(lines) + "\n", encoding="utf-8")

    local_tag = image.rsplit(":", 1)[0] + ":qe-batch-build"
    try:
        subprocess.run(["docker", "build", "--pull=false", "-t", local_tag, str(context)], check=True)
        subprocess.run(["docker", "tag", local_tag, image], check=True)
        # Durable source of truth: update this same package and tag every batch.
        subprocess.run(["docker", "push", image], check=True)
    finally:
        subprocess.run(["docker", "image", "rm", local_tag], check=False,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def _book_entry(book: dict[str, Any], digest: str, page_count: int,
                toc: list[list[Any]], method: str, pdf_path: Path) -> dict[str, Any]:
    return {
        **book,
        "class": str(book["class"]),
        "classes": [str(value) for value in book.get("classes", [book["class"]])],
        "sha256": digest,
        "bytes": pdf_path.stat().st_size,
        "page_count": page_count,
        "toc": toc,
        "file": "books/" + str(book["book_id"]) + ".pdf",
        "download_method": method,
        "rights_basis": (
            "NCERT licence asserted by repository owner; confidential evidence is not committed"
            if book["source_type"] == "NCERT"
            else "Official SCERT Bihar E-resources; source URL retained for provenance"
        ),
        "cached_at": now(),
    }


def _index_add_books(index: dict[str, Any], entries: list[dict[str, Any]]) -> None:
    current = {item.get("book_id"): item for item in index.get("books", []) if isinstance(item, dict)}
    for entry in entries:
        current[entry["book_id"]] = entry
    index["books"] = sorted(current.values(), key=lambda item: (
        min([int(n) for n in item.get("classes", [item.get("class", 0)])]),
        item.get("publisher", ""),
        str(item.get("title", "")).casefold(),
        item.get("book_id", ""),
    ))


def publish_language(language: str, image: str, book_code: str | None = None, refresh: bool = False,
                     max_books: int = 0, ncert_only: bool = False,
                     catalog: list[dict[str, Any]] | None = None,
                     download_workers: int = DEFAULT_DOWNLOAD_WORKERS,
                     push_batch_size: int = DEFAULT_PUSH_BATCH_SIZE,
                     retry_rounds: int = MAX_RETRY_ROUNDS) -> dict[str, Any]:
    if language not in LANGUAGES:
        raise ValueError("Language must be hindi or english.")
    if not 1 <= download_workers <= 32:
        raise ValueError("download-workers must be between 1 and 32.")
    if not 1 <= push_batch_size <= 32:
        raise ValueError("push-batch-size must be between 1 and 32.")
    if not 1 <= retry_rounds <= MAX_RETRY_ROUNDS:
        raise ValueError("retry-rounds must be between 1 and 5.")

    # Always pull the exact existing language image before attempting any source download.
    base_exists, index = pull_index(image)
    index.setdefault("schema_version", 1)
    index.setdefault("registry", image)
    index.setdefault("books", [])
    index.setdefault("download_status", {})
    if not isinstance(index["download_status"], dict):
        index["download_status"] = {}
    existing = {item.get("book_id"): item for item in index["books"] if isinstance(item, dict)}
    catalog = catalog if catalog is not None else discover_books()
    selected = [item for item in catalog if item["medium"] == language]
    if ncert_only:
        selected = [item for item in selected if item["source_type"] == "NCERT"]
    if book_code:
        selected = [
            item for item in selected
            if item.get("code", "").casefold() == book_code.casefold()
            or item["book_id"].casefold() == book_code.casefold()
        ]
        if not selected:
            raise ValueError("No " + language + " book matched " + book_code)

    already_cached = [item["book_id"] for item in selected if item["book_id"] in existing and not refresh]
    candidates = [item for item in selected if refresh or item["book_id"] not in existing]
    if max_books > 0:
        candidates = candidates[:max_books]
    log(
        "Using persistent image " + image + "; books present before run=" + str(len(existing))
        + "; catalog entries=" + str(len(selected)) + "; pending=" + str(len(candidates))
        + "; concurrent downloads=" + str(download_workers)
        + "; books per Docker push=" + str(push_batch_size)
        + "; maximum rounds=" + str(retry_rounds)
    )

    downloaded: list[str] = []
    unchanged: list[str] = []
    failed_downloads: dict[str, dict[str, Any]] = {}
    push_failures: dict[str, str] = {}
    attempt_in_this_run: dict[str, int] = {item["book_id"]: 0 for item in candidates}
    initial_attempt_totals: dict[str, int] = {
        item["book_id"]: int(index.get("download_status", {}).get(item["book_id"], {}).get("attempts_total", 0))
        for item in candidates
    }
    ready_for_push: dict[str, dict[str, Any]] = {}

    with tempfile.TemporaryDirectory(prefix="qe-textbook-batched-") as td:
        temp_root = Path(td)
        downloads_root = temp_root / "downloads"
        downloads_root.mkdir(parents=True, exist_ok=True)

        def persist_index_only() -> bool:
            nonlocal base_exists
            candidate_index = copy.deepcopy(index)
            candidate_index.update({
                "schema_version": 1, "registry": image,
                "language": language, "updated_at": now(),
            })
            try:
                push_batch(image, base_exists, [], {}, candidate_index, temp_root / "index-only")
                index.clear()
                index.update(candidate_index)
                base_exists = True
                return True
            except Exception as exc:
                log("REGISTRY_STATUS_PUSH_FAILED " + image + ": " + str(exc))
                return False

        def push_ready(batch_ids: list[str]) -> bool:
            nonlocal base_exists
            if not batch_ids:
                return True
            entries = [ready_for_push[book_id]["entry"] for book_id in batch_ids]
            candidate_index = copy.deepcopy(index)
            _index_add_books(candidate_index, entries)
            candidate_status = candidate_index.setdefault("download_status", {})
            for book_id in batch_ids:
                item = ready_for_push[book_id]
                candidate_status[book_id] = {
                    "status": "cached",
                    "attempts_this_run": attempt_in_this_run.get(book_id, 0),
                    "attempts_total": initial_attempt_totals.get(book_id, 0) + attempt_in_this_run.get(book_id, 0),
                    "sha256": item["entry"]["sha256"],
                    "bytes": item["entry"]["bytes"],
                    "last_success_at": now(),
                }
            candidate_index.update({
                "schema_version": 1, "registry": image,
                "language": language, "updated_at": now(),
            })
            files_by_id = {book_id: ready_for_push[book_id]["path"] for book_id in batch_ids}
            try:
                push_batch(image, base_exists, entries, files_by_id, candidate_index, temp_root / "oci-context")
                index.clear()
                index.update(candidate_index)
                base_exists = True
                for book_id in batch_ids:
                    downloaded.append(book_id)
                    ready_for_push.pop(book_id, None)
                    existing[book_id] = next(entry for entry in entries if entry["book_id"] == book_id)
                    failed_downloads.pop(book_id, None)
                    push_failures.pop(book_id, None)
                    entry = existing[book_id]
                    summary("| " + language.title() + " | " + str(entry["class"]) + " | " + str(entry["publisher"])
                            + " | " + str(entry["title"]).replace("|", "\\|") + " | " + book_id
                            + " | " + str(entry["sha256"])[:12] + " |")
                    log("PUSHED " + book_id + " image=" + image + " sha256=" + str(entry["sha256"])
                        + " bytes=" + str(entry["bytes"]))
                return True
            except Exception as exc:
                for book_id in batch_ids:
                    item = ready_for_push[book_id]
                    push_failures[book_id] = str(exc)
                    previous = index.get("download_status", {}).get(book_id, {})
                    index.setdefault("download_status", {})[book_id] = {
                        "status": "downloaded_pending_push",
                        "attempts_this_run": attempt_in_this_run.get(book_id, 0),
                        "attempts_total": initial_attempt_totals.get(book_id, 0) + attempt_in_this_run.get(book_id, 0),
                        "sha256": item["entry"]["sha256"],
                        "bytes": item["entry"]["bytes"],
                        "last_error": "GHCR batch push failed: " + str(exc),
                        "updated_at": now(),
                    }
                log("BATCH_PUSH_FAILED " + image + " books=" + ",".join(batch_ids) + ": " + str(exc))
                persist_index_only()
                return False

        def download_batch(batch: list[dict[str, Any]], round_no: int) -> None:
            futures = {}
            for book in batch:
                book_id = book["book_id"]
                attempt_in_this_run[book_id] = attempt_in_this_run.get(book_id, 0) + 1
            with ThreadPoolExecutor(max_workers=min(download_workers, len(batch))) as executor:
                for book in batch:
                    book_id = book["book_id"]
                    destination = downloads_root / (book_id + ".pdf")
                    futures[executor.submit(_download_full_book, book, destination)] = book
                for future in as_completed(futures):
                    book = futures[future]
                    book_id = book["book_id"]
                    try:
                        digest, pages, toc, method, pdf_path = future.result()
                        previous = existing.get(book_id)
                        if previous and previous.get("sha256") == digest and refresh:
                            unchanged.append(book_id)
                            index.setdefault("download_status", {})[book_id] = {
                                "status": "cached",
                                "sha256": digest,
                                "bytes": pdf_path.stat().st_size,
                                "attempts_this_run": attempt_in_this_run.get(book_id, 0),
                                "attempts_total": initial_attempt_totals.get(book_id, 0) + attempt_in_this_run.get(book_id, 0),
                                "last_success_at": now(),
                            }
                            pdf_path.unlink(missing_ok=True)
                            continue
                        ready_for_push[book_id] = {
                            "book": book,
                            "path": pdf_path,
                            "entry": _book_entry(book, digest, pages, toc, method, pdf_path),
                        }
                        previous_status = index.setdefault("download_status", {}).get(book_id, {})
                        index["download_status"][book_id] = {
                            "status": "downloaded_pending_push",
                            "attempts_this_run": attempt_in_this_run.get(book_id, 0),
                            "attempts_total": initial_attempt_totals.get(book_id, 0) + attempt_in_this_run.get(book_id, 0),
                            "sha256": digest,
                            "bytes": pdf_path.stat().st_size,
                            "updated_at": now(),
                        }
                    except Exception as exc:
                        previous_status = index.setdefault("download_status", {}).get(book_id, {})
                        history = list(previous_status.get("attempt_history", []))
                        history.append({"round": round_no, "at": now(), "error": str(exc)})
                        index["download_status"][book_id] = {
                            "status": "failed" if attempt_in_this_run[book_id] >= retry_rounds else "retry_pending",
                            "attempts_this_run": attempt_in_this_run[book_id],
                            "attempts_total": initial_attempt_totals.get(book_id, 0) + attempt_in_this_run[book_id],
                            "last_error": str(exc),
                            "attempt_history": history[-20:],
                            "updated_at": now(),
                        }
                        failed_downloads[book_id] = {
                            "book_id": book_id, "title": book["title"],
                            "attempts_this_run": attempt_in_this_run[book_id], "error": str(exc),
                        }
                        log("BOOK_FAILED round=" + str(round_no) + " "
                            + json.dumps(failed_downloads[book_id], ensure_ascii=False))

            successful_ids = [book_id for book_id in ready_for_push if book_id in {b["book_id"] for b in batch}]
            for offset in range(0, len(successful_ids), push_batch_size):
                push_ready(successful_ids[offset:offset + push_batch_size])
            if not successful_ids:
                persist_index_only()

        for round_no in range(1, retry_rounds + 1):
            log("RETRY_ROUND " + str(round_no) + "/" + str(retry_rounds) + " language=" + language)
            pending_push_ids = list(ready_for_push)
            for offset in range(0, len(pending_push_ids), push_batch_size):
                push_ready(pending_push_ids[offset:offset + push_batch_size])

            pending = [
                item for item in candidates
                if item["book_id"] not in existing
                and item["book_id"] not in ready_for_push
                and attempt_in_this_run.get(item["book_id"], 0) < retry_rounds
            ]
            if not pending:
                if not ready_for_push:
                    break
                continue
            chunks = [pending[offset:offset + push_batch_size] for offset in range(0, len(pending), push_batch_size)]
            for batch in chunks:
                download_batch(batch, round_no)

        for offset in range(0, len(ready_for_push), push_batch_size):
            push_ready(list(ready_for_push)[offset:offset + push_batch_size])
        if any(item.get("status") in {"retry_pending", "failed", "downloaded_pending_push"}
               for item in index.get("download_status", {}).values()):
            persist_index_only()

    for book in selected:
        book_id = book["book_id"]
        status = index.get("download_status", {}).get(book_id, {})
        if book_id not in existing and status.get("status") in {"failed", "retry_pending", "downloaded_pending_push"}:
            failed_downloads.setdefault(book_id, {
                "book_id": book_id, "title": book["title"],
                "attempts_this_run": attempt_in_this_run.get(book_id, 0),
                "error": status.get("last_error", "Not present in the GHCR cache after retries."),
            })

    report = {
        "language": language,
        "image": image,
        "persistent_tag": "latest",
        "catalog_entries": len(selected),
        "books_present_before_run": len(existing) - len(downloaded),
        "already_cached": already_cached,
        "downloaded_and_pushed": downloaded,
        "unchanged_after_refresh": unchanged,
        "failed_after_retries": list(failed_downloads.values()),
        "push_failures": [{"book_id": key, "error": value} for key, value in push_failures.items()],
        "books_in_registry": len(index.get("books", [])),
        "retry_rounds": retry_rounds,
        "download_workers": download_workers,
        "push_batch_size": push_batch_size,
        "updated_at": now(),
    }
    log("BOOK_CACHE_REPORT " + json.dumps(report, ensure_ascii=False))
    summary("\n## " + language.title() + " textbook-cache result\n\n"
            + "- Persistent image: " + image + "\n"
            + "- Books already cached: " + str(len(already_cached)) + "\n"
            + "- Books newly downloaded and pushed: " + str(len(downloaded)) + "\n"
            + "- Unchanged on refresh: " + str(len(unchanged)) + "\n"
            + "- Books still missing after retries: " + str(len(failed_downloads)) + "\n"
            + "- Registry image books total: " + str(len(index.get("books", []))) + "\n"
            + "- Download workers: " + str(download_workers) + "; push batch size: " + str(push_batch_size)
            + "; retry rounds: " + str(retry_rounds) + "\n")
    if failed_downloads:
        summary("\n### Books not downloaded after retry rounds\n\n"
                + "| Book ID | Title | Attempts | Last error |\n|---|---|---:|---|\n"
                + "\n".join("| " + str(row["book_id"]) + " | " + str(row["title"]).replace("|", "\\|")
                            + " | " + str(row["attempts_this_run"]) + " | "
                            + str(row["error"]).replace("|", "\\|").replace("\n", " ") + " |"
                            for row in failed_downloads.values()) + "\n")
    if push_failures:
        summary("\n### GHCR push failures\n\n" + "\n".join(
            "- " + str(key) + ": " + error.replace("\n", " ") for key, error in push_failures.items()
        ) + "\n")
    return report


def _download_full_book(book: dict[str, Any], destination: Path) -> tuple[str, int, list[list[Any]], str, Path]:
    """Worker entry: download and validate one complete book, never a partial fallback."""
    if book["source_type"] == "NCERT":
        digest, pages, toc, method = download_ncert_merged(book, destination)
    else:
        digest, pages, toc, method = download_scert_pdf(book, destination)
    if not destination.is_file() or destination.stat().st_size == 0:
        raise ValueError("Complete textbook PDF was not produced.")
    return digest, pages, toc, method, destination


def pull_image(image: str, output_dir: Path) -> dict[str, Any]:
    """Synchronize an image into a local cache, copying only missing/changed PDFs."""
    subprocess.run(["docker", "pull", image], check=True)
    output_dir.mkdir(parents=True, exist_ok=True)
    container = "qe-textbook-pull-" + hashlib.sha1((image + now()).encode()).hexdigest()[:10]
    subprocess.run(["docker", "create", "--name", container, image, "/__quantaedge_cache_inspection_only"], check=True, stdout=subprocess.DEVNULL)
    temp_index = output_dir / ".index.json.download"
    copied = reused = 0
    try:
        subprocess.run(["docker", "cp", container + ":/index.json", str(temp_index)], check=True)
        index = json.loads(temp_index.read_text(encoding="utf-8"))
        if not isinstance(index.get("books"), list):
            raise ValueError("Textbook image has an invalid index.json.")
        for item in index["books"]:
            relative = Path(str(item.get("file", "")))
            if relative.is_absolute() or not relative.parts or ".." in relative.parts:
                raise ValueError("Unsafe file path in registry index for " + str(item.get("book_id", "unknown")))
            target = output_dir / relative
            expected = str(item.get("sha256", ""))
            if target.is_file() and expected and sha256_file(target) == expected:
                reused += 1
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            staged = target.with_name(target.name + ".download")
            source = "/" + relative.as_posix().lstrip("/")
            subprocess.run(["docker", "cp", container + ":" + source, str(staged)], check=True)
            if not staged.is_file() or not expected or sha256_file(staged) != expected:
                staged.unlink(missing_ok=True)
                raise ValueError("Checksum verification failed while extracting " + str(item.get("book_id", "unknown")))
            staged.replace(target)
            copied += 1
        # Swap index only after every declared PDF has passed SHA-256 verification.
        temp_index.replace(output_dir / "index.json")
    finally:
        temp_index.unlink(missing_ok=True)
        subprocess.run(["docker", "rm", container], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    index = json.loads((output_dir / "index.json").read_text(encoding="utf-8"))
    report = {
        "image": image, "output_dir": str(output_dir), "book_count": len(index.get("books", [])),
        "downloaded_or_updated_files": copied, "reused_local_files": reused, "checksum_failures": [],
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return report


def _safe_filename(value: str, limit: int = 150) -> str:
    slug = re.sub(r"[^A-Za-z0-9._-]+", "-", value).strip("-._").lower()
    return (slug or "textbook")[:limit]


def _pdf_ranges_from_toc(document: Any, index_item: dict[str, Any]) -> tuple[list[tuple[str, int, int]], str]:
    page_count = len(document)
    raw_toc = index_item.get("toc") or document.get_toc() or []
    candidates: list[tuple[str, int]] = []
    for entry in raw_toc:
        try:
            level, title, page = int(entry[0]), str(entry[1]).strip(), int(entry[2])
        except (IndexError, TypeError, ValueError):
            continue
        if level == 1 and title and 1 <= page <= page_count:
            candidates.append((title, page))
    method = "pdf_outline"
    if not candidates:
        # Conservative fallback: detect explicit chapter/lesson headings at page starts.
        method = "text_heading_suggestion"
        patterns = (
            re.compile(r"^(?:chapter|lesson)\s+(?:no\.?\s*)?([0-9]{1,3}|[ivxlcdm]{1,8})\b[\s.:—-]*(.*)$", re.I),
            re.compile(r"^(?:अध्याय|पाठ)\s*([0-9०-९]{1,3}|[ivxlcdm]{1,8})\b[\s.:—-]*(.*)$", re.I),
        )
        for page_no in range(page_count):
            text = document[page_no].get_text("text") or ""
            lines = [line.strip() for line in text.splitlines() if line.strip()]
            for line in lines[:8]:
                match = next((pattern.match(line) for pattern in patterns if pattern.match(line)), None)
                if match:
                    title = line[:200]
                    candidates.append((title, page_no + 1))
                    break
    by_page: dict[int, str] = {}
    for title, page in candidates:
        by_page.setdefault(page, title)
    ordered = sorted(by_page.items())
    ranges: list[tuple[str, int, int]] = []
    for index, (start, title) in enumerate(ordered):
        end = (ordered[index + 1][0] - 1) if index + 1 < len(ordered) else page_count
        if 1 <= start <= end <= page_count:
            ranges.append((title, start, end))
    # Ignore a lone heading at page 1 that appears to describe the front cover
    # unless there are at least two distinct chapter boundaries to split on.
    if len(ranges) < 2:
        return [], method
    return ranges, method


def prepare_library(registry_dir: Path, output_dir: Path) -> dict[str, Any]:
    """Create <=50 MiB PDF assets and SHA-verified bundles for the existing private library importer."""
    try:
        import fitz
    except ImportError as exc:
        raise RuntimeError("Missing PyMuPDF: install with python -m pip install pymupdf.") from exc
    index_path = registry_dir / "index.json"
    if not index_path.is_file():
        raise ValueError("No registry index found at " + str(index_path) + "; run textbook_registry.py pull first.")
    index = json.loads(index_path.read_text(encoding="utf-8"))
    books = index.get("books")
    if not isinstance(books, list):
        raise ValueError("The registry index does not contain a books array.")
    pdf_dir, bundle_dir = output_dir / "pdfs", output_dir / "bundles"
    pdf_dir.mkdir(parents=True, exist_ok=True)
    bundle_dir.mkdir(parents=True, exist_ok=True)
    report: dict[str, Any] = {"registry": str(registry_dir), "output_dir": str(output_dir),
                              "books_seen": len(books), "pdfs_created": 0, "bundles_created": 0,
                              "whole_books_over_library_limit": [], "no_verified_chapter_boundaries": [],
                              "too_large_chapter_assets": [], "failures": []}
    max_library_bytes = 50 * 1024 * 1024

    def write_asset(source_pdf: Path, item: dict[str, Any], title: str,
                    chapter_title: str | None, page_start: int, page_end: int) -> None:
        slug = _safe_filename(str(item.get("book_id", "book")))
        suffix = "whole-book" if chapter_title is None else "chapter-" + _safe_filename(chapter_title, 50)
        filename = (slug[:75] + "--" + suffix[:100] + ".pdf")[:220]
        destination = pdf_dir / filename
        if chapter_title is None:
            if source_pdf.stat().st_size > max_library_bytes:
                report["whole_books_over_library_limit"].append({
                    "book_id": item.get("book_id"), "title": title,
                    "bytes": source_pdf.stat().st_size, "reason": "The current PostgreSQL PDF library has a 50 MiB file limit.",
                })
                return
            shutil.copyfile(source_pdf, destination)
            pages = int(item.get("page_count") or page_end)
        else:
            with fitz.open(source_pdf) as parent:
                child = fitz.open()
                try:
                    child.insert_pdf(parent, from_page=page_start - 1, to_page=page_end - 1)
                    child.set_metadata({"title": title, "author": str(item.get("publisher", "")),
                                        "subject": str(item.get("subject", ""))})
                    child.save(destination, garbage=4, deflate=True)
                    pages = len(child)
                finally:
                    child.close()
            if destination.stat().st_size > max_library_bytes:
                destination.unlink(missing_ok=True)
                report["too_large_chapter_assets"].append({
                    "book_id": item.get("book_id"), "title": title,
                    "page_start": page_start, "page_end": page_end,
                    "reason": "Chapter split still exceeds the current 50 MiB library limit.",
                })
                return
        digest = sha256_file(destination)
        bundle = {
            "bundle_status": "DRAFT_EXTRACTION_ONLY",
            "source": {
                "pdf_filename": filename, "pdf_sha256": digest, "pdf_page_count": pages,
                "title": title, "source_title": item.get("title"),
                "source_url": item.get("catalog_entry_url") or item.get("source_url"),
                "edition": item.get("edition"), "publisher": item.get("publisher"),
                "language": item.get("medium"), "class": item.get("class"), "classes": item.get("classes"),
                "subject": item.get("subject"), "book_id": item.get("book_id"),
                "scope": "SUBJECT_BOOK" if chapter_title is None else "CHAPTER_PDF",
                "chapter_title": chapter_title, "chapter_page_start": page_start,
                "chapter_page_end": page_end, "original_book_sha256": item.get("sha256"),
            },
            "pages": [],
            "chapter_map": [],
        }
        (bundle_dir / (filename + ".json")).write_text(
            json.dumps(bundle, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        report["pdfs_created"] += 1
        report["bundles_created"] += 1

    for item in books:
        try:
            relative = Path(str(item.get("file", "")))
            if relative.is_absolute() or not relative.parts or ".." in relative.parts:
                raise ValueError("Unsafe relative PDF path in index.")
            source_pdf = registry_dir / relative
            if not source_pdf.is_file():
                raise FileNotFoundError("Cached source PDF missing: " + str(source_pdf))
            actual_hash = sha256_file(source_pdf)
            if actual_hash != item.get("sha256"):
                raise ValueError("Original cached book checksum does not match registry index.")
            with fitz.open(source_pdf) as document:
                if document.needs_pass:
                    raise ValueError("Password-protected source PDF cannot be processed.")
                page_count = len(document)
                if not 1 <= page_count <= 2000:
                    raise ValueError("Source book must have 1–2,000 pages.")
                ranges, method = _pdf_ranges_from_toc(document, item)
            # The complete book is useful for reader assignment when it fits the existing
            # database limit. Large originals stay in GHCR and are split into chapter assets.
            write_asset(source_pdf, item, str(item.get("title", item.get("book_id", "Textbook"))),
                        None, 1, page_count)
            if not ranges:
                report["no_verified_chapter_boundaries"].append({
                    "book_id": item.get("book_id"), "title": item.get("title"),
                    "page_count": page_count, "detection_method": method,
                    "note": "A reviewer must provide a chapter/page map; no reliable split was inferred.",
                })
                continue
            for title, start, end in ranges:
                write_asset(source_pdf, item, str(item.get("title", "Textbook")) + " — " + title,
                            title, start, end)
        except Exception as exc:
            failure = {"book_id": item.get("book_id"), "title": item.get("title"), "error": str(exc)}
            report["failures"].append(failure)
            log("PREPARE_FAILED " + json.dumps(failure, ensure_ascii=False))
    report_path = output_dir / "prepare-report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    discover = sub.add_parser("discover", help="List official NCERT and SCERT Bihar entries without downloading PDFs")
    discover.add_argument("--language", choices=("hindi", "english", "both"), default="both")
    publish = sub.add_parser("publish", help="Download one book, push it, and continue")
    publish.add_argument("--language", choices=("hindi", "english", "both"), required=True)
    publish.add_argument("--image-prefix", default=IMAGE_PREFIX)
    publish.add_argument("--book-code", help="Optional exact NCERT book code or registry book_id")
    publish.add_argument("--refresh", action="store_true")
    publish.add_argument("--max-books", type=int, default=0, help="Zero means all pending books")
    publish.add_argument("--ncert-only", action="store_true")
    publish.add_argument("--download-workers", type=int, default=DEFAULT_DOWNLOAD_WORKERS)
    publish.add_argument("--push-batch-size", type=int, default=DEFAULT_PUSH_BATCH_SIZE)
    publish.add_argument("--retry-rounds", type=int, default=MAX_RETRY_ROUNDS)
    pull = sub.add_parser("pull", help="Pull one language image locally and verify all checksums")
    pull.add_argument("--image", required=True)
    pull.add_argument("--output-dir", required=True, type=Path)
    prepare = sub.add_parser("prepare-library", help="Split cached books into draft assets for the existing PDF library importer")
    prepare.add_argument("--registry-dir", required=True, type=Path)
    prepare.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == "discover":
            entries = discover_books()
            if args.language != "both":
                entries = [item for item in entries if item["medium"] == args.language]
            print(json.dumps(entries, ensure_ascii=False, indent=2))
            return 0
        if args.command == "prepare-library":
            report = prepare_library(args.registry_dir, args.output_dir)
            return 1 if report["failures"] else 0
        if args.command == "publish":
            languages = ["hindi", "english"] if args.language == "both" else [args.language]
            reports = []
            prefix = args.image_prefix.lower()

            if args.book_code and args.book_code.casefold().startswith("scert-bihar-"):
                scert_catalog = discover_scert_books()
                matched = [item for item in scert_catalog if item["book_id"].casefold() == args.book_code.casefold()]
                if not matched:
                    raise ValueError("No SCERT textbook matched book_id " + args.book_code)
                languages = [matched[0]["medium"]]
                for language in languages:
                    reports.append(publish_language(
                        language, prefix + "-" + language + ":latest", args.book_code,
                        args.refresh, args.max_books, False, catalog=matched,
                        download_workers=args.download_workers, push_batch_size=args.push_batch_size,
                        retry_rounds=args.retry_rounds,
                    ))
            else:
                # Bootstrap NCERT immediately. Do not wait for the larger SCERT
                # E-resources crawl before the first official book is pushed.
                ncert_catalog = discover_ncert_books()
                if args.book_code:
                    matched = [item for item in ncert_catalog
                               if item.get("code", "").casefold() == args.book_code.casefold()
                               or item["book_id"].casefold() == args.book_code.casefold()]
                    if not matched:
                        raise ValueError("No NCERT textbook matched code/book_id " + args.book_code)
                    languages = [matched[0]["medium"]]
                    ncert_catalog = matched
                for language in languages:
                    reports.append(publish_language(
                        language, prefix + "-" + language + ":latest", args.book_code,
                        args.refresh, args.max_books, True, catalog=ncert_catalog,
                        download_workers=args.download_workers, push_batch_size=args.push_batch_size,
                        retry_rounds=args.retry_rounds,
                    ))

                # Once all selected NCERT books have been pushed, discover SCERT.
                # Each SCERT PDF is still pushed immediately by the same one-book loop.
                if not args.ncert_only and not args.book_code:
                    scert_catalog = discover_scert_books()
                    for language in languages:
                        reports.append(publish_language(
                            language, prefix + "-" + language + ":latest", None,
                            args.refresh, args.max_books, False, catalog=scert_catalog,
                            download_workers=args.download_workers, push_batch_size=args.push_batch_size,
                            retry_rounds=args.retry_rounds,
                        ))
            print(json.dumps({"results": reports}, ensure_ascii=False, indent=2))
            return 1 if any(report["failed"] for report in reports) else 0
        pull_image(args.image, args.output_dir)
        return 0
    except (ValueError, RuntimeError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile) as exc:
        print("ERROR: " + str(exc), file=sys.stderr, flush=True)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
