import base64
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts.analyze_visual_inventory_with_ollama import analyze_one, run_analysis


class FakeResponse:
    def __init__(self, data):
        self.data = json.dumps(data).encode("utf-8")

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def read(self):
        return self.data


class AnalyzeVisualInventoryWithOllamaTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.asset_path = self.root / "page-0001.jpg"
        self.asset_path.write_bytes(b"not-real-jpeg-but-fake-transport-fixture")
        self.asset_hash = hashlib.sha256(self.asset_path.read_bytes()).hexdigest()
        self.inventory_path = self.root / "visual-inventory.v1.json"
        self.inventory_path.write_text(json.dumps({
            "schema_version": "quantaedge.visual-inventory.v1",
            "source": {"book_id": "book-c6-science", "pdf_sha256": "a" * 64},
            "assets": [{
                "asset_id": "asset-1", "kind": "page_preview", "source_book_id": "book-c6-science",
                "source_pdf_sha256": "a" * 64, "source_pdf_page_number": 1,
                "asset_path": self.asset_path.name, "asset_sha256": self.asset_hash,
                "media_type": "image/jpeg", "page_text_context": "Water cycle",
            }],
        }), encoding="utf-8")

    def tearDown(self):
        self.temp.cleanup()

    def _opener(self, request, timeout=180):
        payload = json.loads(request.data.decode("utf-8"))
        self.assertEqual("qwen2.5vl:3b", payload["model"])
        self.assertEqual("Water cycle", payload["messages"][0]["content"].split("Nearby extracted page text")[1].splitlines()[1])
        self.assertEqual(base64.b64encode(self.asset_path.read_bytes()).decode("ascii"), payload["messages"][0]["images"][0])
        return FakeResponse({"message": {"content": json.dumps({
            "visual_summary": "A water-cycle diagram.",
            "visual_type": "diagram",
            "visible_text": ["Water"],
            "concepts": ["water cycle"],
            "context_relation": "The page text discusses water.",
            "limitations": ["Some labels may be small."],
        })}})

    def test_writes_separate_review_only_analysis_and_does_not_change_inventory(self):
        original = self.inventory_path.read_bytes()
        result = run_analysis(self.inventory_path, endpoint="http://127.0.0.1:11434",
                              model="qwen2.5vl:3b", max_assets=1,
                              include_page_previews=True, opener=self._opener)
        self.assertEqual("DRAFT_VISION_ANALYSIS_ONLY", result["analysis_status"])
        self.assertEqual(1, result["summary"]["assets_analyzed"])
        self.assertFalse(result["summary"]["publication_allowed"])
        self.assertEqual("diagram", result["results"][0]["analysis"]["visual_type"])
        self.assertEqual(original, self.inventory_path.read_bytes())

    def test_rejects_non_loopback_endpoint_before_request(self):
        with self.assertRaisesRegex(ValueError, "loopback HTTP"):
            analyze_one(self.root, {
                "asset_path": self.asset_path.name, "asset_sha256": self.asset_hash,
                "media_type": "image/jpeg", "source_book_id": "book-c6-science",
                "source_pdf_page_number": 1,
            }, endpoint="https://example.com", model="vision", opener=self._opener)

    def test_rejects_asset_hash_mismatch(self):
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            analyze_one(self.root, {
                "asset_path": self.asset_path.name, "asset_sha256": "0" * 64,
                "media_type": "image/jpeg", "source_book_id": "book-c6-science",
                "source_pdf_page_number": 1,
            }, endpoint="http://127.0.0.1:11434", model="vision", opener=self._opener)


    def test_resume_skips_successful_matching_asset_checkpoint(self):
        output = self.root / "visual-analysis.v1.json"
        first = run_analysis(
            self.inventory_path, endpoint="http://127.0.0.1:11434",
            model="qwen2.5vl:3b", max_assets=1, include_page_previews=True,
            opener=self._opener, output_path=output,
        )
        self.assertEqual(1, first["summary"]["assets_analyzed"])

        def must_not_call(*args, **kwargs):
            raise AssertionError("a matching successful asset must not be re-analyzed")

        resumed = run_analysis(
            self.inventory_path, endpoint="http://127.0.0.1:11434",
            model="qwen2.5vl:3b", max_assets=1, include_page_previews=True,
            opener=must_not_call, output_path=output, resume=True,
        )
        self.assertEqual(1, resumed["summary"]["assets_skipped_from_checkpoint"])
        self.assertEqual(1, resumed["summary"]["assets_analyzed"])

    def test_resume_rejects_changed_model_or_source(self):
        output = self.root / "visual-analysis.v1.json"
        run_analysis(
            self.inventory_path, endpoint="http://127.0.0.1:11434",
            model="qwen2.5vl:3b", max_assets=1, include_page_previews=True,
            opener=self._opener, output_path=output,
        )
        with self.assertRaisesRegex(ValueError, "model or prompt version changed"):
            run_analysis(
                self.inventory_path, endpoint="http://127.0.0.1:11434",
                model="another-model", max_assets=1, include_page_previews=True,
                output_path=output, resume=True,
            )


    def test_same_runner_run_skips_failed_asset_to_reach_next_batch(self):
        inventory = json.loads(self.inventory_path.read_text(encoding="utf-8"))
        second_path = self.root / "page-0002.jpg"
        second_path.write_bytes(b"second fake image")
        inventory["assets"].append({
            "asset_id": "asset-2", "kind": "page_preview", "source_book_id": "book-c6-science",
            "source_pdf_sha256": "a" * 64, "source_pdf_page_number": 2,
            "asset_path": second_path.name, "asset_sha256": hashlib.sha256(second_path.read_bytes()).hexdigest(),
            "media_type": "image/jpeg", "page_text_context": "Rainfall",
        })
        self.inventory_path.write_text(json.dumps(inventory), encoding="utf-8")
        output = self.root / "visual-analysis.v1.json"

        def fail(*args, **kwargs):
            raise RuntimeError("temporary model failure")

        first = run_analysis(
            self.inventory_path, endpoint="http://127.0.0.1:11434",
            model="qwen2.5vl:3b", max_assets=1, include_page_previews=True,
            opener=fail, output_path=output, run_id="run-123",
        )
        self.assertEqual(1, first["summary"]["assets_processed_this_run"])
        resumed = run_analysis(
            self.inventory_path, endpoint="http://127.0.0.1:11434",
            model="qwen2.5vl:3b", max_assets=1, include_page_previews=True,
            opener=fail, output_path=output, resume=True, run_id="run-123",
        )
        self.assertEqual(1, resumed["summary"]["assets_processed_this_run"])
        self.assertEqual({"asset-1", "asset-2"}, {item["asset_id"] for item in resumed["results"]})


if __name__ == "__main__":
    unittest.main()
