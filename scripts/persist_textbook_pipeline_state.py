#!/usr/bin/env python3
"""Restore and persist QuantaEdge textbook pipeline data outside the application main branch."""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Sequence

DEFAULT_STATE_BRANCH = "automation/textbook-pipeline-state"
STATE_PATHS = (
    "data/textbook-extractions",
    "data/textbook-visual-candidates-v3",
    "data/textbook-visuals",
    "data/textbook-content-pipeline",
    "data/structured-lessons",
    "data/ai-training",
)


def _git(repo: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *args],
        cwd=repo,
        text=True,
        capture_output=True,
        check=check,
    )


def _validate_branch(repo: Path, branch: str) -> None:
    if not branch or branch.startswith("-"):
        raise ValueError("A valid textbook state branch is required.")
    _git(repo, "check-ref-format", "--branch", branch)


def _validate_paths(repo: Path, paths: Sequence[str]) -> list[str]:
    if not paths:
        raise ValueError("At least one pipeline data path is required.")
    allowed: list[str] = []
    for raw in paths:
        candidate = Path(raw)
        if candidate.is_absolute() or ".." in candidate.parts or not candidate.parts:
            raise ValueError(f"Unsafe pipeline state path: {raw}")
        normalized = candidate.as_posix()
        if not any(normalized == root or normalized.startswith(root + "/") for root in STATE_PATHS):
            raise ValueError(f"Pipeline state may only contain approved textbook data directories: {raw}")
        resolved = (repo / candidate).resolve()
        try:
            resolved.relative_to((repo / "data").resolve())
        except ValueError as exc:
            raise ValueError(f"Pipeline state path escapes data/: {raw}") from exc
        if normalized not in allowed:
            allowed.append(normalized)
    return allowed


def _fetch_refs(repo: Path, branch: str) -> None:
    _git(repo, "fetch", "--no-tags", "origin", "main")
    _git(repo, "fetch", "--no-tags", "origin", branch)


def _is_ancestor(repo: Path, older: str, newer: str) -> bool:
    result = _git(repo, "merge-base", "--is-ancestor", older, newer, check=False)
    if result.returncode not in (0, 1):
        raise RuntimeError(f"Unable to compare textbook state refs: {result.stderr.strip()}")
    return result.returncode == 0


def _seed_state_from_latest_main(repo: Path, branch: str) -> str:
    """Fast-forward the untouched state branch to latest main exactly once.

    This captures any legacy checkpoints written by already-running workers before the
    state-branch migration. Once state has its own commit, it is intentionally independent
    from main and will never be overwritten by main.
    """
    _fetch_refs(repo, branch)
    state_ref = f"refs/remotes/origin/{branch}"
    main_ref = "refs/remotes/origin/main"
    if _is_ancestor(repo, state_ref, main_ref) and _git(repo, "rev-parse", state_ref).stdout.strip() != _git(repo, "rev-parse", main_ref).stdout.strip():
        pushed = _git(repo, "push", "origin", f"{main_ref}:refs/heads/{branch}", check=False)
        if pushed.returncode != 0:
            _git(repo, "fetch", "--no-tags", "origin", branch)
            state_ref = f"refs/remotes/origin/{branch}"
            main_ref = "refs/remotes/origin/main"
            if _is_ancestor(repo, state_ref, main_ref):
                detail = (pushed.stderr or pushed.stdout or "unknown Git push error").strip()
                raise RuntimeError(f"Could not seed the textbook state branch from latest main: {detail}")
    _git(repo, "fetch", "--no-tags", "origin", branch)
    return f"refs/remotes/origin/{branch}"


def restore_state(repo_root: Path, branch: str = DEFAULT_STATE_BRANCH) -> dict[str, str]:
    repo = Path(repo_root).resolve()
    _validate_branch(repo, branch)
    state_ref = _seed_state_from_latest_main(repo, branch)
    existing: list[str] = []
    for path in STATE_PATHS:
        result = _git(repo, "ls-tree", "-r", "--name-only", state_ref, "--", path)
        if result.stdout.strip():
            existing.append(path)
    if existing:
        _git(repo, "restore", "--source", state_ref, "--worktree", "--", *existing)
    return {
        "branch": branch,
        "source_ref": state_ref,
        "restored_paths": ",".join(existing),
    }


def _copy_path(source: Path, destination: Path) -> None:
    if destination.is_dir() and not destination.is_symlink():
        shutil.rmtree(destination)
    elif destination.exists() or destination.is_symlink():
        destination.unlink()
    if not source.exists() and not source.is_symlink():
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    if source.is_dir() and not source.is_symlink():
        shutil.copytree(source, destination, dirs_exist_ok=True)
    else:
        shutil.copy2(source, destination, follow_symlinks=False)


def commit_state(
    repo_root: Path,
    paths: Sequence[str],
    message: str,
    branch: str = DEFAULT_STATE_BRANCH,
) -> dict[str, str | bool]:
    repo = Path(repo_root).resolve()
    _validate_branch(repo, branch)
    if not str(message).strip():
        raise ValueError("A non-empty checkpoint commit message is required.")
    normalized_paths = _validate_paths(repo, paths)
    state_ref = _seed_state_from_latest_main(repo, branch)

    with tempfile.TemporaryDirectory(prefix="qe-textbook-state-") as temp:
        worktree = Path(temp) / "worktree"
        _git(repo, "worktree", "add", "--detach", str(worktree), state_ref)
        try:
            for relative in normalized_paths:
                _copy_path(repo / relative, worktree / relative)
            _git(worktree, "add", "-A", "--", *normalized_paths)
            diff = _git(worktree, "diff", "--cached", "--quiet", check=False)
            if diff.returncode == 0:
                return {
                    "branch": branch,
                    "commit_sha": _git(worktree, "rev-parse", "HEAD").stdout.strip(),
                    "changed": False,
                }
            if diff.returncode != 1:
                raise RuntimeError(f"Could not inspect staged textbook state: {diff.stderr.strip()}")
            _git(worktree, "config", "user.name", "github-actions[bot]")
            _git(worktree, "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com")
            _git(worktree, "commit", "-m", message)

            for attempt in range(1, 6):
                pushed = _git(
                    worktree,
                    "push",
                    "origin",
                    f"HEAD:refs/heads/{branch}",
                    check=False,
                )
                if pushed.returncode == 0:
                    return {
                        "branch": branch,
                        "commit_sha": _git(worktree, "rev-parse", "HEAD").stdout.strip(),
                        "changed": True,
                    }

                detail = (pushed.stderr or pushed.stdout or "unknown Git push error").strip()
                if not any(token in detail.lower() for token in (
                    "non-fast-forward", "fetch first", "failed to push some refs", "rejected",
                )):
                    raise RuntimeError(f"Could not push textbook state to {branch}: {detail}")

                _git(worktree, "fetch", "--no-tags", "origin", branch)
                rebased = _git(
                    worktree,
                    "rebase",
                    f"refs/remotes/origin/{branch}",
                    check=False,
                )
                if rebased.returncode != 0:
                    _git(worktree, "rebase", "--abort", check=False)
                    raise RuntimeError(
                        "Textbook state branch has an overlapping write. "
                        "No checkpoint was force-pushed or discarded. "
                        + (rebased.stderr or rebased.stdout or "").strip()
                    )
                if attempt == 5:
                    raise RuntimeError(f"Textbook state push did not converge after {attempt} attempts: {detail}")

            raise RuntimeError("Textbook state push exhausted its retry budget.")
        finally:
            _git(repo, "worktree", "remove", "--force", str(worktree), check=False)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    restore = sub.add_parser("restore", help="Restore isolated textbook data into the current checkout.")
    restore.add_argument("--repo-root", type=Path, default=Path.cwd())
    restore.add_argument("--branch", default=os.environ.get("QUANTAEDGE_TEXTBOOK_STATE_BRANCH", DEFAULT_STATE_BRANCH))
    commit = sub.add_parser("commit", help="Commit selected textbook data paths to the isolated state branch.")
    commit.add_argument("--repo-root", type=Path, default=Path.cwd())
    commit.add_argument("--branch", default=os.environ.get("QUANTAEDGE_TEXTBOOK_STATE_BRANCH", DEFAULT_STATE_BRANCH))
    commit.add_argument("--message", required=True)
    commit.add_argument("--paths", nargs="+", required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "restore":
            result = restore_state(args.repo_root, args.branch)
        else:
            result = commit_state(args.repo_root, args.paths, args.message, args.branch)
        print(json.dumps(result, ensure_ascii=False, sort_keys=True))
        return 0
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as exc:
        print(f"Textbook pipeline state operation stopped safely: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
