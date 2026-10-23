-- V170: align assessment-only assignment readiness with the 150-question TRE 3.0 inventory.
-- Keep V168 immutable for databases that may already have applied it; replace only the
-- readiness function's source-count contract and preserve its mapping/review safeguards.
CREATE OR REPLACE FUNCTION assessment_only_course_ready(p_program_id bigint)
RETURNS boolean
LANGUAGE sql
STABLE
AS $$
  SELECT EXISTS (
      SELECT 1 FROM curriculum_program_delivery_mode m
      WHERE m.program_id=p_program_id AND m.delivery_mode='ASSESSMENT_ONLY'
    )
    AND (
      SELECT count(*)
      FROM assessment a
      JOIN curriculum_chapter ch ON ch.id=a.chapter_id AND ch.code='prev-years'
      JOIN curriculum_subject s ON s.id=a.subject_id AND s.code='computer-science'
      JOIN curriculum_program_subject ps ON ps.program_id=a.course_id
        AND ps.subject_id=s.id AND ps.class_id=s.class_id
      WHERE a.course_id=p_program_id
        AND a.assessment_type='PREVIOUS_YEAR_PAPER'
        AND a.source_exam_cycle IN ('TRE 1.0','TRE 2.0','TRE 3.0')
        AND a.source_package_id IN (
          'tre4-bpsc-tre-1-0-computer-science',
          'tre4-bpsc-tre-2-0-computer-science',
          'tre4-bpsc-tre-3-0-computer-science'
        )
        AND a.source_package_version IS NOT NULL
        AND a.source_package_sha256 ~ '^[0-9a-f]{64}$'
        AND a.source_url IS NOT NULL
        AND a.status IN ('DRAFT','PUBLISHED')
    ) = 3
    AND NOT EXISTS (
      SELECT 1
      FROM (VALUES
        ('TRE 1.0'::varchar, 'tre4-bpsc-tre-1-0-computer-science'::varchar, 120),
        ('TRE 2.0'::varchar, 'tre4-bpsc-tre-2-0-computer-science'::varchar, 150),
        ('TRE 3.0'::varchar, 'tre4-bpsc-tre-3-0-computer-science'::varchar, 150)
      ) expected(cycle,package_id,expected_count)
      WHERE (
        SELECT count(*)
        FROM assessment a
        JOIN curriculum_chapter ch ON ch.id=a.chapter_id AND ch.code='prev-years'
        JOIN curriculum_subject s ON s.id=a.subject_id AND s.code='computer-science'
        WHERE a.course_id=p_program_id
          AND a.assessment_type='PREVIOUS_YEAR_PAPER'
          AND a.source_exam_cycle=expected.cycle
          AND a.source_package_id=expected.package_id
          AND a.source_package_version IS NOT NULL
          AND a.source_package_sha256 ~ '^[0-9a-f]{64}$'
          AND a.source_url IS NOT NULL
          AND a.status IN ('DRAFT','PUBLISHED')
      ) <> 1
      OR (
        SELECT count(*)
        FROM assessment a
        JOIN assessment_question aq ON aq.assessment_id=a.id
        WHERE a.course_id=p_program_id
          AND a.source_exam_cycle=expected.cycle
          AND a.source_package_id=expected.package_id
      ) <> expected.expected_count
    )
    AND NOT EXISTS (
      SELECT 1
      FROM assessment a
      JOIN assessment_question aq ON aq.assessment_id=a.id
      WHERE a.course_id=p_program_id
        AND a.status='PUBLISHED'
        AND (
          coalesce(aq.question_snapshot->>'question_review_status','PENDING_HUMAN_REVIEW') <> 'APPROVED'
          OR coalesce(aq.question_snapshot->'answer_payload'->>'kind','UNRESOLVED') = 'UNRESOLVED'
          OR coalesce(aq.question_snapshot->'answer_payload'->>'value','') = ''
          OR NOT EXISTS (
            SELECT 1
            FROM jsonb_array_elements(coalesce(aq.question_snapshot->'options','[]'::jsonb)) option_row
            WHERE coalesce(option_row->>'key',option_row->>'option_key') =
              aq.question_snapshot->'answer_payload'->>'value'
          )
        )
    );
$$;

INSERT INTO app_metadata(key,value)
VALUES ('schema','assessment-only-course-tre3-150-v170')
ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value;
