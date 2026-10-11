#!/usr/bin/env python3
"""Idempotently attach QuantaEdge's structured practice-bank JSON to CMS lessons as DRAFT questions.

This is a JSON-to-CMS import stage, not a PDF importer and not a translator.
It validates stable question IDs/answer payloads, maps (class, subject, chapter, lesson)
to existing CMS records, then uses the admin append-drafts API. It never approves or publishes.
Run the bilingual database translator after this importer so these questions are included.
"""
from __future__ import annotations

import argparse
import collections
import json
import os
import sys
import urllib.parse
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT / "scripts"))
try:
    from import_site_content_draft import AdminApi
except ImportError as exc:  # pragma: no cover - checkout packaging error
    raise SystemExit("Missing scripts/import_site_content_draft.py; run this from the QuantaEdge repository.") from exc

CATALOG_SCHEMA = "quantaedge.practice-question-catalog-draft.v1"
ALLOWED_CLASSES = {"6", "7", "8"}
ALLOWED_SUBJECTS = {"maths", "science"}
ALLOWED_TYPES = {"MCQ", "TRUE_FALSE", "INPUT", "NUMERICAL"}
DIFFICULTY_MAP = {"EASY": "FOUNDATION", "MEDIUM": "CORE", "HARD": "CHALLENGE"}
MAX_APPEND_BATCH = 200


def csv_codes(raw: str, allowed: set[str], label: str) -> list[str]:
    values = [part.strip().lower() for part in raw.split(",") if part.strip()]
    if not values or any(value not in allowed for value in values):
        raise ValueError(f"{label} must contain comma-separated values from: {', '.join(sorted(allowed))}")
    return list(dict.fromkeys(values))


def load_catalog(path: Path) -> dict[str, Any]:
    if not path.is_file():
        raise ValueError(f"JSON practice catalog does not exist: {path}")
    try:
        catalog = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"Cannot read a valid UTF-8 JSON practice catalog: {exc}") from exc
    if not isinstance(catalog, dict) or catalog.get("schema") != CATALOG_SCHEMA:
        raise ValueError(f"Expected catalog schema {CATALOG_SCHEMA}.")
    if catalog.get("publish_status") != "DRAFT" or catalog.get("student_delivery_allowed") is not False:
        raise ValueError("The practice catalog must remain DRAFT with student_delivery_allowed=false.")
    banks = catalog.get("chapter_banks")
    if not isinstance(banks, list):
        raise ValueError("chapter_banks must be a JSON array.")
    seen_banks: set[tuple[str, str, str]] = set()
    seen_question_ids: set[str] = set()
    for bank_index, bank in enumerate(banks):
        where = f"chapter_banks[{bank_index}]"
        if not isinstance(bank, dict) or bank.get("schema") != "quantaedge.practice-question-bank-draft.v1":
            raise ValueError(f"{where}: unsupported question bank schema.")
        class_code = str(bank.get("class_code", "")).strip()
        subject_code = str(bank.get("subject_code", "")).strip().lower()
        language = str(bank.get("language", "")).strip().lower()
        chapter = bank.get("chapter")
        if class_code not in ALLOWED_CLASSES or subject_code not in ALLOWED_SUBJECTS or language not in {"hi", "en"}:
            raise ValueError(f"{where}: unsupported class/subject/language metadata.")
        if not isinstance(chapter, dict) or not str(chapter.get("code", "")).strip():
            raise ValueError(f"{where}: chapter.code is required.")
        key = (class_code, subject_code, str(chapter["code"]).strip())
        if key in seen_banks:
            raise ValueError(f"{where}: duplicate chapter bank {key}.")
        seen_banks.add(key)
        questions = bank.get("questions")
        if not isinstance(questions, list):
            raise ValueError(f"{where}: questions must be a JSON array.")
        for question_index, question in enumerate(questions):
            qwhere = f"{where}.questions[{question_index}]"
            if not isinstance(question, dict):
                raise ValueError(f"{qwhere}: question must be an object.")
            stable_id = str(question.get("id", "")).strip()
            if not stable_id or stable_id in seen_question_ids:
                raise ValueError(f"{qwhere}: question ID is blank or duplicated: {stable_id!r}.")
            seen_question_ids.add(stable_id)
            question_type = str(question.get("question_type", "")).strip().upper()
            difficulty = str(question.get("difficulty", "")).strip().upper()
            if question_type not in ALLOWED_TYPES or difficulty not in DIFFICULTY_MAP:
                raise ValueError(f"{qwhere}: unsupported question_type/difficulty.")
            if not isinstance(question.get("lesson_code"), str) or not question["lesson_code"].strip():
                raise ValueError(f"{qwhere}: lesson_code is required.")
            if not isinstance(question.get("prompt"), str) or not question["prompt"].strip():
                raise ValueError(f"{qwhere}: prompt is required.")
            answer = question.get("answer_payload")
            if not isinstance(answer, dict):
                raise ValueError(f"{qwhere}: answer_payload must be an object.")
            options = question.get("options", [])
            if not isinstance(options, list):
                raise ValueError(f"{qwhere}: options must be an array.")
            if question_type in {"MCQ", "TRUE_FALSE"}:
                if len(options) < 2:
                    raise ValueError(f"{qwhere}: choice questions need at least two options.")
                keys = [str(opt.get("key", "")).strip() for opt in options if isinstance(opt, dict)]
                correct = [str(opt.get("key", "")).strip() for opt in options
                           if isinstance(opt, dict) and opt.get("correct") is True]
                if len(keys) != len(options) or any(not value for value in keys) or len(set(keys)) != len(keys):
                    raise ValueError(f"{qwhere}: option keys must be non-empty and unique.")
                if len(correct) != 1 or answer.get("kind") != "OPTION" or answer.get("value") != correct[0]:
                    raise ValueError(f"{qwhere}: answer payload must match the single correct option.")
            elif options:
                raise ValueError(f"{qwhere}: only MCQ/true-false questions may have choice options.")
            if question_type == "INPUT" and (answer.get("kind") != "TEXT" or not str(answer.get("value", "")).strip()):
                raise ValueError(f"{qwhere}: INPUT requires a non-empty TEXT answer.")
            if question_type == "NUMERICAL" and answer.get("kind") != "NUMERIC":
                raise ValueError(f"{qwhere}: NUMERICAL requires a NUMERIC answer payload.")
    return catalog


def selected_banks(catalog: dict[str, Any], classes: list[str], subjects: list[str],
                   chapter_code: str | None) -> list[dict[str, Any]]:
    chosen = []
    for bank in catalog["chapter_banks"]:
        if str(bank["class_code"]) not in classes or str(bank["subject_code"]).lower() not in subjects:
            continue
        if chapter_code and str(bank["chapter"]["code"]) != chapter_code:
            continue
        chosen.append(bank)
    if not chosen:
        raise ValueError("No chapter banks match the selected class/subject/chapter filters.")
    return sorted(chosen, key=lambda b: (int(b["class_code"]), b["subject_code"],
                                        int(b["chapter"].get("sort_order", 0)), b["chapter"]["code"]))


def question_payload(question: dict[str, Any], sort_order: int, class_code: str,
                     subject_code: str, bank_language: str) -> dict[str, Any]:
    question_type = str(question["question_type"]).upper()
    tags = [str(tag) for tag in question.get("tags", []) if str(tag).strip()]
    stable_tag = "qe-practice-id:" + str(question["id"])
    tags = [tag for tag in tags if tag != stable_tag]
    tags.append(stable_tag)
    # Mark every imported item for staff review; don't treat AI-authored answer keys as verified.
    if "editorial-review-required" not in tags:
        tags.append("editorial-review-required")
    options = []
    for index, option in enumerate(question.get("options", []), start=1):
        options.append({
            "key": str(option["key"]),
            "label": str(option["label"]),
            "correct": bool(option.get("correct", False)),
            "sort_order": index,
        })
    payload = {
        "question_type": question_type,
        "prompt": str(question["prompt"]).strip(),
        "explanation": str(question.get("explanation", "")).strip(),
        "difficulty": DIFFICULTY_MAP[str(question["difficulty"]).upper()],
        "sort_order": sort_order,
        "active": True,
        "review_status": "DRAFT",
        "marks": int(question.get("marks", 1)),
        "exam_format": str(question.get("exam_format", "Practice"))[:40],
        "source_kind": "AUTHOR_CREATED",
        "source_title": str(question.get("source_title", "QuantaEdge original practice draft — human review required"))[:300],
        "source_ref": None,
        "source_year": None,
        "source_id": None,
        "board": str(question.get("board", "Bihar Board"))[:80],
        "topic": question.get("topic"),
        "subtopic": question.get("subtopic"),
        "skill": question.get("skill"),
        "tags": tags,
        "answer_payload": question["answer_payload"],
        "options": options,
    }
    # Class/subject/language tags aid later audit and never affect grading.
    for tag in (f"qe-class:{class_code}", f"qe-subject:{subject_code}", f"qe-source-language:{bank_language}"):
        if tag not in payload["tags"]:
            payload["tags"].append(tag)
    return payload


def fetch_track_rows(api: AdminApi, class_code: str, subject_code: str) -> list[dict[str, Any]]:
    query = urllib.parse.urlencode({
        "classCode": class_code, "subjectCode": subject_code, "status": "ALL",
    })
    rows = api.request("GET", "/api/v1/admin/content?" + query)
    if not isinstance(rows, list):
        raise RuntimeError(f"Admin content inventory for class {class_code}/{subject_code} returned an unexpected shape.")
    return [row for row in rows if isinstance(row, dict)]


def map_question_lessons(bank: dict[str, Any], track_rows: list[dict[str, Any]]) -> dict[str, int]:
    class_code = str(bank["class_code"])
    subject_code = str(bank["subject_code"]).lower()
    chapter_code = str(bank["chapter"]["code"])
    chapter_rows = [
        row for row in track_rows
        if str(row.get("class_code")) == class_code
        and str(row.get("subject_code", "")).lower() == subject_code
        and str(row.get("chapter_code", "")) == chapter_code
        and row.get("chapter_active") is not False
        and row.get("lesson_active") is not False
        and str(row.get("chapter_status", "")).upper() != "ARCHIVED"
        and str(row.get("lesson_status", "")).upper() != "ARCHIVED"
        and row.get("lesson_id") is not None
    ]
    lesson_ids: dict[str, int] = {}
    for question in bank["questions"]:
        requested_code = str(question["lesson_code"]).strip()
        exact = [row for row in chapter_rows if str(row.get("lesson_code", "")) == requested_code]
        matches = exact or [
            row for row in chapter_rows
            if str(row.get("lesson_code", "")).endswith("-" + requested_code)
        ]
        if len(matches) != 1:
            raise RuntimeError(
                f"Cannot safely map {class_code}/{subject_code}/{chapter_code}/{requested_code}: "
                f"found {len(matches)} matching active lesson rows."
            )
        lesson_ids[requested_code] = int(matches[0]["lesson_id"])
    return lesson_ids


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", type=Path, required=True, help="QuantaEdge_Classes6-8_Hindi_Practice_Banks_ALL.json")
    parser.add_argument("--classes", default="6,7,8")
    parser.add_argument("--subjects", default="maths,science")
    parser.add_argument("--chapter-code", help="Optional exact chapter code for a controlled one-chapter run.")
    parser.add_argument("--apply", action="store_true", help="Append missing questions as DRAFTs using the admin API.")
    parser.add_argument("--api-url", default=os.environ.get("QUANTAEDGE_API_BASE_URL", "http://localhost:8080"))
    parser.add_argument("--output-dir", type=Path, default=REPO_ROOT / "QuantaEdge_Bilingual_Draft_Bundle")
    args = parser.parse_args()

    try:
        classes = csv_codes(args.classes, ALLOWED_CLASSES, "classes")
        subjects = csv_codes(args.subjects, ALLOWED_SUBJECTS, "subjects")
        catalog = load_catalog(args.package)
        banks = selected_banks(catalog, classes, subjects, args.chapter_code)
        session = os.environ.get("QUANTAEDGE_ADMIN_SESSION", "").strip()
        if args.apply and not session:
            parser.error("--apply requires QUANTAEDGE_ADMIN_SESSION from a fresh QE_SESSION cookie. Never pass the cookie as a command-line argument.")
        if args.apply and not args.api_url.startswith(("https://", "http://localhost", "http://127.0.0.1")):
            parser.error("Use HTTPS for remote APIs; HTTP is allowed only for localhost.")
        if args.apply:
            api = AdminApi(args.api_url, session)
        else:
            api = None

        summary: dict[str, Any] = {
            "mode": "APPLY_DRAFT_ONLY" if args.apply else "DRY_RUN",
            "source_file": str(args.package),
            "source_schema": catalog["schema"],
            "selected_banks": len(banks),
            "selected_questions": sum(len(bank["questions"]) for bank in banks),
            "selected_banks_by_class_subject": dict(sorted(collections.Counter(
                f"{bank['class_code']}:{bank['subject_code']}" for bank in banks
            ).items())),
            "question_types": dict(sorted(collections.Counter(
                q["question_type"] for bank in banks for q in bank["questions"]
            ).items())),
            "student_delivery_allowed_by_import": False,
            "publication_attempted": False,
            "mapping_preflight": "NOT_RUN",
            "results": [],
        }

        if not args.apply:
            summary["mapping_preflight"] = "NOT_RUN_DRY_RUN_DOES_NOT_CALL_API"
            print(json.dumps(summary, ensure_ascii=False, indent=2))
            return 0

        # Validate every class/subject/chapter/lesson mapping before the first write.
        cache: dict[tuple[str, str], list[dict[str, Any]]] = {}
        plans: list[dict[str, Any]] = []
        mapping_errors: list[str] = []
        for bank in banks:
            track = (str(bank["class_code"]), str(bank["subject_code"]).lower())
            try:
                if track not in cache:
                    cache[track] = fetch_track_rows(api, *track)
                lesson_ids = map_question_lessons(bank, cache[track])
                per_lesson: dict[int, list[dict[str, Any]]] = collections.defaultdict(list)
                requested_codes: dict[int, str] = {}
                for question in sorted(bank["questions"], key=lambda q: (str(q["lesson_code"]), int(q.get("sort_order", 0)), str(q["id"]))):
                    requested_code = str(question["lesson_code"]).strip()
                    lesson_id = lesson_ids[requested_code]
                    requested_codes[lesson_id] = requested_code
                    per_lesson[lesson_id].append(question)
                for lesson_id, questions in per_lesson.items():
                    if len(questions) > MAX_APPEND_BATCH:
                        mapping_errors.append(
                            f"Class {track[0]} {track[1]} {bank['chapter']['code']} lesson {requested_codes[lesson_id]} "
                            f"has {len(questions)} questions, greater than the API limit {MAX_APPEND_BATCH}."
                        )
                        continue
                    plans.append({
                        "bank": bank,
                        "class_code": track[0],
                        "subject_code": track[1],
                        "chapter_code": str(bank["chapter"]["code"]),
                        "lesson_id": lesson_id,
                        "lesson_code": requested_codes[lesson_id],
                        "bank_language": str(bank["language"]),
                        "questions": questions,
                    })
            except Exception as exc:
                mapping_errors.append(
                    f"Class {track[0]} {track[1]} chapter {bank.get('chapter', {}).get('code')}: {exc}"
                )
        if mapping_errors:
            summary["mapping_preflight"] = "FAILED_NO_WRITES"
            summary["mapping_errors"] = mapping_errors
            summary["failed"] = len(mapping_errors)
            print(json.dumps(summary, ensure_ascii=False, indent=2))
            return 2

        summary["mapping_preflight"] = "PASSED_ALL_SELECTED_BANKS"
        summary["mapped_lesson_batches"] = len(plans)
        total_added = total_skipped = 0
        for plan in plans:
            lesson_id = plan["lesson_id"]
            bank = plan["bank"]
            try:
                base_index = 0
                questions_payload = []
                for index, question in enumerate(plan["questions"], start=1):
                    questions_payload.append(question_payload(
                        question, index, plan["class_code"], plan["subject_code"], plan["bank_language"]
                    ))
                result = api.request(
                    "POST",
                    f"/api/v1/admin/content/lessons/{lesson_id}/questions/append-drafts",
                    {"questions": questions_payload},
                )
                if not isinstance(result, dict):
                    raise RuntimeError("append-drafts API returned an unexpected response.")
                added = int(result.get("added", 0))
                skipped = int(result.get("skippedDuplicate", 0))
                total_added += added
                total_skipped += skipped
                summary["results"].append({
                    "class_code": plan["class_code"],
                    "subject_code": plan["subject_code"],
                    "chapter_code": plan["chapter_code"],
                    "lesson_code": plan["lesson_code"],
                    "lesson_id": lesson_id,
                    "requested": len(questions_payload),
                    "added": added,
                    "skipped_duplicate": skipped,
                    "status": "DRAFT_ONLY",
                })
                print(
                    f"[Class {plan['class_code']} {plan['subject_code']} {plan['chapter_code']}/{plan['lesson_code']}] "
                    f"added={added}, skipped_duplicate={skipped}",
                    file=sys.stderr,
                )
            except Exception as exc:
                summary["results"].append({
                    "class_code": plan["class_code"], "subject_code": plan["subject_code"],
                    "chapter_code": plan["chapter_code"], "lesson_code": plan["lesson_code"],
                    "lesson_id": lesson_id, "requested": len(plan["questions"]),
                    "error": str(exc), "status": "FAILED_NO_PUBLISH",
                })

        failed = sum(1 for result in summary["results"] if result.get("error"))
        summary.update({
            "added_as_draft": total_added,
            "skipped_duplicate": total_skipped,
            "failed_batches": failed,
            "publication_attempted": False,
            "student_delivery_allowed_by_import": False,
        })
        args.output_dir.mkdir(parents=True, exist_ok=True)
        report_path = args.output_dir / "practice-bank-import-report.json"
        report_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        summary["report_file"] = str(report_path)
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return 2 if failed else 0
    except Exception as exc:
        print(f"Practice-bank import stopped safely: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
