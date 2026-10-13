-- V90: keep Education Admin account enablement independent from HRMS employee access.
alter table staff_account
  add column if not exists education_access_enabled boolean not null default true;

-- The HRMS bootstrap identity is intentionally not an Educational Admin identity.
update staff_account
set education_access_enabled=false, updated_at=now()
where lower(coalesce(username,''))='hrms_admin';

create index if not exists idx_staff_account_education_access
  on staff_account(id, education_access_enabled)
  where education_access_enabled=true;
