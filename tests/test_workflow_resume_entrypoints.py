from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = ROOT / ".github" / "workflows"


def read(name: str) -> str:
    return (WORKFLOWS / name).read_text(encoding="utf-8")


class WorkflowResumeEntrypointTests(unittest.TestCase):
    def test_visual_pipeline_accepts_reusable_workflow_inputs(self):
        workflow = read("textbook-visual-candidates-v3.yml")
        self.assertIn("workflow_call:", workflow)
        self.assertIn("github.event_name == 'workflow_call'", workflow)
        self.assertIn("resume_layers_4_5", workflow)
        self.assertIn("resume_preparation", workflow)

    def test_layers_1_to_3_launcher_locks_resume_preparation(self):
        wrapper = read("resume-textbook-visual-layers-1-3.yml")
        self.assertIn("uses: ./.github/workflows/textbook-visual-candidates-v3.yml", wrapper)
        self.assertIn("analysis_mode: resume_preparation", wrapper)
        self.assertNotIn("analysis_mode: ${{ inputs.analysis_mode }}", wrapper)

    def test_layers_4_to_5_launcher_locks_resume_mode(self):
        wrapper = read("resume-textbook-visual-layers-4-5.yml")
        self.assertIn("uses: ./.github/workflows/textbook-visual-candidates-v3.yml", wrapper)
        self.assertIn("analysis_mode: resume_layers_4_5", wrapper)
        self.assertIn('default: "1"', wrapper)
        self.assertIn("${{ inputs.vision_model }}", wrapper)
        self.assertNotIn("analysis_mode: ${{ inputs.analysis_mode }}", wrapper)

    def test_layers_6_to_8_launcher_locks_resume_and_keeps_preflight_acknowledgement(self):
        wrapper = read("resume-quantaedge-layers-6-8.yml")
        self.assertIn("uses: ./.github/workflows/quantaedge-layers-6-8.yml", wrapper)
        self.assertIn("mode: resume", wrapper)
        self.assertIn("${{ inputs.source_book_id }}", wrapper)
        self.assertIn("${{ inputs.generation_model }}", wrapper)
        self.assertIn("${{ inputs.max_model_calls }}", wrapper)
        self.assertIn("${{ inputs.allow_experimental_model }}", wrapper)
        self.assertIn('default: false', wrapper)

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
        self.assertIn('args+=(--require-existing-checkpoint)', preflight_step)
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
        self.assertIn("steps.resume_preflight.outputs.retryable_segments != '0'", approval_step)
        self.assertIn("ALLOW_EXPERIMENTAL_MODEL", approval_step)

    def test_layers_6_to_8_core_accepts_reusable_workflow_calls(self):
        workflow = read("quantaedge-layers-6-8.yml")
        self.assertIn("workflow_call:", workflow)
        self.assertIn("github.event_name == 'workflow_call'", workflow)
        self.assertIn("require-existing-checkpoint", workflow)
        self.assertIn("retryable_segments", workflow)


if __name__ == "__main__":
    unittest.main()
