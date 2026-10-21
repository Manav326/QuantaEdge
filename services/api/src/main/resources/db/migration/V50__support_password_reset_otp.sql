-- Keep every OTP purpose accepted by AuthService valid in the database.
-- PASSWORD_RESET was added to the login API after the initial OTP purpose constraint.
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

alter table otp_challenge
  add constraint otp_challenge_purpose_supported
  check (purpose in ('LOGIN','SIGNUP','STAFF_LOGIN','PASSWORD_RESET'));

insert into app_metadata(key,value) values ('schema','auth-otp-password-reset-purpose-v50')
on conflict(key) do update set value=excluded.value;
