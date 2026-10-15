#!/usr/bin/env python3
"""Create an optional, source-bound visual inventory from an existing textbook PDF.

This sidecar never changes the existing text JSONL, extraction manifest, import API,
or lesson records. It extracts embedded images and can render page previews so
vector diagrams remain available for later vision/reviewer analysis.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import mimetypes
import re
import sys
from pathlib import Path
from typing import Any

try:
    import fitz  # PyMuPDF
except ImportError:  # pragma: no cover - exercised by CLI error path
    fitz = None

SHA_RE = re.compile(r"^[0-9a-f]{64}$")
IMAGE_EXTENSIONS = {"png", "jpg", "jpeg", "jpx", "jp2", "jbig2", "webp", "tif", "tiff"}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def stable_id(*parts: str) -> str:
    raw = "\x1f".join(parts).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()[:24]


def load_page_context(manifest: dict[str, Any], manifest_path: Path, page_count: int, source_hash: str) -> dict[int, dict[str, Any]]:
    """Read the already-produced page JSONL when available; never rewrite it."""
    filename = manifest.get("text_jsonl")
    if not isinstance(filename, str) or not filename.strip():
        return {}
    if Path(filename).name != filename or not filename.endswith(".jsonl"):
        raise ValueError("manifest.text_jsonl must be a sibling JSONL filename.")
    jsonl_path = manifest_path.parent / filename
    if not jsonl_path.is_file():
        return {}
    contexts: dict[int, dict[str, Any]] = {}
    with jsonl_path.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"Invalid JSON in existing text JSONL at line {line_no}.") from exc
            if not isinstance(row, dict):
                raise ValueError(f"Existing text JSONL line {line_no} must be an object.")
            number = row.get("source_book_page_number", row.get("source_pdf_page_number"))
            if not isinstance(number, int) or not 1 <= number <= page_count or number in contexts:
                raise ValueError(f"Invalid or duplicate source page number in text JSONL line {line_no}.")
            if row.get("book_id") and row["book_id"] != manifest.get("book_id"):
                raise ValueError(f"Book ID mismatch in text JSONL line {line_no}.")
            row_source_hash = str(row.get("original_book_sha256") or row.get("source_pdf_sha256") or "").lower()
            if row_source_hash and row_source_hash != source_hash:
                raise ValueError(f"Source PDF SHA-256 mismatch in text JSONL line {line_no}.")
            text = row.get("text", "")
            if not isinstance(text, str):
                raise ValueError(f"Page text must be a string in text JSONL line {line_no}.")
            contexts[number] = {
                "page_text_sha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
                "context_text": text[:1600],
                "text_quality_status": str(row.get("review_status") or "UNKNOWN"),
                "text_extraction_method": str(row.get("extraction_method") or "UNKNOWN"),
            }
    return contexts


def _write_asset(asset_dir: Path, data: bytes, extension: str) -> tuple[str, str, int]:
    digest = hashlib.sha256(data).hexdigest()
    ext = extension.lower().lstrip(".")
    if ext not in IMAGE_EXTENSIONS:
        ext = "bin"
    path = asset_dir / f"{digest}.{ext}"
    if not path.exists():
        path.write_bytes(data)
    mime = mimetypes.guess_type(f"asset.{ext}")[0] or "application/octet-stream"
    return path.name, digest, len(data)


def build_inventory(
    pdf_path: Path,
    manifest_path: Path,
    output_dir: Path,
    *,
    render_page_previews: bool = True,
    preview_width: int = 1200,
) -> dict[str, Any]:
    if fitz is None:
        raise RuntimeError("PyMuPDF is required. Install it with: python -m pip install 'PyMuPDF>=1.24,<2'")
    if not pdf_path.is_file():
        raise FileNotFoundError(f"Source PDF not found: {pdf_path}")
    if not manifest_path.is_file():
        raise FileNotFoundError(f"Source extraction manifest not found: {manifest_path}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if not isinstance(manifest, dict):
        raise ValueError("Source extraction manifest must be a JSON object.")
    book_id = manifest.get("book_id")
    if not isinstance(book_id, str) or not book_id.strip():
        raise ValueError("manifest.book_id is required.")
    expected_hash = str(manifest.get("asset_pdf_sha256") or manifest.get("original_book_sha256") or manifest.get("source_sha256") or "").lower()
    if not SHA_RE.fullmatch(expected_hash):
        raise ValueError("Manifest must contain a valid asset_pdf_sha256 or original_book_sha256.")
    actual_hash = sha256_file(pdf_path)
    if actual_hash != expected_hash:
        raise ValueError("Source PDF SHA-256 does not match the manifest; refusing to associate assets with the wrong book.")
    expected_pages = manifest.get("page_count")
    doc = fitz.open(pdf_path)
    try:
        if doc.is_encrypted and not doc.authenticate(""):
            raise ValueError("Encrypted PDF cannot be opened without a password.")
        page_count = len(doc)
        if not isinstance(expected_pages, int) or expected_pages != page_count:
            raise ValueError(f"PDF page count ({page_count}) does not match manifest.page_count ({expected_pages!r}).")
        contexts = load_page_context(manifest, manifest_path, page_count, actual_hash)
        output_dir.mkdir(parents=True, exist_ok=True)
        asset_dir = output_dir / "assets"
        asset_dir.mkdir(parents=True, exist_ok=True)
        records: list[dict[str, Any]] = []
        page_records: list[dict[str, Any]] = []

        for index, page in enumerate(doc):
            page_no = index + 1
            context = contexts.get(page_no, {})
            xrefs: set[int] = set()
            page_image_count = 0
            for image_info in page.get_images(full=True):
                xref = int(image_info[0])
                if xref <= 0 or xref in xrefs:
                    continue
                xrefs.add(xref)
                try:
                    extracted = doc.extract_image(xref)
                    image_bytes = extracted.get("image", b"")
                    extension = str(extracted.get("ext") or "bin")
                    if not image_bytes:
                        continue
                    filename, digest, byte_count = _write_asset(asset_dir, image_bytes, extension)
                    try:
                        image_doc = fitz.open(stream=image_bytes, filetype=extension)
                        width, height = image_doc[0].rect.width, image_doc[0].rect.height
                        image_doc.close()
                    except Exception:
                        width, height = None, None
                    rects = page.get_image_rects(xref)
                    if not rects:
                        rects = [None]
                    for occurrence, rect in enumerate(rects, start=1):
                        bbox = [round(float(v), 3) for v in rect] if rect is not None else None
                        records.append({
                            "asset_id": stable_id(book_id, actual_hash, str(page_no), str(xref), str(occurrence), digest),
                            "kind": "embedded_image",
                            "source_book_id": book_id,
                            "source_pdf_sha256": actual_hash,
                            "source_pdf_page_number": page_no,
                            "page_text_sha256": context.get("page_text_sha256"),
                            "bbox_pdf_points": bbox,
                            "asset_path": f"assets/{filename}",
                            "asset_sha256": digest,
                            "media_type": mimetypes.guess_type(filename)[0] or "application/octet-stream",
                            "byte_count": byte_count,
                            "width": int(width) if width else None,
                            "height": int(height) if height else None,
                            "page_text_context": context.get("context_text", "")[:600],
                            "text_quality_status": context.get("text_quality_status", "UNKNOWN"),
                            "extraction_method": "PYMUPDF_EMBEDDED_IMAGE",
                            "vision_status": "NOT_ANALYZED",
                            "confidence": None,
                            "rights_status": "SOURCE_REFERENCE_ONLY",
                            "review_status": "CANDIDATE",
                        })
                        page_image_count += 1
                except Exception as exc:
                    records.append({
                        "asset_id": stable_id(book_id, actual_hash, str(page_no), str(xref), "extraction-error"),
                        "kind": "embedded_image_candidate",
                        "source_book_id": book_id,
                        "source_pdf_sha256": actual_hash,
                        "source_pdf_page_number": page_no,
                        "extraction_method": "PYMUPDF_EMBEDDED_IMAGE",
                        "scan_status": "EXTRACTION_ERROR",
                        "error": str(exc)[:300],
                        "review_status": "NEEDS_REVIEW",
                        "rights_status": "SOURCE_REFERENCE_ONLY",
                    })

            preview_path = None
            if render_page_previews:
                scale = min(2.0, max(0.5, preview_width / max(float(page.rect.width), 1.0)))
                pix = page.get_pixmap(matrix=fitz.Matrix(scale, scale), alpha=False)
                preview_name = f"page-{page_no:04d}.jpg"
                pix.save(str(output_dir / preview_name), jpg_quality=75)
                preview_bytes = (output_dir / preview_name).read_bytes()
                preview_hash = hashlib.sha256(preview_bytes).hexdigest()
                preview_path = preview_name
                records.append({
                    "asset_id": stable_id(book_id, actual_hash, str(page_no), "page-preview", preview_hash),
                    "kind": "page_preview",
                    "source_book_id": book_id,
                    "source_pdf_sha256": actual_hash,
                    "source_pdf_page_number": page_no,
                    "page_text_sha256": context.get("page_text_sha256"),
                    "bbox_pdf_points": [0.0, 0.0, round(float(page.rect.width), 3), round(float(page.rect.height), 3)],
                    "asset_path": preview_path,
                    "asset_sha256": preview_hash,
                    "media_type": "image/jpeg",
                    "byte_count": len(preview_bytes),
                    "width": pix.width,
                    "height": pix.height,
                    "page_text_context": context.get("context_text", "")[:600],
                    "text_quality_status": context.get("text_quality_status", "UNKNOWN"),
                    "extraction_method": "PYMUPDF_PAGE_RENDER",
                    "vision_status": "NOT_ANALYZED",
                    "confidence": None,
                    "rights_status": "SOURCE_REFERENCE_ONLY",
                    "review_status": "CANDIDATE",
                    "note": "Whole-page preview; vector diagrams are not yet individually segmented.",
                })
            page_records.append({
                "source_pdf_page_number": page_no,
                "page_text_sha256": context.get("page_text_sha256"),
                "text_quality_status": context.get("text_quality_status", "UNKNOWN"),
                "visual_scan_status": "SCANNED_WITH_PREVIEW" if render_page_previews else "EMBEDDED_IMAGES_SCANNED",
                "embedded_image_occurrences": page_image_count,
                "page_preview": preview_path,
                "vision_status": "NOT_ANALYZED",
                "human_review_required": True,
            })

        result = {
            "schema_version": "quantaedge.visual-inventory.v1",
            "inventory_status": "DRAFT_VISUAL_INVENTORY_ONLY",
            "source": {
                "book_id": book_id,
                "pdf_filename": pdf_path.name,
                "pdf_sha256": actual_hash,
                "pdf_page_count": page_count,
                "edition": manifest.get("edition"),
                "language": manifest.get("language"),
                "subject": manifest.get("subject"),
                "class": manifest.get("class") or manifest.get("classes"),
            },
            "text_context_source": "existing_text_jsonl" if contexts else "pdf_manifest_only",
            "summary": {
                "pages_scanned": len(page_records),
                "pages_with_embedded_images": sum(1 for p in page_records if p["embedded_image_occurrences"]),
                "embedded_image_occurrences": sum(p["embedded_image_occurrences"] for p in page_records),
                "page_previews_rendered": sum(1 for p in page_records if p["page_preview"]),
                "asset_records": len(records),
                "vision_analysis_completed": False,
                "publication_allowed": False,
            },
            "pages": page_records,
            "assets": records,
        }
        manifest_out = output_dir / "visual-inventory.v1.json"
        manifest_out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return result
    finally:
        doc.close()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pdf", required=True, type=Path, help="Original whole-book PDF downloaded from the existing GHCR source.")
    parser.add_argument("--manifest", required=True, type=Path, help="Existing extraction manifest bound to the PDF SHA-256.")
    parser.add_argument("--output-dir", required=True, type=Path, help="New local output directory for sidecar inventory and assets.")
    parser.add_argument("--preview-width", type=int, default=1200, help="Approximate maximum rendered page width in pixels (default: 1200).")
    parser.add_argument("--no-page-previews", action="store_true", help="Only extract embedded images; vector-only figures will not be separately visible.")
    parser.add_argument("--overwrite", action="store_true", help="Explicitly replace an existing inventory output directory.")
    args = parser.parse_args()
    try:
        inventory_path = args.output_dir / "visual-inventory.v1.json"
        if inventory_path.exists() and not args.overwrite:
            raise FileExistsError(f"Refusing to overwrite {inventory_path}; choose a new output directory or pass --overwrite.")
        if args.preview_width < 300 or args.preview_width > 2400:
            raise ValueError("--preview-width must be between 300 and 2400 pixels.")
        result = build_inventory(
            args.pdf, args.manifest, args.output_dir,
            render_page_previews=not args.no_page_previews,
            preview_width=args.preview_width,
        )
    except (OSError, ValueError, RuntimeError, json.JSONDecodeError) as exc:
        print(f"Visual inventory stopped safely: {exc}", file=sys.stderr)
        return 2
    print(json.dumps({
        "output": str(args.output_dir / "visual-inventory.v1.json"),
        "book_id": result["source"]["book_id"],
        "pages_scanned": result["summary"]["pages_scanned"],
        "embedded_image_occurrences": result["summary"]["embedded_image_occurrences"],
        "page_previews_rendered": result["summary"]["page_previews_rendered"],
        "vision_analysis_completed": False,
        "publication_allowed": False,
        "status": result["inventory_status"],
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
