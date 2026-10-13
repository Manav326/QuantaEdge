#!/usr/bin/env python3
"""Generate, deduplicate, split and validate QuantaEdge AI tutor training data.

Uses only the Python standard library. Source extraction bundles stay local/private.
Generated examples are original drafts; this tool never marks them human-approved.
"""
from __future__ import annotations
import argparse, difflib, hashlib, json, os, re, subprocess, sys, time, urllib.error, urllib.request
from collections import defaultdict
from pathlib import Path
from typing import Any

CLASSES = {"6", "7", "8"}
SUBJECTS = {"maths", "science"}
LANGUAGES = {"hi", "en"}
TYPES = {"concept_explanation", "worked_example", "verified_question", "tutor_dialogue", "misconception", "practice_generation", "science_activity", "bilingual_terminology"}
PLACEHOLDER = {"", "unknown", "unverified", "current", "n/a", "not specified"}
SYSTEMS = {
 "hi": "तुम बिहार बोर्ड कक्षा 6–8 के लिए एक धैर्यवान शिक्षक हो। सरल, स्वाभाविक हिंदी में समझाओ। छात्र की उम्र के अनुकूल रहो, गलत धारणा को सम्मानपूर्वक सुधारो, और गणित/विज्ञान में तथ्य व गणना सही रखो। स्रोत का पाठ ज्यों का त्यों न दोहराओ; अपने शब्दों में मौलिक शिक्षण सामग्री बनाओ।",
 "en": "You are a patient Bihar Board teacher for Classes 6–8. Explain in age-appropriate, clear English. Correct misconceptions respectfully and ensure scientific facts and calculations are accurate. Create original teaching material in your own words; do not reproduce source passages."
}
def sha(text: str) -> str:
 return hashlib.sha256(text.encode("utf-8")).hexdigest()
def norm(text: str) -> str:
 text = text.casefold().replace("’", "'").replace("–", "-").replace("—", "-")
 return re.sub(r"\s+", " ", re.sub(r"[^\w\s\u0900-\u097f]", " ", text, flags=re.UNICODE)).strip()
def load_json(path: Path) -> Any:
 with path.open(encoding="utf-8") as f: return json.load(f)
def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
 path.parent.mkdir(parents=True, exist_ok=True)
 tmp = path.with_suffix(path.suffix + ".tmp")
 with tmp.open("w", encoding="utf-8", newline="\n") as f:
  for row in rows: f.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
 tmp.replace(path)
def read_jsonl(path: Path) -> list[dict[str, Any]]:
 rows=[]
 with path.open(encoding="utf-8") as f:
  for n,line in enumerate(f,1):
   if not line.strip(): continue
   try: row=json.loads(line)
   except json.JSONDecodeError as e: raise ValueError(f"{path}:{n}: invalid JSON: {e}") from e
   if not isinstance(row,dict): raise ValueError(f"{path}:{n}: record must be an object")
   rows.append(row)
 return rows
def check_source(bundle: Any, path: Path) -> dict[str, Any]:
 if not isinstance(bundle,dict) or bundle.get("schema_version") != 1 or bundle.get("bundle_status") != "DRAFT_EXTRACTION_ONLY":
  raise ValueError(f"{path}: expected schema_version 1 DRAFT_EXTRACTION_ONLY extraction bundle")
 c=bundle.get("curriculum",{}); s=bundle.get("source",{})
 cls=str(c.get("class_code","")); sub=str(c.get("subject_code",""))
 if cls not in CLASSES or sub not in SUBJECTS: raise ValueError(f"{path}: unsupported class/subject")
 if not str(s.get("url","")).startswith("https://"): raise ValueError(f"{path}: source URL must be HTTPS")
 if str(s.get("edition","")).strip().casefold() in PLACEHOLDER: raise ValueError(f"{path}: source edition is not verified")
 if not re.fullmatch(r"[0-9a-fA-F]{64}",str(s.get("pdf_sha256",""))): raise ValueError(f"{path}: missing PDF SHA-256")
 chapters=bundle.get("chapter_map"); pages=bundle.get("pages")
 if not isinstance(chapters,list) or not chapters or not isinstance(pages,list) or not pages: raise ValueError(f"{path}: chapter_map/pages missing")
 page_by_chapter=defaultdict(list)
 valid_chapters={}
 for ch in chapters:
  if not isinstance(ch,dict): continue
  code=str(ch.get("code","")).strip(); title=str(ch.get("title","")).strip()
  if not code or not title: continue
  valid_chapters[code]=ch
 for p in pages:
  if not isinstance(p,dict) or not isinstance(p.get("page_number"),int): continue
  code=p.get("chapter_code"); text=str(p.get("text","")).strip()
  if code in valid_chapters and len(text)>=40: page_by_chapter[code].append(p)
 usable=[{"code":code,"title":ch.get("title"),"pages":page_by_chapter.get(code,[])} for code,ch in valid_chapters.items() if page_by_chapter.get(code)]
 if not usable: raise ValueError(f"{path}: no mapped chapters have usable extracted text")
 return {"path":str(path),"class_code":cls,"subject_code":sub,"source":s,"curriculum":c,"chapters":usable}
def request_examples(context: dict[str,Any], language: str, batch: int, model: str, base_url: str, api_key: str) -> list[dict[str,Any]]:
 ch=context["chapter"]; src=context["source"]; cur=context["curriculum"]
 all_pages=ch["pages"]
 if len(all_pages) > 6:
  selected_indices=sorted(set(round(i*(len(all_pages)-1)/5) for i in range(6)))
  context_pages=[all_pages[i] for i in selected_indices]
 else:
  context_pages=all_pages
 context["context_pages"]=[p["page_number"] for p in context_pages]
 page_text="\\n\\n".join(f"PDF page {p['page_number']}:\\n{str(p['text'])[:2200]}" for p in context_pages)
 allowed=", ".join(sorted(TYPES))
 prompt = f"""Create exactly {batch} DISTINCT original educational AI-tutor training examples based on this curriculum context. Return ONLY a JSON object with an examples array. Every item must have keys: example_type, student_message, assistant_message, source_pages. example_type must be one of: {allowed}. Each student_message and assistant_message must be non-empty. Use language {language} throughout. Mix useful types; include stepwise teaching and feedback, not just quiz-answer pairs. For maths, independently calculate and explain. For science, avoid unsupported claims. Create new examples in your own words; never copy source wording or reproduce a full textbook question. source_pages must be a list of 1-based PDF page numbers from the context that actually support the example. Do not make up curriculum facts outside the provided context. If context is insufficient, create a basic conceptual question only when supported; do not hallucinate.
Curriculum: Bihar Board, Class {cur['class_code']}, subject {cur['subject_code']}, chapter {ch['title']} ({ch['code']}).
Source: {src['title']}, edition {src['edition']}.
Context:
{page_text}"""

 url=base_url.rstrip("/")+"/v1/chat/completions"
 body={"model":model,"temperature":0.35,"messages":[{"role":"system","content":SYSTEMS[language]},{"role":"user","content":prompt}],"response_format":{"type":"json_object"}}
 # JSON object wrapper avoids providers that reject top-level JSON arrays.
 body["messages"][1]["content"] += "\nReturn a JSON object with one key named examples whose value is the array."
 req=urllib.request.Request(url,data=json.dumps(body).encode(),headers={"Content-Type":"application/json","Authorization":"Bearer "+api_key},method="POST")
 last=None
 for attempt in range(5):
  try:
   with urllib.request.urlopen(req,timeout=120) as response: payload=json.loads(response.read().decode("utf-8"))
   content=payload["choices"][0]["message"]["content"]
   parsed=json.loads(content)
   result=parsed.get("examples") if isinstance(parsed,dict) else None
   if not isinstance(result,list): raise ValueError("provider response must contain an examples array")
   return result
  except (urllib.error.HTTPError,urllib.error.URLError,TimeoutError,KeyError,IndexError,json.JSONDecodeError,ValueError) as exc:
   last=exc
   if isinstance(exc,urllib.error.HTTPError) and exc.code not in {408,425,429,500,502,503,504}: break
   time.sleep(min(2**attempt,20))
 raise RuntimeError(f"generation request failed after retries: {last}")
def commit_and_push_dataset(path: Path) -> None:
 repo_root=Path(__file__).resolve().parents[1]
 resolved=path.resolve()
 try: relative=resolved.relative_to(repo_root.resolve())
 except ValueError as exc: raise ValueError("GitHub output must be inside the QuantaEdge repository.") from exc
 def git(*args: str) -> str:
  return subprocess.run(["git",*args],cwd=repo_root,check=True,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE).stdout.strip()
 branch=git("branch","--show-current")
 if branch!="main": raise ValueError(f"Refusing to push dataset from branch {branch!r}; switch to main first.")
 git("fetch","origin","main")
 if git("rev-parse","HEAD")!=git("rev-parse","origin/main"):
  raise ValueError("Local main is not exactly aligned with origin/main. Pull/reconcile first; dataset was not pushed.")
 git("add","--",str(relative))
 changed=subprocess.run(["git","diff","--cached","--quiet","--",str(relative)],cwd=repo_root)
 if changed.returncode==0:
  print("No dataset changes to commit.")
  return
 if changed.returncode not in (0,1): raise RuntimeError("Unable to inspect staged dataset changes.")
 subprocess.run(["git","commit","--only","-m","Save generated QuantaEdge AI training data","--",str(relative)],cwd=repo_root,check=True)
 git("push","origin","main")
 print(f"Committed and pushed dataset to main: {relative.as_posix()}")

def cmd_generate(a: argparse.Namespace) -> int:
 if not a.generate or not a.confirm_external_processing:
  print("DRY RUN: no provider request made. Re-run with --generate --confirm-external-processing after reviewing provider privacy/rights.")
  print(f"Input directory: {a.input_dir}; target: {a.target}; batch size: {a.batch_size}")
  return 0
 key=os.getenv("OPENAI_API_KEY","").strip(); base=os.getenv("OPENAI_API_BASE_URL","https://api.openai.com").strip(); model=os.getenv("QUANTAEDGE_TRAINING_DATA_MODEL","").strip()
 if not key or not model: raise ValueError("Set OPENAI_API_KEY and QUANTAEDGE_TRAINING_DATA_MODEL in the local environment.")
 paths=sorted(a.input_dir.rglob("*.json"))
 if not paths: raise ValueError(f"No extraction JSON files found in {a.input_dir}. Keep raw bundles private; see docs/ai/training-dataset-plan.md.")
 sources=[]
 for p in paths:
  try:
   raw=load_json(p)
   if isinstance(raw,dict) and raw.get("bundle_status")=="DRAFT_EXTRACTION_ONLY": sources.append(check_source(raw,p))
  except (ValueError,json.JSONDecodeError) as e: print(f"Skipping unusable input {p}: {e}",file=sys.stderr)
 if not sources: raise ValueError("No valid extraction bundles found.")
 chapters=[]
 for s in sources:
  for ch in s["chapters"]:
   chapters.append({"source_bundle":s,"chapter":ch})
 if len({(x["source_bundle"]["class_code"],x["source_bundle"]["subject_code"]) for x in chapters}) < 6:
  print("WARNING: source inputs do not cover all six class-subject tracks; output coverage will be incomplete.",file=sys.stderr)
 out=a.output; out.parent.mkdir(parents=True,exist_ok=True)
 existing=read_jsonl(out) if out.exists() else []
 seen={r.get("example_id") for r in existing}; count=len(existing); calls=0; cursor=0
 # Resume by appending only new records; a stable ID makes reruns safe.
 while count < a.target and calls < a.max_api_calls:
  item=chapters[cursor % len(chapters)]; cursor+=1
  lang="hi" if (count % 5) < 3 else "en"
  ctx=item["source_bundle"]
  calls+=1
  gen_context={"chapter":item["chapter"],"source":ctx["source"],"curriculum":ctx["curriculum"]}
  try: generated=request_examples(gen_context,lang,min(a.batch_size,a.target-count),model,base,key)
  except RuntimeError as e:
   print(str(e),file=sys.stderr); continue
  page_nums=set(gen_context.get("context_pages",[]))
  for n,g in enumerate(generated):
   if not isinstance(g,dict): continue
   student=str(g.get("student_message","")).strip(); assistant=str(g.get("assistant_message","")).strip()
   pages=g.get("source_pages")
   if not student or not assistant or not isinstance(pages,list) or not pages or any(not isinstance(p,int) or p not in page_nums for p in pages): continue
   et=g.get("example_type")
   if et not in TYPES: continue
   row={"messages":[{"role":"system","content":SYSTEMS[lang]},{"role":"user","content":student},{"role":"assistant","content":assistant}],
    "metadata":{"class_code":ctx["class_code"],"subject_code":ctx["subject_code"],"language":lang,"chapter_code":item["chapter"]["code"],"example_type":et,
    "source_id":sha(str(ctx["source"]["url"])+"|"+str(ctx["source"]["pdf_sha256"]))[:24],"source_title":ctx["source"]["title"],"source_url":ctx["source"]["url"],"source_edition":ctx["source"]["edition"],
    "source_pdf_sha256":ctx["source"]["pdf_sha256"],"source_pages":sorted(set(pages)),"generation_model":model,"review_status":"machine_validated_draft","rights_status":"pending_review","duplicate_group_id":None}}
   row["example_id"]="qe-"+sha(json.dumps(row["messages"],ensure_ascii=False,sort_keys=True)+json.dumps(row["metadata"],sort_keys=True))[:24]
   if row["example_id"] not in seen:
    existing.append(row); seen.add(row["example_id"]); count+=1
    if count>=a.target: break
  write_jsonl(out,existing)
  if calls%10==0: print(f"calls={calls} records={count}/{a.target}")
 if a.commit_and_push: commit_and_push_dataset(out)
 print(json.dumps({"output":str(out),"draft_records":len(existing),"target":a.target,"api_calls":calls,"status":"DRAFTS_ONLY","remaining":max(0,a.target-len(existing))},ensure_ascii=False,indent=2))
 return 0
def messages_ok(row: Any) -> bool:
 if not isinstance(row,dict) or not isinstance(row.get("messages"),list): return False
 msgs=row["messages"]
 if len(msgs)<3 or any(not isinstance(m,dict) for m in msgs): return False
 return msgs[0].get("role")=="system" and any(m.get("role")=="user" and str(m.get("content","")).strip() for m in msgs) and any(m.get("role")=="assistant" and str(m.get("content","")).strip() for m in msgs)

def simhash(text: str) -> int:
 tokens=re.findall(r"[\w\u0900-\u097f]+",norm(text))
 if not tokens: return 0
 weights=[0]*64
 for token in tokens:
  h=int(hashlib.blake2b(token.encode(),digest_size=8).hexdigest(),16)
  for i in range(64): weights[i]+=1 if h&(1<<i) else -1
 return sum(1<<i for i,w in enumerate(weights) if w>=0)
def prompt_of(row:dict[str,Any])->str:
 return " ".join(str(m.get("content","")) for m in row["messages"] if m.get("role")=="user")
def deduplicate(rows:list[dict[str,Any]])->tuple[list[dict[str,Any]],int]:
 kept=[]; exact=set(); prompt_exact=set(); buckets=defaultdict(list); dropped=0
 for row in rows:
  content=sha(json.dumps(row.get("messages"),ensure_ascii=False,sort_keys=True))
  prompt=norm(prompt_of(row)); pkey=(row.get("metadata",{}).get("class_code"),row.get("metadata",{}).get("subject_code"),row.get("metadata",{}).get("language"),sha(prompt))
  if content in exact or pkey in prompt_exact: dropped+=1; continue
  sh=simhash(prompt); meta=row.get("metadata",{})
  group=(meta.get("class_code"),meta.get("subject_code"),meta.get("language"))
  candidates=set()
  for shift in (0,16,32,48): candidates.update(buckets[(group,(sh>>shift)&65535)])
  near=False
  for idx in candidates:
   other=norm(prompt_of(kept[idx]))
   if len(prompt)>25 and len(other)>25 and (sh ^ simhash(other)).bit_count()<=3 and difflib.SequenceMatcher(None,prompt,other).ratio()>=0.92:
    near=True; break
  if near: dropped+=1; continue
  idx=len(kept); kept.append(row); exact.add(content); prompt_exact.add(pkey)
  for shift in (0,16,32,48): buckets[(group,(sh>>shift)&65535)].append(idx)
 return kept,dropped
def split_rows(rows:list[dict[str,Any]])->dict[str,list[dict[str,Any]]]:
 result={"train":[],"validation":[],"test":[]}
 for row in rows:
  m=row.get("metadata",{})
  group="|".join(str(m.get(k,"")) for k in ("class_code","subject_code","source_id","chapter_code"))
  n=int(sha(group)[:8],16)%100
  split="train" if n<80 else ("validation" if n<90 else "test")
  result[split].append(row)
 return result
def validate_rows(rows:list[dict[str,Any]], require_approved:bool=False)->list[str]:
 errors=[]; ids=set()
 for i,row in enumerate(rows,1):
  label=f"record {i}"
  if not messages_ok(row): errors.append(f"{label}: invalid conversational messages"); continue
  meta=row.get("metadata",{})
  if not isinstance(meta,dict): errors.append(f"{label}: metadata must be an object"); continue
  if str(meta.get("class_code")) not in CLASSES or meta.get("subject_code") not in SUBJECTS or meta.get("language") not in LANGUAGES: errors.append(f"{label}: invalid class/subject/language")
  if meta.get("example_type") not in TYPES: errors.append(f"{label}: invalid example_type")
  if not str(meta.get("source_edition","")).strip() or str(meta.get("source_edition","")).strip().casefold() in PLACEHOLDER: errors.append(f"{label}: missing verified source edition")
  if meta.get("review_status") not in {"machine_validated_draft","human_reviewed","approved"}: errors.append(f"{label}: invalid review_status")
  if require_approved and meta.get("review_status")!="approved": errors.append(f"{label}: not approved")
  if not meta.get("source_id") or not isinstance(meta.get("source_url"),str) or not meta.get("source_url","").startswith("https://") or not re.fullmatch(r"[0-9a-fA-F]{64}",str(meta.get("source_pdf_sha256",""))): errors.append(f"{label}: missing/invalid source provenance")
  if not isinstance(meta.get("source_pages"),list) or not meta["source_pages"]: errors.append(f"{label}: missing source_pages")
  eid=row.get("example_id")
  if not isinstance(eid,str) or not eid: errors.append(f"{label}: missing example_id")
  elif eid in ids: errors.append(f"{label}: duplicate example_id {eid}")
  else: ids.add(eid)
 return errors
def cmd_build(a:argparse.Namespace)->int:
 rows=read_jsonl(a.input)
 errors=validate_rows(rows,require_approved=not a.include_drafts)
 if errors: raise ValueError("Input validation failed:\n"+"\n".join(errors[:50]))
 rows,dropped=deduplicate(rows)
 if not rows: raise ValueError("No records remain after deduplication.")
 splits=split_rows(rows)
 root=a.output_dir; root.mkdir(parents=True,exist_ok=True)
 write_jsonl(root/"dataset_master.jsonl",rows)
 for name,items in splits.items(): write_jsonl(root/f"{name}.jsonl",items)
 manifest={"schema_version":1,"dataset_name":"quantaedge-ai-tutor","records":len(rows),"duplicates_removed":dropped,"splits":{k:len(v) for k,v in splits.items()},
 "class_subject_language_counts":{},"split_policy":"deterministic SHA-256 assignment by class/subject/source/chapter group; group stays in one split",
 "review_policy":"machine-validated drafts are excluded unless --include-drafts is explicitly passed; this flag is for controlled experiments only",
 "master_sha256":sha((root/"dataset_master.jsonl").read_text(encoding="utf-8")),"status":"DRAFT_EXPERIMENT" if a.include_drafts else "APPROVED_ONLY"}
 for row in rows:
  m=row["metadata"]; key=f"class-{m['class_code']}/{m['subject_code']}/{m['language']}"
  manifest["class_subject_language_counts"][key]=manifest["class_subject_language_counts"].get(key,0)+1
 (root/"manifest.json").write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
 print(json.dumps(manifest,ensure_ascii=False,indent=2))
 return 0
def cmd_validate(a:argparse.Namespace)->int:
 root=a.dataset_dir; errors=[]
 master=root/"dataset_master.jsonl"
 if not master.exists(): raise ValueError(f"Missing {master}")
 rows=read_jsonl(master); errors+=validate_rows(rows,require_approved=a.require_approved)
 split_ids={}; split_groups={}
 for name in ("train","validation","test"):
  p=root/f"{name}.jsonl"
  if not p.exists(): errors.append(f"Missing split file {p}"); continue
  part=read_jsonl(p); split_ids[name]={r.get("example_id") for r in part}
  split_groups[name]={"|".join(str(r.get("metadata",{}).get(k,"")) for k in ("class_code","subject_code","source_id","chapter_code")) for r in part}
  errors+= [f"{name}: {e}" for e in validate_rows(part,require_approved=a.require_approved)]
 expected_ids={r.get("example_id") for r in rows}
 actual_ids=set().union(*split_ids.values()) if split_ids else set()
 if expected_ids != actual_ids: errors.append(f"split/master mismatch: {len(expected_ids-actual_ids)} missing and {len(actual_ids-expected_ids)} unexpected IDs")
 for x,y in (("train","validation"),("train","test"),("validation","test")):
  overlap=split_ids.get(x,set())&split_ids.get(y,set())
  if overlap: errors.append(f"split leakage: {x}/{y} share {len(overlap)} example IDs")
  group_overlap=split_groups.get(x,set())&split_groups.get(y,set())
  if group_overlap: errors.append(f"source/chapter leakage: {x}/{y} share {len(group_overlap)} groups")
 if errors:
  print("\n".join(errors[:100]),file=sys.stderr); return 1
 print(json.dumps({"valid":True,"records":len(rows),"splits":{k:len(v) for k,v in split_ids.items()}},indent=2))
 return 0
def main()->int:
 p=argparse.ArgumentParser(description=__doc__); sub=p.add_subparsers(dest="command",required=True)
 g=sub.add_parser("generate"); g.add_argument("--input-dir",type=Path,default=Path("private-review")); g.add_argument("--output",type=Path,default=Path("data/ai-training/generated/drafts.jsonl")); g.add_argument("--target",type=int,default=25000); g.add_argument("--batch-size",type=int,default=5); g.add_argument("--max-api-calls",type=int,default=5000); g.add_argument("--generate",action="store_true"); g.add_argument("--confirm-external-processing",action="store_true"); g.add_argument("--commit-and-push",action="store_true",help="commit only the generated JSONL file and push it to origin/main after generation; requires a clean, synced local main"); g.set_defaults(func=cmd_generate)
 b=sub.add_parser("build"); b.add_argument("--input",type=Path,default=Path("data/ai-training/generated/drafts.jsonl")); b.add_argument("--output-dir",type=Path,default=Path("data/ai-training/datasets")); b.add_argument("--include-drafts",action="store_true"); b.set_defaults(func=cmd_build)
 v=sub.add_parser("validate"); v.add_argument("--dataset-dir",type=Path,default=Path("data/ai-training/datasets")); v.add_argument("--require-approved",action="store_true"); v.set_defaults(func=cmd_validate)
 a=p.parse_args()
 try:return a.func(a)
 except (OSError,ValueError,json.JSONDecodeError) as e: print(f"ERROR: {e}",file=sys.stderr); return 2
if __name__=="__main__": raise SystemExit(main())
