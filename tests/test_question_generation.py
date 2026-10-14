import copy
import importlib.util
import io
import json
import unittest
import urllib.error
from unittest.mock import MagicMock, patch
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def load_script(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    module = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    spec.loader.exec_module(module)
    return module


GEN = load_script("generate_questions_from_extraction", "scripts/generate_questions_from_extraction.py")
REVIEW = load_script("review_generated_question_bundle", "scripts/review_generated_question_bundle.py")


def sample_extraction():
    return {
        "schema_version": 1,
        "bundle_status": "DRAFT_EXTRACTION_ONLY",
        "curriculum": {"board": "Bihar Board", "class_code": "7", "subject_code": "maths", "language": "hi"},
        "source": {
            "title": "गणित भाग-7",
            "url": "https://scert.bihar.gov.in/textbooks",
            "edition": "2025-26 verified edition",
            "publication_year": 2025,
            "pdf_page_count": 3,
            "pdf_sha256": "a" * 64,
        },
        "chapter_map": [{"code": "algebraic-expressions", "title": "बीजीय व्यंजक", "page_start": 2, "page_end": 3}],
        "extraction": {"student_delivery_allowed": False, "requires_human_review": True},
        "pages": [
            {"page_number": 1, "chapter_code": None, "chapter_title": None, "text": "मुद्रण संस्करण 2025-26।"},
            {"page_number": 2, "chapter_code": "algebraic-expressions", "chapter_title": "बीजीय व्यंजक", "text": "चर और अचर पदों वाले व्यंजकों में अक्षर, संख्या और गणितीय क्रियाएँ होती हैं।"},
            {"page_number": 3, "chapter_code": "algebraic-expressions", "chapter_title": "बीजीय व्यंजक", "text": "उदाहरण: x + 3 में x चर है और 3 अचर पद है। व्यंजक का मान निकालने के लिए चर के स्थान पर दिया गया मान रखें और फिर गणना करें।"},
        ],
    }


def sample_question():
    return {
        "question_type": "MCQ",
        "prompt": "यदि x = 2 हो, तो x + 3 का मान क्या है?",
        "difficulty": "EASY",
        "explanation": "x के स्थान पर 2 रखने पर 2 + 3 = 5।",
        "options": [
            {"key": "A", "label": "4", "correct": False},
            {"key": "B", "label": "5", "correct": True},
            {"key": "C", "label": "6", "correct": False},
            {"key": "D", "label": "7", "correct": False},
        ],
        "answer_payload": {"kind": "OPTION", "value": "B"},
        "source_page_numbers": [2],
        "topic": "बीजीय व्यंजक",
        "subtopic": "चर का मान रखना",
        "skill": "मूल्य की गणना",
    }


def sample_model_output():
    return {
        "lesson_title": "चर का मान रखकर व्यंजक हल करें",
        "lesson_summary": "चर का मान रखकर बीजीय व्यंजक का मान निकालें।",
        "lesson_explanation": "किसी व्यंजक में चर के स्थान पर दिया गया मान रखने के बाद गणना की जाती है।",
        "worked_example": "उदाहरण: x = 2 होने पर x + 3 = 2 + 3 = 5।",
        "guided_practice": "अब x = 4 रखें और x + 3 का मान निकालें।",
        "independent_practice": "x = 5 होने पर x + 3 का मान स्वयं निकालें।",
        "recap": "चर का मान रखने के बाद व्यंजक की गणना करें।",
        "questions": [sample_question()],
    }


class QuestionGenerationTests(unittest.TestCase):
    def test_valid_extraction_is_grouped_by_chapter(self):
        curriculum, source, chapters = GEN.validate_extraction(sample_extraction())
        self.assertEqual("7", curriculum["class_code"])
        self.assertEqual(3, source["pdf_page_count"])
        self.assertEqual("algebraic-expressions", chapters[0]["code"])
        self.assertEqual([2, 3], [p["page_number"] for p in chapters[0]["pages"]])
        self.assertIn("संस्करण 2025-26", source["printed_imprint"])

    def test_model_call_retries_transient_rate_limit_and_honors_retry_after(self):
        response_content = json.dumps(sample_model_output(), ensure_ascii=False)
        response_body = json.dumps({
            "choices": [{"message": {"content": response_content}}]
        }, ensure_ascii=False).encode("utf-8")
        response = MagicMock()
        response.__enter__.return_value = response
        response.read.return_value = response_body
        rate_limit = urllib.error.HTTPError(
            "https://api.example.com/v1/chat/completions",
            429, "Too Many Requests", {"Retry-After": "0"}, io.BytesIO(b"rate limited"),
        )

        with patch.object(GEN.urllib.request, "urlopen", side_effect=[rate_limit, response]) as request, \
                patch.object(GEN.time, "sleep") as sleep:
            attempts = [0]
            result = GEN.call_model(
                "prompt", "test-key", "https://api.example.com/v1", "test-model", 1,
                attempt_counter=attempts,
            )

        self.assertEqual(sample_model_output(), result)
        self.assertEqual(2, request.call_count)
        self.assertEqual(2, attempts[0])
        sleep.assert_called_once_with(0.0)

    def test_model_call_does_not_retry_permanent_authentication_errors(self):
        unauthorized = urllib.error.HTTPError(
            "https://api.example.com/v1/chat/completions",
            401, "Unauthorized", {}, io.BytesIO(b"invalid key"),
        )
        with patch.object(GEN.urllib.request, "urlopen", side_effect=unauthorized) as request, \
                patch.object(GEN.time, "sleep") as sleep:
            with self.assertRaisesRegex(RuntimeError, "HTTP 401"):
                GEN.call_model("prompt", "bad-key", "https://api.example.com/v1", "test-model", 1)
        self.assertEqual(1, request.call_count)
        sleep.assert_not_called()

    def test_resume_candidate_requires_matching_source_and_review_gate(self):
        curriculum, source, chapters = GEN.validate_extraction(sample_extraction())
        chapter = chapters[0]
        chunk = {"page_numbers": [2, 3], "page_start": 2, "page_end": 3, "text": "excerpt"}
        checked = GEN.validate_model_output(sample_model_output(), ["MCQ"], chunk, "hi")
        candidate = GEN.build_candidate_bundle(curriculum, source, chapter, [(chunk, checked)], "test-model")

        self.assertEqual(1, GEN.validate_resume_candidate(
            candidate, curriculum, source, chapter, 1, "test-model",
        ))

        wrong_source = copy.deepcopy(candidate)
        wrong_source["source"]["pdf_sha256"] = "b" * 64
        with self.assertRaisesRegex(ValueError, "different source PDF"):
            GEN.validate_resume_candidate(wrong_source, curriculum, source, chapter, 1, "test-model")

        auto_publish = copy.deepcopy(candidate)
        auto_publish["generated_by"]["auto_publish"] = True
        with self.assertRaisesRegex(ValueError, "review-only publishing gate"):
            GEN.validate_resume_candidate(auto_publish, curriculum, source, chapter, 1, "test-model")

    def test_unknown_edition_is_rejected(self):
        bundle = sample_extraction()
        bundle["source"]["edition"] = "unknown"
        with self.assertRaisesRegex(ValueError, "actual printed textbook edition"):
            GEN.validate_extraction(bundle)

    def test_invalid_page_map_is_rejected(self):
        bundle = sample_extraction()
        bundle["chapter_map"][0]["page_end"] = 4
        with self.assertRaisesRegex(ValueError, "Invalid page range"):
            GEN.validate_extraction(bundle)

    def test_chapter_without_extractable_text_is_rejected(self):
        bundle = sample_extraction()
        bundle["pages"][1]["text"] = ""
        bundle["pages"][2]["text"] = ""
        with self.assertRaisesRegex(ValueError, "no extractable text"):
            GEN.validate_extraction(bundle)

    def test_chunking_preserves_all_text_and_page_numbers(self):
        pages = [{"page_number": 1, "text": "A" * 2200}, {"page_number": 2, "text": "B" * 900}]
        chunks = GEN.make_chunks(pages, 2000)
        self.assertEqual(2, len(chunks))
        self.assertIn(1, chunks[0]["page_numbers"])
        self.assertTrue(all(chunk["text"] for chunk in chunks))
        self.assertEqual(3100, sum(chunk["text"].count("A") + chunk["text"].count("B") for chunk in chunks))

    def test_question_allocation_hits_exact_target(self):
        chunks = [
            {"page_numbers": [i + 1], "page_start": i + 1, "page_end": i + 1, "text": "content"}
            for i in range(5)
        ]
        allocation = GEN.allocate_questions(chunks, 12)
        self.assertEqual(12, sum(count for _, count in allocation))
        self.assertEqual(5, len(allocation))
        self.assertEqual([3, 3, 2, 2, 2], [count for _, count in allocation])

    def test_long_chapter_sampling_spans_chapter_and_caps_calls(self):
        chunks = [
            {"page_numbers": [i + 1], "page_start": i + 1, "page_end": i + 1, "text": "content"}
            for i in range(20)
        ]
        allocation = GEN.allocate_questions(chunks, 4)
        self.assertEqual(4, len(allocation))
        self.assertEqual(4, sum(count for _, count in allocation))
        self.assertEqual(1, allocation[0][0]["page_start"])
        self.assertEqual(20, allocation[-1][0]["page_end"])

    def test_valid_generated_mcq_passes_checks(self):
        chunk = {"page_numbers": [2, 3], "page_start": 2, "page_end": 3, "text": "excerpt"}
        checked = GEN.validate_model_output(sample_model_output(), ["MCQ"], chunk, "hi")
        self.assertEqual("B", checked["questions"][0]["answer_payload"]["value"])

    def test_mcq_must_have_exactly_one_answer_matching_key(self):
        output = sample_model_output()
        output["questions"][0]["options"][0]["correct"] = True
        chunk = {"page_numbers": [1, 2], "page_start": 1, "page_end": 2, "text": "excerpt"}
        with self.assertRaisesRegex(ValueError, "exactly one correct option"):
            GEN.validate_model_output(output, ["MCQ"], chunk)

    def test_questions_cannot_reference_pages_outside_their_excerpt(self):
        output = sample_model_output()
        output["questions"][0]["source_page_numbers"] = [99]
        chunk = {"page_numbers": [1, 2], "page_start": 1, "page_end": 2, "text": "excerpt"}
        with self.assertRaisesRegex(ValueError, "cite only pages"):
            GEN.validate_model_output(output, ["MCQ"], chunk)

    def test_unsupported_types_are_rejected(self):
        output = sample_model_output()
        output["questions"][0]["question_type"] = "LONG_ANSWER"
        chunk = {"page_numbers": [1, 2], "page_start": 1, "page_end": 2, "text": "excerpt"}
        with self.assertRaisesRegex(ValueError, "disallowed type"):
            GEN.validate_model_output(output, ["MCQ"], chunk)

    def test_input_and_numerical_answer_payloads_are_typed(self):
        chunk = {"page_numbers": [2], "page_start": 2, "page_end": 2, "text": "excerpt"}
        output = sample_model_output()
        q = output["questions"][0]
        q.update(question_type="INPUT", options=[], answer_payload={"kind": "TEXT", "value": "5"})
        checked = GEN.validate_model_output(output, ["INPUT"], chunk, "hi")
        self.assertEqual("TEXT", checked["questions"][0]["answer_payload"]["kind"])
        q.update(question_type="NUMERICAL", answer_payload={"kind": "NUMERIC", "value": "5"})
        with self.assertRaisesRegex(ValueError, "finite numeric"):
            GEN.validate_model_output(output, ["NUMERICAL"], chunk)

    def test_question_type_mix_is_deterministic_and_curriculum_aware(self):
        maths = GEN.scheduled_question_types(12, {"MCQ", "TRUE_FALSE", "INPUT", "NUMERICAL"}, "maths")
        self.assertEqual(6, maths.count("MCQ"))
        self.assertEqual(2, maths.count("TRUE_FALSE"))
        self.assertEqual(2, maths.count("INPUT"))
        self.assertEqual(2, maths.count("NUMERICAL"))
        science = GEN.scheduled_question_types(12, {"MCQ", "TRUE_FALSE", "INPUT"}, "science")
        self.assertEqual(7, science.count("MCQ"))
        self.assertEqual(3, science.count("INPUT"))
        self.assertEqual(2, science.count("TRUE_FALSE"))

    def test_candidate_bundle_is_never_auto_approved(self):
        _, source, chapters = GEN.validate_extraction(sample_extraction())
        chunk = {"page_numbers": [2, 3], "page_start": 2, "page_end": 3, "text": "excerpt"}
        checked = GEN.validate_model_output(sample_model_output(), ["MCQ"], chunk, "hi")
        bundle = GEN.build_candidate_bundle(
            sample_extraction()["curriculum"], source, chapters[0], [(chunk, checked)], "test-model"
        )
        self.assertEqual("AI_GENERATED_DRAFT", bundle["bundle_status"])
        self.assertTrue(bundle["generated_by"]["review_required"])
        self.assertFalse(bundle["generated_by"]["auto_publish"])
        self.assertNotIn("editorial_declaration", bundle)
        self.assertIn("PDF page(s) 2", bundle["lessons"][0]["questions"][0]["source_ref"])

    def test_review_gate_requires_all_five_confirmations(self):
        _, source, chapters = GEN.validate_extraction(sample_extraction())
        chunk = {"page_numbers": [2, 3], "page_start": 2, "page_end": 3, "text": "excerpt"}
        checked = GEN.validate_model_output(sample_model_output(), ["MCQ"], chunk, "hi")
        candidate = GEN.build_candidate_bundle(
            sample_extraction()["curriculum"], source, chapters[0], [(chunk, checked)], "test-model"
        )
        with self.assertRaisesRegex(ValueError, "All five editorial confirmations"):
            REVIEW.review_candidate(candidate, "Test reviewer", "2026-10-09", [True, True, True, True, False])
        reviewed = REVIEW.review_candidate(candidate, "Test reviewer", "2026-10-09", [True] * 5)
        self.assertEqual("EDITOR_REVIEWED_DRAFT", reviewed["bundle_status"])
        self.assertEqual("Test reviewer", reviewed["editorial_declaration"]["reviewer"])
        self.assertTrue(reviewed["editorial_declaration"]["rights_reviewed"])
        self.assertEqual("AI_GENERATED_DRAFT", candidate["bundle_status"])

    def test_api_endpoint_supports_base_and_v1_urls(self):
        self.assertEqual("https://example.test/v1/chat/completions", GEN.model_endpoint("https://example.test"))
        self.assertEqual("https://example.test/v1/chat/completions", GEN.model_endpoint("https://example.test/v1"))
        self.assertEqual("https://example.test/v1/chat/completions", GEN.model_endpoint("https://example.test/v1/chat/completions"))


if __name__ == "__main__":
    unittest.main()
