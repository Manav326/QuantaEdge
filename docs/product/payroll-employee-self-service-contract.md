# Payroll and Employee Self-Service: implementation contract

Status: payroll foundation and identity-scoped employee self-service APIs are implemented in source. The employee self-service UI is now being added for profile, attendance, leave requests and leave balances. Payslip issuance, employee document management, profile-change requests and attendance-correction requests remain unimplemented.

## Repository integration and ownership

- Repository: `Manav326/QuantaEdge`; default branch is `main`.
- API and PostgreSQL are shared through `services/api`; the standalone People Operations UI is `apps/people` (local port 3002). Do not place HR workflows inside the learning Admin Console.
- The current HR foundation uses `hr_employee` for employment and `staff_account` for platform staff login/technical permissions. An employee may have no login. A linked staff account does not automatically imply HR, CMS, or administrator permissions.
- Continue the Flyway sequence after the current highest migration. Never edit an already-released migration or reuse a version number; inspect the live main migration directory before choosing the next version.
- Coordinate migration version and shared API contracts with Core HRMS, Attendance/Leave, and Academic Workforce agents before landing overlapping changes.

## Payroll domain model

Use organisation-scoped relational tables; do not put salary fields on `hr_employee`.

1. **Salary component definitions**: organisation, stable code, display name, kind (earning/deduction/employer contribution/informational), calculation method (fixed amount, percentage of explicitly selected basis, or formula from a restricted allow-listed expression language), taxable/reporting classification as configurable metadata, active flag, effective dates, and version. No executable SQL or arbitrary code in formulas.
2. **Employee compensation assignment**: employee, effective-from/to, currency, pay frequency, component assignment values, and change reason/actor. Keep historical versions immutable; overlapping active assignments for one employee must be rejected.
3. **Payroll period**: organisation, period start/end, payment due date, status (draft, calculated, submitted, approved, payment-in-progress, partially-paid, paid, cancelled), creator and timestamps. Enforce one period per organisation/frequency/date range and explicit state transitions.
4. **Payroll run and line snapshots**: freeze employee identity/employee number, compensation version, component definitions/rates, input attendance/leave references, calculation engine version, rounded component amounts, gross, deductions, net, and currency at calculation time. Recalculation creates a new run/version and must never silently rewrite an approved run.
5. **Approval events**: actor, timestamp, decision, reason, prior/new state, and separation-of-duties rule. A creator/calculator must not approve their own run by default; any policy exception must be an explicit organisation setting with audit.
6. **Payment batches and reconciliation**: payment reference, bank/provider reference, amount, currency, status, submitted/settled/failed timestamps, reconciliation actor and notes. Match each payment to an employee's approved net amount; enforce idempotency for provider references and prevent duplicate successful settlement. Do not store raw bank credentials or payment secrets.
7. **Payslips**: immutable snapshot of approved payroll line items and totals, versioned document metadata, generated timestamp and checksum. Access is employee-self only or separately permissioned payroll access. Regeneration after a correction creates a new version and audit event.
8. **Payroll audit trail**: append-only events for compensation changes, calculations, overrides, approvals, payslip issuance, payment/reconciliation and reversals. Capture actor, reason, correlation identifier, timestamp and before/after snapshots where appropriate; redact secrets and unnecessary sensitive values.

Use decimal arithmetic and explicit currency scale/rounding policy. Validate bounds, component references, date ranges, currency consistency and arithmetic invariants (gross = earning total; deductions = deduction total; net = gross minus deductions under the configured policy). Never treat absence of data as zero without an explicit rule.

## Calculation and legal-rule boundaries

The engine should calculate only configured component rules and supplied, auditable inputs. Jurisdiction-dependent tax, social-insurance, statutory deductions, minimum-wage/overtime, payslip disclosures, retention and payment rules must be represented as versioned, effective-dated configuration with jurisdiction and source/review metadata. Do not hard-code India/Bihar tax or legal assumptions. Production payroll must remain disabled until qualified review validates the applicable rules, test vectors and effective dates. Preserve the rule-set version used by every run.

Attendance/leave integration must use approved, date-scoped records and a documented payroll input policy. Do not infer unpaid leave, overtime, or salary proration solely from raw check-in events.

## Permissions and workflow

Introduce dedicated permissions such as `HR_PAYROLL_VIEW`, `HR_PAYROLL_MANAGE`, `HR_PAYROLL_APPROVE`, `HR_PAYROLL_PAY`, `HR_PAYSLIP_VIEW_ALL`, and `HR_DOCUMENT_MANAGE`; map to the repository's existing permission convention before implementation. Check permissions on every API request, not only in UI navigation. Require the approval/payment separation rules server-side. Employee access is a separate principal scope and must not be inferred from a designation or `HR_EMPLOYEE_VIEW`.

Recommended workflow:
1. Configure and approve component/rule versions.
2. Create period and snapshot eligible active employees/compensation.
3. Calculate and validate; show exceptions without silently defaulting values.
4. Submit for independent approval; freeze approved run.
5. Create payment batch; reconcile each payment idempotently.
6. Issue payslips only for approved payroll; permit employees to view/download only their own.
7. Corrections use a controlled adjustment/reversal and a new auditable run, not mutation of historical approved results.

## Employee self-service contract

The People portal remains separate. Self-service is a least-privilege employee experience, not a staff-admin role.

- **Identity linking**: explicitly link a verified employee identity to a dedicated employee principal or an existing login through a controlled, audited process. Never auto-link on matching name/email alone. Do not grant platform staff/admin permissions during linking.
- **Profile**: employee can read own profile and request changes to explicitly employee-editable fields; sensitive fields and employment status remain HR-managed. Requests require review and preserve old values.
- **Attendance**: read own daily records, check-in/out only if the existing attendance policy enables it, and submit correction requests rather than overwrite approved records.
- **Leave**: create and view own requests, withdraw only while policy permits, see reviewer decision and reason. Employees cannot approve their own request.
- **Documents**: private metadata and object storage references with organisation/employee ownership, MIME/size allow-lists, malware scanning, short-lived authorized downloads, retention/deletion policy and access audit. Do not expose public object URLs.
- **Payslips**: list and download only own issued payslips. Do not expose payroll-wide endpoints to the employee role.
- **Authorization**: resolve the employee ID from the authenticated principal server-side for all self-service routes. Never trust an employee ID supplied in a URL/body as proof of ownership. Test horizontal access attempts across two employees.
- **Session/security**: reuse the repository's established authentication/session controls; use MFA/step-up verification for high-risk changes if supported. Rate-limit sensitive actions and log security-relevant events without logging tokens, full bank details, or document contents.

## Implementation sequence and acceptance gates

1. **Contract alignment**: confirm latest migration version, entity names, permission naming, transaction/audit utilities, API error conventions, auth principal model, and People UI API client. Confirm no parallel agent is editing the same files.
2. **Payroll schema + domain tests**: additive migration; uniqueness, effective-date and state-transition constraints; calculation unit tests using deterministic fixtures (tests only, no production/demo payroll seeds).
3. **Permission-checked API**: component and compensation management, period/run calculation, immutable snapshots, approval controls, and audit events.
4. **Payslip and reconciliation**: private artifact delivery, idempotent payment state handling, reversals, access controls and tests.
5. **Self-service APIs/UI**: identity-scoped profile, attendance, leave requests, leave balances and issued payslips are implemented. Payslips are created transactionally on payroll approval, stored as immutable JSONB snapshots with SHA-256 checksums, and exposed only to the linked employee. Next work includes controlled identity linking, own-profile change requests, attendance-correction requests and private documents.
6. **Production readiness**: security review, migration-from-clean and upgrade tests, authorization matrix, two-employee isolation tests, rounding/property tests, rule-set review, privacy/retention policy, backup/restore and operational runbook.

Do not mark payroll production-ready until legal rules are reviewed for the deployment jurisdiction, a payment provider/bank reconciliation contract is defined, and security/accessibility/operational checks pass.
