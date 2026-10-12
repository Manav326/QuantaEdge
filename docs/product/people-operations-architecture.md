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
