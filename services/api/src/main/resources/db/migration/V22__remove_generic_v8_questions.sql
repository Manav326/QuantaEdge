-- V22: remove obsolete generic teaching-path questions created by the early V8 shell.
-- V11 provides the canonical assessment spine. These V8 records were only QA scaffolding
-- and must never remain in the learner-facing question set.
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
