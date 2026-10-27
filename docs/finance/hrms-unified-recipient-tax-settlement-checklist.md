# HRMS Unified Recipient, Tax & Settlement Checklist

Scope: improve the existing HRMS Finance and Payouts workspace in place. This is a working implementation checklist, not tax or legal advice.

## Non-negotiable safety constraints
- [ ] Keep the production `main` branch untouched until a reviewed PR passes checks.
- [ ] Make changes on a feature branch from the current `main`; do not merge or deploy as part of this work.
- [ ] Preserve existing finance API routes and response fields unless a compatibility-safe extension is required.
- [ ] Do not trigger real payouts, bank changes, tax remittances, invoice issuance, or live provider calls as part of implementation/testing.
- [ ] Keep bank beneficiary maker-checker approvals, payout batch approval, provider confirmation, legal-seller review, collection/funding account verification and idempotency protections.
- [ ] No hard-coded institution or company gets special treatment. Resolve every recipient from the active institution/legal-entity records.
- [ ] Never invent a tax rate, tax classification, legal seller, liable party or amount due.
- [ ] Show a tax amount as calculated only when supported by an approved rule and an auditable transaction snapshot. Otherwise show **Needs tax review / Not configured**, not zero.
- [ ] Do not present a provider submission response as a completed transfer; completion requires verified provider reconciliation.

## 1. Recipient register and payout overview
- [ ] Include every active institution and eligible active company/platform legal entity, including recipients with no sales, no allocation, zero balance, no bank account, or an incomplete profile.
- [ ] Keep a single recipient row/card per recipient and currency, with stable IDs and no duplicate active-beneficiary join rows.
- [ ] Distinguish zero earnings from missing/stale data and from API failure.
- [ ] Show earnings, refunds, confirmed collection charges, previous payments, reversals, reserves, refund holdback, recovery and net payable.
- [ ] Show transfer readiness from authoritative backend state, with explicit blocking reasons (minimum payout, beneficiary not approved, currency unsupported, legal funding arrangement unverified, reconciliation outstanding, recovery/tax review hold).
- [ ] Add search/filter for recipient, institution/company, currency, eligibility, missing bank, review, pending payout, and recovery due.
- [ ] Use responsive recipient cards/details on mobile; avoid forcing an 11-column horizontal-scroll table as the only workflow.
- [ ] Preserve the existing payout minimum, refund reserve, remainder policy, monthly batch, maker-checker approval and RazorpayX reconciliation paths.

## 2. Transaction/source traceability
- [ ] Show contributing verified course-sale count and a concise source summary.
- [ ] In the recipient ledger, trace each entry to course title, source institution, marketplace institution, order/invoice/payment reference, allocation and the related event.
- [ ] Distinguish source institution, marketplace institution, legal seller, revenue recipient and the account that funds a bank transfer; these are different roles.
- [ ] Show earned, refunded, charges/fee-tax corrections, payout, reversal and recovery events without silently rewriting historical events.
- [ ] Preserve pagination, access checks, read-only behavior and existing API response compatibility.

## 3. Tax identity, classification and tax rules
- [ ] Identify legal seller/supplier and the contractual collection arrangement for each relevant supply.
- [ ] Capture and verify applicable PAN, GSTIN/registration status, legal name, entity type, tax category and effective dates under appropriate permissions.
- [ ] Have a qualified Indian tax adviser approve seller/marketplace treatment, exemptions, place-of-supply handling, GST on sales/platform fees, GST TCS applicability/rate/base, income-tax TDS applicability/rate/base/threshold and any other withholding.
- [ ] Version tax rules by effective date and preserve the approved calculation inputs/rule version on each transaction.
- [ ] Calculate tax per relevant sale/fee/credit note—not by applying a flat rate to a recipient's payout total.
- [ ] Separate customer-facing GST, GST TCS, income-tax TDS, platform-fee tax, recipient income tax (recipient responsibility) and platform tax obligations.
- [ ] If classification, identity, legal seller or rule is unresolved, flag **Needs tax review** and do not automatically withhold, remit, or claim a final tax amount.

## 4. Tax liability ledger and remittance reconciliation
- [ ] Record tax type, base, rate/rule reference, amount, currency, linked sale/fee/credit note, liable/withholding party, recipient tax identity, effective date and evidence.
- [ ] Keep an auditable append-only correction/reversal trail; no silent overwrites.
- [ ] Show calculated, withheld/collected, due, remitted, acknowledged and outstanding balances separately.
- [ ] Keep trust/account funding cash separate from the accounting tax liability and recipient payout liability.
- [ ] Track applicable return/statement period, due date, payment reference, acknowledgement and reconciliation evidence after the legal workflow is confirmed.
- [ ] Handle refunds and credit notes before and after payout with a versioned policy and auditable adjustment.
- [ ] Never make tax remittance live solely because a recipient payout has been approved.

## 5. Finance navigation and usability
- [ ] Reorganize existing HRMS Finance workspace into Overview, Institutions & Companies, Sales & Tax Ledger, Tax & Compliance, Payout Batches & Transfers, and Reconciliation & Exceptions.
- [ ] Reuse existing Finance components, permissions, routes and design system; no disconnected parallel finance UI.
- [ ] Ensure light/dark theme contrast, keyboard operation, accessible status text, loading/empty/error states and mobile/tablet/desktop layouts.
- [ ] Explain why each transfer is eligible or blocked; do not expose full bank account numbers in browser state after submission or in logs.

## 6. Validation and release gates
- [ ] Validate active recipient with zero allocations, one/many allocations, multiple currencies, multiple approved courses and multiple source/marketplace institutions.
- [ ] Validate no bank account, pending/rejected/suspended beneficiary, duplicate/old beneficiary, recovery due, reserve, minimum threshold and unsupported currency.
- [ ] Validate fee corrections, refund before/after payout, reversal, duplicate webhook, out-of-order provider event, failed/pending/confirmed transfer and batch maker-checker separation.
- [ ] Validate tax-reviewed, tax-exempt, tax-applicable, missing PAN/GSTIN, uncertain seller, effective-date change, refund/credit note and already-remitted tax scenarios using synthetic fixtures only.
- [ ] Verify existing finance endpoints and consumers continue to work; add regression tests and migration upgrade/rollback or forward-fix plan.
- [ ] Run targeted API tests, migration checks, HRMS build/lint/type checks and relevant full CI; report any unexecuted runtime/provider tests honestly.
- [ ] Obtain tax/legal approval before enabling any automatic tax calculation, withholding or Government remittance in production.
- [ ] Merge/deploy only after review and green required checks; deployment remains a separate explicit step.

## Implementation status
- [x] Checklist saved in the repository.
- [ ] Complete recipient catalogue in payout balances, including zero-activity entities.
- [ ] Source/marketplace institution attribution in recipient ledger.
- [ ] Consolidated tax-liability model and reviewed rule lifecycle.
- [ ] Redesigned responsive recipient overview and Finance navigation.
- [ ] Automated regression checks and CI verification.
