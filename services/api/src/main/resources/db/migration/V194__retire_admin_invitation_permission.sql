-- V194: revoke the abandoned Admin join-code reveal capability.
-- Invitation issuance, rotation and sharing remain exclusively controlled by HRMS.
DELETE FROM staff_permission_grant WHERE permission_key='INSTITUTION_INVITATION_VIEW';
DELETE FROM staff_role_permission WHERE permission_key='INSTITUTION_INVITATION_VIEW';
DELETE FROM staff_permission_catalog WHERE permission_key='INSTITUTION_INVITATION_VIEW';

INSERT INTO app_metadata(key,value)
VALUES ('schema','institution-invitation-permission-retired-v194')
ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value;
