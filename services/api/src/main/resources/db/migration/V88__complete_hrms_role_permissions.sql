-- V88: complete workspace-scoped permission templates for existing HRMS workflows.
insert into staff_role_permission(role_code, permission_key, workspace_key)
select mappings.role_code, p.permission_key, 'HRMS'
from (values
  ('HR','HR_EMPLOYEE_VIEW'),
  ('HR','HR_EMPLOYEE_MANAGE'),
  ('HR','HR_ATTENDANCE_VIEW'),
  ('HR','HR_ATTENDANCE_MANAGE'),
  ('HR','HR_ATTENDANCE_CORRECTION_REVIEW'),
  ('HR','HR_LEAVE_VIEW'),
  ('HR','HR_LEAVE_MANAGE'),
  ('HR','HR_EMPLOYEE_CHANGE_REVIEW'),
  ('HR','HR_LEAVE_BALANCE_VIEW'),
  ('HR','HR_LEAVE_BALANCE_MANAGE'),
  ('HR','HR_HOLIDAY_VIEW'),
  ('HR','HR_HOLIDAY_MANAGE'),
  ('HR','HR_APPROVAL_POLICY_MANAGE'),
  ('HR','HR_RECRUITMENT_VIEW'),
  ('HR','HR_RECRUITMENT_MANAGE'),
  ('HR','HR_PERFORMANCE_VIEW'),
  ('HR','HR_PERFORMANCE_MANAGE'),
  ('HR','HR_ASSET_VIEW'),
  ('HR','HR_ASSET_MANAGE'),
  ('HR','HR_EXIT_VIEW'),
  ('HR','HR_EXIT_MANAGE'),
  ('HR','HR_ACADEMIC_ASSIGNMENT_VIEW'),
  ('HR','HR_ACADEMIC_ASSIGNMENT_MANAGE'),
  ('HR','HR_ACADEMIC_ASSIGNMENT_APPROVE'),
  ('HR','HR_MANAGER_TEAM_VIEW'),
  ('HR','HR_PAYROLL_VIEW'),
  ('HR','TEACHING_ASSIGNMENT_VIEW'),
  ('HR','CLASS_SCHEDULE_POLICY_MANAGE'),

  ('ACCOUNTANT','HR_EMPLOYEE_VIEW'),
  ('ACCOUNTANT','HR_PAYROLL_VIEW'),
  ('ACCOUNTANT','HR_PAYROLL_MANAGE'),
  ('ACCOUNTANT','HR_PAYROLL_APPROVE'),
  ('ACCOUNTANT','HR_PAYROLL_PAY'),

  ('PEOPLE_MANAGER','HR_EMPLOYEE_VIEW'),
  ('PEOPLE_MANAGER','HR_ATTENDANCE_VIEW'),
  ('PEOPLE_MANAGER','HR_LEAVE_VIEW'),
  ('PEOPLE_MANAGER','HR_EMPLOYEE_CHANGE_REVIEW'),
  ('PEOPLE_MANAGER','HR_ATTENDANCE_CORRECTION_REVIEW'),
  ('PEOPLE_MANAGER','HR_MANAGER_TEAM_VIEW'),

  ('EMPLOYEE','HR_SELF_SERVICE_VIEW')
) as mappings(role_code, permission_key)
join staff_permission_catalog p on p.permission_key=mappings.permission_key
on conflict(role_code, permission_key) do update
set workspace_key=excluded.workspace_key;

-- The HRMS bootstrap administrator may manage the complete HRMS workspace, including
-- teaching-assignment policies, without gaining an Education Admin permission bypass.
insert into staff_permission_grant(staff_id, workspace_key, permission_key)
select s.id, 'HRMS', p.permission_key
from staff_account s
join staff_permission_catalog p on p.permission_key in (
  'HR_ACCESS_MANAGE',
  'TEACHING_ASSIGNMENT_VIEW',
  'CLASS_SCHEDULE_POLICY_MANAGE'
)
where lower(s.username)='hrms_admin'
on conflict(staff_id, workspace_key, permission_key) do nothing;
