package com.quantaedge.api;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Shared metric definitions. A selected program scopes course metrics; no selection means legacy curriculum only. */
@Service
public class LearningMetricsService {
  private final JdbcTemplate jdbc;
  public LearningMetricsService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

  public Map<String,Object> statsFor(long studentId) {
    return statsFor(studentId, null);
  }

  public Map<String,Object> statsFor(long studentId, Long programId) {
    String subjectScope = programId == null ? "s.program_id is null" : "s.program_id=?";
    Map<String,Object> result=new LinkedHashMap<>(jdbc.queryForMap("""
      select id,public_id,public_user_id,display_name,class_code,board,language,
             case when profile_image_data_url is not null
               then '/api/v1/students/me/profile-photo?v=' || floor(extract(epoch from profile_updated_at)*1000)::bigint::text
               else null end as profile_image_url,
             city,state,school_name,school_medium,favorite_subject,learning_goal
      from student where id=? and active=true
      """,studentId));

    result.put("lessonStats",jdbc.queryForMap("""
      select count(distinct l.id) as total_lessons,
             count(distinct l.id) filter(where p.status='COMPLETED') as completed_lessons,
             coalesce(round(100.0*count(distinct l.id) filter(where p.status='COMPLETED')/
               nullif(count(distinct l.id),0),1),0) as completion_percent
      from student st
      join student_track_enrollment ste on ste.student_id=st.id and ste.status='ACTIVE'
      join curriculum_subject s on s.id=ste.subject_id and s.active=true
      left join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true and ch.content_status='PUBLISHED'
      left join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
      left join student_lesson_progress p on p.lesson_id=l.id and p.student_id=st.id
      where st.id=? and st.active=true and __SUBJECT_SCOPE__
      """.replace("__SUBJECT_SCOPE__",subjectScope), scopedArgs(programId,studentId)));

    result.put("questionStats",jdbc.queryForMap("""
      select count(*) as attempts,count(*) filter(where qa.correct=true) as correct,
             count(*) filter(where qa.correct is not null) as graded_attempts,
             coalesce(round(100.0*count(*) filter(where qa.correct=true)/
               nullif(count(*) filter(where qa.correct is not null),0),1),0) as accuracy_percent
      from student_question_attempt qa
      join question q on q.id=qa.question_id
      join lesson l on l.id=q.lesson_id
      join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      where qa.student_id=? and qa.activity_kind in ('LESSON_INLINE','PRACTICE')
        and __SUBJECT_SCOPE__
      """.replace("__SUBJECT_SCOPE__",subjectScope), scopedArgs(programId,studentId)));

    String curriculumClassScope = programId == null
        ? "c.code=(select class_code from student where id=? and active=true)"
        : "true";
    Object[] curriculumArgs = programId == null
        ? new Object[]{studentId,studentId,studentId}
        : new Object[]{studentId,studentId,programId};
    result.put("curriculum",jdbc.queryForList("""
      select c.code as class_code,s.code as subject_code,s.display_name as subject_name,
             count(distinct ch.id) as chapters,count(distinct l.id) as lessons,
             count(distinct ch.id) filter(where l.id is not null) as content_ready_chapters,
             count(distinct l.id) filter(where p.status='COMPLETED') as completed,
             coalesce(round(100.0*count(distinct l.id) filter(where p.status='COMPLETED')/
               nullif(count(distinct l.id),0),1),0) as completion_percent
      from curriculum_class c
      join curriculum_subject s on s.class_id=c.id and s.active=true
      join student_track_enrollment ste on ste.subject_id=s.id and ste.student_id=? and ste.status='ACTIVE'
      left join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true and ch.content_status='PUBLISHED'
      left join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
      left join student_lesson_progress p on p.lesson_id=l.id and p.student_id=?
      where c.active=true and __CURRICULUM_CLASS_SCOPE__
        and __SUBJECT_SCOPE__
      group by c.code,s.code,s.display_name,s.sort_order order by s.sort_order
      """.replace("__CURRICULUM_CLASS_SCOPE__",curriculumClassScope)
         .replace("__SUBJECT_SCOPE__",subjectScope), curriculumArgs));

    result.put("mastery",jdbc.queryForMap("""
      select count(*) as concepts,coalesce(round(avg(m.mastery_percent),1),0) as average_mastery,
             count(*) filter(where m.mastery_percent>=80) as mastered,
             count(*) filter(where m.mastery_percent<50) as needs_support
      from student_concept_mastery m
      join chapter_concept cc on cc.id=m.concept_id
      join curriculum_chapter ch on ch.id=cc.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      where m.student_id=? and __SUBJECT_SCOPE__
      """.replace("__SUBJECT_SCOPE__",subjectScope), scopedArgs(programId,studentId)));

    result.put("masteryDetails",jdbc.queryForList("""
      select cc.id as concept_id,cc.title as concept_title,ch.display_name as chapter_name,
             s.code as subject_code,s.display_name as subject_name,m.mastery_percent,m.attempts,m.last_attempt_at
      from student_concept_mastery m
      join chapter_concept cc on cc.id=m.concept_id
      join curriculum_chapter ch on ch.id=cc.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      where m.student_id=? and m.mastery_percent<80 and __SUBJECT_SCOPE__
      order by m.mastery_percent asc,ch.teaching_order,ch.sort_order,cc.concept_order,cc.id limit 30
      """.replace("__SUBJECT_SCOPE__",subjectScope), scopedArgs(programId,studentId)));

    result.put("assessmentStats",jdbc.queryForMap("""
      select count(*) filter(where att.status<>'ABANDONED') as attempts,
             count(*) filter(where att.status in ('AWAITING_REVIEW','GRADED','RELEASED')) as submitted,
             count(*) filter(where att.status='AWAITING_REVIEW') as awaiting_review,
             count(*) filter(where att.status='RELEASED') as released,
             coalesce(round(avg(100.0*att.final_score/nullif(att.max_score,0))
               filter(where att.status='RELEASED' and att.final_score is not null),1),0) as average_released_percent
      from student_assessment_attempt att
      join assessment a on a.id=att.assessment_id
      join curriculum_subject s on s.id=a.subject_id
      where att.student_id=? and __SUBJECT_SCOPE__
      """.replace("__SUBJECT_SCOPE__",subjectScope), scopedArgs(programId,studentId)));

    result.put("assessmentByType",jdbc.queryForList("""
      select a.assessment_type,count(att.id) filter(where att.status<>'ABANDONED') as attempts,
             count(att.id) filter(where att.status='RELEASED') as released,
             coalesce(round(avg(100.0*att.final_score/nullif(att.max_score,0))
               filter(where att.status='RELEASED' and att.final_score is not null),1),0) as average_released_percent
      from assessment a join student_assessment_attempt att on att.assessment_id=a.id and att.student_id=?
      join curriculum_subject s on s.id=a.subject_id
      where __SUBJECT_SCOPE__
      group by a.assessment_type
      order by case a.assessment_type when 'TOPIC_END' then 1 when 'CHAPTER_END' then 2 else 3 end
      """.replace("__SUBJECT_SCOPE__",subjectScope), scopedArgs(programId,studentId)));

    result.put("recentAssessments",jdbc.queryForList("""
      select att.id as attempt_id,att.assessment_id,att.attempt_number,att.status,att.final_score,
             att.auto_score,att.max_score,att.started_at,att.submitted_at,att.released_at,
             a.title as assessment_title,a.assessment_type,a.is_generated,s.code as subject_code,s.display_name as subject_name,
             ch.display_name as chapter_name,l.title as lesson_title
      from student_assessment_attempt att join assessment a on a.id=att.assessment_id
      join curriculum_subject s on s.id=a.subject_id
      left join curriculum_chapter ch on ch.id=a.chapter_id
      left join lesson l on l.id=a.lesson_id
      where att.student_id=? and att.status<>'ABANDONED' and __SUBJECT_SCOPE__
      order by coalesce(att.released_at,att.submitted_at,att.started_at) desc,att.id desc limit 10
      """.replace("__SUBJECT_SCOPE__",subjectScope), scopedArgs(programId,studentId)));

    result.put("recentAttempts",jdbc.queryForList("""
      select qa.answered_at,qa.correct,q.question_type,ch.display_name as chapter_name,
             cc.title as concept_title,qa.activity_kind
      from student_question_attempt qa join question q on q.id=qa.question_id
      left join chapter_concept cc on cc.id=q.concept_id
      join lesson l on l.id=q.lesson_id join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      where qa.student_id=? and qa.activity_kind in ('LESSON_INLINE','PRACTICE')
        and __SUBJECT_SCOPE__
      order by qa.answered_at desc,qa.id desc limit 20
      """.replace("__SUBJECT_SCOPE__",subjectScope), scopedArgs(programId,studentId)));

    // Activity minutes and session counts are intentionally learner-wide; individual learning,
    // mastery and assessment metrics above are scoped to the selected program.
    result.put("sessionStats",jdbc.queryForMap("""
      select
        ((select count(*) from learning_session ls where ls.student_id=? and ls.started_at>=now()-interval '30 days')
         +(select count(*) from student_practice_session ps where ps.student_id=? and ps.status='COMPLETED' and ps.created_at>=now()-interval '30 days')
         +(select count(*) from student_diagnostic_attempt da where da.student_id=? and da.status='SUBMITTED' and da.started_at>=now()-interval '30 days')
         +(select count(*) from student_assessment_attempt att where att.student_id=? and att.status in ('AWAITING_REVIEW','GRADED','RELEASED') and att.started_at>=now()-interval '30 days')) as sessions_30d,
        ((select coalesce(sum(ls.minutes),0) from learning_session ls where ls.student_id=? and ls.started_at>=now()-interval '30 days')
         +(select coalesce(sum(least(240,greatest(0,ceil(extract(epoch from (ps.completed_at-ps.created_at))/60)::int))),0)
           from student_practice_session ps where ps.student_id=? and ps.status='COMPLETED' and ps.completed_at is not null
             and ps.created_at>=now()-interval '30 days')
         +(select coalesce(sum(least(240,greatest(0,ceil(extract(epoch from (da.submitted_at-da.started_at))/60)::int))),0)
           from student_diagnostic_attempt da where da.student_id=? and da.status='SUBMITTED' and da.started_at>=now()-interval '30 days')
         +(select coalesce(sum(least(240,greatest(0,ceil(extract(epoch from (att.submitted_at-att.started_at))/60)::int))),0)
           from student_assessment_attempt att where att.student_id=? and att.status in ('AWAITING_REVIEW','GRADED','RELEASED')
             and att.submitted_at is not null and att.started_at>=now()-interval '30 days')) as minutes_30d
      """,studentId,studentId,studentId,studentId,studentId,studentId,studentId,studentId));
    return result;
  }

  private Object[] scopedArgs(Long programId,Object... args) {
    if(programId==null) return args;
    Object[] result=Arrays.copyOf(args,args.length+1);
    result[result.length-1]=programId;
    return result;
  }
}
