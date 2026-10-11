-- V48: QuantaEdge-owned Class 6 learning path aligned to the selected current Class VI book inventory.
-- Old records are archived/deactivated, never deleted. New lessons/questions are imported by the
-- authenticated draft importer; nothing is auto-approved or published by this migration.

-- Preserve auditability while removing the older mixed chapter pathway from Class 6 delivery.
update question q
set active=false,
    review_status='REJECTED',
    review_notes=concat_ws(' ', nullif(btrim(q.review_notes), ''),
      'Legacy Class 6 seed archived during QuantaEdge learning-path refresh; historical record retained.')
from lesson l
join curriculum_chapter ch on ch.id=l.chapter_id
join curriculum_subject s on s.id=ch.subject_id
join curriculum_class c on c.id=s.class_id
where q.lesson_id=l.id and c.code='6' and s.code in ('maths','science');

update lesson l
set active=false, status='ARCHIVED'
from curriculum_chapter ch
join curriculum_subject s on s.id=ch.subject_id
join curriculum_class c on c.id=s.class_id
where l.chapter_id=ch.id and c.code='6' and s.code in ('maths','science');

update chapter_concept cc
set status='ARCHIVED', updated_at=now()
from curriculum_chapter ch
join curriculum_subject s on s.id=ch.subject_id
join curriculum_class c on c.id=s.class_id
where cc.chapter_id=ch.id and c.code='6' and s.code in ('maths','science');

update curriculum_chapter ch
set active=false, content_status='ARCHIVED'
from curriculum_subject s
join curriculum_class c on c.id=s.class_id
where ch.subject_id=s.id and c.code='6' and s.code in ('maths','science');

-- Canonical Class 6 inventory; titles are QuantaEdge chapter display names.
with source(class_code,subject_code,chapter_code,title,description,chapter_no) as (
  values
  ('6','maths','patterns-in-mathematics','गणित में पैटर्न','संख्या, आकृति और क्रियाओं में दोहराव तथा बढ़ने के नियम पहचानना।',1),
  ('6','maths','lines-and-angles','रेखाएँ और कोण','बिंदु, रेखा, रेखाखंड, किरण, कोण और उनके मापन को पहचानना।',2),
  ('6','maths','number-play','संख्याओं का खेल','संख्या-रचनाओं, अंकों के नियमों, अनुमान और संख्या पहेलियों से तर्क करना।',3),
  ('6','maths','data-handling','आँकड़ों का प्रबंधन और प्रस्तुतीकरण','आँकड़े इकट्ठा करना, तालिका और चित्रलेख बनाना तथा आलेख से निष्कर्ष निकालना।',4),
  ('6','maths','prime-time','अभाज्य समय','गुणनखंड, गुणज, अभाज्य और भाज्य संख्याएँ तथा समान गुणनखंडों की पहचान करना।',5),
  ('6','maths','perimeter-area','परिमाप और क्षेत्रफल','परिमाप आकृति की सीमा की कुल लंबाई है; क्षेत्रफल समतल आकृति के भीतर के भाग का माप है।',6),
  ('6','maths','fractions','भिन्न','भिन्न बराबर भागों, हिस्सों और मापन को दर्शाते हैं; अंश लिए गए भाग और हर कुल बराबर भागों की संख्या बताता है।',7),
  ('6','maths','constructions','रचनाओं के साथ खेलना','ज्यामितीय रचना में उपकरण, माप और क्रमबद्ध चरण महत्त्वपूर्ण हैं; केवल देखने से आकृति का सटीक माप तय नहीं होता।',8),
  ('6','maths','symmetry','सममिति','सममिति में आकृति के दो भाग किसी रेखा के दोनों ओर एक-दूसरे के दर्पण प्रतिबिंब जैसे होते हैं।',9),
  ('6','maths','integers-beyond-zero','शून्य के दूसरी ओर','धनात्मक और ऋणात्मक संख्याएँ शून्य के दोनों ओर संख्या रेखा पर स्थित होती हैं; उनका क्रम और दूरी तापमान, ऊँचाई और लाभ-हानि जैसे संदर्भों में उपयोगी है।',10),

  ('6','science','wonderful-world-science','विज्ञान का अनूठा संसार','अवलोकन, प्रश्न, जाँच, प्रमाण और निष्कर्ष विज्ञान सीखने की मुख्य प्रक्रिया हैं।',1),
  ('6','science','diversity-living-world','सजीव जगत में विविधता','पौधों और जंतुओं में आकार, संरचना, आवास, गति और भोजन के आधार पर विविधता पहचानना।',2),
  ('6','science','mindful-eating','उचित आहार—स्वस्थ शरीर का आधार','संतुलित आहार में विभिन्न पोषक तत्व, पर्याप्त पानी और उचित भोजन-आदतें शामिल होती हैं।',3),
  ('6','science','exploring-magnets','चुंबकों को जानें','चुंबकों के गुण, ध्रुव, आकर्षण-विकर्षण और कंपास से दिशा समझना।',4),
  ('6','science','measurement-length-motion','लंबाई एवं गति का मापन','मानक इकाइयों, सही मापन, गति के प्रकार और दूरी को दर्ज करने की प्रक्रिया को समझना।',5),
  ('6','science','materials-around-us','हमारे आस-पास की सामग्री','वस्तुओं को पदार्थों के गुण, पारदर्शिता, घुलनशीलता, कठोरता और उपयोग के आधार पर समूहित करना।',6),
  ('6','science','temperature-measurement','ताप एवं उसका मापन','तापमान गर्म या ठंडा होने की माप है; उपयुक्त थर्मामीटर और सुरक्षित मापन विधि का उपयोग जरूरी है।',7),
  ('6','science','states-of-water','जल की विविध अवस्थाओं की यात्रा','पानी ठोस बर्फ, द्रव जल और गैस जलवाष्प के रूप में पाया जाता है; ऊष्मा के आदान-प्रदान से अवस्थाएँ बदलती हैं।',8),
  ('6','science','separation-methods','दैनिक जीवन में पृथक्करण विधियाँ','मिश्रण के घटकों के आकार, घुलनशीलता, घनत्व या वाष्पशीलता जैसे गुणों के अनुसार पृथक्करण विधि चुनना।',9),
  ('6','science','living-characteristics','सजीव—विशेषताओं का अन्वेषण','सजीवों में पोषण, वृद्धि, श्वसन, अपशिष्ट निष्कासन, उत्तेजना के प्रति प्रतिक्रिया और प्रजनन जैसी प्रक्रियाएँ होती हैं।',10),
  ('6','science','natures-treasures','प्रकृति की अमूल्य संपदा','मिट्टी, जल, वायु, चट्टानें, खनिज और जैव विविधता जीवन को सहारा देते हैं; संसाधनों का सावधानी से उपयोग करना चाहिए।',11),
  ('6','science','beyond-earth','पृथ्वी से परे','सौरमंडल, पृथ्वी, चंद्रमा, ग्रह, तारे और आकाशीय पिंडों को व्यवस्थित अवलोकन से समझना।',12)
)
insert into curriculum_chapter(
  subject_id, code, display_name, description, sort_order, active, content_status,
  curriculum_source, curriculum_source_verified, textbook_chapter_no, teaching_order, pedagogy_note
)
select s.id,src.chapter_code,src.title,src.description,src.chapter_no,true,'DRAFT',
       'QuantaEdge original instructional content',false,src.chapter_no,src.chapter_no,
       'Teach from prior knowledge to concept explanation, worked model, guided reasoning, independent assessment and recap. Human review required before publication.'
from source src
join curriculum_class c on c.code=src.class_code
join curriculum_subject s on s.class_id=c.id and s.code=src.subject_code
on conflict(subject_id,code) do update set
  display_name=excluded.display_name,
  description=excluded.description,
  sort_order=excluded.sort_order,
  active=true,
  content_status='DRAFT',
  curriculum_source='QuantaEdge original instructional content',
  curriculum_source_url=null,
  curriculum_source_edition=null,
  curriculum_source_pages=null,
  curriculum_source_verified=false,
  textbook_chapter_no=excluded.textbook_chapter_no,
  teaching_order=excluded.teaching_order,
  pedagogy_note=excluded.pedagogy_note;

-- Make every newly imported micro-topic a first-class chapter concept in Admin/CMS.
-- The existing importer creates lessons first, so the AFTER INSERT trigger creates concept rows
-- before lesson questions are written. Re-imports update matching concept metadata idempotently.
create or replace function qe_sync_class6_lesson_concept() returns trigger
language plpgsql
as $$
declare
  class_code text;
  subject_code text;
begin
  select c.code,s.code into class_code,subject_code
  from curriculum_chapter ch
  join curriculum_subject s on s.id=ch.subject_id
  join curriculum_class c on c.id=s.class_id
  where ch.id=new.chapter_id;

  if class_code='6' and subject_code in ('maths','science') then
    insert into chapter_concept(chapter_id,code,title,description,concept_order,status,updated_at)
    values(new.chapter_id,new.code,new.title,nullif(new.summary,''),new.sort_order,'DRAFT',now())
    on conflict(chapter_id,code) do update set
      title=excluded.title,
      description=excluded.description,
      concept_order=excluded.concept_order,
      status=case when chapter_concept.status='PUBLISHED' then 'PUBLISHED' else 'DRAFT' end,
      updated_at=now();
  end if;
  return new;
end $$;

drop trigger if exists qe_lesson_concept_sync on lesson;
create trigger qe_lesson_concept_sync
after insert or update of title,summary,sort_order on lesson
for each row execute function qe_sync_class6_lesson_concept();

-- Link a practice/test question to its micro-topic concept using the authored subtopic/topic.
create or replace function qe_assign_class6_question_concept() returns trigger
language plpgsql
as $$
declare
  chapter_key bigint;
  lesson_code text;
  class_code text;
  subject_code text;
  matched_concept_id bigint;
begin
  if new.concept_id is not null or new.lesson_id is null then
    return new;
  end if;
  select l.chapter_id,l.code,c.code,s.code
    into chapter_key,lesson_code,class_code,subject_code
  from lesson l
  join curriculum_chapter ch on ch.id=l.chapter_id
  join curriculum_subject s on s.id=ch.subject_id
  join curriculum_class c on c.id=s.class_id
  where l.id=new.lesson_id;

  if class_code='6' and subject_code in ('maths','science') then
    select cc.id into matched_concept_id
    from chapter_concept cc
    where cc.chapter_id=chapter_key
      and lower(btrim(cc.title))=lower(btrim(coalesce(nullif(new.subtopic,''),nullif(new.topic,''),'' )))
      and cc.status <> 'ARCHIVED'
    order by cc.concept_order,cc.id
    limit 1;

    if matched_concept_id is null then
      select cc.id into matched_concept_id
      from chapter_concept cc
      where cc.chapter_id=chapter_key and cc.code=lesson_code and cc.status <> 'ARCHIVED'
      limit 1;
    end if;
    new.concept_id=matched_concept_id;
  end if;
  return new;
end $$;

drop trigger if exists qe_question_concept_sync on question;
create trigger qe_question_concept_sync
before insert or update of lesson_id,topic,subtopic on question
for each row execute function qe_assign_class6_question_concept();

-- Reset legacy depth targets for Class 6 to the agreed 13-item bank profile.
-- Required: five MCQs and four true/false items. Other authored formats are retained
-- exactly as provided in each question bank and may vary by topic/subject.
update chapter_assessment_requirement r
set minimum_count=0,required=false,rationale='Optional format; the chapter-specific authored bank determines whether it is appropriate.',
    updated_at=now()
from curriculum_chapter ch
join curriculum_subject s on s.id=ch.subject_id
join curriculum_class c on c.id=s.class_id
where r.chapter_id=ch.id and c.code='6' and s.code in ('maths','science');

insert into chapter_assessment_requirement(chapter_id,question_type,minimum_count,required,rationale)
select ch.id,t.question_type,t.minimum_count,true,t.rationale
from curriculum_chapter ch
join curriculum_subject s on s.id=ch.subject_id
join curriculum_class c on c.id=s.class_id
cross join (values
 ('MCQ',5,'QuantaEdge Class 6 question-bank baseline: five MCQs per chapter.'),
 ('TRUE_FALSE',4,'QuantaEdge Class 6 question-bank baseline: four true/false items per chapter.')
) as t(question_type,minimum_count,rationale)
where c.code='6' and s.code in ('maths','science')
  and ch.active=true and ch.content_status='DRAFT'
on conflict(chapter_id,question_type) do update set
  minimum_count=excluded.minimum_count,required=true,rationale=excluded.rationale,updated_at=now();

-- Align CMS requirements to the actual draft block contract while retaining review gates.
insert into lesson_requirement(chapter_id,requirement_code,description,min_count,required)
select ch.id,r.code,r.description,r.min_count,r.required
from curriculum_chapter ch
join curriculum_subject s on s.id=ch.subject_id
join curriculum_class c on c.id=s.class_id
cross join (values
 ('PREREQUISITE','A prior-knowledge bridge is present.',1,true),
 ('EXPLANATION','An original teaching explanation is present.',1,true),
 ('WORKED_EXAMPLE','A worked example and reasoning steps are present.',1,true),
 ('RECAP','A concise learner-facing recap is present.',1,true),
 ('GUIDED_PRACTICE','Practice is supplied through the micro-topic question bank; a separate block is optional.',0,false),
 ('INDEPENDENT_PRACTICE','The independent practice question bank is attached to the micro-topic.',0,false),
 ('VISUAL_OR_NOT_REQUIRED','A useful visual is present, or a reviewer records why one is not needed.',0,false),
 ('NCERT_OR_TEXTBOOK_MAPPING','Curriculum/source coverage remains pending editorial verification.',0,false),
 ('BOARD_FORMAT_COVERAGE','Question formats remain a reviewable assessment-bank decision.',0,false),
 ('SOURCE_TAGGED_QUESTIONS','Questions are original QuantaEdge drafts; source-backed items are not assumed.',0,false)
) as r(code,description,min_count,required)
where c.code='6' and s.code in ('maths','science') and ch.active=true and ch.content_status='DRAFT'
on conflict(chapter_id,requirement_code) do update set
  description=excluded.description,min_count=excluded.min_count,required=excluded.required,updated_at=now();

insert into app_metadata(key,value)
values ('schema','ncert-class6-quantaedge-authored-learning-path-v48')
on conflict(key) do update set value=excluded.value;
