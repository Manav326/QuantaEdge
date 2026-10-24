-- V191: protect learner invitation secrets from read-only roster/report roles.
-- Institution heads (Education Admin ADMIN accounts) can view the currently valid join code;
-- HRMS alone continues to issue/rotate codes and configure the billing prerequisites.
INSERT INTO staff_permission_catalog(permission_key,display_name,description) VALUES
  ('INSTITUTION_INVITATION_VIEW','View learner invitation link','Reveal the currently valid learner join code for the single institution assigned to this Education Admin identity. Cannot issue or rotate codes.')
ON CONFLICT(permission_key) DO UPDATE
SET display_name=EXCLUDED.display_name,description=EXCLUDED.description;

INSERT INTO staff_permission_grant(staff_id,workspace_key,permission_key,granted_by_staff_id)
SELECT s.id,'EDUCATION_ADMIN','INSTITUTION_INVITATION_VIEW',NULL
FROM staff_account s
WHERE s.role='ADMIN' AND s.active=true AND s.education_access_configured=true
ON CONFLICT(staff_id,workspace_key,permission_key) DO NOTHING;

INSERT INTO app_metadata(key,value)
VALUES ('schema','institution-invitation-view-permission-v191')
ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value;
