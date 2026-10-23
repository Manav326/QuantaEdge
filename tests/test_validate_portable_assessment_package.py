from __future__ import annotations

import unittest

from scripts.validate_portable_assessment_package import validate_package


def question(qid: str, section: str, number: int) -> dict:
    return {
        "id": qid,
        "source_question_number": number,
        "source_location": {"pdf_page": number, "printed_page": number, "source_language_page": number + 1},
        "section_code": section,
        "section_result_policy": "SCORED",
        "question_type": "MCQ",
        "prompt": "Which data structure follows FIFO?",
        "prompt_i18n": {
            "en": "Which data structure follows FIFO?",
            "hi": "कौन-सी डेटा संरचना FIFO का पालन करती है?"
        },
        "options": [
            {
                "key": "A",
                "text": "Stack",
                "text_i18n": {"en": "Stack", "hi": "स्टैक"}
            },
            {
                "key": "B",
                "text": "Queue",
                "text_i18n": {"en": "Queue", "hi": "क्यू"}
            }
        ],
        "topic_tags": ["data-structures", "queue"],
        "secondary_topic_tags": [],
        "marks": 1,
        "proposed_answer": {
            "option_key": "B",
            "confidence": "HIGH",
            "explanation": "A queue removes items in the order they were inserted.",
            "explanation_i18n": {
                "en": "A queue removes items in the order they were inserted.",
                "hi": "क्यू में पहले डाला गया तत्व पहले निकाला जाता है।"
            },
            "solution_mode": "CONCISE",
            "answer_source": "INDEPENDENTLY_SOLVED",
            "answer_reference_url": None,
            "review_status": "PENDING_HUMAN_REVIEW",
            "reviewer_notes": ""
        },
        "question_review_status": "PENDING_HUMAN_REVIEW",
        "source_notes": "Fictional test fixture; not from an actual TRE paper."
    }


def sample_package() -> dict:
    return {
        "schema": "quantaedge.portable-assessment-package.v1",
        "package_id": "sample-tre-test",
        "package_version": "1.0.0",
        "title": "TRE sample previous-year paper",
        "assessment_type": "PREVIOUS_YEAR_PAPER",
        "subject": {"name": "Computer Science", "domain_code": "computer-science", "language": "mixed"},
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
            "available_languages": ["en", "hi"],
            "notes": "Draft test fixture only."
        },
        "configuration": {
            "duration_minutes": 120,
            "marks_per_question": 1,
            "negative_marking_fraction": 0.25,
            "unanswered_mark": 0,
            "presentation_hints": ["Full-paper practice"]
        },
        "sections": [
            {
                "code": "GENERAL_STUDIES",
                "name_i18n": {"en": "General Studies", "hi": "सामान्य अध्ययन"},
                "question_count": 1,
                "result_policy": "SCORED"
            },
            {
                "code": "COMPUTER_SCIENCE",
                "name_i18n": {"en": "Computer Science", "hi": "कंप्यूटर विज्ञान"},
                "question_count": 1,
                "result_policy": "SCORED"
            }
        ],
        "questions": [
            question("sample-gs-q1", "GENERAL_STUDIES", 1),
            question("sample-cs-q1", "COMPUTER_SCIENCE", 41)
        ],
        "editorial": {
            "status": "DRAFT",
            "student_delivery_allowed": False,
            "review_status": "PENDING_HUMAN_REVIEW",
            "reviewer": None,
            "reviewed_at": None,
            "review_notes": ""
        }
    }


def native_language_package() -> dict:
    package = sample_package()
    package["sections"].append({
        "code": "LANGUAGE_QUALIFYING",
        "name_i18n": {"en": "Qualifying language", "hi": "अर्हकारी भाषा"},
        "question_count": 2,
        "result_policy": "QUALIFYING_ONLY"
    })
    for number in (101, 102):
        q = question(f"sample-language-e{number}", "LANGUAGE_QUALIFYING", number)
        q["section_result_policy"] = "QUALIFYING_ONLY"
        q["prompt"] = "Choose the correct word for the source-language sentence."
        q["prompt_i18n"] = {"en": q["prompt"]}
        for option in q["options"]:
            option["text_i18n"] = {"en": option["text"]}
        q["proposed_answer"]["explanation_i18n"] = {"en": "The explanation remains in the source language."}
        q["native_language_only"] = True
        q["native_source_language"] = "en"
        q["content_language_policy"] = "NATIVE_SOURCE_ONLY"
        q["stimulus_id"] = "tre-3.0-stimulus-e-1-5"
        q["stimulus_i18n"] = {"en": "An unchanged passage from the original paper."}
        q["stimulus_content_kind"] = "PROSE"
        q["question_set_id"] = q["stimulus_id"]
        q["question_set_type"] = "PASSAGE"
        q["incorrect_marks"] = -0.25
        if number == 102:
            q["question_type"] = "INFERENCE"
            q["options"] = []
            q["proposed_answer"]["option_key"] = None
            q["proposed_answer"]["explanation"] = "An inference must be supported by details in the shared passage."
            q["proposed_answer"]["explanation_i18n"] = {
                "en": "An inference must be supported by details in the shared passage."
            }
        package["questions"].append(q)
    return package


class PortableAssessmentPackageTests(unittest.TestCase):
    def test_accepts_class_neutral_bilingual_sectioned_draft(self):
        self.assertEqual(validate_package(sample_package()), [])

    def test_accepts_native_source_only_questions_sharing_one_passage(self):
        self.assertEqual(validate_package(native_language_package()), [])

    def test_passage_set_is_explicit_one_to_many_and_supports_inference_children(self):
        package = native_language_package()
        first, second = package["questions"][-2:]
        self.assertEqual(first["question_set_type"], "PASSAGE")
        self.assertEqual(first["question_set_id"], second["question_set_id"])
        self.assertEqual(second["question_type"], "INFERENCE")
        self.assertEqual(first["incorrect_marks"], -0.25)
        self.assertEqual(second["options"], [])
        self.assertEqual(validate_package(package), [])

    def test_requires_options_for_mcq_but_allows_written_inference(self):
        package = native_language_package()
        self.assertEqual(package["questions"][-1]["question_type"], "INFERENCE")
        self.assertEqual(package["questions"][-1]["options"], [])
        self.assertEqual(validate_package(package), [])
        package["questions"][0]["options"] = []
        errors = validate_package(package)
        self.assertTrue(any("options must contain 2–5 options for MCQ/TRUE_FALSE" in error for error in errors))

    def test_rejects_passage_question_set_with_only_one_child(self):
        package = native_language_package()
        package["questions"].pop()
        package["sections"][-1]["question_count"] = 1
        errors = validate_package(package)
        self.assertTrue(any("must contain at least two child questions" in error for error in errors))

    def test_rejects_unconfirmed_or_positive_incorrect_marks(self):
        package = native_language_package()
        package["questions"][-1]["incorrect_marks"] = 0.25
        errors = validate_package(package)
        self.assertTrue(any("incorrect_marks must be a number from -1000 to 0" in error for error in errors))

    def test_rejects_translated_fallback_for_native_language_only_question(self):
        package = native_language_package()
        question_data = next(q for q in package["questions"] if q["id"] == "sample-language-e101")
        question_data["prompt_i18n"]["hi"] = "अनुवादित प्रश्न"
        question_data["options"][0]["text_i18n"]["hi"] = "अनुवादित विकल्प"
        errors = validate_package(package)
        self.assertTrue(any("only the native source language" in error for error in errors))

    def test_rejects_different_passage_text_for_same_stimulus_id(self):
        package = native_language_package()
        package["questions"][-1]["stimulus_i18n"]["en"] = "A conflicting passage."
        errors = validate_package(package)
        self.assertTrue(any("conflicts with other questions using shared stimulus ID" in error for error in errors))

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
        package["questions"].append(question("sample-gs-q1", "GENERAL_STUDIES", 2))
        package["sections"][0]["question_count"] = 2
        package["sections"][1]["question_count"] = 1
        self.assertTrue(any("id is duplicated" in err for err in validate_package(package)))

    def test_rejects_answer_key_outside_the_options(self):
        package = sample_package()
        package["questions"][0]["proposed_answer"]["option_key"] = "Z"
        self.assertTrue(any("must match an option key" in err for err in validate_package(package)))

    def test_rejects_missing_hindi_source_variant_for_scored_computer_science(self):
        package = sample_package()
        del package["questions"][1]["prompt_i18n"]["hi"]
        errors = validate_package(package)
        self.assertTrue(any("prompt_i18n is missing required source-language variants: hi" in err for err in errors))

    def test_rejects_missing_hindi_option_text_for_scored_section(self):
        package = sample_package()
        del package["questions"][0]["options"][0]["text_i18n"]["hi"]
        errors = validate_package(package)
        self.assertTrue(any("text_i18n is missing required source-language variants: hi" in err for err in errors))

    def test_rejects_incorrect_declared_section_count(self):
        package = sample_package()
        package["sections"][1]["question_count"] = 80
        errors = validate_package(package)
        self.assertTrue(any("declares 80 questions but contains 1" in err for err in errors))

    def test_rejects_out_of_range_negative_marking_fraction(self):
        package = sample_package()
        package["configuration"]["negative_marking_fraction"] = 1.25
        self.assertTrue(any("must be between 0 and 1" in err for err in validate_package(package)))

    def test_requires_short_explanations(self):
        package = sample_package()
        package["questions"][0]["proposed_answer"]["explanation"] = "x" * 701
        self.assertTrue(any("must stay concise" in err for err in validate_package(package)))

    def test_stepwise_numerical_work_requires_ordered_steps_in_both_languages(self):
        package = sample_package()
        answer = package["questions"][0]["proposed_answer"]
        answer["solution_mode"] = "STEPWISE"
        answer["solution_steps_i18n"] = {
            "en": ["First, identify the given values.", "Then apply the formula."],
            "hi": ["पहले दिए गए मान पहचानें।"]
        }
        self.assertTrue(any("must contain 2–8 ordered" in err for err in validate_package(package)))
        answer["solution_steps_i18n"].pop("en")
        errors = validate_package(package)
        self.assertTrue(any("missing required language steps: en" in err for err in errors))

    def test_answer_completeness_is_an_explicit_later_gate(self):
        package = sample_package()
        package["questions"][0]["proposed_answer"]["option_key"] = None
        errors = validate_package(package)
        self.assertFalse(any("has no proposed answer" in err for err in errors))
        errors = validate_package(package, require_complete_answers=True)
        self.assertTrue(any("has no proposed answer" in err for err in errors))

    def test_prevents_student_publication_of_drafts(self):
        package = sample_package()
        package["editorial"]["student_delivery_allowed"] = True
        self.assertTrue(any("student_delivery_allowed false" in err for err in validate_package(package)))


if __name__ == "__main__":
    unittest.main()
