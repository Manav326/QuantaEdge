#!/usr/bin/env python3
"""Import any registered ASSESSMENT_ONLY course from portable assessment JSON packages.

The default is a no-write dry run. --apply is restricted to localhost PostgreSQL and
uses one transaction for the course identity, assessments, ordered questions, test packs,
release, source assignment, and lifecycle event. It never approves answers or publishes content.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

REPO_ROOT = Path(__file__).resolve().parents[1]
MANIFEST_SCHEMA = "quantaedge.assessment-course-manifest.v1"
PACKAGE_SCHEMA = "quantaedge.portable-assessment-package.v1"
COURSE_KEY_RE = re.compile(r"^[A-Z0-9][A-Z0-9_-]{1,37}$")
PACKAGE_ID_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{2,119}$")
VERSION_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._+-]{0,39}$")
ASSESSMENT_TYPES = {"PREVIOUS_YEAR_PAPER", "MOCK_EXAM", "DIAGNOSTIC", "PRACTICE_SET"}
QUESTION_TYPES = {
    "MCQ", "TRUE_FALSE", "INFERENCE", "INPUT", "NUMERICAL", "SHORT_ANSWER",
    "LONG_ANSWER", "CASE_BASED", "SOURCE_BASED", "DIAGRAM", "MAP",
    "ASSERTION_REASON", "MATCH", "ORDER",
}
ANSWER_KINDS = {"OPTION", "MULTI_OPTION", "TEXT", "NUMERIC", "ORDER", "MATCH"}
SECTION_POLICIES = {"SCORED", "QUALIFYING_ONLY", "PRACTICE_ONLY"}
RIGHTS_VALUES = {
    "UNCONFIRMED", "CLEARED_FOR_INTERNAL_USE",
    "CLEARED_FOR_PUBLIC_REPOSITORY", "DO_NOT_REDISTRIBUTE",
}


class ImportErrorSafe(RuntimeError):
    """Raised when source metadata, identities, or existing lifecycle state is unsafe."""


def ask(label: str, example: str, default: str = "") -> str:
    suffix = f" [example: {example}]" if example else ""
    if default:
        suffix += f" [default: {default}]"
    while True:
        value = input(f"{label}{suffix}: ").strip()
        if not value:
            value = default
        if value:
            return value
        print("This field is required. Enter a value or Ctrl+C to cancel.")


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ImportErrorSafe(f"Could not read JSON file {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise ImportErrorSafe(f"{path} must contain a JSON object.")
    return value


def package_paths(args: argparse.Namespace, manifest: dict[str, Any]) -> list[Path]:
    paths: list[Path] = []
    manifest_path = args.manifest.resolve() if args.manifest else None
    base = manifest_path.parent if manifest_path else REPO_ROOT
    raw_items = manifest.get("packages", [])
    if raw_items and not isinstance(raw_items, list):
        raise ImportErrorSafe("manifest.packages must be a list of portable assessment JSON paths.")
    for item in raw_items or []:
        raw_path = item.get("path") if isinstance(item, dict) else item
        if not isinstance(raw_path, str) or not raw_path.strip():
            raise ImportErrorSafe("Each manifest.packages entry must be a JSON path string or {\"path\":\"...\"}.")
        p = Path(raw_path)
        paths.append((base / p).resolve() if not p.is_absolute() else p.resolve())
    for raw in args.package or []:
        p = Path(raw)
        paths.append((REPO_ROOT / p).resolve() if not p.is_absolute() else p.resolve())
    if not paths and sys.stdin.isatty():
        raw = ask(
            "Path to the first portable assessment JSON package",
            "data/assessment-courses/example/tre-1.json",
        )
        paths.append((REPO_ROOT / raw).resolve())
        while True:
            more = input("Another package path? Leave blank when finished [example: .../tre-2.json]: ").strip()
            if not more:
                break
            p = Path(more)
            paths.append((REPO_ROOT / p).resolve() if not p.is_absolute() else p.resolve())
    if not paths:
        raise ImportErrorSafe(
            "No assessment packages supplied. Add manifest.packages, pass --package PATH, "
            "or run interactively to be asked for a path. Example: --package data/assessment-courses/example/tre-1.json"
        )
    return paths


def complete_course_fields(manifest: dict[str, Any]) -> dict[str, str]:
    raw = manifest.get("course")
    if not isinstance(raw, dict):
        raw = {}
    code = str(raw.get("code") or "").strip().upper()
    display_name = str(raw.get("display_name") or "").strip()
    version = str(raw.get("curriculum_version") or "").strip()
    stream = str(raw.get("stream_code") or "ASSESSMENT").strip()
    if sys.stdin.isatty():
        if not code:
            code = ask(
                "Stable course key / course code (not the generated public ID)",
                "BPSC_TRE_CS_PYQ_SERIES",
            ).upper()
        if not display_name:
            display_name = ask(
                "Course name shown to learners",
                "BPSC TRE Computer Science — Previous-Year Practice Series",
            )
        if not version:
            version = ask("Curriculum/content version label", "BPSC-TRE-CS-PYQ-SERIES")
        if not stream:
            stream = ask("Internal stream code", "ASSESSMENT", "ASSESSMENT")
    if not code:
        raise ImportErrorSafe(
            "Missing course.code. Add it to the manifest or run interactively. "
            "Example: BPSC_TRE_CS_PYQ_SERIES. This is the stable key, not the 8-character public ID."
        )
    if not display_name:
        raise ImportErrorSafe("Missing course.display_name. Example: BPSC TRE Computer Science — Practice Series.")
    if not version:
        raise ImportErrorSafe("Missing course.curriculum_version. Example: BPSC-TRE-CS-PYQ-SERIES.")
    if not COURSE_KEY_RE.fullmatch(code):
        raise ImportErrorSafe("course.code must be 2–38 uppercase letters, digits, underscores or hyphens. Example: BPSC_TRE_CS_PYQ_SERIES.")
    if len(display_name) < 2 or len(display_name) > 200:
        raise ImportErrorSafe("course.display_name must contain 2–200 characters.")
    if len(version) > 120:
        raise ImportErrorSafe("course.curriculum_version must contain 1–120 characters.")
    if not stream or len(stream) > 60:
        raise ImportErrorSafe("course.stream_code must contain 1–60 characters.")
    return {"code": code, "display_name": display_name, "curriculum_version": version, "stream_code": stream}


def _prompt_package_metadata(pkg: dict[str, Any], path: Path) -> dict[str, Any]:
    """Ask for missing package metadata; never invent missing questions or answers."""
    source = pkg.get("source")
    if not isinstance(source, dict):
        source = {}
        pkg["source"] = source
    config = pkg.get("configuration")
    if not isinstance(config, dict):
        config = {}
        pkg["configuration"] = config
    prompts: list[tuple[dict[str, Any], str, str, str]] = [
        (pkg, "package_id", "Stable package ID", "tre-computer-science-paper-1"),
        (pkg, "package_version", "Package version", "1.0.0"),
        (pkg, "title", "Test/package title", "BPSC TRE Computer Science — Practice Test 1"),
        (source, "issuer", "Source issuer", "BPSC"),
        (source, "exam_name", "Exam/source name", "TRE Computer Science"),
        (source, "cycle", "Exam cycle or package label", "TRE 1.0"),
        (source, "source_url", "Source URL", "https://example.org/official-source.pdf"),
        (source, "repository_distribution_rights", "Source rights decision", "UNCONFIRMED"),
        (config, "duration_minutes", "Test duration in minutes", "120"),
        (config, "marks_per_question", "Default marks per question", "1"),
        (config, "negative_marking_fraction", "Negative-marking fraction (0–1)", "0.25"),
        (config, "unanswered_mark", "Marks for an unanswered question", "0"),
        (config, "max_attempts", "Maximum attempts", "1"),
    ]
    if sys.stdin.isatty():
        for target, key, label, example in prompts:
            value = target.get(key)
            if value is None or (isinstance(value, str) and not value.strip()):
                entered = ask(f"{path.name}: {label}", example)
                if key in {"duration_minutes", "max_attempts"}:
                    try:
                        target[key] = int(entered)
                    except ValueError:
                        target[key] = entered
                elif key in {"marks_per_question", "negative_marking_fraction", "unanswered_mark"}:
                    try:
                        target[key] = float(entered)
                    except ValueError:
                        target[key] = entered
                else:
                    target[key] = entered
    pkg.setdefault("schema", PACKAGE_SCHEMA)
    if not isinstance(pkg.get("editorial"), dict):
        pkg["editorial"] = {}
    # An importer may create drafts, but must not accept an upload's publication decision.
    pkg["editorial"].update({
        "status": "DRAFT",
        "student_delivery_allowed": False,
        "review_status": "PENDING_HUMAN_REVIEW",
    })
    return pkg


def validate_package(pkg: dict[str, Any], source_path: Path) -> list[str]:
    issues: list[str] = []
    if pkg.get("schema") != PACKAGE_SCHEMA:
        issues.append(f"{source_path.name}: schema must be {PACKAGE_SCHEMA}.")
    package_id = str(pkg.get("package_id") or "")
    package_version = str(pkg.get("package_version") or "")
    title = str(pkg.get("title") or "").strip()
    assessment_type = str(pkg.get("assessment_type") or "").upper()
    if not PACKAGE_ID_RE.fullmatch(package_id):
        issues.append(f"{source_path.name}: package_id must be a lowercase stable slug, e.g. tre-computer-science-paper-1.")
    if not VERSION_RE.fullmatch(package_version):
        issues.append(f"{source_path.name}: package_version must be 1–40 letters/digits/dot/underscore/plus/hyphen, e.g. 1.0.0.")
    if not title or len(title) > 240:
        issues.append(f"{source_path.name}: title is required (1–240 characters), e.g. BPSC TRE Computer Science — Practice Test 1.")
    if assessment_type not in ASSESSMENT_TYPES:
        issues.append(f"{source_path.name}: assessment_type must be one of {', '.join(sorted(ASSESSMENT_TYPES))}.")
    source = pkg.get("source")
    if not isinstance(source, dict):
        issues.append(f"{source_path.name}: source is missing; add issuer, exam_name, cycle and source_url.")
        source = {}
    for field in ("issuer", "exam_name", "cycle", "source_url"):
        value = str(source.get(field) or "").strip()
        if not value:
            example = {
                "issuer": "BPSC", "exam_name": "TRE Computer Science",
                "cycle": "TRE 1.0", "source_url": "https://example.org/official-source.pdf",
            }[field]
            issues.append(f"{source_path.name}: source.{field} is required. Example: {example}.")
    raw_source_url = str(source.get("source_url") or "").strip()
    if raw_source_url:
        parsed_url = urlparse(raw_source_url)
        if parsed_url.scheme not in {"http", "https"} or not parsed_url.netloc:
            issues.append(f"{source_path.name}: source.source_url must be a valid http(s) URL. Example: https://example.org/official-paper.pdf.")
    rights = str(source.get("repository_distribution_rights") or "UNCONFIRMED")
    if rights not in RIGHTS_VALUES:
        issues.append(f"{source_path.name}: source.repository_distribution_rights must be one of {', '.join(sorted(RIGHTS_VALUES))}.")
    config = pkg.get("configuration")
    if not isinstance(config, dict):
        issues.append(f"{source_path.name}: configuration must include duration, marks, negative marking and unanswered mark.")
        config = {}
    for field, minimum, maximum, integer_only in (
        ("duration_minutes", 1, 240, True), ("marks_per_question", 0, 1000, False),
        ("unanswered_mark", 0, 1000, False), ("max_attempts", 1, 10, True),
    ):
        value = config.get(field, 1 if field == "max_attempts" else None)
        try:
            number = float(value)
            if number < minimum or number > maximum or (integer_only and number != int(number)):
                raise ValueError()
        except (TypeError, ValueError):
            issues.append(f"{source_path.name}: configuration.{field} must be {('an integer' if integer_only else 'a number')} from {minimum} to {maximum}.")
    try:
        penalty = float(config.get("negative_marking_fraction"))
        if penalty < 0 or penalty > 1:
            raise ValueError()
    except (TypeError, ValueError):
        issues.append(f"{source_path.name}: configuration.negative_marking_fraction must be between 0 and 1. Example: 0.25.")
    sections = pkg.get("sections")
    questions = pkg.get("questions")
    if not isinstance(sections, list) or not sections:
        issues.append(f"{source_path.name}: sections is required. Add each scored/qualifying section with its expected question_count.")
        sections = []
    if not isinstance(questions, list) or not questions:
        issues.append(f"{source_path.name}: questions is required. Importer will not invent question content; provide a completed portable JSON package.")
        questions = []
    if len(questions) > 250:
        issues.append(f"{source_path.name}: a portable package supports at most 250 questions; split larger sets into separate packages.")
    section_by_code: dict[str, dict[str, Any]] = {}
    for index, section in enumerate(sections):
        if not isinstance(section, dict):
            issues.append(f"{source_path.name}: sections[{index}] must be an object.")
            continue
        code = str(section.get("code") or "")
        if not re.fullmatch(r"^[A-Z][A-Z0-9_]{1,49}$", code):
            issues.append(f"{source_path.name}: sections[{index}].code is invalid. Example: COMPUTER_SCIENCE.")
        if code in section_by_code:
            issues.append(f"{source_path.name}: section code {code} is duplicated.")
        section_by_code[code] = section
        if section.get("result_policy") not in SECTION_POLICIES:
            issues.append(f"{source_path.name}: section {code} result_policy must be SCORED, QUALIFYING_ONLY, or PRACTICE_ONLY.")
        try:
            expected = int(section.get("question_count"))
            if expected < 1 or expected > 250:
                raise ValueError()
        except (TypeError, ValueError):
            issues.append(f"{source_path.name}: section {code} needs an integer question_count. Example: 120.")
    actual_counts: dict[str, int] = {}
    seen_question_ids: set[str] = set()
    for index, question in enumerate(questions):
        if not isinstance(question, dict):
            issues.append(f"{source_path.name}: questions[{index}] must be an object.")
            continue
        qid = str(question.get("id") or "")
        if not qid or qid in seen_question_ids:
            issues.append(f"{source_path.name}: questions[{index}].id must be present and unique.")
        seen_question_ids.add(qid)
        if question.get("question_type") not in QUESTION_TYPES:
            issues.append(f"{source_path.name}: questions[{index}].question_type is unsupported.")
        if not str(question.get("prompt") or "").strip():
            issues.append(f"{source_path.name}: questions[{index}].prompt is required.")
        if not isinstance(question.get("prompt_i18n"), dict) or not question.get("prompt_i18n"):
            issues.append(f"{source_path.name}: questions[{index}].prompt_i18n needs at least one language. Example: {{\"en\":\"Question text\"}}.")
        options = question.get("options") if isinstance(question.get("options"), list) else []
        if question.get("question_type") in {"MCQ", "TRUE_FALSE"} and len(options) < 2:
            issues.append(f"{source_path.name}: questions[{index}].options needs at least two choices for MCQ/TRUE_FALSE.")
        option_keys: set[str] = set()
        for option_index, option in enumerate(options):
            if not isinstance(option, dict):
                issues.append(f"{source_path.name}: questions[{index}].options[{option_index}] must be an object.")
                continue
            option_key = str(option.get("key") or "")
            if not option_key or option_key in option_keys:
                issues.append(f"{source_path.name}: questions[{index}] option keys must be present and unique.")
            option_keys.add(option_key)
        section_code = str(question.get("section_code") or "")
        actual_counts[section_code] = actual_counts.get(section_code, 0) + 1
        section = section_by_code.get(section_code)
        if section is None:
            issues.append(f"{source_path.name}: questions[{index}].section_code must match a declared section. Example: COMPUTER_SCIENCE.")
        proposed = question.get("proposed_answer")
        if not isinstance(proposed, dict):
            issues.append(f"{source_path.name}: questions[{index}].proposed_answer is required; use UNRESOLVED when the answer is not verified.")
            proposed = {}
        elif proposed.get("review_status") != "PENDING_HUMAN_REVIEW":
            issues.append(f"{source_path.name}: questions[{index}].proposed_answer.review_status must remain PENDING_HUMAN_REVIEW.")
        explicit = question.get("answer_payload")
        if isinstance(explicit, dict):
            kind = explicit.get("kind")
            if kind not in ANSWER_KINDS and not (kind == "UNRESOLVED" and not proposed.get("option_key")):
                issues.append(f"{source_path.name}: questions[{index}].answer_payload.kind is unsupported.")
            if kind in ANSWER_KINDS and explicit.get("value") is None:
                issues.append(f"{source_path.name}: questions[{index}].answer_payload.value is required for a typed answer.")
    for code, section in section_by_code.items():
        try:
            expected = int(section.get("question_count"))
        except (TypeError, ValueError):
            continue
        if actual_counts.get(code, 0) != expected:
            issues.append(f"{source_path.name}: section {code} declares {expected} questions but contains {actual_counts.get(code, 0)}.")
    if pkg.get("course_binding") is not None:
        issues.append(f"{source_path.name}: course_binding is forbidden; the authenticated importer supplies course and tenant scope.")
    return issues


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), default=str)


def digest_json(value: Any) -> str:
    return hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest()


def build_question_snapshot(question: dict[str, Any], section: dict[str, Any], package: dict[str, Any]) -> dict[str, Any]:
    proposed = question.get("proposed_answer") if isinstance(question.get("proposed_answer"), dict) else {}
    explicit = question.get("answer_payload") if isinstance(question.get("answer_payload"), dict) else None
    option_key = proposed.get("option_key")
    if explicit is not None:
        answer_payload = dict(explicit)
    elif option_key:
        answer_payload = {"kind": "OPTION", "value": option_key}
    else:
        answer_payload = {"kind": "UNRESOLVED", "value": ""}
    options: list[dict[str, Any]] = []
    chosen = answer_payload.get("value")
    for sequence, raw in enumerate(question.get("options") or [], 1):
        if not isinstance(raw, dict):
            continue
        key = str(raw.get("key") or "")
        correct = (
            answer_payload.get("kind") == "OPTION" and key == str(chosen)
        ) or (
            answer_payload.get("kind") == "MULTI_OPTION" and isinstance(chosen, list)
            and key in {str(value) for value in chosen}
        )
        option = {
            "key": key,
            "option_key": key,
            "label": raw.get("text", ""),
            "text": raw.get("text", ""),
            "text_i18n": raw.get("text_i18n") or {},
            "is_correct": bool(correct),
            "sort_order": sequence,
        }
        for metadata in ("content_kind", "code_line_indices", "math_line_indices", "source_code_preservation_notes"):
            if metadata in raw:
                option[metadata] = raw[metadata]
        options.append(option)
    source = package.get("source") if isinstance(package.get("source"), dict) else {}
    snapshot: dict[str, Any] = {
        "question_type": str(question.get("question_type") or "MCQ").upper(),
        "prompt": question.get("prompt", ""),
        "prompt_i18n": question.get("prompt_i18n") or {},
        "options": options,
        "answer_payload": answer_payload,
        "proposed_answer": proposed,
        "explanation": proposed.get("explanation", ""),
        "explanation_i18n": proposed.get("explanation_i18n") or {},
        "question_review_status": "PENDING_HUMAN_REVIEW",
        "answer_review_status": "PENDING_HUMAN_REVIEW",
        "review_notes": proposed.get("reviewer_notes", ""),
        "answer_source": proposed.get("answer_source", "UNRESOLVED"),
        "answer_confidence": proposed.get("confidence", "UNRESOLVED"),
        "question_id": question.get("id"),
        "source_question_number": question.get("source_question_number"),
        "source_location": question.get("source_location") or {},
        "source_note": question.get("source_note", ""),
        "section_code": question.get("section_code"),
        "section_name_i18n": section.get("name_i18n") or {},
        "section_result_policy": section.get("result_policy", "SCORED"),
        "marks": question.get("marks", package.get("configuration", {}).get("marks_per_question", 1)),
        "unanswered_mark": package.get("configuration", {}).get("unanswered_mark", 0),
        "topic_tags": question.get("topic_tags") or [],
        "subtopic_tags": question.get("secondary_topic_tags") or [],
        "topic": (question.get("topic_tags") or [""])[0],
        "difficulty": question.get("difficulty", "CORE"),
        "exam_format": package.get("assessment_type"),
        "source_kind": "PORTABLE_PACKAGE",
        "source_title": source.get("paper_title") or source.get("exam_name", ""),
        "source_ref": source.get("source_url", ""),
        "source_year": source.get("cycle", ""),
        "source_id": package.get("package_id"),
        "board": source.get("issuer", ""),
        "source_status": source.get("source_status", ""),
        "native_language_only": bool(question.get("native_language_only", False)),
    }
    if source.get("source_file_sha256"):
        snapshot["source_file_sha256"] = source["source_file_sha256"]
    if source.get("exam_date") is not None:
        snapshot["source_exam_date"] = source["exam_date"]
    for key in (
        "native_source_language", "stimulus_i18n", "stimulus_content_kind", "stimulus",
        "content_language_policy", "incorrect_marks", "prompt_content_kind", "code_line_indices",
        "math_line_indices", "source_code_preservation_notes", "stimulus_code_line_indices",
        "stimulus_math_line_indices", "stimulus_source_languages", "stimulus_id",
        "question_set_id", "question_set_type",
    ):
        if key in question:
            snapshot[key] = question[key]
    return snapshot


def db_url(args: argparse.Namespace) -> str:
    value = args.database_url or os.getenv("ASSESSMENT_DATABASE_URL") or os.getenv("SPRING_DATASOURCE_URL")
    if not value:
        raise ImportErrorSafe("Set ASSESSMENT_DATABASE_URL or pass --database-url for --apply.")
    value = value.strip()
    if value.startswith("jdbc:postgresql://"):
        value = value[len("jdbc:"):]
    parsed = urlparse(value)
    if parsed.scheme not in {"postgresql", "postgres"} or not parsed.hostname:
        raise ImportErrorSafe("Database URL must be postgresql://user:password@localhost:5432/quantaedge.")
    if parsed.hostname.lower() not in {"localhost", "127.0.0.1", "::1"}:
        raise ImportErrorSafe("Refusing a remote database target. This importer is restricted to localhost/loopback.")
    return value


def ensure_schema(cur: Any) -> None:
    cur.execute("""
        SELECT to_regclass('public.course_type_registry'),
               to_regclass('public.institution_course_identity'),
               to_regclass('public.curriculum_program_delivery_mode'),
               to_regclass('public.course_assessment_pack'),
               to_regclass('public.course_assessment_pack_item'),
               to_regprocedure('public.course_content_fingerprint(bigint)'),
               to_regprocedure('public.assessment_only_course_ready(bigint)')
    """)
    row = cur.fetchone()
    if not row or any(item is None for item in row):
        raise ImportErrorSafe("Required assessment-course migrations are missing. Start the local API and confirm Flyway completed.")
    cur.execute("SELECT status FROM public.course_type_registry WHERE code='ASSESSMENT'")
    row = cur.fetchone()
    if not row or row[0] != "ACTIVE":
        raise ImportErrorSafe("HRMS course type ASSESSMENT must be ACTIVE before importing.")


def resolve_institution(cur: Any) -> int:
    cur.execute("""
        SELECT id,status FROM public.institution_tenant
        WHERE tenant_code='QUANTA-EDGE' FOR UPDATE
    """)
    rows = cur.fetchall()
    if len(rows) != 1 or rows[0][1] != "ACTIVE":
        raise ImportErrorSafe("Expected exactly one ACTIVE QUANTA-EDGE source institution. Resolve it through HRMS; importer will not create/activate a tenant.")
    return int(rows[0][0])


def ensure_course(cur: Any, institution_id: int, course: dict[str, str]) -> tuple[int, str, bool, int | None]:
    key = course["code"]
    internal_code = f"I{institution_id}_{key}"
    cur.execute("""
        SELECT id,display_name,course_type_code,status
        FROM institution_course_identity
        WHERE source_institution_id=%s AND stable_course_key=%s FOR UPDATE
    """, (institution_id, key))
    identities = cur.fetchall()
    if len(identities) > 1:
        raise ImportErrorSafe("Multiple course identities use this stable key. Refusing to choose one.")
    identity_id: int
    created_course = False
    if identities:
        identity_id, display_name, type_code, identity_status = identities[0]
        if display_name != course["display_name"] or type_code != "ASSESSMENT" or identity_status != "ACTIVE":
            raise ImportErrorSafe("An existing course uses this stable key with conflicting name, type, or status. Choose its original settings or use a new stable key.")
        cur.execute("""
            SELECT id,public_id,display_name,course_type_code,class_id,curriculum_version,course_identity_id
            FROM curriculum_program WHERE course_identity_id=%s ORDER BY content_version_no DESC,id DESC FOR UPDATE
        """, (identity_id,))
        linked = cur.fetchall()
        if linked:
            match = [row for row in linked if row[5] == course["curriculum_version"]]
            if len(match) != 1:
                raise ImportErrorSafe("This logical course already has another version graph. Use the supported course-version workflow; the importer will not create a second graph.")
            row = match[0]
            course_id, public_id, display, type_code, class_id, version, linked_identity = row
            if display != course["display_name"] or type_code != "ASSESSMENT" or class_id is not None or int(linked_identity) != int(identity_id):
                raise ImportErrorSafe("Existing course graph is not the expected classless ASSESSMENT course. No records were changed.")
        else:
            course_id = None
            public_id = None
    else:
        cur.execute("""
            INSERT INTO institution_course_identity(
              source_institution_id,stable_course_key,display_name,course_type_code
            ) VALUES (%s,%s,%s,'ASSESSMENT') RETURNING id
        """, (institution_id, key, course["display_name"]))
        identity_id = int(cur.fetchone()[0])
        course_id = None
        public_id = None

    if course_id is None:
        cur.execute("SELECT id FROM curriculum_program WHERE code=%s AND curriculum_version=%s FOR UPDATE",
                    (internal_code, course["curriculum_version"]))
        if cur.fetchone():
            raise ImportErrorSafe("An unrelated course already uses this institution-scoped code and version.")
        cur.execute("""
            INSERT INTO curriculum_program(
              class_id,code,display_name,stream_code,curriculum_version,status,notes,public_id_prefix,
              course_type_code,course_identity_id,content_version_no
            ) VALUES (NULL,%s,%s,%s,%s,'ACTIVE',%s,'INS','ASSESSMENT',%s,1)
            RETURNING id,public_id
        """, (
            internal_code, course["display_name"], course["stream_code"], course["curriculum_version"],
            "Standalone assessment-only course. Source packages remain DRAFT until question/answer review and existing release approval workflows pass.",
            identity_id,
        ))
        course_id, public_id = cur.fetchone()
        course_id = int(course_id)
        created_course = True
    if not public_id:
        cur.execute("SELECT public_id FROM curriculum_program WHERE id=%s", (course_id,))
        public_id = cur.fetchone()[0]
    cur.execute("SELECT delivery_mode FROM curriculum_program_delivery_mode WHERE program_id=%s FOR UPDATE", (course_id,))
    mode = cur.fetchone()
    if mode and mode[0] != "ASSESSMENT_ONLY":
        raise ImportErrorSafe("Existing course has a conflicting delivery mode; refusing to overwrite it.")
    if not mode:
        cur.execute("""
            INSERT INTO curriculum_program_delivery_mode(program_id,delivery_mode)
            VALUES (%s,'ASSESSMENT_ONLY')
        """, (course_id,))
    return int(course_id), str(public_id), created_course, int(identity_id)


def check_editable_release(cur: Any, institution_id: int, course_id: int) -> tuple[Any, ...] | None:
    cur.execute("""
        SELECT id,release_version,release_state
        FROM institution_course_release
        WHERE source_institution_id=%s AND program_id=%s
        ORDER BY release_version DESC,id DESC LIMIT 1 FOR UPDATE
    """, (institution_id, course_id))
    latest = cur.fetchone()
    if latest and latest[2] != "DRAFT":
        raise ImportErrorSafe(f"The latest course release is {latest[2]}. Import only writes to an editable DRAFT release.")
    cur.execute("""
        SELECT id,release_state FROM institution_course_release
        WHERE program_id=%s AND release_state IN ('IN_QUALITY_REVIEW','QUALITY_APPROVED','SHAREABLE','LEGACY_MIGRATED','WITHDRAWN')
        ORDER BY release_version DESC,id DESC LIMIT 1 FOR UPDATE
    """, (course_id,))
    frozen = cur.fetchone()
    if frozen:
        raise ImportErrorSafe(f"Course graph is immutable because release {frozen[0]} is {frozen[1]}. Use the supported version workflow.")
    cur.execute("""
        SELECT id,status FROM institution_marketplace_offer
        WHERE course_id=%s AND (status='PUBLISHED' OR hrms_published_by_staff_id IS NOT NULL)
        ORDER BY updated_at DESC,id DESC LIMIT 1 FOR UPDATE
    """, (course_id,))
    offer = cur.fetchone()
    if offer:
        raise ImportErrorSafe(f"Course graph is immutable because Marketplace offer {offer[0]} has published history.")
    return latest


def import_package(cur: Any, course_id: int, institution_id: int, package: dict[str, Any]) -> dict[str, Any]:
    package_id = str(package["package_id"])
    version = str(package["package_version"])
    title = str(package["title"]).strip()
    source = package["source"]
    config = package["configuration"]
    assessment_type = str(package["assessment_type"]).upper()
    digest = digest_json(package)
    cur.execute("""
        SELECT id,content_sha256,status FROM course_assessment_pack
        WHERE course_id=%s AND pack_code=%s AND pack_version=%s FOR UPDATE
    """, (course_id, package_id, version))
    old_pack = cur.fetchone()
    if old_pack:
        pack_id, old_digest, status = old_pack
        if str(old_digest).strip().lower() != digest or status not in {"DRAFT", "PUBLISHED"}:
            raise ImportErrorSafe(f"Package {package_id} v{version} already exists with different content or lifecycle state. Increase the version for revised content.")
        cur.execute("""
            SELECT a.id,a.title,a.status,(SELECT count(*) FROM assessment_question aq WHERE aq.assessment_id=a.id)
            FROM course_assessment_pack_item i JOIN assessment a ON a.id=i.assessment_id
            WHERE i.pack_id=%s ORDER BY i.sequence_no,i.id
        """, (pack_id,))
        item = cur.fetchall()
        if len(item) != 1 or item[0][1] != title or item[0][2] not in {"DRAFT", "PUBLISHED"}:
            raise ImportErrorSafe(f"Existing package {package_id} has a missing or conflicting assessment item.")
        return {
            "packageId": package_id, "packageVersion": version, "packId": int(pack_id),
            "assessmentId": int(item[0][0]), "questionCount": int(item[0][3]),
            "packStatus": status, "assessmentStatus": item[0][2], "idempotent": True,
        }

    question_count = len(package["questions"])
    cur.execute("""
        INSERT INTO assessment(
          course_id,title,description,subject_id,chapter_id,duration_minutes,max_attempts,
          negative_marking_fraction,status,assessment_type,is_generated,source_package_id,
          source_package_version,source_package_sha256,source_exam_cycle,source_url,portable_assessment_type
        ) VALUES (%s,%s,%s,NULL,NULL,%s,%s,%s,'DRAFT','PREVIOUS_YEAR_PAPER',false,%s,%s,%s,%s,%s,%s)
        RETURNING id
    """, (
        course_id, title, package.get("description"), int(config["duration_minutes"]),
        int(config.get("max_attempts", 1)), float(config["negative_marking_fraction"]),
        package_id, version, digest, source.get("cycle"), source.get("source_url"), assessment_type,
    ))
    assessment_id = int(cur.fetchone()[0])
    sections = {str(section["code"]): section for section in package["sections"]}
    for sequence, question in enumerate(package["questions"], 1):
        snapshot = build_question_snapshot(question, sections[str(question["section_code"])], package)
        marks = float(question.get("marks", config.get("marks_per_question", 1)))
        if marks < 0 or marks > 1000:
            raise ImportErrorSafe(f"{package_id}: question {sequence} marks must be between 0 and 1,000.")
        cur.execute("""
            INSERT INTO assessment_question(assessment_id,source_question_id,sequence_no,max_marks,question_snapshot)
            VALUES (%s,NULL,%s,%s,%s::jsonb)
        """, (assessment_id, sequence, marks, json.dumps(snapshot, ensure_ascii=False, separators=(",", ":"), default=str)))
    cur.execute("""
        INSERT INTO course_assessment_pack(
          course_id,source_institution_id,pack_code,pack_version,title,description,status,content_sha256,
          open_book_enabled,resource_policy,created_by_staff_id
        ) VALUES (%s,%s,%s,%s,%s,%s,'DRAFT',%s,false,'{"mode":"CLOSED_BOOK","resources":[]}'::jsonb,NULL)
        RETURNING id
    """, (course_id, institution_id, package_id, version, title, package.get("description"), digest))
    pack_id = int(cur.fetchone()[0])
    cur.execute("""
        INSERT INTO course_assessment_pack_item(pack_id,assessment_id,sequence_no,test_kind)
        VALUES (%s,%s,1,%s)
    """, (pack_id, assessment_id, assessment_type))
    cur.execute("""
        INSERT INTO assessment_audit_log(
          assessment_id,actor_role,action,previous_status,new_status,note,occurred_at
        ) VALUES (%s,'SYSTEM_IMPORT','ASSESSMENT_IMPORTED',NULL,'DRAFT',%s,now())
    """, (assessment_id, f"Imported {question_count} portable source questions from {package_id}; answer review remains pending."))
    return {
        "packageId": package_id, "packageVersion": version, "packId": pack_id,
        "assessmentId": assessment_id, "questionCount": question_count,
        "packStatus": "DRAFT", "assessmentStatus": "DRAFT", "idempotent": False,
    }


def ensure_release(cur: Any, institution_id: int, course_id: int, created_course: bool, identity_id: int, public_id: str) -> dict[str, Any]:
    cur.execute("SELECT course_content_fingerprint(%s)", (course_id,))
    fingerprint = str(cur.fetchone()[0] or "").strip()
    cur.execute("SELECT assessment_only_course_ready(%s)", (course_id,))
    ready = cur.fetchone()
    if not ready or ready[0] is not True:
        raise ImportErrorSafe("Generic ASSESSMENT_ONLY readiness failed. The transaction will roll back; no incomplete course graph will remain.")
    cur.execute("""
        SELECT id,release_version,release_state,content_fingerprint
        FROM institution_course_release
        WHERE source_institution_id=%s AND program_id=%s
        ORDER BY release_version DESC,id DESC LIMIT 1 FOR UPDATE
    """, (institution_id, course_id))
    release = cur.fetchone()
    if not release:
        cur.execute("""
            INSERT INTO institution_course_release(
              source_institution_id,program_id,release_version,release_state,content_fingerprint,
              legacy_migration,created_at,updated_at
            ) VALUES (%s,%s,1,'DRAFT',%s,false,now(),now())
            RETURNING id,release_version,release_state
        """, (institution_id, course_id, fingerprint))
        release_id, release_version, release_state = cur.fetchone()
    else:
        release_id, release_version, release_state, old_fingerprint = release
        if release_state != "DRAFT":
            raise ImportErrorSafe(f"Release is {release_state}; only DRAFT releases can be updated by import.")
        if str(old_fingerprint or "").strip() != fingerprint:
            cur.execute("""
                UPDATE institution_course_release SET content_fingerprint=%s,updated_at=now()
                WHERE id=%s AND source_institution_id=%s AND release_state='DRAFT'
            """, (fingerprint, release_id, institution_id))
            if cur.rowcount != 1:
                raise ImportErrorSafe("The course draft changed concurrently. Retry after reviewing the current state.")

    cur.execute("""
        SELECT id,status FROM institution_course_assignment
        WHERE institution_id=%s AND course_release_id=%s FOR UPDATE
    """, (institution_id, release_id))
    assignment = cur.fetchone()
    if assignment:
        assignment_id, status = assignment
        if status != "ASSIGNED":
            raise ImportErrorSafe("The source assignment is inactive. Reactivate it through the approved workflow before importing.")
    else:
        cur.execute("""
            INSERT INTO institution_course_assignment(
              institution_id,course_release_id,status,assignment_source,assigned_at,created_at,updated_at
            ) VALUES (%s,%s,'ASSIGNED','ORIGIN',now(),now(),now()) RETURNING id
        """, (institution_id, release_id))
        assignment_id = cur.fetchone()[0]
    if created_course:
        details = {
            "courseId": course_id, "courseCode": f"ASSESSMENT_{course_id}",
            "courseIdentityId": identity_id, "publicId": public_id, "courseTypeCode": "ASSESSMENT",
            "hierarchy": "COURSE_TEST_PACK_ASSESSMENT_ORDERED_QUESTIONS",
            "deliveryMode": "ASSESSMENT_ONLY", "source": "scripts/import_assessment_course.py",
        }
        cur.execute("""
            INSERT INTO institution_course_lifecycle_event(
              institution_id,course_release_id,assignment_id,event_type,details
            ) VALUES (%s,%s,%s,'COURSE_CREATED',%s::jsonb)
        """, (institution_id, release_id, assignment_id, json.dumps(details, ensure_ascii=False)))
    return {
        "releaseId": int(release_id), "releaseVersion": int(release_version),
        "releaseState": release_state, "assignmentId": int(assignment_id),
        "releaseFingerprint": fingerprint, "studentDeliveryAllowed": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, help=f"Course manifest JSON ({MANIFEST_SCHEMA}).")
    parser.add_argument("--package", action="append", help="Portable assessment JSON package path; repeat for multiple tests.")
    parser.add_argument("--database-url", help="PostgreSQL URL; otherwise ASSESSMENT_DATABASE_URL or SPRING_DATASOURCE_URL.")
    parser.add_argument("--apply", action="store_true", help="Persist the draft course and all listed packages in one transaction.")
    parser.add_argument("--yes", action="store_true", help="Skip the final confirmation prompt (localhost remains mandatory).")
    args = parser.parse_args()
    try:
        manifest = load_json(args.manifest.resolve()) if args.manifest else {}
        if manifest.get("schema") not in (None, MANIFEST_SCHEMA):
            raise ImportErrorSafe(f"manifest.schema must be {MANIFEST_SCHEMA}.")
        course = complete_course_fields(manifest)
        paths = package_paths(args, manifest)
        packages: list[dict[str, Any]] = []
        errors: list[str] = []
        for path in paths:
            pkg = load_json(path)
            pkg = _prompt_package_metadata(pkg, path)
            # Missing question/section bodies cannot be inferred. Ask for a complete file
            # when running interactively; never fabricate assessment content.
            if (not isinstance(pkg.get("sections"), list) or not pkg.get("sections") or not isinstance(pkg.get("questions"), list) or not pkg.get("questions")) and sys.stdin.isatty():
                replacement = ask(
                    f"{path.name} is missing sections/questions. Path to a completed portable assessment JSON file",
                    "data/assessment-courses/example/tre-1.json",
                )
                replacement_path = Path(replacement)
                if not replacement_path.is_absolute():
                    replacement_path = (REPO_ROOT / replacement_path).resolve()
                if replacement_path != path:
                    path = replacement_path
                    pkg = _prompt_package_metadata(load_json(path), path)
            errors.extend(validate_package(pkg, path))
            packages.append(pkg)
        if errors:
            raise ImportErrorSafe("Fix these package fields before import:\n- " + "\n- ".join(errors[:80]))
        keys = [(p["package_id"], p["package_version"]) for p in packages]
        if len(keys) != len(set(keys)):
            raise ImportErrorSafe("Package ID/version pairs must be unique within this import. Example: tre-cs-paper-1 / 1.0.0.")
        plan = {
            "mode": "APPLY" if args.apply else "DRY_RUN",
            "course": course,
            "courseTypeCode": "ASSESSMENT",
            "deliveryMode": "ASSESSMENT_ONLY",
            "sourceInstitution": "QUANTA-EDGE",
            "packageCount": len(packages),
            "packages": [
                {
                    "packageId": p["package_id"],
                    "packageVersion": p["package_version"],
                    "title": p["title"],
                    "assessmentType": p["assessment_type"],
                    "questionCount": len(p["questions"]),
                    "sourceCycle": p.get("source", {}).get("cycle"),
                    "contentSha256": digest_json(p),
                } for p in packages
            ],
            "publicId": "Generated by PostgreSQL using the registered INS prefix; do not provide it manually.",
            "studentDeliveryAllowed": False,
            "lifecycle": "DRAFT only; question/answer review, publication, HRMS approval and Marketplace launch remain separate.",
        }
        if not args.apply:
            print(json.dumps(plan, ensure_ascii=False, indent=2))
            return 0
        url = db_url(args)
        if not args.yes and sys.stdin.isatty():
            print(json.dumps(plan, ensure_ascii=False, indent=2))
            confirm = input("Type IMPORT to write this draft course and its packages to localhost PostgreSQL: ").strip()
            if confirm != "IMPORT":
                print("Cancelled. No database changes were made.")
                return 0
        elif not args.yes and not sys.stdin.isatty():
            raise ImportErrorSafe("--apply requires an interactive IMPORT confirmation, or pass --yes after reviewing the dry-run output.")
        try:
            import psycopg  # type: ignore[import-not-found]
        except ImportError as exc:
            raise ImportErrorSafe("psycopg 3 is required for --apply. Install with: python -m pip install 'psycopg[binary]>=3.2,<4'.") from exc
        report: dict[str, Any]
        with psycopg.connect(url) as connection:
            with connection.cursor() as cur:
                ensure_schema(cur)
                institution_id = resolve_institution(cur)
                course_id, public_id, created_course, identity_id = ensure_course(cur, institution_id, course)
                existing_release = check_editable_release(cur, institution_id, course_id)
                if existing_release is None and not created_course:
                    raise ImportErrorSafe("The existing course has no source release history. Refusing to import into an orphaned course graph; reconcile it through the supported course workflow.")
                imported = [import_package(cur, course_id, institution_id, pkg) for pkg in packages]
                lifecycle = ensure_release(cur, institution_id, course_id, created_course, identity_id, public_id)
                report = {
                    "applied": True,
                    "course": course,
                    "courseId": course_id,
                    "coursePublicId": public_id,
                    "courseIdentityId": identity_id,
                    "createdCourse": created_course,
                    "courseTypeCode": "ASSESSMENT",
                    "deliveryMode": "ASSESSMENT_ONLY",
                    "packages": imported,
                    "packageCount": len(imported),
                    **lifecycle,
                    "studentDeliveryAllowed": False,
                }
        print(json.dumps(report, ensure_ascii=False, indent=2, default=str))
        return 0
    except (ImportErrorSafe, OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
