-- V182: include the complete learner-visible content graph in course release fingerprints.
-- V169 covered curriculum labels and assessment snapshots, but omitted lesson blocks, question
-- options/translations, source mappings and resource associations. A mutation in those tables
-- could therefore escape the review fingerprint even while changing what learners see.
CREATE OR REPLACE FUNCTION course_content_fingerprint(p_program_id bigint)
RETURNS char(32)
LANGUAGE sql
STABLE
AS $$
  WITH scoped_subjects AS (
    SELECT DISTINCT s.id
    FROM curriculum_program_subject ps
    JOIN curriculum_subject s ON s.id=ps.subject_id AND s.class_id=ps.class_id
      AND (s.program_id=ps.program_id OR s.program_id IS NULL)
    WHERE ps.program_id=p_program_id
  ),
  scoped_chapters AS (
    SELECT DISTINCT ch.id
    FROM curriculum_chapter ch
    JOIN scoped_subjects ss ON ss.id=ch.subject_id
  ),
  scoped_lessons AS (
    SELECT DISTINCT l.id
    FROM lesson l JOIN scoped_chapters ch ON ch.id=l.chapter_id
  ),
  scoped_objectives AS (
    SELECT DISTINCT o.id
    FROM learning_objective o JOIN scoped_chapters ch ON ch.id=o.chapter_id
  ),
  scoped_concepts AS (
    SELECT DISTINCT cc.id
    FROM chapter_concept cc JOIN scoped_chapters ch ON ch.id=cc.chapter_id
  ),
  scoped_questions AS (
    SELECT DISTINCT q.id
    FROM question q
    WHERE q.lesson_id IN (SELECT id FROM scoped_lessons)
       OR q.objective_id IN (SELECT id FROM scoped_objectives)
       OR q.concept_id IN (SELECT id FROM scoped_concepts)
  ),
  scoped_packs AS (
    SELECT p.id
    FROM course_assessment_pack p
    WHERE p.course_id=p_program_id
  ),
  scoped_resources AS (
    SELECT DISTINCT r.id
    FROM learning_resource_assignment a
    JOIN scoped_chapters ch ON ch.id=a.chapter_id
    JOIN learning_resource r ON r.id=a.resource_id
    UNION
    SELECT DISTINCT r.id
    FROM course_assessment_pack_resource pr
    JOIN scoped_packs p ON p.id=pr.pack_id
    JOIN learning_resource r ON r.id=pr.resource_id
  ),
  scoped_assets AS (
    SELECT DISTINCT b.asset_id AS id
    FROM lesson_block b
    JOIN scoped_lessons l ON l.id=b.lesson_id
    WHERE b.asset_id IS NOT NULL
    UNION
    SELECT DISTINCT r.content_asset_id AS id
    FROM learning_resource r
    JOIN scoped_resources sr ON sr.id=r.id
    WHERE r.content_asset_id IS NOT NULL
  ),
  scoped_documents AS (
    SELECT DISTINCT d.id
    FROM learning_document_course_assignment a
    JOIN learning_document d ON d.id=a.learning_document_id
    WHERE a.course_id=p_program_id
    UNION
    SELECT DISTINCT d.id
    FROM learning_resource r
    JOIN scoped_resources sr ON sr.id=r.id
    JOIN learning_document d ON d.id=r.learning_document_id
  ),
  scoped_books AS (
    SELECT DISTINCT b.id
    FROM textbook_source_book b
    WHERE b.program_id=p_program_id
       OR b.subject_id IN (SELECT id FROM scoped_subjects)
  ),
  content_rows AS (
    SELECT 'course-type'::text entity_type, t.code::text entity_id, to_jsonb(t)::text row_json
      FROM course_type_registry t
      JOIN curriculum_program p ON p.course_type_code=t.code
      WHERE p.id=p_program_id
    UNION ALL
    SELECT 'course-identity', i.id::text, to_jsonb(i)::text
      FROM institution_course_identity i
      JOIN curriculum_program p ON p.course_identity_id=i.id
      WHERE p.id=p_program_id
    UNION ALL
    SELECT 'curriculum-class', c.id::text, to_jsonb(c)::text
      FROM curriculum_class c
      JOIN curriculum_program p ON p.class_id=c.id
      WHERE p.id=p_program_id
    UNION ALL
    SELECT 'curriculum-class-translation', t.id::text, to_jsonb(t)::text
      FROM curriculum_class_translation t
      JOIN curriculum_program p ON p.class_id=t.class_id
      WHERE p.id=p_program_id
    UNION ALL
    SELECT 'program', p.id::text, to_jsonb(p)::text
      FROM curriculum_program p WHERE p.id=p_program_id
    UNION ALL
    SELECT 'program-translation', t.id::text, to_jsonb(t)::text
      FROM curriculum_program_translation t WHERE t.program_id=p_program_id
    UNION ALL
    SELECT 'delivery-mode', m.program_id::text, to_jsonb(m)::text
      FROM curriculum_program_delivery_mode m
      WHERE m.program_id=p_program_id
    UNION ALL
    SELECT 'program-subject', concat(ps.program_id,':',ps.class_id,':',ps.subject_id), to_jsonb(ps)::text
      FROM curriculum_program_subject ps WHERE ps.program_id=p_program_id
    UNION ALL
    SELECT 'subject', s.id::text, to_jsonb(s)::text
      FROM curriculum_subject s WHERE s.id IN (SELECT id FROM scoped_subjects)
    UNION ALL
    SELECT 'subject-translation', t.id::text, to_jsonb(t)::text
      FROM curriculum_subject_translation t WHERE t.subject_id IN (SELECT id FROM scoped_subjects)
    UNION ALL
    SELECT 'chapter', ch.id::text, to_jsonb(ch)::text
      FROM curriculum_chapter ch WHERE ch.id IN (SELECT id FROM scoped_chapters)
    UNION ALL
    SELECT 'chapter-translation', t.id::text, to_jsonb(t)::text
      FROM curriculum_chapter_translation t WHERE t.chapter_id IN (SELECT id FROM scoped_chapters)
    UNION ALL
    SELECT 'chapter-source', cs.id::text, to_jsonb(cs)::text
      FROM chapter_source cs WHERE cs.chapter_id IN (SELECT id FROM scoped_chapters)
    UNION ALL
    SELECT 'lesson-requirement', lr.id::text, to_jsonb(lr)::text
      FROM lesson_requirement lr WHERE lr.chapter_id IN (SELECT id FROM scoped_chapters)
    UNION ALL
    SELECT 'chapter-concept', cc.id::text, to_jsonb(cc)::text
      FROM chapter_concept cc WHERE cc.id IN (SELECT id FROM scoped_concepts)
    UNION ALL
    SELECT 'learning-objective', o.id::text, to_jsonb(o)::text
      FROM learning_objective o WHERE o.id IN (SELECT id FROM scoped_objectives)
    UNION ALL
    SELECT 'learning-objective-translation', t.id::text, to_jsonb(t)::text
      FROM learning_objective_translation t WHERE t.objective_id IN (SELECT id FROM scoped_objectives)
    UNION ALL
    SELECT 'lesson', l.id::text, to_jsonb(l)::text
      FROM lesson l WHERE l.id IN (SELECT id FROM scoped_lessons)
    UNION ALL
    SELECT 'lesson-translation', t.id::text, to_jsonb(t)::text
      FROM lesson_translation t WHERE t.lesson_id IN (SELECT id FROM scoped_lessons)
    UNION ALL
    SELECT 'lesson-block', b.id::text, to_jsonb(b)::text
      FROM lesson_block b WHERE b.lesson_id IN (SELECT id FROM scoped_lessons)
    UNION ALL
    SELECT 'lesson-block-translation', t.id::text, to_jsonb(t)::text
      FROM lesson_block_translation t
      JOIN lesson_block b ON b.id=t.block_id
      WHERE b.lesson_id IN (SELECT id FROM scoped_lessons)
    UNION ALL
    SELECT 'question', q.id::text, to_jsonb(q)::text
      FROM question q WHERE q.id IN (SELECT id FROM scoped_questions)
    UNION ALL
    SELECT 'question-option', qo.id::text, to_jsonb(qo)::text
      FROM question_option qo WHERE qo.question_id IN (SELECT id FROM scoped_questions)
    UNION ALL
    SELECT 'question-translation', t.id::text, to_jsonb(t)::text
      FROM question_translation t WHERE t.question_id IN (SELECT id FROM scoped_questions)
    UNION ALL
    SELECT 'content-asset', a.id::text, to_jsonb(a)::text
      FROM content_asset a WHERE a.id IN (SELECT id FROM scoped_assets)
    UNION ALL
    SELECT 'resource-assignment', a.id::text, to_jsonb(a)::text
      FROM learning_resource_assignment a
      JOIN scoped_chapters ch ON ch.id=a.chapter_id
    UNION ALL
    SELECT 'resource-artifact-scope', s.artifact_id||':'||s.chapter_id::text, to_jsonb(s)::text
      FROM learning_resource_artifact_scope s
      JOIN scoped_chapters ch ON ch.id=s.chapter_id
    UNION ALL
    SELECT 'learning-resource', r.id::text, to_jsonb(r)::text
      FROM learning_resource r WHERE r.id IN (SELECT id FROM scoped_resources)
    UNION ALL
    SELECT 'learning-document-assignment', a.id::text, to_jsonb(a)::text
      FROM learning_document_course_assignment a WHERE a.course_id=p_program_id
    UNION ALL
    SELECT 'learning-document', d.id::text, to_jsonb(d)::text
      FROM learning_document d WHERE d.id IN (SELECT id FROM scoped_documents)
    UNION ALL
    SELECT 'learning-pdf-asset', a.id::text, to_jsonb(a)::text
      FROM learning_pdf_asset a
      WHERE a.id IN (SELECT d.pdf_asset_id FROM learning_document d WHERE d.id IN (SELECT id FROM scoped_documents))
        OR a.id IN (SELECT d.pdf_asset_id FROM textbook_source_book d WHERE d.id IN (SELECT id FROM scoped_books))
    UNION ALL
    SELECT 'source-book', b.id::text, to_jsonb(b)::text
      FROM textbook_source_book b WHERE b.id IN (SELECT id FROM scoped_books)
    UNION ALL
    SELECT 'source-book-page', p.id::text, to_jsonb(p)::text
      FROM textbook_extracted_page p WHERE p.source_book_id IN (SELECT id FROM scoped_books)
    UNION ALL
    SELECT 'assessment', a.id::text,
      jsonb_build_object(
        'course_id',a.course_id,'title',a.title,'description',a.description,
        'subject_id',a.subject_id,'chapter_id',a.chapter_id,
        'duration_minutes',a.duration_minutes,'max_attempts',a.max_attempts,
        'negative_marking_fraction',a.negative_marking_fraction,
        'assessment_type',a.assessment_type,'portable_assessment_type',a.portable_assessment_type,
        'is_generated',a.is_generated,'source_package_id',a.source_package_id,
        'source_package_version',a.source_package_version,'source_package_sha256',a.source_package_sha256,
        'source_exam_cycle',a.source_exam_cycle,'source_url',a.source_url
      )::text
      FROM assessment a WHERE a.course_id=p_program_id
    UNION ALL
    SELECT 'assessment-translation', t.id::text, to_jsonb(t)::text
      FROM assessment_translation t
      JOIN assessment a ON a.id=t.assessment_id
      WHERE a.course_id=p_program_id
    UNION ALL
    SELECT 'assessment-question', aq.id::text,
      jsonb_build_object(
        'assessment_id',aq.assessment_id,'source_question_id',aq.source_question_id,
        'sequence_no',aq.sequence_no,'max_marks',aq.max_marks,
        'question_snapshot',aq.question_snapshot
      )::text
      FROM assessment_question aq
      JOIN assessment a ON a.id=aq.assessment_id
      WHERE a.course_id=p_program_id
    UNION ALL
    SELECT 'assessment-question-translation', t.id::text, to_jsonb(t)::text
      FROM assessment_question_translation t
      JOIN assessment_question aq ON aq.id=t.assessment_question_id
      JOIN assessment a ON a.id=aq.assessment_id
      WHERE a.course_id=p_program_id
    UNION ALL
    SELECT 'assessment-pack', p.id::text, to_jsonb(p)::text
      FROM course_assessment_pack p WHERE p.id IN (SELECT id FROM scoped_packs)
    UNION ALL
    SELECT 'assessment-pack-item', i.id::text, to_jsonb(i)::text
      FROM course_assessment_pack_item i
      WHERE i.pack_id IN (SELECT id FROM scoped_packs)
    UNION ALL
    SELECT 'assessment-pack-resource', r.id::text, to_jsonb(r)::text
      FROM course_assessment_pack_resource r
      WHERE r.pack_id IN (SELECT id FROM scoped_packs)
  )
  SELECT md5(coalesce(string_agg(
      entity_type||':'||entity_id||':'||row_json,chr(10)
      ORDER BY entity_type,entity_id
  ),''))::char(32)
  FROM content_rows;
$$;

COMMENT ON FUNCTION course_content_fingerprint(bigint) IS
  'Stable fingerprint of the course-owned curriculum, all lesson teaching blocks/questions/translations, source evidence, assigned resources and assessment/test-pack snapshots. Publication-only assessment status and timestamps are intentionally excluded so a separately reviewed assessment may be published without changing the submitted content fingerprint.';

INSERT INTO app_metadata(key,value)
VALUES ('schema','complete-course-release-content-fingerprint-v182')
ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value;
