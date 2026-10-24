package com.quantaedge.api;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Migrates current access to a newly published course version for eligible learners of that
 * institution. Shared by both trusted automatic publication and the regular HRMS publish step.
 *
 * Historical purchase, attempt, progress and certificate rows are never updated by this service.
 */
@Service
public class CourseVersionLearnerMigrationService {
  private final JdbcTemplate jdbc;

  public CourseVersionLearnerMigrationService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional
  public int grantExistingLearnersToVersion(
      long institutionId, long courseIdentityId, int contentVersionNo, long newProgramId) {
    if (institutionId <= 0 || courseIdentityId <= 0 || contentVersionNo <= 1 || newProgramId <= 0) {
      return 0;
    }
    List<Long> learners = jdbc.queryForList("""
        with old_courses as (
          select p.id
          from curriculum_program p
          where p.course_identity_id=? and p.content_version_no<?
            and exists(
              select 1 from institution_marketplace_offer o
              join institution_course_assignment a
                on a.id=o.assignment_id and a.institution_id=o.institution_id and a.status='ASSIGNED'
              join institution_course_release r on r.id=o.course_release_id
                and r.program_id=o.course_id and r.release_state='SHAREABLE'
                and r.content_fingerprint=course_content_fingerprint(r.program_id)
              where o.institution_id=? and o.course_id=p.id and o.status='PUBLISHED'
                and o.hrms_published_by_staff_id is not null
            )
        ), eligible_students(student_id) as (
          select e.student_id from student_program_enrollment e
          join old_courses old on old.id=e.program_id
          where e.institution_id=? and e.status in ('ACTIVE','PAUSED')
          union
          select purchase.student_id from course_purchase purchase
          join old_courses old on old.id=purchase.course_id
          where purchase.institution_id=? and purchase.student_id is not null
          union
          select entitlement.holder_student_id from course_entitlement entitlement
          join old_courses old on old.id=entitlement.course_id
          where entitlement.institution_id=? and entitlement.holder_student_id is not null
          union
          select learner.id from course_entitlement entitlement
          join student learner on learner.user_account_id=entitlement.holder_user_id and learner.active=true
          join old_courses old on old.id=entitlement.course_id
          where entitlement.institution_id=? and entitlement.holder_user_id is not null
        )
        select distinct learner.id
        from eligible_students eligible
        join student learner on learner.id=eligible.student_id and learner.active=true
        join institution_membership membership on membership.institution_id=?
          and membership.student_id=learner.id and membership.status='ACTIVE'
        order by learner.id
        """, Long.class, courseIdentityId, contentVersionNo, institutionId, institutionId,
        institutionId, institutionId, institutionId, institutionId);
    if (learners.isEmpty()) {
      return 0;
    }

    List<Long> subjects = jdbc.queryForList("""
        select subject_id from curriculum_program_subject
        where program_id=? order by sort_order,subject_id
        """, Long.class, newProgramId);
    int grantedLearners = 0;
    for (Long studentId : learners) {
      int inserted = jdbc.update("""
          insert into student_program_enrollment(
            student_id,program_id,institution_id,status,enrolled_at,ended_at,created_at,updated_at
          ) values(?,?,?,'ACTIVE',now(),null,now(),now())
          on conflict(institution_id,student_id,program_id) do nothing
          """, studentId, newProgramId, institutionId);
      if (inserted > 0) {
        grantedLearners++;
      }
      for (Long subjectId : subjects) {
        jdbc.update("""
            insert into student_track_enrollment(
              student_id,subject_id,institution_id,status,subscribed_at,created_at,updated_at
            ) values(?,?,?,'ACTIVE',now(),now(),now())
            on conflict(institution_id,student_id,subject_id) do nothing
            """, studentId, subjectId, institutionId);
      }
    }
    return grantedLearners;
  }
}
