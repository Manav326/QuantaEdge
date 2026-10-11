from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "translate_quantaedge_content.py"
SPEC = importlib.util.spec_from_file_location("translate_quantaedge_content", SCRIPT)
assert SPEC and SPEC.loader
pipeline = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(pipeline)


class TranslationShapeTests(unittest.TestCase):
    def test_accepts_contextual_text_translation(self):
        source = {
            "prompt": "भिन्न का अर्थ क्या है?",
            "options": [{"key": "A", "label": "एक भाग"}, {"key": "B", "label": "दो भाग"}],
            "correctOption": "A",
        }
        translated = {
            "prompt": "What does a fraction mean?",
            "options": [{"key": "A", "label": "One part"}, {"key": "B", "label": "Two parts"}],
            "correctOption": "A",
        }
        pipeline.check_shape(source, translated)

    def test_rejects_changed_correct_option(self):
        source = {"prompt": "Choose the correct answer", "correctOption": "B"}
        translated = {"prompt": "सही उत्तर चुनें", "correctOption": "A"}
        with self.assertRaisesRegex(ValueError, "Protected structural value changed"):
            pipeline.check_shape(source, translated)

    def test_rejects_changed_numeric_value(self):
        source = {"body": "1 over 4 is less than 1 over 2"}
        translated = {"body": "1 over 4 is less than 1 over 3"}
        with self.assertRaisesRegex(ValueError, "Numeric values changed"):
            pipeline.check_shape(source, translated)

    def test_rejects_changed_json_shape(self):
        with self.assertRaisesRegex(ValueError, "JSON keys changed"):
            pipeline.check_shape({"title": "Title", "summary": "Summary"}, {"title": "शीर्षक"})

    def test_rejects_changed_html_structure(self):
        source = {"html": "<p>Hello <strong>world</strong></p>"}
        translated = {"html": "<p>नमस्ते <em>दुनिया</em></p>"}
        with self.assertRaisesRegex(ValueError, "HTML tags changed"):
            pipeline.check_shape(source, translated)

    def test_sets_language_marker_without_changing_other_keys(self):
        source = {"language": "hi", "heading": "विषय", "meta": {"locale": "hi", "code": "chapter-1"}}
        result = pipeline.set_language_markers(source, "en")
        self.assertEqual(result["language"], "en")
        self.assertEqual(result["meta"]["locale"], "en")
        self.assertEqual(result["meta"]["code"], "chapter-1")
        self.assertEqual(result["heading"], "विषय")


class TranslationSqlTests(unittest.TestCase):
    def test_source_inventory_covers_student_and_media_content(self):
        sql = pipeline.make_source_sql(["6", "7", "8"], ["maths", "science"], False)
        for entity in (
            "'curriculum_chapter'", "'learning_objective'", "'lesson_block'", "'question'",
            "'assessment_question'", "'learning_class_session'", "'recorded_class'",
        ):
            self.assertIn(entity, sql)
        self.assertIn("md5(jsonb_build_object", sql)
        self.assertIn("translation_status", sql)
        self.assertIn("AND l.active = true", sql)

    def test_archived_mode_does_not_add_active_row_filters(self):
        sql = pipeline.make_source_sql(["6"], ["maths"], True)
        self.assertNotIn("AND l.active = true AND l.status <> 'ARCHIVED'", sql)
        self.assertNotIn("AND ch.active=true AND ch.content_status <> 'ARCHIVED' AND l.active=true", sql)

    def test_insert_is_draft_and_does_not_publish(self):
        item = {
            "entity_type": "lesson",
            "entity_id": 10,
            "source_hash": "0123456789abcdef0123456789abcdef",
        }
        statement = pipeline.insert_statement(item, "en", {"title": "Fractions", "summary": "A lesson"}, 1, "test-model")
        self.assertIn("'DRAFT'", statement)
        self.assertIn("'AI'", statement)
        self.assertIn("ON CONFLICT", statement)
        self.assertNotIn("'PUBLISHED'", statement)


if __name__ == "__main__":
    unittest.main()
