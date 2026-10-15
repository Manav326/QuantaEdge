#!/usr/bin/env python3
"""Safely preflight and import a Navodaya structured-content batch as CMS drafts.

The manifest references existing quantaedge.site-content-authoring-draft.v1 JSON
bundles. Existing importer/API contracts remain unchanged. This orchestrator
checks the entire batch first, verifies visual provenance and course scope, then
reuses the existing authenticated draft importer in sequence. It never publishes.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any
from urllib.parse import urlencode, urlparse

from import_site_content_draft import AdminApi, run_import, validate_site_bundle

MANIFEST_SCHEMA = "quantaedge.structured-learning-batch-import.v1"
DRAFT_SCHEMA = "quantaedge.site-content-authoring-draft.v1"
NAVODAYA_CODE = "NAVODAYA_BATCH"
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
MEDIA_TYPES = {"IMAGE", "DIAGRAM", "ANIMATION", "VIDEO", "AUDIO"}
VISUAL_TYPES = {"IMAGE", "DIAGRAM", "ANIMATION"}
VISUAL_KINDS = {"PHOTO", "ILLUSTRATION", "DIAGRAM", "CHART", "TABLE", "INFOGRAPHIC", "SEQUENCE"}


def _digest(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(chunk)
    return hasher.hexdigest()


def _required_text(value: Any, name: str, limit: int = 2000) -> str:
    if not isinstance(value, str) or not value.strip() or len(value.strip()) > limit:
        raise ValueError(f"{name} must be non-empty text of at most {limit} characters.")
    return value.strip()


def _sha(value: Any, name: str) -> str:
    normalised = str(value or "").strip().lower()
    if not SHA256_RE.fullmatch(normalised):
        raise ValueError(f"{name} must be a 64-character SHA-256 hex digest.")
    return normalised


def _https(value: Any, name: str) -> str:
    url = _required_text(value, name, 2048)
    parsed = urlparse(url)
    if parsed.scheme.lower() != "https" or not parsed.netloc or parsed.username or parsed.password:
        raise ValueError(f"{name} must be a valid HTTPS URL without embedded credentials.")
    return url


def _validate_visual_blocks(bundle: dict[str, Any], bundle_label: str) -> list[str]:
    """Validate visual metadata while keeping source visuals in the review-only state."""
    warnings: list[str] = []
    for lesson in bundle["lessons"]:
        for block in lesson["blocks"]:
            kind = str(block["block_type"]).strip().upper()
            if kind not in MEDIA_TYPES:
                continue
            content = block["content"]
            url = _https(content.get("url"), f"{bundle_label}/{lesson['code']} block {block['sequence_no']} url")
            candidate_id = str(content.get("source_candidate_id", "")).strip()
            is_catalog_asset = (
                "raw.githubusercontent.com" in urlparse(url).netloc.lower()
                and ("/content/visual-assets/" in urlparse(url).path.lower()
                     or "/data/textbook-visuals/" in urlparse(url).path.lower())
            )
            if is_catalog_asset and not candidate_id:
                raise ValueError(
                    f"{bundle_label}/{lesson['code']} references a public source-visual asset "
                    "without source_candidate_id provenance."
                )

            if kind in VISUAL_TYPES:
                for field in ("title", "title_hi", "alt", "alt_hi"):
                    _required_text(content.get(field), f"{bundle_label}/{lesson['code']} visual {field}", 500)
                description = str(content.get("description", "")).strip()
                description_hi = str(content.get("description_hi", "")).strip()
                caption = str(content.get("caption", "")).strip()
                caption_hi = str(content.get("caption_hi", "")).strip()
                if bool(description) != bool(description_hi):
                    raise ValueError(f"{bundle_label}/{lesson['code']} visual descriptions must be supplied in both English and Hindi.")
                if bool(caption) != bool(caption_hi):
                    raise ValueError(f"{bundle_label}/{lesson['code']} visual captions must be supplied in both English and Hindi.")

            if candidate_id:
                if kind not in VISUAL_TYPES:
                    raise ValueError(f"{bundle_label}/{lesson['code']} source figures must use IMAGE, DIAGRAM or ANIMATION blocks.")
                if content.get("publication_allowed") is not False:
                    raise ValueError(f"{bundle_label}/{lesson['code']} source visual must explicitly set publication_allowed=false.")
                if str(content.get("visual_review_status", "")).upper() != "NEEDS_REVIEW":
                    raise ValueError(f"{bundle_label}/{lesson['code']} imported source visuals must remain NEEDS_REVIEW.")
                if str(content.get("rights_status", "")).upper() != "SOURCE_REFERENCE_ONLY":
                    raise ValueError(f"{bundle_label}/{lesson['code']} imported source visuals must remain SOURCE_REFERENCE_ONLY.")
                visual_kind = str(content.get("visual_kind", "")).upper()
                if visual_kind not in VISUAL_KINDS:
                    raise ValueError(f"{bundle_label}/{lesson['code']} source visual needs a supported visual_kind.")
                _required_text(content.get("source_book_id"), "source_book_id", 200)
                _sha(content.get("source_pdf_sha256"), "source_pdf_sha256")
                _sha(content.get("source_asset_sha256"), "source_asset_sha256")
                page = content.get("source_pdf_page_number")
                if not isinstance(page, int) or page < 1:
                    raise ValueError(f"{bundle_label}/{lesson['code']} source visual requires a positive PDF page number.")
                if is_catalog_asset:
                    raise ValueError(
                        f"{bundle_label}/{lesson['code']} points at an unreviewed source visual on public raw GitHub. "
                        "Use approved private/rights-cleared HTTPS media storage instead."
                    )
                warnings.append(
                    f"{lesson['code']}: source visual {candidate_id} will remain review-only; "
                    "Content Studio must confirm relevance, rights, accessibility and display."
                )
    return warnings


def load_batch(manifest_path: Path, expected_program_code: str = NAVODAYA_CODE) -> dict[str, Any]:
    """Validate all input files before any CMS/API mutation."""
    manifest_path = manifest_path.resolve()
    if not manifest_path.is_file():
        raise ValueError(f"Manifest does not exist: {manifest_path}")
    root = manifest_path.parent
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if not isinstance(manifest, dict) or manifest.get("schema") != MANIFEST_SCHEMA:
        raise ValueError(f"Manifest schema must be {MANIFEST_SCHEMA}.")
    batch = manifest.get("batch")
    if not isinstance(batch, dict):
        raise ValueError("Manifest batch metadata is required.")
    program_code = str(batch.get("program_code", "")).strip().upper()
    if program_code != expected_program_code:
        raise ValueError(f"This importer is scoped to {expected_program_code}; got {program_code or '(missing)'}.")
    if batch.get("publish_status", "DRAFT") != "DRAFT":
        raise ValueError("The batch publish_status must remain DRAFT.")
    if batch.get("auto_publish", False) is not False:
        raise ValueError("The batch must explicitly keep auto_publish=false.")
    source_class = str(batch.get("source_class_code", "6")).strip()
    if source_class not in {"6", "7", "8"}:
        raise ValueError("batch.source_class_code must be 6, 7 or 8.")

    entries = manifest.get("bundles")
    if not isinstance(entries, list) or not entries:
        raise ValueError("Manifest bundles must be a non-empty array.")

    resolved: list[dict[str, Any]] = []
    seen_paths: set[Path] = set()
    planned_lessons: dict[tuple[str, str, str], set[str]] = {}
    chapter_scopes: dict[str, tuple[str, str, str]] = {}
    summaries: list[dict[str, Any]] = []
    warnings: list[str] = []
    allow_noncanonical = manifest.get("allow_noncanonical_chapter", False)
    if not isinstance(allow_noncanonical, bool):
        raise ValueError("allow_noncanonical_chapter must be true or false.")

    for index, entry in enumerate(entries, 1):
        if not isinstance(entry, dict):
            raise ValueError(f"bundles[{index - 1}] must be an object.")
        relative = _required_text(entry.get("path"), f"bundles[{index - 1}].path", 500)
        path = (root / relative).resolve()
        if root not in path.parents or not path.is_file():
            raise ValueError(f"Bundle path is missing or escapes the manifest folder: {relative!r}")
        if path in seen_paths:
            raise ValueError(f"Bundle appears more than once in the manifest: {relative}")
        seen_paths.add(path)
        actual_digest = _digest(path)
        expected_digest = str(entry.get("sha256", "")).strip()
        if expected_digest and _sha(expected_digest, f"bundles[{index - 1}].sha256") != actual_digest:
            raise ValueError(f"Bundle SHA-256 changed since manifest creation: {relative}")
        bundle = json.loads(path.read_text(encoding="utf-8"))
        entry_allow_noncanonical = entry.get("allow_noncanonical_chapter", allow_noncanonical)
        if not isinstance(entry_allow_noncanonical, bool):
            raise ValueError(f"{relative}: allow_noncanonical_chapter must be true or false.")
        plan = validate_site_bundle(bundle, allow_noncanonical_chapter=entry_allow_noncanonical)

        curriculum = bundle["curriculum"]
        if str(curriculum.get("program_code", "")).strip().upper() != program_code:
            raise ValueError(f"{relative}: curriculum.program_code must be {program_code}.")
        if curriculum.get("course_id") not in (None, "", 0, "0"):
            raise ValueError(f"{relative}: use program_code, not course_id, for this batch.")
        if str(curriculum.get("class_code", "")).strip() != source_class:
            raise ValueError(f"{relative}: class_code must match batch.source_class_code={source_class}.")
        chapter = bundle["chapter"]
        entry_chapter = str(entry.get("chapter_code", chapter["code"])).strip()
        if entry_chapter != chapter["code"]:
            raise ValueError(f"{relative}: manifest chapter_code does not match bundle chapter.code.")
        language = str(curriculum.get("language", "")).strip().lower()
        if entry.get("language", language) != language:
            raise ValueError(f"{relative}: manifest language does not match curriculum.language.")
        if entry.get("schema", DRAFT_SCHEMA) != DRAFT_SCHEMA:
            raise ValueError(f"{relative}: only {DRAFT_SCHEMA} bundles are supported.")

        scope = (source_class, str(curriculum["subject_code"]).strip().lower(), program_code)
        existing_scope = chapter_scopes.setdefault(chapter["code"], scope)
        if existing_scope != scope:
            raise ValueError(
                f"Chapter code {chapter['code']} is used across different batch subject scopes. "
                "Use a distinct chapter code for a different subject."
            )
        key = (chapter["code"], scope[1], language)
        codes = planned_lessons.setdefault(key, set())
        lesson_codes = {str(lesson["code"]).strip() for lesson in bundle["lessons"]}
        overlap = sorted(codes & lesson_codes)
        if overlap:
            raise ValueError(f"{relative}: duplicate lesson codes across bundles: {', '.join(overlap)}.")
        codes.update(lesson_codes)

        visual_warnings = _validate_visual_blocks(bundle, relative)
        warnings.extend(visual_warnings)
        resolved.append({
            "path": path,
            "relative_path": relative,
            "sha256": actual_digest,
            "bundle": bundle,
            "language": language,
            "chapter_code": chapter["code"],
            "scope": scope,
            "allow_noncanonical_chapter": entry_allow_noncanonical,
        })
        summaries.append({
            "path": relative,
            "sha256": actual_digest,
            "language": language,
            "subject_code": scope[1],
            "chapter_code": chapter["code"],
            "chapter_title": chapter["display_name"],
            "lessons": plan["lessons"],
            "questions": plan["questions"],
            "visual_blocks": sum(
                1 for lesson in bundle["lessons"] for block in lesson["blocks"]
                if str(block["block_type"]).upper() in VISUAL_TYPES
            ),
            "status": "DRAFT",
            "student_delivery_allowed": False,
        })

    # A newly created chapter must be the first bundle for that chapter, so all
    # subsequent entries can safely attach to it in the same sequential run.
    first_by_chapter: dict[str, int] = {}
    for index, row in enumerate(resolved):
        first_by_chapter.setdefault(row["chapter_code"], index)
    return {
        "manifest_path": str(manifest_path),
        "program_code": program_code,
        "source_class_code": source_class,
        "bundle_count": len(resolved),
        "lesson_count": sum(item["lessons"] for item in summaries),
        "question_count": sum(item["questions"] for item in summaries),
        "visual_block_count": sum(item["visual_blocks"] for item in summaries),
        "bundles": resolved,
        "summary": summaries,
        "warnings": warnings,
        "first_bundle_by_chapter": first_by_chapter,
    }


def preflight_api(batch: dict[str, Any], base_url: str, session_cookie: str) -> dict[str, Any]:
    """Check the course, subject tracks, chapter mappings and collisions read-only."""
    api = AdminApi(base_url, session_cookie)
    programs = api.request("GET", "/api/v1/admin/content/programs")
    if not isinstance(programs, list):
        raise RuntimeError("Admin course catalogue did not return a list.")
    matching_programs = [
        row for row in programs
        if str(row.get("code", "")).strip().upper() == batch["program_code"]
    ]
    if len(matching_programs) != 1:
        raise RuntimeError(
            f"Expected exactly one active course {batch['program_code']}; found {len(matching_programs)}. "
            "No batch content has been written."
        )

    by_scope: dict[tuple[str, str, str], list[dict[str, Any]]] = {}
    for entry in batch["bundles"]:
        scope = entry["scope"]
        if scope in by_scope:
            continue
        class_code, subject_code, program_code = scope
        query = urlencode({
            "classCode": class_code,
            "subjectCode": subject_code,
            "programCode": program_code,
            "status": "ALL",
        })
        rows = api.request("GET", "/api/v1/admin/content?" + query)
        if not isinstance(rows, list) or not rows:
            raise RuntimeError(
                f"Course {program_code} has no active subject track for {subject_code} / source class {class_code}. "
                "Create/activate that track separately before importing content. No batch content has been written."
            )
        by_scope[scope] = rows

    chapters_existing: dict[tuple[str, str, str], dict[str, list[dict[str, Any]]]] = {}
    for scope, rows in by_scope.items():
        chapter_rows: dict[str, list[dict[str, Any]]] = {}
        for row in rows:
            code = str(row.get("chapter_code") or "").strip()
            if code:
                chapter_rows.setdefault(code, []).append(row)
        chapters_existing[scope] = chapter_rows

    # Plan every collision before the first write. Future bundle entries in the
    # same new chapter attach after the first creates it.
    lesson_codes_by_chapter: dict[tuple[tuple[str, str, str], str], set[str]] = {}
    existing_chapter_keys: set[tuple[tuple[str, str, str], str]] = set()
    for scope, chapter_rows in chapters_existing.items():
        for code, rows in chapter_rows.items():
            existing_chapter_keys.add((scope, code))
            lesson_codes_by_chapter[(scope, code)] = {
                str(row.get("lesson_code")).strip() for row in rows
                if row.get("lesson_code") not in (None, "")
            }

    seen_planned: dict[tuple[tuple[str, str, str], str], set[str]] = {}
    chapter_decisions: dict[tuple[tuple[str, str, str], str], str] = {}
    for entry in batch["bundles"]:
        scope = entry["scope"]
        code = entry["chapter_code"]
        key = (scope, code)
        rows = chapters_existing.get(scope, {}).get(code, [])
        unique_chapter_ids = {
            int(row["chapter_id"]) for row in rows if row.get("chapter_id") not in (None, "")
        }
        if len(unique_chapter_ids) > 1:
            raise RuntimeError(f"Chapter code {code} is ambiguous in the live CMS; no content was written.")
        if key not in chapter_decisions:
            chapter_decisions[key] = "ATTACH_TO_EXISTING" if unique_chapter_ids else "CREATE_DRAFT_CHAPTER"
        elif chapter_decisions[key] == "CREATE_DRAFT_CHAPTER":
            chapter_decisions[key] = "CREATE_THEN_ATTACH"

        planned = seen_planned.setdefault(key, set())
        codes = {str(lesson["code"]).strip() for lesson in entry["bundle"]["lessons"]}
        overlap = sorted(planned & codes)
        if overlap:
            raise RuntimeError(f"Duplicate lesson codes for chapter {code}: {', '.join(overlap)}. No content was written.")
        planned.update(codes)
        current = lesson_codes_by_chapter.get(key, set())
        collision = sorted(codes & current)
        if collision:
            raise RuntimeError(
                f"Lesson code collision(s) already exist in {batch['program_code']}/{code}: "
                f"{', '.join(collision)}. No batch content has been written."
            )
        lesson_codes_by_chapter.setdefault(key, set()).update(codes)

    return {
        "preflight": "PASS_READ_ONLY",
        "program_code": batch["program_code"],
        "matched_program_name": matching_programs[0].get("display_name"),
        "subject_tracks": sorted({scope[1] for scope in by_scope}),
        "chapter_decisions": [
            {"chapter_code": code, "mode": mode}
            for (_, code), mode in sorted(chapter_decisions.items(), key=lambda item: (item[0][1], item[0][0][1]))
        ],
        "writes_performed": 0,
    }


def apply_batch(batch: dict[str, Any], base_url: str, session_cookie: str,
                preflight: dict[str, Any]) -> dict[str, Any]:
    """Import all bundles in order as drafts only; stop immediately on a partial failure."""
    modes = {(row["chapter_code"], row.get("subject_code")): row["mode"]
             for row in preflight.get("chapter_decisions", [])}
    imported: list[dict[str, Any]] = []
    for index, entry in enumerate(batch["bundles"]):
        prior_for_chapter = any(
            done["chapter_code"] == entry["chapter_code"] and done.get("subject_code") == entry["scope"][1]
            for done in imported
        )
        decision = next(
            (row for row in preflight["chapter_decisions"]
             if row["chapter_code"] == entry["chapter_code"]),
            None,
        )
        attach = prior_for_chapter or bool(decision and decision["mode"] == "ATTACH_TO_EXISTING")
        try:
            result = run_import(
                entry["bundle"],
                base_url,
                session_cookie,
                attach_to_existing_chapter=attach,
                allow_noncanonical_chapter=entry["allow_noncanonical_chapter"],
            )
        except Exception as exc:
            raise RuntimeError(
                f"Batch stopped at bundle {entry['relative_path']} after {len(imported)} bundle(s) were imported. "
                "Any completed items remain DRAFT and unpublished. Inspect the listed chapter/lesson IDs before retrying. "
                f"Cause: {exc}"
            ) from exc
        imported.append({
            "path": entry["relative_path"],
            "chapter_code": entry["chapter_code"],
            "subject_code": entry["scope"][1],
            "chapter_id": result.get("chapter_id"),
            "lesson_ids": result.get("lesson_ids", []),
            "lesson_count": result.get("lesson_count", 0),
            "question_count": result.get("question_count", 0),
            "status": "DRAFT",
            "auto_publish": False,
            "student_delivery_allowed": False,
        })
    return {
        "mode": "APPLIED_DRAFT_ONLY",
        "program_code": batch["program_code"],
        "imported_bundle_count": len(imported),
        "imported_lesson_count": sum(row["lesson_count"] for row in imported),
        "imported_question_count": sum(row["question_count"] for row in imported),
        "results": imported,
        "auto_publish": False,
        "student_delivery_allowed": False,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path, help="Batch manifest JSON; bundle paths are relative to it.")
    parser.add_argument("--api-url", default="http://localhost:8080", help="Use HTTPS remotely; HTTP is permitted only on localhost.")
    parser.add_argument("--apply", action="store_true", help="Preflight the live CMS, then import every bundle as DRAFT.")
    parser.add_argument("--program-code", default=NAVODAYA_CODE, help="Safety scope; defaults to NAVODAYA_BATCH.")
    args = parser.parse_args(argv)
    try:
        batch = load_batch(args.manifest, expected_program_code=args.program_code)
        output: dict[str, Any] = {
            "mode": "DRY_RUN" if not args.apply else "PREFLIGHTING",
            "program_code": batch["program_code"],
            "bundle_count": batch["bundle_count"],
            "lesson_count": batch["lesson_count"],
            "question_count": batch["question_count"],
            "visual_block_count": batch["visual_block_count"],
            "bundles": batch["summary"],
            "warnings": batch["warnings"],
            "auto_publish": False,
            "student_delivery_allowed": False,
        }
        if args.apply:
            if not args.api_url.lower().startswith(("http://localhost", "http://127.0.0.1", "https://")):
                raise ValueError("Use HTTPS for remote API URLs; plain HTTP is allowed only for localhost.")
            import os
            session = os.environ.get("QUANTAEDGE_ADMIN_SESSION", "").strip()
            if not session:
                raise ValueError("Set QUANTAEDGE_ADMIN_SESSION from an authenticated staff session; never pass it on the command line.")
            preflight = preflight_api(batch, args.api_url, session)
            output["preflight"] = preflight
            output["result"] = apply_batch(batch, args.api_url, session, preflight)
        print(json.dumps(output, ensure_ascii=False, indent=2))
    except (OSError, json.JSONDecodeError, ValueError, RuntimeError) as exc:
        print(f"Navodaya batch import stopped safely: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
