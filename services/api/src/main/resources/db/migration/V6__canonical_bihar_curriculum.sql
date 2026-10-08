-- Canonical Bihar Board Class 6-8 Maths + Science curriculum order.
-- Source checked against SCERT Bihar e-resources and the current Bihar textbook chapter
-- lists. This migration corrects omissions/order from V4 without deleting learner data.
alter table curriculum_chapter add column if not exists textbook_chapter_no integer;
alter table curriculum_chapter add column if not exists teaching_order integer;
alter table curriculum_chapter add column if not exists pedagogy_note text;

do $$
declare r record; ch_id bigint;
begin
  for r in select * from jsonb_to_recordset('[
{"class":"6","subject":"maths","code":"knowing-numbers","no":1,"name":"संख्याओं की समझ","desc":"संख्याएँ, स्थान-मूल्य, बड़ी संख्याएँ, तुलना, अनुमान और दैनिक जीवन में संख्याओं का उपयोग।"},
{"class":"6","subject":"maths","code":"whole-numbers","no":2,"name":"पूर्ण संख्याएँ","desc":"संख्या रेखा, उत्तरवर्ती-अनुवर्ती, जोड़-घटाव और पूर्ण संख्याओं के गुण।"},
{"class":"6","subject":"maths","code":"playing-numbers","no":3,"name":"संख्याओं का खेल","desc":"गुणनखंड, गुणज, अभाज्य संख्याएँ और विभाज्यता के पैटर्न।"},
{"class":"6","subject":"maths","code":"integers","no":4,"name":"पूर्णांक","desc":"धनात्मक-ऋणात्मक संख्याएँ, संख्या रेखा और पूर्णांकों की संक्रियाएँ।"},
{"class":"6","subject":"maths","code":"geometry-basics","no":5,"name":"आधारभूत ज्यामितीय जानकारियाँ","desc":"बिंदु, रेखा, रेखाखंड, किरण और ज्यामिति की मूल भाषा।"},
{"class":"6","subject":"maths","code":"elementary-shapes","no":6,"name":"सरल आकृतियों की समझ","desc":"कोण, कोणों के प्रकार, त्रिभुज और चतुर्भुज की पहचान और मापन।"},
{"class":"6","subject":"maths","code":"fractions","no":7,"name":"भिन्न","desc":"भिन्न का अर्थ, समतुल्य भिन्न, तुलना और भिन्नों की संक्रियाएँ।"},
{"class":"6","subject":"maths","code":"decimals","no":8,"name":"दशमलव","desc":"दशमलव स्थान-मूल्य, तुलना, जोड़-घटाव और दैनिक जीवन में उपयोग।"},
{"class":"6","subject":"maths","code":"data-handling","no":9,"name":"आँकड़ों का प्रयोग","desc":"तालिका, चित्रलेख, दंड आलेख और आँकड़ों से निष्कर्ष।"},
{"class":"6","subject":"maths","code":"ratio-proportion","no":10,"name":"अनुपात और समानुपात","desc":"राशियों की तुलना, अनुपात लिखना और सरल समानुपात।"},
{"class":"6","subject":"maths","code":"unitary-method","no":11,"name":"ऐकिक नियम","desc":"एक इकाई का मान निकालकर अनेक इकाइयों की समस्याएँ हल करना।"},
{"class":"6","subject":"maths","code":"algebra","no":12,"name":"बीजगणित","desc":"चर, सरल व्यंजक, मान रखना और नियमों को अक्षरों में लिखना।"},
{"class":"6","subject":"maths","code":"mensuration","no":13,"name":"क्षेत्रमिति : परिमिति एवं क्षेत्रफल","desc":"लंबाई, परिमिति, क्षेत्रफल और इकाइयों की समझ।"},
{"class":"6","subject":"maths","code":"symmetry","no":14,"name":"सममिति","desc":"सममिति रेखा, दर्पण प्रतिबिंब और सममित आकृतियाँ।"},
{"class":"6","subject":"maths","code":"practical-geometry","no":15,"name":"प्रायोगिक ज्यामिति","desc":"स्केल, परकार और कोणमापी से सरल ज्यामितीय निर्माण।"},

{"class":"7","subject":"maths","code":"integers","no":1,"name":"पूर्णांक की समझ","desc":"पूर्णांकों की संख्या रेखा तथा जोड़, घटाव, गुणा और भाग के नियम।"},
{"class":"7","subject":"maths","code":"fractions-decimals","no":2,"name":"भिन्न संख्याएँ","desc":"भिन्नों का निरूपण, समतुल्यता, तुलना और संक्रियाएँ।"},
{"class":"7","subject":"maths","code":"decimals","no":3,"name":"दशमलव भिन्न","desc":"दशमलव संख्याओं की तुलना और चारों मूल संक्रियाएँ।"},
{"class":"7","subject":"maths","code":"data-handling","no":4,"name":"आँकड़ों का प्रबंधन","desc":"आँकड़े व्यवस्थित करना, औसत, दंड आलेख और निष्कर्ष।"},
{"class":"7","subject":"maths","code":"lines-angles","no":5,"name":"ज्यामितीय आकृतियों की समझ","desc":"रेखाएँ, कोण, समांतर/प्रतिच्छेदी रेखाएँ और कोणों के संबंध।"},
{"class":"7","subject":"maths","code":"triangles","no":6,"name":"त्रिभुज और उसके गुण","desc":"त्रिभुज के प्रकार, कोणों का योग और भुजा-कोण के गुण।"},
{"class":"7","subject":"maths","code":"congruence","no":7,"name":"सर्वांगसमता","desc":"सर्वांगसम आकृतियों और SSS, SAS, ASA जैसे मानदंडों की समझ।"},
{"class":"7","subject":"maths","code":"exponents-powers","no":8,"name":"घातांक","desc":"घातांक का अर्थ और घातों के मूल नियम।"},
{"class":"7","subject":"maths","code":"algebraic-expressions","no":9,"name":"बीजीय व्यंजक","desc":"चर, पद, गुणांक, समान पद और व्यंजकों का जोड़-घटाव।"},
{"class":"7","subject":"maths","code":"comparing-quantities","no":10,"name":"राशियों की तुलना","desc":"प्रतिशत, लाभ-हानि, छूट और सरल ब्याज की बुनियाद।"},
{"class":"7","subject":"maths","code":"simple-equations","no":11,"name":"सरल समीकरण","desc":"एक चर वाले सरल समीकरणों को संतुलन और प्रतिलोम संक्रियाओं से हल करना।"},
{"class":"7","subject":"maths","code":"rational-numbers","no":12,"name":"परिमेय संख्याएँ","desc":"परिमेय संख्याओं का निरूपण और संक्रियाओं के गुण।"},
{"class":"7","subject":"maths","code":"practical-geometry","no":13,"name":"ज्यामितीय आकृतियों की रचना","desc":"दिए गए माप से त्रिभुज और अन्य आकृतियों का निर्माण।"},
{"class":"7","subject":"maths","code":"symmetry","no":14,"name":"सममिति","desc":"सममिति रेखाएँ और दर्पण/घूर्णन सममिति।"},
{"class":"7","subject":"maths","code":"perimeter-area","no":15,"name":"परिमिति एवं क्षेत्रफल","desc":"आयत, वर्ग, त्रिभुज और समांतर चतुर्भुज का क्षेत्रफल/परिमिति।"},
{"class":"7","subject":"maths","code":"solid-shapes","no":16,"name":"त्रिविमीय आकृतियों का द्विविमीय निरूपण","desc":"दृश्य, किनारे, फलक, शीर्ष और ठोस आकृतियों के निरूपण।"},

{"class":"8","subject":"maths","code":"rational-numbers","no":1,"name":"परिमेय संख्याएँ","desc":"परिमेय संख्याओं के गुण, संख्या रेखा और संक्रियाओं के नियम।"},
{"class":"8","subject":"maths","code":"linear-equations","no":2,"name":"एक चर वाले रैखिक समीकरण","desc":"समीकरण बनाना, हल करना और उत्तर का सत्यापन।"},
{"class":"8","subject":"maths","code":"quadrilaterals","no":3,"name":"ज्यामितीय आकृतियों की समझ","desc":"चतुर्भुज के प्रकार, कोणों का योग और विशेष चतुर्भुजों के गुण।"},
{"class":"8","subject":"maths","code":"data-handling","no":4,"name":"आँकड़ों का प्रबंधन","desc":"संगठित आँकड़े, आलेख और प्रायिकता की शुरुआती समझ।"},
{"class":"8","subject":"maths","code":"squares-roots","no":5,"name":"वर्ग और वर्गमूल","desc":"पूर्ण वर्ग पहचानना, वर्गमूल निकालना और पैटर्न समझना।"},
{"class":"8","subject":"maths","code":"cubes-roots","no":6,"name":"घन और घनमूल","desc":"पूर्ण घन, घनमूल और संख्या के घात रूप से संबंध।"},
{"class":"8","subject":"maths","code":"practical-geometry","no":7,"name":"ज्यामितीय आकृतियों की रचना","desc":"निर्दिष्ट मापों से ज्यामितीय आकृतियों का निर्माण।"},
{"class":"8","subject":"maths","code":"comparing-quantities","no":8,"name":"राशियों की तुलना","desc":"प्रतिशत, छूट, लाभ-हानि, कर और चक्रवृद्धि की शुरुआती समझ।"},
{"class":"8","subject":"maths","code":"algebraic-identities","no":9,"name":"बीजीय व्यंजक","desc":"व्यंजकों का विस्तार, गुणनखंड और मानक सर्वसमिकाओं का उपयोग।"},
{"class":"8","subject":"maths","code":"exponents-powers","no":10,"name":"घातांक और घात","desc":"घातांक के नियम और बहुत बड़ी/छोटी संख्याओं का निरूपण।"},
{"class":"8","subject":"maths","code":"direct-inverse-proportion","no":11,"name":"सीधा और प्रतिलोम समानुपात","desc":"राशियों के बदलने पर उनके संबंध को पहचानना और समस्याएँ हल करना।"},
{"class":"8","subject":"maths","code":"solid-shapes","no":12,"name":"ठोस आकारों का चित्रण","desc":"बहुफलक, जाल और विभिन्न दिशाओं से ठोस आकृतियों को देखना।"},
{"class":"8","subject":"maths","code":"mensuration","no":13,"name":"क्षेत्रमिति","desc":"समलंब, बहुभुज और ठोस आकृतियों के क्षेत्रफल/आयतन की बुनियाद।"},
{"class":"8","subject":"maths","code":"factorisation","no":14,"name":"गुणनखंड","desc":"सामान्य गुणनखंड, सर्वसमिकाओं और व्यंजकों का गुणनखंडन।"},
{"class":"8","subject":"maths","code":"graphs","no":15,"name":"आलेखों से परिचय","desc":"निर्देशांक, बिंदु अंकित करना और सरल संबंधों को आलेख पर दिखाना।"},
{"class":"8","subject":"maths","code":"play-with-numbers","no":16,"name":"संख्याओं के साथ खेलना","desc":"संख्या पैटर्न, विभाज्यता और संख्याओं में छिपे नियमों से समस्या-समाधान।"},

{"class":"6","subject":"science","code":"food-sources","no":1,"name":"भोजन कहाँ से आता है?","desc":"पौधों और पशुओं से मिलने वाले भोजन तथा स्थानीय खाद्य स्रोत।"},
{"class":"6","subject":"science","code":"components-food","no":2,"name":"भोजन से क्या-क्या आता है?","desc":"भोजन के प्रमुख घटक और शरीर में उनकी भूमिका।"},
{"class":"6","subject":"science","code":"fibre-fabric","no":3,"name":"तंतु से वस्त्र तक","desc":"प्राकृतिक तंतु, कपास/ऊन तथा तंतु से धागा और कपड़ा।"},
{"class":"6","subject":"science","code":"sorting-materials","no":4,"name":"विभिन्न प्रकार के पदार्थ","desc":"वस्तुओं को उनके गुणों के आधार पर वर्गीकृत करना।"},
{"class":"6","subject":"science","code":"separation-substances","no":5,"name":"पृथक्करण","desc":"छानना, अवसादन, निथारना और वाष्पीकरण से मिश्रण अलग करना।"},
{"class":"6","subject":"science","code":"changes-around-us","no":6,"name":"पदार्थ में परिवर्तन","desc":"प्रतिवर्ती और अप्रतिवर्ती परिवर्तन।"},
{"class":"6","subject":"science","code":"plants","no":7,"name":"पेड़-पौधे की दुनिया","desc":"जड़, तना, पत्ती और पौधों के प्रमुख कार्य।"},
{"class":"6","subject":"science","code":"flowers","no":8,"name":"फूलों से जान-पहचान","desc":"फूल के भाग, उनके कार्य और विविध फूलों का अवलोकन।"},
{"class":"6","subject":"science","code":"body-movements","no":9,"name":"जंतुओं में गति","desc":"हड्डियाँ, जोड़, मांसपेशियाँ और विभिन्न जीवों की गति।"},
{"class":"6","subject":"science","code":"living-organisms","no":10,"name":"सजीव और निर्जीव","desc":"सजीवों के लक्षण और निर्जीव वस्तुओं से अंतर।"},
{"class":"6","subject":"science","code":"adaptation","no":11,"name":"सजीवों में अनुकूलन","desc":"विभिन्न आवासों में जीवों के अनुकूलन और जीव-पर्यावरण संबंध।"},
{"class":"6","subject":"science","code":"motion-measurement","no":12,"name":"दूरी, मापन एवं गति","desc":"मापन की इकाइयाँ, दूरी, समय और गति का अवलोकन।"},
{"class":"6","subject":"science","code":"light-shadows","no":13,"name":"प्रकाश","desc":"प्रकाश स्रोत, छाया, पारदर्शी/अपारदर्शी वस्तुएँ और परावर्तन।"},
{"class":"6","subject":"science","code":"electricity-circuits","no":14,"name":"बल्ब जलाओ जगमग-जगमग","desc":"सेल, बल्ब, तार, स्विच और बंद/खुले परिपथ।"},
{"class":"6","subject":"science","code":"magnets","no":15,"name":"चुंबक","desc":"चुंबकीय पदार्थ, ध्रुव, आकर्षण-विकर्षण और दिशा।"},
{"class":"6","subject":"science","code":"water","no":16,"name":"जल","desc":"जल के स्रोत, उपयोग, जल चक्र और संरक्षण।"},
{"class":"6","subject":"science","code":"air","no":17,"name":"वायु","desc":"वायु का अस्तित्व, घटक, दहन और जीवों के लिए महत्व।"},
{"class":"6","subject":"science","code":"garbage","no":18,"name":"ठोस कचरा प्रबंधन","desc":"गीला-सूखा कचरा, पृथक्करण, पुनर्चक्रण और स्वच्छता।"},

{"class":"7","subject":"science","code":"water-forests","no":1,"name":"जल और जंगल","desc":"जल संसाधन, वन, जीव-जगत और संरक्षण का संबंध।"},
{"class":"7","subject":"science","code":"nutrition-animals","no":2,"name":"जन्तुओं में पोषण","desc":"भोजन, पाचन और मानव पाचन तंत्र।"},
{"class":"7","subject":"science","code":"heat","no":3,"name":"ऊष्मा","desc":"तापमान, ऊष्मा का संचार और चालक/कुचालक।"},
{"class":"7","subject":"science","code":"motion-time","no":4,"name":"गति एवं समय","desc":"दूरी, समय, गति और सरल गति-समय संबंध।"},
{"class":"7","subject":"science","code":"physical-chemical-changes","no":5,"name":"पदार्थ में रासायनिक परिवर्तन","desc":"रासायनिक परिवर्तन के संकेत और नई पदार्थ बनने की पहचान।"},
{"class":"7","subject":"science","code":"nutrition-plants","no":6,"name":"पौधों में पोषण","desc":"प्रकाश संश्लेषण, क्लोरोफिल, स्वपोषी और परपोषी पोषण।"},
{"class":"7","subject":"science","code":"air-storms","no":7,"name":"हवा, आँधी, तूफान","desc":"वायु का दबाव, आँधी-तूफान और सुरक्षा।"},
{"class":"7","subject":"science","code":"climate-adaptation","no":8,"name":"जलवायु और अनुकूलन","desc":"जलवायु और जीवों के अनुकूलन का संबंध।"},
{"class":"7","subject":"science","code":"wastewater","no":9,"name":"गंदे जल का निपटान","desc":"सीवेज, जल शोधन, स्वच्छता और जल स्रोतों की सुरक्षा।"},
{"class":"7","subject":"science","code":"electric-current-effects","no":10,"name":"विद्युत धारा और इसके प्रभाव","desc":"तापन, चुंबकीय प्रभाव और विद्युत उपकरणों में उपयोग।"},
{"class":"7","subject":"science","code":"fibre-clothing","no":11,"name":"रेशों से वस्त्र तक","desc":"रेशे, धागा, कपड़ा और विभिन्न तंतुओं के गुण।"},
{"class":"7","subject":"science","code":"acids-bases-salts","no":12,"name":"अम्ल, क्षार और लवण","desc":"सूचक, अम्लीय-क्षारीय पदार्थ और उदासीनीकरण।"},
{"class":"7","subject":"science","code":"soil","no":13,"name":"मिट्टी","desc":"मिट्टी की परतें, गुण, जलधारण और पौधों के लिए महत्व।"},
{"class":"7","subject":"science","code":"transportation","no":14,"name":"पौधों में संवहन","desc":"पौधों में जल, खनिज और भोजन का परिवहन।"},
{"class":"7","subject":"science","code":"respiration","no":15,"name":"जीवों में श्वसन","desc":"श्वसन की आवश्यकता, वायवीय/अवायवीय श्वसन और व्यायाम।"},
{"class":"7","subject":"science","code":"light","no":16,"name":"प्रकाश","desc":"परावर्तन, दर्पण और प्रकाश की दिशा बदलने की घटनाएँ।"},
{"class":"7","subject":"science","code":"reproduction-plants","no":17,"name":"पौधों में जनन","desc":"अलैंगिक/लैंगिक जनन, परागण और बीज बनना।"},
{"class":"7","subject":"science","code":"circulation-excretion","no":18,"name":"जंतुओं में रक्त परिसंचरण एवं उत्सर्जन","desc":"रक्त परिसंचरण, हृदय और शरीर से अपशिष्ट निकालना।"},

{"class":"8","subject":"science","code":"combustion-flame","no":1,"name":"दहन और ज्वाला : चीजों का जलना","desc":"दहन की शर्तें, ज्वाला, ईंधन और सुरक्षित उपयोग।"},
{"class":"8","subject":"science","code":"natural-phenomena","no":2,"name":"तड़ित और भूकंप : प्रकृति के दो भयानक रूप","desc":"विद्युत आवेश, तड़ित, भूकंप और सुरक्षा उपाय।"},
{"class":"8","subject":"science","code":"crop-production","no":3,"name":"फसल : उत्पादन एवं प्रबंधन","desc":"कृषि प्रक्रियाएँ, बीज, सिंचाई, खरपतवार और भंडारण।"},
{"class":"8","subject":"science","code":"fibre-clothing","no":4,"name":"कपड़े तरह-तरह के : रेशे तरह-तरह के","desc":"प्राकृतिक/कृत्रिम रेशे और कपड़ा बनने की प्रक्रिया।"},
{"class":"8","subject":"science","code":"force-pressure","no":5,"name":"बल से ज़ोर आजमाइश","desc":"बल के प्रभाव और संपर्क/असंपर्क बल।"},
{"class":"8","subject":"science","code":"friction","no":6,"name":"घर्षण के कारण","desc":"घर्षण के कारण, लाभ-हानि और घर्षण को बदलने के तरीके।"},
{"class":"8","subject":"science","code":"microorganisms","no":7,"name":"सूक्ष्मजीवों का संसार : सूक्ष्मदर्शी द्वारा आँखों देखा","desc":"सूक्ष्मजीवों के उपयोग, रोग, खाद्य संरक्षण और नाइट्रोजन चक्र।"},
{"class":"8","subject":"science","code":"pressure-force","no":8,"name":"दाब और बल का आपसी संबंध","desc":"दाब, बल और क्षेत्रफल के संबंध तथा दैनिक जीवन के उदाहरण।"},
{"class":"8","subject":"science","code":"coal-petroleum","no":9,"name":"ईंधन : हमारी जरूरत","desc":"जीवाश्म ईंधन, उत्पाद, उपयोग और संरक्षण।"},
{"class":"8","subject":"science","code":"chemical-effects-electricity","no":10,"name":"विद्युत धारा के रासायनिक प्रभाव","desc":"चालक द्रव, रासायनिक परिवर्तन और विद्युतलेपन।"},
{"class":"8","subject":"science","code":"light","no":11,"name":"प्रकाश के खेल","desc":"परावर्तन, दर्पण और दृष्टि से जुड़े प्रकाश के प्रयोग।"},
{"class":"8","subject":"science","code":"conservation","no":12,"name":"पौधों और जंतुओं का संरक्षण : जैव विविधता","desc":"वनों की कटाई, जैव विविधता, संरक्षित क्षेत्र और पुनर्वनीकरण।"},
{"class":"8","subject":"science","code":"stars-sun","no":13,"name":"तारे और सूर्य का परिवार","desc":"सौरमंडल, तारे, ग्रह और आकाशीय पिंडों का अवलोकन।"},
{"class":"8","subject":"science","code":"cells","no":14,"name":"कोशिकाएँ : हर जीव की आधारभूत संरचना","desc":"कोशिका, पादप/जंतु कोशिका और प्रमुख कोशिकांगों की भूमिका।"},
{"class":"8","subject":"science","code":"reproduction-animals","no":15,"name":"जंतुओं में प्रजनन","desc":"लैंगिक जनन, निषेचन, भ्रूण विकास और कायांतरण।"},
{"class":"8","subject":"science","code":"metals-nonmetals","no":16,"name":"धातु और अधातु","desc":"धातु-अधातु के भौतिक और रासायनिक गुण तथा उपयोग।"},
{"class":"8","subject":"science","code":"adolescence","no":17,"name":"किशोरावस्था की ओर","desc":"यौवनारंभ, हार्मोन, शारीरिक परिवर्तन और स्वस्थ आदतें।"},
{"class":"8","subject":"science","code":"sound","no":18,"name":"ध्वनियाँ तरह-तरह की","desc":"कंपन, ध्वनि का उत्पादन/संचार, तीव्रता और श्रव्यता।"},
{"class":"8","subject":"science","code":"air-water-pollution","no":19,"name":"वायु एवं जल-प्रदूषण की समस्या","desc":"प्रदूषण के स्रोत, प्रभाव, रोकथाम और नागरिक जिम्मेदारी।"}
]'::jsonb)
  as x(class text,subject text,code text,no int,name text,desc text)
  loop
    insert into curriculum_chapter(subject_id,code,display_name,description,sort_order,curriculum_source,content_status,textbook_chapter_no,teaching_order,pedagogy_note)
    select s.id,r.code,r.name,r.desc,r.no,'SCERT Bihar textbook / teacher handbook reference','PUBLISHED',r.no,r.no,
      'Teacher sequence: activate prior knowledge → build the core idea → model an example → guided practice → independent practice → recap.'
    from curriculum_subject s join curriculum_class c on c.id=s.class_id
    where c.code=r.class and s.code=r.subject
    on conflict(subject_id,code) do update set
      display_name=excluded.display_name,description=excluded.description,sort_order=excluded.sort_order,
      curriculum_source=excluded.curriculum_source,content_status='PUBLISHED',
      textbook_chapter_no=excluded.textbook_chapter_no,teaching_order=excluded.teaching_order,
      pedagogy_note=excluded.pedagogy_note
    returning id into ch_id;
  end loop;
end $$;

-- V4 had broad placeholder chapter names for a few entries. Keep only the
-- canonical textbook set active; obsolete placeholders are retained for data
-- safety but hidden from the learning path.
update curriculum_chapter ch set active=false, content_status='ARCHIVED'
where not exists (
  select 1 from curriculum_class c join curriculum_subject s on s.class_id=c.id
  where s.id=ch.subject_id and c.code in ('6','7','8') and s.code in ('maths','science')
)
and false;

update curriculum_chapter
set active=false, content_status='ARCHIVED'
where code in ('geometry-basics-old','old-placeholder')
  and active=true;

insert into app_metadata(key,value) values ('schema','canonical-bihar-board-curriculum-v6')
on conflict(key) do update set value=excluded.value;
