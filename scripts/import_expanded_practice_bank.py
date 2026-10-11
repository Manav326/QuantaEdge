#!/usr/bin/env python3
"""Merge expanded practice questions into existing QuantaEdge lessons without unpublishing them.

Safe defaults:
- no writes unless --apply is supplied;
- the dedicated API appends questions only and leaves lesson status/blocks untouched;
- all added questions stay DRAFT and require separate review/approval;
- stable package tags and exact prompt matching prevent duplicate imports.

The separate complete_book_extended_practice list is intentionally not imported by this script.
It stays in the authoring ZIP for the reviewed complete-book/written-practice pipeline.
"""
from __future__ import annotations

import argparse
import copy
import json
import os
import re
import sys
import unicodedata
import urllib.parse
import zipfile
from collections import Counter
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

try:
    from import_ncert_class6_drafts import make_codes_unique
    from import_site_content_draft import AdminApi, _question_for_api, validate_site_bundle
except ImportError as exc:
    raise SystemExit(
        "Missing existing QuantaEdge content import helpers. Run this script from the repository checkout."
    ) from exc

DEFAULT_PACKAGES = (
    "QuantaEdge_Class6_Hindi_Practice_Expanded_Package.zip",
    "QuantaEdge_Class7_Hindi_Practice_Expanded_Package.zip",
    "QuantaEdge_Class8_Hindi_Practice_Expanded_Package.zip",
)
SUPPORTED_ONLINE_TYPES = {"MCQ", "TRUE_FALSE", "INPUT", "NUMERICAL"}
DIFFICULTY_MAP = {"EASY": "FOUNDATION", "MEDIUM": "CORE", "HARD": "CHALLENGE"}


def norm_text(value: Any) -> str:
    text = unicodedata.normalize("NFKC", str(value or ""))
    return re.sub(r"\s+", " ", text).strip().casefold()


def parse_json_field(value: Any, fallback: Any) -> Any:
    if value is None:
        return copy.deepcopy(fallback)
    if isinstance(value, (dict, list, int, float, bool)):
        return copy.deepcopy(value)
    raw = str(value).strip()
    if not raw:
        return copy.deepcopy(fallback)
    try:
        return json.loads(raw)
    except json.JSONDecodeError as exc:
        raise ValueError(f"Malformed JSON returned by the Admin API: {raw[:100]!r}") from exc


def normalise_chapter_test_questions(bundle: dict[str, Any]) -> dict[str, Any]:
    """Route the special chapter-test record to a real micro-topic by exact subtopic."""
    result = copy.deepcopy(bundle)
    lesson_codes = {str(lesson["code"]) for lesson in result.get("lessons", [])}
    bank = result.get("online_question_bank", [])
    by_subtopic: dict[str, list[str]] = {}
    for question in bank:
        code = str(question.get("lesson_code", ""))
        if code in lesson_codes and "chapter-end" not in question.get("tags", []):
            key = norm_text(question.get("subtopic"))
            if key:
                by_subtopic.setdefault(key, []).append(code)

    for question in bank:
        code = str(question.get("lesson_code", ""))
        if code in lesson_codes:
            continue
        key = norm_text(question.get("subtopic"))
        candidates = sorted(set(by_subtopic.get(key, [])))
        if not candidates:
            raise ValueError(
                f"Question {question.get('id')} points to non-lesson code {code!r} "
                f"and has no exact subtopic match for safe routing."
            )
        question["lesson_code"] = candidates[0]
    return result


def read_package(path: Path) -> list[tuple[str, dict[str, Any]]]:
    if not path.is_file():
        raise ValueError(f"Package file does not exist: {path}")
    try:
        archive = zipfile.ZipFile(path)
    except zipfile.BadZipFile as exc:
        raise ValueError(f"Not a valid ZIP package: {path}") from exc
    with archive:
        members = [
            name for name in archive.namelist()
            if "/content/bundles/" in f"/{name}" and name.endswith(".json")
            and ("/maths/" in f"/{name}" or "/science/" in f"/{name}")
        ]
        if not members:
            raise ValueError(f"No maths/science content/bundles JSON files found in {path.name}.")
        bundles: list[tuple[str, dict[str, Any]]] = []
        for member in sorted(members):
            try:
                raw = json.loads(archive.read(member).decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError) as exc:
                raise ValueError(f"Invalid UTF-8 JSON in {member}: {exc}") from exc
            routed = normalise_chapter_test_questions(raw)
            transformed = make_codes_unique(routed)
            summary = validate_site_bundle(transformed, allow_noncanonical_chapter=True)
            if summary["all_statuses"] != "DRAFT" or summary["auto_publish"] is not False:
                raise ValueError(f"{member} is not a draft-only bundle.")
            if len(transformed["complete_book_extended_practice"]) != 9:
                raise ValueError(f"{member} must have exactly 9 separate written practice items.")
            lesson_codes = {str(x["code"]) for x in transformed["lessons"]}
            topic_counts: Counter[str] = Counter()
            ids: set[str] = set()
            for question in transformed["online_question_bank"]:
                qid = str(question["id"])
                if qid in ids:
                    raise ValueError(f"Duplicate question ID {qid} in {member}.")
                ids.add(qid)
                if question["question_type"] not in SUPPORTED_ONLINE_TYPES:
                    raise ValueError(f"Unsupported online question type in {member}: {question['question_type']}.")
                if len(str(question.get("prompt", ""))) > 1000:
                    raise ValueError(f"Question {qid} exceeds the API prompt limit of 1,000 characters.")
                if question["lesson_code"] not in lesson_codes:
                    raise ValueError(f"Question {qid} still points to a missing lesson.")
                if "chapter-end" not in question.get("tags", []):
                    topic_counts[question["lesson_code"]] += 1
            if any(topic_counts[code] != 3 for code in lesson_codes):
                raise ValueError(f"{member} must contain exactly 3 micro-topic questions per lesson.")
            bundles.append((member, transformed))
    return bundles


def package_summary(packages: list[Path]) -> dict[str, Any]:
    summary: dict[str, Any] = {
        "mode": "DRY_RUN" ,
        "packages": [],
        "chapters": 0,
        "lessons": 0,
        "online_questions_in_package": 0,
        "microtopic_questions": 0,
        "chapter_end_online_questions": 0,
        "separate_written_practice_items": 0,
        "writes_performed": False,
    }
    for package in packages:
        bundles = read_package(package)
        item = {
            "package": str(package),
            "chapters": len(bundles),
            "lessons": sum(len(b["lessons"]) for _, b in bundles),
            "online_questions": sum(len(b["online_question_bank"]) for _, b in bundles),
            "microtopic_questions": sum(
                1 for _, b in bundles for q in b["online_question_bank"]
                if "chapter-end" not in q.get("tags", [])
            ),
            "chapter_end_online_questions": sum(
                1 for _, b in bundles for q in b["online_question_bank"]
                if "chapter-end" in q.get("tags", [])
            ),
            "separate_written_practice_items": sum(
                len(b["complete_book_extended_practice"]) for _, b in bundles
            ),
            "tracks": sorted({
                f"Class {b['curriculum']['class_code']} / {b['curriculum']['subject_code']}"
                for _, b in bundles
            }),
        }
        summary["packages"].append(item)
        for key in ("chapters", "lessons", "online_questions", "microtopic_questions",
                    "chapter_end_online_questions", "separate_written_practice_items"):
            target = "online_questions_in_package" if key == "online_questions" else key
            summary[target] += item[key]
    summary["note"] = (
        "Dry run validates ZIP/JSON only. Online question-bank records can be merged into existing lessons. "
        "The separate written-practice array is reported but not inserted into the current auto-graded question API."
    )
    return summary


def api_question_to_patch(question: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": int(question["id"]),
        "question_type": str(question["question_type"]).upper(),
        "prompt": str(question["prompt"]),
        "explanation": question.get("explanation"),
        "difficulty": str(question["difficulty"]).upper(),
        "sort_order": int(question.get("sort_order") or 1),
        "active": bool(question.get("active", True)),
        "marks": question.get("marks"),
        "exam_format": question.get("exam_format"),
        "source_kind": str(question.get("source_kind") or "AUTHOR_CREATED").upper(),
        "source_title": question.get("source_title"),
        "source_ref": question.get("source_ref"),
        "source_year": question.get("source_year"),
        "source_id": question.get("source_id"),
        "board": question.get("board"),
        "topic": question.get("topic"),
        "subtopic": question.get("subtopic"),
        "skill": question.get("skill"),
        "tags": parse_json_field(question.get("tags"), []),
        "answer_payload": parse_json_field(question.get("answer_payload"), {}),
        "options": parse_json_field(question.get("options"), []),
    }


def build_incoming_question(source: dict[str, Any], order: int) -> dict[str, Any]:
    question = _question_for_api(source, order)
    question["sort_order"] = order
    tags = list(question.get("tags") or [])
    stable_tag = "qe-practice-id:" + str(source["id"])
    if stable_tag not in tags:
        tags.append(stable_tag)
    question["tags"] = tags
    question["review_status"] = "DRAFT"
    return question


def question_tags(question: dict[str, Any]) -> list[str]:
    return [str(tag) for tag in parse_json_field(question.get("tags"), [])]


def prepare_patch(current: dict[str, Any], incoming: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], int, int]:
    status = str(current.get("lesson_status", "")).upper()
    if status not in {"DRAFT", "REVIEW", "PUBLISHED"}:
        raise ValueError(
            f"Lesson {current.get('lesson_code')} is {status or 'UNKNOWN'}. "
            "Only active DRAFT, REVIEW, or PUBLISHED lessons can receive draft practice questions."
        )

    existing = [api_question_to_patch(q) for q in current.get("questions", [])]
    existing_ids = {q["id"] for q in existing}
    if len(existing_ids) != len(existing):
        raise ValueError(f"Duplicate database question IDs in lesson {current.get('lesson_code')}.")

    all_prompts = {norm_text(q["prompt"]) for q in existing}
    existing_tags = {tag for q in existing for tag in question_tags(q)}
    max_order = max((int(q.get("sort_order") or 0) for q in existing), default=0)
    additions: list[dict[str, Any]] = []
    skipped = 0
    for source in incoming:
        stable_tag = "qe-practice-id:" + str(source["id"])
        prompt_key = norm_text(source.get("prompt"))
        if stable_tag in existing_tags or prompt_key in all_prompts:
            skipped += 1
            continue
        max_order += 1
        question = build_incoming_question(source, max_order)
        # The append-only API accepts only new questions. Do not send existing IDs or teaching blocks.
        additions.append(question)
        all_prompts.add(prompt_key)
        existing_tags.add(stable_tag)

    return additions, len(additions), skipped


def preflight(packages: list[Path], api: AdminApi) -> list[dict[str, Any]]:
    plans: list[dict[str, Any]] = []
    catalog_cache: dict[tuple[str, str], list[dict[str, Any]]] = {}
    for package in packages:
        for member, bundle in read_package(package):
            curriculum = bundle["curriculum"]
            track = (str(curriculum["class_code"]), str(curriculum["subject_code"]))
            if track not in catalog_cache:
                query = urllib.parse.urlencode({
                    "classCode": track[0], "subjectCode": track[1], "status": "ALL"
                })
                rows = api.request("GET", "/api/v1/admin/content?" + query)
                if not isinstance(rows, list):
                    raise ValueError(f"Admin catalog returned an unexpected response for {track}.")
                catalog_cache[track] = rows
            rows = catalog_cache[track]
            chapter_code = str(bundle["chapter"]["code"])
            chapter_rows = [row for row in rows if str(row.get("chapter_code", "")) == chapter_code]
            chapter_ids = {int(row["chapter_id"]) for row in chapter_rows if row.get("chapter_id") is not None}
            if len(chapter_ids) != 1:
                raise ValueError(
                    f"{track} chapter {chapter_code}: expected one existing chapter, found {len(chapter_ids)}. "
                    "No writes have been made."
                )
            chapter_id = next(iter(chapter_ids))
            lesson_rows = {
                str(row.get("lesson_code")): row
                for row in chapter_rows
                if row.get("lesson_code") and int(row.get("chapter_id") or -1) == chapter_id
            }
            bundle_lessons = {str(lesson["code"]): lesson for lesson in bundle["lessons"]}
            missing = sorted(set(bundle_lessons) - set(lesson_rows))
            if missing:
                raise ValueError(
                    f"{track} / {chapter_code}: these lessons are not in the CMS: {missing[:8]}. "
                    "No writes have been made."
                )
            question_groups: dict[str, list[dict[str, Any]]] = {code: [] for code in bundle_lessons}
            for question in bundle["online_question_bank"]:
                question_groups[str(question["lesson_code"])].append(question)

            for lesson_code, lesson in bundle_lessons.items():
                row = lesson_rows[lesson_code]
                lesson_id = int(row["lesson_id"])
                current = api.request("GET", f"/api/v1/admin/content/lessons/{lesson_id}")
                if not isinstance(current, dict):
                    raise ValueError(f"Unexpected lesson detail response for lesson_id={lesson_id}.")
                if int(current.get("chapter_id") or -1) != chapter_id:
                    raise ValueError(
                        f"Lesson {lesson_code} resolved outside chapter {chapter_code}. No writes have been made."
                    )
                additions, added, skipped = prepare_patch(current, question_groups[lesson_code])
                plans.append({
                    "package": package.name,
                    "source_file": Path(member).name,
                    "class_code": track[0],
                    "subject_code": track[1],
                    "chapter_code": chapter_code,
                    "chapter_id": chapter_id,
                    "lesson_code": lesson_code,
                    "lesson_id": lesson_id,
                    "current_question_count": len(current.get("questions", [])),
                    "new_questions": added,
                    "already_present": skipped,
                    "questions": additions,
                })
    lesson_ids = [x["lesson_id"] for x in plans]
    if len(lesson_ids) != len(set(lesson_ids)):
        raise ValueError("Two bundles map to the same database lesson ID. No writes have been made.")
    return plans


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--package", action="append", type=Path,
        help="Expanded class ZIP (repeat --package for multiple classes). With none, all three standard class ZIPs are required."
    )
    parser.add_argument("--apply", action="store_true", help="Append new online questions as DRAFT through the authenticated Admin API.")
    parser.add_argument("--api-url", default=os.environ.get("QUANTAEDGE_API_BASE_URL", "http://localhost:8080"))
    args = parser.parse_args()

    packages = args.package or [ROOT / name for name in DEFAULT_PACKAGES]
    packages = [p.resolve() for p in packages]
    absent = [str(p) for p in packages if not p.is_file()]
    if absent:
        parser.error("Missing package ZIP(s): " + ", ".join(absent))

    try:
        summary = package_summary(packages)
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, json.JSONDecodeError) as exc:
        parser.error(str(exc))

    if not args.apply:
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return 0

    session = os.environ.get("QUANTAEDGE_ADMIN_SESSION", "").strip()
    if not session:
        parser.error("--apply requires QUANTAEDGE_ADMIN_SESSION from a fresh, authenticated Admin QE_SESSION cookie.")
    if not args.api_url.startswith(("https://", "http://localhost", "http://127.0.0.1")):
        parser.error("Use HTTPS for remote APIs; HTTP is permitted only for localhost.")

    api = AdminApi(args.api_url, session)
    try:
        plans = preflight(packages, api)
        to_write = [plan for plan in plans if plan["new_questions"] > 0]
        report = {
            "mode": "APPLY_DRAFT_ONLY",
            "packages": [str(p) for p in packages],
            "chapters_checked": summary["chapters"],
            "lessons_checked": len(plans),
            "lessons_to_update": len(to_write),
            "new_online_questions_to_add": sum(p["new_questions"] for p in plans),
            "already_present_or_duplicate": sum(p["already_present"] for p in plans),
            "publication_or_approval_attempted": False,
            "chapter_end_online_questions_in_packages": summary["chapter_end_online_questions"],
            "chapter_end_online_questions_planned_to_add": sum(
                1 for plan in to_write for question in plan["questions"]
                if "chapter-end" in question_tags(question)
            ),
            "separate_written_practice_items_in_packages": summary["separate_written_practice_items"],
            "separate_written_practice_items_imported": 0,
            "separate_written_practice_items_left_in_packages": summary["separate_written_practice_items"],
            "package_inventory": summary["packages"],
            "results": [],
        }
        # All catalogue and lesson mappings are preflighted before the first write.
        for plan in to_write:
            response = api.request(
                "POST",
                f"/api/v1/admin/content/lessons/{plan['lesson_id']}/questions/append-drafts",
                {"questions": plan["questions"]},
            )
            if not isinstance(response, dict):
                raise ValueError(f"Append endpoint returned an unexpected response for lesson {plan['lesson_id']}.")
            actual_added = int(response.get("added", -1))
            if actual_added < 0 or actual_added > plan["new_questions"]:
                raise ValueError(f"Append endpoint returned an invalid added count for lesson {plan['lesson_id']}.")
            report["results"].append({
                "class_code": plan["class_code"],
                "subject_code": plan["subject_code"],
                "chapter_code": plan["chapter_code"],
                "lesson_code": plan["lesson_code"],
                "lesson_id": plan["lesson_id"],
                "planned_new_questions": plan["new_questions"],
                "added": actual_added,
                "skipped_duplicate": int(response.get("skippedDuplicate", 0)),
                "lesson_status": response.get("lessonStatus"),
                "lesson_status_changed": response.get("lessonStatusChanged"),
                "teaching_blocks_changed": response.get("teachingBlocksChanged"),
                "question_status": response.get("newQuestionStatus"),
            })
        report["writes_performed"] = True
        report["note"] = (
            "Only online_question_bank records were merged. All new questions remain DRAFT. "
            "Online chapter-end items are counted separately above. The complete_book_extended_practice arrays "
            "remain unimported in the ZIP because the current Admin API does not expose a safe dedicated "
            "chapter-end written-practice import contract; review the reported count and package inventory."
        )
        print(json.dumps(report, ensure_ascii=False, indent=2))
    except Exception as exc:
        print(json.dumps({
            "mode": "STOPPED_SAFELY",
            "error": str(exc),
            "note": "Preflight failures occur before writes. The append-only endpoint uses stable qe-practice-id tags and exact-prompt deduplication for safe retries; it never changes lesson status or teaching blocks.",
        }, ensure_ascii=False, indent=2), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
