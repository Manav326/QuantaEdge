-- V216: preserve the previous approved bank account while a replacement is registered/reviewed.
ALTER TABLE finance_payout_beneficiary
  ADD COLUMN replaces_beneficiary_id bigint REFERENCES finance_payout_beneficiary(id) ON DELETE RESTRICT;

DROP INDEX uq_finance_payout_beneficiary_institution;
CREATE UNIQUE INDEX uq_finance_payout_beneficiary_institution
  ON finance_payout_beneficiary(institution_id)
  WHERE institution_id IS NOT NULL AND status<>'REJECTED' AND replaces_beneficiary_id IS NULL;

DROP INDEX uq_finance_payout_beneficiary_company;
CREATE UNIQUE INDEX uq_finance_payout_beneficiary_company
  ON finance_payout_beneficiary(legal_entity_id)
  WHERE legal_entity_id IS NOT NULL AND status<>'REJECTED' AND replaces_beneficiary_id IS NULL;

-- A replacement can coexist with the previous active row while pending, but only one active row
-- may exist for a recipient and only one replacement request may target a particular row.
CREATE UNIQUE INDEX uq_finance_payout_beneficiary_active_institution
  ON finance_payout_beneficiary(institution_id)
  WHERE institution_id IS NOT NULL AND status='ACTIVE';
CREATE UNIQUE INDEX uq_finance_payout_beneficiary_active_company
  ON finance_payout_beneficiary(legal_entity_id)
  WHERE legal_entity_id IS NOT NULL AND status='ACTIVE';
CREATE UNIQUE INDEX uq_finance_payout_beneficiary_replacement_target
  ON finance_payout_beneficiary(replaces_beneficiary_id)
  WHERE replaces_beneficiary_id IS NOT NULL AND status<>'REJECTED';

INSERT INTO app_metadata(key,value)
VALUES ('schema','audited-payout-beneficiary-replacement-v216')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
