#!/usr/bin/env python3
"""Review and approve imported QuantaEdge practice-question drafts through the Admin API.

Default is a read-only dry run. With --apply, eligible DRAFT questions are
submitted to REVIEW and then APPROVED; questions already in REVIEW are resumed
directly at approval. Progress and a checkpoint report are saved during the run.
NUMERICAL uses the same workflow as MCQ, TRUE_FALSE and INPUT.

The script does not write directly to the database, publish lessons/chapters, alter
answer keys, mark source provenance verified, or bypass server-side validation.
Questions in DRAFT lessons, unsupported scoring formats, or failing validation are
reported and left for follow-up.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from typing import Any

DEFAULT_API = os.environ.get("QUANTAEDGE_API_BASE_URL", "http://localhost:8080")
SUPPORTED_SCORING_TYPES = {"MCQ", "TRUE_FALSE", "INPUT", "NUMERICAL"}
CLASSES = ("6", "7", "8")
SUBJECTS = ("maths", "science")


class AdminApi:
    def __init__(self, base_url: str, session_cookie: str, timeout: int = 30):
        self.base_url = base_url.rstrip("/")
        self.session_cookie = session_cookie
        self.timeout = timeout

    def request(self, method: str, path: str, body: dict[str, Any] | None = None) -> Any:
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(self.base_url + path, data=data, method=method)
        req.add_header("Accept", "application/json")
        req.add_header("Cookie", "QE_SESSION=" + self.session_cookie)
        if data is not None:
            req.add_header("Content-Type", "application/json; charset=utf-8")
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as response:
                raw = response.read().decode("utf-8")
                return json.loads(raw) if raw.strip() else {}
        except urllib.error.HTTPError as exc:
            message = exc.read().decode("utf-8", errors="replace")[:800]
            if exc.code == 401:
                detail = message.strip() or "The server did not accept the session cookie."
                raise RuntimeError(
                    f"{method} {path} failed with HTTP 401 (session missing, invalid, or expired): "
                    f"{detail} Sign in again and refresh QUANTAEDGE_ADMIN_SESSION."
                ) from exc
            if exc.code == 403:
                detail = message.strip() or "The server rejected the permission check."
                raise RuntimeError(
                    f"{method} {path} failed with HTTP 403 (permission denied): {detail} "
                    "Use an admin account or a reviewer with CONTENT_VIEW or CONTENT_REVIEW."
                ) from exc
            raise RuntimeError(f"{method} {path} failed with HTTP {exc.code}: {message}") from exc
        except urllib.error.URLError as exc:
            raise RuntimeError(f"Cannot reach QuantaEdge Admin API at {self.base_url}: {exc.reason}") from exc


def get_reviewable_questions(api: AdminApi, class_code: str, subject_code: str) -> list[dict[str, Any]]:
    """Load both DRAFT and REVIEW imported questions so interrupted applies can resume."""
    rows: list[dict[str, Any]] = []
    limit = 250
    for status in ("DRAFT", "REVIEW"):
        offset = 0
        while True:
            query = urllib.parse.urlencode({
                "status": status,
                "classCode": class_code,
                "subjectCode": subject_code,
                "limit": limit,
                "offset": offset,
            })
            result = api.request("GET", "/api/v1/admin/content/questions/review-queue?" + query)
            if not isinstance(result, dict) or not isinstance(result.get("items"), list):
                raise RuntimeError("Question review queue returned an unexpected response.")
            page = result["items"]
            rows.extend(page)
            total = int(result.get("total", len(rows)))
            offset += len(page)
            if not page or offset >= total:
                break
    return rows

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="Actually submit and approve eligible draft questions.")
    parser.add_argument("--api-url", default=DEFAULT_API, help="Local API URL or HTTPS URL.")
    parser.add_argument("--class-code", choices=CLASSES, help="Limit to one class; default checks Classes 6–8.")
    parser.add_argument("--subject", choices=SUBJECTS, help="Limit to Maths or Science; default checks both.")
    parser.add_argument("--limit", type=int, default=0, help="Optional maximum number of questions to process (0 = all).")
    parser.add_argument("--show-items", action="store_true", help="Print every planned/skipped question (large output).")
    parser.add_argument("--report-file", help="Write the full JSON report here; --apply defaults to practice_question_review_report.json.")
    args = parser.parse_args()

    session = os.environ.get("QUANTAEDGE_ADMIN_SESSION", "").strip()
    if not session:
        parser.error("Set QUANTAEDGE_ADMIN_SESSION from the authenticated QE_SESSION cookie (the review queue is protected even for dry runs).")
    if args.apply and not args.api_url.startswith(("https://", "http://localhost", "http://127.0.0.1")):
        parser.error("Use HTTPS for a remote API; HTTP is permitted only for localhost.")
    if args.limit < 0:
        parser.error("--limit must be zero or a positive number.")

    api = AdminApi(args.api_url, session) if args.apply else None
    classes = (args.class_code,) if args.class_code else CLASSES
    subjects = (args.subject,) if args.subject else SUBJECTS
    targets: list[dict[str, Any]] = []
    failures: list[dict[str, Any]] = []
    for class_code in classes:
        for subject_code in subjects:
            try:
                # Queue endpoint is authenticated; use the same session for dry-run only when supplied.
                if api is None:
                    dry_api = AdminApi(args.api_url, session)
                    rows = get_reviewable_questions(dry_api, class_code, subject_code)
                else:
                    rows = get_reviewable_questions(api, class_code, subject_code)
                targets.extend(rows)
            except Exception as exc:
                failures.append({"class": class_code, "subject": subject_code, "stage": "load", "error": str(exc)})

    # Defensive de-duplication across track queries.
    unique: dict[int, dict[str, Any]] = {}
    for row in targets:
        try:
            unique[int(row["question_id"])] = row
        except (KeyError, TypeError, ValueError):
            failures.append({"stage": "preflight", "error": "Queue row has no valid question_id."})

    planned: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    for row in unique.values():
        qid = int(row["question_id"])
        raw_tags = row.get("tags", "[]")
        try:
            parsed_tags = json.loads(raw_tags) if isinstance(raw_tags, str) else raw_tags
        except (TypeError, json.JSONDecodeError):
            parsed_tags = []
        tags = [str(tag).lower() for tag in parsed_tags] if isinstance(parsed_tags, list) else []
        # Avoid accidentally approving unrelated drafts. Imported practice packs use
        # editorial-review-required, qe-practice-id, or import/source tags.
        imported_marker = any(
            "editorial-review-required" in tag or tag.startswith("qe-practice-id:")
            or "imported" in tag or "ncert" in tag or "scert" in tag
            for tag in tags
        )
        if not imported_marker:
            skipped.append({"question_id": qid, "reason": "No known imported-practice tag; left untouched."})
            continue
        qtype = str(row.get("question_type", "")).upper()
        lesson_status = str(row.get("lesson_status", "")).upper()
        review_status = str(row.get("review_status") or "DRAFT").upper()
        item = {
            "question_id": qid,
            "class": row.get("class_code"),
            "subject": row.get("subject_code"),
            "chapter": row.get("chapter_name"),
            "lesson": row.get("lesson_title"),
            "lesson_id": row.get("lesson_id"),
            "question_type": qtype,
            "lesson_status": lesson_status,
            "review_status": review_status,
        }
        if review_status not in {"DRAFT", "REVIEW"}:
            skipped.append({**item, "reason": "Question is not in a resumable DRAFT or REVIEW state."})
        elif not bool(row.get("active", True)):
            skipped.append({**item, "reason": "Question is inactive; script will not reactivate it."})
        elif qtype not in SUPPORTED_SCORING_TYPES:
            skipped.append({**item, "reason": "This specialist type is not enabled for automatic approval/scoring yet."})
        elif lesson_status not in {"REVIEW", "PUBLISHED"}:
            skipped.append({**item, "reason": "Lesson must already be REVIEW or PUBLISHED; lesson workflow was not changed."})
        else:
            planned.append(item)

    if args.limit:
        planned = planned[:args.limit]

    counts = Counter(item["question_type"] for item in planned)
    by_current_status = Counter(item["review_status"] for item in planned)
    skipped_by_reason = Counter(item.get("reason", "Unspecified") for item in skipped)
    report_path = args.report_file or ("practice_question_review_report.json" if args.apply else None)
    report: dict[str, Any] = {
        "mode": "APPLY_RUNNING" if args.apply else "DRY_RUN",
        "api_url": args.api_url,
        "scope": {"classes": list(classes), "subjects": list(subjects)},
        "reviewable_questions_found": len(unique),
        "eligible_imported_questions": len(planned),
        "eligible_by_type": dict(sorted(counts.items())),
        "eligible_by_current_status": dict(sorted(by_current_status.items())),
        "skipped_count": len(skipped),
        "skipped_by_reason": dict(sorted(skipped_by_reason.items())),
        "load_failures": failures,
        "planned": planned,
        "skipped": skipped,
        "results": [],
        "attempted": 0,
        "approved": 0,
        "failed_or_left_in_review": 0,
        "in_flight": None,
    }

    def save_report() -> None:
        if not report_path:
            return
        absolute_path = os.path.abspath(report_path)
        os.makedirs(os.path.dirname(absolute_path), exist_ok=True)
        temporary_path = absolute_path + ".tmp"
        with open(temporary_path, "w", encoding="utf-8") as report_stream:
            json.dump(report, report_stream, ensure_ascii=False, indent=2)
            report_stream.write("\n")
        os.replace(temporary_path, absolute_path)

    def print_summary(include_items: bool = False) -> None:
        results = report.get("results", [])
        planned_sample = report.get("planned", [])
        skipped_sample = report.get("skipped", [])
        output: dict[str, Any] = {
            "mode": report["mode"],
            "api_url": report["api_url"],
            "scope": report["scope"],
            "reviewable_questions_found": report["reviewable_questions_found"],
            "eligible_imported_questions": report["eligible_imported_questions"],
            "eligible_by_type": report["eligible_by_type"],
            "eligible_by_current_status": report["eligible_by_current_status"],
            "skipped_count": report["skipped_count"],
            "skipped_by_reason": report["skipped_by_reason"],
            "load_failures": report["load_failures"],
        }
        output["planned_sample"] = planned_sample if include_items else planned_sample[:10]
        output["planned_omitted_from_console"] = 0 if include_items else max(0, len(planned_sample) - 10)
        output["skipped_sample"] = skipped_sample if include_items else skipped_sample[:10]
        if report.get("mode") != "DRY_RUN":
            output["attempted"] = report["attempted"]
            output["approved"] = report["approved"]
            output["failed_or_left_in_review"] = report["failed_or_left_in_review"]
            output["failed_sample"] = [r for r in results if r.get("result") != "APPROVED"][:10]
            output["results_sample"] = results if include_items else results[-10:]
            output["results_omitted_from_console"] = 0 if include_items else max(0, len(results) - 10)
        if report.get("in_flight"):
            output["in_flight"] = report["in_flight"]
        if report.get("note"):
            output["note"] = report["note"]
        print(json.dumps(output, ensure_ascii=False, indent=2))
        if report_path:
            print(f"Full report/checkpoint: {os.path.abspath(report_path)}")

    if not args.apply:
        if report_path:
            save_report()
        print_summary(args.show_items)
        if failures:
            print("\nDry run incomplete: one or more review queues failed to load. Fix the API errors; do not use --apply yet.")
        else:
            print("\nDry run only; no questions were changed.")
        return 1 if failures else 0

    # Never mutate questions after a partial/failed queue read: the plan must be complete.
    if failures:
        report["mode"] = "STOPPED_SAFELY"
        save_report()
        print_summary(args.show_items)
        print("\nRefusing to apply because one or more review queues failed to load or failed preflight. Fix the errors and rerun a clean dry run first.", file=sys.stderr)
        return 1

    assert api is not None

    print_summary(args.show_items)
    save_report()
    results: list[dict[str, Any]] = report["results"]
    total = len(planned)
    interrupted = False

    try:
        for index, item in enumerate(planned, start=1):
            qid = int(item["question_id"])
            lesson_id = int(item["lesson_id"])
            path = f"/api/v1/admin/content/lessons/{lesson_id}/questions/{qid}/review"
            current_status = str(item.get("review_status") or "DRAFT").upper()
            location = f"Class {item.get('class')} {item.get('subject')} / Q{qid}"

            try:
                if current_status == "DRAFT":
                    print(f"[{index}/{total}] {location}: DRAFT -> REVIEW", flush=True)
                    report["in_flight"] = {"question_id": qid, "stage": "SUBMIT_TO_REVIEW", "note": "If interrupted, next run rechecks server status."}
                    save_report()
                    api.request(
                        "POST", path,
                        {"status": "REVIEW", "reviewNotes": "Imported practice question submitted for standard editorial review."},
                    )
                    item["review_status"] = "REVIEW"
                    report["in_flight"] = {"question_id": qid, "stage": "APPROVE", "note": "Question is now in REVIEW; next run resumes from REVIEW if interrupted."}
                    save_report()
                else:
                    print(f"[{index}/{total}] {location}: resuming from REVIEW -> APPROVED", flush=True)
                    report["in_flight"] = {"question_id": qid, "stage": "APPROVE", "note": "Question was already in REVIEW before this run."}
                    save_report()

                approved = api.request(
                    "POST", path,
                    {"status": "APPROVED", "reviewNotes": "Approved through standard question review workflow; recheck and correct if an issue is later reported."},
                )
                results.append({
                    "question_id": qid,
                    "class": item.get("class"),
                    "subject": item.get("subject"),
                    "question_type": item["question_type"],
                    "result": "APPROVED",
                    "server_response": approved,
                })
                report["attempted"] = len(results)
                report["approved"] = sum(1 for row in results if row["result"] == "APPROVED")
                report["failed_or_left_in_review"] = sum(1 for row in results if row["result"] != "APPROVED")
                report["in_flight"] = None
                save_report()
                print(f"    APPROVED ({report['approved']} of {total} complete)", flush=True)
            except KeyboardInterrupt:
                raise
            except Exception as exc:
                result = {
                    "question_id": qid,
                    "class": item.get("class"),
                    "subject": item.get("subject"),
                    "question_type": item["question_type"],
                    "result": "FAILED_OR_LEFT_IN_REVIEW",
                    "error": str(exc),
                }
                results.append(result)
                report["attempted"] = len(results)
                report["approved"] = sum(1 for row in results if row["result"] == "APPROVED")
                report["failed_or_left_in_review"] = sum(1 for row in results if row["result"] != "APPROVED")
                report["in_flight"] = None
                save_report()
                print(f"    FAILED/LEFT IN REVIEW: {str(exc)[:240]}", file=sys.stderr, flush=True)
                if any(token in str(exc) for token in ("HTTP 401", "HTTP 403", "Cannot reach QuantaEdge Admin API")):
                    print("Stopping early because authentication or API connectivity failed. Correct it and rerun; DRAFT and REVIEW items are resumable.", file=sys.stderr)
                    break
    except KeyboardInterrupt:
        interrupted = True
        report["mode"] = "INTERRUPTED"
        report["interrupted"] = True
        # Keep the in-flight stage: the server may have completed a request before
        # Ctrl+C arrived. A fresh run reads actual DRAFT/REVIEW states from the API.
        save_report()
        print("\nInterrupted by Ctrl+C. The report/checkpoint was saved. Rerun the same command to resume; the queue is reread from the server.", file=sys.stderr)

    report["attempted"] = len(results)
    report["approved"] = sum(1 for row in results if row["result"] == "APPROVED")
    report["failed_or_left_in_review"] = sum(1 for row in results if row["result"] != "APPROVED")
    if not interrupted:
        report["mode"] = "APPLY_RESULT"
        report["note"] = (
            "Approved questions are eligible for student practice when their lesson and chapter are published. "
            "This does not publish draft lessons/chapters or certify source provenance. "
            "The report file is a checkpoint; a new run re-reads DRAFT and REVIEW questions to resume safely."
        )
    save_report()
    print_summary(args.show_items)
    return 130 if interrupted else (1 if report["failed_or_left_in_review"] or failures else 0)


if __name__ == "__main__":
    raise SystemExit(main())
