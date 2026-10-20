-- V156: make e-invoice applicability an explicitly reviewed, auditable legal-seller decision.
ALTER TABLE billing_legal_entity
  ADD COLUMN einvoice_applicability_reviewed_by_staff_id bigint REFERENCES staff_account(id) ON DELETE RESTRICT,
  ADD COLUMN einvoice_applicability_reviewed_at timestamptz,
  ADD COLUMN einvoice_applicability_review_note text;

-- An old status cannot count as reviewed without the evidence introduced in this migration.
UPDATE billing_legal_entity
SET einvoice_applicability='UNREVIEWED'
WHERE einvoice_applicability IN ('NOT_REQUIRED','REQUIRED')
  AND (einvoice_applicability_reviewed_by_staff_id IS NULL
    OR einvoice_applicability_reviewed_at IS NULL
    OR einvoice_applicability_review_note IS NULL
    OR char_length(btrim(einvoice_applicability_review_note)) NOT BETWEEN 20 AND 1000);

ALTER TABLE billing_legal_entity
  ADD CONSTRAINT ck_billing_legal_entity_einvoice_review_evidence
  CHECK (
    (einvoice_applicability='UNREVIEWED'
      AND einvoice_applicability_reviewed_by_staff_id IS NULL
      AND einvoice_applicability_reviewed_at IS NULL
      AND einvoice_applicability_review_note IS NULL)
    OR
    (einvoice_applicability IN ('NOT_REQUIRED','REQUIRED')
      AND einvoice_applicability_reviewed_by_staff_id IS NOT NULL
      AND einvoice_applicability_reviewed_at IS NOT NULL
      AND einvoice_applicability_review_note IS NOT NULL
      AND char_length(btrim(einvoice_applicability_review_note)) BETWEEN 20 AND 1000)
  );

INSERT INTO app_metadata(key,value)
VALUES ('schema','reviewed-gst-einvoice-applicability-v156')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
