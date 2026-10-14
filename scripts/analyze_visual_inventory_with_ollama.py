#!/usr/bin/env python3
"""Optional local Ollama vision analysis for a visual-inventory sidecar.

Uses Ollama's native /api/chat endpoint with image inputs. It writes a separate
analysis sidecar and never edits the source inventory, PDF, page JSONL, or lesson data.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import mimetypes
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

SUPPORTED_IMAGE_TYPES = {"image/jpeg", "image/png", "image/webp"}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _json_object_from_response(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError("Vision model response must be a JSON object.")
    keys = ("visual_summary", "visual_type", "visible_text", "concepts", "context_relation", "limitations")
    if not all(key in value for key in keys):
        raise ValueError("Vision model response is missing required fields.")
    if not isinstance(value["visual_summary"], str) or not value["visual_summary"].strip():
        raise ValueError("visual_summary must be non-empty text.")
    if value["visual_type"] not in {
        "diagram", "chart", "graph", "table", "map", "cartoon", "photo",
        "illustration", "equation", "page_with_multiple_visuals", "other", "uncertain",
    }:
        raise ValueError("visual_type is not in the supported classification set.")
    for key in ("visible_text", "concepts", "limitations"):
        if not isinstance(value[key], list) or not all(isinstance(item, str) for item in value[key]):
            raise ValueError(f"{key} must be a list of strings.")
    if not isinstance(value["context_relation"], str):
        raise ValueError("context_relation must be text.")
    return {
        "visual_summary": value["visual_summary"].strip()[:1200],
        "visual_type": value["visual_type"],
        "visible_text": [item.strip()[:300] for item in value["visible_text"][:30]],
        "concepts": [item.strip()[:200] for item in value["concepts"][:20]],
        "context_relation": value["context_relation"].strip()[:1000],
        "limitations": [item.strip()[:300] for item in value["limitations"][:20]],
    }


def analyze_one(
    inventory_dir: Path,
    asset: dict[str, Any],
    *,
    endpoint: str,
    model: str,
    timeout: int = 180,
    opener=urllib.request.urlopen,
) -> dict[str, Any]:
    relative = asset.get("asset_path")
    if not isinstance(relative, str) or not relative:
        raise ValueError("Asset has no asset_path.")
    asset_path = (inventory_dir / relative).resolve()
    root = inventory_dir.resolve()
    if asset_path != root and root not in asset_path.parents:
        raise ValueError("Asset path escapes the inventory directory.")
    if not asset_path.is_file():
        raise FileNotFoundError(f"Asset file not found: {relative}")
    media_type = str(asset.get("media_type") or mimetypes.guess_type(asset_path.name)[0] or "")
    if media_type not in SUPPORTED_IMAGE_TYPES:
        raise ValueError(f"Unsupported vision media type {media_type!r}; use JPEG, PNG, or WebP assets.")
    expected_hash = asset.get("asset_sha256")
    actual_hash = sha256_file(asset_path)
    if expected_hash and expected_hash != actual_hash:
        raise ValueError("Asset SHA-256 does not match inventory.")
    if not isinstance(asset.get("source_book_id"), str) or not asset["source_book_id"]:
        raise ValueError("Asset is missing source_book_id.")
    page_number = asset.get("source_pdf_page_number")
    if not isinstance(page_number, int) or page_number < 1:
        raise ValueError("Asset is missing a valid source page number.")
    context = str(asset.get("page_text_context") or "")[:1600]
    prompt = (
        "Analyze this textbook visual for an educational content editor. The image may be a full page, "
        "not a cropped figure. Do not guess illegible labels, formulas, quantities, or relationships. "
        "Distinguish visible evidence from uncertainty. Use the supplied page text only as context; "
        "do not claim the image proves something that is not visible. Return only JSON matching the schema. "
        "Keep visible_text short and preserve the original script/language where readable. "
        "Schema: {visual_summary:string, visual_type:diagram|chart|graph|table|map|cartoon|photo|"
        "illustration|equation|page_with_multiple_visuals|other|uncertain, visible_text:string[], "
        "concepts:string[], context_relation:string, limitations:string[]}\n\n"
        f"Book ID: {asset['source_book_id']}\n"
        f"Source PDF page: {page_number}\n"
        f"Asset kind: {asset.get('kind', 'unknown')}\n"
        f"Nearby extracted page text (possibly incomplete):\n{context or '[No extracted text available]'}"
    )
    payload = {
        "model": model,
        "stream": False,
        "format": "json",
        "messages": [{
            "role": "user",
            "content": prompt,
            "images": [base64.b64encode(asset_path.read_bytes()).decode("ascii")],
        }],
        "options": {"temperature": 0},
    }
    base = endpoint.rstrip("/")
    parsed = urllib.parse.urlparse(base)
    if parsed.scheme != "http" or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise ValueError("For this first version, the Ollama endpoint must be loopback HTTP (localhost/127.0.0.1/::1).")
    request = urllib.request.Request(
        base + "/api/chat",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with opener(request, timeout=timeout) as response:
        response_data = json.loads(response.read().decode("utf-8"))
    if not isinstance(response_data, dict):
        raise ValueError("Ollama returned an invalid response.")
    message = response_data.get("message")
    if not isinstance(message, dict) or not isinstance(message.get("content"), str):
        raise ValueError("Ollama response has no message.content.")
    try:
        parsed_content = json.loads(message["content"])
    except json.JSONDecodeError as exc:
        raise ValueError("Vision model did not return valid JSON.") from exc
    analysis = _json_object_from_response(parsed_content)
    return {
        "asset_id": asset.get("asset_id"),
        "asset_sha256": actual_hash,
        "source_book_id": asset["source_book_id"],
        "source_pdf_sha256": asset.get("source_pdf_sha256"),
        "source_pdf_page_number": page_number,
        "asset_path": relative,
        "asset_kind": asset.get("kind"),
        "model_provider": "ollama_local",
        "model": model,
        "analysis_status": "DRAFT_REQUIRES_REVIEW",
        "analysis": analysis,
        "human_review_required": True,
        "publication_allowed": False,
    }


PROMPT_VERSION = "visual-analysis-prompt-v2"


def run_analysis(
    inventory_path: Path,
    *,
    endpoint: str,
    model: str,
    max_assets: int,
    include_page_previews: bool,
    timeout: int = 180,
    opener=urllib.request.urlopen,
    output_path: Path | None = None,
    resume: bool = False,
) -> dict[str, Any]:
    if not inventory_path.is_file():
        raise FileNotFoundError(f"Inventory not found: {inventory_path}")
    inventory = json.loads(inventory_path.read_text(encoding="utf-8"))
    if not isinstance(inventory, dict) or inventory.get("schema_version") != "quantaedge.visual-inventory.v1":
        raise ValueError("Expected a quantaedge.visual-inventory.v1 inventory.")
    source = inventory.get("source")
    assets = inventory.get("assets")
    if not isinstance(source, dict) or not isinstance(assets, list):
        raise ValueError("Visual inventory is missing source or assets.")
    inventory_dir = inventory_path.parent

    previous: dict[str, dict[str, Any]] = {}
    if resume and output_path and output_path.is_file():
        saved = json.loads(output_path.read_text(encoding="utf-8"))
        if not isinstance(saved, dict) or saved.get("schema_version") != "quantaedge.visual-analysis.v1":
            raise ValueError("Cannot resume: existing analysis checkpoint has an unknown schema.")
        if saved.get("source_book_id") != source.get("book_id") or saved.get("source_pdf_sha256") != source.get("pdf_sha256"):
            raise ValueError("Cannot resume: checkpoint source book/PDF hash does not match this inventory.")
        if saved.get("model") != model or saved.get("prompt_version") != PROMPT_VERSION:
            raise ValueError("Cannot resume: model or prompt version changed. Use a new output directory or --overwrite.")
        for item in saved.get("results", []):
            if isinstance(item, dict) and item.get("asset_id"):
                previous[str(item["asset_id"])] = item

    selected: list[dict[str, Any]] = []
    seen: set[str] = set()
    skipped_from_checkpoint = 0
    for asset in assets:
        if not isinstance(asset, dict) or asset.get("kind") not in {"embedded_image", "page_preview"}:
            continue
        if asset.get("kind") == "page_preview" and not include_page_previews:
            continue
        asset_id = asset.get("asset_id")
        if not asset_id or asset_id in seen:
            continue
        seen.add(str(asset_id))
        existing = previous.get(str(asset_id), {})
        if (
            existing.get("analysis_status") == "DRAFT_REQUIRES_REVIEW"
            and existing.get("asset_sha256") == asset.get("asset_sha256")
            and existing.get("model") == model
            and existing.get("prompt_version") == PROMPT_VERSION
        ):
            skipped_from_checkpoint += 1
            continue
        if len(selected) < max_assets:
            selected.append(asset)

    results_by_id = dict(previous)

    def checkpoint() -> dict[str, Any]:
        ordered_results = list(results_by_id.values())
        summary = {
            "assets_selected": len(selected) + skipped_from_checkpoint,
            "assets_analyzed": sum(1 for item in ordered_results if item.get("analysis_status") == "DRAFT_REQUIRES_REVIEW" and item.get("model") == model and item.get("prompt_version") == PROMPT_VERSION),
            "assets_failed": sum(1 for item in ordered_results if item.get("analysis_status") == "ANALYSIS_ERROR"),
            "assets_skipped_from_checkpoint": skipped_from_checkpoint,
            "publication_allowed": False,
        }
        result = {
            "schema_version": "quantaedge.visual-analysis.v1",
            "analysis_status": "DRAFT_VISION_ANALYSIS_ONLY",
            "source_inventory": inventory_path.name,
            "source_book_id": source.get("book_id"),
            "source_pdf_sha256": source.get("pdf_sha256"),
            "model_provider": "ollama_local",
            "model": model,
            "prompt_version": PROMPT_VERSION,
            "summary": summary,
            "results": ordered_results,
        }
        if output_path:
            output_path.parent.mkdir(parents=True, exist_ok=True)
            temporary = output_path.with_suffix(output_path.suffix + ".tmp")
            temporary.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\\n", encoding="utf-8")
            temporary.replace(output_path)
        return result

    # Persist the initial state and every result, so runner interruption loses at most
    # the currently executing asset. Successful assets are reused only when their
    # content hash, model tag and prompt version still match; errors are retried.
    result = checkpoint()
    for asset in selected:
        asset_id = str(asset["asset_id"])
        existing = results_by_id.get(asset_id, {})
        if (
            existing.get("analysis_status") == "DRAFT_REQUIRES_REVIEW"
            and existing.get("asset_sha256") == asset.get("asset_sha256")
            and existing.get("model") == model
            and existing.get("prompt_version") == PROMPT_VERSION
        ):
            continue
        try:
            item = analyze_one(inventory_dir, asset, endpoint=endpoint, model=model, timeout=timeout, opener=opener)
            item["prompt_version"] = PROMPT_VERSION
            item["checkpointed_at"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
            results_by_id[asset_id] = item
        except Exception as exc:
            results_by_id[asset_id] = {
                "asset_id": asset.get("asset_id"),
                "asset_sha256": asset.get("asset_sha256"),
                "source_book_id": source.get("book_id"),
                "source_pdf_sha256": source.get("pdf_sha256"),
                "source_pdf_page_number": asset.get("source_pdf_page_number"),
                "asset_path": asset.get("asset_path"),
                "model_provider": "ollama_local",
                "model": model,
                "prompt_version": PROMPT_VERSION,
                "analysis_status": "ANALYSIS_ERROR",
                "error": str(exc)[:500],
                "human_review_required": True,
                "publication_allowed": False,
            }
        result = checkpoint()
    return result

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", required=True, type=Path, help="Path to visual-inventory.v1.json.")
    parser.add_argument("--model", required=True, help="An already-installed Ollama vision model tag.")
    parser.add_argument("--endpoint", default="http://127.0.0.1:11434", help="Local Ollama endpoint.")
    parser.add_argument("--max-assets", type=int, default=5, help="Maximum candidate assets in this run (default: 5).")
    parser.add_argument("--include-page-previews", action="store_true", help="Include full-page previews; these may cost more context.")
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--resume", action="store_true", help="Resume from the existing output checkpoint and retry failed assets.")
    parser.add_argument("--overwrite", action="store_true", help="Discard an existing checkpoint and start a fresh analysis.")
    args = parser.parse_args()
    output_path = args.inventory.parent / "visual-analysis.v1.json"
    try:
        if args.max_assets < 1 or args.max_assets > 10000:
            raise ValueError("--max-assets must be between 1 and 10000.")
        if args.timeout < 10 or args.timeout > 900:
            raise ValueError("--timeout must be between 10 and 900 seconds.")
        if output_path.exists() and not (args.resume or args.overwrite):
            raise FileExistsError(f"Refusing to overwrite {output_path}; pass --resume to continue or --overwrite to restart.")
        if args.resume and args.overwrite:
            raise ValueError("Choose only one of --resume or --overwrite.")
        if args.overwrite:
            output_path.unlink(missing_ok=True)
        result = run_analysis(
            args.inventory, endpoint=args.endpoint, model=args.model,
            max_assets=args.max_assets, include_page_previews=args.include_page_previews,
            timeout=args.timeout, output_path=output_path, resume=args.resume,
        )
    except (OSError, ValueError, RuntimeError, json.JSONDecodeError) as exc:
        print(f"Visual analysis stopped safely: {exc}", file=sys.stderr)
        return 2
    print(json.dumps({"output": str(output_path), **result["summary"], "model": args.model}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
