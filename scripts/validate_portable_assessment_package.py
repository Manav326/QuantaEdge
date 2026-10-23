#!/usr/bin/env python3
"""Validate portable QuantaEdge assessment packages without assuming a school-class schema.

Validation is structural and does not import, publish, or certify question accuracy. For bilingual
scored questions it requires the actual English and Hindi source variants, not AI translations.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

PACKAGE_SCHEMA = "quantaedge.portable-assessment-package.v1"
RIGHTS_CLEARED_FOR_PUBLIC_REPO = "CLEARED_FOR_PUBLIC_REPOSITORY"
VALID_RIGHTS = {"UNCONFIRMED", "CLEARED_FOR_INTERNAL_USE", RIGHTS_CLEARED_FOR_PUBLIC_REPO, "DO_NOT_REDISTRIBUTE"}
VALID_POLICIES = {"SCORED", "QUALIFYING_ONLY", "PRACTICE_ONLY"}
VALID_QUESTION_TYPES = {
    "MCQ", "TRUE_FALSE", "INFERENCE", "INPUT", "NUMERICAL", "SHORT_ANSWER",
    "LONG_ANSWER", "CASE_BASED", "SOURCE_BASED", "DIAGRAM", "MAP",
    "ASSERTION_REASON", "MATCH", "ORDER",
}
LANGUAGE_KEY = re.compile(r"^[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})?$")


def _is_url(value: Any) -> bool:
    if not isinstance(value, str):
        return False
    parsed = urlparse(value)
    return parsed.scheme in {"http", "https"} and bool(parsed.netloc)


def _localized(value: Any, where: str, required_languages: set[str], errors: list[str],
               min_languages: int = 1) -> None:
    if not isinstance(value, dict) or len(value) < min_languages:
        errors.append(f"{where} must be a localized object with at least {min_languages} language variant(s).")
        return
    for language, text in value.items():
        if not isinstance(language, str) or not LANGUAGE_KEY.fullmatch(language):
            errors.append(f"{where} has an invalid language key {language!r}.")
        if not isinstance(text, str) or not text.strip():
            errors.append(f"{where}.{language} must be non-empty text.")
    missing = sorted(required_languages - set(value))
    if missing:
        errors.append(f"{where} is missing required source-language variants: {', '.join(missing)}.")


def _localized_steps(value: Any, where: str, required_languages: set[str], errors: list[str]) -> None:
    if not isinstance(value,dict) or not value:
        errors.append(f"{where} is required for stepwise solutions.")
        return
    missing=sorted(required_languages-set(value))
    if missing:
        errors.append(f"{where} is missing required language steps: {', '.join(missing)}.")
    for language,steps in value.items():
        if not isinstance(language,str) or not LANGUAGE_KEY.fullmatch(language):
            errors.append(f"{where} has an invalid language key {language!r}.")
        if not isinstance(steps,list) or not 2<=len(steps)<=8:
            errors.append(f"{where}.{language} must contain 2–8 ordered, concise steps.")
            continue
        for index,step in enumerate(steps,1):
            if not isinstance(step,str) or not step.strip() or len(step)>240:
                errors.append(f"{where}.{language}[{index}] must be non-empty text of at most 240 characters.")


def validate_package(package: Any, repo_root: Path | None = None,
                     require_complete_answers: bool = False,
                     check_public_repository: bool = False) -> list[str]:
    errors: list[str] = []
    if not isinstance(package, dict):
        return ["Root must be a JSON object."]
    if package.get("schema") != PACKAGE_SCHEMA:
        errors.append(f"schema must be {PACKAGE_SCHEMA}.")
    package_id = package.get("package_id")
    if not isinstance(package_id, str) or not re.fullmatch(r"[a-z0-9][a-z0-9._-]{2,119}", package_id):
        errors.append("package_id must be a stable lowercase slug of 3–120 characters.")
    package_version=package.get("package_version")
    if not isinstance(package_version,str) or not package_version.strip() or len(package_version)>40 or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]*",package_version):
        errors.append("package_version must be a stable version label of at most 40 characters.")
    if not isinstance(package.get("title"), str) or not package["title"].strip() or len(package["title"]) > 240:
        errors.append("title must be non-empty text of at most 240 characters.")
    if package.get("assessment_type") not in {"PREVIOUS_YEAR_PAPER", "MOCK_EXAM", "DIAGNOSTIC", "PRACTICE_SET"}:
        errors.append("assessment_type is not supported.")

    subject = package.get("subject")
    if not isinstance(subject, dict) or not isinstance(subject.get("name"), str) or not subject["name"].strip():
        errors.append("subject.name is required; no class_code or class_number is required or accepted.")
    elif any(k in subject for k in ("class_code", "class_number", "grade", "curriculum_class_id")):
        errors.append("Portable subject metadata must not encode a school class.")

    source = package.get("source")
    if not isinstance(source, dict):
        errors.append("source must be an object.")
        source = {}
    for field in ("issuer", "exam_name", "cycle"):
        if not isinstance(source.get(field), str) or not source[field].strip():
            errors.append(f"source.{field} is required.")
    if not _is_url(source.get("source_url")):
        errors.append("source.source_url must be an absolute HTTP(S) URL.")
    languages = source.get("available_languages")
    if not isinstance(languages, list) or not languages or any(
        not isinstance(lang, str) or not LANGUAGE_KEY.fullmatch(lang) for lang in languages
    ) or len(set(languages)) != len(languages):
        errors.append("source.available_languages must be a unique list of valid language codes.")
        languages = []
    required_scored_languages = {"en", "hi"} if {"en", "hi"}.issubset(set(languages)) else set()
    if source.get("repository_distribution_rights") not in VALID_RIGHTS:
        errors.append("source.repository_distribution_rights must use a declared rights state.")
    source_path = source.get("source_file_path")
    source_hash = source.get("source_file_sha256")
    if source_path is not None and not isinstance(source_path, str):
        errors.append("source.source_file_path must be a repository-relative string or null.")
    if source_hash is not None and (
        not isinstance(source_hash, str) or not re.fullmatch(r"[a-fA-F0-9]{64}", source_hash)
    ):
        errors.append("source.source_file_sha256 must be a 64-character SHA-256 hex string or null.")
    if check_public_repository:
        if source.get("repository_distribution_rights") != RIGHTS_CLEARED_FOR_PUBLIC_REPO:
            errors.append("Public-repository check failed: source distribution rights are not cleared for public redistribution.")
        if not source_path:
            errors.append("Public-repository check failed: source_file_path must point to the exact stored source PDF.")
        elif repo_root:
            resolved = (repo_root / source_path).resolve()
            root = repo_root.resolve()
            if root not in resolved.parents:
                errors.append("source.source_file_path must remain inside the repository.")
            elif not resolved.is_file():
                errors.append(f"Source PDF does not exist at {source_path}.")
            elif source_hash and hashlib.sha256(resolved.read_bytes()).hexdigest().lower() != source_hash.lower():
                errors.append(f"SHA-256 mismatch for {source_path}.")

    config = package.get("configuration")
    if not isinstance(config, dict):
        errors.append("configuration must be an object.")
        config = {}
    duration = config.get("duration_minutes")
    if not isinstance(duration, int) or isinstance(duration, bool) or not 1 <= duration <= 240:
        errors.append("configuration.duration_minutes must be an integer from 1 to 240.")
    marks = config.get("marks_per_question")
    if not isinstance(marks, (int, float)) or isinstance(marks, bool) or marks <= 0:
        errors.append("configuration.marks_per_question must be positive.")
    penalty = config.get("negative_marking_fraction")
    if not isinstance(penalty, (int, float)) or isinstance(penalty, bool) or not 0 <= penalty <= 1:
        errors.append("configuration.negative_marking_fraction must be between 0 and 1.")
    unanswered = config.get("unanswered_mark")
    if not isinstance(unanswered, (int, float)) or isinstance(unanswered, bool) or unanswered < 0:
        errors.append("configuration.unanswered_mark must be non-negative.")

    editorial = package.get("editorial")
    if not isinstance(editorial, dict) or editorial.get("status") != "DRAFT" or editorial.get("student_delivery_allowed") is not False:
        errors.append("editorial must remain status DRAFT and student_delivery_allowed false.")
    elif editorial.get("review_status") != "PENDING_HUMAN_REVIEW":
        errors.append("editorial.review_status must remain PENDING_HUMAN_REVIEW.")
    if "course_binding" in package:
        errors.append("course_binding is not portable content; use a separate institution/course deployment binding.")

    sections = package.get("sections")
    section_by_code: dict[str, dict[str, Any]] = {}
    if not isinstance(sections, list) or not sections:
        errors.append("sections must define the paper's actual sections and score policies.")
        sections = []
    for index, section in enumerate(sections, 1):
        where = f"sections[{index}]"
        if not isinstance(section, dict):
            errors.append(f"{where} must be an object.")
            continue
        code = section.get("code")
        if not isinstance(code, str) or not re.fullmatch(r"[A-Z][A-Z0-9_]{1,49}", code):
            errors.append(f"{where}.code must be an uppercase stable section code.")
        elif code in section_by_code:
            errors.append(f"{where}.code is duplicated: {code}.")
        else:
            section_by_code[code] = section
        policy = section.get("result_policy")
        if policy not in VALID_POLICIES:
            errors.append(f"{where}.result_policy must be one of {', '.join(sorted(VALID_POLICIES))}.")
        _localized(section.get("name_i18n"), f"{where}.name_i18n", set(), errors)
        count = section.get("question_count")
        if not isinstance(count, int) or isinstance(count, bool) or count < 1:
            errors.append(f"{where}.question_count must be a positive integer.")

    questions = package.get("questions")
    if not isinstance(questions, list) or not 1 <= len(questions) <= 250:
        errors.append("questions must contain between 1 and 250 items.")
        questions = []
    ids: set[str] = set()
    source_numbers: set[tuple[str, str]] = set()
    stimulus_groups: dict[str, str] = {}
    question_set_sizes: dict[str, int] = {}
    actual_section_counts: dict[str, int] = {}
    for index, q in enumerate(questions, 1):
        where = f"questions[{index}]"
        if not isinstance(q, dict):
            errors.append(f"{where} must be an object.")
            continue
        qid = q.get("id")
        if not isinstance(qid, str) or not re.fullmatch(r"[a-z0-9][a-z0-9._-]{1,119}", qid):
            errors.append(f"{where}.id must be a stable lowercase slug.")
        elif qid in ids:
            errors.append(f"{where}.id is duplicated: {qid}.")
        else:
            ids.add(qid)
        code = q.get("section_code")
        if not isinstance(code, str) or code not in section_by_code:
            errors.append(f"{where}.section_code must reference a declared section.")
        else:
            actual_section_counts[code] = actual_section_counts.get(code, 0) + 1
            policy = section_by_code[code].get("result_policy")
            if q.get("section_result_policy", policy) != policy:
                errors.append(f"{where}.section_result_policy conflicts with sections[{code}].result_policy.")
            required_languages = required_scored_languages if policy == "SCORED" and code in {"GENERAL_STUDIES", "COMPUTER_SCIENCE"} else set()
            _localized(q.get("prompt_i18n"), f"{where}.prompt_i18n", required_languages, errors)
            section_names = q.get("section_name_i18n")
            if section_names is not None:
                _localized(section_names, f"{where}.section_name_i18n", set(), errors)
        stimulus_id = q.get("stimulus_id")
        stimulus_i18n = q.get("stimulus_i18n")
        if stimulus_i18n is not None:
            _localized(stimulus_i18n, f"{where}.stimulus_i18n", set(), errors)
        if stimulus_id is not None:
            if not isinstance(stimulus_id, str) or not re.fullmatch(r"[a-z0-9][a-z0-9._-]{1,119}", stimulus_id):
                errors.append(f"{where}.stimulus_id must be a stable lowercase slug when provided.")
            elif not isinstance(stimulus_i18n, dict):
                errors.append(f"{where}.stimulus_id requires stimulus_i18n source text.")
            else:
                stimulus_signature = json.dumps(stimulus_i18n, ensure_ascii=False, sort_keys=True)
                previous_signature = stimulus_groups.get(stimulus_id)
                if previous_signature is not None and previous_signature != stimulus_signature:
                    errors.append(f"{where}.stimulus_i18n conflicts with other questions using shared stimulus ID {stimulus_id!r}.")
                else:
                    stimulus_groups[stimulus_id] = stimulus_signature
        question_set_id = q.get("question_set_id")
        question_set_type = q.get("question_set_type")
        if question_set_id is not None and (
            not isinstance(question_set_id, str)
            or not re.fullmatch(r"[a-z0-9][a-z0-9._-]{1,119}", question_set_id)
        ):
            errors.append(f"{where}.question_set_id must be a stable lowercase slug when provided.")
        if question_set_type is not None and question_set_type != "PASSAGE":
            errors.append(f"{where}.question_set_type must be PASSAGE when provided.")
        if question_set_type == "PASSAGE":
            if not question_set_id or question_set_id != stimulus_id:
                errors.append(f"{where}.question_set_id must match stimulus_id for a PASSAGE question set.")
            if not isinstance(stimulus_i18n, dict) or not stimulus_i18n:
                errors.append(f"{where}.question_set_type PASSAGE requires shared stimulus_i18n text.")
            if question_set_id:
                question_set_sizes[question_set_id] = question_set_sizes.get(question_set_id, 0) + 1
        elif question_set_id is not None:
            errors.append(f"{where}.question_set_id requires question_set_type PASSAGE.")
        incorrect_marks = q.get("incorrect_marks")
        if incorrect_marks is not None and (
            isinstance(incorrect_marks, bool)
            or not isinstance(incorrect_marks, (int, float))
            or not -1000 <= incorrect_marks <= 0
        ):
            errors.append(f"{where}.incorrect_marks must be a number from -1000 to 0, or null.")
        native_only = q.get("native_language_only", False)
        native_language = q.get("native_source_language")
        if "native_language_only" in q and not isinstance(native_only, bool):
            errors.append(f"{where}.native_language_only must be boolean.")
        if native_only:
            if code != "LANGUAGE_QUALIFYING":
                errors.append(f"{where}.native_language_only is reserved for source-language qualifying questions.")
            if q.get("content_language_policy") != "NATIVE_SOURCE_ONLY":
                errors.append(f"{where}.content_language_policy must be NATIVE_SOURCE_ONLY when native_language_only is true.")
            if native_language not in {"en", "hi", "ur", "bn"}:
                errors.append(f"{where}.native_source_language must identify the original source language.")
            else:
                prompt_languages = set(q.get("prompt_i18n", {})) if isinstance(q.get("prompt_i18n"), dict) else set()
                if prompt_languages != {native_language}:
                    errors.append(f"{where}.prompt_i18n must contain only the native source language {native_language!r}; do not add a translated fallback.")
                if isinstance(stimulus_i18n, dict) and set(stimulus_i18n) != {native_language}:
                    errors.append(f"{where}.stimulus_i18n must contain only the native source language {native_language!r}.")
        if not isinstance(q.get("prompt"), str) or not q["prompt"].strip():
            errors.append(f"{where}.prompt is required as a display fallback.")
        if q.get("question_type") not in VALID_QUESTION_TYPES:
            errors.append(f"{where}.question_type must be one of {', '.join(sorted(VALID_QUESTION_TYPES))}.")
        loc = q.get("source_location")
        if not isinstance(loc, dict) or not isinstance(loc.get("pdf_page"), int) or loc.get("pdf_page", 0) < 1:
            errors.append(f"{where}.source_location.pdf_page must be a 1-based positive page number.")
        else:
            for language_field in ("source_language_page",):
                page_value = loc.get(language_field)
                if page_value is not None and (not isinstance(page_value, int) or isinstance(page_value, bool) or page_value < 1):
                    errors.append(f"{where}.source_location.{language_field} must be a positive page number or null.")
        question_number = q.get("source_question_number")
        if isinstance(question_number, (int, str)) and not isinstance(question_number, bool):
            identity = (str(code), str(question_number))
            if identity in source_numbers:
                errors.append(f"{where} duplicates source question number {question_number!r} in section {code}.")
            source_numbers.add(identity)
        tags = q.get("topic_tags")
        if not isinstance(tags, list) or not tags or any(not isinstance(tag, str) or not tag.strip() for tag in tags):
            errors.append(f"{where}.topic_tags must contain at least one non-empty tag.")

        options = q.get("options")
        option_keys: list[str] = []
        if not isinstance(options, list) or not 2 <= len(options) <= 5:
            errors.append(f"{where}.options must contain 2–5 options.")
            options = []
        for opt in options:
            if not isinstance(opt, dict) or not isinstance(opt.get("key"), str) or not opt["key"].strip() or not isinstance(opt.get("text"), str) or not opt["text"].strip():
                errors.append(f"{where}.options entries need non-empty key and text.")
                continue
            option_keys.append(opt["key"])
            is_scored_bilingual = code in {"GENERAL_STUDIES", "COMPUTER_SCIENCE"} and required_scored_languages
            _localized(opt.get("text_i18n"), f"{where}.option[{opt['key']}].text_i18n",
                       required_scored_languages if is_scored_bilingual else set(), errors)
        if native_only and isinstance(options, list) and native_language in {"en", "hi", "ur", "bn"}:
            for option in options:
                if not isinstance(option, dict):
                    continue
                option_i18n = option.get("text_i18n")
                if not isinstance(option_i18n, dict) or set(option_i18n) != {native_language}:
                    errors.append(f"{where}.option[{option.get('key', '?')}].text_i18n must contain only native source language {native_language!r}; do not translate qualifying-language options.")
        if len(set(option_keys)) != len(option_keys):
            errors.append(f"{where}.options keys must be unique.")

        proposed = q.get("proposed_answer")
        if not isinstance(proposed, dict):
            errors.append(f"{where}.proposed_answer must be an object.")
            continue
        key = proposed.get("option_key")
        if key is not None and key not in option_keys:
            errors.append(f"{where}.proposed_answer.option_key must match an option key or be null.")
        if proposed.get("review_status") != "PENDING_HUMAN_REVIEW":
            errors.append(f"{where}.proposed_answer.review_status must remain PENDING_HUMAN_REVIEW.")
        explanation=proposed.get("explanation")
        if not isinstance(explanation, str) or not explanation.strip():
            errors.append(f"{where}.proposed_answer.explanation must be non-empty text.")
        elif len(explanation)>700:
            errors.append(f"{where}.proposed_answer.explanation must stay concise (700 characters maximum).")
        expl_required = required_scored_languages if code in {"GENERAL_STUDIES", "COMPUTER_SCIENCE"} else set()
        explanation_i18n=proposed.get("explanation_i18n")
        _localized(explanation_i18n, f"{where}.proposed_answer.explanation_i18n", expl_required, errors)
        if isinstance(explanation_i18n,dict):
            for language,localized_explanation in explanation_i18n.items():
                if isinstance(localized_explanation,str) and len(localized_explanation)>700:
                    errors.append(f"{where}.proposed_answer.explanation_i18n.{language} must stay concise (700 characters maximum).")
        solution_mode=proposed.get("solution_mode")
        if solution_mode not in {"CONCISE","STEPWISE"}:
            errors.append(f"{where}.proposed_answer.solution_mode must be CONCISE or STEPWISE.")
        solution_steps=proposed.get("solution_steps_i18n")
        if solution_mode=="STEPWISE":
            _localized_steps(solution_steps,f"{where}.proposed_answer.solution_steps_i18n",expl_required,errors)
        elif solution_steps is not None and not isinstance(solution_steps,dict):
            errors.append(f"{where}.proposed_answer.solution_steps_i18n must be an object when supplied.")
        if require_complete_answers:
            if key is None:
                errors.append(f"{where} has no proposed answer.")
            if not str(proposed.get("explanation", "")).strip():
                errors.append(f"{where} has no explanation.")
            if any(not str(proposed.get("explanation_i18n", {}).get(lang, "")).strip() for lang in expl_required):
                errors.append(f"{where} lacks a required localized explanation.")
            if proposed.get("confidence") in {"LOW", "UNRESOLVED"}:
                errors.append(f"{where} answer confidence needs further resolution.")
            if proposed.get("answer_source") == "UNRESOLVED":
                errors.append(f"{where} answer source is unresolved.")
        if q.get("question_review_status") != "PENDING_HUMAN_REVIEW":
            errors.append(f"{where}.question_review_status must remain PENDING_HUMAN_REVIEW.")

    for code, section in section_by_code.items():
        expected = section.get("question_count")
        actual = actual_section_counts.get(code, 0)
        if isinstance(expected, int) and actual != expected:
            errors.append(f"Section {code} declares {expected} questions but contains {actual}.")
    for code in actual_section_counts:
        if code not in section_by_code:
            errors.append(f"Questions use undeclared section {code}.")
    for set_id, member_count in question_set_sizes.items():
        if member_count < 2:
            errors.append(f"PASSAGE question set {set_id!r} must contain at least two child questions.")

    expected_total = sum(
        section.get("question_count", 0) for section in section_by_code.values()
        if isinstance(section.get("question_count"), int)
    )
    if expected_total and expected_total != len(questions):
        errors.append(f"Section totals sum to {expected_total} questions but the package contains {len(questions)}.")

    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("package", type=Path, help="Portable assessment package JSON")
    parser.add_argument("--check-complete-answers", action="store_true",
                        help="Require an answer and explanations, with bilingual explanations for bilingual scored sections.")
    parser.add_argument("--check-public-repository", action="store_true",
                        help="Require public redistribution clearance and verify any stored PDF path/hash.")
    args = parser.parse_args()
    try:
        package = json.loads(args.package.read_text(encoding="utf-8-sig"))
    except (OSError, json.JSONDecodeError) as exc:
        print(f"ERROR: cannot read a valid UTF-8 JSON package: {exc}", file=sys.stderr)
        return 2
    errors = validate_package(package, Path.cwd().resolve(), args.check_complete_answers,
                              args.check_public_repository)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(f"OK: {package.get('package_id')} — {len(package['questions'])} draft questions; not imported or published.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
