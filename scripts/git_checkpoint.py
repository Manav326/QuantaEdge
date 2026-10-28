#!/usr/bin/env python3
"""Push a committed checkpoint safely when another disjoint worker advanced the branch."""
from __future__ import annotations
import argparse
import os
import subprocess
from pathlib import Path

_RETRYABLE_PUSH_ERRORS = ("non-fast-forward", "fetch first", "stale info", "failed to push some refs")


def push_checkpoint(repo_root: Path, target: str, *, max_attempts: int = 5) -> None:
    repo_root = Path(repo_root).resolve()
    target = str(target or "").strip()
    if not target or target.startswith("refs/") or target.startswith("-"):
        raise ValueError("A valid branch name is required for checkpoint pushes.")
    if max_attempts < 1:
        raise ValueError("max_attempts must be positive.")
    for attempt in range(1, max_attempts + 1):
        command = ["git", "push", "origin", f"HEAD:{target}"]
        result = subprocess.run(command, cwd=repo_root, text=True, capture_output=True, check=False)
        if result.returncode == 0:
            if result.stdout:
                print(result.stdout, end="" if result.stdout.endswith("\n") else "\n")
            return
        details = (result.stderr or result.stdout or f"exit code {result.returncode}").strip()
        retryable = any(token in details.lower() for token in _RETRYABLE_PUSH_ERRORS)
        if not retryable or attempt == max_attempts:
            raise subprocess.CalledProcessError(result.returncode, command, output=result.stdout, stderr=result.stderr)
        print(f"Checkpoint push collided with a concurrent branch update ({attempt}/{max_attempts}); fetching and rebasing.", flush=True)
        subprocess.run(["git", "fetch", "origin", target], cwd=repo_root, check=True)
        try:
            subprocess.run(["git", "rebase", f"origin/{target}"], cwd=repo_root, check=True)
        except subprocess.CalledProcessError as exc:
            subprocess.run(["git", "rebase", "--abort"], cwd=repo_root, check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            raise RuntimeError("Checkpoint push collided with overlapping changes. Rebase was aborted to avoid overwriting another worker's checkpoint.") from exc
    raise RuntimeError("Checkpoint push retry loop ended unexpectedly.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path.cwd())
    parser.add_argument("--target", default=os.environ.get("QUANTAEDGE_CHECKPOINT_BRANCH") or os.environ.get("GITHUB_REF_NAME", ""))
    parser.add_argument("--max-attempts", type=int, default=5)
    args = parser.parse_args()
    push_checkpoint(args.repo_root, args.target, max_attempts=args.max_attempts)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
