# HRMS Unified Recipient, Tax & Settlement Checklist

Scope: improve the existing HRMS Finance and Payouts workspace in place. This is a working implementation checklist, not tax or legal advice.

## Non-negotiable safety constraints
- [x] Keep the production `main` branch untouched until a reviewed PR passes checks.
- [x] Make changes on a feature branch from the current `main`; do not merge or deploy as part of this work.
- [x] Preserve existing finance API routes and response fields; changes here are additive.
- [x] Do not trigger real payouts, bank changes, tax remittances, invoice issuance, or live provider calls as part of implementation/testing.
- [x] Keep bank beneficiary maker-checker approvals, payout batch approval, provider confirmation, legal-seller review, collection/funding account verification and idempotency protections intact.
- [x] No hard-coded institution or company gets special treatment. Resolve every recipient from the active institution/legal-entity records.
- [x] Never invent a tax rate, tax classification, legal seller, liable party or amount due.
- [x] Show tax totals only where existing verified-sale/credit-note facts support them. TCS/TDS explicitly say **Not represented**, not ₹0; no automatic withholding is enabled.
- [x] Do not present a provider submission response as a completed transfer; completion requires verified provider reconciliation.

## 1. Recipient register and payout overview
- [x] Include every active institution and eligible active company/platform legal entity, including recipients with no sales, no allocation, zero balance, no bank account, or an incomplete profile.
- [x] Keep a single recipient card per recipient and currency; the backend chooses one current beneficiary row to avoid duplicate joins.
- [x] Distinguish zero earnings from missing/stale data and from API failure.
- [ ] Show earnings, refunds, confirmed collection charges, previous payments, reversals, reserves, refund holdback, recovery and net payable.
- [x] Show backend-derived readiness and reasons for recovery, no positive balance, beneficiary status, minimum payout, unsupported currency, missing fee reconciliation, provider setup and legal funding verification. Statutory TCS/TDS is explicitly called out separately; it is not guessed.
- [ ] Add search/filter for recipient, institution/company, currency, eligibility, missing bank, review, pending payout, and recovery due.
- [x] Use responsive recipient cards/details on mobile; avoid forcing an 11-column horizontal-scroll table as the only workflow.
- [x] Preserve the existing payout minimum, refund reserve, remainder policy, monthly batch, maker-checker approval and RazorpayX reconciliation paths.

## 2. Transaction/source traceability
- [x] Show contributing verified course-sale count; detailed source data is available in the existing paginated recipient ledger.
- [ ] In the recipient ledger, trace each entry to course title, source institution, marketplace institution, order/payment reference, allocation and the related event. Invoice-number linkage is still outstanding.
- [ ] Distinguish source institution, marketplace institution, legal seller, revenue recipient and the account that funds a bank transfer; these are different roles.
- [ ] Show earned, refunded, charges/fee-tax corrections, payout, reversal and recovery events without silently rewriting historical events.
- [x] Preserve pagination, access checks, read-only behavior and existing API response compatibility.

## 3. Tax identity, classification and tax rules
- [ ] Identify legal seller/supplier and the contractual collection arrangement for each relevant supply.
- [ ] Capture and verify applicable PAN, GSTIN/registration status, legal name, entity type, tax category and effective dates under appropriate permissions.
- [ ] Have a qualified Indian tax adviser approve seller/marketplace treatment, exemptions, place-of-supply handling, GST on sales/platform fees, GST TCS applicability/rate/base, income-tax TDS applicability/rate/base/threshold and any other withholding.
- [ ] Version tax rules by effective date and preserve the approved calculation inputs/rule version on each transaction. Existing GST course tax profiles are reused; statutory TCS/TDS rule and liability snapshots still require implementation and adviser approval.
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
- [x] Reuse existing Payouts workspace, ledger dialog, Finance & Billing route and theme tokens; no disconnected finance UI added.
- [ ] Verify light/dark theme contrast, keyboard operation, accessible status text, loading/empty/error states and mobile/tablet/desktop layouts in browsers and CI.
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
- [x] Complete recipient catalogue in payout balances, including zero-activity entities.
- [x] Source/marketplace institution attribution in recipient ledger.
- [ ] Consolidated tax-liability model and reviewed rule lifecycle. Implemented so far: read-only recorded output-tax and processed credit-note tax snapshot plus explicit TCS/TDS coverage gaps. Automated withholding/remittance remains disabled.
- [x] Redesigned responsive recipient overview. Existing Finance and Tax tabs/routes are reused; no risky navigation migration was necessary for this slice.
- [ ] Automated regression checks and CI verification (pending required CI and runtime verification).
