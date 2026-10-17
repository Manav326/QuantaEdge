-- V110: let authorized class managers configure how early a participant can join.
-- Preserve the previous 15-minute default for all existing sessions.
-- 0 disables only the pre-start time gate; enrollment, assignment, status, and end checks remain.
ALTER TABLE learning_class_session
  ADD COLUMN join_window_minutes integer NOT NULL DEFAULT 15,
  ADD CONSTRAINT ck_learning_class_session_join_window_minutes
    CHECK (join_window_minutes BETWEEN 0 AND 1440);

INSERT INTO app_metadata(key, value)
VALUES ('schema', 'configurable-live-class-join-window-v110')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
