# Agent 1 — Core HRMS, Attendance, Leave & Organizational Management Handoff

## Repository baseline

- Repository: `Manav326/QuantaEdge`
- Baseline branch: `main`
- Baseline commit inspected: `0d71946b3854a68c894001193a5174a41fa9a45b` (2026-10-13; “Download only the dedicated QuantaEdge Android APK artifact”).
- Working branch created: `agent/hrms-core-attendance-leave`, based on that exact commit.
- The remote branch did not exist when checked.
- The repository working tree and local uncommitted changes could not be inspected through the available GitHub connector; no claim is made that local working-tree checks or a local test run occurred.

## Baseline findings

The README identifies a standalone People Operations portal at `apps/people` and describes HR endpoints guarded by `HR_EMPLOYEE_VIEW` and `HR_EMPLOYEE_MANAGE`. The architecture document at `docs/product/people-operations-architecture.md` establishes these important boundaries:

- `staff_account` is a platform login; `hr_employee` is an employment record. Linking them is optional and must not implicitly grant platform privileges.
- Phase 1 includes organisation, departments, designations, employment categories, employee directory/profile, employee numbers, manager/date/status fields and employment history.
- The documented Phase 2 scope describes daily attendance, attendance statuses, optional timezone-aware check-in/out, leave types, date-range requests, overlap prevention and pending/approved/rejected states.
- The same document explicitly leaves official holiday calendars, leave balances/accrual, self-service and payroll impact as future work.
- The document's current wording says repeated attendance writes update the existing record. This requires an explicit correction/approval and audit policy before being treated as safe historical attendance behaviour.

The People application package is a Next.js application. Its root page begins with an employee type including employee number, name, employment status, joining date, department/designation and manager. This confirms that an employee directory/profile surface exists, but does not prove all lifecycle and authorization cases are correct.

## Important limitations of this baseline audit

The available repository connector can fetch known files and search indexed code, but the code searches for HR permission symbols, employee schema and attendance/leave implementation returned no indexed results. It does not expose the local Git working tree or a repository-wide directory listing in this session. Therefore I cannot responsibly name unseen migration versions, infer backend source paths, run tests, or assert that a migration/API change is safe. No schema change should be authored by guessing a path or the next Flyway version.

## Downstream integration contract

The following contract should be treated as the minimum boundary for Agents 2 and 3, subject to confirmation against the actual API implementation:

- Stable employee identifier: canonical `hr_employee.id`; employee number is a human-facing identifier, not a substitute for authorization.
- Employment status: only active employees should be eligible for new payroll periods or approved teaching assignments. Terminated/exited employees and effective dates must be handled explicitly.
- Date semantics: attendance and leave are based on organisation-local calendar dates; timestamps such as check-in/out must retain an explicit offset/time-zone interpretation.
- Approved leave: downstream consumers should read approved leave for a date range; pending/rejected/cancelled requests must not be treated as approved absence or deducted from balances.
- Authorization: HR view/manage and decision permissions must be evaluated server-side. Employee linkage must not confer platform-admin/CMS privileges.
- History: employee lifecycle, attendance corrections and leave decisions need actor, timestamp, before/after values and reason where supplied. Consumers must not infer history from mutable current-state fields alone.

## Tests and verification

- Remote baseline commit was identified from recent commit history.
- The assigned branch was created successfully from the baseline SHA.
- No local checkout, baseline test run, database migration run, API integration test, frontend build or test result is claimed in this session.
- This document is a baseline/handoff record, not a declaration that Agent 1's definition of done has been met.

## Remaining implementation work

1. Inspect the complete API and migration tree from a local checkout; record the actual latest migration and current attendance/leave endpoints.
2. Build baseline tests before modifying existing behaviour.
3. Complete five/six-day configurable calendars, dated holidays and organisation-time-zone date calculations.
4. Complete attendance correction/approval and audit history without silently rewriting historical records.
5. Complete leave policy configuration, overlap prevention, transactional approval/cancellation and consistent balance accounting.
6. Verify lifecycle transitions, date invariants, department references and employee/user isolation.
7. Finish People portal forms, search/filtering, pagination, approval states and permission-aware actions.
8. Run migration tests against clean and existing schemas plus relevant backend/frontend suites; record exact commands and observed results.

## Branch and commits

- Branch: `agent/hrms-core-attendance-leave`
- Baseline: `0d71946b3854a68c894001193a5174a41fa9a45b`
- Handoff commit: to be recorded after this file is committed.
- Remote push: this change is being authored directly on the assigned remote branch through the GitHub file API; a local Git push was not performed.

## Current implementation progress

The owner-approved decisions are documented in `docs/implementation/agent-1-hrms-policy-decisions.md` and referenced from the product architecture.

Implemented on this branch:
- UI working-week fallback is six days, while still allowing five or six days to be selected.
- Flyway V71 sets the database column default for newly created organization rows to six days without rewriting existing organizations' configured values.
- Flyway V72 creates attendance correction history, retains before/after status, timestamps, notes, actor and required reason, and blocks update/delete of the history table at the database layer.
- The attendance write API now requires a non-empty correction reason when a row for the employee/work-date already exists and records the old and requested values before updating it in the same transaction.
- A permission-checked attendance correction history read endpoint was added.
- API unit tests were added for missing correction reason rejection and denial of correction-history reads without attendance-view permission.

## Not yet safe to call complete

This is a partial implementation, not a green, complete HRMS release. During migration audit, existing migrations V28–V70 were discovered; the new migrations have been placed at V71–V73 to avoid version collisions. The repository connector does not run Maven/Next.js locally, so tests and builds have not been executed here. CI must validate the migration and Java/test compilation.

Remaining policy work identified by inspection:
- Paid-leave approval now posts a negative `CONSUMPTION` transaction within the same database transaction as the request decision, guarded by a per-employee/type advisory lock and a sufficient-balance check. Unpaid leave does not consume a paid-leave balance. The selected safe policy is to reject approval when the paid balance is insufficient. A unit test covers the insufficient-balance rejection, but the API test suite has not been run here.
- Flyway V73 blocks updates/deletes to posted leave-balance ledger transactions. A permission-controlled reversal API posts an opposite-sign `REVERSAL` linked to the original transaction; the schema's unique reversal reference prevents duplicate reversal. The People UI now exposes reversal with a required reason for users who hold `HR_LEAVE_BALANCE_MANAGE`; reversal integration tests remain outstanding.
- Employee self-service has leave and profile-change requests, but no attendance-correction request endpoint/workflow was found in the inspected route. Direct HR corrections are now auditable; the employee request + approval side of the owner's "both" decision remains incomplete.
- Approval rules are not yet shown to be configurable per lifecycle transition. Current leave decisions use the shared `HR_LEAVE_MANAGE` permission.
- Manager reporting-employee-only row scope and explicit manager capabilities need API-wide negative-path tests; permission checks alone do not prove row-level scoping.
- India holiday defaults require a source-verified central/state calendar and jurisdiction/source metadata. The existing UI accepts organization-entered holidays; no unverified holiday list should be described as preloaded.
- No CI run or test result is claimed in this handoff.

The branch was created from `0d71946b3854a68c894001193a5174a41fa9a45b`. Existing repository migrations already run through V70, so new policy migrations are V71–V77; duplicate migration versions discovered during review were removed. The branch was subsequently merged with latest `main` at `13dbad954539f491e3e74ae27eaffd1a1c8053dd`; compare now reports no commits behind main. No local Git push was performed.

## Follow-up implementation (2026-10-13)

Additional work has now been committed on the same isolated branch:

- **Employee attendance corrections:** V74 adds a request table tied to the existing attendance record, required reason, status/reviewer fields, duplicate-pending protection and a dedicated review permission. Employee self-service endpoints submit/list/cancel requests without accepting a caller-supplied employee ID. HR review endpoints list requests and approve/reject them. Approval locks both request and attendance rows, validates approved leave for an ON_LEAVE status, writes immutable before/after correction history and updates attendance in the same transaction. Employee and HR People portal screens now expose request and review flows.
- **Lifecycle approval policy:** V75 adds organization-scoped transition rules keyed by from/to status and required permission. The employee status API now fails closed if no active rule exists and requires the configured permission in addition to the existing employee-manage permission. The policy API is permission-controlled (HR_APPROVAL_POLICY_MANAGE) and records configuration changes in the staff audit log. V75 creates the rule table and seeds common transitions; V77 fills the complete non-terminal transition matrix for existing organizations. Newly created organizations must have rules configured before status changes are permitted.
- **Holiday provenance:** V75 adds source type, source URL, jurisdiction, year and verification metadata to holiday records; the API returns these fields. Existing manually entered holidays are marked ORG_MANUAL. No default dates are fabricated. The People portal now has an official-calendar import form; the API accepts only HTTPS government domains, validates dates/year and duplicate dates, and records a staff source attestation. It does not independently verify each date. The organization must import an applicable central/state/local calendar before treating it as a baseline.
- **Tests added:** review-controller authorization/decision validation, manager reporting-scope denial, and rejection of non-government holiday import sources. Existing self-service, HR attendance/leave and leave-ledger tests have not been executed in this environment.

## Remaining release blockers

The request/approval workflows are implemented in code but are **not yet verified green**. A real local checkout/build and database migration test is still required. In particular, verify V71–V77 on both a clean database and an existing schema through V70, run the complete Maven test suite and People Next.js build, and fix every compile/schema/test failure before calling the branch complete.

Manager scope is now implemented as an explicit opt-in permission (HR_MANAGER_TEAM_VIEW) for employee directory lists/details/history, employee updates and status changes, attendance lists/summary/direct edits/correction reviews, leave request lists/create/decisions, employee contact-change reviews and leave-balance reads/mutations. The SQL list queries filter to the reporting hierarchy and employee-specific operations perform an API-level scope check. Admin accounts remain unrestricted. The permission must be explicitly granted alongside each HR view/manage permission; do not treat the manager title alone as authorization. Verify all these paths with integration tests before release.
 
Holiday source provenance and an import workflow are now implemented. Authorized staff can import a bounded JSON list against an HTTPS government domain, selecting central/state/district/other official jurisdiction; imported rows record source URL, jurisdiction, calendar year, verifier and verification timestamp. This records a staff attestation of the source and does not independently validate every date. No default holiday dates have been seeded: choose the applicable central/state/local calendar for the actual organization and import the dates from the cited official notification. Manual entries remain organization overrides.
 
The configurable policy now governs employment-status transitions and is exposed in the People portal; missing or inactive rules fail closed. It still does not generalize approval routing to every HR workflow (for example leave approvals), whose existing review permission remains HR_LEAVE_MANAGE.