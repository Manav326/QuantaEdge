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
    _question_is_allowed,
    _split_block_questions,
)


class TreSourceCodePreservationTests(unittest.TestCase):
    def test_tre3_source_has_150_questions_with_all_30_language_items(self):
        self.assertEqual(PAPERS["TRE 3.0"]["total_questions"], 150)
        language_keys = expected_language_keys("TRE 3.0")
        self.assertEqual(len(language_keys), 30)
        self.assertEqual(
            language_keys,
            {f"E-{i}" for i in range(1, 9)} | {f"H-{i}" for i in range(9, 31)},
        )
        self.assertIn("E-1", language_keys)
        self.assertIn("E-6", language_keys)
        self.assertEqual(40 + 80 + len(language_keys), 150)

    def test_tre3_unprefixed_hindi_answer_fragments_are_not_questions(self):
        self.assertFalse(_question_is_allowed("TRE 3.0", "11", 11, ""))
        self.assertFalse(_question_is_allowed("TRE 3.0", "12", 12, ""))
        self.assertFalse(_question_is_allowed("TRE 3.0", "13", 13, ""))
        # TRE 2.0 uses plain numeric labels for its language section.
        self.assertTrue(_question_is_allowed("TRE 2.0", "11", 11, ""))

    def test_split_questions_after_a_shared_passage(self):
        source = (
            "Kerala is known for its variety of scenery and food. "
            "Visitors can enjoy forests, beaches and hills.\\n"
            "E-1. Why is Kerala just the place for you if you love variety ?\\n"
            "(A) There is something here to please everyone\\n"
            "E-2. Why should you take a trip to the spice gardens in the hills ?\\n"
            "(A) To see spices growing in the hills"
        )
        preamble, questions = _split_block_questions(source, "TRE 3.0")
        self.assertTrue(preamble.startswith("Kerala is known"))
        self.assertEqual([q[0] for q in questions], ["E-1", "E-2"])
        self.assertIn("Why is Kerala", questions[0][3])
        self.assertIn("Why should you", questions[1][3])

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
