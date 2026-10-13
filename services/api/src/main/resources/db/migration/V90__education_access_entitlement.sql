-- V90: keep Education Admin account enablement independent from HRMS employee access.
alter table staff_account
  add column if not exists education_access_enabled boolean not null default true,
  add column if not exists education_access_configured boolean not null default true;

-- HRMS bootstrap is not an Educational Admin identity.
update staff_account
set education_access_enabled=false, education_access_configured=false, updated_at=now()
where lower(coalesce(username,''))='hrms_admin';

create index if not exists idx_staff_account_education_access
  on staff_account(id, education_access_enabled)
  where education_access_enabled=true;
