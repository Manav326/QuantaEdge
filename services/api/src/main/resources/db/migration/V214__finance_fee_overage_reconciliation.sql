-- Record actual provider charges that exceed the amount safely deductible from beneficiary balances.
-- These overages are borne by the platform rather than creating negative beneficiary earnings.
ALTER TABLE course_payment_order_fee
  ADD COLUMN platform_borne_overage_paise bigint NOT NULL DEFAULT 0
  CHECK(platform_borne_overage_paise>=0);

ALTER TABLE finance_payout_batch
  ADD COLUMN platform_borne_overage_paise bigint NOT NULL DEFAULT 0
  CHECK(platform_borne_overage_paise>=0);

COMMENT ON COLUMN course_payment_order_fee.platform_borne_overage_paise IS
  'Confirmed collection processing fee/tax amount that exceeds the pre-charge distributable pool and is borne by the platform.';
COMMENT ON COLUMN finance_payout_batch.platform_borne_overage_paise IS
  'Confirmed RazorpayX transfer fee/tax amount in excess of the payout allocation weights, borne by the platform rather than deducted from beneficiaries.';

INSERT INTO app_metadata(key,value)
VALUES ('schema','finance-fee-overage-reconciliation-v214')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
