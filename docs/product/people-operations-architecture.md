# QuantaEdge People Operations (HRMS)

## Product boundary

People Operations is a standalone application in the QuantaEdge ecosystem. It is not a new section of the learning-platform Admin Console. The initial application is planned as `apps/people`, backed by the existing QuantaEdge API and PostgreSQL database.

- Learning portal: student and parent learning journeys.
- Admin portal: learning content, publishing, assessments and platform access governance.
- People portal: organisational structure, employee lifecycle, attendance, leave, academic assignments and payroll.
- API: authenticated service boundary shared by the applications.

Applications must not query one another's database tables directly. Cross-application integration is through authenticated, permission-checked APIs.

## Identity boundary

`staff_account` continues to represent a platform staff login and its technical permissions. `hr_employee` represents an employment relationship. An employee may exist without a login. `linked_staff_account_id` is an optional unique association for the limited case where an employee also has an existing platform staff identity; it must not be populated merely because someone is an employee.

Employee designation, department membership and academic teaching assignments must never grant content publishing, user administration or other technical permissions by implication. Login provisioning and dedicated employee self-service identity are a separate, explicit future integration.

## Phase 1 scope

- Organisation anchor and configurable departments/designations.
- Configurable employment categories.
- Employee directory and profiles.
- Generated employee number.
- Reporting manager, joining/probation/exit dates and employment status.
- Effective-dated employment history records.
- HR-specific view/manage permissions.
- Standalone People portal and separate service deployment target.

## Phase sequence

1. Employee and organisation foundation.
2. Attendance and leave.
3. Teaching assignments and academic workload.
4. Payroll calculation, approvals, payslips and payment reconciliation.
5. Employee self-service, documents and requests.
6. Recruitment, performance, assets, exits and reporting.
7. Security, compliance, recovery and production readiness.

## Phase 2 scope: attendance and leave

- Daily attendance keyed by employee and work date; repeat writes update the existing record.
- Present, absent, half-day, remote, approved leave and holiday statuses, optional timezone-aware check-in/out, notes and date summaries.
- Configurable leave types and dated requests with weekday counting, overlap prevention and pending/approved/rejected states.
- Separate view/manage permissions; decisions are attributed and an employee cannot approve their own linked request.
- No sample employees, attendance or leave requests are seeded. Official holiday calendars, leave balances/accrual, self-service and payroll impact remain future work.

## Cross-site class assignment contract

Teaching assignment is not part of the employee CRUD API. In the academic-workforce phase, create explicit assignment records linking an active employee to a class/subject/academic period, with effective dates, assignment status, approval metadata and an audit trail. The learning platform may consume only active, approved assignments via an authenticated API. The assignment must not confer technical CMS permissions. Staff access to teaching features will be granted independently and narrowly.

## Security and integrity rules

- HR endpoints require `HR_EMPLOYEE_VIEW` or `HR_EMPLOYEE_MANAGE`; frontend visibility is not a security boundary.
- The platform administrator role and employee job designation are different concepts.
- Changes and status transitions record actor, timestamp, before/after state and reason where supplied.
- Historical records are preserved; separated employees are not hard-deleted.
- API input is validated and relational references must belong to the same organisation.
- Sensitive personal data is minimised. Bank/tax data, payroll and private documents will be introduced only with dedicated permissions and controls.
- Attendance, leave and payroll will be separate domain modules; they must not be implemented as mutable columns on the employee record.

## Approved HRMS policy decisions (2026-10-13)

The implementation contract and safety gaps are tracked in [Agent 1 HRMS Policy Decisions](../implementation/agent-1-hrms-policy-decisions.md).

- Organization working week defaults to six days and may be configured to five. The API/database default is authoritative; a UI fallback is not sufficient.
- Attendance supports permission-controlled correction requests and direct HR correction. Corrections preserve prior approved values and include actor, timestamp, reason and linkage. Attendance write permission alone does not grant historical-correction permission.
- Leave types are configurable paid/unpaid types. Policy attributes must explicitly govern eligibility, balance requirements, negative balances and approvals.
- Leave balances derive from an append-only transaction ledger. Reversals/adjustments compensate for earlier entries; posted transactions are not overwritten or deleted.
- Holiday policy uses an India-oriented baseline with explicit organization overrides and jurisdiction/source provenance. Central, state, local and optional holidays must remain distinguishable; never seed an unverified list as official.
- Lifecycle approval requirements are configurable per transition and permission, enforced by the API, with self-approval prohibited.
- Calendar scope is organization-wide. Manager access is limited to reporting employees and separately granted capabilities.
- Approved history is preserved. Status changes, attendance corrections and ledger reversals remain auditable.
- Employee lifecycle, organization, attendance, leave, approvals, ledger, permissions and audit foundations are to be delivered together, with tests and a handoff.

A feature is not complete merely because a People portal control calls an endpoint. API authorization, database invariants, migration compatibility and negative-path tests are required. If a policy cannot yet be enforced safely, the corresponding write path must remain unavailable and the blocker documented.
