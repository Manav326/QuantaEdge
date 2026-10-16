#!/usr/bin/env python3
"""QuantaEdge resumable visual pipeline orchestration and Layer 5 evidence ledger."""
from __future__ import annotations
import argparse, copy, hashlib, json, re, shutil, subprocess, sys, tarfile
from pathlib import Path

KINDS={"embedded_image","page_preview"}
PATCH_FIELDS={"visual_summary","visual_type","visible_text","concepts","context_relation","limitations"}
VISUAL_TYPES={"diagram","chart","graph","table","map","cartoon","photo","illustration","equation","page_with_multiple_visuals","other","uncertain"}

def read_json(path):
    try:return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError,json.JSONDecodeError) as exc:raise ValueError(f"Cannot read {path}: {exc}") from exc
def write_json(path,data):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True);tmp=path.with_suffix(path.suffix+".tmp")
    tmp.write_text(json.dumps(data,ensure_ascii=False,indent=2)+"\n",encoding="utf-8");tmp.replace(path)
def rel(path,root):
    try:return Path(path).resolve().relative_to(Path(root).resolve()).as_posix()
    except ValueError:raise ValueError(f"Path {path} is outside repo root {root}.")
def page_no(row):
    for key in ("source_pdf_page_number","source_book_page_number","page_number"):
        v=row.get(key)
        if isinstance(v,int) and not isinstance(v,bool) and v>0:return v
    return None

def build_coverage_ledger(inventory_path,raw_path,manifest_path,text_path,effective_path,ledger_path,*,corrections_path=None,repo_root=None):
    root=Path(repo_root or Path.cwd()).resolve()
    inv,raw,man=read_json(inventory_path),read_json(raw_path),read_json(manifest_path)
    if inv.get("schema_version")!="quantaedge.visual-inventory.v1" or raw.get("schema_version")!="quantaedge.visual-analysis.v1":
        raise ValueError("Expected visual-inventory.v1 and visual-analysis.v1.")
    src=inv.get("source") or {};bid=src.get("book_id");sha=str(src.get("pdf_sha256") or "").lower()
    msha=str(man.get("original_book_sha256") or man.get("asset_pdf_sha256") or man.get("source_sha256") or "").lower()
    if not bid or len(sha)!=64 or man.get("book_id")!=bid or msha!=sha:raise ValueError("Manifest identity/PDF hash mismatch.")
    if raw.get("source_book_id")!=bid or str(raw.get("source_pdf_sha256") or "").lower()!=sha:raise ValueError("Raw analysis identity/PDF hash mismatch.")
    pages,assets,count=inv.get("pages"),inv.get("assets"),src.get("pdf_page_count")
    if not isinstance(pages,list) or not isinstance(assets,list) or not isinstance(count,int) or count<1:raise ValueError("Inventory pages/assets/page count invalid.")
    texts={}
    for line_no,line in enumerate(Path(text_path).read_text(encoding="utf-8").splitlines(),1):
        if not line.strip():continue
        try:row=json.loads(line)
        except json.JSONDecodeError as exc:raise ValueError(f"Malformed text JSONL line {line_no}: {exc}") from exc
        if not isinstance(row,dict) or page_no(row) is None:raise ValueError(f"Text JSONL line {line_no} missing page number.")
        texts.setdefault(page_no(row),[]).append(row)
    invpages={}
    for row in pages:
        if not isinstance(row,dict) or page_no(row) is None:raise ValueError("Invalid inventory page record.")
        invpages.setdefault(page_no(row),[]).append(row)
    invassets={}
    for i,row in enumerate(assets):
        if not isinstance(row,dict):raise ValueError(f"Inventory asset row {i} is invalid.")
        if row.get("asset_id"):
            if row["asset_id"] in invassets:raise ValueError(f"Duplicate inventory asset_id {row['asset_id']}.")
            invassets[row["asset_id"]]=row
    results=raw.get("results")
    if not isinstance(results,list):raise ValueError("Raw analysis results must be a list.")
    rawby={}
    for row in results:
        if not isinstance(row,dict) or not row.get("asset_id") or row["asset_id"] in rawby:raise ValueError("Raw analysis row has missing/duplicate asset_id.")
        rawby[row["asset_id"]]=row
    accepted={}
    if corrections_path and Path(corrections_path).is_file():
        overlay=read_json(corrections_path)
        if overlay.get("schema_version")!="quantaedge.visual-analysis-corrections.v1" or str(overlay.get("source_pdf_sha256") or "").lower()!=sha:
            raise ValueError("Correction overlay schema or source PDF hash mismatch.")
        corr=overlay.get("corrections")
        if not isinstance(corr,list):raise ValueError("Correction overlay must contain a corrections list.")
        seen=set()
        for c in corr:
            if not isinstance(c,dict) or not isinstance(c.get("asset_id"),str) or c["asset_id"] in seen:raise ValueError("Correction row has missing/duplicate asset_id.")
            aid=c["asset_id"];seen.add(aid)
            if aid not in invassets or c.get("asset_sha256")!=invassets[aid].get("asset_sha256"):raise ValueError(f"Correction {aid}: asset identity/hash mismatch.")
            if c.get("status") not in {"PROPOSED","ACCEPTED","REJECTED"}:raise ValueError(f"Correction {aid}: invalid status.")
            if c["status"]!="ACCEPTED":continue
            if not str(c.get("reviewer") or "").strip() or not str(c.get("reason") or "").strip():raise ValueError(f"Accepted correction {aid} needs reviewer and reason.")
            base=rawby.get(aid,{})
            if base.get("analysis_status")!="DRAFT_REQUIRES_REVIEW" or not isinstance(base.get("analysis"),dict):raise ValueError(f"Accepted correction {aid} requires successful raw analysis.")
            patch=c.get("analysis_patch")
            if not isinstance(patch,dict) or not patch or set(patch)-PATCH_FIELDS:raise ValueError(f"Correction {aid}: unsupported/empty analysis_patch.")
            merged=dict(base["analysis"]);merged.update(patch)
            valid=(isinstance(merged.get("visual_summary"),str) and bool(merged["visual_summary"].strip())
                and merged.get("visual_type") in VISUAL_TYPES and isinstance(merged.get("visible_text"),list)
                and all(isinstance(x,str) for x in merged["visible_text"]) and isinstance(merged.get("concepts"),list)
                and all(isinstance(x,str) for x in merged["concepts"]) and isinstance(merged.get("context_relation"),str)
                and isinstance(merged.get("limitations"),list) and all(isinstance(x,str) for x in merged["limitations"]))
            if not valid:raise ValueError(f"Correction {aid}: invalid effective analysis schema.")
            accepted[aid]={"analysis":merged,"reviewer":c["reviewer"].strip(),"reason":c["reason"].strip(),"accepted_at":c.get("accepted_at")}
    eff=[]
    for aid,row in rawby.items():
        out=copy.deepcopy(row);out["effective_analysis"]=copy.deepcopy(row.get("analysis")) if row.get("analysis_status")=="DRAFT_REQUIRES_REVIEW" else None;out["correction_status"]="NONE"
        if aid in accepted:
            out["effective_analysis"]=accepted[aid]["analysis"];out["correction_status"]="ACCEPTED"
            out["correction_review"]={k:accepted[aid][k] for k in ("reviewer","reason","accepted_at")}
        eff.append(out)
    ei={row["asset_id"]:row for row in eff}
    page_rows=[];page_bad=0
    for n in range(1,count+1):
        ps=invpages.get(n,[]);trs=texts.get(n,[]);txt=[]
        for t in trs:
            val=t.get("text") if isinstance(t.get("text"),str) else "";status=str(t.get("review_status") or t.get("text_quality_status") or "UNKNOWN")
            txt.append({"text_sha256":hashlib.sha256(val.encode()).hexdigest(),"character_count":len(val),"text_preview":val[:400],"extraction_method":t.get("extraction_method"),"extraction_status":status})
        usable=any(x["character_count"] and x["extraction_status"] not in {"NO_EXTRACTABLE_TEXT_REVIEW","EXTRACTION_ERROR"} for x in txt)
        state="COMPLETE" if ps and usable else ("INVENTORY_PAGE_MISSING" if not ps else ("TEXT_EXTRACTION_MISSING" if not trs else "TEXT_EXTRACTION_UNRESOLVED"))
        page_bad += state!="COMPLETE"
        page_rows.append({"source_pdf_page_number":n,"inventory_page_records":len(ps),"text_records":len(trs),"text_evidence":txt,"text_status":state,"visual_asset_ids":[aid for aid,r in invassets.items() if page_no(r)==n],"chapter_id":None,"topic_ids":[],"mapping_status":"NOT_STARTED","human_review_required":True})
    visual_rows=[];visual_bad=0;eligible=0
    for i,a in enumerate(assets):
        aid=a.get("asset_id") or f"MISSING_ASSET_ID_ROW_{i+1}";state="NOT_ELIGIBLE";ea=None;cs="NONE"
        if a.get("kind") in KINDS:
            eligible+=1;r=ei.get(aid)
            if r is None:state="ANALYSIS_MISSING"
            elif r.get("asset_sha256")!=a.get("asset_sha256"):state="ASSET_HASH_MISMATCH"
            elif r.get("analysis_status")=="ANALYSIS_ERROR":state="ANALYSIS_ERROR"
            elif r.get("analysis_status")=="DRAFT_REQUIRES_REVIEW" and isinstance(r.get("effective_analysis"),dict):state="ANALYZED_REQUIRES_HUMAN_REVIEW";ea=r["effective_analysis"];cs=r.get("correction_status","NONE")
            else:state="ANALYSIS_STATUS_UNKNOWN"
            visual_bad += state!="ANALYZED_REQUIRES_HUMAN_REVIEW"
        visual_rows.append({"asset_id":aid,"asset_kind":a.get("kind"),"asset_sha256":a.get("asset_sha256"),"source_pdf_sha256":a.get("source_pdf_sha256") or sha,"source_pdf_page_number":page_no(a),"asset_path":a.get("asset_path"),"media_type":a.get("media_type"),"bbox":a.get("bbox"),"analysis_status":state,"correction_status":cs,"effective_analysis":ea,"chapter_id":None,"topic_ids":[],"mapping_status":"NOT_STARTED","human_review_required":True,"publication_allowed":False})
    orphans=sorted(set(rawby)-{a.get("asset_id") for a in assets if isinstance(a.get("asset_id"),str) and a.get("kind") in KINDS})
    visual_bad+=len(orphans);unresolved=page_bad+visual_bad
    paths={"inventory":rel(inventory_path,root),"raw_analysis":rel(raw_path,root),"manifest":rel(manifest_path,root),"text_jsonl":rel(text_path,root),"corrections":rel(corrections_path,root) if corrections_path else None,"effective_analysis_output":rel(effective_path,root),"coverage_ledger_output":rel(ledger_path,root)}
    write_json(effective_path,{"schema_version":"quantaedge.visual-analysis-effective.v1","source_book_id":bid,"source_pdf_sha256":sha,"raw_analysis_path":paths["raw_analysis"],"raw_analysis_sha256":hashlib.sha256(Path(raw_path).read_bytes()).hexdigest(),"model":raw.get("model"),"prompt_version":raw.get("prompt_version"),"corrections_path":paths["corrections"],"corrections_applied_count":len(accepted),"results":eff,"human_review_required":True,"publication_allowed":False})
    out={"schema_version":"quantaedge.textbook-coverage-ledger.v1","architecture_version":"8-layer-resumable-v1","layer":5,"ledger_status":"COMPLETE" if unresolved==0 else "INCOMPLETE","source":{"class":src.get("class"),"language":src.get("language"),"subject":src.get("subject"),"source_book_id":bid,"source_pdf_sha256":sha,"edition":src.get("edition"),"pdf_page_count":count},"source_paths":paths,"summary":{"source_pdf_page_count":count,"inventory_page_records":len(pages),"page_text_records":sum(map(len,texts.values())),"pages_with_unresolved_text":page_bad,"inventory_asset_rows":len(assets),"eligible_visual_asset_rows":eligible,"raw_analysis_result_rows":len(results),"successful_visual_analyses":sum(x["analysis_status"]=="ANALYZED_REQUIRES_HUMAN_REVIEW" for x in visual_rows),"missing_visual_analyses":sum(x["analysis_status"]=="ANALYSIS_MISSING" for x in visual_rows),"failed_visual_analyses":sum(x["analysis_status"] in {"ANALYSIS_ERROR","ASSET_HASH_MISMATCH","ANALYSIS_STATUS_UNKNOWN"} for x in visual_rows),"orphan_analysis_results":len(orphans),"corrections_applied":len(accepted),"unresolved_count":unresolved,"mapping_status":"NOT_STARTED","analysis_model":raw.get("model"),"analysis_prompt_version":raw.get("prompt_version"),"publication_allowed":False},"pages":page_rows,"visuals":visual_rows,"orphan_analysis_asset_ids":orphans,"human_review_required":True,"publication_allowed":False}
    write_json(ledger_path,out);return out

def git_checkpoint(repo,paths,message):
    subprocess.run(["git","pull","--rebase","origin","main"],cwd=repo,check=True)
    subprocess.run(["git","add",*paths],cwd=repo,check=True)
    if subprocess.run(["git","diff","--cached","--quiet"],cwd=repo).returncode==0:return
    subprocess.run(["git","config","user.name","github-actions[bot]"],cwd=repo,check=True);subprocess.run(["git","config","user.email","41898282+github-actions[bot]@users.noreply.github.com"],cwd=repo,check=True)
    subprocess.run(["git","commit","-m",message],cwd=repo,check=True)
    try:subprocess.run(["git","push","origin","HEAD:main"],cwd=repo,check=True)
    except subprocess.CalledProcessError:subprocess.run(["git","pull","--rebase","origin","main"],cwd=repo,check=True);subprocess.run(["git","push","origin","HEAD:main"],cwd=repo,check=True)

def run_layers(args):
    repo=args.repo_root.resolve();sroot=args.source_root.resolve();selection=read_json(args.selection);model=re.sub(r"[^A-Za-z0-9._-]","-",args.model);bad=[]
    if args.batch_size<1:raise ValueError("batch-size must be positive; it is not a total cap.")
    for s in selection["sources"]:
        slug=s["book_slug"];work=sroot/slug;invdir=work/"inventory";inv=invdir/"visual-inventory.v1.json"
        if not inv.is_file():raise ValueError(f"{slug}: inventory missing; refusing omission.")
        inventory=read_json(inv)
        if inventory.get("source",{}).get("pdf_sha256")!=s.get("sha256"):raise ValueError(f"{slug}: source PDF hash mismatch.")
        name=s.get("text_jsonl_filename")
        if not name or not (work/name).is_file():raise ValueError(f"{slug}: extracted page JSONL missing.")
        for key in ("extraction_manifest_repo_path","text_jsonl_repo_path"):
            if not s.get(key):raise ValueError(f"{slug}: source selection missing {key}.")
        vis=repo/"data"/"textbook-visuals"/slug;layer=repo/"data"/"textbook-content-pipeline"/f"class-{selection['class']}"/"books"/slug
        vis.mkdir(parents=True,exist_ok=True);layer.mkdir(parents=True,exist_ok=True)
        invrepo=vis/"visual-inventory.v1.json";shutil.copy2(inv,invrepo);shutil.copy2(work/"source.manifest.json",vis/"source.manifest.json")
        raw=vis/f"visual-analysis-{model}.v1.json";cor=layer/"visual-analysis-corrections.v1.json";eff=layer/"visual-analysis-effective.v1.json";ledger=layer/"coverage-ledger.v1.json"
        bundle=sroot/f"{slug}-inventory-run-{args.run_id}.tar.gz"
        with tarfile.open(bundle,"w:gz") as tar:tar.add(invdir,arcname="inventory");tar.add(work/"source.manifest.json",arcname="source.manifest.json");tar.add(work/name,arcname=name)
        digest=hashlib.sha256(bundle.read_bytes()).hexdigest();tag=f"inventory-layer1-class{selection['class']}-{slug}-sha{s['sha256'][:12]}-run-{args.run_id}"
        subprocess.run(["oras","push",f"{args.package}:{tag}",f"{bundle}:application/gzip"],cwd=repo,check=True)
        index=vis/"visual-pipeline-index.v1.json"
        write_json(index,{"schema_version":"quantaedge.textbook-content-pipeline-index.v1","architecture_version":"8-layer-resumable-v1","class":selection["class"],"book_slug":slug,"source_book_id":s["book_id"],"source_pdf_sha256":s["sha256"],"layers":{"1":"COMPLETE","2":"COMPLETE","3":"COMPLETE","4":"RUNNING","5":"RUNNING","6":"NOT_STARTED","7":"NOT_STARTED","8":"NOT_STARTED"},"inventory_bundle":{"package":args.package,"tag":tag,"tar_sha256":digest},"analysis_path":rel(raw,repo),"effective_analysis_path":rel(eff,repo),"coverage_ledger_path":rel(ledger,repo),"publication_allowed":False,"human_review_required":True})
        checkpoint=f"layer4-class{selection['class']}-{slug}-{model}-checkpoint"
        while True:
            cmd=[sys.executable,"scripts/analyze_visual_inventory_with_ollama.py","--inventory",str(inv),"--model",args.model,"--max-assets",str(args.batch_size),"--include-page-previews","--run-id",args.run_id,"--output",str(raw)]
            if raw.is_file():cmd.append("--resume")
            else:
                pull=vis/".checkpoint-recovery";pull.mkdir(exist_ok=True)
                got=subprocess.run(["oras","pull",f"{args.package}:{checkpoint}","--output",str(pull)],cwd=repo,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
                files=list(pull.glob(f"visual-analysis-{model}.v1.json")) if got.returncode==0 else []
                if files:shutil.copy2(files[0],raw);cmd.append("--resume")
                shutil.rmtree(pull,ignore_errors=True)
            subprocess.run(cmd,cwd=repo,check=True)
            subprocess.run([sys.executable,"scripts/quantaedge_textbook_pipeline.py","ledger","--inventory",str(invrepo),"--raw-analysis",str(raw),"--manifest",s["extraction_manifest_repo_path"],"--text-jsonl",s["text_jsonl_repo_path"],"--effective-output",str(eff),"--ledger-output",str(ledger),"--corrections",str(cor),"--repo-root",str(repo)],cwd=repo,check=True)
            subprocess.run(["oras","push",f"{args.package}:{checkpoint}",f"{raw}:application/json"],cwd=repo,check=True)
            data=read_json(raw);processed=int(data.get("summary",{}).get("assets_processed_this_run",0))
            git_checkpoint(repo,[rel(p,repo) for p in (invrepo,vis/"source.manifest.json",raw,eff,ledger,index)],f"checkpoint(content): Layer 4-5 {slug}")
            if processed==0:break
        result=read_json(ledger);idx=read_json(index);idx["layers"]["4"]="CHECKPOINTED";idx["layers"]["5"]=result["ledger_status"];write_json(index,idx);git_checkpoint(repo,[rel(index,repo)],f"status(content): Layer 5 {slug}")
        print(json.dumps({"book":slug,"ledger_status":result["ledger_status"],"unresolved_count":result["summary"]["unresolved_count"]}))
        if result["ledger_status"]!="COMPLETE":bad.append({"book":slug,"ledger_status":result["ledger_status"],"unresolved_count":result["summary"]["unresolved_count"]})
    print(json.dumps({"class":selection["class"],"incomplete_books":bad,"layers_6_to_8":"NOT_STARTED","publication_allowed":False},indent=2))
    return 3 if bad else 0

def changed_correction_paths(event_path,root):
    event=read_json(event_path);found={}
    for c in event.get("commits",[]):
        if not isinstance(c,dict):continue
        for key,exists in (("added",True),("modified",True),("removed",False)):
            for p in c.get(key,[]) or []:
                if isinstance(p,str) and p.endswith("/visual-analysis-corrections.v1.json"):found[p]=exists
    return [(root/p,e) for p,e in sorted(found.items())]

def rebuild_corrections(args):
    root=args.repo_root.resolve()
    for overlay,exists in changed_correction_paths(args.event,root):
        lp=overlay.parent/"coverage-ledger.v1.json"
        if not lp.is_file():raise ValueError(f"No prior Layer 5 ledger for {overlay}. Run its class pipeline first.")
        paths=read_json(lp).get("source_paths",{})
        keys=("inventory","raw_analysis","manifest","text_jsonl","effective_analysis_output","coverage_ledger_output")
        if any(not paths.get(k) for k in keys):raise ValueError(f"Ledger source paths missing for {overlay}.")
        pick=lambda key:root/paths[key]
        out=build_coverage_ledger(pick("inventory"),pick("raw_analysis"),pick("manifest"),pick("text_jsonl"),pick("effective_analysis_output"),pick("coverage_ledger_output"),corrections_path=overlay if exists else None,repo_root=root)
        print(json.dumps({"overlay":rel(overlay,root),"ledger_status":out["ledger_status"],"corrections_applied":out["summary"]["corrections_applied"],"raw_layer4_preserved":True}))
    return 0

def main(argv=None):
    p=argparse.ArgumentParser(description=__doc__);sub=p.add_subparsers(dest="mode",required=True)
    q=sub.add_parser("run");q.add_argument("--selection",type=Path,required=True);q.add_argument("--source-root",type=Path,required=True);q.add_argument("--repo-root",type=Path,default=Path.cwd());q.add_argument("--package",required=True);q.add_argument("--run-id",required=True);q.add_argument("--model",required=True);q.add_argument("--batch-size",type=int,default=25)
    q=sub.add_parser("ledger")
    for k in ("inventory","raw-analysis","manifest","text-jsonl","effective-output","ledger-output"):q.add_argument("--"+k,type=Path,required=True)
    q.add_argument("--corrections",type=Path);q.add_argument("--repo-root",type=Path,default=Path.cwd())
    q=sub.add_parser("rebuild-corrections");q.add_argument("--event",type=Path,required=True);q.add_argument("--repo-root",type=Path,default=Path.cwd())
    a=p.parse_args(argv)
    try:
        if a.mode=="run":return run_layers(a)
        if a.mode=="rebuild-corrections":return rebuild_corrections(a)
        r=build_coverage_ledger(a.inventory,a.raw_analysis,a.manifest,a.text_jsonl,a.effective_output,a.ledger_output,corrections_path=a.corrections,repo_root=a.repo_root)
        print(json.dumps({"ledger_status":r["ledger_status"],**r["summary"]},indent=2));return 0
    except (OSError,ValueError,json.JSONDecodeError,subprocess.CalledProcessError) as e:print(f"Textbook content pipeline stopped safely: {e}",file=sys.stderr);return 2
if __name__=="__main__":raise SystemExit(main())
