#!/usr/bin/env python3
"""Import an editor-reviewed, original-content bundle into QuantaEdge as DRAFT only.

The SCERT extractor output is a source-review artifact, not an importable lesson.
This command deliberately rejects raw extraction bundles and never publishes content.
Use --apply to write to the admin API. The QE_SESSION cookie is read from the environment.
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

QUESTION_TYPES = {
    "MCQ", "TRUE_FALSE", "INPUT", "MATCH", "ORDER", "ASSERTION_REASON",
    "CASE_BASED", "SHORT_ANSWER", "LONG_ANSWER", "NUMERICAL", "DIAGRAM", "MAP", "SOURCE_BASED",
}
STUDENT_GRADABLE_TYPES = {"MCQ", "TRUE_FALSE", "INPUT", "NUMERICAL"}
BLOCK_TYPES = {
    "EXPLANATION", "IMAGE", "DIAGRAM", "VIDEO", "QUESTION", "MCQ", "TRUE_FALSE",
    "MATCH", "ORDER", "INPUT", "HINT", "AI_HELP", "SUMMARY", "CHALLENGE",
    "PREREQUISITE", "WORKED_EXAMPLE", "GUIDED_PRACTICE", "INDEPENDENT_PRACTICE", "RECAP",
}
CODE_RE = re.compile(r"[a-z0-9]+(?:-[a-z0-9]+)*")
SHA_RE = re.compile(r"[0-9a-fA-F]{64}")


def require_text(value: Any, name: str, max_length: int = 2000) -> str:
    if not isinstance(value, str) or not value.strip() or len(value.strip()) > max_length:
        raise ValueError(f"{name} must be non-empty text no longer than {max_length} characters.")
    return value.strip()


def page_range(value: Any, name: str, page_count: int) -> tuple[int, int]:
    text = require_text(value, name, 160)
    match = re.fullmatch(r"(?i)PDF pages?\s+(\d+)(?:\s*[-–]\s*(\d+))?", text)
    if not match:
        raise ValueError(f"{name} must use an explicit PDF page range such as 'PDF pages 20-28'.")
    start = int(match.group(1))
    end = int(match.group(2) or start)
    if start < 1 or end < start or end > page_count:
        raise ValueError(f"{name} must be within PDF pages 1-{page_count}.")
    return start, end


def validate_bundle(bundle: Any) -> dict[str, Any]:
    if not isinstance(bundle, dict):
        raise ValueError("The import bundle must be a JSON object.")
    if bundle.get("schema_version") != 1:
        raise ValueError("Only reviewed-content schema_version 1 is supported.")
    if bundle.get("bundle_status") != "EDITOR_REVIEWED_DRAFT":
        raise ValueError("Raw extraction bundles cannot be imported. Set bundle_status to EDITOR_REVIEWED_DRAFT only after editorial review.")
    if "pages" in bundle or "extraction" in bundle:
        raise ValueError("Do not import extracted textbook pages/text. Create original lesson content in the reviewed authoring bundle.")
    declaration = bundle.get("editorial_declaration")
    if not isinstance(declaration, dict) or declaration.get("original_content") is not True:
        raise ValueError("editorial_declaration.original_content=true is required.")
    if declaration.get("rights_reviewed") is not True:
        raise ValueError("editorial_declaration.rights_reviewed=true is required.")
    require_text(declaration.get("reviewer"), "editorial_declaration.reviewer", 160)
    require_text(declaration.get("reviewed_at"), "editorial_declaration.reviewed_at", 80)

    curriculum = bundle.get("curriculum")
    if not isinstance(curriculum, dict):
        raise ValueError("curriculum metadata is required.")
    class_code = str(curriculum.get("class_code", "")).strip()
    subject_code = str(curriculum.get("subject_code", "")).strip()
    if class_code not in {"6", "7", "8"} or subject_code not in {"maths", "science"}:
        raise ValueError("Only Bihar Board Class 6–8 Maths and Science tracks are supported.")

    source = bundle.get("source")
    if not isinstance(source, dict):
        raise ValueError("source metadata is required.")
    require_text(source.get("title"), "source.title", 300)
    source_url = require_text(source.get("url"), "source.url", 2000)
    if not source_url.startswith("https://"):
        raise ValueError("source.url must use HTTPS.")
    edition = require_text(source.get("edition"), "source.edition", 160)
    if edition.lower() in {"unknown", "unverified", "current", "n/a"}:
        raise ValueError("Record the edition/session from the actual downloaded source; do not use a placeholder.")
    if not isinstance(source.get("pdf_page_count"), int) or source["pdf_page_count"] < 1:
        raise ValueError("source.pdf_page_count must be a positive integer.")
    if not isinstance(source.get("pdf_sha256"), str) or not SHA_RE.fullmatch(source["pdf_sha256"]):
        raise ValueError("source.pdf_sha256 must be the SHA-256 of the reviewed source PDF.")

    chapter = bundle.get("chapter")
    if not isinstance(chapter, dict):
        raise ValueError("chapter metadata is required.")
    code = require_text(chapter.get("code"), "chapter.code", 60).lower()
    if not CODE_RE.fullmatch(code):
        raise ValueError("chapter.code must use lowercase letters, numbers and hyphens.")
    require_text(chapter.get("display_name"), "chapter.display_name", 160)
    chapter_start, chapter_end = page_range(chapter.get("source_pages"), "chapter.source_pages", source["pdf_page_count"])
    lessons = bundle.get("lessons")
    if not isinstance(lessons, list) or not lessons:
        raise ValueError("At least one original draft lesson is required.")
    seen_lessons: set[str] = set()
    for index, lesson in enumerate(lessons, 1):
        if not isinstance(lesson, dict):
            raise ValueError(f"lessons[{index - 1}] must be an object.")
        lesson_code = require_text(lesson.get("code"), f"lessons[{index - 1}].code", 100).lower()
        if not CODE_RE.fullmatch(lesson_code) or lesson_code in seen_lessons:
            raise ValueError(f"Lesson codes must be unique and use lowercase letters, numbers and hyphens: {lesson_code}")
        seen_lessons.add(lesson_code)
        require_text(lesson.get("title"), f"lessons[{index - 1}].title", 240)
        lesson_start, lesson_end = page_range(lesson.get("source_pages"), f"lessons[{index - 1}].source_pages", source["pdf_page_count"])
        if lesson_start < chapter_start or lesson_end > chapter_end:
            raise ValueError(f"Lesson {lesson_code} page range must fall inside the reviewed chapter page range.")
        minutes = lesson.get("estimated_minutes", 10)
        order = lesson.get("sort_order", index)
        if not isinstance(minutes, int) or not 1 <= minutes <= 120:
            raise ValueError(f"lessons[{index - 1}].estimated_minutes must be 1–120.")
        if not isinstance(order, int) or not 1 <= order <= 10000:
            raise ValueError(f"lessons[{index - 1}].sort_order must be 1–10000.")
        blocks = lesson.get("blocks")
        if not isinstance(blocks, list) or not blocks:
            raise ValueError(f"Lesson {lesson_code} needs at least one teaching block.")
        for bi, block in enumerate(blocks):
            if not isinstance(block, dict) or str(block.get("block_type", "")).upper() not in BLOCK_TYPES:
                raise ValueError(f"Lesson {lesson_code} block {bi + 1} has an unsupported block_type.")
            if not isinstance(block.get("content"), dict):
                raise ValueError(f"Lesson {lesson_code} block {bi + 1} content must be an authored JSON object.")
        questions = lesson.get("questions", [])
        if not isinstance(questions, list):
            raise ValueError(f"Lesson {lesson_code} questions must be an array.")
        seen_orders: set[int] = set()
        for qi, question in enumerate(questions):
            if not isinstance(question, dict):
                raise ValueError(f"Lesson {lesson_code} question {qi + 1} must be an object.")
            qtype = str(question.get("question_type", "")).upper()
            if qtype not in QUESTION_TYPES:
                raise ValueError(f"Lesson {lesson_code} question {qi + 1} has unsupported question_type {qtype}.")
            require_text(question.get("prompt"), f"{lesson_code}.questions[{qi}].prompt", 1000)
            difficulty = str(question.get("difficulty", "MEDIUM")).upper()
            if difficulty not in {"EASY", "MEDIUM", "HARD"}:
                raise ValueError(f"{lesson_code} question difficulty must be EASY, MEDIUM, or HARD.")
            qorder = question.get("sort_order", qi + 1)
            if not isinstance(qorder, int) or not 1 <= qorder <= 10000 or qorder in seen_orders:
                raise ValueError(f"{lesson_code} question sort_order must be unique and between 1 and 10000.")
            seen_orders.add(qorder)
            options = question.get("options", [])
            if not isinstance(options, list):
                raise ValueError(f"{lesson_code} question options must be an array.")
            if qtype in {"MCQ", "TRUE_FALSE"}:
                if not 2 <= len(options) <= 10:
                    raise ValueError(f"{lesson_code} {qtype} questions require 2–10 options.")
                keys: set[str] = set()
                correct = 0
                for option in options:
                    if not isinstance(option, dict):
                        raise ValueError("Every option must be an object.")
                    key = require_text(option.get("key"), "option.key", 20)
                    require_text(option.get("label"), "option.label", 500)
                    if key in keys:
                        raise ValueError("Option keys must be unique per question.")
                    keys.add(key)
                    correct += option.get("correct") is True
                if correct != 1:
                    raise ValueError(f"{lesson_code} {qtype} questions need exactly one correct option.")
            elif options:
                raise ValueError(f"{lesson_code} {qtype} should not contain choice options in the current CMS.")
            answer = question.get("answer_payload", {})
            if not isinstance(answer, dict):
                raise ValueError(f"{lesson_code} question answer_payload must be a JSON object.")
            if qtype in {"MCQ", "TRUE_FALSE"} and (
                answer.get("kind") != "OPTION"
                or answer.get("value") not in {o["key"] for o in options}
            ):
                raise ValueError(f"{lesson_code} {qtype} answer_payload must match one of its option keys.")
            if qtype == "INPUT" and (answer.get("kind") != "TEXT" or not isinstance(answer.get("value"), str)):
                raise ValueError(f"{lesson_code} INPUT questions need answer_payload kind TEXT and a string value.")
            if qtype == "NUMERICAL" and (answer.get("kind") != "NUMERIC" or "value" not in answer):
                raise ValueError(f"{lesson_code} NUMERICAL questions need answer_payload kind NUMERIC and a value.")
            if qtype not in STUDENT_GRADABLE_TYPES and answer:
                raise ValueError(f"{lesson_code} {qtype} is not yet supported for automatic grading; leave answer_payload empty and keep the item as a reviewed draft.")
    return bundle


def make_request_payloads(bundle: dict[str, Any], chapter_id: int | None = None) -> list[dict[str, Any]]:
    """Build API operations; all chapter/lesson/question statuses are deliberately draft."""
    curriculum = bundle["curriculum"]
    source = bundle["source"]
    chapter = bundle["chapter"]
    ops: list[dict[str, Any]] = []
    ops.append({
        "method": "POST",
        "path": "/api/v1/admin/content/chapters",
        "body": {
            "classCode": str(curriculum["class_code"]),
            "subjectCode": curriculum["subject_code"],
            "code": chapter["code"],
            "displayName": chapter["display_name"],
            "description": chapter.get("description", ""),
            "curriculumSource": source["title"],
            "curriculumSourceUrl": source["url"],
            "curriculumSourceEdition": source["edition"],
            "curriculumSourcePages": chapter["source_pages"],
            "sortOrder": int(chapter.get("sort_order", 1)),
            "status": "DRAFT",
        },
        "result_id": "chapter_id",
    })
    for index, lesson in enumerate(bundle["lessons"], 1):
        ops.append({
            "method": "POST",
            "path": "/api/v1/admin/content/lessons",
            "body": {
                "chapterId": chapter_id if chapter_id is not None else "$chapter_id",
                "code": lesson["code"],
                "title": lesson["title"],
                "summary": lesson.get("summary", ""),
                "estimatedMinutes": int(lesson.get("estimated_minutes", 10)),
                "alignmentSourceTitle": source["title"],
                "alignmentSourceUrl": source["url"],
                "alignmentSourceEdition": source["edition"],
                "alignmentPageRange": lesson["source_pages"],
                "sortOrder": int(lesson.get("sort_order", index)),
                "status": "DRAFT",
            },
            "result_id": "lesson_id",
            "lesson_index": index - 1,
        })
        ops.append({
            "method": "PATCH",
            "path": "/api/v1/admin/content/lessons/$lesson_id",
            "body": {
                "title": lesson["title"],
                "summary": lesson.get("summary", ""),
                "estimatedMinutes": int(lesson.get("estimated_minutes", 10)),
                "alignmentSourceTitle": source["title"],
                "alignmentSourceUrl": source["url"],
                "alignmentSourceEdition": source["edition"],
                "alignmentPageRange": lesson["source_pages"],
                "alignmentSourceVerified": False,
                "status": "DRAFT",
                "sortOrder": int(lesson.get("sort_order", index)),
                "blocks": [
                    {
                        "sequence_no": int(block.get("sequence_no", bi + 1)),
                        "block_type": str(block["block_type"]).upper(),
                        "content": block["content"],
                        "active": True,
                    }
                    for bi, block in enumerate(lesson["blocks"])
                ],
                "questions": [
                    {
                        "question_type": str(question["question_type"]).upper(),
                        "prompt": question["prompt"],
                        "explanation": question.get("explanation", ""),
                        "difficulty": {"EASY": "FOUNDATION", "MEDIUM": "CORE", "HARD": "CHALLENGE", "FOUNDATION": "FOUNDATION", "CORE": "CORE", "CHALLENGE": "CHALLENGE"}.get(str(question.get("difficulty", "MEDIUM")).upper(), "CORE"),
                        "sort_order": int(question.get("sort_order", qi + 1)),
                        "active": True,
                        "review_status": "DRAFT",
                        "review_notes": "Imported as draft; editorial/source verification and approval required.",
                        "marks": question.get("marks"),
                        "exam_format": question.get("exam_format"),
                        "source_kind": "AUTHOR_CREATED",
                        "source_title": question.get("source_title", "QuantaEdge original curriculum-aligned practice"),
                        "source_ref": question.get("source_ref"),
                        "source_year": None,
                        "source_id": None,
                        "board": "Bihar Board",
                        "topic": question.get("topic"),
                        "subtopic": question.get("subtopic"),
                        "skill": question.get("skill"),
                        "tags": question.get("tags", ["original", "curriculum-aligned"]),
                        "answer_payload": question.get("answer_payload", {}),
                        "options": question.get("options", []),
                    }
                    for qi, question in enumerate(lesson.get("questions", []))
                ],
            },
            "result_id": None,
            "lesson_index": index - 1,
        })
    return ops


class AdminApi:
    def __init__(self, base_url: str, session_cookie: str, timeout: int = 20):
        self.base_url = base_url.rstrip("/")
        self.session_cookie = session_cookie
        self.timeout = timeout

    def request(self, method: str, path: str, body: dict[str, Any] | None = None) -> dict[str, Any] | list[Any]:
        url = self.base_url + path
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Accept", "application/json")
        req.add_header("Cookie", "QE_SESSION=" + self.session_cookie)
        if data is not None:
            req.add_header("Content-Type", "application/json; charset=utf-8")
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as response:
                raw = response.read().decode("utf-8")
                return json.loads(raw) if raw.strip() else {}
        except urllib.error.HTTPError as exc:
            message = exc.read().decode("utf-8", errors="replace")[:1000]
            if exc.code in {401, 403}:
                raise RuntimeError("Admin session rejected. Sign in as an administrator and refresh QUANTAEDGE_ADMIN_SESSION.") from exc
            raise RuntimeError(f"{method} {path} failed with HTTP {exc.code}: {message}") from exc
        except urllib.error.URLError as exc:
            raise RuntimeError(f"Cannot reach QuantaEdge admin API at {url}: {exc.reason}") from exc


def run_import(bundle: dict[str, Any], base_url: str, session: str, dry_run: bool, attach_existing_chapter: bool = False) -> dict[str, Any]:
    validate_bundle(bundle)
    curriculum = bundle["curriculum"]
    chapter = bundle["chapter"]
    if dry_run:
        return {
            "mode": "DRY_RUN",
            "class_code": curriculum["class_code"],
            "subject_code": curriculum["subject_code"],
            "chapter_code": chapter["code"],
            "chapter_mode": "ATTACH_TO_EXISTING" if attach_existing_chapter else "CREATE_NEW",
            "lessons": len(bundle["lessons"]),
            "questions": sum(len(x.get("questions", [])) for x in bundle["lessons"]),
            "all_statuses": "DRAFT",
            "student_delivery_allowed": False,
        }
    if not session:
        raise ValueError("QUANTAEDGE_ADMIN_SESSION is required when using --apply.")
    api = AdminApi(base_url, session)
    params = urllib.parse.urlencode({"classCode": curriculum["class_code"], "subjectCode": curriculum["subject_code"]})
    rows = api.request("GET", "/api/v1/admin/content?" + params)
    if not isinstance(rows, list):
        raise RuntimeError("Admin content API did not return a content list.")
    existing = [
        row for row in rows
        if row.get("chapter_code") == chapter["code"]
        and str(row.get("class_code")) == str(curriculum["class_code"])
        and row.get("subject_code") == curriculum["subject_code"]
    ]
    lesson_codes = {lesson["code"] for lesson in bundle["lessons"]}
    existing_lesson_codes = {row.get("lesson_code") for row in rows if row.get("lesson_code")}
    collisions = sorted(lesson_codes & existing_lesson_codes)
    if collisions:
        raise RuntimeError("Lesson code(s) already exist in the selected track: " + ", ".join(collisions) + ". Import stopped before creating anything.")

    if attach_existing_chapter:
        if not existing:
            raise RuntimeError("No matching existing chapter was found for this class/subject/code. Import stopped before creating anything.")
        chapter_ids = {int(row["chapter_id"]) for row in existing if row.get("chapter_id") is not None}
        if len(chapter_ids) != 1:
            raise RuntimeError("Matching chapter rows did not resolve to exactly one chapter ID. Inspect the Admin Content Studio before retrying.")
        chapter_row = existing[0]
        if chapter_row.get("chapter_active") is False or str(chapter_row.get("chapter_status", "")).upper() == "ARCHIVED":
            raise RuntimeError("The matching chapter is inactive/archived. Reactivate it through the CMS before importing drafts.")
        chapter_id = chapter_ids.pop()
    else:
        if existing:
            raise RuntimeError("A chapter with this code already exists in this class/subject. Use --attach-to-existing-chapter to add new draft lessons under the existing chapter; existing content is never overwritten.")
        created = api.request("POST", "/api/v1/admin/content/chapters", make_request_payloads(bundle)[0]["body"])
        if not isinstance(created, dict) or not created.get("chapter_id"):
            raise RuntimeError("Chapter creation returned no chapter_id; stop and inspect the admin CMS before retrying.")
        chapter_id = int(created["chapter_id"])
    lesson_ids: list[int] = []
    for index, lesson in enumerate(bundle["lessons"], 1):
        lesson_body = {
            "chapterId": chapter_id,
            "code": lesson["code"],
            "title": lesson["title"],
            "summary": lesson.get("summary", ""),
            "estimatedMinutes": int(lesson.get("estimated_minutes", 10)),
            "alignmentSourceTitle": bundle["source"]["title"],
            "alignmentSourceUrl": bundle["source"]["url"],
            "alignmentSourceEdition": bundle["source"]["edition"],
            "alignmentPageRange": lesson["source_pages"],
            "sortOrder": int(lesson.get("sort_order", index)),
            "status": "DRAFT",
        }
        created_lesson = api.request("POST", "/api/v1/admin/content/lessons", lesson_body)
        if not isinstance(created_lesson, dict) or not created_lesson.get("lesson_id"):
            raise RuntimeError(f"Lesson {lesson['code']} creation returned no lesson_id; chapter {chapter_id} remains draft.")
        lesson_id = int(created_lesson["lesson_id"])
        lesson_ids.append(lesson_id)
        patch = make_request_payloads({**bundle, "lessons": [lesson]})[-1]["body"]
        patch["alignmentSourceVerified"] = False
        api.request("PATCH", f"/api/v1/admin/content/lessons/{lesson_id}", patch)
    return {
        "mode": "APPLIED_DRAFT_ONLY",
        "chapter_mode": "ATTACHED_EXISTING" if attach_existing_chapter else "CREATED_DRAFT",
        "chapter_id": chapter_id,
        "lesson_ids": lesson_ids,
        "class_code": curriculum["class_code"],
        "subject_code": curriculum["subject_code"],
        "chapter_code": chapter["code"],
        "question_count": sum(len(x.get("questions", [])) for x in bundle["lessons"]),
        "all_statuses": "DRAFT",
        "student_delivery_allowed": False,
        "note": "Source mappings are unverified and every imported question is DRAFT. Review in the admin CMS before any publication.",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", required=True, type=Path, help="Editor-reviewed authoring JSON; not the raw SCERT extraction JSON")
    parser.add_argument("--api-base-url", default=os.environ.get("QUANTAEDGE_API_BASE_URL", "http://localhost:8080"))
    parser.add_argument("--attach-to-existing-chapter", action="store_true", help="Add new draft lessons beneath the matching existing chapter code instead of creating a duplicate chapter.")
    parser.add_argument("--apply", action="store_true", help="Write draft content to the admin API; without this flag the command only validates.")
    args = parser.parse_args()
    if not args.bundle.is_file():
        parser.error(f"Bundle does not exist: {args.bundle}")
    base = args.api_base_url.rstrip("/")
    if not (base.startswith("https://") or base.startswith("http://localhost") or base.startswith("http://127.0.0.1")):
        parser.error("Use HTTPS for non-local admin API URLs.")
    try:
        bundle = json.loads(args.bundle.read_text(encoding="utf-8"))
        result = run_import(bundle, base, os.environ.get("QUANTAEDGE_ADMIN_SESSION", ""), dry_run=not args.apply, attach_existing_chapter=args.attach_to_existing_chapter)
    except (ValueError, json.JSONDecodeError, RuntimeError) as exc:
        print(f"Import stopped safely: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
