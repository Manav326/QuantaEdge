from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "translate_quantaedge_content.py"
SPEC = importlib.util.spec_from_file_location("translate_quantaedge_content", SCRIPT)
assert SPEC and SPEC.loader
pipeline = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(pipeline)


class LocalEnvironmentTests(unittest.TestCase):
    def test_reads_local_env_file_without_printing_or_exporting_values(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            env_file = Path(temp_dir) / ".env"
            env_file.write_text(
                "# local API settings\n"
                'OPENAI_API_KEY="test-secret"\n'
                "export OPENAI_API_BASE_URL=https://example.invalid/v1\n"
                "APP_AI_TUTOR_MODEL=translator-model\n",
                encoding="utf-8",
            )
            values = pipeline.read_env_file(env_file)
        self.assertEqual(values["OPENAI_API_KEY"], "test-secret")
        self.assertEqual(values["OPENAI_API_BASE_URL"], "https://example.invalid/v1")
        self.assertEqual(values["APP_AI_TUTOR_MODEL"], "translator-model")


class TranslationPromptTests(unittest.TestCase):
    def test_hindi_prompt_requires_contextual_devanagari_school_translation(self):
        prompt = pipeline.build_system_prompt("hi")
        self.assertIn("pedagogical localization", prompt)
        self.assertIn("Hindi in Devanagari script", prompt)
        self.assertIn("not word-for-word", prompt)
        self.assertIn("Contextual terminology guide", prompt)
        self.assertIn("preserve that representation", prompt)

    def test_english_prompt_requires_natural_age_appropriate_textbook_english(self):
        prompt = pipeline.build_system_prompt("en")
        self.assertIn("idiomatic, age-appropriate Indian school textbook English", prompt)
        self.assertIn("Translate the intended concept", prompt)
        self.assertIn("not a literal substitution dictionary", prompt)

    def test_small_translation_pilot_balances_both_languages_and_content_types(self):
        sources = [
            {"entity_type": "lesson", "entity_id": 1},
            {"entity_type": "lesson_block", "entity_id": 2},
            {"entity_type": "question", "entity_id": 3},
            {"entity_type": "lesson", "entity_id": 1},
            {"entity_type": "lesson_block", "entity_id": 2},
            {"entity_type": "question", "entity_id": 3},
        ]
        todo = [
            (sources[0], "hi", 1),
            (sources[0], "en", 1),
            (sources[1], "hi", 1),
            (sources[1], "en", 1),
            (sources[2], "hi", 1),
            (sources[2], "en", 1),
        ]
        selected = pipeline.cap_tasks_balanced_by_language_and_type(todo, 4)
        self.assertEqual(len(selected), 4)
        self.assertEqual({language for _row, language, _version in selected}, {"hi", "en"})
        selected_types = {row["entity_type"] for row, _language, _version in selected}
        self.assertGreaterEqual(len(selected_types), 2)


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

    def test_preserves_fraction_written_as_words(self):
        source = {"body": "Shade 1 over 4 of the shape."}
        translated = {"body": "आकृति के 1 over 4 भाग में रंग भरें।"}
        pipeline.check_shape(source, translated)

    def test_rejects_fraction_notation_change(self):
        source = {"body": "Shade 1 over 4 of the shape."}
        translated = {"body": "Shade 1/4 of the shape."}
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


class TranslationCoverageTests(unittest.TestCase):
    def test_coverage_counts_only_current_source_published_records_as_delivered(self):
        sources = [
            {
                "entity_type": "curriculum_class", "entity_id": 6, "source_hash": "class6",
                "context": {"class_code": "6", "class_active": True},
                "existing": [
                    {"language": "hi", "source_hash": "class6", "version_no": 1, "translation_status": "PUBLISHED"},
                    {"language": "en", "source_hash": "class6", "version_no": 2, "translation_status": "DRAFT"},
                ],
            },
            {
                "entity_type": "curriculum_subject", "entity_id": 10, "source_hash": "maths6",
                "context": {"class_code": "6", "subject_code": "maths", "class_active": True, "subject_active": True},
                "existing": [
                    {"language": lang, "source_hash": "maths6", "version_no": 1, "translation_status": "PUBLISHED"}
                    for lang in ("hi", "en")
                ],
            },
            {
                "entity_type": "lesson", "entity_id": 100, "source_hash": "lesson100",
                "context": {"class_code": "6", "subject_code": "maths", "class_active": True, "subject_active": True, "chapter_active": True, "chapter_status": "PUBLISHED", "lesson_active": True, "lesson_status": "PUBLISHED"},
                "existing": [
                    {"language": "hi", "source_hash": "lesson100", "version_no": 1, "translation_status": "PUBLISHED"},
                    {"language": "en", "source_hash": "lesson100", "version_no": 1, "translation_status": "APPROVED"},
                ],
            },
            {
                "entity_type": "lesson_block", "entity_id": 200, "source_hash": "block200",
                "context": {"class_code": "6", "subject_code": "maths", "class_active": True, "subject_active": True, "chapter_active": True, "chapter_status": "PUBLISHED", "lesson_active": True, "lesson_status": "PUBLISHED", "block_active": True},
                "existing": [
                    {"language": lang, "source_hash": "block200", "version_no": 1, "translation_status": "PUBLISHED"}
                    for lang in ("hi", "en")
                ],
            },
            {
                "entity_type": "question", "entity_id": 300, "source_hash": "question300",
                "context": {"class_code": "6", "subject_code": "maths", "class_active": True, "subject_active": True, "chapter_active": True, "chapter_status": "PUBLISHED", "lesson_active": True, "lesson_status": "PUBLISHED", "question_active": True, "question_status": "APPROVED"},
                "existing": [
                    {"language": "hi", "source_hash": "question300", "version_no": 1, "translation_status": "PUBLISHED"},
                    {"language": "en", "source_hash": "old-stale-hash", "version_no": 1, "translation_status": "PUBLISHED"},
                ],
            },
        ]

        report = pipeline.summarize_published_translation_coverage(sources, ["hi", "en"], ["maths", "science"])
        tracks = {
            (row["class_code"], row["subject_code"], row["language"]): row
            for row in report["per_class_subject_language"]
        }
        english_maths = tracks[("6", "maths", "en")]
        self.assertFalse(english_maths["structured_content_fully_published"])
        self.assertIn("curriculum_class", english_maths["core_entity_types_incomplete"])
        self.assertIn("lesson", english_maths["core_entity_types_incomplete"])
        self.assertIn("question", english_maths["core_entity_types_incomplete"])
        self.assertEqual(english_maths["entities"]["lesson"]["approved_not_published"], 1)
        self.assertEqual(english_maths["entities"]["question"]["missing_current"], 1)
        self.assertEqual(english_maths["entities"]["question"]["published_current"], 0)
        self.assertFalse(next(
            row for row in report["bilingual_readiness_by_track"]
            if row["class_code"] == "6" and row["subject_code"] == "maths"
        )["structured_bilingual_ready"])

    def test_current_publication_remains_deliverable_while_newer_draft_is_edited(self):
        source = {
            "entity_type": "lesson", "entity_id": 101, "source_hash": "current",
            "context": {"class_code": "7", "subject_code": "science", "class_active": True, "subject_active": True, "chapter_active": True, "chapter_status": "PUBLISHED", "lesson_active": True, "lesson_status": "PUBLISHED"},
            "existing": [
                {"language": "en", "source_hash": "current", "version_no": 1, "translation_status": "PUBLISHED"},
                {"language": "en", "source_hash": "current", "version_no": 2, "translation_status": "DRAFT"},
            ],
        }
        report = pipeline.summarize_published_translation_coverage([source], ["en"], ["science"])
        track = report["per_class_subject_language"][0]
        self.assertEqual(track["entities"]["lesson"]["published_current"], 1)
        self.assertEqual(track["entities"]["lesson"]["draft"], 0)
        self.assertIn("lesson", track["entities"])
        self.assertFalse(track["structured_content_fully_published"])
        self.assertIn("lesson_block", track["required_content_types_missing"])


class PublishedTranslationScriptAuditTests(unittest.TestCase):
    def test_flags_hindi_script_leaking_into_published_english_translation(self):
        report = pipeline.audit_published_translation_scripts([{
            "class_code": "6", "subject_code": "maths", "language": "en",
            "entity_type": "lesson_block", "entity_id": 42,
            "translated_content": {"content": {"body": "यह संख्या one quarter है"}},
        }])
        self.assertEqual(report["script_review_candidate_count"], 1)
        self.assertEqual(report["review_candidates"][0]["issue"], "DEVANAGARI_TEXT_IN_ENGLISH")

    def test_flags_latin_only_long_hindi_translation_as_candidate(self):
        report = pipeline.audit_published_translation_scripts([{
            "class_code": "8", "subject_code": "science", "language": "hi",
            "entity_type": "lesson", "entity_id": 55,
            "translated_content": {"summary": "This is a very long translated lesson written in English words with enough alphabetic characters to trigger a likely language mismatch."},
        }])
        self.assertEqual(report["script_review_candidate_count"], 1)
        self.assertEqual(report["review_candidates"][0]["issue"], "LOW_DEVANAGARI_SHARE_IN_HINDI")

    def test_natural_hindi_and_english_translations_are_not_flagged(self):
        report = pipeline.audit_published_translation_scripts([
            {"class_code": "6", "subject_code": "maths", "language": "hi",
             "entity_type": "lesson_block", "entity_id": 1,
             "translated_content": {"content": {"body": "यह भिन्न दो बराबर भागों में से एक भाग को दर्शाती है।"}}},
            {"class_code": "7", "subject_code": "science", "language": "en",
             "entity_type": "lesson_block", "entity_id": 2,
             "translated_content": {"content": {"body": "A fraction represents one of two equal parts of a whole."}}},
        ])
        self.assertEqual(report["script_review_candidate_count"], 0)

    def test_script_audit_query_only_reads_current_published_learn_translations(self):
        sql = pipeline.make_published_learn_translation_audit_sql(["6"], ["maths"], ["en", "hi"])
        self.assertIn("t.translation_status='PUBLISHED'", sql)
        self.assertIn("t.source_hash::text=md5", sql)
        self.assertIn("q.review_status IN ('APPROVED','PUBLISHED')", sql)
        self.assertIn("b.active=true", sql)
        self.assertNotIn("translation_status='DRAFT'", sql)


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
        self.assertIn("jsonb_build_object('content',b.content)", sql)
        self.assertIn("AND l.active = true", sql)

    def test_archived_mode_does_not_add_active_row_filters(self):
        sql = pipeline.make_source_sql(["6"], ["maths"], True)
        self.assertNotIn("AND l.active = true AND l.status <> 'ARCHIVED'", sql)
        self.assertNotIn("AND ch.active=true AND ch.content_status <> 'ARCHIVED' AND l.active=true", sql)

    def test_translation_source_uses_structured_records_not_pdfs(self):
        sql = pipeline.make_source_sql(["6", "7", "8"], ["maths", "science"], False)
        self.assertIn("jsonb_build_object('content',b.content)", sql)
        self.assertIn("'question'", sql)
        self.assertIn("'assessment_question'", sql)
        self.assertNotIn("learning_document", sql)
        self.assertNotIn("learning_pdf_asset", sql)

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
