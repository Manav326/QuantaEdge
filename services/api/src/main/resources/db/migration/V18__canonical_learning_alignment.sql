-- V18: canonical lesson stages and concept/question alignment.
update lesson
set status='ARCHIVED', active=false
where active=true
  and code like '%-guided-practice'
  and code not like '%simple-equations-guided-practice%'
  and exists (
    select 1 from lesson l2
    where l2.chapter_id=lesson.chapter_id
      and l2.code=replace(lesson.code,'-guided-practice','-guided')
      and l2.active=true
  );

update question q
set concept_id = case
  when l.code like '%-foundation' then (
    select cc.id from chapter_concept cc where cc.chapter_id=l.chapter_id order by cc.concept_order,cc.id limit 1
  )
  when l.code like '%-guided' then (
    select cc.id from chapter_concept cc where cc.chapter_id=l.chapter_id order by cc.concept_order,cc.id offset 1 limit 1
  )
  when l.code like '%-mastery' then (
    select cc.id from chapter_concept cc where cc.chapter_id=l.chapter_id order by cc.concept_order,cc.id offset 2 limit 1
  )
  else q.concept_id
end
from lesson l
where q.lesson_id=l.id and l.active=true;

update chapter_concept cc
set status='PUBLISHED'
where status='READY_FOR_REVIEW'
  and exists(select 1 from lesson l where l.chapter_id=cc.chapter_id and l.active=true and l.status='PUBLISHED');

insert into app_metadata(key,value) values ('schema','canonical-learning-path-v18')
on conflict(key) do update set value=excluded.value;
