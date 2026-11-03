import subprocess
import tempfile
import unittest
from pathlib import Path

from scripts.persist_textbook_pipeline_state import (
    DEFAULT_STATE_BRANCH,
    commit_state,
    restore_state,
)


def git(cwd: Path, *args: str) -> str:
    return subprocess.run(
        ["git", *args], cwd=cwd, text=True, capture_output=True, check=True
    ).stdout.strip()


def write(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value, encoding="utf-8")


class TextbookPipelineStateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.remote = root / "origin.git"
        self.seed = root / "seed"
        self.clone = root / "clone"
        subprocess.run(["git", "init", "--bare", str(self.remote)], check=True, capture_output=True)
        self.seed.mkdir()
        subprocess.run(["git", "init", "-b", "main"], cwd=self.seed, check=True, capture_output=True)
        git(self.seed, "config", "user.name", "test")
        git(self.seed, "config", "user.email", "test@example.invalid")
        write(self.seed / "data/textbook-extractions/README.md", "initial\n")
        write(self.seed / "data/textbook-content-pipeline/resume-control.v1.json", "{}\n")
        write(self.seed / "data/textbook-visuals/index.json", "{}\n")
        write(self.seed / "data/textbook-visual-candidates-v3/index.json", "{}\n")
        write(self.seed / "data/structured-lessons/index.json", "{}\n")
        write(self.seed / "data/ai-training/datasets/index.json", "{}\n")
        git(self.seed, "add", "-A")
        git(self.seed, "commit", "-m", "seed")
        git(self.seed, "remote", "add", "origin", str(self.remote))
        git(self.seed, "push", "-u", "origin", "main")
        git(self.seed, "checkout", "-b", DEFAULT_STATE_BRANCH)
        git(self.seed, "push", "-u", "origin", DEFAULT_STATE_BRANCH)
        git(self.seed, "checkout", "main")
        subprocess.run(["git", "clone", str(self.remote), str(self.clone)], check=True, capture_output=True)
        git(self.clone, "config", "user.name", "test")
        git(self.clone, "config", "user.email", "test@example.invalid")
        git(self.clone, "fetch", "origin", DEFAULT_STATE_BRANCH)

    def test_restore_fast_forwards_uninitialized_state_from_legacy_main(self):
        write(self.seed / "data/textbook-extractions/class-7/new.manifest.json", "legacy checkpoint\n")
        git(self.seed, "add", "-A")
        git(self.seed, "commit", "-m", "legacy extraction mirror on main")
        git(self.seed, "push", "origin", "main")

        result = restore_state(self.clone, DEFAULT_STATE_BRANCH)
        self.assertEqual(DEFAULT_STATE_BRANCH, result["branch"])
        restored = self.clone / "data/textbook-extractions/class-7/new.manifest.json"
        self.assertEqual("legacy checkpoint\n", restored.read_text(encoding="utf-8"))
        self.assertEqual(
            git(self.clone, "rev-parse", "origin/main"),
            git(self.clone, "rev-parse", f"origin/{DEFAULT_STATE_BRANCH}"),
        )

    def test_checkpoint_is_persisted_to_state_branch_not_main(self):
        restore_state(self.clone, DEFAULT_STATE_BRANCH)
        main_before = git(self.clone, "rev-parse", "origin/main")
        write(self.clone / "data/textbook-content-pipeline/class-6/books/book/coverage-ledger.v1.json", '{"status":"COMPLETE"}\n')
        write(self.clone / "data/ai-training/datasets/train.jsonl", '{"example_id":"draft-1"}\n')
        result = commit_state(
            self.clone,
            [
                "data/textbook-content-pipeline/class-6/books/book/coverage-ledger.v1.json",
                "data/ai-training/datasets/train.jsonl",
            ],
            "checkpoint(content): isolated Layer 5 test",
            DEFAULT_STATE_BRANCH,
        )
        self.assertTrue(result["changed"])
        git(self.clone, "fetch", "origin", "main", DEFAULT_STATE_BRANCH)
        self.assertEqual(main_before, git(self.clone, "rev-parse", "origin/main"))
        self.assertNotEqual(main_before, git(self.clone, "rev-parse", f"origin/{DEFAULT_STATE_BRANCH}"))
        saved = git(
            self.clone,
            "show",
            f"origin/{DEFAULT_STATE_BRANCH}:data/textbook-content-pipeline/class-6/books/book/coverage-ledger.v1.json",
        )
        self.assertIn("COMPLETE", saved)
        saved_dataset = git(
            self.clone,
            "show",
            f"origin/{DEFAULT_STATE_BRANCH}:data/ai-training/datasets/train.jsonl",
        )
        self.assertIn("draft-1", saved_dataset)

    def test_rejects_application_code_and_parent_traversal(self):
        restore_state(self.clone, DEFAULT_STATE_BRANCH)
        with self.assertRaisesRegex(ValueError, "approved textbook data"):
            commit_state(self.clone, ["scripts/application.py"], "must reject", DEFAULT_STATE_BRANCH)
        with self.assertRaisesRegex(ValueError, "Unsafe"):
            commit_state(self.clone, ["data/textbook-extractions/../../README.md"], "must reject", DEFAULT_STATE_BRANCH)


if __name__ == "__main__":
    unittest.main()
