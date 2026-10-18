# User–Institution–Course Access Remediation Tracker

**Status:** OPEN — source findings confirmed; implementation and CI acceptance have not yet been completed.  
**Parent epic:** [#71 — Institution-first multi-tenant implementation](https://github.com/Manav326/QuantaEdge/issues/71)  
**Implementation task:** [#75 — Close user–institution–course scope gaps across learner APIs](https://github.com/Manav326/QuantaEdge/issues/75)  
**Related course product work:** [#70 — Class 6–8 ordinary courses](https://github.com/Manav326/QuantaEdge/issues/70)

## Non-negotiable invariant

For every protected learner operation, derive one validated institution context, establish that the signed-in learner or parent-selected child belongs to that institution, and authorize the requested course against that same institution. Carry the same context through SQL, service calls, state changes, history, purchase/entitlement and session resumption. A tenant header, URL, branding cache, global curriculum row, class/subject URL or legacy grant without matching tenant provenance is never authorization.

**Consumer policy must remain explicit:** do not auto-assign new users to `QUANTA-EDGE` to hide missing access. Preserve approved direct-consumer access through an intentional, tested path. Legacy data is reconciled only by reviewed provenance.

## Locked platform and institutional operating model

- **HRMS is the central platform owner.** HRMS provisions institutions, controls the institution network, and centrally manages integration credentials, provider configuration, allowed web origins/redirect URIs, payment setup, and operational readiness.
- **QuantaEdge is a normal institution in that network.** It is also the destination institution for pre-tenancy/global legacy users and their preserved learning/payment history, so accounts and records are not orphaned.
- **New signup is institution-scoped at creation.** Signup on the QuantaEdge platform entry point creates a QuantaEdge institution membership; signup through an institution-branded route/domain creates membership in that institution. Existing sign-in never silently creates or changes membership.
- **OAuth/payment credentials are HRMS-managed platform configuration, not per-school onboarding prerequisites.** Institutions do not need to supply their own Google client secrets, Razorpay credentials, or other integration secrets. HRMS operates centrally configured OAuth and payment integrations; application records, offers, orders, webhooks, receipts, and entitlements remain bound to the exact institution.
- **No global learner bucket and no tenant secret burden.** Every new user/account/learner profile must have the resolved institution relationship before the signup transaction commits. A child inherits the selected institution only through parent membership or validated institution join flow.
- **Routing must be trustworthy and usable.** Root QuantaEdge web/Android entry points resolve to `QUANTA-EDGE`; school routes/tenant-branded builds resolve to their configured institution. Unknown or conflicting tenant context fails clearly instead of assigning the user to the wrong school.
- **Migration intent is affirmative, not a fallback for new users.** Reviewed legacy/global rows are mapped to QuantaEdge to preserve access and history. This one-time mapping must never be repeated as request-time auto-enrollment.

## Tracklist

### 0 — Baseline, route inventory and contract
- [ ] 0.1 Inventory every learner/parent/child endpoint and classify public/auth, consumer, tenant learner, tenant admin and HRMS owner routes.
- [ ] 0.2 Define a shared server-side authorization service for principal + institution + target learner + course.
- [ ] 0.3 Decide/document direct-consumer onboarding and access policy before any default-membership change.
- [ ] 0.4 Capture baseline failures and relevant current `main` SHA; keep source audit findings separate from any unverified production-data assumptions.

### 1 — Central context and course authorization
- [ ] 1.1 Keep central context selection fail-closed for tenant-required endpoints and close gaps in legacy route-family coverage.
- [ ] 1.2 Ensure each protected controller passes exact `institutionId` into its services; prefix registration alone is not sufficient.
- [ ] 1.3 Replace "any active membership" and tenant-blind purchase/grant checks with exact-institution assignment, offer, entitlement or legacy-grant checks.
- [ ] 1.4 Validate parent-to-child access and the child's active membership in the same selected institution on every target-child operation.
- [ ] 1.5 Preserve intentional public auth/join/branding, institution-admin session and HRMS-owner exceptions.

### 2 — Legacy learning APIs and histories
- [ ] 2.1 `LearningController`: course mapping, legacy class routes, lesson listing/detail/start, questions/answers and authorization.
- [ ] 2.2 `LearningStateService`: eliminate tenant-protected HTTP use of unscoped compatibility overloads; scope progress, attempts, mastery and practice sync.
- [ ] 2.3 `StudentController` and `LearningMetricsService`: scope course enrollment and all progress/metrics/history reads.
- [ ] 2.4 `RecommendationController`: use selected institution, course scope and tenant-scoped history.
- [ ] 2.5 `PracticeController` and `DiagnosticController`: scope catalog, creation, resume, answer, submission, locking and idempotency.
- [ ] 2.6 `TutorService` / tutor conversation: scope session create/reuse/ownership, lesson access, messages and usage quotas.
- [ ] 2.7 `AssessmentController`: scope catalogue, eligibility, create/resume, answer/update/submit, results and attempt uniqueness by institution.
- [ ] 2.8 `CertificateController`: scope eligibility and issuer/history/detail/view/download to the approved product policy.
- [ ] 2.9 Inspect structured lessons, content resources/PDFs, live/recorded classes, notifications and any other route discovered by the inventory.

### 3 — Database integrity and historical data
- [ ] 3.1 Inspect deployed migration versions and affected schema before proposing forward-only migrations; never edit released migrations.
- [ ] 3.2 Add constraints/guards or service invariants so new tenant-owned learner history cannot be created with a null institution.
- [ ] 3.3 Produce read-only diagnostic queries for null-scoped histories/enrollments, stale grants, assignment/offer mismatches and guardian/membership inconsistencies.
- [ ] 3.4 Reconcile rows only with explicit provenance and reviewed rules; do not infer a historical institution solely from current membership.
- [ ] 3.5 Test both clean install and upgraded database paths, retaining financial and learning history.

### 4 — Regression coverage
- [ ] 4.1 Institution A vs B negative tests for list/read/write, using shared course/lesson/question IDs and tampered tenant/user/course/session IDs.
- [ ] 4.2 User cases: single membership, multiple memberships, no/inactive membership, independent learner, parent of children in different institutions, revoked/no-consent link and child-target tampering.
- [ ] 4.3 Course cases: assignment without publication, draft/unpublished offer, revoked entitlement, direct purchase, valid matching legacy grant and non-matching legacy grant.
- [ ] 4.4 Prove progress, answers, mastery, practice, diagnostics, tutor, assessment attempts/results and certificates cannot leak or resume across institutions.
- [ ] 4.5 Prove missing/unknown/inactive/forged context fails closed without breaking intended auth/join/branding or admin workspaces.
- [ ] 4.6 Prove every new tenant-owned history insert writes the exact institution ID; known legacy null rows remain inert until approved reconciliation.

### 6 — HRMS-managed signup and integration onboarding
- [ ] 6.1 Bind Google signup, phone OTP signup, legacy parent signup, and every other account-creation route to the resolved institution in the creation transaction.
- [ ] 6.2 Bind OTP challenges to the signup institution; reject cross-institution verification and make existing-account login verify membership without auto-enrolling.
- [ ] 6.3 Ensure root QuantaEdge URL and the general Android app resolve to `QUANTA-EDGE`; test white-label route/domain resolution and reject unknown/conflicting tenant context.
- [ ] 6.4 Provision Google OAuth client configuration centrally through HRMS-managed deployment secrets/configuration, with every supported tenant origin/redirect registered by the platform operator; no tenant-supplied OAuth secrets.
- [ ] 6.5 Keep Razorpay credentials centrally managed by HRMS. Prove each order, signature verification, webhook, purchase, receipt/branding, refund path and entitlement uses the order's persisted institution ID, not the caller's header.
- [ ] 6.6 Provide an auditable HRMS onboarding/readiness checklist for OAuth, payment provider, domains/origins, branding, course offer, and test transaction before activating a new institution.
- [ ] 6.7 Run signup/login and payment regression tests for QuantaEdge + at least two tenant institutions, including cross-tenant account reuse, cancelled/failed checkout, webhook retry and missing central configuration.

### 5 — Build, CI and release acceptance
- [ ] 5.1 Focused API tests pass, followed by complete API verification.
- [ ] 5.2 Web, Admin and People Operations builds/static checks pass.
- [ ] 5.3 Android unit tests, debug build and relevant cross-app/API contract checks pass.
- [ ] 5.4 Clean and upgraded database migration/integration tests pass.
- [ ] 5.5 Review full GitHub Actions matrix on the exact latest `main` SHA. Fix each failure at its root; do not weaken tests or accept partial green.
- [ ] 5.6 Record final `main` SHA, workflow run URLs/results, migration outcomes, and tested tenant matrix below before closing #75/#71.

## Evidence log

| Checkpoint | SHA / run URL | Result / notes |
|---|---|---|
| Baseline source audit | Source review only | Confirmed code-level scope gaps; production row counts not yet queried |
| Focused API tests | Pending | |
| Full API verification | Pending | |
| Web/Admin/People checks | Pending | |
| Android tests/build | Pending | |
| Clean DB migration/integration | Pending | |
| Upgraded DB migration/integration | Pending | |
| Final exact-`main` Actions matrix | Pending | |

## Definition of done

- [ ] No tenant-protected endpoint silently falls back to a global/legacy learner path.
- [ ] Every institution-specific authorization decision uses the exact institution in the request.
- [ ] New tenant-owned learning history is written and read under the same tenant scope.
- [ ] Parent-child and normal/independent-user flows are explicit and regression-tested.
- [ ] Legacy records are retained and reconciled only through explicit provenance.
- [ ] Required API, database, Web, Admin, People and Android checks pass.
- [ ] All required GitHub Actions are green on the exact latest `main` SHA and evidence is recorded above.
