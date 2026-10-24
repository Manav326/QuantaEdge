-- V195: seed institution-operation permissions for HRMS-bound Education Admin staff.
-- Reporting requires a current content-view grant; source release actions require content-publish.
-- HRMS remains the authority for institutional binding. No unbound identity receives these grants.
WITH effective_permissions AS (
  SELECT g.staff_id,g.permission_key
  FROM staff_permission_grant g
  WHERE g.workspace_key='EDUCATION_ADMIN'
  UNION
  SELECT s.id AS staff_id,rp.permission_key
  FROM staff_account s
  JOIN staff_role_permission rp ON rp.role_code=s.role AND rp.workspace_key='EDUCATION_ADMIN'
  UNION
  SELECT ar.staff_id,rp.permission_key
  FROM staff_additional_role ar
  JOIN staff_role_permission rp ON rp.role_code=ar.role_code AND rp.workspace_key='EDUCATION_ADMIN'
)
INSERT INTO staff_permission_grant(staff_id,workspace_key,permission_key,granted_by_staff_id)
SELECT DISTINCT s.id,'EDUCATION_ADMIN','INSTITUTION_REPORTS_VIEW',NULL::bigint
FROM staff_account s
JOIN institution_staff_binding b ON b.staff_id=s.id AND b.status='ACTIVE'
JOIN institution_tenant t ON t.id=b.institution_id AND t.status='ACTIVE'
JOIN effective_permissions p ON p.staff_id=s.id AND p.permission_key='CONTENT_VIEW'
WHERE s.active=true AND s.education_access_enabled=true AND s.education_access_configured=true
ON CONFLICT(staff_id,workspace_key,permission_key) DO NOTHING;

WITH effective_permissions AS (
  SELECT g.staff_id,g.permission_key
  FROM staff_permission_grant g
  WHERE g.workspace_key='EDUCATION_ADMIN'
  UNION
  SELECT s.id AS staff_id,rp.permission_key
  FROM staff_account s
  JOIN staff_role_permission rp ON rp.role_code=s.role AND rp.workspace_key='EDUCATION_ADMIN'
  UNION
  SELECT ar.staff_id,rp.permission_key
  FROM staff_additional_role ar
  JOIN staff_role_permission rp ON rp.role_code=ar.role_code AND rp.workspace_key='EDUCATION_ADMIN'
)
INSERT INTO staff_permission_grant(staff_id,workspace_key,permission_key,granted_by_staff_id)
SELECT DISTINCT s.id,'EDUCATION_ADMIN','INSTITUTION_RELEASE_MANAGE',NULL::bigint
FROM staff_account s
JOIN institution_staff_binding b ON b.staff_id=s.id AND b.status='ACTIVE'
JOIN institution_tenant t ON t.id=b.institution_id AND t.status='ACTIVE'
JOIN effective_permissions p ON p.staff_id=s.id AND p.permission_key='CONTENT_PUBLISH'
WHERE s.active=true AND s.education_access_enabled=true AND s.education_access_configured=true
ON CONFLICT(staff_id,workspace_key,permission_key) DO NOTHING;

INSERT INTO app_metadata(key,value)
VALUES ('schema','institution-operations-binding-permissions-v195')
ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value;
