-- V108: distinguish public YouTube broadcasts from private meeting URLs.
-- Existing provider values and private-link behavior remain unchanged.
ALTER TABLE learning_class_session
  DROP CONSTRAINT IF EXISTS learning_class_session_provider_check,
  ADD COLUMN youtube_video_id varchar(11);

ALTER TABLE learning_class_session
  ADD CONSTRAINT ck_learning_class_session_provider
    CHECK (provider IN ('CUSTOM','GOOGLE_MEET','ZOOM','MICROSOFT_TEAMS','OTHER','YOUTUBE_LIVE')),
  ADD CONSTRAINT ck_learning_class_session_youtube_reference
    CHECK (
      (provider = 'YOUTUBE_LIVE'
        AND youtube_video_id IS NOT NULL
        AND length(youtube_video_id) = 11
        AND youtube_video_id !~ '[^A-Za-z0-9_-]'
        AND private_join_url IS NULL)
      OR (provider <> 'YOUTUBE_LIVE' AND youtube_video_id IS NULL)
    );

CREATE INDEX idx_learning_class_session_youtube_video
  ON learning_class_session(youtube_video_id)
  WHERE provider = 'YOUTUBE_LIVE';

INSERT INTO app_metadata(key, value)
VALUES ('schema', 'youtube-live-broadcast-provider-v108')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
