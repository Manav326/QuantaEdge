from __future__ import annotations

import unittest

from scripts.validate_portable_assessment_package import validate_package


def sample_package() -> dict:
    return {
        "schema": "quantaedge.portable-assessment-package.v1",
        "package_id": "sample-tre-test",
        "title": "TRE sample previous-year paper",
        "assessment_type": "PREVIOUS_YEAR_PAPER",
        "subject": {"name": "Computer Science", "domain_code": "computer-science", "language": "en"},
        "source": {
            "issuer": "BPSC",
            "exam_name": "Teacher Recruitment Examination",
            "cycle": "TRE 1.0",
            "source_url": "https://example.org/paper.pdf",
            "source_status": "READABLE_ON_WEB_NOT_DOWNLOADED_LOCALLY",
            "source_file_path": None,
            "source_file_sha256": None,
            "repository_distribution_rights": "UNCONFIRMED",
            "rights_evidence_url": None,
            "notes": "Draft test fixture only."
        },
        "configuration": {
            "duration_minutes": 80,
            "marks_per_question": 1,
            "negative_marking_per_wrong_answer": None,
            "unanswered_mark": 0,
            "presentation_hints": ["Practice test"]
        },
        "questions": [{
            "id": "sample-q1",
            "source_question_number": 41,
            "source_location": {"pdf_page": 14, "printed_page": 14, "booklet_series": "A"},
            "question_type": "MCQ",
            "prompt": "Which data structure follows FIFO?",
            "options": [
                {"key": "A", "text": "Stack"},
                {"key": "B", "text": "Queue"}
            ],
            "topic_tags": ["data-structures", "queue"],
            "secondary_topic_tags": [],
            "marks": 1,
            "proposed_answer": {
                "option_key": "B",
                "confidence": "HIGH",
                "explanation": "A queue removes items in the order they were inserted.",
                "answer_source": "INDEPENDENTLY_SOLVED",
                "answer_reference_url": None,
                "review_status": "PENDING_HUMAN_REVIEW",
                "reviewer_notes": ""
            },
            "question_review_status": "PENDING_HUMAN_REVIEW",
            "source_notes": "Fictional test fixture; not from an actual TRE paper."
        }],
        "editorial": {
            "status": "DRAFT",
            "student_delivery_allowed": False,
            "review_status": "PENDING_HUMAN_REVIEW",
            "reviewer": None,
            "reviewed_at": None,
            "review_notes": ""
        }
    }


class PortableAssessmentPackageTests(unittest.TestCase):
    def test_accepts_class_neutral_draft_package(self):
        self.assertEqual(validate_package(sample_package()), [])

    def test_rejects_hard_coded_school_class_fields(self):
        package = sample_package()
        package["subject"]["class_code"] = "6"
        self.assertTrue(any("must not encode a school class" in err for err in validate_package(package)))

    def test_rejects_a_course_binding_inside_the_content_package(self):
        package = sample_package()
        package["course_binding"] = {"institution_id": 1, "course_id": 2}
        self.assertTrue(any("course_binding is not portable" in err for err in validate_package(package)))

    def test_rejects_duplicate_question_ids(self):
        package = sample_package()
        package["questions"].append(package["questions"][0].copy())
        self.assertTrue(any("duplicated" in err for err in validate_package(package)))

    def test_rejects_answer_key_outside_the_options(self):
        package = sample_package()
        package["questions"][0]["proposed_answer"]["option_key"] = "Z"
        self.assertTrue(any("must match an option key" in err for err in validate_package(package)))

    def test_answer_completeness_is_an_explicit_later_gate(self):
        package = sample_package()
        package["questions"][0]["proposed_answer"]["option_key"] = None
        errors = validate_package(package)
        self.assertFalse(any("no proposed answer" in err for err in errors))
        errors = validate_package(package, require_complete_answers=True)
        self.assertTrue(any("has no proposed answer" in err for err in errors))

    def test_prevents_student_publication_of_drafts(self):
        package = sample_package()
        package["editorial"]["student_delivery_allowed"] = True
        self.assertTrue(any("student_delivery_allowed false" in err for err in validate_package(package)))


if __name__ == "__main__":
    unittest.main()
