-- V188: retain the validated semantic package document on the active tenant-owned import ledger.
-- Older V183 rows remain valid with NULL snapshots; this migration does not reconstruct unavailable source JSON.

ALTER TABLE institution_course_package_import
  ADD COLUMN package_snapshot jsonb;

ALTER TABLE institution_course_package_import
  ADD CONSTRAINT ck_course_package_import_snapshot_object
  CHECK (package_snapshot IS NULL OR jsonb_typeof(package_snapshot)='object');

COMMENT ON COLUMN institution_course_package_import.package_snapshot IS
  'Semantic JSON package supplied at import, retained for provenance/reconciliation. Untrusted fields in this snapshot never grant rights, tenant ownership, review approval or publication authority. NULL means the historical package was not retained by the earlier writer.';

COMMENT ON TABLE course_package_import IS
  'OBSOLETE FOR NEW WRITES: legacy V179 import ledger retained for compatibility. The current source-owned importer writes to institution_course_package_import; remove this table only in a separate reviewed cleanup after confirming no active readers and reconciling historical records.';

INSERT INTO app_metadata(key,value)
VALUES ('schema','course-package-source-snapshot-v188')
ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value;
