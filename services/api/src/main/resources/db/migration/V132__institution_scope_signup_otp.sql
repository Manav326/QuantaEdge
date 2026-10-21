-- V132: bind all account-creation OTP challenges to the institution that initiated signup.
-- Existing in-flight/global signup challenges are legacy QuantaEdge entry points; future
-- challenges receive institution_id from the selected tenant before verification.
DO $require_qe$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM institution_tenant
    WHERE tenant_code='QUANTA-EDGE' AND status='ACTIVE'
  ) THEN
    RAISE EXCEPTION 'V132 requires the active QUANTA-EDGE legacy destination tenant.';
  END IF;
END
$require_qe$;

ALTER TABLE otp_challenge
  ADD COLUMN institution_id bigint REFERENCES institution_tenant(id) ON DELETE RESTRICT;

UPDATE otp_challenge challenge
SET institution_id=qe.id
FROM institution_tenant qe
WHERE qe.tenant_code='QUANTA-EDGE'
  AND challenge.institution_id IS NULL
  AND challenge.purpose IN ('SIGNUP','ACCOUNT_SIGNUP');

ALTER TABLE otp_challenge
  ADD CONSTRAINT otp_challenge_signup_requires_institution
  CHECK (purpose NOT IN ('SIGNUP','ACCOUNT_SIGNUP') OR institution_id IS NOT NULL);

CREATE INDEX idx_otp_challenge_institution_pending
  ON otp_challenge(institution_id,purpose,requested_at DESC)
  WHERE consumed_at IS NULL;

INSERT INTO app_metadata(key,value)
VALUES ('schema','institution-bound-signup-otp-v132')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
