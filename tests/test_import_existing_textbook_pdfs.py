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
