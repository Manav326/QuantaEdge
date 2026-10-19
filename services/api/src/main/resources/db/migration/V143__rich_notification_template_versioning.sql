-- V143: immutable template families retain versions after edits.
ALTER TABLE notification_template
  ADD COLUMN template_family_id uuid;

UPDATE notification_template SET template_family_id=public_id WHERE template_family_id IS NULL;

ALTER TABLE notification_template
  ALTER COLUMN template_family_id SET NOT NULL,
  ALTER COLUMN template_family_id SET DEFAULT gen_random_uuid();

CREATE UNIQUE INDEX uq_notification_template_family_version
  ON notification_template(template_family_id,content_version);

INSERT INTO app_metadata(key,value)
VALUES ('schema','rich-notification-template-versioning-v140')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
