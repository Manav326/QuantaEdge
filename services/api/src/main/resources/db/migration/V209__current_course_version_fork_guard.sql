-- V209: close direct-SQL version-fork bypasses using the current source-owned pipeline.
-- V208 is already merged and must remain immutable for Flyway checksum validation.

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
