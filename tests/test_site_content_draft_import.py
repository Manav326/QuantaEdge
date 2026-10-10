from __future__ import annotations

import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from import_site_content_draft import _question_for_api, dry_run_summary, validate_site_bundle


def make_bundle() -> dict:
    return {
        "schema": "quantaedge.site-content-authoring-draft.v1",
        "bundle_status": "AI_AUTHORED_DRAFT",
        "publish_status": "DRAFT",
        "auto_publish": False,
        "editorial_state": "PENDING_HUMAN_REVIEW",
        "ready_for_admin_import": False,
        "curriculum": {
            "board": "Bihar Board",
            "class_code": "6",
            "subject_code": "maths",
            "language": "hi",
            "canonical_track_match": "MATCHED_CHAPTER_CODE",
        },
        "chapter": {"code": "fractions", "display_name": "भिन्न", "sort_order": 7},
        "source_alignment": {
            "status": "NEEDS_EDITOR_VERIFICATION",
            "source_of_truth_candidate": {
                "title": "गणित भाग-6",
                "url": "https://scert.bihar.gov.in/eresources/example",
            },
        },
        "declaration": {"original_content": True, "rights_reviewed": False, "reviewer": None},
        "lessons": [{
            "code": "fractions-meaning",
            "title": "भिन्न का अर्थ",
            "summary": "समझें और उदाहरण देखें।",
            "estimated_minutes": 5,
            "sort_order": 1,
            "blocks": [
                {"sequence_no": 1, "block_type": "EXPLANATION", "content": {"heading": "समझें", "body": "भिन्न बराबर भागों से बनती है।"}},
                {"sequence_no": 2, "block_type": "WORKED_EXAMPLE", "content": {"title": "उदाहरण", "problem": "3/4 पहचानें", "steps": ["हर 4 है", "अंश 3 है"], "answer": "3/4"}},
                {"sequence_no": 3, "block_type": "GUIDED_PRACTICE", "content": {"title": "करें", "prompt": "2/4 को सरल करें", "hint": "अंश और हर को 2 से भाग दें।"}},
            ],
        }],
        "online_question_bank": [{
            "id": "test-q1",
            "lesson_code": "fractions-meaning",
            "question_type": "MCQ",
            "prompt": "3/4 में हर कौन-सी संख्या है?",
            "difficulty": "EASY",
            "sort_order": 1,
            "explanation": "हर पूरे के कुल बराबर भाग बताता है।",
            "options": [
                {"key": "A", "label": "3", "correct": False},
                {"key": "B", "label": "4", "correct": True},
            ],
            "answer_payload": {"kind": "OPTION", "value": "B"},
        }],
    }


class SiteContentDraftImportTests(unittest.TestCase):
    def test_accepts_pending_source_as_admin_review_draft(self) -> None:
        plan = validate_site_bundle(make_bundle())
        self.assertEqual(plan["all_statuses"], "DRAFT")
        self.assertFalse(plan["student_delivery_allowed"])
        self.assertEqual(plan["source_alignment_status"], "NEEDS_EDITOR_VERIFICATION")

    def test_dry_run_never_claims_to_publish(self) -> None:
        plan = dry_run_summary(make_bundle(), attach_to_existing_chapter=True)
        self.assertEqual(plan["mode"], "DRY_RUN")
        self.assertEqual(plan["chapter_mode"], "ATTACH_TO_EXISTING")
        self.assertFalse(plan["auto_publish"])
        self.assertFalse(plan["student_delivery_allowed"])

    def test_rejects_bundle_not_marked_ai_authored_draft(self) -> None:
        bundle = make_bundle()
        bundle["bundle_status"] = "EDITOR_REVIEWED_DRAFT"
        with self.assertRaisesRegex(ValueError, "Only AI_AUTHORED_DRAFT"):
            validate_site_bundle(bundle)

    def test_rejects_auto_publish(self) -> None:
        bundle = make_bundle()
        bundle["auto_publish"] = True
        with self.assertRaisesRegex(ValueError, "auto_publish=false"):
            validate_site_bundle(bundle)

    def test_rejects_fake_rights_review_claim(self) -> None:
        bundle = make_bundle()
        bundle["declaration"]["rights_reviewed"] = True
        with self.assertRaisesRegex(ValueError, "must not claim rights_reviewed=true"):
            validate_site_bundle(bundle)

    def test_noncanonical_chapter_requires_explicit_flag(self) -> None:
        bundle = make_bundle()
        bundle["curriculum"]["canonical_track_match"] = "NO_EXACT_MATCH_IN_CURRENT_OUTLINE"
        bundle["source_alignment"]["status"] = "CURRICULUM_DECISION_REQUIRED"
        with self.assertRaisesRegex(ValueError, "no exact chapter match"):
            validate_site_bundle(bundle)
        result = validate_site_bundle(bundle, allow_noncanonical_chapter=True)
        self.assertTrue(result["noncanonical_chapter"])
        self.assertEqual(result["all_statuses"], "DRAFT")

    def test_rejects_mcq_with_wrong_answer_payload(self) -> None:
        bundle = make_bundle()
        bundle["online_question_bank"][0]["answer_payload"] = {"kind": "OPTION", "value": "A"}
        with self.assertRaisesRegex(ValueError, "must match the sole correct option"):
            validate_site_bundle(bundle)

    def test_rejects_mcq_with_multiple_correct_options(self) -> None:
        bundle = make_bundle()
        bundle["online_question_bank"][0]["options"][0]["correct"] = True
        with self.assertRaisesRegex(ValueError, "exactly one correct option"):
            validate_site_bundle(bundle)

    def test_rejects_unsupported_online_question_type(self) -> None:
        bundle = make_bundle()
        bundle["online_question_bank"][0]["question_type"] = "LONG_ANSWER"
        with self.assertRaisesRegex(ValueError, "unsupported online question type"):
            validate_site_bundle(bundle)

    def test_every_lesson_requires_an_online_question(self) -> None:
        bundle = make_bundle()
        bundle["online_question_bank"][0]["lesson_code"] = "not-a-real-lesson"
        with self.assertRaisesRegex(ValueError, "maps to missing lesson"):
            validate_site_bundle(bundle)

    def test_imported_question_order_is_unique_within_lesson(self) -> None:
        item = make_bundle()["online_question_bank"][0]
        item["sort_order"] = 99  # Draft authoring order is advisory; CMS order is sequential per lesson.
        self.assertEqual(_question_for_api(item, 1)["sort_order"], 1)
        self.assertEqual(_question_for_api(item, 2)["sort_order"], 2)

    def test_requires_unique_block_sequences(self) -> None:
        bundle = make_bundle()
        bundle["lessons"][0]["blocks"][2]["sequence_no"] = 2
        with self.assertRaisesRegex(ValueError, "sequence numbers must be unique"):
            validate_site_bundle(bundle)


if __name__ == "__main__":
    unittest.main()
