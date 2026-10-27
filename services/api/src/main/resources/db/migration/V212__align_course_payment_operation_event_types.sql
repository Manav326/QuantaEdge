-- V212: align the immutable operations ledger with all reconciliation outcomes.
-- The success event name is longer than the original varchar(40) contract.
ALTER TABLE course_payment_operations_event
  DROP CONSTRAINT course_payment_operations_event_event_type_check;

ALTER TABLE course_payment_operations_event
  ALTER COLUMN event_type TYPE varchar(64);

ALTER TABLE course_payment_operations_event
  ADD CONSTRAINT course_payment_operations_event_event_type_check CHECK (event_type IN (
    'RECONCILIATION_REQUESTED',
    'RECONCILIATION_SUCCEEDED',
    'RECONCILIATION_PENDING',
    'RECONCILIATION_RETRYABLE',
    'RECONCILIATION_FAILED',
    'ACTION_BLOCKED',
    'FINANCIAL_SNAPSHOT_REPAIRED_AND_RECONCILED',
    'COURSE_ACCESS_GRANTED_FINANCE_PENDING',
    'FINANCIAL_SNAPSHOT_REPAIR_BLOCKED'
  ));

INSERT INTO app_metadata(key,value)
VALUES ('schema','course-payment-operation-event-types-v212')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
