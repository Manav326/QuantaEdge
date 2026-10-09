-- V32: extend staff governance without mutating the already-released V31 migration.
-- Staff sessions must be exclusive, while a parent-selected-child session may retain both user_id and student_id.
do $staff_session_constraint$
declare
  constraint_row record;
  definition text;
begin
  for constraint_row in
    select conname, pg_get_constraintdef(oid) as definition
    from pg_constraint
    where conrelid='public.auth_session'::regclass and contype='c'
  loop
    definition := constraint_row.definition;
    if definition ilike '%user_id IS NOT NULL%'
       and definition ilike '%student_id IS NOT NULL%'
       and definition ilike '% OR %' then
      execute format('alter table public.auth_session drop constraint %I', constraint_row.conname);
    end if;
  end loop;
end $staff_session_constraint$;

do $staff_session_identity$
begin
  if not exists (
    select 1 from pg_constraint
    where conrelid='public.auth_session'::regclass
      and conname='auth_session_identity_required'
  ) then
    alter table public.auth_session
      add constraint auth_session_identity_required
      check (
        (staff_id is not null and user_id is null and student_id is null)
        or
        (staff_id is null and (user_id is not null or student_id is not null))
      );
  end if;
end $staff_session_identity$;

-- Revoke prior sessions for admin identities and retain their old account rows only as inactive references.
update auth_session s
set revoked_at=now()
where s.revoked_at is null
  and s.user_id in (
    select u.id
    from user_account u
    join staff_account staff on staff.mobile_e164=u.mobile_e164 and staff.role='ADMIN'
  );

update user_account u
set active=false, updated_at=now()
where exists (
  select 1 from staff_account staff
  where staff.mobile_e164=u.mobile_e164 and staff.role='ADMIN'
);

-- Do not advertise the account-management permission until dedicated parent/student management APIs enforce it.
delete from staff_permission_grant where permission_key='USER_MANAGE';
delete from staff_permission_catalog where permission_key='USER_MANAGE';

alter table staff_audit_log add column if not exists details text;

alter table question
  add column if not exists reviewed_by_staff_id bigint references staff_account(id) on delete set null;

create index if not exists idx_question_reviewed_by_staff
  on question(reviewed_by_staff_id, reviewed_at desc)
  where reviewed_by_staff_id is not null;
