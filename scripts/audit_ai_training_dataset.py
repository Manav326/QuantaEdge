#!/usr/bin/env python3
"""Audit existing QuantaEdge AI training records without regenerating or modifying them.

Reads committed JSONL files and exports a small review batch plus an inventory report.
The source dataset is read-only; outputs are written to a separate audit directory.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path
from typing import Any


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows = []
    with path.open(encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"{path}:{line_number}: invalid JSON: {exc}") from exc
            if not isinstance(row, dict):
                raise ValueError(f"{path}:{line_number}: record is not a JSON object")
            rows.append(row)
    return rows


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def language_of(row: dict[str, Any]) -> str:
    metadata = row.get("metadata") or {}
    language = str(metadata.get("language", "")).lower()
    if language in {"en", "hi"}:
        return language
    messages = row.get("messages") or []
    text = " ".join(str(m.get("content", "")) for m in messages if isinstance(m, dict))
    return "hi" if any("\u0900" <= char <= "\u097f" for char in text) else "unknown"


def select_review_batch(rows: list[dict[str, Any]], count: int) -> list[dict[str, Any]]:
    candidates = []
    for row in rows:
        metadata = row.get("metadata") or {}
        if str(metadata.get("class_code", "")) != "6":
            continue
        if str(metadata.get("subject_code", "")).lower() != "maths":
            continue
        if language_of(row) in {"en", "hi"}:
            candidates.append(row)
    # Stable order makes repeated audits reproducible; alternate languages when possible.
    candidates.sort(key=lambda row: str(row.get("example_id", "")))
    by_language = {
        lang: [row for row in candidates if language_of(row) == lang]
        for lang in ("en", "hi")
    }
    selected: list[dict[str, Any]] = []
    positions = {"en": 0, "hi": 0}
    order = ["en", "hi"]
    while len(selected) < min(count, len(candidates)):
        progressed = False
        for lang in order:
            if positions[lang] < len(by_language[lang]) and len(selected) < count:
                selected.append(by_language[lang][positions[lang]])
                positions[lang] += 1
                progressed = True
        if not progressed:
            break
    return selected


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-dir", type=Path, default=Path("data/ai-training/datasets"))
    parser.add_argument("--output-dir", type=Path, default=Path("artifacts/class6-maths-audit"))
    parser.add_argument("--sample-size", type=int, default=10)
    args = parser.parse_args()
    if args.sample_size < 1:
        parser.error("--sample-size must be positive")

    master_path = args.dataset_dir / "dataset_master.jsonl"
    train_path = args.dataset_dir / "train.jsonl"
    manifest_path = args.dataset_dir / "manifest.json"
    if not master_path.is_file() or not train_path.is_file() or not manifest_path.is_file():
        raise SystemExit("Expected dataset_master.jsonl, train.jsonl, and manifest.json in dataset directory.")

    master = read_jsonl(master_path)
    train = read_jsonl(train_path)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    sample = select_review_batch(train, args.sample_size)
    class6_maths = [
        row for row in master
        if str((row.get("metadata") or {}).get("class_code", "")) == "6"
        and str((row.get("metadata") or {}).get("subject_code", "")).lower() == "maths"
    ]
    track_counts = Counter(language_of(row) for row in class6_maths)
    missing_ids = [row.get("example_id") for row in sample if not row.get("example_id")]
    if missing_ids:
        raise SystemExit("Selected records are missing example_id; cannot safely review without stable IDs.")

    args.output_dir.mkdir(parents=True, exist_ok=True)
    sample_path = args.output_dir / "review_batch.json"
    report_path = args.output_dir / "audit_report.json"
    sample_payload = {
        "notice": "These are exact records read from the existing train.jsonl. Source dataset was not changed.",
        "source_file": "data/ai-training/datasets/train.jsonl",
        "source_file_sha256": sha256_file(train_path),
        "manifest_status": manifest.get("status"),
        "records": [
            {
                "example_id": row.get("example_id"),
                "metadata": row.get("metadata", {}),
                "messages": row.get("messages", []),
                "review": {"verdict": None, "feedback": "", "status": "not_reviewed"},
            }
            for row in sample
        ],
    }
    report = {
        "source_dataset": "data/ai-training/datasets",
        "source_master_sha256": sha256_file(master_path),
        "source_train_sha256": sha256_file(train_path),
        "manifest_declared_records": manifest.get("records"),
        "actual_master_records": len(master),
        "actual_train_records": len(train),
        "class6_maths_records_in_master": len(class6_maths),
        "class6_maths_language_counts": dict(track_counts),
        "requested_review_batch_size": args.sample_size,
        "selected_review_batch_size": len(sample),
        "selected_ids": [row.get("example_id") for row in sample],
        "provenance_types": dict(Counter(
            str((row.get("metadata") or {}).get("provenance_type", "missing"))
            for row in class6_maths
        )),
        "manifest_status": manifest.get("status"),
        "source_dataset_modified": False,
        "limitations": [
            "This utility exports existing records; it does not generate or edit training data.",
            "Curriculum correctness and bilingual semantic equivalence still require content-level review.",
            "The ten records are a reproducible sample, not proof that all dataset records are high quality."
        ],
    }
    sample_path.write_text(json.dumps(sample_payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
