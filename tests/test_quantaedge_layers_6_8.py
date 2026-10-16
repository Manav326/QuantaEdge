import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts.ai_training_dataset import request_examples
from types import SimpleNamespace
from scripts.quantaedge_layers_6_8 import (
    PROMPT_VERSION, LESSON_CONTRACT_VERSION, benchmark as run_benchmark, lesson_from, load_visual_candidates, ollama_chat, sha, split_sft, refresh_benchmark,
    validate_lesson, validate_output, validate_splits, segments_for, tutor_citations_valid, write_rag,
    write_json, write_jsonl, write_outputs, prompt_for, run as run_layers,
)

class FakeResponse:
    def __init__(self, data): self.data=json.dumps(data).encode()
    def __enter__(self): return self
    def __exit__(self,*args): return False
    def read(self): return self.data

def model_json(pages):
    bi={"en":"Understand the idea.","hi":"विचार को समझें।"}
    q={"type":"multiple_choice","prompt_by_locale":{"en":"Which statement is correct?","hi":"कौन-सा कथन सही है?"},"source_pages":pages[:1],"difficulty":"easy",
       "options":[{"id":"a","text_by_locale":{"en":"Correct","hi":"सही"}},{"id":"b","text_by_locale":{"en":"Wrong","hi":"गलत"}}],
       "correct_answer_ids":["a"],"feedback_by_locale":bi}
    return {"chapter_number":1,"chapter_title_by_locale":{"en":"Curiosity","hi":"जिज्ञासा"},"mapping_confidence":"HIGH",
      "topic_title_by_locale":{"en":"Observation","hi":"अवलोकन"},"microtopic_title_by_locale":{"en":"Careful observation","hi":"ध्यानपूर्वक अवलोकन"},
      "concepts_by_locale":{"en":["observation"],"hi":["अवलोकन"]},"learning_objectives":[{"statement_by_locale":bi}],
      "explanation_by_locale":{"en":{"heading":"Explain","paragraphs":["Explain in original words."]},"hi":{"heading":"समझें","paragraphs":["अपने शब्दों में समझें।"]}},
      "worked_example_by_locale":{"en":{"heading":"Example","paragraphs":["Observe a leaf and note two features."]},"hi":{"heading":"उदाहरण","paragraphs":["पत्ती देखें और दो विशेषताएँ लिखें।"]}},
      "recap_by_locale":bi,"questions":[q,{**q,"prompt_by_locale":{"en":"Which observation supports the idea?","hi":"कौन-सा अवलोकन इस विचार का समर्थन करता है?"},"source_pages":pages[:1]}],
      "tutor_examples":[{"language":"en","student_message":"Why observe?","assistant_message":"It separates evidence from guesses.","source_pages":pages[:1]},
                        {"language":"hi","student_message":"ध्यान से क्यों देखें?","assistant_message":"इससे प्रमाण और अनुमान अलग होते हैं।","source_pages":pages[:1]}]}

def install_layer5_fixture(root:Path, pagesdir:Path, book:str, pdfhash:str, body:str)->Path:
    """Create a complete synthetic L1/L4/L5 source chain for bridge integration tests."""
    import hashlib
    import os
    asset_hash="c"*64
    analysis={"visual_summary":"A leaf diagram","visual_type":"illustration","visible_text":["leaf"],
              "concepts":["leaf","plant parts"],"context_relation":"next to the source paragraph",
              "limitations":["Machine observations require human review."]}
    invdir=root/"data"/"textbook-visuals"/"curiosity"; invdir.mkdir(parents=True,exist_ok=True)
    inventory_path=invdir/"visual-inventory.v1.json"
    inventory={"schema_version":"quantaedge.visual-inventory.v1","inventory_status":"DRAFT_VISUAL_INVENTORY_ONLY",
      "source":{"book_id":book,"pdf_sha256":pdfhash,"pdf_page_count":1,"class":"6","language":"English","subject":"Science","edition":"Reprint 2026-27"},
      "summary":{"pages_scanned":1,"asset_count":1},"pages":[{"source_pdf_page_number":1,"page_text_sha256":sha(body)}],
      "assets":[{"asset_id":"asset-preview-1","kind":"page_preview","source_book_id":book,
        "source_pdf_sha256":pdfhash,"source_pdf_page_number":1,"asset_path":"assets/page-1.jpg",
        "media_type":"image/jpeg","asset_sha256":asset_hash,"bbox":None}]}
    write_json(inventory_path,inventory)
    raw_path=invdir/"visual-analysis-qwen2.5vl-3b.v1.json"
    raw_result={"asset_id":"asset-preview-1","asset_sha256":asset_hash,"source_book_id":book,
      "source_pdf_sha256":pdfhash,"source_pdf_page_number":1,"asset_path":"assets/page-1.jpg","asset_kind":"page_preview",
      "model_provider":"ollama_local","model":"qwen2.5vl:3b","analysis_status":"DRAFT_REQUIRES_REVIEW","analysis":analysis,
      "human_review_required":True,"publication_allowed":False}
    raw={"schema_version":"quantaedge.visual-analysis.v1","source_book_id":book,"source_pdf_sha256":pdfhash,
      "model":"qwen2.5vl:3b","prompt_version":"visual-analysis-prompt-v2","results":[raw_result],
      "human_review_required":True,"publication_allowed":False}
    write_json(raw_path,raw)
    ledgerdir=root/"data"/"textbook-content-pipeline"/"class-6"/"books"/"curiosity"
    ledgerdir.mkdir(parents=True,exist_ok=True)
    effective_path=ledgerdir/"visual-analysis-effective.v1.json"
    effective_result={**raw_result,"effective_analysis":analysis,"correction_status":"NONE"}
    effective={"schema_version":"quantaedge.visual-analysis-effective.v1","source_book_id":book,
      "source_pdf_sha256":pdfhash,"raw_analysis_path":os.path.relpath(raw_path,root).replace("\\\\","/"),
      "raw_analysis_sha256":hashlib.sha256(raw_path.read_bytes()).hexdigest(),"model":"qwen2.5vl:3b",
      "prompt_version":"visual-analysis-prompt-v2","corrections_path":None,"corrections_applied_count":0,
      "results":[effective_result],"human_review_required":True,"publication_allowed":False}
    write_json(effective_path,effective)
    ledger_path=ledgerdir/"coverage-ledger.v1.json"
    page={"source_pdf_page_number":1,"inventory_page_records":1,"text_records":1,
      "text_evidence":[{"text_sha256":sha(body),"character_count":len(body),"text_preview":body[:400],
        "extraction_method":"synthetic-test","extraction_status":"TEXT_EXTRACTED_REQUIRES_CONTENT_REVIEW"}],
      "text_status":"COMPLETE","visual_asset_ids":["asset-preview-1"],"chapter_id":None,"topic_ids":[],
      "mapping_status":"NOT_STARTED","human_review_required":True}
    visual={"asset_id":"asset-preview-1","asset_kind":"page_preview","asset_sha256":asset_hash,
      "source_pdf_sha256":pdfhash,"source_pdf_page_number":1,"asset_path":"assets/page-1.jpg","media_type":"image/jpeg",
      "bbox":None,"analysis_status":"ANALYZED_REQUIRES_HUMAN_REVIEW","correction_status":"NONE",
      "effective_analysis":analysis,"chapter_id":None,"topic_ids":[],"mapping_status":"NOT_STARTED",
      "human_review_required":True,"publication_allowed":False}
    relative=lambda p:p.resolve().relative_to(root.resolve()).as_posix()
    ledger={"schema_version":"quantaedge.textbook-coverage-ledger.v1","architecture_version":"8-layer-resumable-v1","layer":5,
      "ledger_status":"COMPLETE","source":{"class":"6","language":"english","subject":"Science","source_book_id":book,
        "source_pdf_sha256":pdfhash,"edition":"Reprint 2026-27","pdf_page_count":1},
      "source_paths":{"inventory":relative(inventory_path),"raw_analysis":relative(raw_path),
        "manifest":relative(pagesdir/"book.manifest.json"),"text_jsonl":relative(pagesdir/"pages.jsonl"),
        "corrections":None,"effective_analysis_output":relative(effective_path),"coverage_ledger_output":relative(ledger_path)},
      "summary":{"source_pdf_page_count":1,"inventory_page_records":1,"page_text_records":1,
        "pages_with_unresolved_text":0,"inventory_asset_rows":1,"eligible_visual_asset_rows":1,
        "raw_analysis_result_rows":1,"successful_visual_analyses":1,"missing_visual_analyses":0,
        "failed_visual_analyses":0,"orphan_analysis_results":0,"corrections_applied":0,"unresolved_count":0,
        "mapping_status":"NOT_STARTED","analysis_model":"qwen2.5vl:3b","analysis_prompt_version":"visual-analysis-prompt-v2",
        "publication_allowed":False},
      "pages":[page],"visuals":[visual],"orphan_analysis_asset_ids":[],
      "human_review_required":True,"publication_allowed":False}
    write_json(ledger_path,ledger)
    return ledger_path


class LayersTests(unittest.TestCase):
    def test_segments_preserve_every_character_and_empty_pages(self):
        pages=[{"page_number":1,"text":"A"*2200},{"page_number":2,"text":""},{"page_number":3,"text":"हिंदी text "*500}]
        segments=segments_for(pages,2,2500); seen={}
        for s in segments:
            for piece in s["pieces"]: seen.setdefault(piece["page"],[]).append(piece["text"])
        self.assertEqual(pages[0]["text"],"".join(seen[1]))
        self.assertEqual("", "".join(seen[2]))
        self.assertEqual(pages[2]["text"],"".join(seen[3]))
        self.assertEqual({1,2,3},{n for s in segments for n in s["source_pages"]})

    def test_native_ollama_uses_json_api_and_no_key(self):
        got={}
        def open_(request,timeout=180):
            got["url"]=request.full_url; got["headers"]={k.lower():v for k,v in request.header_items()}
            got["body"]=json.loads(request.data.decode())
            return FakeResponse({"message":{"content":json.dumps(model_json([1]))}})
        v,elapsed=ollama_chat("http://127.0.0.1:11434","qwen2.5:1.5b","prompt",opener=open_)
        self.assertTrue(got["url"].endswith("/api/chat")); self.assertNotIn("authorization",got["headers"])
        self.assertEqual("json",got["body"]["format"]); self.assertEqual(3072,got["body"]["options"]["num_predict"]); self.assertEqual(8192,got["body"]["options"]["num_ctx"]); self.assertEqual("Careful observation",v["microtopic_title_by_locale"]["en"])
        self.assertGreaterEqual(elapsed,0)

    def test_external_ollama_endpoint_is_blocked(self):
        with self.assertRaisesRegex(ValueError,"loopback"):
            ollama_chat("https://example.com","m","p",opener=lambda *a,**k:self.fail("network must not run"))

    def test_structured_lesson_contract_and_source_refs(self):
        generated=model_json([1,2]); self.assertEqual([],validate_output(generated,{1,2}))
        source={"book_id":"ncert-c6-english-fecu1","source_pdf_sha256":"a"*64,"source_url":"https://ncert.nic.in/textbook.php?x=1",
          "title":"Curiosity","edition":"Reprint 2026-27","class_no":6,"subject":"science","language":"en"}
        seg={"segment_id":"seg1","sequence":1,"source_pages":[1,2]}
        lesson=lesson_from(source,seg,generated); self.assertEqual([],validate_lesson(lesson))
        self.assertFalse(lesson["workflow"]["publication_allowed"])
        self.assertEqual(6,lesson["curriculum"]["class"])
        self.assertEqual("science",lesson["curriculum"]["subject"])
        self.assertEqual("draft_lesson",lesson["artifact_type"])
        self.assertEqual(2,len(lesson["practice"]["questions"]))

    def test_question_types_are_contextual_and_question_count_is_not_fixed(self):
        generated=model_json([1,2])
        generated["questions"]=[generated["questions"][0]]
        self.assertEqual([],validate_output(generated,{1,2}))
        source={"book_id":"ncert-c6-english-fecu1","source_pdf_sha256":"a"*64,"source_url":"https://ncert.nic.in/textbook.php?x=1",
          "title":"Curiosity","edition":"Reprint 2026-27","class_no":6,"subject":"science","language":"en"}
        seg={"segment_id":"seg-contextual","sequence":1,"source_pages":[1,2]}
        lesson=lesson_from(source,seg,generated)
        self.assertEqual(1,len(lesson["practice"]["questions"]))
        self.assertEqual([],validate_lesson(lesson))

    def test_true_false_type_is_preserved_without_mcq_quota(self):
        generated=model_json([1,2])
        tf={"type":"true_false","prompt_by_locale":{"en":"A leaf is a living part of a plant.","hi":"पत्ती पौधे का जीवित भाग है।"},
            "feedback_by_locale":{"en":"Leaves are living plant organs.","hi":"पत्तियाँ पौधे के जीवित अंग हैं।"},
            "source_pages":[1],"difficulty":"easy","correct_answer":True}
        generated["questions"]=[generated["questions"][0],tf]
        self.assertEqual([],validate_output(generated,{1,2}))
        source={"book_id":"ncert-c6-english-fecu1","source_pdf_sha256":"a"*64,"source_url":"https://ncert.nic.in/textbook.php?x=1",
          "title":"Curiosity","edition":"Reprint 2026-27","class_no":6,"subject":"science","language":"en"}
        seg={"segment_id":"seg-types","sequence":1,"source_pages":[1,2]}
        lesson=lesson_from(source,seg,generated)
        self.assertEqual(["multiple_choice","true_false"],[q["type"] for q in lesson["practice"]["questions"]])
        self.assertEqual([],validate_lesson(lesson))

    def test_image_observation_requires_allowlisted_visual(self):
        generated=model_json([1,2])
        generated["questions"][0]["type"]="image_observation"
        generated["questions"][0]["visual_candidate_id"]="candidate-not-present"
        errors=validate_output(generated,{1,2},[])
        self.assertTrue(any("visual_candidate_id" in error for error in errors))

    def test_all_supported_question_types_use_their_own_contract(self):
        bi={"en":"Useful feedback.","hi":"उपयोगी प्रतिक्रिया।"}
        options=[
            {"id":"a","text_by_locale":{"en":"Correct","hi":"सही"}},
            {"id":"b","text_by_locale":{"en":"Incorrect","hi":"गलत"}}
        ]
        base_q={"prompt_by_locale":{"en":"What does the evidence show?","hi":"प्रमाण क्या दिखाता है?"},
                "feedback_by_locale":bi,"source_pages":[1],"difficulty":"easy"}
        left=[{"id":"l1","label_by_locale":{"en":"Leaf","hi":"पत्ती"}}]
        right=[{"id":"r1","label_by_locale":{"en":"Plant part","hi":"पौधे का भाग"}}]
        visual={"candidate_id":"v3-image-abcdefgh","source_pdf_page_number":1,
                "url":"https://raw.githubusercontent.com/Manav326/QuantaEdge/main/example.jpg",
                "source_asset_sha256":"b"*64,"display_sha256":"c"*64}
        cases=[
            ("multiple_choice",{"options":options,"correct_answer_ids":["a"]},[]),
            ("multiple_select",{"options":options,"correct_answer_ids":["a","b"]},[]),
            ("true_false",{"correct_answer":True},[]),
            ("matching",{"left_items":left,"right_items":right,
                         "correct_pairs":[{"left_id":"l1","right_id":"r1"}]},[]),
            ("fill_blank",{"accepted_answers":{"en":["leaf"],"hi":["पत्ती"]}},[]),
            ("image_observation",{"options":options,"correct_answer_ids":["a"],
                                  "visual_candidate_id":visual["candidate_id"]},[visual]),
            ("short_answer",{"answer_guide_by_locale":{
                                "en":{"sample_answer":"A leaf is a plant part.","key_points":["It is part of a plant."]},
                                "hi":{"sample_answer":"पत्ती पौधे का एक भाग है।","key_points":["यह पौधे का भाग है।"]}},
                             "grading":{"mode":"rubric","max_score":2,"rubric_by_locale":{"en":"Award up to two points.","hi":"दो अंक तक दें।"}}},[]),
            ("scenario_based",{"scenario_by_locale":{"en":"A learner observes a plant.","hi":"एक विद्यार्थी पौधे का अवलोकन करता है।"},
                               "options":options,"correct_answer_ids":["a"]},[])
        ]
        source={"book_id":"ncert-c6-english-fecu1","source_pdf_sha256":"a"*64,
                "source_url":"https://ncert.nic.in/textbook.php?x=1","title":"Curiosity",
                "edition":"Reprint 2026-27","class_no":6,"subject":"science","language":"en"}
        segment={"segment_id":"seg-contracts","sequence":1,"source_pages":[1,2]}
        for qtype,fields,visuals in cases:
            with self.subTest(question_type=qtype):
                generated=model_json([1,2])
                generated["questions"]=[{"type":qtype,**base_q,**fields}]
                self.assertEqual([],validate_output(generated,{1,2},visuals))
                lesson=lesson_from(source,segment,generated,visuals)
                self.assertIsNotNone(lesson)
                self.assertEqual(qtype,lesson["practice"]["questions"][0]["type"])
                self.assertEqual([],validate_lesson(lesson))

    def test_prompt_has_no_fixed_question_type_distribution(self):
        source={"class_no":6,"subject":"science","language":"en","book_id":"book","title":"Curiosity","edition":"Reprint"}
        seg={"source_pages":[1],"pieces":[{"page":1,"part":1,"text":"Plants have leaves."}]}
        prompt=prompt_for(source,seg,[])
        self.assertIn("There is NO question-type quota",prompt)
        self.assertIn("Return 1 to 6 distinct, useful questions",prompt)
        self.assertNotIn("exactly two MCQs",prompt.lower())

    def test_invalid_page_citations_fail_validation(self):
        v=model_json([99]); self.assertTrue(any("citations" in x for x in validate_output(v,{1,2})))

    def test_unresolved_chapter_must_not_generate_lesson(self):
        from scripts.quantaedge_layers_6_8 import lesson_from
        v=model_json([1]); v["chapter_number"]=None; v["mapping_confidence"]="LOW"; v["chapter_title_by_locale"]={"en":"","hi":""}
        self.assertEqual([],validate_output(v,{1}))
        source={"book_id":"b","source_pdf_sha256":"a"*64,"source_url":"https://ncert.nic.in/x","title":"Book","edition":"Reprint","class_no":6,"subject":"science","language":"en"}
        self.assertIsNone(lesson_from(source,{"segment_id":"s","sequence":1,"source_pages":[1]},v))

    def test_sft_split_groups_do_not_leak(self):
        rows=[]
        for i in range(100):
            group="chapter-"+str(i%12)
            rows.append({"example_id":str(i),"messages":[],"metadata":{"source_group_id":group,"review_status":"machine_validated_draft","publication_allowed":False}})
        parts=split_sft(rows); self.assertEqual([],validate_splits(rows,parts))

    def test_rag_corpus_covers_empty_and_nonempty_pages(self):
        from scripts.quantaedge_layers_6_8 import write_jsonl, read_jsonl
        with tempfile.TemporaryDirectory() as temp:
            source={"pages":[{"page_number":1,"text":"evidence","text_sha256":sha("evidence"),"has_text":True},
                             {"page_number":2,"text":"","text_sha256":sha(""),"has_text":False}],
              "page_count":2,"book_id":"b","source_pdf_sha256":"a"*64,"class_no":6,"subject":"science","language":"en",
              "title":"Book","source_url":"https://ncert.nic.in/x","edition":"Reprint"}
            path=Path(temp)/"rag.jsonl"; write_rag(path,source); rows=read_jsonl(path)
            self.assertEqual(2,len(rows)); self.assertEqual("evidence",rows[0]["text"])
            self.assertFalse(rows[1]["eligible_for_retrieval"]); self.assertTrue(all(r["publication_allowed"] is False for r in rows))

    def test_existing_training_script_native_ollama_has_no_api_key(self):
        captured={}
        def open_(request,timeout=120):
            captured["url"]=request.full_url; captured["headers"]={k.lower():v for k,v in request.header_items()}
            captured["body"]=json.loads(request.data.decode())
            return FakeResponse({"message":{"content":json.dumps({"examples":[{"example_type":"concept_explanation","student_message":"Why?","assistant_message":"Because.","source_pages":[1]}]})}})
        ctx={"chapter":{"code":"ch1","title":"Test","pages":[{"page_number":1,"text":"Enough source context for a small test concept."}]},
             "source":{"title":"Book","edition":"Reprint"},"curriculum":{"class_code":"6","subject_code":"science"}}
        with patch("scripts.ai_training_dataset.urllib.request.urlopen",open_):
            rows=request_examples(ctx,"en",1,"qwen2.5:1.5b","http://127.0.0.1:11434","",provider="ollama")
        self.assertEqual(1,len(rows)); self.assertTrue(captured["url"].endswith("/api/chat"))
        self.assertNotIn("authorization",captured["headers"]); self.assertEqual("json",captured["body"]["format"])


    def test_resumes_completed_checkpoint_and_rejects_changed_source_text(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); book="ncert-c6-english-fecu1"; pdfhash="b"*64
            pagesdir=root/"data"/"textbook-extractions"/"class-6"/"english"/"pages"
            pagesdir.mkdir(parents=True)
            text="Curiosity begins with careful observation. " * 8
            source_path=pagesdir/"pages.jsonl"
            row={"schema_version":1,"book_id":book,"class":"6","language":"english","subject":"Science",
                 "asset_pdf_sha256":pdfhash,"original_book_sha256":pdfhash,"asset_page_index":0,
                 "source_book_page_number":1,"text":text,"review_status":"TEXT_EXTRACTED_REQUIRES_CONTENT_REVIEW"}
            write_jsonl(source_path,[row])
            write_json(pagesdir/"book.manifest.json",{"schema_version":1,"book_id":book,"class":"6","language":"english",
                "subject":"Science","scope":"SUBJECT_BOOK","published":False,"asset_pdf_sha256":pdfhash,
                "original_book_sha256":pdfhash,"page_count":1,"completed_pages":1,"source_page_start":1,
                "text_jsonl":"pages.jsonl","source_url":"https://ncert.nic.in/textbook.php?fecu1=0-12",
                "title":"Curiosity","source_title":"Curiosity","edition":"Reprint 2026-27"})
            ledger=install_layer5_fixture(root,pagesdir,book,pdfhash,text)
            benchmark=root/"data"/"textbook-content-pipeline"/"model-benchmarks"/"class-6-ncert-curiosity.v1.json"
            no_visual_fingerprint=sha("no-matching-visual-pack|"+book+"|"+pdfhash)
            write_json(benchmark,{"schema_version":"quantaedge.local-ollama-benchmark.v1",
                "source":{"book_id":book,"source_pdf_sha256":pdfhash},
                "visual_candidate_fingerprint":no_visual_fingerprint,
                "production_model_selection":"PENDING_HUMAN_REVIEW","production_model":None,
                "prompt_version":PROMPT_VERSION,"lesson_contract_version":LESSON_CONTRACT_VERSION,
                "models":[{"model":"qwen2.5:1.5b","successful_samples":1,"lesson_contract_valid_rate":1.0}]})
            args=SimpleNamespace(repo_root=root,class_no=6,source_book_id=book,model="qwen2.5:1.5b",benchmark_report=None,
                max_pages_per_segment=4,max_context_chars=7000,endpoint="http://127.0.0.1:11434",
                max_model_calls=3,checkpoint_every=1,request_timeout=30,commit_checkpoints=False)
            calls={"count":0}; prompts=[]
            def open_(request,timeout=180):
                calls["count"]+=1
                payload=json.loads(request.data.decode())
                self.assertEqual("json",payload["format"])
                prompts.append(payload["messages"][1]["content"])
                return FakeResponse({"message":{"content":json.dumps(model_json([1]))}})
            result=run_layers(args,opener=open_)
            self.assertEqual(1,calls["count"])
            self.assertEqual(1,result["segments_completed"])
            self.assertEqual("DRAFT_REQUIRES_HUMAN_REVIEW",result["overall_status"])
            self.assertEqual(1,result["layer5_visual_evidence_available_count"])
            self.assertEqual(1,result["layer5_visual_evidence_consumed_count"])
            self.assertIn("A leaf diagram",prompts[0])
            self.assertIn("layer5_visual_evidence_asset_ids",prompts[0])
            outdir=ledger.parent/"layers-6-8"/("qwen2.5:1.5b-sha"+pdfhash[:8]+"-v4")
            mapping=json.loads((outdir/"curriculum-map.v1.json").read_text(encoding="utf-8"))
            self.assertEqual(["asset-preview-1"],mapping["topics"][0]["layer5_visual_evidence_asset_ids"])
            self.assertEqual(sha(ledger.read_text(encoding="utf-8").rstrip("\n")) if False else __import__("hashlib").sha256(ledger.read_bytes()).hexdigest(),mapping["source"]["layer5_coverage_ledger_sha256"])
            lesson=json.loads(next((outdir/"lessons").glob("qe-lesson-*.json")).read_text(encoding="utf-8"))
            self.assertEqual(["asset-preview-1"],lesson["generation_metadata"]["layer5_visual_evidence_asset_ids"])
            self.assertEqual(__import__("hashlib").sha256(ledger.read_bytes()).hexdigest(),lesson["generation_metadata"]["layer5_coverage_ledger_sha256"])
            again=run_layers(args,opener=open_)
            self.assertEqual(1,calls["count"],"completed checkpoints must not call Ollama again")
            self.assertEqual(1,again["segments_completed"])
            args.max_context_chars=6000
            with self.assertRaisesRegex(ValueError,"fingerprint mismatch"):
                run_layers(args,opener=open_)
            args.max_context_chars=7000
            row["text"]="Changed extraction text while retaining the PDF SHA."
            write_jsonl(source_path,[row])
            with self.assertRaisesRegex(ValueError,"text hash/character count"):
                run_layers(args,opener=open_)


    def test_existing_retrieval_corpus_is_checked_page_by_page(self):
        with tempfile.TemporaryDirectory() as temp:
            source={"pages":[{"page_number":1,"text":"grounded source","text_sha256":sha("grounded source"),"has_text":True}],
                "page_count":1,"book_id":"book","source_pdf_sha256":"c"*64,"class_no":6,"subject":"science",
                "language":"en","title":"Science","source_url":"https://ncert.nic.in/x","edition":"Reprint"}
            path=Path(temp)/"rag.jsonl"
            write_rag(path,source)
            rows=[json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
            rows[0]["text"]="tampered text"
            write_jsonl(path,rows)
            with self.assertRaisesRegex(ValueError,"per-page integrity"):
                write_rag(path,source)

    def test_unresolved_chapter_segment_prevents_false_completion(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); text="supported evidence"
            source={"pages":[{"page_number":1,"text":text,"text_sha256":sha(text),"has_text":True}],
                "page_count":1,"book_id":"book","source_pdf_sha256":"d"*64,"class_no":6,"subject":"science",
                "language":"en","title":"Science","source_url":"https://ncert.nic.in/x","edition":"Reprint"}
            seg1={"segment_id":"mapped","sequence":1,"source_pages":[1],"segment_sha256":"1"}
            seg2={"segment_id":"unresolved","sequence":2,"source_pages":[1],"segment_sha256":"2"}
            good=model_json([1])
            unknown=model_json([1]); unknown["chapter_number"]=None; unknown["mapping_confidence"]="LOW"; unknown["chapter_title_by_locale"]={"en":"","hi":""}
            source_for_lesson={**source,"source_pdf_sha256":"d"*64}
            lesson=lesson_from(source_for_lesson,seg1,good); lesson["workflow"]["generation_model"]="qwen2.5:1.5b"
            checkpoint={
                "mapped":{"status":"COMPLETE","segment_sha256":"1","generated":good,"lesson":lesson,"sft_rows":[{"example_id":"1","metadata":{"source_group_id":"book|chapter-1","review_status":"machine_validated_draft","publication_allowed":False}},{"example_id":"2","metadata":{"source_group_id":"book|chapter-1","review_status":"machine_validated_draft","publication_allowed":False}}]},
                "unresolved":{"status":"COMPLETE","segment_sha256":"2","generated":unknown,"lesson":None,"sft_rows":[{"example_id":"3","metadata":{"source_group_id":"book|pages-000","review_status":"machine_validated_draft","publication_allowed":False}},{"example_id":"4","metadata":{"source_group_id":"book|pages-000","review_status":"machine_validated_draft","publication_allowed":False}}]}}
            outdir=root/"out"; rag=root/"rag.jsonl"; write_rag(rag,source)
            report=write_outputs(source,[seg1,seg2],checkpoint,outdir,rag,"qwen2.5:1.5b","e"*64)
            self.assertEqual("INCOMPLETE",report["layers"]["6"])
            self.assertEqual("INCOMPLETE",report["layers"]["7"])
            self.assertEqual("INCOMPLETE",report["overall_status"])
            self.assertEqual(["unresolved"],report["unresolved_chapter_segments"])



    def test_benchmark_does_not_count_bad_schema_as_valid_lesson_and_keeps_sample_output(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); book="ncert-c6-english-fecu1"; pdfhash="9"*64
            pages=root/"data"/"textbook-extractions"/"class-6"/"english"/"pages"; pages.mkdir(parents=True)
            row={"book_id":book,"class":"6","language":"english","subject":"Science","asset_pdf_sha256":pdfhash,
                 "original_book_sha256":pdfhash,"source_book_page_number":1,"asset_page_index":0,
                 "text":"A source concept with enough text to create a bounded benchmark segment."}
            write_jsonl(pages/"pages.jsonl",[row])
            write_json(pages/"book.manifest.json",{"book_id":book,"class":"6","language":"english","subject":"Science",
                "scope":"SUBJECT_BOOK","published":False,"asset_pdf_sha256":pdfhash,"original_book_sha256":pdfhash,
                "page_count":1,"completed_pages":1,"source_page_start":1,"text_jsonl":"pages.jsonl",
                "source_url":"https://ncert.nic.in/textbook.php?fecu1=0-12","title":"Curiosity","edition":"Reprint 2026-27"})
            invalid={"chapter_number":1,"tutor_examples":[
                {"language":"en","student_message":"Why?","assistant_message":"Because.","source_pages":[1]},
                {"language":"hi","student_message":"क्यों?","assistant_message":"क्योंकि।","source_pages":[1]}]}
            args=SimpleNamespace(repo_root=root,source_book_id=book,endpoint="http://127.0.0.1:11434",
                models="qwen2.5:1.5b",sample_segments=1,request_timeout=30,
                output=root/"benchmark.json",commit_checkpoints=False)
            with patch("scripts.quantaedge_layers_6_8.ollama_chat",return_value=(invalid,1.25)):
                report=run_benchmark(args)
            sample=report["models"][0]["samples"][0]
            self.assertFalse(sample["schema_valid"])
            self.assertFalse(sample["lesson_contract_valid"])
            self.assertTrue(sample["source_page_citations_valid"])
            self.assertEqual(invalid,sample["sample_output"])
            self.assertIsNone(sample["lesson_candidate_preview"])
            self.assertEqual(64,sample["sample_output_sha256"] and len(sample["sample_output_sha256"]))



    def test_empty_text_page_stays_unresolved_when_sharing_a_mapped_segment(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); body="supported source concept"
            pages=[{"page_number":1,"text":body,"text_sha256":sha(body),"has_text":True},
                   {"page_number":2,"text":"","text_sha256":sha(""),"has_text":False}]
            source={"pages":pages,"page_count":2,"book_id":"book","source_pdf_sha256":"e"*64,
                "class_no":6,"subject":"science","language":"en","title":"Science",
                "source_url":"https://ncert.nic.in/x","edition":"Reprint"}
            segment={"segment_id":"mixed-text-and-image-page","sequence":1,"source_pages":[1,2],"segment_sha256":"ab"}
            generated=model_json([1,2])
            lesson=lesson_from(source,segment,generated)
            lesson["workflow"]["generation_model"]="qwen2.5:1.5b"
            sft=[{"example_id":str(i),"messages":[],"metadata":{
                "source_group_id":"book|chapter-1","review_status":"machine_validated_draft","publication_allowed":False}}
                for i in (1,2)]
            checkpoint={segment["segment_id"]:{"status":"COMPLETE","segment_sha256":"ab",
                "generated":generated,"lesson":lesson,"sft_rows":sft}}
            rag=root/"rag.jsonl"; write_rag(rag,source)
            report=write_outputs(source,[segment],checkpoint,root/"out",rag,"qwen2.5:1.5b","f"*64)
            self.assertEqual([2],report["unresolved_source_pages"])
            self.assertEqual([2],report["unresolved_text_pages"])
            self.assertEqual("INCOMPLETE",report["layers"]["6"])
            self.assertEqual("INCOMPLETE",report["layers"]["7"])
            self.assertEqual("INCOMPLETE",report["overall_status"])



    def test_recap_block_uses_the_existing_student_renderer_contract(self):
        generated=model_json([1,2])
        source={"book_id":"book","source_pdf_sha256":"a"*64,"source_url":"https://ncert.nic.in/x",
            "title":"Science","edition":"Reprint","class_no":6,"subject":"science","language":"en"}
        segment={"segment_id":"seg-recap","sequence":1,"source_pages":[1,2]}
        lesson=lesson_from(source,segment,generated)
        recap=next(block for block in lesson["explain"]["blocks"] if block["type"]=="recap")
        self.assertNotIn("text_by_locale",recap)
        self.assertEqual(generated["recap_by_locale"]["en"],recap["content_by_locale"]["en"]["key_takeaways"][0])
        self.assertEqual(generated["recap_by_locale"]["hi"],recap["content_by_locale"]["hi"]["key_takeaways"][0])
        self.assertEqual([],validate_lesson(lesson))



    def test_refresh_benchmark_revalidates_raw_sample_without_new_model_calls(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); book="ncert-c6-english-fecu1"; pdfhash="a"*64
            pages=root/"data"/"textbook-extractions"/"class-6"/"english"/"pages"; pages.mkdir(parents=True)
            body="Curiosity develops through observation and careful questions. " * 10
            row={"book_id":book,"class":"6","language":"english","subject":"Science",
                 "asset_pdf_sha256":pdfhash,"original_book_sha256":pdfhash,"source_book_page_number":1,
                 "asset_page_index":0,"text":body}
            write_jsonl(pages/"pages.jsonl",[row])
            write_json(pages/"book.manifest.json",{"book_id":book,"class":"6","language":"english","subject":"Science",
                 "scope":"SUBJECT_BOOK","published":False,"asset_pdf_sha256":pdfhash,"original_book_sha256":pdfhash,
                 "page_count":1,"completed_pages":1,"source_page_start":1,"text_jsonl":"pages.jsonl",
                 "source_url":"https://ncert.nic.in/textbook.php?fecu1=0-12","title":"Curiosity","edition":"Reprint 2026-27"})
            source=__import__("scripts.quantaedge_layers_6_8",fromlist=["load_source"]).load_source(root,6,book)
            ledger=install_layer5_fixture(root,pages,book,pdfhash,body)
            segment=segments_for(source["pages"],2,5000)[0]
            _,visual_fingerprint=load_visual_candidates(root,source,[segment])
            raw=model_json([1])
            report={"schema_version":"quantaedge.local-ollama-benchmark.v1","prompt_version":PROMPT_VERSION,
                 "source":{"book_id":book,"source_pdf_sha256":pdfhash,
                    "layer5_coverage_ledger_sha256":__import__("hashlib").sha256(ledger.read_bytes()).hexdigest(),
                    "sample_segment_ids":[segment["segment_id"]],"sample_source_pages":[1]},
                 "models":[{"model":"qwen2.5:1.5b","samples_attempted":1,"successful_samples":1,
                    "samples":[{"segment_id":segment["segment_id"],"source_pages":[1],"sample_output":raw,
                      "sample_output_sha256":sha(json.dumps(raw,ensure_ascii=False,sort_keys=True)),
                      "schema_valid":True,"lesson_contract_valid":True,"sft_examples_valid":True,
                      "source_page_citations_valid":True,"seconds":1.0}]}],
                 "visual_candidate_fingerprint":visual_fingerprint,
                 "production_model_selection":"PENDING_HUMAN_REVIEW","production_model":None}
            path=root/"benchmark.json"; write_json(path,report)
            args=SimpleNamespace(repo_root=root,source_book_id=book,output=path,commit_checkpoints=False)
            refreshed=refresh_benchmark(args)
            self.assertEqual("REFRESHED",refreshed["status"])
            updated=json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual(LESSON_CONTRACT_VERSION,updated["lesson_contract_version"])
            self.assertEqual(0,updated["benchmark_revalidation"]["new_model_calls"])
            recap=next(b for b in updated["models"][0]["samples"][0]["lesson_candidate_preview"]["explain"]["blocks"] if b["type"]=="recap")
            self.assertIn("key_takeaways",recap["content_by_locale"]["en"])
            self.assertEqual(1,updated["models"][0]["successful_samples"])

    def test_visual_candidates_are_hash_bound_and_preview_only(self):
        with tempfile.TemporaryDirectory() as temp:
            repo=Path(temp); book="book"; pdfhash="f"*64; slug="class6-en-science-test"
            packdir=repo/"data"/"textbook-visual-candidates-v3"/slug; packdir.mkdir(parents=True)
            write_json(packdir/"source-selection.v1.json",{"book_id":book,"sha256":pdfhash,"book_slug":slug})
            import hashlib
            raw=b"synthetic-candidate-image"
            display_sha=hashlib.sha256(raw).hexdigest()
            static=repo/"apps"/"web"/"public"/"content"/"visual-assets"/slug/"candidates"; static.mkdir(parents=True)
            (static/f"v3-image-testcandidate-{display_sha[:12]}.jpg").write_bytes(raw)
            write_json(packdir/"candidates.v3.json",{"schema_version":"quantaedge.visual-candidate-pack.v3",
              "source":{"book_id":book,"pdf_sha256":pdfhash,"page_count":1},"summary":{"selected_embedded_images":1},
              "publication_allowed":False,"candidates":[{"candidate_id":"v3-image-testcandidate","candidate_kind":"embedded_image",
                "source_book_id":book,"source_pdf_sha256":pdfhash,"source_pdf_page_number":1,
                "source_asset_sha256":"a"*64,"heuristic_score":10,"review_status":"CANDIDATE_NEEDS_HUMAN_REVIEW",
                "publication_allowed":False,"human_review_required":True,"page_text_context":"source page"}]})
            source={"book_id":book,"source_pdf_sha256":pdfhash,"source_url":"https://ncert.nic.in/x",
              "title":"Science","edition":"Reprint","class_no":6,"subject":"science","language":"en"}
            segment={"segment_id":"seg-1","sequence":1,"source_pages":[1]}
            attached,fingerprint=load_visual_candidates(repo,source,[segment])
            self.assertEqual(1,len(attached["seg-1"])); self.assertEqual(64,len(fingerprint))
            lesson=lesson_from(source,segment,model_json([1]),attached["seg-1"])
            self.assertEqual([],validate_lesson(lesson))
            visual=next(b for b in lesson["explain"]["blocks"] if b["type"]=="image")
            self.assertFalse(visual["asset"]["publication_allowed"])
            self.assertFalse(visual["asset"]["student_renderable"])
            self.assertEqual(pdfhash,visual["asset"]["source_pdf_sha256"])
            self.assertTrue(visual["asset"]["url"].startswith("https://raw.githubusercontent.com/"))

    def test_visual_candidates_from_another_pdf_edition_are_not_attached(self):
        with tempfile.TemporaryDirectory() as temp:
            repo=Path(temp); slug="wrong-edition"; folder=repo/"data"/"textbook-visual-candidates-v3"/slug; folder.mkdir(parents=True)
            write_json(folder/"source-selection.v1.json",{"book_id":"book","sha256":"a"*64,"book_slug":slug})
            attached,fingerprint=load_visual_candidates(repo,{"book_id":"book","source_pdf_sha256":"b"*64},
                [{"segment_id":"s","source_pages":[1]}])
            self.assertEqual({},attached); self.assertEqual(64,len(fingerprint))

if __name__=="__main__": unittest.main()
