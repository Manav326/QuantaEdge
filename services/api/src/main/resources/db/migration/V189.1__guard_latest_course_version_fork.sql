-- V189.1: a new course version may only supersede the current latest source version.
-- This closes the stale-fork edge case where a caller could fork v1 into v3 after v2 was released.

CREATE OR REPLACE FUNCTION qe_guard_latest_course_version_fork()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  v_latest_program_id bigint;
  v_latest_version integer;
BEGIN
  IF NEW.supersedes_program_id IS NULL THEN
    RETURN NEW;
  END IF;

  IF NEW.course_identity_id IS NULL THEN
    RAISE EXCEPTION 'COURSE_VERSION_SOURCE_STALE: a version fork must retain a source course identity'
      USING ERRCODE='23514';
  END IF;

  SELECT p.id,p.content_version_no
  INTO v_latest_program_id,v_latest_version
  FROM curriculum_program p
  WHERE p.course_identity_id=NEW.course_identity_id
  ORDER BY p.content_version_no DESC NULLS LAST,p.id DESC
  LIMIT 1
  FOR UPDATE;

  IF NOT FOUND OR v_latest_program_id IS DISTINCT FROM NEW.supersedes_program_id THEN
    RAISE EXCEPTION 'COURSE_VERSION_SOURCE_STALE: fork from the latest course version, not a superseded version'
      USING ERRCODE='23514';
  END IF;

  IF NEW.content_version_no IS DISTINCT FROM (v_latest_version + 1) THEN
    RAISE EXCEPTION 'COURSE_VERSION_SEQUENCE_INVALID: the new content version must increment the latest version by one'
      USING ERRCODE='23514';
  END IF;

  RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS trg_guard_latest_course_version_fork ON curriculum_program;
CREATE TRIGGER trg_guard_latest_course_version_fork
BEFORE INSERT ON curriculum_program
FOR EACH ROW
WHEN (NEW.supersedes_program_id IS NOT NULL)
EXECUTE FUNCTION qe_guard_latest_course_version_fork();

COMMENT ON FUNCTION qe_guard_latest_course_version_fork() IS
  'Prevents source course version forks from superseded graphs; every new version must supersede the latest course identity version and increment its version number by one.';

INSERT INTO app_metadata(key,value)
VALUES ('schema','course-version-latest-source-guard-v189-1')
ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value;
