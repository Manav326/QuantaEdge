import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

try:
    import fitz
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

    def test_detects_chapter_openers_and_exact_ranges(self):
        path = self.make_book()
        chapters = [
            {"chapter_id": 601, "chapter_code": "patterns-in-mathematics", "chapter_name": "गणित में पैटर्न"},
            {"chapter_id": 602, "chapter_code": "lines-and-angles", "chapter_name": "रेखाएँ और कोण"},
        ]
        ranges = importer.detect_chapter_ranges(path, "6", "maths", "english", chapters)
        self.assertEqual(
            [(2, 3), (4, 5)],
            [(item["start_page"], item["end_page"]) for item in ranges],
        )
        self.assertEqual(
            ["patterns-in-mathematics", "lines-and-angles"],
            [item["chapter_code"] for item in ranges],
        )

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
