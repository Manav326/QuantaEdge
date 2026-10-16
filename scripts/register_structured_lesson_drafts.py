#!/usr/bin/env python3
"""Register validated Layers 6–8 candidates in the canonical QuantaEdge content store.

Candidate JSON may contain additional metadata; registration preserves it. This command
changes only canonical routing metadata and review status, never publishes content.
Run once without --apply to inspect the plan.
"""
from __future__ import annotations
import argparse
import json
import re
import tempfile
from collections import Counter
from datetime import date
from pathlib import Path
from typing import Any

from scripts.validate_structured_lesson_artifacts import (
    QUESTION_CONTRACTS, read_json, validate_artifact, validate_index
)

def atomic_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    encoded = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, delete=False) as handle:
        tmp = Path(handle.name)
        handle.write(encoded)
        handle.flush()
    tmp.replace(path)

def slug(value: Any, fallback: str) -> str:
    text = re.sub(r"[^a-z0-9]+", "-", str(value or "").lower()).strip("-")[:64].strip("-")
    return text or fallback

def register(candidate_dir: Path, root: Path, index_path: Path, index: dict[str, Any],
             chapter_number: int, *, apply: bool, confirm_mapping: bool) -> dict[str, Any]:
    if not confirm_mapping:
        raise ValueError("Confirm the exact canonical chapter mapping with --confirm-chapter-mapping.")
    if not candidate_dir.is_dir():
        raise ValueError(f"Candidate directory does not exist: {candidate_dir}")
    target = index.get("target") if isinstance(index.get("target"), dict) else {}
    class_number = target.get("class")
    if not isinstance(class_number, int) or isinstance(class_number, bool):
        raise ValueError("The production index target.class must be an integer.")
    folder_subject = index_path.parent.name.lower()
    if not re.fullmatch(r"[a-z][a-z0-9_-]{0,39}", folder_subject):
        raise ValueError("The production index must live in the canonical subject-code folder.")
    chapter = next((row for row in index.get("chapters", [])
                    if isinstance(row, dict) and row.get("number") == chapter_number), None)
    if chapter is None:
        raise ValueError(f"Chapter {chapter_number} is absent from this production index.")
    title_en, title_hi = chapter.get("title_en"), chapter.get("title_hi")
    if not isinstance(title_en, str) or not title_en.strip() or not isinstance(title_hi, str) or not title_hi.strip():
        raise ValueError("Canonical chapter titles in English and Hindi are required.")

    books = target.get("books") if isinstance(target.get("books"), dict) else {}
    allowed_books = {row.get("book_id"): row for locale, row in books.items()
                     if locale in {"en", "hi"} and isinstance(row, dict) and isinstance(row.get("book_id"), str)}
    candidates = sorted(candidate_dir.glob("qe-lesson-*.json"))
    if not candidates:
        raise ValueError("No qe-lesson-*.json candidate artifacts were found.")
    current_rows = chapter.get("topics") if isinstance(chapter.get("topics"), list) else []
    existing_topic_ids = {r.get("topic_id") for c in index.get("chapters", []) if isinstance(c, dict)
                          for r in (c.get("topics") if isinstance(c.get("topics"), list) else []) if isinstance(r, dict)}
    existing_artifact_ids: set[str] = set()
    existing_paths: set[str] = set()
    for c in index.get("chapters", []):
        for row in c.get("topics", []) if isinstance(c, dict) and isinstance(c.get("topics"), list) else []:
            if not isinstance(row, dict):
                continue
            if isinstance(row.get("artifact_path"), str):
                existing_paths.add(row["artifact_path"])
                path = root / row["artifact_path"]
                if path.is_file():
                    old = read_json(path)
                    if isinstance(old, dict) and isinstance(old.get("artifact_id"), str):
                        existing_artifact_ids.add(old["artifact_id"])

    next_number = max((r.get("number", 0) for r in current_rows if isinstance(r, dict)
                       and isinstance(r.get("number"), int) and not isinstance(r.get("number"), bool)), default=0) + 1
    planned = []
    seen_aids: set[str] = set()
    seen_tids: set[str] = set()
    seen_paths: set[str] = set()
    ignored = []
    for candidate_path in candidates:
        lesson = read_json(candidate_path)
        if not isinstance(lesson, dict):
            raise ValueError(f"{candidate_path}: candidate root must be a JSON object.")
        source_chapter = lesson.get("chapter", {}).get("chapter_number") if isinstance(lesson.get("chapter"), dict) else None
        if source_chapter != chapter_number:
            ignored.append({"candidate": candidate_path.name, "reason": "does not map to the human-confirmed chapter"})
            continue
        curriculum = lesson.get("curriculum") if isinstance(lesson.get("curriculum"), dict) else {}
        workflow = lesson.get("workflow") if isinstance(lesson.get("workflow"), dict) else {}
        source_book = workflow.get("source_book_id") or curriculum.get("source_book_id")
        source_hash = workflow.get("source_pdf_sha256")
        if source_book not in allowed_books:
            raise ValueError(f"{candidate_path}: source_book_id is not in this index's exact English/Hindi editions.")
        expected_hash = str(allowed_books[source_book].get("pdf_sha256") or "").lower()
        if not isinstance(source_hash, str) or source_hash.lower() != expected_hash:
            raise ValueError(f"{candidate_path}: source_pdf_sha256 does not match the selected whole-book PDF.")
        if workflow.get("publication_allowed") is not False or workflow.get("human_review_required") is not True:
            raise ValueError(f"{candidate_path}: only review-only candidates can be registered.")
        # Validate before and after canonical routing fields are set.
        before_errors = validate_artifact(lesson, candidate_path.name)
        if before_errors:
            raise ValueError("Candidate schema validation failed:\n" + "\n".join(before_errors[:30]))
        aid = lesson.get("artifact_id")
        topic = lesson.get("topic") if isinstance(lesson.get("topic"), dict) else {}
        tid = topic.get("topic_id")
        if not isinstance(aid, str) or aid in existing_artifact_ids or aid in seen_aids:
            raise ValueError(f"{candidate_path}: duplicate/missing artifact_id: {aid!r}")
        if not isinstance(tid, str) or not tid.strip() or tid in existing_topic_ids or tid in seen_tids:
            raise ValueError(f"{candidate_path}: duplicate/missing topic_id: {tid!r}")

        # Keep every generated key. These exact runtime keys are normalized explicitly.
        lesson["curriculum"]["class"] = class_number
        lesson["curriculum"]["subject"] = folder_subject
        lesson["curriculum"]["class_code"] = str(class_number)
        lesson["curriculum"]["subject_code"] = folder_subject
        lesson["curriculum"]["textbook_by_locale"] = {
            locale: {key: row.get(key, "") for key in ("title", "edition", "book_id", "pdf_sha256", "source_url")}
            for locale, row in books.items() if locale in {"en", "hi"} and isinstance(row, dict)
        }
        lesson["chapter"]["chapter_number"] = chapter_number
        lesson["chapter"]["title_by_locale"] = {"en": title_en, "hi": title_hi}
        lesson["topic"]["topic_number"] = next_number
        workflow = lesson["workflow"]
        workflow.update({
            "status": "DRAFT_GENERATED_NOT_REVIEWED",
            "content_review_status": "PENDING",
            "source_completeness_status": "PENDING_SOURCE_RECONCILIATION",
            "bilingual_parity_status": "PENDING_HUMAN_SEMANTIC_REVIEW",
            "scientific_review_status": "PENDING_HUMAN_REVIEW",
            "visual_review_status": "PENDING",
            "publication_allowed": False,
            "human_review_required": True,
        })
        qtypes = Counter(q.get("type") for q in lesson.get("practice", {}).get("questions", [])
                         if isinstance(q, dict) and q.get("type") in QUESTION_CONTRACTS)
        topic_title = lesson["topic"].get("title_by_locale", {})
        relative = (index_path.parent.relative_to(root) / f"chapter-{chapter_number:02d}" /
                    f"topic-{next_number:02d}-{slug(topic_title.get('en'), aid[-8:])}.v1.json").as_posix()
        if relative in existing_paths or relative in seen_paths or (root / relative).exists():
            raise ValueError(f"Refusing to overwrite canonical artifact path: {relative}")
        lesson["registration_metadata"] = {
            "registered_from_candidate": candidate_path.name,
            "registration_status": "CANONICAL_DRAFT_REVIEW_PENDING",
            "registered_date": date.today().isoformat(),
            "source_pdf_sha256": source_hash,
        }
        after_errors = validate_artifact(lesson, relative)
        if after_errors:
            raise ValueError("Canonical schema validation failed:\n" + "\n".join(after_errors[:30]))
        planned.append({
            "source": str(candidate_path), "destination": relative, "artifact_id": aid,
            "topic_id": tid, "topic_number": next_number, "title_en": topic_title.get("en", ""),
            "title_hi": topic_title.get("hi", ""), "question_count": len(lesson["practice"]["questions"]),
            "question_type_counts": dict(sorted(qtypes.items())), "lesson": lesson,
        })
        seen_aids.add(aid)
        seen_tids.add(tid)
        seen_paths.add(relative)
        next_number += 1

    if not planned:
        raise ValueError(f"No schema-valid candidate maps to chapter {chapter_number}.")
    report = {"status": "DRY_RUN" if not apply else "REGISTERING", "chapter_number": chapter_number,
              "source_candidates_seen": len(candidates), "ignored_other_chapters": ignored,
              "register_count": len(planned), "planned": [{k:v for k,v in row.items() if k != "lesson"} for row in planned],
              "writes_performed": False}
    if not apply:
        return report

    # Prepare updated index and validate a copy of the complete resulting store before writes.
    topic_rows = [{
        "number": row["topic_number"], "topic_id": row["topic_id"], "title_en": row["title_en"],
        "title_hi": row["title_hi"], "artifact_path": row["destination"],
        "artifact_status": "DRAFT_GENERATED_NOT_REVIEWED",
        "source_reconciliation": "PENDING_RENDERED_PDF_REVIEW",
        "bilingual_parity_review": "PENDING_HUMAN_SEMANTIC_REVIEW", "visual_review": "PENDING",
        "practice_item_count": row["question_count"],
        "practice_types": list(row["question_type_counts"].keys()),
        "question_type_counts": row["question_type_counts"],
        "rendering_contract_status": "CONTRACT_VALIDATED_DRAFT",
        "storage_status": "CANONICAL_DRAFT_REVIEW_PENDING",
        "source_book_id": row["lesson"]["workflow"].get("source_book_id"),
        "source_pdf_sha256": row["lesson"]["workflow"].get("source_pdf_sha256"),
        "human_review_required": True, "publication_allowed": False,
    } for row in planned]
    chapter["topics"].extend(topic_rows)
    chapter["state"] = "IN_PROGRESS_REVIEW_PENDING"
    chapter["topic_plan_state"] = "MODEL_CANDIDATES_NEED_SECTION_RECONCILIATION"
    chapter["topics_generated"] = len(chapter["topics"])
    chapter["question_count_generated"] = sum(int(r.get("practice_item_count", 0) or 0) for r in chapter["topics"])
    chapter["source_coverage_status"] = "PENDING_ITEM_LEVEL_COVERAGE"
    chapter.setdefault("source_coverage_path", (index_path.parent / f"chapter-{chapter_number:02d}" / "source-coverage.v1.json").as_posix())

    summary = index.setdefault("production_summary", {})
    all_rows = [r for c in index.get("chapters", []) if isinstance(c, dict)
                for r in (c.get("topics") if isinstance(c.get("topics"), list) else []) if isinstance(r, dict)]
    summary["topics_generated"] = len(all_rows)
    summary["lessons_committed"] = len(all_rows)
    summary["topics_reviewed"] = sum(1 for r in all_rows if str(r.get("artifact_status", "")).upper() in {"REVIEWED", "APPROVED", "APPROVED_FOR_PUBLISHING", "PUBLISHED"})
    summary["topics_bilingual_approved"] = sum(1 for r in all_rows if str(r.get("bilingual_parity_review", "")).upper() == "APPROVED")
    summary["topics_publishable"] = sum(1 for r in all_rows if r.get("publication_allowed") is True)
    by_chapter = []
    total_types: Counter[str] = Counter()
    for c in index.get("chapters", []):
        if not isinstance(c, dict):
            continue
        counts: Counter[str] = Counter()
        total_q = 0
        for r in c.get("topics", []) if isinstance(c.get("topics"), list) else []:
            if not isinstance(r, dict):
                continue
            qcount = r.get("practice_item_count", 0)
            if isinstance(qcount, int) and not isinstance(qcount, bool):
                total_q += qcount
            for qtype, count in (r.get("question_type_counts", {}) if isinstance(r.get("question_type_counts"), dict) else {}).items():
                if qtype in QUESTION_CONTRACTS and isinstance(count, int) and not isinstance(count, bool):
                    counts[qtype] += count
                    total_types[qtype] += count
        by_chapter.append({"chapter_number": c.get("number"), "chapter_title_en": c.get("title_en", ""),
                           "total_questions_generated": total_q, "counts_by_type": dict(sorted(counts.items())),
                           "topic_count_with_questions": sum(1 for r in c.get("topics", []) if isinstance(r, dict) and r.get("practice_item_count", 0)),
                           "topics_generated": len(c.get("topics", [])),
                           "topics_approved": sum(1 for r in c.get("topics", []) if isinstance(r, dict) and str(r.get("artifact_status", "")).upper() in {"APPROVED", "APPROVED_FOR_PUBLISHING", "PUBLISHED"})})
    summary["question_counts_by_chapter"] = by_chapter
    batch = summary.setdefault("batch_question_counts", {})
    batch["total_questions_prepared"] = sum(total_types.values())
    batch["counts_by_type"] = dict(sorted(total_types.items()))
    batch["status"] = "CANONICAL_DRAFT_REVIEW_PENDING"
    index["updated_date"] = date.today().isoformat()

    index_errors = validate_index(index, root, index_path)
    if index_errors["status"] != "PASS":
        raise ValueError("Updated production index would fail validation:\n" + "\n".join(index_errors["errors"][:30]))

    original_index = index_path.read_bytes() if index_path.is_file() else None
    written: list[Path] = []
    try:
        for row in planned:
            path = root / row["destination"]
            atomic_json(path, row["lesson"])
            written.append(path)
        atomic_json(index_path, index)
        final_result = validate_index(read_json(index_path), root, index_path)
        if final_result["status"] != "PASS":
            raise ValueError("Post-write canonical validation failed:\n" + "\n".join(final_result["errors"][:30]))
    except Exception:
        for path in written:
            try:
                path.unlink()
            except OSError:
                pass
        if original_index is not None:
            index_path.write_bytes(original_index)
        raise
    report.update({"status": "REGISTERED_DRAFTS_NOT_PUBLISHED", "writes_performed": True,
                   "destinations": [row["destination"] for row in planned],
                   "production_index": str(index_path.relative_to(root)),
                   "publication_allowed": False,
                   "post_write_validation": {"status": final_result["status"], "artifact_count": final_result["artifact_count"],
                                             "question_count": final_result["question_count"]}})
    return report

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate-dir", type=Path, required=True)
    parser.add_argument("--repo-root", type=Path, default=Path("."))
    parser.add_argument("--index", type=Path, required=True, help="Canonical class/subject production-index.v1.json")
    parser.add_argument("--chapter-number", type=int, required=True)
    parser.add_argument("--confirm-chapter-mapping", action="store_true")
    parser.add_argument("--apply", action="store_true", help="Write canonical draft files and index; default is a dry run.")
    args = parser.parse_args()
    root = args.repo_root.resolve()
    candidate_dir = args.candidate_dir if args.candidate_dir.is_absolute() else root / args.candidate_dir
    index_path = args.index if args.index.is_absolute() else root / args.index
    try:
        index = read_json(index_path)
        report = register(candidate_dir.resolve(), root, index_path.resolve(), index, args.chapter_number,
                          apply=args.apply, confirm_mapping=args.confirm_chapter_mapping)
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0
    except (OSError, ValueError, TypeError, KeyError) as exc:
        print(json.dumps({"status": "ERROR", "error": str(exc)}, ensure_ascii=False, indent=2))
        return 2

if __name__ == "__main__":
    raise SystemExit(main())
