#!/usr/bin/env python3
"""Plan safe, source-bound Layer 4–5 runner matrices for Classes 6–8.
# Resume dispatch epoch: book-sharded Class 8 lane.

Resume work is sharded by whole book so each GitHub runner owns one canonical
Layer 4 checkpoint and one coverage ledger. Non-resume modes retain their
existing class-wide behavior because candidate catalog preparation is shared.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
from typing import Any

BOOKS: tuple[dict[str, str], ...] = (
    {"class_no": "6", "book_id": "ncert-c6-english-fecu1", "book_slug": "class6-en-science-9c1315ba"},
    {"class_no": "6", "book_id": "ncert-c6-english-fegp1", "book_slug": "class6-en-maths-bde5606e"},
    {"class_no": "6", "book_id": "ncert-c6-hindi-fhcu1", "book_slug": "class6-hi-science-f538ae2b"},
    {"class_no": "6", "book_id": "ncert-c6-hindi-fhgp1", "book_slug": "class6-hi-maths-0bbba43f"},
    {"class_no": "7", "book_id": "ncert-c7-english-gecu1", "book_slug": "class7-en-science-93a8f46e"},
    {"class_no": "7", "book_id": "ncert-c7-english-gegp1", "book_slug": "class7-en-maths-part1-d75e5b97"},
    {"class_no": "7", "book_id": "ncert-c7-english-gegp2", "book_slug": "class7-en-maths-part2-b088302e"},
    {"class_no": "7", "book_id": "ncert-c7-hindi-ghcu1", "book_slug": "class7-hi-science-21f4cf0a"},
    {"class_no": "7", "book_id": "ncert-c7-hindi-ghgp1", "book_slug": "class7-hi-maths-part1-d9bd23b6"},
    {"class_no": "7", "book_id": "ncert-c7-hindi-ghgp2", "book_slug": "class7-hi-maths-part2-5262dc75"},
    {"class_no": "8", "book_id": "ncert-c8-english-hecu1", "book_slug": "class8-en-science-9580b02d"},
    {"class_no": "8", "book_id": "ncert-c8-english-hegp1", "book_slug": "class8-en-maths-part1-dbc32d16"},
    {"class_no": "8", "book_id": "ncert-c8-english-hegp2", "book_slug": "class8-en-maths-part2-24c8080e"},
    {"class_no": "8", "book_id": "ncert-c8-hindi-hhcu1", "book_slug": "class8-hi-science-0be682f4"},
    {"class_no": "8", "book_id": "ncert-c8-hindi-hhgp1", "book_slug": "class8-hi-maths-part1-94eac999"},
    {"class_no": "8", "book_id": "ncert-c8-hindi-hhgp2", "book_slug": "class8-hi-maths-part2-aa91fabe"},
)

MARKER_CLASSES = {
    "[resume-visual-layers-4-5-class-6]": ("6",),
    "[resume-visual-layers-4-5-class-7]": ("7",),
    "[resume-visual-layers-4-5-class-8]": ("8",),
    "[analyze-all-visual-layers-4-5-class-7]": ("7",),
    "[analyze-all-visual-layers-4-5-class-8]": ("8",),
    "[resume-visual-layers-4-5-classes-7-8]": ("7", "8"),
    "[resume-visual-layers-4-5-classes-6-8]": ("6", "7", "8"),
}


def plan_matrix(event_name: str, head_message: str = "", class_no: str = "", analysis_mode: str = "") -> list[dict[str, str]]:
    """Return deterministic per-book resume runners or a class-wide non-resume runner."""
    grades: tuple[str, ...] = ()
    per_book = False

    if event_name == "push":
        # Prefer the widest explicit scope; don't accidentally shrink a multi-class request.
        if "[resume-visual-layers-4-5-classes-6-8]" in head_message:
            grades, per_book = ("6", "7", "8"), True
        elif "[resume-visual-layers-4-5-classes-7-8]" in head_message:
            grades, per_book = ("7", "8"), True
        elif "[resume-visual-layers-4-5-class-6]" in head_message:
            grades, per_book = ("6",), True
        elif "[resume-visual-layers-4-5-class-7]" in head_message:
            grades, per_book = ("7",), True
        elif "[resume-visual-layers-4-5-class-8]" in head_message:
            grades, per_book = ("8",), True
        elif "[analyze-all-visual-layers-4-5-class-7]" in head_message:
            grades = ("7",)
        elif "[analyze-all-visual-layers-4-5-class-8]" in head_message:
            grades = ("8",)
        else:
            return []
    elif event_name in {"workflow_dispatch", "workflow_call"}:
        grade = str(class_no).strip()
        if grade not in {"6", "7", "8"}:
            return []
        grades = (grade,)
        per_book = analysis_mode == "resume_layers_4_5"
    else:
        return []

    if per_book:
        return [dict(book) for book in BOOKS if book["class_no"] in grades]

    # Candidate preparation and edition rebuilds share a class-wide catalog;
    # keep them as one job to avoid concurrent writers of the same catalog index.
    return [{"class_no": grade, "book_id": "", "book_slug": "all-books"} for grade in grades]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--event-name", default=os.environ.get("EVENT_NAME", ""))
    parser.add_argument("--head-message", default=os.environ.get("HEAD_MESSAGE", ""))
    parser.add_argument("--class-no", default=os.environ.get("INPUT_CLASS_NO", ""))
    parser.add_argument("--analysis-mode", default=os.environ.get("INPUT_ANALYSIS_MODE", ""))
    parser.add_argument("--github-output", type=Path, default=Path(os.environ["GITHUB_OUTPUT"]) if os.environ.get("GITHUB_OUTPUT") else None)
    args = parser.parse_args()
    matrix = plan_matrix(args.event_name, args.head_message, args.class_no, args.analysis_mode)
    serialized = json.dumps(matrix, separators=(",", ":"))
    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as handle:
            handle.write(f"matrix={serialized}\n")
    print(serialized)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
