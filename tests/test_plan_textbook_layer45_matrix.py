import json
import tempfile
import unittest
from pathlib import Path

from scripts.plan_textbook_layer45_matrix import BOOKS, BOOK_PAIRS, pair_complete, plan_matrix


class TextbookLayer45MatrixTests(unittest.TestCase):
    def test_all_class_resume_creates_eight_paired_book_jobs_not_sixteen_language_jobs(self):
        planned = plan_matrix("push", "[resume-visual-layers-4-5-classes-6-8]")
        self.assertEqual(8, len(planned))
        self.assertEqual({"6": 2, "7": 3, "8": 3}, {
            grade: sum(row["class_no"] == grade for row in planned) for grade in ("6", "7", "8")
        })
        for row in planned:
            ids = row["book_ids_csv"].split(",")
            self.assertEqual(2, len(ids))
            self.assertEqual(2, len(row["source_slugs_csv"].split(",")))
            self.assertTrue(any("-english-" in item for item in ids))
            self.assertTrue(any("-hindi-" in item for item in ids))
            self.assertTrue(row["book_pair_id"].endswith("-bilingual"))

    def test_single_class_resume_only_schedules_that_class_as_language_pairs(self):
        planned = plan_matrix("workflow_dispatch", class_no="7", analysis_mode="resume_layers_4_5")
        self.assertEqual(3, len(planned))
        self.assertTrue(all(row["class_no"] == "7" and len(row["book_ids_csv"].split(",")) == 2 for row in planned))

    def test_coordinator_dispatches_one_all_class_run(self):
        planned = plan_matrix("workflow_dispatch", class_no="all", analysis_mode="resume_layers_4_5")
        self.assertEqual(8, len(planned))

    def test_class_specific_markers_are_pair_sharded(self):
        self.assertEqual(2, len(plan_matrix("push", "[resume-visual-layers-4-5-class-6]")))
        self.assertEqual(3, len(plan_matrix("push", "[resume-visual-layers-4-5-class-7]")))
        self.assertEqual(3, len(plan_matrix("push", "[resume-visual-layers-4-5-class-8]")))
        self.assertEqual(6, len(plan_matrix("push", "[resume-visual-layers-4-5-classes-7-8]")))

    def test_catalog_rebuild_modes_remain_class_wide(self):
        self.assertEqual(
            [{
                "class_no": "8", "book_slug": "all-books", "book_pair_id": "",
                "book_label": "class-wide", "book_ids_csv": "", "source_slugs_csv": "",
            }],
            plan_matrix("workflow_dispatch", class_no="8", analysis_mode="analyze_all"),
        )
        self.assertEqual("all-books", plan_matrix("push", "[analyze-all-visual-layers-4-5-class-7]")[0]["book_slug"])

    def test_completed_pair_is_skipped_only_when_both_language_ledgers_are_valid(self):
        pair = next(item for item in BOOK_PAIRS if item["class_no"] == "6" and item["book_label"] == "Science")
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for book_id, slug in zip(pair["book_ids_csv"].split(","), pair["source_slugs_csv"].split(","), strict=True):
                ledger_path = root / "data" / "textbook-content-pipeline" / "class-6" / "books" / slug / "coverage-ledger.v1.json"
                index_path = root / "data" / "textbook-visuals" / slug / "visual-pipeline-index.v1.json"
                ledger_path.parent.mkdir(parents=True)
                index_path.parent.mkdir(parents=True)
                rel = ledger_path.relative_to(root).as_posix()
                sha = "a" * 64
                ledger_path.write_text(json.dumps({
                    "schema_version": "quantaedge.textbook-coverage-ledger.v1",
                    "ledger_status": "COMPLETE",
                    "source": {"source_book_id": book_id, "source_pdf_sha256": sha},
                    "summary": {"unresolved_count": 0},
                }), encoding="utf-8")
                index_path.write_text(json.dumps({
                    "source_book_id": book_id, "source_pdf_sha256": sha,
                    "layers": {"5": "COMPLETE"}, "coverage_ledger_path": rel,
                }), encoding="utf-8")
            self.assertTrue(pair_complete(root, pair))
            remaining = plan_matrix(
                "workflow_dispatch", class_no="all", analysis_mode="resume_layers_4_5",
                repo_root=root, skip_completed=True,
            )
            self.assertEqual(7, len(remaining))
            self.assertNotIn(pair["book_pair_id"], {x["book_pair_id"] for x in remaining})
            hindi_slug = pair["source_slugs_csv"].split(",")[1]
            bad_ledger = root / "data" / "textbook-content-pipeline" / "class-6" / "books" / hindi_slug / "coverage-ledger.v1.json"
            bad = json.loads(bad_ledger.read_text(encoding="utf-8"))
            bad["summary"]["unresolved_count"] = 1
            bad_ledger.write_text(json.dumps(bad), encoding="utf-8")
            self.assertFalse(pair_complete(root, pair))

    def test_every_bilingual_pair_maps_to_the_exact_declared_english_and_hindi_sources(self):
        source_by_id = {item["book_id"]: item for item in BOOKS}
        for pair in BOOK_PAIRS:
            expected = dict(zip(pair["book_ids_csv"].split(","), pair["source_slugs_csv"].split(","), strict=True))
            self.assertEqual(2, len(expected))
            self.assertEqual(expected, {book_id: source_by_id[book_id]["book_slug"] for book_id in expected})
            self.assertTrue(all(source_by_id[book_id]["class_no"] == pair["class_no"] for book_id in expected))
            self.assertEqual(1, sum("english" in book_id for book_id in expected))
            self.assertEqual(1, sum("hindi" in book_id for book_id in expected))

    def test_matrix_output_uses_github_include_envelope(self):
        planner = (Path(__file__).resolve().parents[1] / "scripts" / "plan_textbook_layer45_matrix.py").read_text(encoding="utf-8")
        self.assertIn('json.dumps({"include": matrix}, separators=(",", ":"))', planner)

    def test_unknown_events_do_not_start_workers(self):
        self.assertEqual([], plan_matrix("push", "unmarked commit"))
        self.assertEqual([], plan_matrix("schedule"))
        self.assertEqual(16, len(BOOKS))
        self.assertEqual(8, len(BOOK_PAIRS))


if __name__ == "__main__":
    unittest.main()
