-- V80: isolate HRMS sessions, support password login, and enable multi-role permission templates.
alter table auth_session
  add column if not exists workspace_key varchar(32),
  add column if not exists absolute_expires_at timestamptz;

update auth_session
set workspace_key = case when staff_id is not null then 'STAFF' else 'LEARNING' end
where workspace_key is null;

-- Existing sessions get a finite migration grace period. New sessions always receive their
-- absolute expiry at issue time; refresh operations may extend idle expiry but never this value.
update auth_session
set absolute_expires_at = coalesce(absolute_expires_at, now() + interval '8 hours')
where absolute_expires_at is null;

alter table auth_session alter column workspace_key set default 'LEARNING';
alter table auth_session alter column workspace_key set not null;

create index if not exists idx_auth_session_workspace_staff_active
  on auth_session(workspace_key, staff_id, absolute_expires_at)
  where staff_id is not null and revoked_at is null;

alter table staff_account
  add column if not exists username varchar(64),
  add column if not exists password_hash varchar(100),
  add column if not exists must_change_password boolean not null default false,
  add column if not exists password_changed_at timestamptz;

-- The first HRMS administrator is identified by username rather than requiring OTP/mobile.
-- A bootstrap account may therefore have no mobile. Normal invited staff still require mobile.
alter table staff_account alter column mobile_e164 drop not null;

create unique index if not exists uq_staff_account_username_lower
  on staff_account(lower(username))
  where username is not null;

alter table staff_account drop constraint if exists staff_account_role_check;
alter table staff_account add constraint staff_account_role_check
  check (role in ('ADMIN','MANAGER','MODERATOR','CONTENT_AUTHOR','CONTENT_REVIEWER','PUBLISHER','HR','ACCOUNTANT','PEOPLE_MANAGER'));

-- Use a deliberately temporary bootstrap password; HRMS blocks all operational APIs until it is
-- replaced with a strong password. This is never intended as a permanent production credential.
do $bootstrap$
declare
  bootstrap_staff_id bigint;
  initial_password_hash constant text := '$2a$12$vv2074FPhWNMKb9HDsP9hu3Av11EyJX/Z8cjDAaCShvZWbrV1m2bK';
begin
  select id into bootstrap_staff_id
  from staff_account
  where lower(username) = 'hrms_admin'
  limit 1;

  if bootstrap_staff_id is null then
    insert into staff_account(
      public_id, mobile_e164, display_name, role, active, username,
      password_hash, must_change_password, created_at, updated_at
    ) values (
      gen_random_uuid(), null, 'People Operations Super Admin', 'HR', true, 'HRMS_Admin',
      initial_password_hash, true, now(), now()
    ) returning id into bootstrap_staff_id;
  else
    -- This is a separate HRMS identity and is never repurposed from an education administrator.
    update staff_account
    set mobile_e164 = null,
        display_name = 'People Operations Super Admin',
        role = 'HR',
        active = true,
        password_hash = coalesce(password_hash, initial_password_hash),
        must_change_password = true,
        updated_at = now()
    where id = bootstrap_staff_id;
  end if;

  -- Seed HRMS-only powers directly. The account does not receive education content permissions
  -- or the global ADMIN role and therefore cannot silently become an Education Admin.
  insert into staff_permission_grant(staff_id, permission_key)
  select bootstrap_staff_id, permission_key
  from staff_permission_catalog
  where permission_key like 'HR\_%' escape '\'
     or permission_key = 'AUDIT_VIEW'
  on conflict(staff_id, permission_key) do nothing;

  -- The system bootstrap identity is not a synthetic employee record. HR employees are
  -- created only through the normal HRMS onboarding/recruitment workflow.
end
$bootstrap$;

create table if not exists staff_additional_role (
  staff_id bigint not null references staff_account(id) on delete cascade,
  role_code varchar(40) not null
    check (role_code in ('HR','ACCOUNTANT','PEOPLE_MANAGER','EDUCATION_CONTENT_ADMIN')),
  assigned_by_staff_id bigint references staff_account(id) on delete set null,
  assigned_at timestamptz not null default now(),
  primary key (staff_id, role_code)
);

create table if not exists staff_role_permission (
  role_code varchar(40) not null,
  permission_key varchar(64) not null references staff_permission_catalog(permission_key) on delete cascade,
  primary key (role_code, permission_key)
);

insert into staff_role_permission(role_code, permission_key)
select roles.role_code, p.permission_key
from (values
  ('HR','HR_EMPLOYEE_VIEW'),
  ('HR','HR_EMPLOYEE_MANAGE'),
  ('HR','HR_ATTENDANCE_VIEW'),
  ('HR','HR_ATTENDANCE_MANAGE'),
  ('HR','HR_LEAVE_VIEW'),
  ('HR','HR_LEAVE_MANAGE'),
  ('HR','HR_PAYROLL_VIEW'),
  ('HR','HR_PAYROLL_MANAGE'),
  ('HR','HR_MANAGER_TEAM_VIEW'),
  ('ACCOUNTANT','HR_EMPLOYEE_VIEW'),
  ('ACCOUNTANT','HR_PAYROLL_VIEW'),
  ('ACCOUNTANT','HR_PAYROLL_MANAGE'),
  ('ACCOUNTANT','HR_PAYROLL_APPROVE'),
  ('ACCOUNTANT','HR_PAYROLL_PAY'),
  ('PEOPLE_MANAGER','HR_EMPLOYEE_VIEW'),
  ('PEOPLE_MANAGER','HR_ATTENDANCE_VIEW'),
  ('PEOPLE_MANAGER','HR_LEAVE_VIEW'),
  ('PEOPLE_MANAGER','HR_MANAGER_TEAM_VIEW'),
  ('EDUCATION_CONTENT_ADMIN','CONTENT_VIEW'),
  ('EDUCATION_CONTENT_ADMIN','CONTENT_CREATE'),
  ('EDUCATION_CONTENT_ADMIN','CONTENT_EDIT'),
  ('EDUCATION_CONTENT_ADMIN','CONTENT_SUBMIT'),
  ('EDUCATION_CONTENT_ADMIN','CONTENT_REVIEW'),
  ('EDUCATION_CONTENT_ADMIN','CONTENT_PUBLISH')
) as roles(role_code, permission_key)
join staff_permission_catalog p on p.permission_key = roles.permission_key
on conflict(role_code, permission_key) do nothing;
