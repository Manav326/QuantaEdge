#!/usr/bin/env python3
"""Read-only completeness audit of the committed QuantaEdge content ZIPs."""
from __future__ import annotations
import argparse, copy, hashlib, json, os, re, sys, zipfile
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from import_site_content_draft import validate_site_bundle  # noqa: E402
IMPORT_PATH = "/content/bundles/"
SUBJECT_PATHS = ("/maths/", "/science/")
MEDIA_TYPES = {"IMAGE", "DIAGRAM", "VIDEO", "AUDIO", "ANIMATION"}
PLACEHOLDER = re.compile(r"\b(TODO|TBD|PLACEHOLDER|FIXME|LOREM IPSUM|INSERT TEXT)\b", re.I)
MAX_UNCOMPRESSED = 700 * 1024 * 1024

def sha256(path: Path) -> str:
    h=hashlib.sha256()
    with path.open("rb") as f:
        for part in iter(lambda:f.read(1024*1024),b""): h.update(part)
    return h.hexdigest()

def all_strings(x: Any):
    if isinstance(x,str): yield x
    elif isinstance(x,dict):
        for v in x.values(): yield from all_strings(v)
    elif isinstance(x,list):
        for v in x: yield from all_strings(v)

def namespaced_code(chapter: str, lesson: str) -> str:
    code=f"{chapter}-{lesson}"
    return code if len(code)<=100 else f"{chapter[:55]}-{lesson[-40:]}"

def inspect_bundle(data: Any, member: str, digest: str) -> dict[str,Any]:
    r={"member":member,"sha256":digest,"schema":data.get("schema") if isinstance(data,dict) else None,
       "validation":"FAILED","validation_error":None,"class_code":"","subject_code":"","language":"",
       "chapter_code":"","chapter_title":"","lessons":0,"questions":0,"blocks":0,"visual_blocks":0,
       "block_types":{},"question_types":{},"questions_per_lesson":{},"devanagari_characters":0,
       "placeholder_hits":[],"public_raw_asset_urls":[],"non_https_media_urls":[]}
    if not isinstance(data,dict):
        r["validation_error"]="JSON root is not an object"; return r
    c=data.get("curriculum") if isinstance(data.get("curriculum"),dict) else {}
    ch=data.get("chapter") if isinstance(data.get("chapter"),dict) else {}
    lessons=data.get("lessons") if isinstance(data.get("lessons"),list) else []
    questions=data.get("online_question_bank") if isinstance(data.get("online_question_bank"),list) else []
    r.update(class_code=str(c.get("class_code") or ""),subject_code=str(c.get("subject_code") or "").lower(),
      language=str(c.get("language") or "").lower(),chapter_code=str(ch.get("code") or "").lower(),
      chapter_title=str(ch.get("display_name") or ""),lessons=len(lessons),questions=len(questions))
    bt,qt,qp=Counter(),Counter(),Counter()
    lesson_codes={str(x.get("code") or "") for x in lessons if isinstance(x,dict)}
    public_urls,non_https=set(),set()
    for lesson in lessons:
        if not isinstance(lesson,dict): continue
        for block in lesson.get("blocks",[]) if isinstance(lesson.get("blocks"),list) else []:
            if not isinstance(block,dict): continue
            kind=str(block.get("block_type") or "(missing)").upper(); bt[kind]+=1
            if kind in MEDIA_TYPES:
                content=block.get("content") if isinstance(block.get("content"),dict) else {}
                url=str(content.get("url") or "").strip()
                if url and not url.lower().startswith("https://"): non_https.add(url)
                if "raw.githubusercontent.com/" in url.lower() or ("github.com/" in url.lower() and "/raw/" in url.lower()): public_urls.add(url)
    for q in questions:
        if not isinstance(q,dict): continue
        qt[str(q.get("question_type") or "(missing)").upper()]+=1
        if str(q.get("lesson_code") or "") in lesson_codes: qp[str(q.get("lesson_code"))]+=1
    text="\n".join(all_strings(data))
    r.update(blocks=sum(bt.values()),visual_blocks=sum(n for k,n in bt.items() if k in MEDIA_TYPES),
      block_types=dict(sorted(bt.items())),question_types=dict(sorted(qt.items())),
      questions_per_lesson={code:qp.get(code,0) for code in sorted(lesson_codes)},
      devanagari_characters=sum(1 for ch in text if "\u0900"<=ch<="\u097F"),
      placeholder_hits=sorted({m.group(0).upper() for m in PLACEHOLDER.finditer(text)}),
      public_raw_asset_urls=sorted(public_urls),non_https_media_urls=sorted(non_https))
    try:
        r["validator_summary"]=validate_site_bundle(copy.deepcopy(data),allow_noncanonical_chapter=True)
        r["validation"]="PASSED"
    except Exception as exc: r["validation_error"]=f"{type(exc).__name__}: {exc}"
    return r

def audit_package(path: Path) -> dict[str,Any]:
    o={"package":path.name,"path":str(path),"exists":path.is_file(),"size_bytes":path.stat().st_size if path.is_file() else None,
       "sha256":None,"zip_integrity":"NOT_CHECKED","member_count":0,"uncompressed_bytes":0,"archive_errors":[],
       "bundle_json_count":0,"unmapped_bundle_json_count":0,"members_sample":[],"bundles":[],"totals":{},"tracks":[],
       "duplicate_chapter_targets":[],"duplicate_lesson_target_codes":[],"repeated_prompts":[],
       "all_bundles_valid":False,"structural_draft_import_ready":False,"legacy_13_lessons_13_questions_ready":False,
       "legacy_profile_by_bundle":[],"warnings":[],"classification":"","next_action":""}
    if not path.is_file():
        o["classification"]="PACKAGE_MISSING"; o["archive_errors"].append("ZIP does not exist."); return o
    o["sha256"]=sha256(path)
    try:
        with zipfile.ZipFile(path) as z:
            infos=z.infolist(); names=[x.filename for x in infos]
            o["member_count"]=len(infos); o["uncompressed_bytes"]=sum(x.file_size for x in infos)
            o["members_sample"]=[{"name":x.filename,"bytes":x.file_size} for x in infos[:120]]
            dups=[n for n,c in Counter(names).items() if c>1]
            if dups: o["archive_errors"].append(f"Duplicate ZIP member names: {dups[:20]}")
            unsafe=[n for n in names if n.startswith(("/","\\")) or ".." in Path(n.replace("\\","/")).parts]
            if unsafe: o["archive_errors"].append(f"Unsafe paths found (nothing extracted): {unsafe[:20]}")
            if o["uncompressed_bytes"]>MAX_UNCOMPRESSED:
                o["zip_integrity"]="NOT_CHECKED_SIZE_LIMIT"; o["classification"]="AUDIT_SIZE_LIMIT"
                o["archive_errors"].append("Archive exceeds the 700 MiB audit ceiling."); return o
            bad=z.testzip(); o["zip_integrity"]="PASSED" if bad is None else "FAILED"
            if bad: o["archive_errors"].append(f"CRC error in member: {bad}")
            all_json=[x for x in infos if not x.is_dir() and IMPORT_PATH in ("/"+x.filename.replace("\\","/")) and x.filename.lower().endswith(".json")]
            bundles=[x for x in all_json if any(s in ("/"+x.filename.replace("\\","/").lower()) for s in SUBJECT_PATHS)]
            o["bundle_json_count"]=len(bundles); o["unmapped_bundle_json_count"]=len(all_json)-len(bundles)
            for info in sorted(bundles,key=lambda x:x.filename):
                try:
                    raw=z.read(info); data=json.loads(raw.decode("utf-8-sig"))
                    item=inspect_bundle(data,info.filename,hashlib.sha256(raw).hexdigest())
                except (UnicodeDecodeError,json.JSONDecodeError) as exc:
                    item=inspect_bundle(None,info.filename,""); item["validation_error"]=f"Invalid JSON: {exc}"
                o["bundles"].append(item)
    except (OSError,zipfile.BadZipFile,RuntimeError) as exc:
        o["zip_integrity"]="FAILED"; o["classification"]="ZIP_INVALID"; o["archive_errors"].append(f"{type(exc).__name__}: {exc}"); return o
    valid=[b for b in o["bundles"] if b["validation"]=="PASSED"]
    o["all_bundles_valid"]=bool(valid) and len(valid)==o["bundle_json_count"]
    o["totals"]={"valid_bundles":len(valid),"failed_bundles":len(o["bundles"])-len(valid),
      "chapters":len({(b["class_code"],b["subject_code"],b["language"],b["chapter_code"]) for b in o["bundles"] if b["chapter_code"]}),
      "lessons":sum(b["lessons"] for b in o["bundles"]),"questions":sum(b["questions"] for b in o["bundles"]),
      "blocks":sum(b["blocks"] for b in o["bundles"]),"visual_blocks":sum(b["visual_blocks"] for b in o["bundles"])}
    o["tracks"]=sorted({f"class-{b['class_code']}/{b['subject_code']}/{b['language']}" for b in valid})
    chapters,lessons,prompts=defaultdict(list),defaultdict(list),defaultdict(list)
    with zipfile.ZipFile(path) as z:
        for b in valid:
            data=json.loads(z.read(b["member"]).decode("utf-8-sig"))
            c,ch=data.get("curriculum",{}),data.get("chapter",{})
            scope=(str(c.get("class_code") or ""),str(c.get("subject_code") or "").lower(),
                   str(c.get("program_code") or "TARGET_OVERRIDE").upper(),str(c.get("language") or "").lower(),
                   str(ch.get("code") or "").lower())
            chapters[scope].append(b["member"])
            for lesson in data.get("lessons",[]):
                key=(*scope[:3],namespaced_code(scope[-1],str(lesson.get("code") or "")))
                lessons[key].append(b["member"]+"::"+str(lesson.get("code") or ""))
            for q in data.get("online_question_bank",[]):
                p=re.sub(r"\s+"," ",str(q.get("prompt") or "")).strip().casefold()
                if p: prompts[p].append(b["member"]+"::"+str(q.get("id") or ""))
    o["duplicate_chapter_targets"]=[{"scope":list(k),"members":v} for k,v in sorted(chapters.items()) if len(v)>1]
    o["duplicate_lesson_target_codes"]=[{"scope":list(k),"locations":v} for k,v in sorted(lessons.items()) if len(v)>1]
    o["repeated_prompts"]=[{"prompt":k[:150],"count":len(v),"locations":v[:8]} for k,v in sorted(prompts.items()) if len(v)>1][:100]
    classes={b["class_code"] for b in valid}; langs={b["language"] for b in valid}; subjects={b["subject_code"] for b in valid}
    expected=bool(valid) and classes=={"6"} and langs=={"hi"} and subjects=={"maths","science"}
    no_collisions=not o["duplicate_chapter_targets"] and not o["duplicate_lesson_target_codes"]
    o["structural_draft_import_ready"]=(o["zip_integrity"]=="PASSED" and not o["archive_errors"] and o["all_bundles_valid"] and o["unmapped_bundle_json_count"]==0 and expected and no_collisions)
    legacy=True
    for b in o["bundles"]:
        qp=b["questions_per_lesson"]
        one_each=bool(qp) and len(qp)==b["lessons"] and all(n==1 for n in qp.values())
        good=b["validation"]=="PASSED" and b["lessons"]==13 and b["questions"]==13 and one_each
        o["legacy_profile_by_bundle"].append({"member":b["member"],"lessons":b["lessons"],"questions":b["questions"],"one_question_per_lesson":one_each,"profile_13_plus_13":good})
        legacy=legacy and good
    o["legacy_13_lessons_13_questions_ready"]=o["structural_draft_import_ready"] and legacy
    if not o["bundle_json_count"]:
        o["classification"]="NO_IMPORTER_COMPATIBLE_AUTHORING_BUNDLES"
        o["next_action"]="Do not use lesson importer; if this contains page extractions, use the source-page review/import workflow."
    elif o["structural_draft_import_ready"]:
        o["classification"]="VALID_DRAFT_AUTHORING_PACKAGE"
        o["next_action"]="Run the existing importer in DRY RUN; verify course and Navodaya syllabus scope, then use authenticated --apply only for DRAFT insertion."
    else:
        o["classification"]="AUTHORING_BUNDLES_PRESENT_BUT_NOT_IMPORT_READY"
        o["next_action"]="Resolve validator, scope, unmapped-file and collision findings before any API write."
    if o["unmapped_bundle_json_count"]: o["warnings"].append("Some bundle JSON does not match the current importer's path filter.")
    if o["repeated_prompts"]: o["warnings"].append(f"{len(o['repeated_prompts'])} repeated normalized prompts need review.")
    if any(b["placeholder_hits"] for b in valid): o["warnings"].append("Placeholder-like text found.")
    if any(b["public_raw_asset_urls"] for b in valid): o["warnings"].append("Public raw GitHub media URLs found; verify rights and privacy.")
    if any(b["non_https_media_urls"] for b in valid): o["warnings"].append("Non-HTTPS or relative media URLs found.")
    if o["structural_draft_import_ready"] and not o["legacy_13_lessons_13_questions_ready"]: o["warnings"].append("Draft structure passes, but not all chapters follow the legacy 13-lessons/13-questions profile.")
    return o

def render(report: dict[str,Any]) -> str:
    lines=["# QuantaEdge content package completeness audit","","Read-only: no archive extraction, API calls, or database writes.","",
      "| Package | ZIP integrity | Bundles | Valid | Lessons | Questions | Draft-ready | Legacy 13+13 |","|---|---|---:|---:|---:|---:|---|---|"]
    for p in report["packages"]:
        t=p.get("totals",{})
        lines.append(f"| {p['package']} | {p['zip_integrity']} | {p['bundle_json_count']} | {t.get('valid_bundles',0)} | {t.get('lessons',0)} | {t.get('questions',0)} | {'YES' if p.get('structural_draft_import_ready') else 'NO'} | {'YES' if p.get('legacy_13_lessons_13_questions_ready') else 'NO'} |")
    for p in report["packages"]:
        lines += ["",f"## {p['package']}","",f"- Classification: **{p.get('classification')}**",f"- SHA-256: {p.get('sha256') or 'unavailable'}",f"- Tracks: {', '.join(p.get('tracks',[])) or '(none)'}",f"- Duplicate chapter/lesson targets: {len(p.get('duplicate_chapter_targets',[]))}/{len(p.get('duplicate_lesson_target_codes',[]))}",f"- Next action: {p.get('next_action','')}"]
        for e in p.get("archive_errors",[]): lines.append(f"- **Error:** {e}")
        for w in p.get("warnings",[]): lines.append(f"- **Warning:** {w}")
        for b in p.get("bundles",[]):
            lines.append(f"- {'PASS' if b['validation']=='PASSED' else 'FAIL'} {b['member']} — Class {b['class_code'] or '?'}/{b['subject_code'] or '?'}/{b['language'] or '?'}; chapter {b['chapter_code']}; {b['lessons']} lessons / {b['questions']} questions; {b['blocks']} blocks / {b['visual_blocks']} visuals.")
            if b.get("validation_error"): lines.append(f"  - Validator: {b['validation_error']}")
        for x in p.get("legacy_profile_by_bundle",[]):
            if not x["profile_13_plus_13"]: lines.append(f"- Legacy profile gap: {x['member']} — {x['lessons']} lessons / {x['questions']} questions; one question per lesson={x['one_question_per_lesson']}.")
    lines += ["","## Interpretation","","Structural readiness does not certify factual accuracy, teacher approval, source rights, reachable media URLs, or Navodaya/JNVST syllabus coverage.","The legacy 13+13 result is informational only; do not use the one-time bulk-release script as an import shortcut.",""]
    return "\n".join(lines)

def main() -> int:
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--package",action="append",type=Path,required=True)
    p.add_argument("--output",type=Path,required=True)
    args=p.parse_args()
    report={"schema_version":"quantaedge.content-package-completeness-audit.v1","audit_type":"READ_ONLY","packages":[audit_package(x.resolve()) for x in args.package]}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    md=render(report); print(md)
    target=os.environ.get("GITHUB_STEP_SUMMARY")
    if target:
        with open(target,"a",encoding="utf-8") as f: f.write(md+"\n")
    return 0

if __name__=="__main__": raise SystemExit(main())
