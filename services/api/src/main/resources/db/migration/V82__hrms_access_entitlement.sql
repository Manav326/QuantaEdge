-- V82: control HRMS entitlement independently of staff identity/application access.
alter table hr_employee
  add column if not exists hrms_access_enabled boolean not null default false;

update hr_employee e
set hrms_access_enabled = true, updated_at = now()
from staff_account s
where e.linked_staff_account_id = s.id
  and lower(s.username) = 'hrms_admin'
  and e.employment_status in ('ACTIVE','ON_LEAVE','NOTICE_PERIOD');

create index if not exists idx_hr_employee_hrms_access
  on hr_employee(linked_staff_account_id, employment_status)
  where hrms_access_enabled = true;
