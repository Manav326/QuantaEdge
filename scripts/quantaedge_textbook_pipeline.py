#!/usr/bin/env python3
"""QuantaEdge resumable visual pipeline orchestration and Layer 5 evidence ledger."""
from __future__ import annotations
from datetime import datetime, timezone
import argparse, copy, hashlib, json, re, shutil, subprocess, sys, tarfile, time
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

_TRANSIENT_REGISTRY_ERRORS = (
    "i/o timeout", "tls handshake timeout", "context deadline exceeded",
    "connection reset", "connection refused", "temporary failure in name resolution",
    "network is unreachable", "dial tcp", "unexpected eof", "unexpected status code 429",
    "unexpected status code 500", "unexpected status code 502", "unexpected status code 503",
    "unexpected status code 504", "too many requests", "server misbehaving",
)

def push_oci_artifact_with_retry(command, *, cwd, description, attempts=4, initial_delay_seconds=10):
    """Retry transient OCI-registry errors; return False after network retries are exhausted.

    Caller must already have a durable Git checkpoint before publishing a Layer 4/5
    analysis artifact. Git is the recovery source when GHCR is temporarily unavailable.
    Permanent/authentication errors fail immediately rather than being hidden.
    """
    if attempts < 1:
        raise ValueError("attempts must be at least 1")
    for attempt in range(1, attempts + 1):
        result = subprocess.run(command, cwd=cwd, text=True, capture_output=True)
        if result.stdout:
            print(result.stdout, end="" if result.stdout.endswith("\n") else "\n", flush=True)
        if result.returncode == 0:
            return True
        details = (result.stderr or result.stdout or f"exit code {result.returncode}").strip()
        print(f"[registry] {description}: push attempt {attempt}/{attempts} failed: {details}", file=sys.stderr, flush=True)
        transient = any(token in details.lower() for token in _TRANSIENT_REGISTRY_ERRORS)
        if not transient:
            raise subprocess.CalledProcessError(result.returncode, command, output=result.stdout, stderr=result.stderr)
        if attempt < attempts:
            delay = initial_delay_seconds * (2 ** (attempt - 1))
            print(f"[registry] transient failure; retrying {description} in {delay}s.", flush=True)
            time.sleep(delay)
    print(
        f"[registry] WARNING: {description} remains unavailable after {attempts} attempts. "
        "The matching Git checkpoint is durable; continuing from Git and disabling further "
        "GHCR pushes for this run.",
        file=sys.stderr,
        flush=True,
    )
    return False

def rel(path,root):
    try:return Path(path).resolve().relative_to(Path(root).resolve()).as_posix()
    except ValueError:raise ValueError(f"Path {path} is outside repo root {root}.")
def page_no(row):
    for key in ("source_pdf_page_number","source_book_page_number","page_number"):
        v=row.get(key)
        if isinstance(v,int) and not isinstance(v,bool) and v>0:return v
    return None

def build_coverage_ledger(inventory_path,raw_path,manifest_path,text_path,effective_path,ledger_path,*,corrections_path=None,repo_root=None):
    layer5_started_at=datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00","Z")
    layer5_started_clock=time.monotonic()
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
    def checkpoint_mismatch(aid,row):
        asset=invassets.get(aid)
        if asset is None:return "CHECKPOINT_ASSET_MISSING"
        if row.get("analysis_status")=="ANALYSIS_ERROR":return "ANALYSIS_ERROR"
        if row.get("asset_sha256")!=asset.get("asset_sha256"):return "ASSET_HASH_MISMATCH"
        if row.get("source_book_id")!=bid or str(row.get("source_pdf_sha256") or "").lower()!=sha:return "SOURCE_IDENTITY_MISMATCH"
        if row.get("model")!=raw.get("model") or row.get("prompt_version")!=raw.get("prompt_version"):return "MODEL_OR_PROMPT_MISMATCH"
        expected_context=hashlib.sha256(str(asset.get("page_text_context") or "")[:1600].encode("utf-8")).hexdigest()
        if row.get("page_text_context_sha256")!=expected_context:return "PAGE_TEXT_CONTEXT_MISMATCH"
        if row.get("analysis_status")!="DRAFT_REQUIRES_REVIEW" or not isinstance(row.get("analysis"),dict):return "ANALYSIS_NOT_SUCCESSFUL"
        return "MATCHED"

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
            if checkpoint_mismatch(aid,base)!="MATCHED":
                raise ValueError(f"Accepted correction {aid} requires current source/model/prompt/page-text-context analysis; resume Layer 4 before applying the correction.")
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
        fingerprint_status=checkpoint_mismatch(aid,row)
        out=copy.deepcopy(row)
        out["effective_analysis"]=copy.deepcopy(row.get("analysis")) if fingerprint_status=="MATCHED" else None
        out["checkpoint_fingerprint_status"]=fingerprint_status
        out["correction_status"]="NONE"
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
            elif r.get("checkpoint_fingerprint_status")=="PAGE_TEXT_CONTEXT_MISMATCH":state="ANALYSIS_STALE_CONTEXT"
            elif r.get("checkpoint_fingerprint_status") not in {None,"MATCHED"}:state="ANALYSIS_STALE_CHECKPOINT"
            elif r.get("analysis_status")=="DRAFT_REQUIRES_REVIEW" and isinstance(r.get("effective_analysis"),dict):state="ANALYZED_REQUIRES_HUMAN_REVIEW";ea=r["effective_analysis"];cs=r.get("correction_status","NONE")
            else:state="ANALYSIS_STATUS_UNKNOWN"
            visual_bad += state!="ANALYZED_REQUIRES_HUMAN_REVIEW"
        visual_rows.append({"asset_id":aid,"asset_kind":a.get("kind"),"asset_sha256":a.get("asset_sha256"),"source_pdf_sha256":a.get("source_pdf_sha256") or sha,"source_pdf_page_number":page_no(a),"asset_path":a.get("asset_path"),"media_type":a.get("media_type"),"bbox":a.get("bbox"),"analysis_status":state,"correction_status":cs,"effective_analysis":ea,"chapter_id":None,"topic_ids":[],"mapping_status":"NOT_STARTED","human_review_required":True,"publication_allowed":False})
    orphans=sorted(set(rawby)-{a.get("asset_id") for a in assets if isinstance(a.get("asset_id"),str) and a.get("kind") in KINDS})
    visual_bad+=len(orphans);unresolved=page_bad+visual_bad
    paths={"inventory":rel(inventory_path,root),"raw_analysis":rel(raw_path,root),"manifest":rel(manifest_path,root),"text_jsonl":rel(text_path,root),"corrections":rel(corrections_path,root) if corrections_path else None,"effective_analysis_output":rel(effective_path,root),"coverage_ledger_output":rel(ledger_path,root)}
    write_json(effective_path,{"schema_version":"quantaedge.visual-analysis-effective.v1","created_at":raw.get("created_at"),"generated_at":datetime.now(timezone.utc).isoformat().replace("+00:00","Z"),"last_progress_at":raw.get("last_progress_at"),"source_book_id":bid,"source_pdf_sha256":sha,"raw_analysis_path":paths["raw_analysis"],"raw_analysis_sha256":hashlib.sha256(Path(raw_path).read_bytes()).hexdigest(),"model":raw.get("model"),"prompt_version":raw.get("prompt_version"),"corrections_path":paths["corrections"],"corrections_applied_count":len(accepted),"results":eff,"human_review_required":True,"publication_allowed":False})
    out={"schema_version":"quantaedge.textbook-coverage-ledger.v1","architecture_version":"8-layer-resumable-v1","layer":5,"generated_at":datetime.now(timezone.utc).isoformat().replace("+00:00","Z"),"last_progress_at":raw.get("last_progress_at"),"ledger_status":"COMPLETE" if unresolved==0 else "INCOMPLETE","source":{"class":src.get("class"),"language":src.get("language"),"subject":src.get("subject"),"source_book_id":bid,"source_pdf_sha256":sha,"edition":src.get("edition"),"pdf_page_count":count},"source_paths":paths,"summary":{"source_pdf_page_count":count,"inventory_page_records":len(pages),"page_text_records":sum(map(len,texts.values())),"pages_with_unresolved_text":page_bad,"inventory_asset_rows":len(assets),"eligible_visual_asset_rows":eligible,"raw_analysis_result_rows":len(results),"successful_visual_analyses":sum(x["analysis_status"]=="ANALYZED_REQUIRES_HUMAN_REVIEW" for x in visual_rows),"missing_visual_analyses":sum(x["analysis_status"]=="ANALYSIS_MISSING" for x in visual_rows),"failed_visual_analyses":sum(x["analysis_status"] in {"ANALYSIS_ERROR","ASSET_HASH_MISMATCH","ANALYSIS_STATUS_UNKNOWN","ANALYSIS_STALE_CONTEXT","ANALYSIS_STALE_CHECKPOINT"} for x in visual_rows),"orphan_analysis_results":len(orphans),"corrections_applied":len(accepted),"unresolved_count":unresolved,"mapping_status":"NOT_STARTED","analysis_model":raw.get("model"),"analysis_prompt_version":raw.get("prompt_version"),"publication_allowed":False},"pages":page_rows,"visuals":visual_rows,"orphan_analysis_asset_ids":orphans,"human_review_required":True,"publication_allowed":False}
    layer5_completed_at=datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00","Z")
    layer5_elapsed=round(time.monotonic()-layer5_started_clock,3)
    out["timing"]={"layer":5,"status":"COMPLETE" if out.get("ledger_status")=="COMPLETE" else "INCOMPLETE",
        "started_at":layer5_started_at,"completed_at":layer5_completed_at,"last_progress_at":layer5_completed_at,
        "attempt_elapsed_seconds":layer5_elapsed,"timing_basis":"Monotonic time for effective-analysis and page/visual coverage reconciliation"}
    write_json(ledger_path,out);return out

def git_checkpoint(repo,paths,message):
    # Generated content remains on the branch that explicitly requested this task.
    repo_root=Path(repo).resolve()
    data_root=(repo_root/"data").resolve()
    staged_paths=[]
    index_paths=[]
    for raw_path in paths:
        candidate=Path(raw_path)
        if not candidate.is_absolute(): candidate=repo_root/candidate
        try: relative=candidate.resolve().relative_to(data_root)
        except ValueError as exc: raise ValueError(f"Pipeline checkpoints may only be committed under data/: {raw_path}") from exc
        staged_paths.append((Path("data")/relative).as_posix())
        if candidate.name=="visual-pipeline-index.v1.json": index_paths.append(candidate)
    try:
        from scripts.textbook_pipeline_handoff import record_book_checkpoint
    except ModuleNotFoundError:
        from textbook_pipeline_handoff import record_book_checkpoint
    for index_path in index_paths:
        staged_paths.extend(record_book_checkpoint(repo_root,index_path.parent.name))
    subprocess.run(["git","add","--",*sorted(set(staged_paths))],cwd=repo_root,check=True)
    rc=subprocess.run(["git","diff","--cached","--quiet"],cwd=repo_root).returncode
    if rc==0:return
    subprocess.run(["git","config","user.name","github-actions[bot]"],cwd=repo_root,check=True)
    subprocess.run(["git","config","user.email","41898282+github-actions[bot]@users.noreply.github.com"],cwd=repo_root,check=True)
    subprocess.run(["git","commit","-m",message],cwd=repo_root,check=True)
    target=os.environ.get("QUANTAEDGE_CHECKPOINT_BRANCH") or os.environ.get("GITHUB_REF_NAME")
    if not target:
        target=subprocess.run(["git","branch","--show-current"],cwd=repo_root,check=True,capture_output=True,text=True).stdout.strip()
    if not target or target.startswith("refs/"):
        raise RuntimeError("No valid caller branch is available for content checkpointing.")
    subprocess.run(["git","push","origin",f"HEAD:{target}"],cwd=repo_root,check=True)


def backfill_legacy_context_hashes(previous_inventory, raw_path, *, book_id, source_sha, model):
    """Recover legacy per-asset text-context fingerprints from the exact committed prior inventory.

    Older raw analysis rows predate page_text_context_sha256. When the prior inventory is
    available, backfill the fingerprint used for those results so unchanged rows remain
    reusable while only rows whose current context changed are sent back to the model.
    Without a source-matching prior inventory, leave the hash absent; the analyzer will
    conservatively reprocess those legacy rows instead of silently trusting them.
    """
    raw_path=Path(raw_path)
    if not raw_path.is_file():
        return 0
    raw=read_json(raw_path)
    if (raw.get("schema_version")!="quantaedge.visual-analysis.v1"
        or raw.get("source_book_id")!=book_id
        or str(raw.get("source_pdf_sha256") or "").lower()!=str(source_sha).lower()
        or raw.get("model")!=model
        or not isinstance(raw.get("results"),list)):
        return 0
    src=(previous_inventory or {}).get("source",{}) if isinstance(previous_inventory,dict) else {}
    if (not isinstance(previous_inventory,dict)
        or previous_inventory.get("schema_version")!="quantaedge.visual-inventory.v1"
        or src.get("book_id")!=book_id
        or str(src.get("pdf_sha256") or "").lower()!=str(source_sha).lower()
        or not isinstance(previous_inventory.get("assets"),list)):
        legacy=sum(1 for row in raw["results"] if isinstance(row,dict) and not row.get("page_text_context_sha256"))
        if legacy:
            print(f"[pipeline] LEGACY_CONTEXT_UNVERIFIABLE book={book_id} rows={legacy}; successful legacy rows without fingerprints will be reanalyzed conservatively.",flush=True)
        return 0
    assets={}
    for asset in previous_inventory["assets"]:
        if isinstance(asset,dict) and isinstance(asset.get("asset_id"),str) and asset.get("kind") in KINDS:
            assets[asset["asset_id"]]=asset
    updated=0
    prompt=raw.get("prompt_version")
    for row in raw["results"]:
        if not isinstance(row,dict) or row.get("page_text_context_sha256"):
            continue
        if (row.get("source_book_id")!=book_id
            or str(row.get("source_pdf_sha256") or "").lower()!=str(source_sha).lower()
            or row.get("model")!=model
            or row.get("prompt_version")!=prompt):
            continue
        asset=assets.get(row.get("asset_id"))
        if not asset or asset.get("asset_sha256")!=row.get("asset_sha256"):
            continue
        context=str(asset.get("page_text_context") or "")[:1600]
        row["page_text_context_sha256"]=hashlib.sha256(context.encode("utf-8")).hexdigest()
        updated+=1
    if updated:
        write_json(raw_path,raw)
        print(f"[pipeline] LEGACY_CONTEXT_HASHES_BACKFILLED book={book_id} model={model} rows={updated}; current inventory context will be compared before reuse.",flush=True)
    return updated


def previous_inventory_for_resume(repo_inventory_path, restored_inventory_path, restored_marker_path, prior_index, source, package):
    """Find the prior inventory only from a source-bound, verified checkpoint.

    A fresh runner may restore Layer 4 analysis from GHCR while the associated
    inventory JSON is absent from Git. In that case, the workflow-created
    restore marker binds the restored inventory bundle to the prior pipeline
    index. Use that restored inventory to backfill legacy page-context hashes
    only after every binding matches. Without that proof, do not infer that
    the current inventory was the one used to generate the old analysis.
    """
    repo_inventory_path = Path(repo_inventory_path)
    restored_inventory_path = Path(restored_inventory_path)
    restored_marker_path = Path(restored_marker_path)
    if repo_inventory_path.is_file():
        return read_json(repo_inventory_path)
    if not restored_marker_path.is_file():
        return None

    marker = read_json(restored_marker_path)
    prior_bundle = prior_index.get("inventory_bundle") if isinstance(prior_index.get("inventory_bundle"), dict) else {}
    prior_matches = (
        prior_index.get("source_book_id") == source.get("book_id")
        and str(prior_index.get("source_pdf_sha256") or "").lower()
        == str(source.get("sha256") or "").lower()
    )
    if not prior_matches:
        raise ValueError(
            f"{source.get('book_id')}: restored inventory marker has no matching prior pipeline index; "
            "refusing to reuse legacy visual analyses."
        )
    if (
        marker.get("source_pdf_sha256") != source.get("sha256")
        or marker.get("tag") != prior_bundle.get("tag")
        or marker.get("tar_sha256") != prior_bundle.get("tar_sha256")
        or prior_bundle.get("package") != package
    ):
        raise ValueError(
            f"{source.get('book_id')}: restored inventory bundle marker/index/source mismatch; "
            "refusing to reuse legacy visual analyses."
        )
    if not restored_inventory_path.is_file():
        raise ValueError(f"{source.get('book_id')}: verified restored inventory file is missing.")

    inventory = read_json(restored_inventory_path)
    inventory_source = inventory.get("source") if isinstance(inventory.get("source"), dict) else {}
    if (
        inventory.get("schema_version") != "quantaedge.visual-inventory.v1"
        or inventory_source.get("book_id") != source.get("book_id")
        or str(inventory_source.get("pdf_sha256") or "").lower()
        != str(source.get("sha256") or "").lower()
    ):
        raise ValueError(
            f"{source.get('book_id')}: restored inventory schema or source identity mismatch."
        )
    return inventory


def layer1_inventory_bundle_from_marker(marker_path, inventory_path, source, package):
    """Use the already-published Layer 1 inventory bundle only if every marker binding matches."""
    marker_path, inventory_path = Path(marker_path), Path(inventory_path)
    if not marker_path.is_file():
        return None
    marker = read_json(marker_path)
    inventory_sha = hashlib.sha256(inventory_path.read_bytes()).hexdigest() if inventory_path.is_file() else None
    tag, archive_sha, fingerprint = marker.get("version_tag"), marker.get("bundle_tar_sha256"), marker.get("preparation_fingerprint")
    valid_hex = lambda value: isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None
    if (
        marker.get("schema_version") != "quantaedge.visual-inventory-checkpoint-marker.v1"
        or marker.get("source_book_id") != source.get("book_id")
        or str(marker.get("source_pdf_sha256") or "").lower() != str(source.get("sha256") or "").lower()
        or marker.get("package") != package
        or marker.get("artifact_type") != "application/vnd.quantaedge.textbook.visual-inventory-bundle.v1"
        or not isinstance(tag, str) or not tag
        or not valid_hex(archive_sha) or not valid_hex(fingerprint)
        or marker.get("inventory_json_sha256") != inventory_sha
    ):
        raise ValueError(f"Layer 1 preparation marker/source/inventory mismatch for {source.get('book_id')}.")
    return {"package": package, "tag": tag, "tar_sha256": archive_sha}


def book_batch_limit(max_batches_per_run: int, source_count: int) -> int:
    """Share each runner's batch budget across its selected whole-book sources."""
    if max_batches_per_run < 1:
        raise ValueError("max-batches-per-run must be positive.")
    if source_count < 1:
        raise ValueError("At least one selected whole-book source is required.")
    return max(1, (max_batches_per_run + source_count - 1) // source_count)


def run_layers(args):
    repo=args.repo_root.resolve();sroot=args.source_root.resolve();selection=read_json(args.selection);model=re.sub(r"[^A-Za-z0-9._-]","-",args.model);bad=[];registry_writes_enabled=True;total_batches=0;yielded_for_resume=False
    if args.batch_size<1:raise ValueError("batch-size must be positive; it is not a total cap.")
    if args.max_batches_per_run < 1:raise ValueError("max-batches-per-run must be positive.")
    if not 10 <= args.request_timeout <= 900:raise ValueError("request-timeout must be between 10 and 900 seconds so a stalled model request cannot hang a runner indefinitely.")
    sources=selection.get("sources")
    if not isinstance(sources,list) or not sources:
        raise ValueError("Source selection must contain at least one whole-book source.")
    per_book_batch_limit=book_batch_limit(args.max_batches_per_run,len(sources))
    print(f"[pipeline] SOURCE_SCHEDULE class={selection['class']} books={len(sources)} max_batches_per_run={args.max_batches_per_run} per_book_batch_limit={per_book_batch_limit}",flush=True)
    for s in sources:
        if total_batches >= args.max_batches_per_run:
            print(f"[pipeline] RUN_BUDGET_REACHED class={selection['class']} max_batches={args.max_batches_per_run} completed_batches={total_batches}; remaining books keep their durable checkpoints.",flush=True)
            yielded_for_resume=True
            break
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
        invrepo=vis/"visual-inventory.v1.json"
        raw=vis/f"visual-analysis-{model}.v1.json";cor=layer/"visual-analysis-corrections.v1.json";eff=layer/"visual-analysis-effective.v1.json";ledger=layer/"coverage-ledger.v1.json"
        index=vis/"visual-pipeline-index.v1.json"
        prior_index=read_json(index) if index.is_file() else {}
        prior_matches=(prior_index.get("source_book_id")==s["book_id"] and str(prior_index.get("source_pdf_sha256") or "").lower()==str(s["sha256"]).lower())
        preparation_marker=work/".layer1-preparation-checkpoint.json"
        prepared_bundle=layer1_inventory_bundle_from_marker(preparation_marker,inv,s,args.package)
        restored_marker=work/".inventory-restored-from-ghcr.json"
        previous_inventory=previous_inventory_for_resume(
            invrepo, inv, restored_marker, prior_index, s, args.package
        )
        shutil.copy2(inv,invrepo);shutil.copy2(work/"source.manifest.json",vis/"source.manifest.json")
        if prepared_bundle is not None:
            inventory_bundle=prepared_bundle
            print(f"[pipeline] INVENTORY_BUNDLE_REUSED_FROM_PREPARATION book={slug} tag={inventory_bundle['tag']} tar_sha256={inventory_bundle['tar_sha256']}",flush=True)
        elif restored_marker.is_file():
            marker=read_json(restored_marker)
            prior_bundle=prior_index.get("inventory_bundle") if isinstance(prior_index.get("inventory_bundle"),dict) else {}
            if not prior_matches:
                raise ValueError(f"{slug}: restored inventory marker has no matching prior pipeline index; refusing to reuse an unbound bundle.")
            if (marker.get("source_pdf_sha256")!=s["sha256"] or marker.get("tag")!=prior_bundle.get("tag")
                or marker.get("tar_sha256")!=prior_bundle.get("tar_sha256") or prior_bundle.get("package")!=args.package):
                raise ValueError(f"{slug}: restored inventory bundle marker/index/source mismatch.")
            inventory_bundle=prior_bundle
            print(f"[pipeline] INVENTORY_BUNDLE_REUSED book={slug} tag={inventory_bundle['tag']} tar_sha256={inventory_bundle['tar_sha256']}",flush=True)
        else:
            bundle=sroot/f"{slug}-inventory-run-{args.run_id}.tar.gz"
            with tarfile.open(bundle,"w:gz") as tar:
                tar.add(invdir,arcname="inventory")
                tar.add(work/"source.manifest.json",arcname="source.manifest.json")
                tar.add(work/name,arcname=name)
            digest=hashlib.sha256(bundle.read_bytes()).hexdigest()
            tag=f"inventory-layer1-class{selection['class']}-{slug}-sha{s['sha256'][:12]}-run-{args.run_id}"
            if registry_writes_enabled:
                published=push_oci_artifact_with_retry(
                    ["oras","push","--artifact-type","application/vnd.quantaedge.textbook.visual-inventory-bundle.v1",f"{args.package}:{tag}",f"{bundle.name}:application/gzip"],
                    cwd=sroot,description=f"{slug} inventory bundle",
                )
                if published:
                    inventory_bundle={"package":args.package,"tag":tag,"tar_sha256":digest}
                    print(f"[pipeline] INVENTORY_BUNDLE_PUBLISHED book={slug} tag={tag} tar_sha256={digest}",flush=True)
                else:
                    registry_writes_enabled=False
                    inventory_bundle=None
            else:
                inventory_bundle=None
                print(f"[registry] GHCR writes disabled for this run; inventory bundle will be recovered from the Git checkpoint for {slug}.",flush=True)
        previous_layers=prior_index.get("layers",{}) if prior_matches and isinstance(prior_index.get("layers"),dict) else {}
        layers={"1":"COMPLETE","2":previous_layers.get("2","COMPLETE"),"3":previous_layers.get("3","COMPLETE"),
                "4":"RUNNING","5":"RUNNING","6":previous_layers.get("6","NOT_STARTED"),
                "7":previous_layers.get("7","NOT_STARTED"),"8":previous_layers.get("8","NOT_STARTED")}
        write_json(index,{"schema_version":"quantaedge.textbook-content-pipeline-index.v1","architecture_version":"8-layer-resumable-v1","timestamps":{"updated_at":datetime.now(timezone.utc).isoformat().replace("+00:00","Z"),"last_progress_at":raw.get("last_progress_at"),"checkpoint_at":datetime.now(timezone.utc).isoformat().replace("+00:00","Z")},"class":selection["class"],"book_slug":slug,"source_book_id":s["book_id"],"source_pdf_sha256":s["sha256"],"layers":layers,"inventory_bundle":inventory_bundle,"analysis_path":rel(raw,repo),"effective_analysis_path":rel(eff,repo),"coverage_ledger_path":rel(ledger,repo),"publication_allowed":False,"human_review_required":True})
        checkpoint=f"layer4-class{selection['class']}-{slug}-{model}-checkpoint"
        batch_number=0
        book_batches=0
        book_yielded=False
        print(f"[pipeline] BOOK_START class={selection['class']} book={slug} eligible_inventory_assets={len(inventory.get('assets',[]))} batch_size={args.batch_size} per_book_batch_limit={per_book_batch_limit} checkpoint_tag={checkpoint}",flush=True)
        while True:
            if total_batches >= args.max_batches_per_run:
                book_yielded=True
                print(f"[pipeline] RUN_BUDGET_REACHED class={selection['class']} max_batches={args.max_batches_per_run} completed_batches={total_batches}; yielding after durable checkpoint.",flush=True)
                break
            if book_batches >= per_book_batch_limit:
                book_yielded=True
                print(f"[pipeline] BOOK_BATCH_BUDGET_REACHED class={selection['class']} book={slug} completed_book_batches={book_batches} per_book_batch_limit={per_book_batch_limit}; continuing to the next selected book before resume.",flush=True)
                break
            batch_number+=1
            print(f"[pipeline] BATCH_START class={selection['class']} book={slug} batch={batch_number} batch_limit={args.batch_size} run_id={args.run_id}",flush=True)
            cmd=[sys.executable,"scripts/analyze_visual_inventory_with_ollama.py","--inventory",str(inv),"--model",args.model,"--max-assets",str(args.batch_size),"--include-page-previews","--run-id",args.run_id,"--timeout",str(args.request_timeout),"--output",str(raw)]
            if raw.is_file():cmd.append("--resume")
            else:
                pull=vis/".checkpoint-recovery";pull.mkdir(exist_ok=True)
                got=subprocess.run(["oras","pull",f"{args.package}:{checkpoint}","--output",str(pull)],cwd=repo,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
                files=list(pull.glob(f"visual-analysis-{model}.v1.json")) if got.returncode==0 else []
                if files:shutil.copy2(files[0],raw);cmd.append("--resume")
                shutil.rmtree(pull,ignore_errors=True)
            if raw.is_file():
                backfill_legacy_context_hashes(previous_inventory,raw,book_id=s["book_id"],source_sha=s["sha256"],model=args.model)
            before_data=read_json(raw) if raw.is_file() else {}
            before_summary=before_data.get("summary",{}) if isinstance(before_data,dict) else {}
            before_results=before_data.get("results",[]) if isinstance(before_data,dict) else []
            before_results_by_id={row.get("asset_id"):row for row in before_results if isinstance(row,dict) and row.get("asset_id")}
            subprocess.run(cmd,cwd=repo,check=True)
            subprocess.run([sys.executable,"scripts/quantaedge_textbook_pipeline.py","ledger","--inventory",str(invrepo),"--raw-analysis",str(raw),"--manifest",s["extraction_manifest_repo_path"],"--text-jsonl",str(s["text_jsonl_repo_path"]),"--effective-output",str(eff),"--ledger-output",str(ledger),"--corrections",str(cor),"--repo-root",str(repo)],cwd=repo,check=True)
            data=read_json(raw);summary=data.get("summary",{});processed=int(summary.get("assets_processed_this_run",0))
            if processed > 0:
                total_batches += 1
                book_batches += 1
            after_results=data.get("results",[]) if isinstance(data,dict) else []
            processed_ids=summary.get("assets_processed_ids",[])
            overwrote_successes=bool(processed_ids) and all(
                asset_id in before_results_by_id
                and before_results_by_id[asset_id].get("analysis_status")=="DRAFT_REQUIRES_REVIEW"
                for asset_id in processed_ids
            )
            no_progress=(int(summary.get("assets_analyzed",0))<=int(before_summary.get("assets_analyzed",0))
                and int(summary.get("assets_failed",0))<=int(before_summary.get("assets_failed",0))
                and len(after_results)<=len(before_results))
            if processed>0 and overwrote_successes and no_progress:
                raise ValueError(
                    f"{slug}: Layer 4–5 repeated prior successful asset IDs without increasing analyzed/failed counts: {processed_ids}. "
                    "Stopping instead of burning the runner on an infinite repeat; the last durable checkpoint is preserved."
                )
            # Git is committed first: even if GHCR times out after analysis, this batch
            # survives runner teardown and the next resume can continue from the repo.
            git_checkpoint(repo,[rel(p,repo) for p in (invrepo,vis/"source.manifest.json",raw,eff,ledger,index)],f"checkpoint(content): Layer 4-5 {slug}")
            if registry_writes_enabled:
                published=push_oci_artifact_with_retry(
                    ["oras","push","--artifact-type","application/vnd.quantaedge.textbook.visual-analysis-checkpoint.v1",f"{args.package}:{checkpoint}",f"{raw.name}:application/json"],
                    cwd=raw.parent,description=f"{slug} Layer 4/5 analysis checkpoint",
                )
                if not published:
                    registry_writes_enabled=False
            print(f"[pipeline] BATCH_CHECKPOINTED class={selection['class']} book={slug} batch={batch_number} processed_this_batch={processed} analyzed_total={summary.get('assets_analyzed',0)} failed_total={summary.get('assets_failed',0)} results_saved={len(data.get('results',[]))} next_batch={'yes' if processed>0 else 'no'}",flush=True)
            if processed==0:break
        result=read_json(ledger);idx=read_json(index);idx["layers"]["4"]="CHECKPOINTED";idx["layers"]["5"]=result["ledger_status"];write_json(index,idx);git_checkpoint(repo,[rel(index,repo)],f"status(content): Layer 5 {slug}")
        print(json.dumps({"book":slug,"ledger_status":result["ledger_status"],"unresolved_count":result["summary"]["unresolved_count"]}))
        if book_yielded:
            yielded_for_resume=True
        if result["ledger_status"]!="COMPLETE":bad.append({"book":slug,"ledger_status":result["ledger_status"],"unresolved_count":result["summary"]["unresolved_count"]})
    if yielded_for_resume:
        print(json.dumps({"pipeline_run_status":"YIELDED_FOR_RESUME","class":selection["class"],"completed_batches":total_batches,"max_batches_per_run":args.max_batches_per_run,"per_book_batch_limit":per_book_batch_limit,"publication_allowed":False}),flush=True)
        return 10
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
    q=sub.add_parser("run");q.add_argument("--selection",type=Path,required=True);q.add_argument("--source-root",type=Path,required=True);q.add_argument("--repo-root",type=Path,default=Path.cwd());q.add_argument("--package",required=True);q.add_argument("--run-id",required=True);q.add_argument("--model",required=True);q.add_argument("--batch-size",type=int,default=5,help="Assets per durable Layer 4–5 checkpoint; smaller batches make progress visible and reduce uncheckpointed work.");q.add_argument("--max-batches-per-run",type=int,default=8,help="Maximum durable analysis invocations per workflow run. Yield after a bounded run so GitHub's hard timeout cannot discard a long-running pass.");q.add_argument("--request-timeout",type=int,default=300,help="Maximum seconds for each Ollama HTTP request; prevents a stuck model call from hanging a GitHub runner indefinitely.")
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
