import json
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

from scripts.import_ghcr_extracted_pages import (
    multipart_body, post_approve, post_multipart, validate_pair,
)


class GhcrExtractedPagesImportTests(unittest.TestCase):
    def test_validates_complete_book_manifest_and_sibling_page_jsonl(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest_path = root / ("a" * 64 + ".manifest.json")
            pages_name = "a" * 64 + ".jsonl"
            manifest_path.write_text(json.dumps({
                "scope": "SUBJECT_BOOK",
                "content_state": "DRAFT_EXTRACTION_ONLY_REQUIRES_REVIEW",
                "book_id": "ncert-c6-english-fegp1",
                "source_url": "https://ncert.nic.in/textbook.php?ln=en",
                "original_book_sha256": "b" * 64,
                "asset_pdf_sha256": "b" * 64,
                "text_jsonl": pages_name,
                "completed_pages": 1,
            }), encoding="utf-8")
            (root / pages_name).write_text('{"asset_page_number":1}\n', encoding="utf-8")
            manifest, pages = validate_pair(manifest_path)
            self.assertEqual("ncert-c6-english-fegp1", manifest["book_id"])
            self.assertTrue(pages.is_file())

    def test_rejects_non_book_or_unreviewed_manifest(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "bad.manifest.json"
            path.write_text(json.dumps({"scope": "CHAPTER_PDF"}), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "complete-book"):
                validate_pair(path)

    def test_multipart_encodes_manifest_and_page_text_as_named_parts(self):
        boundary, body = multipart_body([
            ("manifest", "book.manifest.json", b'{"book_id":"book-a"}'),
            ("pages", "book.jsonl", b'{"page":1}'),
        ])
        self.assertIn(b'name="manifest"; filename="book.manifest.json"', body)
        self.assertIn(b'name="pages"; filename="book.jsonl"', body)
        self.assertIn(boundary.encode("ascii"), body)


    def test_api_requests_use_legacy_session_cookie_without_workspace_override(self):
        with patch("scripts.import_ghcr_extracted_pages.urllib.request.urlopen") as urlopen:
            urlopen.return_value.__enter__.return_value.read.return_value = b'{"sourceBookId": 12}'
            post_multipart(
                "http://localhost:8080/api/v1/admin/textbook-extraction/ghcr-books",
                "test-staff-access-token",
                [("manifest", "book.manifest.json", b'{"book_id":"book-a"}')],
            )
            request = urlopen.call_args.args[0]
            self.assertEqual(
                "QE_SESSION=test-staff-access-token",
                request.get_header("Cookie"),
            )
            self.assertIsNone(request.get_header("X-qe-workspace"))

        with patch("scripts.import_ghcr_extracted_pages.urllib.request.urlopen") as urlopen:
            urlopen.return_value.__enter__.return_value.read.return_value = b'{"approved": true}'
            post_approve(
                "http://localhost:8080/api/v1/admin/textbook-extraction/ghcr-books/12/approve",
                "test-staff-access-token",
            )
            request = urlopen.call_args.args[0]
            self.assertEqual(
                "QE_SESSION=test-staff-access-token",
                request.get_header("Cookie"),
            )
            self.assertIsNone(request.get_header("X-qe-workspace"))


if __name__ == "__main__":
    unittest.main()
