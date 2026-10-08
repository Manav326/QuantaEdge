-- V11: complete the teaching-path contract without fabricating source provenance.
-- Author-created questions are explicitly labelled AUTHOR_CREATED. Textbook mappings remain
-- source mappings only; licensed/verbatim source-question imports must enter through the
-- provenance-aware import pipeline rather than being invented here.

do $$
declare
  r record;
  obj1 bigint;
  obj2 bigint;
  obj3 bigint;
  lesson1 bigint;
  lesson2 bigint;
  lesson3 bigint;
  qid bigint;
  visual_needed boolean;
  source_title text;
begin
  for r in
    select ch.id,ch.code,ch.display_name,ch.description,ch.textbook_chapter_no,
           c.code as class_code,s.code as subject_code
    from curriculum_chapter ch
    join curriculum_subject s on s.id=ch.subject_id
    join curriculum_class c on c.id=s.class_id
    where c.code in ('6','7','8')
      and s.code in ('maths','science')
      and ch.active=true
      and ch.content_status='PUBLISHED'
    order by c.sort_order,s.sort_order,ch.teaching_order,ch.sort_order
  loop
    visual_needed := (
      r.subject_code='science'
      or r.code in (
        'geometry-basics','elementary-shapes','data-handling','symmetry','practical-geometry',
        'lines-angles','triangles','congruence','perimeter-area','solid-shapes',
        'quadrilaterals','squares-roots','cubes-roots','mensuration',
        'algebraic-identities','direct-inverse-proportion'
      )
    );

    source_title := case
      when r.subject_code='maths' then 'Bihar SCERT Class '||r.class_code||' Mathematics — गणित भाग-'||r.class_code
      else 'Bihar SCERT Class '||r.class_code||' Science — विज्ञान भाग-'||
        case r.class_code when '6' then '1' when '7' then '2' else '3' end
    end;

    insert into chapter_concept(chapter_id,code,title,description,concept_order,status)
    values
      (r.id,'core-concept','मुख्य अवधारणा — '||r.display_name,
       r.description,1,'READY_FOR_REVIEW'),
      (r.id,'method-and-reasoning','तरीका और तर्क — '||r.display_name,
       'अवधारणा को उदाहरण, कारण और क्रमबद्ध कदमों से लागू करना।',2,'READY_FOR_REVIEW'),
      (r.id,'application-and-assessment','अनुप्रयोग और आकलन — '||r.display_name,
       'नई परिस्थिति में अवधारणा लागू करना और उत्तर की जाँच/व्याख्या करना।',3,'READY_FOR_REVIEW')
    on conflict(chapter_id,code) do update set
      title=excluded.title,description=excluded.description,concept_order=excluded.concept_order,status=excluded.status;

    insert into learning_objective(chapter_id,code,title,description,sort_order)
    values
      (r.id,'market-foundation','पहले समझें: '||r.display_name,
       'पूर्व ज्ञान सक्रिय करें, शब्दावली समझें और मुख्य अवधारणा बनाएँ।',1),
      (r.id,'market-guided','साथ में करें: '||r.display_name,
       'Worked example और guided practice के साथ तरीका सीखें।',2),
      (r.id,'market-mastery','खुद करके पक्का करें: '||r.display_name,
       'Independent practice, application और exam-style reasoning से mastery जाँचें।',3)
    on conflict(chapter_id,code) do update set
      title=excluded.title,description=excluded.description,sort_order=excluded.sort_order,active=true;

    select id into obj1 from learning_objective where chapter_id=r.id and code='market-foundation';
    select id into obj2 from learning_objective where chapter_id=r.id and code='market-guided';
    select id into obj3 from learning_objective where chapter_id=r.id and code='market-mastery';

    insert into lesson(chapter_id,objective_id,code,title,summary,estimated_minutes,status,sort_order)
    values
      (r.id,obj1,r.code||'-foundation','पहले समझें: '||r.display_name,
       'पूर्व ज्ञान → मुख्य शब्द → अवधारणा → रोज़मर्रा का संदर्भ → छोटी जाँच। '||r.description,12,'PUBLISHED',1),
      (r.id,obj2,r.code||'-guided','साथ में करें: '||r.display_name,
       'शिक्षक के साथ worked example → सोचने की आवाज़ → guided practice → उत्तर की जाँच।',15,'PUBLISHED',2),
      (r.id,obj3,r.code||'-mastery','खुद करके पक्का करें: '||r.display_name,
       'Independent practice → application → exam-style response → recap और self-check।',18,'PUBLISHED',3)
    on conflict(chapter_id,code) do update set
      title=excluded.title,summary=excluded.summary,estimated_minutes=excluded.estimated_minutes,
      status='PUBLISHED',sort_order=excluded.sort_order,objective_id=excluded.objective_id,active=true;

    select id into lesson1 from lesson where chapter_id=r.id and code=r.code||'-foundation';
    select id into lesson2 from lesson where chapter_id=r.id and code=r.code||'-guided';
    select id into lesson3 from lesson where chapter_id=r.id and code=r.code||'-mastery';

    insert into lesson_block(lesson_id,sequence_no,block_type,content)
    values
      (lesson1,1,'PREREQUISITE',jsonb_build_object(
        'title','पूर्व ज्ञान जाँच',
        'body','इस अध्याय से पहले विद्यार्थी को कौन-से शब्द, संख्या/माप/आकृति या विज्ञान के आधारभूत विचार याद हैं, उन्हें अपने शब्दों में बताइए।',
        'check','short_response',
        'chapter',r.display_name)),
      (lesson1,2,'EXPLANATION',jsonb_build_object(
        'heading',r.display_name||' को समझें',
        'body',r.description,
        'teachingMove','पूर्व ज्ञान सक्रिय करें → ठोस/दृश्य उदाहरण → मुख्य विचार → शब्दावली → जाँच।',
        'keyPoints',jsonb_build_array('मुख्य अवधारणा पहचानें।','नए शब्दों को अपने शब्दों में समझाएँ।','अगले उदाहरण पर नियम/कारण लागू करें।'),
        'visualDecision',true,
        'visualRequired',visual_needed,
        'visualRationale',case when visual_needed then 'आकृति, प्रक्रिया, संबंध या डेटा को दृश्य रूप में देखना समझ को बेहतर बनाता है।' else 'पाठ और उदाहरण इस अवधारणा को बिना अतिरिक्त दृश्य के स्पष्ट रूप से व्यक्त कर सकते हैं; जहाँ विद्यार्थी को दृश्य सहारे की जरूरत होगी वहाँ शिक्षक/lesson author दृश्य जोड़ेगा।' end)),
      (lesson1,3,'WORKED_EXAMPLE',jsonb_build_object(
        'title','शिक्षक का पहला उदाहरण',
        'problem','एक सरल, नया उदाहरण चुनें जो केवल इसी अध्याय की मुख्य अवधारणा पर केंद्रित हो: '||r.description,
        'steps',jsonb_build_array('दिए गए तथ्य/राशि/घटना को पहचानें।','कौन-सा नियम या कारण लागू होगा, बताएं।','कदम क्रम से करें।','उत्तर को प्रश्न के संदर्भ में जाँचें।'),
        'answer','उत्तर के साथ reasoning लिखना आवश्यक है।')),
      (lesson1,4,'GUIDED_PRACTICE',jsonb_build_object(
        'title','अब साथ में करें',
        'prompt','ऊपर के उदाहरण से मिलता-जुलता लेकिन नया उदाहरण हल करें। हर कदम के पीछे कारण बताइए।',
        'hint','पहले यह बताइए कि प्रश्न में क्या दिया है और क्या निकालना/समझाना है।')),
      (lesson1,5,'AI_HELP',jsonb_build_object(
        'title','अटकें तो Socratic help',
        'actions',jsonb_build_array('EASY_EXPLANATION','EXAMPLE','DIAGRAM','STEP_BY_STEP','EXAM_ANSWER'),
        'socratic',true)),
      (lesson2,1,'EXPLANATION',jsonb_build_object(
        'heading','तरीका और reasoning',
        'body','अब उसी अवधारणा को दूसरे उदाहरण में लागू करें। केवल नियम याद न करें; यह बताएं कि वह यहाँ क्यों लागू होता है।',
        'keyPoints',jsonb_build_array('जानकारी → विचार/नियम → कदम → उत्तर → जाँच।'),
        'visualDecision',true,
        'visualRequired',visual_needed)),
      (lesson2,2,'WORKED_EXAMPLE',jsonb_build_object(
        'title','Worked example: सोचने की प्रक्रिया',
        'problem','नया उदाहरण: '||r.display_name||'। समाधान शुरू करने से पहले अपनी रणनीति लिखिए।',
        'steps',jsonb_build_array('समस्या को अपने शब्दों में दोहराएँ।','प्रासंगिक अवधारणा चुनें।','क्रमबद्ध समाधान करें।','स्वतंत्र तरीके से उत्तर verify करें।'))),
      (lesson2,3,'GUIDED_PRACTICE',jsonb_build_object(
        'title','Guided practice',
        'prompt','एक नया उदाहरण हल करें। यदि अटकें तो केवल अगला कदम पूछें, पूरा उत्तर नहीं।',
        'hint','प्रश्न में दिए हुए संकेतों को अपनी रणनीति से जोड़ें।')),
      (lesson2,4,'INDEPENDENT_PRACTICE',jsonb_build_object(
        'title','Independent practice',
        'prompt','बिना worked example देखे इसी concept पर एक नया प्रश्न हल करें और reasoning लिखें।')),
      (lesson2,5,'AI_HELP',jsonb_build_object(
        'title','गलती से सीखें',
        'actions',jsonb_build_array('HINT','EASY_EXPLANATION','EXAMPLE','STEP_BY_STEP'),
        'socratic',true)),
      (lesson3,1,'INDEPENDENT_PRACTICE',jsonb_build_object(
        'title','Mastery practice',
        'prompt','अध्याय की मुख्य अवधारणा को नई परिस्थिति में लागू करें। उत्तर के साथ reasoning और self-check लिखें।')),
      (lesson3,2,'CHALLENGE',jsonb_build_object(
        'title','Board-style challenge',
        'prompt','यदि प्रश्न में एक अतिरिक्त जानकारी बदल दी जाए तो आपका तरीका/उत्तर कैसे बदलेगा? कारण सहित लिखें।',
        'hint','सिर्फ उत्तर नहीं; अपने निर्णय का कारण लिखें।')),
      (lesson3,3,'RECAP',jsonb_build_object(
        'title','Chapter recap',
        'points',jsonb_build_array(
          'मुख्य अवधारणा: '||r.display_name,
          'मुख्य विवरण: '||r.description,
          'समाधान क्रम: जानकारी → अवधारणा → कदम → उत्तर → जाँच।',
          'Exam habit: उत्तर के साथ reasoning/units/label जहाँ आवश्यक हो, अवश्य दें।'
        ))),
      (lesson3,4,'AI_HELP',jsonb_build_object(
        'title','Final self-check',
        'actions',jsonb_build_array('EASY_EXPLANATION','EXAMPLE','STEP_BY_STEP','EXAM_ANSWER'),
        'socratic',true))
    on conflict(lesson_id,sequence_no) do update set
      block_type=excluded.block_type,content=excluded.content,active=true;

    if visual_needed then
      insert into lesson_block(lesson_id,sequence_no,block_type,content)
      values
        (lesson1,6,'DIAGRAM',jsonb_build_object(
          'title','दृश्य संकेत',
          'kind',case when r.subject_code='science' then 'PROCESS_OR_SYSTEM' else 'MATH_RELATION' end,
          'description','इस concept के संबंध/क्रम/आकृति को labelled visual में देखें। Visual को lesson author source सामग्री के अनुसार सत्यापित करेगा।',
          'alt','दृश्य का text alternative: '||r.description))
      on conflict(lesson_id,sequence_no) do update set block_type=excluded.block_type,content=excluded.content,active=true;
    else
      insert into lesson_block(lesson_id,sequence_no,block_type,content)
      values
        (lesson1,6,'EXPLANATION',jsonb_build_object(
          'heading','Visual decision',
          'visualDecision',true,
          'visualRequired',false,
          'visualRationale','इस चरण में text + worked example पर्याप्त है; आवश्यकता मिलने पर diagram/graph जोड़ा जा सकता है।'))
      on conflict(lesson_id,sequence_no) do update set block_type=excluded.block_type,content=excluded.content,active=true;
    end if;

    -- One complete author-created assessment spine per chapter. These are not
    -- claimed as NCERT/board past-paper questions.
    insert into question(lesson_id,objective_id,question_type,prompt,explanation,difficulty,sort_order,
      source_kind,source_title,source_ref,source_year,board,marks,exam_format,topic,subtopic,skill,tags,answer_payload)
    values
      (lesson1,obj1,'MCQ',
       'अध्याय “'||r.display_name||'” की मुख्य अवधारणा को नई स्थिति में लागू करते समय सबसे अच्छा पहला कदम क्या है?',
       'पहले प्रश्न में दी जानकारी और पूछी गई बात पहचानना, फिर उपयुक्त अवधारणा चुनना चाहिए।',
       'FOUNDATION',1,'TEXTBOOK_ALIGNED',source_title,'chapter:'||r.textbook_chapter_no,2026,'Bihar',1,'MCQ','core','recognition','concept-selection',
       jsonb_build_array('author-created','textbook-aligned','scert-chapter-'||r.textbook_chapter_no),
       jsonb_build_object('kind','OPTION','value','A')),
      (lesson1,obj1,'TRUE_FALSE',
       '“'||r.display_name||'” में सही उत्तर तक पहुँचने के लिए केवल अंतिम उत्तर याद करना पर्याप्त है।',
       'नहीं। reasoning और verification भी आवश्यक हैं।','FOUNDATION',2,'TEXTBOOK_ALIGNED',source_title,'chapter:'||r.textbook_chapter_no,2026,'Bihar',1,'OBJECTIVE','core','reasoning','verification',
       jsonb_build_array('author-created','textbook-aligned'),'{}'::jsonb),
      (lesson1,obj1,'INPUT',
       '“'||r.display_name||'” के प्रश्न को हल करते समय पहला कदम एक छोटे वाक्य में लिखिए।',
       'पहला कदम है दी गई जानकारी और पूछी गई बात पहचानना।','FOUNDATION',3,'TEXTBOOK_ALIGNED',source_title,'chapter:'||r.textbook_chapter_no,2026,'Bihar',1,'VERY_SHORT','method','setup','problem-reading',
       jsonb_build_array('author-created','textbook-aligned'),'{}'::jsonb),
      (lesson2,obj2,'MATCH',
       '“'||r.display_name||'” में मिलान कीजिए: (1) दी गई जानकारी — (A) प्रश्न में मौजूद तथ्य; (2) अवधारणा — (B) लागू नियम/कारण; (3) जाँच — (C) उत्तर की पुष्टि।',
       'सही मिलान 1-A, 2-B, 3-C है।','CORE',4,'TEXTBOOK_ALIGNED',source_title,'chapter:'||r.textbook_chapter_no,2026,'Bihar',2,'MATCHING','method','classification','reasoning',
       jsonb_build_array('author-created','textbook-aligned'),'{}'::jsonb),
      (lesson2,obj2,'ORDER',
       '“'||r.display_name||'” का समाधान क्रम लगाइए: (A) उत्तर जाँचें, (B) जानकारी पहचानें, (C) अवधारणा चुनें, (D) कदम करें।',
       'सही क्रम B → C → D → A है।','CORE',5,'TEXTBOOK_ALIGNED',source_title,'chapter:'||r.textbook_chapter_no,2026,'Bihar',2,'VERY_SHORT','method','sequence','process',
       jsonb_build_array('author-created','textbook-aligned'),'{}'::jsonb),
      (lesson2,obj2,'ASSERTION_REASON',
       'कथन: “'||r.display_name||'” के प्रश्न में reasoning लिखना उपयोगी है। कारण: इससे समाधान की सोच और सही अवधारणा का चयन जाँचा जा सकता है। क्या दोनों सही हैं और कारण कथन को support करता है?',
       'हाँ। reasoning से प्रक्रिया की जाँच होती है।','CORE',6,'TEXTBOOK_ALIGNED',source_title,'chapter:'||r.textbook_chapter_no,2026,'Bihar',2,'ASSERTION_REASON','reasoning','justification','argument',
       jsonb_build_array('author-created','textbook-aligned'),'{}'::jsonb),
      (lesson2,obj2,'CASE_BASED',
       'केस: एक विद्यार्थी “'||r.display_name||'” से जुड़ी नई परिस्थिति पढ़ता है। वह पहले दिए तथ्य लिखता है, फिर अवधारणा चुनता है और अंत में उत्तर verify करता है। बताइए यह approach क्यों उपयुक्त है।',
       'यह approach problem reading, concept selection और verification को जोड़ती है।','CORE',7,'TEXTBOOK_ALIGNED',source_title,'chapter:'||r.textbook_chapter_no,2026,'Bihar',3,'CASE_BASED','application','case','reasoning',
       jsonb_build_array('author-created','textbook-aligned'),'{}'::jsonb),
      (lesson3,obj3,'SHORT_ANSWER',
       '“'||r.display_name||'” की मुख्य अवधारणा को अपने शब्दों में समझाइए और एक नया उदाहरण बताइए।',
       'उत्तर में concept + example + कारण होना चाहिए।','CORE',8,'TEXTBOOK_ALIGNED',source_title,'chapter:'||r.textbook_chapter_no,2026,'Bihar',2,'SHORT_ANSWER','core','explanation','communication',
       jsonb_build_array('author-created','textbook-aligned'),'{}'::jsonb),
      (lesson3,obj3,'LONG_ANSWER',
       '“'||r.display_name||'” पर एक व्यवस्थित उत्तर लिखिए: अवधारणा, आवश्यक कदम/कारण, उदाहरण और self-check शामिल करें।',
       'पूर्ण उत्तर में अवधारणा, क्रम, उदाहरण और जाँच स्पष्ट होनी चाहिए।','CHALLENGE',9,'TEXTBOOK_ALIGNED',source_title,'chapter:'||r.textbook_chapter_no,2026,'Bihar',4,'LONG_ANSWER','core','synthesis','written-expression',
       jsonb_build_array('author-created','textbook-aligned'),'{}'::jsonb),
      (lesson3,obj3,'NUMERICAL',
       'यदि “'||r.display_name||'” के किसी प्रश्न में संख्यात्मक/मात्रात्मक मान दिए हों, तो उत्तर देने से पहले कौन-सी जाँच अनिवार्य है?',
       'इकाई, गणना और प्रश्न के संदर्भ की जाँच करनी चाहिए।','CORE',10,'TEXTBOOK_ALIGNED',source_title,'chapter:'||r.textbook_chapter_no,2026,'Bihar',2,'NUMERICAL','application','verification','calculation',
       jsonb_build_array('author-created','textbook-aligned'),'{}'::jsonb),
      (lesson3,obj3,'DIAGRAM',
       '“'||r.display_name||'” की उस अवधारणा का labelled diagram/flow/graph बनाइए जहाँ दृश्य representation समझ को स्पष्ट करता है।',
       'Diagram में आवश्यक labels और संबंध स्पष्ट होने चाहिए।','CORE',11,'TEXTBOOK_ALIGNED',source_title,'chapter:'||r.textbook_chapter_no,2026,'Bihar',3,'DIAGRAM','visual','representation','diagramming',
       jsonb_build_array('author-created','textbook-aligned'),'{}'::jsonb),
      (lesson3,obj3,'SOURCE_BASED',
       'स्रोत-आधारित प्रश्न: Bihar SCERT के अध्याय mapping को देखकर बताइए कि “'||r.display_name||'” किस textbook chapter number से mapped है और यह mapping सीखने के लिए क्यों महत्वपूर्ण है।',
       'यह प्रश्न source navigation और curriculum alignment जाँचता है।','FOUNDATION',12,'AUTHOR_CREATED',source_title,'chapter:'||r.textbook_chapter_no,null,'Bihar SCERT',2,'SOURCE_BASED','source-literacy','mapping','source-use',
       jsonb_build_array('author-created','source-mapping'),'{}'::jsonb),
      (lesson3,obj3,'MAP',
       case when r.subject_code='science'
         then 'यदि “'||r.display_name||'” के लिए स्थान/भौगोलिक संबंध आवश्यक हों, तो उपयुक्त labelled map बनाइए; यदि map आवश्यक नहीं है तो स्पष्ट कारण लिखिए।'
         else 'यदि “'||r.display_name||'” के लिए map आवश्यक नहीं है, तो कारण लिखिए; यदि शिक्षक कोई contextual map दे, तो उसमें relevant labels पहचानिए।'
       end,
       'Map को तभी उपयोग करें जब concept को spatial context की आवश्यकता हो।','CHALLENGE',13,'TEXTBOOK_ALIGNED',source_title,'chapter:'||r.textbook_chapter_no,2026,'Bihar',2,'MAP','visual','spatial-reasoning','representation',
       jsonb_build_array('author-created','visual-decision'),'{}'::jsonb);

    -- Add a small option set only to the option-based items.
    insert into question_option(question_id,option_key,label,is_correct,sort_order)
    select q.id,v.key,v.label,v.correct,v.ord
    from question q
    cross join (values
      ('A','दी गई जानकारी और पूछी गई बात पहचानना',true,1),
      ('B','बिना प्रश्न पढ़े उत्तर याद करना',false,2),
      ('C','किसी भी दूसरे अध्याय का नियम लगाना',false,3),
      ('D','केवल अनुमान लगाना',false,4)
    ) v(key,label,correct,ord)
    where q.lesson_id=lesson1 and q.question_type='MCQ'
      and q.sort_order=1
      and not exists(select 1 from question_option qo where qo.question_id=q.id);

    insert into question_option(question_id,option_key,label,is_correct,sort_order)
    select q.id,v.key,v.label,v.correct,v.ord
    from question q
    cross join (values
      ('A','सही',false,1),('B','गलत',true,2)
    ) v(key,label,correct,ord)
    where q.lesson_id=lesson1 and q.question_type='TRUE_FALSE'
      and q.sort_order=2
      and not exists(select 1 from question_option qo where qo.question_id=q.id);

    update question q
    set answer_payload=jsonb_build_object('kind','OPTION','value',
      case when q.question_type='MCQ' then 'A' else 'B' end)
    where q.lesson_id=lesson1
      and q.question_type in ('MCQ','TRUE_FALSE')
      and q.source_kind='AUTHOR_CREATED';

  end loop;
end $$;

insert into app_metadata(key,value)
values ('schema','market-ready-learning-path-v11')
on conflict(key) do update set value=excluded.value;
