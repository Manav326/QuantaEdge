-- Complete the default transition matrix for every existing organization.
-- Separated employment is terminal; all other status changes require HR_EMPLOYEE_MANAGE.
insert into hr_lifecycle_approval_rule(organization_id,transition_key,from_status,to_status,required_permission_key)
select o.id,src.status||'_TO_'||dst.status,src.status,dst.status,'HR_EMPLOYEE_MANAGE'
from hr_organization o
cross join (values ('DRAFT'),('ACTIVE'),('ON_LEAVE'),('NOTICE_PERIOD'),('SEPARATED'),('INACTIVE')) src(status)
cross join (values ('DRAFT'),('ACTIVE'),('ON_LEAVE'),('NOTICE_PERIOD'),('SEPARATED'),('INACTIVE')) dst(status)
where o.active and src.status<>dst.status and src.status<>'SEPARATED'
on conflict(organization_id,transition_key) do nothing;
