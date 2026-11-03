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
    shallow = _git(repo, "rev-parse", "--is-shallow-repository").stdout.strip() == "true"
    if shallow:
        # Workflow checkouts often default to fetch-depth: 1. Ancestry-based
        # state reconciliation needs the shared commit history, not only tip trees.
        _git(repo, "fetch", "--unshallow", "--no-tags", "origin", "main")
    _git(repo, "fetch", "--no-tags", "origin", "main")
    _git(repo, "fetch", "--no-tags", "origin", branch)


def _is_ancestor(repo: Path, older: str, newer: str) -> bool:
    result = _git(repo, "merge-base", "--is-ancestor", older, newer, check=False)
    if result.returncode not in (0, 1):
        raise RuntimeError(f"Unable to compare textbook state refs: {result.stderr.strip()}")
    return result.returncode == 0


def _baseline_file(repo: Path) -> Path:
    raw = _git(repo, "rev-parse", "--git-path", "quantaedge-textbook-state-base.json").stdout.strip()
    path = Path(raw)
    return path if path.is_absolute() else repo / path


def _write_baseline(repo: Path, branch: str, commit_sha: str) -> None:
    path = _baseline_file(repo)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps({"branch": branch, "commit_sha": commit_sha}, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def _read_baseline(repo: Path, branch: str) -> str:
    path = _baseline_file(repo)
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise RuntimeError(
            "No valid textbook state baseline exists for this checkout. "
            "Run 'restore' before committing pipeline checkpoints."
        ) from exc
    commit_sha = str(value.get("commit_sha") or "")
    if value.get("branch") != branch or len(commit_sha) != 40:
        raise RuntimeError("Textbook state baseline does not match the requested branch.")
    return commit_sha


def _reject_stale_path_writes(
    repo: Path,
    branch: str,
    baseline_sha: str,
    latest_state_ref: str,
    paths: Sequence[str],
) -> None:
    latest_sha = _git(repo, "rev-parse", latest_state_ref).stdout.strip()
    if baseline_sha == latest_sha:
        return
    if not _is_ancestor(repo, baseline_sha, latest_sha):
        raise RuntimeError(
            "The isolated textbook state branch was rewritten after this checkout restored it. "
            "No checkpoint was committed; restore the latest state and retry."
        )
    changed: list[str] = []
    for relative in paths:
        result = _git(repo, "diff", "--quiet", baseline_sha, latest_sha, "--", relative, check=False)
        if result.returncode == 1:
            changed.append(relative)
        elif result.returncode != 0:
            raise RuntimeError(
                f"Could not compare the saved textbook checkpoint baseline for {relative}: "
                f"{result.stderr.strip()}"
            )
    if changed:
        raise RuntimeError(
            "Concurrent textbook state changed the same checkpoint path(s) after this checkout "
            f"restored its baseline: {', '.join(changed)}. No checkpoint was overwritten. "
            "Restore the latest state and retry this book."
        )


def _git_bytes(repo: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess[bytes]:
    result = subprocess.run(
        ["git", *args],
        cwd=repo,
        capture_output=True,
        check=False,
    )
    if check and result.returncode:
        detail = result.stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError(detail or f"git {' '.join(args)} failed ({result.returncode})")
    return result


def _blob_at(repo: Path, ref: str, relative: str) -> str | None:
    result = _git(repo, "rev-parse", f"{ref}:{relative}", check=False)
    if result.returncode == 0:
        return result.stdout.strip()
    if "does not exist" in result.stderr.lower() or "not in the working tree" in result.stderr.lower() or "path" in result.stderr.lower():
        return None
    raise RuntimeError(f"Could not inspect {relative} at {ref}: {result.stderr.strip()}")


def _import_late_main_data(repo: Path, branch: str) -> str:
    """Import post-fork data-only commits from main without importing application code.

    Older, already-running manual workflows may finish a final checkpoint on main
    after the state branch is first seeded. Import disjoint data paths; if both main
    and the isolated state changed the same path differently, stop and preserve both
    branch tips for an explicit recovery rather than overwriting either checkpoint.
    """
    data_roots = tuple(STATE_PATHS)
    for attempt in range(1, 6):
        _fetch_refs(repo, branch)
        state_ref = f"refs/remotes/origin/{branch}"
        main_ref = "refs/remotes/origin/main"
        merge_base = _git(repo, "merge-base", state_ref, main_ref).stdout.strip()
        if not merge_base:
            raise RuntimeError("Could not find a common ancestor for the textbook state and main branches.")

        changed_raw = _git(
            repo,
            "diff",
            "--name-only",
            "-z",
            "--no-renames",
            merge_base,
            main_ref,
            "--",
            *data_roots,
        ).stdout
        changed_paths = [item for item in changed_raw.split("\0") if item]
        if not changed_paths:
            return state_ref

        # Only paths explicitly owned by the isolated state branch may be imported.
        changed_paths = _validate_paths(repo, changed_paths)
        with tempfile.TemporaryDirectory(prefix="qe-legacy-main-state-") as temp:
            worktree = Path(temp) / "worktree"
            _git(repo, "worktree", "add", "--detach", str(worktree), state_ref)
            try:
                imported: list[str] = []
                for relative in changed_paths:
                    state_diff = _git(repo, "diff", "--quiet", merge_base, state_ref, "--", relative, check=False)
                    if state_diff.returncode not in (0, 1):
                        raise RuntimeError(f"Could not compare isolated state path {relative} with its merge base.")
                    state_changed = state_diff.returncode == 1
                    main_blob = _blob_at(repo, main_ref, relative)
                    state_blob = _blob_at(repo, state_ref, relative)

                    if state_changed:
                        if main_blob == state_blob:
                            # Both branches already contain identical bytes for this path.
                            continue
                        raise RuntimeError(
                            "Legacy main and isolated textbook state both changed the same data path "
                            f"after their fork: {relative}. No checkpoint was overwritten. "
                            "Preserve both branch tips and resolve this file explicitly."
                        )

                    destination = worktree / relative
                    if main_blob is None:
                        destination.unlink(missing_ok=True)
                    else:
                        payload = _git_bytes(repo, "show", f"{main_ref}:{relative}").stdout
                        destination.parent.mkdir(parents=True, exist_ok=True)
                        destination.write_bytes(payload)
                    imported.append(relative)

                if not imported:
                    return state_ref

                _git(worktree, "add", "-A", "--", *imported)
                diff = _git(worktree, "diff", "--cached", "--quiet", check=False)
                if diff.returncode == 0:
                    return state_ref
                if diff.returncode != 1:
                    raise RuntimeError("Could not inspect staged legacy textbook data imports.")
                _git(worktree, "config", "user.name", "github-actions[bot]")
                _git(worktree, "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com")
                _git(worktree, "commit", "-m", "data(content): import late legacy main textbook checkpoints")

                pushed = _git(
                    worktree,
                    "push",
                    "origin",
                    f"HEAD:refs/heads/{branch}",
                    check=False,
                )
                if pushed.returncode == 0:
                    _git(repo, "fetch", "--no-tags", "origin", branch)
                    return f"refs/remotes/origin/{branch}"

                detail = (pushed.stderr or pushed.stdout or "unknown Git push error").strip()
                if not any(token in detail.lower() for token in (
                    "non-fast-forward", "fetch first", "failed to push some refs", "rejected",
                )):
                    raise RuntimeError(f"Could not import legacy main checkpoints to {branch}: {detail}")
                if attempt == 5:
                    raise RuntimeError(f"Legacy main checkpoint import did not converge after {attempt} attempts: {detail}")
            finally:
                _git(repo, "worktree", "remove", "--force", str(worktree), check=False)

    raise RuntimeError("Legacy main checkpoint import exhausted its retry budget.")


def _seed_state_from_latest_main(repo: Path, branch: str) -> str:
    """Seed an untouched state branch from main, then import later data-only checkpoints."""
    for attempt in range(1, 6):
        _fetch_refs(repo, branch)
        state_ref = f"refs/remotes/origin/{branch}"
        main_ref = "refs/remotes/origin/main"
        state_sha = _git(repo, "rev-parse", state_ref).stdout.strip()
        main_sha = _git(repo, "rev-parse", main_ref).stdout.strip()

        if state_sha == main_sha:
            break
        if not _is_ancestor(repo, state_ref, main_ref):
            # The state branch has its own commits; import only post-fork data paths below.
            break

        pushed = _git(repo, "push", "origin", f"{main_ref}:refs/heads/{branch}", check=False)
        if pushed.returncode == 0:
            _git(repo, "fetch", "--no-tags", "origin", branch)
            break

        detail = (pushed.stderr or pushed.stdout or "unknown Git push error").strip()
        if not any(token in detail.lower() for token in (
            "non-fast-forward", "fetch first", "failed to push some refs", "rejected",
        )):
            raise RuntimeError(f"Could not seed the textbook state branch from latest main: {detail}")
        if attempt == 5:
            raise RuntimeError(f"Could not fast-forward the textbook state branch after {attempt} attempts: {detail}")

    return _import_late_main_data(repo, branch)

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
    baseline_sha = _git(repo, "rev-parse", state_ref).stdout.strip()
    _write_baseline(repo, branch, baseline_sha)
    return {
        "branch": branch,
        "source_ref": state_ref,
        "baseline_sha": baseline_sha,
        "restored_paths": ",".join(existing),
    }


def _changed_state_paths(repo: Path, base: str, head: str) -> list[str]:
    raw = _git(
        repo,
        "diff",
        "--name-only",
        "-z",
        "--no-renames",
        base,
        head,
        "--",
        *STATE_PATHS,
    ).stdout
    values = [item for item in raw.split("\0") if item]
    return _validate_paths(repo, values) if values else []


def _sync_committed_state_to_checkout(
    repo: Path,
    worktree: Path,
    paths: Sequence[str],
    committed_paths: Sequence[str],
) -> None:
    """Refresh imported/disjoint state paths without replacing this call's own inputs."""
    for relative in paths:
        if any(
            relative == committed
            or relative.startswith(committed + "/")
            or committed.startswith(relative + "/")
            for committed in committed_paths
        ):
            continue
        _copy_path(worktree / relative, repo / relative)


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
    baseline_sha = _read_baseline(repo, branch)
    _reject_stale_path_writes(repo, branch, baseline_sha, state_ref, normalized_paths)

    with tempfile.TemporaryDirectory(prefix="qe-textbook-state-") as temp:
        worktree = Path(temp) / "worktree"
        _git(repo, "worktree", "add", "--detach", str(worktree), state_ref)
        try:
            for relative in normalized_paths:
                _copy_path(repo / relative, worktree / relative)
            _git(worktree, "add", "-A", "--", *normalized_paths)
            diff = _git(worktree, "diff", "--cached", "--quiet", check=False)
            if diff.returncode == 0:
                commit_sha = _git(worktree, "rev-parse", "HEAD").stdout.strip()
                changed_state = _changed_state_paths(repo, baseline_sha, commit_sha)
                _sync_committed_state_to_checkout(repo, worktree, changed_state, normalized_paths)
                _write_baseline(repo, branch, commit_sha)
                return {
                    "branch": branch,
                    "commit_sha": commit_sha,
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
                    commit_sha = _git(worktree, "rev-parse", "HEAD").stdout.strip()
                    changed_state = _changed_state_paths(repo, baseline_sha, commit_sha)
                    _sync_committed_state_to_checkout(repo, worktree, changed_state, normalized_paths)
                    _write_baseline(repo, branch, commit_sha)
                    return {
                        "branch": branch,
                        "commit_sha": commit_sha,
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
