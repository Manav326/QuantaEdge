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
