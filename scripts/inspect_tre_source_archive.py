#!/usr/bin/env python3
"""Safely inventory the TRE PDF bundle and produce a temporary, page-addressable extraction.

Output is intended for a short-lived GitHub Actions artifact; it must not be committed as an
intermediate. Original PDFs are copied unchanged so visual QA and SHA-256 checks remain possible.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
import zipfile
from pathlib import Path, PurePosixPath

try:
    import fitz
except ImportError as exc:
    raise SystemExit("PyMuPDF is required. Install it with: python -m pip install pymupdf") from exc

MAX_ARCHIVE_BYTES = 200 * 1024 * 1024
MAX_PDF_BYTES = 80 * 1024 * 1024
MAX_PDFS = 20


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def safe_basename(name: str) -> str:
    # Do not trust directory names from an archive. Keep only a conservative filename.
    base = PurePosixPath(name.replace("\\", "/")).name
    base = re.sub(r"[^A-Za-z0-9._ -]", "_", base).strip(" .")
    return (base or "source.pdf")[:180]


def inspect_archive(archive_path: Path, output_dir: Path) -> dict:
    if not archive_path.is_file():
        raise ValueError(f"ZIP not found: {archive_path}")
    if archive_path.stat().st_size > MAX_ARCHIVE_BYTES:
        raise ValueError(f"ZIP exceeds the {MAX_ARCHIVE_BYTES // (1024 * 1024)} MiB source limit.")
    output_dir.mkdir(parents=True, exist_ok=True)
    pdf_dir = output_dir / "pdfs"
    text_dir = output_dir / "page-text"
    pdf_dir.mkdir(exist_ok=True)
    text_dir.mkdir(exist_ok=True)

    records = []
    names: set[str] = set()
    with zipfile.ZipFile(archive_path) as zf:
        infos = [item for item in zf.infolist() if not item.is_dir()]
        if len(infos) > 500:
            raise ValueError("ZIP has too many entries (limit 500).")
        if sum(max(0, item.file_size) for item in infos) > MAX_ARCHIVE_BYTES:
            raise ValueError("ZIP uncompressed content exceeds the 200 MiB limit.")
        pdf_infos = [item for item in infos if item.filename.lower().endswith(".pdf")]
        other_entries = [
            {"archive_name": item.filename, "size_bytes": item.file_size}
            for item in infos if not item.filename.lower().endswith(".pdf")
        ]
        if not pdf_infos:
            raise ValueError("The ZIP contains no PDF files.")
        if len(pdf_infos) > MAX_PDFS:
            raise ValueError(f"The ZIP contains more than {MAX_PDFS} PDFs; review manually.")
        for info in pdf_infos:
            if info.file_size <= 0 or info.file_size > MAX_PDF_BYTES:
                raise ValueError(f"Invalid PDF size for {info.filename!r}: {info.file_size} bytes.")
            basename = safe_basename(info.filename)
            candidate = basename
            stem, suffix = Path(candidate).stem, Path(candidate).suffix
            serial = 2
            while candidate.casefold() in names:
                candidate = f"{stem}-{serial}{suffix}"
                serial += 1
            names.add(candidate.casefold())
            destination = pdf_dir / candidate
            with zf.open(info, "r") as input_file, destination.open("wb") as output_file:
                shutil.copyfileobj(input_file, output_file)
            pdf_hash = sha256_file(destination)
            pages = []
            with fitz.open(destination) as pdf:
                if not pdf.page_count:
                    raise ValueError(f"PDF has no pages: {info.filename!r}")
                for idx, page in enumerate(pdf):
                    text = page.get_text("text", sort=True)
                    pages.append({
                        "pdf_page": idx + 1,
                        "text_characters": len(text),
                        "text": text,
                    })
                extracted = {
                    "schema": "quantaedge.tre-pdf-page-extraction.v1",
                    "archive_name": info.filename,
                    "file_name": candidate,
                    "file_size_bytes": destination.stat().st_size,
                    "sha256": pdf_hash,
                    "page_count": pdf.page_count,
                    "metadata": pdf.metadata,
                    "pages": pages,
                }
            text_path = text_dir / (Path(candidate).stem + ".pages.json")
            text_path.write_text(json.dumps(extracted, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            full_text = "\n\n".join(f"===== PDF PAGE {p['pdf_page']} =====\n{p['text']}" for p in pages)
            (text_dir / (Path(candidate).stem + ".txt")).write_text(full_text, encoding="utf-8")
            records.append({
                "archive_name": info.filename,
                "file_name": candidate,
                "size_bytes": destination.stat().st_size,
                "sha256": pdf_hash,
                "page_count": len(pages),
                "page_text_chars": [p["text_characters"] for p in pages],
                "has_text_layer": sum(p["text_characters"] > 80 for p in pages),
                "pdf_path": str(destination.relative_to(output_dir)).replace("\\", "/"),
                "page_json_path": str(text_path.relative_to(output_dir)).replace("\\", "/"),
                "plain_text_path": str((text_dir / (Path(candidate).stem + ".txt")).relative_to(output_dir)).replace("\\", "/"),
            })
    manifest = {
        "schema": "quantaedge.tre-source-archive-inspection.v1",
        "source_archive": str(archive_path),
        "source_archive_sha256": sha256_file(archive_path),
        "source_archive_size_bytes": archive_path.stat().st_size,
        "pdf_count": len(records),
        "pdfs": records,
        "non_pdf_entries": other_entries,
        "note": "Inspection outputs are temporary artifacts, not student content and not approval to publish.",
    }
    (output_dir / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True, help="Repository TRE source ZIP")
    parser.add_argument("--output-dir", type=Path, required=True, help="Temporary inspection directory")
    args = parser.parse_args()
    try:
        inspect_archive(args.archive, args.output_dir)
    except (OSError, zipfile.BadZipFile, ValueError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
