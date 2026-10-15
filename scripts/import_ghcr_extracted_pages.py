#!/usr/bin/env python3
"""Import page-text manifests produced by the GHCR extraction pilot into QuantaEdge.

Safe by default: --dry-run validates all pairs without making API changes. Use --apply
to import as draft-only source records. --approve is a separate explicit review action
and can only be used together with --apply.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

MAX_MANIFEST_BYTES = 2 * 1024 * 1024
MAX_PAGES_BYTES = 50 * 1024 * 1024


def multipart_body(parts: list[tuple[str, str, bytes]]) -> tuple[str, bytes]:
    boundary = "----QuantaEdgeExtractedPagesBoundary6f45d1e7a9"
    chunks: list[bytes] = []
    for field, filename, content in parts:
        safe_name = filename.replace('"', "_").replace("\\", "_").replace("/", "_")
        chunks.append(
            (f"--{boundary}\r\n"
             f'Content-Disposition: form-data; name="{field}"; filename="{safe_name}"\r\n'
             "Content-Type: application/octet-stream\r\n\r\n").encode("utf-8")
            + content + b"\r\n"
        )
    chunks.append(f"--{boundary}--\r\n".encode("ascii"))
    return boundary, b"".join(chunks)


def validate_pair(manifest_path: Path) -> tuple[dict[str, Any], Path]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if not isinstance(manifest, dict) or manifest.get("scope") != "SUBJECT_BOOK":
        raise ValueError(f"{manifest_path}: expected a complete-book manifest.")
    if manifest.get("content_state") != "DRAFT_EXTRACTION_ONLY_REQUIRES_REVIEW":
        raise ValueError(f"{manifest_path}: extraction manifest is not draft/review gated.")
    pages_name = manifest.get("text_jsonl")
    if not isinstance(pages_name, str) or Path(pages_name).name != pages_name or not pages_name.endswith(".jsonl"):
        raise ValueError(f"{manifest_path}: unsafe or missing text_jsonl filename.")
    pages_path = manifest_path.parent / pages_name
    if not pages_path.is_file():
        raise FileNotFoundError(f"{manifest_path}: page JSONL is missing: {pages_path}")
    if manifest_path.stat().st_size > MAX_MANIFEST_BYTES or pages_path.stat().st_size > MAX_PAGES_BYTES:
        raise ValueError(f"{manifest_path}: manifest or page JSONL exceeds the import limit.")
    source_hash = str(manifest.get("original_book_sha256") or manifest.get("source_sha256") or "").lower()
    asset_hash = str(manifest.get("asset_pdf_sha256") or "").lower()
    if (not manifest.get("book_id") or not manifest.get("source_url")
            or len(source_hash) != 64 or len(asset_hash) != 64
            or any(ch not in "0123456789abcdef" for ch in source_hash + asset_hash)
            or source_hash != asset_hash):
        raise ValueError(f"{manifest_path}: book ID, official source URL and matching source/PDF hashes are required.")
    return manifest, pages_path


def post_multipart(url: str, cookie: str, parts: list[tuple[str, str, bytes]]) -> dict[str, Any]:
    boundary, body = multipart_body(parts)
    request = urllib.request.Request(
        url, data=body, method="POST",
        headers={
            "Cookie": "QE_STAFF_SESSION=" + cookie,
            "X-QE-Workspace": "EDUCATION_ADMIN",
            "Content-Type": "multipart/form-data; boundary=" + boundary,
            "Accept": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            raw = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")[:2000]
        raise RuntimeError(f"API returned HTTP {exc.code}: {detail}") from exc
    try:
        value = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise RuntimeError("API returned a non-JSON response; check the API URL and session.") from exc
    if not isinstance(value, dict):
        raise RuntimeError("API returned an unexpected response shape.")
    return value


def post_approve(url: str, cookie: str) -> dict[str, Any]:
    request = urllib.request.Request(
        url, data=b"", method="POST",
        headers={"Cookie": "QE_STAFF_SESSION=" + cookie,
            "X-QE-Workspace": "EDUCATION_ADMIN", "Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            value = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")[:2000]
        raise RuntimeError(f"Approval API returned HTTP {exc.code}: {detail}") from exc
    if not isinstance(value, dict):
        raise RuntimeError("Approval API returned an unexpected response shape.")
    return value


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--extracted-root", required=True, type=Path,
                        help="Root directory containing class-N/medium/pages/*.manifest.json.")
    parser.add_argument("--api-base-url", default=os.environ.get("QUANTAEDGE_API_BASE_URL", "http://localhost:8080"))
    parser.add_argument("--apply", action="store_true", help="Actually import validated pages as draft source records.")
    parser.add_argument("--approve", action="store_true", help="Also approve each imported source book (requires --apply).")
    args = parser.parse_args(argv)
    if args.approve and not args.apply:
        parser.error("--approve requires --apply.")
    root = args.extracted_root
    if not root.is_dir():
        parser.error(f"Extracted root does not exist: {root}")
    base = args.api_base_url.rstrip("/")
    parsed = urllib.parse.urlparse(base)
    if parsed.scheme != "https" and parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        parser.error("Use HTTPS for non-local API URLs.")
    cookie = os.environ.get("QUANTAEDGE_ADMIN_SESSION", "").strip()
    if args.apply and not cookie:
        parser.error("Set QUANTAEDGE_ADMIN_SESSION to the QE_STAFF_SESSION cookie value from a signed-in Education Admin session before --apply.")
    manifests = sorted(root.rglob("*.manifest.json"))
    if not manifests:
        parser.error(f"No *.manifest.json files found under {root}")
    successes = 0
    failures: list[dict[str, str]] = []
    for path in manifests:
        try:
            manifest, pages_path = validate_pair(path)
            if not args.apply:
                print("DRY_RUN " + json.dumps({
                    "manifest": str(path), "pages": str(pages_path),
                    "book_id": manifest.get("book_id"), "title": manifest.get("title"),
                    "language": manifest.get("language"), "pages_count": manifest.get("completed_pages"),
                }, ensure_ascii=False))
                successes += 1
                continue
            imported = post_multipart(
                base + "/api/v1/admin/textbook-extraction/ghcr-books", cookie,
                [
                    ("manifest", path.name, path.read_bytes()),
                    ("pages", pages_path.name, pages_path.read_bytes()),
                ],
            )
            if args.approve:
                source_book_id = imported.get("sourceBookId")
                if not isinstance(source_book_id, int):
                    raise RuntimeError("Import response did not contain sourceBookId; source was not approved.")
                imported["approval"] = post_approve(
                    base + f"/api/v1/admin/textbook-extraction/ghcr-books/{source_book_id}/approve", cookie
                )
            print("IMPORTED " + json.dumps(imported, ensure_ascii=False))
            successes += 1
        except Exception as exc:
            failures.append({"manifest": str(path), "error": str(exc)})
            print(f"ERROR {path}: {exc}", file=sys.stderr)
    report = {"manifests_found": len(manifests), "succeeded": successes, "failures": failures,
              "mode": "IMPORT_AND_APPROVE" if args.approve else ("IMPORT_DRAFT" if args.apply else "DRY_RUN")}
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
