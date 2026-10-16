import json
import tempfile
import unittest
from pathlib import Path

from scripts.validate_structured_lesson_coverage import validate_coverage

class StructuredCoverageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.lesson_path = "data/structured-lessons/class-6/science/chapter-01/topic-01.v1.json"
        path = self.root / self.lesson_path
        path.parent.mkdir(parents=True)
        lesson = {
            "schema_version": "quantaedge.structured-lesson.v1",
            "artifact_id": "lesson-1",
            "workflow": {"publication_allowed": False},
            "chapter": {"chapter_number": 1},
            "topic": {"topic_number": 1, "topic_id": "topic-1"},
            "learning_objectives": [{"id": "obj-1"}],
            "explain": {"blocks": [
                {"id": "block-explain", "type": "rich_text"},
                {"id": "block-activity", "type": "interactive",
                 "configuration": {"activity_id": "activity-1"}}
            ]},
            "practice": {"questions": [{"id": "question-1"}]}
        }
        path.write_text(json.dumps(lesson), encoding="utf-8")
        self.ledger = {
            "section_map": [{"section_id": "1.1", "topic_number": 1, "lesson_path": self.lesson_path}],
            "coverage_inventory": {
                "figures": {"expected": ["1.1"]},
                "tables": {"expected": []},
                "activities": {"expected": ["1.1"]},
                "chapter_end_questions": {"expected_count": 1}
            },
            "source_inventory": {"status": "RECONCILED", "items": [
                {"id": "section-1.1", "kind": "section", "source_id": "1.1", "topic_number": 1, "label_by_locale": {"en": "Section 1.1", "hi": "अनुभाग 1.1"}},
                {"id": "figure-1.1", "kind": "figure", "source_id": "1.1", "topic_number": 1, "label_by_locale": {"en": "Figure 1.1", "hi": "चित्र 1.1"}},
                {"id": "activity-1.1", "kind": "activity", "source_id": "1.1", "topic_number": 1, "label_by_locale": {"en": "Activity 1.1", "hi": "गतिविधि 1.1"}},
                {"id": "review-question-1", "kind": "chapter_end_question", "source_id": "1", "topic_number": 1, "label_by_locale": {"en": "Review question 1", "hi": "अभ्यास प्रश्न 1"}}
            ]},
            "coverage_map": [
                {"source_item_id": "section-1.1", "disposition": "COVERED",
                 "covered_by": [{"lesson_path": self.lesson_path, "element_kind": "block", "element_id": "block-explain"}]},
                {"source_item_id": "figure-1.1", "disposition": "COVERED",
                 "covered_by": [{"lesson_path": self.lesson_path, "element_kind": "block", "element_id": "block-explain"}]},
                {"source_item_id": "activity-1.1", "disposition": "COVERED",
                 "covered_by": [{"lesson_path": self.lesson_path, "element_kind": "activity", "element_id": "activity-1"}]},
                {"source_item_id": "review-question-1", "disposition": "COVERED",
                 "covered_by": [{"lesson_path": self.lesson_path, "element_kind": "question", "element_id": "question-1"}]}
            ]
        }

    def tearDown(self):
        self.temp.cleanup()

    def test_complete_item_map_resolves_to_real_artifact_ids(self):
        report = validate_coverage(self.ledger, self.root)
        self.assertEqual("PASS", report["status"])
        self.assertEqual(4, report["source_item_count"])
        self.assertEqual(4, report["covered_item_count"])

    def test_missing_disposition_fails(self):
        self.ledger["coverage_map"] = self.ledger["coverage_map"][:-1]
        report = validate_coverage(self.ledger, self.root)
        self.assertEqual("INVALID", report["status"])
        self.assertTrue(any("no coverage disposition" in e for e in report["errors"]))

    def test_stale_block_or_question_reference_fails(self):
        self.ledger["coverage_map"][0]["covered_by"][0]["element_id"] = "missing-block"
        report = validate_coverage(self.ledger, self.root)
        self.assertEqual("INVALID", report["status"])
        self.assertTrue(any("missing block ID" in e for e in report["errors"]))

    def test_item_count_drift_against_existing_ledger_is_detected(self):
        self.ledger["source_inventory"]["items"].append(
            {"id": "figure-extra", "kind": "figure", "source_id": "1.2", "topic_number": 1, "label_by_locale": {"en": "Extra figure", "hi": "अतिरिक्त चित्र"}}
        )
        self.ledger["coverage_map"].append(
            {"source_item_id": "figure-extra", "disposition": "COVERED",
             "covered_by": [{"lesson_path": self.lesson_path, "element_kind": "block", "element_id": "block-explain"}]}
        )
        report = validate_coverage(self.ledger, self.root)
        self.assertEqual("INVALID", report["status"])
        self.assertTrue(any("count mismatch for figure" in e for e in report["errors"]))

    def test_pending_source_review_is_explicit_and_still_a_blocker_by_default(self):
        self.ledger["coverage_map"][0] = {
            "source_item_id": "section-1.1", "disposition": "PENDING_SOURCE_REVIEW",
            "reason": "Rendered Hindi page boundary still needs review.",
            "covered_by": [{"lesson_path": self.lesson_path, "element_kind": "block", "element_id": "block-explain"}]
        }
        default = validate_coverage(self.ledger, self.root)
        self.assertEqual("INVALID", default["status"])
        staged = validate_coverage(self.ledger, self.root, allow_pending_review=True)
        self.assertEqual("PASS_WITH_REVIEW_BLOCKERS", staged["status"])
        self.assertEqual(["section-1.1"], staged["pending_review_item_ids"])

if __name__ == "__main__":
    unittest.main()
