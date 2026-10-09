-- Source alignment is recorded separately from authored lesson text. Existing rows
-- remain unverified until an editor checks the actual source and page references.
alter table curriculum_chapter
  add column if not exists curriculum_source_url text,
  add column if not exists curriculum_source_edition varchar(160),
  add column if not exists curriculum_source_pages varchar(160),
  add column if not exists curriculum_source_verified boolean not null default false;

alter table lesson
  add column if not exists alignment_source_title varchar(300),
  add column if not exists alignment_source_url text,
  add column if not exists alignment_source_edition varchar(160),
  add column if not exists alignment_page_range varchar(160),
  add column if not exists alignment_source_verified boolean not null default false;

alter table question
  add column if not exists review_status varchar(20) not null default 'DRAFT',
  add column if not exists review_notes varchar(1200),
  add column if not exists reviewed_at timestamptz;

alter table question drop constraint if exists question_review_status_check;
alter table question add constraint question_review_status_check
  check (review_status in ('DRAFT','REVIEW','APPROVED','REJECTED'));

insert into app_metadata(key,value)
values ('schema','curriculum-source-review-v10')
on conflict(key) do update set value=excluded.value;
