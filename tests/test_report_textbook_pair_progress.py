import json
import tempfile
import unittest
from pathlib import Path

from scripts.plan_textbook_layer45_matrix import BOOKS, BOOK_PAIRS
from scripts.report_textbook_pair_progress import build_report, render_markdown


class TextbookPairProgressReportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.pair = next(item for item in BOOK_PAIRS if item["class_no"] == "6" and item["book_label"] == "Science")

    def write_json(self, path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def mark_book_complete(self, book_id, slug, sha="a" * 64):
        grade = next(item["class_no"] for item in BOOKS if item["book_id"] == book_id)
        visual = self.root / "data" / "textbook-visuals" / slug
        layer = self.root / "data" / "textbook-content-pipeline" / f"class-{grade}" / "books" / slug
        inventory = {
            "schema_version": "quantaedge.visual-inventory.v1",
            "source": {"book_id": book_id, "pdf_sha256": sha, "class": grade},
            "assets": [
                {"asset_id": f"asset-{i}", "kind": "page_preview", "asset_sha256": str(i) * 64}
                for i in range(1, 6)
            ],
        }
        raw_rel = f"data/textbook-visuals/{slug}/visual-analysis-qwen2.5vl-3b.v1.json"
        ledger_rel = f"data/textbook-content-pipeline/class-{grade}/books/{slug}/coverage-ledger.v1.json"
        index = {
            "schema_version": "quantaedge.textbook-content-pipeline-index.v1",
            "source_book_id": book_id,
            "source_pdf_sha256": sha,
            "layers": {"4": "CHECKPOINTED", "5": "COMPLETE"},
            "analysis_path": raw_rel,
            "coverage_ledger_path": ledger_rel,
        }
        raw = {
            "schema_version": "quantaedge.visual-analysis.v1",
            "source_book_id": book_id,
            "source_pdf_sha256": sha,
            "model": "qwen2.5vl:3b",
            "summary": {
                "assets_eligible": 5,
                "assets_analyzed": 5,
                "assets_failed": 0,
                "assets_remaining": 0,
                "assets_completion_percent": 100.0,
            },
            "results": [],
        }
        ledger = {
            "schema_version": "quantaedge.textbook-coverage-ledger.v1",
            "ledger_status": "COMPLETE",
            "source": {"source_book_id": book_id, "source_pdf_sha256": sha},
            "summary": {"unresolved_count": 0},
        }
        self.write_json(visual / "visual-inventory.v1.json", inventory)
        self.write_json(visual / "visual-pipeline-index.v1.json", index)
        self.write_json(self.root / raw_rel, raw)
        self.write_json(self.root / ledger_rel, ledger)

    def test_report_groups_classes_and_pairs_and_never_claims_empty_repo_complete(self):
        report = build_report(self.root)
        self.assertEqual("NOT_STARTED", report["overall"]["status"])
        self.assertEqual(8, report["overall"]["subject_book_pairs_total"])
        self.assertEqual(["6", "7", "8"], [item["class_no"] for item in report["classes"]])
        class6 = report["classes"][0]
        self.assertEqual(2, class6["subject_book_pairs_total"])
        self.assertEqual("Science", class6["pairs"][0]["subject_book_label"])
        markdown = render_markdown(report)
        self.assertIn("## Class 6", markdown)
        self.assertIn("English edition", markdown)
        self.assertIn("Hindi edition", markdown)
        self.assertIn("Completion rule", markdown)
        self.assertIs(report["publication_allowed"], False)

    def test_pair_only_completes_when_both_language_ledgers_and_indexes_match(self):
        ids = self.pair["book_ids_csv"].split(",")
        slugs = self.pair["source_slugs_csv"].split(",")
        for book_id, slug in zip(ids, slugs, strict=True):
            self.mark_book_complete(book_id, slug)
        report = build_report(self.root)
        pair = next(item for item in report["classes"][0]["pairs"] if item["book_pair_id"] == self.pair["book_pair_id"])
        self.assertEqual("COMPLETE", pair["status"])
        self.assertEqual(2, pair["verified_language_editions"])
        self.assertEqual(10, pair["combined_analysis"]["eligible_assets"])
        self.assertEqual(10, pair["combined_analysis"]["analyzed_assets"])
        self.assertEqual(100.0, pair["combined_analysis"]["completion_percent"])
        self.assertTrue(all(item["coverage"]["verified_complete"] for item in pair["languages"]))

    def test_mismatched_language_ledger_hash_blocks_pair_completion(self):
        ids = self.pair["book_ids_csv"].split(",")
        slugs = self.pair["source_slugs_csv"].split(",")
        for book_id, slug in zip(ids, slugs, strict=True):
            self.mark_book_complete(book_id, slug)
        hindi_ledger = self.root / "data" / "textbook-content-pipeline" / "class-6" / "books" / slugs[1] / "coverage-ledger.v1.json"
        value = json.loads(hindi_ledger.read_text(encoding="utf-8"))
        value["source"]["source_pdf_sha256"] = "b" * 64
        self.write_json(hindi_ledger, value)
        report = build_report(self.root)
        pair = next(item for item in report["classes"][0]["pairs"] if item["book_pair_id"] == self.pair["book_pair_id"])
        self.assertEqual("BLOCKED", pair["status"])
        self.assertEqual(1, pair["verified_language_editions"])
        self.assertTrue(pair["blockers"])

    def test_report_tracks_english_progress_separately_when_hindi_has_not_started(self):
        english_id, _ = self.pair["book_ids_csv"].split(",")
        english_slug = self.pair["source_slugs_csv"].split(",")[0]
        self.mark_book_complete(english_id, english_slug)
        report = build_report(self.root)
        pair = next(item for item in report["classes"][0]["pairs"] if item["book_pair_id"] == self.pair["book_pair_id"])
        self.assertEqual("IN_PROGRESS", pair["status"])
        editions = {item["language"]: item for item in pair["languages"]}
        self.assertEqual("COMPLETE", editions["english"]["status"])
        self.assertEqual("NOT_STARTED", editions["hindi"]["status"])
        self.assertEqual(1, pair["verified_language_editions"])


if __name__ == "__main__":
    unittest.main()
