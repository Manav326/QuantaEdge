#!/usr/bin/env python3
"""Validate item-level source coverage mappings for QuantaEdge structured lessons."""
from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path
from typing import Any

DISPOSITIONS = {"COVERED", "REFERENCE_ONLY", "EXCLUDED_WITH_REASON", "PENDING_SOURCE_REVIEW"}
ELEMENT_KINDS = {"block", "activity", "question", "objective"}
KIND_ALIASES = {
    "section": {"section", "topic_section"},
    "section_heading": {"section_heading", "subsection", "heading"},
    "topic": {"topic", "lesson_topic"},
    "figure": {"figure", "numbered_figure", "visual"},
    "table": {"table", "chart", "numbered_table"},
    "activity": {"activity", "experiment", "investigation"},
    "special_content": {"special_content", "sidebar", "safety_note", "example", "case"},
    "chapter_end_question": {"chapter_end_question", "review_question", "end_question"},
    "keyword": {"keyword", "key_term"},
    "summary_point": {"summary_point", "summary"},
    "learning_further": {"learning_further", "extension_task"},
    "let_us_play": {"let_us_play", "game"},
}

def read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"Cannot read JSON {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise ValueError(f"{path} must contain a JSON object.")
    return value

def first_list(*values: Any) -> list[Any] | None:
    return next((v for v in values if isinstance(v, list)), None)

def count_node(node: Any, *, list_keys: tuple[str, ...] = ("expected", "items", "tasks", "words", "coverage")) -> int | None:
    if isinstance(node, list):
        return len(node)
    if not isinstance(node, dict):
        return None
    for key in list_keys:
        if isinstance(node.get(key), list):
            return len(node[key])
    for key in ("count", "expected_count", "expected_tasks"):
        value = node.get(key)
        if isinstance(value, int) and not isinstance(value, bool) and value >= 0:
            return value
    return None

def declared_inventory_counts(ledger: dict[str, Any]) -> dict[str, int]:
    """Read declared source counts from the ledger's existing source inventory shape."""
    counts: dict[str, int] = {}
    section_map = first_list(ledger.get("section_map"))
    topic_plan = ledger.get("topic_plan") if isinstance(ledger.get("topic_plan"), dict) else {}
    headings = first_list(topic_plan.get("section_headings"))
    topics = first_list(topic_plan.get("topics"), ledger.get("topics"))
    if section_map is not None:
        counts["section"] = len(section_map)
    elif headings is not None:
        counts["section_heading"] = len(headings)
    elif topics is not None and any(isinstance(x, dict) and (x.get("section") or x.get("section_id")) for x in topics):
        counts["section"] = sum(1 for x in topics if isinstance(x, dict) and (x.get("section") or x.get("section_id")))
    if topics is not None:
        counts["topic"] = len(topics)

    visual_inventory = ledger.get("coverage_inventory") if isinstance(ledger.get("coverage_inventory"), dict) else {}
    for kind, top_key, nested_keys in (
        ("figure", "figures", ("figures", "numbered_figures")),
        ("table", "tables", ("tables",)),
        ("activity", "activities", ("activities",)),
    ):
        top = ledger.get(top_key)
        if isinstance(top, list):
            counts[kind] = len(top)
            continue
        node = next((visual_inventory.get(k) for k in nested_keys if isinstance(visual_inventory.get(k), (dict, list))), None)
        number = count_node(node)
        if number is not None:
            counts[kind] = number

    special = first_list(ledger.get("special_content"), visual_inventory.get("special_content"),
                         visual_inventory.get("special_notes"), visual_inventory.get("unnumbered_investigations"))
    if special is not None:
        counts["special_content"] = len(special)

    chapter_end = ledger.get("chapter_end_matter") if isinstance(ledger.get("chapter_end_matter"), dict) else {}
    end_matter = ledger.get("end_matter") if isinstance(ledger.get("end_matter"), dict) else {}
    review_node = (chapter_end.get("review_questions") or end_matter.get("review_questions")
                   or ledger.get("chapter_end_questions") or visual_inventory.get("chapter_end_questions"))
    review_count = count_node(review_node, list_keys=("items", "questions", "coverage"))
    if review_count is not None:
        counts["chapter_end_question"] = review_count

    keyword_node = (chapter_end.get("keywords") or end_matter.get("keywords")
                    or visual_inventory.get("keywords") or visual_inventory.get("keywords_and_summary"))
    keyword_count = count_node(keyword_node, list_keys=("words", "keywords", "items"))
    if keyword_count is not None:
        counts["keyword"] = keyword_count

    summary_node = (chapter_end.get("summary") or end_matter.get("summary")
                    or visual_inventory.get("summary") or visual_inventory.get("keywords_and_summary"))
    summary_count = count_node(summary_node, list_keys=("points", "coverage", "items", "summary"))
    if summary_count is not None:
        counts["summary_point"] = summary_count

    further_node = (chapter_end.get("learning_further") or end_matter.get("learning_further")
                    or visual_inventory.get("learning_further"))
    further_count = count_node(further_node, list_keys=("tasks", "items"))
    if further_count is not None:
        counts["learning_further"] = further_count

    play_node = chapter_end.get("let_us_play") or end_matter.get("let_us_play") or visual_inventory.get("let_us_play")
    play_count = count_node(play_node, list_keys=("tasks", "items"))
    if play_count is not None:
        counts["let_us_play"] = play_count
    return counts

def gather_lesson_paths(value: Any) -> set[str]:
    paths: set[str] = set()
    def walk(node: Any) -> None:
        if isinstance(node, dict):
            for key, child in node.items():
                if key in {"lesson_path", "artifact_path"} and isinstance(child, str) and child.endswith(".json"):
                    paths.add(child)
                elif key == "path" and isinstance(child, str) and child.startswith("data/") and child.endswith(".json"):
                    paths.add(child)
                walk(child)
        elif isinstance(node, list):
            for child in node:
                walk(child)
    walk(value)
    return paths

def artifact_index(lesson: dict[str, Any]) -> dict[str, set[str]]:
    explain = lesson.get("explain") if isinstance(lesson.get("explain"), dict) else {}
    practice = lesson.get("practice") if isinstance(lesson.get("practice"), dict) else {}
    blocks = explain.get("blocks", [])
    questions = practice.get("questions", [])
    objectives = lesson.get("learning_objectives", [])
    block_ids = {x.get("id") for x in blocks if isinstance(x, dict) and isinstance(x.get("id"), str)}
    question_ids = {x.get("id") for x in questions if isinstance(x, dict) and isinstance(x.get("id"), str)}
    objective_ids = {x.get("id") for x in objectives if isinstance(x, dict) and isinstance(x.get("id"), str)}
    activity_ids: set[str] = set()
    for block in blocks:
        if not isinstance(block, dict) or block.get("type") not in {"activity", "interactive"}:
            continue
        if isinstance(block.get("id"), str):
            activity_ids.add(block["id"])
        if isinstance(block.get("activity_id"), str):
            activity_ids.add(block["activity_id"])
        for holder_name in ("configuration", "config"):
            holder = block.get(holder_name)
            if isinstance(holder, dict):
                for key in ("activity_id", "id"):
                    if isinstance(holder.get(key), str):
                        activity_ids.add(holder[key])
    return {"block": block_ids, "activity": activity_ids, "question": question_ids, "objective": objective_ids}

def validate_coverage(ledger: dict[str, Any], repo_root: Path, *, allow_pending_review: bool = False) -> dict[str, Any]:
    errors: list[str] = []
    warnings: list[str] = []
    inventory = ledger.get("source_inventory")
    if not isinstance(inventory, dict) or not isinstance(inventory.get("items"), list):
        return {"status": "INVALID", "errors": ["source_inventory.items is missing; count-only inventories are not item-level coverage."],
                "warnings": [], "source_item_count": 0, "mapped_item_count": 0, "covered_item_count": 0,
                "pending_review_item_ids": []}
    items = inventory["items"]
    items_by_id: dict[str, dict[str, Any]] = {}
    for index, item in enumerate(items):
        if not isinstance(item, dict):
            errors.append(f"source_inventory.items[{index}] must be an object.")
            continue
        item_id = item.get("id")
        kind = str(item.get("kind", "")).strip().lower()
        if not isinstance(item_id, str) or not item_id.strip():
            errors.append(f"source_inventory.items[{index}] needs a stable non-empty id.")
            continue
        if item_id in items_by_id:
            errors.append(f"duplicate source inventory ID: {item_id}")
            continue
        if not kind:
            errors.append(f"source item {item_id} has no kind.")
        label = item.get("label_by_locale") or item.get("description_by_locale")
        if not isinstance(label, dict) or any(not isinstance(label.get(locale), str) or not label[locale].strip() for locale in ("en", "hi")):
            errors.append(f"source item {item_id} needs an explicit bilingual label/description.")
        items_by_id[item_id] = item

    declared = declared_inventory_counts(ledger)
    actual_kinds = Counter(str(x.get("kind", "")).strip().lower() for x in items if isinstance(x, dict))
    alias_to_actual = {alias: canonical for canonical, aliases in KIND_ALIASES.items() for alias in aliases}
    normalized_counts: Counter[str] = Counter()
    for kind, count in actual_kinds.items():
        normalized_counts[alias_to_actual.get(kind, kind)] += count
    for kind, count in declared.items():
        source_kind_count = normalized_counts.get(kind, 0)
        if source_kind_count != count:
            errors.append(f"source inventory count mismatch for {kind}: ledger declares {count}, item-level inventory has {source_kind_count}.")

    coverage_map = ledger.get("coverage_map")
    if not isinstance(coverage_map, list):
        errors.append("coverage_map must be an array with one disposition per source item.")
        coverage_map = []
    paths_in_ledger = gather_lesson_paths(ledger)
    coverage_by_id: dict[str, dict[str, Any]] = {}
    for index, row in enumerate(coverage_map):
        if not isinstance(row, dict):
            errors.append(f"coverage_map[{index}] must be an object.")
            continue
        item_id = row.get("source_item_id")
        if not isinstance(item_id, str) or not item_id.strip():
            errors.append(f"coverage_map[{index}] needs source_item_id.")
            continue
        if item_id in coverage_by_id:
            errors.append(f"duplicate coverage disposition for source item {item_id}.")
            continue
        coverage_by_id[item_id] = row
        if item_id not in items_by_id:
            errors.append(f"coverage map references unknown source item {item_id}.")
        disposition = str(row.get("disposition", "")).upper()
        if disposition not in DISPOSITIONS:
            errors.append(f"source item {item_id} has unsupported disposition {disposition!r}.")
        reason = row.get("reason")
        if disposition in {"REFERENCE_ONLY", "EXCLUDED_WITH_REASON", "PENDING_SOURCE_REVIEW"} and (
            not isinstance(reason, str) or not reason.strip()
        ):
            errors.append(f"source item {item_id} with disposition {disposition} needs a reason.")
        if disposition == "PENDING_SOURCE_REVIEW":
            if allow_pending_review:
                warnings.append(f"source item {item_id} remains pending source review.")
            else:
                errors.append(f"source item {item_id} remains pending source review; use --allow-pending-review only for a draft checkpoint.")
        refs = row.get("covered_by", [])
        if not isinstance(refs, list):
            errors.append(f"covered_by for {item_id} must be an array.")
            continue
        resolved_refs: list[tuple[str, str, str]] = []
        for ref_index, ref in enumerate(refs):
            if not isinstance(ref, dict):
                errors.append(f"covered_by reference {ref_index} for {item_id} must be an object.")
                continue
            lesson_path = ref.get("lesson_path")
            element_kind = ref.get("element_kind")
            element_id = ref.get("element_id")
            if not isinstance(lesson_path, str) or lesson_path not in paths_in_ledger:
                errors.append(f"source item {item_id} points to a lesson path not declared by this ledger: {lesson_path!r}.")
                continue
            path = (repo_root / lesson_path).resolve()
            try:
                path.relative_to(repo_root.resolve())
            except ValueError:
                errors.append(f"lesson path escapes repository root: {lesson_path}")
                continue
            if not path.is_file():
                errors.append(f"source item {item_id} references missing lesson artifact: {lesson_path}")
                continue
            if element_kind not in ELEMENT_KINDS or not isinstance(element_id, str) or not element_id.strip():
                errors.append(f"source item {item_id} has malformed lesson element reference.")
                continue
            try:
                lesson = read_json(path)
            except ValueError as exc:
                errors.append(str(exc))
                continue
            if "artifact_id" not in lesson or "topic" not in lesson:
                errors.append(f"referenced artifact {lesson_path} is not a structured lesson.")
                continue
            ids = artifact_index(lesson)
            if element_id not in ids[element_kind]:
                errors.append(f"source item {item_id} points to missing {element_kind} ID {element_id!r} in {lesson_path}.")
                continue
            resolved_refs.append((lesson_path, element_kind, element_id))
        if disposition == "COVERED":
            if not resolved_refs:
                errors.append(f"source item {item_id} is marked COVERED but has no resolving block/activity/question reference.")
            elif not any(kind in {"block", "activity", "question"} for _, kind, _ in resolved_refs):
                errors.append(f"source item {item_id} is marked COVERED but only links learning objectives, not actual content.")
    missing = sorted(set(items_by_id) - set(coverage_by_id))
    extra = sorted(set(coverage_by_id) - set(items_by_id))
    if missing:
        errors.append("source items with no coverage disposition: " + ", ".join(missing[:25]))
    if extra:
        errors.append("coverage dispositions with no source inventory item: " + ", ".join(extra[:25]))
    pending = sorted(item_id for item_id, row in coverage_by_id.items()
                     if str(row.get("disposition", "")).upper() == "PENDING_SOURCE_REVIEW")
    if inventory.get("status") not in {"COMPLETE", "RECONCILED", "SOURCE_RECONCILED"}:
        warnings.append("source inventory still requires source-level reconciliation; structural mappings are not proof of completeness.")
    covered_count = sum(1 for row in coverage_by_id.values() if str(row.get("disposition", "")).upper() == "COVERED")
    status = "INVALID" if errors else ("PASS_WITH_REVIEW_BLOCKERS" if warnings or pending else "PASS")
    return {"status": status, "errors": errors, "warnings": warnings,
            "source_item_count": len(items_by_id), "mapped_item_count": len(coverage_by_id) - len(missing),
            "covered_item_count": covered_count, "pending_review_item_ids": pending,
            "declared_counts_by_kind": declared, "actual_counts_by_kind": dict(normalized_counts)}

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ledger", required=True, type=Path)
    parser.add_argument("--repo-root", type=Path, default=Path("."))
    parser.add_argument("--allow-pending-review", action="store_true",
                        help="Validate draft mappings while reporting explicit source-review blockers.")
    args = parser.parse_args()
    root = args.repo_root.resolve()
    try:
        ledger = read_json(args.ledger)
        report = validate_coverage(ledger, root, allow_pending_review=args.allow_pending_review)
    except ValueError as exc:
        print(json.dumps({"status": "INVALID", "errors": [str(exc)]}, ensure_ascii=False, indent=2))
        return 2
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["status"] in {"PASS", "PASS_WITH_REVIEW_BLOCKERS"} else 2

if __name__ == "__main__":
    raise SystemExit(main())
