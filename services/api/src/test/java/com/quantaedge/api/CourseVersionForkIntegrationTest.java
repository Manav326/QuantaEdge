package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CourseVersionForkIntegrationTest {
  @Autowired private JdbcTemplate jdbc;
  @Autowired private CourseVersionService versions;

  @Test
  @Transactional
  void forksReleasedCourseIntoIndependentDraftAndProtectsOriginalGraph() {
    Long sourceInstitutionId = jdbc.queryForObject("""
      select id from institution_tenant where tenant_code='QUANTA-EDGE' and status='ACTIVE'
      """, Long.class);
    ensureCurrentSourceRelease(sourceInstitutionId);
    Map<String,Object> source = jdbc.queryForMap("""
      select p.id as course_id,p.course_identity_id,p.content_version_no,r.release_state
      from curriculum_program p join institution_course_release r
        on r.program_id=p.id and r.source_institution_id=?
      join institution_course_identity identity
        on identity.id=p.course_identity_id and identity.status='ACTIVE'
       and identity.source_institution_id=r.source_institution_id
       and identity.course_type_code=p.course_type_code
      where identity.stable_course_key='CLASS_6' and p.status='ACTIVE'
        and r.release_state in ('SHAREABLE','WITHDRAWN')
        and r.legacy_migration=false and r.obsolete_at is null
      order by p.content_version_no desc,r.release_version desc,p.id desc,r.id desc limit 1
      """, sourceInstitutionId);
    long sourceId = ((Number) source.get("course_id")).longValue();
    long identityId = ((Number) source.get("course_identity_id")).longValue();
    int oldVersion = ((Number) source.get("content_version_no")).intValue();
    assertNotNull(source.get("release_state"));

    long subjects = count("select count(*) from curriculum_program_subject where program_id=?", sourceId);
    long chapters = count("""
      select count(distinct ch.id) from curriculum_chapter ch join curriculum_program_subject ps on ps.subject_id=ch.subject_id
      where ps.program_id=?
      """, sourceId);
    long lessons = count("""
      select count(distinct l.id) from lesson l join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_program_subject ps on ps.subject_id=ch.subject_id where ps.program_id=?
      """, sourceId);
    long blocks = count("""
      select count(distinct b.id) from lesson_block b join lesson l on l.id=b.lesson_id
      join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_program_subject ps on ps.subject_id=ch.subject_id where ps.program_id=?
      """, sourceId);
    long questions = count("""
      select count(*) from question q
      where exists(select 1 from qe_course_program_ids_for_question(q.id) scoped where scoped.program_id=?)
      """, sourceId);
    long options = count("""
      select count(*) from question_option o join question q on q.id=o.question_id
      where exists(select 1 from qe_course_program_ids_for_question(q.id) scoped where scoped.program_id=?)
      """, sourceId);
    assertTrue(subjects>0,"The released source course should have mapped subjects.");
    assertTrue(chapters>0,"The released source course should have chapters.");
    assertTrue(lessons>0,"The released source course should have lessons.");
    assertTrue(blocks>0,"The released source course should have teaching blocks.");

    long enrollmentsBefore = count("select count(*) from student_program_enrollment where program_id=?",sourceId);
    Long sourceBlockId = jdbc.queryForObject("""
      select b.id from lesson_block b join lesson l on l.id=b.lesson_id
      join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_program_subject ps on ps.subject_id=ch.subject_id
      where ps.program_id=? order by b.id limit 1
      """,Long.class,sourceId);
    String sourceContent=jdbc.queryForObject("select content::text from lesson_block where id=?",String.class,sourceBlockId);

    Map<String,Object> created=versions.forkReleasedVersion(sourceId,sourceInstitutionId,null,null);
    long newCourseId=((Number)created.get("courseId")).longValue();
    long releaseId=((Number)created.get("releaseId")).longValue();
    long assignmentId=((Number)created.get("assignmentId")).longValue();
    assertEquals("DRAFT",created.get("releaseStatus"));
    assertEquals(sourceId,((Number)created.get("sourceCourseId")).longValue());
    assertTrue(((Number)created.get("contentVersionNo")).intValue()>oldVersion);
    assertEquals(sourceId,jdbc.queryForObject("select supersedes_program_id from curriculum_program where id=?",Long.class,newCourseId));
    assertEquals(identityId,jdbc.queryForObject("select course_identity_id from curriculum_program where id=?",Long.class,newCourseId));
    assertEquals("DRAFT",jdbc.queryForObject("select release_state from institution_course_release where id=?",String.class,releaseId));
    assertEquals("ASSIGNED",jdbc.queryForObject("select status from institution_course_assignment where id=?",String.class,assignmentId));
    assertEquals(0L,count("""
      select count(*) from institution_course_assignment a join institution_course_release r on r.id=a.course_release_id
      where r.program_id=? and a.institution_id<>? and a.status='ASSIGNED'
      """,newCourseId,sourceInstitutionId));
    assertEquals(0L,count("select count(*) from institution_marketplace_offer where course_id=? and status='PUBLISHED'",newCourseId));
    assertEquals(subjects,mapped(newCourseId,"SUBJECT"));
    assertEquals(chapters,mapped(newCourseId,"CHAPTER"));
    assertEquals(lessons,mapped(newCourseId,"LESSON"));
    assertEquals(blocks,mapped(newCourseId,"LESSON_BLOCK"));
    assertEquals(questions,mapped(newCourseId,"QUESTION"));
    assertEquals(options,mapped(newCourseId,"QUESTION_OPTION"));
    assertEquals(enrollmentsBefore,count("select count(*) from student_program_enrollment where program_id=?",sourceId));

    Long forkedBlockId=jdbc.queryForObject("""
      select version_entity_id from institution_course_version_entity_map
      where version_program_id=? and entity_type='LESSON_BLOCK' and source_entity_id=?
      """,Long.class,newCourseId,sourceBlockId);
    assertEquals(sourceContent,jdbc.queryForObject("select content::text from lesson_block where id=?",String.class,forkedBlockId));
    jdbc.update("update lesson_block set content=content || ?::jsonb where id=?","{\"fork_probe\":\"new draft only\"}",forkedBlockId);
    assertEquals(sourceContent,jdbc.queryForObject("select content::text from lesson_block where id=?",String.class,sourceBlockId));
    assertTrue(jdbc.queryForObject("select jsonb_exists(content, 'fork_probe') from lesson_block where id=?",Boolean.class,forkedBlockId));

    assertThrows(DataAccessException.class,() -> jdbc.update("""
      update lesson_block set content=content || '{"forbidden_mutation":true}'::jsonb where id=?
      """,sourceBlockId),"Editing the released source graph must be blocked by immutability triggers.");
  }

  @Test
  @Transactional
  void databaseRejectsForksBasedOnAnOlderVersionAfterANewerDraftExists() {
    Long sourceInstitutionId = jdbc.queryForObject("""
      select id from institution_tenant where tenant_code='QUANTA-EDGE' and status='ACTIVE'
      """, Long.class);
    ensureCurrentSourceRelease(sourceInstitutionId);
    Map<String,Object> source = jdbc.queryForMap("""
      select p.id as course_id,p.content_version_no
      from curriculum_program p
      join institution_course_release r on r.program_id=p.id and r.source_institution_id=?
      join institution_course_identity identity
        on identity.id=p.course_identity_id and identity.status='ACTIVE'
       and identity.source_institution_id=r.source_institution_id
       and identity.course_type_code=p.course_type_code
      where identity.stable_course_key='CLASS_6' and p.status='ACTIVE'
        and r.release_state in ('SHAREABLE','WITHDRAWN')
        and r.legacy_migration=false and r.obsolete_at is null
      order by p.content_version_no desc,r.release_version desc,p.id desc,r.id desc limit 1
      """, sourceInstitutionId);
    long sourceId=((Number)source.get("course_id")).longValue();
    int oldVersion=((Number)source.get("content_version_no")).intValue();

    versions.forkReleasedVersion(sourceId,sourceInstitutionId,null,null);

    // Simulate an alternate caller trying to use the old source row after a newer draft has
    // already been created. The database trigger must reject it independently of the service.
    assertThrows(DataAccessException.class,() -> jdbc.update("""
      insert into curriculum_program(
        class_id,code,display_name,stream_code,curriculum_version,status,notes,public_id_prefix,
        course_type_code,course_identity_id,content_version_no,supersedes_program_id
      )
      select class_id,left(code,40)||'_STALE_PROBE',display_name,stream_code,curriculum_version,
        'ACTIVE',notes,public_id_prefix,course_type_code,course_identity_id,?,id
      from curriculum_program where id=?
      """,oldVersion+2,sourceId),
      "A superseding version must reference the latest course version, not an older course graph.");
  }

  private void ensureCurrentSourceRelease(long sourceInstitutionId) {
    Long courseId=jdbc.queryForObject("""
      select p.id
      from curriculum_program p
      join institution_course_identity identity
        on identity.id=p.course_identity_id and identity.status='ACTIVE'
       and identity.source_institution_id=?
       and identity.course_type_code=p.course_type_code
      where identity.stable_course_key='CLASS_6' and p.status='ACTIVE' and p.code<>'GENERAL'
      order by p.content_version_no desc,p.id desc limit 1
      """,Long.class,sourceInstitutionId);
    Boolean currentReleaseExists=jdbc.queryForObject("""
      select exists(
        select 1 from institution_course_release release
        where release.program_id=? and release.source_institution_id=?
          and release.release_state in ('SHAREABLE','WITHDRAWN')
          and release.legacy_migration=false and release.obsolete_at is null
      )
      """,Boolean.class,courseId,sourceInstitutionId);
    if(Boolean.TRUE.equals(currentReleaseExists)) return;

    jdbc.update("""
      insert into institution_course_release(
        source_institution_id,program_id,release_version,release_state,
        content_fingerprint,legacy_migration,created_at,updated_at
      )
      select ?,p.id,
        coalesce((select max(existing.release_version)
          from institution_course_release existing
          where existing.source_institution_id=?
            and existing.program_id=p.id),0)+1,
        'WITHDRAWN',course_content_fingerprint(p.id),false,now(),now()
      from curriculum_program p
      where p.id=?
      """,sourceInstitutionId,sourceInstitutionId,courseId);
  }

  private long count(String sql,Object... args) {
    Long value=jdbc.queryForObject(sql,Long.class,args);
    return value==null?0L:value;
  }

  private long mapped(long versionProgramId,String entityType) {
    return count("select count(*) from institution_course_version_entity_map where version_program_id=? and entity_type=?",
        versionProgramId,entityType);
  }
}
