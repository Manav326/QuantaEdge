-- V81: employee self-service identity and HRMS-specific access-management templates.
alter table staff_account drop constraint if exists staff_account_role_check;
alter table staff_account add constraint staff_account_role_check
  check (role in (
    'ADMIN','MANAGER','MODERATOR','CONTENT_AUTHOR','CONTENT_REVIEWER','PUBLISHER',
    'HR','ACCOUNTANT','PEOPLE_MANAGER','EMPLOYEE'
  ));

alter table staff_additional_role drop constraint if exists staff_additional_role_role_code_check;
alter table staff_additional_role add constraint staff_additional_role_role_code_check
  check (role_code in ('HR','ACCOUNTANT','PEOPLE_MANAGER','EDUCATION_CONTENT_ADMIN','EMPLOYEE'));

insert into staff_permission_catalog(permission_key,display_name,description) values
 ('HR_ACCESS_MANAGE','Manage HRMS login access','Create employee login access and assign HRMS-only roles.'),
 ('HR_SELF_SERVICE_VIEW','View own employee information','View the signed-in employee profile and their own permitted HR records.')
on conflict(permission_key) do update
set display_name=excluded.display_name, description=excluded.description;

insert into staff_role_permission(role_code, permission_key) values
 ('EMPLOYEE','HR_SELF_SERVICE_VIEW'),
 ('HR','HR_ACCESS_MANAGE')
on conflict(role_code, permission_key) do nothing;

insert into staff_permission_grant(staff_id, permission_key)
select s.id, 'HR_ACCESS_MANAGE'
from staff_account s
where lower(s.username)='hrms_admin'
on conflict(staff_id, permission_key) do nothing;
