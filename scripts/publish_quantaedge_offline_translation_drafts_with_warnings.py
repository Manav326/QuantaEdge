#!/usr/bin/env python3
"""Publish only flagged English translation drafts with explicit quality warnings.

This script is intentionally separate from the clean-draft publisher. It only accepts
created_draft_needs_review rows from one successful offline import report. Warnings are
persisted in translation quality_notes before publication so the Admin translations queue
continues to flag them for follow-up. Quality notes are internal admin metadata; they are
not added to student-facing translated content.
"""
from __future__ import annotations

import argparse
import getpass
import json
import os
import re
import sys
import time
from collections import Counter
from datetime import datetime, timezone
from http.cookies import SimpleCookie
from pathlib import Path
from typing import Any
from urllib.parse import urlencode

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT / "scripts") not in sys.path:
    sys.path.insert(0, str(ROOT / "scripts"))
from import_site_content_draft import AdminApi

ALLOWED_ENTITY_TYPES = {"curriculum_chapter", "question"}
FLOW_STATES = {"DRAFT", "IN_REVIEW", "APPROVED", "PUBLISHED"}
NEXT_STATE = {"DRAFT": "IN_REVIEW", "IN_REVIEW": "APPROVED", "APPROVED": "PUBLISHED"}
FLAG_PREFIX = "PUBLISHED WITH QUALITY WARNINGS"


def load_targets(path: Path, expected_count: int) -> list[dict[str, Any]]:
    payload = json.loads(path.read_text(encoding="utf-8-sig"))
    if payload.get("schema") != "quantaedge.offline-translation-import-report.v2":
        raise ValueError("The selected file is not an offline translation importer v2 report.")
    if payload.get("mode") != "APPLY_DRAFTS" or payload.get("status") != "SUCCEEDED":
        raise ValueError("Select a successful APPLY_DRAFTS import report.")
    results = payload.get("results")
    if not isinstance(results, list):
        raise ValueError("The selected report has no valid results list.")

    targets: list[dict[str, Any]] = []
    seen_translation_ids: set[tuple[str, int]] = set()
    seen_entity_ids: set[tuple[str, int]] = set()
    problems: list[str] = []
    for row in results:
        if not isinstance(row, dict) or row.get("status") != "created_draft_needs_review":
            continue
        response = row.get("api_response")
        issues = row.get("quality_issues")
        if not isinstance(response, dict):
            problems.append("review-required row has no api_response")
            continue
        if not isinstance(issues, list) or not issues:
            problems.append("review-required row has no quality_issues list")
            continue
        try:
            translation_id = int(response.get("translationId"))
            entity_id = int(response.get("entityId", row.get("entity_id")))
        except (TypeError, ValueError):
            problems.append("review-required row has invalid translationId/entityId")
            continue
        entity_type = str(response.get("entityType", row.get("entity_type", ""))).strip()
        language = str(response.get("language", "")).strip().lower()
        state = str(response.get("translationStatus", "")).strip().upper()
        if entity_type not in ALLOWED_ENTITY_TYPES:
            problems.append("unsupported entity type " + repr(entity_type))
            continue
        if language != "en" or state != "DRAFT":
            problems.append(f"{entity_type} translation {translation_id} is not an English DRAFT")
            continue
        if translation_id <= 0 or entity_id <= 0:
            problems.append("non-positive translation/entity id")
            continue
        tkey = (entity_type, translation_id)
        ekey = (entity_type, entity_id)
        if tkey in seen_translation_ids or ekey in seen_entity_ids:
            problems.append(f"duplicate target {entity_type} translation={translation_id} entity={entity_id}")
            continue
        seen_translation_ids.add(tkey)
        seen_entity_ids.add(ekey)
        targets.append({
            "translation_id": translation_id,
            "entity_type": entity_type,
            "entity_id": entity_id,
            "language": language,
            "class_code": row.get("class_code"),
            "subject_code": row.get("subject_code"),
            "chapter_code": row.get("chapter_code"),
            "quality_issues": [str(issue) for issue in issues],
        })

    if problems:
        raise ValueError("Report validation failed: " + "; ".join(problems[:10]))
    if expected_count <= 0:
        raise ValueError("--expected-count must be positive.")
    if len(targets) != expected_count:
        raise ValueError(
            f"Expected exactly {expected_count:,} review-required drafts, found {len(targets):,}. "
            "No status changes were made."
        )
    return targets


def normalize_state(row: dict[str, Any]) -> str:
    return str(row.get("translation_status", row.get("translationStatus", ""))).strip().upper()


def get_staff_api(base_url: str, timeout: int) -> AdminApi:
    session = (os.environ.get("QUANTAEDGE_ADMIN_SESSION", "").strip()
               or os.environ.get("QE_STAFF_SESSION", "").strip())
    refresh = (os.environ.get("QUANTAEDGE_ADMIN_REFRESH", "").strip()
               or os.environ.get("QE_STAFF_REFRESH", "").strip())
    if not session:
        session = getpass.getpass("Paste QE_STAFF_SESSION cookie value (input hidden): ").strip()
    if not refresh:
        refresh = getpass.getpass(
            "Paste paired QE_STAFF_REFRESH cookie value (input hidden; enables renewal): "
        ).strip()
    if not session or not refresh:
        raise ValueError("Both current QE_STAFF_SESSION and paired QE_STAFF_REFRESH are required.")
    os.environ["QE_STAFF_SESSION"] = session
    os.environ["QE_STAFF_REFRESH"] = refresh
    os.environ["QUANTAEDGE_ADMIN_SESSION"] = session
    os.environ["QUANTAEDGE_ADMIN_REFRESH"] = refresh
    return AdminApi(base_url, session, timeout)


def fetch_current_rows(
    api: AdminApi, targets: list[dict[str, Any]]
) -> dict[tuple[str, int], dict[str, Any]]:
    ids_by_type: dict[str, set[int]] = {}
    for target in targets:
        ids_by_type.setdefault(target["entity_type"], set()).add(target["translation_id"])
    found: dict[tuple[str, int], dict[str, Any]] = {}
    for entity_type, target_ids in ids_by_type.items():
        offset = 0
        while True:
            query = urlencode({
                "language": "en", "status": "ALL", "entityType": entity_type,
                "limit": 250, "offset": offset,
            })
            result = api.request("GET", "/api/v1/admin/translations?" + query)
            if not isinstance(result, dict) or not isinstance(result.get("items"), list):
                raise RuntimeError(f"Unexpected translation list response for {entity_type}.")
            items = result["items"]
            for item in items:
                try:
                    translation_id = int(item.get("translation_id", item.get("translationId")))
                except (TypeError, ValueError):
                    continue
                if translation_id in target_ids:
                    found[(entity_type, translation_id)] = item
            total = int(result.get("total", 0))
            offset += len(items)
            if not items or offset >= total:
                break
    return found


def warning_notes(target: dict[str, Any], existing: Any) -> str:
    issues = "; ".join(dict.fromkeys(target["quality_issues"]))
    prior = str(existing or "").strip()
    note = (
        FLAG_PREFIX + " — follow-up correction required; this translation was not fully reviewed. "
        "Importer flags: " + issues + "."
    )
    if prior and FLAG_PREFIX.lower() not in prior.lower():
        note += " Previous quality notes: " + prior
    return note[:2000]


def write_report(path: Path, payload: dict[str, Any]) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(path)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", required=True, type=Path, help="Exact successful import report")
    parser.add_argument("--expected-count", required=True, type=int,
                        help="Exact count of created_draft_needs_review records to target")
    parser.add_argument("--confirm-publish-count", type=int, default=None,
                        help="Required with --apply; must equal the validated target count")
    parser.add_argument("--api-base-url", default="http://localhost:8080")
    parser.add_argument("--timeout", type=int, default=30)
    parser.add_argument("--delay", type=float, default=0.01)
    parser.add_argument("--apply", action="store_true", help="Perform status changes; default is preview only")
    args = parser.parse_args()
    if not args.api_base_url.startswith(("http://localhost", "http://127.0.0.1")):
        parser.error("This publisher is restricted to the local QuantaEdge API.")
    if args.expected_count <= 0 or args.timeout <= 0 or args.delay < 0:
        parser.error("expected-count/timeout must be positive and delay must be non-negative.")

    try:
        targets = load_targets(args.report, args.expected_count)
        issue_counts = Counter(issue for target in targets for issue in target["quality_issues"])
        print("Import report: " + str(args.report))
        print(f"Flagged English drafts validated: {len(targets):,}")
        print("By entity type: " + json.dumps(dict(Counter(t["entity_type"] for t in targets)), sort_keys=True))
        print("Quality issue counts: " + json.dumps(dict(issue_counts.most_common()), ensure_ascii=False))
        print("Scope: ONLY created_draft_needs_review records; clean drafts and skipped records are excluded.")
        print("Flag destination: Admin translation quality notes. These flags are not student-facing.")
        if not args.apply:
            print("DRY RUN ONLY. No API calls made and no statuses changed.")
            print("To apply, add --apply --confirm-publish-count " + str(len(targets)))
            return 0
        if args.confirm_publish_count != len(targets):
            raise ValueError(
                f"--apply requires --confirm-publish-count {len(targets)} to match the exact target count."
            )

        api = get_staff_api(args.api_base_url, args.timeout)
        print("Preflighting all targets, permissions-visible states, and source revisions before any writes...")
        current_rows = fetch_current_rows(api, targets)
        errors: list[str] = []
        for target in targets:
            key = (target["entity_type"], target["translation_id"])
            row = current_rows.get(key)
            label = f"{target['entity_type']} translation {target['translation_id']}"
            if row is None:
                errors.append(label + " is absent from the current translation list")
                continue
            if str(row.get("entity_type", target["entity_type"])) != target["entity_type"]:
                errors.append(label + " has a mismatched entity type")
            try:
                current_entity_id = int(row.get("entity_id", -1))
            except (TypeError, ValueError):
                current_entity_id = -1
            if current_entity_id != target["entity_id"]:
                errors.append(label + " has a mismatched entity id")
            if str(row.get("language", "")).lower() != "en":
                errors.append(label + " is not currently English")
            state = normalize_state(row)
            if state not in FLOW_STATES:
                errors.append(label + f" is in unsupported state {state!r}; no automatic promotion allowed")
            if row.get("is_current_revision") is False:
                errors.append(label + " targets an outdated source revision")
            source_hash = str(row.get("source_hash", "")).strip()
            current_hash = str(row.get("current_source_hash", "")).strip()
            if source_hash and current_hash and source_hash != current_hash:
                errors.append(label + " source hash differs from current source")
            old_note = str(row.get("quality_notes") or "")
            if state in {"APPROVED", "PUBLISHED"} and FLAG_PREFIX.lower() not in old_note.lower():
                errors.append(label + f" is already {state} without the required warning flag; inspect it manually")
            if state == "REJECTED":
                errors.append(label + " was rejected; a rejected translation will not be published")
        if errors:
            raise ValueError(
                f"Preflight failed for {len(errors)} item(s); no status changes were made. "
                + "; ".join(errors[:10])
            )

        report_dir = ROOT / "reports" / "offline-translation-imports"
        report_dir.mkdir(parents=True, exist_ok=True)
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        out_path = report_dir / f"english-translation-publish-with-warnings-{stamp}.json"
        run_report: dict[str, Any] = {
            "schema": "quantaedge.english-translation-publish-with-warnings-report.v1",
            "started_at": datetime.now(timezone.utc).isoformat(),
            "source_import_report": str(args.report),
            "expected_count": args.expected_count,
            "preflight_count": len(targets),
            "publication_attempted": True,
            "publication_policy": "explicit publish with admin-side quality warning flags",
            "results": [],
            "credentials_recorded": False,
        }
        write_report(out_path, run_report)
        systemic_error = False
        for index, target in enumerate(targets, 1):
            key = (target["entity_type"], target["translation_id"])
            current = current_rows[key]
            state = normalize_state(current)
            result_row = dict(target)
            result_row.update(initial_status=state, final_status=state, status="pending", quality_flag=True)
            notes = warning_notes(target, current.get("quality_notes"))
            try:
                while state != "PUBLISHED":
                    next_state = NEXT_STATE.get(state)
                    if next_state is None:
                        raise RuntimeError(f"Cannot safely promote from {state}.")
                    body: dict[str, Any] = {"status": next_state}
                    if next_state == "APPROVED":
                        body["qualityNotes"] = notes
                    response = api.request(
                        "PATCH",
                        f"/api/v1/admin/translations/{target['entity_type']}/{target['translation_id']}/status",
                        body,
                    )
                    returned = str(response.get("status", "")).upper() if isinstance(response, dict) else ""
                    if returned != next_state:
                        raise RuntimeError(f"Unexpected status response; wanted {next_state}.")
                    state = returned
                    result_row["final_status"] = state
                    if args.delay:
                        time.sleep(args.delay)
                result_row["status"] = (
                    "already_published_with_warning" if result_row["initial_status"] == "PUBLISHED"
                    else "published_with_warning"
                )
            except Exception as exc:
                result_row["status"] = "error"
                result_row["final_status"] = state
                result_row["error"] = str(exc)[:1000]
                if re.search(r"HTTP\s+(?:401|403|408|425|429|5\d\d)\b", str(exc), flags=re.IGNORECASE):
                    systemic_error = True
            run_report["results"].append(result_row)
            if index % 25 == 0 or index == len(targets) or systemic_error:
                run_report["finished_at"] = datetime.now(timezone.utc).isoformat()
                summary = Counter(str(item.get("status")) for item in run_report["results"])
                run_report["summary"] = dict(summary)
                write_report(out_path, run_report)
                print(f"Processed {index:,}/{len(targets):,}; summary={json.dumps(dict(summary), sort_keys=True)}", flush=True)
            if systemic_error:
                print("Stopping on authentication, permission, rate-limit or server error. Rerun the same command to resume.", file=sys.stderr)
                break

        run_report["finished_at"] = datetime.now(timezone.utc).isoformat()
        run_report["status"] = (
            "STOPPED_ON_SYSTEMIC_ERROR" if systemic_error
            else "COMPLETED_WITH_ERRORS" if any(r.get("status") == "error" for r in run_report["results"])
            else "SUCCEEDED"
        )
        run_report["summary"] = dict(Counter(str(item.get("status")) for item in run_report["results"]))
        write_report(out_path, run_report)
        print("Final status: " + run_report["status"])
        print("Summary: " + json.dumps(run_report["summary"], sort_keys=True))
        print("Report: " + str(out_path))
        return 0 if run_report["status"] == "SUCCEEDED" else 1
    except Exception as exc:
        print("ERROR: " + str(exc), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
