package com.quantaedge.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/analytics")
public class AdminAnalyticsController {
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;

  public AdminAnalyticsController(JdbcTemplate jdbc,AuthorizationService authorization){
    this.jdbc=jdbc;
    this.authorization=authorization;
  }

  @GetMapping
  public Map<String,Object> overview(@RequestAttribute(value="authContext",required=false) AuthContext context){
    authorization.requireAdmin(context);
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("summary",jdbc.queryForMap("""
      select
        count(distinct ls.student_id) filter(where ls.started_at>=now()-interval '30 days') as active_students_30d,
        coalesce(round(avg(ls.minutes) filter(where ls.ended_at is not null and ls.started_at>=now()-interval '30 days'),1),0) as avg_session_minutes,
        count(*) filter(where ls.started_at>=now()-interval '30 days') as sessions_30d
      from learning_session ls
      """));
    result.put("learning",jdbc.queryForMap("""
      select
        count(*) as attempts_30d,
        count(*) filter(where qa.correct=true) as correct_30d,
        coalesce(round(100.0*count(*) filter(where qa.correct=true)/
          nullif(count(*) filter(where qa.correct is not null),0),1),0) as accuracy_30d
      from student_question_attempt qa
      where qa.answered_at>=now()-interval '30 days'
      """));
    result.put("daily",jdbc.queryForList("""
      select d::date as day,
             coalesce(s.sessions,0) as sessions,
             coalesce(a.attempts,0) as attempts,
             coalesce(a.accuracy,0) as accuracy
      from generate_series(current_date-interval '13 days',current_date,interval '1 day') d
      left join (
        select started_at::date as day,count(*) as sessions
        from learning_session group by started_at::date
      ) s on s.day=d::date
      left join (
        select answered_at::date as day,count(*) as attempts,
               round(100.0*count(*) filter(where correct=true)/
                 nullif(count(*) filter(where correct is not null),0),1) as accuracy
        from student_question_attempt group by answered_at::date
      ) a on a.day=d::date
      order by day
      """));
    result.put("mastery",jdbc.queryForList("""
      select c.code as class_code,s.code as subject_code,
             count(*) as concepts,
             coalesce(round(avg(m.mastery_percent),1),0) as average_mastery
      from student_concept_mastery m
      join chapter_concept cc on cc.id=m.concept_id
      join curriculum_chapter ch on ch.id=cc.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      group by c.code,s.code order by c.code,s.code
      """));
    return result;
  }
}
