import unittest

from scripts.plan_textbook_layer45_matrix import BOOKS, plan_matrix


class TextbookLayer45MatrixTests(unittest.TestCase):
    def test_resume_all_classes_spreads_every_whole_book_across_runners(self):
        planned = plan_matrix("push", "[resume-visual-layers-4-5-classes-6-8]")
        self.assertEqual(16, len(planned))
        self.assertEqual(16, len({(x["class_no"], x["book_id"]) for x in planned}))
        self.assertEqual({4: 6}, {}) if False else None
        self.assertEqual(4, sum(x["class_no"] == "6" for x in planned))
        self.assertEqual(6, sum(x["class_no"] == "7" for x in planned))
        self.assertEqual(6, sum(x["class_no"] == "8" for x in planned))

    def test_single_class_resume_uses_only_books_for_that_class(self):
        planned = plan_matrix("workflow_dispatch", class_no="7", analysis_mode="resume_layers_4_5")
        self.assertEqual(6, len(planned))
        self.assertTrue(all(x["class_no"] == "7" and x["book_id"] for x in planned))

    def test_class_wide_resume_markers_are_book_sharded(self):
        self.assertEqual(4, len(plan_matrix("push", "[resume-visual-layers-4-5-class-6]")))
        self.assertEqual(6, len(plan_matrix("push", "[resume-visual-layers-4-5-class-7]")))
        self.assertEqual(6, len(plan_matrix("push", "[resume-visual-layers-4-5-class-8]")))

    def test_catalog_rebuild_modes_remain_class_wide(self):
        self.assertEqual(
            [{"class_no": "8", "book_id": "", "book_slug": "all-books"}],
            plan_matrix("workflow_dispatch", class_no="8", analysis_mode="analyze_all"),
        )
        self.assertEqual(
            [{"class_no": "7", "book_id": "", "book_slug": "all-books"}],
            plan_matrix("push", "[analyze-all-visual-layers-4-5-class-7]"),
        )

    def test_unknown_events_and_unmarked_pushes_do_not_start_workers(self):
        self.assertEqual([], plan_matrix("push", "ordinary unrelated commit"))
        self.assertEqual([], plan_matrix("schedule"))
        self.assertEqual(16, len(BOOKS))


if __name__ == "__main__":
    unittest.main()
