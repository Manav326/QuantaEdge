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




def pair_complete(repo_root: Path, pair: dict[str, str]) -> bool:
    """True only if both language editions have source-bound, complete Layer 5 ledgers."""
    repo_root = Path(repo_root)
    for book_id, slug in zip(pair["book_ids_csv"].split(","), pair["source_slugs_csv"].split(","), strict=True):
        grade = pair["class_no"]
        ledger_path = repo_root / "data" / "textbook-content-pipeline" / f"class-{grade}" / "books" / slug / "coverage-ledger.v1.json"
        index_path = repo_root / "data" / "textbook-visuals" / slug / "visual-pipeline-index.v1.json"
        try:
            ledger = json.loads(ledger_path.read_text(encoding="utf-8"))
            index = json.loads(index_path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError):
            return False
        source = ledger.get("source") or {}
        summary = ledger.get("summary") or {}
        source_sha = str(source.get("source_pdf_sha256") or "").lower()
        index_sha = str(index.get("source_pdf_sha256") or "").lower()
        if not (
            ledger.get("schema_version") == "quantaedge.textbook-coverage-ledger.v1"
            and ledger.get("ledger_status") == "COMPLETE"
            and summary.get("unresolved_count") == 0
            and source.get("source_book_id") == book_id
            and bool(index_sha)
            and source_sha == index_sha
            and index.get("source_book_id") == book_id
            and index.get("layers", {}).get("5") == "COMPLETE"
            and str(index.get("coverage_ledger_path") or "") == ledger_path.relative_to(repo_root).as_posix()
        ):
            return False
    return True


def plan_matrix(
    event_name: str,
    head_message: str = "",
    class_no: str = "",
    analysis_mode: str = "",
    *,
    repo_root: Path | None = None,
    skip_completed: bool = False,
) -> list[dict[str, str]]:
    """Return exact bilingual-pair workers for resumes; preparation remains class-wide.

    Processing is intentionally dispatch-only. Push messages and schedules never select
    textbook work; ordinary application commits must not fire expensive inference.
    """
    if event_name not in {"workflow_dispatch", "workflow_call"}:
        return []

    selected = str(class_no).strip().lower()
    if selected == "all":
        grades = ("6", "7", "8")
        pair_sharded = analysis_mode == "resume_layers_4_5"
    elif selected in {"6", "7", "8"}:
        grades = (selected,)
        pair_sharded = analysis_mode == "resume_layers_4_5"
    else:
        return []

    if analysis_mode not in {"prepare_only", "analyze_all", "resume_preparation", "resume_layers_4_5"}:
        raise ValueError(f"Unsupported textbook pipeline mode: {analysis_mode!r}")

    if pair_sharded:
        planned = [dict(pair) for pair in BOOK_PAIRS if pair["class_no"] in grades]
        if skip_completed or analysis_mode == "resume_layers_4_5":
            root = Path(repo_root or Path.cwd())
            planned = [pair for pair in planned if not pair_complete(root, pair)]
        return planned

    # Preparation/catalog rebuild writes shared class indexes, so it stays one job per class.
    return [
        {
            "class_no": grade,
            "book_slug": "all-books",
            "book_pair_id": "",
            "book_label": "class-wide",
            "book_ids_csv": "",
            "source_slugs_csv": "",
        }
        for grade in grades
    ]

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--event-name", default=os.environ.get("EVENT_NAME", ""))
    parser.add_argument("--head-message", default=os.environ.get("HEAD_MESSAGE", ""))
    parser.add_argument("--class-no", default=os.environ.get("INPUT_CLASS_NO", ""))
    parser.add_argument("--analysis-mode", default=os.environ.get("INPUT_ANALYSIS_MODE", ""))
    parser.add_argument("--github-output", type=Path, default=Path(os.environ["GITHUB_OUTPUT"]) if os.environ.get("GITHUB_OUTPUT") else None)
    args = parser.parse_args()
    try:
        matrix = plan_matrix(
            args.event_name,
            args.head_message,
            args.class_no,
            args.analysis_mode,
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
