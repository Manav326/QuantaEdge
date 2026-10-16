import json
import tempfile
import unittest
from pathlib import Path

from scripts.package_published_structured_lessons import is_publishable, stage_published_lessons


def artifact(workflow=None):
    return {
        "schema_version": "quantaedge.structured-lesson.v1",
        "artifact_type": "draft_lesson",
        "artifact_id": "qe-test-topic-v1",
        "workflow": workflow or {
            "status": "DRAFT_GENERATED_NOT_REVIEWED",
            "publication_allowed": False,
        },
    }


def approved_workflow():
    return {
        "status": "PUBLISHED",
        "publication_allowed": True,
        "content_review_status": "APPROVED",
        "source_completeness_status": "APPROVED",
        "bilingual_parity_status": "APPROVED",
        "scientific_review_status": "APPROVED",
        "visual_review_status": "APPROVED_FOR_STUDENT_RENDERING",
    }


class PackagePublishedStructuredLessonsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.source = self.root / "data" / "structured-lessons"
        self.destination = self.root / "build" / "structured-lessons"
        self.lesson_path = self.source / "class-6" / "science" / "chapter-01" / "topic-01-test.v1.json"
        self.lesson_path.parent.mkdir(parents=True)
        self.write_artifact(artifact())

    def tearDown(self):
        self.temp.cleanup()

    def write_artifact(self, value):
        self.lesson_path.write_text(json.dumps(value), encoding="utf-8")

    def test_drafts_are_excluded_from_runtime_but_preserved_as_source(self):
        result = stage_published_lessons(self.source, self.destination)
        self.assertEqual(
            {"source_lesson_count": 1, "packaged_published_count": 0, "excluded_not_publishable_count": 1},
            result,
        )
        self.assertTrue(self.lesson_path.is_file(), "Canonical draft source must be preserved.")
        self.assertFalse((self.destination / "class-6/science/chapter-01/topic-01-test.v1.json").exists())

    def test_only_fully_approved_published_lesson_is_packaged(self):
        self.write_artifact(artifact(approved_workflow()))
        result = stage_published_lessons(self.source, self.destination)
        copied = self.destination / "class-6/science/chapter-01/topic-01-test.v1.json"
        self.assertEqual(1, result["packaged_published_count"])
        self.assertTrue(copied.is_file())
        self.assertEqual(json.loads(self.lesson_path.read_text()), json.loads(copied.read_text()))

    def test_published_flag_alone_is_not_enough(self):
        workflow = approved_workflow()
        workflow["bilingual_parity_status"] = "PENDING"
        self.write_artifact(artifact(workflow))
        self.assertFalse(is_publishable(json.loads(self.lesson_path.read_text())))
        result = stage_published_lessons(self.source, self.destination)
        self.assertEqual(0, result["packaged_published_count"])
        self.assertFalse((self.destination / "class-6/science/chapter-01/topic-01-test.v1.json").exists())

    def test_visual_review_must_be_approved(self):
        workflow = approved_workflow()
        workflow["visual_review_status"] = "PENDING"
        self.assertFalse(is_publishable(artifact(workflow)))

    def test_stale_destination_is_cleared_before_staging(self):
        stale = self.destination / "class-6/science/chapter-01/topic-99-stale.v1.json"
        stale.parent.mkdir(parents=True)
        stale.write_text("{}", encoding="utf-8")
        stage_published_lessons(self.source, self.destination)
        self.assertFalse(stale.exists())

    def test_invalid_canonical_topic_json_fails_the_build(self):
        self.lesson_path.write_text("{bad json", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Cannot parse canonical lesson artifact"):
            stage_published_lessons(self.source, self.destination)


if __name__ == "__main__":
    unittest.main()
