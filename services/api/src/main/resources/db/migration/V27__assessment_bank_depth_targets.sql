-- V27: define a meaningful minimum practice bank per chapter without creating fake questions.
-- These values are editorial targets only; no question rows are inserted or auto-approved.
-- Non-applicable/optional formats have zero minimums until an editor records a genuine need.

update chapter_assessment_requirement r
set minimum_count = case
  when r.question_type='MCQ' then 5
  when r.question_type='TRUE_FALSE' then 2
  when r.question_type='INPUT' then 3
  when r.question_type='MATCH' then 1
  when r.question_type='ORDER' then 1
  when r.question_type='ASSERTION_REASON' and s.code='science' then 2
  when r.question_type='CASE_BASED' then 2
  when r.question_type='SHORT_ANSWER' then 3
  when r.question_type='LONG_ANSWER' then 1
  when r.question_type='NUMERICAL' and s.code='maths' then 3
  when r.question_type='DIAGRAM' and r.required then 1
  else 0
end,
updated_at=now()
from curriculum_ch ch
join curriculum_subject s on s.id=ch.subject_id
join curriculum_class c on c.id=s.class_id
where r.chapter_id=ch.id
  and c.code in ('6','7','8')
  and s.code in ('maths','science')
  and ch.active=true
  and ch.content_status='PUBLISHED';

insert into app_metadata(key,value)
values ('schema','assessment-bank-depth-targets-v27')
on conflict(key) do update set value=excluded.value;
