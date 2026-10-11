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


def post_draft(base_url, cookie, entity_type, entity_id, payload, timeout):
    body = json.dumps({"translatedContent":payload,"qualityNotes":
        "Offline English translation draft; verify subject meaning, mathematical fidelity, terminology, and options before approval. DRAFT only; not published."},
        ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        f"{base_url.rstrip('/')}/api/v1/admin/translations/{entity_type}/{entity_id}/draft?language=en",
        data=body, method="POST",
        headers={"Content-Type":"application/json; charset=utf-8","Accept":"application/json","Cookie":f"QE_SESSION={cookie}"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")[:1500]
        raise RuntimeError(f"HTTP {exc.code}: {detail}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"API connection failed: {exc.reason}") from exc


def process(entity_type, rows, index, translations, args, cookie):
    report = []
    chosen = rows[:args.limit] if args.limit else rows
    for number, row in enumerate(chosen, 1):
        entity_id = int(row["entity_id"])
        item = {k:row.get(k) for k in ("class_code","subject_code","chapter_code")}
        item.update(entity_type=entity_type, entity_id=entity_id)
        if row.get("has_current_translation"):
            item["status"] = "skipped_current_translation"; report.append(item); continue
        payload, issues = chapter_payload(row,index,translations) if entity_type == "curriculum_chapter" else question_payload(row,index,translations)
        if issues or payload is None:
            item.update(status="skipped_incomplete", issues=issues); report.append(item); continue
        if not args.apply:
            item["status"] = "ready_dry_run"
        else:
            try:
                response = post_draft(args.api_base_url,cookie,entity_type,entity_id,payload,args.timeout)
                item.update(status="created_draft" if response.get("translationStatus") == "DRAFT" else "api_response",api_response=response)
            except Exception as exc:
                item.update(status="api_error",error=str(exc))
            if args.delay: time.sleep(args.delay)
        report.append(item)
        if number % 100 == 0: print(f"{entity_type}: checked {number}/{len(chosen)}",flush=True)
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
    try:
        queue, translations, index = load_assets(args.queue,args.translations)
        print(f"Loaded {len(queue):,} queue records and {len(translations):,} translations.")
        chapters = [] if args.questions_only else db_rows(CHAPTER_SQL)
        questions = [] if args.chapters_only else db_rows(QUESTION_SQL)
        print(f"Current database: {len(chapters):,} chapters; {len(questions):,} questions.")
        cookie = ""
        if args.apply:
            cookie = os.environ.get("QE_SESSION","").strip() or getpass.getpass("QE_SESSION cookie (input hidden): ").strip()
            if not cookie: raise ValueError("QE_SESSION is required for --apply.")
            print("Apply mode only creates English DRAFTs; nothing is published.")
        report = []
        if chapters: report.extend(process("curriculum_chapter",chapters,index,translations,args,cookie))
        if questions: report.extend(process("question",questions,index,translations,args,cookie))
        args.report_dir.mkdir(parents=True,exist_ok=True)
        path = args.report_dir / ("offline-translation-import-"+datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")+".json")
        counts = Counter(str(r.get("status")) for r in report)
        path.write_text(json.dumps({"mode":"APPLY_DRAFTS" if args.apply else "DRY_RUN",
            "queue_entries":len(queue),"translation_entries":len(translations),
            "summary":dict(counts),"results":report},ensure_ascii=False,indent=2),encoding="utf-8")
        print("Summary: "+json.dumps(dict(counts),ensure_ascii=False))
        print(f"Report: {path}")
        return 2 if counts.get("api_error") else 0
    except (OSError,ValueError,RuntimeError,json.JSONDecodeError) as exc:
        print(f"ERROR: {exc}",file=sys.stderr); return 2


if __name__ == "__main__":
    raise SystemExit(main())
