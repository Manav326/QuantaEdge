from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = ROOT / ".github" / "workflows"


def read(name: str) -> str:
    return (WORKFLOWS / name).read_text(encoding="utf-8")


class WorkflowResumeEntrypointTests(unittest.TestCase):
    def test_layers_1_to_5_has_all_execution_and_resume_modes_in_one_entrypoint(self):
        workflow = read("textbook-visual-candidates-v3.yml")
        self.assertIn("workflow_dispatch:", workflow)
        self.assertIn("workflow_call:", workflow)
        self.assertIn('options: ["prepare_only", "analyze_all", "resume_preparation", "resume_layers_4_5"]', workflow)
        self.assertIn("github.event_name == 'workflow_call'", workflow)
        self.assertIn("resume_layers_4_5", workflow)
        self.assertIn("resume_preparation", workflow)

    def test_marked_push_runs_resume_mode_for_both_classes_in_the_core_workflow(self):
        workflow = read("textbook-visual-candidates-v3.yml")
        marker = "[resume-visual-layers-4-5-classes-7-8]"
        self.assertIn(marker, workflow)
        self.assertIn("matrix:", workflow)
        self.assertIn("max-parallel: 1", workflow)
        self.assertIn("fromJSON(contains(github.event.head_commit.message", workflow)
        self.assertIn("inputs.analysis_mode || (github.event_name == 'push'", workflow)
        self.assertIn("inputs.vision_model || 'qwen2.5vl:3b'", workflow)
        self.assertNotIn("VISION_MODEL: ${{ inputs.vision_model }}", workflow)
        self.assertIn("contains(github.event.head_commit.message || '', '[resume-visual-layers-4-5-classes-7-8]') && '1'", workflow)
        self.assertIn("env.ANALYSIS_MODE == 'resume_layers_4_5'", workflow)
        self.assertIn("'classes-7-8-resume'", workflow)

    def test_marked_push_can_resume_class7_and_class8_independently(self):
        workflow = read("textbook-visual-candidates-v3.yml")
        marker7 = "[resume-visual-layers-4-5-class-7]"
        marker8 = "[resume-visual-layers-4-5-class-8]"
        self.assertIn(marker7, workflow)
        self.assertIn(marker8, workflow)
        matrix_line = next(line for line in workflow.splitlines() if line.strip().startswith("class_no:"))
        self.assertIn('["7"]', matrix_line)
        self.assertIn('["8"]', matrix_line)
        self.assertIn("'class-7-resume'", workflow)
        self.assertIn("'class-8-resume'", workflow)
        self.assertIn("max-parallel: 1", workflow)
    def test_layers_4_5_resume_preflight_runs_before_inventory_rebuild(self):
        workflow = read("textbook-visual-candidates-v3.yml")
        preflight = workflow.index("- name: Preflight and restore matching Layer 4 checkpoints before loading the vision model")
        inventory = workflow.index("- name: Restore each matching Layer 1 inventory bundle or build only missing inventories")
        self.assertLess(preflight, inventory, "Do not rebuild missing Layer 1 inventory before validating saved Layer 4 checkpoints.")
        preflight_step = workflow[preflight:inventory]
        self.assertIn("VISION_MODEL: ${{ inputs.vision_model || 'qwen2.5vl:3b' }}", preflight_step)
        self.assertIn("resume_preflight", preflight_step)
        self.assertIn("prior_analysis_started", preflight_step)
        self.assertIn("resume stopped instead of silently redoing inference", preflight_step)
        self.assertIn("from scripts.prepare_visual_candidate_batch import missing_artifact", preflight_step)
        self.assertIn('not_found = missing_artifact(detail, f"{package}:{tag}")', preflight_step)
        self.assertIn("missing_artifact(detail, f", workflow)

    def test_layers_6_to_8_has_all_modes_in_one_entrypoint(self):
        workflow = read("quantaedge-layers-6-8.yml")
        self.assertIn("workflow_dispatch:", workflow)
        self.assertIn("workflow_call:", workflow)
        self.assertIn('options: ["benchmark", "run", "resume", "refresh_benchmark"]', workflow)
        self.assertIn("github.event_name == 'workflow_call'", workflow)

    def test_refresh_benchmark_is_in_core_workflow_and_does_not_start_ollama(self):
        workflow = read("quantaedge-layers-6-8.yml")
        refresh = workflow.index("- name: Refresh saved benchmark samples without model inference")
        generation = workflow.index("- name: Run bounded single-book draft generation")
        ollama = workflow.index("- name: Start loopback-only Ollama service")
        self.assertLess(refresh, generation)
        refresh_step = workflow[refresh:generation]
        self.assertIn("if: inputs.mode == 'refresh_benchmark'", refresh_step)
        self.assertIn("python scripts/quantaedge_layers_6_8.py refresh-benchmark", refresh_step)
        self.assertIn("--commit-checkpoints", refresh_step)
        self.assertIn("Guard fixed Class 6 benchmark and refresh modes", workflow)
        self.assertIn("ncert-c6-english-fecu1", workflow)
        ollama_step = workflow[ollama:workflow.index("- name: Pull only candidate models", ollama)]
        self.assertIn("inputs.mode == 'benchmark'", ollama_step)
        self.assertNotIn("refresh_benchmark", ollama_step)

    def test_layers_6_to_8_preflights_run_and_resume_before_model_download(self):
        workflow = read("quantaedge-layers-6-8.yml")
        preflight = workflow.index("- name: Preflight generation checkpoint before model download")
        output_only = workflow.index("- name: Reconcile saved Layers 6–8 outputs without model inference")
        ollama_start = workflow.index("- name: Start loopback-only Ollama service")
        model_pull = workflow.index("- name: Pull only candidate models for this invocation")
        self.assertLess(preflight, output_only)
        self.assertLess(preflight, ollama_start)
        self.assertLess(preflight, model_pull)
        preflight_step = workflow[preflight:output_only]
        self.assertIn("if: inputs.mode == 'run' || inputs.mode == 'resume'", preflight_step)
        self.assertIn('if [[ "$MODE" == "resume" ]]; then', preflight_step)
        self.assertIn("args+=(--require-existing-checkpoint)", preflight_step)
        self.assertIn('{"MATCHED_CHECKPOINT","NOT_STARTED"}', preflight_step)
        self.assertIn("preflight_state=", preflight_step)
        self.assertIn("steps.generation_preflight.outputs.preflight_state == 'MATCHED_CHECKPOINT'", workflow)

    def test_layers_6_to_8_checks_draft_acknowledgement_before_model_startup(self):
        workflow = read("quantaedge-layers-6-8.yml")
        approval = workflow.index("- name: Require explicit experimental-draft approval before model startup")
        ollama_start = workflow.index("- name: Start loopback-only Ollama service")
        model_pull = workflow.index("- name: Pull only candidate models for this invocation")
        generation = workflow.index("- name: Run bounded single-book draft generation")
        self.assertLess(approval, ollama_start)
        self.assertLess(approval, model_pull)
        self.assertLess(approval, generation)
        approval_step = workflow[approval:ollama_start]
        self.assertIn("inputs.mode == 'run'", approval_step)
        self.assertIn("steps.generation_preflight.outputs.retryable_segments != '0'", approval_step)
        self.assertIn("ALLOW_EXPERIMENTAL_MODEL", approval_step)

    def test_retired_duplicate_entrypoints_are_absent(self):
        retired = [
            "agent-4-validation.yml",
            "android-build.yml",
            "commit-class6-extraction-artifact.yml",
            "dispatch-visual-layers-4-5-classes-7-8.yml",
            "benchmark-class6-curiosity-after-layer5.yml",
            "quantaedge-layers-1-5-resume.yml",
            "quantaedge-layers-6-8-generate-once.yml",
            "quantaedge-layers-6-8-refresh-benchmark.yml",
            "resume-quantaedge-layers-6-8.yml",
            "resume-textbook-visual-layers-1-3.yml",
            "resume-textbook-visual-layers-4-5.yml",
        ]
        for name in retired:
            with self.subTest(name=name):
                self.assertFalse((WORKFLOWS / name).exists(), f"{name} is a retired duplicate entrypoint")
        self.assertFalse((ROOT / ".github" / "triggers" / "layers1-5-class6-curiosity.trigger").exists())
        self.assertTrue((WORKFLOWS / "mirror-textbook-extraction-artifact.yml").is_file())

    def test_layers_6_to_8_core_accepts_reusable_workflow_calls(self):
        workflow = read("quantaedge-layers-6-8.yml")
        self.assertIn("workflow_call:", workflow)
        self.assertIn("github.event_name == 'workflow_call'", workflow)
        self.assertIn("require-existing-checkpoint", workflow)
        self.assertIn("retryable_segments", workflow)


if __name__ == "__main__":
    unittest.main()
