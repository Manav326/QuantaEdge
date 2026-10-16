#!/usr/bin/env python3
"""Validate canonical QuantaEdge structured lessons, DB key compatibility and index integrity.

Additional JSON keys are allowed and preserved. Required application contract keys are
checked explicitly; question-type counts are descriptive, never a quota.
"""
from __future__ import annotations
import argparse
import json
import re
from collections import Counter
from pathlib import Path
from typing import Any

QUESTION_CONTRACTS = {
    "multiple_choice": "qtype-multiple-choice.v1",
    "multiple_select": "qtype-multiple-select.v1",
    "true_false": "qtype-true-false.v1",
    "matching": "qtype-matching.v1",
    "fill_blank": "qtype-fill-blank.v1",
    "image_observation": "qtype-image-observation.v1",
    "short_answer": "qtype-short-answer.v1",
    "scenario_based": "qtype-scenario-based.v1",
}
BLOCK_TYPES = {"rich_text", "image", "diagram", "interactive", "activity", "recap"}
OPTION_TYPES = {"multiple_choice", "multiple_select", "image_observation", "scenario_based"}
ARTIFACT_ID_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,119}\Z")
STABLE_ID_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,179}\Z")
SUBJECT_RE = re.compile(r"[A-Za-z][A-Za-z0-9_-]{0,39}\Z")

def read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"Cannot read JSON {path}: {exc}") from exc

def nonempty(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())

def localized(value: Any) -> bool:
    return isinstance(value, dict) and nonempty(value.get("en")) and nonempty(value.get("hi"))

def source_id(value: Any) -> str | None:
    if not isinstance(value, dict):
        return None
    ident = value.get("id") or value.get("source_ref_id")
    return ident if nonempty(ident) else None

def item_ids(items: Any) -> set[str]:
    if not isinstance(items, list) or not items:
        return set()
    ids = []
    for item in items:
        if not isinstance(item, dict) or not nonempty(item.get("id")) or not localized(item.get("label_by_locale")):
            return set()
        ids.append(item["id"])
    return set(ids) if len(ids) == len(set(ids)) else set()

def localized_content(value: Any) -> bool:
    if not isinstance(value, dict):
        return False
    for locale in ("en", "hi"):
        entry = value.get(locale)
        if not isinstance(entry, dict):
            return False
        heading = entry.get("heading")
        paragraphs = entry.get("paragraphs")
        if not nonempty(heading) and not (isinstance(paragraphs, list) and paragraphs):
            return False
        if paragraphs is not None and (not isinstance(paragraphs, list) or any(not nonempty(p) for p in paragraphs)):
            return False
    return True

def localized_instructions(value: Any) -> bool:
    if not isinstance(value, dict):
        return False
    for locale in ("en", "hi"):
        entry = value.get(locale)
        if nonempty(entry):
            continue
        if isinstance(entry, list) and entry and all(nonempty(line) for line in entry):
            continue
        return False
    return True

def instructions_present(block: dict[str, Any]) -> bool:
    """Accept localized text/list instructions and the current nested activity-step schema."""
    if localized_instructions(block.get("instructions_by_locale")):
        return True
    config = block.get("configuration") if isinstance(block.get("configuration"), dict) else {}
    if localized_instructions(config.get("instruction_by_locale")):
        return True
    steps = config.get("steps")
    if isinstance(steps, list) and steps:
        return all(
            isinstance(step, dict) and localized_instructions(step.get("instruction_by_locale") or step.get("instructions_by_locale"))
            for step in steps
        )
    return False

def visual_ids(blocks: list[Any]) -> set[str]:
    ids: set[str] = set()
    for block in blocks:
        if not isinstance(block, dict):
            continue
        asset = block.get("asset") if isinstance(block.get("asset"), dict) else {}
        diagram = block.get("diagram_data") if isinstance(block.get("diagram_data"), dict) else {}
        for value in (block.get("id"), block.get("visual_id"), asset.get("asset_id"),
                      asset.get("candidate_id"), diagram.get("visual_id"), diagram.get("diagram_id")):
            if nonempty(value):
                ids.add(value)
        refs = block.get("asset_refs")
        if isinstance(refs, list):
            ids.update(v for v in refs if nonempty(v))
    return ids

def validate_artifact(lesson: Any, label: str = "<artifact>") -> list[str]:
    errors: list[str] = []
    def err(path: str, message: str) -> None:
        errors.append(f"{label}: {path}: {message}")
    if not isinstance(lesson, dict):
        return [f"{label}: root must be a JSON object"]

    for key in ("schema_version", "artifact_type"):
        if lesson.get(key) != "quantaedge.structured-lesson.v1":
            err(key, "must equal quantaedge.structured-lesson.v1")
    aid = lesson.get("artifact_id")
    if not nonempty(aid) or not ARTIFACT_ID_RE.fullmatch(str(aid)):
        err("artifact_id", "must fit DB varchar(120) and the current stable-ID pattern")

    curriculum = lesson.get("curriculum") if isinstance(lesson.get("curriculum"), dict) else {}
    class_no, subject = curriculum.get("class"), curriculum.get("subject")
    if not isinstance(class_no, int) or isinstance(class_no, bool) or not 1 <= class_no <= 12:
        err("curriculum.class", "required runtime field; integer 1..12")
    if not nonempty(subject) or not SUBJECT_RE.fullmatch(str(subject)):
        err("curriculum.subject", "required runtime subject code; must match a curriculum_subject.code after lowercase normalization")

    chapter = lesson.get("chapter") if isinstance(lesson.get("chapter"), dict) else {}
    ch = chapter.get("chapter_number")
    if not isinstance(ch, int) or isinstance(ch, bool) or not 1 <= ch <= 80:
        err("chapter.chapter_number", "required positive chapter number")
    if not localized(chapter.get("title_by_locale")):
        err("chapter.title_by_locale", "requires English and Hindi")

    topic = lesson.get("topic") if isinstance(lesson.get("topic"), dict) else {}
    tn = topic.get("topic_number")
    if not isinstance(tn, int) or isinstance(tn, bool) or tn < 1:
        err("topic.topic_number", "required positive topic number")
    if not nonempty(topic.get("topic_id")) or not STABLE_ID_RE.fullmatch(str(topic.get("topic_id", ""))):
        err("topic.topic_id", "required stable topic ID")
    if not localized(topic.get("title_by_locale")):
        err("topic.title_by_locale", "requires English and Hindi")

    objectives = lesson.get("learning_objectives")
    if not isinstance(objectives, list) or not objectives:
        err("learning_objectives", "at least one objective is required")
        objectives = []
    objective_ids: set[str] = set()
    for i, row in enumerate(objectives):
        path = f"learning_objectives[{i}]"
        if not isinstance(row, dict) or not nonempty(row.get("id")) or not localized(row.get("statement_by_locale")):
            err(path, "requires a stable ID and bilingual statement_by_locale")
            continue
        if row["id"] in objective_ids:
            err(path + ".id", "duplicate objective ID")
        objective_ids.add(row["id"])

    refs = lesson.get("source_refs")
    if not isinstance(refs, list) or not refs:
        err("source_refs", "at least one source reference is required")
        refs = []
    source_ids: set[str] = set()
    for i, row in enumerate(refs):
        ident = source_id(row)
        if not ident:
            err(f"source_refs[{i}]", "requires id or source_ref_id")
        elif ident in source_ids:
            err(f"source_refs[{i}]", "duplicate source ID")
        else:
            source_ids.add(ident)

    explain = lesson.get("explain") if isinstance(lesson.get("explain"), dict) else {}
    blocks = explain.get("blocks")
    if not isinstance(blocks, list) or not blocks:
        err("explain.blocks", "at least one Explain block is required")
        blocks = []
    block_ids: set[str] = set()
    for i, block in enumerate(blocks):
        path = f"explain.blocks[{i}]"
        if not isinstance(block, dict):
            err(path, "must be an object")
            continue
        bid, btype = block.get("id"), block.get("type")
        if not nonempty(bid) or not STABLE_ID_RE.fullmatch(str(bid or "")):
            err(path + ".id", "required ID must fit DB varchar(180) and stable-ID pattern")
        elif bid in block_ids:
            err(path + ".id", "duplicate block ID")
        else:
            block_ids.add(bid)
        if btype not in BLOCK_TYPES:
            err(path + ".type", f"unsupported block type {btype!r}")
        if btype == "rich_text" and not localized_content(block.get("text_by_locale")):
            err(path + ".text_by_locale", "requires bilingual heading/paragraph content")
        if btype == "recap":
            recap = block.get("content_by_locale")
            if not isinstance(recap, dict) or any(
                not isinstance(recap.get(locale), dict)
                or not isinstance(recap[locale].get("key_takeaways"), list)
                or not recap[locale]["key_takeaways"]
                or any(not nonempty(v) for v in recap[locale]["key_takeaways"])
                for locale in ("en", "hi")
            ):
                err(path + ".content_by_locale", "requires bilingual key_takeaways")
        if btype == "image":
            asset = block.get("asset")
            if not isinstance(asset, dict):
                err(path + ".asset", "image block requires asset provenance")
            if not localized(block.get("alt_by_locale")) or not localized(block.get("caption_by_locale")):
                err(path, "image requires bilingual alt_by_locale and caption_by_locale")
        if btype == "diagram":
            if not isinstance(block.get("diagram_data"), dict):
                err(path + ".diagram_data", "diagram data is required")
            if not localized(block.get("alt_by_locale")):
                err(path + ".alt_by_locale", "requires bilingual alt text")
        if btype in {"interactive", "activity"} and not instructions_present(block):
            err(path, "interactive/activity needs localized instructions_by_locale, configuration.instruction_by_locale, or fully localized configuration.steps")
        for field, allowed in (("objective_ids", objective_ids), ("source_refs", source_ids)):
            if isinstance(block.get(field), list):
                for ref in block[field]:
                    if not isinstance(ref, str) or ref not in allowed:
                        err(path + "." + field, f"contains unknown ID {ref!r}")

    practice = lesson.get("practice") if isinstance(lesson.get("practice"), dict) else {}
    questions = practice.get("questions")
    if not isinstance(questions, list) or not questions:
        err("practice.questions", "requires at least one question")
        questions = []
    if "question_count" in practice and practice.get("question_count") != len(questions):
        err("practice.question_count", "must equal actual question count")
    if "title_by_locale" in practice and not localized(practice.get("title_by_locale")):
        err("practice.title_by_locale", "requires English and Hindi")

    question_ids: set[str] = set()
    known_visuals = visual_ids(blocks)
    for i, question in enumerate(questions):
        path = f"practice.questions[{i}]"
        if not isinstance(question, dict):
            err(path, "must be an object")
            continue
        qid, qtype = question.get("id"), question.get("type")
        if not nonempty(qid) or not STABLE_ID_RE.fullmatch(str(qid or "")):
            err(path + ".id", "required ID must fit DB varchar(180) and stable-ID pattern")
        elif qid in question_ids:
            err(path + ".id", "duplicate question ID within artifact")
        else:
            question_ids.add(qid)
        expected_contract = QUESTION_CONTRACTS.get(qtype)
        if expected_contract is None:
            err(path + ".type", f"unsupported type {qtype!r}")
            continue
        if question.get("rendering_contract_id") != expected_contract:
            err(path + ".rendering_contract_id", f"must equal {expected_contract}")
        if not localized(question.get("prompt_by_locale")):
            err(path + ".prompt_by_locale", "requires English and Hindi")
        if qtype != "short_answer" and not localized(question.get("feedback_by_locale")):
            err(path + ".feedback_by_locale", "requires English and Hindi")
        for field, allowed in (("objective_ids", objective_ids), ("source_refs", source_ids)):
            if isinstance(question.get(field), list):
                for ref in question[field]:
                    if not isinstance(ref, str) or ref not in allowed:
                        err(path + "." + field, f"contains unknown ID {ref!r}")

        if qtype in OPTION_TYPES:
            options = question.get("options")
            if not isinstance(options, list) or len(options) < 2:
                err(path + ".options", "requires at least two options")
                option_ids: set[str] = set()
            else:
                option_ids = set()
                for j, option in enumerate(options):
                    if not isinstance(option, dict) or not nonempty(option.get("id")) or not STABLE_ID_RE.fullmatch(str(option.get("id", ""))) or not localized(option.get("text_by_locale")):
                        err(f"{path}.options[{j}]", "requires stable ID and bilingual text")
                        continue
                    if option["id"] in option_ids:
                        err(f"{path}.options[{j}].id", "duplicate option ID")
                    option_ids.add(option["id"])
            correct = question.get("correct_answer_ids")
            if not isinstance(correct, list) or not correct or any(not isinstance(value, str) or value not in option_ids for value in correct):
                err(path + ".correct_answer_ids", "must reference existing option IDs")
            elif len(correct) != len(set(correct)):
                err(path + ".correct_answer_ids", "contains duplicate option IDs")
            if qtype in {"multiple_choice", "scenario_based", "image_observation"} and isinstance(correct, list) and len(correct) != 1:
                err(path + ".correct_answer_ids", f"{qtype} requires exactly one answer")
        elif qtype == "true_false":
            if not isinstance(question.get("correct_answer"), bool):
                err(path + ".correct_answer", "must be a JSON Boolean")
        elif qtype == "matching":
            left, right = item_ids(question.get("left_items")), item_ids(question.get("right_items"))
            pairs = question.get("correct_pairs")
            if not left or not right:
                err(path, "matching requires bilingual left_items/right_items with unique IDs")
            if not isinstance(pairs, list) or not pairs:
                err(path + ".correct_pairs", "requires pair mappings")
            else:
                used_left: set[str] = set()
                for j, pair in enumerate(pairs):
                    if not isinstance(pair, dict) or pair.get("left_id") not in left or pair.get("right_id") not in right:
                        err(f"{path}.correct_pairs[{j}]", "references unknown left/right IDs")
                    elif pair["left_id"] in used_left:
                        err(f"{path}.correct_pairs[{j}].left_id", "duplicate left mapping")
                    else:
                        used_left.add(pair["left_id"])
        elif qtype == "fill_blank":
            accepted = question.get("accepted_answers")
            if not isinstance(accepted, dict) or any(
                not isinstance(accepted.get(locale), list) or not accepted[locale]
                or any(not nonempty(value) for value in accepted[locale])
                for locale in ("en", "hi")
            ):
                err(path + ".accepted_answers", "requires non-empty accepted answers in English and Hindi")
        elif qtype == "short_answer":
            guide = question.get("answer_guide_by_locale")
            if not isinstance(guide, dict) or any(
                not isinstance(guide.get(locale), dict)
                or not nonempty(guide[locale].get("sample_answer"))
                or not isinstance(guide[locale].get("key_points"), list)
                or not guide[locale]["key_points"]
                or any(not nonempty(value) for value in guide[locale]["key_points"])
                for locale in ("en", "hi")
            ):
                err(path + ".answer_guide_by_locale", "requires bilingual sample answer and key_points")
            grading = question.get("grading")
            max_score = grading.get("max_score") if isinstance(grading, dict) else None
            rubric = grading.get("rubric_by_locale") if isinstance(grading, dict) else None
            if not isinstance(grading, dict) or grading.get("mode") != "rubric":
                err(path + ".grading", "requires grading.mode=rubric")
            if isinstance(max_score, bool) or not isinstance(max_score, int) or max_score <= 0:
                err(path + ".grading.max_score", "must be a positive integer (DB max_score is INTEGER)")
            if not isinstance(rubric, dict) or any(
                not ((isinstance(rubric.get(locale), str) and rubric[locale].strip())
                     or (isinstance(rubric.get(locale), list) and rubric[locale]
                         and all(nonempty(v) for v in rubric[locale])))
                for locale in ("en", "hi")
            ):
                err(path + ".grading.rubric_by_locale", "requires bilingual rubric text/items")
        elif qtype == "scenario_based":
            if not localized(question.get("scenario_by_locale")):
                err(path + ".scenario_by_locale", "requires bilingual scenario text")
        elif qtype == "image_observation":
            ref = question.get("visual_asset_ref")
            if not nonempty(ref) or ref not in known_visuals:
                err(path + ".visual_asset_ref", "must resolve to a visual in this artifact")
            renderability = question.get("renderability")
            if not isinstance(renderability, dict) or not nonempty(renderability.get("status")):
                err(path + ".renderability", "explicit visual gate is required")
            elif renderability.get("status") not in {"APPROVED_FOR_STUDENT_RENDERING", "BLOCKED_FROM_STUDENT_RENDERING"}:
                err(path + ".renderability.status", "unapproved visuals must remain blocked")

    workflow = lesson.get("workflow")
    if not isinstance(workflow, dict):
        err("workflow", "review/publication state is required")
    elif workflow.get("publication_allowed") is True:
        if str(workflow.get("status", "")).upper() != "PUBLISHED":
            err("workflow.status", "publication_allowed=true requires PUBLISHED")
        for key in ("content_review_status", "source_completeness_status", "bilingual_parity_status"):
            if str(workflow.get(key, "")).upper() != "APPROVED":
                err("workflow." + key, "must be APPROVED before publication")
        science = workflow.get("scientific_review_status", workflow.get("science_review_status", ""))
        if str(science).upper() != "APPROVED":
            err("workflow.scientific_review_status", "must be APPROVED before publication")
        if str(workflow.get("visual_review_status", "")).upper() not in {"APPROVED", "APPROVED_FOR_STUDENT_RENDERING"}:
            err("workflow.visual_review_status", "must be approved before publication")
    return errors

def validate_index(index: Any, repo_root: Path, index_path: Path) -> dict[str, Any]:
    errors: list[str] = []
    empty = {"artifact_count": 0, "question_count": 0, "question_type_counts": {},
             "canonical_files_in_scope": 0, "indexed_artifacts": 0}
    if not isinstance(index, dict) or index.get("schema_version") != "quantaedge.lesson-production-index.v1":
        return {"status": "INVALID", "errors": ["invalid production-index schema_version"], **empty}
    chapters = index.get("chapters")
    if not isinstance(chapters, list):
        return {"status": "INVALID", "errors": ["chapters must be an array"], **empty}
    scope = index_path.parent.resolve()
    try:
        scope.relative_to(repo_root.resolve())
    except ValueError:
        return {"status": "INVALID", "errors": ["index path must be under repo root"], **empty}

    seen_paths: set[str] = set()
    seen_artifacts: set[str] = set()
    seen_topics: set[str] = set()
    type_counts: Counter[str] = Counter()
    artifact_count = question_count = 0
    indexed_class = (index.get("target") or {}).get("class")
    expected_subject = scope.name.lower()
    for chapter_row in chapters:
        if not isinstance(chapter_row, dict):
            errors.append("chapter row must be an object")
            continue
        chapter_no = chapter_row.get("number")
        if not isinstance(chapter_no, int) or isinstance(chapter_no, bool):
            errors.append("chapter row.number must be an integer")
            continue
        topic_rows = chapter_row.get("topics", [])
        if not isinstance(topic_rows, list):
            errors.append(f"chapter {chapter_no}: topics must be an array")
            continue
        for row in topic_rows:
            if not isinstance(row, dict):
                errors.append(f"chapter {chapter_no}: topic row must be an object")
                continue
            relative = row.get("artifact_path")
            if not nonempty(relative) or relative in seen_paths:
                errors.append(f"chapter {chapter_no}: missing/duplicate artifact_path {relative!r}")
                continue
            seen_paths.add(relative)
            candidate = (repo_root / relative).resolve()
            try:
                candidate.relative_to(repo_root.resolve())
            except ValueError:
                errors.append(f"{relative}: path escapes repository root")
                continue
            if not candidate.is_file():
                errors.append(f"{relative}: indexed artifact is missing")
                continue
            try:
                artifact = read_json(candidate)
            except ValueError as exc:
                errors.append(str(exc))
                continue
            errors.extend(validate_artifact(artifact, relative))
            if not isinstance(artifact, dict):
                continue
            artifact_count += 1
            aid = artifact.get("artifact_id")
            topic = artifact.get("topic") if isinstance(artifact.get("topic"), dict) else {}
            curriculum = artifact.get("curriculum") if isinstance(artifact.get("curriculum"), dict) else {}
            if isinstance(aid, str):
                if aid in seen_artifacts:
                    errors.append(f"{relative}: duplicate artifact_id {aid}")
                seen_artifacts.add(aid)
            tid = topic.get("topic_id")
            if isinstance(tid, str):
                if tid in seen_topics:
                    errors.append(f"{relative}: duplicate topic_id {tid}")
                seen_topics.add(tid)
            if artifact.get("chapter", {}).get("chapter_number") != chapter_no:
                errors.append(f"{relative}: chapter number differs from production-index row")
            if topic.get("topic_number") != row.get("number"):
                errors.append(f"{relative}: topic number differs from production-index row")
            if row.get("topic_id") and tid != row.get("topic_id"):
                errors.append(f"{relative}: topic_id differs from production-index row")
            if isinstance(indexed_class, int) and curriculum.get("class") != indexed_class:
                errors.append(f"{relative}: curriculum.class differs from index target.class")
            if isinstance(curriculum.get("subject"), str) and curriculum["subject"].lower() != expected_subject:
                errors.append(f"{relative}: curriculum.subject does not match canonical subject folder {expected_subject}")
            questions = artifact.get("practice", {}).get("questions", [])
            counts = Counter(q.get("type") for q in questions if isinstance(q, dict) and q.get("type") in QUESTION_CONTRACTS)
            question_count += len(questions)
            type_counts.update(counts)
            if "practice_item_count" in row and row.get("practice_item_count") != len(questions):
                errors.append(f"{relative}: practice_item_count differs from actual question count")
            if isinstance(row.get("question_type_counts"), dict) and row["question_type_counts"] != dict(counts):
                errors.append(f"{relative}: question_type_counts differs from actual question types")
            if isinstance(row.get("practice_types"), list) and set(row["practice_types"]) != set(counts):
                errors.append(f"{relative}: practice_types differs from actual question types")

    actual_files = {p.resolve() for p in scope.glob("chapter-*/topic-*.json") if p.is_file()}
    indexed_files = {(repo_root / value).resolve() for value in seen_paths}
    for path in sorted(actual_files - indexed_files):
        errors.append(f"{path.relative_to(repo_root.resolve())}: canonical artifact is missing from production index")
    for path in sorted(indexed_files - actual_files):
        errors.append(f"{path}: indexed artifact is outside canonical chapter/topic scope")
    return {
        "status": "INVALID" if errors else "PASS",
        "errors": errors,
        "artifact_count": artifact_count,
        "question_count": question_count,
        "question_type_counts": dict(sorted(type_counts.items())),
        "canonical_files_in_scope": len(actual_files),
        "indexed_artifacts": len(seen_paths),
        "db_key_contract": {
            "artifact_id": "artifact_id -> student_structured_question_response.artifact_id (varchar 120)",
            "artifact_sha256": "computed by API from exact canonical JSON bytes -> artifact_sha256 (char 64); not source_pdf_sha256",
            "question_id": "practice.questions[].id -> question_id (varchar 180)",
            "activity_block_id": "explain.blocks[].id -> block_id (varchar 180)",
            "student_id": "resolved from authenticated student context -> student_id FK; never generated in lesson JSON",
            "response": "submitted learner payload -> response JSONB; answer keys stay server-side",
        },
    }

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path("."))
    parser.add_argument("--index", type=Path, default=Path("data/structured-lessons/class-6/science/production-index.v1.json"))
    parser.add_argument("--json-report", type=Path)
    args = parser.parse_args()
    root = args.repo_root.resolve()
    index_path = args.index if args.index.is_absolute() else root / args.index
    try:
        result = validate_index(read_json(index_path), root, index_path.resolve())
    except ValueError as exc:
        result = {"status": "INVALID", "errors": [str(exc)], "artifact_count": 0,
                  "question_count": 0, "question_type_counts": {}}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if args.json_report:
        report = args.json_report if args.json_report.is_absolute() else root / args.json_report
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0 if result.get("status") == "PASS" else 2

if __name__ == "__main__":
    raise SystemExit(main())
