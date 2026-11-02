import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts.git_checkpoint import push_checkpoint


class CheckpointPushTests(unittest.TestCase):
    def test_non_fast_forward_push_is_rebased_and_retried(self):
        with tempfile.TemporaryDirectory() as directory:
            rejected = subprocess.CompletedProcess(["git", "push"], 1, "", "! [rejected] HEAD -> work (fetch first)")
            succeeded = subprocess.CompletedProcess(["git", "push"], 0, "ok\n", "")
            with patch("scripts.git_checkpoint.subprocess.run", side_effect=[
                rejected,
                subprocess.CompletedProcess(["git", "fetch"], 0, "", ""),
                subprocess.CompletedProcess(["git", "rebase"], 0, "", ""),
                succeeded,
            ]) as run:
                push_checkpoint(Path(directory), "work")
            self.assertEqual(4, run.call_count)
            self.assertEqual(["git", "push", "origin", "HEAD:work"], run.call_args_list[0].args[0])
            self.assertEqual(["git", "fetch", "origin", "work"], run.call_args_list[1].args[0])
            self.assertEqual(["git", "rebase", "origin/work"], run.call_args_list[2].args[0])
            self.assertEqual(["git", "push", "origin", "HEAD:work"], run.call_args_list[3].args[0])

    def test_permanent_push_failure_is_not_retried(self):
        with tempfile.TemporaryDirectory() as directory:
            rejected = subprocess.CompletedProcess(["git", "push"], 1, "", "remote: Permission to repository denied")
            with patch("scripts.git_checkpoint.subprocess.run", return_value=rejected) as run:
                with self.assertRaises(subprocess.CalledProcessError):
                    push_checkpoint(Path(directory), "work")
            run.assert_called_once()

    def test_rebase_conflict_aborts_and_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            rejected = subprocess.CompletedProcess(["git", "push"], 1, "", "! [rejected] HEAD -> work (fetch first)")
            with patch("scripts.git_checkpoint.subprocess.run", side_effect=[
                rejected,
                subprocess.CompletedProcess(["git", "fetch"], 0, "", ""),
                subprocess.CalledProcessError(1, ["git", "rebase"]),
                subprocess.CompletedProcess(["git", "rebase", "--abort"], 0, "", ""),
            ]) as run:
                with self.assertRaisesRegex(RuntimeError, "overlapping changes"):
                    push_checkpoint(Path(directory), "work")
            self.assertEqual(4, run.call_count)


if __name__ == "__main__":
    unittest.main()
