-- V155: record HRMS/tax-adviser review of e-invoice applicability per legal seller.
-- REQUIRED is fail-closed until IRP/IRN + signed QR integration is available.
ALTER TABLE billing_legal_entity
  ADD COLUMN einvoice_applicability varchar(16) NOT NULL DEFAULT 'UNREVIEWED'
    CHECK (einvoice_applicability IN ('UNREVIEWED','NOT_REQUIRED','REQUIRED'));

INSERT INTO app_metadata(key,value)
VALUES ('schema','gst-einvoice-applicability-v155')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
