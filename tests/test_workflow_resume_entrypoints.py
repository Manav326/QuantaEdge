from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = ROOT / ".github" / "workflows"


def read(name: str) -> str:
    return (WORKFLOWS / name).read_text(encoding="utf-8")


class WorkflowResumeEntrypointTests(unittest.TestCase):
    def test_layers_1_to_5_are_dispatch_only_and_state_is_not_written_to_main(self):
        workflow = read("textbook-visual-candidates-v3.yml")
        self.assertIn("workflow_dispatch:", workflow)
        self.assertIn("workflow_call:", workflow)
        self.assertNotIn("\n  push:\n", workflow)
        self.assertNotIn("\n  schedule:\n", workflow)
        self.assertNotIn("\n  workflow_run:\n", workflow)
        self.assertIn("group: qe-textbook-visual-candidates-v3-global", workflow)
        self.assertIn("cancel-in-progress: false", workflow)
        self.assertIn("automation/textbook-pipeline-state", workflow)
        self.assertIn("persist_textbook_pipeline_state.py restore", workflow)
        self.assertIn("persist_textbook_pipeline_state.py commit", workflow)
        self.assertNotIn("git push origin HEAD:main", workflow)

    def test_worker_consumes_planner_pair_fields_and_never_uses_matrix_book_id(self):
        workflow = read("textbook-visual-candidates-v3.yml")
        planner = (ROOT / "scripts" / "plan_textbook_layer45_matrix.py").read_text(encoding="utf-8")
        selector = (ROOT / "scripts" / "select_textbook_visual_pair.py").read_text(encoding="utf-8")
        self.assertIn("BOOK_IDS_CSV: ${{ matrix.book_ids_csv || '' }}", workflow)
        self.assertIn("BOOK_SLUGS_CSV: ${{ matrix.source_slugs_csv || '' }}", workflow)
        self.assertIn("BOOK_PAIR_ID: ${{ matrix.book_pair_id || '' }}", workflow)
        self.assertIn('--book-ids-csv "$BOOK_IDS_CSV"', workflow)
        self.assertIn('--source-slugs-csv "$BOOK_SLUGS_CSV"', workflow)
        self.assertIn('--book-pair-id "$BOOK_PAIR_ID"', workflow)
        self.assertNotIn("matrix.book_id ||", workflow)
        self.assertNotIn("${{ matrix.book_id }}", workflow)
        self.assertNotIn("BOOK_ID:", workflow)
        self.assertIn('"book_ids_csv"', planner)
        self.assertIn('"source_slugs_csv"', planner)
        self.assertIn("select_bilingual_pair", selector)
        self.assertIn("exactly one assigned book_id", selector)
        self.assertIn('{"english", "hindi"}', selector)

    def test_only_resume_work_is_pair_sharded_and_each_pair_is_source_bound(self):
        planner = (ROOT / "scripts" / "plan_textbook_layer45_matrix.py").read_text(encoding="utf-8")
        tests = (ROOT / "tests" / "test_plan_textbook_layer45_matrix.py").read_text(encoding="utf-8")
        self.assertIn('pair_sharded = analysis_mode == "resume_layers_4_5"', planner)
        self.assertIn("pair_complete(root, pair)", planner)
        self.assertIn("source_book_id", planner)
        self.assertIn("source_pdf_sha256", planner)
        self.assertIn("test_all_class_resume_returns_eight_exact_bilingual_pairs", tests)
        self.assertIn("test_completed_pair_is_skipped_only_when_both_language_ledgers_are_valid", tests)
        self.assertIn("test_all_class_analysis_and_preparation_schedule_one_class_job_per_class", tests)

    def test_failed_or_empty_resume_does_not_start_duplicate_or_cancel_other_workers(self):
        workflow = read("textbook-visual-candidates-v3.yml")
        coordinator = read("resume-textbook-layers-4-5-automatically.yml")
        self.assertIn("needs.prepare.result == 'success' || needs.prepare.result == 'skipped'", workflow)
        self.assertIn("Wake checkpoint coordinator after an explicit processing round", workflow)
        self.assertIn("steps.plan.outputs.selected_pending_pairs != '0'", coordinator)
        self.assertIn("active_worker_runs == '0'", coordinator)
        self.assertIn("No duplicate run will be dispatched; no run was cancelled.", coordinator)
        self.assertNotIn("/actions/runs/$run_id/cancel", coordinator)
        self.assertNotIn("\n  push:\n", coordinator)
        self.assertNotIn("\n  schedule:\n", coordinator)
        self.assertNotIn("\n  workflow_run:\n", coordinator)
        self.assertIn("workflow_dispatch:", coordinator)
        self.assertIn('options: ["all", "6", "7", "8"]', coordinator)
        self.assertIn("all_pending_pairs", coordinator)
        self.assertIn("selected_book_pair_ids", coordinator)
        self.assertIn("/actions/workflows/textbook-visual-candidates-v3.yml/dispatches", coordinator)

    def test_bilingual_resume_uses_ten_asset_savepoints_and_fair_book_budget(self):
        workflow = read("textbook-visual-candidates-v3.yml")
        pipeline = (ROOT / "scripts" / "quantaedge_textbook_pipeline.py").read_text(encoding="utf-8")
        self.assertIn('default: "10"', workflow)
        self.assertIn("CHECKPOINT_BATCH_SIZE: ${{ inputs.checkpoint_batch_size || '10' }}", workflow)
        self.assertIn('max-batches-per-run "$MAX_BATCHES_PER_RUN"', workflow)
        self.assertIn("book_batch_budget=", pipeline)
        self.assertIn("BOOK_BUDGET_REACHED", pipeline)
        self.assertIn("BATCH_CHECKPOINTED", pipeline)
        self.assertIn("RUN_BUDGET_REACHED", pipeline)
        self.assertIn('if raw.is_file():cmd.append("--resume")', pipeline)
        self.assertIn('"--num-ctx",str(args.num_ctx)', pipeline)
        self.assertIn("--num-ctx 8192", workflow)
        self.assertIn("default=8192", (ROOT / "scripts" / "analyze_visual_inventory_with_ollama.py").read_text(encoding="utf-8"))

    def test_visual_catalog_shared_index_is_serialized_for_initial_builds(self):
        workflow = read("textbook-visual-candidates-v3.yml")
        self.assertIn("max-parallel: ${{ inputs.analysis_mode == 'resume_layers_4_5' && 4 || 1 }}", workflow)
        self.assertIn("update_visual_catalog_index.py", workflow)
        self.assertIn("data/textbook-visual-candidates-v3/index.v1.json", workflow)

    def test_extraction_and_mirror_are_explicit_and_write_only_to_state_branch(self):
        extraction = read("textbook-extraction-pilot.yml")
        mirror = read("mirror-textbook-extraction-artifact.yml")
        self.assertIn("workflow_dispatch:", extraction)
        self.assertNotIn("\n  push:\n", extraction)
        self.assertNotIn("\n  workflow_run:\n", extraction)
        self.assertIn("workflow_dispatch:", mirror)
        self.assertNotIn("\n  workflow_run:\n", mirror)
        self.assertIn("automation/textbook-pipeline-state", mirror)
        self.assertIn("persist_textbook_pipeline_state.py commit", mirror)
        self.assertNotIn("git push origin HEAD:main", mirror)
        self.assertIn('== "completed"', mirror)
        self.assertIn('== "success"', mirror)

    def test_remaining_textbook_writers_are_dispatch_only_and_use_isolated_state(self):
        specialist = read("textbook-visual-analysis.yml")
        offline_dataset = read("ai-training-offline-dataset.yml")
        cache = read("textbook-cache.yml")
        ai_script = (ROOT / "scripts" / "ai_training_dataset.py").read_text(encoding="utf-8")

        self.assertIn("workflow_dispatch:", specialist)
        self.assertNotIn("\n  push:\n", specialist)
        self.assertIn("group: qe-textbook-visual-candidates-v3-global", specialist)
        self.assertIn("persist_textbook_pipeline_state.py restore", specialist)
        self.assertIn("persist_textbook_pipeline_state.py commit", specialist)
        self.assertNotIn("git push origin HEAD:main", specialist)

        self.assertIn("workflow_dispatch:", offline_dataset)
        self.assertNotIn("\n  push:\n", offline_dataset)
        self.assertIn("automation/textbook-pipeline-state", offline_dataset)
        self.assertIn("persist_textbook_pipeline_state.py commit", offline_dataset)
        self.assertNotIn("git push origin HEAD:main", offline_dataset)
        self.assertIn("persist_textbook_pipeline_state.py", ai_script)
        self.assertNotIn('"HEAD:main"', ai_script)
        self.assertIn("data/ai-training", ai_script)

        self.assertIn("workflow_dispatch:", cache)
        self.assertNotIn("\n  push:\n", cache)
        self.assertIn("repair_queue", cache)
        self.assertIn("INPUT_REPAIR_QUEUE", cache)

    def test_layers_6_to_8_are_manual_and_checkpoint_to_the_isolated_branch(self):
        workflow = read("quantaedge-layers-6-8.yml")
        script = (ROOT / "scripts" / "quantaedge_layers_6_8.py").read_text(encoding="utf-8")
        self.assertIn("workflow_dispatch:", workflow)
        self.assertIn("workflow_call:", workflow)
        self.assertNotIn("\n  push:\n", workflow)
        self.assertIn("persist_textbook_pipeline_state.py restore", workflow)
        self.assertIn('helper=repo/"scripts"/"persist_textbook_pipeline_state.py"', script)
        self.assertIn('str(helper),"commit"', script)
        self.assertNotIn("git push origin HEAD:main", script)
        self.assertIn('options: ["benchmark", "run", "resume", "refresh_benchmark"]', workflow)
        self.assertIn("require-existing-checkpoint", workflow)

    def test_specialist_visual_workflow_uses_bounded_figure_only_inference(self):
        workflow = read("textbook-visual-analysis.yml")
        self.assertIn("batch_size=10", workflow)
        self.assertIn("--max-assets", workflow)
        self.assertIn("--timeout 360", workflow)
        self.assertIn("--num-ctx 8192", workflow)
        self.assertIn("--num-predict 512", workflow)
        self.assertNotIn("--include-page-previews", workflow)
        self.assertIn("group: qe-textbook-visual-candidates-v3-global", workflow)

    def test_ci_does_not_build_app_images_for_data_only_or_workflow_only_pushes(self):
        ci = read("ci.yml")
        self.assertIn("images: ${{ steps.detect.outputs.images }}", ci)
        self.assertIn("images=true", ci)
        self.assertIn("services/api/", ci)
        self.assertIn("apps/web/", ci)
        self.assertIn("apps/admin/", ci)
        self.assertIn("apps/people/", ci)
        self.assertNotIn('if [ "$EVENT_NAME" = "push" ]; then all=true; fi', ci)
        self.assertNotIn('if [ "$all" = "true" ]; then api=true; web=true; admin=true; people=true; content=true; fi', ci)
        self.assertIn("needs.changes.outputs.images", ci)
        self.assertIn("needs.changes.outputs.android", ci)

    def test_ci_writes_exact_commit_release_evidence_and_deploy_reuses_prebuilt_tag(self):
        ci = read("ci.yml")
        deploy = read("deploy.yml")
        self.assertIn("production-release-evidence", ci)
        self.assertIn("tested_commit_sha", ci)
        self.assertIn("image_source_commit_sha", ci)
        self.assertIn("images_built_this_run", ci)
        self.assertIn("retention-days: 90", ci)
        self.assertIn("actions/workflows/ci.yml/runs?head_sha=$target_sha", deploy)
        self.assertIn("production-release-evidence.json", deploy)
        self.assertIn("tested_commit_sha", deploy)
        self.assertIn("image_source_commit_sha", deploy)
        self.assertIn('export QUANTAEDGE_IMAGE_TAG="sha-$IMAGE_SOURCE_SHA"', deploy)
        self.assertIn('git checkout --detach "$DEPLOY_TARGET_SHA"', deploy)
        self.assertNotIn("docker/build-push-action", deploy)

    def test_vision_inference_uses_structured_json_and_records_review_only_results(self):
        analyzer = (ROOT / "scripts" / "analyze_visual_inventory_with_ollama.py").read_text(encoding="utf-8")
        tests = (ROOT / "tests" / "test_analyze_visual_inventory_with_ollama.py").read_text(encoding="utf-8")
        self.assertIn("ANALYSIS_RESPONSE_SCHEMA", analyzer)
        self.assertIn('"format": ANALYSIS_RESPONSE_SCHEMA', analyzer)
        self.assertIn('"publication_allowed": False', analyzer)
        self.assertIn('self.assertEqual("object", payload["format"]["type"])', tests)

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


if __name__ == "__main__":
    unittest.main()
