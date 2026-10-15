-- Distinguish Android learner sessions so a restored refresh token can resume
-- after reinstall without changing WEB/staff idle-session rules or the existing absolute limit.
ALTER TABLE auth_session
  ADD COLUMN IF NOT EXISTS client_type varchar(16) NOT NULL DEFAULT 'WEB';

ALTER TABLE auth_session
  DROP CONSTRAINT IF EXISTS ck_auth_session_client_type;

ALTER TABLE auth_session
  ADD CONSTRAINT ck_auth_session_client_type
  CHECK (client_type IN ('WEB', 'ANDROID'));

CREATE INDEX IF NOT EXISTS idx_auth_session_client_idle
  ON auth_session(client_type, workspace_key, last_seen_at, absolute_expires_at)
  WHERE revoked_at IS NULL;

INSERT INTO app_metadata(key,value)
VALUES ('schema','android-session-restore-v105')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
