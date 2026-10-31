import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from scripts.plan_textbook_layer45_matrix import BOOK_PAIRS, BOOKS
from scripts.textbook_pipeline_handoff import pair_for_book_id, pair_for_id, pair_sources, record_handoff, update_timestamps, validate_pair_completion, validate_book_layer45

class TextbookPipelineHandoffTests(unittest.TestCase):
    def test_all_pairs_resolve_to_exact_english_and_hindi_books(self):
        for pair in BOOK_PAIRS:
            sources=pair_sources(pair)
            self.assertEqual(2,len(sources))
            self.assertEqual({"english","hindi"},{row["language"] for row in sources})
            self.assertEqual(2,len({row["book_id"] for row in sources}))
            self.assertEqual(2,len({row["book_slug"] for row in sources}))
            for row in sources:
                self.assertEqual(pair["book_pair_id"],pair_for_book_id(row["book_id"])["book_pair_id"])

    def test_bad_pair_ids_never_fall_back_to_class_wide_scope(self):
        for value in ("","class6-science","not-a-pair"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                pair_for_id(value)

    def test_running_does_not_fake_progress_or_checkpoint_timestamps(self):
        previous={"timestamps":{"created_at":"2026-10-01T00:00:00Z","last_progress_at":"2026-10-01T00:10:00Z","checkpoint_at":"2026-10-01T00:10:00Z"},
                  "work":{"processed_count":10,"failed_count":1,"unresolved_count":4}}
        result=update_timestamps(previous,"2026-10-01T00:20:00Z","RUNNING",10,1,4,False)
        self.assertEqual("2026-10-01T00:10:00Z",result["last_progress_at"])
        self.assertEqual("2026-10-01T00:10:00Z",result["checkpoint_at"])
        self.assertNotIn("completed_at",result)

    def test_real_checkpoint_and_counter_progress_advance_their_clocks(self):
        previous={"timestamps":{"created_at":"2026-10-01T00:00:00Z","checkpoint_at":"2026-10-01T00:10:00Z"},
                  "work":{"processed_count":10,"failed_count":1,"unresolved_count":4}}
        result=update_timestamps(previous,"2026-10-01T00:30:00Z","CHECKPOINTED",12,1,4,True)
        self.assertEqual("2026-10-01T00:30:00Z",result["last_progress_at"])
        self.assertEqual("2026-10-01T00:30:00Z",result["checkpoint_at"])

    def test_only_complete_status_has_completion_time_and_errors_are_recorded(self):
        previous={"timestamps":{"created_at":"2026-10-01T00:00:00Z"},"work":{"processed_count":10,"failed_count":0,"unresolved_count":0}}
        done=update_timestamps(previous,"2026-10-01T00:30:00Z","COMPLETE",10,0,0,True)
        self.assertEqual("2026-10-01T00:30:00Z",done["completed_at"])
        failed=update_timestamps(previous,"2026-10-01T00:40:00Z","BLOCKED",10,0,1,False)
        self.assertNotIn("completed_at",failed)
        self.assertEqual("2026-10-01T00:40:00Z",failed["last_error_at"])

    def test_fully_source_matched_bilingual_pair_passes_the_strict_crosswalk(self):
        pair = next(p for p in BOOK_PAIRS if p["book_pair_id"] == "class6-science-bilingual")
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for number, source in enumerate(pair_sources(pair), start=1):
                grade, book_id, slug, language = source["class_no"], source["book_id"], source["book_slug"], source["language"]
                pdf_sha = (str(number) * 64)
                asset_sha = (str(number + 2) * 64)
                page_dir = root / "data" / "textbook-extractions" / f"class-{grade}" / language / "pages"
                page_dir.mkdir(parents=True)
                text_name = f"{pdf_sha}.jsonl"
                manifest = {
                    "schema_version": 1, "book_id": book_id, "class": grade, "language": language,
                    "scope": "SUBJECT_BOOK", "content_state": "DRAFT_EXTRACTION_ONLY_REQUIRES_REVIEW",
                    "original_book_sha256": pdf_sha, "page_count": 1, "text_jsonl": text_name,
                }
                (page_dir / f"{pdf_sha}.manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
                row = {"schema_version": 1, "book_id": book_id, "original_book_sha256": pdf_sha,
                       "source_book_page_number": 1, "language": language, "scope": "SUBJECT_BOOK"}
                (page_dir / text_name).write_text(json.dumps(row) + "\n", encoding="utf-8")

                visual_dir = root / "data" / "textbook-visuals" / slug
                visual_dir.mkdir(parents=True)
                inventory = {
                    "schema_version": "quantaedge.visual-inventory.v1",
                    "source": {"book_id": book_id, "pdf_sha256": pdf_sha},
                    "assets": [{"asset_id": f"asset-{number}", "asset_sha256": asset_sha,
                                "source_pdf_sha256": pdf_sha, "kind": "embedded_image",
                                "source_pdf_page_number": 1}],
                }
                inventory_path = visual_dir / "visual-inventory.v1.json"
                inventory_path.write_text(json.dumps(inventory), encoding="utf-8")
                raw_path = visual_dir / "raw-analysis.v1.json"
                raw = {
                    "schema_version": "quantaedge.visual-analysis.v1",
                    "source_book_id": book_id, "source_pdf_sha256": pdf_sha,
                    "model": "test-vision-model", "prompt_version": "test-prompt-v1",
                    "summary": {"assets_eligible": 1, "assets_analyzed": 1, "assets_failed": 0, "assets_remaining": 0},
                    "results": [{"asset_id": f"asset-{number}", "asset_sha256": asset_sha,
                                 "source_book_id": book_id, "source_pdf_sha256": pdf_sha,
                                 "analysis_status": "DRAFT_REQUIRES_REVIEW", "analysis": {"description": "draft"}}],
                }
                raw_path.write_text(json.dumps(raw), encoding="utf-8")
                raw_sha = hashlib.sha256(raw_path.read_bytes()).hexdigest()
                effective_path = visual_dir / "effective-analysis.v1.json"
                effective = {
                    "schema_version": "quantaedge.visual-analysis-effective.v1",
                    "source_book_id": book_id, "source_pdf_sha256": pdf_sha,
                    "raw_analysis_path": raw_path.relative_to(root).as_posix(), "raw_analysis_sha256": raw_sha,
                    "model": "test-vision-model", "prompt_version": "test-prompt-v1",
                    "results": [{"asset_id": f"asset-{number}", "asset_sha256": asset_sha,
                                 "analysis_status": "ANALYZED_REQUIRES_HUMAN_REVIEW",
                                 "effective_analysis": {"description": "draft"}}],
                }
                effective_path.write_text(json.dumps(effective), encoding="utf-8")
                layer_dir = root / "data" / "textbook-content-pipeline" / f"class-{grade}" / "books" / slug
                layer_dir.mkdir(parents=True)
                ledger = {
                    "schema_version": "quantaedge.textbook-coverage-ledger.v1",
                    "ledger_status": "COMPLETE",
                    "source": {"source_book_id": book_id, "source_pdf_sha256": pdf_sha},
                    "summary": {
                        "source_pdf_page_count": 1, "pages_with_unresolved_text": 0,
                        "unresolved_count": 0, "missing_visual_analyses": 0,
                        "failed_visual_analyses": 0, "orphan_analysis_results": 0,
                        "eligible_visual_asset_rows": 1, "successful_visual_analyses": 1,
                        "analysis_model": "test-vision-model", "analysis_prompt_version": "test-prompt-v1",
                    },
                    "pages": [{"source_pdf_page_number": 1}],
                    "visuals": [{"asset_id": f"asset-{number}", "asset_sha256": asset_sha,
                                 "analysis_status": "ANALYZED_REQUIRES_HUMAN_REVIEW"}],
                }
                ledger_path = layer_dir / "coverage-ledger.v1.json"
                ledger_path.write_text(json.dumps(ledger), encoding="utf-8")
                index = {
                    "schema_version": "quantaedge.textbook-content-pipeline-index.v1",
                    "source_book_id": book_id, "book_slug": slug, "source_pdf_sha256": pdf_sha,
                    "layers": {"1": "COMPLETE", "2": "COMPLETE", "3": "COMPLETE", "4": "COMPLETE", "5": "COMPLETE"},
                    "analysis_path": raw_path.relative_to(root).as_posix(),
                    "effective_analysis_path": effective_path.relative_to(root).as_posix(),
                    "coverage_ledger_path": ledger_path.relative_to(root).as_posix(),
                }
                (visual_dir / "visual-pipeline-index.v1.json").write_text(json.dumps(index), encoding="utf-8")

                complete, reasons, _ = validate_book_layer45(root, source)
                self.assertTrue(complete, f"{book_id}: " + "; ".join(reasons))

            complete, blockers = validate_pair_completion(root, pair)
            self.assertTrue(complete, "; ".join(blockers))
            result = record_handoff(root, pair, 5, "COMPLETE", reason_code="TEST_VALID_PAIR")
            self.assertEqual("COMPLETE", result["status"])
            handoff_path = root / "data" / "textbook-content-pipeline" / "handoffs" / f"class-{pair['class_no']}" / pair["book_pair_id"] / "current.v1.json"
            saved = json.loads(handoff_path.read_text(encoding="utf-8"))
            self.assertTrue(saved["validation"]["schema_valid"])
            self.assertTrue(saved["timestamps"]["last_progress_at"].endswith("Z"))
            self.assertTrue(saved["timestamps"]["completed_at"].endswith("Z"))
            self.assertFalse(saved["decision"]["publication_allowed"])

    def test_empty_repository_does_not_claim_any_pair_complete(self):
        for pair in BOOK_PAIRS:
            complete,blockers=validate_pair_completion(Path("/nonexistent"),pair)
            self.assertFalse(complete)
            self.assertTrue(blockers)

if __name__=="__main__":
    unittest.main()
