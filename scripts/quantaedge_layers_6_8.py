#!/usr/bin/env python3
"""Bounded and resumable Layers 6–8 for QuantaEdge, using native local Ollama."""
from __future__ import annotations
import argparse, hashlib, json, re, subprocess, sys, time, urllib.error, urllib.parse, urllib.request
from pathlib import Path
from typing import Any

PROMPT_VERSION = "qe-l6-l8-ollama-v2-visual"
HOSTS = {"ncert.nic.in", "ncert.ncert.org.in"}
LANG = {"english":"en","hindi":"hi","en":"en","hi":"hi"}
SUBJECT = {"maths":"maths","mathematics":"maths","science":"science"}
SYSTEM = "You are a careful NCERT educational author for Classes 6–8. Use only the supplied source excerpt for facts. Write original explanations; do not copy textbook wording/exercises. Check maths and science claims. Return valid JSON only."
TUTOR = {
 "en":"You are a patient NCERT tutor for Classes 6–8. Explain step by step, use original wording and do not invent facts outside the cited pages.",
 "hi":"तुम कक्षा 6–8 के लिए धैर्यवान NCERT शिक्षक हो। सरल, स्वाभाविक हिंदी में चरणबद्ध समझाओ। मूल शब्दों में लिखो और दिए गए स्रोत-पृष्ठों से बाहर के तथ्य मत गढ़ो।"
}

def sha(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()

def sha_file(path: Path) -> str:
    h=hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda:f.read(1024*1024),b""): h.update(block)
    return h.hexdigest()

def read_json(path: Path) -> Any:
    try: return json.loads(path.read_text(encoding="utf-8"))
    except (OSError,json.JSONDecodeError) as exc: raise ValueError(f"Cannot read {path}: {exc}") from exc

def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True,exist_ok=True)
    tmp=path.with_suffix(path.suffix+".tmp")
    tmp.write_text(json.dumps(value,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    tmp.replace(path)

def read_jsonl(path: Path) -> list[dict[str,Any]]:
    rows=[]
    with path.open(encoding="utf-8") as f:
        for n,line in enumerate(f,1):
            if not line.strip(): continue
            try: v=json.loads(line)
            except json.JSONDecodeError as exc: raise ValueError(f"{path}:{n}: invalid JSONL") from exc
            if not isinstance(v,dict): raise ValueError(f"{path}:{n}: JSONL record must be an object")
            rows.append(v)
    return rows

def write_jsonl(path: Path, rows: list[dict[str,Any]]) -> None:
    path.parent.mkdir(parents=True,exist_ok=True)
    tmp=path.with_suffix(path.suffix+".tmp")
    with tmp.open("w",encoding="utf-8",newline="\n") as f:
        for row in rows: f.write(json.dumps(row,ensure_ascii=False,sort_keys=True)+"\n")
        f.flush()
    tmp.replace(path)

def localized(v: Any) -> bool:
    return isinstance(v,dict) and all(isinstance(v.get(k),str) and v[k].strip() for k in ("en","hi"))

def checked_source_url(value: Any) -> str:
    url=str(value or "").strip(); p=urllib.parse.urlparse(url); host=(p.hostname or "").lower().rstrip(".")
    if p.scheme!="https" or not host or p.username or p.password or p.port not in (None,443) or not any(host==h or host.endswith("."+h) for h in HOSTS):
        raise ValueError("Source URL must be HTTPS on an approved official NCERT host.")
    return url

def page_no(row: dict[str,Any]) -> int|None:
    for key in ("source_book_page_number","source_pdf_page_number","page_number"):
        n=row.get(key)
        if isinstance(n,int) and not isinstance(n,bool) and n>0: return n
    n=row.get("asset_page_index")
    return n+1 if isinstance(n,int) and not isinstance(n,bool) and n>=0 else None

def load_source(repo: Path, grade: int, book_id: str) -> dict[str,Any]:
    base=repo/"data"/"textbook-extractions"/f"class-{grade}"
    candidates=[]
    if base.is_dir():
        for path in base.glob("*/pages/*.manifest.json"):
            try: m=read_json(path)
            except ValueError: continue
            if m.get("book_id")==book_id: candidates.append((path,m))
    if len(candidates)!=1: raise ValueError(f"Expected one extraction manifest for {book_id}, found {len(candidates)}.")
    mp,m=candidates[0]
    if str(m.get("class"))!=str(grade) or str(m.get("scope","")).upper()!="SUBJECT_BOOK" or m.get("published") is not False:
        raise ValueError("Source must be an unpublished complete whole-book extraction for the requested class.")
    source_sha=str(m.get("original_book_sha256") or m.get("asset_pdf_sha256") or "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}",source_sha) or source_sha!=str(m.get("asset_pdf_sha256","")).lower():
        raise ValueError("Whole-book source PDF hashes are missing or mismatched.")
    count=m.get("page_count")
    if not isinstance(count,int) or isinstance(count,bool) or not 1<=count<=2000 or m.get("completed_pages")!=count or m.get("source_page_start")!=1:
        raise ValueError("Whole-book extraction is incomplete or its page count/start is invalid.")
    lang=LANG.get(str(m.get("language","")).lower()); subject=SUBJECT.get(str(m.get("subject","")).lower())
    if lang is None or subject is None: raise ValueError("Only English/Hindi NCERT Maths and Science are supported.")
    url=checked_source_url(m.get("source_url"))
    filename=m.get("text_jsonl")
    if not isinstance(filename,str) or Path(filename).name!=filename: raise ValueError("Unsafe manifest text_jsonl filename.")
    tp=mp.parent/filename
    if not tp.is_file(): raise ValueError(f"Missing page JSONL {tp}")
    by_page={}
    for i,row in enumerate(read_jsonl(tp),1):
        n=page_no(row); row_sha=str(row.get("original_book_sha256") or row.get("asset_pdf_sha256") or "").lower()
        if n is None or n>count or row_sha!=source_sha or row.get("book_id")!=book_id or str(row.get("class"))!=str(grade) or LANG.get(str(row.get("language","")).lower())!=lang or not isinstance(row.get("text"),str):
            raise ValueError(f"Source identity/page schema mismatch in page record {i}.")
        if n in by_page: raise ValueError(f"Duplicate extracted original PDF page {n}.")
        by_page[n]=row
    missing=sorted(set(range(1,count+1))-set(by_page))
    if missing: raise ValueError(f"Page JSONL is incomplete; missing pages {missing[:12]}.")
    pages=[]
    for n in range(1,count+1):
        text=by_page[n]["text"]
        pages.append({"page_number":n,"text":text,"text_sha256":sha(text),"has_text":bool(text.strip()),"review_status":by_page[n].get("review_status","UNKNOWN")})
    return {"manifest_path":mp,"text_path":tp,"manifest":m,"book_id":book_id,"class_no":grade,"language":lang,"subject":subject,
            "title":str(m.get("source_title") or m.get("title") or book_id),"edition":str(m.get("edition") or ""),
            "source_url":url,"source_pdf_sha256":source_sha,"page_count":count,"pages":pages}

def layer5_ledger(repo: Path, source: dict[str,Any]) -> Path:
    root=repo/"data"/"textbook-content-pipeline"/f"class-{source['class_no']}"/"books"
    matches=[]
    if root.is_dir():
        for p in root.glob("**/coverage-ledger.v1.json"):
            try: v=read_json(p)
            except ValueError: continue
            s=v.get("source") or {}
            if s.get("source_book_id")==source["book_id"] and str(s.get("source_pdf_sha256","")).lower()==source["source_pdf_sha256"]: matches.append(p)
    if len(matches)!=1: raise ValueError(f"Need one matching Layer 5 ledger for {source['book_id']}; found {len(matches)}. Run Layers 1–5 first.")
    v=read_json(matches[0])
    if v.get("ledger_status")!="COMPLETE" or v.get("publication_allowed") is not False:
        raise ValueError(f"Layer 5 status is {v.get('ledger_status')!r}; only a complete review-only ledger is eligible.")
    return matches[0]

def segments_for(pages: list[dict[str,Any]], max_pages: int=4, max_chars: int=7000) -> list[dict[str,Any]]:
    if not 1<=max_pages<=8 or not 2000<=max_chars<=12000: raise ValueError("Segment limits exceed safe bounds.")
    fragments=[]
    limit=max(1000,max_chars-800)
    for page in pages:
        raw=page["text"]
        chunks=[raw[i:i+limit] for i in range(0,len(raw),limit)] if raw else [""]
        for j,part in enumerate(chunks,1): fragments.append({"page":page["page_number"],"part":j,"text":part,"hash":sha(part)})
    groups=[]; current=[]; used_pages=set(); chars=0
    for fragment in fragments:
        p=fragment["page"]
        if current and (chars+len(fragment["text"])>max_chars or (p not in used_pages and len(used_pages)>=max_pages)):
            groups.append(current); current=[]; used_pages=set(); chars=0
        current.append(fragment); used_pages.add(p); chars+=len(fragment["text"])
    if current: groups.append(current)
    result=[]
    for i,g in enumerate(groups,1):
        pages_used=sorted({x["page"] for x in g})
        identity="|".join(f"{x['page']}:{x['part']}:{x['hash']}" for x in g)
        result.append({"segment_id":f"seg-{i:05d}-{sha(identity)[:10]}","sequence":i,"pieces":g,"source_pages":pages_used,"segment_sha256":sha(identity)})
    if sum(len(x["text"]) for g in result for x in g["pieces"])!=sum(len(p["text"]) for p in pages): raise ValueError("Segment builder dropped source characters.")
    return result


def load_visual_candidates(repo: Path, source: dict[str,Any], segments: list[dict[str,Any]]) -> tuple[dict[str,list[dict[str,Any]]],str]:
    """Find hash-matched V3 visual candidates for admin preview only; never grants publication."""
    catalog=repo/"data"/"textbook-visual-candidates-v3"
    packs=[]
    if catalog.is_dir():
        for selection_path in sorted(catalog.glob("*/source-selection.v1.json")):
            try: selection=read_json(selection_path)
            except ValueError: continue
            if (selection.get("book_id")!=source["book_id"] or
                str(selection.get("sha256") or selection.get("source_pdf_sha256") or "").lower()!=source["source_pdf_sha256"]):
                continue
            slug=str(selection.get("book_slug") or selection_path.parent.name)
            pack_path=selection_path.parent/"candidates.v3.json"
            static_dir=repo/"apps"/"web"/"public"/"content"/"visual-assets"/slug/"candidates"
            if not pack_path.is_file() or not static_dir.is_dir(): continue
            try: pack=read_json(pack_path)
            except ValueError: continue
            info=pack.get("source") or {}
            if (pack.get("schema_version")!="quantaedge.visual-candidate-pack.v3" or
                info.get("book_id")!=source["book_id"] or
                str(info.get("pdf_sha256") or "").lower()!=source["source_pdf_sha256"] or
                pack.get("publication_allowed") is True):
                continue
            count=int((pack.get("summary") or {}).get("selected_embedded_images") or 0)
            packs.append((count,slug,pack_path,static_dir,pack))
    if not packs:
        return {},sha("no-matching-visual-pack|"+source["book_id"]+"|"+source["source_pdf_sha256"])
    _,slug,pack_path,static_dir,pack=sorted(packs,key=lambda item:(-item[0],item[1]))[0]
    wanted_pages={p for seg in segments for p in seg["source_pages"]}
    by_page={}
    for row in pack.get("candidates",[]):
        if not isinstance(row,dict) or row.get("candidate_kind")!="embedded_image": continue
        if (row.get("source_book_id")!=source["book_id"] or
            str(row.get("source_pdf_sha256") or "").lower()!=source["source_pdf_sha256"] or
            row.get("publication_allowed") is not False or row.get("human_review_required") is not True or
            str(row.get("review_status") or "").upper() not in {"CANDIDATE_NEEDS_HUMAN_REVIEW","NEEDS_REVIEW"}):
            continue
        page=row.get("source_pdf_page_number"); cid=str(row.get("candidate_id") or "")
        source_asset_sha=str(row.get("source_asset_sha256") or "").lower()
        if (not isinstance(page,int) or isinstance(page,bool) or page not in wanted_pages or
            not re.fullmatch(r"v3-image-[a-z0-9-]{8,100}",cid) or
            not re.fullmatch(r"[0-9a-f]{64}",source_asset_sha)):
            continue
        valid=[]
        for path in static_dir.glob(cid+"-*.jpg"):
            if path.is_file():
                digest=sha_file(path)
                if path.stem.rsplit("-",1)[-1]==digest[:12]: valid.append((path,digest))
        if len(valid)!=1: continue
        path,display_sha=valid[0]
        by_page.setdefault(page,[]).append({
            "candidate_id":cid,"candidate_kind":"embedded_image","source_book_id":source["book_id"],
            "source_pdf_sha256":source["source_pdf_sha256"],"source_pdf_page_number":page,
            "source_asset_sha256":source_asset_sha,"display_sha256":display_sha,
            "url":"https://raw.githubusercontent.com/Manav326/QuantaEdge/main/"+path.relative_to(repo).as_posix(),
            "heuristic_score":float(row.get("heuristic_score") or 0),
        })
    by_segment={}; selected_for_fingerprint=[]
    for seg in segments:
        attached=[]
        for page in seg["source_pages"]:
            rows=by_page.get(page,[])
            if rows: attached.append(sorted(rows,key=lambda x:(-x["heuristic_score"],x["candidate_id"]))[0])
            if len(attached)>=2: break
        by_segment[seg["segment_id"]]=attached
        selected_for_fingerprint.extend({k:v for k,v in row.items() if k in {"candidate_id","source_pdf_page_number","source_asset_sha256","display_sha256"}} for row in attached)
    fingerprint=sha(json.dumps({"slug":slug,"pack_sha256":sha_file(pack_path),"assets":selected_for_fingerprint},sort_keys=True))
    return by_segment,fingerprint

def prompt_for(source: dict[str,Any], segment: dict[str,Any]) -> str:
    context="\n\n".join(f"Original PDF page {p['page']} (fragment {p['part']}):\n{p['text'] if p['text'].strip() else '[No extractable text]'}" for p in segment["pieces"])
    allowed=json.dumps(segment["source_pages"])
    return f"""Produce one review-only bilingual learning unit grounded exclusively in the supplied NCERT source excerpt.
If the chapter number is not confidently supported, chapter_number must be null and mapping_confidence must be LOW; never guess. Create original teaching explanations, a worked example when supported, a recap, two new multiple-choice questions with correct answers, and one English plus one Hindi tutor conversation. Do not reproduce textbook prose or exercise questions verbatim. Every localized field must contain natural, meaningful English and Hindi text. Every tutor example must cite one or more of the exact allowed PDF page numbers: {allowed}.
Return JSON only with these keys and shapes:
chapter_number (integer 1..80 or null), chapter_title_by_locale {{en,hi}}, mapping_confidence, topic_title_by_locale {{en,hi}}, microtopic_title_by_locale {{en,hi}}, concepts_by_locale {{en:[strings],hi:[strings]}}, learning_objectives [{{statement_by_locale:{{en,hi}}}}],
explanation_by_locale {{en:{{heading,paragraphs:[strings]}},hi:{{heading,paragraphs:[strings]}}}},
worked_example_by_locale (same shape), recap_by_locale {{en,hi}},
questions [exactly 2 objects with prompt_by_locale {{en,hi}}, options [at least 2 objects {{id,text_by_locale:{{en,hi}}}}], correct_answer_ids [one option ID], feedback_by_locale {{en,hi}}],
tutor_examples [objects {{language:"en" or "hi",student_message,assistant_message,source_pages:[allowed page numbers]}}; include both languages].
Class {source['class_no']}; subject {source['subject']}; source language {source['language']}; book ID {source['book_id']}; book {source['title']}; edition {source['edition']}.
Source excerpt:
{context}"""

def ollama_chat(endpoint: str, model: str, prompt: str, timeout: int=180, opener: Any=None) -> tuple[dict[str,Any],float]:
    u=urllib.parse.urlparse(endpoint)
    if u.scheme!="http" or u.hostname not in {"127.0.0.1","localhost","::1"} or u.username or u.password:
        raise ValueError("Generation must use native unauthenticated loopback Ollama HTTP; external providers are disabled.")
    if not model.strip(): raise ValueError("Model is required.")
    body={"model":model,"stream":False,"format":"json","options":{"temperature":0.15},
          "messages":[{"role":"system","content":SYSTEM},{"role":"user","content":prompt}]}
    req=urllib.request.Request(endpoint.rstrip("/")+"/api/chat",data=json.dumps(body,ensure_ascii=False).encode(),headers={"Content-Type":"application/json"},method="POST")
    send=opener or urllib.request.urlopen; last=None
    for attempt in range(2):
        started=time.monotonic()
        try:
            with send(req,timeout=timeout) as response: payload=json.loads(response.read().decode("utf-8"))
            content=(payload.get("message") or {}).get("content")
            if not isinstance(content,str): raise ValueError("Ollama response missing message.content.")
            value=json.loads(content)
            if not isinstance(value,dict): raise ValueError("Ollama response must be a JSON object.")
            return value,time.monotonic()-started
        except urllib.error.HTTPError as exc:
            last=RuntimeError(f"Ollama HTTP {exc.code}: {exc.read().decode(errors='replace')[:500]}")
            if exc.code not in {408,425,429,500,502,503,504}: break
        except (urllib.error.URLError,TimeoutError,ValueError,KeyError,json.JSONDecodeError) as exc: last=exc
        if attempt==0: time.sleep(1)
    raise RuntimeError(f"Local Ollama call failed: {last}") from last

def validate_output(v: Any, allowed: set[int]) -> list[str]:
    errors=[]
    if not isinstance(v,dict): return ["not an object"]
    chapter=v.get("chapter_number")
    if chapter is not None and (not isinstance(chapter,int) or isinstance(chapter,bool) or not 1<=chapter<=80): errors.append("invalid chapter_number")
    if chapter is not None and not localized(v.get("chapter_title_by_locale")): errors.append("chapter title needs en/hi")
    if chapter is None and str(v.get("mapping_confidence","")).upper()!="LOW": errors.append("unmapped chapter must have LOW confidence")
    for k in ("topic_title_by_locale","microtopic_title_by_locale","recap_by_locale"):
        if not localized(v.get(k)): errors.append(k+" needs en/hi")
    concepts=v.get("concepts_by_locale")
    if not isinstance(concepts,dict) or any(not isinstance(concepts.get(l),list) or not concepts[l] or any(not isinstance(x,str) or not x.strip() for x in concepts[l]) for l in ("en","hi")): errors.append("concepts need English and Hindi lists")
    objectives=v.get("learning_objectives")
    if not isinstance(objectives,list) or not objectives or any(not isinstance(o,dict) or not localized(o.get("statement_by_locale")) for o in objectives): errors.append("learning objectives are not bilingual")
    for k in ("explanation_by_locale","worked_example_by_locale"):
        block=v.get(k)
        if not isinstance(block,dict): errors.append(k+" missing"); continue
        for l in ("en","hi"):
            b=block.get(l)
            if not isinstance(b,dict) or not isinstance(b.get("heading"),str) or not b["heading"].strip() or not isinstance(b.get("paragraphs"),list) or not b["paragraphs"] or any(not isinstance(x,str) or not x.strip() for x in b["paragraphs"]): errors.append(k+"."+l+" invalid")
    qs=v.get("questions")
    if not isinstance(qs,list) or len(qs)!=2: errors.append("exactly two questions required")
    else:
        for i,q in enumerate(qs):
            if not isinstance(q,dict) or not localized(q.get("prompt_by_locale")) or not localized(q.get("feedback_by_locale")): errors.append(f"question {i} lacks bilingual prompt/feedback"); continue
            opts=q.get("options")
            if not isinstance(opts,list) or len(opts)<2: errors.append(f"question {i} needs options"); continue
            ids=[o.get("id") for o in opts if isinstance(o,dict) and isinstance(o.get("id"),str) and o.get("id").strip() and localized(o.get("text_by_locale"))]
            if len(ids)!=len(opts) or len(ids)!=len(set(ids)): errors.append(f"question {i} options invalid")
            correct=q.get("correct_answer_ids")
            if not isinstance(correct,list) or len(correct)!=1 or correct[0] not in ids: errors.append(f"question {i} answer key invalid")
    examples=v.get("tutor_examples"); found=set()
    if not isinstance(examples,list): errors.append("tutor_examples missing")
    else:
        for i,e in enumerate(examples):
            if not isinstance(e,dict) or e.get("language") not in {"en","hi"} or not isinstance(e.get("student_message"),str) or not e["student_message"].strip() or not isinstance(e.get("assistant_message"),str) or not e["assistant_message"].strip():
                errors.append(f"tutor example {i} invalid"); continue
            found.add(e["language"]); refs=e.get("source_pages")
            if not isinstance(refs,list) or not refs or any(not isinstance(p,int) or isinstance(p,bool) or p not in allowed for p in refs): errors.append(f"tutor example {i} page citations invalid")
        if found!={"en","hi"}: errors.append("tutor examples must cover English and Hindi")
    return errors

def lesson_from(source: dict[str,Any], seg: dict[str,Any], v: dict[str,Any], visuals: list[dict[str,Any]]|None=None) -> dict[str,Any]|None:
    if v.get("chapter_number") is None: return None
    stable=sha(source["book_id"]+"|"+source["source_pdf_sha256"]+"|"+seg["segment_id"])
    aid="qe-lesson-"+stable[:24]; topic_id="topic-"+stable[:20]
    objectives=[{"id":aid+f"-obj-{i}","statement_by_locale":o["statement_by_locale"]} for i,o in enumerate(v["learning_objectives"],1)]
    objective_ids=[o["id"] for o in objectives]
    refs=[]
    for p in seg["source_pages"]:
        rid=f"src-{source['source_pdf_sha256'][:10]}-p{p}"
        refs.append({"id":rid,"source_ref_id":rid,"source_type":"NCERT_WHOLE_BOOK_PDF","book_id":source["book_id"],
                     "source_title":source["title"],"edition":source["edition"],"url":source["source_url"],
                     "source_pdf_sha256":source["source_pdf_sha256"],"pdf_page":p,"source_pdf_page_number":p,"human_review_required":True})
    refids=[r["id"] for r in refs]
    ref_by_page={r["pdf_page"]:r["id"] for r in refs}
    blocks=[{"id":aid+"-explain","type":"rich_text","order":1,"objective_ids":objective_ids,"source_refs":refids,"text_by_locale":v["explanation_by_locale"]}]
    for i,visual in enumerate(visuals or [],1):
        page=visual["source_pdf_page_number"]
        alt={"en":f"Unreviewed textbook visual candidate from PDF page {page}",
             "hi":f"PDF पृष्ठ {page} से पाठ्यपुस्तक दृश्य उम्मीदवार — समीक्षा लंबित"}
        caption={"en":"Human review required before use with this lesson.",
                 "hi":"इस दृश्य को पाठ के साथ उपयोग करने से पहले मानवीय समीक्षा आवश्यक है।"}
        asset={"asset_id":visual["candidate_id"],"candidate_id":visual["candidate_id"],"url":visual["url"],
          "source_book_id":source["book_id"],"source_pdf_sha256":source["source_pdf_sha256"],
          "source_pdf_page_number":page,"source_asset_sha256":visual["source_asset_sha256"],
          "display_sha256":visual["display_sha256"],"review_status":"CANDIDATE_NEEDS_HUMAN_REVIEW",
          "rights_status":"SOURCE_REFERENCE_ONLY","publication_allowed":False,"student_renderable":False,
          "human_review_required":True,"alt_by_locale":alt,"caption_by_locale":caption}
        blocks.append({"id":aid+"-visual-"+str(i),"type":"image","order":len(blocks)+1,
          "objective_ids":objective_ids,"source_refs":[ref_by_page[page]],"visual_id":visual["candidate_id"],
          "alt_by_locale":alt,"caption_by_locale":caption,"asset":asset})
    blocks.extend([
      {"id":aid+"-worked","type":"rich_text","order":len(blocks)+1,"objective_ids":objective_ids,"source_refs":refids,"text_by_locale":v["worked_example_by_locale"]},
      {"id":aid+"-recap","type":"recap","order":len(blocks)+2,"objective_ids":objective_ids,"source_refs":refids,
       "text_by_locale":{"en":{"heading":"Recap","paragraphs":[v["recap_by_locale"]["en"]]},"hi":{"heading":"पुनरावृत्ति","paragraphs":[v["recap_by_locale"]["hi"]]}}},
    ])
    questions=[]
    for i,q in enumerate(v["questions"],1):
        questions.append({"id":aid+f"-q-{i}","type":"multiple_choice","rendering_contract_id":"qtype-multiple-choice.v1",
                          "prompt_by_locale":q["prompt_by_locale"],"feedback_by_locale":q["feedback_by_locale"],
                          "options":q["options"],"correct_answer_ids":q["correct_answer_ids"],
                          "objective_ids":objective_ids,"source_refs":refids,"difficulty":"review-required"})
    return {"schema_version":"quantaedge.structured-lesson.v1","artifact_type":"quantaedge.structured-lesson.v1","artifact_id":aid,
      "workflow":{"status":"DRAFT_REQUIRES_HUMAN_REVIEW","content_review_status":"NOT_REVIEWED","source_completeness_status":"SOURCE_LINKED_REQUIRES_REVIEW",
                  "visual_review_status":"NOT_REVIEWED","bilingual_parity_status":"MACHINE_CHECKED_REQUIRES_REVIEW","publication_allowed":False,
                  "human_review_required":True,"generation_model":"filled-by-run","source_pdf_sha256":source["source_pdf_sha256"],
                  "source_book_id":source["book_id"],"source_pages":seg["source_pages"]},
      "curriculum":{"class_code":str(source["class_no"]),"subject_code":source["subject"],"source_language":source["language"],"source_book_id":source["book_id"]},
      "chapter":{"chapter_number":v["chapter_number"],"title_by_locale":v["chapter_title_by_locale"],"source_page_start":min(seg["source_pages"]),"source_page_end":max(seg["source_pages"])},
      "topic":{"topic_number":seg["sequence"],"topic_id":topic_id,"title_by_locale":v["microtopic_title_by_locale"]},
      "learning_objectives":objectives,"source_refs":refs,"explain":{"title_by_locale":v["microtopic_title_by_locale"],"blocks":blocks},
      "practice":{"title_by_locale":{"en":"Check your understanding","hi":"अपनी समझ जाँचें"},"questions":questions,"question_count":len(questions)},
      "language_runtime_contract":{"supported_locales":["en","hi"],"default_locale":"en"},
      "generation_metadata":{"model_role":"EXPERIMENTAL_BENCHMARKED_CANDIDATE","prompt_version":PROMPT_VERSION,"segment_id":seg["segment_id"],"source_pages":seg["source_pages"],"publication_allowed":False,"human_review_required":True}}

def validate_lesson(x: dict[str,Any]) -> list[str]:
    e=[]; objs=x.get("learning_objectives",[]); oids={o.get("id") for o in objs if isinstance(o,dict)}
    refs=x.get("source_refs",[]); rids={r.get("source_ref_id") or r.get("id") for r in refs if isinstance(r,dict)}
    if not localized(x.get("chapter",{}).get("title_by_locale")) or not localized(x.get("topic",{}).get("title_by_locale")): e.append("chapter/topic titles invalid")
    if not objs or len(oids)!=len(objs) or None in oids: e.append("objective IDs invalid")
    if not refs or len(rids)!=len(refs) or None in rids: e.append("source refs invalid")
    blocks=x.get("explain",{}).get("blocks",[]); bids={b.get("id") for b in blocks if isinstance(b,dict)}
    if not blocks or len(bids)!=len(blocks) or None in bids: e.append("block IDs invalid")
    for b in blocks:
        if not isinstance(b,dict) or b.get("type") not in {"rich_text","recap","image"}: e.append("block type invalid"); continue
        for field,expected in (("objective_ids",oids),("source_refs",rids)):
            if any(v not in expected for v in b.get(field,[])): e.append("block reference invalid")
        if b.get("type")=="image":
            asset=b.get("asset"); page=asset.get("source_pdf_page_number") if isinstance(asset,dict) else None
            page_refs=[r for r in refs if isinstance(r,dict) and r.get("pdf_page")==page]
            if (not isinstance(asset,dict) or not isinstance(asset.get("url"),str) or not asset["url"].startswith("https://")
                or asset.get("source_pdf_sha256")!=x.get("workflow",{}).get("source_pdf_sha256")
                or asset.get("publication_allowed") is not False or asset.get("student_renderable") is not False
                or asset.get("review_status")!="CANDIDATE_NEEDS_HUMAN_REVIEW"
                or asset.get("rights_status")!="SOURCE_REFERENCE_ONLY" or not localized(asset.get("alt_by_locale"))
                or not localized(asset.get("caption_by_locale")) or not page_refs or
                not set(b.get("source_refs",[])).intersection({r.get("id") or r.get("source_ref_id") for r in page_refs})):
                e.append("review-only visual candidate has invalid provenance or publication gate")
            continue
        tx=b.get("text_by_locale")
        if not isinstance(tx,dict) or any(not isinstance(tx.get(l),dict) or not tx[l].get("paragraphs") for l in ("en","hi")): e.append("block bilingual text invalid")
    qs=x.get("practice",{}).get("questions",[])
    if len(qs)!=2: e.append("question count invalid")
    for q in qs:
        if not isinstance(q,dict) or q.get("type")!="multiple_choice" or q.get("rendering_contract_id")!="qtype-multiple-choice.v1": e.append("question contract invalid"); continue
        if not localized(q.get("prompt_by_locale")) or not localized(q.get("feedback_by_locale")): e.append("question locale gap")
        ids={o.get("id") for o in q.get("options",[]) if isinstance(o,dict)}
        if len(ids)<2 or len(ids)!=len(q.get("options",[])) or len(q.get("correct_answer_ids",[]))!=1 or q["correct_answer_ids"][0] not in ids: e.append("question option/answer invalid")
        if any(r not in oids for r in q.get("objective_ids",[])) or any(r not in rids for r in q.get("source_refs",[])): e.append("question references invalid")
    w=x.get("workflow",{})
    if w.get("publication_allowed") is not False or w.get("human_review_required") is not True: e.append("publication gate invalid")
    return e

def sft_records(source:dict[str,Any],seg:dict[str,Any],v:dict[str,Any],model:str)->list[dict[str,Any]]:
    chapter=v.get("chapter_number")
    group=f"chapter-{chapter}" if isinstance(chapter,int) else f"pages-{(min(seg['source_pages'])-1)//20:03d}"
    source_id=sha(source["source_url"]+"|"+source["source_pdf_sha256"])[:24]; rows=[]
    for ex in v["tutor_examples"]:
        lang=ex["language"]
        messages=[{"role":"system","content":TUTOR[lang]},{"role":"user","content":ex["student_message"].strip()},{"role":"assistant","content":ex["assistant_message"].strip()}]
        meta={"class_code":str(source["class_no"]),"subject_code":source["subject"],"language":lang,"chapter_code":group,
          "example_type":"tutor_dialogue","source_group_id":source["book_id"]+"|"+group,"source_id":source_id,
          "source_title":source["title"],"source_url":source["source_url"],"source_edition":source["edition"],
          "source_pdf_sha256":source["source_pdf_sha256"],"source_pages":sorted(set(ex["source_pages"])),
          "generation_model":model,"prompt_version":PROMPT_VERSION,"review_status":"machine_validated_draft",
          "rights_status":"pending_review","publication_allowed":False,"human_review_required":True}
        rows.append({"example_id":"qe-"+sha(json.dumps({"messages":messages,"metadata":meta},ensure_ascii=False,sort_keys=True))[:24],"messages":messages,"metadata":meta})
    return rows

def split_sft(rows:list[dict[str,Any]])->dict[str,list[dict[str,Any]]]:
    out={"train":[],"validation":[],"test":[]}
    for row in rows:
        m=row["metadata"]; group=str(m["source_group_id"]); bucket=int(sha(group)[:8],16)%100
        out["train" if bucket<80 else ("validation" if bucket<90 else "test")].append(row)
    return out

def validate_splits(rows:list[dict[str,Any]],parts:dict[str,list[dict[str,Any]]])->list[str]:
    errors=[]; ids=[r.get("example_id") for r in rows]
    if len(ids)!=len(set(ids)): errors.append("duplicate SFT IDs")
    actual=[]; groups={}
    for name in ("train","validation","test"):
        actual.extend(r.get("example_id") for r in parts[name])
        groups[name]={r.get("metadata",{}).get("source_group_id") for r in parts[name]}
        for r in parts[name]:
            if r.get("metadata",{}).get("review_status")!="machine_validated_draft" or r.get("metadata",{}).get("publication_allowed") is not False: errors.append("split contains non-draft row")
    if set(ids)!=set(actual) or len(actual)!=len(set(actual)): errors.append("master/split ID mismatch")
    for a,b in (("train","validation"),("train","test"),("validation","test")):
        if groups[a]&groups[b]: errors.append(f"source group leakage between {a} and {b}")
    return errors

def write_rag(path:Path,source:dict[str,Any])->None:
    if path.exists():
        old=read_jsonl(path)
        if any(r.get("source_pdf_sha256")!=source["source_pdf_sha256"] or r.get("source_book_id")!=source["book_id"] for r in old):
            raise ValueError("Existing RAG corpus mixes or belongs to another book/edition.")
        if len(old)==source["page_count"]:
            expected=source["pages"]
            valid=True
            for i,(row,page) in enumerate(zip(old,expected),1):
                expected_id="rag-"+sha(source["book_id"]+"|"+source["source_pdf_sha256"]+"|"+str(page["page_number"])+"|"+page["text_sha256"])[:24]
                if (row.get("source_pdf_page_number")!=i or row.get("text_sha256")!=page["text_sha256"]
                    or row.get("text")!=page["text"] or row.get("document_id")!=expected_id
                    or row.get("eligible_for_retrieval") is not page["has_text"]
                    or row.get("publication_allowed") is not False):
                    valid=False; break
            if valid: return
            raise ValueError("Existing RAG corpus failed per-page integrity validation; preserved for investigation.")
    rows=[]
    for p in source["pages"]:
        rows.append({"schema_version":"quantaedge.ncert-retrieval-document.v1",
          "document_id":"rag-"+sha(source["book_id"]+"|"+source["source_pdf_sha256"]+"|"+str(p["page_number"])+"|"+p["text_sha256"])[:24],
          "text":p["text"],"text_sha256":p["text_sha256"],"eligible_for_retrieval":p["has_text"],
          "class_code":str(source["class_no"]),"subject_code":source["subject"],"source_language":source["language"],
          "source_book_id":source["book_id"],"source_title":source["title"],"source_url":source["source_url"],
          "source_edition":source["edition"],"source_pdf_sha256":source["source_pdf_sha256"],"source_pdf_page_number":p["page_number"],
          "review_status":"SOURCE_TEXT_REQUIRES_REVIEW","publication_allowed":False,"human_review_required":True})
    write_jsonl(path,rows)

def write_outputs(source:dict[str,Any],segments:list[dict[str,Any]],checkpoint:dict[str,Any],outdir:Path,rag:Path,model:str,bench_sha:str)->dict[str,Any]:
    good=[(s,checkpoint[s["segment_id"]]) for s in segments if checkpoint.get(s["segment_id"],{}).get("status")=="COMPLETE"]
    topics=[]; lessons=[]; sft=[]
    for seg,item in good:
        v=item["generated"]; chapter=v.get("chapter_number")
        topics.append({"segment_id":seg["segment_id"],"source_pages":seg["source_pages"],"segment_sha256":seg["segment_sha256"],
          "chapter_number":chapter,"chapter_title_by_locale":v.get("chapter_title_by_locale"),
          "topic_title_by_locale":v["topic_title_by_locale"],"microtopic_title_by_locale":v["microtopic_title_by_locale"],
          "concepts_by_locale":v["concepts_by_locale"],"learning_objectives":v["learning_objectives"],
          "mapping_status":"DRAFT_MAPPED_REQUIRES_REVIEW" if chapter is not None else "UNRESOLVED_CHAPTER",
          "mapping_confidence":v.get("mapping_confidence","UNVERIFIED"),"human_review_required":True,"publication_allowed":False})
        if item.get("lesson"):
            lesson=dict(item["lesson"]); lesson["workflow"]=dict(lesson["workflow"]); lesson["workflow"]["generation_model"]=model
            err=validate_lesson(lesson)
            if err: raise ValueError("Saved lesson contract failure: "+"; ".join(err[:8]))
            lessons.append(lesson)
        sft.extend(item.get("sft_rows",[]))
    mapped={p for t in topics if t["mapping_status"]=="DRAFT_MAPPED_REQUIRES_REVIEW" for p in t["source_pages"]}
    unresolved=sorted(set(range(1,source["page_count"]+1))-mapped)
    unresolved_chapters=sorted(t["segment_id"] for t in topics if t["mapping_status"]!="DRAFT_MAPPED_REQUIRES_REVIEW")
    unresolved_text=sorted(s["segment_id"] for s in segments if checkpoint.get(s["segment_id"],{}).get("status")=="UNRESOLVED_SOURCE_TEXT")
    pending=[s["segment_id"] for s in segments if checkpoint.get(s["segment_id"],{}).get("status") not in {"COMPLETE","UNRESOLVED_SOURCE_TEXT"}]
    incomplete_map=bool(unresolved or unresolved_chapters or unresolved_text)
    mp=outdir/"curriculum-map.v1.json"
    mapping={"schema_version":"quantaedge.curriculum-map-candidate.v1",
      "source":{"class_code":str(source["class_no"]),"subject_code":source["subject"],"language":source["language"],
        "source_book_id":source["book_id"],"source_title":source["title"],"source_url":source["source_url"],
        "source_edition":source["edition"],"source_pdf_sha256":source["source_pdf_sha256"],"pdf_page_count":source["page_count"]},
      "prompt_version":PROMPT_VERSION,"generation_model":model,"segment_count":len(segments),"segments_completed":len(good),
      "pending_segments":pending,"unresolved_chapter_segments":unresolved_chapters,"unresolved_text_segments":unresolved_text,
      "mapped_source_pages":len(mapped),"total_source_pages":source["page_count"],
      "unresolved_source_pages":unresolved,"topics":topics,
      "status":"IN_PROGRESS" if pending else ("INCOMPLETE" if incomplete_map else "DRAFT_REQUIRES_HUMAN_REVIEW"),
      "human_review_required":True,"publication_allowed":False}
    write_json(mp,mapping)
    ldir=outdir/"lessons"; ldir.mkdir(parents=True,exist_ok=True); wanted=set()
    for lesson in lessons:
        name=lesson["artifact_id"]+".json"; wanted.add(name); write_json(ldir/name,lesson)
    for p in ldir.glob("qe-lesson-*.json"):
        if p.name not in wanted: p.unlink()
    unique={r["example_id"]:r for r in sft}; sft=list(unique.values())
    sftpath=outdir/"tutor-sft-candidates.jsonl"; write_jsonl(sftpath,sft)
    parts=split_sft(sft); splitdir=outdir/"sft-splits"
    for name,rows in parts.items(): write_jsonl(splitdir/(name+".jsonl"),rows)
    split_errors=validate_splits(sft,parts)
    if split_errors: raise ValueError("SFT split validation failed: "+"; ".join(split_errors))
    mapped_segments={t["segment_id"] for t in topics}
    lesson_segments={l.get("generation_metadata",{}).get("segment_id") for l in lessons}
    missing_sft=any(len(checkpoint.get(seg["segment_id"],{}).get("sft_rows",[]))<2 for seg in segments)
    layers={"6":mapping["status"],
      "7":"IN_PROGRESS" if pending else ("INCOMPLETE" if unresolved_chapters or unresolved_text or mapped_segments!=lesson_segments else "DRAFT_REQUIRES_HUMAN_REVIEW"),
      "8":"IN_PROGRESS" if pending else ("INCOMPLETE" if missing_sft else "DRAFT_REQUIRES_HUMAN_REVIEW")}
    overall="IN_PROGRESS" if pending else ("INCOMPLETE" if any(v=="INCOMPLETE" for v in layers.values()) else "DRAFT_REQUIRES_HUMAN_REVIEW")
    report={"schema_version":"quantaedge.layers-6-8-run-manifest.v1","source_book_id":source["book_id"],
      "source_pdf_sha256":source["source_pdf_sha256"],"class_code":str(source["class_no"]),"subject_code":source["subject"],
      "language":source["language"],"model":model,"model_role":"EXPERIMENTAL_BENCHMARKED_CANDIDATE",
      "benchmark_report_sha256":bench_sha,"prompt_version":PROMPT_VERSION,"segments_total":len(segments),
      "segments_completed":len(good),"layers":layers,"overall_status":overall,"lesson_candidates":len(lessons),
      "tutor_sft_candidates":len(sft),"visual_candidates_attached_for_human_review":sum(1 for lesson in lessons for block in lesson.get("explain",{}).get("blocks",[]) if block.get("type")=="image"),"retrieval_documents":source["page_count"],
      "retrieval_nonempty_documents":sum(p["has_text"] for p in source["pages"]),"source_pages_mapped":len(mapped),
      "source_page_count":source["page_count"],"unresolved_source_pages":unresolved,"unresolved_chapter_segments":unresolved_chapters,"unresolved_text_segments":unresolved_text,"pending_segments":pending,
      "production_model_selection":"PENDING_HUMAN_REVIEW","production_model":None,
      "human_review_required":True,"publication_allowed":False,
      "next_action":"Resume this exact book/model until checkpoints are complete; then review map, lesson candidates and both datasets. Production model selection remains a separate human decision."}
    write_json(outdir/"layers-6-8-run-manifest.v1.json",report)
    return report

def checkpoint_git(repo:Path,paths:list[Path],message:str)->None:
    items=[]
    for p in paths:
        try: items.append(p.resolve().relative_to(repo.resolve()).as_posix())
        except ValueError as exc: raise ValueError(f"Checkpoint outside repository: {p}") from exc
    if not items: return
    subprocess.run(["git","add","--",*items],cwd=repo,check=True)
    rc=subprocess.run(["git","diff","--cached","--quiet","--",*items],cwd=repo).returncode
    if rc==0: return
    if rc!=1: raise RuntimeError("Cannot inspect staged checkpoint.")
    subprocess.run(["git","config","user.name","github-actions[bot]"],cwd=repo,check=True)
    subprocess.run(["git","config","user.email","41898282+github-actions[bot]@users.noreply.github.com"],cwd=repo,check=True)
    subprocess.run(["git","commit","-m",message,"--",*items],cwd=repo,check=True)
    subprocess.run(["git","pull","--rebase","origin","main"],cwd=repo,check=True)
    try: subprocess.run(["git","push","origin","HEAD:main"],cwd=repo,check=True)
    except subprocess.CalledProcessError:
        subprocess.run(["git","pull","--rebase","origin","main"],cwd=repo,check=True)
        subprocess.run(["git","push","origin","HEAD:main"],cwd=repo,check=True)

def benchmark_report(repo:Path,path:Path|None,model:str)->dict[str,Any]:
    path=path or repo/"data"/"textbook-content-pipeline"/"model-benchmarks"/"class-6-ncert-curiosity.v1.json"
    if not path.is_file(): raise ValueError(f"Benchmark report missing: {path}")
    report=read_json(path); row=next((r for r in report.get("models",[]) if r.get("model")==model),None)
    if not row or row.get("successful_samples",0)<1 or row.get("lesson_contract_valid_rate",0)<=0:
        raise ValueError(f"Model {model} has no successful, contract-valid Class 6 NCERT benchmark sample.")
    if report.get("prompt_version")!=PROMPT_VERSION:
        raise ValueError("Benchmark prompt/lesson-contract version is stale; rerun benchmark before generation.")
    if report.get("production_model_selection")!="PENDING_HUMAN_REVIEW" or report.get("production_model") is not None:
        raise ValueError("Benchmark report must keep production selection pending human review.")
    return report

def run(args:argparse.Namespace,opener:Any=None)->dict[str,Any]:
    repo=args.repo_root.resolve(); source=load_source(repo,args.class_no,args.source_book_id)
    ledger=layer5_ledger(repo,source); report=benchmark_report(repo,args.benchmark_report,args.model)
    segments=segments_for(source["pages"],args.max_pages_per_segment,args.max_context_chars)
    visuals_by_segment,visual_fingerprint=load_visual_candidates(repo,source,segments)
    fingerprint=sha(json.dumps({"source_pdf_sha256":source["source_pdf_sha256"],"visual_fingerprint":visual_fingerprint,"manifest_sha256":sha_file(source["manifest_path"]),
      "pages_sha256":sha_file(source["text_path"]),"layer5_sha256":sha_file(ledger),"model":args.model,"prompt":PROMPT_VERSION},sort_keys=True))
    slug=re.sub(r"[^A-Za-z0-9._-]","-",args.model)
    outdir=ledger.parent/"layers-6-8"/f"{slug}-sha{source['source_pdf_sha256'][:8]}"
    cpfile=outdir/"generation-checkpoints.v1.json"; rag=ledger.parent/"layer-8-rag-corpus.v1.jsonl"
    if cpfile.exists():
        doc=read_json(cpfile)
        if doc.get("input_fingerprint")!=fingerprint: raise ValueError("Resume fingerprint mismatch (edition/text/Layer5/model/prompt changed); existing checkpoint preserved.")
        checkpoints=doc.get("segments",{})
        if not isinstance(checkpoints,dict): raise ValueError("Invalid checkpoint segments.")
    else:
        checkpoints={}; doc={"schema_version":"quantaedge.layers-6-8-checkpoint.v1","source_book_id":source["book_id"],
          "source_pdf_sha256":source["source_pdf_sha256"],"model":args.model,"prompt_version":PROMPT_VERSION,
          "input_fingerprint":fingerprint,"segments":{}}
    write_rag(rag,source); calls=0
    for seg in segments:
        prior=checkpoints.get(seg["segment_id"])
        if isinstance(prior,dict) and prior.get("status") in {"COMPLETE","UNRESOLVED_SOURCE_TEXT"}:
            if prior.get("status")=="COMPLETE" and prior.get("segment_sha256")!=seg["segment_sha256"]:
                raise ValueError(f"Segment checkpoint hash mismatch {seg['segment_id']}")
            continue
        # Empty-text pages are a terminal evidence gap, not something to hallucinate or retry forever.
        if not any(piece["text"].strip() for piece in seg["pieces"]):
            checkpoints[seg["segment_id"]]={"status":"UNRESOLVED_SOURCE_TEXT","segment_sha256":seg["segment_sha256"],"error":"No extractable text; human/OCR review needed."}
            doc["segments"]=checkpoints
            write_json(cpfile,doc)
            continue
        if calls>=args.max_model_calls: break
        calls+=1
        try:
            value,elapsed=ollama_chat(args.endpoint,args.model,prompt_for(source,seg),args.request_timeout,opener)
            errors=validate_output(value,set(seg["source_pages"]))
            if errors:
                checkpoints[seg["segment_id"]]={"status":"ERROR","segment_sha256":seg["segment_sha256"],
                  "attempts":int((prior or {}).get("attempts",0))+1,"error":"; ".join(errors[:12]),"elapsed_seconds":round(elapsed,2)}
            else:
                lesson=lesson_from(source,seg,value,visuals_by_segment.get(seg["segment_id"],[]))
                if lesson:
                    lesson["workflow"]["generation_model"]=args.model
                    lesson_errors=validate_lesson(lesson)
                    if lesson_errors: raise ValueError("Lesson contract: "+"; ".join(lesson_errors[:12]))
                records=sft_records(source,seg,value,args.model)
                checkpoints[seg["segment_id"]]={"status":"COMPLETE","segment_sha256":seg["segment_sha256"],
                  "generated":value,"lesson":lesson,"sft_rows":records,"elapsed_seconds":round(elapsed,2),
                  "mapping_status":"MAPPED" if value.get("chapter_number") is not None else "UNRESOLVED_CHAPTER"}
        except (OSError,ValueError,RuntimeError,urllib.error.URLError) as exc:
            checkpoints[seg["segment_id"]]={"status":"ERROR","segment_sha256":seg["segment_sha256"],
              "attempts":int((prior or {}).get("attempts",0))+1,"error":str(exc)[:1200]}
        doc["segments"]=checkpoints; write_json(cpfile,doc)
        if calls%args.checkpoint_every==0 or calls==args.max_model_calls:
            current=write_outputs(source,segments,checkpoints,outdir,rag,args.model,sha(json.dumps(report,sort_keys=True)))
            if args.commit_checkpoints: checkpoint_git(repo,[outdir,rag],f"checkpoint(content): L6-8 {source['book_id']} ({calls} calls)")
            print(json.dumps({"book_id":source["book_id"],"model":args.model,"calls_this_run":calls,
              "segments_completed":current["segments_completed"],"segments_total":current["segments_total"],"status":current["overall_status"]}))
    doc["segments"]=checkpoints; write_json(cpfile,doc)
    result=write_outputs(source,segments,checkpoints,outdir,rag,args.model,sha(json.dumps(report,sort_keys=True)))
    result["model_calls_this_run"]=calls; result["model_calls_budgeted_per_invocation"]=args.max_model_calls
    write_json(outdir/"layers-6-8-run-manifest.v1.json",result)
    if args.commit_checkpoints: checkpoint_git(repo,[outdir,rag],f"status(content): L6-8 {source['book_id']} ({result['overall_status']})")
    return result

def benchmark(args:argparse.Namespace,opener:Any=None)->dict[str,Any]:
    repo=args.repo_root.resolve(); source=load_source(repo,6,args.source_book_id)
    if source["subject"]!="science" or source["language"]!="en": raise ValueError("Benchmark must use Class 6 NCERT English Science (Curiosity).")
    segments=segments_for(source["pages"],4,7000)
    usable=[segment for segment in segments if any(piece["text"].strip() for piece in segment["pieces"])]
    if not usable: raise ValueError("NCERT benchmark source has no extractable text segments.")
    indices=sorted(set((0,len(usable)//2,len(usable)-1)))[:args.sample_segments]
    samplesegs=[usable[i] for i in indices]; visuals_by_segment,visual_fingerprint=load_visual_candidates(repo,source,samplesegs); results=[]
    models=[x.strip() for x in args.models.split(",") if x.strip()]
    if not models or len(models)>4 or len(set(models))!=len(models): raise ValueError("Provide 1–4 unique benchmark models.")
    for model in models:
        samples=[]
        for seg in samplesegs:
            start=time.monotonic()
            try:
                value,elapsed=ollama_chat(args.endpoint,model,prompt_for(source,seg),args.request_timeout,opener)
                errs=validate_output(value,set(seg["source_pages"]))
                lesson=lesson_from(source,seg,value,visuals_by_segment.get(seg["segment_id"],[])) if not errs else None
                le=validate_lesson(lesson) if lesson else (["unresolved chapter"] if not errs else [])
                sft_ok=not errs and len(sft_records(source,seg,value,model))>=2
                samples.append({"segment_id":seg["segment_id"],"source_pages":seg["source_pages"],"seconds":round(elapsed,2),
                  "schema_valid":not errs,"lesson_contract_valid":not le,"sft_examples_valid":sft_ok,
                  "source_page_citations_valid":not errs,"errors":(errs+le)[:10]})
            except (OSError,ValueError,RuntimeError,urllib.error.URLError) as exc:
                samples.append({"segment_id":seg["segment_id"],"source_pages":seg["source_pages"],"seconds":round(time.monotonic()-start,2),
                  "schema_valid":False,"lesson_contract_valid":False,"sft_examples_valid":False,
                  "source_page_citations_valid":False,"errors":[str(exc)[:1200]]})
        n=len(samples)
        ok=sum(s["schema_valid"] for s in samples); lessons=sum(s["lesson_contract_valid"] for s in samples)
        sft=sum(s["sft_examples_valid"] for s in samples); citations=sum(s["source_page_citations_valid"] for s in samples)
        results.append({"model":model,"samples_attempted":n,"successful_samples":ok,
          "schema_valid_rate":round(ok/n,3),"lesson_contract_valid_rate":round(lessons/n,3),
          "sft_examples_valid_rate":round(sft/n,3),"source_page_citation_valid_rate":round(citations/n,3),
          "average_seconds_per_sample":round(sum(s["seconds"] for s in samples)/n,2),"samples":samples})
    out={"schema_version":"quantaedge.local-ollama-benchmark.v1","benchmark_completed_utc":time.strftime("%Y-%m-%dT%H:%M:%SZ",time.gmtime()),
      "benchmark_scope":"same Class 6 NCERT Science text segments and prompt for all models",
      "source":{"book_id":source["book_id"],"title":source["title"],"edition":source["edition"],"source_url":source["source_url"],
        "source_pdf_sha256":source["source_pdf_sha256"],"sample_segment_ids":[s["segment_id"] for s in samplesegs],
        "sample_source_pages":sorted({p for s in samplesegs for p in s["source_pages"]})},
      "endpoint_mode":"native local Ollama /api/chat; no hosted API or API key","prompt_version":PROMPT_VERSION,"models":results,
      "selection_metric":"Review exact sample outputs plus schema validity, structured-lesson validity, page-citation validity and latency; no automatic winner.",
      "production_model_selection":"PENDING_HUMAN_REVIEW","production_model":None,
      "publication_allowed":False,"student_delivery_allowed":False,"human_review_required":True}
    output=args.output.resolve(); write_json(output,out)
    if args.commit_checkpoints: checkpoint_git(repo,[output],"benchmark(ai): local Ollama on Class 6 NCERT Curiosity")
    return out

def main(argv:list[str]|None=None)->int:
    p=argparse.ArgumentParser(description=__doc__); sub=p.add_subparsers(dest="mode",required=True)
    r=sub.add_parser("run"); r.add_argument("--repo-root",type=Path,default=Path.cwd()); r.add_argument("--class-no",type=int,choices=(6,7,8),required=True)
    r.add_argument("--source-book-id",required=True); r.add_argument("--endpoint",default="http://127.0.0.1:11434"); r.add_argument("--model",default="qwen2.5:1.5b")
    r.add_argument("--max-model-calls",type=int,default=6); r.add_argument("--max-pages-per-segment",type=int,default=4); r.add_argument("--max-context-chars",type=int,default=7000)
    r.add_argument("--request-timeout",type=int,default=180); r.add_argument("--checkpoint-every",type=int,default=2); r.add_argument("--benchmark-report",type=Path)
    r.add_argument("--allow-experimental-model",action="store_true"); r.add_argument("--commit-checkpoints",action="store_true")
    b=sub.add_parser("benchmark"); b.add_argument("--repo-root",type=Path,default=Path.cwd()); b.add_argument("--source-book-id",default="ncert-c6-english-fecu1")
    b.add_argument("--endpoint",default="http://127.0.0.1:11434"); b.add_argument("--models",default="qwen2.5:1.5b,qwen2.5:3b")
    b.add_argument("--sample-segments",type=int,default=3); b.add_argument("--request-timeout",type=int,default=180)
    b.add_argument("--output",type=Path,default=Path("data/textbook-content-pipeline/model-benchmarks/class-6-ncert-curiosity.v1.json")); b.add_argument("--commit-checkpoints",action="store_true")
    a=p.parse_args(argv)
    try:
        if a.mode=="benchmark":
            if not 1<=a.sample_segments<=3: raise ValueError("sample-segments must be 1..3.")
            result=benchmark(a)
        else:
            if not 1<=a.max_model_calls<=100 or not 1<=a.checkpoint_every<=5 or not 30<=a.request_timeout<=600: raise ValueError("Invalid model-call/timeout/checkpoint budget.")
            if not a.allow_experimental_model: raise ValueError("Explicitly pass --allow-experimental-model; these are unapproved review-only drafts.")
            repo=a.repo_root.resolve(); report=benchmark_report(repo,a.benchmark_report,a.model)
            result=run(a)
        print(json.dumps(result,ensure_ascii=False,indent=2)); return 0
    except (OSError,ValueError,RuntimeError,json.JSONDecodeError,subprocess.CalledProcessError) as exc:
        print(f"QuantaEdge Layers 6–8 stopped safely: {exc}",file=sys.stderr); return 2

if __name__=="__main__": raise SystemExit(main())
