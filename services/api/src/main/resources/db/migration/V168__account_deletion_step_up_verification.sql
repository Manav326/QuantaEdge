-- V168: fresh identity proof for account-deletion requests.
-- A valid request is still distinct from erasure; this only strengthens the request identity check.
DO $purpose_check$
DECLARE c record;
BEGIN
  FOR c IN
    SELECT conname FROM pg_constraint
    WHERE conrelid='public.otp_challenge'::regclass AND contype='c'
      AND pg_get_constraintdef(oid) ILIKE '%purpose%'
  LOOP
    EXECUTE format('ALTER TABLE public.otp_challenge DROP CONSTRAINT %I', c.conname);
  END LOOP;
END;
$purpose_check$;

ALTER TABLE otp_challenge
  ADD CONSTRAINT otp_challenge_purpose_supported
  CHECK (purpose IN (
    'LOGIN','SIGNUP','STAFF_LOGIN','PASSWORD_RESET','LEARNER_TRANSITION',
    'ACCOUNT_SIGNUP','ACCOUNT_DELETION'
  ));

ALTER TABLE account_deletion_request
  ADD COLUMN identity_verification_method varchar(24)
    CHECK (identity_verification_method IS NULL OR identity_verification_method IN ('MOBILE_OTP','GOOGLE_REAUTH')),
  ADD COLUMN identity_verified_at timestamptz;

CREATE TABLE account_deletion_verification (
  verification_id uuid PRIMARY KEY,
  user_account_id bigint NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
  method varchar(24) NOT NULL CHECK (method IN ('MOBILE_OTP','GOOGLE_REAUTH')),
  verified_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  consumed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (expires_at > verified_at),
  CHECK (consumed_at IS NULL OR consumed_at >= verified_at)
);

CREATE INDEX idx_account_deletion_verification_account_expiry
  ON account_deletion_verification(user_account_id,expires_at DESC)
  WHERE consumed_at IS NULL;

INSERT INTO app_metadata(key,value)
VALUES ('schema','account-deletion-step-up-verification-v168')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
