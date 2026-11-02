import unittest
from pathlib import Path

from scripts.plan_textbook_layer45_matrix import BOOKS, BOOK_PAIRS, plan_matrix


class TextbookLayer45MatrixTests(unittest.TestCase):
    def test_each_class_is_sharded_to_explicit_bilingual_pairs(self):
        for grade, expected in (("6", 2), ("7", 3), ("8", 3)):
            pairs = plan_matrix("workflow_call", class_no=grade, analysis_mode="prepare_only", book_pair_id="all")
            self.assertEqual(expected, len(pairs))
            self.assertTrue(all(row["book_pair_id"] for row in pairs))
            self.assertTrue(all(len(row["book_ids_csv"].split(",")) == 2 for row in pairs))
            self.assertTrue(all(len(row["source_slugs_csv"].split(",")) == 2 for row in pairs))

    def test_all_classes_are_exactly_eight_pairs(self):
        pairs = plan_matrix("workflow_call", class_no="all", analysis_mode="prepare_only", book_pair_id="all")
        self.assertEqual(8, len(pairs))
        self.assertEqual({"6": 2, "7": 3, "8": 3}, {
            grade: sum(row["class_no"] == grade for row in pairs) for grade in ("6", "7", "8")
        })

    def test_exact_pair_selection_does_not_cross_class(self):
        pair_id = "class7-maths-part1-bilingual"
        pairs = plan_matrix("workflow_call", class_no="7", analysis_mode="analyze_all", book_pair_id=pair_id)
        self.assertEqual([pair_id], [row["book_pair_id"] for row in pairs])
        with self.assertRaisesRegex(ValueError, "belongs to Class 7"):
            plan_matrix("workflow_call", class_no="6", analysis_mode="analyze_all", book_pair_id=pair_id)

    def test_missing_unknown_or_unsupported_pair_scope_fails_closed(self):
        with self.assertRaisesRegex(ValueError, "book_pair_id is required"):
            plan_matrix("workflow_call", class_no="6", analysis_mode="prepare_only")
        with self.assertRaisesRegex(ValueError, "Unknown book_pair_id"):
            plan_matrix("workflow_call", class_no="6", analysis_mode="prepare_only", book_pair_id="class6-science")
        self.assertEqual([], plan_matrix("push", "unmarked code commit", class_no="6", analysis_mode="analyze_all", book_pair_id="all"))

    def test_resume_skips_only_pairs_that_pass_the_strict_artifact_contract(self):
        pairs = plan_matrix("workflow_call", class_no="all", analysis_mode="resume_layers_4_5", book_pair_id="all", repo_root=Path("/nonexistent"))
        self.assertEqual(8, len(pairs))
        self.assertTrue(all(row["book_pair_id"] for row in pairs))

    def test_registry_contains_sixteen_sources_and_eight_bilingual_pairs(self):
        by_id = {row["book_id"]: row for row in BOOKS}
        self.assertEqual(16, len(BOOKS))
        self.assertEqual(8, len(BOOK_PAIRS))
        for pair in BOOK_PAIRS:
            ids = pair["book_ids_csv"].split(",")
            slugs = pair["source_slugs_csv"].split(",")
            self.assertEqual(2, len(ids))
            self.assertEqual(2, len(slugs))
            self.assertEqual(2, len(set(ids)))
            self.assertEqual(2, len(set(slugs)))
            self.assertEqual({by_id[book_id]["book_slug"] for book_id in ids}, set(slugs))
            self.assertEqual(1, sum("english" in book_id for book_id in ids))
            self.assertEqual(1, sum("hindi" in book_id for book_id in ids))


if __name__ == "__main__":
    unittest.main()
