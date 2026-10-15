#!/usr/bin/env python3
"""Prepare a stratified, source-bound visual review pack without AI or image-generation APIs.

This is an additive v3 candidate-preparation stage. It never modifies the source PDF,
inventory, existing v1/v2 sidecars, lesson content, or publication state.
"""
from __future__ import annotations

import argparse
import hashlib
import html
import json
import math
import mimetypes
import shutil
import sys
from collections import defaultdict
from pathlib import Path, PurePosixPath
from typing import Any

try:
    import fitz  # PyMuPDF
except ImportError:  # pragma: no cover - exercised by CLI error path
    fitz = None


SCHEMA_VERSION = "quantaedge.visual-candidate-pack.v3"
MAX_CONTEXT_CHARS = 2400
DEFAULT_MAX_CANDIDATES = 60
DEFAULT_CROP_SCALE = 2.0
TEXTBOOK_SIGNAL_TERMS = (
    "figure", "fig.", "diagram", "observe", "observation", "activity", "experiment",
    "example", "table", "graph", "map", "label", "illustration", "investigate",
    "प्रयोग", "चित्र", "आकृति", "देखिए", "गतिविधि", "उदाहरण", "अवलोकन", "तालिका",
    "मानचित्र", "रेखाचित्र", "आरेख", "प्रश्न", "प्रयोग करें",
)
FRONT_MATTER_TERMS = (
    "isbn", "qr code", "national syllabus", "committee members", "copyright",
    "printed at", "reprint", "acknowledgement", "acknowledgment",
    "राष्ट्रीय पाठ्यचर्या", "समिति के सदस्य", "मुद्रित", "पुनर्मुद्रण",
)
SUPPORTED_EMBEDDED_KINDS = {"embedded_image"}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _safe_asset_path(root: Path, relative: Any) -> Path | None:
    if not isinstance(relative, str) or not relative.strip():
        return None
    candidate = (root / relative).resolve()
    base = root.resolve()
    if candidate != base and base not in candidate.parents:
        raise ValueError(f"Asset path escapes the inventory directory: {relative!r}")
    return candidate if candidate.is_file() else None


def _relative(path: Path, root: Path) -> str:
    return path.resolve().relative_to(root.resolve()).as_posix()


def _context_signal(text: str, page_number: int, page_count: int) -> float:
    lowered = text.casefold()
    score = min(len(text.strip()) / 90.0, 12.0)
    score += min(sum(1 for term in TEXTBOOK_SIGNAL_TERMS if term.casefold() in lowered) * 2.0, 12.0)
    score -= min(sum(1 for term in FRONT_MATTER_TERMS if term.casefold() in lowered) * 4.0, 18.0)
    # Only use a front-matter penalty for substantial books. Short fixtures and short
    # books must remain eligible throughout their page range.
    front_cutoff = max(5, math.ceil(page_count * 0.04))
    if page_count > 30 and page_number <= front_cutoff:
        score -= 14.0
    return round(score, 3)


def _image_score(asset: dict[str, Any], page_text: str, page_rect: Any, page_count: int,
                 occurrence_count: int) -> float:
    score = 12.0 + _context_signal(page_text, int(asset.get("source_pdf_page_number") or 1), page_count) * 0.6
    width = asset.get("width")
    height = asset.get("height")
    if isinstance(width, (int, float)) and isinstance(height, (int, float)) and width > 0 and height > 0:
        smallest = min(width, height)
        largest = max(width, height)
        if smallest >= 400:
            score += 12
        elif smallest >= 180:
            score += 7
        elif smallest < 64:
            score -= 18
        ratio = largest / smallest
        if ratio > 9:
            score -= 18
        elif ratio > 5:
            score -= 8
        elif 0.35 <= width / height <= 3.2:
            score += 4

    bbox = asset.get("bbox_pdf_points")
    if isinstance(bbox, list) and len(bbox) == 4:
        try:
            rect = fitz.Rect(*bbox)
            page_area = max(float(page_rect.width) * float(page_rect.height), 1.0)
            area_ratio = max(0.0, float(rect.width) * float(rect.height)) / page_area
            if area_ratio >= 0.15:
                score += 12
            elif area_ratio >= 0.04:
                score += 8
            elif area_ratio >= 0.01:
                score += 3
            elif area_ratio < 0.0015:
                score -= 16
            vertical_center = (float(rect.y0) + float(rect.y1)) / 2 / max(float(page_rect.height), 1.0)
            if vertical_center < 0.08 or vertical_center > 0.96:
                score -= 4
        except (TypeError, ValueError):
            score -= 4
    else:
        score -= 5

    if occurrence_count > 1:
        score -= min(12.0, math.log2(occurrence_count) * 3.0)
    return round(score, 3)


def _page_score(page_text: str, page_number: int, page_count: int) -> float:
    score = 8.0 + _context_signal(page_text, page_number, page_count)
    if not page_text.strip():
        score -= 8
    return round(score, 3)


def _stratified_select(candidates: list[dict[str, Any]], limit: int, page_count: int) -> list[dict[str, Any]]:
    """Select high-ranked candidates evenly across page strata, then fill remaining slots."""
    if limit <= 0 or not candidates:
        return []
    if len(candidates) <= limit:
        return sorted(candidates, key=lambda row: (int(row.get("source_pdf_page_number") or 1),
                                                   -float(row.get("heuristic_score") or 0)))
    strata_count = min(12, max(1, limit), max(1, page_count))
    strata: list[list[dict[str, Any]]] = [[] for _ in range(strata_count)]
    for candidate in candidates:
        page_number = min(max(int(candidate.get("source_pdf_page_number") or 1), 1), page_count)
        index = min(strata_count - 1, int((page_number - 1) * strata_count / page_count))
        strata[index].append(candidate)
    for group in strata:
        group.sort(key=lambda row: (-float(row.get("heuristic_score") or 0),
                                    int(row.get("source_pdf_page_number") or 1),
                                    str(row.get("candidate_id") or "")))

    chosen: list[dict[str, Any]] = []
    cursors = [0] * strata_count
    while len(chosen) < limit:
        progressed = False
        for index, group in enumerate(strata):
            if len(chosen) >= limit:
                break
            if cursors[index] < len(group):
                chosen.append(group[cursors[index]])
                cursors[index] += 1
                progressed = True
        if not progressed:
            break
    return chosen


def _nearby_page_text(page: Any, bbox: list[float] | None, fallback: str) -> str:
    """Extract page text near an image crop where possible; fall back to existing page JSONL context."""
    try:
        blocks = page.get_text("blocks")
    except Exception:
        blocks = []
    texts: list[tuple[float, float, str]] = []
    if bbox and len(bbox) == 4:
        try:
            rect = fitz.Rect(*bbox)
        except (TypeError, ValueError):
            rect = None
    else:
        rect = None
    for block in blocks:
        if len(block) < 5 or not isinstance(block[4], str) or not block[4].strip():
            continue
        x0, y0, x1, y1 = (float(block[0]), float(block[1]), float(block[2]), float(block[3]))
        if rect is None:
            distance = y0
            texts.append((distance, y0, block[4].strip()))
            continue
        vertical_gap = max(rect.y0 - y1, y0 - rect.y1, 0.0)
        try:
            horizontal_overlap = max(0.0, min(rect.x1, x1) - max(rect.x0, x0))
        except Exception:
            horizontal_overlap = 0.0
        distance = vertical_gap - min(horizontal_overlap, 80.0) * 0.15
        if vertical_gap <= 200 or horizontal_overlap > 0:
            texts.append((distance, y0, block[4].strip()))
    if texts:
        texts.sort(key=lambda item: (item[0], item[1]))
        selected = [item[2] for item in texts[:8]]
        context = " ".join(" ".join(selected).split())[:MAX_CONTEXT_CHARS]
        if context:
            return context
    return " ".join(str(fallback or "").split())[:MAX_CONTEXT_CHARS]


def _render_page(page: Any, target: Path, scale: float) -> None:
    pixmap = page.get_pixmap(matrix=fitz.Matrix(scale, scale), alpha=False)
    pixmap.save(str(target), jpg_quality=88)


def _render_crop(page: Any, bbox: list[float] | None, target: Path, scale: float) -> bool:
    if not isinstance(bbox, list) or len(bbox) != 4:
        return False
    try:
        rect = fitz.Rect(*bbox)
    except (TypeError, ValueError):
        return False
    # A margin helps retain nearby arrows/labels. Keep the rectangle within the page.
    rect = fitz.Rect(rect.x0 - 8, rect.y0 - 8, rect.x1 + 8, rect.y1 + 8) & page.rect
    if rect.is_empty or rect.width < 4 or rect.height < 4:
        return False
    pixmap = page.get_pixmap(matrix=fitz.Matrix(scale, scale), clip=rect, alpha=False)
    pixmap.save(str(target), jpg_quality=90)
    return True


def _make_contact_sheet(candidates: list[dict[str, Any]], output_dir: Path) -> None:
    if fitz is None:
        raise RuntimeError("PyMuPDF is required to create the contact sheet.")
    sheet = fitz.open()
    page_width, page_height = 842.0, 595.0
    margin, columns, rows = 22.0, 3, 4
    cell_width = (page_width - margin * 2) / columns
    cell_height = (page_height - margin * 2) / rows
    per_page = columns * rows

    for start in range(0, len(candidates), per_page):
        page = sheet.new_page(width=page_width, height=page_height)
        for offset, candidate in enumerate(candidates[start:start + per_page]):
            local_index = offset
            col, row = local_index % columns, local_index // columns
            x = margin + col * cell_width
            y = margin + row * cell_height
            image_rect = fitz.Rect(x + 4, y + 4, x + cell_width - 4, y + cell_height - 34)
            image_path = output_dir / str(candidate["display_path"])
            if image_path.is_file():
                page.insert_image(image_rect, filename=str(image_path), keep_proportion=True)
            caption = (
                f'{candidate["candidate_id"]}  |  PDF p. {candidate["source_pdf_page_number"]}\n'
                f'{candidate["candidate_kind"]}  |  score {candidate["heuristic_score"]}\n'
                f'{candidate["selection_reason"]}'
            )
            caption_rect = fitz.Rect(x + 4, y + cell_height - 29, x + cell_width - 4, y + cell_height - 3)
            page.insert_textbox(caption_rect, caption[:260], fontsize=7, lineheight=1.05, color=(0.15, 0.2, 0.3))
    if len(sheet) == 0:
        sheet.new_page(width=page_width, height=page_height)
    pdf_path = output_dir / "contact-sheet.pdf"
    sheet.save(pdf_path, garbage=3, deflate=True)
    first_page_pix = sheet[0].get_pixmap(matrix=fitz.Matrix(1.2, 1.2), alpha=False)
    first_page_pix.save(str(output_dir / "contact-sheet.jpg"), jpg_quality=86)
    sheet.close()


def _write_review_html(candidates: list[dict[str, Any]], output_dir: Path, source: dict[str, Any]) -> None:
    cards = []
    for candidate in candidates:
        display_path = html.escape(str(candidate.get("display_path") or ""), quote=True)
        page_path = html.escape(str(candidate.get("source_page_preview_path") or ""), quote=True)
        context = html.escape(str(candidate.get("page_text_context") or ""))
        occurrences = ", ".join(
            f'p.{item.get("source_pdf_page_number")}'
            for item in candidate.get("occurrences", [])[:12]
        )
        cards.append(
            '<article class="candidate">'
            f'<div class="images"><img loading="lazy" src="{display_path}" alt="Candidate preview">'
            + (f'<img loading="lazy" src="{page_path}" alt="Original textbook page">' if page_path else "")
            + '</div>'
            f'<h2>{html.escape(str(candidate["candidate_id"]))} · page {candidate["source_pdf_page_number"]}</h2>'
            f'<p><b>{html.escape(str(candidate["candidate_kind"]))}</b> · heuristic score {candidate["heuristic_score"]}</p>'
            f'<p>{html.escape(str(candidate["selection_reason"]))}</p>'
            f'<p><b>Occurrences:</b> {html.escape(occurrences or "Not repeated")}</p>'
            f'<details><summary>Source page context</summary><p>{context or "No extractable page text was available."}</p></details>'
            '<p class="status">Candidate only — requires human review; not publishable.</p>'
            '</article>'
        )
    book_title = html.escape(str(source.get("book_id") or "Unknown source"))
    page = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>QuantaEdge visual candidate review pack</title>
<style>
body{margin:0;padding:24px;font:15px/1.5 system-ui,sans-serif;background:#f5f7fb;color:#17233a}
header{max-width:1400px;margin:0 auto 24px}h1{margin:0 0 8px}.meta{color:#53617a}
.grid{max-width:1400px;margin:auto;display:grid;grid-template-columns:repeat(auto-fit,minmax(330px,1fr));gap:16px}
.candidate{background:white;border:1px solid #d9dfeb;border-radius:12px;padding:14px;overflow:hidden}
.images{display:grid;grid-template-columns:1fr 1fr;gap:8px;align-items:center}
.images img{width:100%;max-height:280px;object-fit:contain;background:#edf1f7;border-radius:6px}
h2{font-size:16px;overflow-wrap:anywhere}.status{color:#8a4d00;font-weight:600}
details{background:#f7f9fc;padding:8px;border-radius:6px}.fine{font-size:12px}
</style></head><body><header>
<h1>QuantaEdge visual candidate pack v3</h1>
<p class="meta">Source book: """ + book_title + """ · Source-bound candidates only. Scores are deterministic ranking hints, not semantic verification.</p>
<p class="meta">Use the source page and nearby text to confirm educational meaning. No candidate is automatically accepted or published.</p>
</header><main class="grid">""" + "\n".join(cards) + """</main></body></html>"""
    (output_dir / "review-index.html").write_text(page, encoding="utf-8")


def prepare_candidates(
    inventory_path: Path,
    pdf_path: Path,
    output_dir: Path,
    *,
    max_candidates: int = DEFAULT_MAX_CANDIDATES,
    crop_scale: float = DEFAULT_CROP_SCALE,
) -> dict[str, Any]:
    if fitz is None:
        raise RuntimeError("PyMuPDF is required. Install it with: python -m pip install 'PyMuPDF>=1.24,<2'.")
    if not inventory_path.is_file():
        raise FileNotFoundError(f"Inventory not found: {inventory_path}")
    if not pdf_path.is_file():
        raise FileNotFoundError(f"Source PDF not found: {pdf_path}")
    if max_candidates < 1 or max_candidates > 200:
        raise ValueError("--max-candidates must be between 1 and 200.")
    if crop_scale < 1.0 or crop_scale > 4.0:
        raise ValueError("--crop-scale must be between 1.0 and 4.0.")

    inventory = json.loads(inventory_path.read_text(encoding="utf-8"))
    if not isinstance(inventory, dict) or inventory.get("schema_version") != "quantaedge.visual-inventory.v1":
        raise ValueError("Expected a quantaedge.visual-inventory.v1 inventory.")
    source = inventory.get("source")
    inventory_assets = inventory.get("assets")
    if not isinstance(source, dict) or not isinstance(inventory_assets, list):
        raise ValueError("Inventory must contain source metadata and an assets list.")

    actual_pdf_hash = sha256_file(pdf_path)
    expected_pdf_hash = str(source.get("pdf_sha256") or "").lower()
    if not expected_pdf_hash or actual_pdf_hash != expected_pdf_hash:
        raise ValueError("Source PDF SHA-256 does not match the inventory; refusing to prepare mismatched candidates.")

    if output_dir.exists() and any(output_dir.iterdir()):
        raise FileExistsError(f"Output directory is not empty: {output_dir}. Choose a new output directory.")
    output_dir.mkdir(parents=True, exist_ok=True)

    embedded_groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
    page_assets: dict[int, dict[str, Any]] = {}
    missing_assets = 0
    inventory_root = inventory_path.parent.resolve()

    for asset in inventory_assets:
        if not isinstance(asset, dict):
            continue
        kind = asset.get("kind")
        page_number = asset.get("source_pdf_page_number")
        if not isinstance(page_number, int) or page_number < 1:
            continue
        source_path = _safe_asset_path(inventory_root, asset.get("asset_path"))
        if kind == "page_preview":
            if source_path is None:
                missing_assets += 1
                continue
            # Prefer one page-preview record per page, retaining the first stable entry.
            page_assets.setdefault(page_number, asset)
        elif kind in SUPPORTED_EMBEDDED_KINDS:
            if source_path is None:
                missing_assets += 1
                continue
            digest = str(asset.get("asset_sha256") or sha256_file(source_path)).lower()
            embedded_groups[digest].append(asset)

    pdf = fitz.open(pdf_path)
    try:
        if pdf.is_encrypted and not pdf.authenticate(""):
            raise ValueError("Encrypted PDF cannot be opened without a password.")
        page_count = len(pdf)
        expected_page_count = source.get("pdf_page_count")
        if not isinstance(expected_page_count, int) or page_count != expected_page_count:
            raise ValueError(
                f"PDF page count ({page_count}) does not match inventory ({expected_page_count!r})."
            )
        if page_count < 1:
            raise ValueError("The source PDF contains no pages.")

        page_text_cache: dict[int, str] = {}
        def page_text(page_number: int, fallback: str = "") -> str:
            if page_number not in page_text_cache:
                try:
                    raw = pdf[page_number - 1].get_text("text")
                except Exception:
                    raw = ""
                page_text_cache[page_number] = " ".join(str(raw or fallback or "").split())[:MAX_CONTEXT_CHARS]
            return page_text_cache[page_number] or " ".join(str(fallback or "").split())[:MAX_CONTEXT_CHARS]

        embedded_candidates: list[dict[str, Any]] = []
        for digest, occurrences in embedded_groups.items():
            scored: list[tuple[float, dict[str, Any]]] = []
            for asset in occurrences:
                number = int(asset["source_pdf_page_number"])
                context = page_text(number, str(asset.get("page_text_context") or ""))
                score = _image_score(asset, context, pdf[number - 1].rect, page_count, len(occurrences))
                scored.append((score, asset))
            scored.sort(key=lambda pair: (-pair[0], int(pair[1]["source_pdf_page_number"])))
            best_score, primary = scored[0]
            source_asset_path = _safe_asset_path(inventory_root, primary.get("asset_path"))
            if source_asset_path is None:
                missing_assets += 1
                continue
            image_ext = source_asset_path.suffix.lower()
            if image_ext not in {".png", ".jpg", ".jpeg", ".webp", ".tif", ".tiff"}:
                image_ext = ".bin"
            candidate_id = f"v3-image-{digest[:20]}"
            all_occurrences = []
            for score, item in sorted(scored, key=lambda pair: (int(pair[1]["source_pdf_page_number"]), str(pair[1].get("bbox_pdf_points")))):
                all_occurrences.append({
                    "asset_id": item.get("asset_id"),
                    "source_pdf_page_number": int(item["source_pdf_page_number"]),
                    "bbox_pdf_points": item.get("bbox_pdf_points"),
                    "page_text_sha256": item.get("page_text_sha256"),
                    "asset_path": str(item.get("asset_path") or ""),
                    "heuristic_score": score,
                })
            embedded_candidates.append({
                "candidate_id": candidate_id,
                "candidate_kind": "embedded_image",
                "source_book_id": source.get("book_id"),
                "source_pdf_sha256": actual_pdf_hash,
                "source_pdf_page_number": int(primary["source_pdf_page_number"]),
                "bbox_pdf_points": primary.get("bbox_pdf_points"),
                "source_asset_sha256": digest,
                "source_asset_path": str(primary.get("asset_path") or ""),
                "source_asset_extension": image_ext,
                "source_asset_width": primary.get("width"),
                "source_asset_height": primary.get("height"),
                "page_text_context": page_text(int(primary["source_pdf_page_number"]), str(primary.get("page_text_context") or "")),
                "occurrences": all_occurrences,
                "heuristic_score": best_score,
                "selection_reason": "Unique embedded-image bytes; ranked by source-page context, image dimensions and PDF placement. Selection is stratified across pages where candidates permit.",
                "review_status": "CANDIDATE_NEEDS_HUMAN_REVIEW",
                "publication_allowed": False,
                "human_review_required": True,
            })

        page_candidates: list[dict[str, Any]] = []
        for page_number, asset in page_assets.items():
            fallback = str(asset.get("page_text_context") or "")
            text = page_text(page_number, fallback)
            page_candidates.append({
                "candidate_id": f'v3-page-{page_number:04d}-{str(asset.get("asset_sha256") or "")[:10]}',
                "candidate_kind": "page_preview",
                "source_book_id": source.get("book_id"),
                "source_pdf_sha256": actual_pdf_hash,
                "source_pdf_page_number": page_number,
                "bbox_pdf_points": asset.get("bbox_pdf_points"),
                "source_asset_sha256": asset.get("asset_sha256"),
                "source_asset_path": str(asset.get("asset_path") or ""),
                "source_asset_width": asset.get("width"),
                "source_asset_height": asset.get("height"),
                "page_text_context": text,
                "occurrences": [{
                    "asset_id": asset.get("asset_id"),
                    "source_pdf_page_number": page_number,
                    "bbox_pdf_points": asset.get("bbox_pdf_points"),
                    "page_text_sha256": asset.get("page_text_sha256"),
                    "asset_path": str(asset.get("asset_path") or ""),
                }],
                "heuristic_score": _page_score(text, page_number, page_count),
                "selection_reason": "Whole-page fallback preserves vector art and surrounding context; pages are selected across page-range strata rather than by inventory order.",
                "review_status": "CANDIDATE_NEEDS_HUMAN_REVIEW",
                "publication_allowed": False,
                "human_review_required": True,
            })

        # Preserve broad page-level evidence while keeping useful embedded crops in the pack.
        # Empty quotas are redistributed, so a book with few embedded objects still uses its budget.
        embedded_quota = min(len(embedded_candidates), max(1, int(round(max_candidates * 0.40))))
        page_quota = min(len(page_candidates), max_candidates - embedded_quota)
        selected_embedded = _stratified_select(embedded_candidates, embedded_quota, page_count)
        selected_page = _stratified_select(page_candidates, page_quota, page_count)
        selected_ids = {row["candidate_id"] for row in selected_embedded + selected_page}
        remaining = max_candidates - len(selected_ids)
        if remaining > 0:
            available = [row for row in embedded_candidates + page_candidates if row["candidate_id"] not in selected_ids]
            selected_extra = _stratified_select(available, remaining, page_count)
        else:
            selected_extra = []
        selected = selected_embedded + selected_page + selected_extra
        # Sort by book order for reviewer navigation; candidate IDs preserve identity across reruns.
        selected.sort(key=lambda row: (int(row["source_pdf_page_number"]), row["candidate_kind"], row["candidate_id"]))

        for folder in ("prepared/crops", "prepared/originals", "prepared/pages", "source-pages"):
            (output_dir / folder).mkdir(parents=True, exist_ok=True)

        source_page_copied: set[int] = set()
        for candidate in selected:
            page_number = int(candidate["source_pdf_page_number"])
            page = pdf[page_number - 1]
            page_preview_asset = page_assets.get(page_number)
            source_preview_path: str | None = None
            if page_preview_asset:
                original_preview = _safe_asset_path(inventory_root, page_preview_asset.get("asset_path"))
                if original_preview:
                    copied_preview = output_dir / "source-pages" / f"page-{page_number:04d}.jpg"
                    if page_number not in source_page_copied:
                        shutil.copyfile(original_preview, copied_preview)
                        source_page_copied.add(page_number)
                    if copied_preview.is_file():
                        source_preview_path = _relative(copied_preview, output_dir)
            if not source_preview_path:
                fallback_preview = output_dir / "source-pages" / f"page-{page_number:04d}.jpg"
                _render_page(page, fallback_preview, min(crop_scale, 1.6))
                source_preview_path = _relative(fallback_preview, output_dir)
                source_page_copied.add(page_number)
            candidate["source_page_preview_path"] = source_preview_path

            if candidate["candidate_kind"] == "embedded_image":
                ext = str(candidate.pop("source_asset_extension"))
                original_path = _safe_asset_path(inventory_root, candidate.get("source_asset_path"))
                if original_path:
                    suffix = ext if ext != ".bin" else ".bin"
                    original_copy = output_dir / "prepared" / "originals" / f'{candidate["candidate_id"]}{suffix}'
                    shutil.copyfile(original_path, original_copy)
                    candidate["original_asset_path"] = _relative(original_copy, output_dir)
                    candidate["original_asset_sha256"] = sha256_file(original_copy)
                crop_path = output_dir / "prepared" / "crops" / f'{candidate["candidate_id"]}.jpg'
                if _render_crop(page, candidate.get("bbox_pdf_points"), crop_path, crop_scale):
                    candidate["page_crop_path"] = _relative(crop_path, output_dir)
                    candidate["page_crop_sha256"] = sha256_file(crop_path)
                    candidate["display_path"] = candidate["page_crop_path"]
                    candidate["processing_method"] = "PDF_REGION_CROP_WITH_8PT_MARGIN; original embedded image retained"
                else:
                    candidate["page_crop_path"] = None
                    candidate["page_crop_sha256"] = None
                    candidate["display_path"] = candidate.get("original_asset_path")
                    candidate["processing_method"] = "ORIGINAL_EMBEDDED_IMAGE_ONLY; no valid PDF placement crop"
            else:
                page_path = output_dir / "prepared" / "pages" / f'page-{page_number:04d}.jpg'
                _render_page(page, page_path, min(crop_scale, 2.0))
                candidate["display_path"] = _relative(page_path, output_dir)
                candidate["processing_method"] = "SOURCE_PDF_PAGE_RENDER; vector art and text remain visible"

            candidate.pop("source_asset_extension", None)

        pack = {
            "schema_version": SCHEMA_VERSION,
            "pack_status": "CANDIDATES_FOR_HUMAN_REVIEW",
            "source": {
                "book_id": source.get("book_id"),
                "pdf_sha256": actual_pdf_hash,
                "page_count": page_count,
                "edition": source.get("edition"),
                "language": source.get("language"),
                "subject": source.get("subject"),
                "class": source.get("class"),
            },
            "selection_policy": {
                "method": "STRATIFIED_HEURISTIC_V3",
                "page_preview_target_fraction": 0.60,
                "embedded_image_target_fraction": 0.40,
                "semantic_classification_performed": False,
                "model_used": None,
                "notes": [
                    "Heuristic scores rank candidates; they do not determine educational correctness.",
                    "Unique embedded bytes are deduplicated for storage, while every source-page occurrence is retained.",
                    "Whole-page candidates preserve vector diagrams and surrounding textbook context.",
                    "All candidates remain unpublished and require human review.",
                ],
            },
            "summary": {
                "inventory_asset_records": len(inventory_assets),
                "embedded_image_hash_groups": len(embedded_groups),
                "page_preview_records": len(page_assets),
                "missing_inventory_asset_files": missing_assets,
                "selected_candidate_count": len(selected),
                "selected_embedded_images": sum(1 for row in selected if row["candidate_kind"] == "embedded_image"),
                "selected_page_previews": sum(1 for row in selected if row["candidate_kind"] == "page_preview"),
                "distinct_selected_pages": len({int(row["source_pdf_page_number"]) for row in selected}),
                "selected_page_numbers": sorted({int(row["source_pdf_page_number"]) for row in selected}),
                "publication_allowed": False,
            },
            "candidates": selected,
        }
        manifest_path = output_dir / "candidates.v3.json"
        manifest_path.write_text(json.dumps(pack, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        summary = {
            "schema_version": SCHEMA_VERSION,
            "pack_status": pack["pack_status"],
            "source": pack["source"],
            "summary": pack["summary"],
            "selection_policy": pack["selection_policy"],
            "publication_allowed": False,
        }
        (output_dir / "summary.v3.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        _make_contact_sheet(selected, output_dir)
        _write_review_html(selected, output_dir, source)
        return pack
    finally:
        pdf.close()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", required=True, type=Path, help="Path to visual-inventory.v1.json.")
    parser.add_argument("--pdf", required=True, type=Path, help="Exact source PDF matching the inventory hash.")
    parser.add_argument("--output-dir", required=True, type=Path, help="New/empty directory for the review pack.")
    parser.add_argument("--max-candidates", type=int, default=DEFAULT_MAX_CANDIDATES,
                        help="Maximum candidate count, evenly stratified where possible (default: 60, range 1..200).")
    parser.add_argument("--crop-scale", type=float, default=DEFAULT_CROP_SCALE,
                        help="PDF render scale for crops/pages, from 1.0 to 4.0 (default: 2.0).")
    args = parser.parse_args()
    try:
        pack = prepare_candidates(
            args.inventory, args.pdf, args.output_dir,
            max_candidates=args.max_candidates, crop_scale=args.crop_scale,
        )
    except (OSError, ValueError, RuntimeError, json.JSONDecodeError) as exc:
        print(f"Visual candidate preparation stopped safely: {exc}", file=sys.stderr)
        return 2
    print(json.dumps({
        "output_dir": str(args.output_dir),
        "schema_version": pack["schema_version"],
        "source_book_id": pack["source"].get("book_id"),
        "source_pdf_sha256": pack["source"].get("pdf_sha256"),
        **pack["summary"],
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
