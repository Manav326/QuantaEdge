import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

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

    def test_filename_slots_are_stable(self):
        self.assertEqual(
            "NCERT_Class_6_Maths_English_BOOK.pdf",
            importer.book_filename("6", "maths", "english"),
        )
        self.assertEqual(
            "NCERT_Class_8_Science_Hindi_BOOK.pdf",
            importer.book_filename("8", "science", "hindi"),
        )

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

    def test_complete_book_original_is_not_rewritten_by_split(self):
        source = self.make_book()
        original_hash = importer.sha256_file(source)
        importer.split_chapter_pdf(source, self.root / "range.pdf", 2, 3)
        self.assertEqual(original_hash, importer.sha256_file(source))


if __name__ == "__main__":
    unittest.main()
