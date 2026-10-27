-- V213: align the TRE-specific source inventory gate with final-key-deleted items in TRE 3.0.
-- V168, V199 and V204 are already versioned migrations; preserve their checksums and
-- redefine only the TRE-specific package gate. The supplied Set E booklet contains 150
-- numbered positions, but General Studies items 52 and 69 are marked Deleted. They remain
-- in the source extraction audit and are excluded from the active 148-question attempt.
CREATE OR REPLACE FUNCTION tre4_source_package_ready(p_program_id bigint)
RETURNS boolean
LANGUAGE sql
STABLE
AS $$
  WITH expected(cycle, package_id, expected_count) AS (
    VALUES
      ('TRE 1.0'::varchar, 'tre4-bpsc-tre-1-0-computer-science'::varchar, 120),
      ('TRE 2.0'::varchar, 'tre4-bpsc-tre-2-0-computer-science'::varchar, 150),
      ('TRE 3.0'::varchar, 'tre4-bpsc-tre-3-0-computer-science'::varchar, 148)
  ),
  owned_course AS (
    SELECT p.id, identity.source_institution_id
    FROM curriculum_program p
    JOIN institution_course_identity identity ON identity.id=p.course_identity_id
      AND identity.status='ACTIVE' AND identity.course_type_code=p.course_type_code
    JOIN course_type_registry registered_type ON registered_type.code=p.course_type_code
      AND registered_type.status='ACTIVE'
    WHERE p.id=p_program_id AND p.status='ACTIVE' AND p.course_type_code='ASSESSMENT'
  )
  SELECT coalesce(assessment_only_course_ready(p_program_id),false)
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
        AND NOT assessment_question_snapshot_publish_ready(aq.question_snapshot)
    );
$$;

INSERT INTO app_metadata(key,value)
VALUES ('schema','tre3-final-key-deleted-question-count-v213')
ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value;
