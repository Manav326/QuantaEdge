# Repository Agent Instructions

These instructions apply to all coding agents and contributors working in QuantaEdge, including specialist agents operating in separate conversations.

## Before every task

1. Read `docs/hrms/agent-coordination.md`, `docs/hrms/shared-contracts.md`, and `docs/hrms/integration-status.md` when touching HRMS code.
2. Audit the latest `main` commit and the exact source, tests, and migrations involved.
3. Identify the module owner and any consumers listed in the integration status.
4. Do not rely on an earlier chat's memory as the current code contract; GitHub `main` is the source of truth.

## Working agreement

- Keep all work on `main`. Do not create branches or pull requests.
- Never overwrite a file fetched before a newer commit changed it. Fetch its current version immediately before updating.
- Make focused commits and avoid concurrent edits to the same file.
- Do not invent or seed test/employee/customer records in migrations or production initialization.
- Check the latest migration number before adding a migration; migrations are append-only.
- Do not invent API paths, status values, permission names, or database fields. Verify them in code and record shared contract changes.
- Add or update regression tests for behavior changes. Distinguish tests added from tests actually run.
- After each meaningful commit, update `docs/hrms/integration-status.md` with the SHA, changed files, contract effects, actual test/build results, CI link/result, blockers, and next owner.
- If CI is still running, say pending. If the integration tools cannot show a result, say unverified. Never claim green without observed successful checks.

## HRMS ownership

- **Agent 1 — Core HR:** employee identity/lifecycle, organization, departments, designations, reporting relationships, employment history and core HR authorization.
- **Agent 2 — Attendance & Leave:** attendance, corrections, leave lifecycle, working schedule, holiday calendars and leave-balance ledger.
- **Agent 3 — Academic Workforce:** effective-dated teacher/professor assignments, approval/replacement history, class/subject/academic-period links.
- **Agent 4 — Payroll & ESS:** salary components, payroll periods, auditable calculations/approvals, payslips/reconciliation and employee self-service.

Core HR owns the canonical employment record. Specialist modules reference `hr_employee.id`; they must not create a second employee identity. Keep the People Operations portal separate from the general admin portal.
