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
  private LearningMetricsService learningMetrics;

  @Autowired
  private ContentTranslationController contentTranslationController;

  @Autowired
  private AdminContentController adminContentController;


  @Test
  void bilingualSchemaExistsAndTranslationInventoryQueryExecutes() {
    assertTrue(jdbc.queryForObject("select to_regclass('public.curriculum_program') is not null", Boolean.class));
    assertTrue(jdbc.queryForObject("select to_regclass('public.lesson_translation') is not null", Boolean.class));
    assertTrue(jdbc.queryForObject("select to_regclass('public.lesson_block_translation') is not null", Boolean.class));
    assertTrue(jdbc.queryForObject("select to_regclass('public.question_translation') is not null", Boolean.class));
    assertEquals(1, jdbc.queryForObject("""
      select count(*) from flyway_schema_history
      where version='51' and success=true
      """, Integer.class));

    AuthContext admin = new AuthContext(null, null, 999L, "ADMIN", "CI admin");
    Map<String,Object> result = contentTranslationController.list(null, null, "ALL", "ALL", "ALL", 20, 0, admin);
    assertTrue(result.get("items") instanceof List<?>);
    assertTrue(result.get("total") instanceof Number);
    Map<String,Object> sourceInventory = contentTranslationController.inventory("", "", "ALL", "ALL", 20, 0, admin);
    assertTrue(sourceInventory.get("items") instanceof List<?>);
    assertTrue(sourceInventory.get("total") instanceof Number);
    assertTrue(((Number) sourceInventory.get("total")).longValue() > 0, "Untranslated source inventory should include current class/subject content.");
    @SuppressWarnings("unchecked")
    List<Map<String,Object>> filteredItems = (List<Map<String,Object>>) contentTranslationController
        .inventory("6", "maths", "en", "ALL", 20, 0, admin).get("items");
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
  void sharedStudentAndParentLearningReportMetricsExecuteTheirFullQueryPath() {
    List<Long> activeStudents = jdbc.queryForList(
        "select id from student where active=true order by id limit 1", Long.class);
    // Some isolated migration-only CI fixtures contain no student rows; skip explicitly there.
    org.junit.jupiter.api.Assumptions.assumeFalse(activeStudents.isEmpty(),
        "An active student is required to exercise the shared learning metrics query.");

    Map<String,Object> metrics = learningMetrics.statsFor(activeStudents.getFirst());
    assertTrue(metrics.containsKey("lessonStats"));
    assertTrue(metrics.containsKey("questionStats"));
    assertTrue(metrics.containsKey("sessionStats"));
    @SuppressWarnings("unchecked")
    Map<String,Object> sessionStats = (Map<String,Object>) metrics.get("sessionStats");
    assertTrue(sessionStats.containsKey("minutes_30d"));
    assertTrue(sessionStats.containsKey("sessions_30d"));
  }


}
