import hashlib
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from select_textbook_visual_sources import select_sources


class SelectTextbookVisualSourcesTests(unittest.TestCase):
    def setUp(self):
        self.index = {"books": []}
        class_prefix = {6: "f", 7: "g", 8: "h"}
        for grade in (6, 7, 8):
            for language, medium_code in (("english", "e"), ("hindi", "h")):
                prefix = class_prefix[grade] + medium_code
                math_parts = (1,) if grade == 6 else (1, 2)
                for part in math_parts:
                    code = f"{prefix}gp{part}"
                    book_id = f"ncert-c{grade}-{language}-{code}"
                    self.index["books"].append({
                        "book_id": book_id, "code": code, "file": f"books/{book_id}.pdf",
                        "title": "गणित प्रकाश" if language == "hindi" else "Ganita Prakash",
                        "class": str(grade), "medium": language,
                        "subject": "Mathematics", "sha256": hashlib.sha256(book_id.encode()).hexdigest(),
                        "content_availability": {"status": "complete"},
                    })
                science_code = f"{prefix}cu1"
                science_id = f"ncert-c{grade}-{language}-{science_code}"
                self.index["books"].append({
                    "book_id": science_id, "code": science_code, "file": f"books/{science_id}.pdf",
                    "title": "विज्ञान" if language == "hindi" else "Science",
                    "class": str(grade), "medium": language,
                    "subject": "विज्ञान" if language == "hindi" else "Science",
                    "sha256": hashlib.sha256(science_id.encode()).hexdigest(),
                    "content_availability": {"status": "complete"},
                })
        legacy_id = "ncert-c8-english-hemh1"
        self.index["books"].append({
            "book_id": legacy_id, "code": "hemh1", "file": f"books/{legacy_id}.pdf",
            "title": "Legacy Mathematics", "class": "8", "medium": "english",
            "subject": "Mathematics", "sha256": hashlib.sha256(legacy_id.encode()).hexdigest(),
            "content_availability": {"status": "complete"},
        })
        for grade in (6, 7, 8):
            legacy_id = f"ncert-c{grade}-english-social-science"
            self.index["books"].append({
                "book_id": legacy_id, "file": f"books/{legacy_id}.pdf",
                "title": "Social Science", "class": str(grade), "medium": "english",
                "subject": "Social Science", "sha256": "a" * 64,
                "content_availability": {"status": "complete"},
            })

    def test_language_scoped_selection_matches_single_medium_ghcr_indexes(self):
        for grade in (6, 7, 8):
            for language in ("english", "hindi"):
                with self.subTest(grade=grade, language=language):
                    rows = select_sources(self.index, grade, language)
                    self.assertEqual(2 if grade == 6 else 3, len(rows))
                    self.assertEqual({language}, {row["language"] for row in rows})
                    self.assertEqual({grade}, {row["class"] for row in rows})
                    self.assertEqual(1, sum(row["subject"] == "science" for row in rows))
                    self.assertEqual(1 if grade == 6 else 2,
                                     sum(row["subject"] == "maths" for row in rows))
                    if grade == 6:
                        self.assertEqual({1}, {row["maths_part"] for row in rows if row["subject"] == "maths"})
                    else:
                        self.assertEqual({1, 2}, {row["maths_part"] for row in rows if row["subject"] == "maths"})
                    self.assertFalse(any("social-science" in row["book_id"] or "hemh1" in row["book_id"] for row in rows))

    def test_bilingual_combined_index_selection_remains_available_for_callers(self):
        self.assertEqual(4, len(select_sources(self.index, 6)))
        self.assertEqual(6, len(select_sources(self.index, 7)))
        self.assertEqual(6, len(select_sources(self.index, 8)))

    def test_rejects_missing_current_maths_part(self):
        copied = json.loads(json.dumps(self.index))
        copied["books"] = [b for b in copied["books"] if b["book_id"] != "ncert-c7-english-gegp2"]
        with self.assertRaisesRegex(ValueError, "exactly 2 complete current Maths"):
            select_sources(copied, 7, "english")

    def test_rejects_ambiguous_math_part_labels(self):
        copied = json.loads(json.dumps(self.index))
        entry = next(b for b in copied["books"] if b["book_id"] == "ncert-c7-english-gegp2")
        entry["title"] = "Ganita Prakash Part 1"
        with self.assertRaisesRegex(ValueError, "identify Part 1 and Part 2"):
            select_sources(copied, 7, "english")

    def test_rejects_multiple_complete_science_books(self):
        copied = json.loads(json.dumps(self.index))
        legacy_id = "alternate-science-class7-english"
        copied["books"].append({
            "book_id": legacy_id, "file": f"books/{legacy_id}.pdf", "title": "Another Science",
            "class": "7", "medium": "english", "subject": "Science",
            "sha256": "c" * 64, "content_availability": {"status": "complete"},
        })
        with self.assertRaisesRegex(ValueError, "exactly one complete Science"):
            select_sources(copied, 7, "english")

    def test_invalid_class_or_language_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "Class 6, Class 7 or Class 8"):
            select_sources(self.index, 5)
        with self.assertRaisesRegex(ValueError, "Language must be English or Hindi"):
            select_sources(self.index, 7, "French")


if __name__ == "__main__":
    unittest.main()
