# Certificate Lifecycle Audit and Repair Workflow

## Objective

Keep assessment certificates, the student certificate page, certificate artifacts, and public verification consistent. Fix the root causes across the lifecycle rather than making a visual-only or single-endpoint patch.

## Required workflow

1. **Audit the source of truth** — start from current `main`; trace assessment authoring/type, attempt ownership, grading, result release, student enrollment, eligibility responses, issuance, persistence, artifact storage, PDF/PNG generation, and retrieval.
2. **Compare both student entry points** — compare the test-result page's per-attempt eligibility result with the certificate page's released-assessment picker. Any difference in SQL filters or state mapping must be intentional and explained. Assessment-pass eligibility must not depend on lesson completion; that is reserved for subject-completion certificates.
3. **Preserve issued assessments in the picker** — if a qualifying assessment already has a certificate, keep it visible with its current certificate state. Do not make an assessment appear to have vanished because a certificate was issued from another screen. An existing issued certificate is idempotently retrievable; a revoked certificate must remain visible as revoked and must not silently be replaced.
4. **Keep the server authoritative** — enforce student ownership, active student/current-class relationship, active subject enrollment, admin-created and non-generated assessment, released result, positive maximum score and configured pass threshold on the API. UI state never authorizes issuance.
5. **Use one canonical certificate record** — one persistent issuance snapshot is the source for PDF/PNG, student/guardian views, certificate number, QR payload, barcode payload and public verification. Retry behavior must not create a duplicate.
6. **Provide a public verification journey** — QR opens the verification page for the same certificate; certificate number can be entered manually; a standard barcode scanner/camera reader can resolve Code 128 to that same number. Public lookup must distinguish VALID, REVOKED and NOT FOUND, avoid leaking assessment/profile details, and never rely on the mere presence of a QR/barcode.
7. **Test before merging** — cover the API contract and SQL filters, result-page issuance, the certificate picker after issuance, artifact retrieval, QR deep-link, barcode decoding, certificate-number lookup, revoked and unknown identifiers, authentication/ownership and existing subject-completion policy. Build web, API and Android when affected.
8. **Merge and re-verify** — merge only after the PR is conflict-free and all required GitHub Actions checks are green. Verify the new main SHA and its checks after merge. Image publication is not proof that a running deployment pulled/restarted the image; state live deployment separately.

## Acceptance matrix

| Scenario | Expected outcome |
| --- | --- |
| Released, passing, manually authored admin assessment; active subject enrollment | Eligible in both the test result and certificate page |
| Assessment passes but published lessons are incomplete | Assessment-pass certificate remains eligible |
| Certificate issued from the test result page | Same assessment remains visible in the picker with its existing certificate state; no duplicate record is created |
| Revoked certificate | Visible as revoked, cannot be silently reissued, public verifier reports REVOKED |
| Unreleased, failing, generated/practice attempt or inactive track | Rejected according to the authoritative API rules |
| Issued certificate PDF and PNG | Subject track is printed for both achievement types; same human-readable certificate number and corresponding QR/Code 128 payload |
| Legacy assessment certificate from before track display | First authenticated view/download creates a corrected artifact revision, archives the prior artifact metadata, and keeps the same certificate number and issue date |
| QR scan | Opens the public verification page and checks current status against the server |
| Code 128 scan or manual certificate number entry | Resolves the same canonical certificate record |
| Unknown or malformed identifier | NOT FOUND with no private learner data disclosed |
| Subject-completion certificate | Still requires all currently published lessons in the active subject track |

## Delivery checklist

- [ ] Root cause written down from code and reproduced in regression tests.
- [ ] Student result screen, certificate picker and API rules agree.
- [ ] Certificate number, QR and barcode converge on one verifier.
- [ ] API/web/Android and regression checks pass.
- [ ] Pull request merged into `main`; merged-main checks are green.
- [ ] Running-environment rollout and live student verification are reported separately from CI.
