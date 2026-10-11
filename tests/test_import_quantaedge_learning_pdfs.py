import csv
import importlib.util
import io
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

try:
    import fitz
except ImportError:
    fitz = None

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "import_quantaedge_learning_pdfs.py"
spec = importlib.util.spec_from_file_location("qe_pdf_importer_tested", SCRIPT)
importer = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = importer
spec.loader.exec_module(importer)


def minimal_pdf() -> bytes:
    if fitz is None:
        raise unittest.SkipTest("PyMuPDF is not installed")
    with fitz.open() as doc:
        doc.new_page()
        return doc.tobytes()


class ImportQuantaEdgeLearningPdfTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def test_class6_json_inventory_creates_chapter_and_subject_book_records(self):
        archive = self.root / "QuantaEdge_Class6_QuantaEdge_Premium_Hindi_Package.zip"
        chapter_pdf = "QuantaEdge_Class6_QuantaEdge_Premium_Hindi_Package/pdf/chapterwise/maths/QuantaEdge_Class6_Hindi_maths_Ch01_patterns-in-mathematics_Concepts.pdf"
        book_pdf = "QuantaEdge_Class6_QuantaEdge_Premium_Hindi_Package/pdf/complete-book/QuantaEdge_Class6_Hindi_maths_Complete_Book.pdf"
        inventory = {
            "class_code": "6", "medium": "hi",
            "subjects": {"maths": [{"chapter_no": 1, "chapter_code": "patterns-in-mathematics", "title": "गणित में पैटर्न"}]},
        }
        with zipfile.ZipFile(archive, "w") as zf:
            zf.writestr("QuantaEdge_Class6_QuantaEdge_Premium_Hindi_Package/content/authoring-inventory.json", json.dumps(inventory, ensure_ascii=False))
            zf.writestr(chapter_pdf, minimal_pdf())
            zf.writestr(book_pdf, minimal_pdf())
        records, warnings = importer.extract_records(archive)
        self.assertEqual([], warnings)
        self.assertEqual(2, len(records))
        chapter = next(r for r in records if r.scope == "CHAPTER_PDF")
        book = next(r for r in records if r.scope == "SUBJECT_BOOK")
        self.assertEqual("patterns-in-mathematics", chapter.chapter_code)
        self.assertEqual("hi", chapter.language)
        self.assertIsNone(book.chapter_code)
        self.assertTrue(all(r.source_reference.startswith("asset_origin=QUANTAEDGE_AUTHORED_PDF") for r in records))
        self.assertTrue(all(r.source_url.startswith("https://") for r in records))

    def test_shared_class7_8_csv_only_imports_rows_present_in_named_class_zip(self):
        archive = self.root / "QuantaEdge_Class7_QuantaEdge_Hindi_DRAFT_Package.zip"
        root = "QuantaEdge_Class7_QuantaEdge_Hindi_DRAFT_Package/"
        chapter_rel = "pdfs/chapterwise/class-7/maths/Class_7_Maths_01_large-numbers_Concepts_DRAFT.pdf"
        book_rel = "pdfs/complete-book/QuantaEdge_Class7_Maths_Complete_Book_Hindi_DRAFT.pdf"
        out = io.StringIO()
        writer = csv.DictWriter(out, fieldnames=["class", "subject", "language", "chapter_code", "chapter_title", "source_title", "source_url", "chapterwise_pdf_path", "track_order"])
        writer.writeheader()
        writer.writerow({"class": "7", "subject": "maths", "language": "hi", "chapter_code": "large-numbers", "chapter_title": "बड़ी संख्याएँ", "source_title": "Ganita Prakash Part I", "source_url": "https://ncert.nic.in/textbook.php", "chapterwise_pdf_path": chapter_rel, "track_order": "1"})
        writer.writerow({"class": "8", "subject": "maths", "language": "hi", "chapter_code": "not-in-this-zip", "chapter_title": "Other class", "source_title": "Other", "source_url": "https://ncert.nic.in/textbook.php", "chapterwise_pdf_path": "pdfs/chapterwise/class-8/maths/missing.pdf", "track_order": "1"})
        with zipfile.ZipFile(archive, "w") as zf:
            zf.writestr("coverage/CHAPTER_INVENTORY.csv", out.getvalue())
            zf.writestr(root + chapter_rel, minimal_pdf())
            zf.writestr(root + book_rel, minimal_pdf())
        records, _ = importer.extract_records(archive)
        self.assertEqual(2, len(records))
        self.assertEqual({"7"}, {r.class_code for r in records})
        self.assertEqual({"CHAPTER_PDF", "SUBJECT_BOOK"}, {r.scope for r in records})

    def test_chapter_resolution_requires_exact_code_or_exact_title(self):
        record = importer.PdfRecord(
            package="x.zip", archive_path="x.pdf", sha256="a" * 64, filename="x.pdf",
            title="Chapter", class_code="6", subject_code="maths", subject_name="गणित",
            language="hi", scope="CHAPTER_PDF", chapter_code="patterns-in-mathematics",
            chapter_title="गणित में पैटर्न", page_count=1, size_bytes=100, source_title="QuantaEdge",
            source_url="https://ncert.nic.in/textbook.php", edition="Draft", source_reference="draft")
        curriculum = [{"chapter_id": 42, "chapter_code": "patterns-in-mathematics", "chapter_name": "गणित में पैटर्न", "chapter_active": True}]
        self.assertEqual(42, importer.resolve_chapter(record, curriculum))
        with self.assertRaisesRegex(ValueError, "No exact canonical chapter match"):
            importer.resolve_chapter(record, [{"chapter_id": 52, "chapter_code": "another-chapter", "chapter_name": "अलग अध्याय", "chapter_active": True}])


if __name__ == "__main__":
    unittest.main()
