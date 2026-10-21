-- The owner-approved default for newly created organizations is six days.
-- Existing organizations retain their explicitly stored five- or six-day configuration.
ALTER TABLE hr_organization
  ALTER COLUMN working_days_per_week SET DEFAULT 6;
