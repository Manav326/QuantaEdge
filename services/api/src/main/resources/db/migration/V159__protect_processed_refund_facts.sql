-- V159: lock the provider outcome and credit-note link once a refund reaches a terminal state.
CREATE OR REPLACE FUNCTION protect_course_payment_refund_facts()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF ROW(NEW.course_payment_order_id,NEW.request_idempotency_key,NEW.provider_idempotency_key,
      NEW.provider_payment_id,NEW.requested_amount_paise,NEW.currency,NEW.reason,
      NEW.requested_by_staff_id,NEW.requested_at)
    IS DISTINCT FROM
    ROW(OLD.course_payment_order_id,OLD.request_idempotency_key,OLD.provider_idempotency_key,
      OLD.provider_payment_id,OLD.requested_amount_paise,OLD.currency,OLD.reason,
      OLD.requested_by_staff_id,OLD.requested_at) THEN
    RAISE EXCEPTION 'Refund request facts are immutable after submission'
      USING ERRCODE='23514';
  END IF;

  IF OLD.provider_refund_id IS NOT NULL
      AND NEW.provider_refund_id IS DISTINCT FROM OLD.provider_refund_id THEN
    RAISE EXCEPTION 'A recorded provider refund ID cannot be replaced'
      USING ERRCODE='23514';
  END IF;

  IF OLD.credit_note_document_id IS NOT NULL
      AND NEW.credit_note_document_id IS DISTINCT FROM OLD.credit_note_document_id THEN
    RAISE EXCEPTION 'A refund credit-note link cannot be replaced or removed'
      USING ERRCODE='23514';
  END IF;

  IF OLD.status IN ('PROCESSED','FAILED') AND ROW(
      NEW.status,NEW.provider_refund_id,NEW.provider_status,
      NEW.provider_response_snapshot,NEW.credit_note_document_id,NEW.settled_at
    ) IS DISTINCT FROM ROW(
      OLD.status,OLD.provider_refund_id,OLD.provider_status,
      OLD.provider_response_snapshot,OLD.credit_note_document_id,OLD.settled_at
    ) THEN
    RAISE EXCEPTION 'A terminal refund outcome is immutable'
      USING ERRCODE='23514';
  END IF;

  RETURN NEW;
END;
$$;

INSERT INTO app_metadata(key,value)
VALUES ('schema','terminal-refund-facts-immutable-v159')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
