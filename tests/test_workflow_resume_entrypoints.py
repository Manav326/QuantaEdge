from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = ROOT / ".github" / "workflows"

def read(name):
    path = WORKFLOWS / name
    if not path.is_file():
        raise AssertionError("Required workflow component is missing: " + name)
    return path.read_text(encoding="utf-8")

class WorkflowResumeEntrypointTests(unittest.TestCase):
    def test_one_user_facing_content_workflow_is_dispatchable(self):
        master=read("textbook-content-pipeline.yml")
        self.assertIn("workflow_dispatch:",master)
        self.assertNotIn("workflow_run:",master)
        self.assertNotIn("  schedule:",master)
        self.assertIn("default: progress_report",master)
        self.assertIn("repair_layers_4_5",master)
        self.assertIn("repair_source_cache_queue",master)
        self.assertIn("cancel-in-progress: false",master)

    def test_all_content_workers_are_callable_only(self):
        names=["textbook-visual-candidates-v3.yml","textbook-extraction-pilot.yml",
               "mirror-textbook-extraction-artifact.yml","textbook-cache.yml",
               "curriculum-pipeline-audit.yml","quantaedge-layers-6-8.yml"]
        for name in names:
            with self.subTest(name=name):
                value=read(name)
                self.assertIn("workflow_call:",value)
                self.assertNotIn("workflow_dispatch:",value)
                self.assertNotIn("\n  push:",value)
                self.assertNotIn("\n  schedule:",value)
                self.assertNotIn("workflow_run:",value)
                self.assertNotIn("git pull --rebase origin main",value)
                self.assertNotIn("git push origin HEAD:main",value)

    def test_redundant_scheduler_and_standalone_visual_workflow_are_removed(self):
        self.assertFalse((WORKFLOWS/"resume-textbook-layers-4-5-automatically.yml").exists())
        self.assertFalse((WORKFLOWS/"textbook-visual-analysis.yml").exists())
        state=(ROOT/"data/textbook-content-pipeline/resume-control.v1.json").read_text(encoding="utf-8")
        self.assertIn('"active": false',state)
        self.assertIn("retired",state.lower())

    def test_layer45_worker_is_pair_scoped_serial_and_has_no_scheduler_wake(self):
        worker=read("textbook-visual-candidates-v3.yml")
        self.assertIn("INPUT_BOOK_PAIR_ID",worker)
        self.assertIn("max-parallel: 1",worker)
        self.assertNotIn("wake-resume-coordinator",worker)
        self.assertNotIn("github.event_name == 'push'",worker)
        self.assertIn("textbook_pipeline_handoff.py",worker)
        self.assertIn("ref: " + "$" + "{{ github.ref }}",worker)

    def test_checkpoint_code_never_retargets_main(self):
        for name in ("quantaedge_textbook_pipeline.py","quantaedge_layers_6_8.py"):
            value=(ROOT/"scripts"/name).read_text(encoding="utf-8")
            self.assertNotIn('"pull","--rebase","origin","main"',value)
            self.assertNotIn('"HEAD:main"',value)

if __name__=="__main__":
    unittest.main()
