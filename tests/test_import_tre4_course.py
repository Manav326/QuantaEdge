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


class FakeCursor:
    def __init__(self, rows):
        self.rows = list(rows)
        self.statements = []
        self.rowcount = 1

    def execute(self, sql, params=None):
        self.statements.append((sql, params))

    def fetchone(self):
        return self.rows.pop(0) if self.rows else None

    def fetchall(self):
        row = self.rows.pop(0) if self.rows else []
        if row is None:
            return []
        if isinstance(row, list):
            return row
        return [row]


class Tre4ImporterTests(unittest.TestCase):
    def test_course_identity_is_created_before_tre_content_import(self):
        cursor = FakeCursor([
            ("TRE-4 · Computer Science", "ASSESSMENT", None, None, None, None),
            (0,),
            (0,),
            None,
            (901,),
        ])
        identity_id = import_tre4_course.ensure_course_identity(cursor, 42, 777)
        self.assertEqual(identity_id, 901)
        sql = "\n".join(statement for statement, _ in cursor.statements)
        self.assertIn("INSERT INTO institution_course_identity", sql)
        self.assertIn("course_type_code='ASSESSMENT'", sql)
        self.assertIn("course_identity_id=%s", sql)

    def test_course_is_created_without_a_curriculum_class(self):
        cursor = FakeCursor([[], (777, "INS-QE-TRE4")])
        course_id, public_id, created = import_tre4_course.get_or_create_course(cursor, 42)

        self.assertEqual(course_id, 777)
        self.assertEqual(public_id, "INS-QE-TRE4")
        self.assertTrue(created)
        sql = "\n".join(statement for statement, _ in cursor.statements)
        self.assertIn("class_id,code,display_name", sql)
        self.assertIn("VALUES (NULL,%s,%s,'BPSC_TRE'", sql)
        self.assertIn("'ASSESSMENT'", sql)
        self.assertNotIn("curriculum_class", sql)

    def test_class_bound_tre4_course_is_not_rewritten_in_place(self):
        cursor = FakeCursor([[(777, "TRE-4 · Computer Science", "ACTIVE", "INS-QE-TRE4", 123, "ASSESSMENT")]])
        with self.assertRaises(import_tre4_course.ImportErrorSafe):
            import_tre4_course.get_or_create_course(cursor, 42)
        self.assertEqual(len(cursor.statements), 1)

    def test_new_test_pack_links_one_assessment_without_k12_chapter(self):
        paper = {
            "cycle": "TRE 1.0",
            "package_id": "tre4-bpsc-tre-1-0-computer-science",
            "package_version": "1.0.0",
            "package_sha256": "a" * 64,
        }
        cursor = FakeCursor([[], ("DRAFT",), None, (501,)])
        pack_id, created = import_tre4_course.ensure_test_pack(cursor, 42, 777, 901, paper)

        self.assertEqual(pack_id, 501)
        self.assertTrue(created)
        sql = "\n".join(statement for statement, _ in cursor.statements)
        self.assertIn("INSERT INTO course_assessment_pack(", sql)
        self.assertIn("INSERT INTO course_assessment_pack_item", sql)
        self.assertIn("VALUES (%s,%s,1,'PREVIOUS_YEAR_PAPER')", sql)

    def test_matching_test_pack_is_reused_idempotently(self):
        paper = {
            "cycle": "TRE 1.0",
            "package_id": "tre4-bpsc-tre-1-0-computer-science",
            "package_version": "1.0.0",
            "package_sha256": "a" * 64,
        }
        title = "BPSC TRE 1.0 — Computer Science Previous-Year Paper"
        cursor = FakeCursor([
            [(501, 42, title, "DRAFT", "a" * 64)],
            [(901, 1, "PREVIOUS_YEAR_PAPER")],
        ])
        pack_id, created = import_tre4_course.ensure_test_pack(cursor, 42, 777, 901, paper)

        self.assertEqual(pack_id, 501)
        self.assertFalse(created)
        self.assertEqual(len(cursor.statements), 2)

    def test_test_inserts_are_course_level_and_mark_portable_type(self):
        paper = {
            "cycle": "TRE 1.0",
            "package_id": "tre4-bpsc-tre-1-0-computer-science",
            "package_version": "1.0.0",
            "package_sha256": "a" * 64,
            "title": "BPSC TRE 1.0 — Computer Science Previous-Year Practice Test",
            "duration_minutes": 120,
            "negative_marking_fraction": 0.25,
            "source_url": "https://example.test/tre1.pdf",
            "question_snapshots": [],
        }
        cursor = FakeCursor([[], (901,)])
        assessment_id, created = import_tre4_course.insert_assessment(cursor, 777, paper)

        self.assertEqual(assessment_id, 901)
        self.assertTrue(created)
        insert_sql = next(sql for sql, _ in cursor.statements if "INSERT INTO assessment(" in sql)
        self.assertIn("VALUES (%s,%s,%s,NULL,NULL", insert_sql)
        self.assertIn("portable_assessment_type", insert_sql)
        self.assertIn("'PREVIOUS_YEAR_PAPER')", insert_sql)

    def test_existing_source_owned_tre_identity_is_reused_without_mutation(self):
        cursor = FakeCursor([
            ("TRE-4 · Computer Science", "ASSESSMENT", 901, 42, "I42_TRE4", "ASSESSMENT"),
        ])
        identity_id = import_tre4_course.ensure_course_identity(cursor, 42, 777)
        self.assertEqual(identity_id, 901)
        self.assertEqual(len(cursor.statements), 1)

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
