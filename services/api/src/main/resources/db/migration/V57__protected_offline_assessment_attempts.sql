-- V57: protected, opt-in offline attempts for explicitly approved low-stakes assessments.
-- Offline packages contain only the server-filtered question snapshot; answer keys are never sent.
-- The server remains the only grader. Offline submissions may sync for 72 hours after the attempt deadline.
alter table assessment
  add column if not exists offline_enabled boolean not null default false;

alter table student_assessment_attempt
  add column if not exists offline_mode boolean not null default false,
  add column if not exists offline_sync_expires_at timestamptz,
  add column if not exists offline_package_token_hash text;

do $offline_attempt_constraint$
begin
  if not exists (select 1 from pg_constraint where conname='ck_assessment_offline_attempt_metadata') then
    alter table student_assessment_attempt
      add constraint ck_assessment_offline_attempt_metadata
      check (
        (offline_mode=false and offline_sync_expires_at is null and offline_package_token_hash is null)
        or
        (offline_mode=true and offline_sync_expires_at is not null and offline_package_token_hash is not null)
      ) not valid;
  end if;
end
$offline_attempt_constraint$;

insert into app_metadata(key,value)
values ('schema','protected-offline-assessment-attempts-v57')
on conflict(key) do update set value=excluded.value;
