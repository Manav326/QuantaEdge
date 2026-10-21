import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from scripts.build_visual_lesson_attachment_manifest import build_catalog


class BuildVisualLessonAttachmentManifestTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.pack_root = self.root / "pack"
        self.pack_root.mkdir()
        self.repo = self.root / "repo"
        self.repo.mkdir()
        (self.pack_root / "prepared").mkdir()
        (self.pack_root / "source-pages").mkdir()
        (self.pack_root / "prepared" / "figure.jpg").write_bytes(b"figure bytes")
        (self.pack_root / "source-pages" / "page-0002.jpg").write_bytes(b"page bytes")
        pdf_hash = "a" * 64
        self.pack_path = self.pack_root / "candidates.v3.json"
        self.pack_path.write_text(json.dumps({
            "schema_version": "quantaedge.visual-candidate-pack.v3",
            "pack_status": "CANDIDATES_FOR_HUMAN_REVIEW",
            "source": {"book_id": "ncert-c6-english-fecu1", "pdf_sha256": pdf_hash,
                       "page_count": 8, "language": "english", "subject": "Science", "class": "6"},
            "summary": {"selected_candidate_count": 1, "publication_allowed": False},
            "candidates": [{
                "candidate_id": "v3-image-abcdef1234",
                "candidate_kind": "embedded_image",
                "source_book_id": "ncert-c6-english-fecu1",
                "source_pdf_sha256": pdf_hash,
                "source_pdf_page_number": 2,
                "source_asset_sha256": "b" * 64,
                "display_path": "prepared/figure.jpg",
                "source_page_preview_path": "source-pages/page-0002.jpg",
                "page_text_context": "Chapter text.",
                "bbox_pdf_points": [1, 2, 3, 4],
            }],
        }), encoding="utf-8")

    def tearDown(self):
        self.temp.cleanup()

    def test_catalog_copies_content_addressed_assets_and_keeps_review_gate(self):
        result = build_catalog(self.pack_path, self.pack_root, self.repo, "class6-curiosity-en")
        self.assertEqual("quantaedge.visual-attachment-catalog.v3", result["schema_version"])
        self.assertFalse(result["summary"]["publication_allowed"])
        item = result["candidates"][0]
        self.assertFalse(item["publication_allowed"])
        self.assertEqual("NEEDS_REVIEW", item["visual_review_status"])
        self.assertEqual("SOURCE_REFERENCE_ONLY", item["rights_status"])
        self.assertIn("data/textbook-visual-candidates-v3/class6-curiosity-en/candidates/v3-image-abcdef1234-", item["image_url"])
        self.assertIn("pages/page-0002-", item["source_page_url"])
        self.assertTrue((self.repo / "data/textbook-visual-candidates-v3/class6-curiosity-en" /
                         "candidates").is_dir())
        self.assertEqual(hashlib.sha256(b"figure bytes").hexdigest(), item["display_sha256"])

    def test_attaches_review_only_chapter_suggestion_from_matching_page_map(self):
        chapter_map_path = self.root / "chapter-map.v1.json"
        chapter_map_path.write_text(json.dumps({
            "schema_version": "quantaedge.visual-chapter-map.v1",
            "source_book_id": "ncert-c6-english-fecu1",
            "source_pdf_sha256": "a" * 64,
            "mapping_status": "TOC_DERIVED_NEEDS_HUMAN_REVIEW",
            "human_review_required": True,
            "publication_allowed": False,
            "chapters": [{"chapter_number": 1, "chapter_title": "Introduction",
                          "source_pdf_page_start": 1, "source_pdf_page_end": 3}],
        }), encoding="utf-8")
        result = build_catalog(self.pack_path, self.pack_root, self.repo, "class6-curiosity-en", chapter_map_path)
        row = result["candidates"][0]
        self.assertEqual(1, row["suggested_chapter_number"])
        self.assertEqual("Introduction", row["suggested_chapter_title"])
        self.assertEqual("TOC_DERIVED_NEEDS_HUMAN_REVIEW", row["chapter_map_review_status"])
        self.assertFalse(row["publication_allowed"])

    def test_rejects_candidate_source_book_id_mismatch(self):
        value = json.loads(self.pack_path.read_text(encoding="utf-8"))
        value["candidates"][0]["source_book_id"] = "ncert-c6-hindi-fecu1"
        self.pack_path.write_text(json.dumps(value), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Source book ID mismatch"):
            build_catalog(self.pack_path, self.pack_root, self.repo, "class6-curiosity-en")

    def test_rejects_invalid_source_asset_hash(self):
        value = json.loads(self.pack_path.read_text(encoding="utf-8"))
        value["candidates"][0]["source_asset_sha256"] = ""
        self.pack_path.write_text(json.dumps(value), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Invalid source asset SHA-256"):
            build_catalog(self.pack_path, self.pack_root, self.repo, "class6-curiosity-en")

    def test_rejects_source_hash_mismatch(self):
        value = json.loads(self.pack_path.read_text(encoding="utf-8"))
        value["candidates"][0]["source_pdf_sha256"] = "c" * 64
        self.pack_path.write_text(json.dumps(value), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Source PDF hash mismatch"):
            build_catalog(self.pack_path, self.pack_root, self.repo, "class6-curiosity-en")

    def test_rejects_path_traversal(self):
        value = json.loads(self.pack_path.read_text(encoding="utf-8"))
        value["candidates"][0]["display_path"] = "../../outside.jpg"
        self.pack_path.write_text(json.dumps(value), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "missing or escapes"):
            build_catalog(self.pack_path, self.pack_root, self.repo, "class6-curiosity-en")

    def test_rejects_invalid_candidate_schema_and_empty_candidates(self):
        value = json.loads(self.pack_path.read_text(encoding="utf-8"))
        value["schema_version"] = "unknown"
        self.pack_path.write_text(json.dumps(value), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Expected a quantaedge"):
            build_catalog(self.pack_path, self.pack_root, self.repo, "class6-curiosity-en")


if __name__ == "__main__":
    unittest.main()
