#!/usr/bin/env python3
"""Batched, resumable textbook cache publisher for the persistent Hindi/English GHCR images.

The publisher reads the current image index before downloading, downloads whole books
concurrently, pushes each complete batch to the same :latest image, and retries only
unresolved books for at most five rounds. No arbitrary binary size limit is imposed.
"""
from __future__ import annotations

import argparse, copy, hashlib, html, json, os, re, shutil, subprocess, sys, tempfile, tarfile
import time
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
import urllib.error, urllib.parse, urllib.request, zipfile
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
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


def get_url(url: str, destination: Path | None = None, limit: int | None = None,
            attempts: int = 4) -> bytes | Path:
    if not 1 <= attempts <= 5:
        raise ValueError("Download attempts per URL must be between 1 and 5.")
    last_error: Exception | None = None
    for attempt in range(1, attempts + 1):
        try:
            return _get_url_once(url, destination, limit)
        except FileNotFoundError:
            raise
        except ValueError:
            raise
        except (RuntimeError, OSError, TimeoutError) as exc:
            last_error = exc
            if attempt < attempts:
                delay = attempt * 2
                log("Transient source failure; retry " + str(attempt + 1) + "/4 in " + str(delay) + "s: " + url)
                time.sleep(delay)
    raise RuntimeError("Failed after " + str(attempts) + " attempt(s): " + url + " (" + str(last_error) + ")")


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
                get_url(bundle_url, archive_path, None, attempts=1)
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

        try:
            import fitz
        except ImportError as exc:
            raise RuntimeError("Missing PyMuPDF: install with python -m pip install pymupdf.") from exc

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
    get_url(book["pdf_url"], destination, None, attempts=1)
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




CACHE_COMPACT_LAYER_THRESHOLD = 90


def _ensure_skopeo() -> str:
    """Install the registry-copy utility only when an over-deep image needs recovery."""
    executable = shutil.which("skopeo")
    if executable:
        return executable
    if os.name != "nt" and shutil.which("apt-get"):
        prefix = []
        if hasattr(os, "geteuid") and os.geteuid() != 0:
            if not shutil.which("sudo"):
                raise RuntimeError("The textbook image is too deep for Docker; install skopeo to recover it.")
            prefix = ["sudo"]
        log("CACHE_RECOVERY installing skopeo to recover the existing image without Docker layer unpacking")
        subprocess.run(prefix + ["apt-get", "update"], check=True)
        subprocess.run(prefix + ["apt-get", "install", "-y", "skopeo"], check=True)
        executable = shutil.which("skopeo")
    if not executable:
        raise RuntimeError(
            "The textbook image exceeded Docker's layer-depth limit. "
            "Install skopeo, then rerun to recover and flatten the existing GHCR image."
        )
    return executable


def _oci_blob_path(layout: Path, digest: str) -> Path:
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", str(digest)):
        raise ValueError("OCI cache image contains an unsupported blob digest.")
    algorithm, value = digest.split(":", 1)
    path = layout / "blobs" / algorithm / value
    if not path.is_file():
        raise ValueError("OCI cache recovery is missing blob " + digest + ".")
    return path


def _cache_layer_path(rootfs: Path, parts: tuple[str, ...]) -> Path | None:
    """Allow only the cache index and book files from the upstream image rootfs."""
    if parts == ("index.json",) or (len(parts) >= 2 and parts[0] == "books"):
        return rootfs.joinpath(*parts)
    if parts == ("books",):
        return rootfs / "books"
    return None


def _extract_oci_cache_rootfs(layout: Path, rootfs: Path) -> dict[str, Any]:
    """Merge the cache's OCI layers without asking Docker to register deep overlay layers."""
    rootfs.mkdir(parents=True, exist_ok=True)
    layout_index = json.loads((layout / "index.json").read_text(encoding="utf-8"))
    manifests = layout_index.get("manifests")
    if not isinstance(manifests, list) or not manifests:
        raise ValueError("The GHCR cache did not contain an OCI manifest.")
    descriptor = next(
        (row for row in manifests
         if (row.get("annotations") or {}).get("org.opencontainers.image.ref.name") == "cache"),
        manifests[0],
    )
    manifest = json.loads(_oci_blob_path(layout, str(descriptor.get("digest", ""))).read_text(encoding="utf-8"))
    layers = manifest.get("layers")
    if not isinstance(layers, list):
        raise ValueError("The GHCR cache OCI manifest has no layers array.")

    for layer_number, layer in enumerate(layers, start=1):
        layer_path = _oci_blob_path(layout, str(layer.get("digest", "")))
        with tarfile.open(layer_path, mode="r:*") as archive:
            for member in archive:
                raw_name = member.name.replace("\\", "/")
                parsed = PurePosixPath(raw_name)
                if parsed.is_absolute() or any(part == ".." for part in parsed.parts):
                    raise ValueError("Unsafe path in OCI cache layer " + str(layer_number) + ".")
                parts = tuple(part for part in parsed.parts if part not in ("", ".", "/"))
                if not parts:
                    continue

                parent_parts, basename = parts[:-1], parts[-1]
                if basename == ".wh..wh..opq":
                    if parent_parts in ((), ("books",)):
                        parent = rootfs.joinpath(*parent_parts) if parent_parts else rootfs
                        if parent.is_dir():
                            for child in parent.iterdir():
                                shutil.rmtree(child) if child.is_dir() else child.unlink(missing_ok=True)
                    continue
                if basename.startswith(".wh."):
                    hidden_parts = parent_parts + (basename[4:],)
                    hidden = _cache_layer_path(rootfs, hidden_parts)
                    if hidden is not None and hidden.exists():
                        shutil.rmtree(hidden) if hidden.is_dir() else hidden.unlink(missing_ok=True)
                    continue

                destination = _cache_layer_path(rootfs, parts)
                if destination is None:
                    continue
                if member.isdir():
                    destination.mkdir(parents=True, exist_ok=True)
                    continue
                if not member.isfile():
                    raise ValueError("Unexpected non-file object in textbook cache layer: " + member.name)
                destination.parent.mkdir(parents=True, exist_ok=True)
                source = archive.extractfile(member)
                if source is None:
                    raise ValueError("Could not read textbook cache layer entry: " + member.name)
                with source, destination.open("wb") as target:
                    shutil.copyfileobj(source, target, length=CHUNK)

    index_path = rootfs / "index.json"
    if not index_path.is_file():
        raise ValueError("The over-deep GHCR image has no recoverable /index.json.")
    data = json.loads(index_path.read_text(encoding="utf-8"))
    if not isinstance(data, dict) or not isinstance(data.get("books"), list):
        raise ValueError("The recovered GHCR image has an invalid index.json.")
    for item in data["books"]:
        if not isinstance(item, dict):
            raise ValueError("The recovered cache index contains an invalid book entry.")
        relative = PurePosixPath(str(item.get("file", "")))
        if relative.is_absolute() or any(part in ("", ".", "..") for part in relative.parts) or len(relative.parts) < 2 or relative.parts[0] != "books":
            raise ValueError("Unsafe cache file path for " + str(item.get("book_id", "unknown")) + ".")
        pdf_path = rootfs.joinpath(*relative.parts)
        if not pdf_path.is_file():
            raise ValueError("Cannot recover cached PDF " + str(item.get("book_id", "unknown")) + "; its indexed file is missing.")
        expected_size = item.get("bytes")
        if expected_size is not None and int(expected_size) != pdf_path.stat().st_size:
            raise ValueError("Cached PDF size does not match index.json for " + str(item.get("book_id", "unknown")) + ".")
        expected_hash = str(item.get("sha256", "")).lower()
        if not re.fullmatch(r"[0-9a-f]{64}", expected_hash) or sha256_file(pdf_path) != expected_hash:
            raise ValueError("Cached PDF checksum failed during recovery for " + str(item.get("book_id", "unknown")) + ".")
        with pdf_path.open("rb") as handle:
            if handle.read(5) != b"%PDF-":
                raise ValueError("Recovered cache entry is not a PDF: " + str(item.get("book_id", "unknown")) + ".")
    return data


def _flatten_remote_cache_image(image: str) -> None:
    """Recover an image that Docker cannot pull, verify its contents, and republish it flat."""
    skopeo = _ensure_skopeo()
    log("CACHE_RECOVERY source=GHCR image=" + image + " method=skopeo-OCI")
    with tempfile.TemporaryDirectory(prefix="qe-textbook-image-recovery-") as temporary:
        temp_root = Path(temporary)
        layout = temp_root / "oci"
        layout.mkdir()
        config_root = Path(os.environ.get("DOCKER_CONFIG", str(Path.home() / ".docker")))
        auth_file = config_root if config_root.name == "config.json" else config_root / "config.json"
        command = [skopeo, "copy"]
        if auth_file.is_file():
            command.extend(["--src-authfile", str(auth_file)])
        command.extend(["docker://" + image, "oci:" + str(layout) + ":cache"])
        subprocess.run(command, check=True)

        rootfs = temp_root / "rootfs"
        recovered_index = _extract_oci_cache_rootfs(layout, rootfs)
        context = temp_root / "flattened-image"
        context.mkdir()
        shutil.copy2(rootfs / "index.json", context / "index.json")
        books_source = rootfs / "books"
        if books_source.is_dir():
            shutil.copytree(books_source, context / "books")
        else:
            (context / "books").mkdir()

        dockerfile = [
            "FROM scratch",
            'LABEL org.opencontainers.image.title="QuantaEdge textbook cache"',
            'LABEL org.opencontainers.image.description="Flattened persistent textbook cache; see /index.json"',
            "COPY index.json /index.json",
            "COPY books/ /books/",
        ]
        (context / "Dockerfile").write_text("\n".join(dockerfile) + "\n", encoding="utf-8")
        subprocess.run(["docker", "build", "--pull=false", "-t", image, str(context)], check=True)
        subprocess.run(["docker", "push", image], check=True)
        log("CACHE_RECOVERY_COMPLETE image=" + image
            + " verified_books=" + str(len(recovered_index["books"]))
            + " action=flattened-and-pushed")


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
        if "max depth exceeded" in lowered:
            log("CACHE_RECOVERY required image=" + image + " reason=docker-max-layer-depth")
            _flatten_remote_cache_image(image)
            try:
                subprocess.run(
                    ["docker", "pull", image], check=True, stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT, text=True,
                )
            except subprocess.CalledProcessError as recovered_exc:
                raise RuntimeError(
                    "The GHCR cache was flattened but Docker still cannot pull " + image + ": "
                    + (recovered_exc.stdout or "")[-900:]
                ) from recovered_exc
        else:
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
    """Update the persistent tag, compacting filesystem layers before Docker's depth limit."""
    if context.exists():
        shutil.rmtree(context)
    context.mkdir(parents=True, exist_ok=True)
    books_dir = context / "books"
    books_dir.mkdir(parents=True, exist_ok=True)

    local_tag = image.rsplit(":", 1)[0] + ":qe-batch-build"
    previous_image_id = None
    layer_depth = 0
    if base_exists:
        inspected = subprocess.run(
            ["docker", "image", "inspect", "--format={{.Id}}", image],
            check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
        )
        previous_image_id = inspected.stdout.strip()
        depth_result = subprocess.run(
            ["docker", "image", "inspect", "--format={{len .RootFS.Layers}}", image],
            check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
        )
        layer_depth = int(depth_result.stdout.strip() or "0")

    compact_image = (not base_exists) or (
        layer_depth + 1 + len(books_to_add) >= CACHE_COMPACT_LAYER_THRESHOLD
    )
    if compact_image:
        log("CACHE_IMAGE_COMPACTION image=" + image
            + " prior_layers=" + str(layer_depth)
            + " incoming_books=" + str(len(books_to_add))
            + " mode=flat-scratch-image")

    # For compaction, rehydrate the existing /books directory into the context, then
    # overwrite any refreshed entries with their newly downloaded whole-book PDFs.
    if compact_image and base_exists and index.get("books"):
        container = "qe-compact-" + hashlib.sha1((image + now()).encode()).hexdigest()[:10]
        subprocess.run(
            ["docker", "create", "--name", container, image, "/__quantaedge_cache_inspection_only"],
            check=True, stdout=subprocess.DEVNULL,
        )
        try:
            subprocess.run(
                ["docker", "cp", container + ":/books/.", str(books_dir)],
                check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
            )
        finally:
            subprocess.run(["docker", "rm", container], check=False,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    for book in books_to_add:
        book_id = str(book["book_id"])
        shutil.copyfile(files_by_id[book_id], books_dir / (book_id + ".pdf"))

    (context / "index.json").write_text(
        json.dumps(index, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )

    lines = [
        "FROM scratch" if compact_image else "FROM " + image,
        'LABEL org.opencontainers.image.title="QuantaEdge textbook cache"',
        'LABEL org.opencontainers.image.description="Persistent textbook cache; see /index.json"',
        "COPY index.json /index.json",
    ]
    if compact_image:
        # One aggregate data layer keeps the image safely below Docker's overlay depth limit.
        if any(books_dir.iterdir()):
            lines.append("COPY books/ /books/")
    else:
        # In ordinary batches, keep each PDF in its own content-addressed reusable layer.
        for book in books_to_add:
            filename = str(book["book_id"]) + ".pdf"
            lines.append("COPY books/" + filename + " /books/" + filename)
    (context / "Dockerfile").write_text("\n".join(lines) + "\n", encoding="utf-8")

    try:
        subprocess.run(["docker", "build", "--pull=false", "-t", local_tag, str(context)], check=True)
        subprocess.run(["docker", "tag", local_tag, image], check=True)
        # Durable source of truth: update this same package and tag every batch.
        try:
            subprocess.run(["docker", "push", image], check=True)
        except Exception:
            # A failed registry push must not leave the local latest tag pointing at
            # a candidate image that was never confirmed in GHCR. Restore the last
            # pulled base before any retry or status-only commit.
            if previous_image_id:
                subprocess.run(["docker", "tag", previous_image_id, image], check=False,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            else:
                subprocess.run(["docker", "image", "rm", image], check=False,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            raise
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



def _class_scoped_book_entry(
    book: dict[str, Any], digest: str, page_count: int,
    toc: list[list[Any]], method: str, pdf_path: Path, class_no: int | None,
) -> dict[str, Any]:
    entry = _book_entry(book, digest, page_count, toc, method, pdf_path)
    if class_no is not None:
        entry["source_classes"] = book.get("classes") or [book.get("class")]
        entry["class"] = str(class_no)
        entry["classes"] = [str(class_no)]
        entry["registry_class"] = class_no
    return entry


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



def _entry_class_numbers(item: dict[str, Any]) -> list[int]:
    raw = item.get("classes")
    values = raw if isinstance(raw, list) else [item.get("class")]
    found: set[int] = set()
    for value in values:
        text = str(value or "").strip().lower()
        if text in ROMAN_CLASSES:
            number = ROMAN_CLASSES[text]
        else:
            match = re.search(r"(?i)(?:class\s*)?(6|7|8|9|10|11|12)\b", text)
            if not match:
                continue
            number = int(match.group(1))
        if 6 <= number <= 12:
            found.add(number)
    return sorted(found)


def class_image_reference(image_prefix: str, class_no: int, language: str) -> str:
    if class_no not in range(6, 13):
        raise ValueError("Class-specific GHCR image requires class 6 through 12.")
    if language not in LANGUAGES:
        raise ValueError("Class-specific GHCR image requires hindi or english medium.")
    return image_prefix.rstrip("-").lower() + "-class-" + str(class_no) + "-" + language + ":latest"


def _safe_registry_book_id(value: Any) -> str:
    book_id = str(value or "")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,239}", book_id):
        raise ValueError("Unsafe textbook ID in GHCR index: " + repr(book_id))
    return book_id


def _extract_image_books(image: str, index: dict[str, Any], output_root: Path) -> Path:
    """Extract a language image's indexed complete books once for one-time class migration."""
    books_root = output_root / "books"
    books_root.mkdir(parents=True, exist_ok=True)
    container = "qe-cache-migrate-" + hashlib.sha1((image + now()).encode()).hexdigest()[:10]
    subprocess.run(
        ["docker", "create", "--name", container, image, "/__quantaedge_cache_inspection_only"],
        check=True, stdout=subprocess.DEVNULL,
    )
    try:
        for item in index.get("books", []):
            if not isinstance(item, dict):
                raise ValueError("A GHCR cache index contains a non-object book entry.")
            book_id = _safe_registry_book_id(item.get("book_id"))
            if str(item.get("file", "")) != "books/" + book_id + ".pdf":
                raise ValueError("Unsafe whole-book file path in the old GHCR index for " + book_id)
            expected = str(item.get("sha256", "")).lower()
            if not re.fullmatch(r"[0-9a-f]{64}", expected):
                raise ValueError("The old GHCR index has no valid whole-book SHA-256 for " + book_id)
            target = books_root / (book_id + ".pdf")
            if target.is_file() and sha256_file(target) == expected:
                if item.get("bytes") is not None and target.stat().st_size != int(item["bytes"]):
                    raise ValueError("The old cached book has a size mismatch: " + book_id)
                continue
            staged = target.with_name(target.name + ".copying")
            staged.unlink(missing_ok=True)
            subprocess.run(
                ["docker", "cp", container + ":/books/" + book_id + ".pdf", str(staged)],
                check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
            )
            if not staged.is_file() or sha256_file(staged) != expected:
                staged.unlink(missing_ok=True)
                raise ValueError("The old GHCR whole-book checksum failed during migration: " + book_id)
            if item.get("bytes") is not None and staged.stat().st_size != int(item["bytes"]):
                staged.unlink(missing_ok=True)
                raise ValueError("The old GHCR whole-book size failed during migration: " + book_id)
            staged.replace(target)
    finally:
        subprocess.run(["docker", "rm", container], check=False,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return output_root


def _seed_class_image_from_legacy(
    image_prefix: str, class_no: int, language: str, legacy_loader: Any, work_root: Path,
) -> dict[str, Any]:
    """Migrate the previous medium-wide image into one class image, once and resumably."""
    image = class_image_reference(image_prefix, class_no, language)
    base_exists, index = pull_index(image)
    index.setdefault("schema_version", 1)
    index.setdefault("registry", image)
    index.setdefault("language", language)
    index.setdefault("class_no", class_no)
    index.setdefault("books", [])
    index.setdefault("download_status", {})
    if not isinstance(index.get("books"), list) or not isinstance(index.get("download_status"), dict):
        raise ValueError("Invalid target class cache index; refusing to replace " + image)

    before = len(index["books"])
    migration_already_complete = index.get("legacy_migration_complete") is True
    if migration_already_complete:
        report = {"image": image, "class": class_no, "medium": language,
                  "books_before_migration": before, "migrated_from_legacy": 0,
                  "books_after_migration": before, "migration_skipped": True}
        log("CLASS_IMAGE_SEED_REPORT " + json.dumps(report, ensure_ascii=False))
        return report

    legacy_index, legacy_root = legacy_loader(language)
    legacy_status = legacy_index.get("download_status", {})
    entries_by_id = {item.get("book_id"): item for item in index["books"] if isinstance(item, dict)}
    source_items = [item for item in legacy_index.get("books", [])
                    if isinstance(item, dict) and class_no in _entry_class_numbers(item)]
    additions: list[dict[str, Any]] = []
    files_by_id: dict[str, Path] = {}
    for old_item in source_items:
        book_id = _safe_registry_book_id(old_item.get("book_id"))
        if book_id in entries_by_id:
            continue
        source = legacy_root / "books" / (book_id + ".pdf")
        expected = str(old_item.get("sha256", "")).lower()
        if not source.is_file():
            raise FileNotFoundError(
                "The old registry index includes " + book_id + " but its PDF could not be extracted. "
                "Migration stopped without re-downloading or replacing that book."
            )
        if not re.fullmatch(r"[0-9a-f]{64}", expected) or sha256_file(source) != expected:
            raise ValueError("The old registry SHA-256 did not verify for " + book_id
                             + "; refusing migration or silent re-download.")
        if old_item.get("bytes") is not None and source.stat().st_size != int(old_item["bytes"]):
            raise ValueError("Old registry size mismatch for " + book_id + "; refusing migration.")
        entry = copy.deepcopy(old_item)
        entry["source_classes"] = old_item.get("source_classes") or old_item.get("classes") or [old_item.get("class")]
        entry["class"] = str(class_no)
        entry["classes"] = [str(class_no)]
        entry["registry_class"] = class_no
        entry["medium"] = language
        entry["file"] = "books/" + book_id + ".pdf"
        additions.append(entry)
        files_by_id[book_id] = source
        entries_by_id[book_id] = entry

    _index_add_books(index, additions)
    if isinstance(legacy_status, dict):
        target_status = index.setdefault("download_status", {})
        for old_item in source_items:
            book_id = str(old_item.get("book_id", ""))
            if book_id and book_id not in target_status and book_id in legacy_status:
                target_status[book_id] = copy.deepcopy(legacy_status[book_id])
    index.update({
        "schema_version": 1, "registry": image, "language": language, "class_no": class_no,
        "legacy_migration_complete": True, "legacy_migration_completed_at": now(), "updated_at": now(),
    })
    if additions or not base_exists or not migration_already_complete:
        push_batch(image, base_exists, additions, files_by_id, index,
                   work_root / ("seed-class-" + str(class_no) + "-" + language))
    report = {
        "image": image, "class": class_no, "medium": language, "books_before_migration": before,
        "legacy_books_for_class": len(source_items), "migrated_from_legacy": len(additions),
        "books_after_migration": len(index.get("books", [])), "migration_skipped": False,
    }
    log("CLASS_IMAGE_SEED_REPORT " + json.dumps(report, ensure_ascii=False))
    summary("- Migration checkpoint: " + image + "; carried forward " + str(len(additions))
            + " existing book(s); total image inventory " + str(len(index.get("books", []))) + ".")
    return report


def publish_classwise(
    language: str, image_prefix: str, class_no: int | None = None, book_code: str | None = None,
    refresh: bool = False, max_books: int = 0,
    download_workers: int = DEFAULT_DOWNLOAD_WORKERS,
    push_batch_size: int = DEFAULT_PUSH_BATCH_SIZE,
    retry_rounds: int = MAX_RETRY_ROUNDS,
) -> dict[str, Any]:
    """Publish to stable class/medium images and report the full coverage of each image."""
    if language not in {"hindi", "english", "both"}:
        raise ValueError("Language must be hindi, english, or both.")
    if class_no is not None and class_no not in range(6, 13):
        raise ValueError("Class filter must be from 6 through 12.")
    image_prefix = image_prefix.rstrip("-").lower()
    languages = ["hindi", "english"] if language == "both" else [language]
    classes = [class_no] if class_no is not None else list(range(6, 13))
    reports: list[dict[str, Any]] = []
    seed_reports: dict[str, dict[str, Any]] = {}
    legacy_cache: dict[str, tuple[dict[str, Any], Path]] = {}
    ncert_catalog: list[dict[str, Any]] = []
    scert_catalog: list[dict[str, Any]] = []
    scoped_book: dict[str, Any] | None = None
    scoped_source = ""

    with tempfile.TemporaryDirectory(prefix="qe-classwise-textbook-cache-") as temporary:
        work_root = Path(temporary)

        legacy_lock = threading.Lock()

        def legacy_loader(medium: str) -> tuple[dict[str, Any], Path]:
            with legacy_lock:
                if medium in legacy_cache:
                    return legacy_cache[medium]
                legacy_image = image_prefix + "-" + medium + ":latest"
                exists, legacy_index = pull_index(legacy_image)
                legacy_root = work_root / ("legacy-" + medium)
                (legacy_root / "books").mkdir(parents=True, exist_ok=True)
                if exists:
                    _extract_image_books(legacy_image, legacy_index, legacy_root)
                else:
                    log("LEGACY_IMAGE_ABSENT image=" + legacy_image)
                legacy_cache[medium] = (legacy_index, legacy_root)
                log("LEGACY_IMAGE_INVENTORY image=" + legacy_image
                    + " books=" + str(len(legacy_index.get("books", []))))
                return legacy_cache[medium]

        if book_code:
            if book_code.casefold().startswith("scert-bihar-"):
                scert_catalog = discover_scert_books()
                matches = [item for item in scert_catalog
                           if item.get("book_id", "").casefold() == book_code.casefold()]
                scoped_source = "SCERT Bihar"
            else:
                ncert_catalog = discover_ncert_books()
                matches = [item for item in ncert_catalog
                           if item.get("code", "").casefold() == book_code.casefold()
                           or item.get("book_id", "").casefold() == book_code.casefold()]
                scoped_source = "NCERT"
                if not matches:
                    scert_catalog = discover_scert_books()
                    matches = [item for item in scert_catalog
                               if item.get("book_id", "").casefold() == book_code.casefold()]
                    scoped_source = "SCERT Bihar"
            if not matches:
                raise ValueError("No official NCERT/SCERT book matched " + book_code)
            scoped_book = matches[0]
            languages = [str(scoped_book["medium"])]
            classes = [number for number in _entry_class_numbers(scoped_book)
                       if class_no is None or number == class_no]
            if not classes:
                raise ValueError("The requested book does not match the selected class.")
            if scoped_source == "NCERT":
                ncert_catalog, scert_catalog = [scoped_book], []
            else:
                scert_catalog, ncert_catalog = [scoped_book], []
            for grade in classes:
                image = class_image_reference(image_prefix, grade, languages[0])
                seed_reports[image] = _seed_class_image_from_legacy(
                    image_prefix, grade, languages[0], legacy_loader, work_root)
                reports.append(publish_language(
                    languages[0], image, book_code, refresh, max_books, False,
                    catalog=[scoped_book], download_workers=download_workers,
                    push_batch_size=push_batch_size, retry_rounds=retry_rounds, class_no=grade,
                ))
        else:
            # Seed each of the fourteen stable images from the legacy image indexes in
            # parallel. The legacy loader itself is locked so each old medium image is
            # downloaded/extracted no more than once per run.
            seed_tasks = [
                (medium, grade, class_image_reference(image_prefix, grade, medium))
                for medium in languages for grade in classes
            ]
            seed_workers = min(2, len(seed_tasks)) if seed_tasks else 1
            with ThreadPoolExecutor(max_workers=seed_workers) as executor:
                future_to_seed = {
                    executor.submit(
                        _seed_class_image_from_legacy,
                        image_prefix, grade, medium, legacy_loader, work_root,
                    ): (medium, grade, image)
                    for medium, grade, image in seed_tasks
                }
                for future in as_completed(future_to_seed):
                    medium, grade, image = future_to_seed[future]
                    try:
                        seed_reports[image] = future.result()
                    except Exception as exc:
                        message = str(exc) or exc.__class__.__name__
                        seed_reports[image] = {
                            "image": image, "class": grade, "medium": medium,
                            "migration_error": message, "migration_skipped": False,
                        }
                        log("CLASS_IMAGE_SEED_FAILED " + image + ": " + message)
                        summary("- MIGRATION FAILED: " + image + " — " + message.replace("\n", " "))

            def run_class_phase(catalog: list[dict[str, Any]], ncert_only: bool, phase: str) -> None:
                tasks = [
                    (medium, grade, class_image_reference(image_prefix, grade, medium))
                    for medium in languages for grade in classes
                    if not seed_reports.get(
                        class_image_reference(image_prefix, grade, medium), {}
                    ).get("migration_error")
                ]
                if not tasks:
                    return
                # Two class-image publishers × eight book downloaders prevents one slow
                # class/source from blocking every other image while limiting concurrent
                # temporary large-book files on a standard GitHub-hosted runner.
                with ThreadPoolExecutor(max_workers=min(2, len(tasks))) as executor:
                    future_to_task = {
                        executor.submit(
                            publish_language,
                            medium, image, None, refresh, max_books, ncert_only,
                            catalog=catalog, download_workers=download_workers,
                            push_batch_size=push_batch_size, retry_rounds=retry_rounds,
                            class_no=grade,
                        ): (medium, grade, image)
                        for medium, grade, image in tasks
                    }
                    for future in as_completed(future_to_task):
                        medium, grade, image = future_to_task[future]
                        try:
                            reports.append(future.result())
                        except Exception as exc:
                            message = str(exc) or exc.__class__.__name__
                            failure_report = {
                                "language": medium, "class_no": grade, "image": image,
                                "catalog_entries": 0, "books_present_before_run": 0,
                                "pending_at_start": 0, "pending_after_retries": 0,
                                "resolved_candidates": 0, "already_cached": [],
                                "downloaded_successfully": [], "downloaded_and_pushed": [],
                                "unchanged_after_refresh": [],
                                "failed_after_retries": [{
                                    "book_id": "__CLASS_PUBLISHER__", "title": "Class image publisher",
                                    "attempts_this_run": 0, "error": message,
                                }],
                                "push_failures": [{"book_id": "__CLASS_PUBLISHER__", "error": message}],
                                "books_in_registry": 0,
                                "retry_rounds": retry_rounds, "download_workers": download_workers,
                                "push_batch_size": push_batch_size, "updated_at": now(),
                            }
                            reports.append(failure_report)
                            log("CLASS_IMAGE_PUBLISH_FAILED phase=" + phase + " image=" + image + ": " + message)
                            summary("- PUBLISH FAILED: " + image + " — " + message.replace("\n", " "))

            # Bootstrap NCERT first, before the slower SCERT catalogue discovery.
            ncert_catalog = discover_ncert_books()
            run_class_phase(ncert_catalog, True, "NCERT")
            scert_catalog = discover_scert_books()
            run_class_phase(scert_catalog, False, "SCERT Bihar")

        reports_by_image: dict[str, list[dict[str, Any]]] = {}
        for report in reports:
            reports_by_image.setdefault(report["image"], []).append(report)
        complete_catalog = ncert_catalog + scert_catalog
        final_rows: list[dict[str, Any]] = []
        for medium in languages:
            for grade in classes:
                image = class_image_reference(image_prefix, grade, medium)
                phase_reports = reports_by_image.get(image, [])
                last_report = next(
                    (item for item in reversed(phase_reports) if "cached_book_ids" in item),
                    None,
                )
                latest_phase_report = phase_reports[-1] if phase_reports else None
                cached_ids = set(last_report.get("cached_book_ids", [])) if last_report else set()
                expected = [scoped_book] if scoped_book is not None else [
                    item for item in complete_catalog
                    if item.get("medium") == medium and grade in _entry_class_numbers(item)
                ]
                expected_ids = {str(item["book_id"]) for item in expected}
                missing_ids = sorted(expected_ids - cached_ids)
                downloaded_ids = sorted({
                    str(book_id) for report in phase_reports
                    for book_id in report.get("downloaded_and_pushed", [])
                })
                failed_ids = sorted({
                    str(item.get("book_id")) for report in phase_reports
                    for item in report.get("failed_after_retries", [])
                })
                seed = seed_reports.get(image, {})
                cached_count = last_report.get("books_in_registry", seed.get("books_after_migration", 0)) if last_report else seed.get("books_after_migration", 0)
                failed_count = sum(len(report.get("failed_after_retries", [])) for report in phase_reports)
                push_failure_count = sum(len(report.get("push_failures", [])) for report in phase_reports)
                row = {
                    "image": image, "class": grade, "medium": medium,
                    "catalog_books_expected": len(expected_ids),
                    "already_cached_before_download": len(latest_phase_report.get("already_cached", [])) if latest_phase_report else 0,
                    "migrated_from_legacy": int(seed.get("migrated_from_legacy", 0)),
                    "books_downloaded_this_run": len({
                        str(book_id) for report in phase_reports
                        for book_id in report.get("downloaded_successfully", [])
                    }),
                    "newly_downloaded_and_pushed": len(downloaded_ids),
                    "pushed_to_image_this_run": int(seed.get("migrated_from_legacy", 0)) + len(downloaded_ids),
                    "books_in_registry": cached_count,
                    "remaining_expected": len(missing_ids),
                    "missing_book_ids": missing_ids,
                    "failed_after_retries": failed_ids,
                    "push_failures": push_failure_count,
                    "migration_error": seed.get("migration_error"),
                    "status": "COMPLETE" if not missing_ids and not failed_count and not push_failure_count
                        and not seed.get("migration_error") else "INCOMPLETE",
                }
                final_rows.append(row)
        final_report = {"layout": "class-and-medium", "image_count": len(final_rows),
                        "images": final_rows, "updated_at": now()}
        log("CLASSWISE_GHCR_FINAL_REPORT " + json.dumps(final_report, ensure_ascii=False))
        summary("\n## Final per-GHCR coverage summary\n\n"
                + "| GHCR image | Catalogue books | Cached total | Migrated | Downloaded this run | Pushed this run | Remaining | Failed | Status |\n"
                + "|---|---:|---:|---:|---:|---:|---:|---:|---|\n"
                + "\n".join(
                    "| " + row["image"] + " | " + str(row["catalog_books_expected"])
                    + " | " + str(row["books_in_registry"]) + " | " + str(row["migrated_from_legacy"])
                    + " | " + str(row["books_downloaded_this_run"]) + " | " + str(row["pushed_to_image_this_run"])
                    + " | " + str(row["remaining_expected"])
                    + " | " + str(len(row["failed_after_retries"])) + " | " + row["status"] + " |"
                    for row in final_rows
                ) + "\n")
        summary("\nThe same class/medium image and its index are checked before each source download. "
                "A successful book ID is skipped on later runs; only missing books are eligible for download.")
        incomplete = any(row["status"] != "COMPLETE" for row in final_rows)
        source_failures = (
            any(report.get("failed_after_retries") or report.get("push_failures") for report in reports)
            or any(seed.get("migration_error") for seed in seed_reports.values())
        )
        final_report["exit_code"] = 1 if source_failures or (incomplete and not book_code and max_books == 0) else 0
        return final_report


def publish_language(language: str, image: str, book_code: str | None = None, refresh: bool = False,
                     max_books: int = 0, ncert_only: bool = False,
                     catalog: list[dict[str, Any]] | None = None,
                     download_workers: int = DEFAULT_DOWNLOAD_WORKERS,
                     push_batch_size: int = DEFAULT_PUSH_BATCH_SIZE,
                     retry_rounds: int = MAX_RETRY_ROUNDS,
                     class_no: int | None = None) -> dict[str, Any]:
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
    catalog = copy.deepcopy(catalog) if catalog is not None else discover_books()
    selected = [item for item in catalog if item["medium"] == language]
    if class_no is not None:
        if class_no not in range(6, 13):
            raise ValueError("Class filter must be from 6 through 12.")
        selected = [item for item in selected if class_no in _entry_class_numbers(item)]
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
    books_present_before_run = len(existing)
    if max_books > 0:
        candidates = candidates[:max_books]
    log(
        "Using persistent image " + image + "; books present before run=" + str(len(existing))
        + "; catalog entries=" + str(len(selected)) + "; pending at start=" + str(len(candidates))
        + "; concurrent downloads=" + str(download_workers)
        + "; books per Docker push=" + str(push_batch_size)
        + "; maximum rounds=" + str(retry_rounds)
    )

    downloaded: list[str] = []
    downloaded_successfully_ids: set[str] = set()
    unchanged: list[str] = []
    failed_downloads: dict[str, dict[str, Any]] = {}
    push_failures: dict[str, str] = {}
    attempt_in_this_run: dict[str, int] = {item["book_id"]: 0 for item in candidates}
    initial_attempt_totals: dict[str, int] = {
        item["book_id"]: int(index.get("download_status", {}).get(item["book_id"], {}).get("attempts_total", 0))
        for item in candidates
    }
    ready_for_push: dict[str, dict[str, Any]] = {}
    resolved_ids = set(already_cached)
    status_revision = 0
    persisted_status_revision = 0

    with tempfile.TemporaryDirectory(prefix="qe-textbook-batched-") as td:
        temp_root = Path(td)
        downloads_root = temp_root / "downloads"
        downloads_root.mkdir(parents=True, exist_ok=True)

        def persist_index_only() -> bool:
            nonlocal base_exists, persisted_status_revision
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
                persisted_status_revision = status_revision
                return True
            except Exception as exc:
                log("REGISTRY_STATUS_PUSH_FAILED " + image + ": " + str(exc))
                return False

        def push_ready(batch_ids: list[str]) -> bool:
            nonlocal base_exists, persisted_status_revision, status_revision
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
                persisted_status_revision = status_revision
                for book_id in batch_ids:
                    resolved_ids.add(book_id)
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
                remaining_downloads = sum(
                    1 for candidate in candidates
                    if candidate["book_id"] not in resolved_ids
                    and candidate["book_id"] not in ready_for_push
                    and attempt_in_this_run.get(candidate["book_id"], 0) < retry_rounds
                )
                log("CACHE_PROGRESS image=" + image + " phase=batch-pushed"
                    + " catalog_expected=" + str(len(selected))
                    + " cached_now=" + str(len(existing))
                    + " pushed_this_run=" + str(len(downloaded))
                    + " pending_downloads=" + str(remaining_downloads)
                    + " waiting_for_push=" + str(len(ready_for_push)))
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
                    status_revision += 1
                log("BATCH_PUSH_FAILED " + image + " books=" + ",".join(batch_ids) + ": " + str(exc))
                persist_index_only()
                return False

        def download_batch(batch: list[dict[str, Any]], round_no: int) -> None:
            nonlocal status_revision
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
                        downloaded_successfully_ids.add(book_id)
                        previous = existing.get(book_id)
                        if previous and previous.get("sha256") == digest and refresh:
                            unchanged.append(book_id)
                            resolved_ids.add(book_id)
                            failed_downloads.pop(book_id, None)
                            index.setdefault("download_status", {})[book_id] = {
                                "status": "cached",
                                "sha256": digest,
                                "bytes": pdf_path.stat().st_size,
                                "attempts_this_run": attempt_in_this_run.get(book_id, 0),
                                "attempts_total": initial_attempt_totals.get(book_id, 0) + attempt_in_this_run.get(book_id, 0),
                                "last_success_at": now(),
                            }
                            status_revision += 1
                            pdf_path.unlink(missing_ok=True)
                            continue
                        ready_for_push[book_id] = {
                            "book": book,
                            "path": pdf_path,
                            "entry": _class_scoped_book_entry(
                                book, digest, pages, toc, method, pdf_path, class_no),
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
                    status_revision += 1

            successful_ids = [book_id for book_id in ready_for_push if book_id in {b["book_id"] for b in batch}]
            for offset in range(0, len(successful_ids), push_batch_size):
                push_ready(successful_ids[offset:offset + push_batch_size])
        for round_no in range(1, retry_rounds + 1):
            log("RETRY_ROUND " + str(round_no) + "/" + str(retry_rounds) + " language=" + language)
            pending_push_ids = list(ready_for_push)
            for offset in range(0, len(pending_push_ids), push_batch_size):
                push_ready(pending_push_ids[offset:offset + push_batch_size])

            pending = [
                item for item in candidates
                if item["book_id"] not in resolved_ids
                and item["book_id"] not in ready_for_push
                and attempt_in_this_run.get(item["book_id"], 0) < retry_rounds
            ]
            terminal_failures = sum(
                1 for row in failed_downloads.values()
                if attempt_in_this_run.get(row["book_id"], 0) >= retry_rounds
            )
            log("CACHE_PROGRESS image=" + image + " round=" + str(round_no) + "/" + str(retry_rounds)
                + " resolved=" + str(len(resolved_ids))
                + " pushed_this_run=" + str(len(downloaded))
                + " pending_downloads=" + str(len(pending))
                + " waiting_for_push=" + str(len(ready_for_push))
                + " terminal_failures=" + str(terminal_failures))
            if not pending:
                if not ready_for_push:
                    break
                continue
            chunks = [pending[offset:offset + push_batch_size] for offset in range(0, len(pending), push_batch_size)]
            for batch in chunks:
                download_batch(batch, round_no)
            if status_revision > persisted_status_revision:
                persist_index_only()

        while ready_for_push:
            batch_ids = list(ready_for_push)[:push_batch_size]
            if not push_ready(batch_ids):
                break
        if status_revision > persisted_status_revision:
            persist_index_only()

    for book in candidates:
        book_id = book["book_id"]
        status = index.get("download_status", {}).get(book_id, {})
        if book_id not in resolved_ids and status.get("status") in {"failed", "retry_pending", "downloaded_pending_push"}:
            failed_downloads.setdefault(book_id, {
                "book_id": book_id, "title": book["title"],
                "attempts_this_run": attempt_in_this_run.get(book_id, 0),
                "error": status.get("last_error", "Not present in the GHCR cache after retries."),
            })

    report = {
        "language": language,
        "class_no": class_no,
        "image": image,
        "persistent_tag": "latest",
        "catalog_entries": len(selected),
        "books_present_before_run": books_present_before_run,
        "pending_at_start": len(candidates),
        "pending_after_retries": sum(1 for item in candidates if item["book_id"] not in resolved_ids),
        "resolved_candidates": sum(1 for item in candidates if item["book_id"] in resolved_ids),
        "already_cached": already_cached,
        "downloaded_successfully": sorted(downloaded_successfully_ids),
        "downloaded_and_pushed": downloaded,
        "unchanged_after_refresh": unchanged,
        "failed_after_retries": list(failed_downloads.values()),
        "push_failures": [{"book_id": key, "error": value} for key, value in push_failures.items()],
        "books_in_registry": len(index.get("books", [])),
        "cached_book_ids": sorted(str(book_id) for book_id in existing.keys()),
        "retry_rounds": retry_rounds,
        "download_workers": download_workers,
        "push_batch_size": push_batch_size,
        "updated_at": now(),
    }
    log("BOOK_CACHE_REPORT " + json.dumps(report, ensure_ascii=False))
    scope_label = language.title() + ((" · Class " + str(class_no)) if class_no is not None else "")
    summary("\n## " + scope_label + " textbook-cache result — " + image + "\n\n"
            + "- Persistent image: " + image + "\n"
            + "- Books already cached: " + str(len(already_cached)) + "\n"
            + "- Books newly downloaded and pushed: " + str(len(downloaded)) + "\n"
            + "- Unchanged on refresh: " + str(len(unchanged)) + "\n"
            + "- Pending at start: " + str(len(candidates)) + "\n"
            + "- Pending after retries: " + str(report["pending_after_retries"]) + "\n"
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
    publish.add_argument("--class-wise", action="store_true",
                         help="Publish into separate class/medium GHCR images.")
    publish.add_argument("--class-no", type=int, choices=range(6, 13),
                         help="Limit class-wise publishing to one grade; default is Classes 6–12.")
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
        if args.command == "publish" and args.class_wise:
            final_report = publish_classwise(
                args.language, args.image_prefix, args.class_no, args.book_code,
                args.refresh, args.max_books, args.download_workers,
                args.push_batch_size, args.retry_rounds,
            )
            print(json.dumps(final_report, ensure_ascii=False, indent=2))
            return int(final_report.get("exit_code", 1))
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
            return 1 if any(report["failed_after_retries"] or report["push_failures"] for report in reports) else 0
        pull_image(args.image, args.output_dir)
        return 0
    except (ValueError, RuntimeError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile) as exc:
        print("ERROR: " + str(exc), file=sys.stderr, flush=True)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
