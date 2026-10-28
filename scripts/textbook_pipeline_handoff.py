#!/usr/bin/env python3
"""Time-aware, source-bound handoff validation for the bilingual textbook pipeline."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

try:
    from scripts.plan_textbook_layer45_matrix import BOOKS, BOOK_PAIRS
except ModuleNotFoundError:
    from plan_textbook_layer45_matrix import BOOKS, BOOK_PAIRS

SCHEMA = "quantaedge.textbook-pipeline-handoff.v1"
STATUSES = {"QUEUED", "RUNNING", "CHECKPOINTED", "COMPLETE", "RETRYABLE_FAILED", "BLOCKED", "NEEDS_REVIEW"}
ELIGIBLE_KINDS = {"embedded_image", "page_preview"}


def utcnow() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path}: root must be a JSON object.")
    return value


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def safe_repo_path(root: Path, raw: str) -> Path:
    path = Path(raw)
    if path.is_absolute() or ".." in path.parts:
        raise ValueError(f"Unsafe repository artifact path: {raw!r}")
    resolved = (root / path).resolve()
    resolved.relative_to(root.resolve())
    return resolved


def pair_for_id(pair_id: str) -> dict[str, str]:
    matches = [pair for pair in BOOK_PAIRS if pair["book_pair_id"] == pair_id]
    if len(matches) != 1:
        raise ValueError(f"Unknown or ambiguous bilingual pair ID: {pair_id!r}")
    return matches[0]


def pair_for_book_id(book_id: str) -> dict[str, str]:
    matches = [pair for pair in BOOK_PAIRS if book_id in pair["book_ids_csv"].split(",")]
    if len(matches) != 1:
        raise ValueError(f"Source book {book_id!r} does not map to exactly one bilingual pair.")
    return matches[0]


def pair_sources(pair: dict[str, str]) -> list[dict[str, str]]:
    ids = pair["book_ids_csv"].split(",")
    slugs = pair["source_slugs_csv"].split(",")
    if len(ids) != 2 or len(slugs) != 2 or len(set(ids)) != 2 or len(set(slugs)) != 2:
        raise ValueError(f"{pair['book_pair_id']}: pair must contain exactly two unique book IDs and slugs.")
    by_id = {book["book_id"]: book for book in BOOKS}
    result = []
    for book_id, slug in zip(ids, slugs, strict=True):
        source = by_id.get(book_id)
        if not source or source["book_slug"] != slug or source["class_no"] != pair["class_no"]:
            raise ValueError(f"{pair['book_pair_id']}: source ID-to-slug mapping or class does not match the registry.")
        result.append({**source, "language": "english" if "english" in book_id else "hindi"})
    if {source["language"] for source in result} != {"english", "hindi"}:
        raise ValueError(f"{pair['book_pair_id']}: one English and one Hindi edition are required.")
    return result


def extraction_manifest(root: Path, source: dict[str, str]) -> tuple[Path | None, dict[str, Any] | None, list[str]]:
    grade, book_id, language = source["class_no"], source["book_id"], source["language"]
    folder = root / "data" / "textbook-extractions" / f"class-{grade}" / language / "pages"
    if not folder.is_dir():
        return None, None, [f"{book_id}: extracted-page directory is missing."]
    matches = []
    for path in folder.glob("*.manifest.json"):
        try:
            doc = load_json(path)
        except (OSError, UnicodeError, json.JSONDecodeError, ValueError):
            continue
        if doc.get("book_id") == book_id:
            matches.append((path, doc))
    if len(matches) != 1:
        return None, None, [f"{book_id}: expected exactly one source manifest, found {len(matches)}."]
    path, doc = matches[0]
    problems = []
    digest = str(doc.get("original_book_sha256") or doc.get("asset_pdf_sha256") or doc.get("source_pdf_sha256") or "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", digest):
        problems.append(f"{book_id}: extraction PDF SHA-256 is missing/invalid.")
    if doc.get("scope") != "SUBJECT_BOOK":
        problems.append(f"{book_id}: extraction scope is not SUBJECT_BOOK.")
    if doc.get("content_state") != "DRAFT_EXTRACTION_ONLY_REQUIRES_REVIEW":
        problems.append(f"{book_id}: extraction is not explicitly draft/review-only.")
    if str(doc.get("class")) != grade or str(doc.get("language", "")).lower() != language:
        problems.append(f"{book_id}: manifest class/language does not match the selected pair.")
    text_name = doc.get("text_jsonl")
    if not isinstance(text_name, str) or not text_name or Path(text_name).name != text_name or not text_name.endswith(".jsonl"):
        return path, doc, problems + [f"{book_id}: unsafe or missing text_jsonl reference."]
    text_path = path.parent / text_name
    if not text_path.is_file():
        return path, doc, problems + [f"{book_id}: sibling page JSONL is missing."]
    page_numbers = []
    try:
        with text_path.open("r", encoding="utf-8") as stream:
            for row_no, line in enumerate(stream, 1):
                if not line.strip():
                    continue
                row = json.loads(line)
                if not isinstance(row, dict):
                    problems.append(f"{book_id}: JSONL row {row_no} is not an object.")
                    break
                number = row.get("source_book_page_number")
                if number is None:
                    number = row.get("source_pdf_page_number", row.get("page_number"))
                try:
                    page_numbers.append(int(number))
                except (TypeError, ValueError):
                    problems.append(f"{book_id}: JSONL row {row_no} lacks a valid source page number.")
                    break
                if row.get("book_id") != book_id:
                    problems.append(f"{book_id}: JSONL row {row_no} belongs to a different book.")
                    break
                row_hash = str(row.get("original_book_sha256") or row.get("asset_pdf_sha256") or "")
                if row_hash and row_hash.lower() != digest:
                    problems.append(f"{book_id}: JSONL row {row_no} PDF hash differs from its manifest.")
                    break
    except (OSError, UnicodeError, json.JSONDecodeError):
        problems.append(f"{book_id}: extracted-page JSONL is unreadable/invalid.")
    if not page_numbers:
        problems.append(f"{book_id}: extracted-page JSONL has no verifiable pages.")
    elif page_numbers != list(range(1, len(page_numbers) + 1)):
        problems.append(f"{book_id}: source page numbering is duplicated, missing, or unordered.")
    declared = doc.get("page_count")
    if isinstance(declared, int) and page_numbers and declared != len(page_numbers):
        problems.append(f"{book_id}: manifest page_count differs from page JSONL row count.")
    return path, doc, sorted(set(problems))


def _declared(root: Path, raw: Any, name: str, problems: list[str]) -> Path | None:
    if not isinstance(raw, str) or not raw:
        problems.append(f"{name}: declared path is missing.")
        return None
    try:
        path = safe_repo_path(root, raw)
    except (ValueError, OSError):
        problems.append(f"{name}: declared path is unsafe.")
        return None
    if not path.is_file():
        problems.append(f"{name}: referenced artifact is missing: {raw}.")
        return None
    return path


def validate_book_layer45(root: Path, source: dict[str, str]) -> tuple[bool, list[str], dict[str, Any]]:
    """Fail closed on any source/hash/schema/page/asset mismatch before accepting Layer 5."""
    problems = []
    grade, book_id, slug = source["class_no"], source["book_id"], source["book_slug"]
    visual = root / "data" / "textbook-visuals" / slug
    index_path = visual / "visual-pipeline-index.v1.json"
    counts = {"eligible_assets": 0, "analyzed_assets": 0, "failed_assets": 0, "remaining_assets": 0, "unresolved_count": None}
    try:
        index = load_json(index_path)
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError):
        return False, [f"{book_id}: pipeline index missing/unreadable."], counts
    if index.get("schema_version") != "quantaedge.textbook-content-pipeline-index.v1":
        problems.append(f"{book_id}: pipeline-index schema mismatch.")
    if index.get("source_book_id") != book_id or index.get("book_slug", slug) != slug:
        problems.append(f"{book_id}: pipeline-index book identity mismatch.")
    source_sha = str(index.get("source_pdf_sha256") or "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", source_sha):
        problems.append(f"{book_id}: pipeline index source SHA-256 invalid.")
    for layer in ("1", "2", "3"):
        if (index.get("layers") or {}).get(layer) != "COMPLETE":
            problems.append(f"{book_id}: prerequisite Layer {layer} is not COMPLETE.")
    manifest_path, manifest, manifest_problems = extraction_manifest(root, source)
    problems.extend(manifest_problems)
    if manifest:
        manifest_sha = str(manifest.get("original_book_sha256") or manifest.get("asset_pdf_sha256") or manifest.get("source_pdf_sha256") or "").lower()
        if source_sha and manifest_sha != source_sha:
            problems.append(f"{book_id}: extraction manifest SHA differs from pipeline index.")

    try:
        inventory = load_json(visual / "visual-inventory.v1.json")
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError):
        return False, sorted(set(problems + [f"{book_id}: visual inventory missing/unreadable."])), counts
    if inventory.get("schema_version") != "quantaedge.visual-inventory.v1":
        problems.append(f"{book_id}: visual-inventory schema mismatch.")
    inv_source = inventory.get("source") if isinstance(inventory.get("source"), dict) else {}
    inv_id = inventory.get("source_book_id") or inv_source.get("book_id") or inv_source.get("source_book_id")
    inv_sha = str(inventory.get("source_pdf_sha256") or inv_source.get("pdf_sha256") or inv_source.get("source_pdf_sha256") or "").lower()
    if inv_id and inv_id != book_id:
        problems.append(f"{book_id}: inventory source-book ID mismatch.")
    if inv_sha and source_sha and inv_sha != source_sha:
        problems.append(f"{book_id}: inventory source PDF hash mismatch.")
    asset_rows = inventory.get("assets")
    if not isinstance(asset_rows, list):
        problems.append(f"{book_id}: inventory assets must be an array.")
        asset_rows = []
    inventory_by_id = {}
    for row in asset_rows:
        if not isinstance(row, dict):
            problems.append(f"{book_id}: inventory contains a non-object asset.")
            continue
        asset_id = str(row.get("asset_id") or "")
        if not asset_id or asset_id in inventory_by_id:
            problems.append(f"{book_id}: inventory asset ID is missing or duplicated.")
            continue
        inventory_by_id[asset_id] = row
    eligible = {asset_id: row for asset_id, row in inventory_by_id.items() if row.get("kind") in ELIGIBLE_KINDS}
    counts["eligible_assets"] = len(eligible)

    raw_path = _declared(root, index.get("analysis_path"), f"{book_id} raw analysis", problems)
    raw, raw_sha, raw_by_id = {}, "", {}
    if raw_path:
        raw_sha = sha256_file(raw_path)
        try:
            raw = load_json(raw_path)
        except (OSError, UnicodeError, json.JSONDecodeError, ValueError):
            problems.append(f"{book_id}: raw analysis is invalid JSON.")
            raw = {}
        if raw.get("schema_version") != "quantaedge.visual-analysis.v1":
            problems.append(f"{book_id}: raw-analysis schema mismatch.")
        if raw.get("source_book_id") != book_id or str(raw.get("source_pdf_sha256") or "").lower() != source_sha:
            problems.append(f"{book_id}: raw analysis source ID/PDF hash mismatch.")
        if not raw.get("model") or not raw.get("prompt_version"):
            problems.append(f"{book_id}: raw-analysis model or prompt version missing.")
        rows = raw.get("results")
        if not isinstance(rows, list):
            problems.append(f"{book_id}: raw-analysis results must be an array.")
            rows = []
        for row in rows:
            if not isinstance(row, dict):
                problems.append(f"{book_id}: raw-analysis result is not an object.")
                continue
            asset_id = str(row.get("asset_id") or "")
            if not asset_id or asset_id in raw_by_id:
                problems.append(f"{book_id}: raw-analysis asset ID missing/duplicated.")
                continue
            raw_by_id[asset_id] = row
            if asset_id not in inventory_by_id:
                problems.append(f"{book_id}: orphan raw-analysis asset {asset_id}.")
            elif str(row.get("asset_sha256") or "").lower() != str(inventory_by_id[asset_id].get("asset_sha256") or "").lower():
                problems.append(f"{book_id}: raw-analysis asset hash mismatch for {asset_id}.")
        for asset_id in eligible:
            if asset_id not in raw_by_id:
                problems.append(f"{book_id}: eligible asset {asset_id} has no raw-analysis result.")
        summary = raw.get("summary") if isinstance(raw.get("summary"), dict) else {}
        for key, label in (("assets_eligible","eligible_assets"),("assets_analyzed","analyzed_assets"),("assets_failed","failed_assets"),("assets_remaining","remaining_assets")):
            value = summary.get(key)
            if not isinstance(value, int) or value < 0:
                problems.append(f"{book_id}: raw summary {key} missing/invalid.")
            else:
                counts[label] = value
        if counts["eligible_assets"] != len(eligible):
            problems.append(f"{book_id}: raw eligible count differs from inventory.")
        if counts["analyzed_assets"] != len(eligible) or counts["failed_assets"] != 0 or counts["remaining_assets"] != 0:
            problems.append(f"{book_id}: Layer 4 has failed, missing, or unprocessed visuals.")
        if len(raw_by_id) != len(eligible):
            problems.append(f"{book_id}: raw result count differs from eligible visual count.")

    effective_path = _declared(root, index.get("effective_analysis_path"), f"{book_id} effective analysis", problems)
    if effective_path:
        try:
            effective = load_json(effective_path)
        except (OSError, UnicodeError, json.JSONDecodeError, ValueError):
            problems.append(f"{book_id}: effective analysis invalid JSON.")
            effective = {}
        if effective.get("schema_version") != "quantaedge.visual-analysis-effective.v1":
            problems.append(f"{book_id}: effective-analysis schema mismatch.")
        if effective.get("source_book_id") != book_id or str(effective.get("source_pdf_sha256") or "").lower() != source_sha:
            problems.append(f"{book_id}: effective-analysis source identity/hash mismatch.")
        if effective.get("raw_analysis_path") != index.get("analysis_path") or effective.get("raw_analysis_sha256") != raw_sha:
            problems.append(f"{book_id}: effective-analysis raw path/hash is stale.")
        if raw and (effective.get("model") != raw.get("model") or effective.get("prompt_version") != raw.get("prompt_version")):
            problems.append(f"{book_id}: effective-analysis model/prompt differs from raw analysis.")
        rows = effective.get("results")
        effective_by_id = {}
        if not isinstance(rows, list):
            problems.append(f"{book_id}: effective-analysis results must be an array.")
            rows = []
        for row in rows:
            if not isinstance(row, dict):
                problems.append(f"{book_id}: effective-analysis row is not an object.")
                continue
            asset_id = str(row.get("asset_id") or "")
            if not asset_id or asset_id in effective_by_id:
                problems.append(f"{book_id}: effective-analysis asset ID missing/duplicated.")
                continue
            effective_by_id[asset_id] = row
        for asset_id, asset in eligible.items():
            row = effective_by_id.get(asset_id)
            if not row:
                problems.append(f"{book_id}: effective analysis omits {asset_id}.")
                continue
            if str(row.get("asset_sha256") or "").lower() != str(asset.get("asset_sha256") or "").lower():
                problems.append(f"{book_id}: effective-analysis asset hash mismatch for {asset_id}.")
            if row.get("analysis_status") != "ANALYZED_REQUIRES_HUMAN_REVIEW" or not isinstance(row.get("effective_analysis"), dict):
                problems.append(f"{book_id}: effective analysis for {asset_id} is not valid review-only content.")

    ledger_path = _declared(root, index.get("coverage_ledger_path"), f"{book_id} coverage ledger", problems)
    if ledger_path:
        canonical = root / "data" / "textbook-content-pipeline" / f"class-{grade}" / "books" / slug / "coverage-ledger.v1.json"
        if ledger_path.resolve() != canonical.resolve():
            problems.append(f"{book_id}: index points to a non-canonical coverage ledger.")
        try:
            ledger = load_json(ledger_path)
        except (OSError, UnicodeError, json.JSONDecodeError, ValueError):
            problems.append(f"{book_id}: coverage ledger invalid JSON.")
            ledger = {}
        if ledger.get("schema_version") != "quantaedge.textbook-coverage-ledger.v1":
            problems.append(f"{book_id}: coverage-ledger schema mismatch.")
        source_meta = ledger.get("source") if isinstance(ledger.get("source"), dict) else {}
        if source_meta.get("source_book_id") != book_id or str(source_meta.get("source_pdf_sha256") or "").lower() != source_sha:
            problems.append(f"{book_id}: coverage-ledger source ID/PDF hash mismatch.")
        summary = ledger.get("summary") if isinstance(ledger.get("summary"), dict) else {}
        counts["unresolved_count"] = summary.get("unresolved_count")
        if ledger.get("ledger_status") != "COMPLETE":
            problems.append(f"{book_id}: Layer 5 ledger is not COMPLETE.")
        for key in ("unresolved_count","missing_visual_analyses","failed_visual_analyses","orphan_analysis_results","pages_with_unresolved_text"):
            default = 0 if key in {"missing_visual_analyses","failed_visual_analyses","orphan_analysis_results"} else None
            if summary.get(key, default) != 0:
                problems.append(f"{book_id}: ledger {key} is not zero.")
        if summary.get("eligible_visual_asset_rows") != len(eligible) or summary.get("successful_visual_analyses") != len(eligible):
            problems.append(f"{book_id}: coverage-ledger counts do not match eligible assets.")
        if raw and (summary.get("analysis_model") != raw.get("model") or summary.get("analysis_prompt_version") != raw.get("prompt_version")):
            problems.append(f"{book_id}: ledger model/prompt differs from raw analysis.")
        pages, page_count = ledger.get("pages"), summary.get("source_pdf_page_count")
        if not isinstance(pages, list) or not isinstance(page_count, int) or page_count < 1:
            problems.append(f"{book_id}: ledger page-level mapping is missing.")
        else:
            numbers = [row.get("source_pdf_page_number") for row in pages if isinstance(row, dict)]
            if len(numbers) != len(pages) or numbers != list(range(1, page_count + 1)):
                problems.append(f"{book_id}: ledger pages are not complete ordered 1-based source pages.")
        visuals = ledger.get("visuals")
        if not isinstance(visuals, list):
            problems.append(f"{book_id}: ledger visual crosswalk is missing.")
        else:
            ledger_by_id = {}
            for row in visuals:
                if not isinstance(row, dict):
                    problems.append(f"{book_id}: ledger visual row is not an object.")
                    continue
                asset_id = str(row.get("asset_id") or "")
                if not asset_id or asset_id in ledger_by_id:
                    problems.append(f"{book_id}: ledger visual ID missing/duplicated.")
                    continue
                ledger_by_id[asset_id] = row
            for asset_id, asset in eligible.items():
                row = ledger_by_id.get(asset_id)
                if not row:
                    problems.append(f"{book_id}: ledger omits eligible asset {asset_id}.")
                elif str(row.get("asset_sha256") or "").lower() != str(asset.get("asset_sha256") or "").lower():
                    problems.append(f"{book_id}: ledger hash mismatch for {asset_id}.")
                elif row.get("analysis_status") != "ANALYZED_REQUIRES_HUMAN_REVIEW":
                    problems.append(f"{book_id}: ledger does not verify analysis for {asset_id}.")
    if (index.get("layers") or {}).get("5") != "COMPLETE":
        problems.append(f"{book_id}: pipeline index does not mark Layer 5 COMPLETE.")
    return not problems, sorted(set(problems)), {"source_book_id":book_id,"book_slug":slug,"class_no":grade,"source_pdf_sha256":source_sha or None,"counts":counts}


def validate_pair_completion(root: Path, pair: dict[str, str]) -> tuple[bool, list[str]]:
    problems = []
    try:
        sources = pair_sources(pair)
    except ValueError as exc:
        return False, [str(exc)]
    for source in sources:
        ok, reasons, _ = validate_book_layer45(root, source)
        if not ok:
            problems.extend(reasons)
    return not problems, sorted(set(problems))


def _paths_for_stage(root: Path, pair: dict[str,str], stage: int, source_book_id: str|None) -> tuple[list[Path],list[str]]:
    problems=[]; paths=[]
    sources=pair_sources(pair)
    if source_book_id and stage != 5:
        sources=[s for s in sources if s["book_id"]==source_book_id]
        if not sources: return [],[f"{source_book_id} does not belong to {pair['book_pair_id']}."]
    for source in sources:
        grade,slug=source["class_no"],source["book_slug"]
        visual=root/"data"/"textbook-visuals"/slug
        layer=root/"data"/"textbook-content-pipeline"/f"class-{grade}"/"books"/slug
        if stage == 0:
            mp,doc,reasons=extraction_manifest(root,source); problems.extend(reasons)
            if mp:
                paths.append(mp)
                if doc and isinstance(doc.get("text_jsonl"),str): paths.append(mp.parent/Path(doc["text_jsonl"]).name)
        elif stage == 1: paths.extend([visual/"visual-inventory.v1.json",visual/"visual-pipeline-index.v1.json"])
        elif stage == 2: paths.extend([root/"data"/"textbook-visual-candidates-v3"/slug/name for name in ("candidates.v3.json","summary.v3.json","bundle-index.v3.json")])
        elif stage == 3: paths.extend([root/"data"/"textbook-visual-candidates-v3"/"catalogs"/f"{slug}.v3.json",root/"data"/"textbook-visual-candidates-v3"/"index.v1.json"])
        elif stage == 4:
            try:
                idx=load_json(visual/"visual-pipeline-index.v1.json"); path=_declared(root,idx.get("analysis_path"),f"{source['book_id']} raw analysis",problems)
                if path: paths.append(path)
            except (OSError,ValueError,json.JSONDecodeError): problems.append(f"{source['book_id']}: pipeline index missing.")
        elif stage == 5:
            try:
                idx=load_json(visual/"visual-pipeline-index.v1.json")
                for key in ("effective_analysis_path","coverage_ledger_path"):
                    path=_declared(root,idx.get(key),f"{source['book_id']} {key}",problems)
                    if path: paths.append(path)
            except (OSError,ValueError,json.JSONDecodeError): problems.append(f"{source['book_id']}: pipeline index missing.")
        else:
            ledger=layer/"coverage-ledger.v1.json"
            try:
                led=load_json(ledger); digest=str((led.get("source") or {}).get("source_pdf_sha256") or "")
                output=layer/"layers-6-8"/f"{slug}-sha{digest[:8]}-v5"/"layers-6-8-run-manifest.v1.json"
                if output.is_file(): paths.append(output)
                else: problems.append(f"{source['book_id']}: required Layers 6–8 output is missing.")
            except (OSError,ValueError,json.JSONDecodeError): problems.append(f"{source['book_id']}: Layer 5 prerequisite ledger missing.")
    for path in paths:
        if not path.is_file(): problems.append(f"Layer {stage} output missing: {path}")
    return list(dict.fromkeys(x for x in paths if x.is_file())), sorted(set(problems))


def artifact_ref(root: Path, path: Path) -> dict[str, Any]:
    out={"path":path.resolve().relative_to(root.resolve()).as_posix(),"sha256":sha256_file(path),"byte_size":path.stat().st_size}
    try:
        value=load_json(path); out["schema_version"]=value.get("schema_version")
        for key in ("results","assets","candidates","pages","visuals","sources","items","segments"):
            if isinstance(value.get(key),list): out["row_count"]=len(value[key]); out["row_collection"]=key; break
    except (OSError,ValueError,json.JSONDecodeError):
        if path.suffix==".jsonl":
            with path.open("r",encoding="utf-8") as stream: out["row_count"]=sum(bool(line.strip()) for line in stream)
            out["row_collection"]="jsonl_lines"
    return out


def update_timestamps(previous:dict[str,Any],now:str,status:str,processed:int,failed:int,unresolved:Any,checkpoint_changed:bool)->dict[str,str]:
    old=previous.get("timestamps") or {}; work=previous.get("work") or {}
    times=dict(old); times.setdefault("created_at",now)
    if status not in {"QUEUED"}: times.setdefault("started_at",now)
    old_status=(previous.get("decision") or {}).get("status")
    progress=(processed>int(work.get("processed_count",0) or 0)
        or failed!=int(work.get("failed_count",0) or 0)
        or (unresolved is not None and unresolved!=work.get("unresolved_count"))
        or (status in {"COMPLETE","NEEDS_REVIEW"} and old_status not in {"COMPLETE","NEEDS_REVIEW"}))
    if progress: times["last_progress_at"]=now
    if status=="CHECKPOINTED" and checkpoint_changed: times["checkpoint_at"]=now
    if status=="COMPLETE":
        times["completed_at"]=times.get("completed_at") or now
    else:
        # NEEDS_REVIEW is a saved draft state, not a completed production layer.
        times.pop("completed_at",None)
    if status in {"RETRYABLE_FAILED","BLOCKED"}: times["last_error_at"]=now
    times["updated_at"]=now
    return times


def record_handoff(root:Path,pair:dict[str,str],stage:int,status:str,*,source_book_id:str|None=None,reason_code:str="STATUS_UPDATE",
    message:str="",retryable:bool=False,retry_after:str|None=None,model:str|None=None,prompt_version:str|None=None,
    processed_count:int=0,expected_count:int=0,failed_count:int=0,unresolved_count:int|None=None,completed_batches:int=0,
    budget:int|None=None,extra_artifacts:list[str]|None=None,model_startup_seconds:float|None=None,model_run_seconds:float|None=None)->dict[str,Any]:
    if status not in STATUSES: raise ValueError(f"Invalid status {status!r}.")
    if stage not in range(9): raise ValueError("Layer must be 0 through 8.")
    sources=pair_sources(pair)
    if source_book_id and source_book_id not in {s["book_id"] for s in sources}: raise ValueError("Source book is not a member of selected pair.")
    paths,problems=_paths_for_stage(root,pair,stage,None if stage==5 else source_book_id)
    for raw in extra_artifacts or []:
        path=safe_repo_path(root,raw)
        if not path.is_file(): problems.append(f"Declared output artifact missing: {raw}")
        else: paths.append(path)
    if status=="CHECKPOINTED" and stage==5:
        pair_ok, pair_reasons = validate_pair_completion(root, pair)
        if not pair_ok:
            problems.extend(pair_reasons)
            critical_markers = ("schema mismatch", "hash mismatch", "SHA-256", "identity mismatch",
                "ID mismatch", "slug mismatch", "unsafe", "orphan", "invalid JSON", "unreadable",
                "path is missing", "non-canonical", "page mapping", "PDF hash differs")
            if any(marker.lower() in reason.lower() for reason in pair_reasons for marker in critical_markers):
                status = "BLOCKED"
    if status=="COMPLETE":
        if stage==5:
            ok,reasons=validate_pair_completion(root,pair)
            if not ok: problems.extend(reasons)
        elif stage==4:
            for source in sources:
                if source_book_id and source["book_id"]!=source_book_id: continue
                ok,reasons,_=validate_book_layer45(root,source)
                if not ok: problems.extend(reasons)
        elif stage==0:
            # JSONL is a stream of JSON records, not one JSON document.
            # extraction_manifest validates every row, page identity, whole-book hash and sequence.
            for source in sources:
                _,_,reasons=extraction_manifest(root,source)
                problems.extend(reasons)
        elif not paths:
            problems.append(f"Layer {stage} cannot be COMPLETE without output artifacts.")
        else:
            for path in paths:
                try:
                    value=load_json(path)
                    if not value.get("schema_version"): problems.append(f"{path}: schema_version missing.")
                except (OSError,ValueError,json.JSONDecodeError): problems.append(f"{path}: invalid JSON.")
    if status=="NEEDS_REVIEW" and not paths: problems.append(f"Layer {stage} cannot require review without an output artifact.")
    if problems and status in {"COMPLETE","NEEDS_REVIEW"}: status="BLOCKED"
    output_refs=[artifact_ref(root,p) for p in dict.fromkeys(paths) if p.is_file()]
    input_paths=[]
    for source in sources:
        grade,slug=source["class_no"],source["book_slug"]; visual=root/"data"/"textbook-visuals"/slug
        if stage in (0,1):
            mp,doc,_=extraction_manifest(root,source)
            if mp:
                input_paths.append(mp)
                if doc and isinstance(doc.get("text_jsonl"),str): input_paths.append(mp.parent/Path(doc["text_jsonl"]).name)
        elif stage==2: input_paths.append(visual/"visual-inventory.v1.json")
        elif stage==3: input_paths += [root/"data"/"textbook-visual-candidates-v3"/slug/"candidates.v3.json",root/"data"/"textbook-visual-candidates-v3"/slug/"bundle-index.v3.json"]
        elif stage in (4,5):
            input_paths.append(visual/"visual-inventory.v1.json")
            try:
                idx=load_json(visual/"visual-pipeline-index.v1.json")
                if isinstance(idx.get("analysis_path"),str): input_paths.append(safe_repo_path(root,idx["analysis_path"]))
            except (OSError,ValueError,json.JSONDecodeError): pass
        else: input_paths.append(root/"data"/"textbook-content-pipeline"/f"class-{grade}"/"books"/slug/"coverage-ledger.v1.json")
    input_refs=[artifact_ref(root,p) for p in dict.fromkeys(input_paths) if p.is_file()]
    source_refs=[]
    for source in sources:
        digest=None
        try: digest=str(load_json(root/"data"/"textbook-visuals"/source["book_slug"]/"visual-pipeline-index.v1.json").get("source_pdf_sha256") or "").lower() or None
        except (OSError,ValueError,json.JSONDecodeError): pass
        if not digest:
            _,manifest,_=extraction_manifest(root,source)
            if manifest: digest=str(manifest.get("original_book_sha256") or manifest.get("asset_pdf_sha256") or manifest.get("source_pdf_sha256") or "").lower() or None
        source_refs.append({"source_book_id":source["book_id"],"book_slug":source["book_slug"],"language":source["language"],"source_pdf_sha256":digest})
    base=root/"data"/"textbook-content-pipeline"/"handoffs"/f"class-{pair['class_no']}"/pair["book_pair_id"]
    runs=base/"runs"; runs.mkdir(parents=True,exist_ok=True)
    events=base/"events.v1.jsonl"; current=base/"current.v1.json"
    run_id=os.environ.get("GITHUB_RUN_ID","local"); attempt=os.environ.get("GITHUB_RUN_ATTEMPT","1")
    run_path=runs/f"run-{run_id}-attempt-{attempt}.v1.json"
    old_run={}; old_current={}
    for path,target in ((run_path,old_run),(current,old_current)):
        try: target.update(load_json(path))
        except (OSError,ValueError,json.JSONDecodeError): pass
    previous=old_run or old_current
    old_statuses=old_current.get("layer_statuses") or {}
    previous_layer=old_statuses.get(str(stage),{})
    clock_previous={"timestamps":previous_layer.get("timestamps",{}),"work":previous_layer.get("work",{}),"decision":{"status":previous_layer.get("status")}}
    now=utcnow()
    old_hashes=[(x.get("path"),x.get("sha256")) for x in previous_layer.get("output_artifacts",[])]
    new_hashes=[(x["path"],x["sha256"]) for x in output_refs]
    checkpoint_changed=old_hashes!=new_hashes
    times=update_timestamps(clock_previous,now,status,processed_count,failed_count,unresolved_count,checkpoint_changed)
    if retry_after: times["retry_after"]=retry_after
    elif status not in {"BLOCKED","RETRYABLE_FAILED"}: times.pop("retry_after",None)
    layer_statuses=dict(old_statuses)
    layer_statuses[str(stage)]={"status":status,"timestamps":times,
        "work":{"expected_count":max(0,expected_count),"processed_count":max(0,processed_count),"failed_count":max(0,failed_count),"unresolved_count":unresolved_count},
        "output_artifacts":output_refs,"reason_code":reason_code,"retryable":bool(retryable) and status!="BLOCKED"}
    event_path=events.relative_to(root).as_posix()
    run_url=f"{os.environ.get('GITHUB_SERVER_URL','https://github.com')}/{os.environ.get('GITHUB_REPOSITORY','')}/actions/runs/{run_id}"
    document={
      "schema_version":SCHEMA,"pipeline_run_id":str(run_id),"run_attempt":str(attempt),
      "handoff_id":f"{pair['book_pair_id']}:run-{run_id}-attempt-{attempt}:layer-{stage}",
      "book_pair_id":pair["book_pair_id"],"class_no":int(pair["class_no"]),"subject_book_label":pair["book_label"],
      "source_book_id":source_book_id,"sources":source_refs,
      "stage":{"layer":stage,"name":{0:"source_extraction",1:"visual_inventory",2:"visual_candidates",3:"candidate_catalogue",4:"raw_visual_analysis",5:"effective_analysis_and_coverage",6:"curriculum_mapping",7:"structured_lesson_generation",8:"tutor_retrieval_and_training"}[stage],
        "input_schema_versions":sorted({x["schema_version"] for x in input_refs if x.get("schema_version")}),
        "output_schema_version":output_refs[-1].get("schema_version") if output_refs else None,
        "consumer_contract_version":"quantaedge.textbook-bilingual-pair-progress.v1"},
      "lineage":{"producer_git_sha":os.environ.get("GITHUB_SHA"),"workflow":os.environ.get("GITHUB_WORKFLOW","local"),
        "workflow_run_id":str(run_id),"workflow_run_attempt":str(attempt),"workflow_run_url":run_url,
        "branch":os.environ.get("QUANTAEDGE_CHECKPOINT_BRANCH") or os.environ.get("GITHUB_REF_NAME"),
        "parent_handoff_ids":[f"{pair['book_pair_id']}:layer-{stage-1}"] if stage else [],
        "input_artifacts":input_refs,"output_artifacts":output_refs,"append_only_event_log":event_path},
      "timestamps":times,
      "work":{"attempts":int(attempt) if attempt.isdigit() else 1,"completed_batches":max(0,completed_batches),
        "expected_count":max(0,expected_count),"processed_count":max(0,processed_count),"failed_count":max(0,failed_count),
        "unresolved_count":unresolved_count,"budget":budget,"model":model,"prompt_version":prompt_version,
        "model_startup_seconds":model_startup_seconds,"model_run_seconds":model_run_seconds,"publication_allowed":False},
      "validation":{"schema_valid":not problems,"identity_valid":not any("ID" in x or "book" in x or "pair" in x for x in problems),
        "hashes_valid":not any("hash" in x.lower() or "SHA" in x for x in problems),
        "references_valid":not any("path" in x.lower() or "missing" in x.lower() or "orphan" in x.lower() for x in problems),
        "blockers":sorted(set(problems))},
      "decision":{"status":status,"reason_code":"CONTRACT_VALIDATION_FAILED" if status=="BLOCKED" else reason_code,
        "reason":message or ("; ".join(sorted(set(problems))) if problems else status.lower().replace("_"," ")),
        "retryable":bool(retryable) and status!="BLOCKED",
        "next_action":"Fix the listed contract blockers; do not rerun inference until corrected." if status=="BLOCKED" else
          "Inspect errors and resume only after validating the same source/model/prompt checkpoint." if status=="RETRYABLE_FAILED" else
          "Human review is required before publication." if status=="NEEDS_REVIEW" else
          "Resume only from a new explicit dispatcher request." if status=="CHECKPOINTED" else
          "Validate this handoff before requesting the next layer." if status=="COMPLETE" else
          "No automatic retry is scheduled; operator action is required.",
        "publication_allowed":False,"human_review_required":True},
      "layer_statuses":layer_statuses}
    if old_run and all(old_run.get(k)==document.get(k) for k in ("stage","lineage","work","validation","decision","layer_statuses")):
        old_times=old_run.get("timestamps",{})
        if all(old_times.get(k)==times.get(k) for k in ("last_progress_at","checkpoint_at","completed_at","last_error_at","retry_after")):
            return {"changed":False,"status":status,"run_path":run_path.relative_to(root).as_posix(),"current_path":current.relative_to(root).as_posix()}
    payload=json.dumps(document,ensure_ascii=False,indent=2)+"\n"
    for path in (run_path,current):
        tmp=path.with_suffix(path.suffix+".tmp"); tmp.write_text(payload,encoding="utf-8"); tmp.replace(path)
    event={"schema_version":"quantaedge.textbook-pipeline-handoff-event.v1","event_id":document["handoff_id"]+":"+now,
        "occurred_at":now,"stage":stage,"status":status,"reason_code":document["decision"]["reason_code"],
        "processed_count":max(0,processed_count),"expected_count":max(0,expected_count),"failed_count":max(0,failed_count),
        "unresolved_count":unresolved_count,"source_hashes":{x["source_book_id"]:x["source_pdf_sha256"] for x in source_refs},
        "producer_git_sha":os.environ.get("GITHUB_SHA"),"workflow_run_id":str(run_id),
        "artifact_sha256":{x["path"]:x["sha256"] for x in output_refs},"retryable":bool(retryable) and status!="BLOCKED"}
    with events.open("a",encoding="utf-8") as stream:
        stream.write(json.dumps(event,ensure_ascii=False,separators=(",",":"))+"\n")
    return {"changed":True,"status":status,"run_path":run_path.relative_to(root).as_posix(),"current_path":current.relative_to(root).as_posix(),"events_path":event_path,"blockers":sorted(set(problems))}


def record_book_checkpoint(root:Path,slug:str)->list[str]:
    index_path=root/"data"/"textbook-visuals"/slug/"visual-pipeline-index.v1.json"
    if not index_path.is_file(): return []
    index=load_json(index_path); book_id=str(index.get("source_book_id") or "")
    pair=pair_for_book_id(book_id); source=next(x for x in pair_sources(pair) if x["book_id"]==book_id)
    raw_path=_declared(root,index.get("analysis_path"),"raw analysis",[])
    raw={}
    if raw_path:
        try: raw=load_json(raw_path)
        except (OSError,ValueError,json.JSONDecodeError): pass
    summary=raw.get("summary") if isinstance(raw.get("summary"),dict) else {}
    grade=source["class_no"]
    ledger_path=root/"data"/"textbook-content-pipeline"/f"class-{grade}"/"books"/slug/"coverage-ledger.v1.json"
    ledger={}
    try: ledger=load_json(ledger_path)
    except (OSError,ValueError,json.JSONDecodeError): pass
    ledger_summary=ledger.get("summary") if isinstance(ledger.get("summary"),dict) else {}
    stage=5 if ledger_path.is_file() else 4
    complete,reasons=validate_pair_completion(root,pair) if stage==5 else (False,[])
    durations=[float(row.get("duration_seconds",0) or 0) for row in raw.get("results",[]) if isinstance(row,dict)]
    record_handoff(root,pair,stage,"COMPLETE" if stage==5 and complete else "CHECKPOINTED",
      source_book_id=book_id,reason_code="DURABLE_LAYER45_CHECKPOINT",
      message=f"Checkpoint persisted for {book_id}; pair completion requires both language ledgers.",
      model=raw.get("model"),prompt_version=raw.get("prompt_version"),
      processed_count=int(summary.get("assets_analyzed",0) or 0)+int(summary.get("assets_failed",0) or 0),
      expected_count=int(summary.get("assets_eligible",0) or 0),failed_count=int(summary.get("assets_failed",0) or 0),
      unresolved_count=ledger_summary.get("unresolved_count"),completed_batches=1,model_run_seconds=sum(durations))
    base=root/"data"/"textbook-content-pipeline"/"handoffs"/f"class-{grade}"/pair["book_pair_id"]
    paths=[base/"current.v1.json",base/"events.v1.jsonl",base/"runs"/f"run-{os.environ.get('GITHUB_RUN_ID','local')}-attempt-{os.environ.get('GITHUB_RUN_ATTEMPT','1')}.v1.json"]
    return [p.relative_to(root).as_posix() for p in paths if p.is_file()]


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    sub=parser.add_subparsers(dest="command",required=True)
    rec=sub.add_parser("record")
    rec.add_argument("--repo-root",type=Path,default=Path.cwd()); rec.add_argument("--class-no",required=True)
    rec.add_argument("--book-pair-id",required=True); rec.add_argument("--source-book-id")
    rec.add_argument("--stage",type=int,required=True,choices=range(9)); rec.add_argument("--status",required=True,choices=sorted(STATUSES))
    rec.add_argument("--reason-code",default="STATUS_UPDATE"); rec.add_argument("--message",default="")
    rec.add_argument("--retryable",action="store_true"); rec.add_argument("--retry-after")
    rec.add_argument("--model"); rec.add_argument("--prompt-version")
    rec.add_argument("--processed-count",type=int,default=0); rec.add_argument("--expected-count",type=int,default=0)
    rec.add_argument("--failed-count",type=int,default=0); rec.add_argument("--unresolved-count",type=int)
    rec.add_argument("--completed-batches",type=int,default=0); rec.add_argument("--budget",type=int)
    rec.add_argument("--model-startup-seconds",type=float); rec.add_argument("--model-run-seconds",type=float)
    rec.add_argument("--artifact",action="append",default=[])
    args=parser.parse_args(argv)
    try:
        if args.class_no not in {"6","7","8","all"}: raise ValueError("class_no must be 6, 7, 8, or all.")
        pair=pair_for_id(args.book_pair_id)
        if args.class_no!="all" and pair["class_no"]!=args.class_no:
            raise ValueError(f"{pair['book_pair_id']} belongs to Class {pair['class_no']}, not Class {args.class_no}.")
        result=record_handoff(args.repo_root.resolve(),pair,args.stage,args.status,source_book_id=args.source_book_id,
          reason_code=args.reason_code,message=args.message,retryable=args.retryable,retry_after=args.retry_after,
          model=args.model,prompt_version=args.prompt_version,processed_count=args.processed_count,expected_count=args.expected_count,
          failed_count=args.failed_count,unresolved_count=args.unresolved_count,completed_batches=args.completed_batches,budget=args.budget,
          extra_artifacts=args.artifact,model_startup_seconds=args.model_startup_seconds,model_run_seconds=args.model_run_seconds)
        print(json.dumps(result,ensure_ascii=False,indent=2)); return 0 if result["status"]!="BLOCKED" else 2
    except (OSError,ValueError,KeyError,TypeError,json.JSONDecodeError) as exc:
        print(f"Textbook handoff stopped safely: {exc}",file=sys.stderr); return 2


if __name__=="__main__": raise SystemExit(main())
