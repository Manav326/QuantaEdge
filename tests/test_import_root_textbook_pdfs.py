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
SCRIPT = ROOT / "scripts" / "import_root_textbook_pdfs.py"
sys.path.insert(0, str(SCRIPT.parent))
spec = importlib.util.spec_from_file_location("qe_root_textbook_importer_tested", SCRIPT)
importer = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = importer
spec.loader.exec_module(importer)


@unittest.skipIf(fitz is None, "PyMuPDF is installed in the content-import CI job")
class RootTextbookPdfImporterTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def make_pdf(self, *, outline=True):
        path = self.root / "synthetic.pdf"
        with fitz.open() as doc:
            page = doc.new_page()
            page.insert_text((50, 50), "Contents\\nChapter 1 First Topic\\nChapter 2 Second Topic")
            page = doc.new_page()
            page.insert_text((50, 50), "First Topic\\n1\\nChapter")
            doc.new_page()
            page = doc.new_page()
            page.insert_text((50, 50), "Second Topic\\n2\\nChapter")
            doc.new_page()
            if outline:
                doc.set_toc([[1, "Chapter 1", 2], [1, "Chapter 2", 4]])
            doc.save(path)
        return path

    def test_parses_descriptive_filenames(self):
        science = importer.parse_textbook_filename("Class_6_Science_English.pdf", "english")
        self.assertEqual("6", science["class_code"])
        self.assertEqual("science", science["subject_code"])
        self.assertEqual("english", science["language"])
        maths = importer.parse_textbook_filename("Class_6_Mathematics_English.pdf", "english")
        self.assertEqual("maths", maths["subject_code"])
        hindi = importer.parse_textbook_filename("Class_6_Science_Hindi.pdf", "english")
        self.assertEqual("hindi", hindi["language"])

    def test_parses_ncert_book_code_filenames(self):
        science = importer.parse_textbook_filename("ncert-c6-english-fecu1.pdf", "english")
        self.assertEqual("6", science["class_code"])
        self.assertEqual("science", science["subject_code"])
        self.assertEqual("english", science["language"])
        maths = importer.parse_textbook_filename("ncert-c6-english-femh1.pdf", "english")
        self.assertEqual("maths", maths["subject_code"])

    def test_maps_bookmarked_chapter_pages_without_using_contents_page(self):
        path = self.make_pdf(outline=True)
        chapters = [
            {"chapter_code": "one", "title": "First Topic", "aliases": ["First Topic"]},
            {"chapter_code": "two", "title": "Second Topic", "aliases": ["Second Topic"]},
        ]
        ranges = importer.detect_chapter_ranges(path, chapters)
        self.assertEqual([(2, 3), (4, 5)], [(item["page_start"], item["page_end"]) for item in ranges])
        self.assertEqual(["one", "two"], [item["chapter_code"] for item in ranges])

    def test_text_fallback_ignores_contents_page(self):
        path = self.make_pdf(outline=False)
        chapters = [
            {"chapter_code": "one", "title": "First Topic", "aliases": ["First Topic"]},
            {"chapter_code": "two", "title": "Second Topic", "aliases": ["Second Topic"]},
        ]
        ranges = importer.detect_chapter_ranges(path, chapters)
        self.assertEqual([(2, 3), (4, 5)], [(item["page_start"], item["page_end"]) for item in ranges])

    def test_class6_chapter_maps_have_expected_counts_and_unique_codes(self):
        science = importer.CLASS6_CHAPTERS["science"]
        maths = importer.CLASS6_CHAPTERS["maths"]
        self.assertEqual(12, len(science))
        self.assertEqual(10, len(maths))
        self.assertEqual(len(science), len({item["chapter_code"] for item in science}))
        self.assertEqual(len(maths), len({item["chapter_code"] for item in maths}))

    def test_inspects_valid_pdf(self):
        path = self.make_pdf()
        info = importer.inspect_pdf(path)
        self.assertEqual(5, info["page_count"])
        self.assertEqual(64, len(info["sha256"]))


if __name__ == "__main__":
    unittest.main()
