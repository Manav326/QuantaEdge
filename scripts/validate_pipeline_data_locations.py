#!/usr/bin/env python3
"""Fail if generated QuantaEdge pipeline artifacts are tracked outside data/."""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

FORBIDDEN_PUBLIC_SUFFIXES = (
    "/public/content/visual-candidates/",
    "/public/content/visual-assets/",
)
PIPELINE_DIRECTORY_MARKERS = (
    "textbook-extractions/",
    "textbook-visuals/",
    "textbook-visual-candidates-v3/",
    "textbook-content-pipeline/",
    "structured-lessons/",
    "ai-training/generated/",
)
PIPELINE_FILENAMES = {
    "coverage-ledger.v1.json",
    "visual-analysis-effective.v1.json",
    "visual-analysis-corrections.v1.json",
    "visual-inventory.v1.json",
    "candidates.v3.json",
    "summary.v3.json",
    "source-selection.v1.json",
    "chapter-map.v1.json",
    "bundle-index.v1.json",
    "bundle-index.v3.json",
    "contact-sheet.jpg",
    "contact-sheet.pdf",
    "generation-checkpoints.v1.json",
    "curriculum-map.v1.json",
    "layer-8-rag-corpus.v1.jsonl",
    "layer-8-visual-evidence-candidates.v1.jsonl",
    "tutor-sft-candidates.jsonl",
}
PIPELINE_PREFIXES = ("visual-analysis-", "qe-lesson-")


def is_generated_pipeline_artifact(path: str) -> bool:
    normalized = path.replace("\\", "/").lstrip("./")
    lower = normalized.lower()
    if lower == "data" or lower.startswith("data/"):
        return False
    if any(suffix in "/" + lower for suffix in FORBIDDEN_PUBLIC_SUFFIXES):
        return True
    if any(marker in lower for marker in PIPELINE_DIRECTORY_MARKERS):
        return True
    name = lower.rsplit("/", 1)[-1]
    if name in PIPELINE_FILENAMES:
        return True
    if name.startswith(PIPELINE_PREFIXES) and name.endswith((".v1.json", ".jsonl")):
        return True
    return False


def validate_paths(paths: list[str]) -> list[str]:
    return sorted({p.replace("\\", "/") for p in paths if is_generated_pipeline_artifact(p)})


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path.cwd())
    args = parser.parse_args(argv)
    root = args.repo_root.resolve()
    result = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=root, check=False, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    if result.returncode:
        print(result.stderr.decode("utf-8", errors="replace"), file=sys.stderr)
        return 2
    paths = [item.decode("utf-8", errors="replace") for item in result.stdout.split(b"\0") if item]
    violations = validate_paths(paths)
    print(json.dumps({
        "checked_paths": len(paths),
        "violations": violations,
        "generated_pipeline_data_must_live_under": "data/",
    }, ensure_ascii=False, indent=2))
    if violations:
        print("Generated pipeline data outside data/ is forbidden; move it under data/ before staging/committing.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
