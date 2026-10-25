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
        migration = (ROOT / "services/api/src/main/resources/db/migration/V196__complete_course_release_content_fingerprint.sql").read_text(encoding="utf-8")
        for token in (
            "course_content_fingerprint_v169",
            "lesson-block-translation",
            "question-option",
            "question-translation",
            "learning-resource",
            "learning-document",
            "source-book-page",
            "assessment-pack-resource",
            "release adopts the new fingerprint only when its legacy fingerprint still matches",
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

    def test_quality_queue_returns_fingerprint_current_and_course_type_contract(self):
        controller = (ROOT / "services/api/src/main/java/com/quantaedge/api/PlatformInstitutionController.java").read_text(encoding="utf-8")
        self.assertIn("r.updated_at as release_updated_at", controller)
        self.assertIn("p.course_type_code", controller)
        self.assertIn("as delivery_mode", controller)
        self.assertIn('release.put("contentCurrent",contentCurrent)', controller)
        self.assertIn("course_release_fingerprint_current(?,?,?)", controller)
        self.assertNotIn("as delivery_model", controller)

    def test_content_studio_routes_assessment_only_course_to_test_editor(self):
        page = (ROOT / "apps/admin/app/content/page.tsx").read_text(encoding="utf-8")
        self.assertIn("isAssessmentCourse", page)
        self.assertIn("ASSESSMENT", page)
        self.assertIn("Course → test packs → assessments/tests → ordered question snapshots", page)
        self.assertIn("Open this course’s tests and questions", page)
        self.assertIn("href={'/assessments?courseId='+encodeURIComponent(programFilter)}", page)
        self.assertIn("{isAssessmentCourse ? (", page)

    def test_assessment_analytics_is_course_scoped_and_never_buckets_free_text(self):
        controller = (ROOT / "services/api/src/main/java/com/quantaedge/api/AssessmentController.java").read_text(encoding="utf-8")
        analytics = controller[controller.index('@GetMapping("/admin/assessments/course-analytics")'):controller.index('@PostMapping("/admin/assessments")', controller.index('@GetMapping("/admin/assessments/course-analytics")'))]
        self.assertIn('requireAssignedCourse(courseId,tenant)', analytics)
        self.assertIn('att.institution_id=?', analytics)
        self.assertIn('CAST(? AS bigint) IS NULL OR a.id=?', analytics)
        self.assertIn("rv.question_snapshot->'options'", analytics)
        self.assertIn("rv.selected_value=coalesce(opt.option_json->>'key',opt.option_json->>'option_key')", analytics)
        self.assertIn('"studentIdentifiersIncluded",false', analytics)
        self.assertNotIn('ans.response_payload::text as response', analytics)

    def test_hrms_marketplace_queue_exposes_release_fingerprint_state(self):
        controller = (ROOT / "services/api/src/main/java/com/quantaedge/api/CourseMarketplaceLaunchController.java").read_text(encoding="utf-8")
        self.assertIn("p.course_type_code", controller)
        self.assertIn("as delivery_mode", controller)
        self.assertIn("r.updated_at as release_updated_at", controller)
        self.assertIn('release.put("contentCurrent",readiness.contentFingerprintMatches(', controller)

    def test_hrms_can_preview_exact_assessment_release_before_quality_decision(self):
        controller = (ROOT / "services/api/src/main/java/com/quantaedge/api/CourseMarketplaceLaunchController.java").read_text(encoding="utf-8")
        hrms_ui = (ROOT / "apps/people/app/institutions/MarketplaceLaunchStepper.tsx").read_text(encoding="utf-8")
        self.assertIn('"/{institutionId}/course-releases/{releaseId}/review-content"', controller)
        self.assertIn("course_release_fingerprint_current(?,?,?)", controller)
        fingerprint_gate = (ROOT / "services/api/src/main/resources/db/migration/V201__avoid_repeat_fingerprints_for_verified_releases.sql").read_text(encoding="utf-8")
        self.assertIn("course_release_fingerprint_current", fingerprint_gate)
        self.assertIn("fail closed", fingerprint_gate)
        self.assertIn("question_snapshot::text as question_snapshot", controller)
        self.assertIn("Review submitted content", hrms_ui)
        self.assertIn("Answer review:", hrms_ui)
        self.assertIn("preview.contentCurrent", hrms_ui)
        self.assertIn("reviewedContentFingerprint", hrms_ui)
        self.assertIn("reviewedContentFingerprint", (ROOT / "services/api/src/main/java/com/quantaedge/api/PlatformInstitutionController.java").read_text(encoding="utf-8"))

    def test_quality_gate_has_actionable_preview_recovery_without_bypassing_fingerprint(self):
        hrms_ui = (ROOT / "apps/people/app/institutions/MarketplaceLaunchStepper.tsx").read_text(encoding="utf-8")
        review_controller = (ROOT / "services/api/src/main/java/com/quantaedge/api/PlatformInstitutionController.java").read_text(encoding="utf-8")
        self.assertIn("const previewNeedsLoading = !preview && !previewLoadInProgress", hrms_ui)
        self.assertNotIn("!preview && release.contentCurrent && qualityReady", hrms_ui)
        self.assertIn("Load exact submitted content", hrms_ui)
        self.assertIn("onClick={() => void toggleReviewContent(release)}", hrms_ui)
        self.assertIn("previewFingerprintMismatch", hrms_ui)
        self.assertIn("normalizeFingerprint", hrms_ui)
        self.assertIn("Load exact submitted content", hrms_ui)
        self.assertIn("reviewedContentFingerprint", review_controller)
        self.assertIn("savedFingerprint.equals(reviewedFingerprint)", review_controller)
        self.assertIn("course_release_fingerprint_current(?,?,?)", review_controller)
        self.assertIn("if(approved && \"ASSESSMENT_ONLY\".equals(courseDeliveryMode(programId)))", review_controller)

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


    def test_student_assessment_review_renders_topic_analysis_and_persistent_mistake_practice(self):
        web = (ROOT / "apps/web/app/student/tests/TestsClient.tsx").read_text(encoding="utf-8")
        android = (ROOT / "apps/android/app/src/main/java/com/quantaedge/android/ui/QuantaEdgeApp.kt").read_text(encoding="utf-8")
        controller = (ROOT / "services/api/src/main/java/com/quantaedge/api/AssessmentController.java").read_text(encoding="utf-8")
        api_test = (ROOT / "services/api/src/test/java/com/quantaedge/api/AssessmentLifecycleIntegrationTest.java").read_text(encoding="utf-8")

        # The released result exposes topic/micro-topic diagnostics and keeps blank answers distinct.
        self.assertIn("Topic-wise analysis", web)
        self.assertIn("Show micro-topic breakdown", web)
        self.assertIn("unanswered_count", web)
        self.assertIn("Not classified", web)
        self.assertIn("studentAssessmentTopicMetrics", android)
        self.assertIn("Not classified", android)
        self.assertIn("metric.unscored", android)
        self.assertIn("All incorrectly answered questions are collected here in one practice set.", web)
        self.assertIn("Learn from your mistakes", android)

        # Retry endpoints are authenticated, released-result-only, course/institution scoped,
        # and persist attempts independently so official scoring/negative marking is immutable.
        self.assertIn('@GetMapping("/learning/assessment-attempts/{attemptId}/incorrect-practice")', controller)
        self.assertIn('@PostMapping("/learning/assessment-attempts/{attemptId}/incorrect-practice")', controller)
        self.assertIn("Only a question originally marked incorrect", controller)
        self.assertIn("student_assessment_question_practice_attempt", controller)
        self.assertIn("releasedIncorrectQuestionsCanBeRetriedWithoutChangingTheOfficialScore", api_test)
        self.assertIn("Practice answers must never change the released score", api_test)

        # Each opened and refreshed result is bound to the currently selected course in Android.
        review_start = android.index("fun renderAssessmentReviewRoute()")
        review_end = android.index("fun renderResourcesRoute()", review_start)
        review_route = android[review_start:review_end]
        self.assertIn('?courseId=" + currentCourseId', review_route)

        self.assertIn("const visibleItems=activeItems.length?activeItems:items.slice(0,1)", web)
        self.assertIn("Missed question", web)
        self.assertIn("practiceQuestionIndex", web)
        self.assertIn("questions.forEachIndexed", android)
        self.assertIn("One at a time", android)
        self.assertIn("practiceQuestionIndex = (practiceIndex + 1).coerceAtMost(incorrectQuestions.lastIndex)", android)


if __name__ == "__main__":
    unittest.main()
