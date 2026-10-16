import json
import tempfile
import unittest
from pathlib import Path
from scripts.register_structured_lesson_drafts import register
from test_validate_structured_lesson_artifacts import sample_lesson, sample_index

def draft_with_book(source_book, digest):
    lesson = sample_lesson()
    lesson["workflow"].update({"source_book_id": source_book, "source_pdf_sha256": digest,
                               "human_review_required": True, "scientific_review_status": "PENDING"})
    lesson["curriculum"]["source_book_id"] = source_book
    lesson["artifact_id"] = "qe-candidate-lesson-02"
    lesson["topic"]["topic_id"] = "qe-topic-candidate-02"
    lesson["topic"]["title_by_locale"] = {"en": "Another Idea", "hi": "एक और विचार"}
    lesson["source_refs"][0]["source_book_id"] = source_book
    lesson["source_refs"][0]["source_pdf_sha256"] = digest
    return lesson

class RegisterDraftTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.index_path = self.root / "data/structured-lessons/class-6/science/production-index.v1.json"
        self.index_path.parent.mkdir(parents=True)
        self.lesson_path = self.index_path.parent / "chapter-01/topic-01-curiosity.v1.json"
        self.lesson_path.parent.mkdir(parents=True)
        original = sample_lesson()
        self.lesson_path.write_text(json.dumps(original, ensure_ascii=False), encoding="utf-8")
        self.index = sample_index(str(self.lesson_path.relative_to(self.root)).replace("\\\\", "/"))
        self.index["target"]["books"] = {
            "en": {"book_id": "book-en", "pdf_sha256": "a" * 64, "title": "Curiosity", "edition": "Test", "source_url": "https://ncert.nic.in/"},
            "hi": {"book_id": "book-hi", "pdf_sha256": "b" * 64, "title": "जिज्ञासा", "edition": "Test", "source_url": "https://ncert.nic.in/"}
        }
        self.index_path.write_text(json.dumps(self.index, ensure_ascii=False), encoding="utf-8")
        self.candidate_dir = self.root / "candidate/lessons"
        self.candidate_dir.mkdir(parents=True)
        candidate = draft_with_book("book-en", "a" * 64)
        (self.candidate_dir / "qe-lesson-candidate.json").write_text(json.dumps(candidate, ensure_ascii=False), encoding="utf-8")

    def tearDown(self):
        self.tmp.cleanup()

    def test_dry_run_does_not_write_canonical_data(self):
        report = register(self.candidate_dir, self.root, self.index_path, json.loads(self.index_path.read_text()), 1,
                          apply=False, confirm_mapping=True)
        self.assertEqual("DRY_RUN", report["status"])
        self.assertFalse(report["writes_performed"])
        self.assertEqual(1, len(json.loads(self.index_path.read_text())["chapters"][0]["topics"]))

    def test_apply_preserves_extra_fields_and_registers_only_as_draft(self):
        report = register(self.candidate_dir, self.root, self.index_path, json.loads(self.index_path.read_text()), 1,
                          apply=True, confirm_mapping=True)
        self.assertEqual("REGISTERED_DRAFTS_NOT_PUBLISHED", report["status"])
        registered = json.loads((self.root / report["destinations"][0]).read_text())
        self.assertEqual("science", registered["curriculum"]["subject"])
        self.assertFalse(registered["workflow"]["publication_allowed"])
        self.assertTrue(registered["registration_metadata"]["registered_from_candidate"])
        self.assertEqual("PASS", report["post_write_validation"]["status"])

    def test_unconfirmed_mapping_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "Confirm the exact"):
            register(self.candidate_dir, self.root, self.index_path, json.loads(self.index_path.read_text()), 1,
                     apply=False, confirm_mapping=False)

    def test_mismatched_source_hash_is_rejected(self):
        candidate_path = self.candidate_dir / "qe-lesson-candidate.json"
        candidate = json.loads(candidate_path.read_text())
        candidate["workflow"]["source_pdf_sha256"] = "c" * 64
        candidate_path.write_text(json.dumps(candidate), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "does not match"):
            register(self.candidate_dir, self.root, self.index_path, json.loads(self.index_path.read_text()), 1,
                     apply=False, confirm_mapping=True)

if __name__ == "__main__":
    unittest.main()
