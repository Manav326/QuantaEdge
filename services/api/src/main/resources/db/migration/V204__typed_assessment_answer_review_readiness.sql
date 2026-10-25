-- V204: use one typed, explicit answer-review gate for assessment publishing and release readiness.
-- This is a forward migration: V199/V200 are already deployable migrations and must not be edited
-- in place after Flyway has recorded their checksums.
--
-- Legacy approved snapshots without answer_review_status fall back to question_review_status. New
-- imported snapshots write both fields explicitly, so PENDING/REJECTED answer review cannot pass
-- merely because the question text itself was reviewed.
CREATE OR REPLACE FUNCTION assessment_question_snapshot_publish_ready(p_snapshot jsonb)
RETURNS boolean
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $qe_assessment_snapshot_ready$
  SELECT COALESCE(CASE
    WHEN p_snapshot IS NULL OR jsonb_typeof(p_snapshot) IS DISTINCT FROM 'object' THEN false
    WHEN coalesce(p_snapshot->>'question_review_status','PENDING_HUMAN_REVIEW') <> 'APPROVED' THEN false
    WHEN coalesce(
      p_snapshot->>'answer_review_status',
      p_snapshot->>'question_review_status',
      'PENDING_HUMAN_REVIEW'
    ) <> 'APPROVED' THEN false
    WHEN nullif(btrim(p_snapshot->>'explanation'),'') IS NULL THEN false
    WHEN jsonb_typeof(p_snapshot->'answer_payload') IS DISTINCT FROM 'object' THEN false
    ELSE CASE coalesce(p_snapshot->'answer_payload'->>'kind','')
      WHEN 'OPTION' THEN
        jsonb_typeof(p_snapshot->'answer_payload'->'value') IN ('string','number')
        AND nullif(btrim(p_snapshot->'answer_payload'->>'value'),'') IS NOT NULL
        AND EXISTS (
          SELECT 1
          FROM jsonb_array_elements(
            CASE WHEN jsonb_typeof(p_snapshot->'options')='array'
                 THEN p_snapshot->'options' ELSE '[]'::jsonb END
          ) option_row
          WHERE coalesce(option_row->>'key',option_row->>'option_key') =
            p_snapshot->'answer_payload'->>'value'
        )
      WHEN 'MULTI_OPTION' THEN
        CASE WHEN jsonb_typeof(p_snapshot->'answer_payload'->'value')='array' THEN
          jsonb_array_length(p_snapshot->'answer_payload'->'value') > 0
          AND NOT EXISTS (
            SELECT 1
            FROM jsonb_array_elements(p_snapshot->'answer_payload'->'value') selected_key
            WHERE jsonb_typeof(selected_key) NOT IN ('string','number')
              OR nullif(btrim(selected_key #>> '{}'),'') IS NULL
              OR NOT EXISTS (
                SELECT 1
                FROM jsonb_array_elements(
                  CASE WHEN jsonb_typeof(p_snapshot->'options')='array'
                       THEN p_snapshot->'options' ELSE '[]'::jsonb END
                ) option_row
                WHERE coalesce(option_row->>'key',option_row->>'option_key') =
                  selected_key #>> '{}'
              )
          )
        ELSE false END
      WHEN 'TEXT' THEN
        jsonb_typeof(p_snapshot->'answer_payload'->'value')='string'
        AND nullif(btrim(p_snapshot->'answer_payload'->'value' #>> '{}'),'') IS NOT NULL
      WHEN 'NUMERIC' THEN
        jsonb_typeof(p_snapshot->'answer_payload'->'value') IN ('number','string')
        AND coalesce(p_snapshot->'answer_payload'->'value' #>> '{}','')
          ~ '^[+-]?([0-9]+([.][0-9]*)?|[.][0-9]+)([eE][+-]?[0-9]+)?$'
      WHEN 'ORDER' THEN
        CASE WHEN jsonb_typeof(p_snapshot->'answer_payload'->'value')='array'
          THEN jsonb_array_length(p_snapshot->'answer_payload'->'value') > 0
          ELSE false END
      WHEN 'MATCH' THEN
        (jsonb_typeof(p_snapshot->'answer_payload'->'value')='object'
          AND p_snapshot->'answer_payload'->'value' <> '{}'::jsonb)
        OR
        (jsonb_typeof(p_snapshot->'answer_payload'->'value')='array'
          AND jsonb_array_length(p_snapshot->'answer_payload'->'value') > 0)
      ELSE false
    END
  END, false);
$qe_assessment_snapshot_ready$;

COMMENT ON FUNCTION assessment_question_snapshot_publish_ready(jsonb) IS
  'Single publication gate for imported assessment snapshots: question and answer reviews must be approved, explanation non-empty, and the typed answer payload valid. Supports OPTION, MULTI_OPTION, TEXT, NUMERIC, ORDER and MATCH. Older reviewed snapshots without answer_review_status use their approved question_review_status as the legacy single-review decision.';

CREATE OR REPLACE FUNCTION assessment_only_course_ready(p_program_id bigint)
RETURNS boolean
LANGUAGE sql
STABLE
AS $$
  WITH owned_course AS (
    SELECT p.id, identity.source_institution_id
    FROM curriculum_program p
    JOIN institution_course_identity identity ON identity.id=p.course_identity_id
      AND identity.status='ACTIVE' AND identity.course_type_code=p.course_type_code
    JOIN course_type_registry registered_type ON registered_type.code=p.course_type_code
      AND registered_type.status='ACTIVE'
    WHERE p.id=p_program_id AND p.status='ACTIVE' AND p.course_type_code='ASSESSMENT'
  )
  SELECT EXISTS (
      SELECT 1
      FROM owned_course owner
      JOIN curriculum_program_delivery_mode mode
        ON mode.program_id=owner.id AND mode.delivery_mode='ASSESSMENT_ONLY'
    )
    AND EXISTS (
      SELECT 1
      FROM course_assessment_pack pack
      JOIN owned_course owner ON owner.id=pack.course_id
        AND owner.source_institution_id=pack.source_institution_id
      WHERE pack.course_id=p_program_id
        AND pack.status IN ('DRAFT','PUBLISHED')
        AND pack.content_sha256 ~ '^[0-9a-f]{64}$'
        AND EXISTS (
          SELECT 1 FROM course_assessment_pack_item item
          JOIN assessment a ON a.id=item.assessment_id AND a.course_id=pack.course_id
          WHERE item.pack_id=pack.id AND item.sequence_no BETWEEN 1 AND 1000
            AND a.status IN ('DRAFT','PUBLISHED')
            AND EXISTS (SELECT 1 FROM assessment_question aq WHERE aq.assessment_id=a.id)
        )
    )
    AND NOT EXISTS (
      SELECT 1
      FROM course_assessment_pack pack
      LEFT JOIN owned_course owner ON owner.id=pack.course_id
      WHERE pack.course_id=p_program_id
        AND (
          owner.id IS NULL
          OR owner.source_institution_id<>pack.source_institution_id
          OR pack.status NOT IN ('DRAFT','PUBLISHED')
          OR pack.content_sha256 !~ '^[0-9a-f]{64}$'
          OR NOT EXISTS (
            SELECT 1 FROM course_assessment_pack_item item
            JOIN assessment a ON a.id=item.assessment_id AND a.course_id=pack.course_id
            WHERE item.pack_id=pack.id
              AND a.status IN ('DRAFT','PUBLISHED')
              AND EXISTS (SELECT 1 FROM assessment_question aq WHERE aq.assessment_id=a.id)
          )
          OR EXISTS (
            SELECT 1
            FROM course_assessment_pack_item item
            LEFT JOIN assessment a ON a.id=item.assessment_id
            WHERE item.pack_id=pack.id
              AND (a.id IS NULL OR a.course_id<>pack.course_id
                   OR a.status NOT IN ('DRAFT','PUBLISHED'))
          )
        )
    )
    AND NOT EXISTS (
      SELECT 1
      FROM assessment a
      WHERE a.course_id=p_program_id
        AND NOT EXISTS (
          SELECT 1
          FROM course_assessment_pack_item item
          JOIN course_assessment_pack pack ON pack.id=item.pack_id
          WHERE item.assessment_id=a.id AND pack.course_id=p_program_id
        )
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

-- Import-specific contract: this checks the exact TRE source bundle without making its
-- counts/cycle IDs a requirement for unrelated ASSESSMENT courses.
CREATE OR REPLACE FUNCTION tre4_source_package_ready(p_program_id bigint)
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


-- Redefine the Marketplace gate as the final authority for every course-type path.
-- A registered type must still be ACTIVE, the program and its institution-owned identity must
-- agree on type, and the graph must be active. Unsupported/deprecated/orphaned combinations fail closed.
CREATE OR REPLACE FUNCTION course_marketplace_content_type_ready(p_program_id bigint)
RETURNS boolean
LANGUAGE sql
STABLE
AS $qe_course_type_marketplace_ready$
  SELECT CASE
    WHEN p.course_type_code='ASSESSMENT'
      AND coalesce(mode.delivery_mode,'LEARNING_CONTENT')='ASSESSMENT_ONLY'
      THEN coalesce(assessment_only_course_publish_ready(p.id),false)
    WHEN p.course_type_code='K12'
      AND coalesce(mode.delivery_mode,'LEARNING_CONTENT')='LEARNING_CONTENT'
      AND EXISTS (
        SELECT 1
        FROM curriculum_program_subject ps
        JOIN curriculum_subject s ON s.id=ps.subject_id
          AND s.class_id=ps.class_id AND s.active=true
          AND (s.program_id=ps.program_id OR s.program_id IS NULL)
        WHERE ps.program_id=p.id
      )
      AND NOT EXISTS (
        SELECT 1
        FROM curriculum_program_subject ps
        JOIN curriculum_subject s ON s.id=ps.subject_id
          AND s.class_id=ps.class_id AND s.active=true
          AND (s.program_id=ps.program_id OR s.program_id IS NULL)
        WHERE ps.program_id=p.id
          AND NOT EXISTS (
            SELECT 1
            FROM curriculum_chapter ch
            JOIN lesson l ON l.chapter_id=ch.id
            WHERE ch.subject_id=s.id
              AND ch.active=true AND ch.content_status='PUBLISHED'
              AND l.active=true AND l.status='PUBLISHED'
          )
      )
      THEN true
    ELSE false
  END
  FROM curriculum_program p
  JOIN course_type_registry registered_type
    ON registered_type.code=p.course_type_code AND registered_type.status='ACTIVE'
  JOIN institution_course_identity identity
    ON identity.id=p.course_identity_id AND identity.status='ACTIVE'
   AND identity.course_type_code=p.course_type_code
  LEFT JOIN curriculum_program_delivery_mode mode ON mode.program_id=p.id
  WHERE p.id=p_program_id AND p.status='ACTIVE' AND p.code<>'GENERAL'
$qe_course_type_marketplace_ready$;

COMMENT ON FUNCTION course_marketplace_content_type_ready(bigint) IS
  'Canonical course-type readiness gate: active K12 learning requires published lessons in every active mapped subject; active ASSESSMENT/ASSESSMENT_ONLY requires every pack and assessment published with valid approved question and answer snapshots. Deprecated types, inactive course identities, mismatched type/identity/delivery modes, orphan courses and unsupported types fail closed.';

INSERT INTO app_metadata(key,value)
VALUES ('schema','typed-assessment-answer-review-readiness-v204')
ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value;
