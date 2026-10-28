package com.quantaedge.api;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LearningStateService {
  private final JdbcTemplate jdbc;
  public LearningStateService(JdbcTemplate jdbc){this.jdbc=jdbc;}

  /**
   * Legacy Java compatibility entry point. Tenant-protected HTTP routes use the scoped overload.
   * This overload remains for existing isolated service tests and must not be used by controllers.
   */
  @Transactional
  public void startLesson(long studentId,long lessonId){
    startLesson(studentId,lessonId,null);
  }

  @Transactional
  public void startLesson(long studentId,long lessonId,Long institutionId){
    startLesson(studentId,lessonId,institutionId,null);
  }

  @Transactional
  public void startLesson(long studentId,long lessonId,Long institutionId,Long courseId){
    if(courseId==null) ensureStudentLessonAccess(studentId,lessonId,institutionId);
    else ensureStudentLessonAccess(studentId,lessonId,institutionId,courseId);
    if(institutionId==null){
      jdbc.update("""
        insert into student_lesson_progress(student_id,lesson_id,status,progress_percent,started_at,last_opened_at)
        values(?,?,'IN_PROGRESS',0,now(),now())
        on conflict(student_id,lesson_id) where institution_id is null and course_id is null do update set
          status=case when student_lesson_progress.status='COMPLETED' then 'COMPLETED' else 'IN_PROGRESS' end,
          started_at=coalesce(student_lesson_progress.started_at,now()),last_opened_at=now()
        """,studentId,lessonId);
    } else if(courseId==null) {
      jdbc.update("""
        insert into student_lesson_progress(
          student_id,institution_id,lesson_id,status,progress_percent,started_at,last_opened_at
        ) values(?,?,?,'IN_PROGRESS',0,now(),now())
        on conflict(institution_id,student_id,lesson_id)
          where institution_id is not null and course_id is null
        do update set
          status=case when student_lesson_progress.status='COMPLETED' then 'COMPLETED' else 'IN_PROGRESS' end,
          started_at=coalesce(student_lesson_progress.started_at,now()),last_opened_at=now()
        """,studentId,institutionId,lessonId);
    } else {
      jdbc.update("""
        insert into student_lesson_progress(
          student_id,institution_id,course_id,lesson_id,status,progress_percent,started_at,last_opened_at
        ) values(?,?,?,?, 'IN_PROGRESS',0,now(),now())
        on conflict(institution_id,student_id,course_id,lesson_id)
          where institution_id is not null and course_id is not null
        do update set
          status=case when student_lesson_progress.status='COMPLETED' then 'COMPLETED' else 'IN_PROGRESS' end,
          started_at=coalesce(student_lesson_progress.started_at,now()),last_opened_at=now()
        """,studentId,institutionId,courseId,lessonId);
    }
  }

  @Transactional
  public void recordAttempt(long studentId,long questionId,long lessonId,String answer,boolean autoGraded,Boolean correct){
    recordAttempt(studentId,questionId,lessonId,answer,autoGraded,correct,null,"LESSON_INLINE",null);
  }

  @Transactional
  public void recordAttempt(long studentId,long questionId,long lessonId,String answer,boolean autoGraded,Boolean correct,Long practiceSessionId){
    recordAttempt(studentId,questionId,lessonId,answer,autoGraded,correct,practiceSessionId,
        practiceSessionId==null?"LESSON_INLINE":"PRACTICE",null);
  }

  @Transactional
  public void recordAttempt(long studentId,long questionId,long lessonId,String answer,boolean autoGraded,
      Boolean correct,Long practiceSessionId,String activityKind){
    recordAttempt(studentId,questionId,lessonId,answer,autoGraded,correct,practiceSessionId,activityKind,null);
  }

  /** Tenant-stamped compatibility path for non-course internal callers. Public course routes pass courseId. */
  @Transactional
  public void recordAttempt(long studentId,long questionId,long lessonId,String answer,boolean autoGraded,
      Boolean correct,Long practiceSessionId,String activityKind,Long institutionId){
    recordAttemptInternal(studentId,questionId,lessonId,answer,autoGraded,correct,practiceSessionId,activityKind,institutionId,null);
  }

  @Transactional
  public void recordAttempt(long studentId,long questionId,long lessonId,String answer,boolean autoGraded,
      Boolean correct,Long practiceSessionId,String activityKind,Long institutionId,Long courseId){
    recordAttemptInternal(studentId,questionId,lessonId,answer,autoGraded,correct,practiceSessionId,activityKind,institutionId,courseId);
  }

  private void recordAttemptInternal(long studentId,long questionId,long lessonId,String answer,boolean autoGraded,
      Boolean correct,Long practiceSessionId,String activityKind,Long institutionId,Long courseId){
    if(courseId==null) ensureStudentCanAnswer(studentId,questionId,lessonId,institutionId);
    else ensureStudentCanAnswer(studentId,questionId,lessonId,institutionId,courseId);
    if(answer!=null && answer.length()>5000) throw new IllegalArgumentException("Answer is too long");
    String source=activityKind==null?"LESSON_INLINE":activityKind.trim().toUpperCase(java.util.Locale.ROOT);
    if(!List.of("LESSON_INLINE","PRACTICE","DIAGNOSTIC").contains(source))
      throw new IllegalArgumentException("Invalid learning activity source");
    if(practiceSessionId!=null) {
      List<Map<String,Object>> membership;
      if(institutionId==null){
        membership=jdbc.queryForList("""
          select psq.question_id
          from student_practice_session_question psq
          join student_practice_session ps on ps.id=psq.practice_session_id
          where ps.id=? and ps.student_id=? and psq.question_id=?
            and ps.status='IN_PROGRESS' and psq.answered_at is null
          for update of psq,ps
          """,practiceSessionId,studentId,questionId);
      } else {
        membership=jdbc.queryForList("""
          select psq.question_id
          from student_practice_session_question psq
          join student_practice_session ps on ps.id=psq.practice_session_id
          where ps.id=? and ps.student_id=? and ps.institution_id=? and psq.question_id=?
            and ps.status='IN_PROGRESS' and psq.answered_at is null
          for update of psq,ps
          """,practiceSessionId,studentId,institutionId,questionId);
      }
      if(membership.isEmpty())throw new SecurityException("Question is not assigned to this active practice session or was already answered.");
      if(!"PRACTICE".equals(source)) throw new IllegalArgumentException("Practice-session answers must use the PRACTICE source");
    }
    String selected=answer!=null&&answer.length()<=20&&!answer.startsWith("[")&&!answer.startsWith("{")?answer:null;
    if(institutionId==null){
      jdbc.update("""
        insert into student_question_attempt(
          student_id,question_id,selected_option,answer_text,correct,answered_at,practice_session_id,activity_kind
        ) values(?,?,?,?,?,now(),?,?)
        """,studentId,questionId,selected,answer,correct,practiceSessionId,source);
    } else {
      jdbc.update("""
        insert into student_question_attempt(
          student_id,institution_id,course_id,question_id,selected_option,answer_text,correct,answered_at,practice_session_id,activity_kind
        ) values(?,?,?,?,?,?,?,now(),?,?)
        """,studentId,institutionId,courseId,questionId,selected,answer,correct,practiceSessionId,source);
    }

    if(practiceSessionId!=null) {
      // Incorrect auto-graded answers stay open for a retry. Ungraded answers are saved
      // but do not count as demonstrated mastery.
      boolean itemComplete=!(autoGraded && Boolean.FALSE.equals(correct));
      if(itemComplete) {
        jdbc.update("""
          update student_practice_session_question
          set answered_at=coalesce(answered_at,now())
          where practice_session_id=? and question_id=?
          """,practiceSessionId,questionId);
      }
      if(institutionId==null){
        jdbc.update("""
          update student_practice_session ps set
            status=case when not exists(
              select 1 from student_practice_session_question psq
              where psq.practice_session_id=ps.id and psq.answered_at is null
            ) then 'COMPLETED' else 'IN_PROGRESS' end,
            completed_at=case when not exists(
              select 1 from student_practice_session_question psq
              where psq.practice_session_id=ps.id and psq.answered_at is null
            ) then coalesce(ps.completed_at,now()) else null end
          where ps.id=? and ps.student_id=?
          """,practiceSessionId,studentId);
      } else {
        jdbc.update("""
          update student_practice_session ps set
            status=case when not exists(
              select 1 from student_practice_session_question psq
              where psq.practice_session_id=ps.id and psq.answered_at is null
            ) then 'COMPLETED' else 'IN_PROGRESS' end,
            completed_at=case when not exists(
              select 1 from student_practice_session_question psq
              where psq.practice_session_id=ps.id and psq.answered_at is null
            ) then coalesce(ps.completed_at,now()) else null end
          where ps.id=? and ps.student_id=? and ps.institution_id=?
          """,practiceSessionId,studentId,institutionId);
      }
    }

    // Completion, session lineage and mastery are all read and written within the same institution.
    if(!"DIAGNOSTIC".equals(source)) {
      syncLessonProgress(studentId,lessonId,institutionId,courseId);
      if(practiceSessionId!=null) {
        List<Map<String,Object>> sourceLessons;
        if(institutionId==null){
          sourceLessons=jdbc.queryForList("""
            select (item->>'sourceLessonId')::bigint as lesson_id
            from student_practice_session ps
            cross join lateral jsonb_array_elements(ps.topic_selections) item
            where ps.id=? and ps.student_id=? and jsonb_exists(item, 'sourceLessonId')
            """,practiceSessionId,studentId);
        } else {
          sourceLessons=jdbc.queryForList("""
            select (item->>'sourceLessonId')::bigint as lesson_id
            from student_practice_session ps
            cross join lateral jsonb_array_elements(ps.topic_selections) item
            where ps.id=? and ps.student_id=? and ps.institution_id=?
              and jsonb_exists(item, 'sourceLessonId')
            """,practiceSessionId,studentId,institutionId);
        }
        for(Map<String,Object> row:sourceLessons) {
          Object sourceLesson=row.get("lesson_id");
          if(sourceLesson instanceof Number n) syncLessonProgress(studentId,n.longValue(),institutionId,courseId);
        }
      }
    }

    if(autoGraded&&correct!=null&&!"DIAGNOSTIC".equals(source)){
      Object concept=jdbc.queryForMap("select concept_id from question where id=?",questionId).get("concept_id");
      if(concept instanceof Number) updateMastery(studentId,((Number)concept).longValue(),institutionId,courseId);
    }
  }

  private void syncLessonProgress(long studentId,long lessonId,Long institutionId){
    syncLessonProgress(studentId,lessonId,institutionId,null);
  }

  private void syncLessonProgress(long studentId,long lessonId,Long institutionId,Long courseId){
    String visibleSql;
    Object[] visibleArgs;
    if(courseId!=null){
      visibleSql="""
        select l.id
        from lesson l
        join curriculum_chapter ch on ch.id=l.chapter_id
        join curriculum_subject s on s.id=ch.subject_id
        join curriculum_class c on c.id=s.class_id
        join student st on st.id=? and st.active=true
        join institution_membership m on m.student_id=st.id and m.institution_id=? and m.status='ACTIVE'
        join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
        join student_program_enrollment spe on spe.student_id=st.id and spe.institution_id=m.institution_id
          and spe.program_id=? and spe.status='ACTIVE'
          and spe.course_access_blocked=false
          and (spe.course_expires_at is null or spe.course_expires_at>statement_timestamp())
        join curriculum_program p on p.id=spe.program_id and p.status='ACTIVE' and p.code<>'GENERAL'
        join institution_course_assignment assignment on assignment.institution_id=m.institution_id
          and assignment.status='ASSIGNED'
        join institution_course_release release on release.id=assignment.course_release_id and release.program_id=p.id
        join curriculum_program_subject cps on cps.program_id=p.id and cps.subject_id=s.id and cps.class_id=s.class_id
        where l.id=? and l.active=true and l.status='PUBLISHED'
          and ch.active=true and ch.content_status='PUBLISHED' and s.active=true and c.active=true
          and (s.program_id=p.id or (s.program_id is null and p.class_id=s.class_id))
        """;
      visibleArgs=new Object[]{studentId,institutionId,courseId,lessonId};
    } else if(institutionId==null){
      visibleSql="""
        select l.id
        from lesson l
        join curriculum_chapter ch on ch.id=l.chapter_id
        join curriculum_subject s on s.id=ch.subject_id
        join curriculum_class c on c.id=s.class_id
        join student st on st.id=? and st.active=true
        join student_track_enrollment ste on ste.student_id=st.id and ste.subject_id=s.id and ste.status='ACTIVE'
        where l.id=? and l.active=true and l.status='PUBLISHED'
          and ch.active=true and ch.content_status='PUBLISHED'
          and (
            (s.program_id is null and c.code=st.class_code)
            or (s.program_id is not null and exists(
              select 1 from student_program_enrollment spe
              where spe.student_id=st.id and spe.program_id=s.program_id and spe.status='ACTIVE'
            ))
          )
        """;
      visibleArgs=new Object[]{studentId,lessonId};
    } else {
      visibleSql="""
        select l.id
        from lesson l
        join curriculum_chapter ch on ch.id=l.chapter_id
        join curriculum_subject s on s.id=ch.subject_id
        join curriculum_class c on c.id=s.class_id
        join student st on st.id=? and st.active=true
        join student_track_enrollment ste on ste.student_id=st.id and ste.subject_id=s.id
          and ste.institution_id=? and ste.status='ACTIVE'
        join institution_membership m on m.student_id=st.id and m.institution_id=ste.institution_id
          and m.status='ACTIVE'
        join institution_tenant tenant_scope on tenant_scope.id=m.institution_id and tenant_scope.status='ACTIVE'
        where l.id=? and l.active=true and l.status='PUBLISHED'
          and ch.active=true and ch.content_status='PUBLISHED'
          and (
            (s.program_id is null and c.code=st.class_code)
            or (s.program_id is not null and exists(
              select 1 from student_program_enrollment spe
              where spe.student_id=st.id and spe.institution_id=? and spe.program_id=s.program_id and spe.status='ACTIVE'
            ))
          )
        """;
      visibleArgs=new Object[]{studentId,institutionId,lessonId,institutionId};
    }
    List<Map<String,Object>> visibility=jdbc.queryForList(visibleSql,visibleArgs);
    if(visibility.isEmpty()) return;

    Long total=jdbc.queryForObject("""
      select count(*) from question
      where lesson_id=? and active=true and review_status in ('APPROVED','PUBLISHED')
        and question_type='MCQ'
    """,Long.class,lessonId);
    Long answered;
    Long completedPractice;
    if(courseId!=null){
      answered=jdbc.queryForObject("""
        select count(distinct qa.question_id)
        from student_question_attempt qa
        join question q on q.id=qa.question_id
        where qa.student_id=? and qa.institution_id=? and qa.course_id=?
          and qa.activity_kind='LESSON_INLINE' and q.lesson_id=? and q.active=true
          and q.review_status in ('APPROVED','PUBLISHED') and q.question_type='MCQ'
      """,Long.class,studentId,institutionId,courseId,lessonId);
      String marker = "[{\"sourceLessonId\":" + lessonId + "}]";
      completedPractice=jdbc.queryForObject("""
        select count(*) from student_practice_session
        where student_id=? and institution_id=? and course_id=? and status='COMPLETED'
          and topic_selections @> ?::jsonb
      """,Long.class,studentId,institutionId,courseId,marker);
    } else if(institutionId==null){
      answered=jdbc.queryForObject("""
        select count(distinct qa.question_id)
        from student_question_attempt qa
        join question q on q.id=qa.question_id
        where qa.student_id=? and qa.course_id is null and qa.activity_kind='LESSON_INLINE'
          and q.lesson_id=? and q.active=true and q.review_status in ('APPROVED','PUBLISHED')
          and q.question_type='MCQ'
      """,Long.class,studentId,lessonId);
      String marker = "[{\"sourceLessonId\":" + lessonId + "}]";
      completedPractice=jdbc.queryForObject("""
        select count(*) from student_practice_session
        where student_id=? and course_id is null and status='COMPLETED'
          and topic_selections @> ?::jsonb
      """,Long.class,studentId,marker);
    } else {
      answered=jdbc.queryForObject("""
        select count(distinct qa.question_id)
        from student_question_attempt qa
        join question q on q.id=qa.question_id
        where qa.student_id=? and qa.institution_id=? and qa.course_id is null
          and qa.activity_kind='LESSON_INLINE' and q.lesson_id=? and q.active=true
          and q.review_status in ('APPROVED','PUBLISHED') and q.question_type='MCQ'
      """,Long.class,studentId,institutionId,lessonId);
      String marker = "[{\"sourceLessonId\":" + lessonId + "}]";
      completedPractice=jdbc.queryForObject("""
        select count(*) from student_practice_session
        where student_id=? and institution_id=? and course_id is null
          and status='COMPLETED' and topic_selections @> ?::jsonb
      """,Long.class,studentId,institutionId,marker);
    }

    long required=total==null?0:total;
    long done=answered==null?0:answered;
    boolean practiceDone=completedPractice!=null&&completedPractice>0;
    boolean complete=done>=required&&practiceDone;
    int progress=complete?100:(required==0?0:(int)Math.min(99,Math.round(done*100.0/required)));
    String status=complete?"COMPLETED":"IN_PROGRESS";
    if(institutionId==null){
      jdbc.update("""
        insert into student_lesson_progress(
          student_id,lesson_id,status,progress_percent,started_at,last_opened_at,completed_at
        ) values(?,?,?,?,now(),now(),case when ?='COMPLETED' then now() else null end)
        on conflict(student_id,lesson_id) where institution_id is null and course_id is null do update set
          status=case when student_lesson_progress.status='COMPLETED' then 'COMPLETED' else excluded.status end,
          progress_percent=case when student_lesson_progress.status='COMPLETED'
            then 100 else excluded.progress_percent end,
          last_opened_at=now(),
          completed_at=case when student_lesson_progress.status='COMPLETED'
            then student_lesson_progress.completed_at else excluded.completed_at end
      """,studentId,lessonId,status,progress,status);
    } else if(courseId==null){
      jdbc.update("""
        insert into student_lesson_progress(
          student_id,institution_id,lesson_id,status,progress_percent,started_at,last_opened_at,completed_at
        ) values(?,?,?,?,?,now(),now(),case when ?='COMPLETED' then now() else null end)
        on conflict(institution_id,student_id,lesson_id)
          where institution_id is not null and course_id is null
        do update set
          status=case when student_lesson_progress.status='COMPLETED' then 'COMPLETED' else excluded.status end,
          progress_percent=case when student_lesson_progress.status='COMPLETED'
            then 100 else excluded.progress_percent end,
          last_opened_at=now(),
          completed_at=case when student_lesson_progress.status='COMPLETED'
            then student_lesson_progress.completed_at else excluded.completed_at end
      """,studentId,institutionId,lessonId,status,progress,status);
    } else {
      jdbc.update("""
        insert into student_lesson_progress(
          student_id,institution_id,course_id,lesson_id,status,progress_percent,started_at,last_opened_at,completed_at
        ) values(?,?,?,?,?,?,now(),now(),case when ?='COMPLETED' then now() else null end)
        on conflict(institution_id,student_id,course_id,lesson_id)
          where institution_id is not null and course_id is not null
        do update set
          status=case when student_lesson_progress.status='COMPLETED' then 'COMPLETED' else excluded.status end,
          progress_percent=case when student_lesson_progress.status='COMPLETED'
            then 100 else excluded.progress_percent end,
          last_opened_at=now(),
          completed_at=case when student_lesson_progress.status='COMPLETED'
            then student_lesson_progress.completed_at else excluded.completed_at end
      """,studentId,institutionId,courseId,lessonId,status,progress,status);
    }
  }

  private void updateMastery(long studentId,long conceptId,Long institutionId,Long courseId){
    Map<String,Object> outcome;
    if(institutionId==null){
      outcome=jdbc.queryForMap("""
        with latest_by_question as (
          select distinct on (q.id) q.id as question_id,qa.correct
          from student_question_attempt qa join question q on q.id=qa.question_id
          where qa.student_id=? and qa.course_id is null and q.concept_id=?
            and qa.activity_kind in ('LESSON_INLINE','PRACTICE') and qa.correct is not null
          order by q.id,qa.answered_at desc,qa.id desc
        )
        select
          (select count(*) from student_question_attempt qa join question q on q.id=qa.question_id
           where qa.student_id=? and qa.course_id is null and q.concept_id=?
             and qa.activity_kind in ('LESSON_INLINE','PRACTICE')) as attempts,
          count(*) as graded_attempts,count(*) filter(where correct=true) as correct_attempts
        from latest_by_question
      """,studentId,conceptId,studentId,conceptId);
    } else if(courseId==null){
      outcome=jdbc.queryForMap("""
        with latest_by_question as (
          select distinct on (q.id) q.id as question_id,qa.correct
          from student_question_attempt qa join question q on q.id=qa.question_id
          where qa.student_id=? and qa.institution_id=? and qa.course_id is null and q.concept_id=?
            and qa.activity_kind in ('LESSON_INLINE','PRACTICE') and qa.correct is not null
          order by q.id,qa.answered_at desc,qa.id desc
        )
        select
          (select count(*) from student_question_attempt qa join question q on q.id=qa.question_id
           where qa.student_id=? and qa.institution_id=? and qa.course_id is null and q.concept_id=?
             and qa.activity_kind in ('LESSON_INLINE','PRACTICE')) as attempts,
          count(*) as graded_attempts,count(*) filter(where correct=true) as correct_attempts
        from latest_by_question
      """,studentId,institutionId,conceptId,studentId,institutionId,conceptId);
    } else {
      outcome=jdbc.queryForMap("""
        with latest_by_question as (
          select distinct on (q.id) q.id as question_id,qa.correct
          from student_question_attempt qa join question q on q.id=qa.question_id
          where qa.student_id=? and qa.institution_id=? and qa.course_id=? and q.concept_id=?
            and qa.activity_kind in ('LESSON_INLINE','PRACTICE') and qa.correct is not null
          order by q.id,qa.answered_at desc,qa.id desc
        )
        select
          (select count(*) from student_question_attempt qa join question q on q.id=qa.question_id
           where qa.student_id=? and qa.institution_id=? and qa.course_id=? and q.concept_id=?
             and qa.activity_kind in ('LESSON_INLINE','PRACTICE')) as attempts,
          count(*) as graded_attempts,count(*) filter(where correct=true) as correct_attempts
        from latest_by_question
      """,studentId,institutionId,courseId,conceptId,studentId,institutionId,courseId,conceptId);
    }
    int attempts=((Number)outcome.get("attempts")).intValue();
    int graded=((Number)outcome.get("graded_attempts")).intValue();
    int correct=((Number)outcome.get("correct_attempts")).intValue();
    int percent=graded==0?0:(int)Math.round(100.0*correct/graded);
    if(institutionId==null){
      jdbc.update("""
        insert into student_concept_mastery(
          student_id,concept_id,mastery_percent,attempts,graded_attempts,correct_attempts,last_attempt_at,updated_at
        ) values(?,?,?,?,?,?,now(),now())
        on conflict(student_id,concept_id)
          where institution_id is null and course_id is null
        do update set attempts=excluded.attempts,graded_attempts=excluded.graded_attempts,
          correct_attempts=excluded.correct_attempts,mastery_percent=excluded.mastery_percent,
          last_attempt_at=now(),updated_at=now()
      """,studentId,conceptId,percent,attempts,graded,correct);
    } else if(courseId==null){
      jdbc.update("""
        insert into student_concept_mastery(
          student_id,institution_id,concept_id,mastery_percent,attempts,graded_attempts,correct_attempts,last_attempt_at,updated_at
        ) values(?,?,?,?,?,?,?,now(),now())
        on conflict(institution_id,student_id,concept_id)
          where institution_id is not null and course_id is null
        do update set attempts=excluded.attempts,graded_attempts=excluded.graded_attempts,
          correct_attempts=excluded.correct_attempts,mastery_percent=excluded.mastery_percent,
          last_attempt_at=now(),updated_at=now()
      """,studentId,institutionId,conceptId,percent,attempts,graded,correct);
    } else {
      jdbc.update("""
        insert into student_concept_mastery(
          student_id,institution_id,course_id,concept_id,mastery_percent,attempts,graded_attempts,correct_attempts,last_attempt_at,updated_at
        ) values(?,?,?,?,?,?,?,?,now(),now())
        on conflict(institution_id,student_id,course_id,concept_id)
          where institution_id is not null and course_id is not null
        do update set attempts=excluded.attempts,graded_attempts=excluded.graded_attempts,
          correct_attempts=excluded.correct_attempts,mastery_percent=excluded.mastery_percent,
          last_attempt_at=now(),updated_at=now()
      """,studentId,institutionId,courseId,conceptId,percent,attempts,graded,correct);
    }
  }

  public Map<String,Object> masterySummary(long studentId){
    return jdbc.queryForMap("""
      select count(*) as concepts,coalesce(round(avg(mastery_percent),1),0) as average_mastery,
             count(*) filter(where mastery_percent>=80) as mastered,
             count(*) filter(where mastery_percent<50) as needs_support
      from student_concept_mastery where student_id=?
      """,studentId);
  }

  public Map<String,Object> masterySummary(long studentId,long institutionId){
    return jdbc.queryForMap("""
      select count(*) as concepts,coalesce(round(avg(mastery_percent),1),0) as average_mastery,
             count(*) filter(where mastery_percent>=80) as mastered,
             count(*) filter(where mastery_percent<50) as needs_support
      from student_concept_mastery where student_id=? and institution_id=?
      """,studentId,institutionId);
  }

  private void ensureStudentLessonAccess(long studentId,long lessonId,Long institutionId,Long courseId){
    if(institutionId==null || courseId==null || courseId<1L)
      throw new SecurityException("An active institution course is required for lesson access.");
    Long count=jdbc.queryForObject("""
      select count(*)
      from lesson l
      join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id and c.active=true
      join student st on st.id=? and st.active=true
      join institution_membership m on m.student_id=st.id and m.institution_id=? and m.status='ACTIVE'
      join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
      join student_program_enrollment spe on spe.student_id=st.id and spe.institution_id=m.institution_id
        and spe.program_id=? and spe.status='ACTIVE'
          and spe.course_access_blocked=false
          and (spe.course_expires_at is null or spe.course_expires_at>statement_timestamp())
      join curriculum_program p on p.id=spe.program_id and p.status='ACTIVE' and p.code<>'GENERAL'
      join institution_course_assignment assignment on assignment.institution_id=m.institution_id
        and assignment.status='ASSIGNED'
      join institution_course_release release on release.id=assignment.course_release_id and release.program_id=p.id
      join curriculum_program_subject cps on cps.program_id=p.id and cps.subject_id=s.id and cps.class_id=s.class_id
      where l.id=? and l.active=true and l.status='PUBLISHED'
        and ch.active=true and ch.content_status='PUBLISHED' and s.active=true
        and (s.program_id=p.id or (s.program_id is null and p.class_id=s.class_id))
    """,Long.class,studentId,institutionId,courseId,lessonId);
    if(count==null || count==0) throw new SecurityException("Lesson is not available in the selected institution course.");
    assertInstitutionCourseEntitlement(studentId,courseId,institutionId);
  }

  private void ensureStudentLessonAccess(long studentId,long lessonId,Long institutionId){
    Long count;
    if(institutionId==null){
      count=jdbc.queryForObject("""
        select count(*) from lesson l
        join curriculum_chapter ch on ch.id=l.chapter_id
        join curriculum_subject s on s.id=ch.subject_id
        join curriculum_class c on c.id=s.class_id
        join student st on st.id=? and st.active=true
        join student_track_enrollment ste on ste.student_id=st.id and ste.subject_id=s.id and ste.status='ACTIVE'
        where l.id=? and l.active=true and l.status='PUBLISHED'
          and ch.active=true and ch.content_status='PUBLISHED'
          and (
            (s.program_id is null and c.code=st.class_code)
            or (s.program_id is not null and exists(
              select 1 from student_program_enrollment spe
              where spe.student_id=st.id and spe.program_id=s.program_id and spe.status='ACTIVE'
            ))
          )
      """,Long.class,studentId,lessonId);
    } else {
      count=jdbc.queryForObject("""
        select count(*) from lesson l
        join curriculum_chapter ch on ch.id=l.chapter_id
        join curriculum_subject s on s.id=ch.subject_id
        join curriculum_class c on c.id=s.class_id
        join student st on st.id=? and st.active=true
        join student_track_enrollment ste on ste.student_id=st.id and ste.subject_id=s.id
          and ste.institution_id=? and ste.status='ACTIVE'
        join institution_membership m on m.student_id=st.id and m.institution_id=ste.institution_id
          and m.status='ACTIVE'
        join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
        where l.id=? and l.active=true and l.status='PUBLISHED'
          and ch.active=true and ch.content_status='PUBLISHED'
          and (
            (s.program_id is null and c.code=st.class_code)
            or (s.program_id is not null and exists(
              select 1 from student_program_enrollment spe
              where spe.student_id=st.id and spe.institution_id=? and spe.program_id=s.program_id and spe.status='ACTIVE'
            ))
          )
      """,Long.class,studentId,institutionId,lessonId,institutionId);
    }
    if(count==null||count==0) throw new SecurityException("Lesson access denied");
    Long programId=jdbc.queryForObject("""
      select coalesce(s.program_id,(
        select class_course.id
        from curriculum_program class_course
        where class_course.class_id=c.id and class_course.code='CLASS_' || c.code
          and class_course.curriculum_version='CURRENT' and class_course.status='ACTIVE'
          and c.code in ('6','7','8')
        limit 1
      ))
      from lesson l join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id join curriculum_class c on c.id=s.class_id
      where l.id=?
      """,Long.class,lessonId);
    if(programId!=null) assertInstitutionCourseEntitlement(studentId,programId,institutionId);
  }

  private void ensurePublishedLesson(long lessonId){
    Long count=jdbc.queryForObject("""
      select count(*) from lesson l join curriculum_chapter ch on ch.id=l.chapter_id
      where l.id=? and l.active=true and l.status='PUBLISHED' and ch.active=true and ch.content_status='PUBLISHED'
      """,Long.class,lessonId);
    if(count==0) throw new IllegalArgumentException("Lesson not found");
  }

  private void ensureStudentCanAnswer(long studentId,long questionId,long lessonId,Long institutionId,Long courseId){
    if(institutionId==null || courseId==null || courseId<1L)
      throw new SecurityException("An active institution course is required to answer this question.");
    Long count=jdbc.queryForObject("""
      select count(*)
      from question q
      join lesson l on l.id=q.lesson_id
      join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id and c.active=true
      join student st on st.id=? and st.active=true
      join institution_membership m on m.student_id=st.id and m.institution_id=? and m.status='ACTIVE'
      join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
      join student_program_enrollment spe on spe.student_id=st.id and spe.institution_id=m.institution_id
        and spe.program_id=? and spe.status='ACTIVE'
          and spe.course_access_blocked=false
          and (spe.course_expires_at is null or spe.course_expires_at>statement_timestamp())
      join curriculum_program p on p.id=spe.program_id and p.status='ACTIVE' and p.code<>'GENERAL'
      join institution_course_assignment assignment on assignment.institution_id=m.institution_id
        and assignment.status='ASSIGNED'
      join institution_course_release release on release.id=assignment.course_release_id and release.program_id=p.id
      join curriculum_program_subject cps on cps.program_id=p.id and cps.subject_id=s.id and cps.class_id=s.class_id
      where q.id=? and q.lesson_id=? and q.active=true and q.review_status in ('APPROVED','PUBLISHED')
        and l.active=true and l.status='PUBLISHED' and ch.active=true and ch.content_status='PUBLISHED'
        and s.active=true and (s.program_id=p.id or (s.program_id is null and p.class_id=s.class_id))
    """,Long.class,studentId,institutionId,courseId,questionId,lessonId);
    if(count==null || count==0) throw new SecurityException("Question is not available in the selected institution course.");
    assertInstitutionCourseEntitlement(studentId,courseId,institutionId);
  }

  private void ensureStudentCanAnswer(long studentId,long questionId,long lessonId,Long institutionId){
    Long count;
    if(institutionId==null){
      count=jdbc.queryForObject("""
        select count(*) from question q
        join lesson l on l.id=q.lesson_id join curriculum_chapter ch on ch.id=l.chapter_id
        join curriculum_subject s on s.id=ch.subject_id join curriculum_class c on c.id=s.class_id
        join student st on st.id=? and st.active=true
        join student_track_enrollment ste on ste.student_id=st.id and ste.subject_id=s.id and ste.status='ACTIVE'
        where q.id=? and q.lesson_id=? and q.active=true and q.review_status in ('APPROVED','PUBLISHED')
          and l.active=true and l.status='PUBLISHED' and ch.active=true and ch.content_status='PUBLISHED'
          and (
            (s.program_id is null and c.code=st.class_code)
            or (s.program_id is not null and exists(
              select 1 from student_program_enrollment spe
              where spe.student_id=st.id and spe.program_id=s.program_id and spe.status='ACTIVE'
            ))
          )
      """,Long.class,studentId,questionId,lessonId);
    } else {
      count=jdbc.queryForObject("""
        select count(*) from question q
        join lesson l on l.id=q.lesson_id join curriculum_chapter ch on ch.id=l.chapter_id
        join curriculum_subject s on s.id=ch.subject_id join curriculum_class c on c.id=s.class_id
        join student st on st.id=? and st.active=true
        join student_track_enrollment ste on ste.student_id=st.id and ste.subject_id=s.id
          and ste.institution_id=? and ste.status='ACTIVE'
        join institution_membership m on m.student_id=st.id and m.institution_id=ste.institution_id and m.status='ACTIVE'
        join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
        where q.id=? and q.lesson_id=? and q.active=true and q.review_status in ('APPROVED','PUBLISHED')
          and l.active=true and l.status='PUBLISHED' and ch.active=true and ch.content_status='PUBLISHED'
          and (
            (s.program_id is null and c.code=st.class_code)
            or (s.program_id is not null and exists(
              select 1 from student_program_enrollment spe
              where spe.student_id=st.id and spe.institution_id=? and spe.program_id=s.program_id and spe.status='ACTIVE'
            ))
          )
      """,Long.class,studentId,institutionId,questionId,lessonId,institutionId);
    }
    if(count==0) throw new SecurityException("Question access denied");
    ensurePublishedLesson(lessonId);
    Long programId=jdbc.queryForObject("""
      select coalesce(s.program_id,(
        select class_course.id from curriculum_program class_course
        where class_course.class_id=c.id and class_course.code='CLASS_' || c.code
          and class_course.curriculum_version='CURRENT' and class_course.status='ACTIVE'
          and c.code in ('6','7','8') limit 1
      ))
      from lesson l join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id join curriculum_class c on c.id=s.class_id
      where l.id=?
      """,Long.class,lessonId);
    if(programId!=null) assertInstitutionCourseEntitlement(studentId,programId,institutionId);
  }

  private void assertInstitutionCourseEntitlement(long studentId,long programId,Long institutionId) {
    if(institutionId==null){
      // This branch exists only for legacy direct Java calls. The AuthFilter requires a tenant
      // on every public learner endpoint in production.
      Boolean hasActiveInstitution=jdbc.queryForObject("""
        select exists(select 1 from institution_membership m
          where m.student_id=? and m.status='ACTIVE')
        """,Boolean.class,studentId);
      if(!Boolean.TRUE.equals(hasActiveInstitution)) return;
      Boolean allowed=jdbc.queryForObject("""
        select
          exists(select 1 from institution_membership m join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
            join institution_course_entitlement e on e.institution_id=m.institution_id
              and e.program_id=? and e.status='ACTIVE' and e.valid_from<=now()
              and (e.valid_until is null or e.valid_until>now())
            where m.student_id=? and m.status='ACTIVE')
          or exists(select 1 from course_purchase cp where cp.student_id=? and cp.course_id=?)
      """,Boolean.class,programId,studentId,studentId,programId);
      if(!Boolean.TRUE.equals(allowed)) throw new SecurityException("This course is not authorised for your institution or direct purchase.");
      return;
    }
    Boolean allowed=jdbc.queryForObject("""
      select exists(
        select 1 from institution_membership m
        join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
        join institution_course_assignment a on a.institution_id=m.institution_id and a.status='ASSIGNED'
        join institution_course_release r on r.id=a.course_release_id and r.program_id=?
          and r.release_state='SHAREABLE' and r.legacy_migration=false
          and a.assignment_source in ('ORIGIN','HRMS')
        where m.student_id=? and m.institution_id=? and m.status='ACTIVE'
      )
      and (
        exists(select 1 from student_program_enrollment e where e.student_id=? and e.institution_id=?
          and e.program_id=? and e.status in ('ACTIVE','PAUSED'))
        or exists(select 1 from course_purchase cp where cp.student_id=? and cp.institution_id=? and cp.course_id=?)
        or exists(select 1 from course_entitlement ce where ce.institution_id=? and ce.course_id=?
          and (ce.holder_student_id=? or ce.holder_user_id=(select user_account_id from student where id=?)))
      )
      """,Boolean.class,programId,studentId,institutionId,
      studentId,institutionId,programId,studentId,institutionId,programId,
      institutionId,programId,studentId,studentId);
    if(!Boolean.TRUE.equals(allowed)) {
      throw new SecurityException("This course is not authorised for the selected institution or learner.");
    }
  }
}
