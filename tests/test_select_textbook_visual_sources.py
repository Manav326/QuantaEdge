import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from select_textbook_visual_sources import select_sources


class SelectTextbookVisualSourcesTests(unittest.TestCase):
    def setUp(self):
        self.hashes = {name: (hex(ord(name[0]))[2:] * 64)[:64] for name in []}
        self.index = {"books": []}
        for grade in (7, 8):
            for language, medium in (("english", "english"), ("hindi", "hindi")):
                lang_suffix = "en" if language == "english" else "hi"
                entries = [
                    (f"math-part-1-{grade}-{lang_suffix}", "Mathematics", f"Mathematics Part 1 {language}", 1),
                    (f"math-part-2-{grade}-{lang_suffix}", "Mathematics", f"Mathematics Part 2 {language}", 2),
                    (f"science-{grade}-{lang_suffix}", "Science", f"Science {language}", None),
                ]
                for book_id, subject, title, part in entries:
                    self.index["books"].append({
                        "book_id": book_id,
                        "file": f"books/{book_id}.pdf",
                        "title": title,
                        "part": part,
                        "class": str(grade),
                        "medium": medium,
                        "subject": subject,
                        "sha256": (("abcdef0123456789" * 4)[:64] if "part-1" in book_id else
                                   ("1234567890abcdef" * 4)[:64] if "part-2" in book_id else
                                   ("fedcba9876543210" * 4)[:64]),
                        "content_availability": {"status": "complete"},
                    })
            self.index["books"].append({
                "book_id": f"social-science-{grade}",
                "file": f"books/social-science-{grade}.pdf",
                "title": "Social Science",
                "class": str(grade), "medium": "english", "subject": "Social Science",
                "sha256": "a" * 64, "content_availability": {"status": "complete"},
            })
        self.index["books"].append({
            "book_id": "science-class6-en", "file": "books/science-class6-en.pdf",
            "title": "Science", "class": "6", "medium": "english", "subject": "Science",
            "sha256": "b" * 64, "content_availability": {"status": "complete"},
        })

    def test_selects_only_selected_grade_science_and_two_maths_parts_in_both_languages(self):
        rows = select_sources(self.index, 7)
        self.assertEqual(6, len(rows))
        self.assertEqual({"english", "hindi"}, {x["language"] for x in rows})
        self.assertEqual({7}, {x["class"] for x in rows})
        self.assertEqual(2, sum(x["subject"] == "maths" for x in rows if x["language"] == "english"))
        self.assertEqual(2, sum(x["subject"] == "maths" for x in rows if x["language"] == "hindi"))
        self.assertEqual({"science"}, {x["subject"] for x in rows if x["book_id"].startswith("science")})
        self.assertEqual({1, 2}, {x["maths_part"] for x in rows if x["subject"] == "maths"})
        self.assertFalse(any("social-science" in x["book_id"] or "class6" in x["book_id"] for x in rows))

    def test_rejects_missing_maths_part(self):
        copied = json.loads(json.dumps(self.index))
        copied["books"] = [b for b in copied["books"] if b["book_id"] != "math-part-2-7-en"]
        with self.assertRaisesRegex(ValueError, "exactly two complete Maths"):
            select_sources(copied, 7)

    def test_rejects_ambiguous_math_parts(self):
        copied = json.loads(json.dumps(self.index))
        entry = next(b for b in copied["books"] if b["book_id"] == "math-part-2-7-en")
        entry["title"] = "Mathematics book"
        entry.pop("part", None)
        entry["book_id"] = "math-book-7-en"
        entry["file"] = "books/math-book-7-en.pdf"
        with self.assertRaisesRegex(ValueError, "clearly identify Part 1 and Part 2"):
            select_sources(copied, 7)

    def test_rejects_multiple_complete_science_books(self):
        copied = json.loads(json.dumps(self.index))
        copied["books"].append({
            "book_id":"science-alt-7-en", "file":"books/science-alt-7-en.pdf",
            "title":"Another Science", "class":"7", "medium":"english", "subject":"Science",
            "sha256":"c"*64, "content_availability":{"status":"complete"},
        })
        with self.assertRaisesRegex(ValueError, "exactly one complete Science"):
            select_sources(copied, 7)

    def test_class_must_be_7_or_8_and_social_science_is_excluded(self):
        with self.assertRaisesRegex(ValueError, "Class 7 or Class 8"):
            select_sources(self.index, 6)


if __name__ == "__main__":
    unittest.main()
