import importlib.util
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "import_quantaedge_offline_translations.py"
SPEC = importlib.util.spec_from_file_location("offline_translations", SCRIPT)
module = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(module)


class NumberFidelityTests(unittest.TestCase):
    def test_comma_grouping_and_spaced_math(self):
        self.assertTrue(module.numbers_preserved("2, 7, 12, 17", "2, 7, 12, 17"))

    def test_superscript_and_caret_exponents(self):
        self.assertTrue(module.numbers_preserved("5⁵ gives 3125", "5^5 gives 3125"))

    def test_fraction_decimal_and_changed_values(self):
        self.assertTrue(module.numbers_preserved("3/2 = 1.5", "3/2 = 1.5"))
        self.assertFalse(module.numbers_preserved("3/2 = 1.5", "3/2 = 1.6"))

    def test_written_out_number_can_preserve_source_value(self):
        self.assertTrue(module.numbers_preserved("3 identical items cost ₹27", "Three identical items cost ₹27"))


class PayloadTests(unittest.TestCase):
    def setUp(self):
        self.queue = [
            {"tid":"p6","field":"prompt","source":"3 समान वस्तुओं का मूल्य ₹27 है। एक वस्तु का मूल्य क्या है?",
             "context":{"class":"6","subject":"maths","chapter_code":"ratios","chapter_title":"अनुपात",
                        "question_type":"MCQ","prompt":"3 समान वस्तुओं का मूल्य ₹27 है। एक वस्तु का मूल्य क्या है?"}},
            {"tid":"e6","field":"explanation","source":"27÷3=9।",
             "context":{"class":"6","subject":"maths","chapter_code":"ratios","question_type":"MCQ",
                        "prompt":"3 समान वस्तुओं का मूल्य ₹27 है। एक वस्तु का मूल्य क्या है?"}},
            {"tid":"t6","field":"topic","source":"अनुपात","context":{"class":"6","subject":"maths","chapter_code":"ratios"}},
            {"tid":"s6","field":"subtopic","source":"इकाई दर","context":{"class":"6","subject":"maths","chapter_code":"ratios"}},
            {"tid":"sk6","field":"skill","source":"अवधारणा, अनुप्रयोग और तर्क","context":{"class":"6","subject":"maths"}},
            {"tid":"oB","field":"option_label_MCQ","source":"₹9",
             "context":{"class":"6","subject":"maths","chapter_code":"ratios","question_type":"MCQ",
                        "prompt":"3 समान वस्तुओं का मूल्य ₹27 है। एक वस्तु का मूल्य क्या है?","option_key":"B","option_index":2}},
        ]
        self.translations = {"p6":"Three identical items cost ₹27. What is the cost of one item?",
                             "e6":"27 ÷ 3 = 9.","t6":"Ratio","s6":"Unit rate",
                             "sk6":"Conceptual understanding, application, and reasoning","oB":"₹9"}
        self.row = {"class_code":"6","subject_code":"maths","chapter_code":"ratios","chapter_title":"अनुपात",
                    "question_type":"MCQ","prompt":self.queue[0]["source"],"explanation":"27÷3=9।",
                    "topic":"अनुपात","subtopic":"इकाई दर","skill":"अवधारणा, अनुप्रयोग और तर्क",
                    "options":[{"key":"A","label":"₹8"},{"key":"B","label":"₹9"}],"tags":["hindi","class-6"]}

    def test_option_keys_order_and_tags_are_preserved(self):
        payload, issues = module.question_payload(self.row, self._index(self.queue), self.translations)
        self.assertEqual([], issues)
        self.assertEqual([{"key":"A","label":"₹8"},{"key":"B","label":"₹9"}], payload["options"])
        self.assertEqual(["hindi","class-6"], payload["tags"])
        self.assertEqual("Three identical items cost ₹27. What is the cost of one item?", payload["prompt"])

    def test_missing_prompt_translation_blocks_partial_payload(self):
        queue = [item for item in self.queue if item["field"] != "prompt"]
        payload, issues = module.question_payload(self.row, self._index(queue), self.translations)
        self.assertIsNone(payload)
        self.assertTrue(any("prompt" in issue for issue in issues))

    def test_repeated_source_prefers_matching_chapter(self):
        queue = self.queue + [{"tid":"other","field":"topic","source":"अनुपात",
            "context":{"class":"8","subject":"maths","chapter_code":"different"}}]
        translations = dict(self.translations, other="Different topic")
        got = module.resolve(self._index(queue), translations, "topic", "अनुपात", self.row)
        self.assertEqual("Ratio", got)

    def test_true_false_has_contextual_safe_fallback(self):
        row = dict(self.row, question_type="TRUE_FALSE",
                   options=[{"key":"A","label":"सही"},{"key":"B","label":"गलत"}])
        payload, issues = module.question_payload(row, self._index(self.queue), self.translations)
        self.assertEqual([], issues)
        self.assertEqual([{"key":"A","label":"True"},{"key":"B","label":"False"}], payload["options"])

    @staticmethod
    def _index(queue):
        out = {}
        for item in queue:
            out.setdefault((item["field"], str(item["source"]).strip()), []).append(item)
        return out


if __name__ == "__main__":
    unittest.main()
