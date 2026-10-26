-- V211: current-contract paid course orders must carry the complete immutable checkout snapshot.
-- Keep legacy identity_contract_version=0 rows readable for explicit HRMS recovery; do not
-- validate/backfill historical rows by guessing. NOT VALID still protects all new writes/updates.
-- New paid checkout attempts must explicitly opt into the canonical v1 identity contract.
-- Existing rows remain version 0 and can only be addressed through guarded HRMS recovery.
CREATE OR REPLACE FUNCTION qe_require_current_course_order_contract_v211()
RETURNS trigger LANGUAGE plpgsql AS $qe_v211$
BEGIN
  IF NEW.status IN ('CREATING','PENDING','VERIFIED')
     AND NEW.identity_contract_version <> 1 THEN
    RAISE EXCEPTION 'New course payment orders must use canonical course identity contract v1.'
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$qe_v211$;

CREATE TRIGGER trg_qe_current_course_order_contract_v211
BEFORE INSERT ON course_payment_order
FOR EACH ROW EXECUTE FUNCTION qe_require_current_course_order_contract_v211();

ALTER TABLE course_payment_order
  ADD CONSTRAINT ck_course_order_v1_locked_financial_facts
  CHECK (
    identity_contract_version <> 1
    OR status NOT IN ('CREATING','PENDING','VERIFIED')
    OR (
      institution_id IS NOT NULL
      AND institution_offer_id IS NOT NULL
      AND course_identity_id IS NOT NULL
      AND offer_price_paise IS NOT NULL
      AND listed_price_paise IS NOT NULL
      AND taxable_value_paise IS NOT NULL
      AND output_tax_paise IS NOT NULL
      AND tax_profile_id IS NOT NULL
      AND revenue_share_policy_id IS NOT NULL
      AND tax_calculation_snapshot IS NOT NULL
      AND jsonb_typeof(tax_calculation_snapshot) = 'object'
      AND tax_calculation_snapshot ? 'customerPayablePaise'
      AND tax_calculation_snapshot ? 'listedPricePaise'
      AND tax_calculation_snapshot ? 'taxProfileId'
      AND tax_calculation_snapshot ? 'taxableValuePaise'
      AND tax_calculation_snapshot ? 'outputTaxPaise'
      AND tax_calculation_snapshot ? 'discountPaise'
      AND tax_calculation_snapshot->>'customerPayablePaise' IS NOT DISTINCT FROM offer_price_paise::text
      AND tax_calculation_snapshot->>'listedPricePaise' IS NOT DISTINCT FROM listed_price_paise::text
      AND tax_calculation_snapshot->>'taxProfileId' IS NOT DISTINCT FROM tax_profile_id::text
      AND tax_calculation_snapshot->>'taxableValuePaise' IS NOT DISTINCT FROM taxable_value_paise::text
      AND tax_calculation_snapshot->>'outputTaxPaise' IS NOT DISTINCT FROM output_tax_paise::text
      AND tax_calculation_snapshot->>'discountPaise' IS NOT DISTINCT FROM discount_amount_paise::text
      AND revenue_share_policy_snapshot IS NOT NULL
      AND jsonb_typeof(revenue_share_policy_snapshot) = 'object'
      AND revenue_share_policy_snapshot ? 'policyId'
      AND jsonb_typeof(revenue_share_policy_snapshot->'recipients') = 'array'
      AND CASE
        WHEN jsonb_typeof(revenue_share_policy_snapshot->'recipients') = 'array'
        THEN jsonb_array_length(revenue_share_policy_snapshot->'recipients') > 0
        ELSE false
      END
      AND revenue_share_policy_snapshot->>'policyId' IS NOT DISTINCT FROM revenue_share_policy_id::text
      AND buyer_snapshot IS NOT NULL
      AND jsonb_typeof(buyer_snapshot) = 'object'
      AND buyer_snapshot <> '{}'::jsonb
      AND discount_snapshot IS NOT NULL
      AND jsonb_typeof(discount_snapshot) = 'object'
      AND discount_snapshot ? 'discountPaise'
      AND discount_snapshot->>'discountPaise' IS NOT DISTINCT FROM discount_amount_paise::text
      AND (
        (
          discount_offer_id IS NULL
          AND discount_amount_paise = 0
          AND discount_reference IS NULL
          AND tax_calculation_snapshot->>'discountOfferId' IS NOT DISTINCT FROM '0'
          AND tax_calculation_snapshot->>'discountCode' IS NULL
          AND discount_snapshot->>'discountType' IS NOT DISTINCT FROM 'NONE'
          AND NOT (discount_snapshot ? 'discountOfferId')
          AND NOT (discount_snapshot ? 'code')
        )
        OR
        (
          discount_offer_id IS NOT NULL
          AND discount_amount_paise > 0
          AND discount_reference IS NOT NULL
          AND tax_calculation_snapshot->>'discountOfferId' IS NOT DISTINCT FROM discount_offer_id::text
          AND discount_snapshot->>'discountOfferId' IS NOT DISTINCT FROM discount_offer_id::text
          AND tax_calculation_snapshot->>'discountCode' IS NOT DISTINCT FROM discount_reference
          AND discount_snapshot->>'code' IS NOT DISTINCT FROM discount_reference
        )
      )
    )
  ) NOT VALID;

INSERT INTO app_metadata(key,value)
VALUES ('schema','current-course-order-financial-snapshot-guard-v211')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
