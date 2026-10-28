from pathlib import Path
import re
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

    def test_every_dispatch_task_is_wired_to_an_explicit_job(self):
        master = read("textbook-content-pipeline.yml")
        task_section = re.search(
            r"(?ms)^      task:\n.*?^        options:\s*\[([^\]]+)\]",
            master,
        )
        self.assertIsNotNone(task_section, "The visible task dropdown must be explicit.")
        options = [value.strip().strip("'\"") for value in task_section.group(1).split(",")]
        self.assertEqual([
            "refresh_source_cache", "repair_source_cache_queue", "extract_and_mirror",
            "prepare_layers_1_5", "repair_layers_4_5", "rebuild_corrections",
            "layers_6_8", "progress_report", "audit_contracts",
        ], options)

        jobs_section = master.split("\njobs:\n", 1)[1]
        parts = re.split(r"(?m)^  ([A-Za-z0-9_-]+):\s*$", jobs_section)
        job_blocks = {
            parts[index]: parts[index + 1]
            for index in range(1, len(parts) - 1, 2)
        }
        expected_routes = {
            "refresh_source_cache": ("cache_textbooks",),
            "repair_source_cache_queue": ("cache_textbooks",),
            "extract_and_mirror": ("extract_textbooks", "mirror_extraction"),
            "prepare_layers_1_5": ("prepare_plan", "prepare_validate"),
            "repair_layers_4_5": ("prepare_plan", "prepare_validate"),
            "rebuild_corrections": ("rebuild-corrections",),
            "layers_6_8": ("layers68_validate", "layers68_execute"),
            "progress_report": ("report-progress",),
            "audit_contracts": ("audit_offline",),
        }
        self.assertEqual(set(options), set(expected_routes))
        for task, expected_jobs in expected_routes.items():
            for job in expected_jobs:
                with self.subTest(task=task, job=job):
                    self.assertIn(job, job_blocks, f"{task} has a dangling job route.")
                    self.assertRegex(
                        job_blocks[job],
                        rf"inputs\.task\s*==\s*'{re.escape(task)}'",
                        f"{job} must be gated by task={task}.",
                    )

        # Expensive/live registry work is an opt-in subtask of the audit only.
        self.assertIn("inputs.task == 'audit_contracts' && inputs.pull_live_registry == true",
                      job_blocks["audit_live_registry"])
        self.assertIn("needs: [validate, prepare_plan, prepare_validate]",
                      job_blocks["prepare_books"])
        self.assertIn("fromJSON(needs.prepare_plan.outputs.matrix)",
                      job_blocks["prepare_books"])
        self.assertIn("max-parallel: 1", job_blocks["prepare_books"])
        self.assertIn("default: prepare_only", master)
        self.assertIn("default: progress_report", master)
        self.assertNotIn("uses: ./.github/workflows/", master)
        self.assertNotRegex(master, r"(?m)^\s*(push|schedule|workflow_run):")

    def test_every_choice_input_has_the_expected_supported_values(self):
        master = read("textbook-content-pipeline.yml")

        def choices_for(field):
            match = re.search(
                rf"(?ms)^      {re.escape(field)}:\\n(.*?)(?=^      [A-Za-z0-9_-]+:\\s*$|^permissions:)",
                master,
            )
            self.assertIsNotNone(match, f"Missing workflow_dispatch input: {field}")
            options = re.search(r"(?m)^        options:\\s*\\[([^\\]]+)\\]\\s*$", match.group(1))
            self.assertIsNotNone(options, f"Input {field} must use an explicit finite choice list")
            return [item.strip().strip("'\\\"") for item in options.group(1).split(",")]

        expected = {
            "class_no": ["6", "7", "8", "all"],
            "book_pair_id": [
                "all", "class6-science-bilingual", "class6-maths-bilingual",
                "class7-science-bilingual", "class7-maths-part1-bilingual",
                "class7-maths-part2-bilingual", "class8-science-bilingual",
                "class8-maths-part1-bilingual", "class8-maths-part2-bilingual",
            ],
            "analysis_mode": ["prepare_only", "analyze_all", "resume_preparation", "resume_layers_4_5"],
            "checkpoint_batch_size": ["5", "10"],
            "cache_language": ["both", "hindi", "english"],
            "layer68_mode": ["benchmark", "run", "resume", "refresh_benchmark"],
            "checkpoint_every": ["1", "2", "3", "5"],
        }
        for field, values in expected.items():
            with self.subTest(field=field):
                self.assertEqual(values, choices_for(field))

        self.assertIn("default: progress_report", master)
        self.assertIn("default: prepare_only", master)
        self.assertNotIn("name: Checkout main", master)
        self.assertNotRegex(master, r"(?m)^\\s*(push|schedule|workflow_run):")
        self.assertNotIn("uses: ./.github/workflows/", master)

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
