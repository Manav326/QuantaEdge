#!/usr/bin/env python3
"""Plan checkpoint-safe bilingual book-pair jobs for QuantaEdge Classes 6–8."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

# Keep language editions as separate evidence records, but schedule them as one
# logical unit so both editions progress in the same runner round.
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

BOOK_PAIRS: tuple[dict[str, str], ...] = (
    {"class_no": "6", "book_slug": "class6-science-bilingual", "book_label": "Science", "book_pair_id": "class6-science-bilingual", "book_ids_csv": "ncert-c6-english-fecu1,ncert-c6-hindi-fhcu1", "source_slugs_csv": "class6-en-science-9c1315ba,class6-hi-science-f538ae2b"},
    {"class_no": "6", "book_slug": "class6-maths-bilingual", "book_label": "Maths", "book_pair_id": "class6-maths-bilingual", "book_ids_csv": "ncert-c6-english-fegp1,ncert-c6-hindi-fhgp1", "source_slugs_csv": "class6-en-maths-bde5606e,class6-hi-maths-0bbba43f"},
    {"class_no": "7", "book_slug": "class7-science-bilingual", "book_label": "Science", "book_pair_id": "class7-science-bilingual", "book_ids_csv": "ncert-c7-english-gecu1,ncert-c7-hindi-ghcu1", "source_slugs_csv": "class7-en-science-93a8f46e,class7-hi-science-21f4cf0a"},
    {"class_no": "7", "book_slug": "class7-maths-part1-bilingual", "book_label": "Maths Part I", "book_pair_id": "class7-maths-part1-bilingual", "book_ids_csv": "ncert-c7-english-gegp1,ncert-c7-hindi-ghgp1", "source_slugs_csv": "class7-en-maths-part1-d75e5b97,class7-hi-maths-part1-d9bd23b6"},
    {"class_no": "7", "book_slug": "class7-maths-part2-bilingual", "book_label": "Maths Part II", "book_pair_id": "class7-maths-part2-bilingual", "book_ids_csv": "ncert-c7-english-gegp2,ncert-c7-hindi-ghgp2", "source_slugs_csv": "class7-en-maths-part2-b088302e,class7-hi-maths-part2-5262dc75"},
    {"class_no": "8", "book_slug": "class8-science-bilingual", "book_label": "Science", "book_pair_id": "class8-science-bilingual", "book_ids_csv": "ncert-c8-english-hecu1,ncert-c8-hindi-hhcu1", "source_slugs_csv": "class8-en-science-9580b02d,class8-hi-science-0be682f4"},
    {"class_no": "8", "book_slug": "class8-maths-part1-bilingual", "book_label": "Maths Part I", "book_pair_id": "class8-maths-part1-bilingual", "book_ids_csv": "ncert-c8-english-hegp1,ncert-c8-hindi-hhgp1", "source_slugs_csv": "class8-en-maths-part1-dbc32d16,class8-hi-maths-part1-94eac999"},
    {"class_no": "8", "book_slug": "class8-maths-part2-bilingual", "book_label": "Maths Part II", "book_pair_id": "class8-maths-part2-bilingual", "book_ids_csv": "ncert-c8-english-hegp2,ncert-c8-hindi-hhgp2", "source_slugs_csv": "class8-en-maths-part2-24c8080e,class8-hi-maths-part2-aa91fabe"},
)

RESUME_SCOPES = {
    "[resume-visual-layers-4-5-class-6]": ("6",),
    "[resume-visual-layers-4-5-class-7]": ("7",),
    "[resume-visual-layers-4-5-class-8]": ("8",),
    "[resume-visual-layers-4-5-classes-7-8]": ("7", "8"),
    "[resume-visual-layers-4-5-classes-6-8]": ("6", "7", "8"),
}
REBUILD_SCOPES = {
    "[analyze-all-visual-layers-4-5-class-7]": ("7",),
    "[analyze-all-visual-layers-4-5-class-8]": ("8",),
}


def pair_complete(repo_root: Path, pair: dict[str, str]) -> bool:
    """Use the same strict contract validator as the report and handoff writer."""
    try:
        from scripts.textbook_pipeline_handoff import validate_pair_completion
    except ModuleNotFoundError:
        from textbook_pipeline_handoff import validate_pair_completion
    complete, _ = validate_pair_completion(Path(repo_root), pair)
    return complete


def plan_matrix(
    event_name: str,
    head_message: str = "",
    class_no: str = "",
    analysis_mode: str = "",
    *,
    book_pair_id: str = "",
    repo_root: Path | None = None,
    skip_completed: bool = False,
) -> list[dict[str, str]]:
    """Plan exact bilingual pairs only. Missing/invalid scope never falls back to a whole class."""
    if event_name not in {"workflow_dispatch", "workflow_call"}:
        return []
    selected_class = str(class_no).strip().lower()
    if selected_class not in {"6", "7", "8", "all"}:
        raise ValueError("class_no must be exactly 6, 7, 8, or all.")
    if analysis_mode not in {"prepare_only", "analyze_all", "resume_preparation", "resume_layers_4_5"}:
        raise ValueError(f"Unsupported analysis_mode {analysis_mode!r}.")
    requested_pair = str(book_pair_id).strip()
    if not requested_pair:
        raise ValueError("book_pair_id is required; pass 'all' or one exact bilingual pair ID.")
    pairs_by_id = {pair["book_pair_id"]: pair for pair in BOOK_PAIRS}
    if requested_pair != "all" and requested_pair not in pairs_by_id:
        raise ValueError(f"Unknown book_pair_id {requested_pair!r}; no workers were scheduled.")
    grades = {"6", "7", "8"} if selected_class == "all" else {selected_class}
    planned = [dict(pair) for pair in BOOK_PAIRS if pair["class_no"] in grades]
    if requested_pair != "all":
        exact = pairs_by_id[requested_pair]
        if exact["class_no"] not in grades:
            raise ValueError(f"book_pair_id {requested_pair!r} belongs to Class {exact['class_no']}, not class {selected_class!r}.")
        planned = [dict(exact)]
    if analysis_mode == "resume_layers_4_5" or skip_completed:
        root = Path(repo_root or Path.cwd())
        planned = [pair for pair in planned if not pair_complete(root, pair)]
    return planned

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--event-name", default=os.environ.get("EVENT_NAME", ""))
    parser.add_argument("--head-message", default=os.environ.get("HEAD_MESSAGE", ""))
    parser.add_argument("--class-no", default=os.environ.get("INPUT_CLASS_NO", ""))
    parser.add_argument("--analysis-mode", default=os.environ.get("INPUT_ANALYSIS_MODE", ""))
    parser.add_argument("--book-pair-id", default=os.environ.get("INPUT_BOOK_PAIR_ID", ""))
    parser.add_argument("--github-output", type=Path, default=Path(os.environ["GITHUB_OUTPUT"]) if os.environ.get("GITHUB_OUTPUT") else None)
    args = parser.parse_args()
    try:
        matrix = plan_matrix(
            args.event_name,
            args.head_message,
            args.class_no,
            args.analysis_mode,
            book_pair_id=args.book_pair_id,
            skip_completed=args.analysis_mode == "resume_layers_4_5",
        )
    except ValueError as exc:
        parser.error(str(exc))
    serialized = json.dumps({"include": matrix}, separators=(",", ":"))
    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as handle:
            handle.write(f"matrix={serialized}\n")
    print(serialized)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
