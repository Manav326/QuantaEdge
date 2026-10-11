#!/usr/bin/env python3
"""Review and approve imported QuantaEdge practice-question drafts through the Admin API.

Default is a read-only dry run. With --apply, each eligible DRAFT question is
submitted to REVIEW and then APPROVED through the same permissioned endpoint used
by the Admin UI. NUMERICAL uses the same workflow as MCQ, TRUE_FALSE and INPUT.

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
            if exc.code in {401, 403}:
                raise RuntimeError(
                    "Admin session/permission rejected. Sign in as an admin or reviewer, "
                    "then refresh QUANTAEDGE_ADMIN_SESSION."
                ) from exc
            raise RuntimeError(f"{method} {path} failed with HTTP {exc.code}: {message}") from exc
        except urllib.error.URLError as exc:
            raise RuntimeError(f"Cannot reach QuantaEdge Admin API at {self.base_url}: {exc.reason}") from exc


def get_drafts(api: AdminApi, class_code: str, subject_code: str) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    offset = 0
    limit = 250
    while True:
        query = urllib.parse.urlencode({
            "status": "DRAFT",
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
    args = parser.parse_args()

    session = os.environ.get("QUANTAEDGE_ADMIN_SESSION", "").strip()
    if args.apply and not session:
        parser.error("--apply requires QUANTAEDGE_ADMIN_SESSION from the authenticated QE_SESSION cookie.")
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
                    rows = get_drafts(dry_api, class_code, subject_code)
                else:
                    rows = get_drafts(api, class_code, subject_code)
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
        qtype = str(row.get("question_type", "")).upper()
        lesson_status = str(row.get("lesson_status", "")).upper()
        item = {
            "question_id": qid,
            "class": row.get("class_code"),
            "subject": row.get("subject_code"),
            "chapter": row.get("chapter_name"),
            "lesson": row.get("lesson_title"),
            "lesson_id": row.get("lesson_id"),
            "question_type": qtype,
            "lesson_status": lesson_status,
        }
        if not bool(row.get("active", True)):
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
    print(json.dumps({
        "mode": "APPLY" if args.apply else "DRY_RUN",
        "api_url": args.api_url,
        "scope": {"classes": list(classes), "subjects": list(subjects)},
        "draft_questions_found": len(unique),
        "eligible_questions": len(planned),
        "eligible_by_type": dict(sorted(counts.items())),
        "skipped": skipped,
        "load_failures": failures,
        "planned": planned,
    }, ensure_ascii=False, indent=2))

    if not args.apply:
        print("\nDry run only. Review the list, then rerun with --apply to submit and approve these questions.")
        return 1 if failures else 0

    assert api is not None
    results: list[dict[str, Any]] = []
    for item in planned:
        qid = item["question_id"]
        lesson_id = item["lesson_id"]
        path = f"/api/v1/admin/content/lessons/{lesson_id}/questions/{qid}/review"
        try:
            # Same two-step moderation path used in Admin UI: DRAFT -> REVIEW -> APPROVED.
            api.request("POST", path, {"status": "REVIEW", "reviewNotes": "Imported practice question submitted for standard editorial review."})
            approved = api.request("POST", path, {"status": "APPROVED", "reviewNotes": "Approved through standard question review workflow; recheck and correct if an issue is later reported."})
            results.append({"question_id": qid, "question_type": item["question_type"],
                            "result": "APPROVED", "server_response": approved})
        except Exception as exc:
            results.append({"question_id": qid, "question_type": item["question_type"],
                            "result": "FAILED_OR_LEFT_IN_REVIEW", "error": str(exc)})

    succeeded = sum(1 for row in results if row["result"] == "APPROVED")
    failed = len(results) - succeeded
    print(json.dumps({
        "mode": "APPLY_RESULT",
        "attempted": len(results),
        "approved": succeeded,
        "failed_or_left_in_review": failed,
        "skipped": len(skipped),
        "results": results,
        "note": "Question approval does not publish draft lessons/chapters or certify source provenance. Fix reported validation failures in Admin before retrying.",
    }, ensure_ascii=False, indent=2))
    return 1 if failed or failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
