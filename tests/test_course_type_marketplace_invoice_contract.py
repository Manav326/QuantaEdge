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

    def test_learner_listing_and_checkout_use_type_gate_while_verification_uses_saved_identity(self):
        controller = (ROOT / "services/api/src/main/java/com/quantaedge/api/CourseMarketplaceController.java").read_text(encoding="utf-8")
        # Select the implementation overload explicitly; the five-argument learner-safe
        # forwarding overload may be placed before or after it without changing verification.
        verification_signature = """private Map<String,Object> verifyOrderInternal(
      long localOrderId, VerifyCoursePaymentRequest request, Long callerStudentId, Long callerUserId,
      long institutionId, boolean includeFinanceDiagnostics)"""
        verification_start = controller.index(verification_signature)
        verification_end = controller.find("\n  private ", verification_start + 1)
        self.assertGreater(verification_end, verification_start, "Payment verification method boundary must remain explicit.")
        checkout_paths = controller[:verification_start]
        verification = controller[verification_start:verification_end]

        # New listings/checkouts must use the shared course-type gate. Historical payment
        # verification deliberately uses the saved order's immutable offer/course identity,
        # because later publication changes must not invalidate a payment already in flight.
        self.assertGreaterEqual(checkout_paths.count("course_marketplace_content_type_ready("), 8)
        self.assertIn("course_marketplace_content_type_ready(release.program_id)", checkout_paths)
        self.assertIn("course_marketplace_content_type_ready(r.program_id)", checkout_paths)
        self.assertIn("course_marketplace_content_type_ready(newer_release.program_id)", checkout_paths)
        self.assertNotIn("course_marketplace_content_type_ready(", verification)
        self.assertIn("originalOfferMatchesOrder", verification)
        self.assertIn("offer.id=? and offer.institution_id=? and offer.course_id=?", verification)
        self.assertIn("identity.course_type_code=program.course_type_code", verification)
        self.assertIn("identity.source_institution_id=release.source_institution_id", verification)
        # Both nullable caller ids must be explicitly typed for PostgreSQL when one is null.
        self.assertIn("cast(? as bigint) is not null and student_id=?", verification)
        self.assertIn("cast(? as bigint) is not null and purchaser_user_id=?", verification)
        self.assertNotIn("offer.status='PUBLISHED'", verification)
        self.assertNotIn("release.release_state='SHAREABLE'", verification)
        self.assertGreaterEqual(
            controller.count("newer_program.status='ACTIVE' and newer_program.code<>'GENERAL'"), 4,
            "Inactive or placeholder course versions must not hide a currently published learner offer.")

    def test_provider_event_pagination_separates_filter_from_order_by(self):
        operations = (ROOT / "services/api/src/main/java/com/quantaedge/api/CoursePaymentOperationsController.java").read_text(encoding="utf-8")
        self.assertIn('""" + where + "\\n" + """', operations)
        self.assertIn('order by e.received_at desc,e.id desc limit ? offset ?', operations)
        self.assertNotIn('""" + where + """\\n        order by e.received_at', operations)

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
        self.assertIn("courseInvoiceRows", android_app)
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


    def test_release_hrms_assignment_origin_approval_and_marketplace_share_type_gate(self):
        root = ROOT
        migration = (root / "services/api/src/main/resources/db/migration/V205__active_course_type_marketplace_readiness.sql").read_text(encoding="utf-8")
        self.assertIn("registered_type.status='ACTIVE'", migration)
        self.assertIn("identity.status='ACTIVE'", migration)
        self.assertIn("conflicting_release.source_institution_id<>identity.source_institution_id", migration)
        self.assertNotIn("origin_release", migration, "Initial course readiness must not require an already-created release.")
        self.assertIn("p.status='ACTIVE' AND p.code<>'GENERAL'", migration)
        self.assertIn("p.course_type_code='ASSESSMENT'", migration)
        self.assertIn("coalesce(mode.delivery_mode,'LEARNING_CONTENT')='ASSESSMENT_ONLY'", migration)
        self.assertIn("p.course_type_code='K12'", migration)
        self.assertIn("coalesce(mode.delivery_mode,'LEARNING_CONTENT')='LEARNING_CONTENT'", migration)
        self.assertIn("ELSE false", migration)
        self.assertIn("assessment_only_course_publish_ready(p.id)", migration)

        release_workflow = (root / "services/api/src/main/java/com/quantaedge/api/InstitutionCourseReleaseWorkflowService.java").read_text(encoding="utf-8")
        hrms_controller = (root / "services/api/src/main/java/com/quantaedge/api/PlatformInstitutionController.java").read_text(encoding="utf-8")
        launch_controller = (root / "services/api/src/main/java/com/quantaedge/api/CourseMarketplaceLaunchController.java").read_text(encoding="utf-8")
        launch_readiness = (root / "services/api/src/main/java/com/quantaedge/api/CourseMarketplaceLaunchReadinessService.java").read_text(encoding="utf-8")
        hrms_ui = (root / "apps/people/app/institutions/MarketplaceLaunchStepper.tsx").read_text(encoding="utf-8")
        importer = (root / "scripts/import_tre4_course.py").read_text(encoding="utf-8")

        # Source release submission and origin approval use the same DB-owned contract.
        self.assertGreaterEqual(release_workflow.count("select course_marketplace_content_type_ready(?)"), 2)
        # HRMS quality approval and destination assignment each recheck canonical readiness.
        self.assertGreaterEqual(hrms_controller.count("select course_marketplace_content_type_ready(?)"), 2)
        self.assertIn("course_marketplace_content_type_ready(p.id) as course_type_publish_ready", hrms_controller)
        # Marketplace launch checklist, submitted-content preview and available-release list align.
        self.assertGreaterEqual(launch_readiness.count("select course_marketplace_content_type_ready(?)"), 2)
        self.assertIn("course_marketplace_content_type_ready(p.id) as course_type_publish_ready", launch_controller)
        self.assertIn('result.put("courseTypePublishReady"', launch_controller)
        self.assertIn("and course_marketplace_content_type_ready(p.id)", launch_controller)
        # UI reads the server verdict, it does not independently reimplement readiness.
        self.assertIn("release.course_type_publish_ready === true", hrms_ui)
        self.assertIn("preview.courseTypePublishReady !== true", hrms_ui)
        # New importer refuses to run without the same active-type contract.
        self.assertIn("Flyway V205 is required", importer)
        self.assertIn("registered_type.status='ACTIVE'", importer)
        self.assertIn("conflicting_release.source_institution_id<>identity.source_institution_id", importer)
        self.assertIn("SELECT course_marketplace_content_type_ready(%s)", importer)

    def test_typed_answer_review_gate_is_shared_with_assessment_publication(self):
        migration = (ROOT / "services/api/src/main/resources/db/migration/V204__typed_assessment_answer_review_readiness.sql").read_text(encoding="utf-8")
        assessment_controller = (ROOT / "services/api/src/main/java/com/quantaedge/api/AssessmentController.java").read_text(encoding="utf-8")
        admin_assessments = (ROOT / "apps/admin/app/assessments/page.tsx").read_text(encoding="utf-8")
        self.assertIn("assessment_question_snapshot_publish_ready", migration)
        self.assertIn("SELECT COALESCE(CASE", migration)
        self.assertIn("END, false);", migration)
        for kind in ("OPTION", "MULTI_OPTION", "TEXT", "NUMERIC", "ORDER", "MATCH"):
            self.assertIn(f"WHEN '{kind}'", migration)
        self.assertIn("answer_review_status", migration)
        self.assertIn("not assessment_question_snapshot_publish_ready(aq.question_snapshot)", assessment_controller)
        self.assertIn('select assessment_question_snapshot_publish_ready(?::jsonb)', assessment_controller)
        self.assertIn('answer.put("kind",kind)', assessment_controller)
        self.assertIn('snapshot.put("answer_review_status",reviewStatus)', assessment_controller)
        self.assertIn("Answer: {String(question.answer_review_status||'PENDING_HUMAN_REVIEW')}", admin_assessments)
        self.assertIn('String explanation=body.get("explanation")==null?""', assessment_controller)
        self.assertIn('if("APPROVED".equals(reviewStatus)&&explanation.isBlank())', assessment_controller)
        self.assertIn("boolean requiresQuestionAnswerReview", assessment_controller)
        self.assertIn('&& !assessmentOnlyCourse', assessment_controller)
        self.assertIn('portable-package questions or tests belonging to an ASSESSMENT_ONLY course', assessment_controller)


    def test_revenue_share_calculation_snapshot_is_typed_as_jsonb(self):
        financial = (ROOT / "services/api/src/main/java/com/quantaedge/api/CourseOrderFinancialService.java").read_text(encoding="utf-8")

        # JdbcTemplate binds JSON strings as VARCHAR. Cast them at the SQL boundary
        # because PostgreSQL does not implicitly coerce these parameters to jsonb.
        self.assertIn(
            "allocated_amount_paise,platform_remainder_paise,calculation_snapshot,idempotency_key",
            financial,
        )
        self.assertIn(
            ") values(?,?,?,?,?,?,?, ?,?,?,?,?,?,?::jsonb,?) returning id",
            financial,
        )
        self.assertIn(
            "supplier_snapshot,recipient_snapshot,",
            financial,
        )
        self.assertIn(
            "transaction_snapshot,tax_summary_snapshot,taxable_value_paise",
            financial,
        )
        self.assertIn(
            ") values(?,?,?,?,?,?,?,'COURSE_ORDER',?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?,?,?,?,?) returning id",
            financial,
        )


    
    def test_student_invoice_download_audits_student_identity_without_null_user_id(self):
        invoices = (ROOT / "services/api/src/main/java/com/quantaedge/api/CourseInvoiceController.java").read_text(encoding="utf-8")

        # Student workspace identities are keyed by student_id; user_id is legitimately null.
        # Both document-based and legacy invoice download URLs must preserve that identity
        # for the audit event instead of unboxing a null user_id into a primitive long.
        self.assertIn('return buildDownload(orderId,"STUDENT",null,context.studentId());', invoices)
        self.assertIn('return buildDownloadDocument(orderId,documentId,"STUDENT",null,context.studentId());', invoices)
        self.assertIn('if(actorStudentId!=null) auditDetails.put("actorStudentId",actorStudentId);', invoices)
        self.assertIn('if(actorUserId!=null) auditDetails.put("actorUserId",actorUserId);', invoices)

        # Parent downloads continue to be attributed to the purchasing user.
        self.assertIn('return buildDownload(orderId,"PARENT",context.userId(),null);', invoices)
        self.assertIn('return buildDownloadDocument(orderId,documentId,"PARENT",context.userId(),null);', invoices)



    def test_finance_distribution_supports_company_recipients_remainder_and_confirmed_fees(self):
        calculator = (ROOT / "services/api/src/main/java/com/quantaedge/api/RevenueDistributionCalculator.java").read_text(encoding="utf-8")
        financial = (ROOT / "services/api/src/main/java/com/quantaedge/api/CourseOrderFinancialService.java").read_text(encoding="utf-8")
        finance = (ROOT / "services/api/src/main/java/com/quantaedge/api/PlatformFinanceController.java").read_text(encoding="utf-8")
        migration = (ROOT / "services/api/src/main/resources/db/migration/V213__finance_beneficiaries_and_razorpayx_payouts.sql").read_text(encoding="utf-8")

        self.assertIn("calculateBeneficiaries(", calculator)
        self.assertIn("actualPaidPaise-outputTaxPaise-processingChargesPaise", calculator)
        self.assertIn("explicitAmountPaise(),remainder", calculator)
        self.assertIn('recipient_type', migration)
        self.assertIn("legal_entity_id", migration)
        self.assertIn("remainder_recipient_type", migration)
        self.assertIn("PROCESSING_FEE_DEDUCTED", migration)
        self.assertIn("reconcileProviderFees(", financial)
        self.assertIn("entity_kind in ('PLATFORM','COMPANY')", finance)
        self.assertIn("remainderRecipientType", finance)

    def test_razorpayx_payout_requires_legal_review_maker_checker_and_safe_webhooks(self):
        payout = (ROOT / "services/api/src/main/java/com/quantaedge/api/FinancePayoutController.java").read_text(encoding="utf-8")
        webhook = (ROOT / "services/api/src/main/java/com/quantaedge/api/RazorpayXPayoutWebhookController.java").read_text(encoding="utf-8")
        migration = (ROOT / "services/api/src/main/resources/db/migration/V213__finance_beneficiaries_and_razorpayx_payouts.sql").read_text(encoding="utf-8")
        panel = (ROOT / "apps/people/app/finance/PayoutsPanel.tsx").read_text(encoding="utf-8")
        route = (ROOT / "apps/people/app/finance/payouts/page.tsx").read_text(encoding="utf-8")

        self.assertIn("requirePayoutReady()", payout)
        self.assertIn("QE_FINANCE_PAYOUT_APPROVE", payout)
        self.assertIn("created_by_staff_id", payout)
        self.assertIn("collection_arrangement_status", payout)
        self.assertIn("X-Payout-Idempotency", payout)
        self.assertIn("X-Razorpay-Signature", webhook)
        self.assertIn("HmacSHA256", webhook)
        self.assertIn("request.getInputStream().readNBytes", webhook)
        self.assertIn("finance_payout_provider_event", payout)
        self.assertIn("bank_account_last4", migration)
        self.assertNotIn("account_number varchar", migration)
        self.assertIn("Legal seller & collection-account review", panel)
        self.assertIn("PayoutsPanel", route)

    def test_refund_reversal_accounts_for_confirmed_fee_adjustments_for_any_recipient(self):
        refunds = (ROOT / "services/api/src/main/java/com/quantaedge/api/CourseRefundLedgerService.java").read_text(encoding="utf-8")
        payout_ui = (ROOT / "apps/people/app/finance/PayoutsPanel.tsx").read_text(encoding="utf-8")
        css = (ROOT / "apps/people/app/finance/Finance.module.css").read_text(encoding="utf-8")

        self.assertIn("a.legal_entity_id", refunds)
        self.assertIn("PROCESSING_FEE_DEDUCTED", refunds)
        self.assertIn("grossAllocated-number(allocation.get(\"fee_deducted_paise\"))", refunds)
        self.assertIn("cumulativeNetRefund>=pool?allocated", refunds)
        self.assertIn("/payout-balances", payout_ui)
        self.assertIn("recovery_due_paise", payout_ui)
        self.assertIn(".payoutBalanceTable", css)



    def test_unified_payout_recipient_register_and_tax_visibility_are_explicit(self):
        payout = (ROOT / "services/api/src/main/java/com/quantaedge/api/FinancePayoutController.java").read_text(encoding="utf-8")
        panel = (ROOT / "apps/people/app/finance/PayoutsPanel.tsx").read_text(encoding="utf-8")
        css = (ROOT / "apps/people/app/finance/Finance.module.css").read_text(encoding="utf-8")
        checklist = (ROOT / "docs/finance/hrms-unified-recipient-tax-settlement-checklist.md").read_text(encoding="utf-8")

        # Active entities begin the query so zero-activity recipients remain visible.
        self.assertIn("with recipients as (", payout)
        self.assertIn("from recipients r", payout)
        self.assertIn("left join balance_by_currency fin", payout)
        self.assertIn("entity_kind in ('PLATFORM','COMPANY')", payout)
        self.assertIn("Keep historic liabilities visible", payout)
        self.assertIn("recipient_status", payout)
        self.assertIn('row.get("recipient_status")', payout)
        self.assertIn("payout_readiness_reason", payout)
        self.assertIn("unreconciled_fee_order_count", payout)
        self.assertIn("does not assess GST TCS or income-tax TDS", payout)

        # Source and marketplace institutions are distinct, and tax is currency-safe.
        self.assertIn("source_institution_name", payout)
        self.assertIn("marketplace_institution_name", payout)
        self.assertIn('@GetMapping("/payout-tax-overview")', payout)
        self.assertIn("upper(currency)='INR'", payout)
        self.assertIn('"gstTcsLedgerStatus","NOT_REPRESENTED"', payout)
        self.assertIn('"incomeTaxTdsLedgerStatus","NOT_REPRESENTED"', payout)

        # The UI avoids suggesting a zero TCS/TDS liability and no longer depends on
        # an 11-column horizontal table as the recipient's primary view.
        self.assertIn("All institutions & companies", panel)
        self.assertIn("payoutRecipientGrid", panel)
        self.assertIn("recipientReadinessFilter", panel)
        self.assertIn("recipientCurrencyFilter", panel)
        self.assertIn("RECOVERY_DUE", panel)
        self.assertIn("IN_PROGRESS", panel)
        self.assertIn("Payout checks pass · tax review needed", panel)
        self.assertIn("Earned allocation", panel)
        self.assertIn("Tax review is not included in automated payout readiness", panel)
        self.assertIn("Tax & compliance snapshot", panel)
        self.assertIn("This must not be treated as ₹0", panel)
        self.assertIn(".payoutRecipientGrid{", css)
        self.assertIn("GST TCS", panel)
        self.assertIn("Income-tax TDS", panel)
        self.assertIn("never invent a tax rate", checklist.lower())


if __name__ == "__main__":
    unittest.main()
