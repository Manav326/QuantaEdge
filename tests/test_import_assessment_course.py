"""Regression tests for the manifest-driven assessment-only course importer."""
from __future__ import annotations

import argparse
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import import_assessment_course as importer  # noqa: E402


def valid_package() -> dict:
    return {
        "schema": "quantaedge.portable-assessment-package.v1",
        "package_id": "example-computer-science-practice",
        "package_version": "1.0.0",
        "title": "Example Computer Science Practice Set",
        "assessment_type": "PRACTICE_SET",
        "source": {
            "issuer": "Example issuer",
            "exam_name": "Example assessment",
            "cycle": "Practice Set 1",
            "source_url": "https://example.org/source.pdf",
            "repository_distribution_rights": "UNCONFIRMED",
            "available_languages": ["en", "hi"],
        },
        "configuration": {
            "duration_minutes": 60,
            "marks_per_question": 1,
            "negative_marking_fraction": 0,
            "unanswered_mark": 0,
            "max_attempts": 1,
        },
        "sections": [{
            "code": "COMPUTER_SCIENCE",
            "name_i18n": {"en": "Computer Science", "hi": "कंप्यूटर विज्ञान"},
            "question_count": 1,
            "result_policy": "SCORED",
        }],
        "questions": [{
            "id": "example-question-1",
            "source_question_number": "EXAMPLE-1",
            "question_type": "MCQ",
            "prompt": "Which option is the example answer?",
            "prompt_i18n": {
                "en": "Which option is the example answer?",
                "hi": "उदाहरण उत्तर कौन सा विकल्प है?",
            },
            "options": [
                {"key": "A", "text": "Alpha", "text_i18n": {"en": "Alpha", "hi": "अल्फा"}},
                {"key": "B", "text": "Beta", "text_i18n": {"en": "Beta", "hi": "बीटा"}},
            ],
            "topic_tags": ["Example topic"],
            "difficulty": "CORE",
            "proposed_answer": {
                "option_key": "A",
                "confidence": "UNRESOLVED",
                "explanation": "Pending human review.",
                "answer_source": "UNRESOLVED",
                "review_status": "PENDING_HUMAN_REVIEW",
                "explanation_i18n": {"en": "Pending human review.", "hi": "मानवीय समीक्षा लंबित है।"},
                "solution_mode": "CONCISE",
            },
            "section_code": "COMPUTER_SCIENCE",
            "section_result_policy": "SCORED",
            "marks": 1,
        }],
        "editorial": {
            "status": "DRAFT",
            "student_delivery_allowed": False,
            "review_status": "PENDING_HUMAN_REVIEW",
        },
    }


class AssessmentCourseImporterTests(unittest.TestCase):
    def setUp(self) -> None:
        self.path = Path("example-package.json")

    def test_valid_portable_package_passes_offline_validation(self) -> None:
        self.assertEqual(importer.validate_package(valid_package(), self.path), [])

    def test_missing_source_fields_return_actionable_examples(self) -> None:
        package = valid_package()
        package["source"].pop("source_url")
        errors = importer.validate_package(package, self.path)
        self.assertTrue(any("source.source_url is required" in error for error in errors))
        self.assertTrue(any("https://example.org/official-paper.pdf" in error for error in errors))

    def test_invalid_url_is_rejected(self) -> None:
        package = valid_package()
        package["source"]["source_url"] = "javascript:alert(1)"
        errors = importer.validate_package(package, self.path)
        self.assertTrue(any("valid http(s) URL" in error for error in errors))

    def test_missing_questions_are_never_invented(self) -> None:
        package = valid_package()
        package.pop("questions")
        errors = importer.validate_package(package, self.path)
        self.assertTrue(any("Importer will not invent question content" in error for error in errors))

    def test_duplicate_option_keys_are_rejected(self) -> None:
        package = valid_package()
        package["questions"][0]["options"][1]["key"] = "A"
        errors = importer.validate_package(package, self.path)
        self.assertTrue(any("option keys must be present and unique" in error for error in errors))

    def test_question_snapshot_is_bilingual_and_always_review_gated(self) -> None:
        package = valid_package()
        question = package["questions"][0]
        section = package["sections"][0]
        snapshot = importer.build_question_snapshot(question, section, package)
        self.assertEqual(snapshot["prompt_i18n"]["hi"], "उदाहरण उत्तर कौन सा विकल्प है?")
        self.assertEqual(snapshot["options"][0]["text_i18n"]["hi"], "अल्फा")
        self.assertEqual(snapshot["answer_payload"], {"kind": "OPTION", "value": "A"})
        self.assertEqual(snapshot["question_review_status"], "PENDING_HUMAN_REVIEW")
        self.assertEqual(snapshot["answer_review_status"], "PENDING_HUMAN_REVIEW")
        self.assertFalse(snapshot["options"][0]["is_correct"] is False)

    def test_unresolved_answer_remains_unresolved(self) -> None:
        package = valid_package()
        package["questions"][0]["proposed_answer"]["option_key"] = None
        snapshot = importer.build_question_snapshot(package["questions"][0], package["sections"][0], package)
        self.assertEqual(snapshot["answer_payload"], {"kind": "UNRESOLVED", "value": ""})
        self.assertEqual(snapshot["answer_review_status"], "PENDING_HUMAN_REVIEW")

    def test_course_key_format_is_not_the_generated_public_id_format(self) -> None:
        self.assertRegex("BPSC_TRE_CS_PYQ_SERIES", importer.COURSE_KEY_RE)
        self.assertNotRegex("INSABCDE", importer.COURSE_KEY_RE)
        self.assertRegex("tre-computer-science-paper-1", importer.PACKAGE_ID_RE)

    def test_database_url_must_be_localhost(self) -> None:
        args = argparse.Namespace(database_url="postgresql://user:pass@localhost:5432/quantaedge")
        self.assertEqual(importer.urlparse(importer.db_url(args)).hostname, "localhost")
        remote = argparse.Namespace(database_url="postgresql://user:pass@db.example.com:5432/quantaedge")
        with self.assertRaisesRegex(importer.ImportErrorSafe, "Refusing a remote database"):
            importer.db_url(remote)

    def test_generic_importer_uses_generic_not_tre_readiness_gate(self) -> None:
        source = (ROOT / "scripts/import_assessment_course.py").read_text(encoding="utf-8")
        self.assertIn("assessment_only_course_ready", source)
        self.assertNotIn("tre4_source_package_ready", source)
        self.assertIn("studentDeliveryAllowed", source)
        self.assertIn("release_state", source)
        self.assertIn("PENDING_HUMAN_REVIEW", source)


if __name__ == "__main__":
    unittest.main()
