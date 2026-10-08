-- V10: secure question delivery and machine-checkable answer payloads.
alter table question add column if not exists answer_payload jsonb not null default '{}'::jsonb;

update question q
set answer_payload=jsonb_build_object(
  'kind', case when q.question_type in ('MCQ','TRUE_FALSE') then 'OPTION' else 'TEXT' end,
  'value', coalesce((
    select qo.option_key from question_option qo
    where qo.question_id=q.id and qo.is_correct=true order by qo.sort_order limit 1
  ), '')
)
where q.answer_payload='{}'::jsonb;

create index if not exists idx_question_source on question(source_kind,source_year,board,exam_format);
create index if not exists idx_question_tags on question using gin(tags);

insert into app_metadata(key,value)
values ('schema','secure-question-delivery-v10')
on conflict(key) do update set value=excluded.value;
