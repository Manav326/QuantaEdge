#!/usr/bin/env python3
"""Promote an AI_GENERATED_DRAFT bundle only after a named editor completes review."""
from __future__ import annotations

import argparse
import json
import sys
from datetime import date
from pathlib import Path
from typing import Any


def review_candidate(bundle: Any, reviewer: str, reviewed_at: str, confirmations: list[bool]) -> dict[str, Any]:
    if not isinstance(bundle, dict) or bundle.get("schema_version") != 1:
        raise ValueError("Expected a schema_version 1 generated bundle.")
    if bundle.get("bundle_status") != "AI_GENERATED_DRAFT" or not isinstance(bundle.get("generated_by"), dict):
        raise ValueError("Only an AI_GENERATED_DRAFT bundle can be promoted for editorial review.")
    lessons = bundle.get("lessons")
    if not isinstance(lessons, list) or not lessons:
        raise ValueError("Candidate contains no lessons.")
    question_count = sum(len(lesson.get("questions", [])) for lesson in lessons if isinstance(lesson, dict))
    if question_count < 1:
        raise ValueError("Candidate contains no questions.")
    if not reviewer.strip() or len(reviewer.strip()) > 160:
        raise ValueError("Reviewer name must be non-empty and no longer than 160 characters.")
    try:
        date.fromisoformat(reviewed_at)
    except ValueError as exc:
        raise ValueError("Review date must use YYYY-MM-DD.") from exc
    if len(confirmations) != 5 or not all(confirmations):
        raise ValueError("All five editorial confirmations are required; no bundle was promoted.")

    reviewed = dict(bundle)
    reviewed["bundle_status"] = "EDITOR_REVIEWED_DRAFT"
    reviewed["editorial_declaration"] = {
        "original_content": True,
        "rights_reviewed": True,
        "reviewer": reviewer.strip(),
        "reviewed_at": reviewed_at,
    }
    reviewed["editorial_review"] = {
        "human_review_completed": True,
        "reviewer": reviewer.strip(),
        "reviewed_at": reviewed_at,
        "checks": [
            "source edition, chapter map and cited page ranges checked against the original PDF",
            "every question solved independently; option keys and numerical calculations verified",
            "Hindi wording, age suitability, curriculum alignment, explanations and difficulty checked",
            "OCR-sensitive numerals, equations, units, science terms and duplicate coverage checked",
            "originality and applicable content-reuse rights reviewed by an authorized reviewer",
        ],
        "question_count": question_count,
        "import_still_draft_only": True,
    }
    return reviewed


def ask_yes(prompt: str) -> bool:
    reply = input(prompt + " Type YES to confirm: ").strip()
    return reply == "YES"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", required=True, type=Path, help="AI_GENERATED_DRAFT JSON candidate")
    parser.add_argument("--output", required=True, type=Path, help="New reviewed-bundle path; existing files are never overwritten")
    parser.add_argument("--reviewer", help="Reviewer name; if omitted, it is requested interactively")
    parser.add_argument("--reviewed-at", default=date.today().isoformat(), help="Review date, YYYY-MM-DD")
    args = parser.parse_args()

    if not args.bundle.is_file():
        parser.error(f"Candidate bundle does not exist: {args.bundle}")
    if args.output.exists():
        parser.error(f"Refusing to overwrite existing file: {args.output}")
    try:
        bundle = json.loads(args.bundle.read_text(encoding="utf-8"))
        reviewer = args.reviewer or input("Responsible editor/reviewer name: ").strip()
        checks = [
            "I compared the exact PDF edition, chapter, and each cited page range.",
            "I independently solved every question and checked all answer keys and explanations.",
            "I checked Hindi wording, class level, difficulty, alignment, and duplicates.",
            "I checked OCR-sensitive content, including numerals, equations, units, and science terms.",
            "I confirmed the content is original and an authorized person has reviewed reuse rights.",
        ]
        confirmations = [ask_yes(check) for check in checks]
        reviewed = review_candidate(bundle, reviewer, args.reviewed_at, confirmations)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("x", encoding="utf-8") as handle:
            json.dump(reviewed, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"Review promotion stopped safely: {exc}", file=sys.stderr)
        return 2
    print(json.dumps({
        "mode": "EDITOR_REVIEWED_DRAFT",
        "output": str(args.output),
        "reviewer": reviewer,
        "reviewed_at": args.reviewed_at,
        "questions": reviewed["editorial_review"]["question_count"],
        "import_status": "DRAFT_ONLY",
        "question_approval": "STILL_REQUIRED_IN_ADMIN_CONTENT_STUDIO",
        "auto_publish": False,
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
