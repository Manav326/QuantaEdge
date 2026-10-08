-- QuantaEdge Bihar Board content foundation
-- Original lesson content authored for QuantaEdge; curriculum mapping is based on the SCERT Bihar textbook catalogue.
alter table curriculum_chapter add column if not exists curriculum_source varchar(500);
alter table curriculum_chapter add column if not exists content_status varchar(30) not null default 'DRAFT';

do $$
declare
  r record;
  ch_id bigint;
  obj_id bigint;
  v_lesson_id bigint;
  question_id bigint;
  stage record;
  q record;
  answer text;
begin
  for r in select * from jsonb_to_recordset('[
{"class_code":"6","subject_code":"maths","code":"knowing-numbers","name":"संख्याओं की पहचान और बड़ी संख्याएँ","description":"स्थान-मूल्य, तुलना, अनुमान और दैनिक जीवन में बड़ी संख्याओं का उपयोग।","sort_order":1},
{"class_code":"6","subject_code":"maths","code":"whole-numbers","name":"पूर्ण संख्याएँ","description":"संख्या रेखा, उत्तरवर्ती-अनुवर्ती और जोड़-घटाव के गुण।","sort_order":2},
{"class_code":"6","subject_code":"maths","code":"playing-numbers","name":"संख्याओं से खेल","description":"गुणनखंड, गुणज, अभाज्य संख्याएँ, विभाज्यता और सामान्य गुणज/गुणनखंड।","sort_order":3},
{"class_code":"6","subject_code":"maths","code":"geometry-basics","name":"ज्यामिति की मूल बातें","description":"बिंदु, रेखा, रेखाखंड, किरण और मूल ज्यामितीय आकृतियों की पहचान।","sort_order":4},
{"class_code":"6","subject_code":"maths","code":"elementary-shapes","name":"प्रारम्भिक आकृतियों की समझ","description":"कोण, कोणों के प्रकार, त्रिभुज और चतुर्भुज की पहचान तथा मापन।","sort_order":5},
{"class_code":"6","subject_code":"maths","code":"integers","name":"पूर्णांक","description":"धनात्मक-ऋणात्मक संख्याएँ, संख्या रेखा और पूर्णांकों के जोड़-घटाव।","sort_order":6},
{"class_code":"6","subject_code":"maths","code":"fractions","name":"भिन्न","description":"भिन्न का अर्थ, समतुल्य भिन्न, तुलना और चारों मूल संक्रियाओं की शुरुआत।","sort_order":7},
{"class_code":"6","subject_code":"maths","code":"decimals","name":"दशमलव","description":"दशमलव स्थान-मूल्य, तुलना, जोड़-घटाव और दैनिक जीवन में उपयोग।","sort_order":8},
{"class_code":"6","subject_code":"maths","code":"data-handling","name":"आँकड़ों का प्रबंधन","description":"तालिका, चित्रलेख, दंड आलेख और सरल आँकड़ों की व्याख्या।","sort_order":9},
{"class_code":"6","subject_code":"maths","code":"mensuration","name":"मापन","description":"लंबाई, परिमाप और क्षेत्रफल की मूल अवधारणाएँ तथा इकाइयाँ।","sort_order":10},
{"class_code":"6","subject_code":"maths","code":"algebra","name":"बीजगणित की शुरुआत","description":"चर, सरल व्यंजक, मान रखना और सरल नियमों को अक्षरों में लिखना।","sort_order":11},
{"class_code":"6","subject_code":"maths","code":"ratio-proportion","name":"अनुपात और समानुपात","description":"दो राशियों की तुलना, अनुपात लिखना और सरल समानुपात।","sort_order":12},
{"class_code":"6","subject_code":"maths","code":"symmetry","name":"सममिति","description":"सममिति रेखा, दर्पण प्रतिबिंब और सममित आकृतियों की पहचान।","sort_order":13},
{"class_code":"6","subject_code":"maths","code":"practical-geometry","name":"व्यावहारिक ज्यामिति","description":"स्केल, परकार और कोणमापी से सरल ज्यामितीय निर्माण।","sort_order":14},
{"class_code":"6","subject_code":"science","code":"food-sources","name":"भोजन के स्रोत","description":"पौधों और पशुओं से मिलने वाले भोजन तथा स्थानीय खाद्य स्रोतों की पहचान।","sort_order":1},
{"class_code":"6","subject_code":"science","code":"components-food","name":"भोजन के घटक","description":"कार्बोहाइड्रेट, प्रोटीन, वसा, विटामिन, खनिज, रेशा और जल की भूमिका।","sort_order":2},
{"class_code":"6","subject_code":"science","code":"fibre-fabric","name":"रेशे से वस्त्र","description":"प्राकृतिक रेशे, कपास और ऊन तथा रेशे से धागा और कपड़ा बनने की प्रक्रिया।","sort_order":3},
{"class_code":"6","subject_code":"science","code":"sorting-materials","name":"वस्तुओं का समूह बनाना","description":"वस्तुओं के गुणों के आधार पर वर्गीकरण और सामग्री के चयन का कारण।","sort_order":4},
{"class_code":"6","subject_code":"science","code":"separation-substances","name":"पदार्थों का पृथक्करण","description":"छानना, अवसादन, निथारना, वाष्पीकरण और मिश्रणों को अलग करना।","sort_order":5},
{"class_code":"6","subject_code":"science","code":"changes-around-us","name":"हमारे आसपास के परिवर्तन","description":"प्रतिवर्ती-अप्रतिवर्ती परिवर्तन और दैनिक जीवन में उनके उदाहरण।","sort_order":6},
{"class_code":"6","subject_code":"science","code":"plants","name":"पौधों को जानें","description":"जड़, तना, पत्ती, फूल और उनके कार्यों की पहचान।","sort_order":7},
{"class_code":"6","subject_code":"science","code":"body-movements","name":"शरीर में गति","description":"हड्डियाँ, जोड़, मांसपेशियाँ और विभिन्न जीवों की गति।","sort_order":8},
{"class_code":"6","subject_code":"science","code":"living-organisms","name":"सजीव और उनका परिवेश","description":"सजीवों के लक्षण, आवास और पर्यावरण के प्रति अनुकूलन।","sort_order":9},
{"class_code":"6","subject_code":"science","code":"motion-measurement","name":"गति और मापन","description":"लंबाई की इकाइयाँ, दूरी, समय और सरल गति का अवलोकन।","sort_order":10},
{"class_code":"6","subject_code":"science","code":"light-shadows","name":"प्रकाश, छाया और परावर्तन","description":"प्रकाश स्रोत, पारदर्शी/अपारदर्शी वस्तुएँ, छाया और परावर्तन।","sort_order":11},
{"class_code":"6","subject_code":"science","code":"electricity-circuits","name":"विद्युत और परिपथ","description":"सेल, बल्ब, तार, स्विच और बंद/खुले परिपथ की समझ।","sort_order":12},
{"class_code":"6","subject_code":"science","code":"magnets","name":"चुंबकों के साथ खेल","description":"चुंबकीय पदार्थ, ध्रुव, आकर्षण-विकर्षण और दिशा ज्ञात करना।","sort_order":13},
{"class_code":"6","subject_code":"science","code":"water","name":"जल","description":"जल के स्रोत, उपयोग, जल चक्र और जल संरक्षण।","sort_order":14},
{"class_code":"6","subject_code":"science","code":"air","name":"हमारे चारों ओर वायु","description":"वायु का अस्तित्व, घटक, दहन और जीवों के लिए वायु का महत्व।","sort_order":15},
{"class_code":"6","subject_code":"science","code":"garbage","name":"कचरा—हमारी जिम्मेदारी","description":"गीला-सूखा कचरा, अपशिष्ट पृथक्करण, पुनर्चक्रण और स्वच्छता।","sort_order":16},
{"class_code":"7","subject_code":"maths","code":"integers","name":"पूर्णांक","description":"पूर्णांकों की संख्या रेखा और जोड़, घटाव, गुणा तथा भाग के नियम।","sort_order":1},
{"class_code":"7","subject_code":"maths","code":"fractions-decimals","name":"भिन्न और दशमलव","description":"भिन्न-दशमलव का रूपांतरण तथा चारों संक्रियाएँ।","sort_order":2},
{"class_code":"7","subject_code":"maths","code":"data-handling","name":"आँकड़ों का प्रबंधन","description":"औसत, माध्य, दंड आलेख और आँकड़ों से निष्कर्ष निकालना।","sort_order":3},
{"class_code":"7","subject_code":"maths","code":"simple-equations","name":"सरल समीकरण","description":"एक चर वाले सरल समीकरणों को संतुलन और प्रतिलोम संक्रियाओं से हल करना।","sort_order":4},
{"class_code":"7","subject_code":"maths","code":"lines-angles","name":"रेखाएँ और कोण","description":"समांतर रेखाएँ, प्रतिच्छेदी रेखाएँ और कोणों के संबंध।","sort_order":5},
{"class_code":"7","subject_code":"maths","code":"triangles","name":"त्रिभुज","description":"त्रिभुज के प्रकार, कोणों का योग और भुजा-कोण के आधार पर गुण।","sort_order":6},
{"class_code":"7","subject_code":"maths","code":"congruence","name":"त्रिभुजों की सर्वांगसमता","description":"सर्वांगसम आकृतियों की पहचान और SSS, SAS, ASA जैसे मानदंड।","sort_order":7},
{"class_code":"7","subject_code":"maths","code":"comparing-quantities","name":"राशियों की तुलना","description":"प्रतिशत, लाभ-हानि, छूट और सरल ब्याज की बुनियाद।","sort_order":8},
{"class_code":"7","subject_code":"maths","code":"rational-numbers","name":"परिमेय संख्याएँ","description":"परिमेय संख्याओं का निरूपण और संक्रियाओं के गुण।","sort_order":9},
{"class_code":"7","subject_code":"maths","code":"practical-geometry","name":"व्यावहारिक ज्यामिति","description":"दिए गए माप से त्रिभुज और अन्य आकृतियों का निर्माण।","sort_order":10},
{"class_code":"7","subject_code":"maths","code":"perimeter-area","name":"परिमाप और क्षेत्रफल","description":"आयत, वर्ग, त्रिभुज तथा समांतर चतुर्भुज का क्षेत्रफल और परिमाप।","sort_order":11},
{"class_code":"7","subject_code":"maths","code":"algebraic-expressions","name":"बीजीय व्यंजक","description":"चर, पद, गुणांक, समान पद और व्यंजकों का जोड़-घटाव।","sort_order":12},
{"class_code":"7","subject_code":"maths","code":"exponents-powers","name":"घातांक और घात","description":"घातांक के अर्थ और मूल नियमों का उपयोग।","sort_order":13},
{"class_code":"7","subject_code":"maths","code":"symmetry","name":"सममिति","description":"सममिति रेखाएँ और घूर्णन/दर्पण सममिति की पहचान।","sort_order":14},
{"class_code":"7","subject_code":"maths","code":"solid-shapes","name":"ठोस आकृतियों का दृश्य","description":"दृश्य, किनारे, फलक, शीर्ष और ठोस आकृतियों के विभिन्न निरूपण।","sort_order":15},
{"class_code":"7","subject_code":"science","code":"nutrition-plants","name":"पौधों में पोषण","description":"प्रकाश संश्लेषण, क्लोरोफिल, स्वपोषी और परपोषी पोषण।","sort_order":1},
{"class_code":"7","subject_code":"science","code":"nutrition-animals","name":"प्राणियों में पोषण","description":"भोजन का पाचन, मानव पाचन तंत्र और पोषण के चरण।","sort_order":2},
{"class_code":"7","subject_code":"science","code":"heat","name":"ऊष्मा","description":"तापमान, ऊष्मा का संचार और चालक/कुचालक की समझ।","sort_order":3},
{"class_code":"7","subject_code":"science","code":"acids-bases-salts","name":"अम्ल, क्षार और लवण","description":"सूचक, अम्लीय-क्षारीय पदार्थ और उदासीनीकरण के दैनिक उदाहरण।","sort_order":4},
{"class_code":"7","subject_code":"science","code":"physical-chemical-changes","name":"भौतिक और रासायनिक परिवर्तन","description":"परिवर्तन के संकेत और नई पदार्थ बनने की पहचान।","sort_order":5},
{"class_code":"7","subject_code":"science","code":"respiration","name":"जीवों में श्वसन","description":"श्वसन की आवश्यकता, वायवीय/अवायवीय श्वसन और व्यायाम के प्रभाव।","sort_order":6},
{"class_code":"7","subject_code":"science","code":"transportation","name":"प्राणियों और पौधों में परिवहन","description":"रक्त परिसंचरण तथा पौधों में जल और भोजन का परिवहन।","sort_order":7},
{"class_code":"7","subject_code":"science","code":"reproduction-plants","name":"पौधों में जनन","description":"अलैंगिक और लैंगिक जनन, परागण और बीज बनने की प्रक्रिया।","sort_order":8},
{"class_code":"7","subject_code":"science","code":"motion-time","name":"गति और समय","description":"दूरी, समय, गति और सरल गति-समय संबंध।","sort_order":9},
{"class_code":"7","subject_code":"science","code":"electric-current-effects","name":"विद्युत धारा के प्रभाव","description":"तापन, चुंबकीय प्रभाव और विद्युत उपकरणों में उपयोग।","sort_order":10},
{"class_code":"7","subject_code":"science","code":"light","name":"प्रकाश","description":"परावर्तन, दर्पण और प्रकाश की दिशा बदलने की घटनाएँ।","sort_order":11},
{"class_code":"7","subject_code":"science","code":"forests","name":"वन—हमारा जीवन","description":"वनों का पारिस्थितिक महत्व, जैव विविधता और संरक्षण।","sort_order":12},
{"class_code":"7","subject_code":"science","code":"wastewater","name":"अपशिष्ट जल की कहानी","description":"सीवेज, जल शोधन, स्वच्छता और जल स्रोतों की सुरक्षा।","sort_order":13},
{"class_code":"8","subject_code":"maths","code":"rational-numbers","name":"परिमेय संख्याएँ","description":"परिमेय संख्याओं के गुण, संख्या रेखा और संक्रियाओं के नियम।","sort_order":1},
{"class_code":"8","subject_code":"maths","code":"linear-equations","name":"एक चर वाले रैखिक समीकरण","description":"समीकरण बनाना, हल करना और उत्तर का सत्यापन।","sort_order":2},
{"class_code":"8","subject_code":"maths","code":"quadrilaterals","name":"चतुर्भुजों की समझ","description":"चतुर्भुज के प्रकार, कोणों का योग और विशेष चतुर्भुजों के गुण।","sort_order":3},
{"class_code":"8","subject_code":"maths","code":"practical-geometry","name":"व्यावहारिक ज्यामिति","description":"निर्दिष्ट मापों से चतुर्भुज और अन्य ज्यामितीय आकृतियों का निर्माण।","sort_order":4},
{"class_code":"8","subject_code":"maths","code":"data-handling","name":"आँकड़ों का प्रबंधन","description":"संगठित आँकड़े, आलेख और प्रायिकता की शुरुआती समझ।","sort_order":5},
{"class_code":"8","subject_code":"maths","code":"squares-roots","name":"वर्ग और वर्गमूल","description":"पूर्ण वर्ग पहचानना, वर्गमूल निकालना और पैटर्न समझना।","sort_order":6},
{"class_code":"8","subject_code":"maths","code":"cubes-roots","name":"घन और घनमूल","description":"पूर्ण घन, घनमूल और संख्या के घात रूप से संबंध।","sort_order":7},
{"class_code":"8","subject_code":"maths","code":"comparing-quantities","name":"राशियों की तुलना","description":"प्रतिशत, छूट, लाभ-हानि, कर और चक्रवृद्धि की शुरुआती समझ।","sort_order":8},
{"class_code":"8","subject_code":"maths","code":"algebraic-identities","name":"बीजीय व्यंजक और सर्वसमिकाएँ","description":"व्यंजकों का विस्तार, गुणनखंड और मानक सर्वसमिकाओं का उपयोग।","sort_order":9},
{"class_code":"8","subject_code":"maths","code":"solid-shapes","name":"ठोस आकृतियों का दृश्य","description":"बहुफलक, जाल और विभिन्न दिशाओं से ठोस आकृतियों को देखना।","sort_order":10},
{"class_code":"8","subject_code":"maths","code":"mensuration","name":"क्षेत्रमिति","description":"समलंब, बहुभुज और ठोस आकृतियों के पृष्ठीय क्षेत्रफल/आयतन की बुनियाद।","sort_order":11},
{"class_code":"8","subject_code":"maths","code":"exponents-powers","name":"घातांक और घात","description":"घातांक के नियम और बहुत बड़ी/छोटी संख्याओं का निरूपण।","sort_order":12},
{"class_code":"8","subject_code":"maths","code":"direct-inverse-proportion","name":"प्रत्यक्ष और व्युत्क्रमानुपात","description":"राशियों के बदलने पर उनके संबंध को पहचानना और समस्याएँ हल करना।","sort_order":13},
{"class_code":"8","subject_code":"maths","code":"factorisation","name":"गुणनखंडन","description":"सामान्य गुणनखंड, सर्वसमिकाओं और व्यंजकों का गुणनखंडन।","sort_order":14},
{"class_code":"8","subject_code":"maths","code":"graphs","name":"आलेख","description":"निर्देशांक, बिंदु अंकित करना और सरल संबंधों को आलेख पर दिखाना।","sort_order":15},
{"class_code":"8","subject_code":"science","code":"crop-production","name":"फसल उत्पादन और प्रबंधन","description":"कृषि की मूल प्रक्रियाएँ, बीज, सिंचाई, खरपतवार नियंत्रण और भंडारण।","sort_order":1},
{"class_code":"8","subject_code":"science","code":"microorganisms","name":"सूक्ष्मजीव—मित्र और शत्रु","description":"सूक्ष्मजीवों के उपयोग, रोग, खाद्य संरक्षण और नाइट्रोजन चक्र।","sort_order":2},
{"class_code":"8","subject_code":"science","code":"coal-petroleum","name":"कोयला और पेट्रोलियम","description":"जीवाश्म ईंधन, उनके उत्पाद, उपयोग और संरक्षण की आवश्यकता।","sort_order":3},
{"class_code":"8","subject_code":"science","code":"combustion-flame","name":"दहन और ज्वाला","description":"दहन की शर्तें, ज्वाला के प्रकार, ईंधन और सुरक्षित उपयोग।","sort_order":4},
{"class_code":"8","subject_code":"science","code":"conservation","name":"पौधों और प्राणियों का संरक्षण","description":"वनों की कटाई, जैव विविधता, संरक्षित क्षेत्र और पुनर्वनीकरण।","sort_order":5},
{"class_code":"8","subject_code":"science","code":"reproduction-animals","name":"प्राणियों में जनन","description":"लैंगिक जनन, निषेचन, भ्रूण विकास और कायांतरण की मूल बातें।","sort_order":6},
{"class_code":"8","subject_code":"science","code":"adolescence","name":"किशोरावस्था","description":"यौवनारंभ, हार्मोन, शारीरिक परिवर्तन और स्वस्थ आदतें।","sort_order":7},
{"class_code":"8","subject_code":"science","code":"force-pressure","name":"बल तथा दाब","description":"बल के प्रभाव, संपर्क/असंपर्क बल और दाब के दैनिक उपयोग।","sort_order":8},
{"class_code":"8","subject_code":"science","code":"friction","name":"घर्षण","description":"घर्षण के कारण, लाभ-हानि और घर्षण कम/अधिक करने के तरीके।","sort_order":9},
{"class_code":"8","subject_code":"science","code":"sound","name":"ध्वनि","description":"कंपन, ध्वनि का उत्पादन/संचार, तीव्रता और श्रव्यता की समझ।","sort_order":10},
{"class_code":"8","subject_code":"science","code":"chemical-effects-electricity","name":"विद्युत धारा के रासायनिक प्रभाव","description":"चालक द्रव, रासायनिक परिवर्तन और विद्युतलेपन का परिचय।","sort_order":11},
{"class_code":"8","subject_code":"science","code":"natural-phenomena","name":"कुछ प्राकृतिक घटनाएँ","description":"विद्युत आवेश, तड़ित और भूकंप के दौरान सुरक्षा उपाय।","sort_order":12},
{"class_code":"8","subject_code":"science","code":"light","name":"प्रकाश","description":"परावर्तन, दर्पण, दृष्टि और दृष्टिबाधित लोगों के लिए ब्रेल की भूमिका।","sort_order":13}
]'::jsonb)
    as x(class_code text,subject_code text,code text,name text,description text,sort_order int)
  loop
    insert into curriculum_chapter(subject_id,code,display_name,description,sort_order,curriculum_source,content_status)
    select s.id,r.code,r.name,r.description,r.sort_order,'SCERT Bihar textbook catalogue','PUBLISHED'
    from curriculum_subject s join curriculum_class c on c.id=s.class_id
    where c.code=r.class_code and s.code=r.subject_code
    on conflict(subject_id,code) do update set
      display_name=excluded.display_name,description=excluded.description,
      sort_order=excluded.sort_order,curriculum_source=excluded.curriculum_source,
      content_status=excluded.content_status
    returning id into ch_id;

    for stage in select * from (values
      (1,'foundation','पहले समझें',8),
      (2,'guided-practice','उदाहरण से सीखें',10),
      (3,'mastery','खुद करके पक्का करें',12)
    ) v(n,code,title,minutes)
    loop
      insert into learning_objective(chapter_id,code,title,description,sort_order)
      values(ch_id,'objective-'||stage.n,stage.title||': '||r.name,r.description,stage.n)
      on conflict(chapter_id,code) do update set title=excluded.title,description=excluded.description,sort_order=excluded.sort_order
      returning id into obj_id;

      insert into lesson(chapter_id,objective_id,code,title,summary,estimated_minutes,status,sort_order)
      values(
        ch_id,obj_id,r.code||'-'||stage.code,stage.title||': '||r.name,
        case stage.n
          when 1 then 'इस lesson में '||r.name||' की बुनियादी समझ बनेगी। '||r.description||' इसे रोज़मर्रा के उदाहरण से जोड़कर सोचें और नए शब्दों का अर्थ अपने शब्दों में बताएं।'
          when 2 then r.name||' को केवल याद नहीं करना है—इसे इस्तेमाल करना है। पहले जानकारी पहचानें, सही नियम/विधि चुनें, कदम क्रम से करें और उत्तर जाँचें।'
          else r.name||' में mastery का अर्थ है कि आप नए उदाहरण में सही विचार चुनें, गलती पहचानें और कारण समझाएं।'
        end,
        stage.minutes,'PUBLISHED',stage.n)
      on conflict(chapter_id,code) do update set
        title=excluded.title,summary=excluded.summary,estimated_minutes=excluded.estimated_minutes,
        status='PUBLISHED',sort_order=excluded.sort_order,objective_id=excluded.objective_id
      returning id into v_lesson_id;

      delete from question q where q.lesson_id=v_lesson_id;

      insert into lesson_block(lesson_id,sequence_no,block_type,content)
      values
      (v_lesson_id,1,'EXPLANATION',
        jsonb_build_object(
          'heading',case stage.n when 1 then r.name||' आसान भाषा में' when 2 then 'कदम-दर-कदम तरीका' else 'महारत जाँच' end,
          'body',case stage.n when 1 then r.description when 2 then 'प्रश्न की जानकारी अलग करें। सही नियम चुनें। कदमों को क्रम से लागू करें। उत्तर को दूसरी विधि या अनुमान से जाँचें।' else 'अवधारणा को नए उदाहरण में लागू करें और अपने उत्तर का कारण भी बताएं।' end,
          'keyPoints',jsonb_build_array(r.description,'नए शब्दों का अर्थ अपने शब्दों में बताएं।','उत्तर देने के बाद जाँच जरूर करें।')
        )),
      (v_lesson_id,2,'CHALLENGE',
        jsonb_build_object(
          'title',case stage.n when 1 then 'सोचकर बताइए' when 2 then 'अब खुद करके देखें' else 'एक कदम आगे' end,
          'prompt','"'||r.name||'" को अपने आसपास की किसी स्थिति से जोड़कर एक उदाहरण बनाइए।',
          'hint','उत्तर में अध्याय की कम-से-कम एक मुख्य अवधारणा का नाम लें।'
        )),
      (v_lesson_id,3,case stage.n when 2 then 'AI_HELP' else 'SUMMARY' end,
        case when stage.n=2 then
          jsonb_build_object('title','अगर अटकें तो मदद लें','actions',jsonb_build_array('EASY_EXPLANATION','EXAMPLE','STEP_BY_STEP','EXAM_ANSWER'),'socratic',true)
        else
          jsonb_build_object('points',jsonb_build_array(r.description,'अवधारणा को उदाहरण पर लागू करें।','अंत में उत्तर की जाँच करना सीखने का हिस्सा है।'))
        end)
      on conflict(lesson_id,sequence_no) do update set block_type=excluded.block_type,content=excluded.content,active=true;

      for q in select * from (values
        (1,'मुख्य उद्देश्य क्या है?','FOUNDATION'),
        (2,'सीखते समय सबसे उपयोगी आदत कौन-सी है?','CORE'),
        (3,'mastery दिखाने का बेहतर प्रमाण क्या है?','CHALLENGE')
      ) v(n,prompt,difficulty)
      loop
        if q.n=1 then answer:=r.description;
        elsif q.n=2 then answer:='प्रश्न की जानकारी पहचानकर सही नियम/विधि लागू करना और उत्तर जाँचना।';
        else answer:='नए उदाहरण में अवधारणा लागू करके कारण सहित सही उत्तर देना।';
        end if;

        insert into question(lesson_id,objective_id,question_type,prompt,explanation,difficulty,sort_order)
        values(v_lesson_id,obj_id,'MCQ',r.name||' — '||q.prompt,answer,q.difficulty,q.n) returning id into question_id;

        insert into question_option(question_id,option_key,label,is_correct,sort_order)
        select question_id,k,label,correct,ord
        from (values
          ('A',answer,true,1),
          ('B','केवल उत्तर याद करना',false,2),
          ('C','प्रश्न पढ़े बिना अनुमान लगाना',false,3),
          ('D','किसी दूसरे अध्याय का नियम लगाना',false,4)
        ) v(k,label,correct,ord);
      end loop;
    end loop;
  end loop;
end $$;

insert into app_metadata(key,value) values ('schema','bihar-board-content-v4')
on conflict(key) do update set value=excluded.value;
