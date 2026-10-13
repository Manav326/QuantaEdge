-- V89: preserve a deliberate, explicit team-scope marker without limiting the HR super admin.
delete from staff_role_permission
where role_code='HR' and permission_key='HR_MANAGER_TEAM_VIEW';

delete from staff_permission_grant g
using staff_account s
where g.staff_id=s.id
  and g.workspace_key='HRMS'
  and g.permission_key='HR_MANAGER_TEAM_VIEW'
  and lower(s.username)='hrms_admin';

-- People Manager role retains the effective permission. HrmsAccessController synchronizes
-- the direct marker used by legacy recursive-reporting SQL whenever this role is assigned.
insert into staff_role_permission(role_code,permission_key,workspace_key)
select 'PEOPLE_MANAGER','HR_MANAGER_TEAM_VIEW','HRMS'
where exists(select 1 from staff_permission_catalog where permission_key='HR_MANAGER_TEAM_VIEW')
on conflict(role_code,permission_key) do update
set workspace_key=excluded.workspace_key;
