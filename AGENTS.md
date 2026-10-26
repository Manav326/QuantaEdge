# Mandatory agent instructions

Before changing course creation, content versioning, release/assignment, Marketplace offers, learner access, purchase checkout, payment verification, enrollment, parent checkout, certificates, or course progress, read:

- `docs/product/entity-identity-contract.md`
- `docs/product/course-identity-and-commerce-contract.md`

These are implementation contracts, not suggestions. If existing code conflicts with them, do not extend the conflicting pattern; update all affected paths and their database constraints coherently.

## Non-negotiable course identity rules

1. **Logical course identity:** `institution_course_identity.id` is the canonical internal identity; `institution_course_identity.public_id` is its stable external identifier. Both survive content upgrades.
2. **Never use a version as a product identity:** `curriculum_program.id`, `curriculum_program.public_id`, `curriculum_program.code`, `content_version_no`, release IDs/version numbers, assignment IDs, and Marketplace offer IDs identify a content graph or workflow/listing revision, not the logical course.
3. **Never infer identity from labels:** course name, class name, display name, title, translated text, price, or a code without its defined source-institution scope must never be used to merge or deduplicate courses.
4. **Purchases are tenant-scoped and identity-scoped:** prevent duplicate purchases for the same logical course and learner within the same institution, irrespective of content version or offer revision. Keep different `course_identity_id` values separate, even when names or prices match. Institution ownership/billing boundaries must not be crossed.
5. **A content upgrade is not a new billable product:** it must not cause a second charge, erase enrollment, or discard the old payment record. Link access to the stable course identity; preserve each transaction's original offer and financial snapshots.
6. **Offer identity still matters for money:** every order keeps its exact `institution_marketplace_offer.id`, source course/version, provider order, price/currency, tax, revenue-share, discount, and buyer snapshots. Do not rewrite historical orders to a newer offer to bypass a mismatch.
7. **Resolve pending payments before replacement:** query the payment provider for the exact saved provider order/amount/currency. Paid, authorised, processing, or ambiguous states must block a new charge and be reconciled. Only a conclusively unpaid/retryable old order may be expired before a fresh checkout.
8. **Fail closed on ambiguous legacy data:** backfill a stable course identity only from an explicit FK/identity relationship. Never match legacy rows by display name, and never delete or silently rewrite payments, ownership, enrollment, certificates, attempts, or progress to make a uniqueness rule pass.
9. **Database enforcement is mandatory:** application checks alone are insufficient for identity, tenant scope, open checkouts and duplicate ownership. Use FK/unique/check/trigger enforcement where feasible, and keep the API's validation aligned with it.
10. **Change all paths, not only the screen that exposed the bug:** student and parent catalogues, paid/free enrollment, checkout/order recovery, verification/reconciliation, entitlement/transfer, course selection and analytics must agree on the same identity semantics.

## Required review before calling a course change complete

Verify these cases for K12 and assessment products:

- two content versions of the same identity: purchase once, access latest eligible version without another charge;
- two different identity UUIDs with the same display title: independent courses and purchases;
- same identity in two institution tenants: tenant-scoped access/billing remains isolated;
- student, parent purchasing for self, and parent purchasing for a child;
- concurrent clicks/retries and an old pending order tied to an archived offer;
- exact old offer paid, unpaid/retryable, processing, provider unavailable and mismatched amount/currency;
- historical purchases, certificates, tests, progress and invoices remain auditable.

Do not declare the work complete based only on a happy-path UI check. Verify schema/migration behavior, API order and access paths, and CI/build results. Do not make production database edits while changing code; use a reviewed forward migration.
