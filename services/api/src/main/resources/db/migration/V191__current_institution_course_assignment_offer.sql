-- V191: one canonical definition of the current institution-assigned course release.
-- Historical assignments/offers remain stored; this function only identifies which release
-- is current for operational configuration and discovery.
CREATE OR REPLACE FUNCTION qe_is_current_institution_assignment(p_assignment_id bigint)
RETURNS boolean
LANGUAGE sql
STABLE
AS $$
  SELECT EXISTS (
    SELECT 1
    FROM institution_course_assignment target_assignment
    JOIN institution_course_release target_release
      ON target_release.id=target_assignment.course_release_id
    JOIN curriculum_program target_program
      ON target_program.id=target_release.program_id
     AND target_program.status='ACTIVE'
     AND target_program.code<>'GENERAL'
    WHERE target_assignment.id=p_assignment_id
      AND target_assignment.status='ASSIGNED'
      AND (target_assignment.assignment_source<>'ORIGIN'
        OR target_release.release_state='SHAREABLE')
      AND NOT EXISTS (
        SELECT 1
        FROM institution_course_assignment candidate_assignment
        JOIN institution_course_release candidate_release
          ON candidate_release.id=candidate_assignment.course_release_id
        JOIN curriculum_program candidate_program
          ON candidate_program.id=candidate_release.program_id
         AND candidate_program.status='ACTIVE'
         AND candidate_program.code<>'GENERAL'
        WHERE candidate_assignment.institution_id=target_assignment.institution_id
          AND candidate_assignment.status='ASSIGNED'
          AND (candidate_assignment.assignment_source<>'ORIGIN'
            OR candidate_release.release_state='SHAREABLE')
          AND COALESCE(candidate_program.course_identity_id,-candidate_program.id)
              =COALESCE(target_program.course_identity_id,-target_program.id)
          AND (
            candidate_program.content_version_no>target_program.content_version_no
            OR (
              candidate_program.content_version_no=target_program.content_version_no
              AND (
                candidate_release.release_version>target_release.release_version
                OR (
                  candidate_release.release_version=target_release.release_version
                  AND (
                    candidate_release.id>target_release.id
                    OR (
                      candidate_release.id=target_release.id
                      AND (
                        candidate_assignment.assigned_at>target_assignment.assigned_at
                        OR (
                          candidate_assignment.assigned_at=target_assignment.assigned_at
                          AND candidate_assignment.id>target_assignment.id
                        )
                      )
                    )
                  )
                )
              )
            )
          )
      )
  )
$$;

COMMENT ON FUNCTION qe_is_current_institution_assignment(bigint) IS
  'True only for the highest-ranked active assignment for a logical course identity within one institution, using the same content-version/release/assignment ordering as HRMS launch stepper. It never changes history.';

CREATE OR REPLACE FUNCTION qe_is_current_institution_offer(p_offer_id bigint)
RETURNS boolean
LANGUAGE sql
STABLE
AS $$
  SELECT EXISTS (
    SELECT 1
    FROM institution_marketplace_offer offer
    JOIN institution_course_assignment assignment
      ON assignment.id=offer.assignment_id
     AND assignment.institution_id=offer.institution_id
     AND assignment.course_release_id=offer.course_release_id
     AND assignment.status='ASSIGNED'
    JOIN institution_course_release release
      ON release.id=offer.course_release_id
     AND release.program_id=offer.course_id
    JOIN curriculum_program program
      ON program.id=offer.course_id
     AND program.id=release.program_id
     AND program.status='ACTIVE'
     AND program.code<>'GENERAL'
    WHERE offer.id=p_offer_id
      AND offer.status<>'ARCHIVED'
      AND release.release_state='SHAREABLE'
      AND qe_is_current_institution_assignment(assignment.id)
  )
$$;

COMMENT ON FUNCTION qe_is_current_institution_offer(bigint) IS
  'True only when the non-archived offer is bound to the currently assigned, shareable release for the logical course identity. Historical financial references remain intact.';

INSERT INTO app_metadata(key,value)
VALUES ('schema','current-institution-course-assignment-offer-v191')
ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value;
