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

    def test_there_is_only_one_textbook_workflow_and_no_local_workflow_calls(self):
        master = read("textbook-content-pipeline.yml")
        workflow_names = {
            path.name for path in WORKFLOWS.iterdir()
            if path.is_file() and (
                "textbook" in path.name.lower()
                or path.name in {"curriculum-pipeline-audit.yml", "quantaedge-layers-6-8.yml"}
            )
        }
        self.assertEqual({"textbook-content-pipeline.yml"}, workflow_names)
        self.assertNotIn("uses: ./.github/workflows/", master)
        self.assertIn("cache_textbooks:", master)
        self.assertIn("extract_textbooks:", master)
        self.assertIn("mirror_extraction:", master)
        self.assertIn("prepare_books:", master)
        self.assertIn("layers68_execute:", master)
        self.assertIn("audit_offline:", master)

    def test_redundant_scheduler_and_standalone_visual_workflow_are_removed(self):
        self.assertFalse((WORKFLOWS/"resume-textbook-layers-4-5-automatically.yml").exists())
        self.assertFalse((WORKFLOWS/"textbook-visual-analysis.yml").exists())
        state=(ROOT/"data/textbook-content-pipeline/resume-control.v1.json").read_text(encoding="utf-8")
        self.assertIn('"active": false',state)
        self.assertIn("retired",state.lower())

    def test_layer45_matrix_is_pair_scoped_and_serial(self):
        master=read("textbook-content-pipeline.yml")
        self.assertIn("INPUT_BOOK_PAIR_ID",master)
        self.assertIn("max-parallel: 1",master)
        self.assertIn("textbook_pipeline_handoff.py",master)
        self.assertIn("ref: " + "$" + "{{ github.ref }}",master)
        self.assertNotIn("wake-resume-coordinator",master)

    def test_checkpoint_code_never_retargets_main(self):
        for name in ("quantaedge_textbook_pipeline.py","quantaedge_layers_6_8.py"):
            value=(ROOT/"scripts"/name).read_text(encoding="utf-8")
            self.assertNotIn('"pull","--rebase","origin","main"',value)
            self.assertNotIn('"HEAD:main"',value)

if __name__=="__main__":
    unittest.main()
