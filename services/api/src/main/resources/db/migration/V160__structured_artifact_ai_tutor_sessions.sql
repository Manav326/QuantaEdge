-- V160: support tutor sessions launched from published structured lesson artifacts.
-- Existing sessions remain lesson-scoped; packaged artifacts do not invent a numeric lesson FK.
ALTER TABLE tutor_session
  ALTER COLUMN lesson_id DROP NOT NULL;

ALTER TABLE tutor_session
  ADD COLUMN structured_artifact_id text;

ALTER TABLE tutor_session
  ADD CONSTRAINT tutor_session_context_source_check
  CHECK (
    (lesson_id IS NOT NULL AND structured_artifact_id IS NULL)
    OR
    (lesson_id IS NULL AND structured_artifact_id IS NOT NULL
      AND length(trim(structured_artifact_id)) BETWEEN 1 AND 200)
  );

CREATE INDEX idx_tutor_session_structured_artifact_owner
  ON tutor_session(institution_id,student_id,course_id,structured_artifact_id,created_at DESC,id DESC)
  WHERE structured_artifact_id IS NOT NULL;

INSERT INTO app_metadata(key,value)
VALUES ('schema','structured-artifact-ai-tutor-sessions-v160')
ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value;
