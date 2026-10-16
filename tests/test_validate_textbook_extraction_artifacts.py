import json
import tempfile
import unittest
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from validate_textbook_extraction_artifacts import validate


class ValidateTextbookExtractionArtifactsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.extraction = self.root / "extracted"
        (self.extraction / "pages").mkdir(parents=True)
        self.index_path = self.root / "index.json"
        self.hashes = {
            "math-part-1": "a" * 64,
            "math-part-2": "b" * 64,
            "science": "c" * 64,
            "math-part-3": "d" * 64,
            "science-alt": "e" * 64,
        }

    def tearDown(self):
        self.temp.cleanup()

    def write_source(self, ids=("math-part-1", "math-part-2", "science")):
        books = []
        for book_id in ids:
            books.append({
                "book_id": book_id,
                "title": book_id.replace("-", " "),
                "class": "7",
                "medium": "english",
                "subject": "Mathematics" if book_id.startswith("math") else "Science",
                "sha256": self.hashes[book_id],
                "content_availability": {"status": "complete"},
            })
        self.index_path.write_text(json.dumps({"books": books}), encoding="utf-8")

    def write_manifest(self, book_id, subject, digest, page_count=2):
        jsonl_name = f"{digest}.jsonl"
        rows = []
        for page in range(1, page_count + 1):
            rows.append({
                "book_id": book_id,
                "original_book_sha256": digest,
                "language": "english",
                "subject": subject,
                "source_book_page_number": page,
                "text": f"page {page}",
            })
        page_path = self.extraction / "pages" / jsonl_name
        page_path.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
        manifest = {
            "book_id": book_id,
            "original_book_sha256": digest,
            "language": "english",
            "subject": subject,
            "scope": "SUBJECT_BOOK",
            "content_state": "DRAFT_EXTRACTION_ONLY_REQUIRES_REVIEW",
            "page_count": page_count,
            "completed_pages": page_count,
            "text_jsonl": jsonl_name,
        }
        (self.extraction / "pages" / f"{digest}.manifest.json").write_text(
            json.dumps(manifest), encoding="utf-8"
        )
        return manifest

    def test_class7_requires_and_accepts_both_maths_parts(self):
        self.write_source()
        self.write_manifest("math-part-1", "Mathematics", self.hashes["math-part-1"])
        self.write_manifest("math-part-2", "Mathematics", self.hashes["math-part-2"])
        self.write_manifest("science", "Science", self.hashes["science"])
        result = validate(self.index_path, self.extraction, 7, "english")
        self.assertEqual(2, len(result["maths_source_books"]))
        self.assertEqual(1, len(result["science_source_books"]))
        self.assertEqual({1, 2}, {x["maths_part"] for x in result["verified_books"] if x["subject"] == "maths"})
        self.assertEqual(6, result["verified_page_count"])
        self.assertFalse(result["publication_allowed"])

    def test_class7_fails_if_second_maths_part_is_missing(self):
        self.write_source(ids=("math-part-1", "science"))
        with self.assertRaisesRegex(ValueError, "exactly two complete Maths"):
            validate(self.index_path, self.extraction, 7, "english")

    def test_class7_rejects_more_than_two_math_books(self):
        self.write_source(ids=("math-part-1", "math-part-2", "math-part-3", "science"))
        with self.assertRaisesRegex(ValueError, "exactly two complete Maths"):
            validate(self.index_path, self.extraction, 7, "english")

    def test_class7_rejects_more_than_one_science_book(self):
        self.write_source(ids=("math-part-1", "math-part-2", "science", "science-alt"))
        with self.assertRaisesRegex(ValueError, "exactly one complete Science"):
            validate(self.index_path, self.extraction, 7, "english")

    def test_class7_rejects_unlabelled_maths_parts(self):
        self.write_source()
        books = json.loads(self.index_path.read_text(encoding="utf-8"))
        row = dict(next(x for x in books["books"] if x["book_id"] == "math-part-2"))
        row["title"] = "Mathematics"
        row["book_id"] = "math-book"
        books["books"] = [x for x in books["books"] if x["book_id"] != "math-part-2"] + [row]
        self.index_path.write_text(json.dumps(books), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "identify Part 1 and Part 2"):
            validate(self.index_path, self.extraction, 7, "english")

    def test_missing_page_record_fails_integrity_check(self):
        self.write_source()
        self.write_manifest("math-part-1", "Mathematics", self.hashes["math-part-1"])
        self.write_manifest("math-part-2", "Mathematics", self.hashes["math-part-2"])
        self.write_manifest("science", "Science", self.hashes["science"])
        manifest_path = self.extraction / "pages" / f"{self.hashes['math-part-1']}.manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        (self.extraction / "pages" / manifest["text_jsonl"]).write_text(
            json.dumps({"book_id": "math-part-1", "original_book_sha256": self.hashes["math-part-1"],
                        "language": "english", "source_book_page_number": 1, "text": "page 1"}) + "\n",
            encoding="utf-8"
        )
        with self.assertRaisesRegex(ValueError, "missing page records"):
            validate(self.index_path, self.extraction, 7, "english")


if __name__ == "__main__":
    unittest.main()
