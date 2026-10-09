package com.quantaedge.api;

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
    ensureStudentCanAnswer(studentId,questionId,lessonId);
    if(answer!=null && answer.length()>5000) throw new IllegalArgumentException("Answer is too long");
    String selected=answer!=null&&answer.length()<=20?answer:null;
    jdbc.update("""
      insert into student_question_attempt(student_id,question_id,selected_option,answer_text,correct,answered_at)
      values(?,?,?,?,?,now())
      """,studentId,questionId,selected,answer,correct);

    long total=jdbc.queryForObject("select count(*) from question where lesson_id=? and active=true and review_status in ('APPROVED','PUBLISHED')",Long.class,lessonId);
    long answered=jdbc.queryForObject("""
      select count(distinct question_id) from student_question_attempt
      where student_id=? and question_id in(select id from question where lesson_id=? and active=true and review_status in ('APPROVED','PUBLISHED'))
      """,Long.class,studentId,lessonId);
    int progress=(int)Math.min(100,total==0?0:Math.round(answered*100f/total));
    String status=progress>=100?"COMPLETED":"IN_PROGRESS";
    jdbc.update("""
      insert into student_lesson_progress(student_id,lesson_id,status,progress_percent,started_at,last_opened_at,completed_at)
      values(?,?,?,?,now(),now(),case when ?='COMPLETED' then now() else null end)
      on conflict(student_id,lesson_id) do update set status=excluded.status,progress_percent=excluded.progress_percent,
        last_opened_at=now(),completed_at=case when excluded.status='COMPLETED'
          then coalesce(student_lesson_progress.completed_at,now()) else student_lesson_progress.completed_at end
      """,studentId,lessonId,status,progress,status);

    if(autoGraded&&correct!=null){
      Object concept=jdbc.queryForMap("select concept_id from question where id=?",questionId).get("concept_id");
      if(concept instanceof Number) updateMastery(studentId,((Number)concept).longValue(),correct);
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

  private void updateMastery(long studentId,long conceptId,boolean correct){
    jdbc.update("""
      insert into student_concept_mastery(student_id,concept_id,mastery_percent,attempts,graded_attempts,correct_attempts,last_attempt_at,updated_at)
      values(?,?,case when ? then 100 else 0 end,1,1,case when ? then 1 else 0 end,now(),now())
      on conflict(student_id,concept_id) do update set
        attempts=student_concept_mastery.attempts+1,graded_attempts=student_concept_mastery.graded_attempts+1,
        correct_attempts=student_concept_mastery.correct_attempts+excluded.correct_attempts,
        mastery_percent=least(100,greatest(0,round(100.0*(student_concept_mastery.correct_attempts+excluded.correct_attempts) /
          nullif(student_concept_mastery.graded_attempts+1,0)))),last_attempt_at=now(),updated_at=now()
      """,studentId,conceptId,correct,correct);
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
