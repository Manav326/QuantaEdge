-- V168: make assessment-only course products explicit without turning exam prep into a school grade.
-- Standard learning courses have no row in curriculum_program_delivery_mode and retain their existing
-- content fingerprint byte-for-byte. TRE-style paper libraries use the separate mode row so existing
-- course releases are not silently invalidated by adding a curriculum_program column.
CREATE TABLE curriculum_program_delivery_mode (
  program_id bigint PRIMARY KEY REFERENCES curriculum_program(id) ON DELETE CASCADE,
  delivery_mode varchar(24) NOT NULL CHECK (delivery_mode = 'ASSESSMENT_ONLY'),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE curriculum_program_delivery_mode IS
  'Optional delivery modes for course products whose durable learning assets are assessments rather than lessons. No row means the legacy LEARNING_CONTENT mode.';

-- One canonical fingerprint implementation shared by source release, HRMS review, assignment,
-- and marketplace readiness. The base rows are identical to the pre-V168 Java implementation.
-- Only ASSESSMENT_ONLY courses include the mode row plus assessment definitions and question snapshots.
CREATE OR REPLACE FUNCTION course_content_fingerprint(p_program_id bigint)
RETURNS char(32)
LANGUAGE sql
STABLE
AS $$
  SELECT md5(coalesce(string_agg(entity_type||':'||entity_id::text||':'||row_json,chr(10)
      ORDER BY entity_type,entity_id),''))
  FROM (
    SELECT 'program'::text entity_type,p.id entity_id,to_jsonb(p)::text row_json
    FROM curriculum_program p WHERE p.id=p_program_id
    UNION ALL
    SELECT 'subject',s.id,to_jsonb(ps)::text||to_jsonb(s)::text
    FROM curriculum_program_subject ps
    JOIN curriculum_program p ON p.id=ps.program_id
    JOIN curriculum_subject s ON s.id=ps.subject_id AND s.class_id=ps.class_id
      AND (s.program_id=ps.program_id OR s.program_id IS NULL)
    WHERE ps.program_id=p_program_id
    UNION ALL
    SELECT 'chapter',ch.id,to_jsonb(ch)::text
    FROM curriculum_program_subject ps
    JOIN curriculum_subject s ON s.id=ps.subject_id AND s.class_id=ps.class_id
    JOIN curriculum_chapter ch ON ch.subject_id=s.id
    WHERE ps.program_id=p_program_id
    UNION ALL
    SELECT 'lesson',l.id,to_jsonb(l)::text
    FROM curriculum_program_subject ps
    JOIN curriculum_subject s ON s.id=ps.subject_id AND s.class_id=ps.class_id
    JOIN curriculum_chapter ch ON ch.subject_id=s.id
    JOIN lesson l ON l.chapter_id=ch.id
    WHERE ps.program_id=p_program_id
    UNION ALL
    SELECT 'delivery-mode',m.program_id,to_jsonb(m)::text
    FROM curriculum_program_delivery_mode m
    WHERE m.program_id=p_program_id AND m.delivery_mode='ASSESSMENT_ONLY'
    UNION ALL
    SELECT 'assessment',a.id,to_jsonb(a)::text
    FROM curriculum_program_delivery_mode m
    JOIN assessment a ON a.course_id=m.program_id
    WHERE m.program_id=p_program_id AND m.delivery_mode='ASSESSMENT_ONLY'
    UNION ALL
    SELECT 'assessment-question',aq.id,to_jsonb(aq)::text
    FROM curriculum_program_delivery_mode m
    JOIN assessment a ON a.course_id=m.program_id
    JOIN assessment_question aq ON aq.assessment_id=a.id
    WHERE m.program_id=p_program_id AND m.delivery_mode='ASSESSMENT_ONLY'
  ) content_rows;
$$;

-- Assignment readiness for this importer is deliberately narrower than learner publication:
-- all three exact source papers and expected question counts must exist under Prev years. Their
-- assessment status may be DRAFT (normal import state) or PUBLISHED; a published test additionally
-- requires every question's answer and human-review gate to have passed.
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
        ('TRE 3.0'::varchar, 'tre4-bpsc-tre-3-0-computer-science'::varchar, 148)
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
VALUES ('schema','assessment-only-courses-v168')
ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value;
