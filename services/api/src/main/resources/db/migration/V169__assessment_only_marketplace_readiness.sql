-- V169: distinguish source-package assignment readiness from learner-facing publication readiness.
-- Assessment-only products can be reviewed/assigned while their imported tests are drafts, but a
-- Marketplace offer may be listed only when all three tests are published and answer-reviewed.
-- Lifecycle status/timestamps are excluded from assessment-only content fingerprints so publishing an
-- already reviewed test does not make the reviewed content fingerprint stale. Question snapshots,
-- answers, source identity and scoring configuration remain fingerprinted.
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
    SELECT 'assessment',a.id,jsonb_build_object(
      'course_id',a.course_id,
      'title',a.title,
      'description',a.description,
      'subject_id',a.subject_id,
      'chapter_id',a.chapter_id,
      'duration_minutes',a.duration_minutes,
      'max_attempts',a.max_attempts,
      'negative_marking_fraction',a.negative_marking_fraction,
      'assessment_type',a.assessment_type,
      'is_generated',a.is_generated,
      'source_package_id',a.source_package_id,
      'source_package_version',a.source_package_version,
      'source_package_sha256',a.source_package_sha256,
      'source_exam_cycle',a.source_exam_cycle,
      'source_url',a.source_url
    )::text
    FROM curriculum_program_delivery_mode m
    JOIN assessment a ON a.course_id=m.program_id
    WHERE m.program_id=p_program_id AND m.delivery_mode='ASSESSMENT_ONLY'
    UNION ALL
    SELECT 'assessment-question',aq.id,jsonb_build_object(
      'assessment_id',aq.assessment_id,
      'source_question_id',aq.source_question_id,
      'sequence_no',aq.sequence_no,
      'max_marks',aq.max_marks,
      'question_snapshot',aq.question_snapshot
    )::text
    FROM curriculum_program_delivery_mode m
    JOIN assessment a ON a.course_id=m.program_id
    JOIN assessment_question aq ON aq.assessment_id=a.id
    WHERE m.program_id=p_program_id AND m.delivery_mode='ASSESSMENT_ONLY'
  ) content_rows;
$$;

CREATE OR REPLACE FUNCTION assessment_only_course_publish_ready(p_program_id bigint)
RETURNS boolean
LANGUAGE sql
STABLE
AS $$
  SELECT coalesce(assessment_only_course_ready(p_program_id),false)
    AND (
      SELECT count(*)
      FROM assessment a
      WHERE a.course_id=p_program_id
        AND a.assessment_type='PREVIOUS_YEAR_PAPER'
        AND a.source_package_id IN (
          'tre4-bpsc-tre-1-0-computer-science',
          'tre4-bpsc-tre-2-0-computer-science',
          'tre4-bpsc-tre-3-0-computer-science'
        )
        AND a.status='PUBLISHED'
    ) = 3;
$$;

INSERT INTO app_metadata(key,value)
VALUES ('schema','assessment-only-marketplace-readiness-v169')
ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value;
