# Agent 2 — Payroll, Employee Self-Service and Extended HR Handoff

**Repository:** `Manav326/QuantaEdge`  
**Baseline:** `main` at `0d71946b3854a68c894001193a5174a41fa9a45b`  
**Working branch:** `agent/payroll-ess-extended-hr`  
**Status:** Partial implementation committed; runtime verification remains outstanding.

## 1. Baseline audit findings

The source tree and the existing `docs/product/payroll-employee-self-service-contract.md` were inspected on the baseline commit. Existing implementation already included:

- Canonical `hr_employee` and staff identity separation, organisation/department/designation/employment type and employee history.
- Attendance, leave requests, leave balances and configurable working-week/holiday migrations.
- Payroll components limited to fixed monthly earning/deduction amounts, effective-dated employee component assignments, monthly periods, calculation snapshots, submit/approve workflow, payment reconciliation records and payroll audit events.
- Payslip JSON snapshots with SHA-256 checksums created transactionally when a payroll run is approved.
- Identity-scoped ESS endpoints for profile, own attendance, leave requests/balances, payslip list and profile-change requests.
- A People portal at `apps/people`, separate from the learning Admin Console.

The self-approval issue recorded in the task does **not** appear to remain in the checked baseline source: `HrPayrollController.approve` rejects a creator/submitter self-approval with HTTP 403, and `HrPayrollControllerTest.payrollSubmitterCannotApproveTheirOwnSubmittedRun` asserts 403. This test was retained; it was not weakened.

Important existing limitations found:
- Payroll calculation refuses mid-period employment or component changes with HTTP 409 instead of applying a configured proration policy.
- Component rules are fixed monthly amounts only; percentage/formula components and jurisdiction-reviewed rule sets are not present.
- Payroll payment rows are reconciliation records only; there is no payment-provider integration or bank transfer.
- Payslips are immutable JSON snapshots, not generated PDF documents.
- ESS private employment documents and attendance-correction requests are not implemented.
- Recruitment, configurable performance workflows, asset custody and exit clearance were not present in the baseline tree.

## 2. Implemented in this branch

### Extended HR schema — migration V71

Added `services/api/src/main/resources/db/migration/V71__hrms_extended_workforce.sql` with organisation-scoped tables for:

- Vacancies and candidate applications.
- Candidate evaluation/interview records and hiring decisions.
- Configurable performance cycles, employee goals and review records.
- Asset inventory and immutable assignment/return history.
- Exit records, checklist items and final-settlement workflow status.
- Append-only extended-HR event history.
- Dedicated permission catalogue entries for recruitment, performance, assets and exits.

No employee, candidate, payroll or asset sample data is seeded.

### Extended HR API

Added `HrExtendedWorkforceController` under `/api/v1/hr` with permission-checked endpoints for:

- `/recruitment/vacancies` — create/list vacancies and publish/hold/close/cancel state changes.
- `/recruitment/candidates` — create/list applications, stage changes, evaluations/hiring decisions, and approved-candidate conversion.
- `/recruitment/candidates/{id}/convert` — transactionally creates the canonical `hr_employee` row and `hr_employee_history` event; candidate conversion is unique and requires an approved hiring decision.
- `/performance/cycles`, `/performance/reviews` and cycle/goal/review actions — cycle lifecycle, review assignment, self-assessment submission, manager assessment and approval.
- `/assets`, `/assets/{id}/assign` and `/assets/assignments/{assignmentId}/return` — inventory, custody, condition and return handling. Damaged returns move the asset to maintenance.
- `/exits`, exit status/checklist/settlement routes — auditable clearance state, with completion blocked until checklist clearance and settlement confirmation.

All sensitive mutation routes check dedicated permissions in the backend. Related employees/departments are checked against the owning organisation. Candidate-to-employee conversion reuses `hr_employee`; it does not create a second employee registry. Exit workflow does not silently change employee status, revoke a login or transfer money.

### People portal

Added `apps/people/app/workforce/page.tsx` and linked it from the People portal home. The page has recruitment, performance, asset and exit sections, forms, tables, workflow actions, loading/error/success states and permission-aware controls. API results, rather than optimistic UI state, determine success messages.

### Tests added

Added `HrExtendedWorkforceControllerTest` covering:
- Recruitment permission enforcement before database access.
- Rejection of unsupported vacancy state changes.
- Blocking candidate conversion without an approved hiring decision.
- Rejection of invalid asset return conditions without mutation.

## 3. Existing payroll and ESS API contracts

Existing payroll routes remain under `/api/v1/hr/payroll`, including components, effective-dated compensation, periods, calculation, submission, approval, payment reconciliation and run detail. Existing ESS routes remain under `/api/v1/hr/self-service`, including profile, attendance, leave, balances, payslip list and profile-change requests.

No existing payroll migration was edited and no migration version was reused.

## 4. Calculation assumptions and financial boundaries

- Existing payroll calculation uses decimal `BigDecimal` amounts, two-decimal database columns and a fixed-monthly-components calculation version.
- Payroll does not currently infer unpaid leave, overtime or statutory tax/deduction rules.
- Mid-period employment and salary changes are currently rejected instead of prorated. A reviewed organisation policy and calculation test vectors are still needed before implementing a default.
- Reconciliation records do not prove money moved. No real transfer or payment confirmation is made by these APIs.
- Do not enable production payroll until applicable jurisdictional requirements, rounding, proration, statutory rules and payslip disclosures have been reviewed by qualified personnel.

## 5. Permissions and data integrity

- Payroll retains `HR_PAYROLL_VIEW`, `HR_PAYROLL_MANAGE`, `HR_PAYROLL_APPROVE` and `HR_PAYROLL_PAY`.
- Extended modules use `HR_RECRUITMENT_VIEW/MANAGE`, `HR_PERFORMANCE_VIEW/MANAGE`, `HR_ASSET_VIEW/MANAGE` and `HR_EXIT_VIEW/MANAGE`.
- Candidate conversion is guarded by a row lock and a unique converted-employee reference.
- One active asset assignment per asset is enforced by a partial unique index.
- Exit transitions are restricted and final completion requires checklist clearance/waiver and a settled final-settlement status.
- Event rows retain actor, reason and details. Employee status changes remain owned by the canonical HR employee workflow.

## 6. Tests and verification status

**Tests have been added but have not been executed in this environment.** I could not run Maven or Next.js locally because this execution environment cannot resolve `github.com` for cloning, and the available GitHub connector exposes read/write repository operations but no workflow-dispatch action. The repository CI workflow runs on pushes to `main`, pull requests targeting `main`, or manual workflow dispatch; I did not push to `main` or open a pull request because the task explicitly prohibits both.

Therefore, the new Java controller, migration, tests and frontend must be treated as **unverified**, not green. Run the repository's API Maven verification and People build before integrating:
- `cd services/api && mvn test`
- `cd apps/people && npm ci && npm run build`

Also validate migration V71 against a clean PostgreSQL 17 database and an upgraded copy of the current schema. Fix any compile, migration, or runtime failures before merge.

## 7. Dependencies and incomplete work

- Core HRMS/Attendance agent integration should confirm the final employee status, exit-date and approved attendance/leave contracts. This branch does not duplicate attendance/leave storage.
- Employee self-service currently relies on an explicitly linked `staff_account`; it does not auto-link by name/email. A dedicated employee principal/onboarding invitation flow remains future integration.
- Recruitment candidate conversion requires the canonical employment type and joining date supplied by an authorised operator. The API creates the canonical employee and history record, but does not automatically provision a login or permissions.
- Candidate resume references are metadata only; secure document upload, malware scanning, private download and retention policy are not included.
- Performance workflow currently provides a configurable JSON evaluation schema and optional numeric rating, but not a complete form-schema editor, manager/employee scoped portal view or goal progress reporting.
- Asset workflows track assignment and return; automated overdue reminders, depreciation and procurement are not included.
- Exit workflow tracks clearance and settlement state; it does not automatically alter employment status, disable accounts, calculate a final settlement amount, or deduct asset loss from payroll.
- Payroll proration, percentage/formula components, adjustment/reversal workflow, PDF payslip rendering, and private document storage/download remain incomplete.
- The new test suite does not yet cover every acceptance item from the task. Expand it after the first CI run, especially migration upgrade, review authorization, two-employee isolation, asset concurrency, exit transitions and recruitment conversion uniqueness.

## 8. Branch and commit record

Working branch: `agent/payroll-ess-extended-hr`

Commits made through GitHub on this branch:
- `48eb2f4` — Add extended HR workflow schema
- `7918e3b` — Implement extended HR workflow APIs
- `d1636cd` / `4eb2a60` — Add and refine extended HR tests
- `0dfe5f7` — Add extended HR workflow workspace
- `10fcdf0` — Link extended HR workspace from People portal

The remote branch was available and these commits were written to it. No commit was made to `main`; no force-push or pull request was used.
