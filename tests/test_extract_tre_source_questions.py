from __future__ import annotations

import unittest

from scripts.extract_tre_source_questions import (
    PAPERS,
    classify_content,
    detected_code_line_indices,
    detected_math_line_indices,
    expected_language_keys,
    parse_prompt_options,
    preserve_layout,
)


class TreSourceCodePreservationTests(unittest.TestCase):
    def test_tre3_source_uses_its_actual_148_question_count(self):
        self.assertEqual(PAPERS["TRE 3.0"]["total_questions"], 148)
        language_keys = expected_language_keys("TRE 3.0")
        self.assertEqual(len(language_keys), 28)
        self.assertEqual(
            language_keys,
            {f"E-{i}" for i in (2, 3, 4, 5, 7, 8)} | {f"H-{i}" for i in range(9, 31)},
        )
        self.assertNotIn("E-1", language_keys)
        self.assertNotIn("E-6", language_keys)
        self.assertEqual(40 + 80 + len(language_keys), 148)

    def test_preserve_layout_keeps_indentation_and_blank_lines(self):
        original = 'for (i = 0; i < 3; i++) {\n    printf("%d", i);\n}\n'
        self.assertEqual(
            preserve_layout(original),
            'for (i = 0; i < 3; i++) {\n    printf("%d", i);\n}',
        )

    def test_code_detection_marks_program_trace(self):
        code = "int total = 0;\nfor (i = 0; i < 3; i++) {\n    total += i;\n}"
        self.assertEqual(classify_content(code), "CODE")
        self.assertEqual(detected_code_line_indices(code), [0, 1, 2, 3])

    def test_formula_detection_does_not_rewrite_equation(self):
        formula = "If x = 4, calculate 2x + 3."
        self.assertIn(0, detected_math_line_indices(formula))
        self.assertEqual(classify_content(formula), "MATHEMATICAL")

    def test_option_parser_preserves_multiline_code_and_indentation(self):
        source = (
            "What is the output of this code?\n"
            "(A) int x = 1;\n"
            "    if (x < 2) {\n"
            "        x++;\n"
            "    }\n"
            "(B) int x = 2;\n"
            "    x--;\n"
        )
        prompt, options = parse_prompt_options(source, "41", "en", "TRE 1.0")
        self.assertEqual(prompt, "What is the output of this code?")
        self.assertEqual(len(options), 2)
        self.assertEqual(
            options[0]["text"],
            "int x = 1;\n    if (x < 2) {\n        x++;\n    }",
        )
        self.assertEqual(options[0]["content_kind"], "CODE")
        self.assertEqual(options[1]["text"], "int x = 2;\n    x--;")
        self.assertEqual(options[1]["content_kind"], "CODE")

    def test_option_parser_keeps_option_labels_separate_from_code(self):
        prompt, options = parse_prompt_options(
            "Output?\n(A) x = 1 + 2;\n(B) x = 3;",
            "41", "en", "TRE 1.0",
        )
        self.assertEqual(prompt, "Output?")
        self.assertEqual([o["key"] for o in options], ["A", "B"])
        self.assertEqual([o["text"] for o in options], ["x = 1 + 2;", "x = 3;"])


if __name__ == "__main__":
    unittest.main()
