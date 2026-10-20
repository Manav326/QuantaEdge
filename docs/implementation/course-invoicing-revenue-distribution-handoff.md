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


### 2026-10-20 — First backend calculation checkpoint

Commits on `main`:
- `e5e9055e53d25b76b3721833b3e70e98ed9adcae` — initial durable spec/source audit.
- `a6a625614dc96c8d727812b96de3d4fbde8cfd02` — V149 financial-document and revenue-share foundation.
- `d8154c4e62e1be328c61e47cbd0b8210f3bcfc8e` — schema integrity correction: store total institutional allocation per pool and ensure allocation recipient/policy/institution IDs match.
- `24f9cf65d1fa867c04d12c33b5dcc8ac7deb63ff` — deterministic distribution calculator.
- `989eaa3801df8e269148555a72777db7bb1b5db3` — unit tests for mixed allocation rules.

Changes present in this slice:
- Added initial tables for legal entities/GST registrations, versioned course tax profiles, invoice template versions, fiscal document numbering/documents/line snapshots/assets/events, revenue-share policy recipients/allocation ledger/events and financial audit log.
- Added database triggers rejecting update/delete for issued fiscal documents, document lines/assets/events, allocation facts/events and financial audit entries.
- Added pure integer-minor-unit distribution math: requires explicit verified-captured confirmation, uses actual paid amount minus tax, supports percentage and flat shares, applies HALF_UP basis-point rounding, and rejects duplicate institutions or shares exceeding the pool.
- No fiscal document issuance endpoint, tax-policy API, invoice renderer/download endpoint, coupon integration, payout integration or HRMS screen is claimed complete yet.

Verification at this checkpoint:
- Source writes succeeded on `main`.
- GitHub Actions run #4863 for `989eaa3801df8e269148555a72777db7bb1b5db3` was pending when checked; check Actions again after this checkpoint.
- Unit tests have been committed but not independently executed in this chat environment.
- Still required next: tax calculation with discount-inclusive/exclusive prices; HRMS-authorized APIs and UI for legal entity/tax/policy/template configuration; live invoice preview; then payment-to-invoice/allocation integration and secure user/parent downloads.


### 2026-10-20 — HRMS configuration and live-design-preview checkpoint

- Added `CourseTaxCalculator` and unit tests. It calculates from an approved treatment/rate input, applies an eligible up-front discount before the tax base, supports inclusive/exclusive pricing and rejects a mismatch between computed customer payable and the server-verified paid amount. It does not choose a legal rate or determine exemption eligibility.
- Added `PlatformFinanceController` under `/api/v1/hrms/platform/finance`; every route requires `QE_PLATFORM_OWNER` in HRMS. It covers legal-entity drafts/activation, GST registration review, offer tax-profile draft/submit/approval, percentage/flat policy drafts/recipient management/submission/approval, template draft/version/publish, read-only document listing/detail and overview.
- Added a People Operations Finance & Billing workspace with legal entity/GST forms, tax-profile review, mixed percentage/flat revenue-share policy editor and a premium live invoice-template preview. The preview has an explicit sample/not-issued watermark and does not fabricate a GSTIN, tax rate or real invoice number. Published designs use a versioned schema and can start a new draft version.
- Added navigation to Finance & Billing under the HRMS platform-owner section.
- Added follow-up migration V150 for the tax-profile version uniqueness and to make platform-level audit actions representable while requiring an actor on every financial audit record.
- Still not complete: real Razorpay-to-invoice/allocation orchestration, invoice PDF renderer/private download service, student/parent Orders & Payments screens, coupon integration audit, actual payout integration, provider/e-invoice integration, and runtime/integration verification.
- CI status for the exact commit must be checked after this update; earlier commits triggered independent runs and some were cancelled as newer main commits were pushed. Do not treat cancellation as a product test failure or as successful verification.


### 2026-10-20 — CI-driven corrections

- Run #4865 for commit `5a2176238e1061e68c8ce48fcd2e00ddfd8709e2` failed its central localization parity check because the canonical catalogs under `localization/catalogs` had not been updated before editing the People app copies. The canonical English/Hinglish catalogs and corresponding web/Admin/People generated copies are synchronized in this checkpoint.
- Fixed the revenue-share policy submission race: recipients are now saved and awaited before the policy is submitted. The save-only button retains its own async flow.
- Tightened GST registration consistency (the GSTIN prefix must match the supplied state code) and added explicit duplicate-recipient validation before insert, so duplicates return a controlled validation error rather than a database conflict.
- Imported `CSSProperties` as a type. Re-check People Operations build, API verification and localization parity on the new exact commit.
- Tax calculation and HRMS financial configuration remain foundations: actual Razorpay settlement-to-invoice/allocation, PDF issuance/download and payouts are not complete.


### 2026-10-20 — Server-rendered PDF preview and policy validation

- Added an HRMS-only `POST /api/v1/hrms/platform/finance/invoice-template-preview.pdf` route. It returns an inline PDF sample from the current unsaved design definition; preview rendering never creates a fiscal document, allocates a legal invoice number or writes a financial record.
- Added `InvoicePreviewPdfRenderer` and unit tests checking PDF structure/page geometry and safe design fallbacks. The preview is prominently marked PREVIEW / NOT A TAX INVOICE and uses sample placeholders.
- Added an “Open PDF preview” action to HRMS Invoice Design Studio and an embedded PDF viewer with a download-preview action. The HTML preview remains live for unsaved changes; the PDF is rendered from the same current design payload.
- Converted effective policy/tax-profile timestamps into JDBC-supported UTC `OffsetDateTime` values before PostgreSQL queries.
- Policy approval now rejects combined percentage recipients exceeding 100% before activation. Flat amounts remain subject to the actual captured sale's distributable-pool validation.
- This remains preview/design functionality, not a production-issued invoice. Next: verify the current exact-commit CI; then connect captured payment + approved tax snapshot + allocation + immutable fiscal-document issuance and secure downloads.


### 2026-10-20 — Follow-up build correction

- Actions run #4866 (`57bbfd8271a2c887bd5f4737a3c9a6e75291f1ed`) verified API, web, Admin, Android and content-import safeguards successfully, but the People Operations build failed TypeScript at `app/finance/page.tsx`: the Issued Documents table referenced `source_type` without declaring it on the `FinancialDocument` type. This field is returned by the controller and is now included in that client type.
- The PDF renderer and preview endpoint are in commit `015ad5a8c578eb3437d6bde9d9710f6ebc0c6e23`; run #4867 is the first CI verification for that exact commit and must be checked after the UI type correction.


### 2026-10-20 — Policy and JDBC hardening

- Corrected the remaining tax-profile INSERT to bind UTC `OffsetDateTime` values rather than `Instant`, matching PostgreSQL JDBC's supported timestamp-with-time-zone type.
- Percentage recipients are now validated at draft-save time as well as approval time: total percentage must be at most 100%. Mixed flat amounts can still exceed a small actual pool, so the deterministic sale allocation must validate the real pool before any allocation is posted.
- Customer billing profiles and per-order locked billing/tax/discount snapshots are the next model slice; production sale issuance is not yet wired to these foundations.


### 2026-10-20 — Purchaser billing identity snapshot

- Added `BillingCustomerProfileController`: authenticated student and parent routes retrieve/update only the caller's billing profile. It validates business billing address completeness, email/phone format, GSTIN length and GST state-prefix consistency; event history stores changed field names, not the submitted PII.
- Added validation tests for individual and business profiles, GSTIN/state mismatch, incomplete business address, invalid GSTIN and invalid email.
- Course-order creation now persists the purchaser's billing profile snapshot and beneficiary name together with list-price and zero-discount values. Later profile edits cannot rewrite an already-verified order snapshot (database guard from V151).
- No customer-facing invoice issuance is enabled yet: the course order still needs an approved tax-profile snapshot, legal document selection/numbering, immutable fiscal document + line creation and private PDF storage. Coupons/offers integration remains to be audited/implemented before claiming discounted checkouts work.


### 2026-10-20 — Student/parent billing profile UI

- Added a reusable `BillingProfileEditor` to student and parent course marketplace pages. It loads the authenticated purchaser's server-side profile and lets them save individual/business billing name, optional GSTIN/state code, postal address, billing email and phone.
- The parent editor uses the parent workspace endpoint and keeps purchaser identity separate from the child beneficiary; the student editor uses the student workspace endpoint. English/Hinglish labels are supported on the parent screen.
- Profile-save errors remain in the editor and do not block browsing the marketplace. Existing orders retain their saved buyer snapshot; profile changes apply to future order snapshots.
- This UI does not yet apply a tax calculation or issue invoices; those integration steps remain controlled by the approved seller/tax configuration.


### 2026-10-20 — Template review checkpoint

- Migration V152 adds a dedicated template-review ledger and immutable submitted/approved event records.
- HRMS template actions are separate: submit, approve, then publish. The backend checks that the template design still matches the submitted snapshot before approval and publication.
- Seller setup now requires a complete registered address. Tax-profile seller selection is limited to the platform or the institution that owns the course offer.
- Remaining major work: attach approved tax snapshots to course orders, reconcile captured payment amount, issue and render invoices, persist revenue-share allocation snapshots, add secure customer/parent invoice downloads, audit discounts, and implement refund/credit-note adjustments.


### 2026-10-20 — Captured-sale finalization and customer invoice downloads

- Added V153 to persist the approved revenue-share policy and invoice series on each pending course order, and to extend the verified-order immutability guard to the new financial facts.
- Course checkout now requires an effective approved tax profile, legal seller, published invoice template and a single effective approved revenue-share policy. It computes the payable amount before creating the Razorpay order, and persists the tax/buyer/template/share snapshots against that order.
- Course payment verification now calls the financial finalizer only after verifying Razorpay signature, exact order, amount, currency and captured status. In the same database transaction it posts immutable invoice and revenue-share allocation rows and then marks the order verified with captured amount, timestamp and fiscal-document link.
- Added an actual A4 PDF renderer and private artifact storage for issued tax invoices/bills of supply. Issuance records document numbering, line/tax snapshots, PDF hash/size and issue/render events. Invoice sequence is legal-entity and financial-year scoped.
- Added purchaser-scoped student/parent invoice list and download endpoints. Downloads verify order ownership and tenant scope, hash-check the private PDF, and append a download-audit event. Student Marketplace and Parent Courses now include an “Invoices & payments” section with a PDF download action.
- Discount amount is currently zero because a coupon/offer-code pricing engine has not been found or integrated into this purchase path. The schema and invoice math are prepared to snapshot discounts, but offers must not be represented as working until the coupon source is audited and wired into the quote flow.
- Next: check the exact latest Actions results and fix compilation/migration issues; add tests for the complete financial snapshot/finalization contract; then surface a download link in HRMS Finance & Billing, implement adjustment/credit-note reversals and exercise the full flow against a test Razorpay order and three institution tenants.


### 2026-10-20 — Invoice issue/download controls

- HRMS Finance & Billing exposes the legal invoice-series prefix per seller, audit-logs series changes, and provides an authenticated PDF download action in Issued Documents.
- Student Marketplace and Parent Courses display verified invoice history and download PDFs through the same-origin API client, preserving tenant/workspace context. The API enforces purchaser ownership and records every download.
- The invoice renderer now uses the course title and institution branding stored at checkout rather than looking those values up again at issue time. A sale's descriptive text therefore stays anchored to the checkout snapshot.
- Recent Actions identified a missing Spring HttpHeaders constant on the new download endpoint; this was corrected to the literal standard header name. Run the latest CI before treating the finance flow as build-verified.


### 2026-10-20 — GST serial-length compliance

- Updated the printed invoice serial to use a compact financial year (for example `QE/26-27/000001`) while keeping the full financial year internally for sequence partitioning.
- HRMS restricts invoice series prefixes to 1–3 characters, the API guards the final serial length, and migration V154 adds a database check limiting new invoice numbers to 16 characters. A sequence stops safely at 999,999 for each series/year rather than emitting an overlength serial.
- This aligns the serial length with the current CBIC GST Rule 46 text. Tax rates, supply classification, place-of-supply decisions and applicability still require approval by the institution/platform's tax adviser before live issuance.


### 2026-10-20 — E-invoice applicability fail-closed gate

- Added V156 to store the reviewer, review timestamp and rationale for the HRMS legal-seller e-invoice applicability decision, with a database check that prevents a reviewed status without its evidence.
- HRMS Finance & Billing now has a per-seller applicability control (NOT_REQUIRED or REQUIRED) and requires a review rationale/reference. The API records the decision in the existing append-only financial audit log. This is an HRMS human attestation, not a GST-portal validation or a substitute for the seller's professional tax advice.
- Course checkout now fails closed if the decision has not been reviewed. A seller marked REQUIRED cannot create new paid checkouts until IRP/IRN submission and signed-QR verification are implemented and enabled. NOT_REQUIRED only clears this single gate; active seller, tax profile, GST registration where required, published invoice template and revenue-share policy gates still apply.
- Added targeted service tests for unreviewed sellers, sellers marked REQUIRED, and pass-through behavior for a reviewed NOT_REQUIRED seller.
- The next implementation priorities are: (1) audit and wire coupon/offer pricing into the server quote, tax base and share calculation; (2) implement idempotent full/partial refund and credit/debit-note adjustment events tied to the original document/allocation; (3) exercise captured sale/retry/refund across at least three institution tenants, including parent purchases; and (4) verify Actions on the exact current main SHA before release.
- Release remains **NOT READY** until end-to-end provider, refund, coupon, multi-tenant and statutory review tests pass.
