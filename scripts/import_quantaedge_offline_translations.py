#!/usr/bin/env python3
"""Import offline English translation DRAFTs into the local QuantaEdge Admin API.

No OpenAI key is used. It reads source rows from Postgres and creates separate
English translation records; it never modifies questions or publishes content.
"""
from __future__ import annotations

import argparse
import getpass
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from import_site_content_draft import AdminApi

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_QUEUE = ROOT / "translation-queue.jsonl"
DEFAULT_MAP = ROOT / "english-translation-map-complete.jsonl"
REPORT_DIR = ROOT / "reports" / "offline-translation-imports"
DEVANAGARI = re.compile(r"[\u0900-\u097F]")
NUMBERS = re.compile(r"(?<![\w°²³])[-+]?\d+(?:,\d{3})*(?:\.\d+)?(?:\s*/\s*\d+)?")
SUPERSCRIPT = str.maketrans("⁰¹²³⁴⁵⁶⁷⁸⁹", "0123456789")
NUMBER_WORDS = {
    "zero":"0","one":"1","two":"2","three":"3","four":"4","five":"5","six":"6",
    "seven":"7","eight":"8","nine":"9","ten":"10","eleven":"11","twelve":"12",
    "thirteen":"13","fourteen":"14","fifteen":"15","sixteen":"16","seventeen":"17",
    "eighteen":"18","nineteen":"19","twenty":"20","thirty":"30","forty":"40",
    "fifty":"50","sixty":"60","seventy":"70","eighty":"80","ninety":"90",
}
CHAPTER_SQL = r"""
WITH src AS (
 SELECT ch.id::bigint entity_id,c.code::text class_code,s.code::text subject_code,
        ch.code::text chapter_code,ch.display_name::text display_name,ch.description::text description,
        md5(jsonb_build_object('display_name',ch.display_name,'description',ch.description)::text) source_hash
 FROM curriculum_chapter ch JOIN curriculum_subject s ON s.id=ch.subject_id
 JOIN curriculum_class c ON c.id=s.class_id
 WHERE c.active=true AND c.code IN ('6','7','8') AND s.active=true
   AND s.code IN ('maths','science') AND ch.active=true AND ch.content_status<>'ARCHIVED'
)
SELECT COALESCE(jsonb_agg(jsonb_build_object(
 'entity_id',r.entity_id,'class_code',r.class_code,'subject_code',r.subject_code,
 'chapter_code',r.chapter_code,'display_name',r.display_name,'description',r.description,
 'has_current_translation',EXISTS(SELECT 1 FROM curriculum_chapter_translation t
   WHERE t.chapter_id=r.entity_id AND t.language='en' AND t.source_hash::text=r.source_hash
   AND t.translation_status NOT IN ('REJECTED','SUPERSEDED'))
) ORDER BY r.class_code,r.subject_code,r.chapter_code),'[]'::jsonb)::text FROM src r;
"""
QUESTION_SQL = r"""
WITH src AS (
 SELECT q.id::bigint entity_id,c.code::text class_code,s.code::text subject_code,
        ch.code::text chapter_code,ch.display_name::text chapter_title,q.question_type::text question_type,
        q.prompt::text prompt,q.explanation::text explanation,
        COALESCE((SELECT jsonb_agg(jsonb_build_object('key',qo.option_key,'label',qo.label)
          ORDER BY qo.sort_order) FROM question_option qo WHERE qo.question_id=q.id),'[]'::jsonb) options,
        q.topic::text topic,q.subtopic::text subtopic,q.skill::text skill,q.tags tags,
        md5(jsonb_build_object('prompt',q.prompt,'explanation',q.explanation,
          'options',COALESCE((SELECT jsonb_agg(jsonb_build_object('key',qo.option_key,'label',qo.label)
            ORDER BY qo.sort_order) FROM question_option qo WHERE qo.question_id=q.id),'[]'::jsonb),
          'topic',q.topic,'subtopic',q.subtopic,'skill',q.skill,'tags',q.tags)::text) source_hash
 FROM question q JOIN lesson l ON l.id=q.lesson_id
 JOIN curriculum_chapter ch ON ch.id=l.chapter_id
 JOIN curriculum_subject s ON s.id=ch.subject_id JOIN curriculum_class c ON c.id=s.class_id
 WHERE c.active=true AND c.code IN ('6','7','8') AND s.active=true
   AND s.code IN ('maths','science') AND ch.active=true AND ch.content_status<>'ARCHIVED'
   AND l.active=true AND l.status<>'ARCHIVED' AND q.active=true
   AND q.review_status NOT IN ('REJECTED','ARCHIVED')
)
SELECT COALESCE(jsonb_agg(jsonb_build_object(
 'entity_id',r.entity_id,'class_code',r.class_code,'subject_code',r.subject_code,
 'chapter_code',r.chapter_code,'chapter_title',r.chapter_title,'question_type',r.question_type,
 'prompt',r.prompt,'explanation',r.explanation,'options',r.options,'topic',r.topic,
 'subtopic',r.subtopic,'skill',r.skill,'tags',r.tags,
 'has_current_translation',EXISTS(SELECT 1 FROM question_translation t
   WHERE t.question_id=r.entity_id AND t.language='en' AND t.source_hash::text=r.source_hash
   AND t.translation_status NOT IN ('REJECTED','SUPERSEDED'))
) ORDER BY r.class_code,r.subject_code,r.chapter_code,r.entity_id),'[]'::jsonb)::text FROM src r;
"""


def read_jsonl(path: Path, key: str) -> dict[str, dict[str, Any]]:
    rows: dict[str, dict[str, Any]] = {}
    with path.open(encoding="utf-8-sig") as f:
        for line_no, line in enumerate(f, 1):
            if not line.strip():
                continue
            row = json.loads(line)
            value = str(row.get(key, ""))
            if not value or value in rows:
                raise ValueError(f"{path}:{line_no}: missing or duplicate {key} {value!r}")
            rows[value] = row
    return rows


def load_assets(queue_path: Path, map_path: Path):
    queue = list(read_jsonl(queue_path, "tid").values())
    mapping_rows = read_jsonl(map_path, "tid")
    qids = {r["tid"] for r in queue}
    if qids != set(mapping_rows):
        raise ValueError(f"Translation queue/map mismatch: {len(qids-set(mapping_rows))} missing, {len(set(mapping_rows)-qids)} extra.")
    translations = {tid: str(row.get("english", "")).strip() for tid, row in mapping_rows.items()}
    if any(not v for v in translations.values()):
        raise ValueError("The English map contains blank translation values.")
    index: dict[tuple[str, str], list[dict[str, Any]]] = {}
    for item in queue:
        index.setdefault((str(item.get("field", "")), str(item.get("source", "")).strip()), []).append(item)
    return queue, translations, index


def db_rows(sql: str) -> list[dict[str, Any]]:
    cmd = ["docker", "compose", "exec", "-T", "postgres", "sh", "-lc",
           'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atq']
    try:
        p = subprocess.run(cmd, cwd=ROOT, input=sql, text=True, encoding="utf-8",
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180)
    except (FileNotFoundError, subprocess.TimeoutExpired) as exc:
        raise RuntimeError(f"Could not query local Postgres: {exc}") from exc
    if p.returncode:
        raise RuntimeError("Postgres query failed: " + p.stderr.strip())
    try:
        result = json.loads(p.stdout.strip())
    except json.JSONDecodeError as exc:
        raise RuntimeError("Postgres did not return one JSON array.") from exc
    if not isinstance(result, list):
        raise RuntimeError("Expected a JSON array from Postgres.")
    return result


def resolve(index, translations, field, source, row, option_key=None, option_index=None):
    src = "" if source is None else str(source).strip()
    if not src:
        return "" if source == "" else None
    candidates = index.get((field, src), [])
    if not candidates:
        if field == "option_label_TRUE_FALSE" and src in ("सही", "गलत"):
            return "True" if src == "सही" else "False"
        return src if not DEVANAGARI.search(src) else None
    best_score, best = -1, []
    expected = {
        "class":str(row.get("class_code", "")), "subject":str(row.get("subject_code", "")),
        "chapter_code":str(row.get("chapter_code", "")),
        "chapter_title":str(row.get("chapter_title", row.get("display_name", "")) or ""),
        "question_type":str(row.get("question_type", "")), "prompt":str(row.get("prompt", "")),
        "topic":str(row.get("topic", "") or ""), "subtopic":str(row.get("subtopic", "") or ""),
    }
    for item in candidates:
        ctx = item.get("context") or {}
        score = 0
        score += 50 if str(ctx.get("subject", item.get("subject", ""))) == expected["subject"] else 0
        score += 35 if str(ctx.get("class", "")) == expected["class"] else 0
        score += 60 if str(ctx.get("chapter_code", "")) == expected["chapter_code"] else 0
        score += 10 if str(ctx.get("chapter_title", "")) == expected["chapter_title"] else 0
        score += 20 if str(ctx.get("question_type", "")) == expected["question_type"] else 0
        score += 100 if str(ctx.get("prompt", "")) == expected["prompt"] else 0
        score += 5 if str(ctx.get("topic", "")) == expected["topic"] else 0
        score += 5 if str(ctx.get("subtopic", "")) == expected["subtopic"] else 0
        score += 20 if option_key is not None and str(ctx.get("option_key", "")) == str(option_key) else 0
        score += 20 if option_index is not None and str(ctx.get("option_index", "")) == str(option_index) else 0
        if score > best_score: best_score, best = score, [item]
        elif score == best_score: best.append(item)
    values = {translations[item["tid"]] for item in best}
    if len(values) != 1:
        return None
    ctx = best[0].get("context") or {}
    if str(ctx.get("subject", best[0].get("subject", ""))) not in ("", expected["subject"]):
        return None
    if field == "prompt" and str(ctx.get("prompt", "")) not in ("", expected["prompt"]):
        return None
    return next(iter(values))


def normalized_math(text: str) -> str:
    text = text.replace("−", "-")
    text = re.sub(r"(\d)([⁰¹²³⁴⁵⁶⁷⁸⁹]+)", lambda m: m.group(1)+"^"+m.group(2).translate(SUPERSCRIPT), text)
    text = re.sub(r"(?i)(\d+)\s+over\s+(\d+)", r"\1/\2", text)
    text = re.sub(r"(\d+)\s+बटा\s+(\d+)", r"\1/\2", text)
    return text


def number_signature(text: str) -> list[str]:
    return sorted(x.replace(",", "").replace(" ", "") for x in NUMBERS.findall(normalized_math(text)))


def numbers_preserved(source: str, translated: str) -> bool:
    src, dst = number_signature(source), number_signature(translated)
    normalized = normalized_math(translated)
    for word, number in NUMBER_WORDS.items():
        deficit = src.count(number) - dst.count(number)
        if deficit > 0:
            found = list(re.finditer(r"\b"+re.escape(word)+r"\b", normalized, re.I))
            dst.extend([number] * min(deficit, len(found)))
    return sorted(src) == sorted(dst)


def chapter_payload(row, index, translations):
    title = resolve(index, translations, "chapter_title", row.get("display_name"), row)
    description = resolve(index, translations, "chapter_description", row.get("description"), row)
    issues = []
    if title is None and DEVANAGARI.search(str(row.get("display_name") or "")): issues.append("missing chapter title")
    if description is None and DEVANAGARI.search(str(row.get("description") or "")): issues.append("missing chapter description")
    payload = {"display_name":row.get("display_name") if title is None else title,
               "description":row.get("description") if description is None else description}
    for field in ("display_name", "description"):
        source, target = str(row.get(field) or ""), str(payload.get(field) or "")
        if source and not numbers_preserved(source, target): issues.append("numeric check failed for "+field)
    return (None, issues) if issues else (payload, [])


def question_payload(row, index, translations):
    payload, issues = {}, []
    for field in ("prompt","explanation","topic","subtopic","skill"):
        source = row.get(field)
        target = resolve(index, translations, field, source, row)
        if target is None and DEVANAGARI.search(str(source or "")): issues.append("missing "+field+" translation")
        elif target is None: target = source
        if source is not None and target is not None and not numbers_preserved(str(source), str(target)):
            issues.append("numeric check failed for "+field)
        payload[field] = target
    options = row.get("options") or []
    option_field = "option_label_TRUE_FALSE" if str(row.get("question_type","")).upper() == "TRUE_FALSE" else "option_label_MCQ"
    translated_options = []
    for i, option in enumerate(options, 1):
        label = option.get("label")
        target = resolve(index, translations, option_field, label, row,
                         option_key=str(option.get("key","")), option_index=i)
        if target is None and str(row.get("question_type","")).upper() == "TRUE_FALSE" and label in ("सही","गलत"):
            target = "True" if label == "सही" else "False"
        if target is None and DEVANAGARI.search(str(label or "")):
            issues.append("missing option "+str(option.get("key",i))+" translation")
        elif target is None: target = label
        if label is not None and target is not None and not numbers_preserved(str(label), str(target)):
            issues.append("numeric check failed for option "+str(option.get("key",i)))
        translated_options.append({"key":option.get("key"),"label":target})
    payload["options"] = translated_options
    payload["tags"] = row.get("tags")
    return (None, issues) if issues else (payload, [])


def post_draft(api: AdminApi, entity_type, entity_id, payload):
    body = {
        "translatedContent": payload,
        "qualityNotes": (
            "Offline English translation draft; verify subject meaning, mathematical fidelity, "
            "terminology, and options before approval. DRAFT only; not published."
        ),
    }
    return api.request(
        "POST",
        f"/api/v1/admin/translations/{entity_type}/{entity_id}/draft?language=en",
        body,
    )


def process(entity_type, rows, index, translations, args, api: AdminApi | None, candidate_limit=None):
    report = []
    prepared = 0
    if candidate_limit is None:
        candidate_limit = args.limit
    for number, row in enumerate(rows, 1):
        # Limit counts ready draft candidates, not incomplete or already-current
        # rows, and is supplied by main as a shared budget across entity types.
        if candidate_limit is not None and prepared >= candidate_limit:
            break
        entity_id = int(row["entity_id"])
        item = {k:row.get(k) for k in ("class_code","subject_code","chapter_code")}
        item.update(entity_type=entity_type, entity_id=entity_id)
        if row.get("has_current_translation"):
            item["status"] = "skipped_current_translation"; report.append(item); continue
        payload, issues = chapter_payload(row,index,translations) if entity_type == "curriculum_chapter" else question_payload(row,index,translations)
        if issues or payload is None:
            item.update(status="skipped_incomplete", issues=issues); report.append(item); continue
        prepared += 1
        if not args.apply:
            item["status"] = "ready_dry_run"
        else:
            try:
                response = post_draft(api,entity_type,entity_id,payload)
                item.update(status="created_draft" if response.get("translationStatus") == "DRAFT" else "api_response",api_response=response)
            except Exception as exc:
                error = str(exc)
                item.update(status="api_error", error=error)
                report.append(item)
                # Authentication/permission failures, rate limits, server errors,
                # and connection failures won't heal by retrying every remaining row.
                # Stop immediately and report what remains unattempted.
                systemic = bool(
                    re.search(r"HTTP\s+(?:401|403|408|425|429|5\d\d)\b", error, flags=re.IGNORECASE)
                    or "API connection failed:" in error
                    or "timed out" in error.lower()
                    or "remote end closed connection" in error.lower()
                )
                if systemic:
                    report.append({
                        "entity_type": entity_type,
                        "status": "stopped_early",
                        "reason": error,
                        "failed_entity_id": entity_id,
                        "remaining_rows_not_scanned": max(0, len(rows) - number),
                        "writes_stopped_to_avoid_repeated_api_failures": True,
                    })
                    print(
                        f"{entity_type}: stopped after systemic API error at entity {entity_id}; "
                        f"{max(0, len(rows) - number)} rows not scanned in this query.",
                        file=sys.stderr, flush=True,
                    )
                    break
                if args.delay:
                    time.sleep(args.delay)
                continue
            if args.delay:
                time.sleep(args.delay)
        report.append(item)
        if number % 100 == 0: print(f"{entity_type}: scanned {number}/{len(rows)}; ready {prepared}",flush=True)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queue",type=Path,default=DEFAULT_QUEUE)
    parser.add_argument("--translations",type=Path,default=DEFAULT_MAP)
    parser.add_argument("--api-base-url",default="http://localhost:8080")
    parser.add_argument("--chapters-only",action="store_true")
    parser.add_argument("--questions-only",action="store_true")
    parser.add_argument("--limit",type=int,default=None,help="Limit each entity type for a small pilot")
    parser.add_argument("--timeout",type=int,default=30)
    parser.add_argument("--delay",type=float,default=0.03)
    parser.add_argument("--report-dir",type=Path,default=REPORT_DIR)
    parser.add_argument("--apply",action="store_true",help="Create DRAFT translation records; without it, preview only")
    args=parser.parse_args()
    if args.chapters_only and args.questions_only: parser.error("Choose at most one of --chapters-only/--questions-only")
    if args.limit is not None and args.limit < 1: parser.error("--limit must be >= 1")
    if args.timeout < 1 or args.delay < 0: parser.error("Invalid timeout or delay")
    if not args.api_base_url.startswith(("http://localhost", "http://127.0.0.1")):
        parser.error("This importer reads IDs from the local Docker Postgres database; use a localhost API URL.")
    run_started_at = datetime.now(timezone.utc)
    try:
        queue, translations, index = load_assets(args.queue,args.translations)
        print(f"Loaded {len(queue):,} queue records and {len(translations):,} translations.")
        chapters = [] if args.questions_only else db_rows(CHAPTER_SQL)
        questions = [] if args.chapters_only else db_rows(QUESTION_SQL)
        print(f"Current database: {len(chapters):,} chapters; {len(questions):,} questions.")
        api = None
        if args.apply:
            cookie = (
                os.environ.get("QUANTAEDGE_ADMIN_SESSION", "").strip()
                or os.environ.get("QE_SESSION", "").strip()
                or getpass.getpass("Paste QE_STAFF_SESSION cookie value (input hidden): ").strip()
            )
            refresh_cookie = (
                os.environ.get("QUANTAEDGE_ADMIN_REFRESH", "").strip()
                or os.environ.get("QE_STAFF_REFRESH", "").strip()
            )
            if not refresh_cookie:
                refresh_cookie = getpass.getpass(
                    "Paste paired QE_STAFF_REFRESH cookie value (input hidden; enables automatic 15-minute renewal): "
                ).strip()
            if not cookie:
                raise ValueError("A QE_STAFF_SESSION cookie is required for --apply.")
            if not refresh_cookie:
                raise ValueError("A paired QE_STAFF_REFRESH cookie is required for safe long-running --apply.")
            os.environ["QUANTAEDGE_ADMIN_REFRESH"] = refresh_cookie
            os.environ["QE_STAFF_REFRESH"] = refresh_cookie
            api = AdminApi(args.api_base_url, cookie, args.timeout)
            print("Apply mode only creates English DRAFTs; nothing is published.")
            print("Automatic staff-session refresh is enabled; cookie values are never printed or reported.")
        report = []
        remaining_budget = args.limit
        systemic_stop = False
        if chapters:
            chapter_results = process(
                "curriculum_chapter", chapters, index, translations, args, api,
                candidate_limit=remaining_budget,
            )
            report.extend(chapter_results)
            consumed = sum(1 for row in chapter_results if row.get("status") in {
                "ready_dry_run", "created_draft", "api_response", "api_error"
            })
            if remaining_budget is not None:
                remaining_budget = max(0, remaining_budget - consumed)
            systemic_stop = any(row.get("status") == "stopped_early" for row in chapter_results)
        if questions and not systemic_stop and (remaining_budget is None or remaining_budget > 0):
            question_results = process(
                "question", questions, index, translations, args, api,
                candidate_limit=remaining_budget,
            )
            report.extend(question_results)
            systemic_stop = any(row.get("status") == "stopped_early" for row in question_results)

        args.report_dir.mkdir(parents=True,exist_ok=True)
        path = args.report_dir / ("offline-translation-import-"+datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")+".json")
        counts = Counter(str(r.get("status")) for r in report)
        api_errors = [r for r in report if r.get("status") == "api_error"]
        incomplete = [r for r in report if r.get("status") == "skipped_incomplete"]
        fatal_status = ("STOPPED_EARLY_SYSTEMIC_API_ERROR" if systemic_stop else
                        "SUCCEEDED_WITH_ERRORS" if api_errors else "SUCCEEDED")
        path.write_text(json.dumps({
            "schema":"quantaedge.offline-translation-import-report.v2",
            "mode":"APPLY_DRAFTS" if args.apply else "DRY_RUN",
            "status":fatal_status,
            "started_at":run_started_at.isoformat(),
            "finished_at":datetime.now(timezone.utc).isoformat(),
            "duration_seconds":round((datetime.now(timezone.utc)-run_started_at).total_seconds(),2),
            "queue_entries":len(queue),
            "translation_entries":len(translations),
            "limit_total_ready_candidates":args.limit,
            "chapters_selected":len(chapters),
            "questions_selected":len(questions),
            "summary":dict(counts),
            "created_drafts":counts.get("created_draft",0),
            "api_error_count":len(api_errors),
            "api_errors":[{
                "entity_type":r.get("entity_type"),"entity_id":r.get("entity_id"),
                "class_code":r.get("class_code"),"subject_code":r.get("subject_code"),
                "chapter_code":r.get("chapter_code"),"error":r.get("error")
            } for r in api_errors],
            "skipped_incomplete_count":len(incomplete),
            "skipped_incomplete":[{
                "entity_type":r.get("entity_type"),"entity_id":r.get("entity_id"),
                "class_code":r.get("class_code"),"subject_code":r.get("subject_code"),
                "chapter_code":r.get("chapter_code"),"issues":r.get("issues",[])
            } for r in incomplete],
            "auto_publish":False,
            "publication_attempted":False,
            "cookie_value_recorded":False,
            "results":report
        },ensure_ascii=False,indent=2),encoding="utf-8")
        print("Final status: "+fatal_status)
        print("Summary: "+json.dumps(dict(counts),ensure_ascii=False,sort_keys=True))
        print(f"API errors recorded: {len(api_errors)}")
        for row in api_errors[:10]:
            print(
                f"API_ERROR {row.get('entity_type')}#{row.get('entity_id')} "
                f"class={row.get('class_code')} subject={row.get('subject_code')} "
                f"chapter={row.get('chapter_code')}: {row.get('error')}",
                file=sys.stderr,
            )
        if len(api_errors) > 10:
            print(f"... plus {len(api_errors)-10} API errors in the JSON report.",file=sys.stderr)
        print(f"Report: {path}")
        return 2 if api_errors or systemic_stop else 0
    except (OSError,ValueError,RuntimeError,json.JSONDecodeError) as exc:
        print(f"ERROR: {exc}",file=sys.stderr); return 2


if __name__ == "__main__":
    raise SystemExit(main())
