import json
import tempfile
import unittest
from pathlib import Path
from scripts.validate_structured_lesson_artifacts import validate_artifact, validate_index

def bi(en, hi):
    return {"en": en, "hi": hi}

def sample_lesson():
    return {
        "schema_version": "quantaedge.structured-lesson.v1",
        "artifact_type": "quantaedge.structured-lesson.v1",
        "artifact_id": "qe-fixture-topic-01",
        "workflow": {"status": "DRAFT_GENERATED_NOT_REVIEWED", "publication_allowed": False,
                     "content_review_status": "PENDING", "source_completeness_status": "PENDING",
                     "bilingual_parity_status": "PENDING", "visual_review_status": "PENDING"},
        "curriculum": {"class": 6, "subject": "science", "class_code": "6", "subject_code": "science"},
        "chapter": {"chapter_number": 1, "title_by_locale": bi("Chapter One", "अध्याय एक")},
        "topic": {"topic_number": 1, "topic_id": "qe-topic-fixture-01",
                  "title_by_locale": bi("Curiosity", "जिज्ञासा")},
        "learning_objectives": [{"id": "obj-1", "statement_by_locale": bi("Describe curiosity.", "जिज्ञासा का वर्णन करें।")}],
        "source_refs": [{"id": "src-1", "source_pdf_sha256": "a" * 64, "pdf_page": 1}],
        "explain": {"title_by_locale": bi("Explain", "समझें"), "blocks": [
            {"id": "block-1", "type": "rich_text", "objective_ids": ["obj-1"], "source_refs": ["src-1"],
             "text_by_locale": {"en": {"heading": "Curiosity", "paragraphs": ["Curiosity leads to questions."]},
                                "hi": {"heading": "जिज्ञासा", "paragraphs": ["जिज्ञासा से प्रश्न बनते हैं।"]}},
            {"id": "block-interactive", "type": "interactive", "configuration": {
                "steps": [
                    {"id": "step-enhi", "instruction_by_locale": bi("Observe one thing.", "एक वस्तु देखें।")},
                    {"id": "step-enhi-2", "instruction_by_locale": bi("Write a question.", "एक प्रश्न लिखें।")}
                ]
            }}
        ]},
        "practice": {"title_by_locale": bi("Practice", "अभ्यास"), "question_count": 1, "questions": [
            {"id": "q-1", "type": "multiple_choice", "rendering_contract_id": "qtype-multiple-choice.v1",
             "prompt_by_locale": bi("What encourages questions?", "प्रश्न पूछने के लिए क्या प्रेरित करता है?"),
             "feedback_by_locale": bi("Curiosity encourages questions.", "जिज्ञासा प्रश्न पूछने के लिए प्रेरित करती है।"),
             "objective_ids": ["obj-1"], "source_refs": ["src-1"],
             "options": [{"id": "a", "text_by_locale": bi("Curiosity", "जिज्ञासा")},
                         {"id": "b", "text_by_locale": bi("Ignoring evidence", "प्रमाण की अनदेखी")}],
             "correct_answer_ids": ["a"]}
        ]}
    }

def sample_index(path):
    return {"schema_version": "quantaedge.lesson-production-index.v1",
            "target": {"class": 6, "subject": "Science"},
            "production_summary": {"question_counts_by_chapter": [], "batch_question_counts": {}},
            "chapters": [{"number": 1, "title_en": "Chapter One", "title_hi": "अध्याय एक", "topics": [
                {"number": 1, "topic_id": "qe-topic-fixture-01", "title_en": "Curiosity", "title_hi": "जिज्ञासा",
                 "artifact_path": path, "practice_item_count": 1, "practice_types": ["multiple_choice"],
                 "question_type_counts": {"multiple_choice": 1}}
            ]}]}

class StructuredLessonArtifactTests(unittest.TestCase):
    def test_current_runtime_contract_accepts_extra_metadata_and_config_steps(self):
        artifact = sample_lesson()
        artifact["generation_metadata"] = {"model": "local", "extra": {"kept": True}}
        self.assertEqual([], validate_artifact(artifact, "fixture.json"))

    def test_missing_current_db_track_key_is_rejected(self):
        artifact = sample_lesson()
        artifact["curriculum"].pop("class")
        errors = validate_artifact(artifact, "fixture.json")
        self.assertTrue(any("curriculum.class" in error for error in errors))

    def test_db_id_length_is_enforced(self):
        artifact = sample_lesson()
        artifact["practice"]["questions"][0]["id"] = "x" * 181
        errors = validate_artifact(artifact, "fixture.json")
        self.assertTrue(any("practice.questions[0].id" in error and "varchar(180)" in error for error in errors))

    def test_question_type_contract_is_exact_not_merely_nonempty(self):
        artifact = sample_lesson()
        artifact["practice"]["questions"][0]["rendering_contract_id"] = "another.v1"
        errors = validate_artifact(artifact, "fixture.json")
        self.assertTrue(any("rendering_contract_id" in error for error in errors))

    def test_production_index_checks_file_path_numbers_and_counts(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            index_path = root / "data/structured-lessons/class-6/science/production-index.v1.json"
            lesson_path = root / "data/structured-lessons/class-6/science/chapter-01/topic-01-curiosity.v1.json"
            lesson_path.parent.mkdir(parents=True)
            lesson_path.write_text(json.dumps(sample_lesson(), ensure_ascii=False), encoding="utf-8")
            idx = sample_index(str(lesson_path.relative_to(root)).replace("\\\\", "/"))
            result = validate_index(idx, root, index_path)
            self.assertEqual("PASS", result["status"])
            self.assertEqual(1, result["artifact_count"])
            self.assertEqual(1, result["question_count"])

    def test_orphaned_canonical_file_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            index_path = root / "data/structured-lessons/class-6/science/production-index.v1.json"
            folder = index_path.parent / "chapter-01"
            folder.mkdir(parents=True)
            first = sample_lesson()
            (folder / "topic-01-curiosity.v1.json").write_text(json.dumps(first, ensure_ascii=False), encoding="utf-8")
            second = sample_lesson()
            second["artifact_id"] = "qe-fixture-topic-02"
            second["topic"] = {**second["topic"], "topic_number": 2, "topic_id": "qe-topic-fixture-02"}
            (folder / "topic-02-orphan.v1.json").write_text(json.dumps(second, ensure_ascii=False), encoding="utf-8")
            idx = sample_index("data/structured-lessons/class-6/science/chapter-01/topic-01-curiosity.v1.json")
            result = validate_index(idx, root, index_path)
            self.assertEqual("INVALID", result["status"])
            self.assertTrue(any("missing from production index" in error for error in result["errors"]))

if __name__ == "__main__":
    unittest.main()
