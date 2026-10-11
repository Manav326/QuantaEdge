-- V49: Align Class 7-8 tracks with the selected current NCERT Hindi inventory.
-- Legacy rows are archived, never deleted. New chapters are DRAFT and source verification remains false.
-- Math Part II content is a Hindi translation draft based on the listed English reference; editorial source/edition review is mandatory.

update question q
set active=false,
    review_status='REJECTED',
    review_notes=concat_ws(' ', nullif(btrim(q.review_notes), ''),
      'Legacy Class 7-8 learning path archived during QuantaEdge curriculum alignment; historical record retained.')
from lesson l
join curriculum_chapter ch on ch.id=l.chapter_id
join curriculum_subject s on s.id=ch.subject_id
join curriculum_class c on c.id=s.class_id
where q.lesson_id=l.id and c.code in ('7','8') and s.code in ('maths','science');

update lesson l
set active=false, status='ARCHIVED'
from curriculum_chapter ch
join curriculum_subject s on s.id=ch.subject_id
join curriculum_class c on c.id=s.class_id
where l.chapter_id=ch.id and c.code in ('7','8') and s.code in ('maths','science');

update chapter_concept cc
set status='ARCHIVED', updated_at=now()
from curriculum_chapter ch
join curriculum_subject s on s.id=ch.subject_id
join curriculum_class c on c.id=s.class_id
where cc.chapter_id=ch.id and c.code in ('7','8') and s.code in ('maths','science');

update curriculum_chapter ch
set active=false, content_status='ARCHIVED'
from curriculum_subject s
join curriculum_class c on c.id=s.class_id
where ch.subject_id=s.id and c.code in ('7','8') and s.code in ('maths','science');

with source(class_code,subject_code,chapter_code,title,description,source_title,source_url,chapter_no) as (
values
  ('7','maths','large-numbers-around-us','हमारे आस-पास की बड़ी संख्याएँ','बड़ी संख्याओं को पढ़ना, लिखना, तुलना करना और संदर्भ में समझना।','Class VII Mathematics — Ganita Prakash Part I (Hindi)','https://ncert.nic.in/textbook.php?ghgp1=7-7',1),
  ('7','maths','arithmetic-expressions','अंकगणितीय व्यंजक','संख्याओं और संक्रियाओं से बने व्यंजकों को क्रमबद्ध नियमों के अनुसार पढ़ना और हल करना।','Class VII Mathematics — Ganita Prakash Part I (Hindi)','https://ncert.nic.in/textbook.php?ghgp1=7-7',2),
  ('7','maths','view-beyond-point','बिंदु से परे एक दृष्टि','दशमलव और भिन्नों को संख्या रेखा, मापन और भागों के संदर्भ में समझना।','Class VII Mathematics — Ganita Prakash Part I (Hindi)','https://ncert.nic.in/textbook.php?ghgp1=7-7',3),
  ('7','maths','useful-expressions-with-letters','अक्षर-संख्याओं के उपयोगी व्यंजक','अक्षरों से अज्ञात या बदलती राशियों को निरूपित करना और व्यंजक बनाना।','Class VII Mathematics — Ganita Prakash Part I (Hindi)','https://ncert.nic.in/textbook.php?ghgp1=7-7',4),
  ('7','maths','parallel-and-intersecting-lines','समांतर और प्रतिच्छेदी रेखाएँ','रेखाओं की स्थिति, प्रतिच्छेदन और कोणों के संबंधों को पहचानना।','Class VII Mathematics — Ganita Prakash Part I (Hindi)','https://ncert.nic.in/textbook.php?ghgp1=7-7',5),
  ('7','maths','number-play','संख्याओं का खेल','संख्या-खेल, अंकीय नियमों और तार्किक निष्कर्षों से गणितीय सोच विकसित करना।','Class VII Mathematics — Ganita Prakash Part I (Hindi)','https://ncert.nic.in/textbook.php?ghgp1=7-7',6),
  ('7','maths','story-of-three-intersecting-lines','तीन प्रतिच्छेदी रेखाओं की एक कथा','तीन रेखाओं से बनते कोणों और उनके योग-संबंधों का ज्यामितीय विश्लेषण करना।','Class VII Mathematics — Ganita Prakash Part I (Hindi)','https://ncert.nic.in/textbook.php?ghgp1=7-7',7),
  ('7','maths','working-with-fractions','भिन्नों के साथ कार्य करना','समतुल्य भिन्नों, तुलना और भिन्नों पर संक्रियाओं को मॉडल के साथ समझना।','Class VII Mathematics — Ganita Prakash Part I (Hindi)','https://ncert.nic.in/textbook.php?ghgp1=7-7',8),
  ('7','maths','geometric-twins','ज्यामितीय जुड़वाँ आकृतियाँ','रूप, कोण, भुजाओं और समान गुणों के आधार पर ज्यामितीय आकृतियों की तुलना करना।','NCERT Ganita Prakash Part II (English source; Hindi translation draft)','https://ncert.nic.in/textbook.php?gegp2=0-7',9),
  ('7','maths','operations-with-integers','पूर्णांकों पर संक्रियाएँ','धनात्मक और ऋणात्मक पूर्णांकों का जोड़, घटाव, गुणा और भाग संदर्भ सहित समझना।','NCERT Ganita Prakash Part II (English source; Hindi translation draft)','https://ncert.nic.in/textbook.php?gegp2=0-7',10),
  ('7','maths','finding-common-ground','समान आधार खोजना','साझा गुणनखंड, गुणज और तुलनात्मक संबंधों का उपयोग करके समस्याएँ हल करना।','NCERT Ganita Prakash Part II (English source; Hindi translation draft)','https://ncert.nic.in/textbook.php?gegp2=0-7',11),
  ('7','maths','another-peek-beyond-point','बिंदु से परे एक और झलक','परिमेय संख्याओं और दशमलव निरूपण के बीच संबंधों का विस्तार करना।','NCERT Ganita Prakash Part II (English source; Hindi translation draft)','https://ncert.nic.in/textbook.php?gegp2=0-7',12),
  ('7','maths','connecting-the-dots','बिंदुओं को जोड़ना','निर्देशांक-जैसे निरूपण, बिंदु-पैटर्न और संबंधों को चित्रों से समझना।','NCERT Ganita Prakash Part II (English source; Hindi translation draft)','https://ncert.nic.in/textbook.php?gegp2=0-7',13),
  ('7','maths','constructions-and-tilings','रचनाएँ और फर्श-बिछावन','ज्यामितीय रचना और टाइलिंग में कोण, आकृति, दोहराव और सटीकता के नियम लागू करना।','NCERT Ganita Prakash Part II (English source; Hindi translation draft)','https://ncert.nic.in/textbook.php?gegp2=0-7',14),
  ('7','maths','finding-the-unknown','अज्ञात मान खोजना','समानता और प्रतिलोम संक्रियाओं से अज्ञात राशि वाले सरल समीकरण हल करना।','NCERT Ganita Prakash Part II (English source; Hindi translation draft)','https://ncert.nic.in/textbook.php?gegp2=0-7',15),
  ('7','science','ever-growing-universe-of-science','विज्ञान का निरंतर बढ़ता संसार','अवलोकन, प्रश्न, परिकल्पना, निष्पक्ष जाँच और प्रमाण पर आधारित निष्कर्ष का अभ्यास।','Class VII Science — Jigyasa (Hindi)','https://ncert.nic.in/textbook.php?ghcu1=7-12',1),
  ('7','science','acids-bases-neutrals','पदार्थों का अन्वेषण — अम्लीय, क्षारीय एवं उदासीन','सूचक पदार्थों और सुरक्षित परीक्षण से अम्ल, क्षार और उदासीन पदार्थों में अंतर करना।','Class VII Science — Jigyasa (Hindi)','https://ncert.nic.in/textbook.php?ghcu1=7-12',2),
  ('7','science','electricity-circuits-components','विद्युत — परिपथ एवं उनके घटक','सेल, तार, स्विच और बल्ब से बंद परिपथ बनाना तथा चालक-अचालक पहचानना।','Class VII Science — Jigyasa (Hindi)','https://ncert.nic.in/textbook.php?ghcu1=7-12',3),
  ('7','science','world-of-metals-nonmetals','धातुओं और अधातुओं का संसार','भौतिक गुणों और रोज़मर्रा के उपयोग से धातुओं और अधातुओं की तुलना करना।','Class VII Science — Jigyasa (Hindi)','https://ncert.nic.in/textbook.php?ghcu1=7-12',4),
  ('7','science','physical-chemical-changes','हमारे आस-पास के परिवर्तन — भौतिक एवं रासायनिक','नए पदार्थ बनने या न बनने के प्रमाण से भौतिक और रासायनिक परिवर्तन पहचानना।','Class VII Science — Jigyasa (Hindi)','https://ncert.nic.in/textbook.php?ghcu1=7-12',5),
  ('7','science','adolescence-growth-change','किशोरावस्था — वृद्धि एवं परिवर्तन की अवस्था','किशोरावस्था में होने वाले शारीरिक, भावनात्मक और सामाजिक परिवर्तनों को वैज्ञानिक ढंग से समझना।','Class VII Science — Jigyasa (Hindi)','https://ncert.nic.in/textbook.php?ghcu1=7-12',6),
  ('7','science','heat-transfer-in-nature','प्रकृति में ऊष्मा का स्थानांतरण','चालन, संवहन और विकिरण को दैनिक जीवन के उदाहरणों से अलग-अलग पहचानना।','Class VII Science — Jigyasa (Hindi)','https://ncert.nic.in/textbook.php?ghcu1=7-12',7),
  ('7','science','measurement-of-time-motion','समय एवं गति का मापन','समय, दूरी और चाल के संबंध से गति का वर्णन और गणना करना।','Class VII Science — Jigyasa (Hindi)','https://ncert.nic.in/textbook.php?ghcu1=7-12',8),
  ('7','science','animal-life-processes','जंतुओं में जैव प्रक्रम','पोषण, श्वसन, परिवहन और उत्सर्जन जैसे जैव प्रक्रमों की भूमिका समझना।','Class VII Science — Jigyasa (Hindi)','https://ncert.nic.in/textbook.php?ghcu1=7-12',9),
  ('7','science','plant-life-processes','पादपों में जैव प्रक्रम','प्रकाश संश्लेषण, जल-परिवहन, श्वसन और वृद्धि को पादपों के जीवन से जोड़ना।','Class VII Science — Jigyasa (Hindi)','https://ncert.nic.in/textbook.php?ghcu1=7-12',10),
  ('7','science','light-shadow-reflection','प्रकाश — छाया एवं परावर्तन','प्रकाश के सीधी रेखा में चलने, छाया बनने और दर्पण से परावर्तन को जाँचना।','Class VII Science — Jigyasa (Hindi)','https://ncert.nic.in/textbook.php?ghcu1=7-12',11),
  ('7','science','earth-moon-sun','पृथ्वी, चंद्रमा एवं सूर्य','दिन-रात, चंद्र कलाओं और आकाशीय पिंडों की प्रत्यक्ष गति के मॉडल बनाना।','Class VII Science — Jigyasa (Hindi)','https://ncert.nic.in/textbook.php?ghcu1=7-12',12),
  ('8','maths','squares-and-cubes','वर्ग और घन','वर्ग, घन, वर्गमूल और घनमूल को गुणन और ज्यामितीय व्यवस्थाओं से समझना।','Class VIII Mathematics — Ganita Prakash Part I (Hindi)','https://ncert.nic.in/textbook.php?hhgp1=7-7',1),
  ('8','maths','exponents-play','घातों का खेल','घातांक नियमों से बड़ी या छोटी संख्याओं को संक्षिप्त रूप में लिखना और सरल करना।','Class VIII Mathematics — Ganita Prakash Part I (Hindi)','https://ncert.nic.in/textbook.php?hhgp1=7-7',2),
  ('8','maths','story-of-numbers','संख्याओं की कहानी','संख्या प्रणालियों, गुणनखंडों, विभाज्यता और संख्यात्मक पैटर्न का तर्कपूर्ण अध्ययन।','Class VIII Mathematics — Ganita Prakash Part I (Hindi)','https://ncert.nic.in/textbook.php?hhgp1=7-7',3),
  ('8','maths','quadrilaterals','चतुर्भुज','चतुर्भुजों के कोण, भुजाओं और विकर्णों के गुणों से वर्गीकरण और निष्कर्ष निकालना।','Class VIII Mathematics — Ganita Prakash Part I (Hindi)','https://ncert.nic.in/textbook.php?hhgp1=7-7',4),
  ('8','maths','number-play','संख्याओं का खेल','संख्यात्मक चालों, पैटर्न और तर्क से सामान्य नियमों की जाँच करना।','Class VIII Mathematics — Ganita Prakash Part I (Hindi)','https://ncert.nic.in/textbook.php?hhgp1=7-7',5),
  ('8','maths','sharing-makes-grow','जितना बाँटेंगे उतना बढ़ेगा','भिन्नों के भाग और अनुपात के अर्थ को समान समूहों तथा मापन से समझना।','Class VIII Mathematics — Ganita Prakash Part I (Hindi)','https://ncert.nic.in/textbook.php?hhgp1=7-7',6),
  ('8','maths','proportional-reasoning-1','समानुपातिक तर्कण-1','अनुपात, समानुपात और स्थिर दर की मदद से वास्तविक समस्याएँ हल करना।','Class VIII Mathematics — Ganita Prakash Part I (Hindi)','https://ncert.nic.in/textbook.php?hhgp1=7-7',7),
  ('8','maths','fractions-in-disguise','भिन्नों के छिपे रूप','भिन्नों को अलग-अलग निरूपणों में पहचानना और उनके संबंधों का विश्लेषण करना।','NCERT Ganita Prakash Part II (English source; Hindi translation draft)','https://ncert.nic.in/textbook.php?hegp2=0-7',8),
  ('8','maths','baudhayana-pythagoras-theorem','बौधायन–पाइथागोरस प्रमेय','समकोण त्रिभुज की भुजाओं के वर्गों के क्षेत्रफल संबंध को मॉडल से समझना।','NCERT Ganita Prakash Part II (English source; Hindi translation draft)','https://ncert.nic.in/textbook.php?hegp2=0-7',9),
  ('8','maths','proportional-reasoning-2','समानुपातिक तर्कण-2','प्रत्यक्ष और अन्य अनुपातिक संबंधों को तालिका, दर और ग्राफ से पहचानना।','NCERT Ganita Prakash Part II (English source; Hindi translation draft)','https://ncert.nic.in/textbook.php?hegp2=0-7',10),
  ('8','maths','exploring-geometric-themes','कुछ ज्यामितीय विषयों की खोज','सममिति, कोण, त्रिभुज और बहुभुजों के गुणों से अनुमान और औचित्य प्रस्तुत करना।','NCERT Ganita Prakash Part II (English source; Hindi translation draft)','https://ncert.nic.in/textbook.php?hegp2=0-7',11),
  ('8','maths','tales-by-dots-and-lines','बिंदुओं और रेखाओं की कथाएँ','बिंदु-रेखाओं के चित्रों और पैटर्नों से संबंध, संरचना और सामान्य नियम पहचानना।','NCERT Ganita Prakash Part II (English source; Hindi translation draft)','https://ncert.nic.in/textbook.php?hegp2=0-7',12),
  ('8','maths','algebra-play','बीजगणित का खेल','चर, व्यंजक और समीकरणों से संख्या संबंधों का सामान्यीकरण करना।','NCERT Ganita Prakash Part II (English source; Hindi translation draft)','https://ncert.nic.in/textbook.php?hegp2=0-7',13),
  ('8','maths','area','क्षेत्रफल','आकृतियों के क्षेत्रफल को इकाई वर्ग, विघटन और उपयुक्त सूत्रों से मापना।','NCERT Ganita Prakash Part II (English source; Hindi translation draft)','https://ncert.nic.in/textbook.php?hegp2=0-7',14),
  ('8','science','world-of-scientific-exploration','विज्ञान के अन्वेषी संसार की खोज यात्रा','वैज्ञानिक प्रश्न बनाना, अवलोकन दर्ज करना, प्रमाण की तुलना और निष्कर्ष की समीक्षा करना।','Class VIII Science — Jigyasa (Hindi)','https://ncert.nic.in/textbook.php?hhcu1=1-13',1),
  ('8','science','invisible-microbial-world','अदृश्य जीव-जगत — हमारी आँखों की क्षमता से परे','सूक्ष्मजीवों की विविधता, उपयोग, रोग और रोकथाम को प्रमाण तथा स्वच्छता से समझना।','Class VIII Science — Jigyasa (Hindi)','https://ncert.nic.in/textbook.php?hhcu1=1-13',2),
  ('8','science','health-priceless-treasure','स्वास्थ्य — एक अमूल्य निधि','पोषण, स्वच्छता, रोग-निवारण और शारीरिक-मानसिक स्वास्थ्य के संबंध समझना।','Class VIII Science — Jigyasa (Hindi)','https://ncert.nic.in/textbook.php?hhcu1=1-13',3),
  ('8','science','electricity-magnetic-thermal-effects','विद्युत — चुंबकीय एवं तापीय प्रभाव','विद्युत धारा के तापीय और चुंबकीय प्रभाव तथा सुरक्षित उपयोग का अध्ययन करना।','Class VIII Science — Jigyasa (Hindi)','https://ncert.nic.in/textbook.php?hhcu1=1-13',4),
  ('8','science','knowing-forces','बलों को जानें','बल की दिशा, परिमाण और वस्तुओं की गति या आकार में परिवर्तन के संबंध को समझना।','Class VIII Science — Jigyasa (Hindi)','https://ncert.nic.in/textbook.php?hhcu1=1-13',5),
  ('8','science','pressure-wind-storms-cyclones','दाब, पवन, झंझावात एवं चक्रवात','दाब-अंतर, हवा की गति और मौसम तंत्र को मॉडल तथा सुरक्षा उपायों से समझना।','Class VIII Science — Jigyasa (Hindi)','https://ncert.nic.in/textbook.php?hhcu1=1-13',6),
  ('8','science','particulate-nature-matter','द्रव्य की कणीय प्रकृति','द्रव्य के कणों के बीच रिक्त स्थान, गति और आकर्षण को अवस्थाओं से जोड़ना।','Class VIII Science — Jigyasa (Hindi)','https://ncert.nic.in/textbook.php?hhcu1=1-13',7),
  ('8','science','elements-compounds-mixtures','द्रव्य की प्रकृति — तत्व, यौगिक और मिश्रण','तत्वों, यौगिकों और मिश्रणों में अंतर तथा पृथक्करण के आधारों का विश्लेषण करना।','Class VIII Science — Jigyasa (Hindi)','https://ncert.nic.in/textbook.php?hhcu1=1-13',8),
  ('8','science','solutes-solvents-solutions','विलेयों, विलायकों और विलयनों का अद्भुत संसार','विलयन, घुलनशीलता, सांद्रता और तापमान के प्रभाव को प्रयोगों से समझना।','Class VIII Science — Jigyasa (Hindi)','https://ncert.nic.in/textbook.php?hhcu1=1-13',9),
  ('8','science','light-mirrors-lenses','प्रकाश — दर्पण एवं लेंस','परावर्तन और अपवर्तन के सरल मॉडलों से दर्पणों तथा लेंसों के उपयोग समझना।','Class VIII Science — Jigyasa (Hindi)','https://ncert.nic.in/textbook.php?hhcu1=1-13',10),
  ('8','science','celestial-events-timekeeping','आकाशीय परिघटनाएँ और काल-निर्धारण','खगोलीय चक्रों, छाया और आवर्ती घटनाओं से समय मापन का इतिहास समझना।','Class VIII Science — Jigyasa (Hindi)','https://ncert.nic.in/textbook.php?hhcu1=1-13',11),
  ('8','science','nature-in-harmony','प्रकृति कैसे सामंजस्य में कार्य करती है','पारिस्थितिकी तंत्र, जीव-अजैव अंतःक्रिया और संतुलन के कारण-परिणाम संबंध समझना।','Class VIII Science — Jigyasa (Hindi)','https://ncert.nic.in/textbook.php?hhcu1=1-13',12),
  ('8','science','earth-unique-life-supporting-planet','हमारा आवास — पृथ्वी एक अद्वितीय जीवनदायी ग्रह','पृथ्वी के वायुमंडल, जल, ताप और अन्य परिस्थितियों को जीवन के लिए आवश्यक समझना।','Class VIII Science — Jigyasa (Hindi)','https://ncert.nic.in/textbook.php?hhcu1=1-13',13)
)
insert into curriculum_chapter(
  subject_id,code,display_name,description,sort_order,active,content_status,
  curriculum_source,curriculum_source_url,curriculum_source_edition,curriculum_source_pages,
  curriculum_source_verified,textbook_chapter_no,teaching_order,pedagogy_note
)
select s.id,src.chapter_code,src.title,src.description,src.chapter_no,true,'DRAFT',
       src.source_title,src.source_url,null,null,false,src.chapter_no,src.chapter_no,
       'QuantaEdge authored draft. Verify selected textbook edition, all chapter headings, activities, visuals, page mappings and Hindi terminology before publication.'
from source src
join curriculum_class c on c.code=src.class_code
join curriculum_subject s on s.class_id=c.id and s.code=src.subject_code
on conflict(subject_id,code) do update set
  display_name=excluded.display_name,
  description=excluded.description,
  sort_order=excluded.sort_order,
  active=true,
  content_status='DRAFT',
  curriculum_source=excluded.curriculum_source,
  curriculum_source_url=excluded.curriculum_source_url,
  curriculum_source_edition=null,
  curriculum_source_pages=null,
  curriculum_source_verified=false,
  textbook_chapter_no=excluded.textbook_chapter_no,
  teaching_order=excluded.teaching_order,
  pedagogy_note=excluded.pedagogy_note;

-- Keep new micro-topics first-class concepts for the Class 6-8 CMS.
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

  if class_code in ('6','7','8') and subject_code in ('maths','science') then
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

  if class_code in ('6','7','8') and subject_code in ('maths','science') then
    select cc.id into matched_concept_id
    from chapter_concept cc
    where cc.chapter_id=chapter_key
      and lower(btrim(cc.title))=lower(btrim(coalesce(nullif(new.subtopic,''),nullif(new.topic,''),'')))
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

-- Baseline assessment depth for every Class 7-8 chapter.
update chapter_assessment_requirement r
set minimum_count=0,required=false,
    rationale='Optional format; the chapter-specific authored bank determines whether it is appropriate.',
    updated_at=now()
from curriculum_chapter ch
join curriculum_subject s on s.id=ch.subject_id
join curriculum_class c on c.id=s.class_id
where r.chapter_id=ch.id and c.code in ('7','8') and s.code in ('maths','science');

insert into chapter_assessment_requirement(chapter_id,question_type,minimum_count,required,rationale)
select ch.id,t.question_type,t.minimum_count,true,t.rationale
from curriculum_chapter ch
join curriculum_subject s on s.id=ch.subject_id
join curriculum_class c on c.id=s.class_id
cross join (values
 ('MCQ',5,'QuantaEdge Class 7-8 question-bank baseline: five MCQs per chapter.'),
 ('TRUE_FALSE',4,'QuantaEdge Class 7-8 question-bank baseline: four true/false items per chapter.')
) as t(question_type,minimum_count,rationale)
where c.code in ('7','8') and s.code in ('maths','science') and ch.active=true and ch.content_status='DRAFT'
on conflict(chapter_id,question_type) do update set
  minimum_count=excluded.minimum_count,required=true,rationale=excluded.rationale,updated_at=now();

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
where c.code in ('7','8') and s.code in ('maths','science') and ch.active=true and ch.content_status='DRAFT'
on conflict(chapter_id,requirement_code) do update set
  description=excluded.description,min_count=excluded.min_count,required=excluded.required,updated_at=now();

insert into app_metadata(key,value)
values ('schema','ncert-class7-8-quantaedge-authored-learning-path-v49')
on conflict(key) do update set value=excluded.value;
