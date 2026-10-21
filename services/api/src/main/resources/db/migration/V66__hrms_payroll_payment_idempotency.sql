-- A payroll run must never have more than one settled payment per employee.
-- Multiple pending/failed attempts are retained for reconciliation history.
create unique index uq_hr_payroll_payment_settled_employee
  on hr_payroll_payment(run_id,employee_id)
  where payment_status='SETTLED';
