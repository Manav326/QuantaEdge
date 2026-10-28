#!/usr/bin/env python3
"""Report verified Class 6–8 Layer 4–5 progress by bilingual subject/book pair."""
from __future__ import annotations

import argparse
import json
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from scripts.plan_textbook_layer45_matrix import BOOK_PAIRS, BOOKS
from scripts.textbook_pipeline_handoff import validate_book_layer45

SCHEMA = "quantaedge.textbook-bilingual-pair-progress.v1"
VALID_HEX = set("0123456789abcdef")
ANALYZABLE_KINDS = {"embedded_image", "page_preview"}


def read_json(path: Path) -> tuple[dict[str, Any] | None, str | None]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return None, "missing"
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        return None, f"unreadable: {type(exc).__name__}"
    if not isinstance(value, dict):
        return None, "invalid: root must be a JSON object"
    return value, None


def read_json_fields(
    path: Path, keys: list[str], *, max_prefix_bytes: int = 4 * 1024 * 1024
) -> tuple[dict[str, Any], str | None]:
    """Decode only selected fields near the start of a large JSON checkpoint.

    Inventory, raw-analysis and coverage-ledger documents can contain thousands of
    assets/pages. Their identity and summary objects are near the beginning; reading
    those fields avoids loading the entire document just to calculate progress.
    """
    values: dict[str, Any] = {}
    decoder = json.JSONDecoder()
    patterns = {key: re.compile(r'"' + re.escape(key) + r'"\s*:\s*') for key in keys}
    try:
        with path.open("r", encoding="utf-8") as stream:
            prefix = ""
            eof = False
            while len(prefix.encode("utf-8")) < max_prefix_bytes and not eof:
                chunk = stream.read(64 * 1024)
                if not chunk:
                    eof = True
                else:
                    prefix += chunk
                for key in keys:
                    if key in values:
                        continue
                    match = patterns[key].search(prefix)
                    if match is None:
                        continue
                    try:
                        values[key], _ = decoder.raw_decode(prefix, match.end())
                    except json.JSONDecodeError:
                        # The object may be cut at the current read boundary; read more.
                        continue
                if all(key in values for key in keys):
                    return values, None
    except FileNotFoundError:
        return {}, "missing"
    except (OSError, UnicodeError) as exc:
        return {}, f"unreadable: {type(exc).__name__}"
    return values, "required fields missing or beyond the bounded JSON prefix"


def valid_sha(value: Any) -> bool:
    digest = str(value or "").lower()
    return len(digest) == 64 and all(char in VALID_HEX for char in digest)


def book_layer_timings(repo_root: Path, source: dict[str, str], index: dict[str, Any] | None,
                       raw_path: Path | None, raw_fields: dict[str, Any],
                       ledger_fields: dict[str, Any]) -> list[dict[str, Any]]:
    """Read nine layer timing records from small metadata prefixes; do not scan large arrays."""
    grade, book_id, slug = source["class_no"], source["book_id"], source["book_slug"]
    language = "english" if "english" in book_id else "hindi"
    now = datetime.now(timezone.utc)
    pair = next((p for p in BOOK_PAIRS if book_id in p["book_ids_csv"].split(",")), None)
    handoff = {}
    if pair:
        handoff, _ = read_json_fields(
            repo_root / "data" / "textbook-content-pipeline" / "handoffs" /
            f"class-{grade}" / pair["book_pair_id"] / "current.v1.json",
            ["book_layer_statuses", "layer_statuses"], max_prefix_bytes=2 * 1024 * 1024)
    saved_layers = (handoff.get("book_layer_statuses") or {}).get(book_id) or {}
    pair_layers = handoff.get("layer_statuses") or {}
    visual = repo_root / "data" / "textbook-visuals" / slug
    layers = (index or {}).get("layers") or {}

    def make(layer: int, name: str, fallback_status: str, timing: dict[str, Any] | None = None, **metrics: Any) -> dict[str, Any]:
        timing = timing or {}
        saved = saved_layers.get(str(layer)) or pair_layers.get(str(layer)) or {}
        clocks = saved.get("timestamps") or {}
        status = saved.get("status") or timing.get("status") or fallback_status
        started_at = clocks.get("started_at") or timing.get("started_at")
        elapsed = _timing_number(saved.get("cumulative_elapsed_seconds"))
        if elapsed is None:
            elapsed = _timing_number(timing.get("cumulative_elapsed_seconds",
                timing.get("total_elapsed_seconds", timing.get("attempt_elapsed_seconds", timing.get("elapsed_seconds")))))
        attempt = _timing_number(saved.get("attempt_elapsed_seconds"))
        parsed_start = _parse_timing_utc(started_at)
        if status == "RUNNING" and parsed_start:
            attempt = max(0.0, (now - parsed_start).total_seconds())
            elapsed = (elapsed or 0.0) + attempt
        return {
            "layer": layer, "name": name, "status": status,
            "started_at": started_at,
            "completed_at": clocks.get("completed_at") or timing.get("completed_at"),
            "last_progress_at": clocks.get("last_progress_at") or timing.get("last_progress_at"),
            "checkpoint_at": clocks.get("checkpoint_at"), "last_error_at": clocks.get("last_error_at"),
            "retry_after": clocks.get("retry_after"), "actual_elapsed_seconds": elapsed,
            "attempt_elapsed_seconds": attempt, "cumulative_elapsed_seconds": elapsed,
            "model_runtime_seconds": None, "estimated_model_remaining_seconds": None,
            "estimated_total_seconds": None, "estimated_remaining_seconds": None,
            "historical_sample_count": 0, "estimate_basis": "Not enough measured completed runs yet",
            "timing_available": elapsed is not None,
            "timing_basis": timing.get("timing_basis") or (
                "Per-book durable handoff runtime" if elapsed is not None else
                "Legacy artifact has no elapsed timer; runtime is not inferred from file dates"),
            **metrics,
        }

    # Stage 0: find the source-specific extraction manifest; JSONL rows are not re-read here.
    extraction_dir = repo_root / "data" / "textbook-extractions" / f"class-{grade}" / language / "pages"
    manifest = {}
    if extraction_dir.is_dir():
        for p in sorted(extraction_dir.glob("*.manifest.json")):
            meta, _ = read_json_fields(p, ["book_id", "scope", "page_count", "completed_pages", "timing", "created_at_utc"], max_prefix_bytes=256 * 1024)
            if meta.get("book_id") == book_id:
                manifest = meta
                break
    extraction_timing = manifest.get("timing") if isinstance(manifest.get("timing"), dict) else {}
    rows = [make(0, "Source extraction",
        "COMPLETE" if manifest.get("scope") == "SUBJECT_BOOK" else ("NOT_STARTED" if not manifest else "BLOCKED"),
        extraction_timing, pages_total=manifest.get("page_count"), pages_processed=manifest.get("completed_pages"))]

    inventory_path = visual / "visual-inventory.v1.json"
    inv, _ = read_json_fields(inventory_path, ["schema_version", "timing", "source"], max_prefix_bytes=256 * 1024) if inventory_path.is_file() else ({}, "missing")
    rows.append(make(1, "Visual inventory", str(layers.get("1") or ("AVAILABLE_LEGACY" if inv else "NOT_STARTED")),
        inv.get("timing") if isinstance(inv.get("timing"), dict) else {}))

    candidate = repo_root / "data" / "textbook-visual-candidates-v3" / slug
    summary, _ = read_json_fields(candidate / "summary.v3.json", ["summary", "timing"], max_prefix_bytes=256 * 1024) if (candidate / "summary.v3.json").is_file() else ({}, "missing")
    bundle, _ = read_json_fields(candidate / "bundle-index.v3.json", ["layer_timings", "timing"], max_prefix_bytes=512 * 1024) if (candidate / "bundle-index.v3.json").is_file() else ({}, "missing")
    bundle_times = bundle.get("layer_timings") or {}
    candidates = summary.get("summary") or {}
    rows.append(make(2, "Visual candidate preparation", "AVAILABLE_LEGACY" if summary else "NOT_STARTED",
        bundle_times.get("2") or summary.get("timing") or {},
        units_total=candidates.get("candidate_pool_count"), units_processed=candidates.get("selected_candidate_count")))
    catalog = candidate.parent / "catalogs" / f"{slug}.v3.json"
    rows.append(make(3, "Candidate catalogue / attachment handoff",
        "AVAILABLE_LEGACY" if catalog.is_file() else "NOT_STARTED", bundle_times.get("3") or {}))

    raw_meta, _ = read_json_fields(raw_path, ["started_at", "attempt_started_at", "completed_at", "last_progress_at",
        "elapsed_seconds_this_attempt", "estimated_model_seconds_remaining", "summary", "layer_status"],
        max_prefix_bytes=512 * 1024) if raw_path and raw_path.is_file() else ({}, "missing")
    raw_summary = raw_meta.get("summary") if isinstance(raw_meta.get("summary"), dict) else raw_fields.get("summary") or {}
    raw_timing = {"started_at": raw_meta.get("attempt_started_at") or raw_meta.get("started_at"),
        "completed_at": raw_meta.get("completed_at"), "last_progress_at": raw_meta.get("last_progress_at"),
        "elapsed_seconds": raw_meta.get("elapsed_seconds_this_attempt"),
        "timing_basis": "Measured attempt wall time in raw-analysis checkpoint"}
    rows.append(make(4, "Raw visual analysis",
        str(layers.get("4") or raw_meta.get("layer_status") or ("IN_PROGRESS" if raw_fields else "NOT_STARTED")),
        raw_timing, assets_total=raw_summary.get("assets_eligible"), assets_processed=raw_summary.get("assets_analyzed"),
        assets_remaining=raw_summary.get("assets_remaining"),
        model_runtime_seconds=_timing_number(raw_summary.get("total_model_seconds_recorded")),
        estimated_model_remaining_seconds=_timing_number(raw_meta.get("estimated_model_seconds_remaining",
            raw_summary.get("estimated_model_seconds_remaining"))),
        duration_sample_count=raw_summary.get("duration_sample_count", 0)))

    ledger_path = repo_root / "data" / "textbook-content-pipeline" / f"class-{grade}" / "books" / slug / "coverage-ledger.v1.json"
    ledger_meta, _ = read_json_fields(ledger_path, ["timing"], max_prefix_bytes=512 * 1024) if ledger_path.is_file() else ({}, "missing")
    ledger_summary = (ledger_fields or {}).get("summary") or {}
    layer5_artifact_timing=ledger_meta.get("timing") if isinstance(ledger_meta.get("timing"),dict) else {}
    layer5_row=make(5, "Effective analysis / coverage ledger",
        str(layers.get("5") or layer5_artifact_timing.get("status") or (ledger_fields or {}).get("ledger_status") or "NOT_STARTED"),
        layer5_artifact_timing,
        eligible_visuals=ledger_summary.get("eligible_visual_asset_rows"),
        successful_visuals=ledger_summary.get("successful_visual_analyses"),
        unresolved_count=ledger_summary.get("unresolved_count"))
    # Layer 5's own elapsed timer measures effective-analysis/ledger reconciliation;
    # its handoff status clock can overlap Layer 4, so show the direct measured duration.
    if layer5_artifact_timing:
        direct=_timing_number(layer5_artifact_timing.get("attempt_elapsed_seconds",
            layer5_artifact_timing.get("elapsed_seconds")))
        if direct is not None:
            layer5_row["actual_elapsed_seconds"]=direct
            layer5_row["attempt_elapsed_seconds"]=direct
            layer5_row["cumulative_elapsed_seconds"]=_timing_number(layer5_artifact_timing.get("total_elapsed_seconds")) or direct
            layer5_row["timing_available"]=True
            layer5_row["timing_basis"]="Direct monotonic timer inside Layer 5 effective-analysis/coverage-ledger reconciliation"
    rows.append(layer5_row)

    l68_root = repo_root / "data" / "textbook-content-pipeline" / f"class-{grade}" / "books" / slug / "layers-6-8"
    manifest68 = {}
    if l68_root.is_dir():
        for p in sorted(l68_root.glob("*/layers-6-8-run-manifest.v1.json"), key=lambda p: p.stat().st_mtime, reverse=True):
            meta, _ = read_json_fields(p, ["source_book_id", "source_pdf_sha256", "layers", "layer_timings", "timing", "segments_total", "segments_completed"], max_prefix_bytes=1024 * 1024)
            if meta.get("source_book_id") == book_id and str(meta.get("source_pdf_sha256") or "").lower() == str((index or {}).get("source_pdf_sha256") or "").lower():
                manifest68 = meta
                break
    l68_status = manifest68.get("layers") or {}
    l68_times = manifest68.get("layer_timings") or {}
    for layer, name in ((6,"Curriculum/concept mapping"),(7,"Structured lesson/question generation"),(8,"Tutor retrieval/training material")):
        explicit = l68_times.get(str(layer)) or manifest68.get("timing") or {}
        status = str(l68_status.get(str(layer)) or ("NOT_STARTED" if not manifest68 else "AVAILABLE_LEGACY"))
        rows.append(make(layer, name, status, explicit, segments_total=manifest68.get("segments_total"),
            segments_completed=manifest68.get("segments_completed"),
            shared_runtime_across_layers_6_8=not bool(l68_times.get(str(layer)))))
    return rows


def book_progress(repo_root: Path, source: dict[str, str]) -> dict[str, Any]:
    grade, book_id, slug = source["class_no"], source["book_id"], source["book_slug"]
    visual_dir = repo_root / "data" / "textbook-visuals" / slug
    inventory_path = visual_dir / "visual-inventory.v1.json"
    index_path = visual_dir / "visual-pipeline-index.v1.json"
    ledger_path = (
        repo_root / "data" / "textbook-content-pipeline" / f"class-{grade}"
        / "books" / slug / "coverage-ledger.v1.json"
    )

    # The index is small. Read identity/summary fields only from large checkpoint JSONs.
    index, index_error = read_json(index_path)
    ledger_fields, ledger_error = read_json_fields(
        ledger_path, ["schema_version", "ledger_status", "source", "summary"]
    )
    inventory_present = inventory_path.is_file()
    blockers: list[str] = []
    if index_error not in (None, "missing"):
        blockers.append("pipeline index is unreadable")
    if ledger_error not in (None, "missing"):
        blockers.append("coverage ledger is unreadable or its summary is beyond the bounded prefix")
    existing = inventory_present or index_path.exists() or ledger_path.exists()
    expected_sha = ""
    raw_path: Path | None = None
    raw_fields: dict[str, Any] = {}
    raw_error: str | None = "missing"

    if index is not None:
        if index.get("source_book_id") != book_id:
            blockers.append("pipeline index book ID does not match the planner")
        expected_sha = str(index.get("source_pdf_sha256") or "").lower()
        if not valid_sha(expected_sha):
            blockers.append("pipeline index has no valid source PDF SHA-256")
        declared_path = str(index.get("analysis_path") or "")
        path_parts = Path(declared_path).parts
        if (
            not declared_path.startswith("data/textbook-visuals/")
            or Path(declared_path).is_absolute()
            or ".." in path_parts
        ):
            blockers.append("pipeline index has an unsafe or invalid analysis path")
        else:
            raw_path = repo_root / declared_path
            raw_fields, raw_error = read_json_fields(
                raw_path, ["schema_version", "source_book_id", "source_pdf_sha256", "summary"]
            )
            if raw_fields and raw_fields.get("source_book_id") != book_id:
                blockers.append("raw analysis identity does not match its source-book ID")
            if raw_fields and str(raw_fields.get("source_pdf_sha256") or "").lower() != expected_sha:
                blockers.append("raw analysis PDF hash differs from the pipeline index")

    if not inventory_present and existing:
        blockers.append("visual inventory file is missing")

    ledger_source = ledger_fields.get("source") or {}
    ledger_summary = ledger_fields.get("summary") or {}
    if ledger_fields:
        if ledger_fields.get("schema_version") != "quantaedge.textbook-coverage-ledger.v1":
            blockers.append("coverage ledger schema mismatch")
        if ledger_source.get("source_book_id") != book_id:
            blockers.append("coverage ledger book ID does not match the planner")
        ledger_sha = str(ledger_source.get("source_pdf_sha256") or "").lower()
        if expected_sha and ledger_sha != expected_sha:
            blockers.append("coverage ledger PDF hash differs from the pipeline index")
        expected_ledger_rel = ledger_path.relative_to(repo_root).as_posix()
        if index is not None and str(index.get("coverage_ledger_path") or "") != expected_ledger_rel:
            blockers.append("pipeline index points to a different coverage ledger")
    elif ledger_error not in (None, "missing"):
        blockers.append("coverage ledger is unreadable or its summary is beyond the bounded prefix")
    if ledger_error == "missing" and index is not None and (index.get("layers") or {}).get("5") == "COMPLETE":
        blockers.append("pipeline index marks Layer 5 complete but its coverage ledger is missing")
    if index is None and index_error == "missing" and ledger_fields.get("ledger_status") == "COMPLETE":
        blockers.append("coverage ledger claims completion but the pipeline index is missing")

    raw_summary = raw_fields.get("summary") or {}
    eligible = raw_summary.get("assets_eligible")
    if not isinstance(eligible, int) or eligible < 0:
        eligible = ledger_summary.get("eligible_visual_asset_rows", 0)
    eligible = eligible if isinstance(eligible, int) and eligible >= 0 else 0
    analyzed = raw_summary.get("assets_analyzed")
    if not isinstance(analyzed, int) or analyzed < 0:
        analyzed = ledger_summary.get("successful_visual_analyses", 0)
    analyzed = analyzed if isinstance(analyzed, int) and analyzed >= 0 else 0
    failed = raw_summary.get("assets_failed")
    if not isinstance(failed, int) or failed < 0:
        failed = ledger_summary.get("failed_visual_analyses", 0)
    failed = failed if isinstance(failed, int) and failed >= 0 else 0
    remaining = raw_summary.get("assets_remaining")
    if not isinstance(remaining, int) or remaining < 0:
        remaining = max(0, eligible - analyzed)
    percent = raw_summary.get("assets_completion_percent")
    if not isinstance(percent, (int, float)):
        percent = round(100 * analyzed / eligible, 1) if eligible else 0.0
    percent = max(0.0, min(100.0, float(percent)))

    layers = (index or {}).get("layers") or {}
    ledger_complete = (
        bool(index)
        and bool(ledger_fields)
        and inventory_present
        and bool(raw_fields)
        and ledger_fields.get("ledger_status") == "COMPLETE"
        and ledger_summary.get("unresolved_count") == 0
        and ledger_source.get("source_book_id") == book_id
        and valid_sha(expected_sha)
        and str(ledger_source.get("source_pdf_sha256") or "").lower() == expected_sha
        and index.get("source_book_id") == book_id
        and str(index.get("source_pdf_sha256") or "").lower() == expected_sha
        and raw_fields.get("source_book_id") == book_id
        and str(raw_fields.get("source_pdf_sha256") or "").lower() == expected_sha
        and layers.get("5") == "COMPLETE"
        and str(index.get("coverage_ledger_path") or "")
            == ledger_path.relative_to(repo_root).as_posix()
        and not blockers
    )

    if ledger_complete:
        strict_ok, strict_reasons, _details = validate_book_layer45(repo_root, source)
        if strict_ok:
            status = "COMPLETE"
        else:
            blockers.extend(strict_reasons)
            ledger_complete = False
            status = "BLOCKED"
    elif blockers:
        status = "BLOCKED"
    elif existing or raw_fields or inventory_present:
        status = "IN_PROGRESS"
    else:
        status = "NOT_STARTED"

    if raw_path is not None and raw_path.exists() and raw_error not in (None, "missing"):
        blockers.append("raw analysis checkpoint is unreadable or its summary is beyond the bounded prefix")
    if raw_error == "missing" and index is not None and (index.get("layers") or {}).get("5") == "COMPLETE":
        blockers.append("pipeline index marks Layer 5 complete but its raw analysis checkpoint is missing")
    if blockers and status != "COMPLETE":
        status = "BLOCKED"

    return {
        "class_no": grade,
        "book_id": book_id,
        "book_slug": slug,
        "language": "english" if "english" in book_id else "hindi",
        "subject": "science" if "-science-" in slug else "maths" if "maths" in slug else "unknown",
        "maths_part": 2 if "maths-part2" in slug else 1 if "maths" in slug else None,
        "status": status,
        "layers": {
            "inventory": "AVAILABLE" if inventory_present else "MISSING",
            "layer4": str(layers.get("4") or ("AVAILABLE" if raw_fields else "NOT_STARTED")),
            "layer5": str(layers.get("5") or ledger_fields.get("ledger_status") or "NOT_STARTED"),
        },
        "analysis": {
            "eligible_assets": eligible,
            "analyzed_assets": min(analyzed, eligible) if eligible else analyzed,
            "failed_assets": failed,
            "remaining_assets": remaining,
            "completion_percent": percent,
        },
        "coverage": {
            "ledger_status": ledger_fields.get("ledger_status", "MISSING"),
            "unresolved_count": ledger_summary.get("unresolved_count"),
            "verified_complete": ledger_complete,
        },
        "source_pdf_sha256": expected_sha or None,
        "layer_timings": book_layer_timings(repo_root, source, index, raw_path, raw_fields, ledger_fields),
        "blockers": sorted(set(blockers)),
    }

def build_report(repo_root: Path) -> dict[str, Any]:
    repo_root = Path(repo_root).resolve()
    source_by_id = {source["book_id"]: source for source in BOOKS}
    pairs: list[dict[str, Any]] = []
    class_rows: dict[str, list[dict[str, Any]]] = {"6": [], "7": [], "8": []}

    for definition in BOOK_PAIRS:
        expected_ids = [value.strip() for value in definition["book_ids_csv"].split(",")]
        if len(expected_ids) != 2 or any(value not in source_by_id for value in expected_ids):
            raise ValueError(f"Planner pair has invalid book ID mapping: {definition['book_pair_id']}")
        editions = [book_progress(repo_root, source_by_id[book_id]) for book_id in expected_ids]
        eligible = sum(item["analysis"]["eligible_assets"] for item in editions)
        analyzed = sum(item["analysis"]["analyzed_assets"] for item in editions)
        failed = sum(item["analysis"]["failed_assets"] for item in editions)
        remaining = sum(item["analysis"]["remaining_assets"] for item in editions)
        percent = round(100 * analyzed / eligible, 1) if eligible else 0.0
        if all(item["status"] == "COMPLETE" for item in editions):
            status = "COMPLETE"
        elif any(item["status"] == "BLOCKED" for item in editions):
            status = "BLOCKED"
        elif any(item["status"] != "NOT_STARTED" for item in editions):
            status = "IN_PROGRESS"
        else:
            status = "NOT_STARTED"
        handoff_path = repo_root / "data" / "textbook-content-pipeline" / "handoffs" / f"class-{definition['class_no']}" / definition["book_pair_id"] / "current.v1.json"
        handoff = {}
        try:
            handoff = json.loads(handoff_path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError):
            handoff = {}
        handoff_times = handoff.get("timestamps") or {}
        handoff_work = handoff.get("work") or {}
        handoff_decision = handoff.get("decision") or {}
        pair = {
            "class_no": definition["class_no"],
            "book_pair_id": definition["book_pair_id"],
            "subject_book_label": definition["book_label"],
            "status": status,
            "languages": editions,
            "combined_analysis": {
                "eligible_assets": eligible,
                "analyzed_assets": analyzed,
                "failed_assets": failed,
                "remaining_assets": remaining,
                "completion_percent": percent,
            },
            "verified_language_editions": sum(item["status"] == "COMPLETE" for item in editions),
            "required_language_editions": 2,
            "blockers": sorted(set({reason for item in editions for reason in item["blockers"]} | set((handoff.get("validation") or {}).get("blockers") or []))),
            "handoff": {
                "status": handoff_decision.get("status", "NOT_RECORDED"),
                "pipeline_run_id": handoff.get("pipeline_run_id"),
                "stage": (handoff.get("stage") or {}).get("layer"),
                "timestamps": handoff_times,
                "work": handoff_work,
                "reason_code": handoff_decision.get("reason_code"),
                "retryable": handoff_decision.get("retryable", False),
                "next_action": handoff_decision.get("next_action"),
                "event_log_path": (handoff.get("lineage") or {}).get("append_only_event_log"),
            },
        }
        pairs.append(pair)
        class_rows[definition["class_no"]].append(pair)

    # Calibrate expected duration from completed measured runs only. Two samples are
    # the minimum for an ETA; legacy file dates are never treated as runtime.
    timing_samples: dict[int, list[float]] = {layer: [] for layer in range(9)}
    for pair in pairs:
        for edition in pair["languages"]:
            for timing in edition.get("layer_timings", []):
                layer = timing.get("layer")
                elapsed = timing.get("actual_elapsed_seconds")
                if not isinstance(layer, int) or not isinstance(elapsed, (int, float)) or elapsed <= 0:
                    continue
                if timing.get("status") not in {"COMPLETE", "NEEDS_REVIEW"}:
                    continue
                if timing.get("shared_runtime_across_layers_6_8") and layer != 7:
                    continue
                timing_samples[layer].append(float(elapsed))
    medians: dict[int, float] = {}
    for layer, values in timing_samples.items():
        values.sort()
        if len(values) >= 2:
            mid = len(values) // 2
            medians[layer] = values[mid] if len(values) % 2 else (values[mid - 1] + values[mid]) / 2
    for pair in pairs:
        for edition in pair["languages"]:
            for timing in edition.get("layer_timings", []):
                layer = timing["layer"]
                elapsed = timing.get("actual_elapsed_seconds")
                status = timing.get("status")
                samples = timing_samples[layer]
                expected = float(elapsed) if status in {"COMPLETE", "NEEDS_REVIEW"} and isinstance(elapsed, (int, float)) else medians.get(layer)
                progress = 0.0
                for completed_key, total_key in (
                    ("pages_processed", "pages_total"), ("units_processed", "units_total"),
                    ("assets_processed", "assets_total"), ("successful_visuals", "eligible_visuals"),
                    ("segments_completed", "segments_total")
                ):
                    total_count = timing.get(total_key)
                    completed_count = timing.get(completed_key)
                    if isinstance(total_count, (int, float)) and total_count > 0 and isinstance(completed_count, (int, float)):
                        progress = max(progress, min(1.0, float(completed_count) / float(total_count)))
                timing["estimated_total_seconds"] = round(expected, 1) if expected is not None else None
                timing["estimated_remaining_seconds"] = (
                    0.0 if status in {"COMPLETE", "NEEDS_REVIEW"} else round(max(0.0, expected * (1.0 - progress)), 1)
                ) if expected is not None else None
                timing["historical_sample_count"] = len(samples)
                timing["estimate_basis"] = (
                    "Measured duration for this source book/layer"
                    if status in {"COMPLETE", "NEEDS_REVIEW"} and isinstance(elapsed, (int, float))
                    else f"Median of {len(samples)} measured completed runs; estimate only"
                    if len(samples) >= 2
                    else "Not enough measured completed runs; estimate withheld"
                )
                if layer == 4 and timing.get("estimated_model_remaining_seconds") is not None:
                    timing["model_eta_basis"] = "Sum of per-asset model seconds; not wall-clock remaining time"

    classes = []
    for grade in ("6", "7", "8"):
        items = class_rows[grade]
        eligible = sum(item["combined_analysis"]["eligible_assets"] for item in items)
        analyzed = sum(item["combined_analysis"]["analyzed_assets"] for item in items)
        failed = sum(item["combined_analysis"]["failed_assets"] for item in items)
        remaining = sum(item["combined_analysis"]["remaining_assets"] for item in items)
        if items and all(item["status"] == "COMPLETE" for item in items):
            status = "COMPLETE"
        elif any(item["status"] == "BLOCKED" for item in items):
            status = "BLOCKED"
        elif any(item["status"] != "NOT_STARTED" for item in items):
            status = "IN_PROGRESS"
        else:
            status = "NOT_STARTED"
        classes.append({
            "class_no": grade,
            "status": status,
            "subject_book_pairs_complete": sum(item["status"] == "COMPLETE" for item in items),
            "subject_book_pairs_total": len(items),
            "language_editions_complete": sum(item["verified_language_editions"] for item in items),
            "language_editions_total": sum(item["required_language_editions"] for item in items),
            "combined_analysis": {
                "eligible_assets": eligible,
                "analyzed_assets": analyzed,
                "failed_assets": failed,
                "remaining_assets": remaining,
                "completion_percent": round(100 * analyzed / eligible, 1) if eligible else 0.0,
            },
            "pairs": items,
        })

    total_eligible = sum(item["combined_analysis"]["eligible_assets"] for item in pairs)
    total_analyzed = sum(item["combined_analysis"]["analyzed_assets"] for item in pairs)
    return {
        "schema_version": SCHEMA,
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
        "as_of": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
        "scope": "NCERT whole-book Layer 4–5; English and Hindi tracked together by subject/book pair",
        "publication_allowed": False,
        "review_required": True,
        "overall": {
            "status": (
                "COMPLETE" if all(item["status"] == "COMPLETE" for item in pairs)
                else "BLOCKED" if any(item["status"] == "BLOCKED" for item in pairs)
                else "IN_PROGRESS" if any(item["status"] != "NOT_STARTED" for item in pairs)
                else "NOT_STARTED"
            ),
            "subject_book_pairs_complete": sum(item["status"] == "COMPLETE" for item in pairs),
            "subject_book_pairs_total": len(pairs),
            "language_editions_complete": sum(item["verified_language_editions"] for item in pairs),
            "language_editions_total": sum(item["required_language_editions"] for item in pairs),
            "eligible_assets": total_eligible,
            "analyzed_assets": total_analyzed,
            "completion_percent": round(100 * total_analyzed / total_eligible, 1) if total_eligible else 0.0,
        },
        "classes": classes,
    }


def render_markdown(report: dict[str, Any]) -> str:
    overall = report["overall"]
    lines = [
        "# Class 6–8 English + Hindi Textbook Progress",
        "",
        f"- Generated: {report['generated_at']}",
        f"- Overall status: **{overall['status']}**",
        f"- Subject/book pairs verified complete: {overall['subject_book_pairs_complete']}/{overall['subject_book_pairs_total']}",
        f"- Language editions verified complete: {overall['language_editions_complete']}/{overall['language_editions_total']}",
        f"- Visual analysis: {overall['analyzed_assets']:,}/{overall['eligible_assets']:,} assets ({overall['completion_percent']:.1f}%)",
        "- Publication: disabled; outputs remain review-only.",
        "",
    ]
    for class_item in report["classes"]:
        metrics = class_item["combined_analysis"]
        lines.extend([
            f"## Class {class_item['class_no']} — {class_item['status']}",
            "",
            f"Pairs complete: {class_item['subject_book_pairs_complete']}/{class_item['subject_book_pairs_total']} · "
            f"Language editions complete: {class_item['language_editions_complete']}/{class_item['language_editions_total']} · "
            f"Combined visual analysis: {metrics['analyzed_assets']:,}/{metrics['eligible_assets']:,} "
            f"({metrics['completion_percent']:.1f}%)",
            "",
            "| Subject / book pair | Pair status | English edition | Hindi edition | Combined visual analysis | Failed assets |",
            "|---|---|---:|---:|---:|---:|",
        ])
        for pair in class_item["pairs"]:
            editions = {item["language"]: item for item in pair["languages"]}
            def edition_summary(item: dict[str, Any]) -> str:
                a = item["analysis"]
                if item["status"] == "NOT_STARTED" and a["eligible_assets"] == 0:
                    return "Not started"
                return f"{a['analyzed_assets']:,}/{a['eligible_assets']:,} ({a['completion_percent']:.1f}%) · {item['status']}"
            combined = pair["combined_analysis"]
            lines.append(
                f"| {pair['subject_book_label']} | {pair['status']} | "
                f"{edition_summary(editions['english'])} | {edition_summary(editions['hindi'])} | "
                f"{combined['analyzed_assets']:,}/{combined['eligible_assets']:,} "
                f"({combined['completion_percent']:.1f}%) · {combined['remaining_assets']:,} remaining | "
                f"{combined['failed_assets']:,} |"
            )
            handoff = pair.get("handoff") or {}
            timing = handoff.get("timestamps") or {}
            lines.append(
                f"- Handoff: {handoff.get('status', 'NOT_RECORDED')} · layer {handoff.get('stage') or '—'} · "
                f"run {handoff.get('pipeline_run_id') or '—'} · last progress {timing.get('last_progress_at') or '—'} · "
                f"checkpoint {timing.get('checkpoint_at') or '—'} · last error {timing.get('last_error_at') or '—'} · "
                f"retry after {timing.get('retry_after') or '—'}"
            )
            if pair["blockers"]:
                lines.append(f"\n> **{pair['subject_book_label']} handoff warnings:** " + "; ".join(pair["blockers"]))
            lines.extend([
                "",
                "**Per-source-book, per-layer timing**",
                "",
                "| Edition | Source book ID | Layer | Status | Actual elapsed | Expected total | Remaining | Last progress (UTC) | Model ETA* |",
                "|---|---|---|---|---:|---:|---:|---|---:|",
            ])
            def _duration_text(value):
                if not isinstance(value, (int, float)) or isinstance(value, bool):
                    return "Not recorded"
                seconds = max(0, int(value))
                hours, rest = divmod(seconds, 3600)
                minutes, seconds = divmod(rest, 60)
                return f"{hours:02d}:{minutes:02d}:{seconds:02d}"
            for language in ("english", "hindi"):
                edition = editions[language]
                for timing in edition.get("layer_timings", []):
                    lines.append(
                        f"| {language.capitalize()} | {edition['book_id']} | L{timing['layer']} {timing['name']} | "
                        f"{timing['status']} | {_duration_text(timing.get('actual_elapsed_seconds'))} | "
                        f"{_duration_text(timing.get('estimated_total_seconds'))} | "
                        f"{_duration_text(timing.get('estimated_remaining_seconds'))} | "
                        f"{timing.get('last_progress_at') or '—'} | "
                        f"{_duration_text(timing.get('estimated_model_remaining_seconds'))} |"
                    )
            lines.append("*Model ETA is measured in model-seconds, not wall-clock time. Layers 6–8 share runtime unless separate sub-layer timings are present; unknown historical durations remain “Not recorded.”")
        lines.append("")
    lines.extend([
        "## Completion rule",
        "",
        "A subject/book pair is complete only when both language editions have source-matched Layer 5 ledgers with COMPLETE status, zero unresolved items, and matching pipeline indexes with Layer 5 marked COMPLETE. Raw analysis progress alone never marks a pair complete.",
        "",
    ])
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path.cwd())
    parser.add_argument("--json-out", type=Path, required=True)
    parser.add_argument("--markdown-out", type=Path, required=True)
    args = parser.parse_args(argv)
    report = build_report(args.repo_root)
    outputs = (
        (args.json_out, json.dumps(report, ensure_ascii=False, indent=2) + "\n"),
        (args.markdown_out, render_markdown(report)),
    )
    for output, text_value in outputs:
        output.parent.mkdir(parents=True, exist_ok=True)
        temporary = output.with_suffix(output.suffix + ".tmp")
        temporary.write_text(text_value, encoding="utf-8")
        temporary.replace(output)
    print(json.dumps({
        "schema_version": report["schema_version"],
        "overall": report["overall"],
        "class_statuses": [{"class_no": c["class_no"], "status": c["status"]} for c in report["classes"]],
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
