package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CourseExpiryPolicyIntegrationTest {
  @Autowired private JdbcTemplate jdbc;
  @Autowired private InstitutionMembershipService institutionMembershipService;

  @Test
  @Transactional
  void sharedPolicyUsesIstCalendarArithmeticAndSnapshotsOnlyNewEnrollments() {
    long institutionId = jdbc.queryForObject("""
      select id from institution_tenant where tenant_code='QUANTA-EDGE' and status='ACTIVE'
      """, Long.class);
    long[] course = jdbc.queryForObject("""
      select identity.id,program.id
      from institution_course_identity identity
      join curriculum_program program on program.course_identity_id=identity.id
        and program.code='CLASS_6' and program.status='ACTIVE'
      where identity.stable_course_key='CLASS_6' and identity.status='ACTIVE'
      order by program.content_version_no desc,program.id desc limit 1
      """, (rs, row) -> new long[]{rs.getLong(1), rs.getLong(2)});
    assertNotNull(course);

    long identityId = course[0];
    long programId = course[1];
    Long initialVersion = jdbc.queryForObject("""
      insert into course_expiry_policy(
        course_identity_id,policy_mode,calendar_years,calendar_months,calendar_days,fixed_days,
        policy_version,change_note
      ) values(?,'CALENDAR_DURATION',1,6,2,null,1,'Integration test calendar duration')
      on conflict(course_identity_id) do update set
        policy_mode='CALENDAR_DURATION',calendar_years=1,calendar_months=6,calendar_days=2,
        fixed_days=null,policy_version=course_expiry_policy.policy_version+1,
        change_note='Integration test calendar duration',updated_at=now()
      returning policy_version
      """, Long.class, identityId);

    long firstStudent = createStudent("Course expiry policy first fixture", institutionId);
    jdbc.update("""
      insert into student_program_enrollment(
        student_id,program_id,institution_id,status,enrolled_at,ended_at,created_at,updated_at
      ) values(?,?,?,'ACTIVE',timestamptz '2026-10-27 10:00:00+05:30',null,now(),now())
      """, firstStudent, programId, institutionId);

    String firstExpiry = expiryLocal(firstStudent, institutionId, programId);
    assertEquals("2028-04-29 10:00:00", firstExpiry,
        "1 year, 6 months and 2 days from 27 October 2026 must expire on 29 April 2028 in IST.");
    assertEquals(initialVersion, jdbc.queryForObject("""
      select course_expiry_policy_version_snapshot from student_program_enrollment
      where student_id=? and institution_id=? and program_id=?
      """, Long.class, firstStudent, institutionId, programId));
    assertEquals(Boolean.TRUE, jdbc.queryForObject(
        "select qe_course_enrollment_access_allowed(?,?,?)", Boolean.class,
        firstStudent, institutionId, programId));
    String originalEnrollmentDate = jdbc.queryForObject("""
      select to_char(enrolled_at at time zone 'Asia/Kolkata','YYYY-MM-DD HH24:MI:SS')
      from student_program_enrollment
      where student_id=? and institution_id=? and program_id=?
      """, String.class, firstStudent, institutionId, programId);
    jdbc.update("""
      update student_program_enrollment set status='PAUSED',updated_at=now()
      where student_id=? and institution_id=? and program_id=?
      """, firstStudent, institutionId, programId);
    jdbc.update("""
      update student_program_enrollment
      set status='ACTIVE',enrolled_at=timestamptz '2027-01-01 00:00:00+05:30',updated_at=now()
      where student_id=? and institution_id=? and program_id=?
      """, firstStudent, institutionId, programId);
    assertEquals(firstExpiry, expiryLocal(firstStudent, institutionId, programId),
        "Resuming a paused enrolment must preserve the original computed expiry.");
    assertEquals(originalEnrollmentDate, jdbc.queryForObject("""
      select to_char(enrolled_at at time zone 'Asia/Kolkata','YYYY-MM-DD HH24:MI:SS')
      from student_program_enrollment
      where student_id=? and institution_id=? and program_id=?
      """, String.class, firstStudent, institutionId, programId),
        "Resuming must not rewrite the original enrolment date.");

    jdbc.update("""
      update student_program_enrollment
      set course_access_blocked=true,course_access_blocked_at=now()
      where student_id=? and institution_id=? and program_id=?
      """, firstStudent, institutionId, programId);
    assertEquals(Boolean.FALSE, jdbc.queryForObject(
        "select qe_course_enrollment_access_allowed(?,?,?)", Boolean.class,
        firstStudent, institutionId, programId));
    jdbc.update("""
      update student_program_enrollment
      set course_access_blocked=false,course_access_blocked_at=null,
          course_access_blocked_by_staff_id=null,course_expires_at=timestamptz '2026-10-27 10:00:00+05:30'
      where student_id=? and institution_id=? and program_id=?
      """, firstStudent, institutionId, programId);
    assertEquals(Boolean.FALSE, jdbc.queryForObject(
        "select qe_course_enrollment_access_allowed(?,?,?)", Boolean.class,
        firstStudent, institutionId, programId));
    jdbc.update("""
      update student_program_enrollment
      set course_expires_at=timestamptz '2028-04-29 10:00:00+05:30'
      where student_id=? and institution_id=? and program_id=?
      """, firstStudent, institutionId, programId);
    assertEquals(Boolean.TRUE, jdbc.queryForObject(
        "select qe_course_enrollment_access_allowed(?,?,?)", Boolean.class,
        firstStudent, institutionId, programId));

    jdbc.update("""
      update course_expiry_policy
      set policy_mode='FIXED_DAYS',calendar_years=null,calendar_months=null,calendar_days=null,
          fixed_days=180,policy_version=policy_version+1,
          change_note='Integration test fixed duration',updated_at=now()
      where course_identity_id=?
      """, identityId);
    long updatedVersion = jdbc.queryForObject("""
      select policy_version from course_expiry_policy where course_identity_id=?
      """, Long.class, identityId);

    assertEquals("2028-04-29 10:00:00", expiryLocal(firstStudent, institutionId, programId),
        "Changing the shared policy must not silently modify an existing enrolment.");
    assertEquals(initialVersion, jdbc.queryForObject("""
      select course_expiry_policy_version_snapshot from student_program_enrollment
      where student_id=? and institution_id=? and program_id=?
      """, Long.class, firstStudent, institutionId, programId));

    long secondStudent = createStudent("Course expiry policy second fixture", institutionId);
    jdbc.update("""
      insert into student_program_enrollment(
        student_id,program_id,institution_id,status,enrolled_at,ended_at,created_at,updated_at
      ) values(?,?,?,'ACTIVE',timestamptz '2026-10-27 10:00:00+05:30',null,now(),now())
      """, secondStudent, programId, institutionId);

    assertEquals("2027-04-25 10:00:00", expiryLocal(secondStudent, institutionId, programId),
        "A new enrolment must snapshot the current fixed 180-day policy.");
    assertEquals(updatedVersion, jdbc.queryForObject("""
      select course_expiry_policy_version_snapshot from student_program_enrollment
      where student_id=? and institution_id=? and program_id=?
      """, Long.class, secondStudent, institutionId, programId));
  }

  @Test
  @Transactional
  void newlyRegisteredCourseCannotActivateLearnerBeforeExpiryPolicyIsConfigured() {
    long institutionId = jdbc.queryForObject("""
      select id from institution_tenant where tenant_code='QUANTA-EDGE' and status='ACTIVE'
      """, Long.class);
    long[] course = jdbc.queryForObject("""
      select identity.id,program.id
      from institution_course_identity identity
      join curriculum_program program on program.course_identity_id=identity.id
        and program.code='CLASS_6' and program.status='ACTIVE'
      where identity.stable_course_key='CLASS_6' and identity.status='ACTIVE'
      order by program.content_version_no desc,program.id desc limit 1
      """, (rs, row) -> new long[]{rs.getLong(1), rs.getLong(2)});
    long sourceIdentityId=course[0];
    long sourceProgramId=course[1];
    assertFalse(Boolean.TRUE.equals(jdbc.queryForObject("""
      select course_expiry_policy_required from institution_course_identity where id=?
      """,Boolean.class,sourceIdentityId)),
      "Existing identities must be grandfathered by the migration.");

    // Create an isolated, newly registered logical course. Never mutate a production course
    // identity or bypass the immutable released-course guard just to set up this test.
    long identityId=jdbc.queryForObject("""
      insert into institution_course_identity(
        source_institution_id,stable_course_key,display_name,course_type_code,course_expiry_policy_required
      )
      select source_institution_id,
        'qe_expiry_test_'||replace(gen_random_uuid()::text,'-',''),
        'Expiry-required integration fixture',course_type_code,true
      from institution_course_identity where id=?
      returning id
      """,Long.class,sourceIdentityId);
    long programId=jdbc.queryForObject("""
      insert into curriculum_program(
        class_id,code,display_name,stream_code,curriculum_version,status,notes,public_id_prefix,
        course_type_code,course_identity_id,content_version_no
      )
      select class_id,'QE_EXP_'||replace(gen_random_uuid()::text,'-',''),
        'Expiry-required integration fixture',stream_code,'EXPIRY_TEST','ACTIVE',
        'Isolated course expiry policy integration fixture','INS',course_type_code,?,1
      from curriculum_program where id=?
      returning id
      """,Long.class,identityId,sourceProgramId);
    long studentId=createStudent("Course expiry required-policy fixture",institutionId);

    DataAccessException error=assertThrows(DataAccessException.class, () -> jdbc.update("""
      insert into student_program_enrollment(
        student_id,program_id,institution_id,status,enrolled_at,ended_at,created_at,updated_at
      ) values(?,?,?,'ACTIVE',now(),null,now(),now())
      """,studentId,programId,institutionId),
      "The database must reject an active enrolment until HRMS configures the required shared policy.");
    assertTrue(String.valueOf(error.getMostSpecificCause().getMessage())
        .contains("COURSE_EXPIRY_POLICY_REQUIRED"),
        "The failure must come from the expiry-policy guard, not an unrelated constraint.");
  }

  private long createStudent(String displayName, long institutionId) {
    long userId = jdbc.queryForObject("""
      insert into user_account(public_id,mobile_e164,display_name,role,mobile_verified_at)
      values(gen_random_uuid(),'+919'||lpad((floor(random()*1000000000))::bigint::text,9,'0'),
        ?,'LEARNER',now())
      returning id
      """, Long.class, displayName);
    long studentId = jdbc.queryForObject("""
      insert into student(public_id,display_name,class_code,board,language,environment,active,user_account_id)
      values(gen_random_uuid(),?,'6','Bihar Board','hi','PRODUCTION',true,?)
      returning id
      """, Long.class, displayName, userId);
    institutionMembershipService.ensureStudentMembership(studentId,institutionId,"PHONE_SIGNUP",null);
    return studentId;
  }

  private String expiryLocal(long studentId, long institutionId, long programId) {
    return jdbc.queryForObject("""
      select to_char(course_expires_at at time zone 'Asia/Kolkata','YYYY-MM-DD HH24:MI:SS')
      from student_program_enrollment
      where student_id=? and institution_id=? and program_id=?
      """, String.class, studentId, institutionId, programId);
  }
}
