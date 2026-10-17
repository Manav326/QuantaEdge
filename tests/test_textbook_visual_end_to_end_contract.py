import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BOOK_SLUG = "class6-en-science-9c1315ba"
CATALOG_DIR = ROOT / "data/textbook-visual-candidates-v3" / "catalogs"
CATALOG_PATH = CATALOG_DIR / f"{BOOK_SLUG}.v3.json"
PACK_PATH = ROOT / "data/textbook-visual-candidates-v3" / BOOK_SLUG / "candidates.v3.json"
CHAPTER_MAP_PATH = ROOT / "data/textbook-visual-candidates-v3" / BOOK_SLUG / "chapter-map.v1.json"
INDEX_PATH = ROOT / "data/textbook-visual-candidates-v3/index.v1.json"
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
        catalog_slugs = {path.name.removesuffix(".v3.json") for path in CATALOG_DIR.glob("*.v3.json")}
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
                self.assertEqual(self.pack["source"]["book_id"], item["source_book_id"])
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
                    relative_path = url.removeprefix(RAW_PREFIX).replace(
                        "apps/web/public/content/visual-assets/", "data/textbook-visual-candidates-v3/"
                    )
                    path = ROOT / relative_path
                    self.assertTrue(path.is_file(), f"{field} does not resolve to a checked-in file: {path}")

    def test_every_catalog_index_pack_and_tracked_asset_agree_before_persistence(self):
        catalog_dir = CATALOG_DIR
        index_by_slug = {item["book_slug"]: item for item in self.index["catalogs"]}
        catalog_paths = sorted(catalog_dir.glob("*.v3.json"))
        self.assertGreater(len(catalog_paths), 0)
        self.assertEqual({path.name.removesuffix(".v3.json") for path in catalog_paths}, set(index_by_slug))

        for catalog_path in catalog_paths:
            slug = catalog_path.name.removesuffix(".v3.json")
            catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
            pack_path = ROOT / "data/textbook-visual-candidates-v3" / slug / "candidates.v3.json"
            self.assertTrue(pack_path.is_file(), f"Missing persisted source candidate pack: {pack_path}")
            pack = json.loads(pack_path.read_text(encoding="utf-8"))
            source = catalog["source"]
            pack_source = pack["source"]
            entry = index_by_slug[slug]

            with self.subTest(book_slug=slug):
                self.assertEqual("quantaedge.visual-attachment-catalog.v3", catalog["schema_version"])
                self.assertEqual("quantaedge.visual-candidate-pack.v3", pack["schema_version"])
                self.assertEqual(source["book_id"], pack_source["book_id"])
                self.assertEqual(source["pdf_sha256"], pack_source["pdf_sha256"])
                self.assertEqual(source["pdf_sha256"], entry["source_pdf_sha256"])
                self.assertEqual(source["book_id"], entry["source_book_id"])
                self.assertEqual(len(catalog["candidates"]), len(pack["candidates"]))
                self.assertEqual(len(catalog["candidates"]), entry["candidate_count"])
                self.assertIs(catalog["summary"]["publication_allowed"], False)
                self.assertIs(pack["summary"]["publication_allowed"], False)
                source_items = {item["candidate_id"]: item for item in pack["candidates"]}
                catalog_items = {item["candidate_id"]: item for item in catalog["candidates"]}
                self.assertEqual(set(source_items), set(catalog_items))
                for candidate_id, item in catalog_items.items():
                    original = source_items[candidate_id]
                    with self.subTest(book_slug=slug, candidate=candidate_id):
                        self.assertEqual(source["book_id"], item["source_book_id"])
                        self.assertEqual(source["pdf_sha256"], item["source_pdf_sha256"])
                        self.assertEqual(original["source_asset_sha256"], item["source_asset_sha256"])
                        self.assertIs(item["publication_allowed"], False)
                        self.assertIs(item["human_review_required"], True)
                        self.assertEqual("NEEDS_REVIEW", item["visual_review_status"])
                        self.assertEqual("SOURCE_REFERENCE_ONLY", item["rights_status"])
                        for field in ("image_url", "source_page_url"):
                            url = item[field]
                            self.assertTrue(url.startswith(RAW_PREFIX), f"{slug}/{candidate_id}: {field} not repository-hosted")
                            relative_path = url.removeprefix(RAW_PREFIX).replace(
                                "apps/web/public/content/visual-assets/", "data/textbook-visual-candidates-v3/"
                            )
                            local_file = ROOT / relative_path
                            self.assertTrue(local_file.is_file(), f"{slug}/{candidate_id}: missing tracked asset {local_file}")

        self.assertEqual(len(index_by_slug), self.index["summary"]["catalog_count"])
        self.assertFalse(self.index["summary"]["publication_allowed"])
        self.assertTrue(self.index["summary"]["review_required"])

    def test_legacy_class6_curiosity_subset_is_retired_in_favor_of_exhaustive_pack(self):
        legacy_slug = "class6-curiosity-en"
        current_slug = "class6-en-science-9c1315ba"
        self.assertFalse((ROOT / "data/textbook-visual-candidates-v3" / legacy_slug).exists())
        self.assertFalse((CATALOG_DIR / f"{legacy_slug}.v3.json").exists())
        self.assertTrue((ROOT / "data/textbook-visual-candidates-v3" / current_slug / "candidates.v3.json").is_file())
        self.assertFalse(any(row["book_slug"] == legacy_slug for row in self.index["catalogs"]))
        current = next(row for row in self.index["catalogs"] if row["book_slug"] == current_slug)
        self.assertEqual(1472, current["candidate_count"])

    def test_generated_catalogs_are_not_kept_under_application_public_directories(self):
        for relative in (
            "apps/admin/public/content/visual-candidates",
            "apps/web/public/content/visual-assets",
        ):
            directory = ROOT / relative
            if directory.exists():
                self.assertFalse(any(path.is_file() for path in directory.rglob("*")),
                                 f"Generated review data remains outside data/: {relative}")
        self.assertTrue(CATALOG_PATH.is_file())
        self.assertTrue(INDEX_PATH.is_file())

    def test_layer4_5_artifact_contract_uses_relative_paths_and_expanded_package(self):
        workflow = (ROOT / ".github/workflows/textbook-visual-candidates-v3.yml").read_text(encoding="utf-8")
        pipeline = (ROOT / "scripts/quantaedge_textbook_pipeline.py").read_text(encoding="utf-8")
        self.assertIn('--package "ghcr.io/${GITHUB_REPOSITORY_OWNER,,}/quantaedge-textbook-visuals"', workflow)
        self.assertNotIn("ghcr.io/" + chr(92) + "${GITHUB_REPOSITORY_OWNER,,}/quantaedge-textbook-visuals", workflow)
        self.assertIn('f"{bundle.name}:application/gzip"', pipeline)
        self.assertIn('],cwd=sroot,check=True)', pipeline)
        self.assertIn('f"{raw.name}:application/json"', pipeline)
        self.assertIn('],cwd=raw.parent,check=True)', pipeline)

    def test_chapter_suggestions_are_ordered_contiguous_and_review_only(self):
        chapters = self.chapter_map["chapters"]
        self.assertGreater(len(chapters), 0)
        self.assertTrue(all(a["source_pdf_page_end"] + 1 == b["source_pdf_page_start"]
                            for a, b in zip(chapters, chapters[1:])))
        self.assertTrue(all(a["source_pdf_page_start"] <= a["source_pdf_page_end"] for a in chapters))
        self.assertEqual(272, chapters[-1]["source_pdf_page_end"])
        mapped_statuses = {
            item.get("chapter_map_review_status")
            for item in self.catalog["candidates"]
            if item.get("chapter_map_review_status")
        }
        if mapped_statuses:
            self.assertEqual({self.chapter_map["mapping_status"]}, mapped_statuses)
        else:
            # The canonical exhaustive pack is not yet chapter-mapped. Keep the sidecar
            # TOC map review-only instead of claiming its suggestions were applied.
            self.assertFalse(self.chapter_map["publication_allowed"])
            self.assertTrue(all(
                item["publication_allowed"] is False and item["human_review_required"] is True
                for item in self.catalog["candidates"]
            ))

    def test_admin_prevents_attaching_a_source_to_an_unrelated_subject(self):
        source = (ROOT / "apps/admin/app/content/page.tsx").read_text(encoding="utf-8")
        self.assertIn("function visualSourceMatchesLesson", source)
        self.assertIn("sourceClass!==lessonClass", source)
        self.assertIn("विज्ञान", source)
        self.assertIn("गणित", source)
        self.assertIn("normalize('NFKC')", source)
        self.assertIn("function visualCatalogMatchesLesson", source)
        self.assertIn("index.v1.json", source)
        self.assertNotIn("class6-curiosity-en.v3.json", source)
        self.assertIn("visualCatalogMatchesLesson(targetRow,visualCatalog)", source)
        self.assertIn("!visualCatalogMatchesTarget", source)
        self.assertIn("Choose a lesson with the matching class and subject.", source)
        self.assertIn("visualCandidateRelevanceScore", source)
        self.assertIn("visualCandidateBlockRelevanceScore", source)
        self.assertIn("source_pdf_sha256===candidate.source_pdf_sha256", source)
        self.assertIn("strongest source-text overlap", source)

    def test_renderers_follow_the_shared_visual_contract(self):
        expected_kinds = {item["id"] for item in self.contract["visual_kinds"]}
        web = (ROOT / "apps/web/app/student/learn/LearnClient.tsx").read_text(encoding="utf-8")
        android = (ROOT / "apps/android/app/src/main/java/com/quantaedge/android/ui/LessonContentBlock.kt").read_text(encoding="utf-8")
        admin = (ROOT / "apps/admin/app/content/page.tsx").read_text(encoding="utf-8")

        for kind in expected_kinds - {"TEXTBOOK_FIGURE", "UNCLASSIFIED"}:
            with self.subTest(kind=kind):
                self.assertIn(kind, web)
                self.assertIn(kind, android)
        for required in ("alt_hi", "key+'_hi'", "source_book_title", "source_pdf_page_number",
                         "source_page_url", "qe-visual-lightbox", "qe-lesson-source-attribution"):
            with self.subTest(web_field=required):
                self.assertIn(required, web)
        for required in ('data.textAny("alt_hi")', 'data.textAny("caption_hi")',
                         'sourcePageUrl', 'LessonPlayableMedia', 'LessonAnimatedImage',
                         'AnimatedImageDrawable', 'Dialog'):
            with self.subTest(android_feature=required):
                self.assertIn(required, android)
        for required in ("LEARNER VISUAL PREVIEW", "caption_hi", "alt_hi", "Source provenance", "qe-admin-visual-lightbox", "View source page", "फ़ोटो"):
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
        self.assertIn("isGeneratedV3VisualAssetUrl", source)
        self.assertIn("existingSourceVisualUrls.contains(submittedMediaUrl)", source)
        self.assertIn("visualSourceMappingValidationError", source)
        self.assertIn('content.get("source_class")', source)
        self.assertIn('content.get("source_subject")', source)
        self.assertIn("if(sourceMappingIssue!=null) throw badRequest(sourceMappingIssue);", source)
        self.assertIn("private void replaceBlocks(long lessonId, Object value, AuthContext context)", source)


if __name__ == "__main__":
    unittest.main()
