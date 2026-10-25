package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ApplicationStartupSmokeTest {
  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private LearningController learningController;

  @Autowired
  private LearningMetricsService learningMetrics;

  @Autowired
  private ContentTranslationController contentTranslationController;

  @Autowired
  private AdminContentController adminContentController;

  @Autowired
  private InstitutionMembershipService institutionMembershipService;

  @Autowired
  private GuardianController guardianController;


  @Test
  @Transactional
  void signupMembershipAuditPersistsNullActorAsJsonNull() {
    Long institutionId = jdbc.queryForObject("""
      select id from institution_tenant
      where tenant_code='QUANTA-EDGE' and status='ACTIVE'
      """, Long.class);

    // Mirror the identity shape created by AuthService.verifyOtp(ACCOUNT_SIGNUP):
    // a verified LEARNER user_account linked to a newly inserted production student,
    // while the signup request's AuthContext still has a null userId for audit purposes.
    Long userId = jdbc.queryForObject("""
      insert into user_account(public_id,mobile_e164,display_name,role,mobile_verified_at)
      values(gen_random_uuid(), '+919'||lpad((floor(random()*1000000000))::bigint::text,9,'0'),
        'Signup audit null-actor regression','LEARNER',now())
      returning id
      """, Long.class);
    Long studentId = jdbc.queryForObject("""
      insert into student(public_id,display_name,class_code,board,language,environment,active,user_account_id)
      values(gen_random_uuid(),'Signup audit null-actor regression','6','Bihar Board','hi','PRODUCTION',true,?)
      returning id
      """, Long.class, userId);

    institutionMembershipService.ensureStudentMembership(
        studentId, institutionId, "PHONE_SIGNUP", null);

    assertTrue(jdbc.queryForObject("""
      select exists(select 1 from institution_membership
        where institution_id=? and student_id=? and status='ACTIVE')
      """, Boolean.class, institutionId, studentId),
      "Signup must establish the student membership for the selected institution.");
    assertTrue(jdbc.queryForObject("""
      select exists(select 1 from institution_user_membership
        where institution_id=? and user_id=? and status='ACTIVE')
      """, Boolean.class, institutionId, userId),
      "Signup must establish the linked user-account membership in the same institution.");
    assertTrue(jdbc.queryForObject("""
      select jsonb_exists(details, 'actorUserId') and details->>'actorUserId' is null
      from institution_membership_event
      where institution_id=? and student_id=? and event_type='MEMBERSHIP_CREATED'
      order by id desc limit 1
      """, Boolean.class, institutionId, studentId),
      "A null signup actor must be serialized as JSON null without failing membership creation.");
  }

  @Test
  void classSixThroughEightArePublishedOrdinaryCoursesWithMappedTracksAndGrandfatheredAccess() {
    assertEquals(3, jdbc.queryForObject("""
      select count(*) from curriculum_program p
      where p.code in ('CLASS_6','CLASS_7','CLASS_8')
        and p.status='ACTIVE' and p.curriculum_version='CURRENT'
    """, Integer.class));
    assertEquals(3, jdbc.queryForObject("""
      select count(distinct p.code) from curriculum_program p
      join curriculum_class c on c.id=p.class_id and c.code=substring(p.code,7)
      join curriculum_program_subject ps on ps.program_id=p.id and ps.class_id=c.id
      join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id
        and s.program_id is null and s.active=true
      where p.code in ('CLASS_6','CLASS_7','CLASS_8') and p.status='ACTIVE'
    """, Integer.class));
    assertEquals(0, jdbc.queryForObject("""
      select count(*) from curriculum_program p
      join course_marketplace_offer o on o.course_id=p.id
      where p.code in ('CLASS_6','CLASS_7','CLASS_8')
        and p.status='ACTIVE' and o.status='PUBLISHED'
    """, Integer.class), "The retired global Marketplace table must not expose live course offers.");
    assertEquals(3, jdbc.queryForObject("""
      select count(*) from curriculum_program p
      join course_marketplace_offer o on o.course_id=p.id
      where p.code in ('CLASS_6','CLASS_7','CLASS_8')
        and p.status='ACTIVE' and o.status='ARCHIVED'
        and o.price_paise=100000 and o.currency='INR'
    """, Integer.class), "Historical global offers must be retained as archived rows.");
    assertEquals(0, jdbc.queryForObject("""
      select count(*) from student_course_access_grant
      where obsolete_at is null or obsolete_reason is null
    """, Integer.class), "Legacy free-access grants must be marked obsolete.");
    assertTrue(jdbc.queryForObject("select to_regclass('public.student_course_access_grant') is not null", Boolean.class));
    assertEquals(0, jdbc.queryForObject("""
      select count(*) from student_program_enrollment spe
      join curriculum_program p on p.id=spe.program_id
      where spe.status in ('ACTIVE','PAUSED')
        and p.code in ('CLASS_6','CLASS_7','CLASS_8')
        and not exists(select 1 from course_purchase cp
          where cp.student_id=spe.student_id and cp.institution_id=spe.institution_id
            and cp.course_id=spe.program_id)
        and not exists(
          select 1 from institution_course_entitlement e
          where e.institution_id=spe.institution_id
            and e.program_id=spe.program_id and e.status='ACTIVE'
        )
        and not exists(
          select 1 from institution_marketplace_offer offer
          where offer.institution_id=spe.institution_id
            and offer.course_id=spe.program_id and offer.price_paise=0
            and qe_marketplace_offer_publicly_sellable(offer.id)
        )
    """, Integer.class), "Every active course enrollment must be backed by a current purchase, institution entitlement or current free Marketplace offer.");
  }

  @Test
  void studentProgramsCatalogueSqlExecutesAgainstMigratedTenantSchema() {
    Long institutionId = jdbc.queryForObject("""
      select id from institution_tenant
      where tenant_code='QUANTA-EDGE' and status='ACTIVE'
      """, Long.class);

    // A non-existent probe ID deliberately returns no learner rows while forcing PostgreSQL
    // to parse and plan the complete catalogue SQL, including every tenant-scoped access table
    // and all bound parameters. This catches the class of migrated-schema errors that returned
    // HTTP 500 without needing to borrow or mutate a real learner's identity.
    AuthContext probeStudent = new AuthContext(
        null, Long.MAX_VALUE, null, "STUDENT", "Catalogue SQL regression", "STUDENT");
    StudentProgramController controller =
        new StudentProgramController(jdbc, new AuthorizationService(jdbc));
    List<Map<String,Object>> rows = controller.programs(
        probeStudent, new InstitutionTenantContext(institutionId, "QUANTA-EDGE", "QuantaEdge"));
    assertTrue(rows != null, "The student catalogue query must return a JSON-serializable row list.");

    // Exercise the exact per-course subject SQL used by the endpoint as well. This test is
    // concerned with migrated-schema SQL compatibility, not whether the fixture has published
    // lessons or complete subject labels (those have separate curriculum-content checks).
    Long classSixCourseId = jdbc.queryForObject("""
      select id from curriculum_program
      where code='CLASS_6' and status='ACTIVE' and curriculum_version='CURRENT'
      order by id limit 1
      """, Long.class);
    List<Map<String,Object>> subjects = controller.subjectsForProgram(classSixCourseId);
    assertTrue(subjects != null, "The course-subject query must execute against the migrated schema.");
  }

  @Test
  @Transactional
  void guardianChildProgramsCatalogueSqlExecutesAgainstMigratedTenantSchema() {
    Long institutionId = jdbc.queryForObject("""
      select id from institution_tenant
      where tenant_code='QUANTA-EDGE' and status='ACTIVE'
      """, Long.class);

    // Use isolated test identities so the catalogue query can be exercised without
    // borrowing a real family's child or changing any existing enrollment data.
    Long parentUserId = jdbc.queryForObject("""
      insert into user_account(public_id,mobile_e164,display_name,role,mobile_verified_at)
      values(gen_random_uuid(), '+918'||lpad((floor(random()*1000000000))::bigint::text,9,'0'),
        'Guardian catalogue SQL regression','PARENT',now())
      returning id
      """, Long.class);
    Long learnerUserId = jdbc.queryForObject("""
      insert into user_account(public_id,mobile_e164,display_name,role,mobile_verified_at)
      values(gen_random_uuid(), '+917'||lpad((floor(random()*1000000000))::bigint::text,9,'0'),
        'Guardian catalogue SQL regression learner','LEARNER',now())
      returning id
      """, Long.class);
    Long studentId = jdbc.queryForObject("""
      insert into student(public_id,display_name,class_code,board,language,environment,active,user_account_id)
      values(gen_random_uuid(),'Guardian catalogue SQL regression learner','6','Bihar Board','hi','PRODUCTION',true,?)
      returning id
      """, Long.class, learnerUserId);

    institutionMembershipService.ensureStudentMembership(
        studentId, institutionId, "GUARDIAN_CATALOGUE_SQL_REGRESSION", null);
    jdbc.update("""
      insert into guardian_student(
        guardian_user_id,student_id,relationship,consent_status,consent_version,
        consent_source,consented_by,active,access_level)
      values (?,?,'PARENT','CONSENTED',?,'PARENT_PORTAL',?,true,'FULL')
      """, parentUserId, studentId, AuthService.GUARDIAN_CONSENT_VERSION, parentUserId);

    AuthContext parent = new AuthContext(
        parentUserId, null, null, "PARENT", "Guardian catalogue SQL regression", "PARENT");
    List<Map<String,Object>> courses = guardianController.childPrograms(
        studentId, false, parent,
        new InstitutionTenantContext(institutionId, "QUANTA-EDGE", "QuantaEdge"));
    assertTrue(courses != null,
        "The parent dashboard course catalogue SQL must execute against the migrated tenant schema.");
  }

  @Test
  @Transactional
  void bilingualSchemaExistsAndTranslationInventoryQueryExecutes() {
    assertTrue(jdbc.queryForObject("select to_regclass('public.curriculum_program') is not null", Boolean.class));
    assertTrue(jdbc.queryForObject("select to_regclass('public.lesson_translation') is not null", Boolean.class));
    assertTrue(jdbc.queryForObject("select to_regclass('public.lesson_block_translation') is not null", Boolean.class));
    assertTrue(jdbc.queryForObject("select to_regclass('public.question_translation') is not null", Boolean.class));
    assertEquals(1, jdbc.queryForObject("""
      select count(*) from flyway_schema_history
      where version='51' and success=true
      """, Integer.class));

    Long institutionId = jdbc.queryForObject("""
      select id from institution_tenant where tenant_code='QUANTA-EDGE' and status='ACTIVE'
      """, Long.class);
    Long courseId = jdbc.queryForObject("""
       select p.id
       from curriculum_program p
       join institution_course_identity identity
         on identity.id=p.course_identity_id and identity.source_institution_id=? and identity.status='ACTIVE'
       where p.code='CLASS_6' and p.status='ACTIVE' and p.curriculum_version='CURRENT'
       order by p.id limit 1
       """, Long.class, institutionId);
    // The controller requires an assignment in the current lifecycle; don't rely on migrated
    // CLASS_6 assignment/grant rows that V208 deliberately retires.
    Long releaseVersion = jdbc.queryForObject("""
      select coalesce(max(release_version),0)+1
      from institution_course_release
      where source_institution_id=? and program_id=?
      """, Long.class, institutionId, courseId);
    Long releaseId = jdbc.queryForObject("""
      insert into institution_course_release(
        source_institution_id,program_id,release_version,release_state,content_fingerprint,
        legacy_migration,created_at,updated_at)
      values(?,?,?,'DRAFT',course_content_fingerprint(?),false,now(),now())
      returning id
      """, Long.class, institutionId, courseId, releaseVersion, courseId);
    jdbc.update("""
      insert into institution_course_assignment(
        institution_id,course_release_id,status,assignment_source,assigned_at,created_at,updated_at)
      values(?,?,'ASSIGNED','ORIGIN',now(),now(),now())
      """, institutionId, releaseId);
    InstitutionTenantContext tenant = new InstitutionTenantContext(institutionId, "QUANTA-EDGE", "QuantaEdge");
    // Authorization is grant-based: seed an explicit test grant rather than relying on a former
    // global ADMIN bypass. This test transaction rolls the temporary staff and grant rows back.
    Long staffId = jdbc.queryForObject("""
      insert into staff_account(public_id,mobile_e164,display_name,role,active)
      values(gen_random_uuid(),null,'CI translation smoke reviewer','ADMIN',true)
      returning id
      """, Long.class);
    jdbc.update("""
      insert into staff_permission_grant(staff_id,workspace_key,permission_key)
      values(?,'EDUCATION_ADMIN','CONTENT_VIEW')
      on conflict(staff_id,workspace_key,permission_key) do nothing
      """, staffId);
    AuthContext admin = new AuthContext(null, null, staffId, "ADMIN", "CI admin", "EDUCATION_ADMIN");
    Map<String,Object> result = contentTranslationController.list(courseId, null, null, "ALL", "ALL", "ALL", 20, 0, admin, tenant);
    assertTrue(result.get("items") instanceof List<?>);
    assertTrue(result.get("total") instanceof Number);
    Map<String,Object> sourceInventory = contentTranslationController.inventory(courseId, "ALL", "ALL", "ALL", "ALL", 20, 0, admin, tenant);
    assertTrue(sourceInventory.get("items") instanceof List<?>);
    assertTrue(sourceInventory.get("total") instanceof Number);
    assertTrue(((Number) sourceInventory.get("total")).longValue() > 0, "Untranslated source inventory should include content mapped to the selected course.");
    @SuppressWarnings("unchecked")
    List<Map<String,Object>> filteredItems = (List<Map<String,Object>>) contentTranslationController
        .inventory(courseId, "6", "maths", "en", "ALL", 20, 0, admin, tenant).get("items");
    assertTrue(!filteredItems.isEmpty(), "Class 6 Mathematics source should be filterable for English.");
    assertTrue(filteredItems.stream().allMatch(row -> "6".equals(String.valueOf(row.get("class_code")))
        && "maths".equals(String.valueOf(row.get("subject_code")))
        && "en".equals(String.valueOf(row.get("language")))),
        "Source inventory results must match class, subject and target-language filters.");
  }

  @Test
  void practiceBankDeduplicationUsesFullQuestionContentNotPromptAlone() {
    Map<String,Object> incoming = new java.util.LinkedHashMap<>();
    incoming.put("question_type", "MCQ");
    incoming.put("prompt", "कौन-सा कथन सही है?");
    incoming.put("difficulty", "CORE");
    incoming.put("marks", 1);
    incoming.put("exam_format", "Topic practice");
    incoming.put("explanation", "पहला कथन सही है।");
    incoming.put("answer_payload", Map.of("kind", "OPTION", "value", "A"));
    incoming.put("options", List.of(
        Map.of("key", "A", "label", "रेखाखंड के दो अंत-बिंदु होते हैं", "correct", true, "sort_order", 1),
        Map.of("key", "B", "label", "रेखा के दो अंत-बिंदु होते हैं", "correct", false, "sort_order", 2)));

    Map<String,Object> stored = new java.util.LinkedHashMap<>();
    stored.put("question_type", "MCQ");
    stored.put("prompt", "  कौन-सा   कथन सही है? ");
    stored.put("difficulty", "CORE");
    stored.put("marks", 1);
    stored.put("exam_format", "Topic practice");
    stored.put("explanation", "पहला कथन सही है।");
    stored.put("answer_payload", "{\"value\":\"A\",\"kind\":\"OPTION\"}");
    stored.put("options", "[{\"key\":\"A\",\"label\":\"रेखाखंड के दो अंत-बिंदु होते हैं\",\"correct\":true,\"sort_order\":1},"
        + "{\"key\":\"B\",\"label\":\"रेखा के दो अंत-बिंदु होते हैं\",\"correct\":false,\"sort_order\":2}]");

    String incomingFingerprint = adminContentController.questionContentFingerprint(incoming);
    assertEquals(incomingFingerprint, adminContentController.questionContentFingerprint(stored));

    Map<String,Object> differentChoices = new java.util.LinkedHashMap<>(incoming);
    differentChoices.put("options", List.of(
        Map.of("key", "A", "label", "रेखा के दो अंत-बिंदु होते हैं", "correct", true, "sort_order", 1),
        Map.of("key", "B", "label", "रेखाखंड के दो अंत-बिंदु होते हैं", "correct", false, "sort_order", 2)));
    assertTrue(!incomingFingerprint.equals(adminContentController.questionContentFingerprint(differentChoices)),
        "Same prompt with different answer options must not be treated as the same question.");
  }

  @Test
  void cleanDatabaseMigratesPeopleOperationsFoundationWithoutCreatingFakeEmployees() {
    assertTrue(jdbc.queryForObject("select to_regclass('public.hr_organization') is not null", Boolean.class));
    assertTrue(jdbc.queryForObject("select to_regclass('public.hr_holiday_calendar') is not null", Boolean.class));
    assertEquals(1, jdbc.queryForObject("select count(*) from flyway_schema_history where version='63' and success=true", Integer.class));
    assertEquals(0, jdbc.queryForObject("select count(*) from hr_holiday_calendar", Integer.class));
    assertEquals(2, jdbc.queryForObject("select count(*) from staff_permission_catalog where permission_key in ('HR_HOLIDAY_VIEW','HR_HOLIDAY_MANAGE')", Integer.class));
    assertTrue(jdbc.queryForObject("select exists(select 1 from information_schema.columns where table_schema='public' and table_name='hr_organization' and column_name='working_days_per_week')", Boolean.class));
    assertTrue(jdbc.queryForObject("select working_days_per_week=5 from hr_organization where name='QuantaEdge'", Boolean.class));
    assertEquals(1, jdbc.queryForObject("select count(*) from flyway_schema_history where version='62' and success=true", Integer.class));
    assertTrue(jdbc.queryForObject("select to_regclass('public.hr_department') is not null", Boolean.class));
    assertTrue(jdbc.queryForObject("select to_regclass('public.hr_designation') is not null", Boolean.class));
    assertTrue(jdbc.queryForObject("select to_regclass('public.hr_employee') is not null", Boolean.class));
    assertTrue(jdbc.queryForObject("select to_regclass('public.hr_employee_history') is not null", Boolean.class));
    assertTrue(jdbc.queryForObject("select to_regclass('public.hr_attendance_record') is not null", Boolean.class));
    assertTrue(jdbc.queryForObject("select to_regclass('public.hr_leave_type') is not null", Boolean.class));
    assertTrue(jdbc.queryForObject("select to_regclass('public.hr_leave_request') is not null", Boolean.class));
    assertEquals(1, jdbc.queryForObject("select count(*) from flyway_schema_history where version='61' and success=true", Integer.class));
    assertEquals(5, jdbc.queryForObject("select count(*) from hr_leave_type where active=true", Integer.class));
    for (String permission : List.of("HR_ATTENDANCE_VIEW", "HR_ATTENDANCE_MANAGE", "HR_LEAVE_VIEW", "HR_LEAVE_MANAGE")) {
      assertTrue(jdbc.queryForObject("select exists(select 1 from staff_permission_catalog where permission_key=?)", Boolean.class, permission));
    }
    assertEquals(0, jdbc.queryForObject("select count(*) from hr_attendance_record", Integer.class));
    assertEquals(0, jdbc.queryForObject("select count(*) from hr_leave_request", Integer.class));
    assertEquals(1, jdbc.queryForObject("""
      select count(*) from flyway_schema_history where version='60' and success=true
      """, Integer.class));
    assertEquals(1, jdbc.queryForObject("select count(*) from hr_organization where name='QuantaEdge'", Integer.class));
    assertEquals(0, jdbc.queryForObject("select count(*) from hr_employee", Integer.class),
        "A clean installation must not invent employee records.");
    assertEquals(7, jdbc.queryForObject("select count(*) from hr_employment_type where active=true", Integer.class));
    assertTrue(jdbc.queryForObject("""
      select exists(select 1 from staff_permission_catalog where permission_key='HR_EMPLOYEE_VIEW')
      """, Boolean.class));
    assertTrue(jdbc.queryForObject("""
      select exists(select 1 from staff_permission_catalog where permission_key='HR_EMPLOYEE_MANAGE')
      """, Boolean.class));
  }

  @Test
  void cleanDatabaseMigratesAndRetainsAllSixClassSubjectTracks() {
    assertTrue(jdbc.queryForObject("select to_regclass('public.curriculum_class') is not null", Boolean.class));
    assertTrue(jdbc.queryForObject("select to_regclass('public.question') is not null", Boolean.class));
    assertTrue(jdbc.queryForObject("select to_regclass('public.student_track_enrollment') is not null", Boolean.class));
    assertEquals(1, jdbc.queryForObject("""
      select count(*) from flyway_schema_history
      where version='28' and success=true
      """, Integer.class));
    assertEquals(6, jdbc.queryForObject("""
      select count(*) from curriculum_class c
      join curriculum_subject s on s.class_id=c.id
      where c.code in ('6','7','8') and s.code in ('maths','science')
        and s.program_id is null
      """, Integer.class));
    assertEquals(2, jdbc.queryForObject("""
      select count(*) from curriculum_program p
      join curriculum_program_subject ps on ps.program_id=p.id
      join curriculum_subject s on s.id=ps.subject_id and s.program_id=p.id
        and s.class_id=ps.class_id
      join curriculum_class c on c.id=ps.class_id
      where p.code='NAVODAYA_BATCH' and p.status='ACTIVE'
        and s.code in ('maths','science') and s.active=true
      """, Integer.class));
    assertTrue(jdbc.queryForObject("""
      select exists(select 1 from information_schema.columns
        where table_schema='public' and table_name='question' and column_name='review_status')
      """, Boolean.class));
    assertTrue(jdbc.queryForObject("""
      select exists(select 1 from information_schema.columns
        where table_schema='public' and table_name='lesson' and column_name='alignment_source_url')
      """, Boolean.class));
    assertEquals(2, jdbc.queryForObject("""
      select count(*) from flyway_schema_history
      where version in ('10','13') and success=true
      """, Integer.class));
  }
  @Test
  void publicIdentityMigrationKeepsStableNumericIdsAndUsesGloballyUniqueEightCharacterCodes() {
    assertTrue(jdbc.queryForObject("select to_regclass('public.public_identity_registry') is not null", Boolean.class));
    assertTrue(jdbc.queryForObject("select to_regclass('public.public_identity_prefix_registry') is not null", Boolean.class));
    assertEquals(1, jdbc.queryForObject("select count(*) from flyway_schema_history where version='99' and success=true", Integer.class));
    assertEquals(0, jdbc.queryForObject("""
      select count(*) from curriculum_class
      where length(public_id)<>8 or public_id !~ '^[A-Z]{3}[A-Z0-9]{5}' or left(public_id,3)<>'SCH'
      """, Integer.class));
    assertEquals(0, jdbc.queryForObject("""
      select count(*) from curriculum_program
      where code<>'GENERAL' and (length(public_id)<>8 or public_id !~ '^[A-Z]{3}[A-Z0-9]{5}'
        or public_id_prefix is null or left(public_id,3)<>public_id_prefix)
      """, Integer.class));
    assertEquals(0, jdbc.queryForObject("""
      select count(*) from (
        select public_id from (
          select public_id from curriculum_class
          union all
          select public_id from curriculum_program where public_id is not null
        ) all_identities
        group by public_id having count(*)>1
      ) duplicates
      """, Integer.class));
    assertEquals(
      jdbc.queryForObject("select count(*) from curriculum_class", Integer.class),
      jdbc.queryForObject("select count(*) from public_identity_registry where entity_type='CLASS'", Integer.class));
    assertEquals(
      jdbc.queryForObject("select count(*) from curriculum_program where code<>'GENERAL'", Integer.class),
      jdbc.queryForObject("select count(*) from public_identity_registry where entity_type='PROGRAM'", Integer.class));
  }
  @Test
  @Transactional
  void newAssessmentsReceiveCourseScopedMonotonicPublicIds() {
    Long classSubjectId = jdbc.queryForObject("select id from curriculum_subject where program_id is null order by id limit 1", Long.class);
    String classPublicId = jdbc.queryForObject("select c.public_id from curriculum_class c join curriculum_subject s on s.class_id=c.id where s.id=?", String.class, classSubjectId);
    Long firstClassAssessment = jdbc.queryForObject("insert into assessment(title,subject_id,status) values(?,?,'DRAFT') returning id", Long.class, "identity-smoke-class-1", classSubjectId);
    Long secondClassAssessment = jdbc.queryForObject("insert into assessment(title,subject_id,status) values(?,?,'DRAFT') returning id", Long.class, "identity-smoke-class-2", classSubjectId);
    String firstClassTestId = jdbc.queryForObject("select public_id from assessment where id=?", String.class, firstClassAssessment);
    String secondClassTestId = jdbc.queryForObject("select public_id from assessment where id=?", String.class, secondClassAssessment);
    Long firstClassSequence = jdbc.queryForObject("select public_id_sequence from assessment where id=?", Long.class, firstClassAssessment);
    Long secondClassSequence = jdbc.queryForObject("select public_id_sequence from assessment where id=?", Long.class, secondClassAssessment);
    assertTrue(firstClassTestId.startsWith(classPublicId + "-"));
    assertTrue(firstClassTestId.substring(classPublicId.length()+1).matches("[0-9]+"));
    assertEquals(firstClassSequence + 1L, secondClassSequence);
    assertTrue(secondClassTestId.startsWith(classPublicId + "-"));

    Map<String,Object> navSubject = jdbc.queryForMap("select s.id as subject_id,p.public_id from curriculum_subject s join curriculum_program p on p.id=s.program_id where p.code='NAVODAYA_BATCH' order by s.id limit 1");
    Long navSubjectId = ((Number) navSubject.get("subject_id")).longValue();
    String navPublicId = String.valueOf(navSubject.get("public_id"));
    Long navAssessmentId = jdbc.queryForObject("insert into assessment(title,subject_id,status) values(?,?,'DRAFT') returning id", Long.class, "identity-smoke-nav", navSubjectId);
    String navTestId = jdbc.queryForObject("select public_id from assessment where id=?", String.class, navAssessmentId);
    assertTrue(navTestId.startsWith(navPublicId + "-"));
  }

  @Test
  @Transactional
  void publicUserIdIsDatabaseGeneratedUniqueAndExactlyTwelveAlphanumericCharacters() {
    assertTrue(jdbc.queryForObject("""
      select exists(select 1 from information_schema.columns
        where table_schema='public' and table_name='user_account' and column_name='public_user_id')
      """, Boolean.class));
    assertTrue(jdbc.queryForObject("""
      select exists(select 1 from information_schema.columns
        where table_schema='public' and table_name='student' and column_name='public_user_id')
      """, Boolean.class));
    assertEquals(0, jdbc.queryForObject("""
      select count(*) from student
      where public_user_id is null or length(public_user_id)<>12 or public_user_id !~ '^[A-Z0-9]{12}$'
      """, Integer.class));
    assertEquals(0, jdbc.queryForObject("""
      select count(*) from (
        select public_user_id from student group by public_user_id having count(*)>1
      ) duplicates
      """, Integer.class));
    assertEquals(0, jdbc.queryForObject("""
      select count(*) from student st
      join user_account u on u.id=st.user_account_id
      where st.public_user_id<>u.public_user_id
      """, Integer.class));
    assertEquals(0, jdbc.queryForObject("""
      select count(*) from user_account
      where length(public_user_id)<>12 or public_user_id !~ '^[A-Z0-9]{12}$'
      """, Integer.class));
    assertEquals(0, jdbc.queryForObject("""
      select count(*) from (
        select public_user_id from user_account group by public_user_id having count(*)>1
      ) duplicates
      """, Integer.class));
    Long accountId = jdbc.queryForObject("""
      insert into user_account(public_id,mobile_e164,display_name,role)
      values (gen_random_uuid(), '+919'||lpad((floor(random()*1000000000))::bigint::text,9,'0'),
        'public-id-smoke-test','PARENT')
      returning id
      """, Long.class);
    String publicId = jdbc.queryForObject(
      "select public_user_id from user_account where id=?", String.class, accountId);
    assertTrue(publicId.matches("[A-Z0-9]{12}"));
    assertEquals(1, jdbc.queryForObject(
      "select count(*) from user_account where public_user_id=?", Integer.class, publicId));
    assertTrue(jdbc.queryForObject("""
      select exists(select 1 from pg_constraint
        where conrelid='public.user_account'::regclass
          and conname='uq_user_account_public_user_id' and contype='u')
      """, Boolean.class));
  }

  @Test
  void accountLifecycleSchemaRequiresVerifiedAdultAndParentEligibilityWithoutStoringDateOfBirth() {
    assertTrue(jdbc.queryForObject("select to_regclass('public.student_adult_verification') is not null", Boolean.class));
    assertTrue(jdbc.queryForObject("select to_regclass('public.user_parent_eligibility') is not null", Boolean.class));
    assertTrue(jdbc.queryForObject("select to_regclass('public.account_lifecycle_audit') is not null", Boolean.class));
    assertTrue(jdbc.queryForObject("select exists(select 1 from information_schema.columns where table_schema='public' and table_name='student' and column_name='user_account_id')", Boolean.class));
    assertTrue(jdbc.queryForObject("select exists(select 1 from information_schema.columns where table_schema='public' and table_name='guardian_student' and column_name='access_level')", Boolean.class));
    assertTrue(jdbc.queryForObject("select exists(select 1 from pg_constraint where conrelid='public.user_account'::regclass and conname='user_account_role_supported' and pg_get_constraintdef(oid) like '%LEARNER%')", Boolean.class));
    assertTrue(jdbc.queryForObject("select exists(select 1 from pg_trigger where tgrelid='public.user_account'::regclass and tgname='trg_protect_account_lifecycle_role_transition' and not tgisinternal)", Boolean.class));
    assertEquals(0, jdbc.queryForObject("select count(*) from information_schema.columns where table_schema='public' and table_name='student' and column_name in ('date_of_birth','birth_date')", Integer.class));
  }

  @Test
  void activeCourseEnrollmentsHaveTheirSubjectTrackRowsBackfilled() {
    assertEquals(0, jdbc.queryForObject("""
      select count(*) from student_program_enrollment spe
      join curriculum_program_subject ps on ps.program_id=spe.program_id
      join curriculum_subject s on s.id=ps.subject_id
        and s.program_id=spe.program_id and s.active=true
      left join student_track_enrollment ste
        on ste.student_id=spe.student_id and ste.subject_id=s.id
      where spe.status='ACTIVE' and ste.id is null
      """, Integer.class));
  }


  @Test
  @Transactional
  void courseScopedStudentMetricsSqlExecutesAgainstMigratedTenantSchema() {
    Long institutionId = jdbc.queryForObject("""
      select id from institution_tenant where tenant_code='QUANTA-EDGE' and status='ACTIVE'
      """, Long.class);
    Long courseId = jdbc.queryForObject("""
      select id from curriculum_program where code='CLASS_6' and status='ACTIVE'
        and curriculum_version='CURRENT' order by id limit 1
      """, Long.class);
    String classCode = jdbc.queryForObject("""
      select c.code from curriculum_program p join curriculum_class c on c.id=p.class_id where p.id=?
      """, String.class, courseId);
    Long studentId = jdbc.queryForObject("""
      insert into student(public_id,display_name,class_code,board,language,environment,active)
      values (gen_random_uuid(),'Course metrics regression probe',?,'BSEB','en','LOCAL_PREVIEW',true)
      returning id
      """, Long.class, classCode);
    // Exercise the same selected-course aggregate path used by GET /api/v1/students/me?courseId=...
    Map<String,Object> metrics = learningMetrics.statsFor(studentId, courseId, institutionId);
    assertTrue(metrics.get("lessonStats") instanceof Map<?,?>);
    assertTrue(metrics.get("questionStats") instanceof Map<?,?>);
    assertTrue(metrics.get("curriculum") instanceof List<?>);
    assertTrue(metrics.get("mastery") instanceof Map<?,?>);
    assertTrue(metrics.get("assessmentStats") instanceof Map<?,?>);
    assertTrue(metrics.get("diagnosticStats") instanceof Map<?,?>);
    assertTrue(metrics.get("diagnosticSubjects") instanceof List<?>);
    assertTrue(metrics.get("sessionStats") instanceof Map<?,?>);
  }

  @Test
  void sharedStudentAndParentReportSessionAggregatesExecuteWithoutLearnerData() {
    // This is the shared query path used by both dashboards. It must execute even on an
    // empty student fixture so SQL syntax failures cannot be hidden by conditional test skips.
    Map<String,Object> sessionStats = learningMetrics.sessionStatsFor(0L);
    assertTrue(sessionStats.containsKey("minutes_30d"));
    assertTrue(sessionStats.containsKey("sessions_30d"));
  }



  @Test
  @Transactional
  void learningQuestionProjectionSqlExecutesAgainstMigratedTenantSchema() {
    Long institutionId = jdbc.queryForObject("""
      select id from institution_tenant
      where tenant_code='QUANTA-EDGE' and status='ACTIVE'
      """, Long.class);
    Long classSixCourseId = jdbc.queryForObject("""
      select id from curriculum_program
      where code='CLASS_6' and status='ACTIVE' and curriculum_version='CURRENT'
      order by id limit 1
      """, Long.class);

    // A non-existent learner/lesson returns zero rows, but PostgreSQL still parses the complete
    // answer projection and bind list, including the tenant/course-scoped latest-attempt lateral join.
    List<Map<String,Object>> result = learningController.publicQuestions(
        Long.MAX_VALUE, Long.MAX_VALUE, classSixCourseId, "en", institutionId);
    assertTrue(result != null, "The legacy learning question projection must execute on the migrated schema.");
  }

}
