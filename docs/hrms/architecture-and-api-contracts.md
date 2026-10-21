# HRMS Architecture and API Contracts

This document records architecture and API/database contracts verified from current source and migrations. Items marked **not yet verified** are intentionally left open rather than guessed.

## Canonical identity

- `hr_employee.id` is the authoritative employment-record identifier.
- `hr_employee.public_id` is a public UUID; `employee_number` is a generated human-readable identifier.
- Employment is distinct from platform access: `hr_employee.linked_staff_account_id` is optional and unique, and the V60 migration explicitly keeps employment records separate from `staff_account`.
- Organization-scoped employee, department, designation and manager references must remain within the employee's organization.
- Attendance, teaching assignments, payroll and employee self-service must reference `hr_employee.id` and must not create a parallel employee identity.
- Existing employment status values: `DRAFT`, `ACTIVE`, `ON_LEAVE`, `NOTICE_PERIOD`, `SEPARATED`, `INACTIVE`.
- A separated employee requires an exit date; exit date cannot precede joining date. Manager chains must not contain cycles.

## Core HR API

Base path: `/api/v1/hr`.

| Method and path | Purpose | Permission observed |
|---|---|---|
| `GET /organization` | Read organization summary | `HR_EMPLOYEE_VIEW` |
| `GET /organization/{id}/work-schedule` | Read working-days setting | `HR_EMPLOYEE_VIEW` |
| `PUT /organization/{id}/work-schedule` | Set working days to 5 or 6 | `HR_EMPLOYEE_MANAGE` |
| `GET /employment-types` | List active employment types | `HR_EMPLOYEE_VIEW` |
| `GET /departments` | List departments and parent labels | `HR_EMPLOYEE_VIEW` |
| `POST /departments` | Create a department | `HR_EMPLOYEE_MANAGE` |
| `GET /designations` | List designations | `HR_EMPLOYEE_VIEW` |
| `POST /designations` | Create a designation | `HR_EMPLOYEE_MANAGE` |
| `GET /employees` | Search/paginate employee directory | `HR_EMPLOYEE_VIEW` |
| `GET /employees/{id}` | Read employee record | `HR_EMPLOYEE_VIEW` |
| `GET /employees/{id}/history` | Read employment history | `HR_EMPLOYEE_VIEW` |
| `POST /employees` | Create employment record | `HR_EMPLOYEE_MANAGE` |
| `PUT /employees/{id}` | Update employee profile and references | `HR_EMPLOYEE_MANAGE` |
| `PATCH /employees/{id}/status` | Change employment status | `HR_EMPLOYEE_MANAGE` |

Employee history is append-only audit context in `hr_employee_history`, recording change type, effective date, before/after state, reason, actor and timestamp. Keep it intact when extending lifecycle workflows.

## Attendance and leave data

Verified from migrations `V61__hrms_attendance_and_leave.sql`, `V62__hrms_configurable_working_week.sql`, `V63__hrms_holiday_calendar.sql`, and `V64__hrms_leave_balance_ledger.sql`:

- `hr_attendance_record.employee_id` references `hr_employee.id`; one record per employee and work date.
- Attendance states: `PRESENT`, `ABSENT`, `HALF_DAY`, `REMOTE`, `ON_LEAVE`, `HOLIDAY`.
- `hr_leave_request.employee_id` references `hr_employee.id`; request states: `PENDING`, `APPROVED`, `REJECTED`, `CANCELLED`.
- Leave balance is represented by `hr_leave_balance_transaction` ledger entries, not a manually maintained balance total. The migration intentionally seeds no employee balances.
- Observed permission keys include `HR_ATTENDANCE_VIEW`, `HR_ATTENDANCE_MANAGE`, `HR_LEAVE_VIEW`, `HR_LEAVE_MANAGE`, `HR_LEAVE_BALANCE_VIEW`, and `HR_LEAVE_BALANCE_MANAGE`.
- Organization identity must be validated against the employee before any organization-scoped ledger mutation.

## Capabilities that still need implementation or source verification

The following capabilities must be implemented or verified from current controllers/services before the HRMS can be considered complete:

- Exact attendance/leave route list, request and response schemas, and actor/employee access rules.
- Academic assignment table/API names, effective-date inclusivity, approval transitions, replacement semantics, and permission keys.
- Payroll period and component schemas, rounding rules, approval/locking lifecycle, payment reconciliation identifiers, and payslip visibility rules.
- Employee self-service identity mapping and which fields an employee may view or update.
- Shared migration allocation for future work. Current highest observed migration is V64; any next version must be rechecked against latest main before use.

## Contract change checklist

Before a shared contract changes:
1. Update this document in the same or an earlier commit.
2. Name all consumers affected in `integration-status.md`.
3. Preserve backward compatibility where feasible; otherwise document migration and rollout order.
4. Add authorization, validation, audit and regression tests.
5. Verify the combined API build and CI before marking integration complete.
