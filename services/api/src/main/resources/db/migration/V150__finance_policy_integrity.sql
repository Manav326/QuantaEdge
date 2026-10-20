-- V150: integrity refinements for versioned finance policies and platform-scoped audit actions.
-- This is an additive follow-up to V149; never rewrite historical migrations after deployment.

ALTER TABLE course_tax_profile
  ADD CONSTRAINT uq_course_tax_profile_offer_version UNIQUE (institution_offer_id,version),
  ADD CONSTRAINT ck_course_tax_profile_non_taxable_cess
    CHECK (tax_treatment='TAXABLE' OR cess_rate_basis_points=0);

-- A platform-scope template action has no tenant or seller entity by design. Its audit record is
-- still attributable to an explicit HRMS actor; tenant/seller IDs remain optional context.
ALTER TABLE billing_financial_audit_log
  DROP CONSTRAINT billing_financial_audit_log_check,
  ADD CONSTRAINT ck_billing_financial_audit_actor
    CHECK (actor_staff_id IS NOT NULL);

INSERT INTO app_metadata(key,value)
VALUES ('schema','finance-policy-integrity-v150')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
