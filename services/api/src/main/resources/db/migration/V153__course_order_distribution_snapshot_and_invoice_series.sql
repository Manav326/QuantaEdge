-- V153: lock the approved revenue-share policy to each checkout and configure legal invoice numbering.
ALTER TABLE course_payment_order
  ADD COLUMN revenue_share_policy_id bigint REFERENCES revenue_share_policy(id) ON DELETE RESTRICT,
  ADD COLUMN revenue_share_policy_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb
    CHECK (jsonb_typeof(revenue_share_policy_snapshot)='object');

ALTER TABLE billing_legal_entity
  ADD COLUMN invoice_series_code varchar(16) NOT NULL DEFAULT 'QE'
    CHECK (invoice_series_code ~ '^[A-Z0-9_-]{1,16}$');

CREATE INDEX idx_course_order_share_policy
  ON course_payment_order(revenue_share_policy_id,created_at DESC)
  WHERE revenue_share_policy_id IS NOT NULL;

-- Once a payment has been verified, both the tax basis and the policy/recipient snapshot are
-- accounting facts. Corrections go through credit/debit note flows; they are never overwritten.
CREATE OR REPLACE FUNCTION lock_verified_course_order_financial_snapshot()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status='VERIFIED' AND ROW(
      NEW.listed_price_paise,NEW.discount_amount_paise,NEW.discount_reference,NEW.discount_snapshot,
      NEW.tax_profile_id,NEW.taxable_value_paise,NEW.output_tax_paise,NEW.tax_calculation_snapshot,
      NEW.buyer_snapshot,NEW.captured_amount_paise,NEW.captured_at,
      NEW.revenue_share_policy_id,NEW.revenue_share_policy_snapshot,NEW.fiscal_document_id
    ) IS DISTINCT FROM ROW(
      OLD.listed_price_paise,OLD.discount_amount_paise,OLD.discount_reference,OLD.discount_snapshot,
      OLD.tax_profile_id,OLD.taxable_value_paise,OLD.output_tax_paise,OLD.tax_calculation_snapshot,
      OLD.buyer_snapshot,OLD.captured_amount_paise,OLD.captured_at,
      OLD.revenue_share_policy_id,OLD.revenue_share_policy_snapshot,OLD.fiscal_document_id
    ) THEN
    RAISE EXCEPTION 'Verified course-order financial facts are immutable; use a fiscal adjustment'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;

INSERT INTO app_metadata(key,value)
VALUES ('schema','course-order-policy-snapshot-invoice-series-v153')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
