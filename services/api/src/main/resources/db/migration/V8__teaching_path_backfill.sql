-- Backfill the same three-stage teaching path for chapters added/corrected by V6/V7.
do $$
declare r record; obj_id bigint; v_lesson_id bigint; v_question_id bigint; stage record; answer text;
begin
  for r in
    select ch.id,ch.code,ch.display_name,ch.description
    from curriculum_chapter ch
    join curriculum_subject s on s.id=ch.subject_id
    join curriculum_class c on c.id=s.class_id
    where c.code in ('6','7','8') and s.code in ('maths','science')
      and ch.active=true and ch.content_status='PUBLISHED'
      and not exists (select 1 from lesson l where l.chapter_id=ch.id and l.active=true)
  loop
    for stage in select * from (values
      (1,'foundation','पहले समझें',8),
      (2,'guided-practice','उदाहरण के साथ करें',10),
      (3,'mastery','खुद करके पक्का करें',12)
    ) v(n,code,title,minutes)
    loop
      insert into learning_objective(chapter_id,code,title,description,sort_order)
      values(r.id,'objective-'||stage.n,stage.title||': '||r.display_name,r.description,stage.n)
      on conflict(chapter_id,code) do update set title=excluded.title,description=excluded.description,sort_order=excluded.sort_order
      returning id into obj_id;

      insert into lesson(chapter_id,objective_id,code,title,summary,estimated_minutes,status,sort_order)
      values(
        r.id,obj_id,r.code||'-'||stage.code,stage.title||': '||r.display_name,
        case stage.n
          when 1 then 'पहले परिचित उदाहरण से शुरुआत करें। '||r.description||' फिर मुख्य शब्दों और विचारों को अपने शब्दों में समझाएं।'
          when 2 then 'अब शिक्षक के साथ एक उदाहरण करें: जानकारी पहचानें → सही विचार/नियम चुनें → कदम क्रम से करें → उत्तर जाँचें।'
          else 'अब बिना सहारे नया उदाहरण हल करें। उत्तर के साथ यह भी बताएं कि आपने यही तरीका क्यों चुना।'
        end,
        stage.minutes,'PUBLISHED',stage.n)
      on conflict(chapter_id,code) do update set
        title=excluded.title,summary=excluded.summary,estimated_minutes=excluded.estimated_minutes,
        status='PUBLISHED',sort_order=excluded.sort_order,objective_id=excluded.objective_id
      returning id into v_lesson_id;

      insert into lesson_block(lesson_id,sequence_no,block_type,content)
      values
      (v_lesson_id,1,'EXPLANATION',jsonb_build_object(
        'heading',case stage.n when 1 then r.display_name||' को समझें' when 2 then 'शिक्षक के साथ करके देखें' else 'अब खुद समझाएं' end,
        'body',r.description,
        'teachingMove',case stage.n when 1 then 'Activate prior knowledge, introduce vocabulary, connect to daily life.' when 2 then 'Model one worked example and think aloud.' else 'Remove scaffolding and ask for reasoning.' end,
        'keyPoints',jsonb_build_array('मुख्य अवधारणा पहचानें।','उदाहरण देखकर तरीका समझें।','उत्तर का कारण बताना सीखें।')
      )),
      (v_lesson_id,2,'CHALLENGE',jsonb_build_object(
        'title',case stage.n when 1 then 'देखो और सोचो' when 2 then 'साथ में हल करें' else 'अब तुम्हारी बारी' end,
        'prompt',case stage.n when 1 then 'इस विषय का एक रोज़मर्रा का उदाहरण पहचानिए और बताइए कि उसमें क्या हो रहा है।'
          when 2 then 'ऊपर दिए विचार को एक नए उदाहरण पर लागू कीजिए। पहले तरीका बताइए, फिर उत्तर दीजिए।'
          else 'एक नया उदाहरण खुद बनाइए, हल कीजिए और अपने उत्तर की जाँच कीजिए।' end,
        'hint','पहले मुख्य शब्द/मात्रा/आकृति/घटना पहचानें, फिर संबंधित नियम या कारण चुनें।'
      )),
      (v_lesson_id,3,'AI_HELP',jsonb_build_object(
        'title','अटकें तो QuantaEdge से पूछें',
        'actions',jsonb_build_array('EASY_EXPLANATION','EXAMPLE','DIAGRAM','STEP_BY_STEP','EXAM_ANSWER'),
        'socratic',true
      )),
      (v_lesson_id,4,'SUMMARY',jsonb_build_object(
        'points',jsonb_build_array(r.description,'मुख्य विचार को अपने शब्दों में दोहराएँ।','एक नए उदाहरण में लागू करके mastery जाँचें।')
      ))
      on conflict do nothing;

      insert into question(lesson_id,objective_id,question_type,prompt,explanation,difficulty,sort_order)
      values(
        v_lesson_id,obj_id,'MCQ',
        r.display_name||' — सीखने के दौरान सबसे सही अभ्यास क्या है?',
        case stage.n
          when 1 then 'मुख्य अवधारणा को पहचानकर रोज़मर्रा के उदाहरण से जोड़ना।'
          when 2 then 'सही तरीका चुनना, कदम क्रम से करना और उत्तर जाँचना।'
          else 'नए उदाहरण में उत्तर के साथ कारण समझाना।'
        end,
        case when stage.n=1 then 'FOUNDATION' when stage.n=2 then 'CORE' else 'CHALLENGE' end,
        1)
      returning id into v_question_id;

      insert into question_option(question_id,option_key,label,is_correct,sort_order)
      values
        (v_question_id,'A',case stage.n
          when 1 then 'मुख्य अवधारणा को पहचानकर उदाहरण से जोड़ना।'
          when 2 then 'सही तरीका चुनकर कदम क्रम से करना और उत्तर जाँचना।'
          else 'नए उदाहरण में उत्तर के साथ कारण समझाना।'
        end,true,1),
        (v_question_id,'B','केवल उत्तर याद कर लेना',false,2),
        (v_question_id,'C','बिना प्रश्न पढ़े अनुमान लगाना',false,3),
        (v_question_id,'D','दूसरे अध्याय का नियम लगा देना',false,4);
    end loop;
  end loop;
end $$;

insert into app_metadata(key,value) values ('schema','teaching-path-backfill-v8')
on conflict(key) do update set value=excluded.value;
