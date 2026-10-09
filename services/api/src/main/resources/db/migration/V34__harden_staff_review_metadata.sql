-- V34: make staff review metadata resilient on databases that were upgraded from an older build.
-- The submit-for-review route updates reviewed_by_staff_id. Older local databases may have the
-- route code without this column, which PostgreSQL reports as an uncaught 500.
alter table question
  add column if not exists reviewed_by_staff_id bigint;

do $reviewed_by_staff_fk$
begin
  if not exists (
    select 1
    from pg_constraint
    where conrelid='public.question'::regclass
      and conname='question_reviewed_by_staff_fk'
  ) then
    alter table public.question
      add constraint question_reviewed_by_staff_fk
      foreign key (reviewed_by_staff_id)
      references public.staff_account(id)
      on delete set null;
  end if;
end $reviewed_by_staff_fk$;

create index if not exists idx_question_reviewed_by_staff
  on question(reviewed_by_staff_id, reviewed_at desc)
  where reviewed_by_staff_id is not null;

alter table staff_audit_log
  add column if not exists details text;
