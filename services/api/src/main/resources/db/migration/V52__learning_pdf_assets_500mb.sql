-- V52: align the private PDF asset limit with the 500 MiB Admin upload limit.
-- NCERT source books can exceed 50 MiB; keep the database constraint consistent with
-- the API's ADMIN_MAX_PDF_BYTES and application.yml multipart ceilings.
ALTER TABLE learning_pdf_asset
  DROP CONSTRAINT IF EXISTS learning_pdf_asset_file_size_bytes_check;

ALTER TABLE learning_pdf_asset
  ADD CONSTRAINT ck_learning_pdf_asset_file_size_bytes
  CHECK (file_size_bytes BETWEEN 1 AND 524288000);

INSERT INTO app_metadata(key, value)
VALUES ('schema', 'learning-pdf-assets-500mb-v52')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
