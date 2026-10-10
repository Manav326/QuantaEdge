import hashlib
import json
import tempfile
import unittest
from pathlib import Path

import pymupdf

from scripts.extract_textbook_text import process_bundle, page_status


class TextbookTextExtractionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.pdf_dir = self.root / "pdfs"
        self.bundle_dir = self.root / "bundles"
        self.output_dir = self.root / "text-library"
        self.pdf_dir.mkdir()
        self.bundle_dir.mkdir()
        self.pdf_path = self.pdf_dir / "sample.pdf"

        doc = pymupdf.open()
        page = doc.new_page()
        page.insert_text((72, 72), "कक्षा छह गणित का उदाहरण पाठ। यह पाठ पर्याप्त लंबा है।")
        page = doc.new_page()
        page.insert_text((72, 72), "अध्याय दो का दूसरा पृष्ठ। इसमें हिंदी पाठ उपलब्ध है।")
        doc.save(self.pdf_path)
        doc.close()
        digest = hashlib.sha256(self.pdf_path.read_bytes()).hexdigest()
        self.bundle_path = self.bundle_dir / "sample.pdf.json"
        self.bundle_path.write_text(json.dumps({
            "bundle_status": "DRAFT_EXTRACTION_ONLY",
            "source": {
                "pdf_filename": "sample.pdf",
                "pdf_sha256": digest,
                "pdf_page_count": 2,
                "title": "उदाहरण — अध्याय 1",
                "source_title": "उदाहरण पुस्तक",
                "book_id": "test-book",
                "subject": "Mathematics",
                "class": 6,
                "language": "hindi",
                "scope": "CHAPTER_PDF",
                "chapter_title": "अध्याय 1",
                "chapter_page_start": 7,
                "original_book_sha256": "a" * 64,
            }
        }, ensure_ascii=False), encoding="utf-8")

    def tearDown(self):
        self.temp.cleanup()

    def test_extracts_page_text_and_preserves_original_page_mapping(self):
        result = process_bundle(self.bundle_path, self.pdf_dir, self.output_dir)
        self.assertEqual(2, result["completed_pages"])
        self.assertEqual(2, result["pages_newly_extracted"])
        rows_path = self.output_dir / "pages" / (result["asset_pdf_sha256"] + ".jsonl")
        rows = [json.loads(line) for line in rows_path.read_text(encoding="utf-8").splitlines()]
        self.assertEqual([1, 2], [row["asset_page_number"] for row in rows])
        self.assertEqual([7, 8], [row["source_book_page_number"] for row in rows])
        self.assertTrue(all(row["has_devanagari"] for row in rows))
        self.assertTrue(all(row["review_status"] == "TEXT_EXTRACTED_REQUIRES_CONTENT_REVIEW" for row in rows))
        self.assertFalse(result["published"])

    def test_second_run_resumes_without_reextracting_pages(self):
        first = process_bundle(self.bundle_path, self.pdf_dir, self.output_dir)
        second = process_bundle(self.bundle_path, self.pdf_dir, self.output_dir)
        self.assertEqual(2, first["pages_newly_extracted"])
        self.assertEqual(0, second["pages_newly_extracted"])
        self.assertEqual(2, second["completed_pages"])

    def test_checksum_mismatch_is_rejected(self):
        bundle = json.loads(self.bundle_path.read_text(encoding="utf-8"))
        bundle["source"]["pdf_sha256"] = "0" * 64
        self.bundle_path.write_text(json.dumps(bundle), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "SHA-256 does not match"):
            process_bundle(self.bundle_path, self.pdf_dir, self.output_dir)

    def test_page_status_flags_missing_or_short_text_for_review(self):
        self.assertEqual("NO_EXTRACTABLE_TEXT_REVIEW", page_status("  \n "))
        self.assertEqual("LOW_TEXT_REVIEW", page_status("हिंदी"))
        self.assertEqual("NO_DEVANAGARI_REVIEW", page_status("A sufficiently long English-only extracted text."))


if __name__ == "__main__":
    unittest.main()
