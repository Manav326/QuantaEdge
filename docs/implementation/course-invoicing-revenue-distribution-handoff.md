# Course invoices, GST and institutional revenue distribution

**Status:** Implementation in progress. This document is the durable source of truth for this workstream and must be updated in each implementation checkpoint.
**Owner:** HRMS / QuantaEdge platform finance
**Repository:** `Manav326/QuantaEdge`
**Target branch:** `main` (direct commits; no feature branch required for this workstream)
**Last checkpoint:** 2026-10-20
**Initial audited migration baseline:** V148 (V149 is the next migration number at the time of initial work)
**Release status:** NOT READY. Schema foundations and calculation tests do not by themselves authorize production invoicing or payouts.

## 1. Product goal

Deliver one server-authoritative financial-document system shared by course marketplace, student web, parent web, Android, Admin and HRMS. It must:

- Calculate tax using an HRMS-approved, versioned tax policy and the actual transaction.
- Issue and preserve legally appropriate invoices and other fiscal documents.
- Calculate institutional revenue shares from the actual customer money received **after the applicable output tax amount is deducted**.
- Allow each participating institution to receive either a percentage of the distributable pool or a flat amount per eligible completed sale.
- Require HRMS to configure and approve all allocations when two or more institutions participate in the same sale.
- Apply coupon/offer discounts before deriving actual paid amount and taxable value under the approved tax policy; distributions must never use the undiscounted catalogue price as if that money had been received.
- Retain the calculation basis, policy version, seller, recipient, payment, tax and every adjustment in an auditable record.
- Provide secure invoice access from web and Android purchase history.
- Give HRMS a premium Invoice Design Studio with a visible, updating preview, print/PDF preview, template versioning and approval/publish flow.
- Keep institution billing-contract invoices separate from learner course-sale invoices and separate from the seller's invoice to an institution for a revenue-share/commission service, where one is required by the actual legal agreement.

## 2. Source audit — initial checkpoint

Reviewed relevant source on `main` on 2026-10-20:

- `services/api/src/main/java/com/quantaedge/api/CourseMarketplaceController.java`: server-side course checkout creates local orders from an institution-published offer, sends a server-created Razorpay order, verifies/reconciles payment and grants entitlements. Student and parent purchase routes are institution-scoped.
- `services/api/src/main/resources/db/migration/V113__course_marketplace_razorpay.sql`: legacy foundation for `course_payment_order` and `course_purchase`.
- `services/api/src/main/resources/db/migration/V115__unified_identity_and_course_entitlements.sql`: purchase/entitlement model and purchaser-versus-recipient concepts.
- `services/api/src/main/resources/db/migration/V120__institution_tenant_white_label_foundation.sql`: existing institution billing contracts and append-only `institution_billable_event` charge/credit ledger.
- `services/api/src/main/resources/db/migration/V125__institution_course_releases_and_tenant_offers.sql`: institution-specific course release, assignment and marketplace-offer model.
- `apps/people/app/institutions/page.tsx`: HRMS institution billing contract UI exists, but source text explicitly says invoice issuance, tax/credits and final invoicing are not release-ready.
- `docs/operations/hrms-managed-institution-integrations.md`: HRMS owns central Razorpay credentials; the tenant must not provide provider secrets.
- At this checkpoint the highest numbered migration found in the migration directory was `V148__course_scope_ai_tutor_sessions.sql`. Use V149 as the next migration only after re-checking main to avoid a collision.

Initial limitations/findings:

1. Payment/order/purchase evidence exists, but an end-to-end fiscal document ledger, statutory invoice numbering, document snapshots, secure invoice-download API and invoice design studio were not found in the searches/review.
2. Institution billing contracts are for platform-to-institution recurring/periodic contractual billing. They are not the learner's course-sale invoice.
3. The actual course-sale legal supplier cannot be inferred from the tenant brand or Razorpay account. Model and approve a legal seller separately.
4. Coupon/offer-code integration was not yet proven by the targeted repository searches. Audit actual discount write paths before implementing the final amount/tax snapshot.
5. Do not issue a real invoice, show production tax values, report revenue, or make real payouts from fixture/mock data.

## 3. Money and policy rules

### 3.1 Definitions

- **List price:** the displayed catalogue/offer price; not a reliable distribution basis if a discount is used.
- **Actual paid amount:** amount confirmed as captured/paid by the server against the local order.
- **Tax amount:** the approved tax engine's output tax for that supply and transaction, calculated consistently with the issued invoice.
- **Distributable pool:** actual paid amount minus the applicable output-tax amount deducted for distribution. The CA-approved accounting/tax policy must state how rounding, refunds, gateway charges, chargebacks and any other adjustments are handled. Do not silently deduct gateway fees from institutional shares.
- **Institution allocation:** the amount payable/earned under the approved policy for a participating institution.
- **Platform remainder:** pool minus all institution allocations; allocation total must never exceed the pool.

The normal distribution formula is:

`pool_paise = actual_paid_paise - output_tax_paise`

For each approved recipient:

- Percentage: round `pool_paise * percentage_basis_points / 10000` to the nearest paise using documented half-up rounding.
- Flat: use `flat_amount_paise` per eligible completed sale, in the order currency.
- `sum(allocations) <= pool_paise` is a hard invariant. Reject the policy execution and raise a reconciliation exception if it fails; never silently prorate or reduce signed contractual amounts.

All money must be represented as integer minor units (INR paise for INR), not binary floating point. Currency must be stored explicitly.

### 3.2 Sale lifecycle

1. Resolve institution, course release/offer, legal seller, approved tax profile, price and applicable discount before creating provider checkout.
2. Persist the payable total and a snapshot of the offer and tax calculation expected for the order. Price/tax cannot be accepted from the browser as authoritative.
3. Create a Razorpay order for exactly that payable amount.
4. On callback/webhook/retry, ask the server/provider to confirm the correct order, currency, amount and captured payment. Popup success alone is insufficient.
5. Reconcile the original order idempotently. No second charge or second invoice should be needed if the callback was lost.
6. When the payment is confirmed, persist the course purchase/entitlement with the current existing domain flow. Issue the appropriate document exactly once.
7. Create immutable allocation calculation snapshots from actual paid amount, the tax result and an approved effective revenue-share policy. Do not calculate from list price or client-side totals.
8. Move an allocation through recorded operational events (earned, payable, paid, held or reversed). A real payout is a distinct and separately authorized action.
9. For refunds, partial refunds, chargebacks and credit notes, add linked adjustment/reversal events. Never delete or rewrite the original invoice/allocation record.
10. Retry/reconciliation paths must be idempotent and retain provider references, local IDs and audit events.

## 4. Revenue share policy design

A policy is associated with a specific eligible institution marketplace offer / course-sale scope and has a version, currency, effective period, author, approval authority and status: DRAFT, PENDING_APPROVAL, APPROVED, RETIRED (or equivalent validated states).

Each recipient row contains:

- Institution ID (must reference an existing institution tenant).
- Method: PERCENTAGE or FLAT.
- Percentage in basis points or flat money amount in minor units.
- Effective policy/version reference and optional contractual reference/notes.
- Approver and approval timestamp in the policy audit record.

Policy constraints:

- HRMS platform owner / explicit finance permission is the only actor allowed to approve multi-institution distribution; staff job title alone grants no finance permission.
- A course/offer may have only one active applicable approved policy for a given effective period; version changes are effective-dated and never rewrite an old sale.
- Reject duplicate recipient institutions within one policy.
- Reject malformed percentage/flat values.
- At approval time, validate every possible policy rule that can be validated without a sale. At settlement time, validate the actual currency-specific pool against the real allocations.
- No missing configuration fallback, global/orphan institution, negative amount or cross-tenant leakage.
- Payout destination verification, tax invoices from institutions, TDS/withholding and other vendor-payable obligations are separate gates determined by contract and professional advice.

## 5. Legal seller, tax and documents

### 5.1 Legal seller

The seller is an explicit `billing_legal_entity`, separately related to the owning institution/platform and the offer. Institution display branding is not a legal-seller record. Razorpay platform credentials or collection alone do not decide who legally supplied the service.

Before production, HRMS/finance and a CA/GST practitioner must approve the seller-of-record model for QuantaEdge's own courses, third-party institution courses, agency/marketplace arrangements and institution commission/revenue-share arrangements.

### 5.2 Tax

- No hardcoded GST percentage and no assumption that school curriculum content makes a paid digital course exempt.
- Use versioned, effective-dated product/service tax profiles containing the approved classification (including SAC where applicable), treatment, rate, inclusive/exclusive price basis, supply/place-of-supply rule, rounding rule and approver.
- Invoice and revenue distribution must consume the same tax calculation snapshot.
- Discounts/offers must be handled according to approved GST treatment and captured actual consideration; tax value must not be guessed from the catalogue/list price.
- Block production issuance when the supplier's legal/tax configuration is incomplete or invalid.
- Determine when tax invoice, bill of supply, receipt voucher, credit/debit note and any e-invoice/IRN are required. Do not call a generic payment receipt a GST invoice.
- GST rates, exemption eligibility, e-invoice thresholds/timelines, registration and withholding must be validated for the actual seller and date before launch.

### 5.3 Common fiscal-document model

Suggested records: legal entities, GST registrations, tax profile versions, fiscal documents, immutable line snapshots, per-issuer/per-document-type/per-financial-year sequence, render asset + hash/template version, original-document relationship for credit/debit notes, e-invoice submission events and delivery/audit log.

Minimum invoice data: legal supplier name/address/GSTIN/state; serial number and issue date; recipient identity/address/GSTIN where applicable; course/service description and classification; quantity/unit value; discount; taxable value; tax treatment/rate and applicable tax components; place of supply where required; total/tax/amount in words; currency; supplier-required authentication; original invoice references for adjustment documents. Include commercial context (course/offer, order reference, payment reference and beneficiary where useful) as traceability fields without exposing unnecessary private student data.

Issued invoice and line snapshots are immutable. Reprints use the same saved versioned snapshot and PDF asset/hash; they do not recompute from today's course, branding or tax settings. Corrections use legally appropriate linked documents, not edit/delete.

## 6. User experience

### Student / parent

- Student: My Learning / My Purchases → Orders & Payments → order detail → View Invoice / Download PDF.
- Parent: Purchases & Invoices; clearly distinguish the purchaser/bill-to customer from the beneficiary learner (parent-self or linked child). Parent should not have to switch into child identity to access the parent's purchase invoice.
- Same server-issued document/API for web and Android. Web opens PDF viewer with download/print; Android opens PDF preview with Save/Share.
- Payment processing/reconciling, payment failed, paid-awaiting-invoice, invoice issued, refunded/adjusted have distinct, truthful states.
- If captured payment callback is lost, reconcile original order; don't ask user to pay again. No final paid invoice before authoritative payment confirmation and fiscal checks.
- Provide accessible email delivery/secure link and resend controls with audit/rate limit. Do not expose another customer's invoices by guessing IDs.

### HRMS

Dedicated Finance & Billing workspace:
- Legal Entities & GST
- Tax Policies
- Marketplace Seller / Offer Setup
- Revenue Distribution
- Sales Invoices / Credit Notes
- Institution Contract Billing
- Payments & Reconciliation
- Payouts / Institution Statements
- Tax Exports / Reports
- Invoice Design Studio

The Studio must have a left-side control panel, a prominent live preview, and explicit preview/approval/publish steps. Preview should use conspicuously non-real example data and watermark PREVIEW; preview generation must not issue an invoice or consume a legal number. Provide desktop, mobile, print/PDF modes; show multi-page layout and long descriptions. Publish stores a new immutable template version; prior issued documents retain the original template version.

Visual direction: premium, calm, smooth high-quality layered surfaces, careful whitespace and typography, restrained orange-led QuantaEdge branding, professional tax tables and clear hierarchy. Maintain contrast, accessible font sizes, printer-friendly light document canvas and separate on-screen chrome. Do not change the approved centralized brand palette.

### Education Admin / institution customer

- Education Admin can manage permitted course offer/pricing/discount operations and view scoped transaction/preview information, but cannot change legal seller, GST registration, tax policy, invoice number or issued document.
- Institution administrators see only their institution's contractual invoices, statement and allowed sales/revenue report. No access to another tenant or the platform's unredacted finance area.
- Supplier invoice to an institution for a commission/revenue-share service, if contractually required, is a different document from the student's purchase invoice and from the platform's institution-contract invoice.

## 7. Authorization and data isolation

- All authoritative calculation, document issuance, number allocation, policy approval and payout state changes run on the backend.
- HRMS needs explicit finance permissions separate from generic HR roles/designations.
- Education Admin permissions are limited to existing education-admin scope; institution admin is tenant-scoped.
- Every order, document, policy, allocation, payout, refund and audit query must be tenant/seller scoped. Verify at least three institutions including an approved cross-institution course assignment.
- Never return Razorpay secrets or other financial secrets to the client. Keep secure file storage private; download endpoints enforce document ownership/role scope.
- Financial ledgers and issued document snapshots must be append-only or database-enforced immutable, with compensating events for correction.

## 8. Technical implementation plan

1. **Checkpoint A — durable spec and source audit** (this document).
2. **Checkpoint B — model foundation:** database migration for seller/tax config, versioned distribution policies/recipients, issued fiscal documents/lines/sequences, allocation snapshots/events, audit and template versions. Use next Flyway version after rechecking `main`.
3. **Checkpoint C — pure deterministic money-calculation service and unit tests:** minor units only; reject tax > captured amount, unsupported states, duplicate institutions and oversubscribed pools; test discount/actual-payment basis, percentage and flat shares, mixed methods, rounding and idempotency.
4. **Checkpoint D — API lifecycle:** HRMS configuration and policy approval; payment-to-invoice/allocation orchestration; secure document lookup/download; reconciliation/refund/credit note flow. Keep seller/tax configuration fail-closed.
5. **Checkpoint E — HRMS interface:** invoice design studio and live PDF preview; legal entity/GST policy; revenue allocation UI; invoices, statements, reconciliation and approval actions.
6. **Checkpoint F — customer UI:** student/parent Orders & Payments and invoice details/download; shared web/Android API and email delivery.
7. **Checkpoint G — QA and release:** clean + upgrade Flyway test; API tests; web/People/Admin builds; Android tests/build; visual PDF inspection; three-tenant isolation; payment provider sandbox; discount and refund scenarios; exact commit Actions status.
8. Update this document at every checkpoint with commit hash, files, tests/results, known blockers and next action. Work directly on `main` in small commits; use unique migration numbers after checking the latest main. Do not state “done” while CI/runtime tests are pending.

## 9. Acceptance checklist

- [ ] Seller identity/tax arrangement approved by finance/CA for each seller model.
- [ ] Legal/tax setup fails closed when missing or invalid.
- [ ] Actual paid amount after discount is persisted and reconciles with captured provider payment.
- [ ] Invoice/tax snapshots and numbering are unique and immutable.
- [ ] Every invoice matches the approved tax calculation; invoice and allocation share the same tax snapshot.
- [ ] Allocations use actual paid amount minus approved tax amount, and never list price.
- [ ] Percentage and flat recipient methods both work; mixed policy cannot allocate above pool.
- [ ] Multi-institution policy is explicitly HRMS-approved and effective-dated.
- [ ] Retries, duplicate callbacks, concurrent issuance and period boundaries do not duplicate documents or allocations.
- [ ] Refunds, partial refunds/chargebacks and credit notes have linked adjustment/reversal records.
- [ ] Student/parent web and Android share consistent authorized invoice access.
- [ ] HRMS preview displays actual proposed template and a generated preview PDF without issuing a fiscal document.
- [ ] Template update leaves previously issued invoice output unchanged.
- [ ] Three-institution positive and negative authorization tests pass.
- [ ] No real payout is made from tests; provider tests use sandbox/test data.
- [ ] Current-main CI and migration upgrade tests pass before production approval.

## 10. Implementation checkpoints

### 2026-10-20 — Initial source audit and specification

- Findings: course Razorpay/payment/order/entitlement model exists; institution contract billing has charge/credit ledger; fiscal document generation and invoice preview were not found in the reviewed source; coupon/offer-code integration still requires a complete targeted audit.
- Next migration identified at initial audit: V149, **recheck before creating**.
- First implementation slice planned: schema foundation plus deterministic revenue-distribution calculator and unit tests. This is not production invoicing/payout authorization.
- The Actions run visible during this checkpoint was run #4858 for commit `13a568745bec09d9e979d37b98be4b938f7462d5` (push; in progress when queried). Do not treat it as the status for later commits; check Actions on the exact latest main SHA.
