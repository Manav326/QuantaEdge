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
        status = "COMPLETE"
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
            "blockers": sorted({reason for item in editions for reason in item["blockers"]}),
        }
        pairs.append(pair)
        class_rows[definition["class_no"]].append(pair)

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
        "generated_at": datetime.now(timezone.utc).isoformat(),
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
            if pair["blockers"]:
                lines.append(f"\n> **{pair['subject_book_label']} handoff warnings:** " + "; ".join(pair["blockers"]))
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
