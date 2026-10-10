#!/usr/bin/env python3
"""Register existing SCERT source PDFs in QuantaEdge's private PDF library.

Extraction JSON contains metadata and extracted page text, not the original PDF
bytes. This tool matches each bundle's SHA-256 to a source PDF, then uploads
that exact PDF to the authenticated API library. It is dry-run by default.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


MAX_BYTES = 50 * 1024 * 1024
MAX_PAGES = 2000


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_bundle(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict) or not isinstance(value.get("source"), dict):
        raise ValueError(f"{path}: not an extraction bundle with source metadata.")
    source = value["source"]
    filename = source.get("pdf_filename")
    digest = source.get("pdf_sha256")
    title = source.get("title")
    pages = source.get("pdf_page_count")
    if not isinstance(filename, str) or not filename.strip():
        raise ValueError(f"{path}: source.pdf_filename is missing.")
    if not isinstance(digest, str) or len(digest) != 64 or any(ch not in "0123456789abcdefABCDEF" for ch in digest):
        raise ValueError(f"{path}: source.pdf_sha256 is invalid.")
    if not isinstance(title, str) or not title.strip():
        raise ValueError(f"{path}: source.title is missing.")
    if not isinstance(pages, int) or not 1 <= pages <= MAX_PAGES:
        raise ValueError(f"{path}: source.pdf_page_count must be between 1 and {MAX_PAGES}.")
    availability = value.get("content_availability")
    if not isinstance(availability, dict):
        availability = source.get("content_availability")
    if not isinstance(availability, dict):
        availability = {"status": value.get("asset_content_status", "complete")}
    status = str(availability.get("status", value.get("asset_content_status", "complete"))).lower()
    if status not in {"complete", "partial", "unavailable"}:
        status = "complete"
    missing_chapters = availability.get("missing_chapters", [])
    available_chapters = availability.get("available_chapters", [])
    return {
        "bundle": path,
        "filename": Path(filename).name,
        "sha256": digest.lower(),
        "title": title.strip(),
        "page_count": pages,
        "content_availability": availability,
        "asset_content_status": status,
        "source_url": str(source.get("source_url") or ""),
        "missing_chapters": missing_chapters if isinstance(missing_chapters, list) else [],
        "available_chapters": available_chapters if isinstance(available_chapters, list) else [],
    }


def source_reference(item: dict[str, Any]) -> str:
    """Make partial chapter coverage searchable and visible in the private PDF library."""
    coverage = item.get("content_availability") or {}
    status = str(item.get("asset_content_status") or coverage.get("status") or "complete").upper()
    parts = []
    if status != "COMPLETE":
        parts.append("content_status=" + status)
        missing = item.get("missing_chapters") or coverage.get("missing_chapters") or []
        available = item.get("available_chapters") or coverage.get("available_chapters") or []
        if missing:
            parts.append("missing_chapters=" + ",".join(str(n) for n in missing))
        if available:
            parts.append("available_chapters=" + ",".join(str(n) for n in available))
    parts.extend(["extraction-bundle=" + item["bundle"].name, "sha256=" + item["sha256"]])
    return ";".join(parts)[:500]


def find_matching_pdf(pdf_dir: Path, filename: str, expected_sha: str) -> Path | None:
    for path in pdf_dir.rglob("*"):
        if not path.is_file() or path.name.casefold() != filename.casefold():
            continue
        if path.stat().st_size > MAX_BYTES:
            continue
        if sha256_file(path).lower() == expected_sha:
            return path
    return None


def multipart_body(fields: dict[str, str], filename: str, pdf_bytes: bytes) -> tuple[str, bytes]:
    boundary = "----QuantaEdgePDFImportBoundary7MA4YWxkTrZu0gW"
    parts: list[bytes] = []
    for key, value in fields.items():
        parts.append(
            (f"--{boundary}\r\n"
             f'Content-Disposition: form-data; name="{key}"\r\n'
             "Content-Type: text/plain; charset=utf-8\r\n\r\n"
             f"{value}\r\n").encode("utf-8")
        )
    safe_filename = filename.replace('"', "_").replace("\\", "_").replace("/", "_")
    parts.append(
        (f"--{boundary}\r\n"
         'Content-Disposition: form-data; name="file"; filename="' + safe_filename + '"\r\n'
         "Content-Type: application/pdf\r\n\r\n").encode("utf-8")
        + pdf_bytes + b"\r\n"
    )
    parts.append(f"--{boundary}--\r\n".encode("ascii"))
    return boundary, b"".join(parts)


def upload_pdf(api_base_url: str, cookie: str, path: Path, item: dict[str, Any]) -> dict[str, Any]:
    base = api_base_url.rstrip("/")
    parsed = urllib.parse.urlparse(base)
    if parsed.scheme != "https" and parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise ValueError("Use HTTPS for non-local API URLs.")
    if not cookie.strip():
        raise ValueError("Set QUANTAEDGE_ADMIN_SESSION to an authenticated QE_SESSION cookie value.")
    pdf_bytes = path.read_bytes()
    if len(pdf_bytes) > MAX_BYTES:
        raise ValueError(f"{path}: PDF exceeds the 50 MB upload limit.")
    if len(pdf_bytes) < 5 or not pdf_bytes.startswith(b"%PDF-"):
        raise ValueError(f"{path}: file does not have a PDF signature.")
    fields = {
        "title": item["title"],
        "sourceKind": "EXTRACTION_IMPORT",
        "sourceReference": source_reference(item),
    }
    boundary, body = multipart_body(fields, path.name, pdf_bytes)
    request = urllib.request.Request(
        base + "/api/v1/admin/learning-pdfs",
        data=body,
        method="POST",
        headers={
            "Cookie": "QE_SESSION=" + cookie.strip(),
            "Content-Type": "multipart/form-data; boundary=" + boundary,
            "Accept": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            result = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")[:1200]
        raise RuntimeError(f"API upload failed for {path.name} (HTTP {exc.code}): {detail}") from exc
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle-dir", required=True, type=Path, help="Folder containing SCERT extraction JSON bundles")
    parser.add_argument("--pdf-dir", required=True, type=Path, help="Folder containing the unchanged original source PDFs")
    parser.add_argument("--api-base-url", default=os.environ.get("QUANTAEDGE_API_BASE_URL", "http://localhost:8080"))
    parser.add_argument("--apply", action="store_true", help="Actually upload matching PDFs to the private database library")
    args = parser.parse_args()

    if not args.bundle_dir.is_dir():
        parser.error(f"Bundle directory does not exist: {args.bundle_dir}")
    if not args.pdf_dir.is_dir():
        parser.error(f"PDF directory does not exist: {args.pdf_dir}")
    bundles = sorted(args.bundle_dir.rglob("*.json"))
    if not bundles:
        parser.error(f"No extraction JSON bundles found under {args.bundle_dir}")

    seen_hashes: set[str] = set()
    found = uploaded = duplicates = missing = errors = 0
    for bundle_path in bundles:
        try:
            item = load_bundle(bundle_path)
            if item["sha256"] in seen_hashes:
                print(f"SKIP duplicate bundle hash {item['sha256']}: {bundle_path}")
                continue
            seen_hashes.add(item["sha256"])
            pdf_path = find_matching_pdf(args.pdf_dir, item["filename"], item["sha256"])
            if pdf_path is None:
                print(f"MISSING original PDF for {bundle_path.name}: expected {item['filename']} with SHA-256 {item['sha256']}")
                missing += 1
                continue
            if pdf_path.stat().st_size > MAX_BYTES:
                print(f"SKIP oversized PDF: {pdf_path}")
                errors += 1
                continue
            found += 1
            if not args.apply:
                marker = ""
                if item["asset_content_status"] == "partial":
                    marker = " PARTIAL: missing chapter(s) " + ",".join(map(str, item["missing_chapters"]))
                print(f"DRY RUN{marker} {pdf_path} -> library title={item['title']!r}, pages={item['page_count']}, sha256={item['sha256']}")
                continue
            cookie = os.environ.get("QUANTAEDGE_ADMIN_SESSION", "")
            result = upload_pdf(args.api_base_url, cookie, pdf_path, item)
            if result.get("duplicate"):
                duplicates += 1
                print(f"EXISTS {item['title']}: library item {result.get('pdf_asset_id')}")
            else:
                uploaded += 1
                marker = ""
                if item["asset_content_status"] == "partial":
                    marker = " PARTIAL: missing chapter(s) " + ",".join(map(str, item["missing_chapters"]))
                print(f"IMPORTED{marker} {item['title']}: library item {result.get('pdf_asset_id')}, pages={result.get('page_count')}")
        except (OSError, ValueError, json.JSONDecodeError, RuntimeError) as exc:
            errors += 1
            print(f"ERROR {bundle_path}: {exc}", file=sys.stderr)

    mode = "apply" if args.apply else "dry-run"
    print(json.dumps({
        "mode": mode, "bundles": len(bundles), "matching_pdfs": found,
        "uploaded": uploaded, "already_in_library": duplicates,
        "missing_originals": missing, "errors": errors,
    }, indent=2))
    if missing or errors:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
