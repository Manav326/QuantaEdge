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
        self.assertIn("steps.generation_preflight.outputs.retryable_segments != '0'", approval_step)
        self.assertIn("ALLOW_EXPERIMENTAL_MODEL", approval_step)

    def test_class6_benchmark_runs_only_after_layer5_recovery_and_fails_closed(self):
        workflow = read("benchmark-class6-curiosity-after-layer5.yml")
        self.assertIn("workflow_run:", workflow)
        self.assertIn('workflows: ["Resume Class 6 NCERT Curiosity Layers 1-5 evidence ledger"]', workflow)
        gate = workflow.index("- name: Require a real COMPLETE Class 6 Curiosity Layer 5 ledger")
        ollama = workflow.index("- name: Start loopback-only Ollama")
        benchmark = workflow.index("- name: Benchmark the same source-bound NCERT segments under the current contract")
        require_valid = workflow.index("- name: Require at least one contract-valid candidate before allowing generation")
        self.assertLess(gate, ollama)
        self.assertLess(ollama, benchmark)
        self.assertLess(benchmark, require_valid)
        self.assertIn('assert ledger.get("ledger_status") == "COMPLETE"', workflow)
        self.assertIn('"qwen2.5:1.5b,qwen2.5:3b,qwen3:4b"', workflow)
        self.assertIn('row.get("successful_samples", 0) >= 1', workflow)
        self.assertIn('row.get("lesson_contract_valid_rate", 0) > 0', workflow)
        self.assertIn('"production_model_selection": report.get("production_model_selection")', workflow)
        self.assertIn('"publication_allowed": report.get("publication_allowed")', workflow)

    def test_class6_generation_waits_for_successful_fresh_benchmark(self):
        workflow = read("quantaedge-layers-6-8-generate-once.yml")
        self.assertIn('workflows: ["Benchmark Class 6 Curiosity after Layer 5 recovery"]', workflow)
        self.assertIn("if: github.event.workflow_run.conclusion == 'success'", workflow)
        preflight = workflow.index("- name: Verify complete Layer 5 evidence and select an eligible experimental candidate")
        ollama = workflow.index("- name: Start loopback-only local Ollama")
        model_pull = workflow.index("- name: Pull only the contract-valid experimental candidate")
        generate = workflow.index("- name: Generate six review-only model calls with durable savepoints")
        self.assertLess(preflight, ollama)
        self.assertLess(ollama, model_pull)
        self.assertLess(model_pull, generate)
        self.assertIn('ledger.get("ledger_status") != "COMPLETE"', workflow)
        self.assertIn('row.get("lesson_contract_valid_rate", 0) > 0', workflow)
        self.assertIn('report.get("production_model") is not None', workflow)
        self.assertIn("--checkpoint-every 1", workflow)
        self.assertIn("--allow-experimental-model", workflow)
        self.assertNotIn(".github/triggers/generate-class6-curiosity.trigger", workflow)

    def test_layers_6_to_8_core_accepts_reusable_workflow_calls(self):
        workflow = read("quantaedge-layers-6-8.yml")
        self.assertIn("workflow_call:", workflow)
        self.assertIn("github.event_name == 'workflow_call'", workflow)
        self.assertIn("require-existing-checkpoint", workflow)
        self.assertIn("retryable_segments", workflow)


if __name__ == "__main__":
    unittest.main()
