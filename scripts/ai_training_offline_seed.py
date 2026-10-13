#!/usr/bin/env python3
"""Create a deterministic, API-key-free synthetic curriculum-aligned dataset.

These are original template-generated learning examples, not textbook extracts or
human-reviewed answers. Provenance records curriculum alignment URLs, never fake
page numbers or PDF hashes. Use this as a starter corpus pending source-grounded
generation and teacher review.
"""
from __future__ import annotations
import argparse, hashlib, json, random
from pathlib import Path

OFFICIAL = {
    "ncert": "https://ncert.nic.in/textbook.php",
    "bihar_scert": "https://scert.bihar.gov.in/",
}
TOPICS = {
 "6": {
  "maths": [("kn", "Knowing Our Numbers"), ("wn", "Whole Numbers"), ("pl", "Playing with Numbers"), ("gi", "Geometry"), ("int", "Integers"), ("fra", "Fractions"), ("dec", "Decimals"), ("data", "Data Handling"), ("mens", "Mensuration"), ("alg", "Algebra"), ("ratio", "Ratio and Proportion"), ("sym", "Symmetry")],
  "science": [("food", "Food and its Sources"), ("components", "Components of Food"), ("fibre", "Fibre to Fabric"), ("materials", "Sorting Materials"), ("separation", "Separation of Substances"), ("changes", "Changes Around Us"), ("plants", "Getting to Know Plants"), ("body", "Body Movements"), ("living", "Living Organisms"), ("motion", "Motion and Measurement"), ("light", "Light, Shadows and Reflections"), ("electricity", "Electricity and Circuits"), ("magnets", "Fun with Magnets"), ("water", "Water"), ("air", "Air Around Us"), ("waste", "Garbage In, Garbage Out")]
 },
 "7": {
  "maths": [("integers", "Integers"), ("fractions", "Fractions and Decimals"), ("data", "Data Handling"), ("equations", "Simple Equations"), ("lines", "Lines and Angles"), ("triangles", "The Triangle and its Properties"), ("congruence", "Congruence of Triangles"), ("compare", "Comparing Quantities"), ("rational", "Rational Numbers"), ("perimeter", "Perimeter and Area"), ("algebra", "Algebraic Expressions"), ("exponents", "Exponents and Powers"), ("symmetry", "Symmetry"), ("solid", "Visualising Solid Shapes")],
  "science": [("nutrition", "Nutrition in Plants"), ("animals", "Nutrition in Animals"), ("fibres", "Fibre to Fabric"), ("heat", "Heat"), ("acids", "Acids, Bases and Salts"), ("changes", "Physical and Chemical Changes"), ("weather", "Weather, Climate and Adaptations"), ("wind", "Winds, Storms and Cyclones"), ("soil", "Soil"), ("respiration", "Respiration in Organisms"), ("transport", "Transportation in Animals and Plants"), ("reproduction", "Reproduction in Plants"), ("motion", "Motion and Time"), ("electricity", "Electric Current and its Effects"), ("light", "Light"), ("water", "Water: A Precious Resource"), ("forests", "Forests: Our Lifeline"), ("waste", "Wastewater Story")]
 },
 "8": {
  "maths": [("rational", "Rational Numbers"), ("linear", "Linear Equations in One Variable"), ("quadrilaterals", "Understanding Quadrilaterals"), ("data", "Data Handling"), ("squares", "Squares and Square Roots"), ("cubes", "Cubes and Cube Roots"), ("comparing", "Comparing Quantities"), ("algebra", "Algebraic Expressions and Identities"), ("mensuration", "Mensuration"), ("exponents", "Exponents and Powers"), ("proportion", "Direct and Inverse Proportions"), ("factorisation", "Factorisation"), ("graphs", "Introduction to Graphs"), ("numbers", "Playing with Numbers")],
  "science": [("crops", "Crop Production and Management"), ("microbes", "Microorganisms: Friend and Foe"), ("materials", "Synthetic Fibres and Plastics"), ("metals", "Materials: Metals and Non-Metals"), ("fuels", "Coal and Petroleum"), ("combustion", "Combustion and Flame"), ("conservation", "Conservation of Plants and Animals"), ("cells", "Cell—Structure and Functions"), ("reproduction", "Reproduction in Animals"), ("adolescence", "Reaching the Age of Adolescence"), ("force", "Force and Pressure"), ("friction", "Friction"), ("sound", "Sound"), ("electricity", "Chemical Effects of Electric Current"), ("storms", "Some Natural Phenomena"), ("light", "Light"), ("space", "Stars and the Solar System"), ("water", "Pollution of Air and Water")]
 }
}
HINDI_SUBJECT = {"maths":"गणित", "science":"विज्ञान"}
def digest(s: str) -> str: return hashlib.sha256(s.encode("utf-8")).hexdigest()
def math_example(cls: str, topic: str, lang: str, i: int, rng: random.Random):
    a, b = rng.randint(2, 99), rng.randint(2, 30)
    kind = i % 8
    if topic in {"fra","fractions","dec","rational"}:
        den = rng.choice([2,3,4,5,6,8,10]); num = rng.randint(1,den-1)
        if lang == "hi":
            q = f"{num}/{den} का अर्थ समझाइए और बताइए कि यह 1 से छोटा क्यों है।"
            ans = f"भिन्न {num}/{den} में {num} अंश और {den} हर है। क्योंकि अंश हर से छोटा है, इसलिए इसका मान 1 से छोटा है।"
        else:
            q = f"Explain the fraction {num}/{den} and why it is less than one."
            ans = f"In {num}/{den}, {num} is the numerator and {den} is the denominator. Since the numerator is smaller than the denominator, the fraction is less than one."
    elif topic in {"mens","mensuration","perimeter","gi"} and kind % 2 == 0:
        length, width = rng.randint(3,30), rng.randint(2,20)
        if lang == "hi":
            q=f"एक आयत की लंबाई {length} सेमी और चौड़ाई {width} सेमी है। उसका परिमाप ज्ञात कीजिए।"
            ans=f"आयत का परिमाप = 2 × (लंबाई + चौड़ाई) = 2 × ({length} + {width}) = {2*(length+width)} सेमी।"
        else:
            q=f"A rectangle is {length} cm long and {width} cm wide. Find its perimeter."
            ans=f"Perimeter = 2 × (length + width) = 2 × ({length} + {width}) = {2*(length+width)} cm."
    elif topic in {"linear","equations","alg","algebra","factorisation"}:
        x=rng.randint(2,20); c=rng.randint(1,15); total=x+c
        if lang=="hi":
            q=f"समीकरण x + {c} = {total} हल कीजिए।"
            ans=f"दोनों पक्षों से {c} घटाएँ: x = {total} − {c} = {x}। जाँच: {x} + {c} = {total}।"
        else:
            q=f"Solve the equation x + {c} = {total}."
            ans=f"Subtract {c} from both sides: x = {total} − {c} = {x}. Check: {x} + {c} = {total}."
    elif topic in {"data","graphs"}:
        vals=[rng.randint(2,20) for _ in range(5)]; mean=sum(vals)/len(vals)
        if lang=="hi":
            q=f"इन संख्याओं {', '.join(map(str,vals))} का औसत निकालिए।"
            ans=f"औसत = सभी संख्याओं का योग ÷ कुल संख्या = {sum(vals)} ÷ 5 = {mean:g}।"
        else:
            q=f"Find the mean of these numbers: {', '.join(map(str,vals))}."
            ans=f"Mean = sum of values ÷ number of values = {sum(vals)} ÷ 5 = {mean:g}."
    elif topic in {"ratio","proportion","compare","comparing"}:
        p=rng.randint(5,80); rate=rng.choice([10,15,20,25]); value=p*rate/100
        if lang=="hi":
            q=f"{p} का {rate}% कितना होगा? चरणों सहित समझाइए।"
            ans=f"{rate}% का अर्थ {rate}/100 है। इसलिए {p} × {rate}/100 = {value:g}।"
        else:
            q=f"What is {rate}% of {p}? Show the steps."
            ans=f"{rate}% means {rate}/100. Therefore {p} × {rate}/100 = {value:g}."
    else:
        op = i % 4
        if op == 0: symbol, result = "+", a+b
        elif op == 1: symbol, result = "−", max(a,b)-min(a,b); a,b=max(a,b),min(a,b)
        elif op == 2: symbol, result = "×", a*(b%12+1); b=b%12+1
        else: symbol, result = "÷", a; a=a*b
        if lang=="hi":
            q=f"{a} {symbol} {b} हल कीजिए और अपनी गणना समझाइए।"
            ans=f"चरण-दर-चरण गणना करने पर {a} {symbol} {b} = {result}। उत्तर देने से पहले संक्रिया और गणना दोबारा जाँचें।"
        else:
            q=f"Calculate {a} {symbol} {b} and explain your working."
            ans=f"Working through the operation gives {a} {symbol} {b} = {result}. Check the operation and arithmetic before finalising the answer."
    return q, ans, "worked_example" if kind in (0,1,2,3) else "practice_generation"
SCIENCE = {
 "food":("Why do living things need food?","Living things need food for energy, growth, repair, and carrying out life processes.","जीवों को भोजन की आवश्यकता क्यों होती है?","जीवों को ऊर्जा, वृद्धि, शरीर की मरम्मत और जीवन-प्रक्रियाएँ चलाने के लिए भोजन चाहिए।"),
 "components":("Why is a balanced diet important?","A balanced diet supplies energy and nutrients in suitable amounts. No single food provides everything the body needs.","संतुलित आहार क्यों आवश्यक है?","संतुलित आहार ऊर्जा और पोषक तत्त्व उचित मात्रा में देता है। कोई एक भोजन शरीर की सभी आवश्यकताएँ पूरी नहीं करता।"),
 "plants":("How can you distinguish a herb from a tree?","Herbs usually have soft, green stems and are small; trees are generally taller and have a hard, woody trunk.","शाक और वृक्ष में क्या अंतर है?","शाक के तने सामान्यतः कोमल और हरे होते हैं तथा वे छोटे होते हैं; वृक्ष प्रायः बड़े होते हैं और उनका तना कठोर व काष्ठीय होता है।"),
 "electricity":("What makes a simple electric circuit work?","A circuit needs a source, conducting path, and a closed connection through the component. An open switch breaks the path.","सरल विद्युत परिपथ कब काम करता है?","परिपथ में स्रोत, चालक मार्ग और घटक से होकर बंद संपर्क होना चाहिए। खुला स्विच मार्ग को तोड़ देता है।"),
 "magnets":("What happens when like poles of two magnets face each other?","Like poles repel; unlike poles attract. Keep magnets away from devices that can be affected by magnetic fields.","जब दो चुंबकों के समान ध्रुव आमने-सामने हों तो क्या होता है?","समान ध्रुव एक-दूसरे को प्रतिकर्षित करते हैं और विपरीत ध्रुव आकर्षित करते हैं।"),
 "water":("Why should water be conserved?","Fresh water is limited and essential for people, plants, animals, farming, and ecosystems. Preventing leaks and avoiding waste help conserve it.","जल संरक्षण क्यों करना चाहिए?","मीठा जल सीमित है और मनुष्यों, पौधों, पशुओं, खेती तथा पारिस्थितिक तंत्र के लिए आवश्यक है। रिसाव रोकना और अपव्यय से बचना जल बचाते हैं।"),
 "nutrition":("How do green plants make food?","Green plants use light energy, water, and carbon dioxide to make sugars through photosynthesis; chlorophyll helps capture light.","हरे पौधे भोजन कैसे बनाते हैं?","हरे पौधे प्रकाश-संश्लेषण में प्रकाश ऊर्जा, जल और कार्बन डाइऑक्साइड से शर्करा बनाते हैं; क्लोरोफिल प्रकाश ग्रहण करने में सहायता करता है।"),
 "heat":("How is heat different from temperature?","Heat is energy transferred because of a temperature difference. Temperature indicates how hot or cold something is; the concepts are related but not identical.","ऊष्मा और तापमान में क्या अंतर है?","तापांतर के कारण स्थानांतरित होने वाली ऊर्जा को ऊष्मा कहते हैं। तापमान बताता है कि कोई वस्तु कितनी गर्म या ठंडी है; दोनों संबंधित हैं, पर एक नहीं हैं।"),
 "acids":("What is a safe way to investigate whether a solution is acidic or basic?","Use a suitable indicator, follow lab instructions, wear eye protection, and never taste an unknown substance. Indicators show a colour change depending on the solution.","किसी विलयन की अम्लीय या क्षारीय प्रकृति सुरक्षित रूप से कैसे जाँचें?","उपयुक्त सूचक का उपयोग करें, प्रयोगशाला निर्देश मानें और आँखों की सुरक्षा करें। अज्ञात पदार्थ को कभी न चखें। सूचक विलयन के अनुसार रंग बदलता है।"),
 "respiration":("Why do cells need respiration?","Cellular respiration releases usable energy from food molecules. In many organisms it commonly uses oxygen, but some forms can occur without oxygen.","कोशिकाओं को श्वसन की आवश्यकता क्यों होती है?","कोशिकीय श्वसन भोजन के अणुओं से उपयोगी ऊर्जा मुक्त करता है। अनेक जीवों में इसमें सामान्यतः ऑक्सीजन का उपयोग होता है, लेकिन कुछ प्रकार ऑक्सीजन के बिना भी हो सकते हैं।"),
 "force":("What is a force, and what can it do?","A force is a push or pull. It can change an object's motion, direction, or shape; the effect depends on the situation.","बल क्या है और यह क्या कर सकता है?","बल धक्का या खिंचाव है। यह वस्तु की गति, दिशा या आकार बदल सकता है; प्रभाव परिस्थिति पर निर्भर करता है।"),
 "friction":("Give one useful effect and one drawback of friction.","Friction provides grip for walking and braking. It can also cause wear and unwanted heating in moving parts.","घर्षण का एक लाभ और एक नुकसान बताइए।","घर्षण चलने और ब्रेक लगाने में पकड़ देता है। यह चलने वाले भागों में घिसाव और अनचाही गर्मी भी पैदा कर सकता है।"),
 "sound":("How is sound produced?","Sound is produced by vibrating objects. It travels through a material medium and does not travel through a perfect vacuum.","ध्वनि कैसे उत्पन्न होती है?","ध्वनि कंपन करती वस्तुओं से उत्पन्न होती है। यह किसी भौतिक माध्यम से चलती है और पूर्ण निर्वात में नहीं चलती।"),
 "cells":("Why is a cell called a basic unit of life?","A cell is the smallest unit that carries out essential life processes. Organisms may consist of one cell or many specialised cells.","कोशिका को जीवन की मूल इकाई क्यों कहते हैं?","कोशिका जीवन की आवश्यक प्रक्रियाएँ करने वाली सबसे छोटी इकाई है। जीव एक कोशिका या अनेक विशिष्ट कोशिकाओं से बने हो सकते हैं।"),
 "crops":("Why do farmers prepare soil before sowing?","Soil preparation loosens and aerates the soil, helps roots grow, and can make it easier to mix nutrients and manage weeds.","बुवाई से पहले किसान मिट्टी तैयार क्यों करते हैं?","मिट्टी तैयार करने से वह भुरभुरी और हवादार होती है, जड़ों को बढ़ने में मदद मिलती है और पोषक तत्त्व मिलाना तथा खरपतवार संभालना आसान हो सकता है।"),
 "microbes":("Are all microorganisms harmful? Explain.","No. Some microorganisms help make foods, decompose waste, and support nutrient cycles. Others can cause disease, so context and hygiene matter.","क्या सभी सूक्ष्मजीव हानिकारक होते हैं? समझाइए।","नहीं। कुछ सूक्ष्मजीव भोजन बनाने, कचरा विघटित करने और पोषक चक्रों में मदद करते हैं। कुछ रोग पैदा कर सकते हैं, इसलिए परिस्थिति और स्वच्छता महत्त्वपूर्ण हैं।"),
 "light":("Why can we see an object?","We see an object when light from it, either emitted or reflected, reaches our eyes.","हम किसी वस्तु को क्यों देख पाते हैं?","जब किसी वस्तु से निकला या उससे परावर्तित प्रकाश हमारी आँखों तक पहुँचता है, तब हम उसे देख पाते हैं।"),
 "weather":("How are weather and climate different?","Weather describes atmospheric conditions over a short period; climate describes typical patterns over many years.","मौसम और जलवायु में क्या अंतर है?","मौसम कम समय की वायुमंडलीय दशाएँ बताता है; जलवायु अनेक वर्षों के सामान्य प्रतिरूप बताती है।"),
 "air":("Why is air important for life?","Air contains gases used in respiration and photosynthesis, and the atmosphere also helps regulate Earth's temperature.","वायु जीवन के लिए क्यों महत्त्वपूर्ण है?","वायु में श्वसन और प्रकाश-संश्लेषण में काम आने वाली गैसें हैं। वायुमंडल पृथ्वी के तापमान को नियंत्रित करने में भी मदद करता है।"),
}
def science_example(topic, lang, i):
    pair = SCIENCE.get(topic)
    if pair is None:
        pair = ("What is one key idea to remember about this topic?", f"Start by defining the main concept in your own words, connect it to an everyday observation, and distinguish evidence from a guess. Topic focus: {topic.replace('_',' ')}.",
                "इस विषय की एक मुख्य बात क्या याद रखनी चाहिए?", f"पहले मुख्य अवधारणा को अपने शब्दों में परिभाषित करें, उसे रोज़मर्रा के अवलोकन से जोड़ें और प्रमाण तथा अनुमान में अंतर करें। विषय: {topic.replace('_',' ')}।")
    enq, ena, hiq, hia = pair
    variants_en = [
      (enq, ena, "concept_explanation"),
      ("A learner has a misconception about this topic: " + enq, ena + " Ask what evidence or observation supports the explanation.", "misconception"),
      ("Explain this idea to a Class  %s learner using a familiar example: " % "", enq + " " + ena, "tutor_dialogue"),
      ("Write one safe observation or reflection activity related to " + topic.replace("_"," ") + ".", "Observe a relevant everyday example, record what you notice, and explain how it relates to the concept. Do not handle unknown chemicals, electricity, or hazardous materials.", "science_activity"),
    ]
    variants_hi = [
      (hiq, hia, "concept_explanation"),
      ("एक विद्यार्थी की इस विषय पर गलत धारणा है: " + hiq, hia + " उससे पूछें कि कौन-सा प्रमाण या अवलोकन इस व्याख्या का समर्थन करता है।", "misconception"),
      ("इस विचार को कक्षा के विद्यार्थी को रोज़मर्रा के उदाहरण से समझाइए: " + hiq, hia, "tutor_dialogue"),
      (topic + " से जुड़ी एक सुरक्षित अवलोकन गतिविधि बताइए।", "रोज़मर्रा का उपयुक्त उदाहरण देखें, अवलोकन लिखें और बताएँ कि वह अवधारणा से कैसे जुड़ता है। अज्ञात रसायन, बिजली या खतरनाक वस्तुओं को न छुएँ।", "science_activity"),
    ]
    variants = variants_hi if lang=="hi" else variants_en
    q,a,t = variants[i % len(variants)]
    if "%s" in q: q=q.replace("%s","")
    return q,a,t
def main():
    p=argparse.ArgumentParser()
    p.add_argument("--target",type=int,default=25000)
    p.add_argument("--output-dir",type=Path,default=Path("data/ai-training/datasets"))
    a=p.parse_args()
    if a.target < 1: p.error("--target must be positive")
    records=[]
    for cls, subjects in TOPICS.items():
      for subject, topics in subjects.items():
       for lang in ("hi","en"):
        track=[]
        rng=random.Random(f"quantaedge-{cls}-{subject}-{lang}-v1")
        i=0
        while len(track) < a.target:
         code,title=topics[i%len(topics)]
         if subject=="maths":
          q,answer,kind=math_example(cls,code,lang,i,rng)
         else:
          q,answer,kind=science_example(code,lang,i)
         # Add a topic-specific learner task that changes the prompt without inventing source page numbers.
         if i % 4 == 3:
          if lang=="hi": q=f"कक्षा {cls} के विद्यार्थी के लिए ‘{title}’ विषय समझने में मदद करें। प्रश्न: {q}"
          else: q=f"Help a Class {cls} learner understand '{title}'. Question: {q}"
         metadata={
          "class_code":cls,"subject_code":subject,"language":lang,"chapter_code":code,"chapter_title":title,
          "example_type":kind,"source_id":f"curriculum-{cls}-{subject}-{code}",
          "source_title":"Curriculum-aligned synthetic example; not a textbook excerpt",
          "source_url":OFFICIAL["bihar_scert"] if cls in {"6","7","8"} else OFFICIAL["ncert"],
          "source_edition":"curriculum-alignment-reference; exact textbook edition/page not asserted",
          "source_pdf_sha256":None,"source_pages":[],
          "provenance_type":"synthetic_curriculum_alignment",
          "generation_method":"deterministic_original_templates_v1",
          "review_status":"machine_validated_draft","rights_status":"review_before_production",
          "duplicate_group_id":None
         }
         messages=[
          {"role":"system","content":("तुम बिहार बोर्ड कक्षा 6–8 के धैर्यवान शिक्षक हो। सरल हिंदी में सही, चरणबद्ध और उम्र-अनुकूल उत्तर दो।" if lang=="hi" else "You are a patient Bihar Board Classes 6–8 teacher. Give accurate, stepwise, age-appropriate explanations in clear English.")},
          {"role":"user","content":q},{"role":"assistant","content":answer}
         ]
         eid="qe-"+digest(json.dumps([cls,subject,lang,code,i,q,answer],ensure_ascii=False))[:24]
         track.append({"example_id":eid,"messages":messages,"metadata":metadata})
         i+=1
        records.extend(track)
    # A target other than the default is supported, but the standard corpus is exactly 25k.
    records=records[:a.target]
    # Stable split by example ID gives exact record-level disjointness. A separate check
    # documents that template families may share concepts and require teacher review.
    records.sort(key=lambda r:r["example_id"])
    splits={"train":[],"validation":[],"test":[]}
    for row in records:
      n=int(digest(row["example_id"])[:8],16)%100
      splits["train" if n<80 else ("validation" if n<90 else "test")].append(row)
    a.output_dir.mkdir(parents=True,exist_ok=True)
    def dump(path,rows):
      with path.open("w",encoding="utf-8",newline="\n") as f:
       for row in rows: f.write(json.dumps(row,ensure_ascii=False,sort_keys=True)+"\n")
    dump(a.output_dir/"dataset_master.jsonl",records)
    for name,rows in splits.items(): dump(a.output_dir/f"{name}.jsonl",rows)
    counts={}
    for row in records:
      m=row["metadata"]; key=f"class-{m['class_code']}/{m['subject_code']}/{m['language']}"
      counts[key]=counts.get(key,0)+1
    manifest={
      "schema_version":1,"dataset_name":"quantaedge-ai-tutor","records":len(records),
      "splits":{k:len(v) for k,v in splits.items()},"class_subject_language_counts":counts,
      "provenance_policy":"Official curriculum portal URLs indicate alignment references only; no claim of exact source page or PDF extraction.",
      "generation_method":"deterministic_original_templates_v1","status":"SYNTHETIC_DRAFT_REQUIRES_TEACHER_REVIEW",
      "limitations":["Not generated by an LLM; no API key required.","Not a substitute for source-grounded SCERT/NCERT extraction.","Template repetition and coverage require teacher review before fine-tuning or production use.","Do not describe these records as official SCERT/NCERT questions."]
    }
    manifest["master_sha256"]=digest((a.output_dir/"dataset_master.jsonl").read_text(encoding="utf-8"))
    (a.output_dir/"manifest.json").write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({"records":len(records),"splits":manifest["splits"],"output_dir":str(a.output_dir),"status":manifest["status"]},ensure_ascii=False,indent=2))
if __name__=="__main__": main()
