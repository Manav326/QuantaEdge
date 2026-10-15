import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BOOK_SLUG = "class6-curiosity-en"
CATALOG_PATH = ROOT / "apps/admin/public/content/visual-candidates" / f"{BOOK_SLUG}.v3.json"
PACK_PATH = ROOT / "data/textbook-visual-candidates-v3" / BOOK_SLUG / "candidates.v3.json"
CHAPTER_MAP_PATH = ROOT / "data/textbook-visual-candidates-v3" / BOOK_SLUG / "chapter-map.v1.json"
INDEX_PATH = ROOT / "apps/admin/public/content/visual-candidates/index.v1.json"
CONTRACT_PATH = ROOT / "docs/ai/visual-render-contract.v1.json"
RAW_PREFIX = "https://raw.githubusercontent.com/Manav326/QuantaEdge/main/"


class TextbookVisualEndToEndContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalog = json.loads(CATALOG_PATH.read_text(encoding="utf-8"))
        cls.pack = json.loads(PACK_PATH.read_text(encoding="utf-8"))
        cls.chapter_map = json.loads(CHAPTER_MAP_PATH.read_text(encoding="utf-8"))
        cls.index = json.loads(INDEX_PATH.read_text(encoding="utf-8"))
        cls.contract = json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))

    def test_committed_catalog_tracks_its_exact_candidate_pack_and_tracked_assets(self):
        self.assertEqual("quantaedge.visual-attachment-catalog.v3", self.catalog["schema_version"])
        self.assertEqual("quantaedge.visual-candidate-pack.v3", self.pack["schema_version"])
        self.assertEqual(self.pack["source"]["pdf_sha256"], self.catalog["source"]["pdf_sha256"])
        self.assertEqual(self.pack["source"]["book_id"], self.catalog["source"]["book_id"])
        self.assertTrue(self.catalog["source"]["title"])
        self.assertEqual("quantaedge.visual-catalog-index.v1", self.index["schema_version"])
        catalog_slugs = {path.name.removesuffix(".v3.json") for path in (ROOT / "apps/admin/public/content/visual-candidates").glob("*.v3.json")}
        self.assertEqual(catalog_slugs, {row["book_slug"] for row in self.index["catalogs"]})
        indexed = next(row for row in self.index["catalogs"] if row["book_slug"] == BOOK_SLUG)
        self.assertEqual(self.catalog["source"]["book_id"], indexed["source_book_id"])
        self.assertEqual(self.catalog["source"]["pdf_sha256"], indexed["source_pdf_sha256"])
        self.assertEqual(self.catalog["source"]["title"], indexed["title"])
        self.assertEqual(self.pack["source"]["pdf_sha256"], self.chapter_map["source_pdf_sha256"])
        self.assertIs(self.catalog["summary"]["publication_allowed"], False)
        self.assertIs(self.pack["summary"]["publication_allowed"], False)
        self.assertIs(self.chapter_map["publication_allowed"], False)
        self.assertIs(self.chapter_map["human_review_required"], True)

        pack_by_id = {row["candidate_id"]: row for row in self.pack["candidates"]}
        catalog_by_id = {row["candidate_id"]: row for row in self.catalog["candidates"]}
        self.assertEqual(set(pack_by_id), set(catalog_by_id), "Catalog IDs must match the source candidate pack exactly.")
        self.assertGreater(len(catalog_by_id), 0, "The candidate pack must be non-empty; current records are still a sample, not whole-book coverage.")

        for candidate_id, item in catalog_by_id.items():
            original = pack_by_id[candidate_id]
            with self.subTest(candidate=candidate_id):
                self.assertEqual(original["candidate_kind"], item["candidate_kind"])
                self.assertEqual(original["source_pdf_sha256"], item["source_pdf_sha256"])
                self.assertEqual(original["source_pdf_page_number"], item["source_pdf_page_number"])
                self.assertEqual(original["source_asset_sha256"], item["source_asset_sha256"])
                self.assertIs(item["publication_allowed"], False)
                self.assertIs(item["human_review_required"], True)
                self.assertEqual("NEEDS_REVIEW", item["visual_review_status"])
                self.assertEqual("SOURCE_REFERENCE_ONLY", item["rights_status"])
                self.assertGreaterEqual(item["source_pdf_page_number"], 1)
                self.assertLessEqual(item["source_pdf_page_number"], self.pack["source"]["page_count"])
                for field in ("image_url", "source_page_url"):
                    url = item[field]
                    self.assertTrue(url.startswith(RAW_PREFIX), f"{field} is not a repository asset URL.")
                    path = ROOT / url.removeprefix(RAW_PREFIX)
                    self.assertTrue(path.is_file(), f"{field} does not resolve to a checked-in file: {path}")

    def test_chapter_suggestions_are_ordered_contiguous_and_review_only(self):
        chapters = self.chapter_map["chapters"]
        self.assertGreater(len(chapters), 0)
        self.assertTrue(all(a["source_pdf_page_end"] + 1 == b["source_pdf_page_start"]
                            for a, b in zip(chapters, chapters[1:])))
        self.assertTrue(all(a["source_pdf_page_start"] <= a["source_pdf_page_end"] for a in chapters))
        self.assertEqual(272, chapters[-1]["source_pdf_page_end"])
        self.assertTrue(all(item["chapter_map_review_status"] == self.chapter_map["mapping_status"]
                            for item in self.catalog["candidates"]))

    def test_admin_prevents_attaching_a_source_to_an_unrelated_class_or_subject(self):
        source = (ROOT / "apps/admin/app/content/page.tsx").read_text(encoding="utf-8")
        self.assertIn("function visualSourceMatchesLesson", source)
        self.assertIn("function visualCatalogMatchesLesson", source)
        self.assertIn("index.v1.json", source)
        self.assertNotIn("class6-curiosity-en.v3.json", source)
        self.assertIn("visualCatalogMatchesLesson(targetRow,visualCatalog)", source)
        self.assertIn("!visualCatalogMatchesTarget", source)
        self.assertIn("Choose a lesson in the matching class and subject.", source)

    def test_renderers_follow_the_shared_visual_contract(self):
        expected_kinds = {item["id"] for item in self.contract["visual_kinds"]}
        web = (ROOT / "apps/web/app/student/learn/LearnClient.tsx").read_text(encoding="utf-8")
        android = (ROOT / "apps/android/app/src/main/java/com/quantaedge/android/ui/LessonContentBlock.kt").read_text(encoding="utf-8")
        admin = (ROOT / "apps/admin/app/content/page.tsx").read_text(encoding="utf-8")

        for kind in expected_kinds - {"TEXTBOOK_FIGURE", "UNCLASSIFIED"}:
            with self.subTest(kind=kind):
                self.assertIn(kind, web)
                self.assertIn(kind, android)
        for required in ("alt_hi", "caption_hi", "source_book_title", "source_pdf_page_number",
                         "source_page_url", "qe-visual-lightbox", "qe-lesson-source-attribution"):
            with self.subTest(web_field=required):
                self.assertIn(required, web)
        for required in ('data.textAny("alt_hi")', 'data.textAny("caption_hi")',
                         'sourcePageUrl', 'LessonPlayableMedia', 'LessonAnimatedImage',
                         'AnimatedImageDrawable', 'Dialog'):
            with self.subTest(android_feature=required):
                self.assertIn(required, android)
        for required in ("LEARNER VISUAL PREVIEW", "caption_hi", "alt_hi", "Source provenance"):
            with self.subTest(admin_feature=required):
                self.assertIn(required, admin)
        self.assertIn("Android 9", self.contract["visual_kinds"][-1]["presentation"] if self.contract["visual_kinds"][-1]["id"] == "SEQUENCE" else
                      next(item["presentation"] for item in self.contract["visual_kinds"] if item["id"] == "SEQUENCE"))

    def test_api_requires_bilingual_accessibility_and_paired_translations(self):
        source = (ROOT / "services/api/src/main/java/com/quantaedge/api/AdminContentController.java").read_text(encoding="utf-8")
        self.assertIn('content.getOrDefault("alt_hi","")', source)
        self.assertIn('content.getOrDefault("title_hi","")', source)
        self.assertIn('content.getOrDefault("description_hi","")', source)
        self.assertIn('content.getOrDefault("caption_hi","")', source)
        self.assertIn("Add meaningful Hindi alternative text", source)
        self.assertIn("Provide the visual caption in both English and Hindi", source)


if __name__ == "__main__":
    unittest.main()
