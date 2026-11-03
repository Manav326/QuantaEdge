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


    def _make_second_clone(self) -> Path:
        second = Path(self.temp.name) / "clone-second"
        subprocess.run(
            ["git", "clone", str(self.remote), str(second)],
            check=True,
            capture_output=True,
        )
        git(second, "config", "user.name", "test")
        git(second, "config", "user.email", "test@example.invalid")
        git(second, "fetch", "origin", DEFAULT_STATE_BRANCH)
        return second

    def test_concurrent_disjoint_book_checkpoints_are_both_preserved(self):
        second = self._make_second_clone()
        restore_state(self.clone, DEFAULT_STATE_BRANCH)
        restore_state(second, DEFAULT_STATE_BRANCH)
        main_before = git(self.clone, "rev-parse", "origin/main")

        first_path = "data/textbook-visuals/class6-en-science/index.json"
        second_path = "data/textbook-visuals/class6-hi-science/index.json"
        write(self.clone / first_path, '{"book":"english","checkpoint":1}\n')
        write(second / second_path, '{"book":"hindi","checkpoint":1}\n')

        first = commit_state(
            self.clone, [first_path], "checkpoint(content): English book checkpoint",
            DEFAULT_STATE_BRANCH,
        )
        second_result = commit_state(
            second, [second_path], "checkpoint(content): Hindi book checkpoint",
            DEFAULT_STATE_BRANCH,
        )
        self.assertTrue(first["changed"])
        self.assertTrue(second_result["changed"])

        git(self.clone, "fetch", "origin", DEFAULT_STATE_BRANCH, "main")
        english = git(self.clone, "show", f"origin/{DEFAULT_STATE_BRANCH}:{first_path}")
        hindi = git(self.clone, "show", f"origin/{DEFAULT_STATE_BRANCH}:{second_path}")
        self.assertIn("english", english)
        self.assertIn("hindi", hindi)
        self.assertEqual(main_before, git(self.clone, "rev-parse", "origin/main"))

    def test_concurrent_same_file_checkpoint_conflict_fails_without_force_push(self):
        second = self._make_second_clone()
        restore_state(self.clone, DEFAULT_STATE_BRANCH)
        restore_state(second, DEFAULT_STATE_BRANCH)
        main_before = git(self.clone, "rev-parse", "origin/main")
        shared_path = "data/textbook-content-pipeline/class-6/books/shared/coverage-ledger.v1.json"

        write(self.clone / shared_path, '{"status":"ENGLISH"}\n')
        write(second / shared_path, '{"status":"HINDI"}\n')
        commit_state(
            self.clone, [shared_path], "checkpoint(content): first shared path writer",
            DEFAULT_STATE_BRANCH,
        )
        with self.assertRaisesRegex(RuntimeError, "same checkpoint path"):
            commit_state(
                second, [shared_path], "checkpoint(content): conflicting shared path writer",
                DEFAULT_STATE_BRANCH,
            )

        git(self.clone, "fetch", "origin", DEFAULT_STATE_BRANCH, "main")
        saved = git(self.clone, "show", f"origin/{DEFAULT_STATE_BRANCH}:{shared_path}")
        self.assertIn("ENGLISH", saved)
        self.assertNotIn("HINDI", saved)
        self.assertEqual(main_before, git(self.clone, "rev-parse", "origin/main"))


    def test_restore_imports_late_legacy_main_checkpoint_after_state_branch_diverges(self):
        restore_state(self.clone, DEFAULT_STATE_BRANCH)
        state_path = "data/textbook-visuals/class6-en-science/index.json"
        legacy_path = "data/textbook-extractions/class-7/english/late.manifest.json"

        write(self.clone / state_path, '{"source":"isolated-state"}\n')
        commit_state(
            self.clone, [state_path], "checkpoint(content): state branch diverged",
            DEFAULT_STATE_BRANCH,
        )
        main_before = git(self.clone, "rev-parse", "origin/main")

        write(self.seed / legacy_path, '{"source":"late-legacy-main"}\n')
        git(self.seed, "add", "-A")
        git(self.seed, "commit", "-m", "late legacy textbook checkpoint")
        git(self.seed, "push", "origin", "main")

        result = restore_state(self.clone, DEFAULT_STATE_BRANCH)
        self.assertEqual(DEFAULT_STATE_BRANCH, result["branch"])
        self.assertEqual(
            '{"source":"late-legacy-main"}\n',
            (self.clone / legacy_path).read_text(encoding="utf-8"),
        )
        self.assertEqual(
            '{"source":"isolated-state"}\n',
            (self.clone / state_path).read_text(encoding="utf-8"),
        )
        git(self.clone, "fetch", "origin", DEFAULT_STATE_BRANCH, "main")
        imported = git(self.clone, "show", f"origin/{DEFAULT_STATE_BRANCH}:{legacy_path}")
        self.assertIn("late-legacy-main", imported)
        self.assertNotEqual(main_before, git(self.clone, "rev-parse", "origin/main"))


    def test_checkpoint_syncs_imported_legacy_paths_before_future_directory_savepoints(self):
        restore_state(self.clone, DEFAULT_STATE_BRANCH)
        legacy_path = "data/textbook-visuals/class6-en-science/late-legacy.json"
        worker_path = "data/textbook-visuals/class6-hi-science/index.json"

        write(self.seed / legacy_path, '{"source":"late-legacy-main"}\n')
        git(self.seed, "add", "-A")
        git(self.seed, "commit", "-m", "late legacy visual checkpoint")
        git(self.seed, "push", "origin", "main")

        write(self.clone / worker_path, '{"source":"current-worker"}\n')
        commit_state(
            self.clone, [worker_path], "checkpoint(content): persist current worker after legacy import",
            DEFAULT_STATE_BRANCH,
        )
        self.assertEqual(
            '{"source":"late-legacy-main"}\n',
            (self.clone / legacy_path).read_text(encoding="utf-8"),
        )

        write(self.clone / "data/textbook-visuals/class6-hi-science/second.json", '{"second":true}\n')
        commit_state(
            self.clone, ["data/textbook-visuals"],
            "checkpoint(content): broad visual directory after legacy import",
            DEFAULT_STATE_BRANCH,
        )
        git(self.clone, "fetch", "origin", DEFAULT_STATE_BRANCH)
        saved_legacy = git(self.clone, "show", f"origin/{DEFAULT_STATE_BRANCH}:{legacy_path}")
        saved_worker = git(self.clone, "show", f"origin/{DEFAULT_STATE_BRANCH}:{worker_path}")
        saved_second = git(
            self.clone, "show",
            f"origin/{DEFAULT_STATE_BRANCH}:data/textbook-visuals/class6-hi-science/second.json",
        )
        self.assertIn("late-legacy-main", saved_legacy)
        self.assertIn("current-worker", saved_worker)
        self.assertIn("true", saved_second)

    def test_restore_rejects_same_path_legacy_main_conflict_without_overwriting_either_branch(self):
        restore_state(self.clone, DEFAULT_STATE_BRANCH)
        shared_path = "data/textbook-visuals/class6-en-science/index.json"

        write(self.clone / shared_path, '{"source":"isolated-state"}\n')
        state_result = commit_state(
            self.clone, [shared_path], "checkpoint(content): state version",
            DEFAULT_STATE_BRANCH,
        )
        state_before = state_result["commit_sha"]

        write(self.seed / shared_path, '{"source":"late-legacy-main"}\n')
        git(self.seed, "add", "-A")
        git(self.seed, "commit", "-m", "conflicting legacy textbook checkpoint")
        git(self.seed, "push", "origin", "main")
        main_before = git(self.clone, "ls-remote", "origin", "refs/heads/main").split()[0]

        with self.assertRaisesRegex(RuntimeError, "both changed the same data path"):
            restore_state(self.clone, DEFAULT_STATE_BRANCH)

        git(self.clone, "fetch", "origin", DEFAULT_STATE_BRANCH, "main")
        state_saved = git(self.clone, "show", f"origin/{DEFAULT_STATE_BRANCH}:{shared_path}")
        main_saved = git(self.clone, "show", f"origin/main:{shared_path}")
        self.assertIn("isolated-state", state_saved)
        self.assertIn("late-legacy-main", main_saved)
        self.assertEqual(state_before, git(self.clone, "rev-parse", f"origin/{DEFAULT_STATE_BRANCH}"))
        self.assertEqual(main_before, git(self.clone, "rev-parse", "origin/main"))


    def test_restore_unshallows_github_style_checkout_before_state_reconciliation(self):
        state_path = "data/textbook-visuals/class6-en-science/index.json"
        legacy_path = "data/textbook-extractions/class-7/english/late.manifest.json"

        git(self.seed, "checkout", DEFAULT_STATE_BRANCH)
        write(self.seed / state_path, '{"source":"isolated-state"}\n')
        git(self.seed, "add", "-A")
        git(self.seed, "commit", "-m", "state branch checkpoint")
        git(self.seed, "push", "origin", DEFAULT_STATE_BRANCH)

        git(self.seed, "checkout", "main")
        write(self.seed / legacy_path, '{"source":"late-legacy-main"}\n')
        git(self.seed, "add", "-A")
        git(self.seed, "commit", "-m", "late main checkpoint")
        git(self.seed, "push", "origin", "main")

        shallow = Path(self.temp.name) / "shallow"
        subprocess.run(
            ["git", "clone", "--depth", "1", "--branch", "main", f"file://{self.remote}", str(shallow)],
            check=True,
            capture_output=True,
        )
        self.assertEqual("true", git(shallow, "rev-parse", "--is-shallow-repository"))

        result = restore_state(shallow, DEFAULT_STATE_BRANCH)
        self.assertEqual(DEFAULT_STATE_BRANCH, result["branch"])
        self.assertEqual("false", git(shallow, "rev-parse", "--is-shallow-repository"))
        self.assertEqual(
            '{"source":"late-legacy-main"}\n',
            (shallow / legacy_path).read_text(encoding="utf-8"),
        )
        self.assertEqual(
            '{"source":"isolated-state"}\n',
            (shallow / state_path).read_text(encoding="utf-8"),
        )

    def test_rejects_application_code_and_parent_traversal(self):
        restore_state(self.clone, DEFAULT_STATE_BRANCH)
        with self.assertRaisesRegex(ValueError, "approved textbook data"):
            commit_state(self.clone, ["scripts/application.py"], "must reject", DEFAULT_STATE_BRANCH)
        with self.assertRaisesRegex(ValueError, "Unsafe"):
            commit_state(self.clone, ["data/textbook-extractions/../../README.md"], "must reject", DEFAULT_STATE_BRANCH)


if __name__ == "__main__":
    unittest.main()
