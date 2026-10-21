# Course/Batch Identity, Test IDs, and Account Lifecycle — Implementation Plan

Status: decisions locked; implementation backlog
Target branch: `main`
Compatibility requirement: additive-only; preserve existing API contracts and historical references.

## Locked decisions

- Every school class and every independent batch has an immutable public identifier exactly 8 characters long: a registered 3-letter prefix plus a 5-character alphanumeric suffix.
- Prefix registry starts with `SCH` for school classes and `NAV` for Navodaya batches. Future batch types must register an unused appropriate 3-letter prefix. The prefix identifies record type, not the display name or class level.
- Existing numeric primary keys remain unchanged. Public codes are additional identifiers. Renaming a display name never changes the public code.
- Admin-created test IDs use `<COURSE_OR_BATCH_PUBLIC_ID>-<SEQUENCE>`, e.g. `NAV-A7K2Q-12`. Sequence is monotonically increasing per course, allocated transactionally, and never reused after archive/deletion.
- An eligible student older than 18 may transition to an independent normal-user account while retaining the same user identity, learner profile, enrolments, learning history, attempts, results, and certificates.
- A normal user may become a parent only after verified adult status and required parent/guardian eligibility checks.
- The reverse parent-to-student/normal-user role transition is prohibited. Account-type changes cannot grant permissions through a client-only update.
- Keep parent, purchaser/account holder, and learner as separate concepts. Keep course identity, source curriculum level, enrolment, entitlement, and progress distinct.
- All changes are additive. Existing identifiers and API response fields remain supported; no destructive migration or forced identity recreation.

## Phase 0 — Baseline and compatibility inventory

- [ ] Record current `main` SHA and required GitHub Actions baseline.
- [ ] Map schema and usages of `curriculum_class`, `curriculum_program`, `curriculum_subject`, `student_program_enrollment`, `assessment`, `student`, `parent`, account/session identity, guardian links, certificates, and assessment attempts.
- [ ] Trace assessment creation, listing, publishing, attempts, results, offline attempts, and certificate issuance.
- [ ] Inventory existing external IDs and API payload contracts.
- [ ] Identify test fixtures and all consumers before adding columns or constraints.

## Phase 1 — Public identity registry (P0)

- [ ] Add a prefix registry with uniqueness and format validation for exactly three uppercase letters.
- [ ] Add an additive public-code field to classes and batches; retain existing numeric primary keys and foreign keys.
- [ ] Backfill every existing class and batch with collision-checked public codes. Use `SCH` for school classes and `NAV` for the Navodaya batch; preserve the current row IDs and relationships.
- [ ] Ensure global public-code uniqueness across class and batch records, not merely uniqueness within each table.
- [ ] Generate codes server-side using a collision-safe allocator. Reject malformed or client-chosen collisions.
- [ ] Expose public codes additively in APIs and admin UI; continue accepting existing numeric IDs and existing code fields.
- [ ] Add tests for backfill, uniqueness, exact length/character set, collisions, rename stability, and old API compatibility.

## Phase 2 — Course/batch scope and enrolment (P0)

- [ ] Keep one shared content and assessment engine for classes and all other batches.
- [ ] Keep subject `class_id` as source curriculum metadata only for independently scoped batches; do not treat it as learner eligibility by default.
- [ ] Model learner, guardian, course/batch, subject, enrolment, entitlement, and source curriculum level independently.
- [ ] Extend catalogue and enrolment flows for three entry paths: parent assigns to a linked child, student enrols self, direct learner enrols their own account.
- [ ] Enforce the acting account's permissions and the target learner's identity server-side.
- [ ] Preserve existing parent-created student credentials and parent read/reporting behaviour.
- [ ] Clearly distinguish discoverable, enrolable, entitled, content-ready, and assessment-ready states.
- [ ] Add end-to-end tests for each entry path and multiple concurrent enrolments.

## Phase 3 — Course-scoped test identifiers (P0)

- [ ] Add an additive test public-ID field and a per-course sequence allocator/table; keep `assessment.id` and all attempt foreign keys unchanged.
- [ ] Determine course scope from the selected subject's existing stable relationship, not from display names.
- [ ] Allocate sequence numbers inside the same transaction as test creation, using a row lock or atomic upsert to prevent races.
- [ ] Enforce uniqueness of generated test IDs and never reuse an allocated sequence after archive/deletion.
- [ ] Preserve existing tests and their current identifiers; only newly created tests receive the new formatted ID unless an explicit, safe compatibility mapping is approved.
- [ ] Return the new ID additively in admin/student API payloads and show it in relevant screens.
- [ ] Add tests for first and subsequent tests, separate course counters, concurrent requests, rollback, archive/delete gaps, and legacy assessment compatibility.

## Phase 4 — Account lifecycle (P0, security-sensitive)

- [ ] Inspect existing user/student/parent identity and session schema before deciding where lifecycle state belongs.
- [ ] Add an explicit, auditable account-transition record/state without replacing existing role fields or login identifiers.
- [ ] Implement student-to-normal-user transition only when the verified age is strictly greater than 18.
- [ ] Preserve the same user identity, learner profile, enrolments, progress, attempts, results, certificates, and audit history.
- [ ] Define and test guardian-link/access handling before transition; do not silently delete or transfer a child relationship.
- [ ] Permit normal-user-to-parent transition only after verified adult status and required guardian/parent eligibility checks.
- [ ] Reject parent-to-student/normal-user reverse transitions at the API/service layer; never rely on hiding a UI action.
- [ ] Revoke or refresh sessions as required when permissions change; audit actor, time, prior state, new state, and verification reference.
- [ ] Add positive and negative authorization tests, including direct API bypass attempts and historical-data preservation.

## Phase 5 — Full end-to-end regression (P0)

- [ ] Verify class and batch IDs across admin, web, Android/API payloads, enrolments, subjects, tests, progress, certificates, and reports.
- [ ] Rename a class/batch after enrolment and after tests/results exist; confirm no relationship or test ID changes.
- [ ] Verify test IDs remain stable in attempts, results, offline sync, and certificates.
- [ ] Verify all three learner-entry paths and parent-to-child reporting.
- [ ] Verify permitted and prohibited account transitions.
- [ ] Run migration tests, API tests, backend tests, frontend lint/build/tests, and Android checks affected by API contract changes.
- [ ] Run required GitHub Actions; fix failures before release.
- [ ] Confirm all changes and reusable scripts are committed to `main`; report exact commit SHA and action result.

## Release gates

1. No destructive schema or data changes.
2. Existing numeric IDs, logins, enrolments, attempts, certificates, and API fields remain valid.
3. All new public IDs satisfy the format and uniqueness constraints.
4. Concurrent test creation cannot duplicate a test ID or sequence.
5. Account transitions are server-enforced and audited.
6. No phase is called implemented merely because the UI renders; migration, backend, regression, and GitHub Actions checks must pass.

## Implementation note

This document locks scope and acceptance criteria; it is not evidence that the code changes above have already been implemented. Each phase must be completed and verified against the repository before being marked done.
