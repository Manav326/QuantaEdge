#!/usr/bin/env python3
"""Import original QuantaEdge site-content drafts into the admin CMS for review.

This is deliberately separate from import_reviewed_content.py:
- accepts only AI_AUTHORED_DRAFT bundles;
- creates chapters/lessons/questions as DRAFT;
- never asserts that rights/source alignment were verified;
- never approves questions, submits lessons for review, or publishes;
- refuses non-canonical chapter placement unless explicitly acknowledged.

A staff editor must still verify curriculum placement, source edition/page ranges,
language, diagrams, answer keys, student preview, and rights before publication.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

SCHEMA = "quantaedge.site-content-authoring-draft.v1"
QUESTION_TYPES = {"MCQ", "TRUE_FALSE", "INPUT", "NUMERICAL"}
BLOCK_TYPES = {
    "EXPLANATION", "IMAGE", "DIAGRAM", "VIDEO", "QUESTION", "MCQ", "TRUE_FALSE",
    "MATCH", "ORDER", "INPUT", "HINT", "AI_HELP", "SUMMARY", "CHALLENGE",
    "PREREQUISITE", "WORKED_EXAMPLE", "GUIDED_PRACTICE", "INDEPENDENT_PRACTICE",
    "RECAP", "AUDIO", "ANIMATION",
}
CODE_RE = re.compile(r"[a-z0-9]+(?:-[a-z0-9]+)*")
DIFFICULTIES = {"EASY", "MEDIUM", "HARD"}


def require_text(value: Any, name: str, limit: int = 2000) -> str:
    if not isinstance(value, str) or not value.strip() or len(value.strip()) > limit:
        raise ValueError(f"{name} must be non-empty text no longer than {limit} characters.")
    return value.strip()


def validate_site_bundle(bundle: Any, allow_noncanonical_chapter: bool = False) -> dict[str, Any]:
    """Validate a draft-only content bundle; no reviewer or rights claim is inferred."""
    if not isinstance(bundle, dict) or bundle.get("schema") != SCHEMA:
        raise ValueError(f"Expected schema {SCHEMA}.")
    if bundle.get("bundle_status") != "AI_AUTHORED_DRAFT":
        raise ValueError("Only AI_AUTHORED_DRAFT bundles may use this importer.")
    if bundle.get("publish_status") != "DRAFT" or bundle.get("auto_publish") is not False:
        raise ValueError("The bundle must explicitly remain DRAFT with auto_publish=false.")
    if bundle.get("editorial_state") != "PENDING_HUMAN_REVIEW":
        raise ValueError("This path only accepts content whose human review is still pending.")
    declaration = bundle.get("declaration")
    if not isinstance(declaration, dict) or declaration.get("rights_reviewed") is not False:
        raise ValueError("The draft must not claim rights_reviewed=true.")

    curriculum = bundle.get("curriculum")
    if not isinstance(curriculum, dict):
        raise ValueError("curriculum metadata is required.")
    class_code = str(curriculum.get("class_code", "")).strip()
    subject_code = str(curriculum.get("subject_code", "")).strip().lower()
    language = str(curriculum.get("language", "")).strip().lower()
    if class_code not in {"6", "7", "8"} or subject_code not in {"maths", "science"}:
        raise ValueError("Only supported Bihar Board Classes 6–8 Maths/Science tracks may be imported.")
    if language not in {"hi", "en"}:
        raise ValueError("curriculum.language must be hi or en.")

    chapter = bundle.get("chapter")
    if not isinstance(chapter, dict):
        raise ValueError("chapter metadata is required.")
    chapter_code = require_text(chapter.get("code"), "chapter.code", 60).lower()
    if not CODE_RE.fullmatch(chapter_code):
        raise ValueError("chapter.code must use lowercase letters, numbers and hyphens.")
    require_text(chapter.get("display_name"), "chapter.display_name", 160)

    alignment = bundle.get("source_alignment")
    if not isinstance(alignment, dict):
        raise ValueError("source_alignment metadata is required.")
    candidate = alignment.get("source_of_truth_candidate")
    if not isinstance(candidate, dict):
        raise ValueError("source_alignment.source_of_truth_candidate is required.")
    candidate_title = require_text(candidate.get("title"), "source candidate title", 300)
    candidate_url = require_text(candidate.get("url"), "source candidate URL", 2000)
    if not candidate_url.lower().startswith("https://"):
        raise ValueError("The candidate source URL must use HTTPS.")
    track_match = str(curriculum.get("canonical_track_match", "")).upper()
    noncanonical = ("NO_EXACT_MATCH" in track_match
                    or str(alignment.get("status", "")).upper() == "CURRICULUM_DECISION_REQUIRED")
    if noncanonical and not allow_noncanonical_chapter:
        raise ValueError(
            "This draft has no exact chapter match in the current canonical curriculum. "
            "Review the placement, then explicitly pass --allow-noncanonical-chapter to create it as DRAFT."
        )

    lessons = bundle.get("lessons")
    if not isinstance(lessons, list) or not lessons:
        raise ValueError("At least one lesson draft is required.")
    lesson_codes: set[str] = set()
    lesson_order: set[int] = set()
    for index, item in enumerate(lessons, 1):
        if not isinstance(item, dict):
            raise ValueError(f"lessons[{index - 1}] must be an object.")
        code = require_text(item.get("code"), f"lessons[{index - 1}].code", 100).lower()
        if not CODE_RE.fullmatch(code) or code in lesson_codes:
            raise ValueError(f"Lesson codes must be unique lowercase slugs: {code}")
        lesson_codes.add(code)
        require_text(item.get("title"), f"{code}.title", 240)
        minutes = item.get("estimated_minutes")
        order = item.get("sort_order")
        if not isinstance(minutes, int) or not 1 <= minutes <= 120:
            raise ValueError(f"{code}.estimated_minutes must be between 1 and 120.")
        if not isinstance(order, int) or not 1 <= order <= 10000 or order in lesson_order:
            raise ValueError(f"{code}.sort_order must be a unique integer between 1 and 10000.")
        lesson_order.add(order)
        blocks = item.get("blocks")
        if not isinstance(blocks, list) or not blocks:
            raise ValueError(f"{code} requires at least one teaching block.")
        seen_sequences: set[int] = set()
        for bi, block in enumerate(blocks, 1):
            if not isinstance(block, dict) or str(block.get("block_type", "")).upper() not in BLOCK_TYPES:
                raise ValueError(f"{code} block {bi} has an unsupported block_type.")
            sequence = block.get("sequence_no")
            if not isinstance(sequence, int) or sequence < 1 or sequence in seen_sequences:
                raise ValueError(f"{code} block sequence numbers must be unique positive integers.")
            seen_sequences.add(sequence)
            content = block.get("content")
            if not isinstance(content, dict) or not content:
                raise ValueError(f"{code} block {bi} content must be a non-empty JSON object.")
            kind = str(block["block_type"]).upper()
            if kind in {"GUIDED_PRACTICE", "INDEPENDENT_PRACTICE"} and not str(content.get("prompt", "")).strip():
                raise ValueError(f"{code} {kind} blocks require a prompt.")
            if kind == "WORKED_EXAMPLE" and (not str(content.get("problem", "")).strip()
                                             or not isinstance(content.get("steps"), list)
                                             or not str(content.get("answer", "")).strip()):
                raise ValueError(f"{code} worked examples require a problem, steps and answer.")
            if kind == "EXPLANATION" and not any(str(content.get(key, "")).strip()
                                                  for key in ("body", "html", "description")):
                raise ValueError(f"{code} explanations require body, html or description text.")

    questions = bundle.get("online_question_bank")
    if not isinstance(questions, list) or not questions:
        raise ValueError("online_question_bank must contain at least one question.")
    question_ids: set[str] = set()
    questions_by_lesson = {code: 0 for code in lesson_codes}
    for index, item in enumerate(questions, 1):
        if not isinstance(item, dict):
            raise ValueError(f"online_question_bank[{index - 1}] must be an object.")
        qid = require_text(item.get("id"), f"question {index} id", 120)
        if qid in question_ids:
            raise ValueError(f"Duplicate question id: {qid}")
        question_ids.add(qid)
        lesson_code = require_text(item.get("lesson_code"), f"{qid}.lesson_code", 100)
        if lesson_code not in lesson_codes:
            raise ValueError(f"Question {qid} maps to missing lesson {lesson_code}.")
        questions_by_lesson[lesson_code] += 1
        qtype = str(item.get("question_type", "")).upper()
        if qtype not in QUESTION_TYPES:
            raise ValueError(f"{qid} uses unsupported online question type {qtype}.")
        require_text(item.get("prompt"), f"{qid}.prompt", 1500)
        if str(item.get("difficulty", "")).upper() not in DIFFICULTIES:
            raise ValueError(f"{qid}.difficulty must be EASY, MEDIUM or HARD.")
        options = item.get("options", [])
        answer = item.get("answer_payload")
        if not isinstance(options, list) or not isinstance(answer, dict):
            raise ValueError(f"{qid} requires options and answer_payload objects.")
        if qtype in {"MCQ", "TRUE_FALSE"}:
            if not 2 <= len(options) <= 10:
                raise ValueError(f"{qid} requires 2–10 options.")
            keys: set[str] = set()
            correct_keys: list[str] = []
            for option in options:
                if not isinstance(option, dict):
                    raise ValueError(f"{qid} options must be objects.")
                key = require_text(option.get("key"), f"{qid}.option.key", 20)
                require_text(option.get("label"), f"{qid}.option.label", 500)
                if key in keys:
                    raise ValueError(f"{qid} has duplicate option keys.")
                keys.add(key)
                if option.get("correct") is True:
                    correct_keys.append(key)
            if len(correct_keys) != 1:
                raise ValueError(f"{qid} must have exactly one correct option.")
            if answer.get("kind") != "OPTION" or answer.get("value") != correct_keys[0]:
                raise ValueError(f"{qid} answer_payload must match the sole correct option.")
        elif qtype == "INPUT":
            if options or answer.get("kind") != "TEXT" or not isinstance(answer.get("value"), str) or not answer["value"].strip():
                raise ValueError(f"{qid} INPUT requires a non-empty TEXT answer and no options.")
        elif qtype == "NUMERICAL":
            if options or answer.get("kind") != "NUMERIC" or not isinstance(answer.get("value"), (int, float)):
                raise ValueError(f"{qid} NUMERICAL requires a numeric answer and no options.")
    missing = sorted(code for code, count in questions_by_lesson.items() if count == 0)
    if missing:
        raise ValueError("Each lesson needs at least one supported online question before it can later be published. Missing: " + ", ".join(missing))

    return {
        "class_code": class_code,
        "subject_code": subject_code,
        "language": language,
        "chapter_code": chapter_code,
        "chapter_title": chapter["display_name"],
        "candidate_source_title": candidate_title,
        "source_alignment_status": str(alignment.get("status", "PENDING")),
        "noncanonical_chapter": noncanonical,
        "lessons": len(lessons),
        "questions": len(questions),
        "all_statuses": "DRAFT",
        "student_delivery_allowed": False,
        "auto_publish": False,
    }


class AdminApi:
    def __init__(self, base_url: str, session_cookie: str, timeout: int = 30):
        self.base_url = base_url.rstrip("/")
        self.session_cookie = session_cookie
        self.timeout = timeout

    def request(self, method: str, path: str, body: dict[str, Any] | None = None) -> dict[str, Any] | list[Any]:
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
            message = exc.read().decode("utf-8", errors="replace")[:700]
            if exc.code in {401, 403}:
                raise RuntimeError("Admin session rejected. Sign in as an authorised staff member and refresh QUANTAEDGE_ADMIN_SESSION.") from exc
            raise RuntimeError(f"{method} {path} failed with HTTP {exc.code}: {message}") from exc
        except urllib.error.URLError as exc:
            raise RuntimeError(f"Cannot reach QuantaEdge admin API at {self.base_url}: {exc.reason}") from exc


def _candidate_source(bundle: dict[str, Any]) -> tuple[str, str]:
    source = bundle["source_alignment"]["source_of_truth_candidate"]
    return str(source["title"]).strip() + " — स्रोत सत्यापन लंबित", str(source["url"]).strip()


def _question_for_api(item: dict[str, Any], index: int) -> dict[str, Any]:
    difficulty = {"EASY": "FOUNDATION", "MEDIUM": "CORE", "HARD": "CHALLENGE"}[str(item["difficulty"]).upper()]
    return {
        "question_type": str(item["question_type"]).upper(),
        "prompt": item["prompt"],
        "explanation": item.get("explanation", ""),
        "difficulty": difficulty,
        "sort_order": int(item.get("sort_order", index)),
        "active": True,
        "review_status": "DRAFT",
        "review_notes": "Imported as an AI-authored draft. Verify content, source alignment and answer key before approval.",
        "marks": item.get("marks", 1),
        "exam_format": item.get("exam_format", "Practice"),
        "source_kind": "AUTHOR_CREATED",
        "source_title": item.get("source_title", "QuantaEdge original practice draft — review pending"),
        "source_ref": None,
        "source_year": None,
        "source_id": None,
        "board": item.get("board", "Bihar Board"),
        "topic": item.get("topic"),
        "subtopic": item.get("subtopic"),
        "skill": item.get("skill"),
        "tags": item.get("tags", ["original", "class-6", "hindi", "editorial-review-required"]),
        "answer_payload": item["answer_payload"],
        "options": item.get("options", []),
    }


def build_lesson_patch(bundle: dict[str, Any], lesson: dict[str, Any], questions: list[dict[str, Any]], source_title: str, source_url: str) -> dict[str, Any]:
    """Build a CMS update with no verified-source, reviewed, or published flags."""
    return {
        "title": lesson["title"],
        "summary": lesson.get("summary", ""),
        "estimatedMinutes": int(lesson["estimated_minutes"]),
        "alignmentSourceTitle": source_title,
        "alignmentSourceUrl": source_url,
        "alignmentSourceEdition": "",
        "alignmentPageRange": "",
        "alignmentSourceVerified": False,
        "status": "DRAFT",
        "sortOrder": int(lesson["sort_order"]),
        "blocks": [
            {
                "sequence_no": int(block["sequence_no"]),
                "block_type": str(block["block_type"]).upper(),
                "content": block["content"],
                "active": True,
            }
            for block in sorted(lesson["blocks"], key=lambda x: x["sequence_no"])
        ],
        "questions": [
            _question_for_api(item, index)
            for index, item in enumerate(questions, 1)
        ],
    }


def dry_run_summary(bundle: dict[str, Any], attach_to_existing_chapter: bool = False,
                    allow_noncanonical_chapter: bool = False) -> dict[str, Any]:
    plan = validate_site_bundle(bundle, allow_noncanonical_chapter)
    plan["mode"] = "DRY_RUN"
    plan["chapter_mode"] = "ATTACH_TO_EXISTING" if attach_to_existing_chapter else "CREATE_DRAFT_CHAPTER"
    plan["publication"] = "NO_CHAPTER_OR_LESSON_OR_QUESTION_WILL_BE_PUBLISHED"
    return plan


def run_import(bundle: dict[str, Any], base_url: str, session: str,
               attach_to_existing_chapter: bool = False,
               allow_noncanonical_chapter: bool = False) -> dict[str, Any]:
    summary = validate_site_bundle(bundle, allow_noncanonical_chapter)
    if not session:
        raise ValueError("Set QUANTAEDGE_ADMIN_SESSION from an authenticated QE_SESSION cookie; never pass it on the command line.")
    if not base_url.lower().startswith(("http://localhost", "http://127.0.0.1", "https://")):
        raise ValueError("Use HTTPS for remote API URLs; plain HTTP is allowed only for localhost.")
    api = AdminApi(base_url, session)
    curriculum = bundle["curriculum"]
    chapter = bundle["chapter"]
    source_title, source_url = _candidate_source(bundle)
    query = urllib.parse.urlencode({
        "classCode": curriculum["class_code"],
        "subjectCode": curriculum["subject_code"],
        "status": "ALL",
    })
    rows = api.request("GET", "/api/v1/admin/content?" + query)
    if not isinstance(rows, list):
        raise RuntimeError("Admin content catalogue did not return a list.")
    matches: dict[int, dict[str, Any]] = {}
    for row in rows:
        if str(row.get("chapter_code", "")).strip() == chapter["code"]:
            try:
                matches[int(row["chapter_id"])] = row
            except (KeyError, TypeError, ValueError):
                continue
    if attach_to_existing_chapter:
        if len(matches) != 1:
            raise RuntimeError(
                f"Expected exactly one existing chapter code {chapter['code']} in class {curriculum['class_code']} / "
                f"{curriculum['subject_code']}; found {len(matches)}. No content was written."
            )
        chapter_id = next(iter(matches))
    else:
        if matches:
            raise RuntimeError(
                f"Chapter code {chapter['code']} already exists in this class/subject. "
                "Use --attach-to-existing-chapter after confirming the exact canonical match; no content was written."
            )
        chapter_payload = {
            "classCode": curriculum["class_code"],
            "subjectCode": curriculum["subject_code"],
            "code": chapter["code"],
            "displayName": chapter["display_name"],
            "description": str(chapter.get("description", ""))[:500],
            "curriculumSource": source_title,
            "curriculumSourceUrl": source_url,
            "curriculumSourceEdition": "",
            "curriculumSourcePages": "",
            "sortOrder": max(1, int(chapter.get("sort_order", 1))),
            "status": "DRAFT",
        }
        created = api.request("POST", "/api/v1/admin/content/chapters", chapter_payload)
        try:
            chapter_id = int(created["chapter_id"])
        except (KeyError, TypeError, ValueError) as exc:
            raise RuntimeError("Chapter creation returned no chapter_id; inspect Admin Content Studio before retrying.") from exc

    planned_codes = {str(item["code"]).strip() for item in bundle["lessons"]}
    existing_lesson_codes = {
        str(row.get("lesson_code")).strip()
        for row in rows
        if str(row.get("chapter_code", "")).strip() == chapter["code"] and row.get("lesson_code")
    }
    collisions = sorted(planned_codes & existing_lesson_codes)
    if collisions:
        raise RuntimeError("Lesson code collision(s): " + ", ".join(collisions) + ". No lessons were written; inspect the CMS.")

    questions_by_lesson: dict[str, list[dict[str, Any]]] = {str(item["code"]): [] for item in bundle["lessons"]}
    for item in bundle["online_question_bank"]:
        questions_by_lesson[str(item["lesson_code"])].append(item)

    imported_lesson_ids: list[int] = []
    try:
        for lesson in sorted(bundle["lessons"], key=lambda item: item["sort_order"]):
            create_body = {
                "chapterId": chapter_id,
                "code": lesson["code"],
                "title": lesson["title"],
                "summary": str(lesson.get("summary", ""))[:800],
                "estimatedMinutes": int(lesson["estimated_minutes"]),
                "alignmentSourceTitle": source_title,
                "alignmentSourceUrl": source_url,
                "alignmentSourceEdition": "",
                "alignmentPageRange": "",
                "sortOrder": int(lesson["sort_order"]),
                "status": "DRAFT",
            }
            created_lesson = api.request("POST", "/api/v1/admin/content/lessons", create_body)
            try:
                lesson_id = int(created_lesson["lesson_id"])
            except (KeyError, TypeError, ValueError) as exc:
                raise RuntimeError(f"Lesson {lesson['code']} creation returned no lesson_id. Inspect the CMS.") from exc
            imported_lesson_ids.append(lesson_id)
            patch = build_lesson_patch(
                bundle, lesson, questions_by_lesson[str(lesson["code"])], source_title, source_url
            )
            api.request("PATCH", f"/api/v1/admin/content/lessons/{lesson_id}", patch)
    except Exception as exc:
        raise RuntimeError(
            f"Draft import stopped after writing chapter_id={chapter_id} and lesson_ids={imported_lesson_ids}. "
            f"Nothing was published. Inspect those draft records before retrying. Cause: {exc}"
        ) from exc

    result = dict(summary)
    result.update({
        "mode": "APPLIED_DRAFT_ONLY",
        "chapter_id": chapter_id,
        "lesson_ids": imported_lesson_ids,
        "lesson_count": len(imported_lesson_ids),
        "question_count": len(bundle["online_question_bank"]),
        "source_verified": False,
        "question_review_status": "DRAFT",
        "chapter_published": False if not attach_to_existing_chapter else "UNCHANGED_EXISTING_CHAPTER",
        "lesson_status": "DRAFT",
        "student_delivery_allowed": False,
        "auto_publish": False,
    })
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", required=True, type=Path, help="QuantaEdge site-content authoring draft JSON")
    parser.add_argument("--apply", action="store_true", help="Write DRAFT records to the authenticated admin API")
    parser.add_argument("--attach-to-existing-chapter", action="store_true",
                        help="Attach lessons to exactly one existing chapter with the same code; never create a duplicate")
    parser.add_argument("--allow-noncanonical-chapter", action="store_true",
                        help="Explicitly allow a curriculum-mismatched chapter to be created as DRAFT only")
    parser.add_argument("--api-url", default=os.environ.get("QUANTAEDGE_API_BASE_URL", "http://localhost:8080"))
    args = parser.parse_args()
    if not args.bundle.is_file():
        parser.error(f"Bundle does not exist: {args.bundle}")
    try:
        bundle = json.loads(args.bundle.read_text(encoding="utf-8"))
        if args.apply:
            result = run_import(
                bundle, args.api_url, os.environ.get("QUANTAEDGE_ADMIN_SESSION", ""),
                args.attach_to_existing_chapter, args.allow_noncanonical_chapter
            )
        else:
            result = dry_run_summary(bundle, args.attach_to_existing_chapter, args.allow_noncanonical_chapter)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (OSError, json.JSONDecodeError, ValueError, RuntimeError) as exc:
        print(f"Draft import stopped safely: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
