-- V82: keep HRMS account administration and payroll execution restricted.
delete from staff_role_permission
where role_code='HR' and permission_key='HR_ACCESS_MANAGE';

-- HR staff may inspect payroll records when granted the base HR role, but payroll
-- execution/payment remains an accounting responsibility unless explicitly granted.
delete from staff_role_permission
where role_code='HR'
  and permission_key in ('HR_PAYROLL_MANAGE','HR_PAYROLL_APPROVE','HR_PAYROLL_PAY');
