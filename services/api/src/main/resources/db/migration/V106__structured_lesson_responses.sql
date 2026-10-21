-- Stable structured-artifact question responses; legacy numeric question attempts are untouched.
CREATE TABLE IF NOT EXISTS student_structured_question_response (
  id bigserial PRIMARY KEY,
  student_id bigint NOT NULL REFERENCES student(id) ON DELETE CASCADE,
  artifact_id varchar(120) NOT NULL,
  artifact_sha256 char(64) NOT NULL,
  question_id varchar(180) NOT NULL,
  response jsonb NOT NULL,
  result_status varchar(24) NOT NULL DEFAULT 'PENDING_REVIEW',
  score integer,
  max_score integer,
  attempt_count integer NOT NULL DEFAULT 1,
  first_submitted_at timestamptz NOT NULL DEFAULT now(),
  last_submitted_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_student_structured_question_response
    UNIQUE (student_id, artifact_id, artifact_sha256, question_id),
  CONSTRAINT ck_student_structured_response_status
    CHECK (result_status IN ('CORRECT', 'INCORRECT', 'PARTIALLY_CORRECT', 'PENDING_REVIEW')),
  CONSTRAINT ck_student_structured_response_attempt_count
    CHECK (attempt_count > 0),
  CONSTRAINT ck_student_structured_response_score
    CHECK (
      (score IS NULL AND max_score IS NULL)
      OR (score IS NOT NULL AND max_score IS NOT NULL AND score >= 0 AND max_score >= score)
    )
);

CREATE INDEX IF NOT EXISTS idx_student_structured_response_latest
  ON student_structured_question_response(student_id, artifact_id, last_submitted_at DESC);

INSERT INTO app_metadata(key, value)
VALUES ('schema', 'structured-lesson-responses-v106')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
