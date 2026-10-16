-- Interactive activity responses are learner-entered and are not objectively graded.
CREATE TABLE IF NOT EXISTS student_structured_activity_response (
  id bigserial PRIMARY KEY,
  student_id bigint NOT NULL REFERENCES student(id) ON DELETE CASCADE,
  artifact_id varchar(120) NOT NULL,
  artifact_sha256 char(64) NOT NULL,
  block_id varchar(180) NOT NULL,
  response jsonb NOT NULL,
  attempt_count integer NOT NULL DEFAULT 1,
  first_saved_at timestamptz NOT NULL DEFAULT now(),
  last_saved_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_student_structured_activity_response UNIQUE (student_id, artifact_id, artifact_sha256, block_id),
  CONSTRAINT ck_student_structured_activity_attempt_count CHECK (attempt_count > 0)
);
CREATE INDEX IF NOT EXISTS idx_student_structured_activity_latest
  ON student_structured_activity_response(student_id, artifact_id, last_saved_at DESC);
INSERT INTO app_metadata(key, value)
VALUES ('schema', 'structured-lesson-activities-v107')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
