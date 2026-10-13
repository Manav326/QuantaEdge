#!/usr/bin/env python3
"""Read-only audit of QuantaEdge textbook assets, page extraction, question drafts and bilingual coverage."""
from __future__ import annotations
import argparse,csv,hashlib,json,os,re
from pathlib import Path,PurePosixPath
from urllib.error import HTTPError,URLError
from urllib.request import Request,urlopen
TYPES={"MCQ","TRUE_FALSE","INPUT","MATCH","ORDER","ASSERTION_REASON","CASE_BASED","SHORT_ANSWER","LONG_ANSWER","NUMERICAL","DIAGRAM","MAP","SOURCE_BASED"}
AUTO={"MCQ","TRUE_FALSE","INPUT","NUMERICAL"}
LANG={"hi":"hindi","hindi":"hindi","en":"english","english":"english"}

def issue(sev,stage,code,msg,**ctx):
 d={"severity":sev,"stage":stage,"code":code,"message":msg}
 if ctx:d["context"]=ctx
 return d

def sha(path):
 h=hashlib.sha256()
 with path.open("rb") as f:
  for b in iter(lambda:f.read(1024*1024),b""):h.update(b)
 return h.hexdigest()

def load_targets(path):
 rows=[];issues=[]
 if not path.is_file():return [],[issue("BLOCKER","curriculum","TARGETS_CSV_MISSING","Target CSV missing.",path=str(path))]
 with path.open(encoding="utf-8-sig",newline="") as f:
  for line,r in enumerate(csv.DictReader(f),2):
   try:x={"class_code":str(int(r["class_code"])),"subject_code":r["subject_code"].strip().lower(),"chapter_code":r["chapter_code"].strip(),"question_type":r["question_type"].strip().upper(),"minimum_target_count":int(r["minimum_target_count"]),"actual_active_approved_count":int(r.get("actual_active_approved_count","0") or 0),"target_eligible":str(r.get("target_eligible","true")).lower()!="false"}
   except (ValueError,KeyError,AttributeError) as e:issues.append(issue("BLOCKER","curriculum","TARGET_ROW_INVALID","Malformed target row.",line=line,error=str(e)));continue
   if x["class_code"] not in {"6","7","8"} or x["subject_code"] not in {"maths","science"} or not x["chapter_code"] or x["question_type"] not in TYPES or min(x["minimum_target_count"],x["actual_active_approved_count"])<0:
    issues.append(issue("BLOCKER","curriculum","TARGET_ROW_INVALID","Invalid class/subject/chapter/type/count.",line=line));continue
   rows.append(x)
 return rows,issues

def audit_book(root,e,page_check=True):
 bad=[]
 if not isinstance(e,dict):return None,[issue("BLOCKER","registry","ENTRY_INVALID","Book index entry must be object.")]
 bid=str(e.get("book_id") or e.get("id") or "unknown");cov=e.get("content_availability");cov=cov if isinstance(cov,dict) else {}
 file=str(e.get("file") or "").strip()
 if not file:
  status=str(cov.get("status") or "").lower()
  return {"book_id":bid,"title":str(e.get("title") or ""),"subject":str(e.get("subject") or ""),"status":status or "missing","valid_pdf":False},[issue("BLOCKER" if status!="unavailable" else "WARNING","registry","BOOK_FILE_MISSING","No whole-book PDF indexed.",book_id=bid,missing_chapters=cov.get("missing_chapters",[]))]
 rel=PurePosixPath(file.replace("\\","/"))
 if rel.is_absolute() or any(p in {"",".",".."} for p in rel.parts):return None,[issue("BLOCKER","registry","UNSAFE_PATH","Unsafe manifest file path.",book_id=bid,file=file)]
 path=root.joinpath(*rel.parts)
 if not path.is_file():return None,[issue("BLOCKER","registry","PDF_NOT_FOUND","Indexed PDF absent from pulled image.",book_id=bid,file=file)]
 size=path.stat().st_size
 if isinstance(e.get("bytes"),int) and e["bytes"]!=size:bad.append(issue("BLOCKER","registry","SIZE_MISMATCH","File size differs from manifest.",book_id=bid))
 with path.open("rb") as f:sig=f.read(5)==b"%PDF-"
 if not sig:bad.append(issue("BLOCKER","registry","PDF_SIGNATURE_INVALID","Indexed file has no PDF signature.",book_id=bid))
 actual=sha(path);expected=str(e.get("sha256") or "").lower()
 if not re.fullmatch(r"[0-9a-f]{64}",expected):bad.append(issue("BLOCKER","registry","SHA_MISSING","Manifest lacks SHA-256.",book_id=bid))
 elif actual!=expected:bad.append(issue("BLOCKER","registry","SHA_MISMATCH","PDF differs from manifest checksum.",book_id=bid,expected=expected,actual=actual))
 pages=None
 if page_check:
  try:
   import pymupdf
   with pymupdf.open(path) as pdf:
    pages=len(pdf)
    if pdf.needs_pass or pages<1:bad.append(issue("BLOCKER","registry","PDF_UNREADABLE","PDF is encrypted or empty.",book_id=bid))
  except ImportError:bad.append(issue("WARNING","registry","PYMUPDF_MISSING","Install PyMuPDF to check actual page count.",book_id=bid))
  except Exception as ex:bad.append(issue("BLOCKER","registry","PDF_UNREADABLE","Cannot parse PDF.",book_id=bid,error=str(ex)))
 if isinstance(e.get("page_count"),int) and pages is not None and pages!=e["page_count"]:bad.append(issue("BLOCKER","registry","PAGE_COUNT_MISMATCH","Actual page count differs from manifest.",book_id=bid,expected=e["page_count"],actual=pages))
 edition=str(e.get("edition") or "").strip().lower()
 if edition in {"","unknown","unverified","current","n/a","not specified"}:bad.append(issue("BLOCKER","registry","EDITION_UNVERIFIED","Actual source edition/session is not recorded.",book_id=bid,edition=edition))
 source_url=str(e.get("source_url") or e.get("catalog_entry_url") or "")
 if not source_url.startswith("https://"):bad.append(issue("BLOCKER","registry","SOURCE_URL_MISSING","Official HTTPS source URL is missing.",book_id=bid))
 declared=str(cov.get("status") or "").lower();method=str(e.get("download_method") or "").lower()
 if declared in {"partial","unavailable"} or "partial" in method:
  status="partial";bad.append(issue("BLOCKER","registry","PARTIAL_BOOK_NOT_COMPLETE","Partial asset cannot satisfy whole-book coverage.",book_id=bid,missing_chapters=cov.get("missing_chapters",[])))
 elif declared=="complete":status="complete"
 else:status="provisional";bad.append(issue("WARNING","registry","COMPLETENESS_UNCONFIRMED","Manifest does not attest whole-book completeness.",book_id=bid))
 return {"book_id":bid,"title":str(e.get("title") or ""),"subject":str(e.get("subject") or ""),"edition":str(e.get("edition") or ""),"source_url":str(e.get("source_url") or e.get("catalog_entry_url") or ""),"file":file,"bytes":size,"sha256_matches":actual==expected,"manifest_page_count":e.get("page_count"),"actual_page_count":pages,"status":status,"valid_pdf":sig},bad

def audit_registry(root,grades,languages):
 out=[];bad=[];tracks={}
 for grade in grades:
  for lang in languages:
   folder=root/f"class-{grade}"/lang;ip=folder/"index.json";image={"class_code":grade,"language":lang,"index_path":str(ip),"book_entries":0,"books":[]}
   if not ip.is_file():bad.append(issue("BLOCKER","registry","REGISTRY_INDEX_MISSING","Pull the corresponding GHCR image before auditing.",class_code=grade,language=lang));out.append(image);continue
   try:index=json.loads(ip.read_text(encoding="utf-8"))
   except (OSError,json.JSONDecodeError) as ex:bad.append(issue("BLOCKER","registry","REGISTRY_INDEX_INVALID","Cannot parse index.json.",path=str(ip),error=str(ex)));out.append(image);continue
   if not isinstance(index,dict) or not isinstance(index.get("books"),list):bad.append(issue("BLOCKER","registry","REGISTRY_SCHEMA_INVALID","index.json must contain books array.",path=str(ip)));out.append(image);continue
   image["registry"]=index.get("registry");image["book_entries"]=len(index["books"])
   for entry in index["books"]:
    record,found=audit_book(folder,entry);bad.extend(found)
    if record is None:continue
    image["books"].append(record);image[record["status"]]=image.get(record["status"],0)+1
    subject_text=str(record.get("subject") or "").lower()
    subject="maths" if any(t in subject_text for t in ("math","ganit","गणित")) else ("science" if any(t in subject_text for t in ("science","vigyan","विज्ञान")) else None)
    if subject:
     key=f"{grade}:{subject}:{lang}";tr=tracks.setdefault(key,{"class_code":grade,"subject_code":subject,"language":lang,"complete_book_ids":[],"partial_book_ids":[],"provisional_book_ids":[]})
     col={"complete":"complete_book_ids","partial":"partial_book_ids","provisional":"provisional_book_ids"}.get(record["status"])
     if col:tr[col].append(record["book_id"])
   out.append(image)
 for grade in grades:
  for lang in languages:
   for subject in ("maths","science"):
    key=f"{grade}:{subject}:{lang}";tr=tracks.setdefault(key,{"class_code":grade,"subject_code":subject,"language":lang,"complete_book_ids":[],"partial_book_ids":[],"provisional_book_ids":[]})
    if not tr["complete_book_ids"]:bad.append(issue("BLOCKER","registry","TRACK_BOOK_COVERAGE_MISSING","No explicit complete book found for this class/subject/language.",**tr))
 return {"images":out,"tracks":list(tracks.values())},bad

def audit_extraction(b,path):
 bad=[]
 if not isinstance(b,dict) or b.get("schema_version")!=1 or b.get("bundle_status")!="DRAFT_EXTRACTION_ONLY":return None,[issue("BLOCKER","extraction","BUNDLE_INVALID","Expected DRAFT_EXTRACTION_ONLY schema 1.",path=str(path))]
 c=b.get("curriculum") if isinstance(b.get("curriculum"),dict) else {};grade=str(c.get("class_code",""));sub=str(c.get("subject_code","")).lower();lang=LANG.get(str(c.get("language","")).lower())
 if grade not in {"6","7","8"} or sub not in {"maths","science"} or not lang:bad.append(issue("BLOCKER","extraction","TRACK_INVALID","Class/subject/language invalid.",path=str(path),curriculum=c))
 src=b.get("source") if isinstance(b.get("source"),dict) else {};n=src.get("pdf_page_count");hs=str(src.get("pdf_sha256") or "").lower()
 edition=str(src.get("edition") or "").strip().lower()
 if edition in {"","unknown","unverified","current","n/a","not specified"}:bad.append(issue("BLOCKER","extraction","EDITION_UNVERIFIED","Actual source edition/session is missing.",path=str(path)))
 source_url=str(src.get("url") or "")
 if not source_url.startswith("https://"):bad.append(issue("BLOCKER","extraction","SOURCE_URL_MISSING","Official source URL is missing.",path=str(path)))
 if not isinstance(n,int) or n<1:bad.append(issue("BLOCKER","extraction","PAGE_COUNT_INVALID","Source PDF page count invalid.",path=str(path)));n=0
 if not re.fullmatch(r"[0-9a-f]{64}",hs):bad.append(issue("BLOCKER","extraction","SOURCE_HASH_INVALID","Source SHA-256 invalid.",path=str(path)))
 chapters=b.get("chapter_map")
 if not isinstance(chapters,list) or not chapters:bad.append(issue("BLOCKER","extraction","CHAPTER_MAP_MISSING","No reviewed chapter map.",path=str(path)));chapters=[]
 codes=set();ranges=[]
 for ch in chapters:
  if not isinstance(ch,dict):bad.append(issue("BLOCKER","extraction","CHAPTER_ROW_INVALID","Malformed chapter map row.",path=str(path)));continue
  code,a,z=str(ch.get("code") or ""),ch.get("page_start"),ch.get("page_end")
  if not code or code in codes or not isinstance(a,int) or not isinstance(z,int) or a<1 or z<a or z>n:bad.append(issue("BLOCKER","extraction","CHAPTER_RANGE_INVALID","Duplicate or invalid page range.",path=str(path),chapter=ch));continue
  for oa,oz,oc in ranges:
   if max(a,oa)<=min(z,oz):bad.append(issue("BLOCKER","extraction","CHAPTER_RANGE_OVERLAP","Chapter ranges overlap.",path=str(path),chapter=code,overlaps=oc))
  codes.add(code);ranges.append((a,z,code))
 pages=b.get("pages")
 if not isinstance(pages,list):bad.append(issue("BLOCKER","extraction","PAGES_MISSING","Page records missing.",path=str(path)));pages=[]
 seen={}
 for page in pages:
  if not isinstance(page,dict) or not isinstance(page.get("page_number"),int):bad.append(issue("BLOCKER","extraction","PAGE_ROW_INVALID","Malformed page row.",path=str(path)));continue
  num=page["page_number"]
  if num in seen or num<1 or num>n:bad.append(issue("BLOCKER","extraction","PAGE_DUPLICATE_OR_RANGE","Duplicate/out-of-range extracted page.",path=str(path),page=num));continue
  seen[num]=page;code=page.get("chapter_code")
  if code is not None and not any(c==code and a<=num<=z for a,z,c in ranges):bad.append(issue("BLOCKER","extraction","PAGE_CHAPTER_MISMATCH","Page-to-chapter assignment conflicts with reviewed map.",path=str(path),page=num,chapter=code))
  if not str(page.get("text") or "").strip():bad.append(issue("WARNING","extraction","PAGE_TEXT_EMPTY","Empty extracted page; inspect OCR/figures.",path=str(path),page=num))
 missing=sorted(set(range(1,n+1))-set(seen))
 if missing:bad.append(issue("BLOCKER","extraction","PAGE_COVERAGE_GAP","Extraction missing PDF pages.",path=str(path),count=len(missing),pages=missing[:50]))
 unmap=[num for num,p in seen.items() if p.get("chapter_code") is None]
 if unmap:bad.append(issue("WARNING","extraction","PAGES_UNMAPPED","Review unmapped pages as front/back matter or omissions.",path=str(path),count=len(unmap),pages=unmap[:50]))
 if (b.get("extraction") or {}).get("requires_human_review") is not True:bad.append(issue("BLOCKER","extraction","HUMAN_REVIEW_MISSING","Extraction lacks human review flag.",path=str(path)))
 return {"path":str(path),"class_code":grade,"subject_code":sub,"language":lang,"pdf_sha256":hs,"pdf_page_count":n,"chapter_codes":sorted(codes),"mapped_pages":sum(p.get("chapter_code") is not None for p in seen.values()),"unmapped_pages":len(unmap),"empty_text_pages":sum(not str(p.get("text") or "").strip() for p in seen.values())},bad

def audit_dir(folder,kind):
 stage="extraction" if kind=="extraction" else "question_generation"
 if folder is None:return {"configured":False,"count":0,"items":[]},[]
 if not folder.is_dir():return {"configured":True,"directory":str(folder),"count":0,"items":[]},[issue("BLOCKER",stage,"BUNDLE_DIR_MISSING","Bundle directory missing.",path=str(folder))]
 items=[];bad=[]
 for p in sorted(folder.rglob("*.json")):
  try:b=json.loads(p.read_text(encoding="utf-8"))
  except (OSError,json.JSONDecodeError) as ex:bad.append(issue("BLOCKER",stage,"JSON_INVALID","Cannot parse bundle JSON.",path=str(p),error=str(ex)));continue
  if kind=="extraction":
   rec,found=audit_extraction(b,p);bad.extend(found)
   if rec:items.append(rec)
   continue
  if not isinstance(b,dict) or b.get("bundle_status") not in {"AI_GENERATED_DRAFT","EDITOR_REVIEWED_DRAFT"}:bad.append(issue("BLOCKER",stage,"STATUS_INVALID","Expected generated or editor-reviewed draft.",path=str(p)));continue
  c=b.get("curriculum") if isinstance(b.get("curriculum"),dict) else {};ch=b.get("chapter") if isinstance(b.get("chapter"),dict) else {};src=b.get("source") if isinstance(b.get("source"),dict) else {}
  grade=str(c.get("class_code",""));sub=str(c.get("subject_code","")).lower();lang=LANG.get(str(c.get("language","")).lower());code=str(ch.get("code") or "")
  if grade not in {"6","7","8"} or sub not in {"maths","science"} or not lang or not code:bad.append(issue("BLOCKER",stage,"IDENTITY_INVALID","Candidate track identity invalid.",path=str(p)));continue
  lessons=b.get("lessons")
  if not isinstance(lessons,list) or not lessons:bad.append(issue("BLOCKER",stage,"LESSONS_MISSING","Candidate has no lesson.",path=str(p)));continue
  counts={};total=0
  for lesson in lessons:
   if not isinstance(lesson,dict):bad.append(issue("BLOCKER",stage,"LESSON_INVALID","Lesson row malformed.",path=str(p)));continue
   blocks=lesson.get("blocks",[]) if isinstance(lesson.get("blocks",[]),list) else []
   block_types=set()
   for block in blocks:
    if not isinstance(block,dict) or not isinstance(block.get("content"),dict):bad.append(issue("BLOCKER",stage,"BLOCK_INVALID","Lesson block malformed.",path=str(p)))
    elif block.get("active",True):block_types.add(str(block.get("block_type") or "").upper())
   required_blocks={"EXPLANATION","WORKED_EXAMPLE","GUIDED_PRACTICE","INDEPENDENT_PRACTICE","RECAP"}
   missing_blocks=sorted(required_blocks-block_types)
   if missing_blocks:bad.append(issue("BLOCKER",stage,"TEACHING_SECTION_GAP","Draft lesson lacks required teaching sections.",path=str(p),missing_blocks=missing_blocks))
   qs=lesson.get("questions")
   if not isinstance(qs,list):bad.append(issue("BLOCKER",stage,"QUESTIONS_INVALID","Questions not a list.",path=str(p)));continue
   for q in qs:
    total+=1
    if not isinstance(q,dict):bad.append(issue("BLOCKER",stage,"QUESTION_INVALID","Question malformed.",path=str(p)));continue
    typ=str(q.get("question_type") or "").upper();counts[typ]=counts.get(typ,0)+1;ref=q.get("source_page_numbers");pc=src.get("pdf_page_count")
    if typ not in TYPES or not str(q.get("prompt") or "").strip():bad.append(issue("BLOCKER",stage,"QUESTION_INVALID","Question type/prompt invalid.",path=str(p),type=typ))
    if not isinstance(ref,list) or not ref or any(not isinstance(x,int) or x<1 or (isinstance(pc,int) and x>pc) for x in ref):bad.append(issue("BLOCKER",stage,"QUESTION_CITATION_INVALID","Question missing valid source-page citation.",path=str(p),type=typ))
    if typ not in AUTO and q.get("answer_payload",{}):bad.append(issue("BLOCKER",stage,"REVIEW_ONLY_AUTO_ANSWER","Review-only type has automatic answer payload.",path=str(p),type=typ))
  meta=b.get("generated_by") if isinstance(b.get("generated_by"),dict) else {}
  if b.get("bundle_status")=="AI_GENERATED_DRAFT" and meta.get("auto_publish") is not False:bad.append(issue("BLOCKER",stage,"AUTO_PUBLISH_NOT_DISABLED","AI draft does not explicitly disable auto-publication.",path=str(p)))
  if meta.get("question_count") is not None and meta["question_count"]!=total:bad.append(issue("BLOCKER",stage,"QUESTION_COUNT_MISMATCH","Declared and actual question count differ.",path=str(p)))
  if b.get("bundle_status")=="EDITOR_REVIEWED_DRAFT" and (b.get("editorial_review") or {}).get("human_review_completed") is not True:bad.append(issue("BLOCKER",stage,"EDITOR_REVIEW_MISSING","No completed human-review evidence.",path=str(p)))
  items.append({"path":str(p),"bundle_status":b["bundle_status"],"class_code":grade,"subject_code":sub,"language":lang,"chapter_code":code,"lesson_count":len(lessons),"question_count":total,"question_type_counts":counts,"status":"DRAFT_ONLY"})
 return {"configured":True,"directory":str(folder),"count":len(items),"items":items},bad

def summary(rows):
 chapters={(r["class_code"],r["subject_code"],r["chapter_code"]) for r in rows};target=sum(r["minimum_target_count"] for r in rows if r["target_eligible"]);base=sum(r["actual_active_approved_count"] for r in rows);tracks={}
 for r in rows:
  k=f"{r['class_code']}:{r['subject_code']}";t=tracks.setdefault(k,{"class_code":r["class_code"],"subject_code":r["subject_code"],"chapter_codes":set(),"target":0,"documented_baseline":0})
  t["chapter_codes"].add(r["chapter_code"]);t["target"]+=r["minimum_target_count"];t["documented_baseline"]+=r["actual_active_approved_count"]
 for t in tracks.values():t["chapter_count"]=len(t.pop("chapter_codes"));t["remaining_to_target"]=max(0,t["target"]-t["documented_baseline"])
 return {"canonical_chapters":len(chapters),"minimum_question_target":target,"documented_baseline_approved_questions":base,"remaining_to_target_at_documented_baseline":max(0,target-base),"tracks":list(tracks.values())}


def reconcile_coverage(target_rows,grades,languages,extractions,candidates,reviewed):
 expected={(r["class_code"],r["subject_code"],r["chapter_code"],lang) for r in target_rows if r["class_code"] in grades for lang in languages}
 issues=[]; ex_found=set(); candidate_found=set()
 if extractions.get("configured"):
  for item in extractions.get("items",[]):
   for code in item.get("chapter_codes",[]): ex_found.add((item["class_code"],item["subject_code"],code,item["language"]))
  missing=sorted(expected-ex_found)
  if missing:issues.append(issue("BLOCKER","extraction","BILINGUAL_CHAPTER_COVERAGE_GAP","Canonical chapters are not represented in extraction bundles for both selected languages.",missing_count=len(missing),examples=[{"class_code":g,"subject_code":sub,"chapter_code":ch,"language":lang} for g,sub,ch,lang in missing[:80]]))
 if candidates.get("configured"):
  for item in candidates.get("items",[]):candidate_found.add((item["class_code"],item["subject_code"],item["chapter_code"],item["language"]))
 if reviewed.get("configured"):
  for item in reviewed.get("items",[]):candidate_found.add((item["class_code"],item["subject_code"],item["chapter_code"],item["language"]))
 if candidates.get("configured") or reviewed.get("configured"):
  missing=sorted(expected-candidate_found)
  if missing:issues.append(issue("BLOCKER","question_generation","BILINGUAL_CANDIDATE_COVERAGE_GAP","Not every canonical chapter has a generated/reviewed draft for both selected languages.",missing_count=len(missing),examples=[{"class_code":g,"subject_code":sub,"chapter_code":ch,"language":lang} for g,sub,ch,lang in missing[:80]]))
 # Candidate and reviewed copies can represent the same bundle; use the larger tier count, never their sum.
 def count_by_type(report):
  counts={}
  for item in report.get("items",[]):
   prefix=(item["class_code"],item["subject_code"],item["chapter_code"],item["language"])
   for typ,n in item.get("question_type_counts",{}).items():
    key=prefix+(typ,);counts[key]=counts.get(key,0)+int(n)
  return counts
 draft_counts=count_by_type(candidates);reviewed_counts=count_by_type(reviewed)
 gaps=[];expected_by_language=0
 for row in target_rows:
  if row["class_code"] not in grades or not row["target_eligible"] or row["minimum_target_count"]<1:continue
  for lang in languages:
   expected_by_language+=row["minimum_target_count"]
   key=(row["class_code"],row["subject_code"],row["chapter_code"],lang,row["question_type"])
   count=max(draft_counts.get(key,0),reviewed_counts.get(key,0))
   if count<row["minimum_target_count"]:
    gaps.append({"class_code":row["class_code"],"subject_code":row["subject_code"],"chapter_code":row["chapter_code"],"language":lang,"question_type":row["question_type"],"target":row["minimum_target_count"],"draft_or_reviewed_count":count,"gap":row["minimum_target_count"]-count})
 if gaps and (candidates.get("configured") or reviewed.get("configured")):
  issues.append(issue("BLOCKER","question_generation","BILINGUAL_QUESTION_TARGET_GAPS","Generated/reviewed drafts do not yet cover the curriculum question targets for both languages; draft counts are not approved/published questions.",gap_rows=len(gaps),missing_question_total=sum(x["gap"] for x in gaps),examples=gaps[:100]))
 return {"expected_chapter_track_language_rows":len(expected),"extraction_rows_found":len(ex_found),"extraction_rows_missing":len(expected-ex_found) if extractions.get("configured") else None,
         "candidate_chapter_track_language_rows":len(candidate_found),"candidate_rows_missing":len(expected-candidate_found) if (candidates.get("configured") or reviewed.get("configured")) else None,
         "question_target_for_selected_languages":expected_by_language,"question_type_gap_rows":len(gaps) if (candidates.get("configured") or reviewed.get("configured")) else None,
         "missing_question_total":sum(x["gap"] for x in gaps) if (candidates.get("configured") or reviewed.get("configured")) else None,
         "candidate_counts_are_not_approved_or_published":True},issues

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument("--cache-root",type=Path,default=Path("source-pdfs/registry-cache"));p.add_argument("--targets-csv",type=Path,default=Path("docs/product/curriculum-question-targets.csv"))
 p.add_argument("--extraction-dir",type=Path);p.add_argument("--candidate-dir",type=Path);p.add_argument("--reviewed-dir",type=Path);p.add_argument("--classes",default="6,7,8");p.add_argument("--languages",default="hi,en")
 p.add_argument("--skip-registry",action="store_true");p.add_argument("--live-api",action="store_true");p.add_argument("--api-base-url",default="http://localhost:8080");p.add_argument("--report",type=Path);p.add_argument("--strict",action="store_true");a=p.parse_args()
 grades=[x.strip() for x in a.classes.split(",") if x.strip()];langs=[]
 for v in a.languages.split(","):
  lang=LANG.get(v.strip().lower())
  if not lang:p.error("languages must be hi,en,hindi,english")
  if lang not in langs:langs.append(lang)
 if not grades or any(x not in {"6","7","8"} for x in grades):p.error("classes must be 6,7,8")
 t,ti=load_targets(a.targets_csv);reg,ri=({"skipped":True,"images":[],"tracks":[]},[]) if a.skip_registry else audit_registry(a.cache_root,grades,langs)
 ex,ei=audit_dir(a.extraction_dir,"extraction");ca,ci=audit_dir(a.candidate_dir,"candidate");rv,vi=audit_dir(a.reviewed_dir,"candidate")
 coverage,coverage_issues=reconcile_coverage(t,grades,langs,ex,ca,rv)
 live={"status":"NOT_RUN"};li=[];session=os.environ.get("QUANTAEDGE_ADMIN_SESSION","").strip()
 if a.live_api:
  live={"status":"CHECKED","tracks":[]}
  if not session:li.append(issue("BLOCKER","live_cms","SESSION_MISSING","Set QUANTAEDGE_ADMIN_SESSION locally for --live-api."))
  else:
   for grade in grades:
    for sub in ("maths","science"):
     url=f"{a.api_base_url.rstrip('/')}/api/v1/admin/content?classCode={grade}&subjectCode={sub}"
     try:
      with urlopen(Request(url,headers={"Accept":"application/json","Cookie":"QE_SESSION="+session}),timeout=20) as response:body=json.loads(response.read().decode("utf-8"))
      if not isinstance(body,list):raise ValueError("expected a JSON array")
      live["tracks"].append({"class_code":grade,"subject_code":sub,"records":len(body)})
     except (HTTPError,URLError,TimeoutError,OSError,ValueError,json.JSONDecodeError) as e:li.append(issue("BLOCKER","live_cms","LIVE_API_FAILED","CMS read failed.",class_code=grade,subject_code=sub,error=str(e)))
 findings=ti+ri+ei+ci+vi+coverage_issues+li
 report={"schema_version":1,"audit":"QuantaEdge textbook/chapter/bilingual assessment pipeline","read_only":True,"database_schema_changed":False,"scope":{"classes":grades,"languages":langs,"subjects":["maths","science"]},
 "overall_status":"BLOCKED" if any(x["severity"]=="BLOCKER" for x in findings) else "NEEDS_RUNTIME_VERIFICATION","runtime_end_to_end_test":"NOT_PROVEN_BY_ASSET_AUDIT",
 "summary":{"blockers":sum(x["severity"]=="BLOCKER" for x in findings),"warnings":sum(x["severity"]=="WARNING" for x in findings),"findings":len(findings)},
 "curriculum_targets":summary(t),"book_assets":reg,"chapter_extraction":ex,"bilingual_coverage":coverage,"question_candidates":ca,"reviewed_bundles":rv,"live_cms":live,"findings":findings,
 "question_type_capabilities":{"candidate_generation_schema":sorted(TYPES),"automatic_grading_types":sorted(AUTO),"draft_only_until_interaction_and_grading_exists":sorted(TYPES-AUTO)},
 "important_limits":["Checksum/page-count checks do not prove official edition or complete curriculum coverage.","Generated/reviewed candidates remain drafts until CMS approval.","Static checks do not prove browser render, assessment grading or progress persistence.","Target CSV counts are migration-derived, not live database counts."]}
 text=json.dumps(report,ensure_ascii=False,indent=2);print(text)
 if a.report:a.report.parent.mkdir(parents=True,exist_ok=True);a.report.write_text(text+"\n",encoding="utf-8")
 return 1 if a.strict and report["summary"]["blockers"] else 0
if __name__=="__main__":raise SystemExit(main())
