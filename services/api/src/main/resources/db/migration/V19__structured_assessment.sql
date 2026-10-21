-- V19: canonical stage cleanup and structured authored answer payloads.
update lesson
set status='ARCHIVED',active=false
where active=true
  and code='simple-equations-guided-practice'
  and exists(select 1 from lesson l2 where l2.chapter_id=lesson.chapter_id and l2.code='simple-equations-guided' and l2.active=true);

update question
set answer_payload=jsonb_build_object('kind','MATCH','value',
  jsonb_build_object('1','A','2','B','3','C'))
where active=true and source_kind='AUTHOR_CREATED' and question_type='MATCH';

update question
set answer_payload=jsonb_build_object('kind','ORDER','value',
  jsonb_build_array('B','C','D','A'))
where active=true and source_kind='AUTHOR_CREATED' and question_type='ORDER';

insert into app_metadata(key,value) values ('schema','structured-assessment-v19')
on conflict(key) do update set value=excluded.value;
