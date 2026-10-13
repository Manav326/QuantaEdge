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
