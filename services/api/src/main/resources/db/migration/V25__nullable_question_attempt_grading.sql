-- V25: allow persisted learner responses that require manual or later grading.
alter table student_question_attempt
  alter column correct drop not null;

insert into app_metadata(key,value) values ('schema','nullable-question-attempt-grading-v25')
on conflict(key) do update set value=excluded.value;
