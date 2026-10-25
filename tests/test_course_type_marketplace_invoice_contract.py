from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


class CourseTypeMarketplaceInvoiceContractTests(unittest.TestCase):
    def test_course_type_gate_is_generic_and_fails_closed(self):
        migration = ROOT / "services/api/src/main/resources/db/migration/V202__marketplace_course_type_readiness.sql"
        sql = migration.read_text(encoding="utf-8")
        self.assertIn("course_marketplace_content_type_ready", sql)
        self.assertIn("p.course_type_code='ASSESSMENT'", sql)
        self.assertIn("assessment_only_course_publish_ready(p.id)", sql)
        self.assertIn("p.course_type_code='K12'", sql)
        self.assertIn("coalesce(mode.delivery_mode,'LEARNING_CONTENT')='LEARNING_CONTENT'", sql)
        self.assertIn("curriculum_program_subject ps", sql)
        self.assertIn("s.active=true", sql)
        self.assertIn("ch.content_status='PUBLISHED'", sql)
        self.assertIn("l.status='PUBLISHED'", sql)
        self.assertIn("ELSE false", sql)
        self.assertNotIn("tre4-bpsc", sql)
        self.assertNotIn("TRE 1.0", sql)

    def test_learner_listing_checkout_and_payment_verification_use_type_gate(self):
        controller = (ROOT / "services/api/src/main/java/com/quantaedge/api/CourseMarketplaceController.java").read_text(encoding="utf-8")
        self.assertGreaterEqual(controller.count("course_marketplace_content_type_ready("), 9)
        self.assertIn("course_marketplace_content_type_ready(release.program_id)", controller)
        self.assertIn("course_marketplace_content_type_ready(r.program_id)", controller)
        self.assertIn("course_marketplace_content_type_ready(newer_release.program_id)", controller)
        self.assertGreaterEqual(
            controller.count("newer_program.status='ACTIVE' and newer_program.code<>'GENERAL'"), 4,
            "Inactive or placeholder course versions must not hide a currently published learner offer.")

    def test_android_marketplace_and_invoice_history_preserve_course_type(self):
        android_student = (ROOT / "apps/android/app/src/main/java/com/quantaedge/android/ui/StudentMarketplaceScreen.kt").read_text(encoding="utf-8")
        android_parent = (ROOT / "apps/android/app/src/main/java/com/quantaedge/android/ui/ParentCourseMarketplaceScreen.kt").read_text(encoding="utf-8")
        android_type = (ROOT / "apps/android/app/src/main/java/com/quantaedge/android/ui/CourseMarketplaceTypePresentation.kt").read_text(encoding="utf-8")
        android_invoice = (ROOT / "apps/android/app/src/main/java/com/quantaedge/android/ui/CourseInvoiceHistoryScreen.kt").read_text(encoding="utf-8")
        android_app = (ROOT / "apps/android/app/src/main/java/com/quantaedge/android/ui/QuantaEdgeApp.kt").read_text(encoding="utf-8")
        navigation = (ROOT / "apps/android/app/src/main/java/com/quantaedge/android/ui/StudentNavigationContract.kt").read_text(encoding="utf-8")
        provider_paths = (ROOT / "apps/android/app/src/main/res/xml/certificate_file_paths.xml").read_text(encoding="utf-8")

        for screen in (android_student, android_parent):
            self.assertIn("marketplaceCourseTypeLabel", screen)
            self.assertIn("marketplaceAssessmentInventorySummary", screen)
            self.assertIn("onInvoices", screen)
        self.assertIn('"K12"', android_type)
        self.assertIn('"COMPETITIVE"', android_type)
        self.assertIn('"ASSESSMENT"', android_type)
        self.assertIn("course_type_code", android_invoice)
        self.assertIn("course_type_code", android_app)
        self.assertIn("/api/v1/marketplace/family/invoices", android_app)
        self.assertIn("/api/v1/marketplace/invoices", android_app)
        self.assertIn("/api/v1/marketplace/family/orders/", android_app)
        self.assertIn("/api/v1/marketplace/orders/", android_app)
        self.assertIn("CreateDocument(\"application/pdf\")", android_app)
        self.assertIn("Intent.ACTION_SEND", android_app)
        self.assertIn("COURSE_INVOICES", navigation)
        self.assertIn('path="course-invoices/"', provider_paths)

    def test_course_type_is_retained_through_invoice_snapshot_and_history(self):
        financial = (ROOT / "services/api/src/main/java/com/quantaedge/api/CourseOrderFinancialService.java").read_text(encoding="utf-8")
        invoices = (ROOT / "services/api/src/main/java/com/quantaedge/api/CourseInvoiceController.java").read_text(encoding="utf-8")
        invoice_ui = (ROOT / "apps/web/app/components/InvoiceDownloads.tsx").read_text(encoding="utf-8")
        renderer = (ROOT / "services/api/src/main/java/com/quantaedge/api/FiscalDocumentRenderer.java").read_text(encoding="utf-8")

        self.assertIn('taxSnapshot.put("courseTypeCode"', financial)
        self.assertIn('transaction.put("courseTypeCode"', financial)
        self.assertIn("d.transaction_snapshot->>'courseTitle'", invoices)
        self.assertIn("d.transaction_snapshot->>'courseCode'", invoices)
        self.assertIn("d.transaction_snapshot->>'courseTypeCode'", invoices)
        self.assertIn("course_type_code?:string|null", invoice_ui)
        self.assertIn("Course type:", renderer)
        hrms = (ROOT / "apps/people/app/institutions/MarketplaceLaunchStepper.tsx").read_text(encoding="utf-8")
        self.assertIn("Assessment hierarchy complete", hrms)
        self.assertNotIn("TRE inventory complete", hrms)
        self.assertIn('case "ASSESSMENT" -> "Assessment & tests"', renderer)
        # Service classification and tax treatment remain an explicit approved HRMS decision.
        self.assertIn('taxSnapshot.put("serviceClassificationCode",nullableString(profile.get("service_classification_code")))', financial)


if __name__ == "__main__":
    unittest.main()
