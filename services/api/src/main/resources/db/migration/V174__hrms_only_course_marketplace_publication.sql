-- V174: make HRMS approval/publication attributable and enforce it for new offer writes.
-- Existing offers are backfilled only when the lifecycle ledger proves an HRMS actor performed
-- the corresponding action. Legacy or institution-admin publication is not treated as approval.
ALTER TABLE institution_marketplace_offer
  ADD COLUMN IF NOT EXISTS hrms_approved_by_staff_id bigint REFERENCES staff_account(id) ON DELETE SET NULL,
  ADD COLUMN IF NOT EXISTS hrms_approved_at timestamptz,
  ADD COLUMN IF NOT EXISTS hrms_published_by_staff_id bigint REFERENCES staff_account(id) ON DELETE SET NULL,
  ADD COLUMN IF NOT EXISTS hrms_published_at timestamptz;

-- Recover trustworthy HRMS attribution from the append-only course lifecycle ledger.
-- Rows logged by an institution-admin actor have actor_platform_staff_id NULL and are excluded.
WITH latest_hrms_approval AS (
  SELECT DISTINCT ON (e.offer_id)
    e.offer_id, e.actor_platform_staff_id, e.created_at
  FROM institution_course_lifecycle_event e
  WHERE e.offer_id IS NOT NULL
    AND e.event_type = 'OFFER_APPROVED'
    AND e.actor_platform_staff_id IS NOT NULL
  ORDER BY e.offer_id, e.created_at DESC, e.id DESC
)
UPDATE institution_marketplace_offer o
SET hrms_approved_by_staff_id = e.actor_platform_staff_id,
    hrms_approved_at = e.created_at
FROM latest_hrms_approval e
WHERE o.id = e.offer_id
  AND o.hrms_approved_by_staff_id IS NULL;

WITH latest_hrms_publication AS (
  SELECT DISTINCT ON (e.offer_id)
    e.offer_id, e.actor_platform_staff_id, e.created_at
  FROM institution_course_lifecycle_event e
  WHERE e.offer_id IS NOT NULL
    AND e.event_type = 'OFFER_PUBLISHED'
    AND e.actor_platform_staff_id IS NOT NULL
  ORDER BY e.offer_id, e.created_at DESC, e.id DESC
)
UPDATE institution_marketplace_offer o
SET hrms_published_by_staff_id = e.actor_platform_staff_id,
    hrms_published_at = e.created_at
FROM latest_hrms_publication e
WHERE o.id = e.offer_id
  AND o.hrms_published_by_staff_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_institution_marketplace_offer_hrms_publication
  ON institution_marketplace_offer(institution_id, status, hrms_published_by_staff_id, updated_at DESC);

-- NOT VALID preserves historical rows while enforcing the invariant on every new or changed row.
-- Historic rows without ledger-backed HRMS approval stay stored, but must not be discoverable as
-- current Marketplace offers until they pass the proper HRMS workflow.
DO $course_hrms_publication_checks$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'ck_marketplace_published_by_hrms'
      AND conrelid = 'public.institution_marketplace_offer'::regclass
  ) THEN
    ALTER TABLE institution_marketplace_offer
      ADD CONSTRAINT ck_marketplace_published_by_hrms
      CHECK (
        status <> 'PUBLISHED'
        OR (
          hrms_approved_by_staff_id IS NOT NULL
          AND hrms_approved_at IS NOT NULL
          AND hrms_published_by_staff_id IS NOT NULL
          AND hrms_published_at IS NOT NULL
        )
      ) NOT VALID;
  END IF;
END
$course_hrms_publication_checks$;

COMMENT ON COLUMN institution_marketplace_offer.hrms_approved_by_staff_id IS
  'Authenticated HRMS staff actor who approved this exact institution Marketplace offer.';
COMMENT ON COLUMN institution_marketplace_offer.hrms_published_by_staff_id IS
  'Authenticated HRMS staff actor who published this exact institution Marketplace offer. Null is not public-eligible.';
COMMENT ON CONSTRAINT ck_marketplace_published_by_hrms ON institution_marketplace_offer IS
  'New/changed PUBLISHED offers require recorded HRMS approval and publication; historical rows are revalidated as they are changed.';

INSERT INTO app_metadata(key,value)
VALUES ('schema','course-hrms-publication-authority-v174')
ON CONFLICT(key) DO UPDATE SET value = EXCLUDED.value;
