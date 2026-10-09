import copy
import importlib.util
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).resolve().parents[1] / "scripts" / "import_reviewed_content.py"
SPEC = importlib.util.spec_from_file_location("import_reviewed_content", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(MODULE)


def valid_bundle():
    return {
        "schema_version": 1,
        "bundle_status": "EDITOR_REVIEWED_DRAFT",
        "curriculum": {"board": "Bihar Board", "class_code": "7", "subject_code": "maths", "language": "hi"},
        "source": {
            "title": "SCERT Bihar Class 7 Mathematics",
            "url": "https://scert.bihar.gov.in/textbooks",
            "edition": "2025-26 verified PDF edition",
            "publication_year": 2025,
            "pdf_page_count": 100,
            "pdf_sha256": "a" * 64,
        },
        "editorial_declaration": {
            "original_content": True,
            "rights_reviewed": True,
            "reviewer": "Curriculum editor",
            "reviewed_at": "2026-10-09",
        },
        "chapter": {
            "code": "algebraic-expressions-new",
            "display_name": "बीजीय व्यंजक — नया पाठ",
            "description": "Original explanation of the chapter concept.",
            "sort_order": 1,
            "source_pages": "PDF pages 20-28",
        },
        "lessons": [
            {
                "code": "algebraic-expressions-new-intro",
                "title": "बीजीय व्यंजक समझें",
                "summary": "An original lesson summary.",
                "estimated_minutes": 12,
                "sort_order": 1,
                "source_pages": "PDF pages 20-23",
                "blocks": [
                    {
                        "sequence_no": 1,
                        "block_type": "EXPLANATION",
                        "content": {
                            "heading": "व्यंजक क्या है?",
                            "body": "यह QuantaEdge द्वारा लिखा गया मूल विवरण है।",
                        },
                    }
                ],
                "questions": [
                    {
                        "question_type": "MCQ",
                        "prompt": "यदि x = 2 हो, तो x + 3 का मान क्या है?",
                        "difficulty": "EASY",
                        "sort_order": 1,
                        "explanation": "x के स्थान पर 2 रखने पर 2 + 3 = 5।",
                        "answer_payload": {"kind": "OPTION", "value": "B"},
                        "options": [
                            {"key": "A", "label": "4", "correct": False},
                            {"key": "B", "label": "5", "correct": True},
                        ],
                    }
                ],
            }
        ],
    }


class ReviewedContentImportTests(unittest.TestCase):
    def test_valid_original_content_bundle_passes_validation(self):
        bundle = valid_bundle()
        self.assertIs(MODULE.validate_bundle(bundle), bundle)

    def test_raw_extraction_bundle_is_rejected(self):
        bundle = valid_bundle()
        bundle["bundle_status"] = "DRAFT_EXTRACTION_ONLY"
        bundle["pages"] = [{"page_number": 1, "text": "copied source text"}]
        with self.assertRaisesRegex(ValueError, "Raw extraction bundles cannot be imported"):
            MODULE.validate_bundle(bundle)

    def test_missing_original_content_attestation_is_rejected(self):
        bundle = valid_bundle()
        bundle["editorial_declaration"]["original_content"] = False
        with self.assertRaisesRegex(ValueError, "original_content=true"):
            MODULE.validate_bundle(bundle)

    def test_mcq_must_have_exactly_one_correct_option_and_matching_answer_key(self):
        bundle = valid_bundle()
        bundle["lessons"][0]["questions"][0]["options"][0]["correct"] = True
        with self.assertRaisesRegex(ValueError, "exactly one correct option"):
            MODULE.validate_bundle(bundle)
        bundle = valid_bundle()
        bundle["lessons"][0]["questions"][0]["answer_payload"]["value"] = "Z"
        with self.assertRaisesRegex(ValueError, "must match one of its option keys"):
            MODULE.validate_bundle(bundle)

    def test_source_edition_cannot_be_a_placeholder(self):
        bundle = valid_bundle()
        bundle["source"]["edition"] = "unknown"
        with self.assertRaisesRegex(ValueError, "do not use a placeholder"):
            MODULE.validate_bundle(bundle)

    def test_page_ranges_must_be_explicit_and_inside_the_chapter(self):
        bundle = valid_bundle()
        bundle["lessons"][0]["source_pages"] = "PDF pages 19-23"
        with self.assertRaisesRegex(ValueError, "inside the reviewed chapter page range"):
            MODULE.validate_bundle(bundle)
        bundle = valid_bundle()
        bundle["chapter"]["source_pages"] = "PDF pages 95-105"
        with self.assertRaisesRegex(ValueError, "within PDF pages"):
            MODULE.validate_bundle(bundle)

    def test_payloads_force_draft_and_never_publish(self):
        bundle = valid_bundle()
        operations = MODULE.make_request_payloads(bundle)
        self.assertEqual("DRAFT", operations[0]["body"]["status"])
        self.assertEqual("DRAFT", operations[1]["body"]["status"])
        self.assertEqual("DRAFT", operations[2]["body"]["status"])
        patch = operations[2]["body"]
        self.assertFalse(patch["alignmentSourceVerified"])
        self.assertEqual("DRAFT", patch["questions"][0]["review_status"])
        self.assertEqual("AUTHOR_CREATED", patch["questions"][0]["source_kind"])
        self.assertFalse(patch["questions"][0].get("correctAnswer"))

    def test_dry_run_does_not_require_session_or_call_network(self):
        result = MODULE.run_import(valid_bundle(), "http://localhost:8080", "", dry_run=True)
        self.assertEqual("DRY_RUN", result["mode"])
        self.assertEqual(1, result["questions"])
        self.assertEqual("DRAFT", result["all_statuses"])
        self.assertFalse(result["student_delivery_allowed"])

    def test_duplicate_lesson_codes_are_rejected_before_any_api_call(self):
        bundle = valid_bundle()
        bundle["lessons"].append(copy.deepcopy(bundle["lessons"][0]))
        with self.assertRaisesRegex(ValueError, "Lesson codes must be unique"):
            MODULE.validate_bundle(bundle)


if __name__ == "__main__":
    unittest.main()
