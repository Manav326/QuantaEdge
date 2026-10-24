#!/usr/bin/env python3
"""Import the TRE-4 source papers into QuantaEdge as a draft, assessment-only course.

Default mode is a dry run. This importer never publishes assessments or treats extracted/AI
answers as official. Use --apply to write the course and three immutable draft assessment sets.
Use --submit-for-review with --apply to enqueue the release for the existing HRMS quality-review
workflow; it does not approve the release or make it shareable.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ARCHIVE = REPO_ROOT / "data" / "TRE-4_PYQ.zip"
DEFAULT_REGISTER = REPO_ROOT / "data" / "TRE-Papres" / "source-register.json"
COURSE_VERSION = "BPSC-TRE-4"
COURSE_CODE_SUFFIX = "TRE4"
COURSE_TITLE = "TRE-4 · Computer Science"
COURSE_TYPE_CODE = "ASSESSMENT"
PACKAGE_VERSION = "1.0.0"
EXPECTED_QUESTIONS = {"TRE 1.0": 120, "TRE 2.0": 150, "TRE 3.0": 150}
EXPECTED_SOURCE_IDENTITIES = {
    "TRE 1.0": {
        "GENERAL_STUDIES": {str(n) for n in range(1, 41)},
        "COMPUTER_SCIENCE": {str(n) for n in range(41, 121)},
    },
    "TRE 2.0": {
        "LANGUAGE_QUALIFYING": {str(n) for n in range(1, 31)},
        "GENERAL_STUDIES": {str(n) for n in range(31, 71)},
        "COMPUTER_SCIENCE": {str(n) for n in range(71, 151)},
    },
    "TRE 3.0": {
        # Preserve all 30 qualifying-language items present in the extracted TRE 3.0 booklet.
        "LANGUAGE_QUALIFYING": {f"E-{n}" for n in range(1, 9)} | {f"H-{n}" for n in range(9, 31)},
        "GENERAL_STUDIES": {str(n) for n in range(31, 71)},
        "COMPUTER_SCIENCE": {str(n) for n in range(71, 151)},
    },
}
PACKAGE_IDS = {
    "TRE 1.0": "tre4-bpsc-tre-1-0-computer-science",
    "TRE 2.0": "tre4-bpsc-tre-2-0-computer-science",
    "TRE 3.0": "tre4-bpsc-tre-3-0-computer-science",
}
SECTION_POLICY = {
    "GENERAL_STUDIES": "SCORED",
    "COMPUTER_SCIENCE": "SCORED",
    "LANGUAGE_QUALIFYING": "QUALIFYING_ONLY",
}


class ImportErrorSafe(RuntimeError):
    """Raised for an incomplete source bundle or a conflicting existing course."""


def sha256_json(value: Any) -> str:
    canonical = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def norm_text(value: Any) -> str:
    """Trim only empty boundary lines, preserving code/formula indentation inside source text."""
    text = str(value or "").replace("\r\n", "\n").replace("\r", "\n")
    lines = text.split("\n")
    while lines and not lines[0].strip():
        lines.pop(0)
    while lines and not lines[-1].strip():
        lines.pop()
    return "\n".join(line.rstrip() for line in lines)


def validate_question_inventory(cycle: str, questions: list[dict[str, Any]]) -> None:
    expected = EXPECTED_SOURCE_IDENTITIES[cycle]
    actual: dict[str, set[str]] = {}
    seen_questions: set[str] = set()
    seen_keys: set[tuple[str, str]] = set()
    for index, question in enumerate(questions, 1):
        if not isinstance(question, dict):
            raise ImportErrorSafe(f"{cycle} source question {index} is not an object.")
        section = str(question.get("section_code") or "").strip().upper()
        source_number = str(question.get("source_question_number") or "").strip()
        if not section or not source_number:
            raise ImportErrorSafe(f"{cycle} source question {index} is missing section_code/source_question_number.")
        qid = str(question.get("question_id") or "").strip()
        if qid:
            if qid in seen_questions:
                raise ImportErrorSafe(f"{cycle} contains duplicate question_id {qid!r}.")
            seen_questions.add(qid)
        key = (section, source_number)
        if key in seen_keys:
            raise ImportErrorSafe(f"{cycle} contains duplicate source identity {section}/{source_number}.")
        seen_keys.add(key)
        actual.setdefault(section, set()).add(source_number)

    if set(actual) != set(expected):
        raise ImportErrorSafe(
            f"{cycle} section inventory differs from the source contract; "
            f"expected sections={sorted(expected)}, actual={sorted(actual)}."
        )
    for section, expected_ids in expected.items():
        actual_ids = actual.get(section, set())
        if actual_ids != expected_ids:
            missing = sorted(expected_ids - actual_ids)
            extra = sorted(actual_ids - expected_ids)
            raise ImportErrorSafe(
                f"{cycle}/{section} source identities do not reconcile; missing={missing[:12]}, extra={extra[:12]}."
            )


def expected_question_count(cycle: str) -> int:
    try:
        return EXPECTED_QUESTIONS[cycle]
    except KeyError as exc:
        raise ImportErrorSafe(f"Unexpected TRE cycle in source inventory: {cycle!r}") from exc


def source_pdf_url(register: dict[str, Any], cycle: str) -> str:
    matches = [item for item in register.get("sources", []) if item.get("cycle") == cycle]
    if len(matches) != 1:
        raise ImportErrorSafe(f"Expected one source-register row for {cycle}; found {len(matches)}.")
    url = str(matches[0].get("readable_pdf_url") or matches[0].get("readable_pdf_citation_url") or "").strip()
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise ImportErrorSafe(f"Missing valid source URL for {cycle}.")
    return url


def localized_variant(record: dict[str, Any], language: str) -> dict[str, Any] | None:
    variants = record.get("source_variants")
    if not isinstance(variants, dict):
        return None
    value = variants.get(language)
    return value if isinstance(value, dict) else None


def variant_prompt(variant: dict[str, Any] | None) -> str:
    if not variant:
        return ""
    return norm_text(variant.get("prompt_i18n") or variant.get("prompt_raw"))


def variant_options(variant: dict[str, Any] | None) -> dict[str, dict[str, Any]]:
    if not variant:
        return {}
    raw = variant.get("options")
    if not isinstance(raw, list):
        return {}
    result: dict[str, dict[str, Any]] = {}
    for option in raw:
        if not isinstance(option, dict):
            continue
        key = str(option.get("key") or "").strip().upper()
        label = norm_text(option.get("text_i18n") or option.get("raw_text") or option.get("text"))
        if key and label:
            result[key] = option
    return result


def build_question_snapshot(record: dict[str, Any], cycle: str, pdf_path: str, pdf_sha256: str) -> dict[str, Any]:
    """Convert one source inventory row to the assessment engine's frozen snapshot contract."""
    en = localized_variant(record, "en")
    hi = localized_variant(record, "hi")
    prompts = {"en": variant_prompt(en), "hi": variant_prompt(hi)}
    prompts = {key: value for key, value in prompts.items() if value}
    prompt = prompts.get("en") or prompts.get("hi") or ""
    if not prompt:
        raw_variants = record.get("source_variants") or {}
        for variant in raw_variants.values():
            if isinstance(variant, dict) and variant_prompt(variant):
                prompt = variant_prompt(variant)
                break
    if not prompt:
        raise ImportErrorSafe(
            f"{cycle} question {record.get('source_question_number')} has no readable prompt; refusing to insert an empty question."
        )

    en_options = variant_options(en)
    hi_options = variant_options(hi)
    option_keys = list(en_options)
    option_keys.extend(key for key in hi_options if key not in en_options)
    options: list[dict[str, Any]] = []
    for sequence, key in enumerate(option_keys, 1):
        en_label = norm_text(en_options.get(key, {}).get("text_i18n") or en_options.get(key, {}).get("raw_text"))
        hi_label = norm_text(hi_options.get(key, {}).get("text_i18n") or hi_options.get(key, {}).get("raw_text"))
        text_i18n = {lang: label for lang, label in (("en", en_label), ("hi", hi_label)) if label}
        fallback = en_label or hi_label
        option: dict[str, Any] = {
            "option_key": key,
            "key": key,
            "label": fallback,
            "text_i18n": text_i18n,
            "is_correct": False,
            "sort_order": sequence,
        }
        source_option = en_options.get(key) or hi_options.get(key) or {}
        for field in ("content_kind", "code_line_indices", "math_line_indices", "source_code_preservation_notes"):
            if field in source_option:
                option[field] = source_option[field]
        options.append(option)

    section_code = str(record.get("section_code") or "COMPUTER_SCIENCE").upper()
    source_number = record.get("source_question_number")
    source_locations = record.get("source_locations") if isinstance(record.get("source_locations"), dict) else {}
    location = {
        language: value
        for language, value in source_locations.items()
        if language in {"en", "hi"} and isinstance(value, dict)
    }
    snapshot: dict[str, Any] = {
        "question_type": "MCQ",
        "prompt": prompt,
        "prompt_i18n": prompts,
        "options": options,
        "answer_payload": {"kind": "UNRESOLVED", "value": None},
        "explanation": "",
        "marks": 1,
        "section_code": section_code,
        "section_name_i18n": {
            "en": {
                "GENERAL_STUDIES": "General Studies",
                "COMPUTER_SCIENCE": "Computer Science",
                "LANGUAGE_QUALIFYING": "Qualifying Language",
            }.get(section_code, section_code.replace("_", " ").title()),
            "hi": {
                "GENERAL_STUDIES": "सामान्य अध्ययन",
                "COMPUTER_SCIENCE": "कंप्यूटर विज्ञान",
                "LANGUAGE_QUALIFYING": "अर्हक भाषा",
            }.get(section_code, section_code.replace("_", " ").title()),
        },
        "section_result_policy": SECTION_POLICY.get(section_code, "SCORED"),
        "topic_tags": record.get("topic_tags") or [],
        "source_question_number": source_number,
        "source_locations": location,
        "source_file_path": pdf_path,
        "source_file_sha256": pdf_sha256,
        "source_exam_cycle": cycle,
        "question_review_status": "PENDING_HUMAN_REVIEW",
        "answer_review_status": "PENDING_HUMAN_REVIEW",
        "native_language_only": bool(record.get("native_language_only", False)),
        "content_language_policy": record.get("content_language_policy", "BILINGUAL_SOURCE_VARIANTS"),
        "review_flags": record.get("review_flags") or [],
        "source_notes": "Source extraction only. No answer key or explanation was inferred by this importer.",
    }

    stimulus = record.get("stimulus_i18n")
    if isinstance(stimulus, dict):
        filtered = {str(k): norm_text(v) for k, v in stimulus.items() if k in {"en", "hi"} and norm_text(v)}
        if filtered:
            snapshot["stimulus_i18n"] = filtered
            snapshot["stimulus"] = filtered.get("en") or filtered.get("hi") or ""
            snapshot["stimulus_id"] = record.get("stimulus_id")
            snapshot["stimulus_source_languages"] = record.get("stimulus_source_languages") or {}
            snapshot["stimulus_content_kind"] = record.get("stimulus_content_kind", "PROSE")
            snapshot["stimulus_code_line_indices"] = record.get("stimulus_code_line_indices") or []
            snapshot["stimulus_math_line_indices"] = record.get("stimulus_math_line_indices") or []
    if record.get("question_set_id"):
        snapshot["question_set_id"] = record["question_set_id"]
        snapshot["question_set_type"] = record.get("question_set_type", "PASSAGE")
    for field in ("prompt_content_kind", "code_line_indices", "math_line_indices", "source_code_preservation_notes"):
        if field in record:
            snapshot[field] = record[field]
    if record.get("native_source_language"):
        snapshot["native_source_language"] = record["native_source_language"]
    return snapshot


def build_paper_plan(cycle: str, record: dict[str, Any], register: dict[str, Any]) -> dict[str, Any]:
    questions = record.get("questions")
    expected = expected_question_count(cycle)
    if not isinstance(questions, list) or len(questions) != expected:
        actual = len(questions) if isinstance(questions, list) else 0
        raise ImportErrorSafe(
            f"{cycle} must have exactly {expected} unique source questions; extraction returned {actual}. No DB rows were written."
        )
    validate_question_inventory(cycle, questions)
    source_meta = next((item for item in register.get("sources", []) if item.get("cycle") == cycle), None)
    if not isinstance(source_meta, dict):
        raise ImportErrorSafe(f"Source register is missing {cycle}.")
    if source_meta.get("source_status") != "VERIFIED_SOURCE_FILE":
        raise ImportErrorSafe(f"{cycle} source is not registered as a verified source file.")
    if source_meta.get("repository_distribution_rights") != "CLEARED_FOR_PUBLIC_REPOSITORY":
        raise ImportErrorSafe(f"{cycle} source is not cleared for repository distribution.")
    pdf_path = str(source_meta.get("binary_path") or "").strip()
    if not pdf_path:
        raise ImportErrorSafe(f"{cycle} source register is missing the repository PDF path.")
    root = REPO_ROOT.resolve()
    source_pdf = (REPO_ROOT / pdf_path).resolve()
    if root not in source_pdf.parents or not source_pdf.is_file():
        raise ImportErrorSafe(f"{cycle} source PDF is missing or outside the repository: {pdf_path}.")
    source_hash = str(source_meta.get("sha256") or "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", source_hash):
        raise ImportErrorSafe(f"{cycle} has no verified SHA-256 in the source register.")
    if hashlib.sha256(source_pdf.read_bytes()).hexdigest() != source_hash:
        raise ImportErrorSafe(f"{cycle} repository PDF SHA-256 differs from source-register.json.")
    if record.get("source_file", {}).get("sha256", "").lower() != source_hash:
        raise ImportErrorSafe(f"{cycle} source PDF SHA-256 differs from source-register.json.")
    url = source_pdf_url(register, cycle)
    snapshots = [
        build_question_snapshot(question, cycle, pdf_path, source_hash)
        for question in questions
    ]
    package_body = {
        "schema": "quantaedge.tre-db-import.v1",
        "package_id": PACKAGE_IDS[cycle],
        "package_version": PACKAGE_VERSION,
        "title": record.get("title", f"BPSC {cycle} — Computer Science Previous-Year Paper"),
        "cycle": cycle,
        "source_url": url,
        "source_file_path": pdf_path,
        "source_file_sha256": source_hash,
        "configuration": {
            "duration_minutes": int(record.get("exam_configuration", {}).get("duration_minutes") or 120),
            "marks_per_question": float(record.get("exam_configuration", {}).get("marks_per_question") or 1),
            "negative_marking_fraction": float(record.get("exam_configuration", {}).get("negative_marking_fraction") or 0),
            "scored_sections": record.get("exam_configuration", {}).get("scored_sections", {}),
            "section_counts": record.get("extraction_summary", {}).get("section_counts", {}),
        },
        "question_count": len(snapshots),
        "questions": snapshots,
    }
    return {
        "cycle": cycle,
        "package_id": PACKAGE_IDS[cycle],
        "package_version": PACKAGE_VERSION,
        "title": f"BPSC {cycle} — Computer Science Previous-Year Practice Test",
        "duration_minutes": int(record.get("exam_configuration", {}).get("duration_minutes") or 120),
        "negative_marking_fraction": float(record.get("exam_configuration", {}).get("negative_marking_fraction") or 0),
        "source_url": url,
        "source_file_path": pdf_path,
        "source_file_sha256": source_hash,
        "package_sha256": sha256_json(package_body),
        "question_snapshots": snapshots,
        "section_counts": record.get("extraction_summary", {}).get("section_counts", {}),
    }


def load_inventory(args: argparse.Namespace) -> tuple[dict[str, Any], dict[str, Any]]:
    if args.inventory:
        inventory_path = args.inventory.resolve()
        if not inventory_path.is_file():
            raise ImportErrorSafe(f"Inventory file not found: {inventory_path}")
        inventory = json.loads(inventory_path.read_text(encoding="utf-8"))
    else:
        archive = args.archive.resolve()
        if not archive.is_file():
            raise ImportErrorSafe(f"TRE source archive not found: {archive}")
        extractor = REPO_ROOT / "scripts" / "extract_tre_source_questions.py"
        if not extractor.is_file():
            raise ImportErrorSafe(f"Existing extraction script not found: {extractor}")
        with tempfile.TemporaryDirectory(prefix="qe-tre4-import-") as work:
            workdir = Path(work)
            output_dir = workdir / "extraction"
            command = [
                sys.executable, str(extractor),
                "--archive", str(archive),
                "--output-dir", str(output_dir),
                "--repo-root", str(workdir),
            ]
            proc = subprocess.run(command, cwd=REPO_ROOT, text=True, capture_output=True, check=False)
            if proc.returncode != 0:
                raise ImportErrorSafe(
                    "TRE source extraction failed. No DB changes were made.\n"
                    + (proc.stdout[-6000:] if proc.stdout else "")
                    + (proc.stderr[-6000:] if proc.stderr else "")
                )
            inventory_path = output_dir / "tre-question-inventory.json"
            inventory = json.loads(inventory_path.read_text(encoding="utf-8"))
    if inventory.get("schema") != "quantaedge.tre-question-source-inventory.v1":
        raise ImportErrorSafe("Unsupported TRE inventory schema.")
    register_path = args.register.resolve()
    if not register_path.is_file():
        raise ImportErrorSafe(f"Source register not found: {register_path}")
    register = json.loads(register_path.read_text(encoding="utf-8"))
    if register.get("schema") != "quantaedge.assessment-source-register.v1":
        raise ImportErrorSafe("Unsupported TRE source-register schema.")
    return inventory, register


def database_url(args: argparse.Namespace) -> str:
    value = args.database_url or os.getenv("TRE4_DATABASE_URL") or os.getenv("SPRING_DATASOURCE_URL")
    if not value:
        raise ImportErrorSafe(
            "Set TRE4_DATABASE_URL or SPRING_DATASOURCE_URL, or pass --database-url. "
            "The default run is dry-run and does not need a DB URL."
        )
    value = value.strip()
    if value.startswith("jdbc:postgresql://"):
        value = value[len("jdbc:"):]
    if not value.startswith(("postgresql://", "postgres://")):
        raise ImportErrorSafe("Database URL must be a PostgreSQL URL (or jdbc:postgresql:// URL).")
    return value


def ensure_schema(cursor: Any) -> None:
    cursor.execute("""
        SELECT to_regclass('public.course_type_registry'),
               to_regclass('public.curriculum_program_delivery_mode'),
               to_regclass('public.course_assessment_pack'),
               to_regclass('public.course_assessment_pack_item'),
               to_regprocedure('public.course_content_fingerprint(bigint)'),
               to_regprocedure('public.assessment_only_course_ready(bigint)'),
               pg_get_functiondef(to_regprocedure('public.course_content_fingerprint(bigint)')),
               pg_get_functiondef(to_regprocedure('public.assessment_only_course_ready(bigint)'))
    """)
    row = cursor.fetchone()
    if not row or any(value is None for value in row):
        raise ImportErrorSafe(
            "Flyway V168, V177, V180, and V197 are required. Start the API against this database and verify migrations complete before importing."
        )
    fingerprint_definition = str(row[6] or "")
    readiness_definition = str(row[7] or "")
    if "assessment-pack-item" not in fingerprint_definition:
        raise ImportErrorSafe(
            "Flyway V196 is required so course release fingerprints include test packs and pack items."
        )
    if "course_assessment_pack_item" not in readiness_definition:
        raise ImportErrorSafe(
            "Flyway V197 is required: assessment-only release readiness must validate test packs, not school chapters."
        )
    if not re.search(
        r"'TRE 3\\.0'::varchar,\\s*'tre4-bpsc-tre-3-0-computer-science'::varchar,\\s*150",
        readiness_definition,
    ):
        raise ImportErrorSafe(
            "Flyway V197 is required: the TRE 3.0 readiness contract must expect all 150 source questions."
        )


def get_or_create_course(cursor: Any, institution_id: int) -> tuple[int, str, bool]:
    """Create/reuse an ASSESSMENT course without fabricating a school class or K12 hierarchy."""
    internal_code = f"I{institution_id}_{COURSE_CODE_SUFFIX}"
    cursor.execute("""
        SELECT id,display_name,status,public_id,class_id,course_type_code
        FROM curriculum_program
        WHERE code=%s AND curriculum_version=%s
        FOR UPDATE
    """, (internal_code, COURSE_VERSION))
    rows = cursor.fetchall()
    if len(rows) > 1:
        raise ImportErrorSafe("Multiple TRE-4 course rows exist for the same institution-scoped key; refusing to choose one.")
    if rows:
        course_id, display_name, status, public_id, class_id, course_type = rows[0]
        if display_name != COURSE_TITLE or status != "ACTIVE" or not public_id:
            raise ImportErrorSafe("Existing TRE-4 course metadata differs from the expected source course.")
        if class_id is not None:
            raise ImportErrorSafe(
                "Existing TRE-4 course still has a curriculum-class link. This importer now requires the registered "
                "ASSESSMENT hierarchy and will not rewrite a K12-shaped course in place. Reconcile that draft through "
                "the course-version workflow before retrying."
            )
        if course_type != COURSE_TYPE_CODE:
            raise ImportErrorSafe("Existing TRE-4 course is not registered as course_type_code='ASSESSMENT'.")
        return int(course_id), str(public_id), False
    cursor.execute("""
        INSERT INTO curriculum_program(
          class_id,code,display_name,stream_code,curriculum_version,status,notes,public_id_prefix,
          course_type_code
        ) VALUES (NULL,%s,%s,'BPSC_TRE',%s,'ACTIVE',%s,'INS','ASSESSMENT')
        RETURNING id,public_id
    """, (
        internal_code, COURSE_TITLE, COURSE_VERSION,
        "QuantaEdge-origin BPSC TRE-4 assessment course. Hierarchy is course → test packs → assessments → ordered question snapshots; source questions and answers remain review-gated.",
    ))
    course_id, public_id = cursor.fetchone()
    return int(course_id), str(public_id), True


def ensure_course_identity(cursor: Any, institution_id: int, course_id: int) -> int:
    """Attach the TRE4 course to its source-owned identity before content import."""
    stable_key = f"I{institution_id}_TRE4"
    cursor.execute("""
        SELECT p.display_name,p.course_type_code,p.course_identity_id,
               i.source_institution_id,i.stable_course_key,i.course_type_code
        FROM curriculum_program p
        LEFT JOIN institution_course_identity i ON i.id=p.course_identity_id
        WHERE p.id=%s FOR UPDATE OF p
    """, (course_id,))
    row = cursor.fetchone()
    if not row or row[0] != COURSE_TITLE:
        raise ImportErrorSafe("TRE-4 course identity cannot be reconciled with the expected course.")
    display_name, course_type, identity_id, owner_id, saved_key, identity_type = row
    if identity_id is not None:
        if int(owner_id or 0) != institution_id or saved_key != stable_key:
            raise ImportErrorSafe("TRE-4 course is linked to a different source identity; refusing cross-institution mutation.")
        if course_type != "ASSESSMENT" or identity_type != "ASSESSMENT":
            raise ImportErrorSafe("TRE-4 course identity has a conflicting type; reconcile it through the source-owned draft workflow.")
        return int(identity_id)

    cursor.execute("""
        SELECT count(*) FROM institution_course_release
        WHERE program_id=%s AND release_state NOT IN ('DRAFT')
    """, (course_id,))
    non_draft_releases = int(cursor.fetchone()[0])
    cursor.execute("SELECT count(*) FROM assessment WHERE course_id=%s AND status='PUBLISHED'", (course_id,))
    published_assessments = int(cursor.fetchone()[0])
    if non_draft_releases or published_assessments:
        raise ImportErrorSafe("An unlinked TRE-4 course has reviewed/published history; refusing to rewrite its identity or type.")

    cursor.execute("""
        SELECT id,display_name,course_type_code,status
        FROM institution_course_identity
        WHERE source_institution_id=%s AND stable_course_key=%s
        FOR UPDATE
    """, (institution_id, stable_key))
    existing = cursor.fetchone()
    if existing:
        identity_id, identity_name, identity_type, identity_status = existing
        if identity_name != COURSE_TITLE or identity_type not in {"ASSESSMENT", "K12"} or identity_status != "ACTIVE":
            raise ImportErrorSafe("The stable TRE-4 identity exists with conflicting metadata.")
        if identity_type != "ASSESSMENT":
            cursor.execute("""
                UPDATE institution_course_identity SET course_type_code='ASSESSMENT',updated_at=now()
                WHERE id=%s AND source_institution_id=%s AND status='ACTIVE'
            """, (identity_id, institution_id))
    else:
        cursor.execute("""
            INSERT INTO institution_course_identity(
              source_institution_id,stable_course_key,display_name,course_type_code
            ) VALUES (%s,%s,%s,'ASSESSMENT') RETURNING id
        """, (institution_id, stable_key, COURSE_TITLE))
        identity_id = int(cursor.fetchone()[0])

    cursor.execute("""
        UPDATE curriculum_program
        SET course_type_code='ASSESSMENT',course_identity_id=%s,
            stream_code='BPSC_TRE',updated_at=now()
        WHERE id=%s AND course_identity_id IS NULL
    """, (identity_id, course_id))
    if cursor.rowcount != 1:
        raise ImportErrorSafe("TRE-4 course identity changed concurrently; retry the import.")
    return int(identity_id)


def ensure_delivery_mode(cursor: Any, course_id: int) -> None:
    cursor.execute("SELECT delivery_mode FROM curriculum_program_delivery_mode WHERE program_id=%s FOR UPDATE", (course_id,))
    row = cursor.fetchone()
    if row:
        if row[0] != "ASSESSMENT_ONLY":
            raise ImportErrorSafe("TRE-4 course has a conflicting delivery mode; refusing to overwrite it.")
        return
    cursor.execute("""
        INSERT INTO curriculum_program_delivery_mode(program_id,delivery_mode)
        VALUES (%s,'ASSESSMENT_ONLY')
    """, (course_id,))


def ensure_source_assignment(cursor: Any, institution_id: int, release_id: int) -> int:
    cursor.execute("""
        SELECT id,status,assignment_source FROM institution_course_assignment
        WHERE institution_id=%s AND course_release_id=%s FOR UPDATE
    """, (institution_id, release_id))
    row = cursor.fetchone()
    if row:
        if row[1] != "ASSIGNED":
            raise ImportErrorSafe("The QuantaEdge source assignment exists but is not active; refusing to reactivate it silently.")
        return int(row[0])
    cursor.execute("""
        INSERT INTO institution_course_assignment(
          institution_id,course_release_id,status,assignment_source,assigned_at,created_at,updated_at
        ) VALUES (%s,%s,'ASSIGNED','ORIGIN',now(),now(),now()) RETURNING id
    """, (institution_id, release_id))
    return int(cursor.fetchone()[0])


def insert_assessment(cursor: Any, course_id: int, paper: dict[str, Any]) -> tuple[int, bool]:
    """Persist a test as a course-level ASSESSMENT, not under a K12 subject/chapter."""
    package_id = paper["package_id"]
    cursor.execute("""
        SELECT id,title,status,source_package_sha256,
               (SELECT count(*) FROM assessment_question aq WHERE aq.assessment_id=a.id) AS question_count
        FROM assessment a
        WHERE a.course_id=%s AND a.source_package_id=%s AND a.source_package_version=%s
        FOR UPDATE
    """, (course_id, package_id, paper["package_version"]))
    existing = cursor.fetchall()
    expected_count = len(paper["question_snapshots"])
    if existing:
        if len(existing) != 1:
            raise ImportErrorSafe(f"Multiple DB assessments exist for {package_id} {paper['package_version']}.")
        assessment_id, title, status, digest, question_count = existing[0]
        if (
            title != paper["title"] or digest is None
            or str(digest).strip().lower() != paper["package_sha256"]
            or int(question_count) != expected_count
        ):
            raise ImportErrorSafe(
                f"Existing {package_id} differs from source inventory. No updates/deletes were attempted; review it and create a reviewed version explicitly."
            )
        return int(assessment_id), False

    cursor.execute("""
        INSERT INTO assessment(
          course_id,title,description,subject_id,chapter_id,duration_minutes,max_attempts,
          negative_marking_fraction,status,assessment_type,is_generated,source_package_id,
          source_package_version,source_package_sha256,source_exam_cycle,source_url,portable_assessment_type
        ) VALUES (%s,%s,%s,NULL,NULL,%s,3,%s,'DRAFT','PREVIOUS_YEAR_PAPER',false,%s,%s,%s,%s,%s,'PREVIOUS_YEAR_PAPER')
        RETURNING id
    """, (
        course_id, paper["title"],
        f"{paper['cycle']} source-paper practice test. Imported source wording only; answer key and explanations require human review.",
        paper["duration_minutes"], paper["negative_marking_fraction"],
        package_id, paper["package_version"], paper["package_sha256"], paper["cycle"], paper["source_url"],
    ))
    assessment_id = int(cursor.fetchone()[0])
    for sequence, snapshot in enumerate(paper["question_snapshots"], 1):
        cursor.execute("""
            INSERT INTO assessment_question(
              assessment_id,source_question_id,sequence_no,max_marks,question_snapshot
            ) VALUES (%s,NULL,%s,1,%s::jsonb)
        """, (assessment_id, sequence, json.dumps(snapshot, ensure_ascii=False, separators=(",", ":"))))
    cursor.execute("""
        INSERT INTO assessment_audit_log(
          assessment_id,actor_role,action,previous_status,new_status,note,occurred_at
        ) VALUES (%s,'SYSTEM_IMPORT','ASSESSMENT_IMPORTED',NULL,'DRAFT',%s,now())
    """, (assessment_id, f"Imported {expected_count} source questions from {package_id}; all answer keys remain unresolved and the test is not published."))
    return assessment_id, True


def ensure_test_pack(
    cursor: Any, institution_id: int, course_id: int, assessment_id: int, paper: dict[str, Any]
) -> tuple[int, bool]:
    """Create one versioned test pack per paper and attach its ordered assessment item."""
    package_id = str(paper["package_id"])
    package_version = str(paper["package_version"])
    pack_title = f"BPSC {paper['cycle']} — Computer Science Previous-Year Paper"
    cursor.execute("""
        SELECT id,source_institution_id,title,status,content_sha256
        FROM course_assessment_pack
        WHERE course_id=%s AND pack_code=%s AND pack_version=%s
        FOR UPDATE
    """, (course_id, package_id, package_version))
    existing = cursor.fetchall()
    if existing:
        if len(existing) != 1:
            raise ImportErrorSafe(f"Multiple test packs exist for {package_id} {package_version}; refusing to choose one.")
        pack_id, source_institution_id, title, status, content_sha256 = existing[0]
        if (
            int(source_institution_id) != institution_id
            or title != pack_title
            or str(content_sha256 or "").strip().lower() != str(paper["package_sha256"]).lower()
            or status not in {"DRAFT", "PUBLISHED"}
        ):
            raise ImportErrorSafe(
                f"Existing test pack {package_id} {package_version} conflicts with the verified source package; "
                "no existing pack was changed."
            )
        cursor.execute("""
            SELECT assessment_id,sequence_no,test_kind
            FROM course_assessment_pack_item
            WHERE pack_id=%s ORDER BY sequence_no,id
        """, (pack_id,))
        items = cursor.fetchall()
        if len(items) != 1 or (
            int(items[0][0]) != assessment_id
            or int(items[0][1]) != 1
            or items[0][2] != "PREVIOUS_YEAR_PAPER"
        ):
            raise ImportErrorSafe(
                f"Existing test pack {package_id} has a missing or conflicting assessment item; no records were changed."
            )
        return int(pack_id), False

    cursor.execute("""
        SELECT pack.id
        FROM course_assessment_pack_item item
        JOIN course_assessment_pack pack ON pack.id=item.pack_id
        WHERE item.assessment_id=%s
        FOR UPDATE OF pack
    """, (assessment_id,))
    if cursor.fetchone() is not None:
        raise ImportErrorSafe(
            f"Assessment {assessment_id} is already attached to another test pack; refusing to duplicate it."
        )
    cursor.execute("""
        INSERT INTO course_assessment_pack(
          course_id,source_institution_id,pack_code,pack_version,title,description,
          status,content_sha256,open_book_enabled,resource_policy,created_by_staff_id
        ) VALUES (%s,%s,%s,%s,%s,%s,'DRAFT',%s,false,
                  '{"mode":"CLOSED_BOOK","resources":[]}'::jsonb,NULL)
        RETURNING id
    """, (
        course_id, institution_id, package_id, package_version, pack_title,
        f"Versioned source-paper pack for {paper['cycle']}. The assessment and every answer remain draft until human review.",
        paper["package_sha256"],
    ))
    pack_id = int(cursor.fetchone()[0])
    cursor.execute("""
        INSERT INTO course_assessment_pack_item(pack_id,assessment_id,sequence_no,test_kind)
        VALUES (%s,%s,1,'PREVIOUS_YEAR_PAPER')
    """, (pack_id, assessment_id))
    return pack_id, True


def database_import(args: argparse.Namespace, papers: list[dict[str, Any]], archive_sha256: str) -> dict[str, Any]:
    try:
        import psycopg  # type: ignore[import-not-found]
    except ImportError as exc:
        raise ImportErrorSafe(
            "psycopg 3 is required for --apply. Install it with: python -m pip install 'psycopg[binary]>=3.2,<4'"
        ) from exc
    url = database_url(args)
    summary: dict[str, Any] = {"course": COURSE_TITLE, "deliveryMode": "ASSESSMENT_ONLY", "papers": [], "submittedForReview": False}
    with psycopg.connect(url) as connection:
        with connection.cursor() as cursor:
            ensure_schema(cursor)
            cursor.execute("""
                SELECT id,display_name,status FROM institution_tenant
                WHERE tenant_code='QUANTA-EDGE' FOR UPDATE
            """)
            qe_rows = cursor.fetchall()
            if len(qe_rows) != 1 or qe_rows[0][1] != "QuantaEdge" or qe_rows[0][2] != "ACTIVE":
                raise ImportErrorSafe("Expected exactly one active QuantaEdge source institution (QUANTA-EDGE).")
            institution_id = int(qe_rows[0][0])
            course_id, public_id, created_course = get_or_create_course(cursor, institution_id)
            course_identity_id = ensure_course_identity(cursor, institution_id, course_id)
            ensure_delivery_mode(cursor, course_id)

            created_assessments: list[int] = []
            created_test_packs: list[int] = []
            for paper in papers:
                assessment_id, created = insert_assessment(cursor, course_id, paper)
                pack_id, pack_created = ensure_test_pack(cursor, institution_id, course_id, assessment_id, paper)
                if created:
                    created_assessments.append(assessment_id)
                if pack_created:
                    created_test_packs.append(pack_id)
                summary["papers"].append({
                    "cycle": paper["cycle"],
                    "assessmentId": assessment_id,
                    "testPackId": pack_id,
                    "testPackCode": paper["package_id"],
                    "testPackVersion": paper["package_version"],
                    "questionCount": len(paper["question_snapshots"]),
                    "status": "DRAFT",
                    "testPackCreated": pack_created,
                    "inserted": created,
                    "packageSha256": paper["package_sha256"],
                })

            cursor.execute("SELECT course_content_fingerprint(%s)", (course_id,))
            fingerprint = str(cursor.fetchone()[0]).strip()
            cursor.execute("""
                SELECT id,release_version,release_state,content_fingerprint
                FROM institution_course_release
                WHERE source_institution_id=%s AND program_id=%s
                ORDER BY release_version DESC,id DESC LIMIT 1 FOR UPDATE
            """, (institution_id, course_id))
            release_row = cursor.fetchone()
            if args.submit_for_review:
                cursor.execute("SELECT assessment_only_course_ready(%s)", (course_id,))
                ready_row = cursor.fetchone()
                if not ready_row or ready_row[0] is not True:
                    raise ImportErrorSafe("Assessment-only source-package readiness failed; the release cannot be queued for HRMS review.")
            release_created = False
            release_submitted = False
            if release_row is None:
                initial_state = "IN_QUALITY_REVIEW" if args.submit_for_review else "DRAFT"
                cursor.execute("""
                    INSERT INTO institution_course_release(
                      source_institution_id,program_id,release_version,release_state,content_fingerprint,
                      submitted_at,legacy_migration,legacy_migration_note,created_at,updated_at
                    ) VALUES (%s,%s,1,%s,%s,CASE WHEN %s THEN now() ELSE NULL END,false,NULL,now(),now())
                    RETURNING id,release_version,release_state,content_fingerprint
                """, (institution_id, course_id, initial_state, fingerprint, args.submit_for_review))
                release_row = cursor.fetchone()
                release_created = True
                release_submitted = args.submit_for_review
            else:
                release_id, release_version, release_state, saved_fingerprint = release_row
                if str(saved_fingerprint).strip() != fingerprint and release_state != "DRAFT":
                    raise ImportErrorSafe(
                        f"Course release {release_id} is {release_state} but its content fingerprint changed. "
                        "The script will not rewrite approved/shareable review evidence; submit a new release through the Institution workflow."
                    )
                if release_state in {"REJECTED", "WITHDRAWN"}:
                    raise ImportErrorSafe(
                        f"Latest course release is {release_state}; create a new release through the Institution workflow before retrying."
                    )
                if release_state == "DRAFT":
                    if args.submit_for_review:
                        cursor.execute("""
                            SELECT assessment_only_course_ready(%s)
                        """, (course_id,))
                        if cursor.fetchone()[0] is not True:
                            raise ImportErrorSafe("Assessment-only source-package readiness failed; cannot queue the release for HRMS review.")
                        cursor.execute("""
                            UPDATE institution_course_release
                            SET release_state='IN_QUALITY_REVIEW',content_fingerprint=%s,submitted_at=now(),
                                submitted_by_institution_admin_id=NULL,updated_at=now()
                            WHERE id=%s AND source_institution_id=%s AND release_state='DRAFT'
                        """, (fingerprint, release_id, institution_id))
                        release_submitted = True
                    else:
                        cursor.execute("""
                            UPDATE institution_course_release SET content_fingerprint=%s,updated_at=now()
                            WHERE id=%s AND source_institution_id=%s AND release_state='DRAFT'
                        """, (fingerprint, release_id, institution_id))
                elif str(saved_fingerprint).strip() != fingerprint:
                    raise ImportErrorSafe("Release content differs from the imported source bundle; no release state was changed.")
                cursor.execute("""
                    SELECT id,release_version,release_state,content_fingerprint
                    FROM institution_course_release WHERE id=%s
                """, (release_id,))
                release_row = cursor.fetchone()

            release_id, release_version, release_state, _ = release_row
            assignment_id = ensure_source_assignment(cursor, institution_id, int(release_id))
            if created_course:
                cursor.execute("""
                    INSERT INTO institution_course_lifecycle_event(
                      institution_id,course_release_id,assignment_id,event_type,details
                    ) VALUES (%s,%s,%s,'COURSE_CREATED',%s::jsonb)
                """, (institution_id, release_id, assignment_id, json.dumps({
                    "courseId": course_id, "courseCode": f"I{institution_id}_TRE4",
                    "courseIdentityId": course_identity_id, "publicId": public_id,
                    "courseTypeCode": COURSE_TYPE_CODE,
                    "hierarchy": "COURSE_TEST_PACK_ASSESSMENT_ORDERED_QUESTIONS",
                    "testPackIds": [row["testPackId"] for row in summary["papers"]],
                    "deliveryMode": "ASSESSMENT_ONLY", "source": "scripts/import_tre4_course.py",
                })))
            if release_submitted:
                cursor.execute("""
                    INSERT INTO institution_course_lifecycle_event(
                      institution_id,course_release_id,assignment_id,event_type,details
                    ) VALUES (%s,%s,%s,'RELEASE_SUBMITTED',%s::jsonb)
                """, (institution_id, release_id, assignment_id, json.dumps({
                    "courseId": course_id, "workflow": "TRE-4 importer",
                    "deliveryMode": "ASSESSMENT_ONLY", "sourceArchiveSha256": archive_sha256,
                    "reviewRequired": True, "studentDeliveryAllowed": False,
                })))
            summary.update({
                "applied": True,
                "institutionId": institution_id,
                "courseId": course_id,
                "coursePublicId": public_id,
                "courseTypeCode": COURSE_TYPE_CODE,
                "hierarchy": "COURSE → TEST PACK → ASSESSMENT → ORDERED QUESTIONS",
                "testPackCount": len(summary["papers"]),
                "createdTestPacks": created_test_packs,
                "releaseId": int(release_id),
                "releaseVersion": int(release_version),
                "releaseState": str(release_state),
                "assignmentId": assignment_id,
                "createdCourse": created_course,
                "createdAssessments": created_assessments,
                "releaseFingerprint": fingerprint,
                "studentDeliveryAllowed": False,
                "nextStep": (
                    "HRMS quality review, then QuantaEdge origin approval, then HRMS can assign the SHAREABLE release."
                    if str(release_state) == "IN_QUALITY_REVIEW"
                    else "Submit the source release for HRMS quality review; draft assessments remain hidden from learners."
                ),
            })
    return summary


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, default=DEFAULT_ARCHIVE, help="Source archive ZIP (default: data/TRE-4_PYQ.zip).")
    parser.add_argument("--register", type=Path, default=DEFAULT_REGISTER, help="Verified source-register.json.")
    parser.add_argument("--inventory", type=Path, help="Use a previously generated tre-question-inventory.json instead of extracting.")
    parser.add_argument("--database-url", help="PostgreSQL URL; otherwise TRE4_DATABASE_URL or SPRING_DATASOURCE_URL is used.")
    parser.add_argument("--apply", action="store_true", help="Commit validated course, chapter, draft assessments, and release records. Without this flag, no DB writes occur.")
    parser.add_argument("--submit-for-review", action="store_true", help="With --apply, queue a DRAFT release for HRMS quality review after all import gates pass. Does not approve or publish it.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.submit_for_review and not args.apply:
        print("ERROR: --submit-for-review requires --apply.", file=sys.stderr)
        return 2
    try:
        inventory, register = load_inventory(args)
        papers_map = inventory.get("papers")
        if not isinstance(papers_map, dict):
            raise ImportErrorSafe("Inventory has no papers object.")
        papers = [build_paper_plan(cycle, papers_map.get(cycle) or {}, register) for cycle in EXPECTED_QUESTIONS]
        archive_hash = str(inventory.get("source_archive", {}).get("sha256") or "")
        if not re.fullmatch(r"[0-9a-fA-F]{64}", archive_hash):
            raise ImportErrorSafe("Inventory has no valid source archive SHA-256.")
        plan = {
            "mode": "APPLY" if args.apply else "DRY_RUN",
            "course": COURSE_TITLE,
            "sourceInstitution": "QUANTA-EDGE",
            "courseTypeCode": COURSE_TYPE_CODE,
            "deliveryMode": "ASSESSMENT_ONLY",
            "hierarchy": "COURSE → TEST PACK → ASSESSMENT → ORDERED QUESTIONS",
            "testPackCount": len(papers),
            "sourceArchiveSha256": archive_hash,
            "papers": [
                {
                    "cycle": paper["cycle"],
                    "packageId": paper["package_id"],
                    "testPackCode": paper["package_id"],
                    "testPackVersion": paper["package_version"],
                    "questionCount": len(paper["question_snapshots"]),
                    "durationMinutes": paper["duration_minutes"],
                    "negativeMarkingFraction": paper["negative_marking_fraction"],
                    "sourceFileSha256": paper["source_file_sha256"],
                    "packageSha256": paper["package_sha256"],
                    "draftOnly": True,
                    "answersReviewed": False,
                }
                for paper in papers
            ],
            "submitForReview": bool(args.submit_for_review),
            "studentDeliveryAllowed": False,
        }
        if not args.apply:
            print(json.dumps(plan, ensure_ascii=False, indent=2))
            return 0
        report = database_import(args, papers, archive_hash)
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0
    except (ImportErrorSafe, OSError, ValueError, json.JSONDecodeError, subprocess.SubprocessError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
