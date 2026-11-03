import unittest

from scripts.plan_textbook_layer45_matrix import BOOK_PAIRS
from scripts.select_textbook_visual_pair import select_bilingual_pair


class TextbookVisualPairSelectionTests(unittest.TestCase):
    def setUp(self):
        self.pair = next(
            row for row in BOOK_PAIRS
            if row["class_no"] == "6" and row["book_label"] == "Maths"
        )
        ids = self.pair["book_ids_csv"].split(",")
        slugs = self.pair["source_slugs_csv"].split(",")
        self.english = {
            "class": 6, "language": "english",
            "sources": [
                {"book_id": ids[0], "book_slug": slugs[0], "subject": "Mathematics"},
                {"book_id": "ncert-c6-english-fecu1", "book_slug": "class6-en-science-9c1315ba", "subject": "Science"},
            ],
        }
        self.hindi = {
            "class": 6, "language": "hindi",
            "sources": [
                {"book_id": ids[1], "book_slug": slugs[1], "subject": "Maths"},
                {"book_id": "ncert-c6-hindi-fhcu1", "book_slug": "class6-hi-science-f538ae2b", "subject": "Science"},
            ],
        }

    def select(self, english=None, hindi=None):
        return select_bilingual_pair(
            english or self.english,
            hindi or self.hindi,
            class_no=self.pair["class_no"],
            book_ids_csv=self.pair["book_ids_csv"],
            source_slugs_csv=self.pair["source_slugs_csv"],
            book_pair_id=self.pair["book_pair_id"],
        )

    def test_selects_only_the_planned_math_pair_not_the_entire_class(self):
        selected = self.select()
        self.assertEqual(self.pair["book_pair_id"], selected["book_pair_id"])
        self.assertEqual(self.pair["book_ids_csv"].split(","), [x["book_id"] for x in selected["sources"]])
        self.assertEqual(self.pair["source_slugs_csv"].split(","), [x["book_slug"] for x in selected["sources"]])
        self.assertEqual(["english", "hindi"], [x["source_language"] for x in selected["sources"]])
        self.assertEqual(["maths", "maths"], [x["subject"].lower() for x in selected["sources"]])
        self.assertFalse(selected["publication_allowed"])

    def test_fails_closed_when_an_assigned_book_is_missing(self):
        bad = dict(self.english, sources=self.english["sources"][1:])
        with self.assertRaisesRegex(ValueError, "exactly one assigned book_id"):
            self.select(english=bad)

    def test_rejects_wrong_subject_pair(self):
        bad_hindi = {
            "class": 6, "language": "hindi",
            "sources": [
                {"book_id": self.pair["book_ids_csv"].split(",")[1],
                 "book_slug": self.pair["source_slugs_csv"].split(",")[1], "subject": "Science"}
            ],
        }
        with self.assertRaisesRegex(ValueError, "subject mismatch"):
            self.select(hindi=bad_hindi)

    def test_rejects_wrong_source_slug_even_when_book_id_matches(self):
        bad = {
            **self.english,
            "sources": [
                {**self.english["sources"][0], "book_slug": "class6-en-science-9c1315ba"}
            ],
        }
        with self.assertRaisesRegex(ValueError, "slug mismatch"):
            self.select(english=bad)

    def test_rejects_duplicate_or_incomplete_pair_inputs(self):
        with self.assertRaisesRegex(ValueError, "exactly two distinct"):
            select_bilingual_pair(
                self.english, self.hindi, class_no="6",
                book_ids_csv="ncert-c6-english-fegp1",
                source_slugs_csv=self.pair["source_slugs_csv"],
                book_pair_id=self.pair["book_pair_id"],
            )


if __name__ == "__main__":
    unittest.main()
