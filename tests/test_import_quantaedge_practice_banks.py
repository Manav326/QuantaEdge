from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "import_quantaedge_practice_banks.py"
SPEC = importlib.util.spec_from_file_location("import_quantaedge_practice_banks", SCRIPT)
assert SPEC and SPEC.loader
importer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(importer)


def valid_question(question_id: str = "fractions-micro-01-q1") -> dict:
    return {
        "id": question_id,
        "lesson_code": "micro-01",
        "question_type": "MCQ",
        "prompt": "1 over 4 is equivalent to which fraction?",
        "difficulty": "MEDIUM",
        "sort_order": 1,
        "explanation": "Equivalent fractions have the same value.",
        "answer_payload": {"kind": "OPTION", "value": "A"},
        "options": [
            {"key": "A", "label": "2 over 8", "correct": True},
            {"key": "B", "label": "1 over 8", "correct": False},
        ],
        "marks": 1,
        "exam_format": "Topic practice",
        "source_kind": "AUTHOR_CREATED",
        "source_title": "QuantaEdge original practice draft — human review required",
        "board": "Bihar Board",
        "topic": "Fractions",
        "subtopic": "Equivalent fractions",
        "skill": "Conceptual reasoning",
        "tags": ["original", "class-6", "hindi", "maths", "editorial-review-required"],
    }


def valid_catalog() -> dict:
    return {
        "schema": importer.CATALOG_SCHEMA,
        "title": "Practice test",
        "editorial_state": "PENDING_HUMAN_REVIEW",
        "publish_status": "DRAFT",
        "student_delivery_allowed": False,
        "chapter_banks": [
            {
                "schema": "quantaedge.practice-question-bank-draft.v1",
                "class_code": "6",
                "subject_code": "maths",
                "language": "hi",
                "chapter": {"code": "fractions", "display_name": "भिन्न", "sort_order": 7},
                "editorial_state": "PENDING_HUMAN_REVIEW",
                "publish_status": "DRAFT",
                "student_delivery_allowed": False,
                "question_count": 1,
                "questions": [valid_question()],
            }
        ],
    }


class PracticeBankImportTests(unittest.TestCase):
    def load_from_object(self, catalog: dict) -> dict:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "catalog.json"
            source.write_text(json.dumps(catalog, ensure_ascii=False), encoding="utf-8")
            return importer.load_catalog(source)

    def test_accepts_draft_catalog_and_validates_question_keys(self):
        catalog = self.load_from_object(valid_catalog())
        self.assertEqual(catalog["schema"], importer.CATALOG_SCHEMA)
        self.assertEqual(len(catalog["chapter_banks"][0]["questions"]), 1)

    def test_rejects_bank_that_claims_to_be_published(self):
        catalog = valid_catalog()
        catalog["chapter_banks"][0]["publish_status"] = "PUBLISHED"
        with self.assertRaisesRegex(ValueError, "each chapter bank must remain DRAFT"):
            self.load_from_object(catalog)

    def test_rejects_answer_key_mismatch_before_any_api_write(self):
        catalog = valid_catalog()
        catalog["chapter_banks"][0]["questions"][0]["answer_payload"]["value"] = "B"
        with self.assertRaisesRegex(ValueError, "answer payload must match"):
            self.load_from_object(catalog)

    def test_namespaced_lesson_code_maps_within_exact_chapter(self):
        bank = valid_catalog()["chapter_banks"][0]
        rows = [{
            "class_code": "6",
            "subject_code": "maths",
            "chapter_code": "fractions",
            "chapter_active": True,
            "chapter_status": "PUBLISHED",
            "lesson_id": 42,
            "lesson_code": "fractions-micro-01",
            "lesson_active": True,
            "lesson_status": "PUBLISHED",
        }]
        self.assertEqual(importer.map_question_lessons(bank, rows), {"micro-01": 42})

    def test_rejects_ambiguous_lesson_mapping(self):
        bank = valid_catalog()["chapter_banks"][0]
        rows = [
            {"class_code": "6", "subject_code": "maths", "chapter_code": "fractions",
             "chapter_active": True, "chapter_status": "PUBLISHED", "lesson_id": 42,
             "lesson_code": "fractions-micro-01", "lesson_active": True, "lesson_status": "PUBLISHED"},
            {"class_code": "6", "subject_code": "maths", "chapter_code": "fractions",
             "chapter_active": True, "chapter_status": "PUBLISHED", "lesson_id": 43,
             "lesson_code": "other-fractions-micro-01", "lesson_active": True, "lesson_status": "PUBLISHED"},
        ]
        with self.assertRaisesRegex(RuntimeError, "found 2 matching active lesson rows"):
            importer.map_question_lessons(bank, rows)

    def test_payload_keeps_answer_and_stable_identity_but_stays_draft(self):
        source = valid_question()
        payload = importer.question_payload(source, 61, "6", "maths", "hi")
        self.assertEqual(payload["answer_payload"], source["answer_payload"])
        self.assertEqual(payload["options"][0]["correct"], True)
        self.assertIn("qe-practice-id:fractions-micro-01-q1", payload["tags"])
        self.assertEqual(payload["review_status"], "DRAFT")
        self.assertEqual(payload["sort_order"], 61)
        self.assertEqual(payload["source_kind"], "AUTHOR_CREATED")


if __name__ == "__main__":
    unittest.main()
