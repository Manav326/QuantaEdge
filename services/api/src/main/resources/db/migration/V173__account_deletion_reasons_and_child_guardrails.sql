-- V173: capture deletion reasons, request initiator and guardian-requested child lifecycle.
-- This migration adds request metadata only; it does not itself erase data.
ALTER TABLE account_deletion_request
  ADD COLUMN requested_by_user_id bigint REFERENCES user_account(id) ON DELETE SET NULL,
  ADD COLUMN reason_category varchar(40) NOT NULL DEFAULT 'LEGACY_UNSPECIFIED',
  ADD COLUMN reason_details varchar(1500);

-- A guardian request may target a managed student profile with no user_account row.
ALTER TABLE account_deletion_request
  ALTER COLUMN subject_user_public_id DROP NOT NULL;

ALTER TABLE account_deletion_request
  DROP CONSTRAINT IF EXISTS account_deletion_request_subject_user_public_id_check;

ALTER TABLE account_deletion_request
  ADD CONSTRAINT ck_account_deletion_subject_user_public_id
    CHECK (subject_user_public_id IS NULL OR subject_user_public_id ~ '^[A-Z0-9]{12}$'),
  ADD CONSTRAINT ck_account_deletion_has_subject
    CHECK (status = 'COMPLETED' OR subject_user_id IS NOT NULL OR subject_student_id IS NOT NULL);

UPDATE account_deletion_request
SET requested_by_user_id = subject_user_id
WHERE requested_by_user_id IS NULL AND subject_user_id IS NOT NULL;

ALTER TABLE account_deletion_request
  ADD CONSTRAINT ck_account_deletion_reason_category
    CHECK (reason_category IN (
      'NO_LONGER_NEEDED','PRIVACY_CONCERN','SECURITY_CONCERN',
      'DUPLICATE_ACCOUNT','MOVING_TO_ANOTHER_SERVICE','DATA_CONCERN',
      'OTHER','LEGACY_UNSPECIFIED')),
  ADD CONSTRAINT ck_account_deletion_other_reason
    CHECK (reason_category <> 'OTHER' OR length(btrim(coalesce(reason_details,''))) >= 5);

ALTER TABLE account_deletion_request
  DROP CONSTRAINT IF EXISTS account_deletion_request_identity_verification_method_check;

ALTER TABLE account_deletion_request
  ADD CONSTRAINT ck_account_deletion_identity_method_supported
    CHECK (identity_verification_method IS NULL OR identity_verification_method IN (
      'MOBILE_OTP','GOOGLE_REAUTH','MANUAL_REVIEW_REQUIRED',
      'GUARDIAN_MOBILE_OTP','GUARDIAN_GOOGLE_REAUTH'
    ));

CREATE INDEX idx_account_deletion_request_requester
  ON account_deletion_request(requested_by_user_id,requested_at DESC,request_id);

INSERT INTO app_metadata(key,value)
VALUES ('schema','account-deletion-reasons-and-child-guardrails-v173')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
