-- V33: allow purpose-scoped staff OTPs while keeping parent and staff identities independent.
-- V15 only allowed LOGIN and SIGNUP. STAFF_LOGIN was added in application code but the
-- database check constraint was not updated, so inserting a staff OTP raised SQLSTATE 23514
-- and surfaced as HTTP 500.
do $otp_purpose_constraint$
declare
  constraint_row record;
begin
  for constraint_row in
    select conname
    from pg_constraint
    where conrelid='public.otp_challenge'::regclass
      and contype='c'
      and pg_get_constraintdef(oid) ilike '%purpose%'
  loop
    execute format('alter table public.otp_challenge drop constraint %I', constraint_row.conname);
  end loop;
end $otp_purpose_constraint$;

alter table public.otp_challenge
  add constraint otp_challenge_purpose_supported
  check (purpose in ('LOGIN','SIGNUP','STAFF_LOGIN'));

-- Previous staff migration logic deactivated the matching parent account because it assumed
-- one mobile could represent only one identity. Authentication now separates these identities
-- by OTP purpose, so no parent/customer row is changed merely because a staff identity exists.
