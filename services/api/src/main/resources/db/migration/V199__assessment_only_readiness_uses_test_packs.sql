-- V199: make assessment-only release readiness follow the registered assessment hierarchy.
-- ASSESSMENT courses are course -> versioned test packs -> assessments -> ordered question snapshots.
-- Do not require school-class, subject, or chapter rows for this course type.
CREATE OR REPLACE FUNCTION assessment_only_course_ready(p_program_id bigint)
RETURNS boolean
LANGUAGE sql
STABLE
AS $$
  WITH expected(cycle, package_id, expected_count) AS (
    VALUES
      ('TRE 1.0'::varchar, 'tre4-bpsc-tre-1-0-computer-science'::varchar, 120),
      ('TRE 2.0'::varchar, 'tre4-bpsc-tre-2-0-computer-science'::varchar, 150),
      ('TRE 3.0'::varchar, 'tre4-bpsc-tre-3-0-computer-science'::varchar, 150)
  ),
  owned_course AS (
    SELECT p.id, identity.source_institution_id
    FROM curriculum_program p
    JOIN institution_course_identity identity ON identity.id=p.course_identity_id
    WHERE p.id=p_program_id AND p.course_type_code='ASSESSMENT'
  )
  SELECT EXISTS (
      SELECT 1
      FROM owned_course owner
      JOIN curriculum_program_delivery_mode mode ON mode.program_id=owner.id
        AND mode.delivery_mode='ASSESSMENT_ONLY'
    )
    AND (
      SELECT count(*)
      FROM assessment a
      JOIN expected e ON e.cycle=a.source_exam_cycle AND e.package_id=a.source_package_id
      WHERE a.course_id=p_program_id
        AND a.assessment_type='PREVIOUS_YEAR_PAPER'
        AND a.portable_assessment_type='PREVIOUS_YEAR_PAPER'
        AND a.source_package_version IS NOT NULL
        AND a.source_package_sha256 ~ '^[0-9a-f]{64}$'
        AND a.source_url IS NOT NULL
        AND a.status IN ('DRAFT','PUBLISHED')
    ) = 3
    AND NOT EXISTS (
      SELECT 1
      FROM expected e
      WHERE (
        SELECT count(*)
        FROM course_assessment_pack pack
        JOIN owned_course owner ON owner.id=pack.course_id
          AND owner.source_institution_id=pack.source_institution_id
        JOIN course_assessment_pack_item item ON item.pack_id=pack.id
        JOIN assessment a ON a.id=item.assessment_id AND a.course_id=pack.course_id
        WHERE pack.course_id=p_program_id
          AND pack.pack_code=e.package_id
          AND pack.pack_version=a.source_package_version
          AND pack.content_sha256=a.source_package_sha256
          AND pack.status IN ('DRAFT','PUBLISHED')
          AND item.sequence_no=1
          AND item.test_kind='PREVIOUS_YEAR_PAPER'
          AND (SELECT count(*) FROM course_assessment_pack_item every_item
               WHERE every_item.pack_id=pack.id)=1
          AND a.source_exam_cycle=e.cycle
          AND a.source_package_id=e.package_id
          AND a.assessment_type='PREVIOUS_YEAR_PAPER'
          AND a.portable_assessment_type='PREVIOUS_YEAR_PAPER'
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
          AND a.assessment_type='PREVIOUS_YEAR_PAPER'
          AND a.source_exam_cycle=e.cycle
          AND a.source_package_id=e.package_id
          AND a.status IN ('DRAFT','PUBLISHED')
      ) <> e.expected_count
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
VALUES ('schema','assessment-only-test-pack-readiness-v199')
ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value;
