-- V154: enforce GST invoice serial length and keep the chosen prefix short enough for a 16-character number.
-- NOT VALID preserves any legacy rows while enforcing these constraints for all newly issued invoices.
ALTER TABLE billing_legal_entity
  ADD CONSTRAINT ck_billing_legal_entity_invoice_series_prefix_len
  CHECK (char_length(invoice_series_code) BETWEEN 1 AND 3) NOT VALID;

ALTER TABLE fiscal_document
  ADD CONSTRAINT ck_fiscal_document_number_max_16
  CHECK (char_length(document_number) BETWEEN 1 AND 16) NOT VALID;

INSERT INTO app_metadata(key,value)
VALUES ('schema','gst-invoice-serial-length-v154')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
