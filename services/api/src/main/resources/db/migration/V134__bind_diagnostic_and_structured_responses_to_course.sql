-- Course-scoped diagnostic attempts and structured lesson answers.
-- Historical rows are deliberately left unbound when the originating course cannot be
-- established reliably; learner routes no longer expose them through a class-based fallback.

ALTER TABLE student_diagnostic_attempt
  ADD COLUMN course_id bigint REFERENCES curriculum_program(id) ON DELETE RESTRICT;

DROP INDEX IF EXISTS uq_student_diagnostic_active;
CREATE UNIQUE INDEX uq_student_diagnostic_active_course
  ON student_diagnostic_attempt(student_id,institution_id,course_id)
  WHERE status='IN_PROGRESS' AND course_id IS NOT NULL;
CREATE INDEX idx_student_diagnostic_course_history
  ON student_diagnostic_attempt(institution_id,student_id,course_id,started_at DESC,id DESC);

ALTER TABLE student_structured_question_response
  ADD COLUMN course_id bigint REFERENCES curriculum_program(id) ON DELETE RESTRICT;
ALTER TABLE student_structured_activity_response
  ADD COLUMN course_id bigint REFERENCES curriculum_program(id) ON DELETE RESTRICT;

DROP INDEX IF EXISTS uq_structured_question_response_tenant;
DROP INDEX IF EXISTS uq_structured_activity_response_tenant;

CREATE UNIQUE INDEX uq_structured_question_response_course
  ON student_structured_question_response(institution_id,student_id,course_id,artifact_id,artifact_sha256,question_id);
CREATE UNIQUE INDEX uq_structured_activity_response_course
  ON student_structured_activity_response(institution_id,student_id,course_id,artifact_id,artifact_sha256,block_id);

CREATE INDEX idx_structured_question_response_course_latest
  ON student_structured_question_response(institution_id,student_id,course_id,artifact_id,last_submitted_at DESC);
CREATE INDEX idx_structured_activity_response_course_latest
  ON student_structured_activity_response(institution_id,student_id,course_id,artifact_id,last_saved_at DESC);

INSERT INTO app_metadata(key,value)
VALUES ('schema','course-scoped-diagnostic-and-structured-responses-v134')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
