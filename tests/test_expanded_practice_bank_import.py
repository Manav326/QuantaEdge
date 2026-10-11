from __future__ import annotations

import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from import_expanded_practice_bank import (
    normalise_chapter_test_questions,
    prepare_patch,
)


class ExpandedPracticeBankImportTests(unittest.TestCase):
    def test_chapter_test_question_maps_to_exact_matching_microtopic(self) -> None:
        bundle = {
            "lessons": [{"code": "micro-01"}, {"code": "micro-07"}],
            "online_question_bank": [
                {"id": "q1", "lesson_code": "micro-01", "subtopic": "संख्या पैटर्न", "tags": ["microtopic-practice"]},
                {"id": "q2", "lesson_code": "micro-07", "subtopic": "वर्ग संख्याएँ", "tags": ["microtopic-practice"]},
                {"id": "end1", "lesson_code": "chapter-test", "subtopic": "वर्ग संख्याएँ", "tags": ["chapter-end"]},
            ],
        }
        result = normalise_chapter_test_questions(bundle)
        self.assertEqual(result["online_question_bank"][2]["lesson_code"], "micro-07")
        self.assertEqual(bundle["online_question_bank"][2]["lesson_code"], "chapter-test")

    def test_unmatched_chapter_test_question_is_rejected(self) -> None:
        bundle = {
            "lessons": [{"code": "micro-01"}],
            "online_question_bank": [
                {"id": "q1", "lesson_code": "micro-01", "subtopic": "संख्या पैटर्न", "tags": ["microtopic-practice"]},
                {"id": "end1", "lesson_code": "chapter-test", "subtopic": "अज्ञात उपविषय", "tags": ["chapter-end"]},
            ],
        }
        with self.assertRaisesRegex(ValueError, "no exact subtopic match"):
            normalise_chapter_test_questions(bundle)

    def test_merge_preserves_existing_question_and_appends_only_new_drafts(self) -> None:
        current = {
            "lesson_status": "DRAFT",
            "lesson_code": "fractions-micro-01",
            "lesson_title": "भिन्न का अर्थ",
            "lesson_summary": "भिन्न समझें",
            "estimated_minutes": 10,
            "lesson_sort_order": 1,
            "alignment_source_verified": False,
            "questions": [{
                "id": 27,
                "question_type": "MCQ",
                "prompt": "पहले से मौजूद प्रश्न?",
                "explanation": "पुराना explanation",
                "difficulty": "CORE",
                "sort_order": 1,
                "active": True,
                "marks": 1,
                "exam_format": "Practice",
                "source_kind": "AUTHOR_CREATED",
                "source_title": "पुराना draft",
                "source_ref": None,
                "source_year": None,
                "source_id": None,
                "board": "Bihar Board",
                "topic": "भिन्न",
                "subtopic": "भिन्न का अर्थ",
                "skill": None,
                "tags": '["legacy"]',
                "answer_payload": '{"kind":"OPTION","value":"A"}',
                "options": '[{"key":"A","label":"सही","correct":true,"sort_order":1},{"key":"B","label":"गलत","correct":false,"sort_order":2}]',
            }],
        }
        incoming = [{
            "id": "fractions-micro-01-topic-2-mcq",
            "lesson_code": "fractions-micro-01",
            "question_type": "MCQ",
            "prompt": "नई अभ्यास समस्या?",
            "difficulty": "MEDIUM",
            "explanation": "सही उत्तर का कारण।",
            "options": [
                {"key": "A", "label": "एक", "correct": True},
                {"key": "B", "label": "दो", "correct": False},
            ],
            "answer_payload": {"kind": "OPTION", "value": "A"},
            "topic": "भिन्न",
            "subtopic": "भिन्न का अर्थ",
            "tags": ["microtopic-practice"],
            "source_kind": "AUTHOR_CREATED",
            "source_title": "QuantaEdge original practice draft",
            "board": "Bihar Board",
            "marks": 1,
            "exam_format": "Topic practice",
        }]
        additions, added, skipped = prepare_patch(current, incoming)
        self.assertEqual((added, skipped), (1, 0))
        self.assertEqual(len(additions), 1)
        new_item = additions[0]
        self.assertNotIn("id", new_item)
        self.assertIn("qe-practice-id:fractions-micro-01-topic-2-mcq", new_item["tags"])
        self.assertEqual(new_item["review_status"], "DRAFT")
        self.assertEqual(new_item["sort_order"], 2)

    def test_exact_prompt_duplicate_is_skipped(self) -> None:
        current = {
            "lesson_status": "DRAFT",
            "lesson_code": "x",
            "lesson_title": "Topic",
            "lesson_summary": "",
            "estimated_minutes": 10,
            "lesson_sort_order": 1,
            "alignment_source_verified": False,
            "questions": [{
                "id": 1, "question_type": "INPUT", "prompt": "  उत्तर लिखिए  ",
                "explanation": "", "difficulty": "CORE", "sort_order": 1, "active": True,
                "marks": 1, "exam_format": "Practice", "source_kind": "AUTHOR_CREATED",
                "source_title": None, "source_ref": None, "source_year": None, "source_id": None,
                "board": None, "topic": None, "subtopic": None, "skill": None,
                "tags": "[]", "answer_payload": '{"kind":"TEXT","value":"हाँ"}', "options": "[]",
            }],
        }
        incoming = [{
            "id": "q-new", "lesson_code": "x", "question_type": "INPUT",
            "prompt": "उत्तर लिखिए", "difficulty": "EASY", "explanation": "",
            "options": [], "answer_payload": {"kind": "TEXT", "value": "हाँ"},
        }]
        patch, added, skipped = prepare_patch(current, incoming)
        self.assertEqual((added, skipped), (0, 1))
        self.assertEqual([q["id"] for q in patch["questions"]], [1])

    def test_published_lesson_accepts_append_only_drafts(self) -> None:
        current = {
            "lesson_status": "PUBLISHED",
            "lesson_code": "live-topic",
            "lesson_title": "Live",
            "lesson_summary": "",
            "estimated_minutes": 10,
            "lesson_sort_order": 1,
            "alignment_source_verified": False,
            "questions": [],
        }
        additions, added, skipped = prepare_patch(current, [{
            "id": "live-add",
            "prompt": "नया प्रश्न",
            "question_type": "INPUT",
            "difficulty": "EASY",
            "lesson_code": "live-topic",
            "answer_payload": {"kind": "TEXT", "value": "उत्तर"},
            "options": [],
        }])
        self.assertEqual((added, skipped), (1, 0))
        self.assertEqual(len(additions), 1)
        self.assertEqual(additions[0]["review_status"], "DRAFT")


if __name__ == "__main__":
    unittest.main()
