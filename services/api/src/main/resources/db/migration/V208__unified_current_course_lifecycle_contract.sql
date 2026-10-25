-- V208: one active course-publication contract; preserve, but retire, old pipeline rows.
--
-- Logical identity: institution_course_identity
-- Content graph/version: curriculum_program
-- Source release: institution_course_release
-- Destination assignment: institution_course_assignment (ORIGIN or HRMS only)
-- Institution offer: institution_marketplace_offer
-- Learner order/access/history: exact offer + exact course graph, with immutable snapshots.
--
-- Older rows are not deleted. They are archived/revoked, kept for audit, and excluded from
-- the resolver used by Marketplace, free enrollment, parent purchases and finance catalog.

-- Obsolescence is explicit on the affected row, not only implicit in a status/comment.
-- Retirement markers do not rewrite financial or learner-history foreign keys.
ALTER TABLE institution_course_release
  ADD COLUMN IF NOT EXISTS obsolete_at timestamptz,
  ADD COLUMN IF NOT EXISTS obsolete_reason text;
ALTER TABLE institution_course_assignment
  ADD COLUMN IF NOT EXISTS obsolete_at timestamptz,
  ADD COLUMN IF NOT EXISTS obsolete_reason text;
ALTER TABLE institution_marketplace_offer
  ADD COLUMN IF NOT EXISTS obsolete_at timestamptz,
  ADD COLUMN IF NOT EXISTS obsolete_reason text;
ALTER TABLE course_marketplace_offer
  ADD COLUMN IF NOT EXISTS obsolete_at timestamptz,
  ADD COLUMN IF NOT EXISTS obsolete_reason text;

ALTER TABLE student_certificate
  ADD COLUMN IF NOT EXISTS course_identity_id bigint
    REFERENCES institution_course_identity(id) ON DELETE RESTRICT;

UPDATE student_certificate certificate
SET course_identity_id = program.course_identity_id
FROM curriculum_program program
WHERE certificate.program_id = program.id
  AND certificate.course_identity_id IS NULL
  AND program.course_identity_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_student_certificate_course_identity
  ON student_certificate(course_identity_id, institution_id, student_id);

-- Enforce the new certificate identity contract for inserts, without blocking
-- revocation or audit updates to historical certificates that cannot be mapped safely.
CREATE OR REPLACE FUNCTION qe_require_course_identity_for_new_certificate()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
  IF NEW.program_id IS NULL OR NEW.course_identity_id IS NULL THEN
    RAISE EXCEPTION
      'COURSE_IDENTITY_REQUIRED: newly issued certificates must reference a stable course identity and exact content version.'
      USING ERRCODE = '23514';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM curriculum_program program
    JOIN institution_course_identity identity
      ON identity.id = program.course_identity_id
     AND identity.status = 'ACTIVE'
     AND identity.course_type_code = program.course_type_code
    WHERE program.id = NEW.program_id
      AND program.course_identity_id = NEW.course_identity_id
      AND program.status = 'ACTIVE'
      AND program.code <> 'GENERAL'
  ) THEN
    RAISE EXCEPTION
      'COURSE_IDENTITY_MISMATCH: certificate course identity does not match an active content version.'
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END
$function$;

DROP TRIGGER IF EXISTS trg_qe_certificate_course_identity_insert ON student_certificate;
CREATE TRIGGER trg_qe_certificate_course_identity_insert
  BEFORE INSERT ON student_certificate
  FOR EACH ROW EXECUTE FUNCTION qe_require_course_identity_for_new_certificate();

CREATE OR REPLACE FUNCTION qe_current_course_offer_revision(p_offer_id bigint)
RETURNS boolean
LANGUAGE sql
STABLE
AS $function$
  SELECT EXISTS (
    SELECT 1
    FROM institution_marketplace_offer offer
    JOIN institution_course_assignment assignment
      ON assignment.id = offer.assignment_id
     AND assignment.institution_id = offer.institution_id
     AND assignment.status = 'ASSIGNED'
     AND assignment.assignment_source IN ('ORIGIN','HRMS')
    JOIN institution_course_release release
      ON release.id = offer.course_release_id
     AND release.program_id = offer.course_id
    JOIN curriculum_program program
      ON program.id = offer.course_id
     AND program.status = 'ACTIVE'
     AND program.code <> 'GENERAL'
    JOIN institution_course_identity identity
      ON identity.id = program.course_identity_id
     AND identity.status = 'ACTIVE'
     AND identity.source_institution_id = release.source_institution_id
     AND identity.course_type_code = program.course_type_code
    JOIN institution_tenant destination
      ON destination.id = offer.institution_id
     AND destination.status = 'ACTIVE'
    WHERE offer.id = p_offer_id
      AND offer.status <> 'ARCHIVED'
      AND offer.legacy_migration = false
      AND release.release_state = 'SHAREABLE'
      AND release.legacy_migration = false
      AND (
        assignment.assignment_source <> 'ORIGIN'
        OR assignment.institution_id = release.source_institution_id
      )
      AND course_marketplace_content_type_ready(program.id)
      AND course_release_fingerprint_current(
        release.program_id, release.content_fingerprint::text, release.updated_at
      )
      AND NOT EXISTS (
        SELECT 1
        FROM institution_course_assignment newer_assignment
        JOIN institution_course_release newer_release
          ON newer_release.id = newer_assignment.course_release_id
        JOIN curriculum_program newer_program
          ON newer_program.id = newer_release.program_id
        JOIN institution_course_identity newer_identity
          ON newer_identity.id = newer_program.course_identity_id
        WHERE newer_assignment.institution_id = offer.institution_id
          AND newer_assignment.status = 'ASSIGNED'
          AND newer_assignment.assignment_source IN ('ORIGIN','HRMS')
          AND (
            newer_assignment.assignment_source <> 'ORIGIN'
            OR newer_assignment.institution_id = newer_release.source_institution_id
          )
          AND newer_release.release_state = 'SHAREABLE'
          AND newer_release.legacy_migration = false
          AND newer_release.source_institution_id = newer_identity.source_institution_id
          AND newer_identity.id = identity.id
          AND newer_identity.status = 'ACTIVE'
          AND newer_program.status = 'ACTIVE'
          AND newer_program.code <> 'GENERAL'
          AND newer_program.course_type_code = newer_identity.course_type_code
          AND course_marketplace_content_type_ready(newer_program.id)
          AND course_release_fingerprint_current(
            newer_release.program_id,
            newer_release.content_fingerprint::text,
            newer_release.updated_at
          )
          AND (
            newer_program.content_version_no > program.content_version_no
            OR (
              newer_program.content_version_no = program.content_version_no
              AND (
                newer_release.release_version > release.release_version
                OR (
                  newer_release.release_version = release.release_version
                  AND newer_release.id > release.id
                )
              )
            )
          )
      )
  )
$function$;

-- The latest candidate release and the currently live published release are distinct:
-- a newly shareable release must not hide an older published version before HRMS approves
-- and publishes the replacement offer. This resolver moves the public listing only at publication.
CREATE OR REPLACE FUNCTION qe_marketplace_offer_publicly_sellable(p_offer_id bigint)
RETURNS boolean
LANGUAGE sql
STABLE
AS $function$
  SELECT EXISTS (
    SELECT 1
    FROM institution_marketplace_offer offer
    JOIN institution_course_assignment assignment
      ON assignment.id = offer.assignment_id
     AND assignment.institution_id = offer.institution_id
     AND assignment.status = 'ASSIGNED'
     AND assignment.assignment_source IN ('ORIGIN','HRMS')
    JOIN institution_course_release release
      ON release.id = offer.course_release_id
     AND release.program_id = offer.course_id
     AND release.release_state = 'SHAREABLE'
     AND release.legacy_migration = false
    JOIN curriculum_program program
      ON program.id = offer.course_id
     AND program.status = 'ACTIVE'
     AND program.code <> 'GENERAL'
    JOIN institution_course_identity identity
      ON identity.id = program.course_identity_id
     AND identity.status = 'ACTIVE'
     AND identity.source_institution_id = release.source_institution_id
     AND identity.course_type_code = program.course_type_code
    JOIN institution_tenant destination
      ON destination.id = offer.institution_id
     AND destination.status = 'ACTIVE'
    WHERE offer.id = p_offer_id
      AND offer.status = 'PUBLISHED'
      AND offer.legacy_migration = false
      AND offer.hrms_approved_by_staff_id IS NOT NULL
      AND offer.hrms_approved_at IS NOT NULL
      AND offer.hrms_published_by_staff_id IS NOT NULL
      AND offer.hrms_published_at IS NOT NULL
      AND (
        assignment.assignment_source <> 'ORIGIN'
        OR assignment.institution_id = release.source_institution_id
      )
      AND course_marketplace_content_type_ready(program.id)
      AND course_release_fingerprint_current(
        release.program_id, release.content_fingerprint::text, release.updated_at
      )
      AND NOT EXISTS (
        SELECT 1
        FROM institution_marketplace_offer newer_offer
        JOIN institution_course_assignment newer_assignment
          ON newer_assignment.id = newer_offer.assignment_id
         AND newer_assignment.institution_id = newer_offer.institution_id
         AND newer_assignment.status = 'ASSIGNED'
         AND newer_assignment.assignment_source IN ('ORIGIN','HRMS')
        JOIN institution_course_release newer_release
          ON newer_release.id = newer_offer.course_release_id
         AND newer_release.program_id = newer_offer.course_id
         AND newer_release.release_state = 'SHAREABLE'
         AND newer_release.legacy_migration = false
        JOIN curriculum_program newer_program
          ON newer_program.id = newer_offer.course_id
         AND newer_program.status = 'ACTIVE'
         AND newer_program.code <> 'GENERAL'
        JOIN institution_course_identity newer_identity
          ON newer_identity.id = newer_program.course_identity_id
         AND newer_identity.status = 'ACTIVE'
         AND newer_identity.source_institution_id = newer_release.source_institution_id
         AND newer_identity.course_type_code = newer_program.course_type_code
        WHERE newer_offer.institution_id = offer.institution_id
          AND newer_offer.status = 'PUBLISHED'
          AND newer_offer.legacy_migration = false
          AND newer_offer.hrms_approved_by_staff_id IS NOT NULL
          AND newer_offer.hrms_approved_at IS NOT NULL
          AND newer_offer.hrms_published_by_staff_id IS NOT NULL
          AND newer_offer.hrms_published_at IS NOT NULL
          AND newer_identity.id = identity.id
          AND (
            newer_assignment.assignment_source <> 'ORIGIN'
            OR newer_assignment.institution_id = newer_release.source_institution_id
          )
          AND course_marketplace_content_type_ready(newer_program.id)
          AND course_release_fingerprint_current(
            newer_release.program_id,
            newer_release.content_fingerprint::text,
            newer_release.updated_at
          )
          AND (
            newer_program.content_version_no > program.content_version_no
            OR (
              newer_program.content_version_no = program.content_version_no
              AND (
                newer_release.release_version > release.release_version
                OR (
                  newer_release.release_version = release.release_version
                  AND newer_release.id > release.id
                )
              )
            )
          )
      )
  )
$function$;

COMMENT ON FUNCTION qe_current_course_offer_revision(bigint) IS
  'Canonical resolver for the current, active institution offer revision. Rejects archived/migrated offers, legacy assignments/releases, stale release versions, inactive tenants, unready content and invalid release fingerprints.';
COMMENT ON FUNCTION qe_marketplace_offer_publicly_sellable(bigint) IS
  'Canonical learner-facing sales eligibility. Keeps the previous live offer sellable while a newer version is pending listing approval; swaps only when a newer approved and published offer is eligible. Do not reimplement this rule in UI code.';

-- Preserve the old source enum values as historical metadata, but never let them be reused
-- to create or reactivate an assignment. A legacy row can only be retired to REVOKED.
CREATE OR REPLACE FUNCTION qe_reject_obsolete_course_assignment_write()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
  IF TG_OP = 'UPDATE' AND OLD.assignment_source NOT IN ('ORIGIN','HRMS') THEN
    IF OLD.status = 'ASSIGNED'
       AND NEW.status = 'REVOKED'
       AND NEW.assignment_source = OLD.assignment_source
       AND (to_jsonb(OLD) - ARRAY['status','revoked_at','revoked_by_staff_id','updated_at','obsolete_at','obsolete_reason'])
         = (to_jsonb(NEW) - ARRAY['status','revoked_at','revoked_by_staff_id','updated_at','obsolete_at','obsolete_reason']) THEN
      RETURN NEW;
    END IF;

    RAISE EXCEPTION
      'OBSOLETE_COURSE_ASSIGNMENT: legacy assignment rows are historical; create a new ORIGIN or HRMS assignment through the current course lifecycle.'
      USING ERRCODE = '55000';
  END IF;

  IF NEW.assignment_source NOT IN ('ORIGIN','HRMS') THEN
    RAISE EXCEPTION
      'OBSOLETE_COURSE_ASSIGNMENT_SOURCE: only ORIGIN and HRMS assignments may be created.'
      USING ERRCODE = '55000';
  END IF;

  IF NEW.status = 'ASSIGNED' AND NOT EXISTS (
    SELECT 1
    FROM institution_course_release release
    WHERE release.id = NEW.course_release_id
      AND release.legacy_migration = false
      AND release.release_state <> 'LEGACY_MIGRATED'
      AND (
        (NEW.assignment_source = 'ORIGIN'
          AND release.source_institution_id = NEW.institution_id)
        OR
        (NEW.assignment_source = 'HRMS'
          AND release.release_state = 'SHAREABLE'
          AND release.source_institution_id <> NEW.institution_id)
      )
  ) THEN
    RAISE EXCEPTION
      'CURRENT_COURSE_RELEASE_REQUIRED: origin assignments must belong to the source institution; HRMS assignments require a current shareable release from another institution.'
      USING ERRCODE = '23514';
  END IF;

  RETURN NEW;
END
$function$;

DROP TRIGGER IF EXISTS trg_qe_current_course_assignment_only
  ON institution_course_assignment;
CREATE TRIGGER trg_qe_current_course_assignment_only
  BEFORE INSERT OR UPDATE ON institution_course_assignment
  FOR EACH ROW EXECUTE FUNCTION qe_reject_obsolete_course_assignment_write();

-- A migrated offer may remain in the database only after being archived. Existing rows are
-- retained, but this constraint stops new legacy offers and stops a legacy offer being republished.
DO $legacy_offer_constraint$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'ck_institution_offer_legacy_rows_archived'
      AND conrelid = 'public.institution_marketplace_offer'::regclass
  ) THEN
    ALTER TABLE institution_marketplace_offer
      ADD CONSTRAINT ck_institution_offer_legacy_rows_archived
      CHECK (legacy_migration = false OR status = 'ARCHIVED') NOT VALID;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'ck_institution_offer_current_hrms_publication'
      AND conrelid = 'public.institution_marketplace_offer'::regclass
  ) THEN
    ALTER TABLE institution_marketplace_offer
      ADD CONSTRAINT ck_institution_offer_current_hrms_publication
      CHECK (
        status <> 'PUBLISHED'
        OR (
          legacy_migration = false
          AND hrms_approved_by_staff_id IS NOT NULL
          AND hrms_approved_at IS NOT NULL
          AND hrms_published_by_staff_id IS NOT NULL
          AND hrms_published_at IS NOT NULL
        )
      ) NOT VALID;
  END IF;
END
$legacy_offer_constraint$;

-- Record why each visible old offer is being retired before changing its status.
INSERT INTO institution_course_lifecycle_event(
  institution_id, course_release_id, assignment_id, offer_id, event_type,
  details, created_at
)
SELECT offer.institution_id, offer.course_release_id, offer.assignment_id, offer.id,
       'OFFER_ARCHIVED_OBSOLETE_PIPELINE',
       jsonb_build_object(
         'migration', 'V208',
         'previousStatus', offer.status,
         'legacyMigration', offer.legacy_migration,
         'reason', 'Offer does not satisfy the single current course lifecycle contract; historical financial references are retained.'
       ),
       now()
FROM institution_marketplace_offer offer
WHERE offer.status <> 'ARCHIVED'
  AND (
    offer.legacy_migration = true
    OR (
      NOT qe_current_course_offer_revision(offer.id)
      AND NOT qe_marketplace_offer_publicly_sellable(offer.id)
    )
    OR (
      offer.status = 'PUBLISHED'
      AND NOT qe_marketplace_offer_publicly_sellable(offer.id)
    )
  );

UPDATE institution_marketplace_offer offer
SET status = 'ARCHIVED',
    obsolete_at = coalesce(offer.obsolete_at, now()),
    obsolete_reason = coalesce(offer.obsolete_reason,
      'OBSOLETE: offer is not the current eligible revision or is missing required HRMS publication approval; historical transaction references are retained.'),
    updated_at = now()
WHERE offer.legacy_migration = true
   OR (
     NOT qe_current_course_offer_revision(offer.id)
     AND NOT qe_marketplace_offer_publicly_sellable(offer.id)
   )
   OR (
     offer.status = 'PUBLISHED'
     AND NOT qe_marketplace_offer_publicly_sellable(offer.id)
   );

-- Explicitly mark migrated releases as obsolete, retaining their original content graph.
UPDATE institution_course_release
SET obsolete_at = coalesce(obsolete_at, now()),
    obsolete_reason = coalesce(obsolete_reason,
      'OBSOLETE: release was created by the prior migration pipeline; new assignments require a current source-owned release.')
WHERE legacy_migration = true OR release_state = 'LEGACY_MIGRATED';

-- Existing migrated releases are mutable only enough to mark them obsolete. No new or updated
-- row can enter the legacy release state or be used as an active release.
CREATE OR REPLACE FUNCTION qe_reject_obsolete_course_release_write()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
  IF TG_OP = 'UPDATE'
     AND (OLD.legacy_migration = true OR OLD.release_state = 'LEGACY_MIGRATED') THEN
    IF NEW.legacy_migration = OLD.legacy_migration
       AND NEW.release_state = OLD.release_state
       AND (to_jsonb(OLD) - ARRAY['obsolete_at','obsolete_reason','updated_at'])
         = (to_jsonb(NEW) - ARRAY['obsolete_at','obsolete_reason','updated_at']) THEN
      RETURN NEW;
    END IF;
    RAISE EXCEPTION
      'OBSOLETE_COURSE_RELEASE: migrated release rows are historical and may only receive obsolescence metadata.'
      USING ERRCODE = '55000';
  END IF;

  IF NEW.legacy_migration = true OR NEW.release_state = 'LEGACY_MIGRATED' THEN
    RAISE EXCEPTION
      'OBSOLETE_COURSE_RELEASE: new legacy-migrated releases are not supported by the current course lifecycle.'
      USING ERRCODE = '55000';
  END IF;

  RETURN NEW;
END
$function$;

DROP TRIGGER IF EXISTS trg_qe_obsolete_course_release_write ON institution_course_release;
CREATE TRIGGER trg_qe_obsolete_course_release_write
  BEFORE INSERT OR UPDATE ON institution_course_release
  FOR EACH ROW EXECUTE FUNCTION qe_reject_obsolete_course_release_write();

-- Mark and audit legacy assignments as unusable. This does not delete order, invoice,
-- entitlement, attempt, progress, certificate or audit history.
INSERT INTO institution_course_lifecycle_event(
  institution_id, course_release_id, assignment_id, event_type, details, created_at
)
SELECT assignment.institution_id, assignment.course_release_id, assignment.id,
       'ASSIGNMENT_REVOKED_OBSOLETE_PIPELINE',
       jsonb_build_object(
         'migration', 'V208',
         'previousSource', assignment.assignment_source,
         'reason', 'Legacy or migrated release/assignment is not part of the active course lifecycle.'
       ),
       now()
FROM institution_course_assignment assignment
JOIN institution_course_release release ON release.id = assignment.course_release_id
WHERE assignment.status = 'ASSIGNED'
  AND (
    assignment.assignment_source NOT IN ('ORIGIN','HRMS')
    OR release.legacy_migration = true
    OR release.release_state = 'LEGACY_MIGRATED'
  );

UPDATE institution_course_assignment assignment
SET status = 'REVOKED',
    revoked_at = coalesce(assignment.revoked_at, now()),
    obsolete_at = coalesce(assignment.obsolete_at, now()),
    obsolete_reason = coalesce(assignment.obsolete_reason,
      'OBSOLETE: assignment uses a retired source type or legacy-migrated release; use ORIGIN or HRMS in the current lifecycle.'),
    updated_at = now()
FROM institution_course_release release
WHERE release.id = assignment.course_release_id
  AND assignment.status = 'ASSIGNED'
  AND (
    assignment.assignment_source NOT IN ('ORIGIN','HRMS')
    OR release.legacy_migration = true
    OR release.release_state = 'LEGACY_MIGRATED'
  );

COMMENT ON COLUMN institution_course_release.obsolete_at IS
  'Set when a release is retired from the active pipeline; content graph and learner history remain preserved.';
COMMENT ON COLUMN institution_course_release.obsolete_reason IS
  'Reason this historical release cannot participate in the active course pipeline.';
COMMENT ON COLUMN institution_course_assignment.obsolete_at IS
  'Set on retired legacy assignment rows; only ORIGIN or HRMS assignments are usable.';
COMMENT ON COLUMN institution_course_assignment.obsolete_reason IS
  'Reason this historical assignment cannot participate in the active course pipeline.';
COMMENT ON COLUMN institution_marketplace_offer.obsolete_at IS
  'Set when an offer is retired from live listing; orders and invoice snapshots remain bound to the exact offer.';
COMMENT ON COLUMN institution_marketplace_offer.obsolete_reason IS
  'Reason this offer cannot be used for a new sale or publication.';
COMMENT ON COLUMN course_marketplace_offer.obsolete_at IS
  'All rows in this superseded global catalogue are obsolete; live listings use institution_marketplace_offer.';
COMMENT ON COLUMN course_marketplace_offer.obsolete_reason IS
  'Reason this row from the superseded global catalogue was retired.';

COMMENT ON TABLE institution_course_identity IS
  'ACTIVE: permanent logical course identity. Its public_id is the stable public reference; content versions live in curriculum_program.';
COMMENT ON TABLE curriculum_program IS
  'ACTIVE: one immutable course content graph/version. Use course_identity_id + content_version_no for the logical course/version relationship.';
COMMENT ON TABLE institution_course_release IS
  'ACTIVE: source-owned release workflow for one exact course content graph. A release is usable for Marketplace only after current quality/origin approval and SHAREABLE state.';
COMMENT ON TABLE institution_course_assignment IS
  'ACTIVE: destination assignment in the current lifecycle. ORIGIN and HRMS are the only usable sources; older source values are preserved only on revoked historical rows.';
COMMENT ON TABLE institution_marketplace_offer IS
  'ACTIVE: one institution-specific listing revision bound to an exact assignment, release and content graph. Use qe_current_course_offer_revision and qe_marketplace_offer_publicly_sellable as the database eligibility contract.';
COMMENT ON TABLE course_marketplace_offer IS
  'OBSOLETE FOR LIVE COURSE LIFECYCLE: old global Marketplace offer table. Do not create, edit, or publish offers here. Retained temporarily for historical reconciliation; the sole active offer table is institution_marketplace_offer.';
COMMENT ON COLUMN institution_marketplace_offer.legacy_migration IS
  'Historical migration marker. Rows with legacy_migration=true must be ARCHIVED and cannot be published or used by the current course lifecycle.';
COMMENT ON TABLE student_certificate IS
  'ACTIVE evidence record: institution-scoped learner achievement tied to a stable course identity and exact curriculum_program content version. Artifact bytes and achievement snapshot remain immutable.';

-- Database-level publication guard: HTTP-layer checks are necessary but not sufficient.
-- Only the newest eligible source release can transition into a live listing.
CREATE OR REPLACE FUNCTION qe_validate_published_course_offer_write()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
  IF NEW.status <> 'PUBLISHED' THEN
    RETURN NEW;
  END IF;

  IF NEW.legacy_migration
     OR NEW.hrms_approved_by_staff_id IS NULL
     OR NEW.hrms_approved_at IS NULL
     OR NEW.hrms_published_by_staff_id IS NULL
     OR NEW.hrms_published_at IS NULL THEN
    RAISE EXCEPTION
      'HRMS_PUBLICATION_REQUIRED: only an HRMS-approved and HRMS-published current offer may be PUBLISHED.'
      USING ERRCODE = '23514';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM institution_course_assignment assignment
    JOIN institution_course_release release
      ON release.id = NEW.course_release_id
     AND release.program_id = NEW.course_id
     AND release.id = assignment.course_release_id
    JOIN curriculum_program program
      ON program.id = NEW.course_id
     AND program.status = 'ACTIVE'
     AND program.code <> 'GENERAL'
    JOIN institution_course_identity identity
      ON identity.id = program.course_identity_id
     AND identity.status = 'ACTIVE'
     AND identity.source_institution_id = release.source_institution_id
     AND identity.course_type_code = program.course_type_code
    JOIN institution_tenant destination
      ON destination.id = NEW.institution_id
     AND destination.status = 'ACTIVE'
    WHERE assignment.id = NEW.assignment_id
      AND assignment.institution_id = NEW.institution_id
      AND assignment.status = 'ASSIGNED'
      AND assignment.assignment_source IN ('ORIGIN','HRMS')
      AND release.release_state = 'SHAREABLE'
      AND release.legacy_migration = false
      AND (
        assignment.assignment_source <> 'ORIGIN'
        OR assignment.institution_id = release.source_institution_id
      )
      AND course_marketplace_content_type_ready(program.id)
      AND course_release_fingerprint_current(
        release.program_id, release.content_fingerprint::text, release.updated_at
      )
      AND NOT EXISTS (
        SELECT 1
        FROM institution_course_assignment newer_assignment
        JOIN institution_course_release newer_release
          ON newer_release.id = newer_assignment.course_release_id
        JOIN curriculum_program newer_program
          ON newer_program.id = newer_release.program_id
        JOIN institution_course_identity newer_identity
          ON newer_identity.id = newer_program.course_identity_id
        WHERE newer_assignment.institution_id = NEW.institution_id
          AND newer_assignment.status = 'ASSIGNED'
          AND newer_assignment.assignment_source IN ('ORIGIN','HRMS')
          AND (
            newer_assignment.assignment_source <> 'ORIGIN'
            OR newer_assignment.institution_id = newer_release.source_institution_id
          )
          AND newer_release.release_state = 'SHAREABLE'
          AND newer_release.legacy_migration = false
          AND newer_release.source_institution_id = newer_identity.source_institution_id
          AND newer_identity.id = identity.id
          AND newer_identity.status = 'ACTIVE'
          AND newer_program.status = 'ACTIVE'
          AND newer_program.code <> 'GENERAL'
          AND newer_program.course_type_code = newer_identity.course_type_code
          AND course_marketplace_content_type_ready(newer_program.id)
          AND course_release_fingerprint_current(
            newer_release.program_id,
            newer_release.content_fingerprint::text,
            newer_release.updated_at
          )
          AND (
            newer_program.content_version_no > program.content_version_no
            OR (
              newer_program.content_version_no = program.content_version_no
              AND (
                newer_release.release_version > release.release_version
                OR (
                  newer_release.release_version = release.release_version
                  AND newer_release.id > release.id
                )
              )
            )
          )
      )
  ) THEN
    RAISE EXCEPTION
      'CURRENT_COURSE_RELEASE_REQUIRED: the offer must point to the newest active, shareable, fingerprint-current release for this institution.'
      USING ERRCODE = '23514';
  END IF;

  RETURN NEW;
END
$function$;

DROP TRIGGER IF EXISTS trg_qe_validate_published_course_offer ON institution_marketplace_offer;
CREATE TRIGGER trg_qe_validate_published_course_offer
  BEFORE INSERT OR UPDATE OF status,assignment_id,course_release_id,course_id,institution_id,
    legacy_migration,hrms_approved_by_staff_id,hrms_approved_at,
    hrms_published_by_staff_id,hrms_published_at
  ON institution_marketplace_offer
  FOR EACH ROW EXECUTE FUNCTION qe_validate_published_course_offer_write();

COMMENT ON FUNCTION qe_validate_published_course_offer_write() IS
  'Database-enforced publication gate: active tenant, active identity, eligible assignment, newest shareable nonlegacy release, course-type readiness, current fingerprint and HRMS approval/publication attribution.';

-- Mark old per-student free grants obsolete. Preserve the records, but no current
-- authorization path may use or create them. Purchase/enrollment/entitlement records retain
-- their own independent evidence and are not inferred from this grant table.
ALTER TABLE student_course_access_grant
  ADD COLUMN IF NOT EXISTS obsolete_at timestamptz,
  ADD COLUMN IF NOT EXISTS obsolete_reason text;

UPDATE student_course_access_grant
SET obsolete_at = coalesce(obsolete_at, now()),
    obsolete_reason = coalesce(obsolete_reason,
      'OBSOLETE: prior global-class free access grant; access must now come from current institution enrollment, entitlement, or verified purchase.')
WHERE obsolete_at IS NULL OR obsolete_reason IS NULL;

COMMENT ON TABLE student_course_access_grant IS
  'OBSOLETE FOR AUTHORIZATION: historical Class 6-8 free-access grants are retained for audit but cannot grant new learning, tutor, certificate, or commerce access. Use the current institution membership + enrollment/entitlement or verified purchase pipeline.';

CREATE OR REPLACE FUNCTION qe_reject_obsolete_student_course_access_grant_write()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
  RAISE EXCEPTION
    'OBSOLETE_STUDENT_COURSE_ACCESS_GRANT: this legacy grant table is historical only; use current institution enrollment, entitlement, or verified purchase.'
    USING ERRCODE = '55000';
END
$function$;

DROP TRIGGER IF EXISTS trg_qe_obsolete_student_course_access_grant_write ON student_course_access_grant;
CREATE TRIGGER trg_qe_obsolete_student_course_access_grant_write
  BEFORE INSERT OR UPDATE ON student_course_access_grant
  FOR EACH ROW EXECUTE FUNCTION qe_reject_obsolete_student_course_access_grant_write();

-- Retire every row in the superseded global catalogue table before installing its write fence.
-- The later dependency cleanup may drop it only after all financial/audit references are reconciled.
UPDATE course_marketplace_offer
SET status = 'ARCHIVED',
    obsolete_at = coalesce(obsolete_at, now()),
    obsolete_reason = coalesce(obsolete_reason,
      'OBSOLETE: global Marketplace catalogue replaced by HRMS-controlled institution_marketplace_offer. Historical references are retained for reconciliation.'),
    updated_at = now();

COMMENT ON COLUMN course_marketplace_offer.status IS
  'OBSOLETE TABLE: all global-catalogue offers are archived. Live offers belong only to institution_marketplace_offer and are controlled by HRMS.';

-- Database-level fence for the obsolete global Marketplace offer table. DELETE remains available
-- for a separately reviewed retirement phase; active application code must use the institution table.
CREATE OR REPLACE FUNCTION qe_reject_obsolete_global_marketplace_offer_write()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
  RAISE EXCEPTION
    'OBSOLETE_GLOBAL_MARKETPLACE: course_marketplace_offer is retired; use the HRMS-controlled institution offer lifecycle.'
    USING ERRCODE = '55000';
END
$function$;

DROP TRIGGER IF EXISTS trg_qe_obsolete_global_marketplace_offer_write
  ON course_marketplace_offer;
CREATE TRIGGER trg_qe_obsolete_global_marketplace_offer_write
  BEFORE INSERT OR UPDATE ON course_marketplace_offer
  FOR EACH ROW EXECUTE FUNCTION qe_reject_obsolete_global_marketplace_offer_write();

INSERT INTO app_metadata(key, value)
VALUES (
  'course-lifecycle-active-contract-v208',
  'ACTIVE: institution_course_identity -> curriculum_program version -> institution_course_release -> institution_course_assignment(ORIGIN|HRMS) -> institution_marketplace_offer -> course_payment_order/entitlement -> learning/assessment -> student_certificate. Obsolete: global course_marketplace_offer; legacy assignment sources; migrated offers/releases. Historical rows are archived/revoked, not deleted.'
)
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value;
