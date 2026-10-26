-- V209: close direct-SQL version-fork bypasses using the current source-owned pipeline.
-- V208 is already merged and must remain immutable for Flyway checksum validation.

-- A WITHDRAWN release is not live/sellable, but its immutable graph remains a valid
-- source for the next version. V208 marked these rows obsolete for discovery; clear only that
-- specific retirement reason when the source identity is still valid. Legacy/orphan rows stay obsolete.
UPDATE institution_course_release release
SET obsolete_at = NULL,
    obsolete_reason = NULL,
    updated_at = now()
FROM curriculum_program program
JOIN institution_course_identity identity
  ON identity.id = program.course_identity_id
 AND identity.status = 'ACTIVE'
 AND identity.course_type_code = program.course_type_code
WHERE program.id = release.program_id
  AND identity.source_institution_id = release.source_institution_id
  AND release.release_state = 'WITHDRAWN'
  AND release.legacy_migration = false
  AND release.obsolete_reason =
    'OBSOLETE: source institution withdrew this release; relisting requires a new source release.';

-- Versioning has one source: only a current nonlegacy released graph may be forked.
-- The version service and UI validate this too; this trigger closes the direct-SQL function path.
CREATE OR REPLACE FUNCTION qe_validate_current_course_version_fork_source()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
  IF NEW.event_type = 'DRAFT_VERSION_CREATED' AND NOT EXISTS (
    SELECT 1
    FROM curriculum_program source_program
    JOIN institution_course_identity identity
      ON identity.id = source_program.course_identity_id
     AND identity.status = 'ACTIVE'
     AND identity.source_institution_id = (
       SELECT source_institution_id
       FROM institution_course_release
       WHERE program_id = source_program.id
         AND legacy_migration = false
         AND obsolete_at IS NULL
         AND release_state IN ('SHAREABLE','WITHDRAWN')
       ORDER BY release_version DESC, id DESC
       LIMIT 1
     )
     AND identity.course_type_code = source_program.course_type_code
    WHERE source_program.id = NEW.source_program_id
      AND source_program.course_identity_id = NEW.course_identity_id
      AND source_program.status = 'ACTIVE'
      AND source_program.code <> 'GENERAL'
      AND NEW.content_version_no = (
        SELECT version_program.content_version_no
        FROM curriculum_program version_program
        WHERE version_program.id = NEW.version_program_id
          AND version_program.course_identity_id = NEW.course_identity_id
      )
      AND EXISTS (
        SELECT 1
        FROM institution_course_release source_release
        WHERE source_release.program_id = source_program.id
          AND source_release.source_institution_id = identity.source_institution_id
          AND source_release.legacy_migration = false
          AND source_release.obsolete_at IS NULL
          AND source_release.release_state IN ('SHAREABLE','WITHDRAWN')
      )
  ) THEN
    RAISE EXCEPTION
      'CURRENT_COURSE_VERSION_SOURCE_REQUIRED: a new version must be forked from an active source-owned course identity with a current, nonlegacy SHAREABLE or WITHDRAWN release.'
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END
$function$;

DROP TRIGGER IF EXISTS trg_qe_current_course_version_fork_source
  ON institution_course_version_event;
CREATE TRIGGER trg_qe_current_course_version_fork_source
  BEFORE INSERT ON institution_course_version_event
  FOR EACH ROW EXECUTE FUNCTION qe_validate_current_course_version_fork_source();

COMMENT ON FUNCTION qe_validate_current_course_version_fork_source() IS
  'Stops direct SQL callers and obsolete API paths from creating a new draft version from a legacy-migrated or obsolete course release. Existing event and learner history remains intact.';

-- Reuse V208's insert trigger, but require a released version. SHAREABLE courses are live
-- or have previously been live; WITHDRAWN releases remain valid for existing learner history
-- and certificate completion but do not re-enter Marketplace publication.
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
      AND EXISTS (
        SELECT 1
        FROM institution_course_release source_release
        WHERE source_release.program_id = program.id
          AND source_release.source_institution_id = identity.source_institution_id
          AND source_release.legacy_migration = false
          AND source_release.release_state IN ('SHAREABLE','WITHDRAWN')
          AND source_release.obsolete_at IS NULL
      )
  ) THEN
    RAISE EXCEPTION
      'COURSE_IDENTITY_MISMATCH: newly issued certificates require an active identity and an exact course version with a valid SHAREABLE or WITHDRAWN source release.'
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END
$function$;

COMMENT ON FUNCTION qe_require_course_identity_for_new_certificate() IS
  'A certificate is bound to a stable logical course identity plus exact content version. Draft, in-review and merely quality-approved versions cannot issue certificates; already-shared or withdrawn versions remain valid for existing learner completion.';

