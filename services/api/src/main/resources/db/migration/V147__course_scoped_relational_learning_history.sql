-- V147: make relational learning history explicitly course-scoped without dropping legacy history.
-- Existing rows are assigned only when the learner has exactly one enrolled, released course
-- whose subject map contains their source content. Ambiguous/unscoped rows remain intact with
-- course_id NULL; they are never guessed into the current course.

ALTER TABLE student_lesson_progress
  ADD COLUMN course_id bigint REFERENCES curriculum_program(id) ON DELETE RESTRICT;
ALTER TABLE student_question_attempt
  ADD COLUMN course_id bigint REFERENCES curriculum_program(id) ON DELETE RESTRICT;
ALTER TABLE student_practice_session
  ADD COLUMN course_id bigint REFERENCES curriculum_program(id) ON DELETE RESTRICT;
ALTER TABLE student_concept_mastery
  ADD COLUMN course_id bigint REFERENCES curriculum_program(id) ON DELETE RESTRICT;

WITH candidates AS (
  SELECT h.id AS history_id, min(p.id) AS course_id
  FROM student_lesson_progress h
  JOIN lesson l ON l.id=h.lesson_id
  JOIN curriculum_chapter ch ON ch.id=l.chapter_id
  JOIN curriculum_subject s ON s.id=ch.subject_id
  JOIN student_program_enrollment spe ON spe.student_id=h.student_id
    AND spe.institution_id=h.institution_id
  JOIN curriculum_program p ON p.id=spe.program_id AND p.status='ACTIVE' AND p.code<>'GENERAL'
  JOIN curriculum_program_subject cps ON cps.program_id=p.id
    AND cps.subject_id=s.id AND cps.class_id=s.class_id
  JOIN institution_course_assignment a ON a.institution_id=h.institution_id AND a.status='ASSIGNED'
  JOIN institution_course_release r ON r.id=a.course_release_id AND r.program_id=p.id
  WHERE h.institution_id IS NOT NULL AND h.course_id IS NULL
    AND (s.program_id=p.id OR (s.program_id IS NULL AND p.class_id=s.class_id))
  GROUP BY h.id
  HAVING count(DISTINCT p.id)=1
)
UPDATE student_lesson_progress h SET course_id=c.course_id
FROM candidates c WHERE c.history_id=h.id AND h.course_id IS NULL;

WITH candidates AS (
  SELECT h.id AS history_id, min(p.id) AS course_id
  FROM student_question_attempt h
  JOIN question q ON q.id=h.question_id
  JOIN lesson l ON l.id=q.lesson_id
  JOIN curriculum_chapter ch ON ch.id=l.chapter_id
  JOIN curriculum_subject s ON s.id=ch.subject_id
  JOIN student_program_enrollment spe ON spe.student_id=h.student_id
    AND spe.institution_id=h.institution_id
  JOIN curriculum_program p ON p.id=spe.program_id AND p.status='ACTIVE' AND p.code<>'GENERAL'
  JOIN curriculum_program_subject cps ON cps.program_id=p.id
    AND cps.subject_id=s.id AND cps.class_id=s.class_id
  JOIN institution_course_assignment a ON a.institution_id=h.institution_id AND a.status='ASSIGNED'
  JOIN institution_course_release r ON r.id=a.course_release_id AND r.program_id=p.id
  WHERE h.institution_id IS NOT NULL AND h.course_id IS NULL
    AND (s.program_id=p.id OR (s.program_id IS NULL AND p.class_id=s.class_id))
  GROUP BY h.id
  HAVING count(DISTINCT p.id)=1
)
UPDATE student_question_attempt h SET course_id=c.course_id
FROM candidates c WHERE c.history_id=h.id AND h.course_id IS NULL;

WITH candidates AS (
  SELECT ps.id AS session_id, min(p.id) AS course_id
  FROM student_practice_session ps
  JOIN student_program_enrollment spe ON spe.student_id=ps.student_id
    AND spe.institution_id=ps.institution_id
  JOIN curriculum_program p ON p.id=spe.program_id AND p.status='ACTIVE' AND p.code<>'GENERAL'
  JOIN institution_course_assignment a ON a.institution_id=ps.institution_id AND a.status='ASSIGNED'
  JOIN institution_course_release r ON r.id=a.course_release_id AND r.program_id=p.id
  CROSS JOIN LATERAL jsonb_array_elements(
    CASE WHEN jsonb_typeof(ps.topic_selections)='array' THEN ps.topic_selections ELSE '[]'::jsonb END
  ) topic
  JOIN lesson l ON l.id=CASE
    WHEN coalesce(topic->>'sourceLessonId','') ~ '^[1-9][0-9]*$'
      THEN (topic->>'sourceLessonId')::bigint ELSE NULL END
  JOIN curriculum_chapter ch ON ch.id=l.chapter_id
  JOIN curriculum_subject s ON s.id=ch.subject_id
  JOIN curriculum_program_subject cps ON cps.program_id=p.id
    AND cps.subject_id=s.id AND cps.class_id=s.class_id
  WHERE ps.institution_id IS NOT NULL AND ps.course_id IS NULL
    AND jsonb_array_length(CASE WHEN jsonb_typeof(ps.topic_selections)='array'
      THEN ps.topic_selections ELSE '[]'::jsonb END)=1
    AND (s.program_id=p.id OR (s.program_id IS NULL AND p.class_id=s.class_id))
  GROUP BY ps.id
  HAVING count(DISTINCT p.id)=1
)
UPDATE student_practice_session ps SET course_id=c.course_id
FROM candidates c WHERE c.session_id=ps.id AND ps.course_id IS NULL;

WITH candidates AS (
  SELECT h.id AS history_id, min(p.id) AS course_id
  FROM student_concept_mastery h
  JOIN chapter_concept cc ON cc.id=h.concept_id
  JOIN curriculum_chapter ch ON ch.id=cc.chapter_id
  JOIN curriculum_subject s ON s.id=ch.subject_id
  JOIN student_program_enrollment spe ON spe.student_id=h.student_id
    AND spe.institution_id=h.institution_id
  JOIN curriculum_program p ON p.id=spe.program_id AND p.status='ACTIVE' AND p.code<>'GENERAL'
  JOIN curriculum_program_subject cps ON cps.program_id=p.id
    AND cps.subject_id=s.id AND cps.class_id=s.class_id
  JOIN institution_course_assignment a ON a.institution_id=h.institution_id AND a.status='ASSIGNED'
  JOIN institution_course_release r ON r.id=a.course_release_id AND r.program_id=p.id
  WHERE h.institution_id IS NOT NULL AND h.course_id IS NULL
    AND (s.program_id=p.id OR (s.program_id IS NULL AND p.class_id=s.class_id))
  GROUP BY h.id
  HAVING count(DISTINCT p.id)=1
)
UPDATE student_concept_mastery h SET course_id=c.course_id
FROM candidates c WHERE c.history_id=h.id AND h.course_id IS NULL;

DROP INDEX IF EXISTS uq_student_lesson_progress_tenant;
CREATE UNIQUE INDEX uq_student_lesson_progress_course
  ON student_lesson_progress(institution_id,student_id,course_id,lesson_id)
  WHERE institution_id IS NOT NULL AND course_id IS NOT NULL;
CREATE UNIQUE INDEX uq_student_lesson_progress_unassigned
  ON student_lesson_progress(institution_id,student_id,lesson_id)
  WHERE institution_id IS NOT NULL AND course_id IS NULL;
CREATE UNIQUE INDEX uq_student_lesson_progress_global_unassigned
  ON student_lesson_progress(student_id,lesson_id)
  WHERE institution_id IS NULL AND course_id IS NULL;

DROP INDEX IF EXISTS uq_student_concept_mastery_tenant;
CREATE UNIQUE INDEX uq_student_concept_mastery_course
  ON student_concept_mastery(institution_id,student_id,course_id,concept_id)
  WHERE institution_id IS NOT NULL AND course_id IS NOT NULL;
CREATE UNIQUE INDEX uq_student_concept_mastery_unassigned
  ON student_concept_mastery(institution_id,student_id,concept_id)
  WHERE institution_id IS NOT NULL AND course_id IS NULL;
CREATE UNIQUE INDEX uq_student_concept_mastery_global_unassigned
  ON student_concept_mastery(student_id,concept_id)
  WHERE institution_id IS NULL AND course_id IS NULL;

CREATE INDEX idx_student_question_attempt_course
  ON student_question_attempt(institution_id,student_id,course_id,question_id,answered_at DESC,id DESC);
CREATE INDEX idx_student_practice_session_course
  ON student_practice_session(institution_id,student_id,course_id,status,created_at DESC);
CREATE INDEX idx_student_lesson_progress_course
  ON student_lesson_progress(institution_id,student_id,course_id,status,lesson_id);
CREATE INDEX idx_student_concept_mastery_course
  ON student_concept_mastery(institution_id,student_id,course_id,concept_id);

INSERT INTO app_metadata(key,value)
VALUES ('schema','course-scoped-relational-learning-history-v147')
ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value;
