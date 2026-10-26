from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = ROOT / ".github" / "workflows"


def read(name: str) -> str:
    return (WORKFLOWS / name).read_text(encoding="utf-8")


class WorkflowResumeEntrypointTests(unittest.TestCase):
    def test_marked_push_can_reextract_one_class_without_rebuilding_the_other_class(self):
        workflow = read("textbook-extraction-pilot.yml")
        self.assertIn("[reextract-textbook-class-7]", workflow)
        self.assertIn("[reextract-textbook-class-8]", workflow)
        self.assertIn(
            "contains(github.event.head_commit.message || '', '[reextract-textbook-class-7]') && fromJSON('[7]')",
            workflow,
        )
        self.assertIn(
            "contains(github.event.head_commit.message || '', '[reextract-textbook-class-8]') && fromJSON('[8]')",
            workflow,
        )


    def test_targeted_analyze_all_markers_rebuild_stale_books_per_class(self):
        workflow = read("textbook-visual-candidates-v3.yml")
        planner = (ROOT / "scripts" / "plan_textbook_layer45_matrix.py").read_text(encoding="utf-8")
        for grade in ("7", "8"):
            marker = f"[analyze-all-visual-layers-4-5-class-{grade}]"
            self.assertIn(marker, workflow)
            self.assertIn(marker, planner)
        self.assertIn("matrix: ${{ fromJSON(needs.plan.outputs.matrix) }}", workflow)
        self.assertIn("&& 'analyze_all') || (github.event_name == 'push'", workflow)
        self.assertIn("cancel-in-progress:", workflow)
        coordinator = read("resume-textbook-layers-4-5-automatically.yml")
        self.assertIn('"Mirror verified textbook extraction artifact to main"', coordinator)
        self.assertIn("TRIGGER_CONCLUSION", coordinator)
        self.assertIn("textbook-extraction-pilot.yml/runs", coordinator)
        self.assertIn("active source extraction or Layer 4–5 work", coordinator)

    def test_class8_rebuild_uses_fresh_verified_source_and_keeps_publication_disabled(self):
        workflow = read("textbook-visual-candidates-v3.yml")
        planner = (ROOT / "scripts" / "plan_textbook_layer45_matrix.py").read_text(encoding="utf-8")
        marker = "[analyze-all-visual-layers-4-5-class-8]"
        self.assertIn(marker, workflow)
        self.assertIn(marker, planner)
        self.assertIn("&& 'analyze_all') || (github.event_name == 'push'", workflow)
        self.assertIn("source PDF SHA-256 differs", workflow)
        self.assertIn('"publication_allowed": False', workflow)

    def test_class8_targeted_rebuild_replaces_only_the_class8_resume_lane(self):
        workflow = read("textbook-visual-candidates-v3.yml")
        planner = (ROOT / "scripts" / "plan_textbook_layer45_matrix.py").read_text(encoding="utf-8")
        self.assertIn("&& '8')", workflow)
        self.assertIn("[analyze-all-visual-layers-4-5-class-8]", workflow)
        self.assertIn("[analyze-all-visual-layers-4-5-class-8]", planner)
        self.assertIn("cancel-in-progress:", workflow)
        self.assertIn("publication_allowed", workflow)
        self.assertIn("source PDF SHA-256 differs", workflow)
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
        planner = (ROOT / "scripts" / "plan_textbook_layer45_matrix.py").read_text(encoding="utf-8")
        for marker in (
            "[resume-visual-layers-4-5-classes-7-8]",
            "[resume-visual-layers-4-5-classes-6-8]",
            "[resume-visual-layers-4-5-class-6]",
            "[resume-visual-layers-4-5-class-7]",
            "[resume-visual-layers-4-5-class-8]",
        ):
            self.assertIn(marker, workflow)
            self.assertIn(marker, planner)
        self.assertIn("matrix: ${{ fromJSON(needs.plan.outputs.matrix) }}", workflow)
        self.assertIn("max-parallel: 16", workflow)
        self.assertIn("inputs.analysis_mode || (github.event_name == 'push'", workflow)
        self.assertIn("VISION_MODEL: ${{ inputs.vision_model || 'qwen2.5vl:3b' }}", workflow)
        self.assertIn("CHECKPOINT_BATCH_SIZE: ${{ inputs.checkpoint_batch_size || '10' }}", workflow)
        self.assertIn("env.ANALYSIS_MODE == 'resume_layers_4_5'", workflow)
        self.assertIn("'classes-7-8-resume'", workflow)
        self.assertIn("BOOK_ID: ${{ matrix.book_id || '' }}", workflow)

    def test_marked_push_can_resume_class7_and_class8_independently(self):
        workflow = read("textbook-visual-candidates-v3.yml")
        planner = (ROOT / "scripts" / "plan_textbook_layer45_matrix.py").read_text(encoding="utf-8")
        for marker in (
            "[resume-visual-layers-4-5-class-7]",
            "[resume-visual-layers-4-5-class-8]",
            "[resume-visual-layers-4-5-classes-7-8]",
        ):
            self.assertIn(marker, workflow)
            self.assertIn(marker, planner)
        self.assertIn("matrix: ${{ fromJSON(needs.plan.outputs.matrix) }}", workflow)
        self.assertIn("max-parallel: 16", workflow)
        self.assertIn("&& '7')", workflow)
        self.assertIn("&& '8')", workflow)

    def test_cancelled_class6_to8_run_is_resumed_by_separate_coordinator(self):
        workflow = read("textbook-visual-candidates-v3.yml")
        planner = (ROOT / "scripts" / "plan_textbook_layer45_matrix.py").read_text(encoding="utf-8")
        self.assertNotIn("workflow_run:", workflow)
        self.assertIn("data/textbook-content-pipeline/resume-control.v1.json", workflow)
        self.assertIn("[resume-visual-layers-4-5-classes-6-8]", workflow)
        self.assertIn("[resume-visual-layers-4-5-classes-6-8]", planner)
        self.assertIn("matrix: ${{ fromJSON(needs.plan.outputs.matrix) }}", workflow)
        self.assertIn("max-parallel: 16", workflow)
        self.assertIn("Wake checkpoint coordinator after each book-worker round", workflow)
        coordinator = read("resume-textbook-layers-4-5-automatically.yml")
        self.assertIn("push:", coordinator)
        self.assertIn('".github/workflows/resume-textbook-layers-4-5-automatically.yml"', coordinator)
        self.assertIn("schedule:", coordinator)
        self.assertIn('cron: "*/5 * * * *"', coordinator)
        self.assertIn("workflow_run:", coordinator)
        self.assertIn("types: [completed]", coordinator)
        self.assertIn("branches: [main]", coordinator)
        self.assertIn("actions: write", coordinator)
        self.assertIn("/actions/workflows/textbook-visual-candidates-v3.yml/dispatches", coordinator)
        self.assertIn("resume_layers_4_5", coordinator)
        self.assertIn("for grade in (6,7,8)", coordinator)
        self.assertIn('p=root/f"class-{grade}.v1.json"', coordinator)
        self.assertIn("active_classes", coordinator)
        self.assertIn("already has active source extraction or Layer 4–5 work", coordinator)
        self.assertIn('else job.get("started_at")', coordinator)
        self.assertIn("checkpoint_baseline=", coordinator)

    def test_layer45_resume_uses_finite_request_timeout_and_replaces_only_matching_class_runner(self):
        workflow = read("textbook-visual-candidates-v3.yml")
        pipeline = (ROOT / "scripts" / "quantaedge_textbook_pipeline.py").read_text(encoding="utf-8")
        self.assertIn("--request-timeout 300", workflow)
        self.assertIn('q.add_argument("--request-timeout",type=int,default=300', pipeline)
        self.assertIn('"--timeout",str(args.request_timeout)', pipeline)
        self.assertIn("max-parallel: 16", workflow)
        self.assertIn("BOOK_ID: ${{ matrix.book_id || '' }}", workflow)
        coordinator = read("resume-textbook-layers-4-5-automatically.yml")
        self.assertIn("age >= 1800", coordinator)
        self.assertIn("/actions/runs/$run_id/cancel", coordinator)
        self.assertIn("stale runner cancellation requested", coordinator.lower())
        self.assertIn("baseline=latest or started", coordinator)
        self.assertIn("checkpoint(content): Layer 4-5", coordinator)
        self.assertIn("all_processing_steps_stale", coordinator)
        self.assertIn("source_ledgers", coordinator)
        self.assertIn("ledger_sha256", coordinator)
        self.assertIn('summary.get("unresolved_count")!=0', coordinator)
    def test_layers_4_5_resume_preflight_runs_before_inventory_rebuild(self):
        workflow = read("textbook-visual-candidates-v3.yml")
        restore = workflow[workflow.index("- name: Restore each matching Layer 1 inventory bundle or build only missing inventories"):workflow.index("- name: Restore or build immutable per-book candidate checkpoints")]
        self.assertIn("from scripts.prepare_visual_candidate_batch import extract_archive, missing_artifact, validate_layer1_bundle", restore)
        self.assertIn('wrapper=f"{slug}-layer1"', restore)
        self.assertIn("validate_layer1_bundle(layer1_root,source,work,fingerprint)", restore)
        self.assertIn("tag_fingerprint", restore)
        self.assertIn("inventory bundle mixes wrapped and root-level paths", restore)
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

    def test_benchmark_checks_layer5_before_starting_or_downloading_models(self):
        workflow = read("quantaedge-layers-6-8.yml")
        preflight = workflow.index("- name: Preflight benchmark source and Layer 5 before model download")
        ollama = workflow.index("- name: Start loopback-only Ollama service")
        pull = workflow.index("- name: Pull only candidate models for this invocation")
        self.assertLess(preflight, ollama)
        self.assertLess(preflight, pull)
        step = workflow[preflight:workflow.index("- name: Preflight generation checkpoint before model download", preflight)]
        self.assertIn("if: inputs.mode == 'benchmark' || inputs.mode == 'refresh_benchmark'", step)
        self.assertIn("python scripts/quantaedge_layers_6_8.py preflight", step)
        self.assertIn("--source-book-id ncert-c6-english-fecu1", step)

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


    def test_layer45_run_budget_yields_safely_and_is_resumable(self):
        workflow = read("textbook-visual-candidates-v3.yml")
        pipeline = (ROOT / "scripts" / "quantaedge_textbook_pipeline.py").read_text(encoding="utf-8")
        self.assertIn("--max-batches-per-run", pipeline)
        self.assertIn("RUN_BUDGET_REACHED", pipeline)
        self.assertIn("YIELDED_FOR_RESUME", pipeline)
        self.assertIn("if processed > 0: total_batches += 1", pipeline)
        self.assertIn("id: layer45_run", workflow)
        self.assertIn("MAX_BATCHES_PER_RUN: 12", workflow)
        self.assertIn('--max-batches-per-run "$MAX_BATCHES_PER_RUN"', workflow)
        self.assertIn('result" -eq 10', workflow)
        self.assertIn("needs: [plan, prepare]", workflow)
    def test_layer45_no_progress_guard_and_inventory_source_identity_recovery_exist(self):
        analyzer = (ROOT / "scripts" / "analyze_visual_inventory_with_ollama.py").read_text(encoding="utf-8")
        pipeline = (ROOT / "scripts" / "quantaedge_textbook_pipeline.py").read_text(encoding="utf-8")
        self.assertIn("SOURCE_IDENTITY_NORMALIZED", analyzer)
        self.assertIn("assets_processed_ids", analyzer)
        self.assertIn("repeated prior successful asset IDs without increasing analyzed/failed counts", pipeline)

    def test_layer45_cancel_only_marker_skips_work_and_class_resume_markers_cancel_stale_runs(self):
        workflow = read("textbook-visual-candidates-v3.yml")
        self.assertIn("[cancel-layer45-matrix]", workflow)
        self.assertIn("github.event.head_commit.message || '', '[resume-visual-layers-4-5-class-7]'", workflow)
        self.assertIn("github.event.head_commit.message || '', '[resume-visual-layers-4-5-class-8]'", workflow)
        self.assertIn("&& !(github.event_name == 'push' && contains(github.event.head_commit.message || '', '[cancel-layer45-matrix]'))", workflow)


if __name__ == "__main__":
    unittest.main()
