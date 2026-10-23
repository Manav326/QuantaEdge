#!/usr/bin/env python3
"""Validate portable QuantaEdge assessment content without assuming a school class schema.

This intentionally does not import or publish assessments. Source rights and question-answer
review are separate gates from structural validation and course/institution deployment.
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
VALID_RIGHTS = {
    "UNCONFIRMED",
    "CLEARED_FOR_INTERNAL_USE",
    RIGHTS_CLEARED_FOR_PUBLIC_REPO,
    "DO_NOT_REDISTRIBUTE",
}


def _is_url(value: Any) -> bool:
    if not isinstance(value, str):
        return False
    parsed = urlparse(value)
    return parsed.scheme in {"http", "https"} and bool(parsed.netloc)


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
    if source.get("repository_distribution_rights") not in VALID_RIGHTS:
        errors.append("source.repository_distribution_rights must use a declared rights state.")
    source_path = source.get("source_file_path")
    source_hash = source.get("source_file_sha256")
    if source_path is not None and not isinstance(source_path, str):
        errors.append("source.source_file_path must be a repository-relative string or null.")
    if source_hash is not None and (not isinstance(source_hash, str) or not re.fullmatch(r"[a-fA-F0-9]{64}", source_hash)):
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
            elif source_hash:
                digest = hashlib.sha256(resolved.read_bytes()).hexdigest()
                if digest.lower() != source_hash.lower():
                    errors.append(f"SHA-256 mismatch for {source_path}.")

    config = package.get("configuration")
    if not isinstance(config, dict):
        errors.append("configuration must be an object.")
    else:
        duration = config.get("duration_minutes")
        if not isinstance(duration, int) or isinstance(duration, bool) or not 1 <= duration <= 240:
            errors.append("configuration.duration_minutes must be an integer from 1 to 240.")
        marks = config.get("marks_per_question")
        if not isinstance(marks, (int, float)) or isinstance(marks, bool) or marks <= 0:
            errors.append("configuration.marks_per_question must be positive.")
        penalty = config.get("negative_marking_per_wrong_answer")
        if penalty is not None and (not isinstance(penalty, (int, float)) or isinstance(penalty, bool) or penalty < 0):
            errors.append("negative_marking_per_wrong_answer must be a non-negative number or null.")
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

    questions = package.get("questions")
    if not isinstance(questions, list) or not 1 <= len(questions) <= 250:
        errors.append("questions must contain between 1 and 250 items.")
        questions = []
    ids: set[str] = set()
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
        if not isinstance(q.get("prompt"), str) or not q["prompt"].strip():
            errors.append(f"{where}.prompt is required.")
        if q.get("question_type") not in {"MCQ", "TRUE_FALSE"}:
            errors.append(f"{where}.question_type must be MCQ or TRUE_FALSE.")
        loc = q.get("source_location")
        if not isinstance(loc, dict) or not isinstance(loc.get("pdf_page"), int) or loc.get("pdf_page", 0) < 1:
            errors.append(f"{where}.source_location.pdf_page must be a 1-based positive page number.")
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
            else:
                option_keys.append(opt["key"])
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
        if not isinstance(proposed.get("explanation"), str):
            errors.append(f"{where}.proposed_answer.explanation must be text.")
        if require_complete_answers:
            if key is None:
                errors.append(f"{where} has no proposed answer.")
            if not str(proposed.get("explanation", "")).strip():
                errors.append(f"{where} has no explanation.")
            if proposed.get("confidence") in {"LOW", "UNRESOLVED"}:
                errors.append(f"{where} answer confidence needs further resolution.")
        if q.get("question_review_status") != "PENDING_HUMAN_REVIEW":
            errors.append(f"{where}.question_review_status must remain PENDING_HUMAN_REVIEW.")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("package", type=Path, help="Portable assessment package JSON")
    parser.add_argument("--check-complete-answers", action="store_true",
                        help="Require a proposed answer and explanation for every question.")
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
