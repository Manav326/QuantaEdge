-- V109: idempotent private recording uploads.
-- Existing recordings and legacy upload clients retain NULL keys.
ALTER TABLE recorded_class
  ADD COLUMN idempotency_key varchar(128),
  ADD COLUMN request_fingerprint varchar(64),
  ADD CONSTRAINT ck_recorded_class_idempotency_pair
    CHECK (
      (idempotency_key IS NULL AND request_fingerprint IS NULL)
      OR
      (idempotency_key IS NOT NULL
        AND length(idempotency_key) BETWEEN 8 AND 128
        AND idempotency_key !~ '[^A-Za-z0-9._:-]'
        AND request_fingerprint IS NOT NULL
        AND length(request_fingerprint) = 64
        AND request_fingerprint !~ '[^0-9a-f]')
    );

CREATE UNIQUE INDEX uq_recorded_class_creator_idempotency_key
  ON recorded_class(created_by_staff_id, idempotency_key)
  WHERE idempotency_key IS NOT NULL;

INSERT INTO app_metadata(key, value)
VALUES ('schema', 'recording-upload-idempotency-v109')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
