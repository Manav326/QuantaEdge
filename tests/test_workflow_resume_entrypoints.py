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

    def test_layers_6_to_8_core_accepts_reusable_workflow_calls(self):
        workflow = read("quantaedge-layers-6-8.yml")
        self.assertIn("workflow_call:", workflow)
        self.assertIn("github.event_name == 'workflow_call'", workflow)
        self.assertIn("require-existing-checkpoint", workflow)
        self.assertIn("retryable_segments", workflow)


if __name__ == "__main__":
    unittest.main()
