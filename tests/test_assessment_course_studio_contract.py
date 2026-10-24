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
        self.assertIn("student-delivery", page.lower())

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

    def test_portable_package_preserves_analytics_dimensions(self):
        schema = (ROOT / "schemas/portable-assessment-package.schema.json").read_text(encoding="utf-8")
        for field in ('"topic_tags"', '"secondary_topic_tags"', '"difficulty"', '"section_code"', '"source_location"', '"question_type"'):
            self.assertIn(field, schema)


if __name__ == "__main__":
    unittest.main()
