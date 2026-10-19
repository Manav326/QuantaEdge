-- V144: index active template names independently from immutable version numbers.
-- Version uniqueness is enforced by (template_family_id, content_version) in V143.
-- The earlier name/version index wrongly blocked two different template families from
-- using the same display name at the same family-relative version number.
DROP INDEX IF EXISTS uq_notification_template_name_version;

CREATE UNIQUE INDEX uq_notification_template_active_name
  ON notification_template(
    (COALESCE(institution_id,0)),
    lower(template_name)
  )
  WHERE status='ACTIVE';

INSERT INTO app_metadata(key,value)
VALUES ('schema','rich-notification-template-name-index-v141')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
