#!/usr/bin/env python3
"""Stage only explicitly approved, published structured lessons into the API JAR.

Canonical authoring drafts remain under data/structured-lessons. They are not
runtime content and must not be bundled just because they exist in the repo.
The approval predicates mirror StructuredLessonArtifactService.isPublicationApproved.
"""
from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path
from typing import Any

APPROVED = "APPROVED"
REQUIRED_APPROVAL_FIELDS = (
    "content_review_status",
    "source_completeness_status",
    "bilingual_parity_status",
)
APPROVED_VISUAL_STATES = {"APPROVED", "APPROVED_FOR_STUDENT_RENDERING"}


def is_publishable(artifact: Any) -> bool:
    if not isinstance(artifact, dict):
        return False
    workflow = artifact.get("workflow")
    if not isinstance(workflow, dict):
        return False
    if str(workflow.get("status", "")).upper() != "PUBLISHED":
        return False
    if workflow.get("publication_allowed") is not True:
        return False
    if any(str(workflow.get(key, "")).upper() != APPROVED for key in REQUIRED_APPROVAL_FIELDS):
        return False
    scientific_status = workflow.get("scientific_review_status", workflow.get("science_review_status", ""))
    if str(scientific_status).upper() != APPROVED:
        return False
    return str(workflow.get("visual_review_status", "")).upper() in APPROVED_VISUAL_STATES


def stage_published_lessons(source_dir: Path, destination_dir: Path) -> dict[str, int]:
    source = source_dir.resolve()
    if not source.is_dir():
        raise FileNotFoundError(f"Structured lesson source directory does not exist: {source}")

    lesson_paths = sorted(source.glob("class-*/*/chapter-*/topic-*.json"))
    # Replace the target rather than overlaying it, to avoid stale/draft resources
    # surviving a later build when an artifact is removed or loses approval.
    if destination_dir.exists():
        if destination_dir.is_dir():
            shutil.rmtree(destination_dir)
        else:
            destination_dir.unlink()
    destination_dir.mkdir(parents=True, exist_ok=True)

    copied = 0
    excluded = 0
    for lesson_path in lesson_paths:
        try:
            artifact = json.loads(lesson_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise ValueError(f"Cannot parse canonical lesson artifact {lesson_path}: {error}") from error
        if not isinstance(artifact, dict):
            raise ValueError(f"Canonical lesson artifact must be a JSON object: {lesson_path}")
        if not is_publishable(artifact):
            excluded += 1
            continue

        relative = lesson_path.relative_to(source)
        target = destination_dir / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(lesson_path, target)
        copied += 1

    return {
        "source_lesson_count": len(lesson_paths),
        "packaged_published_count": copied,
        "excluded_not_publishable_count": excluded,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path, help="Canonical source folder, e.g. data/structured-lessons")
    parser.add_argument("--destination", required=True, type=Path, help="JAR resource folder, e.g. services/api/src/main/resources/structured-lessons")
    args = parser.parse_args(argv)
    try:
        result = stage_published_lessons(args.source, args.destination)
    except (OSError, ValueError) as error:
        print(f"Structured lesson packaging stopped safely: {error}", file=sys.stderr)
        return 2
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
