import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

try:
    import pymupdf as fitz
except ImportError:
    fitz = None

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "import_official_ncert_books_and_chapters.py"
sys.path.insert(0, str(SCRIPT.parent))
spec = importlib.util.spec_from_file_location("qe_official_ncert_book_split_importer_tested", SCRIPT)
importer = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = importer
spec.loader.exec_module(importer)


@unittest.skipIf(fitz is None, "PyMuPDF is installed in the content-import CI job")
class OfficialNcertBookChapterTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def make_book(self, unknown_title=False):
        path = self.root / "book.pdf"
        with fitz.open() as doc:
            page = doc.new_page()
            page.insert_text((50, 50), "Contents")
            page.insert_text((50, 85), "1 Patterns in Mathematics 2")
            page.insert_text((50, 105), "2 Lines and Angles 4")

            page = doc.new_page()
            page.insert_text((50, 60), "Chapter 1")
            page.insert_text((50, 100), "Unknown First Title" if unknown_title else "Patterns in Mathematics")
            page.insert_text((50, 150), "A first section of the first chapter.")
            doc.new_page()
            page = doc.new_page()
            page.insert_text((50, 60), "Chapter 2")
            page.insert_text((50, 100), "Lines and Angles")
            page.insert_text((50, 150), "A first section of the second chapter.")
            doc.new_page()
            doc.save(path)
        return path

    def test_dedupes_chapters_repeated_for_each_lesson(self):
        rows = [
            {"class_code": "6", "subject_code": "maths", "chapter_id": 601,
             "chapter_code": "patterns-in-mathematics", "chapter_name": "गणित में पैटर्न",
             "chapter_sort_order": 1, "chapter_active": True, "lesson_id": 1},
            {"class_code": "6", "subject_code": "maths", "chapter_id": 601,
             "chapter_code": "patterns-in-mathematics", "chapter_name": "गणित में पैटर्न",
             "chapter_sort_order": 1, "chapter_active": True, "lesson_id": 2},
            {"class_code": "6", "subject_code": "maths", "chapter_id": 602,
             "chapter_code": "lines-and-angles", "chapter_name": "रेखाएँ और कोण",
             "chapter_sort_order": 2, "chapter_active": True, "lesson_id": 3},
            {"class_code": "6", "subject_code": "science", "chapter_id": 603,
             "chapter_code": "not-maths", "chapter_name": "Wrong track",
             "chapter_sort_order": 1, "chapter_active": True},
            {"class_code": "6", "subject_code": "maths", "chapter_id": 604,
             "chapter_code": "inactive", "chapter_name": "Inactive chapter",
             "chapter_sort_order": 3, "chapter_active": False},
        ]
        result = importer.dedupe_live_chapters(rows, "6", "maths")
        self.assertEqual([601, 602], [row["chapter_id"] for row in result])

    def test_filename_slots_are_stable(self):
        self.assertEqual(
            "NCERT_Class_6_Maths_English_BOOK.pdf",
            importer.book_filename("6", "maths", "english"),
        )
        self.assertEqual(
            "NCERT_Class_8_Science_Hindi_BOOK.pdf",
            importer.book_filename("8", "science", "hindi"),
        )

    def test_create_import_plan_reads_books_from_books_directory(self):
        books_dir = self.root / "books"
        books_dir.mkdir()
        chapters_by_track = {}
        for grade in importer.CLASSES:
            for subject in importer.SUBJECTS:
                chapters_by_track[(grade, subject)] = [{
                    "chapter_id": int(grade) * 1000 + (1 if subject == "maths" else 2),
                    "chapter_code": f"test-{grade}-{subject}",
                    "chapter_name": f"Test chapter {grade} {subject}",
                }]
                for language in importer.LANGUAGES:
                    path = books_dir / importer.book_filename(grade, subject, language)
                    with fitz.open() as doc:
                        page = doc.new_page()
                        page.insert_text((50, 50), f"Test book Class {grade} {subject} {language}")
                        doc.save(path)

        def fake_detect(path, grade, subject, language, chapters):
            chapter = chapters[0]
            return [{
                **chapter,
                "start_page": 1,
                "end_page": 1,
                "page_count": 1,
                "chapter_order": 1,
                "mapping_note": "Test-only mapping",
            }]

        with patch.object(importer, "detect_chapter_ranges", side_effect=fake_detect):
            records, _warnings = importer.create_import_plan(
                self.root,
                chapters_by_track,
                50 * 1024 * 1024,
                books_dir=books_dir,
            )

        books = [record for record in records if record["scope"] == "SUBJECT_BOOK"]
        splits = [record for record in records if record["scope"] == "CHAPTER_PDF"]
        self.assertEqual(12, len(books))
        self.assertEqual(12, len(splits))
        self.assertTrue(all(Path(record["path"]).parent == books_dir for record in books))
        self.assertTrue(all("private-review" in Path(record["path"]).parts for record in splits))

    def test_english_only_import_does_not_require_hindi_books(self):
        books_dir = self.root / "books"
        books_dir.mkdir()
        chapters_by_track = {}
        for grade in importer.CLASSES:
            for subject in importer.SUBJECTS:
                chapters_by_track[(grade, subject)] = [{
                    "chapter_id": int(grade) * 1000 + (1 if subject == "maths" else 2),
                    "chapter_code": f"test-{grade}-{subject}",
                    "chapter_name": f"Test chapter {grade} {subject}",
                }]
                path = books_dir / importer.book_filename(grade, subject, "english")
                with fitz.open() as doc:
                    page = doc.new_page()
                    page.insert_text((50, 50), f"Test book Class {grade} {subject} English")
                    doc.save(path)

        def fake_detect(path, grade, subject, language, chapters):
            chapter = chapters[0]
            return [{
                **chapter,
                "start_page": 1,
                "end_page": 1,
                "page_count": 1,
                "chapter_order": 1,
                "mapping_note": "Test-only mapping",
            }]

        with patch.object(importer, "detect_chapter_ranges", side_effect=fake_detect):
            records, _warnings = importer.create_import_plan(
                self.root,
                chapters_by_track,
                50 * 1024 * 1024,
                books_dir=books_dir,
                languages=("english",),
            )

        books = [record for record in records if record["scope"] == "SUBJECT_BOOK"]
        splits = [record for record in records if record["scope"] == "CHAPTER_PDF"]
        self.assertEqual(6, len(books))
        self.assertEqual(6, len(splits))
        self.assertEqual({"english"}, {record["language"] for record in records})
        self.assertTrue(all(Path(record["path"]).parent == books_dir for record in books))
        self.assertTrue(all("private-review" in Path(record["path"]).parts for record in splits))

    def test_input_presence_accepts_six_english_books_and_rejects_incomplete_language_set(self):
        books_dir = self.root / "books"
        books_dir.mkdir()
        for grade in importer.CLASSES:
            for subject in importer.SUBJECTS:
                path = books_dir / importer.book_filename(grade, subject, "english")
                with fitz.open() as doc:
                    doc.new_page()
                    doc.save(path)
        importer.validate_input_book_presence(books_dir, ("english",))
        with self.assertRaisesRegex(FileNotFoundError, "Missing NCERT_Class_6_Maths_Hindi_BOOK.pdf"):
            importer.validate_input_book_presence(books_dir, ("english", "hindi"))
        missing = books_dir / importer.book_filename("8", "science", "english")
        missing.unlink()
        with self.assertRaisesRegex(FileNotFoundError, "NCERT_Class_8_Science_English_BOOK.pdf"):
            importer.validate_input_book_presence(books_dir, ("english",))

    def test_no_chapter_extracts_written_until_all_book_mappings_validate(self):
        books_dir = self.root / "books"
        books_dir.mkdir()
        chapters_by_track = {}
        for grade in importer.CLASSES:
            for subject in importer.SUBJECTS:
                chapters_by_track[(grade, subject)] = [{
                    "chapter_id": int(grade) * 1000 + (1 if subject == "maths" else 2),
                    "chapter_code": f"test-{grade}-{subject}",
                    "chapter_name": f"Test chapter {grade} {subject}",
                }]
                path = books_dir / importer.book_filename(grade, subject, "english")
                with fitz.open() as doc:
                    doc.new_page()
                    doc.save(path)

        def fail_on_last_book(path, grade, subject, language, chapters):
            if grade == "8" and subject == "science":
                raise ValueError("test-only unknown chapter title")
            chapter = chapters[0]
            return [{
                **chapter, "start_page": 1, "end_page": 1, "page_count": 1,
                "chapter_order": 1, "mapping_note": "Test-only mapping",
            }]

        with patch.object(importer, "detect_chapter_ranges", side_effect=fail_on_last_book):
            with self.assertRaisesRegex(ValueError, "test-only unknown chapter title"):
                importer.create_import_plan(
                    self.root, chapters_by_track, 50 * 1024 * 1024,
                    books_dir=books_dir, languages=("english",),
                )
        output_dir = self.root / "private-review" / "ncert-book-splits"
        self.assertFalse((output_dir / "class-6").exists())
        self.assertFalse((output_dir / "class-7").exists())
        self.assertFalse((output_dir / "class-8").exists())

    def test_existing_chapter_extract_is_never_silently_overwritten(self):
        books_dir = self.root / "books"
        books_dir.mkdir()
        chapters_by_track = {}
        for grade in importer.CLASSES:
            for subject in importer.SUBJECTS:
                code = f"test-{grade}-{subject}"
                chapters_by_track[(grade, subject)] = [{
                    "chapter_id": int(grade) * 1000 + (1 if subject == "maths" else 2),
                    "chapter_code": code, "chapter_name": f"Test chapter {grade} {subject}",
                }]
                path = books_dir / importer.book_filename(grade, subject, "english")
                with fitz.open() as doc:
                    page = doc.new_page()
                    page.insert_text((50, 50), f"Book {grade} {subject}")
                    doc.save(path)

        output_dir = self.root / "private-review" / "ncert-book-splits"
        target = output_dir / "class-6" / "maths" / "english" / importer.chapter_output_filename(
            "6", "maths", "english", "test-6-maths"
        )
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(b"preserve existing local output")
        original = target.read_bytes()

        def fake_detect(path, grade, subject, language, chapters):
            chapter = chapters[0]
            return [{
                **chapter, "start_page": 1, "end_page": 1, "page_count": 1,
                "chapter_order": 1, "mapping_note": "Test-only mapping",
            }]

        with patch.object(importer, "detect_chapter_ranges", side_effect=fake_detect):
            with self.assertRaisesRegex(FileExistsError, "Nothing was overwritten"):
                importer.create_import_plan(
                    self.root, chapters_by_track, 50 * 1024 * 1024,
                    books_dir=books_dir, languages=("english",),
                )
        self.assertEqual(original, target.read_bytes())
        self.assertFalse((output_dir / "class-7").exists())
        self.assertFalse((output_dir / "class-8").exists())

    def test_failure_report_never_overwrites_existing_apply_checkpoint(self):
        checkpoint = self.root / "checkpoint.json"
        checkpoint.write_text('{"result":"APPLY_IN_PROGRESS"}', encoding="utf-8")
        before = checkpoint.read_text(encoding="utf-8")
        args = importer.argparse.Namespace(
            root=str(self.root), books_dir="", languages=["english"],
            api_url="http://localhost:8080", apply=True, report=str(checkpoint),
        )
        failure_path = importer.write_failure_report(args, RuntimeError("simulated failure"))
        self.assertNotEqual(checkpoint, failure_path)
        self.assertTrue(failure_path.is_file())
        self.assertEqual(before, checkpoint.read_text(encoding="utf-8"))
        payload = __import__("json").loads(failure_path.read_text(encoding="utf-8"))
        self.assertEqual("STOPPED_SAFELY", payload["result"])
        self.assertTrue(payload["application_writes_may_have_started"])
        self.assertFalse(payload["credentials_recorded"])

    def test_report_record_sync_keeps_draft_ids_and_status(self):
        row = {
            "class_code": "6", "subject_code": "maths", "language": "english",
            "scope": "CHAPTER_PDF", "chapter_code": "test", "chapter_id": 6001,
            "sha256": "abc", "action": "PENDING", "path": "private/file.pdf",
        }
        report = {"files": [importer.make_report_record(row)]}
        row.update({"asset_id": 123, "document_id": 456, "status": "DRAFT", "action": "CREATED_DRAFT"})
        importer.sync_report_record(report, row)
        self.assertEqual(1, len(report["files"]))
        self.assertEqual(123, report["files"][0]["asset_id"])
        self.assertEqual(456, report["files"][0]["document_id"])
        self.assertEqual("DRAFT", report["files"][0]["status"])
        self.assertNotIn("path", report["files"][0])

    def test_detects_chapter_openers_and_exact_ranges(self):
        path = self.make_book()
        chapters = [
            {"chapter_id": 601, "chapter_code": "patterns-in-mathematics", "chapter_name": "गणित में पैटर्न"},
            {"chapter_id": 602, "chapter_code": "lines-and-angles", "chapter_name": "रेखाएँ और कोण"},
        ]
        progress_messages = []
        ranges = importer.detect_chapter_ranges(
            path, "6", "maths", "english", chapters, progress=progress_messages.append
        )
        self.assertTrue(any("PDF text scan:" in item for item in progress_messages))
        self.assertTrue(any("Matched chapter 1/2:" in item for item in progress_messages))
        self.assertEqual(
            [(2, 3), (4, 5)],
            [(item["start_page"], item["end_page"]) for item in ranges],
        )
        self.assertEqual(
            ["patterns-in-mathematics", "lines-and-angles"],
            [item["chapter_code"] for item in ranges],
        )

    def test_detects_heading_when_pdf_text_extraction_order_is_not_visual_order(self):
        path = self.root / "class7-maths-geometric-twins.pdf"
        with fitz.open() as doc:
            page = doc.new_page()
            # Simulate PDFs whose internal content order puts body text before the visual
            # chapter heading. The exact title remains positioned near the top of the page.
            for index in range(20):
                page.insert_text((50, 180 + index * 20), f"Section text {index}")
            page.insert_text((50, 80), "Geometric Twins")
            doc.save(path)

        chapters = [{
            "chapter_id": 709,
            "chapter_code": "geometric-twins",
            "chapter_name": "ज्यामितीय जुड़वाँ आकृतियाँ",
        }]
        original_bytes = path.read_bytes()
        ranges = importer.detect_chapter_ranges(path, "7", "maths", "english", chapters)
        self.assertEqual(1, ranges[0]["start_page"])
        self.assertEqual(1, ranges[0]["end_page"])
        self.assertEqual(original_bytes, path.read_bytes())

    def test_fails_closed_when_a_chapter_title_cannot_be_found(self):
        path = self.make_book(unknown_title=True)
        chapters = [
            {"chapter_id": 601, "chapter_code": "patterns-in-mathematics", "chapter_name": "गणित में पैटर्न"},
            {"chapter_id": 602, "chapter_code": "lines-and-angles", "chapter_name": "रेखाएँ और कोण"},
        ]
        with self.assertRaisesRegex(ValueError, "cannot identify the opening page"):
            importer.detect_chapter_ranges(path, "6", "maths", "english", chapters)

    def test_splits_preserve_requested_page_count(self):
        source = self.make_book()
        output = self.root / "chapter.pdf"
        result = importer.split_chapter_pdf(source, output, 2, 3)
        self.assertEqual(2, result["page_count"])
        with fitz.open(output) as pdf:
            self.assertEqual(2, pdf.page_count)
            self.assertIn("Patterns in Mathematics", pdf[0].get_text("text"))

    def test_baudhayana_aliases_accept_the_nc ert_diacritic_spelling(self):
        chapter = {
            "chapter_id": 809,
            "chapter_code": "baudhayana-pythagoras-theorem",
            "chapter_name": "बौधायन-पाइथागोरस प्रमेय",
        }
        aliases = importer.aliases_for("8", "maths", "english", chapter)
        self.assertIn("The Baudhāyana-Pythagoras Theorem", aliases)
        self.assertTrue(
            importer.line_matches_title(
                "THE BAUDHĀYANA-PYTHAGORAS THEOREM",
                "The Baudhāyana-Pythagoras Theorem",
            )
        )

    def test_detects_baudhayana_chapter_heading_with_macron(self):
        path = self.root / "class8-maths-baudhayana.pdf"
        with fitz.open() as doc:
            page = doc.new_page()
            page.insert_text((50, 50), "Contents")
            page.insert_text((50, 90), "Chapter 1 Fractions in Disguise 1")
            page.insert_text((50, 110), "Chapter 2 The Baudhāyana-Pythagoras Theorem 20")

            page = doc.new_page()
            page.insert_text((50, 60), "Chapter 1")
            page.insert_text((50, 100), "Fractions in Disguise")
            page.insert_text((50, 150), "Fractions content.")

            page = doc.new_page()
            page.insert_text((50, 60), "Chapter 2")
            page.insert_text((50, 100), "THE BAUDHĀYANA-PYTHAGORAS THEOREM")
            page.insert_text((50, 150), "The theorem chapter content.")
            doc.save(path)

        chapters = [
            {"chapter_id": 808, "chapter_code": "fractions-in-disguise", "chapter_name": "भिन्नों के छिपे रूप"},
            {"chapter_id": 809, "chapter_code": "baudhayana-pythagoras-theorem", "chapter_name": "बौधायन-पाइथागोरस प्रमेय"},
        ]
        ranges = importer.detect_chapter_ranges(path, "8", "maths", "english", chapters)
        self.assertEqual(
            [(2, 2), (3, 3)],
            [(row["start_page"], row["end_page"]) for row in ranges],
        )

    def test_short_official_titles_down_to_four_characters_are_supported(self):
        for code, title in (("area", "Area"), ("circle", "Circle"), ("square", "Square")):
            with self.subTest(title=title):
                chapter = {
                    "chapter_id": 814,
                    "chapter_code": code,
                    "chapter_name": title,
                }
                self.assertEqual(
                    [title],
                    importer.aliases_for("8", "maths", "english", chapter),
                )

        too_short = {
            "chapter_id": 899,
            "chapter_code": "one-letter-title",
            "chapter_name": "Ab",
        }
        self.assertEqual(
            [],
            importer.aliases_for("8", "maths", "english", too_short),
        )

    def test_short_area_heading_requires_standalone_text_near_page_top(self):
        path = self.root / "class8-maths-area.pdf"
        with fitz.open() as doc:
            page = doc.new_page()
            page.insert_text((50, 50), "Contents")
            page.insert_text((50, 90), "13 Algebra Play 80")
            page.insert_text((50, 110), "14 Area 90")

            page = doc.new_page()
            page.insert_text((50, 70), "Chapter 13")
            page.insert_text((50, 110), "Algebra Play")
            page.insert_text((50, 200), "This chapter mentions the area of a shape.")

            page = doc.new_page()
            # A standalone lower-page "Area" section heading is not a chapter opener.
            page.insert_text((50, 500), "Area")
            page.insert_text((50, 540), "An explanation of area in a worked example.")

            page = doc.new_page()
            page.insert_text((50, 60), "Chapter 14")
            page.insert_text((50, 100), "Area")
            page.insert_text((50, 150), "The Class 8 Maths chapter begins here.")
            doc.save(path)

        chapters = [
            {"chapter_id": 813, "chapter_code": "algebra-play", "chapter_name": "बीजगणित का खेल"},
            {"chapter_id": 814, "chapter_code": "area", "chapter_name": "क्षेत्रफल"},
        ]
        ranges = importer.detect_chapter_ranges(path, "8", "maths", "english", chapters)
        self.assertEqual(
            [(2, 3), (4, 4)],
            [(row["start_page"], row["end_page"]) for row in ranges],
        )

    def test_uses_hindi_curriculum_title_alias_for_hindi_book(self):
        self.assertIn(
            "विज्ञान का अद्भुत संसार",
            importer.HINDI_TITLE_ALIASES[("6", "science", "wonderful-world-science")],
        )

    def test_part_one_and_part_two_are_combined_without_modifying_originals(self):
        part1 = self.root / importer.part_book_filename("7", "maths", "english", 1)
        part2 = self.root / importer.part_book_filename("7", "maths", "english", 2)
        for path, title in ((part1, "Large Numbers Around Us"), (part2, "Geometric Twins")):
            with fitz.open() as doc:
                page = doc.new_page()
                page.insert_text((50, 50), title)
                page.insert_text((50, 100), "Source part content remains on this page.")
                doc.save(path)
        hash1, hash2 = importer.sha256_file(part1), importer.sha256_file(part2)
        output_dir = self.root / "private-review" / "ncert-book-splits"
        source = importer.prepare_source_book(self.root, "7", "maths", "english", output_dir)
        self.assertTrue(source["merged_from_parts"])
        self.assertEqual(2, importer.inspect_book(Path(source["path"]))["page_count"])
        combined_hash = importer.sha256_file(Path(source["path"]))
        resumed = importer.prepare_source_book(self.root, "7", "maths", "english", output_dir)
        self.assertEqual(combined_hash, importer.sha256_file(Path(resumed["path"])))
        self.assertEqual(hash1, importer.sha256_file(part1))
        self.assertEqual(hash2, importer.sha256_file(part2))
        self.assertEqual(
            "NCERT_Class_7_Maths_English_BOOK.pdf",
            source["slot_filename"],
        )

    def test_part_pair_takes_precedence_over_existing_canonical_copy(self):
        # Regression: a stale canonical filename may contain only Part I. When both
        # official part files are present, the merged source must include Part II.
        canonical = self.root / importer.book_filename("7", "maths", "english")
        with fitz.open() as doc:
            page = doc.new_page()
            page.insert_text((50, 50), "Stale Part I only")
            doc.save(canonical)

        part1 = self.root / importer.part_book_filename("7", "maths", "english", 1)
        part2 = self.root / importer.part_book_filename("7", "maths", "english", 2)
        for path, title in ((part1, "Large Numbers Around Us"), (part2, "Geometric Twins")):
            with fitz.open() as doc:
                page = doc.new_page()
                page.insert_text((50, 50), title)
                doc.save(path)

        canonical_before = canonical.read_bytes()
        part1_before = part1.read_bytes()
        part2_before = part2.read_bytes()
        output_dir = self.root / "private-review" / "ncert-book-splits"
        source = importer.prepare_source_book(self.root, "7", "maths", "english", output_dir)

        self.assertTrue(source["merged_from_parts"])
        with fitz.open(source["path"]) as merged:
            self.assertEqual(2, merged.page_count)
            self.assertIn("Geometric Twins", merged[1].get_text("text"))
        self.assertEqual(canonical_before, canonical.read_bytes())
        self.assertEqual(part1_before, part1.read_bytes())
        self.assertEqual(part2_before, part2.read_bytes())

    def test_missing_maths_part_fails_with_both_filenames(self):
        part1 = self.root / importer.part_book_filename("8", "maths", "hindi", 1)
        with fitz.open() as doc:
            doc.new_page()
            doc.save(part1)
        with self.assertRaisesRegex(FileNotFoundError, "Both official Mathematics parts are required"):
            importer.prepare_source_book(
                self.root, "8", "maths", "hindi",
                self.root / "private-review" / "ncert-book-splits",
            )

    def test_complete_book_original_is_not_rewritten_by_split(self):
        source = self.make_book()
        original_hash = importer.sha256_file(source)
        importer.split_chapter_pdf(source, self.root / "range.pdf", 2, 3)
        self.assertEqual(original_hash, importer.sha256_file(source))


if __name__ == "__main__":
    unittest.main()
