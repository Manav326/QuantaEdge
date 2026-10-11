#!/usr/bin/env python3
"""Generate contextual Hindi/English draft translations from the QuantaEdge database.

Safety guarantees:
- Reads are performed through the local Postgres Docker service; no production URL is assumed.
- Target records are inserted as DRAFT only. This script never approves or publishes a translation.
- It never translates evaluation answer payloads/correct-answer keys.
- Existing translations for the same source revision are skipped, making reruns resumable.
- Source revisions are versioned so a changed source never silently overwrites a translation.
- Generated JSONL and a summary report make the run auditable.

Requires: Python 3.10+, Docker Compose, psql in the postgres container, OPENAI_API_KEY.
The model is configurable with OPENAI_TRANSLATION_MODEL or --model.
"""

from __future__ import annotations

import argparse
import collections
import datetime as dt
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT_DIR = REPO_ROOT / "QuantaEdge_Bilingual_Draft_Bundle"
ALLOWED_CLASSES = {"6", "7", "8"}
ALLOWED_SUBJECTS = {"maths", "science"}
ALLOWED_LANGUAGES = {"hi", "en"}
LANGUAGE_NAMES = {"hi": "Hindi written naturally in Devanagari", "en": "clear, age-appropriate English"}
SKIP_STATUSES = {"DRAFT", "IN_REVIEW", "APPROVED", "PUBLISHED"}
PROTECTED_KEYS = {
    "id", "code", "url", "href", "src", "assetid", "asset_id", "storagekey",
    "correctoption", "correct_option", "correctanswer", "correct_answer",
    "answerpayload", "answer_payload", "answerkey", "answer_key", "expectedanswer",
    "expected_answer", "kind", "type", "questiontype", "question_type",
    "language", "locale", "formula", "latex", "mathml", "math", "sha256",
    "source_ref", "source_year", "exam_format", "difficulty", "option_key",
    "key", "is_correct", "correct", "response_mode", "status", "sequence_no",
    "sequence", "sort_order", "marks", "max_marks", "asset_url", "asset_alt",
    "image_url", "imageurl", "video_url", "audio_url", "mime_type"
}

# Table-specific source and translation fields; names are fixed constants, never user input.
INSERT_SPECS = {
    "curriculum_class": {
        "table": "curriculum_class_translation", "fk": "class_id",
        "fields": [("display_name", "display_name", "text")],
    },
    "curriculum_program": {
        "table": "curriculum_program_translation", "fk": "program_id",
        "fields": [("display_name", "display_name", "text")],
    },
    "curriculum_subject": {
        "table": "curriculum_subject_translation", "fk": "subject_id",
        "fields": [("display_name", "display_name", "text")],
    },
    "curriculum_chapter": {
        "table": "curriculum_chapter_translation", "fk": "chapter_id",
        "fields": [("display_name", "display_name", "text"), ("description", "description", "text")],
    },
    "learning_objective": {
        "table": "learning_objective_translation", "fk": "objective_id",
        "fields": [("title", "title", "text"), ("description", "description", "text")],
    },
    "lesson": {
        "table": "lesson_translation", "fk": "lesson_id",
        "fields": [("title", "title", "text"), ("summary", "summary", "text")],
    },
    "lesson_block": {
        "table": "lesson_block_translation", "fk": "block_id",
        "fields": [("content", "content", "jsonb")],
    },
    "question": {
        "table": "question_translation", "fk": "question_id",
        "fields": [
            ("prompt", "prompt", "text"), ("explanation", "explanation", "text"),
            ("options", "options", "jsonb"), ("topic", "topic", "text"),
            ("subtopic", "subtopic", "text"), ("skill", "skill", "text"), ("tags", "tags", "jsonb"),
        ],
    },
    "assessment": {
        "table": "assessment_translation", "fk": "assessment_id",
        "fields": [("title", "title", "text"), ("description", "description", "text")],
    },
    "assessment_question": {
        "table": "assessment_question_translation", "fk": "assessment_question_id",
        "fields": [("prompt", "prompt", "text"), ("explanation", "explanation", "text"), ("options", "options", "jsonb")],
    },
    "learning_class_session": {
        "table": "learning_class_session_translation", "fk": "session_id",
        "fields": [("title", "title", "text"), ("description", "description", "text")],
    },
    "recorded_class": {
        "table": "recorded_class_translation", "fk": "recorded_class_id",
        "fields": [("title", "title", "text"), ("description", "description", "text")],
    },
}

def sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"

def validate_codes(raw: str, allowed: set[str], label: str) -> list[str]:
    values = [item.strip() for item in raw.split(",") if item.strip()]
    if not values or any(value not in allowed for value in values):
        raise ValueError(f"{label} must be comma-separated values from: {', '.join(sorted(allowed))}")
    return list(dict.fromkeys(values))

def make_source_sql(class_codes: list[str], subject_codes: list[str], include_archived: bool) -> str:
    classes = ",".join(sql_literal(x) for x in class_codes)
    subjects = ",".join(sql_literal(x) for x in subject_codes)
    chapter_filter = "" if include_archived else " AND ch.active = true AND ch.content_status <> 'ARCHIVED'"
    objective_filter = "" if include_archived else " AND o.active = true"
    lesson_filter = "" if include_archived else " AND l.active = true AND l.status <> 'ARCHIVED'"
    block_filter = "" if include_archived else " AND b.active = true"
    question_filter = "" if include_archived else " AND q.active = true AND q.review_status NOT IN ('REJECTED','ARCHIVED')"
    assessment_filter = "" if include_archived else " AND a.status <> 'ARCHIVED'"
    session_filter = "" if include_archived else " AND cs.status <> 'CANCELLED'"
    recording_filter = "" if include_archived else " AND rc.status <> 'ARCHIVED'"
    current_chapter_filter = "" if include_archived else " AND ch.active=true AND ch.content_status <> 'ARCHIVED'"
    current_lesson_parent_filter = "" if include_archived else " AND ch.active=true AND ch.content_status <> 'ARCHIVED' AND l.active=true AND l.status <> 'ARCHIVED'"
    current_optional_chapter_filter = "" if include_archived else " AND (ch.id IS NULL OR (ch.active=true AND ch.content_status <> 'ARCHIVED'))"

    # Each source_hash is computed by PostgreSQL from the exact source fields used by the API.
    return f"""
WITH sources AS (
  SELECT 'curriculum_class'::text AS entity_type, c.id::bigint AS entity_id,
         md5(jsonb_build_object('display_name',c.display_name)::text) AS source_hash,
         jsonb_build_object('display_name',c.display_name) AS source_content,
         jsonb_build_object('class_code',c.code,'class_name',c.display_name) AS context
  FROM curriculum_class c
  WHERE c.code IN ({classes})

  UNION ALL
  SELECT 'curriculum_program', p.id,
         md5(jsonb_build_object('display_name',p.display_name)::text),
         jsonb_build_object('display_name',p.display_name),
         jsonb_build_object('class_code',c.code,'subject_code','all','chapter_name',p.display_name,
                            'program_code',p.code,'stream_code',p.stream_code)
  FROM curriculum_program p JOIN curriculum_class c ON c.id=p.class_id
  WHERE c.code IN ({classes}) AND p.status <> 'ARCHIVED'

  UNION ALL
  SELECT 'curriculum_subject',s.id,
         md5(jsonb_build_object('display_name',s.display_name)::text),
         jsonb_build_object('display_name',s.display_name),
         jsonb_build_object('class_code',c.code,'subject_code',s.code,'chapter_name',s.display_name)
  FROM curriculum_subject s JOIN curriculum_class c ON c.id=s.class_id
  WHERE c.code IN ({classes}) AND s.code IN ({subjects}) AND s.active=true

  UNION ALL
  SELECT 'curriculum_chapter',ch.id,
         md5(jsonb_build_object('display_name',ch.display_name,'description',ch.description)::text),
         jsonb_build_object('display_name',ch.display_name,'description',ch.description),
         jsonb_build_object('class_code',c.code,'subject_code',s.code,'chapter_name',ch.display_name)
  FROM curriculum_chapter ch JOIN curriculum_subject s ON s.id=ch.subject_id
  JOIN curriculum_class c ON c.id=s.class_id
  WHERE c.code IN ({classes}) AND s.code IN ({subjects}) {chapter_filter}

  UNION ALL
  SELECT 'learning_objective',o.id,
         md5(jsonb_build_object('title',o.title,'description',o.description)::text),
         jsonb_build_object('title',o.title,'description',o.description),
         jsonb_build_object('class_code',c.code,'subject_code',s.code,'chapter_name',ch.display_name,
                            'lesson_title',o.title)
  FROM learning_objective o JOIN curriculum_chapter ch ON ch.id=o.chapter_id
  JOIN curriculum_subject s ON s.id=ch.subject_id JOIN curriculum_class c ON c.id=s.class_id
  WHERE c.code IN ({classes}) AND s.code IN ({subjects}) {chapter_filter}{objective_filter}

  UNION ALL
  SELECT 'lesson',l.id,
         md5(jsonb_build_object('title',l.title,'summary',l.summary)::text),
         jsonb_build_object('title',l.title,'summary',l.summary),
         jsonb_build_object('class_code',c.code,'subject_code',s.code,'chapter_name',ch.display_name,
                            'lesson_title',l.title)
  FROM lesson l JOIN curriculum_chapter ch ON ch.id=l.chapter_id
  JOIN curriculum_subject s ON s.id=ch.subject_id JOIN curriculum_class c ON c.id=s.class_id
  WHERE c.code IN ({classes}) AND s.code IN ({subjects}) {lesson_filter}{current_chapter_filter}

  UNION ALL
  SELECT 'lesson_block',b.id, md5(b.content::text), b.content,
         jsonb_build_object('class_code',c.code,'subject_code',s.code,'chapter_name',ch.display_name,
                            'lesson_title',l.title,'block_type',b.block_type)
  FROM lesson_block b JOIN lesson l ON l.id=b.lesson_id
  JOIN curriculum_chapter ch ON ch.id=l.chapter_id
  JOIN curriculum_subject s ON s.id=ch.subject_id JOIN curriculum_class c ON c.id=s.class_id
  WHERE c.code IN ({classes}) AND s.code IN ({subjects}) {block_filter}{current_lesson_parent_filter}

  UNION ALL
  SELECT 'question',q.id,
         md5(jsonb_build_object('prompt',q.prompt,'explanation',q.explanation,'options',COALESCE((
           SELECT jsonb_agg(jsonb_build_object('key',qo.option_key,'label',qo.label) ORDER BY qo.sort_order)
           FROM question_option qo WHERE qo.question_id=q.id
         ),'[]'::jsonb),'topic',q.topic,'subtopic',q.subtopic,'skill',q.skill,'tags',q.tags)::text),
         jsonb_build_object('prompt',q.prompt,'explanation',q.explanation,'options',COALESCE((
           SELECT jsonb_agg(jsonb_build_object('key',qo.option_key,'label',qo.label) ORDER BY qo.sort_order)
           FROM question_option qo WHERE qo.question_id=q.id
         ),'[]'::jsonb),'topic',q.topic,'subtopic',q.subtopic,'skill',q.skill,'tags',q.tags),
         jsonb_build_object('class_code',c.code,'subject_code',s.code,'chapter_name',ch.display_name,
                            'lesson_title',l.title,'question_type',q.question_type)
  FROM question q JOIN lesson l ON l.id=q.lesson_id
  JOIN curriculum_chapter ch ON ch.id=l.chapter_id
  JOIN curriculum_subject s ON s.id=ch.subject_id JOIN curriculum_class c ON c.id=s.class_id
  WHERE c.code IN ({classes}) AND s.code IN ({subjects}) {question_filter}{current_lesson_parent_filter}

  UNION ALL
  SELECT 'assessment',a.id,
         md5(jsonb_build_object('title',a.title,'description',a.description)::text),
         jsonb_build_object('title',a.title,'description',a.description),
         jsonb_build_object('class_code',c.code,'subject_code',s.code,
                            'chapter_name',ch.display_name,'lesson_title',a.title)
  FROM assessment a JOIN curriculum_subject s ON s.id=a.subject_id
  JOIN curriculum_class c ON c.id=s.class_id
  LEFT JOIN curriculum_chapter ch ON ch.id=a.chapter_id
  WHERE c.code IN ({classes}) AND s.code IN ({subjects}) {assessment_filter}{current_optional_chapter_filter}

  UNION ALL
  SELECT 'assessment_question',aq.id,
         md5(jsonb_build_object(
           'prompt',aq.question_snapshot->>'prompt',
           'explanation',aq.question_snapshot->>'explanation',
           'options',COALESCE((
             SELECT jsonb_agg(jsonb_build_object(
               'key',COALESCE(opt.value->>'key',opt.value->>'option_key'),
               'label',opt.value->>'label') ORDER BY opt.ordinality)
             FROM jsonb_array_elements(CASE
               WHEN jsonb_typeof(aq.question_snapshot->'options')='array'
               THEN aq.question_snapshot->'options' ELSE '[]'::jsonb END)
               WITH ORDINALITY AS opt(value,ordinality)
           ),'[]'::jsonb)
         )::text),
         jsonb_build_object(
           'prompt',aq.question_snapshot->>'prompt',
           'explanation',aq.question_snapshot->>'explanation',
           'options',COALESCE((
             SELECT jsonb_agg(jsonb_build_object(
               'key',COALESCE(opt.value->>'key',opt.value->>'option_key'),
               'label',opt.value->>'label') ORDER BY opt.ordinality)
             FROM jsonb_array_elements(CASE
               WHEN jsonb_typeof(aq.question_snapshot->'options')='array'
               THEN aq.question_snapshot->'options' ELSE '[]'::jsonb END)
               WITH ORDINALITY AS opt(value,ordinality)
           ),'[]'::jsonb)
         ),
         jsonb_build_object('class_code',c.code,'subject_code',s.code,
                            'chapter_name',ch.display_name,'lesson_title',a.title)
  FROM assessment_question aq JOIN assessment a ON a.id=aq.assessment_id
  JOIN curriculum_subject s ON s.id=a.subject_id JOIN curriculum_class c ON c.id=s.class_id
  LEFT JOIN curriculum_chapter ch ON ch.id=a.chapter_id
  WHERE c.code IN ({classes}) AND s.code IN ({subjects}) {assessment_filter}{current_optional_chapter_filter}

  UNION ALL
  SELECT 'learning_class_session',cs.id,
         md5(jsonb_build_object('title',cs.title,'description',cs.description)::text),
         jsonb_build_object('title',cs.title,'description',cs.description),
         jsonb_build_object('class_code',c.code,'subject_code',s.code,'chapter_name',ch.display_name,
                            'lesson_title',cs.title)
  FROM learning_class_session cs JOIN curriculum_subject s ON s.id=cs.subject_id
  JOIN curriculum_class c ON c.id=s.class_id
  LEFT JOIN curriculum_chapter ch ON ch.id=cs.chapter_id
  WHERE c.code IN ({classes}) AND s.code IN ({subjects}) {session_filter}{current_optional_chapter_filter}

  UNION ALL
  SELECT 'recorded_class',rc.id,
         md5(jsonb_build_object('title',rc.title,'description',rc.description)::text),
         jsonb_build_object('title',rc.title,'description',rc.description),
         jsonb_build_object('class_code',c.code,'subject_code',s.code,'chapter_name',ch.display_name,
                            'lesson_title',rc.title)
  FROM recorded_class rc JOIN curriculum_subject s ON s.id=rc.subject_id
  JOIN curriculum_class c ON c.id=s.class_id
  LEFT JOIN curriculum_chapter ch ON ch.id=rc.chapter_id
  WHERE c.code IN ({classes}) AND s.code IN ({subjects}) {recording_filter}{current_optional_chapter_filter}
), existing AS (
  SELECT 'curriculum_class'::text entity_type,class_id entity_id,language,source_hash,version_no,translation_status FROM curriculum_class_translation
  UNION ALL SELECT 'curriculum_program',program_id,language,source_hash,version_no,translation_status FROM curriculum_program_translation
  UNION ALL SELECT 'curriculum_subject',subject_id,language,source_hash,version_no,translation_status FROM curriculum_subject_translation
  UNION ALL SELECT 'curriculum_chapter',chapter_id,language,source_hash,version_no,translation_status FROM curriculum_chapter_translation
  UNION ALL SELECT 'learning_objective',objective_id,language,source_hash,version_no,translation_status FROM learning_objective_translation
  UNION ALL SELECT 'lesson',lesson_id,language,source_hash,version_no,translation_status FROM lesson_translation
  UNION ALL SELECT 'lesson_block',block_id,language,source_hash,version_no,translation_status FROM lesson_block_translation
  UNION ALL SELECT 'question',question_id,language,source_hash,version_no,translation_status FROM question_translation
  UNION ALL SELECT 'assessment',assessment_id,language,source_hash,version_no,translation_status FROM assessment_translation
  UNION ALL SELECT 'assessment_question',assessment_question_id,language,source_hash,version_no,translation_status FROM assessment_question_translation
  UNION ALL SELECT 'learning_class_session',session_id,language,source_hash,version_no,translation_status FROM learning_class_session_translation
  UNION ALL SELECT 'recorded_class',recorded_class_id,language,source_hash,version_no,translation_status FROM recorded_class_translation
)
SELECT COALESCE(jsonb_agg(jsonb_build_object(
  'entity_type',s.entity_type,'entity_id',s.entity_id,'source_hash',s.source_hash,
  'source_content',s.source_content,'context',s.context,
  'existing',COALESCE(e.rows,'[]'::jsonb)
) ORDER BY s.entity_type,s.entity_id),'[]'::jsonb)::text
FROM sources s
LEFT JOIN LATERAL (
  SELECT jsonb_agg(jsonb_build_object(
    'language',x.language,'source_hash',x.source_hash,'version_no',x.version_no,
    'translation_status',x.translation_status
  ) ORDER BY x.version_no) AS rows
  FROM existing x
  WHERE x.entity_type=s.entity_type AND x.entity_id=s.entity_id AND x.source_hash=s.source_hash
) e ON true;
"""

def psql(sql: str, *, readonly: bool = False, timeout: int = 180) -> str:
    shell = 'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atq'
    if readonly:
        sql = "BEGIN TRANSACTION READ ONLY;\n" + sql + "\nROLLBACK;\n"
    proc = subprocess.run(
        ["docker", "compose", "exec", "-T", "postgres", "sh", "-lc", shell],
        cwd=REPO_ROOT, input=sql, text=True, encoding="utf-8",
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout,
    )
    if proc.returncode != 0:
        raise RuntimeError("psql failed:\n" + proc.stderr.strip())
    return proc.stdout.strip()

def parse_json_response(raw: str) -> dict[str, Any]:
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        match = re.search(r"\{[\s\S]*\}", raw)
        if not match:
            raise ValueError("The model response did not contain a JSON object.")
        data = json.loads(match.group(0))
    if not isinstance(data, dict):
        raise ValueError("The model response must be a JSON object.")
    return data

def translate_batch(items: list[dict[str, Any]], language: str, model: str, api_key: str,
                    base_url: str, timeout: int, max_retries: int) -> dict[str, dict[str, Any]]:
    system_prompt = (
        "You are a senior school textbook translator and subject editor for an Indian school curriculum. "
        "Translate educational content contextually, not word-for-word. Preserve the concept, age level, "
        "all mathematical/scientific meaning, units, examples, values, steps, answer logic, option ordering, "
        "HTML/markup, JSON structure, identifiers, and every non-translatable structural value. "
        "Do not add facts, examples, claims, hints, questions, or answers that were not present. "
        "Keep mathematical notation intact. Translate all learner-facing strings and labels. "
        "For Hindi, use natural, grammatically correct Devanagari Hindi; avoid Hinglish and English sentence "
        "structure. Keep standard scientific/mathematical symbols and established technical terms where natural, "
        "and use a brief English term in parentheses only when pedagogically useful. "
        "For English, use clear, age-appropriate textbook English. "
        "Return valid JSON only, exactly matching the requested response schema."
    )
    user_payload = {
        "target_language": LANGUAGE_NAMES[language],
        "output_schema": {
            "translations": [
                {"id": "exact input id", "content": "same JSON shape as source_content"}
            ]
        },
        "items": [
            {
                "id": f"{item['entity_type']}:{item['entity_id']}",
                "context": item.get("context") or {},
                "source_content": item.get("source_content"),
            }
            for item in items
        ],
        "preservation_rules": [
            "Keep all object keys and array lengths/order exactly unchanged.",
            "Do not translate identifiers, URLs, formulas, LaTeX, numeric answer keys or structural markers.",
            "For option objects, translate only their human-readable label; keep keys unchanged.",
            "Never calculate, correct, infer or change an answer.",
            "Translate HTML text without changing tags or attributes.",
            "The output must include exactly one translation for every supplied id.",
        ],
    }
    endpoint = base_url.rstrip("/") + "/v1/chat/completions"
    request_data = {
        "model": model,
        "temperature": 0.1,
        "response_format": {"type": "json_object"},
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": json.dumps(user_payload, ensure_ascii=False)},
        ],
    }
    body = json.dumps(request_data, ensure_ascii=False).encode("utf-8")
    last_error = "Unknown translation error"
    for attempt in range(1, max_retries + 1):
        req = urllib.request.Request(
            endpoint, data=body,
            headers={"Authorization": "Bearer " + api_key, "Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=timeout) as response:
                response_body = response.read().decode("utf-8")
            result = json.loads(response_body)
            content = result["choices"][0]["message"]["content"]
            output = parse_json_response(content)
            translations = output.get("translations")
            if not isinstance(translations, list):
                raise ValueError("The model response is missing the translations array.")
            by_id: dict[str, dict[str, Any]] = {}
            for row in translations:
                if not isinstance(row, dict) or not isinstance(row.get("id"), str) or "content" not in row:
                    raise ValueError("A translation entry is missing id or content.")
                if row["id"] in by_id:
                    raise ValueError("Duplicate translation id: " + row["id"])
                if not isinstance(row["content"], dict):
                    raise ValueError("Translated content must remain a JSON object.")
                by_id[row["id"]] = row["content"]
            expected = {f"{item['entity_type']}:{item['entity_id']}" for item in items}
            if set(by_id) != expected:
                missing, extra = sorted(expected - set(by_id)), sorted(set(by_id) - expected)
                raise ValueError(f"Translation IDs do not match. Missing={missing[:5]}, extra={extra[:5]}.")
            return by_id
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, KeyError,
                json.JSONDecodeError, ValueError) as exc:
            last_error = str(exc)
            if attempt < max_retries:
                time.sleep(min(30, 2 ** (attempt - 1)))
    raise RuntimeError(f"Translation batch failed after {max_retries} attempts: {last_error}")

def check_shape(source: Any, translated: Any, path: str = "$", parent_key: str = "") -> None:
    if isinstance(source, dict):
        if not isinstance(translated, dict) or set(source) != set(translated):
            raise ValueError(f"JSON keys changed at {path}.")
        for key in source:
            check_shape(source[key], translated[key], path + "." + key, key)
        return
    if isinstance(source, list):
        if not isinstance(translated, list) or len(source) != len(translated):
            raise ValueError(f"Array shape/order changed at {path}.")
        for index, (left, right) in enumerate(zip(source, translated)):
            check_shape(left, right, f"{path}[{index}]", parent_key)
        return
    if source is None or isinstance(source, (bool, int, float)):
        if source != translated or type(source) is not type(translated):
            raise ValueError(f"Non-text value changed at {path}.")
        return
    if isinstance(source, str):
        if not isinstance(translated, str):
            raise ValueError(f"Expected text at {path}.")
        normalized_key = re.sub(r"[^a-z0-9_]", "", parent_key.lower())
        if normalized_key in PROTECTED_KEYS and source != translated:
            raise ValueError(f"Protected structural value changed at {path}.")
        if parent_key.lower() == "html":
            if collections.Counter(re.findall(r"</?[a-zA-Z][^>]*>", source)) != collections.Counter(re.findall(r"</?[a-zA-Z][^>]*>", translated)):
                raise ValueError(f"HTML tags changed at {path}.")
        # Numeric values in teaching examples and scientific notation must be retained exactly.
        number_pattern = r"(?<![A-Za-z])[-+]?\d+(?:[.,]\d+)?(?:\s*/\s*\d+)?"
        source_numbers = collections.Counter(re.findall(number_pattern, source))
        translated_numbers = collections.Counter(re.findall(number_pattern, translated))
        if source_numbers != translated_numbers:
            raise ValueError(f"Numeric values changed at {path}: {source_numbers} != {translated_numbers}.")
        return
    raise ValueError(f"Unsupported value type at {path}.")

def set_language_markers(value: Any, language: str) -> Any:
    if isinstance(value, dict):
        result = {}
        for key, child in value.items():
            if key.lower() in {"language", "locale"} and isinstance(child, str):
                result[key] = language
            else:
                result[key] = set_language_markers(child, language)
        return result
    if isinstance(value, list):
        return [set_language_markers(child, language) for child in value]
    return value

def sql_value(value: Any, kind: str) -> str:
    if value is None:
        return "NULL"
    if kind == "jsonb":
        return sql_literal(json.dumps(value, ensure_ascii=False, separators=(",", ":"))) + "::jsonb"
    return sql_literal(str(value))

def insert_statement(item: dict[str, Any], language: str, translated: dict[str, Any],
                     version_no: int, model: str) -> str:
    spec = INSERT_SPECS[item["entity_type"]]
    columns = [spec["fk"], "language", "source_language", "source_hash", "version_no"]
    values: list[str] = [
        str(int(item["entity_id"])), sql_literal(language), "'mixed'",
        sql_literal(item["source_hash"]), str(version_no),
    ]
    for source_key, column, kind in spec["fields"]:
        columns.append(column)
        values.append(sql_value(translated.get(source_key), kind))
    columns.extend(["translation_status", "translation_method", "model_name", "created_at", "updated_at"])
    values.extend(["'DRAFT'", "'AI'", sql_literal(model[:120]), "now()", "now()"])
    table = spec["table"]
    conflict_key = f"{spec['fk']}, language, source_hash, version_no"
    return (
        f"INSERT INTO {table} ({', '.join(columns)}) VALUES ({', '.join(values)}) "
        f"ON CONFLICT ({conflict_key}) DO NOTHING;"
    )

def apply_batch(statements: list[str]) -> None:
    if not statements:
        return
    sql = "BEGIN;\n" + "\n".join(statements) + "\nCOMMIT;\n"
    psql(sql, timeout=240)

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classes", default="6,7,8", help="Class codes; currently 6,7,8.")
    parser.add_argument("--subjects", default="maths,science", help="Subject codes; currently maths,science.")
    parser.add_argument("--languages", default="hi,en", help="Target locales, hi and/or en.")
    parser.add_argument("--include-archived", action="store_true",
                        help="Include archived/inactive historical curriculum rows; normally excluded.")
    parser.add_argument("--audit-only", action="store_true",
                        help="Inventory eligible source rows and existing translation statuses; no model calls or writes.")
    parser.add_argument("--apply", action="store_true",
                        help="Insert generated translations as DRAFT rows. No translations are published.")
    parser.add_argument("--model", default=os.getenv("OPENAI_TRANSLATION_MODEL", "").strip())
    parser.add_argument("--base-url", default=os.getenv("OPENAI_API_BASE_URL", "https://api.openai.com").strip())
    parser.add_argument("--batch-size", type=int, default=20)
    parser.add_argument("--max-items", type=int, default=0, help="Cap translated source entities for a pilot; 0 means all.")
    parser.add_argument("--max-retries", type=int, default=5)
    parser.add_argument("--timeout", type=int, default=90)
    parser.add_argument("--output-dir", default=str(DEFAULT_OUTPUT_DIR))
    args = parser.parse_args()

    try:
        classes = validate_codes(args.classes, ALLOWED_CLASSES, "classes")
        subjects = validate_codes(args.subjects, ALLOWED_SUBJECTS, "subjects")
        languages = validate_codes(args.languages, ALLOWED_LANGUAGES, "languages")
        if not 1 <= args.batch_size <= 20:
            raise ValueError("--batch-size must be between 1 and 20.")
        if not 1 <= args.max_retries <= 5:
            raise ValueError("--max-retries must be between 1 and 5.")
        if args.max_items < 0:
            raise ValueError("--max-items cannot be negative.")
        if not args.audit_only and not args.model:
            raise ValueError("Set OPENAI_TRANSLATION_MODEL or pass --model.")
        api_key = os.getenv("OPENAI_API_KEY", "").strip()
        if not args.audit_only and not api_key:
            raise ValueError("OPENAI_API_KEY is required to generate translations.")
        output_dir = Path(args.output_dir).resolve()
        output_dir.mkdir(parents=True, exist_ok=True)
        timestamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
        drafts_path = output_dir / f"translation-drafts-{timestamp}.jsonl"
        summary_path = output_dir / f"translation-summary-{timestamp}.json"
        errors_path = output_dir / f"translation-errors-{timestamp}.jsonl"

        raw = psql(make_source_sql(classes, subjects, args.include_archived), readonly=True, timeout=300)
        sources = json.loads(raw or "[]")
        if not isinstance(sources, list):
            raise RuntimeError("Database inventory query did not return a JSON array.")

        type_counts = collections.Counter(row["entity_type"] for row in sources)
        existing_counts: collections.Counter[str] = collections.Counter()
        todo: list[tuple[dict[str, Any], str, int]] = []
        for row in sources:
            existing = row.get("existing") or []
            for language in languages:
                same_revision = [e for e in existing
                    if e.get("language") == language and e.get("source_hash") == row["source_hash"]]
                for entry in same_revision:
                    existing_counts[f"{language}:{entry['translation_status']}"] += 1
                if any(e.get("translation_status") in SKIP_STATUSES for e in same_revision):
                    continue
                version = max((int(e.get("version_no") or 0) for e in same_revision), default=0) + 1
                todo.append((row, language, version))

        audit = {
            "created_at": dt.datetime.now(dt.timezone.utc).isoformat(),
            "classes": classes,
            "subjects": subjects,
            "languages": languages,
            "include_archived": args.include_archived,
            "source_entities_total": len(sources),
            "source_entities_by_type": dict(sorted(type_counts.items())),
            "existing_translation_rows_by_language_status": dict(sorted(existing_counts.items())),
            "translation_tasks_pending": len(todo),
            "mode": "audit-only" if args.audit_only else ("apply-draft" if args.apply else "generate-report-only"),
            "translations_published": 0,
            "note": "Generated translations remain DRAFT and are not visible to students until separately reviewed and published.",
        }
        if args.audit_only:
            summary_path.write_text(json.dumps(audit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print(json.dumps(audit, ensure_ascii=False, indent=2))
            print(f"Audit report: {summary_path}")
            return 0

        if args.max_items:
            todo = todo[:args.max_items]
            audit["translation_tasks_selected_after_cap"] = len(todo)

        report_errors: list[dict[str, Any]] = []
        generated = 0
        inserted = 0
        with drafts_path.open("w", encoding="utf-8") as drafts:
            for language in languages:
                lang_tasks = [(row, lang, version) for row, lang, version in todo if lang == language]
                for offset in range(0, len(lang_tasks), args.batch_size):
                    batch = lang_tasks[offset:offset + args.batch_size]
                    try:
                        translated_by_id = translate_batch(
                            [row for row, _, _ in batch], language, args.model, api_key,
                            args.base_url, args.timeout, args.max_retries,
                        )
                        statements: list[str] = []
                        output_rows: list[dict[str, Any]] = []
                        for row, lang, version in batch:
                            identity = f"{row['entity_type']}:{row['entity_id']}"
                            translated = translated_by_id[identity]
                            source = row["source_content"]
                            check_shape(source, translated)
                            translated = set_language_markers(translated, language)
                            record = {
                                "entity_type": row["entity_type"],
                                "entity_id": row["entity_id"],
                                "language": lang,
                                "source_hash": row["source_hash"],
                                "version_no": version,
                                "context": row.get("context") or {},
                                "source_content": source,
                                "translated_content": translated,
                                "translation_status": "DRAFT",
                                "translation_method": "AI",
                                "model_name": args.model,
                            }
                            output_rows.append(record)
                            statements.append(insert_statement(row, lang, translated, version, args.model))
                        for record in output_rows:
                            drafts.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")
                        drafts.flush()
                        generated += len(output_rows)
                        if args.apply:
                            apply_batch(statements)
                            inserted += len(statements)
                        print(
                            f"[{language}] {min(offset + len(batch), len(lang_tasks))}/{len(lang_tasks)} "
                            f"drafts validated" + (f"; {inserted} inserted" if args.apply else ""),
                            file=sys.stderr,
                        )
                    except Exception as exc:
                        details = {
                            "language": language,
                            "items": [f"{row['entity_type']}:{row['entity_id']}" for row, _, _ in batch],
                            "error": str(exc),
                        }
                        report_errors.append(details)
                        with errors_path.open("a", encoding="utf-8") as errors_file:
                            errors_file.write(json.dumps(details, ensure_ascii=False) + "\n")
                        print(f"ERROR [{language}] batch {offset // args.batch_size + 1}: {exc}", file=sys.stderr)

        audit["source_entities_by_type"] = dict(sorted(type_counts.items()))
        audit["translation_tasks_pending"] = len(todo)
        audit["translations_generated_and_validated"] = generated
        audit["translation_drafts_inserted"] = inserted
        audit["failed_batches"] = len(report_errors)
        audit["translations_published"] = 0
        audit["drafts_file"] = str(drafts_path)
        audit["errors_file"] = str(errors_path) if report_errors else None
        summary_path.write_text(json.dumps(audit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(audit, ensure_ascii=False, indent=2))
        print(f"Draft bundle: {drafts_path}")
        print(f"Summary: {summary_path}")
        if report_errors:
            print(f"Some batches failed. Review {errors_path}; rerunning is safe and skips existing drafts.", file=sys.stderr)
            return 2
        return 0
    except Exception as exc:
        print(f"Translation pipeline stopped safely: {exc}", file=sys.stderr)
        return 1

if __name__ == "__main__":
    raise SystemExit(main())
