# Account deletion: dependency map and fulfillment boundary

## Scope and truth in the UI

The request-intake stage is implemented on branch `feat/account-deletion-lifecycle-20261023`. It does **not** claim that a submitted request has already deleted an account or its personal data.

User Web entry points:
- `/account-deletion` is the externally accessible request page, including sign-in return navigation.
- Account identity/profile settings expose the same request and status panel for signed-in parent and independent learner accounts.
- A parent-managed child cannot submit deletion for the guardian's account or delete the child independently; the child must be managed through a verified guardian.

API surface:
- `GET /api/v1/account/me/deletion-request`: latest request status.
- `POST /api/v1/account/me/deletion-request`: submit a request after typed confirmation.
- `DELETE /api/v1/account/me/deletion-request`: cancel an open request.
- `GET /api/v1/admin/account-deletion-requests?status=REQUESTED`: staff review queue.
- `GET /api/v1/admin/account-deletion-requests/{requestId}`: request and snapshotted child IDs.
- `POST /api/v1/admin/account-deletion-requests/{requestId}/start-review`: move a new request into review.

Requests derive the subject from the authenticated `AuthContext`; the client cannot select an arbitrary user ID. Staff identities cannot use end-user self-service. The API requires an explicit `DELETE` confirmation, snapshots only currently linked **parent-managed** learner public IDs when the guardian explicitly includes all linked children, writes identity-audit events, and does not offer a false “completed” action. A linked learner whose own independent `user_account` is set is deliberately excluded from parent-controlled deletion.

## Confirmed data-dependency blockers

These are examples verified in existing migrations. They are **not yet an exhaustive inventory** of every personal-data field or foreign-key dependency.

### Account/user relationships

- `course_payment_order.purchaser_user_id` and related purchaser/recipient links use restrictive references to `user_account` (V115+).
- `course_entitlement.holder_user_id`, grant/transfer history and historical purchases have restrictive or one-way transfer rules (V115 and V117). A deletion process must preserve purchase facts without accidentally transferring course rights.
- `parent_course_purchase_request.guardian_user_id` is restrictive (V115).
- `billing_customer_address.user_account_id` is restrictive (V166) and stores legal name, GST data, address and contact fields.
- `billing_customer_address_event` is append-only and has restrictive references to both the account and address (V166). Existing invoice/order records contain their own saved billing snapshots; editing or deleting the current address does not erase those snapshots.
- `institution_user_membership.user_id` is restrictive (V131).
- `student_login_credential.guardian_user_id` references the guardian account, with no cascading-delete clause in its creation migration (V37).
- `admin_action_log.admin_user_id` is restrictive (V17); other operational/audit records may intentionally retain an actor reference or immutable event.

### Learner/student relationships

- `student.user_account_id` is restrictive and unique (V101), so deleting a linked account cannot be treated as a simple user-row delete.
- Issued certificates reference the student with `ON DELETE RESTRICT` (V58).
- `guardian_student_audit.student_id` and `guardian_child_creation_request.student_id` are restrictive (V94). They preserve child-lifecycle evidence.
- Purchase orders, course entitlements, entitlement-transfer audits, OTP challenge records and institutional access records also refer to a student (V101, V115, V120+).
- Learning progress and assessment history spans multiple tables with mixed cascade, restrictive and audit-retention semantics; the full graph has not yet been proved in a real migrated database.

### Personal-data surfaces requiring inventory

The account model includes verified login identifiers and profile fields (mobile, contact/verified email, Google subject/profile fields, username, password hash, locale, and profile imagery). Student and guardian-profile tables hold separate names/profile data. Billing snapshots, generated certificates, notification payloads, support/audit details, institutional records and third-party payment/provider records must be checked for copied identifiers or free text.

## Why the first slice does not physically delete rows

A direct `DELETE FROM user_account` or `DELETE FROM student` would be blocked by restrictive references and could destroy or orphan child access, paid entitlements, invoices, certificates, immutable finance records or audit evidence. The schema also does not provide a complete, legally approved field-by-field retention/anonymization policy. Account deactivation by itself would not satisfy the deletion requirement.

Do not add an endpoint that marks a request `COMPLETED` until the following are verified:

1. Produce an exhaustive FK and PII-field inventory from the fully migrated database, including dynamic/runtime-created tables and any provider-owned copies.
2. Assign each data class a documented action: erase, irreversibly anonymize, preserve under a specific lawful retention basis, or request deletion from a processor. Confirm financial/invoice, certificate, institutional and security/audit retention with the responsible business/legal owner.
3. Define the verified guardian-transfer procedure, including target-guardian verification and child sign-in continuity. If a child is included in deletion, record the explicit guardian decision per linked child.
4. Implement a transactional/idempotent fulfillment worker with session/refresh-token revocation, credential invalidation, safe relationship handling, data minimization, per-processor deletion where applicable, retriable status and an audit trail that does not reintroduce deleted PII.
5. Exercise deletion and cancellation against seeded parent-only, parent-with-one-child, parent-with-multiple-children, child-with-more-than-one-guardian, independent learner, learner-with-certificates, and learner/parent-with-purchases scenarios. Verify no child is orphaned and no historical finance fact is rewritten.
6. Review the actual deployed policy/disclosures and timing with the responsible owner before marking production compliance complete.

Reference: [Google Play account deletion requirements](https://support.google.com/googleplay/android-developer/answer/13327111?hl=en).
