-- V141: historical notification template/media records must survive creator deactivation/deletion.
-- Sender/uploader identity is audit metadata, not a lifetime ownership invariant.
ALTER TABLE notification_template
  DROP CONSTRAINT IF EXISTS ck_notification_template_editor;

ALTER TABLE notification_media_asset
  DROP CONSTRAINT IF EXISTS ck_notification_media_uploader;

INSERT INTO app_metadata(key,value)
VALUES ('schema','rich-notification-retained-provenance-v141')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
