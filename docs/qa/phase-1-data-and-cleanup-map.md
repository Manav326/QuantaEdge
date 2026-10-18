# Phase 1 — Data Relationship, Seed Ownership and Cleanup Map

**Status:** Partial schema/source review; not a migration plan and not approval to delete data.  
**Repository:** `Manav326/QuantaEdge`  
**Evidence inspected:** selected schema migrations `V15`, `V28`, `V42`, `V43`, `V58`, `V60`, `V61`, `V64`, `V65`, `V113`–`V115`, `V120`–`V121`, `V125`–`V126`, `V128`; `LocalPreviewDataInitializer.java`; `docker-compose.yml`; selected tenant-scope and authorization tests. A full dependency graph for all tables, migrations and file/object storage remains open.

## 1. Executive findings

1. **No platform-wide `is_test_data` field was found by the current repository code search.** A `test_run_id` match appears in HR payroll-run UI/controller data, but that is a payroll calculation/run identity, not a platform QA-pack ownership system. This is preliminary search evidence, not proof that no equivalent ownership system exists under another name.
2. **An existing local preview seed is enabled by the local compose default:** `docker-compose.yml` sets `APP_DEMO_SEED: ${APP_DEMO_SEED:-true}`; `application.yml` itself defaults it to false. The production compose and `.env.production.example` explicitly set false, and `AuthService` refuses to start in production when demo seeding is enabled. The initializer runs only when `app.demo-seed=true`.
3. The local preview initializer owns a canonical student public UUID `7f4c7e1a-1b21-4db9-a6f6-9d4a1f1e7a31`, sets `environment='LOCAL_PREVIEW'`, links the student to the existing active `QUANTA-EDGE` tenant, enables class 7 Maths/Science track enrollments, marks published lessons completed and inserts correct-answer attempts for published questions that lack an attempt.
4. **The initializer begins with a broad cleanup query:** `delete from student where environment='LOCAL_PREVIEW' and public_id<>?`. `AuthService.previewLogin()` selects the first active `LOCAL_PREVIEW` student, and `StudentController.preview` checks the same environment. Adding QA fixtures with this marker could therefore both be deleted on startup and collide with preview login selection. This is existing local-demo behavior, not an acceptable QA-pack ownership strategy.
5. Migration `V125` creates the explicit `QUANTA-EDGE` tenant if missing and performs a legacy membership backfill only for active students without an existing active membership. Migration `V128` deliberately makes a set of learner-history uniqueness keys institution-scoped. These migration-owned identities and existing business records are not removable test fixtures.
6. Many relationships use `ON DELETE RESTRICT`, intentionally protecting learning, payment, entitlement, HRMS and audit history. The test installer should prefer run-scoped soft cleanup/reversal or dependency-aware deletion in an isolated test database rather than trying to delete shared reference data or historical business rows.

## 2. Baseline and data ownership classes

| Data class | Examples | QA pack action |
|---|---|---|
| Shared/reference data | Curriculum classes/programs/subjects, chapters, lessons, questions, question options, leave types, permission catalog, identity-prefix registry | **Borrow/read only** unless a dedicated test explicitly creates a namespaced fixture. Never uninstall shared curriculum/configuration. |
| Platform baseline | Existing `QUANTA-EDGE` institution, institution branding baseline, platform staff/access grants, migration-created identity and permission metadata | Treat as protected pre-existing data. Never include in a pack-owned delete manifest just because a test refers to it. |
| Existing local demo seed | Canonical student UUID, `LOCAL_PREVIEW` environment, the preview student's membership, tracks, progress and question attempts | Preserve and distinguish from QA packs. Document as an explicit baseline/demo identity, not as a generated per-run user. Test whether the existing broad cleanup query can affect concurrent runs. |
| Run-owned synthetic identities | Test-only accounts, new student IDs, guardian links, synthetic HR employee/access identities, synthetic institution/admin identities | Create with a unique run ID and record actual primary keys/public IDs in an immutable manifest. Use unique synthetic emails/usernames/codes and ensure no collision with real identities. |
| Run-owned transactional records | Enrollments, progress, practice/assessment attempts, purchase requests/orders in sandbox, test attendance/leave/payroll runs, certificates, class attendance and chat messages | Record ownership and dependencies. Cleanup only after checking transaction state, historical/audit constraints and whether data has escaped the test boundary. |
| External or filesystem resources | Test payment-provider orders, Google Meet/calendar events, uploaded branding images, uploaded recordings, private PDFs/media, rendered certificate files, textbook/document cache entries | Use sandbox/test doubles. Save provider/file identifiers and exact owner paths. Revoke/delete only if created by the run and the provider supports safe cleanup. |

## 3. Relationship graph that test data must respect

This is a domain dependency map, not a substitute for complete foreign-key extraction.

### 3.1 Identity, session and family

- `user_account` carries verified identity, username/password/profile and Google subject/email metadata (`V115` adds identity columns and unique indexes).
- `student` is a separate learner identity/domain record with public ID, class, language, status and an environment field used by local preview seeding.
- `guardian_student` links a guardian user/account to a student and includes active/consent/access semantics. Guardian controller routes guard parent identity and target-child operations.
- `auth_session` and `otp_challenge` retain session/verification lifecycles. Institution-admin sessions are separate records in `institution_admin_session`.
- `student_login_credential`/related credential tables and child-credential operations need to follow actual identity mapping and lifecycle rules; do not fabricate only a guardian name on a student record.

**Setup order:** create the correct user/student identities → add guardian-child relationship and consent → establish only the allowed credential/session state → join the relevant institution → create downstream records.

**Cleanup caution:** revoke test sessions/tokens before deleting dependent identities; preserve audit evidence where needed. Never reuse real accounts or real verified Google identities as pack-owned objects.

### 3.2 Institution and course-commerce

- `institution_tenant` owns a unique public ID and unique tenant code; valid states include draft/active/suspended/closed.
- `institution_admin_account` is institution-bound. `institution_admin_session` references institution-admin and institution records and cascades when its owning rows are removed.
- `institution_branding`, `institution_branding_asset`, institution join codes, memberships, membership events, billing contracts, billable events and audit records form a tenant-scoped graph.
- `institution_course_release` references a source institution and shared `curriculum_program`; assignments reference both institution and course release; offers reference an assignment and release/course.
- `institution_course_entitlement` grants a program to an institution. It is not the same as a learner's purchase/access.
- `institution_membership` connects a student to a tenant. The pair (institution, student) is unique. Membership events provide lifecycle history.
- `course_payment_order`, `course_purchase`, `course_entitlement`, `parent_course_purchase_request`, `student_course_access_grant`, `institution_marketplace_offer` and `course_entitlement_transfer_audit` represent different steps of offer, order, purchase, access, parent request and transfer.

`V113` introduced marketplace offers, payment orders and purchases. `V114` adds a client request key for payment-order idempotency. `V115` adds unified purchaser/holder identity and course-entitlement records. `V125` makes institution ID and institution offer part of offer/order/purchase/request relationships and introduces composite foreign keys with `ON DELETE RESTRICT`. `V128` replaces certain global uniqueness rules with tenant-aware indexes.

**Setup order:** synthetic tenant (only where scenario needs a new tenant) → institution admin (if needed) → release/assignment/approved published offer → learner membership and course entitlement/enrollment as applicable → sandbox order/payment verification → resulting purchase/access record → downstream learning attempts.

**Never seed a paid purchase by directly fabricating only the client-success response.** The test must distinguish created order, provider status/verification, purchase/entitlement and granted access. Use provider-supported test mode or an explicit test payment adapter. Never use real debit details or live webhooks for a synthetic scenario.

### 3.3 Curriculum and learning

The shared curriculum graph includes classes/programs/subjects → chapters → lessons → questions/options and approved resources. Learner state references shared content rather than cloning the textbook or curriculum data.

| Learner state | Relationship | Test implications |
|---|---|---|
| Program/course enrollment | student + institution + program | Verify exact institution-scoped membership and unique enrollment keys |
| Subject/track enrollment | student + institution + subject | Select only subjects included in the course/tenant; avoid duplicate natural keys |
| Lesson progress/mastery | student + institution + lesson/concept | Keep history tenant-scoped; do not reuse an existing student's progress |
| Question attempts/practice sessions | student + institution + question/lesson/session | Respect question/lesson foreign keys, session lifecycles and retry/idempotency rules |
| Diagnostic/assessment attempts | student + institution + assessment; attempt → attempt-question → answers | Preserve attempt sequence, in-progress uniqueness, submitted/closed state and answer ownership |
| Live/recorded class state | tenant-scoped class/recording; attendance and playback progress reference session/recording and student | Test assignment/course access, join window, attendance, recording status and media access independently |
| Structured lesson response/activity | user/student + published artifact/question/activity | Use actual published or explicit test artifact; test draft/unpublished denial separately |
| Tutor session | student + lesson + institution | Keep conversations and retrieval context within test student's lesson and tenant |
| Certificate | student + institution + optional assessment attempt; certificate audit references issued/revoked object | Issue through normal eligibility flow or a documented fixture helper; verify recipient, uniqueness, downloadable bytes and public QR state |

Migrations `V42`, `V43`, `V58`, `V106` and later tenant-scoping changes establish durable relationships for assessments, sessions, answers, classes/recordings, certificates and structured lesson responses. Not every FK/delete policy has yet been inventoried line by line; that remains a closure task.

### 3.4 HRMS and finance

- `hr_organization` → departments/designations/employment types → `hr_employee`; employees may have managers and links to staff access identity.
- Staff account → workspace-specific role/permission grants/additional roles → HRMS routes and audit trails.
- `hr_attendance_record` references an employee with `ON DELETE RESTRICT` and enforces one record per employee/work date.
- `hr_leave_request` references employee and leave type and enforces status/date invariants.
- `hr_leave_balance_transaction` is a ledger; the source index enforces source uniqueness and a single reversal per transaction. Do not delete one ledger row while leaving the original/reversal inconsistent.
- `hr_salary_component` and effective-dated `hr_employee_salary_component` feed `hr_payroll_period` → versioned `hr_payroll_run` → lines/events/payments. Compensation ranges, currencies and run transitions have business constraints.
- Academic periods → teaching assignments → decision/history/workload reports → teacher-visible class eligibility. An assignment marked proposed/unapproved must not be used as an approved teaching assignment.

**Setup order:** organization and reference settings → employees and reporting relationships → staff identity/access grant in HRMS workspace → period/policy → attendance/leave/ledger or teaching assignment → compensation components and effective dates → payroll period/run → test-only review/approval/payment path.

**Financial isolation:** synthetic payroll and leave-ledger rows must be visibly run-owned and excluded from real payroll, exports, payouts, notifications and statutory reports. Use sandbox/mock payment settlement; never call a real payout from the test-data installer.

## 4. Destructive constraints and safe cleanup

Representative schema protections observed:
- Payment orders, purchases, entitlements, parent purchase requests, transfer audits and their user/course/holder references commonly use `ON DELETE RESTRICT`.
- Institution business entities and historical records often restrict institution/program/membership/contract deletion. Institution branding may cascade from its tenant; institution-admin sessions cascade from their owning admin/tenant.
- Assessment answers can restrict deletion of their attempt-question; attendance/leave records restrict employee deletion; ledger reversals require audit consistency.
- Unique constraints are increasingly tenant-scoped. A student's same course or lesson may legitimately be represented independently in two institutions, but must not collide within the same institution.
- File and external-provider resources are not cleaned up automatically by database deletes and need explicit manifest entries.

### Required deletion algorithm (future phase, not yet implemented)

1. Validate the target run ID and a non-production/test-environment safety gate.
2. Load a signed/immutable manifest created at install time; refuse broad deletion if the manifest is missing, ambiguous or inconsistent.
3. Stop queued test jobs and revoke test sessions/access first.
4. Reconcile external resources and any payment/meeting side effects; only void/delete objects actually created in supported test mode.
5. Delete pack-owned dependent transactional rows in a dependency-aware order, preserving required immutable audit/ledger records or creating explicit compensating/reversal records if business rules require them.
6. Remove owned files/cache/object-store keys by exact recorded key/path and ownership marker; never wildcard-delete shared directories.
7. Delete pack-owned identities and tenant objects only if no protected/pre-existing references remain; if an FK restriction fires, stop and report the blocker rather than forcing cascade.
8. Verify counts/hash of pre-run protected rows and confirm unrelated records are unchanged.
9. Mark the test run uninstalled with an audit record and retain a cleanup report. Re-run must be safe/idempotent.

**Forbidden:** `DELETE FROM table WHERE is_test_data=true` across the database; deleting by a generic `LOCAL_PREVIEW`/demo environment marker; truncating tables; deleting the `QUANTA-EDGE` tenant; deleting shared curriculum/permission rows; deleting all rows by ID range/timestamp; or treating FK failure as a reason to force cascade.

## 5. Existing seed and baseline collision findings

| ID | Priority | Evidence | Required action |
|---|---|---|---|
| DATA-01 | P1 | Local compose defaults `APP_DEMO_SEED` to `true`; application config defaults it false, production compose/env set false, and `AuthService` throws if demo seed is true in production. | Keep the pack independent of this toggle; preserve the existing production guard and verify config at runtime. |
| DATA-02 | P1 | Local preview seed uses a fixed student public UUID and `environment='LOCAL_PREVIEW'`; it writes membership, class-7 tracks, published lesson progress and correct-answer attempts. | Reserve the canonical preview UUID and all related records as protected baseline; test that installing/uninstalling a QA pack leaves them unchanged. |
| DATA-03 | P1 | Initializer deletes other student rows tagged `LOCAL_PREVIEW`, and preview login selects the first active row with that marker. | Review broad cleanup and preview-user selection together. Do not use `LOCAL_PREVIEW` as the test-pack ownership boundary; cleanup must be run-specific. |
| DATA-04 | P1 | `V125` creates `QUANTA-EDGE`, backfills memberships for active students without another active membership, and records membership events; `V128` tenant-scopes learner-history natural keys. | Preserve migration-owned QuantaEdge tenant and historical backfill. QA fixture setup must test multi-tenant identity/history and cannot assume all historical learner rows belong to a synthetic tenant. |
| DATA-05 | P1 | Repository search found no global `is_test_data` marker; many tables use strong relational constraints. | Before Phase 2 implementation, inventory all tables/FKs and approve a hybrid owner-manifest + explicit marker schema where justified. |
| DATA-06 | P1 | Payment, institution billing, payroll and leave ledgers include idempotency, unique source keys and audit relationships. | Test replays/reversal paths without real settlement; preserve audit/ledger consistency at cleanup. |
| DATA-07 | P2 | Branding, PDFs, recordings, private media, caches, certificate files and provider-created records have lifecycles beyond row deletion. | Inventory exact storage keys/provider IDs and implement per-resource cleanup adapters with a dry-run preview. |

## 6. Phase 1 work remaining for persistence/cleanup

- [ ] Generate a complete relation/constraint catalog from all migrations at current main (tables, columns, primary/foreign/unique/check constraints, indexes, delete action, migration owner).
- [ ] Trace controller write paths and map the mutated rows to this catalog.
- [ ] Inventory DB-backed audit/history tables that must not be hard-deleted even for test activity.
- [ ] Inventory private media, textbook cache, certificates, branding, recordings and temporary upload storage; locate owner keys and deletion operations.
- [ ] Identify provider-side objects (Razorpay test orders, Google Meet/calendar events, messages) and their supported void/delete semantics.
- [ ] Decide which integration tests use a real provider sandbox versus a deterministic test double.
- [ ] Design the per-run manifest schema/API only after this evidence review; include rows, files, provider resource IDs, expected count, and dependency order.
- [ ] Prove through integration tests that uninstall preserves the canonical preview student, QuantaEdge baseline, all pre-existing users/content/payments, and other QA runs.

---

**Audit integrity:** This map reflects observed source and migration evidence, not a complete dump of every schema constraint. Do not implement cleanup SQL solely from this document.

## 7. Centralized QA pack implementation status (2026-10-18)

The run lifecycle and safety contract now has a dedicated source of truth at [qa-test-pack-lifecycle.md](./qa-test-pack-lifecycle.md). It defines the expected state machine, exact resource manifest, environment/permission gates, cleanup ordering, external-resource handling and QA-001–QA-007 acceptance matrix.

The implementation is **not yet complete**. The engine must not be a generic SQL executor or use delete-by-marker logic. Database writes are restricted to allow-listed typed fixture builders; the manifest records actual identifiers; uninstall fails closed when a resource cannot be proven owned or cleaned safely.

The local-preview isolation fix is now also present on the centralized-QA feature branch. Draft [PR #78](https://github.com/Manav326/QuantaEdge/pull/78) remains open separately; its API verification failed because of broader institution-context test failures. The fix is not claimed as verified or merged.

Current acceptance status:
- QA-001, QA-002, QA-004, QA-005 and QA-007: NOT_EXECUTED. Starter install/idempotency/gate/uninstall code exists on the feature branch, but no exact-head disposable-PostgreSQL acceptance evidence is recorded.
- QA-003: NOT_IMPLEMENTED for scenario-runner/coverage-report behavior beyond starter-pack invariant checks.
- QA-006: NOT_IMPLEMENTED for sandbox/fake adapter lifecycle and exact cleanup of payment, messaging, calendar, media or storage provider resources.
- Schema cleanup map: still partial; a full FK/unique/check/delete-action catalog and write-path trace are required before adding fixture builders for every domain.
- Production, live payments, real notification endpoints and provider resources are never test targets.
