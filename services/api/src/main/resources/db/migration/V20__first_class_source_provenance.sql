-- V20: first-class source provenance for source-backed questions.
alter table question
  add column if not exists source_id bigint references content_source(id) on delete set null;

create index if not exists idx_question_source_id on question(source_id);

insert into app_metadata(key,value)
values ('schema','first-class-source-provenance-v20')
on conflict(key) do update set value=excluded.value;
