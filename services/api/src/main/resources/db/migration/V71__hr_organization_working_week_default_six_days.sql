-- The owner-approved default is a six-day working week; organizations may
-- explicitly configure five days through the work-schedule API.
ALTER TABLE hr_organization
  ALTER COLUMN working_days_per_week SET DEFAULT 6;

-- V60 seeds the default QuantaEdge organization before V62 adds this setting.
-- Normalize that original seeded row from the old schema default (5) to the
-- newly approved default (6). Do not overwrite other organizations' choices.
UPDATE hr_organization
SET working_days_per_week = 6
WHERE name = 'QuantaEdge'
  AND working_days_per_week = 5;
