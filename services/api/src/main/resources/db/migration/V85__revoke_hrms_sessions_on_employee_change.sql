-- V85: revoke HRMS sessions immediately when employment or HRMS entitlement is withdrawn.
create or replace function revoke_hrms_sessions_for_employee_access()
returns trigger
language plpgsql
as $$
begin
  if old.linked_staff_account_id is distinct from new.linked_staff_account_id
     and old.linked_staff_account_id is not null then
    update auth_session
    set revoked_at = now()
    where staff_id = old.linked_staff_account_id
      and workspace_key = 'HRMS'
      and revoked_at is null;
  end if;

  if new.linked_staff_account_id is not null
     and (
       new.hrms_access_enabled = false
       or new.employment_status not in ('ACTIVE','ON_LEAVE','NOTICE_PERIOD')
     ) then
    update auth_session
    set revoked_at = now()
    where staff_id = new.linked_staff_account_id
      and workspace_key = 'HRMS'
      and revoked_at is null;
  end if;

  return new;
end
$$;

drop trigger if exists trg_revoke_hrms_sessions_for_employee_access on hr_employee;
create trigger trg_revoke_hrms_sessions_for_employee_access
after update of linked_staff_account_id, hrms_access_enabled, employment_status
on hr_employee
for each row
execute function revoke_hrms_sessions_for_employee_access();
