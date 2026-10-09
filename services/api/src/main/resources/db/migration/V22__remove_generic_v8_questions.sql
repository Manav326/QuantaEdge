-- V22: remove obsolete generic QA questions from the early V8 teaching-path seed.
-- Canonical chapter/lesson shells are not completed teaching content. This migration
-- does not synthesize assessment content; only reviewed, original questions should be active.
update question
set active=false,
    review_status='REJECTED'
where active=true
  and source_kind='AUTHOR_CREATED'
  and source_ref is null
  and question_type='MCQ'
  and prompt like '% — सीखने के दौरान सबसे सही अभ्यास क्या है?';

insert into app_metadata(key,value)
values ('schema','remove-v8-generic-question-shell-v22')
on conflict(key) do update set value=excluded.value;
