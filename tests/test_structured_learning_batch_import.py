from __future__ import annotations

import copy
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from import_structured_learning_batch import (
    DRAFT_SCHEMA,
    MANIFEST_SCHEMA,
    NAVODAYA_CODE,
    load_batch,
    preflight_api,
)


def make_bundle(language: str = "en") -> dict:
    title = "Fractions" if language == "en" else "भिन्न"
    explanation = "Understand fractions." if language == "en" else "भिन्नों को समझें।"
    question = "Which number is the denominator?" if language == "en" else "हर कौन-सी संख्या है?"
    return {
        "schema": DRAFT_SCHEMA,
        "bundle_status": "AI_AUTHORED_DRAFT",
        "publish_status": "DRAFT",
        "auto_publish": False,
        "editorial_state": "PENDING_HUMAN_REVIEW",
        "ready_for_admin_import": False,
        "curriculum": {
            "board": "Navodaya",
            "class_code": "6",
            "subject_code": "maths",
            "language": language,
            "program_code": NAVODAYA_CODE,
            "canonical_track_match": "MATCHED_CHAPTER_CODE",
        },
        "chapter": {"code": "fractions", "display_name": title, "sort_order": 1},
        "source_alignment": {
            "status": "NEEDS_EDITOR_VERIFICATION",
            "source_of_truth_candidate": {
                "title": "Navodaya authoring source",
                "url": "https://example.org/navodaya/source",
            },
        },
        "declaration": {"original_content": True, "rights_reviewed": False, "reviewer": None},
        "lessons": [{
            "code": "fractions-intro-" + language,
            "title": title + " introduction",
            "summary": "Draft for editor review",
            "estimated_minutes": 8,
            "sort_order": 1,
            "blocks": [{
                "sequence_no": 1,
                "block_type": "EXPLANATION",
                "content": {"heading": title, "body": explanation},
            }],
        }],
        "online_question_bank": [{
            "id": "fraction-question-" + language,
            "lesson_code": "fractions-intro-" + language,
            "question_type": "MCQ",
            "prompt": question,
            "difficulty": "EASY",
            "sort_order": 1,
            "explanation": "Draft explanation",
            "options": [
                {"key": "A", "label": "3", "correct": False},
                {"key": "B", "label": "4", "correct": True},
            ],
            "answer_payload": {"kind": "OPTION", "value": "B"},
        }],
    }


def make_visual_block(url: str = "https://media.example.org/fraction.png") -> dict:
    return {
        "sequence_no": 2,
        "block_type": "DIAGRAM",
        "active": True,
        "content": {
            "title": "Fraction diagram",
            "title_hi": "भिन्न का आरेख",
            "description": "A whole divided into four equal parts.",
            "description_hi": "एक पूर्ण वस्तु को चार बराबर भागों में बाँटा गया है।",
            "alt": "A rectangle divided into four equal parts, three shaded.",
            "alt_hi": "एक आयत चार बराबर भागों में बाँटा गया है, जिनमें तीन रंगे हैं।",
            "caption": "Three quarters.",
            "caption_hi": "तीन-चौथाई।",
            "url": url,
            "visual_kind": "DIAGRAM",
            "source_candidate_id": "candidate-fraction-1",
            "source_book_id": "navodaya-source-book",
            "source_pdf_sha256": "a" * 64,
            "source_asset_sha256": "b" * 64,
            "source_pdf_page_number": 3,
            "rights_status": "SOURCE_REFERENCE_ONLY",
            "visual_review_status": "NEEDS_REVIEW",
            "publication_allowed": False,
            "human_review_required": True,
        },
    }


def write_fixture(root: Path, bundles: list[dict], program_code: str = NAVODAYA_CODE) -> Path:
    bundle_dir = root / "drafts"
    bundle_dir.mkdir(parents=True, exist_ok=True)
    entries = []
    for index, bundle in enumerate(bundles, 1):
        name = f"bundle-{index}.json"
        path = bundle_dir / name
        path.write_text(json.dumps(bundle, ensure_ascii=False), encoding="utf-8")
        entries.append({
            "path": f"drafts/{name}",
            "language": bundle["curriculum"]["language"],
            "chapter_code": bundle["chapter"]["code"],
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        })
    manifest = {
        "schema": MANIFEST_SCHEMA,
        "batch": {
            "program_code": program_code,
            "source_class_code": "6",
            "publish_status": "DRAFT",
            "auto_publish": False,
        },
        "bundles": entries,
    }
    result = root / "batch-manifest.json"
    result.write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")
    return result


class StructuredLearningBatchTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def test_dry_run_counts_bundles_lessons_questions_and_visuals(self):
        bundle = make_bundle()
        bundle["lessons"][0]["blocks"].append(make_visual_block())
        manifest = write_fixture(self.root, [bundle])
        result = load_batch(manifest)
        self.assertEqual(NAVODAYA_CODE, result["program_code"])
        self.assertEqual(1, result["bundle_count"])
        self.assertEqual(1, result["lesson_count"])
        self.assertEqual(1, result["question_count"])
        self.assertEqual(1, result["visual_block_count"])
        self.assertFalse(result["summary"][0]["student_delivery_allowed"])
        self.assertTrue(any("remain review-only" in item for item in result["warnings"]))

    def test_only_navodaya_program_is_accepted_by_default(self):
        manifest = write_fixture(self.root, [make_bundle()], program_code="GENERAL")
        with self.assertRaisesRegex(ValueError, "scoped to NAVODAYA_BATCH"):
            load_batch(manifest)

    def test_bundle_path_traversal_is_rejected(self):
        manifest = write_fixture(self.root, [make_bundle()])
        value = json.loads(manifest.read_text(encoding="utf-8"))
        value["bundles"][0]["path"] = "../outside.json"
        manifest.write_text(json.dumps(value), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "escapes"):
            load_batch(manifest)

    def test_changed_bundle_hash_is_rejected(self):
        manifest = write_fixture(self.root, [make_bundle()])
        bundle_path = self.root / "drafts" / "bundle-1.json"
        bundle_path.write_text(bundle_path.read_text(encoding="utf-8") + " ", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "SHA-256 changed"):
            load_batch(manifest)

    def test_source_visual_must_remain_unreviewed_and_not_publishable(self):
        bundle = make_bundle()
        block = make_visual_block()
        block["content"]["visual_review_status"] = "APPROVED"
        bundle["lessons"][0]["blocks"].append(block)
        manifest = write_fixture(self.root, [bundle])
        with self.assertRaisesRegex(ValueError, "must remain NEEDS_REVIEW"):
            load_batch(manifest)

    def test_public_source_candidate_urls_are_rejected(self):
        bundle = make_bundle()
        block = make_visual_block(
            "https://raw.githubusercontent.com/Manav326/QuantaEdge/main/apps/web/public/content/visual-assets/batch/diagram.png"
        )
        bundle["lessons"][0]["blocks"].append(block)
        manifest = write_fixture(self.root, [bundle])
        with self.assertRaisesRegex(ValueError, "public raw GitHub"):
            load_batch(manifest)

    def test_bilingual_visual_accessibility_fields_are_required(self):
        bundle = make_bundle()
        block = make_visual_block()
        del block["content"]["alt_hi"]
        bundle["lessons"][0]["blocks"].append(block)
        manifest = write_fixture(self.root, [bundle])
        with self.assertRaisesRegex(ValueError, "visual alt_hi"):
            load_batch(manifest)

    def test_duplicate_lesson_codes_in_same_language_and_chapter_are_rejected(self):
        bundle1 = make_bundle()
        bundle2 = copy.deepcopy(bundle1)
        bundle2["online_question_bank"][0]["id"] = "q2"
        manifest = write_fixture(self.root, [bundle1, bundle2])
        with self.assertRaisesRegex(ValueError, "duplicate lesson codes"):
            load_batch(manifest)

    def test_course_preflight_is_read_only_and_checks_existing_track(self):
        bundle = make_bundle()
        batch = load_batch(write_fixture(self.root, [bundle]))
        class FakeApi:
            def __init__(self, *_args, **_kwargs):
                pass
            def request(self, method, path):
                if path == "/api/v1/admin/content/programs":
                    return [{"code": NAVODAYA_CODE, "display_name": "Navodaya Batch"}]
                if "subjectCode=maths" in path:
                    return [{
                        "chapter_id": None, "chapter_code": None, "lesson_code": None,
                        "course_id": 33, "program_code": NAVODAYA_CODE,
                    }]
                return []
        with patch("import_structured_learning_batch.AdminApi", FakeApi):
            result = preflight_api(batch, "http://localhost:8080", "fake-session")
        self.assertEqual("PASS_READ_ONLY", result["preflight"])
        self.assertEqual(0, result["writes_performed"])
        self.assertEqual("CREATE_DRAFT_CHAPTER", result["chapter_decisions"][0]["mode"])

    def test_course_preflight_fails_if_subject_track_missing(self):
        batch = load_batch(write_fixture(self.root, [make_bundle()]))
        class FakeApi:
            def __init__(self, *_args, **_kwargs):
                pass
            def request(self, method, path):
                if path == "/api/v1/admin/content/programs":
                    return [{"code": NAVODAYA_CODE, "display_name": "Navodaya Batch"}]
                return []
        with patch("import_structured_learning_batch.AdminApi", FakeApi):
            with self.assertRaisesRegex(RuntimeError, "no active subject track"):
                preflight_api(batch, "http://localhost:8080", "fake-session")


if __name__ == "__main__":
    unittest.main()
