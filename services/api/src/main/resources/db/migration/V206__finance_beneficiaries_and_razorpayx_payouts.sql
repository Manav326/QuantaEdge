-- V206: extend the existing course revenue ledger to company beneficiaries and approved RazorpayX payouts.
-- Allow ordinary company legal entities in addition to the HRMS/platform entity and institution sellers.
-- The offer tax-profile link remains the explicit choice of legal seller for each course offer.
ALTER TABLE billing_legal_entity DROP CONSTRAINT IF EXISTS billing_legal_entity_entity_kind_check;
ALTER TABLE billing_legal_entity DROP CONSTRAINT IF EXISTS billing_legal_entity_check;
ALTER TABLE billing_legal_entity
  ADD CONSTRAINT billing_legal_entity_entity_kind_check CHECK(entity_kind IN ('PLATFORM','INSTITUTION','COMPANY')),
  ADD CONSTRAINT billing_legal_entity_owner_scope_check CHECK(
    (entity_kind='INSTITUTION' AND institution_id IS NOT NULL)
    OR (entity_kind IN ('PLATFORM','COMPANY') AND institution_id IS NULL)
  );

-- This migration creates no beneficiaries, bank accounts, payout batches, payout instructions or financial values.
-- Bank account numbers are sent to RazorpayX server-to-server and are never persisted in QuantaEdge.

ALTER TABLE revenue_share_policy_recipient
  ADD COLUMN recipient_type varchar(16) NOT NULL DEFAULT 'INSTITUTION',
  ADD COLUMN legal_entity_id bigint REFERENCES billing_legal_entity(id) ON DELETE RESTRICT;
ALTER TABLE revenue_share_policy_recipient
  ALTER COLUMN institution_id DROP NOT NULL;
ALTER TABLE revenue_share_policy_recipient
  ADD CONSTRAINT revenue_share_policy_recipient_kind_check CHECK (
    (recipient_type='INSTITUTION' AND institution_id IS NOT NULL AND legal_entity_id IS NULL)
    OR (recipient_type='COMPANY' AND institution_id IS NULL AND legal_entity_id IS NOT NULL)
  );
ALTER TABLE revenue_share_policy_recipient
  ADD CONSTRAINT revenue_share_policy_recipient_company_scope_check CHECK (
    recipient_type<>'COMPANY' OR legal_entity_id IS NOT NULL
  );
CREATE UNIQUE INDEX uq_revenue_share_recipient_policy_company
  ON revenue_share_policy_recipient(policy_id,legal_entity_id)
  WHERE legal_entity_id IS NOT NULL;
ALTER TABLE revenue_share_policy_recipient
  ADD CONSTRAINT uq_revenue_share_recipient_id_policy_company UNIQUE(id,policy_id,legal_entity_id);

ALTER TABLE revenue_share_policy
  ADD COLUMN remainder_recipient_type varchar(24) NOT NULL DEFAULT 'PLATFORM_REMAINDER',
  ADD COLUMN remainder_institution_id bigint REFERENCES institution_tenant(id) ON DELETE RESTRICT,
  ADD COLUMN remainder_legal_entity_id bigint REFERENCES billing_legal_entity(id) ON DELETE RESTRICT,
  ADD CONSTRAINT revenue_share_policy_remainder_recipient_check CHECK (
    (remainder_recipient_type='INSTITUTION' AND remainder_institution_id IS NOT NULL AND remainder_legal_entity_id IS NULL)
    OR (remainder_recipient_type='COMPANY' AND remainder_institution_id IS NULL AND remainder_legal_entity_id IS NOT NULL)
    OR (remainder_recipient_type='PLATFORM_REMAINDER' AND remainder_institution_id IS NULL AND remainder_legal_entity_id IS NULL)
  );

-- A saved beneficiary allocation may reference an institution or a company legal entity.
-- Existing rows remain institution rows; old policy snapshots retain their original semantics.
ALTER TABLE revenue_share_allocation
  ALTER COLUMN policy_recipient_id DROP NOT NULL,
  ALTER COLUMN institution_id DROP NOT NULL,
  ADD COLUMN recipient_type varchar(16) NOT NULL DEFAULT 'INSTITUTION',
  ADD COLUMN legal_entity_id bigint REFERENCES billing_legal_entity(id) ON DELETE RESTRICT,
  ADD CONSTRAINT revenue_share_allocation_recipient_kind_check CHECK (
    (recipient_type='INSTITUTION' AND institution_id IS NOT NULL AND legal_entity_id IS NULL)
    OR (recipient_type='COMPANY' AND institution_id IS NULL AND legal_entity_id IS NOT NULL)
  ),
  ADD CONSTRAINT revenue_share_allocation_company_recipient_unique UNIQUE(course_payment_order_id,legal_entity_id);
DO $
DECLARE fk_name text;
BEGIN
  SELECT conname INTO fk_name
  FROM pg_constraint
  WHERE conrelid='revenue_share_allocation'::regclass
    AND contype='f'
    AND pg_get_constraintdef(oid) LIKE 'FOREIGN KEY (policy_recipient_id, policy_id, institution_id)%';
  IF fk_name IS NOT NULL THEN
    EXECUTE format('ALTER TABLE revenue_share_allocation DROP CONSTRAINT %I',fk_name);
  END IF;
END $;
ALTER TABLE revenue_share_allocation
  ADD CONSTRAINT revenue_share_allocation_policy_institution_fk
    FOREIGN KEY(policy_recipient_id,policy_id,institution_id)
    REFERENCES revenue_share_policy_recipient(id,policy_id,institution_id) ON DELETE RESTRICT,
  ADD CONSTRAINT revenue_share_allocation_policy_company_fk
    FOREIGN KEY(policy_recipient_id,policy_id,legal_entity_id)
    REFERENCES revenue_share_policy_recipient(id,policy_id,legal_entity_id) ON DELETE RESTRICT;

ALTER TABLE revenue_share_allocation_event
  ALTER COLUMN event_type TYPE varchar(40),
  DROP CONSTRAINT revenue_share_allocation_event_event_type_check,
  ADD CONSTRAINT revenue_share_allocation_event_event_type_check CHECK (
    event_type IN ('EARNED','PAYABLE','PAID','ON_HOLD','RELEASED','REVERSED','REFUNDED','PROCESSING_FEE_DEDUCTED')
  );

-- The default remainder recipient and payout schedule are explicit HRMS decisions, never guessed.
CREATE TABLE billing_finance_payout_settings (
  id smallint PRIMARY KEY CHECK(id=1),
  default_remainder_recipient_type varchar(16) NOT NULL CHECK(default_remainder_recipient_type IN ('INSTITUTION','COMPANY')),
  default_remainder_institution_id bigint REFERENCES institution_tenant(id) ON DELETE RESTRICT,
  default_remainder_legal_entity_id bigint REFERENCES billing_legal_entity(id) ON DELETE RESTRICT,
  payment_collection_legal_entity_id bigint REFERENCES billing_legal_entity(id) ON DELETE RESTRICT,
  razorpayx_funding_legal_entity_id bigint REFERENCES billing_legal_entity(id) ON DELETE RESTRICT,
  collection_arrangement_status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(collection_arrangement_status IN ('PENDING','VERIFIED','BLOCKED')),
  collection_arrangement_reference varchar(180),
  collection_arrangement_verified_by_staff_id bigint REFERENCES staff_account(id) ON DELETE RESTRICT,
  collection_arrangement_verified_at timestamptz, 
  payout_frequency varchar(16) NOT NULL DEFAULT 'MONTHLY' CHECK(payout_frequency='MONTHLY'),
  payout_minimum_paise bigint CHECK(payout_minimum_paise IS NULL OR payout_minimum_paise>=100),
  refund_reserve_basis_points integer CHECK(refund_reserve_basis_points IS NULL OR refund_reserve_basis_points BETWEEN 0 AND 10000),
  updated_by_staff_id bigint REFERENCES staff_account(id) ON DELETE RESTRICT,
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK (
    (default_remainder_recipient_type='INSTITUTION' AND default_remainder_institution_id IS NOT NULL AND default_remainder_legal_entity_id IS NULL)
    OR (default_remainder_recipient_type='COMPANY' AND default_remainder_institution_id IS NULL AND default_remainder_legal_entity_id IS NOT NULL)
  ),
  CHECK (
    collection_arrangement_status<>'VERIFIED'
    OR (payment_collection_legal_entity_id IS NOT NULL AND razorpayx_funding_legal_entity_id IS NOT NULL
        AND collection_arrangement_reference IS NOT NULL AND length(btrim(collection_arrangement_reference))>=3
        AND collection_arrangement_verified_by_staff_id IS NOT NULL AND collection_arrangement_verified_at IS NOT NULL)
  )
);

-- Only provider IDs and masked account details are stored. Full bank account numbers are not persisted.
CREATE TABLE finance_payout_beneficiary (
  id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  public_id uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
  beneficiary_type varchar(16) NOT NULL CHECK(beneficiary_type IN ('INSTITUTION','COMPANY')),
  institution_id bigint REFERENCES institution_tenant(id) ON DELETE RESTRICT,
  legal_entity_id bigint REFERENCES billing_legal_entity(id) ON DELETE RESTRICT,
  beneficiary_name varchar(240) NOT NULL CHECK(length(btrim(beneficiary_name))>0),
  bank_ifsc varchar(11) NOT NULL CHECK(bank_ifsc ~ '^[A-Z]{4}0[A-Z0-9]{6}$'),
  bank_account_last4 char(4) NOT NULL CHECK(bank_account_last4 ~ '^[0-9]{4}$'),
  provider_contact_id varchar(80),
  provider_fund_account_id varchar(80),
  status varchar(32) NOT NULL DEFAULT 'PROVISIONING'
    CHECK(status IN ('PROVISIONING','PENDING_APPROVAL','ACTIVE','SUSPENDED','REJECTED','RECONCILIATION_REQUIRED')),
  created_by_staff_id bigint NOT NULL REFERENCES staff_account(id) ON DELETE RESTRICT,
  approved_by_staff_id bigint REFERENCES staff_account(id) ON DELETE RESTRICT,
  approved_at timestamptz,
  rejection_reason varchar(500),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK(
    (beneficiary_type='INSTITUTION' AND institution_id IS NOT NULL AND legal_entity_id IS NULL)
    OR (beneficiary_type='COMPANY' AND institution_id IS NULL AND legal_entity_id IS NOT NULL)
  ),
  CHECK(status<>'ACTIVE' OR (provider_contact_id IS NOT NULL AND provider_fund_account_id IS NOT NULL AND approved_by_staff_id IS NOT NULL AND approved_at IS NOT NULL)),
  CHECK(approved_by_staff_id IS NULL OR approved_by_staff_id<>created_by_staff_id)
);
CREATE UNIQUE INDEX uq_finance_payout_beneficiary_institution
  ON finance_payout_beneficiary(institution_id) WHERE institution_id IS NOT NULL AND status<>'REJECTED';
CREATE UNIQUE INDEX uq_finance_payout_beneficiary_company
  ON finance_payout_beneficiary(legal_entity_id) WHERE legal_entity_id IS NOT NULL AND status<>'REJECTED';
CREATE UNIQUE INDEX uq_finance_payout_beneficiary_provider_contact
  ON finance_payout_beneficiary(provider_contact_id) WHERE provider_contact_id IS NOT NULL;
CREATE UNIQUE INDEX uq_finance_payout_beneficiary_provider_fund
  ON finance_payout_beneficiary(provider_fund_account_id) WHERE provider_fund_account_id IS NOT NULL;

-- Actual fees must be reconciled from a provider settlement record before that sale is payout-eligible.
-- The fee source and settlement reference are recorded; estimated fees are not accepted as final facts.
CREATE TABLE course_payment_order_fee (
  id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  course_payment_order_id bigint NOT NULL UNIQUE REFERENCES course_payment_order(id) ON DELETE RESTRICT,
  provider_payment_id varchar(80) NOT NULL,
  processing_fee_paise bigint NOT NULL CHECK(processing_fee_paise>=0),
  fee_tax_paise bigint NOT NULL CHECK(fee_tax_paise>=0),
  settlement_reference varchar(180) NOT NULL CHECK(length(btrim(settlement_reference)) BETWEEN 3 AND 180),
  source_type varchar(32) NOT NULL CHECK(source_type IN ('SETTLEMENT_REPORT','PROVIDER_API','MANUAL_RECONCILIATION')),
  idempotency_key varchar(180) NOT NULL UNIQUE,
  confirmed_by_staff_id bigint NOT NULL REFERENCES staff_account(id) ON DELETE RESTRICT,
  confirmed_at timestamptz NOT NULL DEFAULT now(),
  details jsonb NOT NULL DEFAULT '{}'::jsonb CHECK(jsonb_typeof(details)='object')
);
CREATE TRIGGER trg_course_payment_order_fee_immutable
BEFORE UPDATE OR DELETE ON course_payment_order_fee
FOR EACH ROW EXECUTE FUNCTION reject_billing_financial_mutation();

CREATE TABLE finance_payout_batch (
  id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  public_id uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
  period_start date NOT NULL,
  period_end date NOT NULL,
  currency char(3) NOT NULL DEFAULT 'INR' CHECK(currency='INR'),
  status varchar(24) NOT NULL DEFAULT 'DRAFT'
    CHECK(status IN ('DRAFT','PENDING_APPROVAL','APPROVED','PROCESSING','PARTIAL','COMPLETED','FAILED','CANCELLED')),
  beneficiary_count integer NOT NULL DEFAULT 0 CHECK(beneficiary_count>=0),
  total_amount_paise bigint NOT NULL DEFAULT 0 CHECK(total_amount_paise>=0),
  provider_fee_total_paise bigint CHECK(provider_fee_total_paise IS NULL OR provider_fee_total_paise>=0),
  provider_fee_tax_total_paise bigint CHECK(provider_fee_tax_total_paise IS NULL OR provider_fee_tax_total_paise>=0),
  provider_fee_reconciled_at timestamptz,
  created_by_staff_id bigint NOT NULL REFERENCES staff_account(id) ON DELETE RESTRICT,
  approved_by_staff_id bigint REFERENCES staff_account(id) ON DELETE RESTRICT,
  approved_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK(period_end>=period_start),
  CHECK(approved_by_staff_id IS NULL OR approved_by_staff_id<>created_by_staff_id)
);
CREATE UNIQUE INDEX uq_finance_payout_month_batch
  ON finance_payout_batch(period_start,period_end,currency)
  WHERE status NOT IN ('FAILED','CANCELLED');

CREATE TABLE finance_payout (
  id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  public_id uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
  batch_id bigint NOT NULL REFERENCES finance_payout_batch(id) ON DELETE RESTRICT,
  beneficiary_id bigint NOT NULL REFERENCES finance_payout_beneficiary(id) ON DELETE RESTRICT,
  amount_paise bigint NOT NULL CHECK(amount_paise>0),
  currency char(3) NOT NULL DEFAULT 'INR' CHECK(currency='INR'),
  provider_idempotency_key varchar(80) NOT NULL UNIQUE,
  provider_payout_id varchar(80) UNIQUE,
  provider_status varchar(32),
  provider_fee_paise bigint CHECK(provider_fee_paise IS NULL OR provider_fee_paise>=0),
  provider_fee_tax_paise bigint CHECK(provider_fee_tax_paise IS NULL OR provider_fee_tax_paise>=0),
  provider_fee_reference varchar(180),
  provider_fee_source_type varchar(32) CHECK(provider_fee_source_type IS NULL OR provider_fee_source_type IN ('PROVIDER_API','SETTLEMENT_REPORT','MANUAL_RECONCILIATION')),
  provider_fee_confirmed_by_staff_id bigint REFERENCES staff_account(id) ON DELETE RESTRICT,
  provider_fee_confirmed_at timestamptz,
  status varchar(32) NOT NULL DEFAULT 'PENDING_APPROVAL'
    CHECK(status IN ('PENDING_APPROVAL','APPROVED','SUBMITTING','PENDING','QUEUED','PROCESSING','PROCESSED','REVERSED','FAILED','REJECTED','RECONCILIATION_REQUIRED')),
  transfer_reference varchar(180),
  status_details varchar(500),
  created_by_staff_id bigint NOT NULL REFERENCES staff_account(id) ON DELETE RESTRICT,
  approved_by_staff_id bigint REFERENCES staff_account(id) ON DELETE RESTRICT,
  approved_at timestamptz,
  submitted_at timestamptz,
  processed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK(approved_by_staff_id IS NULL OR approved_by_staff_id<>created_by_staff_id)
);
CREATE INDEX idx_finance_payout_batch_status ON finance_payout(batch_id,status,id);
CREATE INDEX idx_finance_payout_beneficiary_status ON finance_payout(beneficiary_id,status,created_at DESC);

CREATE TABLE finance_payout_item (
  id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  payout_id bigint NOT NULL REFERENCES finance_payout(id) ON DELETE RESTRICT,
  allocation_id bigint NOT NULL REFERENCES revenue_share_allocation(id) ON DELETE RESTRICT,
  amount_paise bigint NOT NULL CHECK(amount_paise>0),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(payout_id,allocation_id)
);
CREATE INDEX idx_finance_payout_item_allocation ON finance_payout_item(allocation_id,payout_id);

CREATE TABLE finance_payout_event (
  id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  payout_id bigint NOT NULL REFERENCES finance_payout(id) ON DELETE RESTRICT,
  event_type varchar(40) NOT NULL CHECK(event_type IN ('CREATED','APPROVED','SUBMITTED','PROVIDER_PENDING','QUEUED','PROCESSING','PROCESSED','REVERSED','FAILED','REJECTED','RECONCILIATION_REQUIRED','FEES_RECONCILED')),
  idempotency_key varchar(180) NOT NULL UNIQUE,
  actor_staff_id bigint REFERENCES staff_account(id) ON DELETE RESTRICT,
  provider_event_id varchar(120),
  details jsonb NOT NULL DEFAULT '{}'::jsonb CHECK(jsonb_typeof(details)='object'),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_finance_payout_event_history ON finance_payout_event(payout_id,created_at,id);

CREATE TABLE finance_payout_provider_event (
  id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  provider_event_id varchar(120) NOT NULL UNIQUE,
  provider_payout_id varchar(80),
  event_type varchar(80) NOT NULL,
  payload_sha256 char(64) NOT NULL CHECK(payload_sha256 ~ '^[0-9a-f]{64}$'),
  disposition varchar(32) NOT NULL CHECK(disposition IN ('PROCESSED','IGNORED','UNMATCHED','RECONCILIATION_REQUIRED')),
  received_at timestamptz NOT NULL DEFAULT now(),
  processed_at timestamptz
);
CREATE INDEX idx_finance_payout_provider_event_state ON finance_payout_provider_event(disposition,received_at DESC);

CREATE OR REPLACE FUNCTION reject_finance_payout_event_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% is append-only; create a new event instead', TG_TABLE_NAME USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER trg_finance_payout_event_immutable
BEFORE UPDATE OR DELETE ON finance_payout_event
FOR EACH ROW EXECUTE FUNCTION reject_finance_payout_event_mutation();
CREATE TRIGGER trg_finance_payout_provider_event_immutable
BEFORE UPDATE OR DELETE ON finance_payout_provider_event
FOR EACH ROW EXECUTE FUNCTION reject_finance_payout_event_mutation();

INSERT INTO staff_permission_catalog(permission_key,display_name,description) VALUES
 ('QE_FINANCE_PAYOUT_MANAGE','Manage course finance payouts','Review beneficiary accounts, prepare monthly beneficiary payout batches, approve transfers and reconcile RazorpayX outcomes.'),
 ('QE_FINANCE_PAYOUT_APPROVE','Approve course finance payouts','Independently approve or reject beneficiary and monthly payout transfers before they are submitted to RazorpayX.')
ON CONFLICT(permission_key) DO UPDATE
SET display_name=excluded.display_name,description=excluded.description;

-- Only the configured platform owner receives the initial grant. Additional users must be
-- explicitly granted these permissions; HRMS role names alone do not authorize payout approval.
INSERT INTO staff_permission_grant(staff_id,workspace_key,permission_key)
SELECT id,'HRMS','QE_FINANCE_PAYOUT_MANAGE'
FROM staff_account WHERE lower(username)='hrms_admin' AND active=true
ON CONFLICT(staff_id,workspace_key,permission_key) DO NOTHING;

INSERT INTO staff_permission_grant(staff_id,workspace_key,permission_key)
SELECT id,'HRMS','QE_FINANCE_PAYOUT_APPROVE'
FROM staff_account WHERE lower(username)='hrms_admin' AND active=true
ON CONFLICT(staff_id,workspace_key,permission_key) DO NOTHING;

INSERT INTO app_metadata(key,value)
VALUES ('schema','finance-beneficiaries-razorpayx-payouts-v206')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
