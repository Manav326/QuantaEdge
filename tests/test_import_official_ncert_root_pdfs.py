import csv
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
SCRIPT = ROOT / "scripts" / "import_official_ncert_root_pdfs.py"
sys.path.insert(0, str(SCRIPT.parent))
spec = importlib.util.spec_from_file_location("qe_official_ncert_root_importer_tested", SCRIPT)
importer = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = importer
spec.loader.exec_module(importer)


def fake_curriculum():
    result = {}
    for grade in importer.CLASSES:
        for subject in importer.SUBJECTS:
            result[(grade, subject)] = [{
                "chapter_id": int(grade) * 100 + (1 if subject == "maths" else 2),
                "chapter_code": f"class-{grade}-{subject}-first",
                "chapter_name": f"Example Class {grade} {subject.title()} chapter",
                "chapter_sort_order": 1,
                "active": True,
            }]
    return result


class OfficialNcertManifestTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def test_filename_patterns_are_explicit_and_slot_safe(self):
        self.assertEqual(
            "NCERT_Class_6_Maths_English_BOOK.pdf",
            importer.expected_filename("6", "maths", "english", "SUBJECT_BOOK"),
        )
        self.assertEqual(
            "NCERT_Class_8_Science_Hindi_CHAPTER_class-8-science-first.pdf",
            importer.expected_filename(
                "8", "science", "hindi", "CHAPTER_PDF", "class-8-science-first"
            ),
        )
        with self.assertRaises(ValueError):
            importer.expected_filename("6", "maths", "english", "CHAPTER_PDF", "../wrong")

    def test_template_contains_twelve_book_slots_and_both_languages_for_chapters(self):
        rows = importer.make_manifest_rows(fake_curriculum())
        books = [row for row in rows if row["scope"] == "SUBJECT_BOOK"]
        chapters = [row for row in rows if row["scope"] == "CHAPTER_PDF"]
        self.assertEqual(12, len(books))
        self.assertEqual(12, len(chapters))
        self.assertEqual(
            {"english", "hindi"},
            {row["language"] for row in books},
        )
        self.assertEqual(
            24,
            len({importer.manifest_key(row) for row in rows}),
        )

    def make_edited_manifest(self):
        rows = importer.make_manifest_rows(fake_curriculum())
        for row in rows:
            if row["scope"] == "SUBJECT_BOOK":
                row["edition"] = "Reprint 2025-26"
            # Source URLs are already prefilled on BOOK rows and inherited by chapters.
        path = self.root / importer.MANIFEST_NAME
        with path.open("w", encoding="utf-8-sig", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=importer.MANIFEST_FIELDS)
            writer.writeheader()
            writer.writerows(rows)
        return path

    def test_manifest_round_trips_and_chapters_inherit_book_metadata(self):
        path = self.make_edited_manifest()
        rows = importer.read_manifest(path)
        prepared, _book_metadata = importer.validate_manifest_against_curriculum(
            rows, fake_curriculum()
        )
        self.assertEqual(24, len(prepared))
        book = next(row for row in prepared if row["scope"] == "SUBJECT_BOOK")
        chapter = next(
            row for row in prepared
            if row["scope"] == "CHAPTER_PDF"
            and row["class_code"] == book["class_code"]
            and row["subject_code"] == book["subject_code"]
            and row["language"] == book["language"]
        )
        self.assertEqual("Reprint 2025-26", chapter["edition"])
        self.assertEqual(importer.OFFICIAL_CATALOG_URL, chapter["source_url"])
        self.assertEqual(1, chapter["chapter_id"])

    def test_manifest_missing_a_live_chapter_fails_closed(self):
        rows = importer.make_manifest_rows(fake_curriculum())
        rows = [
            row for row in rows
            if not (
                row["scope"] == "CHAPTER_PDF"
                and row["class_code"] == "6"
                and row["subject_code"] == "maths"
                and row["language"] == "english"
            )
        ]
        with self.assertRaisesRegex(ValueError, "does not match the live active curriculum"):
            importer.validate_manifest_against_curriculum(rows, fake_curriculum())

    def test_manifest_rejects_unverified_edition_values(self):
        rows = importer.make_manifest_rows(fake_curriculum())
        for row in rows:
            if row["scope"] == "SUBJECT_BOOK":
                row["edition"] = "Edition to be confirmed"
        with self.assertRaisesRegex(ValueError, "looks like a placeholder"):
            importer.validate_manifest_against_curriculum(rows, fake_curriculum())

    def test_template_never_overwrites_an_existing_manifest(self):
        path = self.root / importer.MANIFEST_NAME
        path.write_text("retain me", encoding="utf-8")
        with self.assertRaises(FileExistsError):
            importer.write_manifest_template(path, importer.make_manifest_rows(fake_curriculum()))
        self.assertEqual("retain me", path.read_text(encoding="utf-8"))

    def test_pdf_validation_checks_signature_and_page_count(self):
        path = self.root / "valid.pdf"
        with fitz.open() as doc:
            doc.new_page()
            doc.save(path)
        details = importer.inspect_pdf(path, importer.ADMIN_MAX_BYTES)
        self.assertEqual(1, details["page_count"])
        self.assertEqual(64, len(details["sha256"]))
        bad = self.root / "not-a-pdf.pdf"
        bad.write_bytes(b"not a PDF")
        with self.assertRaisesRegex(ValueError, "PDF signature"):
            importer.inspect_pdf(bad, importer.ADMIN_MAX_BYTES)


if __name__ == "__main__":
    unittest.main()
