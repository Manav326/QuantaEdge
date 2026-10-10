#!/usr/bin/env python3
"""Incremental per-language GHCR cache for official NCERT and SCERT Bihar textbooks.

One book is downloaded, checksummed, appended as an OCI image layer and pushed before
the next book starts. Successful uploads survive failures/timeouts and are skipped on rerun.
"""
from __future__ import annotations

import argparse, hashlib, html, json, os, re, shutil, subprocess, sys, tempfile
import urllib.error, urllib.parse, urllib.request, zipfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

NCERT = "https://ncert.nic.in"
NCERT_CATALOG = NCERT + "/textbook.php?ln=en"
SCERT_CATALOG = "https://scert.bihar.gov.in/eresources?per_page=100"
OFFICIAL_HOSTS = {"ncert.nic.in", "scert.bihar.gov.in", "bstbpc.gov.in"}
LANGUAGES = {"hindi": "h", "english": "e"}
ROMAN_CLASSES = {"vi": 6, "vii": 7, "viii": 8, "ix": 9, "x": 10, "xi": 11, "xii": 12}
MAX_BOOK_BYTES = 350 * 1024 * 1024
MAX_ZIP_BYTES = 500 * 1024 * 1024
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


def get_url(url: str, destination: Path | None = None, limit: int = MAX_BOOK_BYTES) -> bytes | Path:
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
                    if total > limit:
                        raise ValueError("Response exceeds size limit.")
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
                    if total > limit:
                        raise ValueError("Download exceeds size limit.")
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


def fetch_text(url: str) -> str:
    data = get_url(url, None, 8 * 1024 * 1024)
    assert isinstance(data, bytes)
    return data.decode("utf-8", errors="replace")


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


def discover_books() -> list[dict[str, Any]]:
    log("Discovering current official NCERT catalogue...")
    books = parse_ncert_catalog(fetch_text(NCERT_CATALOG))
    log("NCERT entries: " + str(len(books)))
    log("Discovering SCERT Bihar E-resources...")
    urls = discover_scert_urls()
    log("SCERT detail pages found: " + str(len(urls)))
    for index, url in enumerate(urls, start=1):
        try:
            books.extend(parse_scert_detail(url, fetch_text(url)))
        except Exception as exc:
            log("SCERT detail warning " + str(index) + ": " + url + " (" + str(exc) + ")")
    unique = {item["book_id"]: item for item in books if item["medium"] in LANGUAGES}
    return sorted(unique.values(), key=lambda item: (
        item["medium"], min(item.get("classes") or [item["class"]]),
        0 if item["source_type"] == "SCERT_BIHAR" else 1,
        str(item["title"]).casefold(), item["book_id"],
    ))


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(CHUNK), b""):
            digest.update(block)
    return digest.hexdigest()


def download_ncert_merged(book: dict[str, Any], destination: Path) -> tuple[str, int, list[list[Any]], str]:
    try:
        import fitz
    except ImportError as exc:
        raise RuntimeError("Missing PyMuPDF: install with python -m pip install pymupdf.") from exc
    with tempfile.TemporaryDirectory(prefix="qe-textbook-") as td:
        work = Path(td)
        bundle = work / (book["code"] + "dd.zip")
        members: list[tuple[int, str]] = []
        try:
            get_url(book["bundle_url"], bundle, MAX_BOOK_BYTES)
            with zipfile.ZipFile(bundle) as archive:
                if sum(entry.file_size for entry in archive.infolist() if not entry.is_dir()) > MAX_ZIP_BYTES:
                    raise ValueError("NCERT bundle expands beyond 500 MB.")
                for entry in archive.infolist():
                    name = Path(entry.filename).name
                    chapter = re.match(r"^" + re.escape(book["code"]) + r"(\d{2})\.+pdf$", name, re.I)
                    prelims = re.match(r"^" + re.escape(book["code"]) + r"ps\.+pdf$", name, re.I)
                    if chapter:
                        members.append((int(chapter.group(1)), entry.filename))
                    elif prelims:
                        members.append((0, entry.filename))
            members.sort(key=lambda pair: (pair[0], pair[1]))
        except FileNotFoundError:
            members = []
        except zipfile.BadZipFile:
            members = []
        destination.parent.mkdir(parents=True, exist_ok=True)
        output = fitz.open()
        toc: list[list[Any]] = []
        try:
            if members:
                with zipfile.ZipFile(bundle) as archive:
                    for chapter_no, member_name in members:
                        content = archive.read(member_name)
                        if not content.startswith(b"%PDF-"):
                            continue
                        source = fitz.open(stream=content, filetype="pdf")
                        start_page = len(output) + 1
                        output.insert_pdf(source)
                        if chapter_no:
                            toc.append([1, "Chapter " + str(chapter_no), start_page])
                        source.close()
            else:
                for chapter_no in range(1, int(book["chapter_count"]) + 1):
                    found_path = None
                    for dots in (1, 2):
                        candidate = work / (book["code"] + f"{chapter_no:02d}" + ".pdf")
                        url = NCERT + "/textbook/pdf/" + book["code"] + f"{chapter_no:02d}" + ("." * dots) + "pdf"
                        try:
                            get_url(url, candidate, 50 * 1024 * 1024)
                            found_path = candidate
                            break
                        except (FileNotFoundError, RuntimeError, ValueError) as exc:
                            log("Chapter fallback warning " + book["code"] + str(chapter_no) + ": " + str(exc))
                    if found_path:
                        source = fitz.open(found_path)
                        start_page = len(output) + 1
                        output.insert_pdf(source)
                        toc.append([1, "Chapter " + str(chapter_no), start_page])
                        source.close()
            if not len(output):
                raise ValueError("No readable chapter PDFs found for " + book["code"])
            output.set_toc(toc)
            output.set_metadata({"title": book["title"], "author": "NCERT", "subject": str(book.get("subject", ""))})
            output.save(destination, garbage=4, deflate=True)
            pages = len(output)
        finally:
            output.close()
    return sha256_file(destination), pages, toc, "Merged official chapter PDFs; chapter bookmarks added"


def download_scert_pdf(book: dict[str, Any], destination: Path) -> tuple[str, int, list[list[Any]], str]:
    try:
        import fitz
    except ImportError as exc:
        raise RuntimeError("Missing PyMuPDF: install with python -m pip install pymupdf.") from exc
    get_url(book["pdf_url"], destination, MAX_BOOK_BYTES)
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
    try:
        subprocess.run(["docker", "pull", image], check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    except subprocess.CalledProcessError as exc:
        message = exc.stdout or ""
        log("Could not pull previous image; attempting initial image. " + message[-400:])
        return False, {"schema_version": 1, "registry": image, "books": []}
    container = "qe-index-" + hashlib.sha1((image + now()).encode()).hexdigest()[:10]
    subprocess.run(["docker", "create", "--name", container, image], check=True, stdout=subprocess.DEVNULL)
    try:
        with tempfile.TemporaryDirectory(prefix="qe-index-") as td:
            path = Path(td) / "index.json"
            subprocess.run(["docker", "cp", container + ":/index.json", str(path)], check=True, stdout=subprocess.DEVNULL)
            data = json.loads(path.read_text(encoding="utf-8"))
            if not isinstance(data.get("books"), list):
                raise ValueError("Existing GHCR image has invalid index.json.")
            return True, data
    finally:
        subprocess.run(["docker", "rm", container], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def summary(line: str) -> None:
    target = os.environ.get("GITHUB_STEP_SUMMARY")
    if target:
        with open(target, "a", encoding="utf-8") as handle:
            handle.write(line.rstrip() + "\n")


def push_one(image: str, base_exists: bool, book: dict[str, Any], pdf: Path,
             index: dict[str, Any], context: Path) -> None:
    if context.exists():
        shutil.rmtree(context)
    (context / "books").mkdir(parents=True)
    filename = book["book_id"] + ".pdf"
    shutil.copyfile(pdf, context / "books" / filename)
    (context / "index.json").write_text(json.dumps(index, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    if base_exists:
        dockerfile = "FROM " + image + "\nLABEL org.opencontainers.image.title=\"QuantaEdge textbook cache\"\nCOPY index.json /index.json\nCOPY books/" + filename + " /books/" + filename + "\n"
    else:
        dockerfile = "FROM scratch\nLABEL org.opencontainers.image.title=\"QuantaEdge textbook cache\"\nLABEL org.opencontainers.image.description=\"Official textbook cache; source and checksums are in /index.json\"\nCOPY index.json /index.json\nCOPY books/" + filename + " /books/" + filename + "\n"
    (context / "Dockerfile").write_text(dockerfile, encoding="utf-8")
    next_tag = image.rsplit(":", 1)[0] + ":qe-next"
    subprocess.run(["docker", "build", "--pull=false", "-t", next_tag, str(context)], check=True)
    subprocess.run(["docker", "tag", next_tag, image], check=True)
    subprocess.run(["docker", "push", image], check=True)
    subprocess.run(["docker", "image", "rm", next_tag], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def publish_language(language: str, image: str, book_code: str | None = None, refresh: bool = False,
                     max_books: int = 0, ncert_only: bool = False,
                     catalog: list[dict[str, Any]] | None = None) -> dict[str, Any]:
    base_exists, index = pull_index(image)
    index.setdefault("schema_version", 1)
    index.setdefault("registry", image)
    index.setdefault("books", [])
    existing = {item.get("book_id"): item for item in index["books"] if isinstance(item, dict)}
    catalog = catalog if catalog is not None else discover_books()
    selected = [item for item in catalog if item["medium"] == language]
    if ncert_only:
        selected = [item for item in selected if item["source_type"] == "NCERT"]
    if book_code:
        selected = [item for item in selected if item.get("code", "").casefold() == book_code.casefold()
                    or item["book_id"].casefold() == book_code.casefold()]
        if not selected:
            raise ValueError("No " + language + " book matched " + book_code)
    log("Publishing " + language + ": " + str(len(selected)) + " official catalogue entries.")
    uploaded, reused, failed = [], [], []
    processed = 0
    with tempfile.TemporaryDirectory(prefix="qe-textbook-one-book-") as td:
        root = Path(td)
        for book in selected:
            book_id = book["book_id"]
            previous = existing.get(book_id)
            if previous and not refresh:
                reused.append(book_id)
                continue
            if max_books and processed >= max_books:
                break
            processed += 1
            log("BOOK_START " + str(processed) + "/" + str(len(selected)) + " " + book_id + " — " + book["title"])
            path = root / (book_id + ".pdf")
            try:
                if book["source_type"] == "NCERT":
                    digest, pages, toc, method = download_ncert_merged(book, path)
                else:
                    digest, pages, toc, method = download_scert_pdf(book, path)
                if path.stat().st_size > MAX_BOOK_BYTES:
                    raise ValueError("Prepared book exceeds allowed size.")
                if previous and previous.get("sha256") == digest:
                    reused.append(book_id)
                    log("UNCHANGED " + book_id + " sha256=" + digest)
                    continue
                rights_basis = (
                    "NCERT licence asserted by repository owner; confidential evidence is not committed"
                    if book["source_type"] == "NCERT"
                    else "Official SCERT Bihar E-resources; source URL retained for provenance"
                )
                entry = {
                    **book, "class": str(book["class"]),
                    "classes": [str(value) for value in book.get("classes", [book["class"]])],
                    "sha256": digest, "bytes": path.stat().st_size, "page_count": pages, "toc": toc,
                    "file": "books/" + book_id + ".pdf", "download_method": method,
                    "rights_basis": rights_basis, "cached_at": now(),
                }
                index["books"] = [item for item in index["books"] if item.get("book_id") != book_id]
                index["books"].append(entry)
                index["books"].sort(key=lambda item: (
                    min([int(n) for n in item.get("classes", [item.get("class", 0)])]),
                    item.get("publisher", ""), item.get("title", "").casefold(), item.get("book_id", "")
                ))
                index.update({"schema_version": 1, "registry": image, "language": language, "updated_at": now()})
                push_one(image, base_exists, book, path, index, root / "oci-context")
                base_exists = True
                existing[book_id] = entry
                uploaded.append(book_id)
                summary("| " + language.title() + " | " + str(book["class"]) + " | " + book["publisher"] + " | "
                        + book["title"].replace("|", "\\|") + " | " + book_id + " | " + digest[:12] + " |")
                log("PUSHED " + book_id + " image=" + image + " sha256=" + digest + " bytes=" + str(path.stat().st_size))
            except Exception as exc:
                item = {"book_id": book_id, "title": str(book["title"]), "error": str(exc)}
                failed.append(item)
                log("BOOK_FAILED " + json.dumps(item, ensure_ascii=False))
    report = {
        "language": language, "image": image, "catalog_entries": len(selected),
        "downloaded_and_pushed": uploaded, "reused_or_unchanged": reused,
        "failed": failed, "books_in_registry": len(index["books"]), "updated_at": now(),
    }
    log("BOOK_CACHE_REPORT " + json.dumps(report, ensure_ascii=False))
    summary("\n## " + language.title() + " textbook-cache result\n\n"
            + "- Registry image: " + image + "\n"
            + "- Books newly downloaded and pushed: " + str(len(uploaded)) + "\n"
            + "- Books reused or unchanged: " + str(len(reused)) + "\n"
            + "- Failed book downloads: " + str(len(failed)) + "\n")
    if failed:
        summary("\n### Failed books\n\n" + "\n".join(
            "- " + item["book_id"] + ": " + item["error"].replace("\n", " ") for item in failed
        ) + "\n")
    return report


def pull_image(image: str, output_dir: Path) -> dict[str, Any]:
    subprocess.run(["docker", "pull", image], check=True)
    output_dir.mkdir(parents=True, exist_ok=True)
    container = "qe-textbook-pull-" + hashlib.sha1((image + now()).encode()).hexdigest()[:10]
    subprocess.run(["docker", "create", "--name", container, image], check=True, stdout=subprocess.DEVNULL)
    try:
        books_dir = output_dir / "books"
        books_dir.mkdir(parents=True, exist_ok=True)
        subprocess.run(["docker", "cp", container + ":/books/.", str(books_dir)], check=True)
        subprocess.run(["docker", "cp", container + ":/index.json", str(output_dir / "index.json")], check=True)
    finally:
        subprocess.run(["docker", "rm", container], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    index = json.loads((output_dir / "index.json").read_text(encoding="utf-8"))
    bad = [item.get("book_id", "unknown") for item in index.get("books", [])
           if not (output_dir / item["file"]).is_file() or sha256_file(output_dir / item["file"]) != item.get("sha256")]
    if bad:
        raise ValueError("Checksum verification failed for: " + ", ".join(bad))
    report = {"image": image, "output_dir": str(output_dir), "book_count": len(index.get("books", [])), "checksum_failures": bad}
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
    pull = sub.add_parser("pull", help="Pull one language image locally and verify all checksums")
    pull.add_argument("--image", required=True)
    pull.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == "discover":
            entries = discover_books()
            if args.language != "both":
                entries = [item for item in entries if item["medium"] == args.language]
            print(json.dumps(entries, ensure_ascii=False, indent=2))
            return 0
        if args.command == "publish":
            languages = ["hindi", "english"] if args.language == "both" else [args.language]
            reports = []
            prefix = args.image_prefix.lower()
            catalog = discover_books()
            for language in languages:
                reports.append(publish_language(
                    language, prefix + "-" + language + ":latest", args.book_code,
                    args.refresh, args.max_books, args.ncert_only, catalog=catalog,
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
