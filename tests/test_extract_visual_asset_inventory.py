import hashlib
import json
import tempfile
import unittest
from pathlib import Path

import pymupdf as fitz

from scripts.extract_visual_asset_inventory import build_inventory


class ExtractVisualAssetInventoryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.pdf_path = self.root / "source.pdf"
        self.manifest_path = self.root / "book.manifest.json"
        self.output_dir = self.root / "visuals"
        self._write_pdf_and_manifest()

    def tearDown(self):
        self.temp.cleanup()

    def _write_pdf_and_manifest(self):
        doc = fitz.open()
        page = doc.new_page(width=300, height=400)
        page.insert_text((30, 40), "Class 6 Science — Water", fontsize=12)
        # Draw a vector diagram: it should remain visible in the page preview even
        # though it is not an embedded image object.
        page.draw_rect(fitz.Rect(40, 80, 140, 150), color=(0, 0, 0), width=1)
        page.insert_text((50, 110), "Water", fontsize=10)
        doc.new_page(width=300, height=400)
        doc.save(self.pdf_path)
        doc.close()
        pdf_hash = hashlib.sha256(self.pdf_path.read_bytes()).hexdigest()
        self.manifest_path.write_text(json.dumps({
            "book_id": "ncert-c6-english-curiosity",
            "asset_pdf_sha256": pdf_hash,
            "original_book_sha256": pdf_hash,
            "page_count": 2,
            "edition": "Reprint 2026-27",
            "language": "english",
            "subject": "Science",
            "class": "6",
        }), encoding="utf-8")

    def test_creates_page_scan_records_and_non_publishable_sidecar(self):
        result = build_inventory(self.pdf_path, self.manifest_path, self.output_dir)
        self.assertEqual("quantaedge.visual-inventory.v1", result["schema_version"])
        self.assertEqual("DRAFT_VISUAL_INVENTORY_ONLY", result["inventory_status"])
        self.assertEqual(2, result["summary"]["pages_scanned"])
        self.assertEqual(2, result["summary"]["page_previews_rendered"])
        self.assertFalse(result["summary"]["publication_allowed"])
        self.assertEqual("SCANNED_WITH_PREVIEW", result["pages"][0]["visual_scan_status"])
        self.assertTrue((self.output_dir / "page-0001.jpg").is_file())
        self.assertTrue((self.output_dir / "visual-inventory.v1.json").is_file())
        self.assertEqual("NOT_ANALYZED", result["pages"][0]["vision_status"])

    def test_rejects_pdf_that_does_not_match_source_hash(self):
        data = json.loads(self.manifest_path.read_text(encoding="utf-8"))
        data["asset_pdf_sha256"] = "0" * 64
        self.manifest_path.write_text(json.dumps(data), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "does not match the manifest"):
            build_inventory(self.pdf_path, self.manifest_path, self.output_dir)

    def test_reads_existing_page_context_without_changing_legacy_jsonl(self):
        pdf_hash = hashlib.sha256(self.pdf_path.read_bytes()).hexdigest()
        rows = [
            {"book_id": "ncert-c6-english-curiosity", "source_book_page_number": 1,
             "text": "A textbook explanation.", "review_status": "TEXT_EXTRACTED_REQUIRES_CONTENT_REVIEW",
             "extraction_method": "PDF_TEXT"},
            {"book_id": "ncert-c6-english-curiosity", "source_book_page_number": 2,
             "text": "", "review_status": "NO_EXTRACTABLE_TEXT_REVIEW", "extraction_method": "PDF_TEXT"},
        ]
        jsonl_path = self.root / "pages.jsonl"
        jsonl_path.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
        data = json.loads(self.manifest_path.read_text(encoding="utf-8"))
        data["text_jsonl"] = jsonl_path.name
        self.manifest_path.write_text(json.dumps(data), encoding="utf-8")
        original_jsonl = jsonl_path.read_bytes()
        result = build_inventory(self.pdf_path, self.manifest_path, self.output_dir, render_page_previews=False)
        self.assertEqual("existing_text_jsonl", result["text_context_source"])
        self.assertEqual(hashlib.sha256(b"A textbook explanation.").hexdigest(),
                         result["pages"][0]["page_text_sha256"])
        self.assertEqual("NO_EXTRACTABLE_TEXT_REVIEW", result["pages"][1]["text_quality_status"])
        self.assertEqual(original_jsonl, jsonl_path.read_bytes())
        self.assertFalse(result["pages"][0]["page_preview"])


if __name__ == "__main__":
    unittest.main()
