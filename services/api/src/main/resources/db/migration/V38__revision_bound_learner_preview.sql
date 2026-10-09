-- V38: persist preview confirmation against the saved micro-topic revision.
-- Any saved content edit increments content_revision and clears the previous confirmation.
alter table lesson
  add column if not exists content_revision bigint not null default 1,
  add column if not exists preview_checked_revision bigint,
  add column if not exists preview_checked_at timestamptz,
  add column if not exists preview_checked_by_staff_id bigint;

do $preview_check_staff_fk$
begin
  if not exists (
    select 1
    from pg_constraint con
    where con.conrelid='public.lesson'::regclass
      and con.contype='f'
      and con.confrelid='public.staff_account'::regclass
      and exists (
        select 1 from pg_attribute att
        where att.attrelid=con.conrelid
          and att.attnum=any(con.conkey)
          and att.attname='preview_checked_by_staff_id'
      )
  ) then
    alter table public.lesson
      add constraint lesson_preview_checked_by_staff_fk
      foreign key (preview_checked_by_staff_id)
      references public.staff_account(id)
      on delete set null;
  end if;
end $preview_check_staff_fk$;

-- Preserve the readiness display for topics already live before this control existed.
-- Any subsequent edit moves the topic to draft and clears this acknowledgement.
update lesson
set preview_checked_revision=content_revision,
    preview_checked_at=coalesce(updated_at,now())
where status='PUBLISHED' and active=true
  and preview_checked_revision is null;

insert into app_metadata(key,value)
values ('schema','revision-bound-learner-preview-v38')
on conflict(key) do update set value=excluded.value;
