-- Supplementary Class 7 Science sections and canonical curriculum audit metadata.
do $$
declare sid bigint; cid bigint;
begin
  select s.id into sid from curriculum_subject s join curriculum_class c on c.id=s.class_id where c.code='7' and s.code='science';
  insert into curriculum_chapter(subject_id,code,display_name,description,sort_order,curriculum_source,content_status,textbook_chapter_no,teaching_order,pedagogy_note)
  values
  (sid,'appendix','परिशिष्ट','पुस्तक में दिए गए संदर्भ/अतिरिक्त सामग्री का guided revision section।',19,'SCERT Bihar Science Part-2','PUBLISHED',19,19,'Use after the 18 core chapters as reference and revision, not as a prerequisite.'),
  (sid,'human-body','मानव शरीर के आंतरिक अंग','मानव शरीर के प्रमुख आंतरिक अंगों का नाम, स्थान और मूल कार्य।',20,'SCERT Bihar Science Part-2','PUBLISHED',20,20,'Use as visual recap after the core biology chapters.')
  on conflict(subject_id,code) do update set
    display_name=excluded.display_name,description=excluded.description,sort_order=excluded.sort_order,
    curriculum_source=excluded.curriculum_source,content_status='PUBLISHED',
    textbook_chapter_no=excluded.textbook_chapter_no,teaching_order=excluded.teaching_order,pedagogy_note=excluded.pedagogy_note;

  update curriculum_chapter set active=false,content_status='ARCHIVED'
  where subject_id=sid and code='forests';
end $$;

insert into app_metadata(key,value) values ('schema','canonical-bihar-board-curriculum-v7')
on conflict(key) do update set value=excluded.value;
