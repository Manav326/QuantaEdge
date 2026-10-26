-- V211: current-contract paid course orders must carry the complete immutable checkout snapshot.
-- Keep legacy identity_contract_version=0 rows readable for explicit HRMS recovery; do not
-- validate/backfill historical rows by guessing. NOT VALID still protects all new writes/updates.
ALTER TABLE course_payment_order
  ADD CONSTRAINT ck_course_order_v1_locked_financial_facts
  CHECK (
    identity_contract_version <> 1
    OR status NOT IN ('CREATING','PENDING','VERIFIED')
    OR (
      institution_id IS NOT NULL
      AND institution_offer_id IS NOT NULL
      AND course_identity_id IS NOT NULL
      AND listed_price_paise IS NOT NULL
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
      AND tax_calculation_snapshot->>'customerPayablePaise' = offer_price_paise::text
      AND tax_calculation_snapshot->>'listedPricePaise' = listed_price_paise::text
      AND tax_calculation_snapshot->>'taxProfileId' = tax_profile_id::text
      AND tax_calculation_snapshot->>'taxableValuePaise' = taxable_value_paise::text
      AND tax_calculation_snapshot->>'outputTaxPaise' = output_tax_paise::text
      AND tax_calculation_snapshot->>'discountPaise' = discount_amount_paise::text
      AND revenue_share_policy_snapshot IS NOT NULL
      AND jsonb_typeof(revenue_share_policy_snapshot) = 'object'
      AND revenue_share_policy_snapshot ? 'policyId'
      AND jsonb_typeof(revenue_share_policy_snapshot->'recipients') = 'array'
      AND revenue_share_policy_snapshot->>'policyId' = revenue_share_policy_id::text
      AND buyer_snapshot IS NOT NULL
      AND jsonb_typeof(buyer_snapshot) = 'object'
      AND buyer_snapshot <> '{}'::jsonb
      AND discount_snapshot IS NOT NULL
      AND jsonb_typeof(discount_snapshot) = 'object'
      AND discount_snapshot ? 'discountPaise'
      AND discount_snapshot->>'discountPaise' = discount_amount_paise::text
    )
  ) NOT VALID;

INSERT INTO app_metadata(key,value)
VALUES ('schema','current-course-order-financial-snapshot-guard-v211')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
