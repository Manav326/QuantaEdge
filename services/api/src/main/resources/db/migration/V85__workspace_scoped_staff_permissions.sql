-- V85: make direct permission grants and role templates application-scoped.
alter table staff_permission_grant
  add column if not exists workspace_key varchar(32) not null default 'EDUCATION_ADMIN';

update staff_permission_grant
set workspace_key = case
  when permission_key like 'HR\_%' escape '\' or permission_key='HR_SELF_SERVICE_VIEW' then 'HRMS'
  else 'EDUCATION_ADMIN'
end;

alter table staff_permission_grant drop constraint if exists staff_permission_grant_pkey;
alter table staff_permission_grant
  add constraint staff_permission_grant_pkey primary key (staff_id, workspace_key, permission_key);

create index if not exists idx_staff_permission_grant_workspace
  on staff_permission_grant(staff_id, workspace_key, permission_key);

alter table staff_role_permission
  add column if not exists workspace_key varchar(32) not null default 'HRMS';

update staff_role_permission
set workspace_key='EDUCATION_ADMIN'
where role_code='EDUCATION_CONTENT_ADMIN';

create index if not exists idx_staff_role_permission_workspace
  on staff_role_permission(workspace_key, role_code, permission_key);

-- Education audit visibility is not an HRMS permission, even for the HRMS bootstrap account.
delete from staff_permission_grant g
using staff_account s
where g.staff_id=s.id and lower(s.username)='hrms_admin'
  and g.permission_key='AUDIT_VIEW';

