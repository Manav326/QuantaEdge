import json
import tempfile
import unittest
from pathlib import Path

from scripts.update_visual_catalog_index import build_catalog_index


class UpdateVisualCatalogIndexTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.catalog_dir = self.root / "catalogs"
        self.catalog_dir.mkdir()

    def tearDown(self):
        self.temp.cleanup()

    def write_catalog(self, slug, book_id, class_no, subject, pdf_hash):
        value = {
            "schema_version": "quantaedge.visual-attachment-catalog.v3",
            "book_slug": slug,
            "source": {
                "book_id": book_id,
                "title": f"Book {slug}",
                "class": str(class_no),
                "subject": subject,
                "language": "english",
                "edition": "test edition",
                "pdf_sha256": pdf_hash,
                "page_count": 2,
            },
            "summary": {
                "candidate_count": 1,
                "publication_allowed": False,
                "review_required": True,
            },
            "candidates": [{
                "candidate_id": f"v3-image-{slug}",
                "source_pdf_sha256": pdf_hash,
                "publication_allowed": False,
                "human_review_required": True,
            }],
        }
        (self.catalog_dir / f"{slug}.v3.json").write_text(json.dumps(value), encoding="utf-8")

    def test_builds_deterministic_index_for_multiple_books(self):
        self.write_catalog("class7-science-en", "book-7s", 7, "Science", "a" * 64)
        self.write_catalog("class6-maths-hi", "book-6m-hi", 6, "Maths", "b" * 64)
        index = build_catalog_index(self.catalog_dir)
        self.assertEqual("quantaedge.visual-catalog-index.v1", index["schema_version"])
        self.assertEqual(2, index["summary"]["catalog_count"])
        self.assertFalse(index["summary"]["publication_allowed"])
        self.assertTrue(index["summary"]["review_required"])
        self.assertEqual(["class6-maths-hi", "class7-science-en"],
                         [row["book_slug"] for row in index["catalogs"]])
        self.assertEqual("data/textbook-visual-candidates-v3/catalogs/class6-maths-hi.v3.json",
                         index["catalogs"][0]["catalog_path"])
        self.assertEqual("b" * 64, index["catalogs"][0]["source_pdf_sha256"])

    def test_rejects_catalog_that_claims_publication_allowed(self):
        self.write_catalog("class6-science-en", "book-6s", 6, "Science", "a" * 64)
        path = self.catalog_dir / "class6-science-en.v3.json"
        value = json.loads(path.read_text(encoding="utf-8"))
        value["summary"]["publication_allowed"] = True
        path.write_text(json.dumps(value), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "review-only"):
            build_catalog_index(self.catalog_dir)

    def test_rejects_candidate_with_mismatched_source_pdf(self):
        self.write_catalog("class6-science-en", "book-6s", 6, "Science", "a" * 64)
        path = self.catalog_dir / "class6-science-en.v3.json"
        value = json.loads(path.read_text(encoding="utf-8"))
        value["candidates"][0]["source_pdf_sha256"] = "c" * 64
        path.write_text(json.dumps(value), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "source hash mismatch"):
            build_catalog_index(self.catalog_dir)


if __name__ == "__main__":
    unittest.main()
