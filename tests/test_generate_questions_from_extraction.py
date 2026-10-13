import csv
import tempfile
import unittest
from pathlib import Path

from scripts.generate_questions_from_extraction import (
    SUPPORTED_TYPES,
    load_curriculum_question_targets,
    validate_model_output,
)



def lesson_result(question):
    return {
        "lesson_title": "Fractions in daily life",
        "lesson_summary": "A short original summary.",
        "lesson_explanation": "Fractions represent equal parts of a whole.",
        "worked_example": "Divide one roti into four equal pieces. Two pieces are two fourths.",
        "guided_practice": "Count three of five equal pieces with the teacher.",
        "independent_practice": "Write a fraction for one of six equal pieces.",
        "recap": "The denominator counts equal parts; the numerator counts selected parts.",
        "questions": [question],
    }


def question(question_type, **overrides):
    value = {
        "question_type": question_type,
        "prompt": "Match the fraction to its visual description.",
        "difficulty": "MEDIUM",
        "explanation": "Expected answer: 1/2 maps to two of four equal shaded parts; verify the visual during editorial review.",
        "options": [],
        "answer_payload": {},
        "source_page_numbers": [3],
        "topic": "Fractions",
        "subtopic": "Representations",
        "skill": "Match representations",
    }
    value.update(overrides)
    return value


class QuestionGenerationSchemaTests(unittest.TestCase):
    def setUp(self):
        self.chunk = {"page_numbers": [3], "page_start": 3, "page_end": 3}

    def test_curriculum_target_mode_generates_only_the_remaining_gap(self):
        rows = [
            {"class_code": "6", "subject_code": "maths", "chapter_code": "alpha", "question_type": "MCQ", "minimum_target_count": "5", "actual_active_approved_count": "2", "target_eligible": "true"},
            {"class_code": "6", "subject_code": "maths", "chapter_code": "alpha", "question_type": "INPUT", "minimum_target_count": "2", "actual_active_approved_count": "2", "target_eligible": "true"},
            {"class_code": "6", "subject_code": "maths", "chapter_code": "beta", "question_type": "MCQ", "minimum_target_count": "1", "actual_active_approved_count": "1", "target_eligible": "true"},
            {"class_code": "7", "subject_code": "maths", "chapter_code": "alpha", "question_type": "MCQ", "minimum_target_count": "99", "actual_active_approved_count": "0", "target_eligible": "true"},
        ]
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "targets.csv"
            with path.open("w", encoding="utf-8", newline="") as handle:
                writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
                writer.writeheader()
                writer.writerows(rows)
            result = load_curriculum_question_targets(
                path,
                {"class_code": "6", "subject_code": "maths"},
                [{"code": "alpha"}, {"code": "beta"}],
            )
        self.assertEqual(["MCQ", "MCQ", "MCQ"], result["alpha"])
        self.assertEqual([], result["beta"])

    def test_generator_declares_all_curriculum_types(self):
        self.assertTrue({
            "MCQ", "TRUE_FALSE", "INPUT", "NUMERICAL", "MATCH", "ORDER",
            "ASSERTION_REASON", "CASE_BASED", "SHORT_ANSWER", "LONG_ANSWER",
            "DIAGRAM", "MAP", "SOURCE_BASED",
        }.issubset(SUPPORTED_TYPES))

    def test_review_only_type_is_validated_as_draft_candidate(self):
        result = validate_model_output(lesson_result(question("MATCH")), ["MATCH"], self.chunk)
        self.assertIn("worked_example", result)
        self.assertIn("guided_practice", result)
        self.assertIn("independent_practice", result)
        self.assertIn("recap", result)
        self.assertEqual("MATCH", result["questions"][0]["question_type"])
        self.assertEqual({}, result["questions"][0]["answer_payload"])

    def test_review_only_type_rejects_automatic_answer_payload(self):
        payload = {"kind": "TEXT", "value": "A to 1"}
        with self.assertRaisesRegex(ValueError, "review-only"):
            validate_model_output(
                lesson_result(question("MATCH", answer_payload=payload)),
                ["MATCH"],
                self.chunk,
            )

    def test_all_lesson_sections_are_required(self):
        data = lesson_result(question("MATCH"))
        del data["recap"]
        with self.assertRaisesRegex(ValueError, "recap"):
            validate_model_output(data, ["MATCH"], self.chunk)


if __name__ == "__main__":
    unittest.main()
