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
    def test_source_institution_uses_stable_tenant_code_not_editable_display_name(self):
        cursor = FakeCursor([[(42, "ACTIVE", "QuantaEdge Bihar")]])

        institution_id = import_tre4_course.resolve_quantaedge_source_institution(cursor)

        self.assertEqual(institution_id, 42)
        sql = "\n".join(statement for statement, _ in cursor.statements)
        self.assertIn("tenant_code='QUANTA-EDGE'", sql)
        self.assertNotIn("display_name='QuantaEdge'", sql)

    def test_source_institution_missing_has_actionable_error_and_is_not_created(self):
        cursor = FakeCursor([[]])

        with self.assertRaisesRegex(
            import_tre4_course.ImportErrorSafe, "No institution_tenant row exists.*V125"
        ):
            import_tre4_course.resolve_quantaedge_source_institution(cursor)
        self.assertEqual(len(cursor.statements), 1)
        self.assertNotIn("INSERT", cursor.statements[0][0].upper())

    def test_inactive_source_institution_must_be_reactivated_through_hrms(self):
        cursor = FakeCursor([[(42, "SUSPENDED", "QuantaEdge")]])

        with self.assertRaisesRegex(
            import_tre4_course.ImportErrorSafe, "SUSPENDED.*authorized HRMS/platform institution"
        ):
            import_tre4_course.resolve_quantaedge_source_institution(cursor)
        self.assertEqual(len(cursor.statements), 1)

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

    def test_matching_test_pack_is_reused_when_test_publication_state_differs(self):
        paper = {
            "cycle": "TRE 1.0",
            "package_id": "tre4-bpsc-tre-1-0-computer-science",
            "package_version": "1.0.0",
            "package_sha256": "a" * 64,
            "source_url": "https://example.test/tre1.pdf",
        }
        title = "BPSC TRE 1.0 — Computer Science Previous-Year Paper"
        cursor = FakeCursor([
            [(501, 42, title, "DRAFT", "a" * 64)],
            [(901, 1, "PREVIOUS_YEAR_PAPER")],
            ("PUBLISHED", "tre4-bpsc-tre-1-0-computer-science", "1.0.0", "a" * 64,
             "PREVIOUS_YEAR_PAPER", "PREVIOUS_YEAR_PAPER", None, None, "TRE 1.0",
             "https://example.test/tre1.pdf"),
        ])
        pack_id, created = import_tre4_course.ensure_test_pack(cursor, 42, 777, 901, paper)

        self.assertEqual(pack_id, 501)
        self.assertFalse(created)
        self.assertEqual(len(cursor.statements), 3)

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

    def test_class_bound_assessment_is_not_reused_by_classless_import(self):
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
        cursor = FakeCursor([
            [(901, paper["title"], "DRAFT", "a" * 64, 0, 22, 33,
              "PREVIOUS_YEAR_PAPER", "PREVIOUS_YEAR_PAPER", "TRE 1.0", paper["source_url"])]
        ])

        with self.assertRaises(import_tre4_course.ImportErrorSafe):
            import_tre4_course.insert_assessment(cursor, 777, paper)
        self.assertEqual(len(cursor.statements), 1)

    def test_identity_linked_to_another_course_version_is_not_reused(self):
        cursor = FakeCursor([
            ("TRE-4 · Computer Science", "ASSESSMENT", None, None, None, None),
            (0,),
            (0,),
            (901, "TRE-4 · Computer Science", "K12", "ACTIVE"),
            [(7770, "LEGACY_TRE4", "BPSC-TRE-4", 1)],
        ])
        with self.assertRaisesRegex(
            import_tre4_course.ImportErrorSafe, "already linked to another course/version"
        ):
            import_tre4_course.ensure_course_identity(cursor, 42, 777)
        self.assertEqual(len(cursor.statements), 5)

    def test_existing_source_owned_tre_identity_is_reused_without_mutation(self):
        cursor = FakeCursor([
            ("TRE-4 · Computer Science", "ASSESSMENT", 901, 42, "I42_TRE4", "ASSESSMENT"),
        ])
        identity_id = import_tre4_course.ensure_course_identity(cursor, 42, 777)
        self.assertEqual(identity_id, 901)
        self.assertEqual(len(cursor.statements), 1)

    def test_generic_assessment_readiness_is_not_tre_specific(self):
        migration = SCRIPT.parents[1] / "services" / "api" / "src" / "main" / "resources" / "db" / "migration" / "V199__assessment_only_readiness_uses_test_packs.sql"
        sql = migration.read_text(encoding="utf-8")
        generic_start = sql.index("CREATE OR REPLACE FUNCTION assessment_only_course_ready")
        tre_start = sql.index("-- Import-specific contract:")
        generic = sql[generic_start:tre_start]
        tre_specific = sql[tre_start:]

        self.assertIn("course_assessment_pack_item", generic)
        self.assertNotIn("TRE 3.0", generic)
        self.assertIn("CREATE OR REPLACE FUNCTION tre4_source_package_ready", tre_specific)
        self.assertIn("'TRE 1.0'::varchar", tre_specific)
        self.assertIn("'TRE 2.0'::varchar", tre_specific)
        self.assertIn("'TRE 3.0'::varchar", tre_specific)
        self.assertIn("150", tre_specific)

    def test_marketplace_publish_readiness_is_generic_for_assessment_courses(self):
        migration = SCRIPT.parents[1] / "services" / "api" / "src" / "main" / "resources" / "db" / "migration" / "V200__generic_assessment_publish_readiness.sql"
        sql = migration.read_text(encoding="utf-8")
        function_start = sql.index("CREATE OR REPLACE FUNCTION assessment_only_course_publish_ready")
        function_end = sql.index("INSERT INTO app_metadata", function_start)
        publish_ready = sql[function_start:function_end]

        self.assertIn("assessment_only_course_ready(p_program_id)", publish_ready)
        self.assertIn("pack.status='PUBLISHED'", publish_ready)
        self.assertIn("pack.status<>'PUBLISHED'", publish_ready)
        self.assertIn("a.status='PUBLISHED'", publish_ready)
        self.assertIn("a.status<>'PUBLISHED'", publish_ready)
        self.assertNotIn("tre4-bpsc", publish_ready)
        self.assertNotIn("TRE 1.0", publish_ready)
        self.assertNotIn("TRE 2.0", publish_ready)
        self.assertNotIn("TRE 3.0", publish_ready)

    def test_verified_release_fingerprint_gate_avoids_rehashing_current_releases(self):
        migration = SCRIPT.parents[1] / "services" / "api" / "src" / "main" / "resources" / "db" / "migration" / "V201__avoid_repeat_fingerprints_for_verified_releases.sql"
        sql = migration.read_text(encoding="utf-8")
        self.assertIn("course_release_fingerprint_current", sql)
        self.assertIn("flyway_schema_history", sql)
        self.assertIn("version='196' AND success=true", sql)
        self.assertIn("p_release_updated_at >= v_v196_cutoff - interval '1 minute'", sql)
        self.assertIn("RETURN false;", sql)
        self.assertIn("V180 makes the corresponding content graph immutable", sql)
        helper = sql[sql.index("CREATE OR REPLACE FUNCTION course_release_fingerprint_current"):sql.index("COMMENT ON FUNCTION course_release_fingerprint_current")]
        self.assertNotIn("course_content_fingerprint(", helper,
                         "Marketplace reads must not rehash pre-V196 graphs.")

    def test_course_fingerprint_aggregates_compact_row_hashes(self):
        migration = SCRIPT.parents[1] / "services" / "api" / "src" / "main" / "resources" / "db" / "migration" / "V203__compact_course_release_fingerprint.sql"
        sql = migration.read_text(encoding="utf-8")
        function_start = sql.index("CREATE OR REPLACE FUNCTION course_content_fingerprint")
        function_end = sql.index("COMMENT ON FUNCTION course_content_fingerprint", function_start)
        function = sql[function_start:function_end]

        self.assertIn("content_rows AS NOT MATERIALIZED", function)
        self.assertIn("md5(row_json)", function)
        self.assertIn("ORDER BY entity_type,entity_id", function)
        for entity in ("'lesson-block'", "'question-option'", "'assessment-pack-item'", "'source-book-page'"):
            self.assertIn(entity, function)
        self.assertNotIn("entity_type||':'||entity_id||':'||row_json", function,
                         "Do not aggregate full JSON course rows into one large string.")

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


    def test_importer_locks_only_the_latest_editable_draft_release(self):
        draft = (77, 2, "DRAFT", "a" * 64)
        cursor = FakeCursor([draft])

        result = import_tre4_course.lock_latest_draft_release(cursor, 42, 777)

        self.assertEqual(result, draft)
        self.assertEqual(len(cursor.statements), 1)
        self.assertIn("FOR UPDATE", cursor.statements[0][0])
        self.assertIn("ORDER BY release_version DESC,id DESC", cursor.statements[0][0])

    def test_importer_refuses_to_modify_any_non_draft_release(self):
        for state in ("IN_QUALITY_REVIEW", "QUALITY_APPROVED", "SHAREABLE", "REJECTED", "WITHDRAWN", "LEGACY_MIGRATED"):
            with self.subTest(state=state):
                cursor = FakeCursor([(77, 2, state, "a" * 64)])
                with self.assertRaisesRegex(import_tre4_course.ImportErrorSafe, state):
                    import_tre4_course.lock_latest_draft_release(cursor, 42, 777)
                self.assertEqual(len(cursor.statements), 1)

    def test_importer_never_shortcuts_course_release_lifecycle(self):
        importer = SCRIPT.read_text(encoding="utf-8")

        self.assertNotIn("--submit-for-review", importer)
        self.assertNotIn("SET release_state='IN_QUALITY_REVIEW'", importer)
        self.assertNotIn('initial_state = "IN_QUALITY_REVIEW"', importer)
        self.assertIn("VALUES (%s,%s,1,'DRAFT',%s,NULL,false,NULL,now(),now())", importer)
        self.assertIn('"submittedForReview": False', importer)
        self.assertIn('"assessmentStatus": assessment_state', importer)
        self.assertIn('"testPackStatus": pack_state', importer)
        self.assertIn("questionReviewStatusCounts", importer)
        self.assertIn("answerReviewStatusCounts", importer)



if __name__ == "__main__":
    unittest.main()
