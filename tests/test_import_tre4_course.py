"""Offline tests for the TRE-4 draft course importer; these tests do not touch PostgreSQL."""
from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "import_tre4_course.py"
SPEC = importlib.util.spec_from_file_location("import_tre4_course", SCRIPT)
assert SPEC and SPEC.loader
import_tre4_course = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(import_tre4_course)


class Tre4ImporterTests(unittest.TestCase):
    def test_expected_primary_booklet_counts(self):
        self.assertEqual(import_tre4_course.expected_question_count("TRE 1.0"), 120)
        self.assertEqual(import_tre4_course.expected_question_count("TRE 2.0"), 150)
        self.assertEqual(import_tre4_course.expected_question_count("TRE 3.0"), 150)

    def test_question_snapshot_preserves_bilingual_options_and_review_gates(self):
        row = {
            "source_question_number": 71,
            "section_code": "COMPUTER_SCIENCE",
            "source_variants": {
                "en": {
                    "prompt_i18n": "Which option is correct?",
                    "options": [
                        {"key": "A", "text_i18n": "Alpha", "content_kind": "PROSE"},
                        {"key": "B", "text_i18n": "Beta", "content_kind": "PROSE"},
                    ],
                },
                "hi": {
                    "prompt_i18n": "कौन सा विकल्प सही है?",
                    "options": [
                        {"key": "A", "text_i18n": "अल्फा"},
                        {"key": "B", "text_i18n": "बीटा"},
                    ],
                },
            },
            "review_flags": ["EXAMPLE_REVIEW_FLAG"],
        }
        snapshot = import_tre4_course.build_question_snapshot(
            row, "TRE 1.0", "data/TRE-Papres/sources/example.pdf", "a" * 64
        )
        self.assertEqual(snapshot["prompt_i18n"]["hi"], "कौन सा विकल्प सही है?")
        self.assertEqual(snapshot["options"][0]["text_i18n"], {"en": "Alpha", "hi": "अल्फा"})
        self.assertEqual(snapshot["answer_payload"], {"kind": "UNRESOLVED", "value": None})
        self.assertEqual(snapshot["question_review_status"], "PENDING_HUMAN_REVIEW")
        self.assertEqual(snapshot["section_result_policy"], "SCORED")

    def test_language_qualifying_questions_are_not_counted_in_aggregate_score(self):
        row = {
            "source_question_number": "H-9",
            "section_code": "LANGUAGE_QUALIFYING",
            "native_language_only": True,
            "source_variants": {
                "hi": {"prompt_i18n": "यह एक स्रोत प्रश्न है।", "options": []},
            },
        }
        snapshot = import_tre4_course.build_question_snapshot(
            row, "TRE 3.0", "data/TRE-Papres/sources/example.pdf", "b" * 64
        )
        self.assertEqual(snapshot["section_result_policy"], "QUALIFYING_ONLY")
        self.assertTrue(snapshot["native_language_only"])

    def test_empty_prompt_fails_closed(self):
        with self.assertRaises(import_tre4_course.ImportErrorSafe):
            import_tre4_course.build_question_snapshot(
                {"source_question_number": 3, "section_code": "COMPUTER_SCIENCE", "source_variants": {}},
                "TRE 1.0", "source.pdf", "c" * 64
            )

    def test_normalizer_preserves_code_indentation_and_internal_newlines(self):
        raw = "    SELECT *\r\n    FROM question\r\n"
        self.assertEqual(import_tre4_course.norm_text(raw), "    SELECT *\n    FROM question")

    def test_exact_tre3_identity_set_preserves_all_30_language_items(self):
        expected = import_tre4_course.EXPECTED_SOURCE_IDENTITIES["TRE 3.0"]["LANGUAGE_QUALIFYING"]
        self.assertEqual(len(expected), 30)
        self.assertEqual(expected, {f"E-{n}" for n in range(1, 9)} | {f"H-{n}" for n in range(9, 31)})

    def test_inventory_validator_rejects_duplicate_identity(self):
        valid = [
            {"question_id": "q-1", "source_question_number": 1, "section_code": "GENERAL_STUDIES"},
            {"question_id": "q-1-dup", "source_question_number": 1, "section_code": "GENERAL_STUDIES"},
        ]
        with self.assertRaises(import_tre4_course.ImportErrorSafe):
            import_tre4_course.validate_question_inventory("TRE 1.0", valid)

    def test_hash_is_stable_for_dictionary_key_order(self):
        first = {"b": 2, "a": 1}
        second = {"a": 1, "b": 2}
        self.assertEqual(import_tre4_course.sha256_json(first), import_tre4_course.sha256_json(second))


if __name__ == "__main__":
    unittest.main()
