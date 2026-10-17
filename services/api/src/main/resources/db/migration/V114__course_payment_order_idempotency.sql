-- V114: durable checkout idempotency for authenticated learner/course pairs.
-- Backfill existing orders before enforcing NOT NULL; older orders remain verifiable.
ALTER TABLE course_payment_order
  ADD COLUMN client_request_key varchar(64);

UPDATE course_payment_order
SET client_request_key = 'legacy_migration_' || id::text
WHERE client_request_key IS NULL;

ALTER TABLE course_payment_order
  ALTER COLUMN client_request_key SET NOT NULL;

ALTER TABLE course_payment_order
  ADD CONSTRAINT chk_course_payment_order_client_request_key
  CHECK (client_request_key ~ '^[A-Za-z0-9_-]{1,64}$');

CREATE UNIQUE INDEX uq_course_payment_order_request_key
  ON course_payment_order(student_id, course_id, client_request_key);
