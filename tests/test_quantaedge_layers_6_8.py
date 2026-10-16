import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts.ai_training_dataset import request_examples
from types import SimpleNamespace
from scripts.quantaedge_layers_6_8 import (
    PROMPT_VERSION, benchmark as run_benchmark, lesson_from, load_visual_candidates, ollama_chat, sha, split_sft,
    validate_lesson, validate_output, validate_splits, segments_for, tutor_citations_valid, write_rag,
    write_json, write_jsonl, write_outputs, run as run_layers,
)

class FakeResponse:
    def __init__(self, data): self.data=json.dumps(data).encode()
    def __enter__(self): return self
    def __exit__(self,*args): return False
    def read(self): return self.data

def model_json(pages):
    bi={"en":"Understand the idea.","hi":"विचार को समझें।"}
    q={"prompt_by_locale":{"en":"Which statement is correct?","hi":"कौन-सा कथन सही है?"},
       "options":[{"id":"a","text_by_locale":{"en":"Correct","hi":"सही"}},{"id":"b","text_by_locale":{"en":"Wrong","hi":"गलत"}}],
       "correct_answer_ids":["a"],"feedback_by_locale":bi}
    return {"chapter_number":1,"chapter_title_by_locale":{"en":"Curiosity","hi":"जिज्ञासा"},"mapping_confidence":"HIGH",
      "topic_title_by_locale":{"en":"Observation","hi":"अवलोकन"},"microtopic_title_by_locale":{"en":"Careful observation","hi":"ध्यानपूर्वक अवलोकन"},
      "concepts_by_locale":{"en":["observation"],"hi":["अवलोकन"]},"learning_objectives":[{"statement_by_locale":bi}],
      "explanation_by_locale":{"en":{"heading":"Explain","paragraphs":["Explain in original words."]},"hi":{"heading":"समझें","paragraphs":["अपने शब्दों में समझें।"]}},
      "worked_example_by_locale":{"en":{"heading":"Example","paragraphs":["Observe a leaf and note two features."]},"hi":{"heading":"उदाहरण","paragraphs":["पत्ती देखें और दो विशेषताएँ लिखें।"]}},
      "recap_by_locale":bi,"questions":[q,dict(q)],
      "tutor_examples":[{"language":"en","student_message":"Why observe?","assistant_message":"It separates evidence from guesses.","source_pages":pages[:1]},
                        {"language":"hi","student_message":"ध्यान से क्यों देखें?","assistant_message":"इससे प्रमाण और अनुमान अलग होते हैं।","source_pages":pages[:1]}]}

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
        self.assertEqual("json",got["body"]["format"]); self.assertEqual(2048,got["body"]["options"]["num_predict"]); self.assertEqual(6144,got["body"]["options"]["num_ctx"]); self.assertEqual("Careful observation",v["microtopic_title_by_locale"]["en"])
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
        self.assertEqual(2,len(lesson["practice"]["questions"]))

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
            ledger=root/"data"/"textbook-content-pipeline"/"class-6"/"books"/"curiosity"/"coverage-ledger.v1.json"
            write_json(ledger,{"ledger_status":"COMPLETE","publication_allowed":False,
                "source":{"source_book_id":book,"source_pdf_sha256":pdfhash}})
            benchmark=root/"data"/"textbook-content-pipeline"/"model-benchmarks"/"class-6-ncert-curiosity.v1.json"
            write_json(benchmark,{"production_model_selection":"PENDING_HUMAN_REVIEW","production_model":None,
                "prompt_version":PROMPT_VERSION,"models":[{"model":"qwen2.5:1.5b","successful_samples":1,"lesson_contract_valid_rate":1.0}]})
            args=SimpleNamespace(repo_root=root,class_no=6,source_book_id=book,model="qwen2.5:1.5b",benchmark_report=None,
                max_pages_per_segment=4,max_context_chars=7000,endpoint="http://127.0.0.1:11434",
                max_model_calls=3,checkpoint_every=1,request_timeout=30,commit_checkpoints=False)
            calls={"count":0}
            def open_(request,timeout=180):
                calls["count"]+=1
                self.assertEqual("json",json.loads(request.data.decode())["format"])
                return FakeResponse({"message":{"content":json.dumps(model_json([1]))}})
            result=run_layers(args,opener=open_)
            self.assertEqual(1,calls["count"])
            self.assertEqual(1,result["segments_completed"])
            self.assertEqual("DRAFT_REQUIRES_HUMAN_REVIEW",result["overall_status"])
            again=run_layers(args,opener=open_)
            self.assertEqual(1,calls["count"],"completed checkpoints must not call Ollama again")
            self.assertEqual(1,again["segments_completed"])
            args.max_context_chars=6000
            with self.assertRaisesRegex(ValueError,"fingerprint mismatch"):
                run_layers(args,opener=open_)
            args.max_context_chars=7000
            row["text"]="Changed extraction text while retaining the PDF SHA."
            write_jsonl(source_path,[row])
            with self.assertRaisesRegex(ValueError,"fingerprint mismatch"):
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
