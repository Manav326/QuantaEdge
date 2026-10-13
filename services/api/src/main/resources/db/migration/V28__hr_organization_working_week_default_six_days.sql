-- The owner-approved default is a six-day working week; organizations may
-- explicitly configure five days through the work-schedule API.
-- Preserve every existing organization's configured value during upgrade.
ALTER TABLE hr_organization
  ALTER COLUMN working_days_per_week SET DEFAULT 6;
