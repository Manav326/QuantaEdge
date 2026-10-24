-- V190: separate institutional reporting and source-release authority from general content visibility.
-- Education Admin is the institution-head workspace; HRMS remains the cross-institution control plane.
INSERT INTO staff_permission_catalog(permission_key,display_name,description) VALUES
  ('INSTITUTION_REPORTS_VIEW','View institution operations','View only the institution assigned to this Education Admin identity: learner roster, learning progress, authorised courses, and source-release status.'),
  ('INSTITUTION_RELEASE_MANAGE','Manage institution course releases','Submit a source-owned course release to HRMS quality review and approve it for sharing after HRMS quality approval; does not grant Marketplace publication.')
ON CONFLICT(permission_key) DO UPDATE
SET display_name=EXCLUDED.display_name,description=EXCLUDED.description;

-- Existing Education Admin administrators are the institution-head Admin accounts for this
-- workspace. Preserve their current ability to use the newly consolidated functions. Other staff
-- receive these permissions only through the ordinary explicit permission-grant screen.
INSERT INTO staff_permission_grant(staff_id,workspace_key,permission_key,granted_by_staff_id)
SELECT s.id,'EDUCATION_ADMIN',p.permission_key,NULL
FROM staff_account s
JOIN staff_permission_catalog p ON p.permission_key IN (
  'INSTITUTION_REPORTS_VIEW','INSTITUTION_RELEASE_MANAGE'
)
WHERE s.role='ADMIN' AND s.active=true AND s.education_access_configured=true
ON CONFLICT(staff_id,workspace_key,permission_key) DO NOTHING;

INSERT INTO app_metadata(key,value)
VALUES ('schema','institution-operations-permissions-v190')
ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value;
