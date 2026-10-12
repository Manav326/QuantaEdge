[CmdletBinding()]
param(
    [switch]$Apply
)

$ErrorActionPreference = 'Stop'
$RepoRoot = Split-Path -Parent $PSScriptRoot

# Safe by default: this command reports only. -Apply requires an explicit phrase
# and makes a custom-format pg_dump backup before running the transactional purge.
$sql = @'
\set ON_ERROR_STOP on
\if :{?apply_cleanup}
\else
\set apply_cleanup false
\endif

BEGIN;

-- A chapter is purgeable only when deleting its learning path cannot cascade away
-- learner progress, question attempts, practice-session membership, tutor sessions,
-- concept mastery, or a live/non-archived learning document.
CREATE TEMP TABLE qe_purge_chapters ON COMMIT DROP AS
SELECT ch.id
FROM curriculum_chapter ch
WHERE ch.content_status = 'ARCHIVED'
  AND NOT EXISTS (
    SELECT 1
    FROM student_lesson_progress p
    JOIN lesson l ON l.id = p.lesson_id
    WHERE l.chapter_id = ch.id
  )
  AND NOT EXISTS (
    SELECT 1
    FROM tutor_session ts
    JOIN lesson l ON l.id = ts.lesson_id
    WHERE l.chapter_id = ch.id
  )
  AND NOT EXISTS (
    SELECT 1
    FROM question q
    LEFT JOIN lesson l ON l.id = q.lesson_id
    LEFT JOIN learning_objective o ON o.id = q.objective_id
    LEFT JOIN chapter_concept cc ON cc.id = q.concept_id
    WHERE (l.chapter_id = ch.id OR o.chapter_id = ch.id OR cc.chapter_id = ch.id)
      AND (
        EXISTS (SELECT 1 FROM student_question_attempt a WHERE a.question_id = q.id)
        OR EXISTS (SELECT 1 FROM student_practice_session_question psq WHERE psq.question_id = q.id)
      )
  )
  AND NOT EXISTS (
    SELECT 1
    FROM student_concept_mastery m
    JOIN chapter_concept cc ON cc.id = m.concept_id
    WHERE cc.chapter_id = ch.id
  )
  AND NOT EXISTS (
    SELECT 1
    FROM learning_document d
    WHERE d.chapter_id = ch.id
      AND (
        d.status <> 'ARCHIVED'
        OR EXISTS (
          SELECT 1
          FROM student_learning_document_progress p
          WHERE p.learning_document_id = d.id
        )
      )
  );

CREATE TEMP TABLE qe_purge_lessons ON COMMIT DROP AS
SELECT l.id
FROM lesson l
LEFT JOIN curriculum_chapter ch ON ch.id = l.chapter_id
WHERE l.status = 'ARCHIVED'
   OR ch.id IN (SELECT id FROM qe_purge_chapters);

-- A lesson with learner progress, tutor history, question attempts, or practice
-- membership is retained, even if marked ARCHIVED.
DELETE FROM qe_purge_lessons pl
WHERE EXISTS (
        SELECT 1 FROM student_lesson_progress p WHERE p.lesson_id = pl.id
      )
   OR EXISTS (
        SELECT 1 FROM tutor_session ts WHERE ts.lesson_id = pl.id
      )
   OR EXISTS (
        SELECT 1
        FROM question q
        WHERE q.lesson_id = pl.id
          AND (
            EXISTS (SELECT 1 FROM student_question_attempt a WHERE a.question_id = q.id)
            OR EXISTS (SELECT 1 FROM student_practice_session_question psq WHERE psq.question_id = q.id)
          )
      );

CREATE TEMP TABLE qe_purge_objectives ON COMMIT DROP AS
SELECT o.id
FROM learning_objective o
WHERE o.chapter_id IN (SELECT id FROM qe_purge_chapters);

CREATE TEMP TABLE qe_purge_questions ON COMMIT DROP AS
SELECT q.id
FROM question q
WHERE (
       q.review_status = 'ARCHIVED'
       OR q.lesson_id IN (SELECT id FROM qe_purge_lessons)
       OR (
         q.lesson_id IS NULL
         AND (
           q.objective_id IN (SELECT id FROM qe_purge_objectives)
           OR q.concept_id IN (
             SELECT cc.id
             FROM chapter_concept cc
             WHERE cc.chapter_id IN (SELECT id FROM qe_purge_chapters)
           )
         )
       )
      )
  AND NOT EXISTS (
    SELECT 1 FROM student_question_attempt a WHERE a.question_id = q.id
  )
  AND NOT EXISTS (
    SELECT 1 FROM student_practice_session_question psq WHERE psq.question_id = q.id
  );

CREATE TEMP TABLE qe_purge_concepts ON COMMIT DROP AS
SELECT cc.id
FROM chapter_concept cc
WHERE cc.chapter_id IN (SELECT id FROM qe_purge_chapters)
   OR (
     cc.status = 'ARCHIVED'
     AND NOT EXISTS (
       SELECT 1 FROM student_concept_mastery m WHERE m.concept_id = cc.id
     )
     AND NOT EXISTS (
       SELECT 1
       FROM question q
       WHERE q.concept_id = cc.id
         AND q.id NOT IN (SELECT id FROM qe_purge_questions)
     )
   );

CREATE TEMP TABLE qe_purge_assessments ON COMMIT DROP AS
SELECT a.id
FROM assessment a
WHERE a.status = 'ARCHIVED'
  AND NOT EXISTS (
    SELECT 1 FROM student_assessment_attempt sat WHERE sat.assessment_id = a.id
  )
  AND NOT EXISTS (
    SELECT 1 FROM assessment_audit_log aal WHERE aal.assessment_id = a.id
  );

CREATE TEMP TABLE qe_purge_documents ON COMMIT DROP AS
SELECT d.id
FROM learning_document d
WHERE d.status = 'ARCHIVED'
  AND NOT EXISTS (
    SELECT 1
    FROM student_learning_document_progress p
    WHERE p.learning_document_id = d.id
  );

CREATE TEMP TABLE qe_purge_recorded_classes ON COMMIT DROP AS
SELECT rc.id
FROM recorded_class rc
WHERE rc.status = 'ARCHIVED'
  AND NOT EXISTS (
    SELECT 1
    FROM student_recorded_class_progress p
    WHERE p.recorded_class_id = rc.id
  );

CREATE TEMP TABLE qe_purge_programs ON COMMIT DROP AS
SELECT p.id
FROM curriculum_program p
WHERE p.status = 'ARCHIVED'
  AND NOT EXISTS (
    SELECT 1 FROM student_program_enrollment e WHERE e.program_id = p.id
  );

CREATE TEMP TABLE qe_purge_assets ON COMMIT DROP AS
SELECT a.id
FROM content_asset a
WHERE a.status = 'ARCHIVED';

-- Preview counts are also printed in Apply mode, so the exact plan is visible
-- before the script executes any DELETE statement.
SELECT 'curriculum_chapter' AS entity,
       (SELECT COUNT(*) FROM curriculum_chapter WHERE content_status = 'ARCHIVED') AS archived_found,
       (SELECT COUNT(*) FROM qe_purge_chapters) AS eligible_for_delete,
       (SELECT COUNT(*) FROM curriculum_chapter WHERE content_status = 'ARCHIVED')
         - (SELECT COUNT(*) FROM qe_purge_chapters) AS retained_for_history_or_documents
UNION ALL
SELECT 'lesson',
       (SELECT COUNT(*) FROM lesson l LEFT JOIN curriculum_chapter ch ON ch.id = l.chapter_id
        WHERE l.status = 'ARCHIVED' OR ch.content_status = 'ARCHIVED'),
       (SELECT COUNT(*) FROM qe_purge_lessons),
       (SELECT COUNT(*) FROM lesson l LEFT JOIN curriculum_chapter ch ON ch.id = l.chapter_id
        WHERE l.status = 'ARCHIVED' OR ch.content_status = 'ARCHIVED')
         - (SELECT COUNT(*) FROM qe_purge_lessons)
UNION ALL
SELECT 'question',
       (SELECT COUNT(*) FROM question q
        LEFT JOIN lesson l ON l.id = q.lesson_id
        LEFT JOIN curriculum_chapter ch ON ch.id = l.chapter_id
        LEFT JOIN learning_objective o ON o.id = q.objective_id
        LEFT JOIN chapter_concept cc ON cc.id = q.concept_id
        WHERE q.review_status = 'ARCHIVED' OR l.status = 'ARCHIVED' OR ch.content_status = 'ARCHIVED'
           OR (q.lesson_id IS NULL AND (
             o.chapter_id IN (SELECT id FROM curriculum_chapter WHERE content_status = 'ARCHIVED')
             OR cc.chapter_id IN (SELECT id FROM curriculum_chapter WHERE content_status = 'ARCHIVED')
           ))),
       (SELECT COUNT(*) FROM qe_purge_questions),
       (SELECT COUNT(*) FROM question q
        LEFT JOIN lesson l ON l.id = q.lesson_id
        LEFT JOIN curriculum_chapter ch ON ch.id = l.chapter_id
        LEFT JOIN learning_objective o ON o.id = q.objective_id
        LEFT JOIN chapter_concept cc ON cc.id = q.concept_id
        WHERE q.review_status = 'ARCHIVED' OR l.status = 'ARCHIVED' OR ch.content_status = 'ARCHIVED'
           OR (q.lesson_id IS NULL AND (
             o.chapter_id IN (SELECT id FROM curriculum_chapter WHERE content_status = 'ARCHIVED')
             OR cc.chapter_id IN (SELECT id FROM curriculum_chapter WHERE content_status = 'ARCHIVED')
           )))
         - (SELECT COUNT(*) FROM qe_purge_questions)
UNION ALL
SELECT 'assessment', (SELECT COUNT(*) FROM assessment WHERE status = 'ARCHIVED'),
       (SELECT COUNT(*) FROM qe_purge_assessments),
       (SELECT COUNT(*) FROM assessment WHERE status = 'ARCHIVED') - (SELECT COUNT(*) FROM qe_purge_assessments)
UNION ALL
SELECT 'learning_document', (SELECT COUNT(*) FROM learning_document WHERE status = 'ARCHIVED'),
       (SELECT COUNT(*) FROM qe_purge_documents),
       (SELECT COUNT(*) FROM learning_document WHERE status = 'ARCHIVED') - (SELECT COUNT(*) FROM qe_purge_documents)
UNION ALL
SELECT 'recorded_class', (SELECT COUNT(*) FROM recorded_class WHERE status = 'ARCHIVED'),
       (SELECT COUNT(*) FROM qe_purge_recorded_classes),
       (SELECT COUNT(*) FROM recorded_class WHERE status = 'ARCHIVED') - (SELECT COUNT(*) FROM qe_purge_recorded_classes)
UNION ALL
SELECT 'curriculum_program', (SELECT COUNT(*) FROM curriculum_program WHERE status = 'ARCHIVED'),
       (SELECT COUNT(*) FROM qe_purge_programs),
       (SELECT COUNT(*) FROM curriculum_program WHERE status = 'ARCHIVED') - (SELECT COUNT(*) FROM qe_purge_programs)
UNION ALL
SELECT 'chapter_concept',
       (SELECT COUNT(*) FROM chapter_concept cc
        WHERE cc.status = 'ARCHIVED'
           OR cc.chapter_id IN (SELECT id FROM curriculum_chapter WHERE content_status = 'ARCHIVED')),
       (SELECT COUNT(*) FROM qe_purge_concepts),
       (SELECT COUNT(*) FROM chapter_concept cc
        WHERE cc.status = 'ARCHIVED'
           OR cc.chapter_id IN (SELECT id FROM curriculum_chapter WHERE content_status = 'ARCHIVED'))
         - (SELECT COUNT(*) FROM qe_purge_concepts)
UNION ALL
SELECT 'content_asset', (SELECT COUNT(*) FROM content_asset WHERE status = 'ARCHIVED'),
       (SELECT COUNT(*) FROM qe_purge_assets),
       (SELECT COUNT(*) FROM content_asset WHERE status = 'ARCHIVED') - (SELECT COUNT(*) FROM qe_purge_assets);

\if :apply_cleanup

-- Preserve non-archived linked objects and records with assessment history by
-- detaching them from chapters that are being removed.
UPDATE assessment
SET chapter_id = NULL
WHERE chapter_id IN (SELECT id FROM qe_purge_chapters)
  AND id NOT IN (SELECT id FROM qe_purge_assessments);

UPDATE learning_class_session
SET chapter_id = NULL
WHERE chapter_id IN (SELECT id FROM qe_purge_chapters);

UPDATE recorded_class
SET chapter_id = NULL
WHERE chapter_id IN (SELECT id FROM qe_purge_chapters)
  AND id NOT IN (SELECT id FROM qe_purge_recorded_classes);

-- Remove ingestion-to-chapter associations for chapters being purged, while
-- retaining the source ingestion job and the original PDF asset/provenance.
DELETE FROM source_ingestion_chapter
WHERE chapter_id IN (SELECT id FROM qe_purge_chapters);

DELETE FROM learning_document
WHERE id IN (SELECT id FROM qe_purge_documents);

DELETE FROM assessment
WHERE id IN (SELECT id FROM qe_purge_assessments);

DELETE FROM recorded_class
WHERE id IN (SELECT id FROM qe_purge_recorded_classes);

-- Delete only questions without learner attempts or practice-session membership.
-- Review-history snapshots remain as audit records.
DELETE FROM question
WHERE id IN (SELECT id FROM qe_purge_questions);

DELETE FROM lesson
WHERE id IN (SELECT id FROM qe_purge_lessons);

-- Clear stale cross-links on retained content before removing archived objectives
-- and concepts; this does not delete the retained question/lesson itself.
UPDATE question
SET objective_id = NULL
WHERE objective_id IN (SELECT id FROM qe_purge_objectives);

UPDATE lesson
SET objective_id = NULL
WHERE objective_id IN (SELECT id FROM qe_purge_objectives);

UPDATE question
SET concept_id = NULL
WHERE concept_id IN (SELECT id FROM qe_purge_concepts);

DELETE FROM learning_objective
WHERE id IN (SELECT id FROM qe_purge_objectives);

DELETE FROM chapter_concept
WHERE id IN (SELECT id FROM qe_purge_concepts);

DELETE FROM curriculum_chapter
WHERE id IN (SELECT id FROM qe_purge_chapters);

DELETE FROM curriculum_program
WHERE id IN (SELECT id FROM qe_purge_programs);

-- Archived rich-media metadata is removed. Referencing lesson blocks are retained;
-- the schema sets their asset_id to NULL automatically.
DELETE FROM content_asset
WHERE id IN (SELECT id FROM qe_purge_assets);

COMMIT;
\else
ROLLBACK;
\endif
'@

function Invoke-QeArchivedCleanupSql {
    param([Parameter(Mandatory = $true)][bool]$DoApply)

    $flag = if ($DoApply) { 'true' } else { 'false' }
    $psqlCommand = 'psql -X -v ON_ERROR_STOP=1 -v apply_cleanup=' + $flag +
        ' -U "$POSTGRES_USER" -d "$POSTGRES_DB"'

    $sql | & docker compose exec -T postgres sh -lc $psqlCommand
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "Archived-content cleanup SQL failed with exit code $exitCode. PostgreSQL should have rolled back the transaction."
    }
}

if (-not $Apply) {
    Write-Host 'DRY RUN: no archived records will be deleted.'
    Invoke-QeArchivedCleanupSql -DoApply:$false
    Write-Host ''
    Write-Host 'Review the eligible_for_delete and retained_for_history_or_documents columns above.'
    Write-Host 'To execute after reviewing the plan, use -Apply. A database backup is taken first.'
    exit 0
}

$confirmation = Read-Host 'This permanently deletes eligible archived content. Type DELETE ARCHIVED QUANTAEDGE CONTENT to continue'
if ($confirmation -cne 'DELETE ARCHIVED QUANTAEDGE CONTENT') {
    throw 'Confirmation did not match. Nothing was changed.'
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backupDirectory = Join-Path $env:TEMP 'QuantaEdge-archived-content-backups'
New-Item -ItemType Directory -Path $backupDirectory -Force | Out-Null
$backupPath = Join-Path $backupDirectory "quantaedge-before-archive-cleanup-$stamp.dump"
$containerDumpPath = "/tmp/quantaedge-before-archive-cleanup-$stamp.dump"

$containerId = (& docker compose ps -q postgres).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($containerId)) {
    throw 'The postgres container is not running. Nothing was changed.'
}

Write-Host "Creating database backup: $backupPath"
$dumpCommand = 'pg_dump -Fc -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f ' + $containerDumpPath
& docker compose exec -T postgres sh -lc $dumpCommand
if ($LASTEXITCODE -ne 0) {
    throw 'Database backup failed. Cleanup was not started.'
}

$containerBackupSpec = $containerId + ':' + $containerDumpPath
& docker cp $containerBackupSpec $backupPath
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $backupPath -PathType Leaf) -or
    (Get-Item -LiteralPath $backupPath -ErrorAction SilentlyContinue).Length -le 0) {
    throw 'Could not copy the database backup to the host. Cleanup was not started; the temporary dump remains in the postgres container.'
}

& docker compose exec -T postgres rm -f $containerDumpPath
Write-Host 'Backup completed successfully.'
Write-Host 'Executing the transactionally guarded archived-content cleanup...'
Invoke-QeArchivedCleanupSql -DoApply:$true
Write-Host "Archived-content cleanup completed. Backup retained at: $backupPath"
