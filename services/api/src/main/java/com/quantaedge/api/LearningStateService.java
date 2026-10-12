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

  @Transactional
  public void startLesson(long studentId,long lessonId){
    ensureStudentLessonAccess(studentId,lessonId);
    jdbc.update("""
      insert into student_lesson_progress(student_id,lesson_id,status,progress_percent,started_at,last_opened_at)
      values(?,?,'IN_PROGRESS',0,now(),now())
      on conflict(student_id,lesson_id) do update set
        status=case when student_lesson_progress.status='COMPLETED' then 'COMPLETED' else 'IN_PROGRESS' end,
        started_at=coalesce(student_lesson_progress.started_at,now()),last_opened_at=now()
      """,studentId,lessonId);
  }

  @Transactional
  public void recordAttempt(long studentId,long questionId,long lessonId,String answer,boolean autoGraded,Boolean correct){
    recordAttempt(studentId,questionId,lessonId,answer,autoGraded,correct,null,"LESSON_INLINE");
  }

  @Transactional
  public void recordAttempt(long studentId,long questionId,long lessonId,String answer,boolean autoGraded,Boolean correct,Long practiceSessionId){
    recordAttempt(studentId,questionId,lessonId,answer,autoGraded,correct,practiceSessionId,
        practiceSessionId==null?"LESSON_INLINE":"PRACTICE");
  }

  @Transactional
  public void recordAttempt(long studentId,long questionId,long lessonId,String answer,boolean autoGraded,
      Boolean correct,Long practiceSessionId,String activityKind){
    ensureStudentCanAnswer(studentId,questionId,lessonId);
    if(answer!=null && answer.length()>5000) throw new IllegalArgumentException("Answer is too long");
    String source=activityKind==null?"LESSON_INLINE":activityKind.trim().toUpperCase(java.util.Locale.ROOT);
    if(!List.of("LESSON_INLINE","PRACTICE","DIAGNOSTIC").contains(source))
      throw new IllegalArgumentException("Invalid learning activity source");
    if(practiceSessionId!=null) {
      List<Map<String,Object>> membership=jdbc.queryForList("""
        select psq.question_id
        from student_practice_session_question psq
        join student_practice_session ps on ps.id=psq.practice_session_id
        where ps.id=? and ps.student_id=? and psq.question_id=?
          and ps.status='IN_PROGRESS' and psq.answered_at is null
        for update of psq,ps
        """,practiceSessionId,studentId,questionId);
      if(membership.isEmpty())throw new SecurityException("Question is not assigned to this active practice session or was already answered.");
      if(!"PRACTICE".equals(source)) throw new IllegalArgumentException("Practice-session answers must use the PRACTICE source");
    }
    String selected=answer!=null&&answer.length()<=20?answer:null;
    jdbc.update("""
      insert into student_question_attempt(
        student_id,question_id,selected_option,answer_text,correct,answered_at,practice_session_id,activity_kind
      ) values(?,?,?,?,?,now(),?,?)
      """,studentId,questionId,selected,answer,correct,practiceSessionId,source);

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
    }

    // Completion means the learner has finished the topic workflow; accuracy and mastery are
    // reported separately. Both the inline MCQs and a completed practice session are required.
    // This prevents practice/diagnostic answers from falsely completing an unrelated lesson.
    if(!"DIAGNOSTIC".equals(source)) {
      syncLessonProgress(studentId,lessonId);
      if(practiceSessionId!=null) {
        List<Map<String,Object>> sourceLessons=jdbc.queryForList("""
          select (item->>'sourceLessonId')::bigint as lesson_id
          from student_practice_session ps
          cross join lateral jsonb_array_elements(ps.topic_selections) item
          where ps.id=? and ps.student_id=? and item ? 'sourceLessonId'
          """,practiceSessionId,studentId);
        for(Map<String,Object> row:sourceLessons) {
          Object sourceLesson=row.get("lesson_id");
          if(sourceLesson instanceof Number n) syncLessonProgress(studentId,n.longValue());
        }
      }
    }

    if(autoGraded&&correct!=null&&!"DIAGNOSTIC".equals(source)){
      Object concept=jdbc.queryForMap("select concept_id from question where id=?",questionId).get("concept_id");
      if(concept instanceof Number) updateMastery(studentId,((Number)concept).longValue());
    }
  }

  private void syncLessonProgress(long studentId,long lessonId){
    List<Map<String,Object>> visibility=jdbc.queryForList("""
      select l.id
      from lesson l
      join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      join student st on st.id=? and st.active=true and st.class_code=c.code
      join student_track_enrollment ste on ste.student_id=st.id and ste.subject_id=s.id and ste.status='ACTIVE'
      where l.id=? and l.active=true and l.status='PUBLISHED'
        and ch.active=true and ch.content_status='PUBLISHED'
    """,studentId,lessonId);
    if(visibility.isEmpty()) return;

    Long total=jdbc.queryForObject("""
      select count(*) from question
      where lesson_id=? and active=true and review_status in ('APPROVED','PUBLISHED')
        and question_type='MCQ'
    """,Long.class,lessonId);
    Long answered=jdbc.queryForObject("""
      select count(distinct qa.question_id)
      from student_question_attempt qa
      join question q on q.id=qa.question_id
      where qa.student_id=? and qa.activity_kind='LESSON_INLINE'
        and q.lesson_id=? and q.active=true and q.review_status in ('APPROVED','PUBLISHED')
        and q.question_type='MCQ'
    """,Long.class,studentId,lessonId);
    String marker="[{\"sourceLessonId\":"+lessonId+"}]";
    Long completedPractice=jdbc.queryForObject("""
      select count(*) from student_practice_session
      where student_id=? and status='COMPLETED' and topic_selections @> ?::jsonb
    """,Long.class,studentId,marker);

    long required=total==null?0:total;
    long done=answered==null?0:answered;
    boolean practiceDone=completedPractice!=null&&completedPractice>0;
    boolean complete=done>=required&&practiceDone;
    int progress=complete?100:(required==0?0:(int)Math.min(99,Math.round(done*100.0/required)));
    String status=complete?"COMPLETED":"IN_PROGRESS";
    jdbc.update("""
      insert into student_lesson_progress(
        student_id,lesson_id,status,progress_percent,started_at,last_opened_at,completed_at
      ) values(?,?,?,?,now(),now(),case when ?='COMPLETED' then now() else null end)
      on conflict(student_id,lesson_id) do update set
        status=case when student_lesson_progress.status='COMPLETED' then 'COMPLETED' else excluded.status end,
        progress_percent=case when student_lesson_progress.status='COMPLETED'
          then 100 else excluded.progress_percent end,
        last_opened_at=now(),
        completed_at=case when student_lesson_progress.status='COMPLETED'
          then student_lesson_progress.completed_at else excluded.completed_at end
    """,studentId,lessonId,status,progress,status);
  }

  private void updateMastery(long studentId,long conceptId){
    Map<String,Object> outcome=jdbc.queryForMap("""
      with latest_by_question as (
        select distinct on (q.id) q.id as question_id,qa.correct
        from student_question_attempt qa
        join question q on q.id=qa.question_id
        where qa.student_id=? and q.concept_id=?
          and qa.activity_kind in ('LESSON_INLINE','PRACTICE') and qa.correct is not null
        order by q.id,qa.answered_at desc,qa.id desc
      )
      select
        (select count(*) from student_question_attempt qa join question q on q.id=qa.question_id
         where qa.student_id=? and q.concept_id=? and qa.activity_kind in ('LESSON_INLINE','PRACTICE')) as attempts,
        count(*) as graded_attempts,
        count(*) filter(where correct=true) as correct_attempts
      from latest_by_question
    """,studentId,conceptId,studentId,conceptId);
    int attempts=((Number)outcome.get("attempts")).intValue();
    int graded=((Number)outcome.get("graded_attempts")).intValue();
    int correct=((Number)outcome.get("correct_attempts")).intValue();
    int percent=graded==0?0:(int)Math.round(100.0*correct/graded);
    jdbc.update("""
      insert into student_concept_mastery(
        student_id,concept_id,mastery_percent,attempts,graded_attempts,correct_attempts,last_attempt_at,updated_at
      ) values(?,?,?,?,?,?,now(),now())
      on conflict(student_id,concept_id) do update set
        attempts=excluded.attempts,graded_attempts=excluded.graded_attempts,
        correct_attempts=excluded.correct_attempts,mastery_percent=excluded.mastery_percent,
        last_attempt_at=now(),updated_at=now()
    """,studentId,conceptId,percent,attempts,graded,correct);
  }

  public Map<String,Object> masterySummary(long studentId){
    return jdbc.queryForMap("""
      select count(*) as concepts,coalesce(round(avg(mastery_percent),1),0) as average_mastery,
             count(*) filter(where mastery_percent>=80) as mastered,
             count(*) filter(where mastery_percent<50) as needs_support
      from student_concept_mastery where student_id=?
      """,studentId);
  }

  private void ensureStudentLessonAccess(long studentId,long lessonId){
    Long count=jdbc.queryForObject("""
      select count(*) from lesson l
      join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      join student st on st.id=?
      where l.id=? and l.active=true and l.status='PUBLISHED'
        and ch.active=true and ch.content_status='PUBLISHED'
        and c.code=st.class_code and st.active=true
      """,Long.class,studentId,lessonId);
    if(count==null||count==0) throw new SecurityException("Lesson access denied");
  }

  private void ensurePublishedLesson(long lessonId){
    Long count=jdbc.queryForObject("""
      select count(*) from lesson l join curriculum_chapter ch on ch.id=l.chapter_id
      where l.id=? and l.active=true and l.status='PUBLISHED' and ch.active=true and ch.content_status='PUBLISHED'
      """,Long.class,lessonId);
    if(count==0) throw new IllegalArgumentException("Lesson not found");
  }

  private void ensureStudentCanAnswer(long studentId,long questionId,long lessonId){
    Long count=jdbc.queryForObject("""
      select count(*) from question q
      join lesson l on l.id=q.lesson_id
      join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      join student st on st.id=?
      where q.id=? and q.lesson_id=? and q.active=true
        and q.review_status in ('APPROVED','PUBLISHED')
        and l.active=true and l.status='PUBLISHED'
        and ch.active=true and ch.content_status='PUBLISHED'
        and st.active=true and c.code=st.class_code
      """,Long.class,studentId,questionId,lessonId);
    if(count==0) throw new SecurityException("Question access denied");
    ensurePublishedLesson(lessonId);
  }
}
