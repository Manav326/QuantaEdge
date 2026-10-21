#!/usr/bin/env python3
"""One-time, package-scoped bulk release for the imported Class 6-8 Hindi tracks.

This exceptional operator tool deliberately bypasses the regular lesson preview/source-review
publish workflow. It targets only codes/prompts found in the supplied Hindi package ZIPs,
backs up local PostgreSQL before writes, preserves archived/inactive content, leaves source
verification flags untouched, and writes both staff audit and question review-history events.
Default mode is a read-only dry run; persistent writes require --apply.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import subprocess
import zipfile
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
CLASSES = ("6", "7", "8")
SUBJECTS = {"maths", "science"}
LANGUAGE = "hi"
LESSONS_PER_CHAPTER = 13
QUESTIONS_PER_CHAPTER = 13


def stop(message: str) -> None:
    raise SystemExit(f"Bulk release stopped safely: {message}")


def literal(value: object) -> str:
    return "'" + str(value).replace("'", "''") + "'"


def find_package(class_code: str, folder: Path) -> Path:
    preferred = [
        folder / f"QuantaEdge_Class{class_code}_QuantaEdge_Hindi_DRAFT_Package.zip",
        folder / f"QuantaEdge_Class{class_code}_NCERT_Hindi_Complete_Content_Package.zip",
        folder / f"QuantaEdge_Class{class_code}_Hindi_DRAFT_Package.zip",
    ]
    for candidate in preferred:
        if candidate.is_file():
            return candidate
    candidates = sorted(p for p in folder.glob(f"QuantaEdge_Class{class_code}*Hindi*Package.zip") if p.is_file())
    if len(candidates) != 1:
        names = ", ".join(p.name for p in candidates) or "(none)"
        stop(f"Class {class_code}: expected one Hindi package in {folder}; candidates: {names}.")
    return candidates[0]


def lesson_db_code(chapter_code: str, lesson_code: str) -> str:
    value = f"{chapter_code}-{lesson_code}"
    return value if len(value) <= 100 else f"{chapter_code[:55]}-{lesson_code[-40:]}"


def read_package(path: Path, expected_class: str) -> list[tuple[str, dict[str, Any]]]:
    found: list[tuple[str, dict[str, Any]]] = []
    try:
        with zipfile.ZipFile(path) as archive:
            members = sorted(
                name for name in archive.namelist()
                if "/content/bundles/" in ("/" + name)
                and name.endswith(".json")
                and ("/maths/" in ("/" + name) or "/science/" in ("/" + name))
            )
            for name in members:
                try:
                    bundle = json.loads(archive.read(name).decode("utf-8-sig"))
                except (UnicodeDecodeError, json.JSONDecodeError) as exc:
                    stop(f"Cannot parse {name} in {path.name}: {exc}")
                curriculum = bundle.get("curriculum", {}) if isinstance(bundle, dict) else {}
                if str(curriculum.get("class_code", "")) != expected_class:
                    continue
                if str(curriculum.get("language", "")).lower() != LANGUAGE:
                    stop(f"{name}: expected Hindi ('hi') content only.")
                subject = str(curriculum.get("subject_code", "")).lower()
                if subject not in SUBJECTS:
                    stop(f"{name}: unsupported subject {subject!r}.")
                found.append((name, bundle))
    except (OSError, zipfile.BadZipFile) as exc:
        stop(f"Cannot open {path}: {exc}")
    if not found:
        stop(f"No Class {expected_class} Hindi Maths/Science bundles found in {path.name}.")
    return found


def build_targets(folder: Path):
    packages = [find_package(c, folder) for c in CLASSES]
    chapter_rows: set[tuple[str, str, str]] = set()
    lesson_rows: set[tuple[str, str, str, str]] = set()
    question_rows: set[tuple[str, str, str, str, str, str]] = set()
    counts: dict[str, int] = {c: 0 for c in CLASSES}
    subject_counts: dict[tuple[str, str], int] = {}

    for class_code, package in zip(CLASSES, packages):
        for source_name, bundle in read_package(package, class_code):
            curriculum = bundle["curriculum"]
            subject = str(curriculum["subject_code"]).lower()
            chapter = bundle.get("chapter", {})
            chapter_code = str(chapter.get("code", "")).strip().lower()
            if not chapter_code:
                stop(f"{source_name}: missing chapter.code.")
            lessons = bundle.get("lessons")
            questions = bundle.get("online_question_bank")
            if not isinstance(lessons, list) or len(lessons) != LESSONS_PER_CHAPTER:
                stop(f"{source_name}: expected {LESSONS_PER_CHAPTER} lessons; found {len(lessons) if isinstance(lessons, list) else 'invalid'}.")
            if not isinstance(questions, list) or len(questions) != QUESTIONS_PER_CHAPTER:
                stop(f"{source_name}: expected {QUESTIONS_PER_CHAPTER} questions; found {len(questions) if isinstance(questions, list) else 'invalid'}.")
            bundle_counts = {str(item.get("code", "")) for item in lessons if isinstance(item, dict)}
            if len(bundle_counts) != len(lessons) or "" in bundle_counts:
                stop(f"{source_name}: duplicate/missing lesson codes.")
            mapping = {old: lesson_db_code(chapter_code, old) for old in bundle_counts}
            if len(set(mapping.values())) != len(mapping):
                stop(f"{source_name}: duplicate lesson codes after namespacing.")
            if (class_code, subject, chapter_code) in chapter_rows:
                stop(f"Duplicate chapter target: {class_code}/{subject}/{chapter_code}.")
            chapter_rows.add((class_code, subject, chapter_code))
            counts[class_code] += 1
            subject_counts[(class_code, subject)] = subject_counts.get((class_code, subject), 0) + 1

            for old in bundle_counts:
                lesson_rows.add((class_code, subject, chapter_code, mapping[old]))
            per_lesson_questions: dict[str, int] = {code: 0 for code in bundle_counts}
            for q in questions:
                if not isinstance(q, dict):
                    stop(f"{source_name}: question entry is not an object.")
                old_code = str(q.get("lesson_code", ""))
                if old_code not in mapping:
                    stop(f"{source_name}: question {q.get('id')} points to unknown lesson {old_code!r}.")
                qtype = str(q.get("question_type", "")).upper()
                prompt = q.get("prompt")
                if not qtype or not isinstance(prompt, str) or not prompt.strip():
                    stop(f"{source_name}: a question has a missing type or prompt.")
                key = (class_code, subject, chapter_code, mapping[old_code], qtype, prompt)
                if key in question_rows:
                    stop(f"{source_name}: duplicate target question for lesson {mapping[old_code]}.")
                question_rows.add(key)
                per_lesson_questions[old_code] += 1
            if any(value != 1 for value in per_lesson_questions.values()):
                stop(f"{source_name}: expected exactly one question per lesson for this one-time rollout.")

    if not chapter_rows or any(subject_counts.get((c, s), 0) == 0 for c in CLASSES for s in SUBJECTS):
        stop("Each class must have at least one Maths and one Science chapter.")
    if len(lesson_rows) != len(chapter_rows) * LESSONS_PER_CHAPTER:
        stop("Lesson target count is inconsistent with the chapter packages.")
    if len(question_rows) != len(chapter_rows) * QUESTIONS_PER_CHAPTER:
        stop("Question target count is inconsistent with the chapter packages.")
    return packages, sorted(chapter_rows), sorted(lesson_rows), sorted(question_rows), counts


def build_sql(packages, chapters, lessons, questions, apply: bool) -> str:
    chapter_values = ",\n".join("(" + ",".join(literal(v) for v in row) + ")" for row in chapters)
    lesson_values = ",\n".join("(" + ",".join(literal(v) for v in row) + ")" for row in lessons)
    question_values = ",\n".join("(" + ",".join(literal(v) for v in row) + ")" for row in questions)
    package_names = ", ".join(p.name for p in packages)
    run_stamp = dt.datetime.now().astimezone().isoformat(timespec="seconds")
    reason = (
        f"One-time Class 6-8 Hindi package release requested by the operator on {run_stamp}. "
        f"Package scope: {package_names}. This bypasses the normal lesson-by-lesson preview/review "
        "workflow; source verification fields were not changed and this event is not a claim of official source verification."
    )
    preflight = r"""
DO $preflight$
DECLARE n integer;
BEGIN
  IF EXISTS (SELECT 1 FROM app_metadata WHERE key='one-time-ncert-hindi-bulk-release-v1') THEN
    RAISE EXCEPTION 'The one-time Class 6-8 Hindi bulk release has already been applied. Do not run it again.';
  END IF;

  SELECT count(*) INTO n
  FROM qe_release_target_chapter t
  WHERE (SELECT count(*) FROM curriculum_class c
    JOIN curriculum_subject s ON s.class_id=c.id
    JOIN curriculum_chapter ch ON ch.subject_id=s.id
    WHERE c.code=t.class_code AND s.code=t.subject_code AND ch.code=t.chapter_code) <> 1;
  IF n > 0 THEN RAISE EXCEPTION 'Preflight failed: % package chapter(s) are missing or ambiguous in the database.', n; END IF;

  SELECT count(*) INTO n
  FROM qe_release_target_chapter t
  JOIN curriculum_class c ON c.code=t.class_code
  JOIN curriculum_subject s ON s.class_id=c.id AND s.code=t.subject_code
  JOIN curriculum_chapter ch ON ch.subject_id=s.id AND ch.code=t.chapter_code
  WHERE c.active=false OR s.active=false;
  IF n > 0 THEN RAISE EXCEPTION 'Preflight failed: % target track(s) have an inactive class/subject. No release performed.', n; END IF;

  SELECT count(*) INTO n
  FROM qe_release_target_lesson t
  WHERE (SELECT count(*) FROM curriculum_class c
    JOIN curriculum_subject s ON s.class_id=c.id
    JOIN curriculum_chapter ch ON ch.subject_id=s.id
    JOIN lesson l ON l.chapter_id=ch.id
    WHERE c.code=t.class_code AND s.code=t.subject_code AND ch.code=t.chapter_code AND l.code=t.lesson_code) <> 1;
  IF n > 0 THEN RAISE EXCEPTION 'Preflight failed: % package lesson(s) are missing or ambiguous. No release performed.', n; END IF;

  SELECT count(*) INTO n
  FROM qe_release_target_question t
  WHERE (SELECT count(*) FROM curriculum_class c
    JOIN curriculum_subject s ON s.class_id=c.id
    JOIN curriculum_chapter ch ON ch.subject_id=s.id
    JOIN lesson l ON l.chapter_id=ch.id
    JOIN question q ON q.lesson_id=l.id
    WHERE c.code=t.class_code AND s.code=t.subject_code
      AND ch.code=t.chapter_code AND l.code=t.lesson_code
      AND upper(q.question_type)=t.question_type AND q.prompt=t.prompt) <> 1;
  IF n > 0 THEN RAISE EXCEPTION 'Preflight failed: % target question(s) are missing/duplicated against the imported lesson content. No release performed.', n; END IF;
END
$preflight$;
"""
    header = f"""
BEGIN;
CREATE TEMP TABLE qe_release_target_chapter(
  class_code text NOT NULL, subject_code text NOT NULL, chapter_code text NOT NULL,
  PRIMARY KEY(class_code,subject_code,chapter_code)
) ON COMMIT DROP;
INSERT INTO qe_release_target_chapter VALUES
{chapter_values};

CREATE TEMP TABLE qe_release_target_lesson(
  class_code text NOT NULL, subject_code text NOT NULL,
  chapter_code text NOT NULL, lesson_code text NOT NULL,
  PRIMARY KEY(class_code,subject_code,chapter_code,lesson_code)
) ON COMMIT DROP;
INSERT INTO qe_release_target_lesson VALUES
{lesson_values};

CREATE TEMP TABLE qe_release_target_question(
  class_code text NOT NULL, subject_code text NOT NULL, chapter_code text NOT NULL,
  lesson_code text NOT NULL, question_type text NOT NULL, prompt text NOT NULL,
  PRIMARY KEY(class_code,subject_code,chapter_code,lesson_code,question_type,prompt)
) ON COMMIT DROP;
INSERT INTO qe_release_target_question VALUES
{question_values};
{preflight}
"""
    summary = r"""
SELECT jsonb_pretty(jsonb_build_object(
  'target_classes', jsonb_build_array('6','7','8'),
  'target_chapters', (SELECT count(*) FROM qe_release_target_chapter),
  'target_lessons', (SELECT count(*) FROM qe_release_target_lesson),
  'target_questions', (SELECT count(*) FROM qe_release_target_question),
  'archived_chapters_to_preserve', (
    SELECT count(*) FROM qe_release_target_chapter t
    JOIN curriculum_class c ON c.code=t.class_code
    JOIN curriculum_subject s ON s.class_id=c.id AND s.code=t.subject_code
    JOIN curriculum_chapter ch ON ch.subject_id=s.id AND ch.code=t.chapter_code
    WHERE ch.content_status='ARCHIVED' OR ch.active=false
  ),
  'archived_lessons_to_preserve', (
    SELECT count(*) FROM qe_release_target_lesson t
    JOIN curriculum_class c ON c.code=t.class_code
    JOIN curriculum_subject s ON s.class_id=c.id AND s.code=t.subject_code
    JOIN curriculum_chapter ch ON ch.subject_id=s.id AND ch.code=t.chapter_code
    JOIN lesson l ON l.chapter_id=ch.id AND l.code=t.lesson_code
    WHERE l.status='ARCHIVED' OR l.active=false OR ch.content_status='ARCHIVED' OR ch.active=false
  ),
  'source_unverified_chapters_that_will_remain_unverified', (
    SELECT count(*) FROM qe_release_target_chapter t
    JOIN curriculum_class c ON c.code=t.class_code
    JOIN curriculum_subject s ON s.class_id=c.id AND s.code=t.subject_code
    JOIN curriculum_chapter ch ON ch.subject_id=s.id AND ch.code=t.chapter_code
    WHERE ch.curriculum_source_verified=false
  ),
  'questions_to_match_exactly', (SELECT count(*) FROM qe_release_target_question)
));
"""
    if not apply:
        return header + summary + "\nROLLBACK;\n"

    return header + r"""
CREATE TEMP TABLE qe_release_question_before ON COMMIT DROP AS
SELECT q.id AS question_id, q.lesson_id, q.review_status AS previous_status,
       to_jsonb(q) || jsonb_build_object('options', coalesce((
         SELECT jsonb_agg(jsonb_build_object(
           'option_key',qo.option_key,'label',qo.label,'is_correct',qo.is_correct,'sort_order',qo.sort_order
         ) ORDER BY qo.sort_order) FROM question_option qo WHERE qo.question_id=q.id
       ), '[]'::jsonb)) AS before_snapshot
FROM qe_release_target_question t
JOIN curriculum_class c ON c.code=t.class_code
JOIN curriculum_subject s ON s.class_id=c.id AND s.code=t.subject_code
JOIN curriculum_chapter ch ON ch.subject_id=s.id AND ch.code=t.chapter_code
JOIN lesson l ON l.chapter_id=ch.id AND l.code=t.lesson_code
JOIN question q ON q.lesson_id=l.id AND upper(q.question_type)=t.question_type AND q.prompt=t.prompt
WHERE c.active=true AND s.active=true AND ch.active=true AND ch.content_status<>'ARCHIVED'
  AND l.active=true AND l.status<>'ARCHIVED' AND q.active=true;

UPDATE curriculum_chapter ch
SET content_status='PUBLISHED'
FROM qe_release_target_chapter t
JOIN curriculum_class c ON c.code=t.class_code
JOIN curriculum_subject s ON s.class_id=c.id AND s.code=t.subject_code
WHERE ch.subject_id=s.id AND ch.code=t.chapter_code
  AND ch.active=true AND ch.content_status<>'ARCHIVED';

UPDATE lesson l
SET status='PUBLISHED'
FROM qe_release_target_lesson t
JOIN curriculum_class c ON c.code=t.class_code
JOIN curriculum_subject s ON s.class_id=c.id AND s.code=t.subject_code
JOIN curriculum_chapter ch ON ch.subject_id=s.id AND ch.code=t.chapter_code
WHERE l.chapter_id=ch.id AND l.code=t.lesson_code
  AND ch.active=true AND ch.content_status='PUBLISHED'
  AND l.active=true AND l.status<>'ARCHIVED';

UPDATE question q
SET review_status='PUBLISHED',
    review_notes=concat_ws(' ',nullif(btrim(q.review_notes),''),
      'One-time bulk release override on __STAMP__; normal per-question review workflow bypassed. This status does not imply official textbook/source verification.')
FROM qe_release_question_before b
WHERE q.id=b.question_id;

INSERT INTO question_review_history(
  question_id,lesson_id,event_type,previous_status,new_status,actor_staff_id,actor_role,
  change_reason,before_snapshot,after_snapshot,occurred_at
)
SELECT q.id,q.lesson_id,'BULK_PUBLISH_OVERRIDE',b.previous_status,q.review_status,
       null,'BULK_RELEASE_OPERATOR',
       'One-time package-scoped Class 6-8 Hindi release explicitly requested by the operator; source verification was not inferred.',
       b.before_snapshot,
       to_jsonb(q) || jsonb_build_object('options',coalesce((
         SELECT jsonb_agg(jsonb_build_object(
           'option_key',qo.option_key,'label',qo.label,'is_correct',qo.is_correct,'sort_order',qo.sort_order
         ) ORDER BY qo.sort_order) FROM question_option qo WHERE qo.question_id=q.id
       ), '[]'::jsonb)),
       now()
FROM qe_release_question_before b
JOIN question q ON q.id=b.question_id;

INSERT INTO app_metadata(key,value)
VALUES ('one-time-ncert-hindi-bulk-release-v1', __MARKER_VALUE__);

INSERT INTO staff_audit_log(
  actor_staff_id,actor_role,http_method,request_path,response_status,details
)
VALUES (
  null,'BULK_RELEASE_OPERATOR','ACTION','/operations/one-time-ncert-hindi-bulk-release',200,
  __REASON__
);

SELECT jsonb_pretty(jsonb_build_object(
  'mode','APPLIED_ONE_TIME_OVERRIDE',
  'target_classes',jsonb_build_array('6','7','8'),
  'target_chapters',(SELECT count(*) FROM qe_release_target_chapter),
  'chapters_published',(SELECT count(*) FROM qe_release_target_chapter t
    JOIN curriculum_class c ON c.code=t.class_code
    JOIN curriculum_subject s ON s.class_id=c.id AND s.code=t.subject_code
    JOIN curriculum_chapter ch ON ch.subject_id=s.id AND ch.code=t.chapter_code
    WHERE ch.content_status='PUBLISHED' AND ch.active=true),
  'target_lessons',(SELECT count(*) FROM qe_release_target_lesson),
  'lessons_published',(SELECT count(*) FROM qe_release_target_lesson t
    JOIN curriculum_class c ON c.code=t.class_code
    JOIN curriculum_subject s ON s.class_id=c.id AND s.code=t.subject_code
    JOIN curriculum_chapter ch ON ch.subject_id=s.id AND ch.code=t.chapter_code
    JOIN lesson l ON l.chapter_id=ch.id AND l.code=t.lesson_code
    WHERE ch.active=true AND ch.content_status='PUBLISHED' AND l.active=true AND l.status='PUBLISHED'),
  'target_questions',(SELECT count(*) FROM qe_release_target_question),
  'questions_published',(SELECT count(*) FROM qe_release_question_before b
    JOIN question q ON q.id=b.question_id WHERE q.active=true AND q.review_status='PUBLISHED'),
  'archived_or_inactive_chapters_preserved',(SELECT count(*) FROM qe_release_target_chapter t
    JOIN curriculum_class c ON c.code=t.class_code
    JOIN curriculum_subject s ON s.class_id=c.id AND s.code=t.subject_code
    JOIN curriculum_chapter ch ON ch.subject_id=s.id AND ch.code=t.chapter_code
    WHERE ch.content_status='ARCHIVED' OR ch.active=false),
  'archived_or_inactive_lessons_preserved',(SELECT count(*) FROM qe_release_target_lesson t
    JOIN curriculum_class c ON c.code=t.class_code
    JOIN curriculum_subject s ON s.class_id=c.id AND s.code=t.subject_code
    JOIN curriculum_chapter ch ON ch.subject_id=s.id AND ch.code=t.chapter_code
    JOIN lesson l ON l.chapter_id=ch.id AND l.code=t.lesson_code
    WHERE l.status='ARCHIVED' OR l.active=false OR ch.content_status='ARCHIVED' OR ch.active=false),
  'question_history_events',(SELECT count(*) FROM qe_release_question_before),
  'source_verification_flags_modified',false,
  'learner_preview_checks_marked_by_script',false,
  'staff_audit_record_created',true
));
COMMIT;
""".replace("__STAMP__", run_stamp).replace("__REASON__", literal(reason)).replace("__MARKER_VALUE__", literal(run_stamp + " | " + package_names))


def execute_psql(sql: str) -> str:
    command = [
        "docker", "compose", "exec", "-T", "postgres", "sh", "-lc",
        'psql -X -q -A -t -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f -',
    ]
    try:
        result = subprocess.run(
            command, cwd=ROOT, input=sql, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, encoding="utf-8", errors="replace"
        )
    except FileNotFoundError:
        stop("Docker CLI was not found. Start Docker Desktop and run this from the repository folder.")
    if result.returncode != 0:
        stop("PostgreSQL command failed; the transaction was rolled back if it had begun.\n" + (result.stderr or result.stdout)[-7000:])
    return result.stdout.strip()


def backup_database(destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    command = [
        "docker", "compose", "exec", "-T", "postgres", "sh", "-lc",
        'pg_dump -Fc -U "$POSTGRES_USER" -d "$POSTGRES_DB"',
    ]
    try:
        result = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    except FileNotFoundError:
        stop("Docker CLI was not found. Start Docker Desktop before running --apply.")
    if result.returncode != 0 or not result.stdout:
        stop("Database backup failed; no publish updates were attempted.\n" + result.stderr.decode("utf-8", "replace")[-5000:])
    destination.write_bytes(result.stdout)
    if destination.stat().st_size == 0:
        destination.unlink(missing_ok=True)
        stop("Database backup was empty; no publish updates were attempted.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package-dir", type=Path, default=Path("."),
                        help="Folder containing the Class 6, 7 and 8 Hindi package ZIPs.")
    parser.add_argument("--apply", action="store_true",
                        help="Back up PostgreSQL and perform the one-time package-scoped release.")
    args = parser.parse_args()
    folder = args.package_dir.resolve()
    if not folder.is_dir():
        stop(f"Package folder does not exist: {folder}")

    packages, chapters, lessons, questions, chapter_counts = build_targets(folder)
    print(json.dumps({
        "mode": "APPLY_REQUESTED" if args.apply else "DRY_RUN",
        "packages": {c: p.name for c, p in zip(CLASSES, packages)},
        "chapters_by_class": chapter_counts,
        "chapter_targets": len(chapters),
        "lesson_targets": len(lessons),
        "question_targets": len(questions),
        "archived_content_will_be_preserved": True,
        "source_verification_flags_will_be_untouched": True,
        "default_is_dry_run": True,
    }, ensure_ascii=False, indent=2))

    if args.apply:
        stamp = dt.datetime.now().astimezone().strftime("%Y%m%d-%H%M%S")
        backup = ROOT / "private-review" / f"quantaedge-pre-bulk-release-{stamp}.dump"
        backup_database(backup)
        print(f"PostgreSQL backup created: {backup.relative_to(ROOT)} ({backup.stat().st_size:,} bytes)")
    sql = build_sql(packages, chapters, lessons, questions, apply=args.apply)
    result = execute_psql(sql)
    if result:
        print(result)
    if args.apply:
        print("Bulk release transaction committed. Backup and package-scoped result are retained under private-review/.")
    else:
        print("Dry run only: PostgreSQL changes were rolled back; no content was published.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
