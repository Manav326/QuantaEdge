import csv
import tempfile
import unittest
from pathlib import Path

from unittest.mock import patch
import json

from scripts.generate_questions_from_extraction import (
    SUPPORTED_TYPES,
    call_model,
    is_loopback_model_url,
    model_endpoint,
    model_request_headers,
    load_curriculum_question_targets,
    validate_extraction,
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


def extraction_bundle(language, text, edition="2025–26 reprint"):
    code = "ch1"
    chapter_title = "भिन्नों का परिचय" if language in {"hi", "hindi"} else "Introduction to fractions"
    return {
        "schema_version": 1,
        "bundle_status": "DRAFT_EXTRACTION_ONLY",
        "curriculum": {"class_code": "6", "subject_code": "maths", "language": language},
        "source": {
            "title": "Bihar Board Mathematics",
            "url": "https://scert.bihar.gov.in/eresources/sample",
            "edition": edition,
            "pdf_page_count": 2,
            "pdf_sha256": "a" * 64,
        },
        "chapter_map": [{"code": code, "title": chapter_title, "page_start": 2, "page_end": 2}],
        "extraction": {"requires_human_review": True},
        "pages": [
            {
                "page_number": 1,
                "chapter_code": None,
                "text": "मुद्रण संस्करण 2025-26। " if language in {"hi", "hindi"} else "Printed edition 2025-26.",
            },
            {"page_number": 2, "chapter_code": code, "text": text},
        ],
    }


def hindi_lesson_result():
    data = lesson_result(question(
        "MATCH",
        prompt="भिन्न को उसके सही अर्थ से मिलाइए।",
        explanation="एक आधा चार बराबर भागों में से दो भागों को दर्शाता है। इसे मिलान करने से पहले दृश्य की जाँच करें।",
        topic="भिन्न", subtopic="समान भाग", skill="प्रतिनिधित्व का मिलान",
    ))
    data.update({
        "lesson_title": "दैनिक जीवन में भिन्न",
        "lesson_summary": "भिन्न किसी पूर्ण वस्तु के बराबर भागों को दर्शाती है।",
        "lesson_explanation": "जब किसी वस्तु को बराबर भागों में बाँटा जाता है, तब चुने गए भागों को भिन्न से दिखाते हैं।",
        "worked_example": "एक रोटी के चार बराबर टुकड़े करें। दो टुकड़े दो चौथाई हैं।",
        "guided_practice": "शिक्षक के साथ पाँच बराबर टुकड़ों में से तीन टुकड़े गिनें।",
        "independent_practice": "छह बराबर भागों में से एक भाग की भिन्न लिखें।",
        "recap": "हर नीचे बराबर भागों की संख्या और अंश चुने गए भागों की संख्या बताता है।",
    })
    return data



class FakeHttpResponse:
    def __init__(self, body):
        self.body = body.encode("utf-8")

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def read(self):
        return self.body


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

    def test_loopback_in_house_model_endpoint_needs_no_cloud_key_or_external_consent(self):
        self.assertTrue(is_loopback_model_url("http://localhost:11434"))
        self.assertTrue(is_loopback_model_url("http://127.0.0.1:11434/v1"))
        self.assertTrue(is_loopback_model_url("http://[::1]:11434/v1"))

    def test_only_exact_loopback_hosts_are_treated_as_local(self):
        self.assertFalse(is_loopback_model_url("http://localhost.attacker.example"))
        self.assertFalse(is_loopback_model_url("http://127.0.0.1.attacker.example"))
        self.assertFalse(is_loopback_model_url("http://192.168.1.20:11434"))
        self.assertFalse(is_loopback_model_url("https://api.example.com"))
        self.assertFalse(is_loopback_model_url("http://user:pass@localhost:11434"))

    def test_local_model_request_omits_fabricated_authorization_header(self):
        headers = model_request_headers("")
        self.assertNotIn("Authorization", headers)
        self.assertEqual("application/json; charset=utf-8", headers["Content-Type"])

    def test_configured_hosted_model_key_is_sent_as_bearer(self):
        self.assertEqual("Bearer test-key", model_request_headers(" test-key ")["Authorization"])


    def test_native_ollama_uses_api_chat_json_mode_without_openai_key(self):
        captured = {}
        answer = lesson_result(question("MATCH"))
        def open_url(request, timeout=90):
            captured["url"] = request.full_url
            captured["headers"] = {key.lower(): value for key, value in request.header_items()}
            captured["payload"] = json.loads(request.data.decode("utf-8"))
            return FakeHttpResponse(json.dumps({"message": {"content": json.dumps(answer, ensure_ascii=False)}}))
        with patch("scripts.generate_questions_from_extraction.urllib.request.urlopen", open_url):
            result = call_model("prompt", "", "http://127.0.0.1:11434", "qwen2.5:1.5b", 10, provider="ollama")
        self.assertTrue(captured["url"].endswith("/api/chat"))
        self.assertNotIn("authorization", captured["headers"])
        self.assertEqual("json", captured["payload"]["format"])
        self.assertFalse(captured["payload"]["stream"])
        self.assertEqual(8192, captured["payload"]["options"]["num_ctx"])
        self.assertEqual(4096, captured["payload"]["options"]["num_predict"])
        self.assertEqual(answer, result)

    def test_native_ollama_refuses_non_loopback_before_network(self):
        with self.assertRaisesRegex(ValueError, "loopback"):
            call_model("prompt", "", "https://example.com", "qwen2.5:1.5b", 10, provider="ollama")

    def test_openai_compatible_provider_remains_explicit_and_supported(self):
        captured = {}
        answer = lesson_result(question("MATCH"))
        def open_url(request, timeout=90):
            captured["url"] = request.full_url
            captured["headers"] = {key.lower(): value for key, value in request.header_items()}
            captured["payload"] = json.loads(request.data.decode("utf-8"))
            return FakeHttpResponse(json.dumps({"choices": [{"message": {"content": json.dumps(answer)}}]}))
        with patch("scripts.generate_questions_from_extraction.urllib.request.urlopen", open_url):
            result = call_model("prompt", "secret-test-token", "https://api.example.com", "test-model", 10,
                                provider="openai-compatible")
        self.assertTrue(captured["url"].endswith("/v1/chat/completions"))
        self.assertEqual("Bearer secret-test-token", captured["headers"]["authorization"])
        self.assertEqual("json_object", captured["payload"]["response_format"]["type"])
        self.assertEqual(answer, result)

    def test_model_endpoint_defaults_to_native_ollama_and_preserves_legacy_api_path(self):
        self.assertEqual("http://127.0.0.1:11434/api/chat", model_endpoint("http://127.0.0.1:11434"))
        self.assertEqual("https://api.example.com/v1/chat/completions",
                         model_endpoint("https://api.example.com", "openai-compatible"))

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

    def test_hindi_source_requires_explicit_language_and_devanagari_text(self):
        hindi_text = "यह गणित की पुस्तक है जिसमें भिन्नों के उदाहरण और समान भागों का वर्णन है। विद्यार्थियों को अभ्यास कराया जाता है।"
        curriculum, source, chapters = validate_extraction(extraction_bundle("hi", hindi_text))
        self.assertEqual("hi", curriculum["language"])
        self.assertEqual("ch1", chapters[0]["code"])
        self.assertIn("संस्करण 2025-26", source["printed_imprint"])

    def test_english_only_source_cannot_be_labelled_as_hindi(self):
        english_text = "This mathematics textbook explains fractions and equal parts with examples for students."
        with self.assertRaisesRegex(ValueError, "source-language check failed"):
            validate_extraction(extraction_bundle("hi", english_text))

    def test_placeholder_source_edition_is_rejected_even_when_phrase_is_not_exact_match(self):
        text = "This mathematics textbook explains fractions and equal parts with examples for students."
        bundle = extraction_bundle("en", text, "Current official catalogue; retain printed reprint year from the source PDF")
        with self.assertRaisesRegex(ValueError, "actual printed textbook edition"):
            validate_extraction(bundle)

    def test_model_cannot_return_english_draft_for_hindi_medium(self):
        with self.assertRaisesRegex(ValueError, "Model output language check failed"):
            validate_model_output(lesson_result(question("MATCH")), ["MATCH"], self.chunk, "hi")

    def test_model_cannot_return_hindi_draft_for_english_medium(self):
        with self.assertRaisesRegex(ValueError, "Model output language check failed"):
            validate_model_output(hindi_lesson_result(), ["MATCH"], self.chunk, "en")

    def test_hindi_model_output_passes_the_language_gate(self):
        result = validate_model_output(hindi_lesson_result(), ["MATCH"], self.chunk, "hi")
        self.assertEqual("MATCH", result["questions"][0]["question_type"])



if __name__ == "__main__":
    unittest.main()
