#!/usr/bin/env python3
"""Bounded and resumable Layers 6–8 for QuantaEdge, using native local Ollama."""
from __future__ import annotations
import argparse, hashlib, json, re, subprocess, sys, time, urllib.error, urllib.parse, urllib.request
from pathlib import Path
from typing import Any

PROMPT_VERSION = "qe-l6-l8-ollama-v7-exact-visual-asset-crosswalk"
LESSON_CONTRACT_VERSION = "quantaedge.structured-lesson-contextual-questions-runtime-schema.v5"
HOSTS = {"ncert.nic.in", "ncert.ncert.org.in"}
LANG = {"english":"en","hindi":"hi","en":"en","hi":"hi"}
SUBJECT = {"maths":"maths","mathematics":"maths","science":"science"}
KINDS = {"embedded_image","page_preview"}
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

def load_layer5_evidence(repo: Path, source: dict[str,Any]) -> tuple[Path,dict[str,Any],dict[int,list[dict[str,Any]]]]:
    """Verify L5's input lineage and return review-only visual evidence indexed by PDF page."""
    repo=repo.resolve()
    ledger_path=layer5_ledger(repo,source).resolve()
    ledger=read_json(ledger_path)
    if ledger.get("schema_version")!="quantaedge.textbook-coverage-ledger.v1" or ledger.get("layer")!=5:
        raise ValueError("Unsupported Layer 5 coverage-ledger schema/version.")
    if ledger.get("publication_allowed") is not False or ledger.get("human_review_required") is not True:
        raise ValueError("Layer 5 must remain review-only and publication-blocked.")
    src=ledger.get("source") if isinstance(ledger.get("source"),dict) else {}
    if (src.get("source_book_id")!=source["book_id"] or
        str(src.get("source_pdf_sha256") or "").lower()!=source["source_pdf_sha256"] or
        src.get("pdf_page_count")!=source["page_count"]):
        raise ValueError("Layer 5 source book, exact PDF hash or page count does not match the extraction input.")
    if str(src.get("class")) not in {str(source["class_no"]), "Class "+str(source["class_no"]), "class-"+str(source["class_no"])}:
        raise ValueError("Layer 5 class does not match the source extraction.")
    if LANG.get(str(src.get("language") or "").strip().lower())!=source["language"]:
        raise ValueError("Layer 5 language does not match the source extraction.")
    if SUBJECT.get(str(src.get("subject") or "").strip().lower())!=source["subject"]:
        raise ValueError("Layer 5 subject does not match the source extraction.")
    summary=ledger.get("summary") if isinstance(ledger.get("summary"),dict) else {}
    if ledger.get("ledger_status")!="COMPLETE" or summary.get("unresolved_count")!=0:
        raise ValueError("Layer 5 ledger is not evidence-complete; Layers 6–8 must not proceed.")
    paths=ledger.get("source_paths") if isinstance(ledger.get("source_paths"),dict) else {}
    def artifact(key: str) -> Path:
        value=paths.get(key)
        if not isinstance(value,str) or not value.strip():
            raise ValueError(f"Layer 5 source_paths.{key} is missing.")
        relpath=Path(value)
        if relpath.is_absolute() or ".." in relpath.parts:
            raise ValueError(f"Layer 5 source_paths.{key} is not a safe repository-relative path.")
        resolved=(repo/relpath).resolve()
        if not resolved.is_relative_to(repo) or not resolved.is_file():
            raise ValueError(f"Layer 5 source_paths.{key} does not resolve to a repository file.")
        return resolved
    inventory_path=artifact("inventory")
    raw_path=artifact("raw_analysis")
    manifest_path=artifact("manifest")
    text_path=artifact("text_jsonl")
    effective_path=artifact("effective_analysis_output")
    stored_ledger_path=artifact("coverage_ledger_output")
    if stored_ledger_path!=ledger_path:
        raise ValueError("Layer 5 ledger self-path does not resolve to the loaded ledger.")
    if manifest_path!=source["manifest_path"].resolve() or text_path!=source["text_path"].resolve():
        raise ValueError("Layer 5 manifest/page-JSONL paths do not match the exact extraction inputs.")
    inventory=read_json(inventory_path)
    raw=read_json(raw_path)
    manifest=read_json(manifest_path)
    effective=read_json(effective_path)
    inv_src=inventory.get("source") if isinstance(inventory.get("source"),dict) else {}
    if (inventory.get("schema_version")!="quantaedge.visual-inventory.v1" or
        inv_src.get("book_id")!=source["book_id"] or
        str(inv_src.get("pdf_sha256") or "").lower()!=source["source_pdf_sha256"] or
        inv_src.get("pdf_page_count")!=source["page_count"]):
        raise ValueError("Layer 1 inventory source identity differs from the extracted PDF.")
    manifest_sha=str(manifest.get("original_book_sha256") or manifest.get("asset_pdf_sha256") or "").lower()
    if manifest.get("book_id")!=source["book_id"] or manifest_sha!=source["source_pdf_sha256"]:
        raise ValueError("Layer 5 source manifest identity/hash mismatch.")
    if (raw.get("schema_version")!="quantaedge.visual-analysis.v1" or raw.get("source_book_id")!=source["book_id"] or
        str(raw.get("source_pdf_sha256") or "").lower()!=source["source_pdf_sha256"]):
        raise ValueError("Layer 4 raw analysis identity/hash mismatch.")
    raw_sha=sha_file(raw_path)
    if (effective.get("schema_version")!="quantaedge.visual-analysis-effective.v1" or
        effective.get("source_book_id")!=source["book_id"] or
        str(effective.get("source_pdf_sha256") or "").lower()!=source["source_pdf_sha256"] or
        effective.get("raw_analysis_sha256")!=raw_sha):
        raise ValueError("Layer 5 effective analysis does not match the current raw Layer 4 checkpoint.")
    page_rows=ledger.get("pages")
    if not isinstance(page_rows,list) or len(page_rows)!=source["page_count"]:
        raise ValueError("Layer 5 ledger does not contain exactly one row per original PDF page.")
    page_by_number={}
    for row in page_rows:
        if not isinstance(row,dict):
            raise ValueError("Layer 5 page entry must be an object.")
        number=row.get("source_pdf_page_number")
        if not isinstance(number,int) or isinstance(number,bool) or number in page_by_number:
            raise ValueError("Layer 5 page entries contain a missing/duplicate page number.")
        page_by_number[number]=row
    if set(page_by_number)!=set(range(1,source["page_count"]+1)):
        raise ValueError("Layer 5 page ledger is not contiguous across the complete source PDF.")
    for page in source["pages"]:
        number=page["page_number"]
        row=page_by_number[number]
        if row.get("text_status")!="COMPLETE":
            raise ValueError(f"Layer 5 page {number} is not text-complete.")
        evidence=row.get("text_evidence")
        if not isinstance(evidence,list) or not any(
            isinstance(item,dict) and item.get("text_sha256")==page["text_sha256"] and
            item.get("character_count")==len(page["text"]) for item in evidence
        ):
            raise ValueError(f"Layer 5 page {number} text hash/character count does not match the source JSONL.")
    assets=inventory.get("assets")
    ledger_visuals=ledger.get("visuals")
    raw_rows=raw.get("results")
    effective_rows=effective.get("results")
    if not all(isinstance(value,list) for value in (assets,ledger_visuals,raw_rows,effective_rows)):
        raise ValueError("Layer 5 visual evidence arrays are missing or malformed.")
    asset_by_id={}
    for row in assets:
        if not isinstance(row,dict) or not isinstance(row.get("asset_id"),str) or not row["asset_id"]:
            raise ValueError("Layer 1 inventory contains an asset without a stable ID.")
        if row["asset_id"] in asset_by_id:
            raise ValueError("Layer 1 inventory contains duplicate asset IDs.")
        asset_by_id[row["asset_id"]]=row
    ledger_by_id={row.get("asset_id"):row for row in ledger_visuals if isinstance(row,dict) and isinstance(row.get("asset_id"),str)}
    raw_by_id={row.get("asset_id"):row for row in raw_rows if isinstance(row,dict) and isinstance(row.get("asset_id"),str)}
    effective_by_id={row.get("asset_id"):row for row in effective_rows if isinstance(row,dict) and isinstance(row.get("asset_id"),str)}
    if len(ledger_by_id)!=len(ledger_visuals) or set(ledger_by_id)!=set(asset_by_id):
        raise ValueError("Layer 5 visual rows do not map one-to-one to the Layer 1 inventory assets.")
    if len(raw_by_id)!=len(raw_rows) or len(effective_by_id)!=len(effective_rows):
        raise ValueError("Layer 4/5 analysis records contain missing or duplicate asset IDs.")
    eligible_ids={aid for aid,row in asset_by_id.items() if row.get("kind") in KINDS}
    if set(raw_by_id)!=eligible_ids or set(effective_by_id)!=eligible_ids:
        raise ValueError("Layer 4/5 result IDs do not equal the eligible Layer 1 visual asset IDs.")
    expected_by_page={}
    for aid,asset in asset_by_id.items():
        number=page_no(asset)
        expected_by_page.setdefault(number,[]).append(aid)
    evidence_by_page:dict[int,list[dict[str,Any]]]={}
    for aid,asset in asset_by_id.items():
        row=ledger_by_id[aid]
        asset_hash=str(asset.get("asset_sha256") or "").lower()
        number=page_no(asset)
        if not isinstance(number,int) or isinstance(number,bool) or not 1<=number<=source["page_count"]:
            raise ValueError(f"Layer 1 visual {aid} has an invalid original PDF page number.")
        if (row.get("asset_sha256")!=asset_hash or row.get("source_pdf_sha256")!=source["source_pdf_sha256"] or
            row.get("source_pdf_page_number")!=number or row.get("asset_kind")!=asset.get("kind")):
            raise ValueError(f"Layer 5 visual {aid} does not match its inventory hash/page/kind.")
        if asset.get("kind") not in KINDS:
            continue
        raw_row=raw_by_id.get(aid)
        effective_row=effective_by_id.get(aid)
        if (row.get("analysis_status")!="ANALYZED_REQUIRES_HUMAN_REVIEW" or
            not isinstance(row.get("effective_analysis"),dict) or raw_row is None or effective_row is None):
            raise ValueError(f"Layer 5 marks eligible visual {aid} incomplete despite COMPLETE status.")
        if (raw_row.get("asset_sha256")!=asset_hash or raw_row.get("analysis_status")!="DRAFT_REQUIRES_REVIEW" or
            not isinstance(raw_row.get("analysis"),dict) or effective_row.get("asset_sha256")!=asset_hash or
            effective_row.get("effective_analysis")!=row.get("effective_analysis")):
            raise ValueError(f"Layer 4/5 analysis for visual {aid} does not match the source-bound ledger.")
        analysis=row["effective_analysis"]
        evidence_by_page.setdefault(number,[]).append({
            "asset_id":aid,"asset_kind":asset.get("kind"),"source_pdf_sha256":source["source_pdf_sha256"],"source_pdf_page_number":number,
            "source_asset_sha256":asset_hash,"analysis_status":row["analysis_status"],
            "correction_status":row.get("correction_status","NONE"),
            "visual_type":analysis.get("visual_type"),"visual_summary":analysis.get("visual_summary"),
            "visible_text":analysis.get("visible_text",[]),"concepts":analysis.get("concepts",[]),
            "context_relation":analysis.get("context_relation"),"limitations":analysis.get("limitations",[]),
            "human_review_required":True,"publication_allowed":False})
    for number,row in page_by_number.items():
        if sorted(row.get("visual_asset_ids",[]))!=sorted(expected_by_page.get(number,[])):
            raise ValueError(f"Layer 5 page {number} visual IDs do not match the Layer 1 asset inventory.")
    for rows in evidence_by_page.values():
        rows.sort(key=lambda item:item["asset_id"])
    return ledger_path,ledger,evidence_by_page


def layer5_evidence_for_segment(evidence_by_page: dict[int,list[dict[str,Any]]], segment: dict[str,Any]) -> list[dict[str,Any]]:
    result=[]
    for number in segment.get("source_pages",[]):
        result.extend(evidence_by_page.get(number,[]))
    return sorted(result,key=lambda item:(item["source_pdf_page_number"],item["asset_id"]))


def prompt_evidence_summary(rows: list[dict[str,Any]]) -> dict[str,Any]:
    """Bound model context while preserving complete evidence IDs and marking omitted detail."""
    pages={}
    for row in rows:
        number=row["source_pdf_page_number"]
        pages.setdefault(number,[]).append(row)
    result=[]
    for number,items in sorted(pages.items()):
        concepts=sorted({str(value).strip()[:100] for row in items for value in row.get("concepts",[])
                         if isinstance(value,str) and value.strip()})
        visible=sorted({str(value).strip()[:100] for row in items for value in row.get("visible_text",[])
                        if isinstance(value,str) and value.strip()})
        details=[]
        for row in items[:6]:
            details.append({"asset_id":row["asset_id"],"kind":row["asset_kind"],"visual_type":row.get("visual_type"),
                "summary":str(row.get("visual_summary") or "")[:180],
                "visible_text":[str(v)[:80] for v in row.get("visible_text",[])[:5] if isinstance(v,str)],
                "concepts":[str(v)[:80] for v in row.get("concepts",[])[:5] if isinstance(v,str)],
                "limitations":[str(v)[:100] for v in row.get("limitations",[])[:3] if isinstance(v,str)]})
        result.append({"source_pdf_page_number":number,"visual_asset_count":len(items),
            "all_visual_asset_ids":[row["asset_id"] for row in items],"visual_types":sorted({str(row.get("visual_type") or "uncertain") for row in items}),
            "visible_text_summary":visible[:20],"concept_summary":concepts[:24],"detail_rows":details,
            "asset_detail_rows_omitted":max(0,len(items)-len(details))})
    return {"schema_version":"quantaedge.layer5-prompt-evidence-summary.v1",
            "source_pdf_sha256":rows[0].get("source_pdf_sha256") if rows else None,
            "pages":result,"evidence_asset_count":len(rows),
            "all_asset_ids":[row["asset_id"] for row in rows],
            "human_review_required":True,"publication_allowed":False}


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
    source_page_count=source.get("page_count")
    if not isinstance(source_page_count,int) or isinstance(source_page_count,bool):
        source_page_count=(pack.get("source") or {}).get("page_count")
    if not isinstance(source_page_count,int) or isinstance(source_page_count,bool) or source_page_count<1:
        raise ValueError("Source/candidate pack is missing a valid original PDF page count.")
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
        occurrence_ids=sorted({str(occ.get("asset_id")) for occ in (row.get("occurrences") or [])
            if isinstance(occ,dict) and occ.get("source_pdf_page_number")==page
            and isinstance(occ.get("asset_id"),str) and occ.get("asset_id")})
        if not occurrence_ids:
            continue
        by_page.setdefault(page,[]).append({
            "candidate_id":cid,"candidate_kind":"embedded_image","source_book_id":source["book_id"],
            "source_pdf_sha256":source["source_pdf_sha256"],"source_pdf_page_number":page,
            "source_asset_sha256":source_asset_sha,"source_inventory_asset_ids":occurrence_ids,"display_sha256":display_sha,
            "url":"https://raw.githubusercontent.com/Manav326/QuantaEdge/main/"+path.relative_to(repo).as_posix(),
            "heuristic_score":float(row.get("heuristic_score") or 0),
        })
    by_segment={}
    for seg in segments:
        attached=[]
        for page in seg["source_pages"]:
            rows=by_page.get(page,[])
            if rows: attached.append(sorted(rows,key=lambda x:(-x["heuristic_score"],x["candidate_id"]))[0])
            if len(attached)>=2: break
        by_segment[seg["segment_id"]]=attached
    pack_fingerprint_assets=[]
    for row in pack.get("candidates",[]):
        if not isinstance(row,dict): continue
        page=row.get("source_pdf_page_number"); cid=str(row.get("candidate_id") or "")
        asset_sha=str(row.get("source_asset_sha256") or "").lower()
        if (row.get("candidate_kind")=="embedded_image" and row.get("source_book_id")==source["book_id"] and
            str(row.get("source_pdf_sha256") or "").lower()==source["source_pdf_sha256"] and
            row.get("publication_allowed") is False and row.get("human_review_required") is True and
            str(row.get("review_status") or "").upper() in {"CANDIDATE_NEEDS_HUMAN_REVIEW","NEEDS_REVIEW"} and
            isinstance(page,int) and not isinstance(page,bool) and 1<=page<=source_page_count and
            re.fullmatch(r"v3-image-[a-z0-9-]{8,100}",cid) and re.fullmatch(r"[0-9a-f]{64}",asset_sha)):
            pack_fingerprint_assets.append({"candidate_id":cid,"source_pdf_page_number":page,
                "source_asset_sha256":asset_sha,"display_path":row.get("display_path")})
    pack_fingerprint_assets.sort(key=lambda item:item["candidate_id"])
    fingerprint=sha(json.dumps({"slug":slug,"pack_sha256":sha_file(pack_path),"assets":pack_fingerprint_assets},sort_keys=True))
    return by_segment,fingerprint

QUESTION_TYPES = {
 "multiple_choice", "multiple_select", "true_false", "matching",
 "fill_blank", "image_observation", "short_answer", "scenario_based"
}
RENDERER_IDS = {
 "multiple_choice":"qtype-multiple-choice.v1",
 "multiple_select":"qtype-multiple-select.v1",
 "true_false":"qtype-true-false.v1",
 "matching":"qtype-matching.v1",
 "fill_blank":"qtype-fill-blank.v1",
 "image_observation":"qtype-image-observation.v1",
 "short_answer":"qtype-short-answer.v1",
 "scenario_based":"qtype-scenario-based.v1",
}

def bind_layer5_evidence_to_visual_candidates(visuals_by_segment: dict[str,list[dict[str,Any]]],
                                                   layer5_by_page: dict[int,list[dict[str,Any]]]) -> dict[str,list[dict[str,Any]]]:
    """Attach only Layer 5 analyses proven to describe the exact underlying V3 image bytes."""
    for segment_id,rows in visuals_by_segment.items():
        for candidate in rows:
            page=candidate.get("source_pdf_page_number")
            candidate_hash=str(candidate.get("source_asset_sha256") or "").lower()
            ids=candidate.get("source_inventory_asset_ids")
            if not isinstance(page,int) or isinstance(page,bool) or not isinstance(ids,list) or not ids:
                raise ValueError(f"{segment_id}/{candidate.get('candidate_id')}: V3 candidate lacks a source inventory occurrence crosswalk.")
            expected_ids=set(ids)
            matches=[row for row in layer5_by_page.get(page,[])
                if row.get("asset_id") in expected_ids
                and row.get("source_pdf_sha256")==candidate.get("source_pdf_sha256")
                and str(row.get("source_asset_sha256") or "").lower()==candidate_hash]
            matched_ids=sorted({row["asset_id"] for row in matches})
            if matched_ids!=sorted(expected_ids):
                raise ValueError(f"{segment_id}/{candidate.get('candidate_id')}: Layer 5 analysis IDs/hash do not reconcile with the V3 candidate's exact source-image occurrences.")
            candidate["layer5_analysis_asset_ids"]=matched_ids
            candidate["layer5_analysis"]=[{
                "asset_id":row["asset_id"],"source_pdf_page_number":row["source_pdf_page_number"],
                "source_asset_sha256":row["source_asset_sha256"],"visual_type":row.get("visual_type"),
                "visual_summary":str(row.get("visual_summary") or "")[:180],
                "visible_text":[str(value)[:80] for value in row.get("visible_text",[])[:5] if isinstance(value,str)],
                "concepts":[str(value)[:80] for value in row.get("concepts",[])[:5] if isinstance(value,str)],
                "context_relation":str(row.get("context_relation") or "")[:160],
                "limitations":[str(value)[:100] for value in row.get("limitations",[])[:3] if isinstance(value,str)],
                "correction_status":row.get("correction_status","NONE"),
                "human_review_required":True,"publication_allowed":False
            } for row in matches]
    return visuals_by_segment


def prompt_for(source: dict[str,Any], segment: dict[str,Any], visuals: list[dict[str,Any]]|None=None,
               layer5_visual_evidence: list[dict[str,Any]]|None=None) -> str:
    context="\n\n".join(f"Original PDF page {p['page']} (fragment {p['part']}):\n{p['text'] if p['text'].strip() else '[No extractable text]'}" for p in segment["pieces"])
    allowed=json.dumps(segment["source_pages"])
    visual_rows=visuals or []
    layer5_text=json.dumps(prompt_evidence_summary(layer5_visual_evidence or []),ensure_ascii=False,separators=(",",":"))
    visual_choices=[
        {"visual_candidate_id":x["candidate_id"],"source_pdf_page_number":x["source_pdf_page_number"],
         "source_asset_sha256":x.get("source_asset_sha256"),
         "source_inventory_asset_ids":x.get("source_inventory_asset_ids",[]),
         "matching_layer5_analysis_asset_ids":x.get("layer5_analysis_asset_ids",[]),
         "same_asset_layer5_analysis":x.get("layer5_analysis",[])}
        for x in visual_rows if isinstance(x,dict) and x.get("candidate_id")
    ]
    visual_text=json.dumps(visual_choices,ensure_ascii=False)
    return f"""Create one compact, review-only bilingual NCERT learning unit grounded only in this excerpt. Use original wording; never copy textbook text or exercise questions. Never guess facts, chapter identity, page numbers or visual contents.
Layer 5 same-PDF visual-analysis evidence (unapproved machine observations, for curriculum-mapping hints only): {layer5_text}
Layer 5 summaries/concepts/visible text are hypotheses, not verified facts. Cross-check them against the supplied source text and retain uncertainty and limitations. For a visual question, use only the exact V3 candidate's same-asset Layer 5 analysis listed with it; both its candidate ID and source inventory asset IDs must remain traceable. Never substitute a different asset's analysis. Layer 5 asset IDs are provenance references, not visual question IDs. Visual questions may cite only the separately allow-listed V3 candidate IDs above; those candidates remain blocked until human approval.
There is NO question-type quota. The structured types below are a supported catalogue, not a checklist. Generate only questions that the source context and learning objectives justify; do not rotate through types, do not pad the output, and do not omit a core assessable idea just to keep the set short. Return 1 to 6 distinct, useful questions; fewer high-quality questions are better than redundant questions.
Supported types and purpose:
- multiple_choice: one best choice for an important concept or misconception;
- multiple_select: select all independently correct statements when there are genuinely multiple answers;
- true_false: test a precise, meaningful proposition (not trivial wording);
- matching: match related terms, structures, functions or categories;
- fill_blank: recall a clearly defined term/value with a stable expected answer;
- image_observation: interpret a visual only when its candidate ID is in the supplied allowlist and the visual genuinely supports the question;
- short_answer: explain, compare, predict or justify; include a bilingual rubric and sample answer;
- scenario_based: apply the concept to a distinct realistic scenario with a choice response.
The only eligible image candidates for this excerpt are: {visual_text}. Do not use image_observation if no relevant candidate exists in this allowlist. Never invent visual IDs. A visual candidate is still unapproved; all visual-dependent output remains non-publishable and blocked from student rendering.
Every generated question must assess a stated learning objective, cite one or more exact allowed source PDF pages {allowed}, remain within the mapped chapter, and add information not already asked redundantly by another question. Source sections, tables, activities and examples may be accounted for through explanation or worked example; do not force every item to become a question.
Keep all learner-facing content semantically aligned in natural English and Hindi. Use one concise bilingual learning objective, at most two concepts per locale, concise explanations and one worked example when supported. Create exactly two tutor examples: one English and one Hindi; each must cite allowed pages.
Return JSON only:
{{
  "chapter_number": 1,
  "chapter_title_by_locale": {{"en":"...","hi":"..."}},
  "mapping_confidence": "HIGH|MEDIUM|LOW",
  "topic_title_by_locale": {{"en":"...","hi":"..."}},
  "microtopic_title_by_locale": {{"en":"...","hi":"..."}},
  "concepts_by_locale": {{"en":["..."],"hi":["..."]}},
  "learning_objectives": [{{"statement_by_locale":{{"en":"...","hi":"..."}}}}],
  "explanation_by_locale": {{"en":{{"heading":"...","paragraphs":["..."]}},"hi":{{"heading":"...","paragraphs":["..."]}}}},
  "worked_example_by_locale": {{"en":{{"heading":"...","paragraphs":["..."]}},"hi":{{"heading":"...","paragraphs":["..."]}}}},
  "recap_by_locale": {{"en":"...","hi":"..."}},
  "questions": [
    {{"type":"multiple_choice|multiple_select|true_false|matching|fill_blank|image_observation|short_answer|scenario_based",
      "prompt_by_locale":{{"en":"...","hi":"..."}},
      "feedback_by_locale":{{"en":"...","hi":"..."}},
      "source_pages":[{segment['source_pages'][0]}],
      "difficulty":"easy|medium|hard",
      "options":[{{"id":"stable-option-id","text_by_locale":{{"en":"...","hi":"..."}}}}],
      "correct_answer_ids":["stable-option-id"],
      "correct_answer":true,
      "left_items":[{{"id":"left-id","label_by_locale":{{"en":"...","hi":"..."}}}}],
      "right_items":[{{"id":"right-id","label_by_locale":{{"en":"...","hi":"..."}}}}],
      "correct_pairs":[{{"left_id":"left-id","right_id":"right-id"}}],
      "accepted_answers":{{"en":["..."],"hi":["..."]}},
      "answer_guide_by_locale":{{"en":{{"sample_answer":"...","key_points":["..."]}},"hi":{{"sample_answer":"...","key_points":["..."]}}}},
      "grading":{{"mode":"rubric","max_score":2,"rubric_by_locale":{{"en":"...","hi":"..."}}}},
      "scenario_by_locale":{{"en":"...","hi":"..."}},
      "visual_candidate_id":"an ID from the supplied allowlist or null"
    }}
  ],
  "tutor_examples":[
    {{"language":"en","student_message":"...","assistant_message":"...","source_pages":[{segment['source_pages'][0]}]}},
    {{"language":"hi","student_message":"...","assistant_message":"...","source_pages":[{segment['source_pages'][0]}]}}
  ]
}}
For each question emit only fields appropriate to its selected type. Option/answer ID arrays are for choice questions; true_false uses boolean correct_answer; matching uses both item lists and correct_pairs; fill_blank uses accepted answers in both locales; short_answer uses bilingual answer guide and rubric with a positive max score; scenario_based keeps separate scenario and prompt; image_observation uses a candidate ID from the allowlist plus choice fields. Do not make up scores for short answers.
Class {source['class_no']}; subject {source['subject']}; language edition {source['language']}; book ID {source['book_id']}; title {source['title']}; edition {source['edition']}.
SOURCE EXCERPT START
{context}
SOURCE EXCERPT END
""";

def ollama_chat(endpoint: str, model: str, prompt: str, timeout: int=180, opener: Any=None) -> tuple[dict[str,Any],float]:
    u=urllib.parse.urlparse(endpoint)
    if u.scheme!="http" or u.hostname not in {"127.0.0.1","localhost","::1"} or u.username or u.password:
        raise ValueError("Generation must use native unauthenticated loopback Ollama HTTP; external providers are disabled.")
    if not model.strip(): raise ValueError("Model is required.")
    body={"model":model,"stream":False,"format":"json","options":{"temperature":0.1,"num_ctx":8192,"num_predict":3072},
          "messages":[{"role":"system","content":SYSTEM},{"role":"user","content":prompt}]}
    req=urllib.request.Request(endpoint.rstrip("/")+"/api/chat",data=json.dumps(body,ensure_ascii=False).encode(),headers={"Content-Type":"application/json"},method="POST")
    send=opener or urllib.request.urlopen; last=None
    for attempt in range(2):
        started=time.monotonic()
        try:
            with send(req,timeout=timeout) as response: payload=json.loads(response.read().decode("utf-8"))
            content=(payload.get("message") or {}).get("content")
            if not isinstance(content,str): raise ValueError("Ollama response missing message.content.")
            if len(content.encode("utf-8")) > 100_000:
                raise ValueError("Ollama JSON response exceeds the 100 KB output budget.")
            value=json.loads(content)
            if not isinstance(value,dict): raise ValueError("Ollama response must be a JSON object.")
            return value,time.monotonic()-started
        except urllib.error.HTTPError as exc:
            last=RuntimeError(f"Ollama HTTP {exc.code}: {exc.read().decode(errors='replace')[:500]}")
            if exc.code not in {408,425,429,500,502,503,504}: break
        except (urllib.error.URLError,TimeoutError,ValueError,KeyError,json.JSONDecodeError) as exc: last=exc
        if attempt==0: time.sleep(1)
    raise RuntimeError(f"Local Ollama call failed: {last}") from last

def _bilingual_list(value: Any) -> bool:
    return isinstance(value,dict) and all(
        isinstance(value.get(locale),list) and bool(value[locale]) and
        all(isinstance(item,str) and item.strip() for item in value[locale])
        for locale in ("en","hi")
    )

def _bilingual_items(items: Any) -> bool:
    if not isinstance(items,list) or not items:
        return False
    seen=set()
    for item in items:
        if not isinstance(item,dict) or not isinstance(item.get("id"),str) or not item["id"].strip():
            return False
        if item["id"] in seen or not localized(item.get("label_by_locale")):
            return False
        seen.add(item["id"])
    return True

def validate_output(v: Any, allowed: set[int], visuals: list[dict[str,Any]]|None=None) -> list[str]:
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
    available_visuals={x.get("candidate_id"):x for x in (visuals or []) if isinstance(x,dict) and isinstance(x.get("candidate_id"),str)}
    qs=v.get("questions")
    if not isinstance(qs,list) or not 1<=len(qs)<=6: errors.append("questions must contain between 1 and 6 useful items")
    else:
        seen_prompts=set()
        for i,q in enumerate(qs):
            if not isinstance(q,dict): errors.append(f"question {i} invalid"); continue
            qtype=str(q.get("type","")).strip()
            if qtype not in QUESTION_TYPES: errors.append(f"question {i} unsupported type {qtype!r}"); continue
            if not localized(q.get("prompt_by_locale")) or not localized(q.get("feedback_by_locale")):
                errors.append(f"question {i} lacks bilingual prompt/feedback")
            if str(q.get("difficulty", "")).strip().lower() not in {"easy", "medium", "hard"}:
                errors.append(f"question {i} difficulty must be easy, medium or hard")
            prompt_key=json.dumps(q.get("prompt_by_locale"),ensure_ascii=False,sort_keys=True)
            if prompt_key in seen_prompts: errors.append(f"question {i} duplicates another question prompt")
            seen_prompts.add(prompt_key)
            cited=q.get("source_pages")
            if not isinstance(cited,list) or not cited or any(not isinstance(p,int) or isinstance(p,bool) or p not in allowed for p in cited):
                errors.append(f"question {i} must cite one or more exact allowed PDF pages")
            if qtype in {"multiple_choice","multiple_select","scenario_based","image_observation"}:
                opts=q.get("options"); correct=q.get("correct_answer_ids")
                if not isinstance(opts,list) or len(opts)<2: errors.append(f"question {i} needs at least two options"); continue
                ids=[o.get("id") for o in opts if isinstance(o,dict) and isinstance(o.get("id"),str) and o.get("id").strip() and localized(o.get("text_by_locale"))]
                if len(ids)!=len(opts) or len(ids)!=len(set(ids)): errors.append(f"question {i} options invalid")
                if not isinstance(correct,list) or not correct or any(not isinstance(cid,str) for cid in correct) or len(correct)!=len(set(correct)) or any(cid not in ids for cid in correct):
                    errors.append(f"question {i} answer option references invalid")
                if qtype in {"multiple_choice","scenario_based","image_observation"} and (not isinstance(correct,list) or len(correct)!=1):
                    errors.append(f"question {i} must have exactly one correct option")
                if qtype=="scenario_based" and not localized(q.get("scenario_by_locale")):
                    errors.append(f"question {i} scenario is not bilingual and separate from its prompt")
                if qtype=="image_observation":
                    visual_id=q.get("visual_candidate_id")
                    visual=available_visuals.get(visual_id) if isinstance(visual_id,str) else None
                    if visual is None:
                        errors.append(f"question {i} image observation requires an allowed relevant visual_candidate_id")
                    elif not isinstance(cited,list) or visual.get("source_pdf_page_number") not in cited:
                        errors.append(f"question {i} must cite the PDF page containing its referenced image")
            elif qtype=="true_false":
                if not isinstance(q.get("correct_answer"),bool): errors.append(f"question {i} true_false correct_answer must be boolean")
            elif qtype=="matching":
                left=q.get("left_items"); right=q.get("right_items"); pairs=q.get("correct_pairs")
                if not _bilingual_items(left) or not _bilingual_items(right): errors.append(f"question {i} matching items invalid")
                else:
                    lset={x["id"] for x in left}; rset={x["id"] for x in right}
                    if not isinstance(pairs,list) or len(pairs)!=len(left): errors.append(f"question {i} matching needs one pair per left item")
                    else:
                        seen_left=set()
                        for pair in pairs:
                            if not isinstance(pair,dict) or not isinstance(pair.get("left_id"),str) or not isinstance(pair.get("right_id"),str) or pair.get("left_id") not in lset or pair.get("right_id") not in rset or pair.get("left_id") in seen_left:
                                errors.append(f"question {i} matching pair references invalid"); break
                            seen_left.add(pair["left_id"])
            elif qtype=="fill_blank":
                if not _bilingual_list(q.get("accepted_answers")): errors.append(f"question {i} accepted answers need non-empty English/Hindi lists")
            elif qtype=="short_answer":
                guide=q.get("answer_guide_by_locale"); grading=q.get("grading")
                if not isinstance(guide,dict) or any(not isinstance(guide.get(l),dict) or not isinstance(guide[l].get("sample_answer"),str) or not guide[l]["sample_answer"].strip() or not isinstance(guide[l].get("key_points"),list) or not guide[l]["key_points"] or any(not isinstance(point,str) or not point.strip() for point in guide[l]["key_points"] ) for l in ("en","hi")):
                    errors.append(f"question {i} needs bilingual short-answer guide/sample answer")
                if not isinstance(grading,dict) or grading.get("mode")!="rubric" or not isinstance(grading.get("max_score"),int) or isinstance(grading.get("max_score"),bool) or grading["max_score"]<=0 or not localized(grading.get("rubric_by_locale")):
                    errors.append(f"question {i} needs positive max_score and bilingual rubric")
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

def tutor_citations_valid(value: Any, allowed_pages: set[int]) -> bool:
    examples=value.get("tutor_examples") if isinstance(value,dict) else None
    if not isinstance(examples,list) or not examples:
        return False
    languages=set()
    for example in examples:
        if not isinstance(example,dict) or example.get("language") not in {"en","hi"}:
            return False
        languages.add(example["language"])
        pages=example.get("source_pages")
        if not isinstance(pages,list) or not pages or any(
            not isinstance(page,int) or isinstance(page,bool) or page not in allowed_pages for page in pages
        ):
            return False
    return languages=={"en","hi"}


def lesson_from(source: dict[str,Any], seg: dict[str,Any], v: dict[str,Any], visuals: list[dict[str,Any]]|None=None,
                layer5_visual_evidence: list[dict[str,Any]]|None=None) -> dict[str,Any]|None:
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
          "source_inventory_asset_ids":visual.get("source_inventory_asset_ids",[]),
          "layer5_analysis_asset_ids":visual.get("layer5_analysis_asset_ids",[]),
          "display_sha256":visual["display_sha256"],"review_status":"CANDIDATE_NEEDS_HUMAN_REVIEW",
          "rights_status":"SOURCE_REFERENCE_ONLY","publication_allowed":False,"student_renderable":False,
          "human_review_required":True,"alt_by_locale":alt,"caption_by_locale":caption}
        blocks.append({"id":aid+"-visual-"+str(i),"type":"image","order":len(blocks)+1,
          "objective_ids":objective_ids,"source_refs":[ref_by_page[page]],"visual_id":visual["candidate_id"],
          "alt_by_locale":alt,"caption_by_locale":caption,"asset":asset})
    blocks.extend([
      {"id":aid+"-worked","type":"rich_text","order":len(blocks)+1,"objective_ids":objective_ids,"source_refs":refids,"text_by_locale":v["worked_example_by_locale"]},
      {"id":aid+"-recap","type":"recap","order":len(blocks)+2,"objective_ids":objective_ids,"source_refs":refids,
       "content_by_locale":{"en":{"key_takeaways":[v["recap_by_locale"]["en"]],"vocabulary":[]},"hi":{"key_takeaways":[v["recap_by_locale"]["hi"]],"vocabulary":[]}}},
    ])
    questions=[]
    visual_by_id={x.get("candidate_id"):x for x in (visuals or []) if isinstance(x,dict) and isinstance(x.get("candidate_id"),str)}
    candidate_block_by_id={b.get("visual_id"):b for b in blocks if isinstance(b,dict) and b.get("type")=="image"}
    for i,q in enumerate(v["questions"],1):
        qtype=q["type"]
        question={"id":aid+f"-q-{i}","type":qtype,"rendering_contract_id":RENDERER_IDS[qtype],
                  "prompt_by_locale":q["prompt_by_locale"],"feedback_by_locale":q["feedback_by_locale"],
                  "objective_ids":objective_ids,"source_refs":[ref_by_page[p] for p in q["source_pages"]],
                  "difficulty":q["difficulty"]}
        for field in ("options","correct_answer_ids","correct_answer","left_items","right_items","correct_pairs",
                      "accepted_answers","answer_guide_by_locale","grading","scenario_by_locale"):
            if field in q: question[field]=q[field]
        if qtype=="image_observation":
            visual_id=q["visual_candidate_id"]
            block=candidate_block_by_id.get(visual_id)
            if visual_id not in visual_by_id or block is None:
                raise ValueError(f"image_observation references missing lesson visual {visual_id}")
            question["visual_asset_ref"]=visual_id
            question["alt_by_locale"]=block["asset"]["alt_by_locale"]
            question["caption_by_locale"]=block["asset"]["caption_by_locale"]
            question["renderability"]={"status":"BLOCKED_FROM_STUDENT_RENDERING",
                "reason":"Candidate visual is unapproved; render only after exact-asset visual, rights, language and accessibility approval."}
        questions.append(question)
    return {"schema_version":"quantaedge.structured-lesson.v1","artifact_type":"draft_lesson","artifact_id":aid,
      "workflow":{"status":"DRAFT_REQUIRES_HUMAN_REVIEW","content_review_status":"NOT_REVIEWED","source_completeness_status":"SOURCE_LINKED_REQUIRES_REVIEW",
                  "visual_review_status":"NOT_REVIEWED","bilingual_parity_status":"MACHINE_CHECKED_REQUIRES_REVIEW","publication_allowed":False,
                  "human_review_required":True,"generation_model":"filled-by-run","source_pdf_sha256":source["source_pdf_sha256"],
                  "source_book_id":source["book_id"],"source_pages":seg["source_pages"]},
      "curriculum":{"class":source["class_no"],"subject":source["subject"],"class_code":str(source["class_no"]),"subject_code":source["subject"],"source_language":source["language"],"source_book_id":source["book_id"]},
      "chapter":{"chapter_number":v["chapter_number"],"title_by_locale":v["chapter_title_by_locale"],"source_page_start":min(seg["source_pages"]),"source_page_end":max(seg["source_pages"])},
      "topic":{"topic_number":seg["sequence"],"topic_id":topic_id,"title_by_locale":v["microtopic_title_by_locale"]},
      "learning_objectives":objectives,"source_refs":refs,"explain":{"title_by_locale":v["microtopic_title_by_locale"],"blocks":blocks},
      "practice":{"title_by_locale":{"en":"Check your understanding","hi":"अपनी समझ जाँचें"},"questions":questions,"question_count":len(questions)},
      "language_runtime_contract":{"supported_locales":["en","hi"],"default_locale":"en"},
      "generation_metadata":{"model_role":"EXPERIMENTAL_BENCHMARKED_CANDIDATE","prompt_version":PROMPT_VERSION,"segment_id":seg["segment_id"],"source_pages":seg["source_pages"],"layer5_coverage_ledger_path":source.get("layer5_ledger_path"),"layer5_coverage_ledger_sha256":source.get("layer5_ledger_sha256"),"layer5_visual_evidence_asset_ids":[x["asset_id"] for x in (layer5_visual_evidence or [])],"layer5_visual_evidence_count":len(layer5_visual_evidence or []),"publication_allowed":False,"human_review_required":True}}

def validate_lesson(x: dict[str,Any]) -> list[str]:
    e=[]; curriculum=x.get("curriculum",{})
    if not isinstance(curriculum,dict) or not isinstance(curriculum.get("class"),int) or isinstance(curriculum.get("class"),bool) or not 1<=curriculum["class"]<=12 or not isinstance(curriculum.get("subject"),str) or not re.fullmatch(r"[a-z][a-z0-9_-]{0,39}",curriculum["subject"]):
        e.append("runtime curriculum.class/subject contract invalid")
    objs=x.get("learning_objectives",[]); oids={o.get("id") for o in objs if isinstance(o,dict)}
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
        if b.get("type")=="recap":
            recap=b.get("content_by_locale")
            if not isinstance(recap,dict) or any(
                not isinstance(recap.get(l),dict) or
                not isinstance(recap[l].get("key_takeaways"),list) or
                not recap[l]["key_takeaways"] or
                any(not isinstance(p,str) or not p.strip() for p in recap[l]["key_takeaways"])
                for l in ("en","hi")
            ):
                e.append("recap block needs bilingual content_by_locale.key_takeaways")
            continue
        tx=b.get("text_by_locale")
        if not isinstance(tx,dict) or any(not isinstance(tx.get(l),dict) or not tx[l].get("paragraphs") for l in ("en","hi")): e.append("block bilingual text invalid")
    qs=x.get("practice",{}).get("questions",[])
    if not isinstance(qs,list) or not 1<=len(qs)<=6: e.append("question count must be between 1 and 6")
    visual_by_id={b.get("visual_id"):b for b in blocks if isinstance(b,dict) and b.get("type")=="image" and isinstance(b.get("visual_id"),str)}
    for q in qs:
        if not isinstance(q,dict): e.append("question invalid"); continue
        qtype=q.get("type")
        if not isinstance(qtype,str) or qtype not in QUESTION_TYPES or q.get("rendering_contract_id")!=RENDERER_IDS.get(qtype):
            e.append("question renderer contract invalid"); continue
        if not localized(q.get("prompt_by_locale")) or not localized(q.get("feedback_by_locale")): e.append("question locale gap")
        if any(r not in oids for r in q.get("objective_ids",[])) or any(r not in rids for r in q.get("source_refs",[])): e.append("question references invalid")
        if qtype in {"multiple_choice","multiple_select","scenario_based","image_observation"}:
            options=q.get("options",[]); ids={o.get("id") for o in options if isinstance(o,dict)}
            correct=q.get("correct_answer_ids")
            if len(ids)<2 or len(ids)!=len(options) or not isinstance(correct,list) or not correct or any(cid not in ids for cid in correct): e.append("question option/answer invalid")
            if qtype in {"multiple_choice","scenario_based","image_observation"} and (not isinstance(correct,list) or len(correct)!=1): e.append("single-select answer invalid")
            if qtype=="scenario_based" and not localized(q.get("scenario_by_locale")): e.append("scenario locale gap")
            if qtype=="image_observation":
                visual_ref=q.get("visual_asset_ref")
                if not isinstance(visual_ref,str) or visual_ref not in visual_by_id: e.append("image-observation visual reference invalid")
                if not localized(q.get("alt_by_locale")) or not localized(q.get("caption_by_locale")): e.append("image-observation alt/caption locale gap")
                renderability=q.get("renderability")
                if not isinstance(renderability,dict) or renderability.get("status")!="BLOCKED_FROM_STUDENT_RENDERING": e.append("unapproved image-observation question must remain blocked")
        elif qtype=="true_false":
            if not isinstance(q.get("correct_answer"),bool): e.append("true_false answer invalid")
        elif qtype=="matching":
            left=q.get("left_items",[]); right=q.get("right_items",[]); pairs=q.get("correct_pairs",[])
            left_ids={row.get("id") for row in left if isinstance(row,dict)}
            right_ids={row.get("id") for row in right if isinstance(row,dict)}
            if not _bilingual_items(left) or not _bilingual_items(right) or not isinstance(pairs,list) or len(pairs)!=len(left): e.append("matching contract invalid")
            elif any(not isinstance(pair,dict) or pair.get("left_id") not in left_ids or pair.get("right_id") not in right_ids for pair in pairs): e.append("matching references invalid")
        elif qtype=="fill_blank":
            if not _bilingual_list(q.get("accepted_answers")): e.append("fill_blank accepted answers invalid")
        elif qtype=="short_answer":
            guide=q.get("answer_guide_by_locale"); grading=q.get("grading")
            if not isinstance(guide,dict) or any(not isinstance(guide.get(l),dict) or not isinstance(guide[l].get("sample_answer"),str) or not guide[l]["sample_answer"].strip() or not isinstance(guide[l].get("key_points"),list) or not guide[l]["key_points"] for l in ("en","hi")): e.append("short-answer guide invalid")
            if not isinstance(grading,dict) or grading.get("mode")!="rubric" or not isinstance(grading.get("max_score"),int) or isinstance(grading.get("max_score"),bool) or grading["max_score"]<=0 or not localized(grading.get("rubric_by_locale")): e.append("short-answer grading invalid")
    w=x.get("workflow",{})
    if w.get("publication_allowed") is not False or w.get("human_review_required") is not True: e.append("publication gate invalid")
    return e

def sft_records(source:dict[str,Any],seg:dict[str,Any],v:dict[str,Any],model:str,
                layer5_visual_evidence:list[dict[str,Any]]|None=None)->list[dict[str,Any]]:
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
          "generation_model":model,"prompt_version":PROMPT_VERSION,
          "layer5_coverage_ledger_path":source.get("layer5_ledger_path"),
          "layer5_coverage_ledger_sha256":source.get("layer5_ledger_sha256"),
          "layer5_visual_evidence_asset_ids":[x["asset_id"] for x in (layer5_visual_evidence or [])],
          "layer5_visual_evidence_count":len(layer5_visual_evidence or []),
          "review_status":"machine_validated_draft",
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

def write_layer5_visual_evidence_candidates(path:Path,source:dict[str,Any],
                                          evidence_by_page:dict[int,list[dict[str,Any]]])->int:
    """Persist L5 visual observations as blocked review candidates, never as live retrieval docs."""
    rows=[]
    for page in sorted(evidence_by_page):
        for evidence in sorted(evidence_by_page[page],key=lambda item:item["asset_id"]):
            asset_id=str(evidence["asset_id"])
            pieces=[f"Unapproved machine-generated visual analysis for source PDF page {page}."]
            if evidence.get("visual_type"): pieces.append("Visual type candidate: "+str(evidence["visual_type"]))
            if evidence.get("visual_summary"): pieces.append("Visual summary candidate (requires review): "+str(evidence["visual_summary"]))
            visible=[str(x).strip() for x in evidence.get("visible_text",[]) if isinstance(x,str) and x.strip()]
            concepts=[str(x).strip() for x in evidence.get("concepts",[]) if isinstance(x,str) and x.strip()]
            limitations=[str(x).strip() for x in evidence.get("limitations",[]) if isinstance(x,str) and x.strip()]
            if visible: pieces.append("Visible text candidates: "+"; ".join(visible))
            if concepts: pieces.append("Concept candidates: "+"; ".join(concepts))
            if evidence.get("context_relation"): pieces.append("Context relation candidate: "+str(evidence["context_relation"]))
            if limitations: pieces.append("Known limitations; verify before use: "+"; ".join(limitations))
            text_value="\n".join(pieces)
            metadata={
                "source_book_id":source["book_id"],"source_title":source["title"],
                "source_url":source["source_url"],"source_edition":source["edition"],
                "source_pdf_sha256":source["source_pdf_sha256"],"source_pdf_page_number":page,
                "source_asset_sha256":evidence["source_asset_sha256"],"layer5_visual_asset_id":asset_id,
                "layer5_coverage_ledger_path":source.get("layer5_ledger_path"),
                "layer5_coverage_ledger_sha256":source.get("layer5_ledger_sha256"),
                "visual_type_candidate":evidence.get("visual_type"),"correction_status":evidence.get("correction_status","NONE"),
                "review_status":"MACHINE_GENERATED_REQUIRES_HUMAN_REVIEW",
                "human_review_required":True,"publication_allowed":False,"eligible_for_retrieval":False
            }
            document_id="qe-vis-"+sha(source["book_id"]+"|"+source["source_pdf_sha256"]+"|"+asset_id+"|"+str(evidence["source_asset_sha256"]))[:24]
            rows.append({"schema_version":"quantaedge.ncert-visual-evidence-candidate.v1","document_id":document_id,
                "text":text_value,"text_sha256":sha(text_value),"metadata":metadata,
                "human_review_required":True,"publication_allowed":False,"eligible_for_retrieval":False})
    write_jsonl(path,rows)
    return len(rows)


def write_outputs(source:dict[str,Any],segments:list[dict[str,Any]],checkpoint:dict[str,Any],outdir:Path,rag:Path,model:str,bench_sha:str,
                  layer5_visual_evidence_by_page:dict[int,list[dict[str,Any]]]|None=None)->dict[str,Any]:
    good=[(s,checkpoint[s["segment_id"]]) for s in segments if checkpoint.get(s["segment_id"],{}).get("status")=="COMPLETE"]
    topics=[]; lessons=[]; sft=[]
    for seg,item in good:
        v=item["generated"]; chapter=v.get("chapter_number")
        topics.append({"segment_id":seg["segment_id"],"source_pages":seg["source_pages"],"segment_sha256":seg["segment_sha256"],
          "layer5_coverage_ledger_path":source.get("layer5_ledger_path"),"layer5_coverage_ledger_sha256":item.get("layer5_ledger_sha256"),
          "layer5_visual_evidence_asset_ids":item.get("layer5_visual_evidence_asset_ids",[]),
          "layer5_visual_evidence_count":len(item.get("layer5_visual_evidence_asset_ids",[])),
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
    nonempty_source_pages={p["page_number"] for p in source["pages"] if p["text"].strip()}
    mapped={p for t in topics if t["mapping_status"]=="DRAFT_MAPPED_REQUIRES_REVIEW"
            for p in t["source_pages"] if p in nonempty_source_pages}
    unresolved=sorted(set(range(1,source["page_count"]+1))-mapped)
    unresolved_chapters=sorted(t["segment_id"] for t in topics if t["mapping_status"]!="DRAFT_MAPPED_REQUIRES_REVIEW")
    unresolved_text=sorted(s["segment_id"] for s in segments if checkpoint.get(s["segment_id"],{}).get("status")=="UNRESOLVED_SOURCE_TEXT")
    unresolved_text_pages=sorted(p["page_number"] for p in source["pages"] if not p["text"].strip())
    pending=[s["segment_id"] for s in segments if checkpoint.get(s["segment_id"],{}).get("status") not in {"COMPLETE","UNRESOLVED_SOURCE_TEXT"}]
    incomplete_map=bool(unresolved or unresolved_chapters or unresolved_text or unresolved_text_pages)
    mp=outdir/"curriculum-map.v1.json"
    mapping={"schema_version":"quantaedge.curriculum-map-candidate.v1",
      "source":{"class_code":str(source["class_no"]),"subject_code":source["subject"],"language":source["language"],
        "source_book_id":source["book_id"],"source_title":source["title"],"source_url":source["source_url"],
        "source_edition":source["edition"],"source_pdf_sha256":source["source_pdf_sha256"],"pdf_page_count":source["page_count"],
        "layer5_coverage_ledger_path":source.get("layer5_ledger_path"),"layer5_coverage_ledger_sha256":source.get("layer5_ledger_sha256"),
        "layer5_visual_evidence_available_count":source.get("layer5_visual_evidence_count",0)},
      "prompt_version":PROMPT_VERSION,"generation_model":model,"segment_count":len(segments),"segments_completed":len(good),
      "pending_segments":pending,"unresolved_chapter_segments":unresolved_chapters,"unresolved_text_segments":unresolved_text,"unresolved_text_pages":unresolved_text_pages,
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
    visual_evidence_candidate_path=outdir/"layer-8-visual-evidence-candidates.v1.jsonl"
    visual_evidence_candidate_count=write_layer5_visual_evidence_candidates(
        visual_evidence_candidate_path,source,layer5_visual_evidence_by_page or {})
    mapped_segments={t["segment_id"] for t in topics}
    lesson_segments={l.get("generation_metadata",{}).get("segment_id") for l in lessons}
    missing_sft=any(len(checkpoint.get(seg["segment_id"],{}).get("sft_rows",[]))<2 for seg in segments)
    layers={"6":mapping["status"],
      "7":"IN_PROGRESS" if pending else ("INCOMPLETE" if unresolved_chapters or unresolved_text or unresolved_text_pages or mapped_segments!=lesson_segments else "DRAFT_REQUIRES_HUMAN_REVIEW"),
      "8":"IN_PROGRESS" if pending else ("INCOMPLETE" if missing_sft else "DRAFT_REQUIRES_HUMAN_REVIEW")}
    overall="IN_PROGRESS" if pending else ("INCOMPLETE" if any(v=="INCOMPLETE" for v in layers.values()) else "DRAFT_REQUIRES_HUMAN_REVIEW")
    report={"schema_version":"quantaedge.layers-6-8-run-manifest.v1","source_book_id":source["book_id"],
      "source_pdf_sha256":source["source_pdf_sha256"],"class_code":str(source["class_no"]),"subject_code":source["subject"],
      "language":source["language"],"model":model,"model_role":"EXPERIMENTAL_BENCHMARKED_CANDIDATE",
      "benchmark_report_sha256":bench_sha,"prompt_version":PROMPT_VERSION,"lesson_contract_version":LESSON_CONTRACT_VERSION,"input_fingerprint":source.get("pipeline_input_fingerprint"),"visual_candidate_fingerprint":source.get("visual_candidate_fingerprint"),
      "layer5_coverage_ledger_path":source.get("layer5_ledger_path"),"layer5_coverage_ledger_sha256":source.get("layer5_ledger_sha256"),
      "layer5_visual_evidence_available_count":source.get("layer5_visual_evidence_count",0),
      "layer5_visual_evidence_asset_ids":sorted({aid for _,item in good for aid in item.get("layer5_visual_evidence_asset_ids",[])}),
      "layer5_visual_evidence_consumed_count":len({aid for _,item in good for aid in item.get("layer5_visual_evidence_asset_ids",[])}),"segments_total":len(segments),
      "segments_completed":len(good),"layers":layers,"overall_status":overall,"lesson_candidates":len(lessons),
      "tutor_sft_candidates":len(sft),"visual_candidates_attached_for_human_review":sum(1 for lesson in lessons for block in lesson.get("explain",{}).get("blocks",[]) if block.get("type")=="image"),
      "layer5_visual_evidence_candidate_path":visual_evidence_candidate_path.name,
      "layer5_visual_evidence_candidate_documents":visual_evidence_candidate_count,
      "layer5_visual_evidence_retrieval_eligible":False,"retrieval_documents":source["page_count"],
      "retrieval_nonempty_documents":sum(p["has_text"] for p in source["pages"]),"source_pages_mapped":len(mapped),
      "source_page_count":source["page_count"],"unresolved_source_pages":unresolved,"unresolved_text_pages":unresolved_text_pages,"unresolved_chapter_segments":unresolved_chapters,"unresolved_text_segments":unresolved_text,"pending_segments":pending,
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
    outside_data=[item for item in items if item!="data" and not item.startswith("data/")]
    if outside_data: raise ValueError(f"Generated Layers 6-8 checkpoints may only be committed under data/: {outside_data}")
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
    report=read_json(path)
    if report.get("schema_version")!="quantaedge.local-ollama-benchmark.v1":
        raise ValueError("Unsupported local Ollama benchmark report schema.")
    row=next((r for r in report.get("models",[]) if r.get("model")==model),None)
    if not row or row.get("successful_samples",0)<1 or row.get("lesson_contract_valid_rate",0)<=0:
        raise ValueError(f"Model {model} has no successful, contract-valid Class 6 NCERT benchmark sample.")
    if report.get("prompt_version")!=PROMPT_VERSION:
        raise ValueError("Benchmark prompt version is stale; rerun benchmark before generation.")
    if report.get("lesson_contract_version")!=LESSON_CONTRACT_VERSION:
        raise ValueError("Benchmark lesson-renderer contract is stale; run refresh-benchmark on its saved raw samples before generation.")
    if report.get("production_model_selection")!="PENDING_HUMAN_REVIEW" or report.get("production_model") is not None:
        raise ValueError("Benchmark report must keep production selection pending human review.")
    # Benchmark identity is intentionally anchored to Class 6 English Science;
    # the target book's own Layer 5 ledger is validated separately by run().
    bench_source=load_source(repo,6,"ncert-c6-english-fecu1")
    if bench_source["subject"]!="science" or bench_source["language"]!="en":
        raise ValueError("The benchmark anchor must be Class 6 English NCERT Science (Curiosity).")
    source_record=report.get("source") if isinstance(report.get("source"),dict) else {}
    if (source_record.get("book_id")!=bench_source["book_id"] or
        str(source_record.get("source_pdf_sha256") or "").lower()!=bench_source["source_pdf_sha256"]):
        raise ValueError("Benchmark report does not match the current Class 6 Curiosity PDF edition.")
    bench_ledger_path,_,_=load_layer5_evidence(repo,bench_source)
    bench_ledger_sha=sha_file(bench_ledger_path)
    if source_record.get("layer5_coverage_ledger_sha256")!=bench_ledger_sha:
        raise ValueError("Benchmark was built against an older Layer 5 ledger; rerun benchmark inference.")
    bench_segments=segments_for(bench_source["pages"],2,5000)
    _,bench_visual_fingerprint=load_visual_candidates(repo,bench_source,bench_segments)
    if report.get("visual_candidate_fingerprint")!=bench_visual_fingerprint:
        raise ValueError("Benchmark visual candidates changed since inference; rerun benchmark against current inputs.")
    return report

def run(args:argparse.Namespace,opener:Any=None)->dict[str,Any]:
    repo=args.repo_root.resolve(); source=load_source(repo,args.class_no,args.source_book_id)
    ledger_path,ledger,layer5_by_page=load_layer5_evidence(repo,source)
    layer5_sha=sha_file(ledger_path)
    source["layer5_ledger_path"]=ledger_path.relative_to(repo).as_posix()
    source["layer5_ledger_sha256"]=layer5_sha
    source["layer5_visual_evidence_count"]=sum(len(rows) for rows in layer5_by_page.values())
    report=benchmark_report(repo,args.benchmark_report,args.model)
    segments=segments_for(source["pages"],args.max_pages_per_segment,args.max_context_chars)
    visuals_by_segment,visual_fingerprint=load_visual_candidates(repo,source,segments)
    visuals_by_segment=bind_layer5_evidence_to_visual_candidates(visuals_by_segment,layer5_by_page)
    source["visual_candidate_fingerprint"]=visual_fingerprint
    fingerprint=sha(json.dumps({"source_pdf_sha256":source["source_pdf_sha256"],"visual_fingerprint":visual_fingerprint,
      "manifest_sha256":sha_file(source["manifest_path"]),"pages_sha256":sha_file(source["text_path"]),
      "layer5_coverage_ledger_sha256":layer5_sha,"benchmark_report_sha256":sha(json.dumps(report,sort_keys=True)),
      "model":args.model,"prompt":PROMPT_VERSION,"lesson_contract_version":LESSON_CONTRACT_VERSION,"max_pages_per_segment":args.max_pages_per_segment,
      "max_context_chars":args.max_context_chars,"generation_parameters":{"temperature":0.1,"num_ctx":8192,"num_predict":3072}},sort_keys=True))
    source["pipeline_input_fingerprint"]=fingerprint
    slug=re.sub(r"[^A-Za-z0-9._-]","-",args.model)
    outdir=ledger_path.parent/"layers-6-8"/f"{slug}-sha{source['source_pdf_sha256'][:8]}-v5"
    cpfile=outdir/"generation-checkpoints.v1.json"; rag=ledger_path.parent/"layer-8-rag-corpus.v1.jsonl"
    if cpfile.exists():
        doc=read_json(cpfile)
        if doc.get("input_fingerprint")!=fingerprint: raise ValueError("Resume fingerprint mismatch (edition/text/Layer5/model/prompt changed); existing checkpoint preserved.")
        checkpoints=doc.get("segments",{})
        if not isinstance(checkpoints,dict): raise ValueError("Invalid checkpoint segments.")
    else:
        checkpoints={}; doc={"schema_version":"quantaedge.layers-6-8-checkpoint.v1","source_book_id":source["book_id"],
          "source_pdf_sha256":source["source_pdf_sha256"],"model":args.model,"prompt_version":PROMPT_VERSION,"lesson_contract_version":LESSON_CONTRACT_VERSION,
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
        segment_layer5=layer5_evidence_for_segment(layer5_by_page,seg)
        segment_layer5_ids=[row["asset_id"] for row in segment_layer5]
        try:
            value,elapsed=ollama_chat(args.endpoint,args.model,prompt_for(source,seg,visuals_by_segment.get(seg["segment_id"],[]),segment_layer5),args.request_timeout,opener)
            errors=validate_output(value,set(seg["source_pages"]),visuals_by_segment.get(seg["segment_id"],[]))
            if errors:
                checkpoints[seg["segment_id"]]={"status":"ERROR","segment_sha256":seg["segment_sha256"],
                  "attempts":int((prior or {}).get("attempts",0))+1,"layer5_ledger_sha256":layer5_sha,
                  "layer5_visual_evidence_asset_ids":segment_layer5_ids,"error":"; ".join(errors[:12]),"elapsed_seconds":round(elapsed,2)}
            else:
                lesson=lesson_from(source,seg,value,visuals_by_segment.get(seg["segment_id"],[]),segment_layer5)
                if lesson:
                    lesson["workflow"]["generation_model"]=args.model
                    lesson_errors=validate_lesson(lesson)
                    if lesson_errors: raise ValueError("Lesson contract: "+"; ".join(lesson_errors[:12]))
                records=sft_records(source,seg,value,args.model,segment_layer5)
                checkpoints[seg["segment_id"]]={"status":"COMPLETE","segment_sha256":seg["segment_sha256"],
                  "generated":value,"lesson":lesson,"sft_rows":records,"layer5_ledger_sha256":layer5_sha,
                  "layer5_visual_evidence_asset_ids":segment_layer5_ids,"elapsed_seconds":round(elapsed,2),
                  "mapping_status":"MAPPED" if value.get("chapter_number") is not None else "UNRESOLVED_CHAPTER"}
        except (OSError,ValueError,RuntimeError,urllib.error.URLError) as exc:
            checkpoints[seg["segment_id"]]={"status":"ERROR","segment_sha256":seg["segment_sha256"],
              "attempts":int((prior or {}).get("attempts",0))+1,"layer5_ledger_sha256":layer5_sha,
              "layer5_visual_evidence_asset_ids":segment_layer5_ids,"error":str(exc)[:1200]}
        doc["segments"]=checkpoints; write_json(cpfile,doc)
        if calls%args.checkpoint_every==0 or calls==args.max_model_calls:
            current=write_outputs(source,segments,checkpoints,outdir,rag,args.model,sha(json.dumps(report,sort_keys=True)),layer5_by_page)
            if args.commit_checkpoints: checkpoint_git(repo,[outdir,rag],f"checkpoint(content): L6-8 {source['book_id']} ({calls} calls)")
            print(json.dumps({"book_id":source["book_id"],"model":args.model,"calls_this_run":calls,
              "segments_completed":current["segments_completed"],"segments_total":current["segments_total"],"status":current["overall_status"]}))
    doc["segments"]=checkpoints; write_json(cpfile,doc)
    result=write_outputs(source,segments,checkpoints,outdir,rag,args.model,sha(json.dumps(report,sort_keys=True)),layer5_by_page)
    result["model_calls_this_run"]=calls; result["model_calls_budgeted_per_invocation"]=args.max_model_calls
    write_json(outdir/"layers-6-8-run-manifest.v1.json",result)
    if args.commit_checkpoints: checkpoint_git(repo,[outdir,rag],f"status(content): L6-8 {source['book_id']} ({result['overall_status']})")
    return result


def refresh_benchmark(args:argparse.Namespace)->dict[str,Any]:
    """Revalidate saved raw Ollama samples under the current lesson renderer contract, without model calls."""
    repo=args.repo_root.resolve()
    output=args.output.resolve()
    if not output.is_file():
        raise ValueError(f"Benchmark report missing: {output}")
    report=read_json(output)
    if report.get("schema_version")!="quantaedge.local-ollama-benchmark.v1":
        raise ValueError("Unsupported benchmark report schema.")
    if report.get("prompt_version")!=PROMPT_VERSION:
        raise ValueError("Benchmark prompt changed; new model inference is required.")
    source=load_source(repo,6,args.source_book_id)
    ledger_path,ledger,layer5_by_page=load_layer5_evidence(repo,source)
    layer5_sha=sha_file(ledger_path)
    source["layer5_ledger_path"]=ledger_path.relative_to(repo).as_posix()
    source["layer5_ledger_sha256"]=layer5_sha
    source["layer5_visual_evidence_count"]=sum(len(rows) for rows in layer5_by_page.values())
    source_report=report.get("source") or {}
    if source_report.get("book_id")!=source["book_id"] or str(source_report.get("source_pdf_sha256") or "").lower()!=source["source_pdf_sha256"]:
        raise ValueError("Benchmark source book/PDF SHA-256 mismatch.")
    if source_report.get("layer5_coverage_ledger_sha256")!=layer5_sha:
        raise ValueError("Layer 5 evidence changed since benchmark inference; rerun the benchmark.")
    segments=segments_for(source["pages"],2,5000)
    segments_by_id={segment["segment_id"]:segment for segment in segments}
    ids=source_report.get("sample_segment_ids")
    if not isinstance(ids,list) or not ids or any(segment_id not in segments_by_id for segment_id in ids):
        raise ValueError("Benchmark sample segments do not match current source segmentation.")
    selected=[segments_by_id[segment_id] for segment_id in ids]
    pages=sorted({page for segment in selected for page in segment["source_pages"]})
    if sorted(source_report.get("sample_source_pages") or [])!=pages:
        raise ValueError("Benchmark source page set changed; rerun inference.")
    visuals_by_segment,visual_fingerprint=load_visual_candidates(repo,source,selected)
    visuals_by_segment=bind_layer5_evidence_to_visual_candidates(visuals_by_segment,layer5_by_page)
    prior_contract=report.get("lesson_contract_version")
    if report.get("visual_candidate_fingerprint")!=visual_fingerprint:
        raise ValueError("Visual candidate pack changed since benchmark inference; rerun the benchmark.")
    if prior_contract==LESSON_CONTRACT_VERSION and report.get("visual_candidate_fingerprint")==visual_fingerprint:
        return {"status":"ALREADY_CURRENT","report":str(output),"lesson_contract_version":LESSON_CONTRACT_VERSION,
                "new_model_calls":0,"models":[{"model":m.get("model"),"successful_samples":m.get("successful_samples"),
                 "lesson_contract_valid_rate":m.get("lesson_contract_valid_rate")} for m in report.get("models",[])]}
    old_report_sha=sha_file(output)
    selected_by_id={segment["segment_id"]:segment for segment in selected}
    refreshed_metrics=[]
    models=report.get("models")
    if not isinstance(models,list) or not models:
        raise ValueError("Benchmark report contains no models.")
    for model_row in models:
        if not isinstance(model_row,dict) or not isinstance(model_row.get("model"),str):
            raise ValueError("Malformed benchmark model record.")
        samples=model_row.get("samples")
        if not isinstance(samples,list) or not samples:
            raise ValueError("No raw per-sample outputs available; rerun the model benchmark.")
        for sample in samples:
            segment_id=sample.get("segment_id")
            segment=selected_by_id.get(segment_id)
            if segment is None:
                raise ValueError(f"Unknown benchmark segment ID: {segment_id}")
            if sample.get("source_pages")!=segment["source_pages"]:
                raise ValueError(f"Benchmark source pages changed for {segment_id}; rerun inference.")
            value=sample.get("sample_output")
            if value is None:
                sample.update({"schema_valid":False,"lesson_contract_valid":False,"sft_examples_valid":False,
                    "source_page_citations_valid":False,"lesson_candidate_preview":None,
                    "lesson_contract_errors":sample.get("errors") or ["Original sample had no model response."]})
                continue
            if not isinstance(value,dict):
                raise ValueError(f"Raw model output is not a JSON object: {segment_id}")
            value_sha=sha(json.dumps(value,ensure_ascii=False,sort_keys=True))
            if sample.get("sample_output_sha256") and sample["sample_output_sha256"]!=value_sha:
                raise ValueError(f"Raw output hash mismatch: {model_row['model']} / {segment_id}")
            schema_errors=validate_output(value,set(segment["source_pages"]),visuals_by_segment.get(segment_id,[]))
            segment_layer5=layer5_evidence_for_segment(layer5_by_page,segment)
            lesson=lesson_from(source,segment,value,visuals_by_segment.get(segment_id,[]),segment_layer5) if not schema_errors else None
            lesson_errors=validate_lesson(lesson) if lesson is not None else (["unresolved chapter mapping"] if not schema_errors else [])
            lesson_valid=(not schema_errors and lesson is not None and not lesson_errors)
            citations_valid=tutor_citations_valid(value,set(segment["source_pages"]))
            sft_valid=(not schema_errors and len(sft_records(source,segment,value,model_row["model"],segment_layer5))>=2)
            sample.update({"schema_valid":not schema_errors,"lesson_contract_valid":lesson_valid,
                "sft_examples_valid":sft_valid,"source_page_citations_valid":citations_valid,
                "sample_output_sha256":value_sha,"lesson_candidate_preview":lesson,
                "lesson_contract_errors":lesson_errors[:12],"errors":(schema_errors+lesson_errors)[:12]})
        count=len(samples)
        schema_ok=sum(bool(sample.get("schema_valid")) for sample in samples)
        lesson_ok=sum(bool(sample.get("lesson_contract_valid")) for sample in samples)
        sft_ok=sum(bool(sample.get("sft_examples_valid")) for sample in samples)
        citation_ok=sum(bool(sample.get("source_page_citations_valid")) for sample in samples)
        model_row.update({"samples_attempted":count,"successful_samples":schema_ok,
            "schema_valid_rate":round(schema_ok/count,3),"lesson_contract_valid_rate":round(lesson_ok/count,3),
            "sft_examples_valid_rate":round(sft_ok/count,3),"source_page_citation_valid_rate":round(citation_ok/count,3)})
        refreshed_metrics.append({"model":model_row["model"],"successful_samples":schema_ok,
            "schema_valid_rate":model_row["schema_valid_rate"],"lesson_contract_valid_rate":model_row["lesson_contract_valid_rate"],
            "sft_examples_valid_rate":model_row["sft_examples_valid_rate"],
            "source_page_citation_valid_rate":model_row["source_page_citation_valid_rate"]})
    if report.get("production_model_selection")!="PENDING_HUMAN_REVIEW" or report.get("production_model") is not None:
        raise ValueError("Refusing to revalidate a report that already selected a production model.")
    report["lesson_contract_version"]=LESSON_CONTRACT_VERSION
    report["visual_candidate_fingerprint"]=visual_fingerprint
    report["source"]["layer5_coverage_ledger_path"]=source["layer5_ledger_path"]
    report["source"]["layer5_coverage_ledger_sha256"]=layer5_sha
    report["source"]["layer5_visual_evidence_count"]=source["layer5_visual_evidence_count"]
    report["benchmark_revalidation"]={"reason":"lesson-renderer-contract-refresh","previous_contract_version":prior_contract,
        "previous_report_sha256":old_report_sha,"revalidated_utc":time.strftime("%Y-%m-%dT%H:%M:%SZ",time.gmtime()),
        "new_model_calls":0,"source_pdf_sha256":source["source_pdf_sha256"]}
    write_json(output,report)
    if args.commit_checkpoints:
        checkpoint_git(repo,[output],"review(ai): revalidate Ollama benchmark lesson contract")
    return {"status":"REFRESHED","report":str(output),"lesson_contract_version":LESSON_CONTRACT_VERSION,
        "new_model_calls":0,"models":refreshed_metrics,"source_pdf_sha256":source["source_pdf_sha256"]}


def benchmark(args:argparse.Namespace,opener:Any=None)->dict[str,Any]:
    repo=args.repo_root.resolve(); source=load_source(repo,6,args.source_book_id)
    if source["subject"]!="science" or source["language"]!="en": raise ValueError("Benchmark must use Class 6 NCERT English Science (Curiosity).")
    ledger_path,ledger,layer5_by_page=load_layer5_evidence(repo,source)
    layer5_sha=sha_file(ledger_path)
    source["layer5_ledger_path"]=ledger_path.relative_to(repo).as_posix()
    source["layer5_ledger_sha256"]=layer5_sha
    source["layer5_visual_evidence_count"]=sum(len(rows) for rows in layer5_by_page.values())
    segments=segments_for(source["pages"],2,5000)
    usable=[segment for segment in segments if any(piece["text"].strip() for piece in segment["pieces"])]
    if not usable: raise ValueError("NCERT benchmark source has no extractable text segments.")
    indices=sorted(set((0,len(usable)//2,len(usable)-1)))[:args.sample_segments]
    samplesegs=[usable[i] for i in indices]; visuals_by_segment,visual_fingerprint=load_visual_candidates(repo,source,samplesegs)
    visuals_by_segment=bind_layer5_evidence_to_visual_candidates(visuals_by_segment,layer5_by_page); results=[]
    models=[x.strip() for x in args.models.split(",") if x.strip()]
    if not models or len(models)>4 or len(set(models))!=len(models): raise ValueError("Provide 1–4 unique benchmark models.")
    for model in models:
        samples=[]
        for seg in samplesegs:
            start=time.monotonic()
            try:
                value,elapsed=ollama_chat(args.endpoint,model,prompt_for(source,seg,visuals_by_segment.get(seg["segment_id"],[]),layer5_evidence_for_segment(layer5_by_page,seg)),args.request_timeout,opener)
                errs=validate_output(value,set(seg["source_pages"]),visuals_by_segment.get(seg["segment_id"],[]))
                lesson=lesson_from(source,seg,value,visuals_by_segment.get(seg["segment_id"],[]),layer5_evidence_for_segment(layer5_by_page,seg)) if not errs else None
                lesson_errors=validate_lesson(lesson) if lesson is not None else (["unresolved chapter mapping"] if not errs else [])
                lesson_valid=(not errs and lesson is not None and not lesson_errors)
                citations_valid=tutor_citations_valid(value,set(seg["source_pages"]))
                segment_layer5=layer5_evidence_for_segment(layer5_by_page,seg)
                sft_ok=not errs and len(sft_records(source,seg,value,model,segment_layer5))>=2
                sample_output_sha=sha(json.dumps(value,ensure_ascii=False,sort_keys=True))
                samples.append({"segment_id":seg["segment_id"],"source_pages":seg["source_pages"],"seconds":round(elapsed,2),
                  "response_chars":len(json.dumps(value,ensure_ascii=False)),
                  "schema_valid":not errs,"lesson_contract_valid":lesson_valid,"sft_examples_valid":sft_ok,
                  "source_page_citations_valid":citations_valid,"sample_output_sha256":sample_output_sha,
                  "sample_output":value,"lesson_candidate_preview":lesson,"lesson_contract_errors":lesson_errors[:10],
                  "errors":(errs+lesson_errors)[:12]})
            except (OSError,ValueError,RuntimeError,urllib.error.URLError) as exc:
                samples.append({"segment_id":seg["segment_id"],"source_pages":seg["source_pages"],"seconds":round(time.monotonic()-start,2),
                  "schema_valid":False,"lesson_contract_valid":False,"sft_examples_valid":False,
                  "source_page_citations_valid":False,"sample_output":None,"lesson_candidate_preview":None,
                  "lesson_contract_errors":[str(exc)[:1200]],"errors":[str(exc)[:1200]]})
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
        "source_pdf_sha256":source["source_pdf_sha256"],"layer5_coverage_ledger_path":source["layer5_ledger_path"],
        "layer5_coverage_ledger_sha256":layer5_sha,"layer5_visual_evidence_count":source["layer5_visual_evidence_count"],
        "sample_segment_ids":[s["segment_id"] for s in samplesegs],
        "sample_source_pages":sorted({p for s in samplesegs for p in s["source_pages"]})},
      "endpoint_mode":"native local Ollama /api/chat; no hosted API or API key","prompt_version":PROMPT_VERSION,"lesson_contract_version":LESSON_CONTRACT_VERSION,"visual_candidate_fingerprint":visual_fingerprint,"models":results,
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
    r.add_argument("--max-model-calls",type=int,default=6); r.add_argument("--max-pages-per-segment",type=int,default=2); r.add_argument("--max-context-chars",type=int,default=5000)
    r.add_argument("--request-timeout",type=int,default=300); r.add_argument("--checkpoint-every",type=int,default=2); r.add_argument("--benchmark-report",type=Path)
    r.add_argument("--allow-experimental-model",action="store_true"); r.add_argument("--commit-checkpoints",action="store_true")
    b=sub.add_parser("benchmark"); b.add_argument("--repo-root",type=Path,default=Path.cwd()); b.add_argument("--source-book-id",default="ncert-c6-english-fecu1")
    b.add_argument("--endpoint",default="http://127.0.0.1:11434"); b.add_argument("--models",default="qwen2.5:1.5b,qwen2.5:3b")
    b.add_argument("--sample-segments",type=int,default=2); b.add_argument("--request-timeout",type=int,default=300)
    b.add_argument("--output",type=Path,default=Path("data/textbook-content-pipeline/model-benchmarks/class-6-ncert-curiosity.v1.json")); b.add_argument("--commit-checkpoints",action="store_true")
    refresh=sub.add_parser("refresh-benchmark",help="Revalidate saved raw samples against the current lesson renderer contract without model calls.")
    refresh.add_argument("--repo-root",type=Path,default=Path.cwd()); refresh.add_argument("--source-book-id",default="ncert-c6-english-fecu1")
    refresh.add_argument("--output",type=Path,default=Path("data/textbook-content-pipeline/model-benchmarks/class-6-ncert-curiosity.v1.json"))
    refresh.add_argument("--commit-checkpoints",action="store_true")
    a=p.parse_args(argv)
    try:
        if a.mode=="refresh-benchmark":
            result=refresh_benchmark(a)
        elif a.mode=="benchmark":
            if not 1<=a.sample_segments<=2: raise ValueError("sample-segments must be 1..2 for the bounded CPU benchmark.")
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
