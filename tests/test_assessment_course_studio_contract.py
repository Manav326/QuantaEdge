"""Cross-layer contract smoke tests for the TRE assessment-course launch path."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class AssessmentCourseStudioContractTests(unittest.TestCase):
    def test_admin_studio_uses_authenticated_course_scoped_package_routes(self):
        page = (ROOT / "apps/admin/app/content/assessment-course/page.tsx").read_text(encoding="utf-8")
        self.assertIn("courseTypeCode: 'ASSESSMENT'", page)
        self.assertIn("/api/v1/admin/content/courses", page)
        self.assertIn("/api/v1/admin/assessments/validate-package", page)
        self.assertIn("/api/v1/admin/assessments/import-package", page)
        self.assertIn("student delivery", page.lower())

    def test_assessment_course_creation_is_type_specific_and_tenant_owned(self):
        service = (ROOT / "services/api/src/main/java/com/quantaedge/api/CourseAuthoringService.java").read_text(encoding="utf-8")
        self.assertIn('"ASSESSMENT".equals(type)', service)
        self.assertIn("institution_course_identity", service)
        self.assertIn("curriculum_program_delivery_mode", service)
        self.assertIn("'prev-years','Prev years'", service)
        self.assertIn('"QUANTA-EDGE".equalsIgnoreCase(tenantCode)', service)

    def test_tre_batch_import_binds_course_identity_and_type(self):
        importer = (ROOT / "scripts/import_tre4_course.py").read_text(encoding="utf-8")
        self.assertIn("def ensure_course_identity", importer)
        self.assertIn("course_type_code='ASSESSMENT'", importer)
        self.assertIn("institution_course_identity", importer)
        self.assertIn("EXPECTED_QUESTIONS = {\"TRE 1.0\": 120, \"TRE 2.0\": 150, \"TRE 3.0\": 150}", importer)

    def test_release_fingerprint_covers_full_lesson_and_resource_graph(self):
        migration = (ROOT / "services/api/src/main/resources/db/migration/V182__complete_course_release_content_fingerprint.sql").read_text(encoding="utf-8")
        for token in (
            "course_content_fingerprint_v169",
            "lesson-block-translation",
            "question-option",
            "question-translation",
            "learning-resource",
            "learning-document",
            "source-book-page",
            "assessment-pack-resource",
            "reviewed release adopts the new fingerprint only when its legacy fingerprint still matches",
        ):
            self.assertIn(token, migration)

    def test_hrms_preview_includes_k12_lessons_blocks_questions_translations_and_resources(self):
        controller = (ROOT / "services/api/src/main/java/com/quantaedge/api/CourseMarketplaceLaunchController.java").read_text(encoding="utf-8")
        hrms_ui = (ROOT / "apps/people/app/institutions/MarketplaceLaunchStepper.tsx").read_text(encoding="utf-8")
        self.assertIn('RequestParam(required=false) Long chapterId', controller)
        self.assertIn("lesson_block_translation", controller)
        self.assertIn("question_option", controller)
        self.assertIn("learning_resource_assignment", controller)
        self.assertIn("Review chapter content", hrms_ui)
        self.assertIn("Teaching blocks", hrms_ui)
        self.assertIn("Chapter resources", hrms_ui)
        self.assertIn("Lesson questions and answer keys", hrms_ui)

    def test_hrms_can_preview_exact_assessment_release_before_quality_decision(self):
        controller = (ROOT / "services/api/src/main/java/com/quantaedge/api/CourseMarketplaceLaunchController.java").read_text(encoding="utf-8")
        hrms_ui = (ROOT / "apps/people/app/institutions/MarketplaceLaunchStepper.tsx").read_text(encoding="utf-8")
        self.assertIn('"/{institutionId}/course-releases/{releaseId}/review-content"', controller)
        self.assertIn("course_content_fingerprint(?)", controller)
        self.assertIn("question_snapshot::text as question_snapshot", controller)
        self.assertIn("Review submitted content", hrms_ui)
        self.assertIn("Answer review:", hrms_ui)
        self.assertIn("preview.contentCurrent", hrms_ui)
        self.assertIn("reviewedContentFingerprint", hrms_ui)
        self.assertIn("reviewedContentFingerprint", (ROOT / "services/api/src/main/java/com/quantaedge/api/PlatformInstitutionController.java").read_text(encoding="utf-8"))

    def test_learner_catalogue_exposes_renderer_contract(self):
        controller = (ROOT / "services/api/src/main/java/com/quantaedge/api/StudentProgramController.java").read_text(encoding="utf-8")
        web_courses = (ROOT / "apps/web/app/student/courses/page.tsx").read_text(encoding="utf-8")
        web_tests = (ROOT / "apps/web/app/student/tests/TestsClient.tsx").read_text(encoding="utf-8")
        self.assertIn("p.course_type_code", controller)
        self.assertIn("as delivery_mode", controller)
        self.assertIn("as layout_schema_version", controller)
        self.assertIn("'/student/tests?courseId='", web_courses)
        self.assertIn("useStudentCourseScope(requestedCourseId)", web_tests)
        self.assertIn("const rawCourseId=(searchParams.get('courseId')||'').trim()", web_tests)

    def test_portable_package_preserves_analytics_dimensions(self):
        schema = (ROOT / "schemas/portable-assessment-package.schema.json").read_text(encoding="utf-8")
        for field in ('"topic_tags"', '"secondary_topic_tags"', '"difficulty"', '"section_code"', '"source_location"', '"question_type"'):
            self.assertIn(field, schema)


if __name__ == "__main__":
    unittest.main()
