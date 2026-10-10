import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from scripts.import_existing_textbook_pdfs import find_matching_pdf, load_bundle, multipart_body


class ImportExistingTextbookPdfsTest(unittest.TestCase):
    def test_bundle_uses_real_source_checksum_and_page_count(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pdf_bytes = b"%PDF-1.4\nrepresentative source bytes\n"
            digest = hashlib.sha256(pdf_bytes).hexdigest()
            bundle = root / "class-6-maths.json"
            bundle.write_text(json.dumps({
                "bundle_status": "DRAFT_EXTRACTION_ONLY",
                "source": {
                    "title": "Class 6 Mathematics",
                    "pdf_filename": "book.pdf",
                    "pdf_sha256": digest,
                    "pdf_page_count": 18,
                },
                "pages": [],
            }), encoding="utf-8")

            result = load_bundle(bundle)

            self.assertEqual("Class 6 Mathematics", result["title"])
            self.assertEqual("book.pdf", result["filename"])
            self.assertEqual(digest, result["sha256"])
            self.assertEqual(18, result["page_count"])
            self.assertEqual("complete", result["asset_content_status"])

    def test_partial_coverage_is_preserved_and_made_visible_in_library_reference(self):
        from scripts.import_existing_textbook_pdfs import source_reference
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bundle = root / "class-12-english-flamingo.json"
            digest = hashlib.sha256(b"%PDF-1.4 partial").hexdigest()
            coverage = {
                "status": "partial", "expected_chapters": list(range(1, 14)),
                "available_chapters": [1, 2, 3, 4, 5, 6, 7, 8, 11, 12, 13],
                "missing_chapters": [9, 10],
            }
            bundle.write_text(json.dumps({
                "source": {
                    "title": "Flamingo", "pdf_filename": "flamingo-partial.pdf",
                    "pdf_sha256": digest, "pdf_page_count": 45,
                },
                "content_availability": coverage,
                "asset_content_status": "partial",
            }), encoding="utf-8")
            item = load_bundle(bundle)
            reference = source_reference(item)
            self.assertEqual("partial", item["asset_content_status"])
            self.assertEqual([9, 10], item["missing_chapters"])
            self.assertIn("content_status=PARTIAL", reference)
            self.assertIn("missing_chapters=9,10", reference)
            self.assertIn("available_chapters=", reference)
            self.assertLessEqual(len(reference), 500)

    def test_matching_pdf_requires_the_original_sha256(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_dir = root / "source-pdfs"
            source_dir.mkdir()
            pdf_path = source_dir / "book.pdf"
            pdf_bytes = b"%PDF-1.4\noriginal bytes\n"
            pdf_path.write_bytes(pdf_bytes)
            digest = hashlib.sha256(pdf_bytes).hexdigest()

            self.assertEqual(pdf_path, find_matching_pdf(source_dir, "book.pdf", digest))
            self.assertIsNone(find_matching_pdf(source_dir, "book.pdf", "0" * 64))

    def test_multipart_body_marks_the_pdf_and_keeps_bytes_intact(self):
        pdf_bytes = b"%PDF-1.4\nprivate pdf payload\n"
        boundary, body = multipart_body(
            {"title": "गणित", "sourceKind": "EXTRACTION_IMPORT"},
            "book.pdf",
            pdf_bytes,
        )

        self.assertIn(b'name="title"', body)
        self.assertIn("गणित".encode("utf-8"), body)
        self.assertIn(b'name="sourceKind"', body)
        self.assertIn(b'name="file"; filename="book.pdf"', body)
        self.assertIn(b"Content-Type: application/pdf", body)
        self.assertIn(pdf_bytes, body)
        self.assertTrue(body.endswith(("--" + boundary + "--\r\n").encode("ascii")))

    def test_bundle_rejects_missing_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            bundle = Path(directory) / "bad.json"
            bundle.write_text(json.dumps({
                "source": {
                    "title": "Textbook",
                    "pdf_filename": "book.pdf",
                    "pdf_sha256": "not-a-sha",
                    "pdf_page_count": 10,
                }
            }), encoding="utf-8")

            with self.assertRaises(ValueError):
                load_bundle(bundle)


if __name__ == "__main__":
    unittest.main()
