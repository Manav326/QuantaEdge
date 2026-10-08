package com.quantaedge.api;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/tutor")
public class TutorAnalyticsController {
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  public TutorAnalyticsController(JdbcTemplate jdbc,AuthorizationService authorization){this.jdbc=jdbc;this.authorization=authorization;}
  @GetMapping("/analytics")
  public Map<String,Object> analytics(@RequestAttribute(value="authContext",required=false) AuthContext context){
    authorization.requireAdmin(context);
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("summary",jdbc.queryForMap("""
      select count(distinct ts.student_id) filter(where tm.created_at>=now()-interval '30 days') as active_students_30d,
             count(*) filter(where tm.role='USER' and tm.created_at>=now()-interval '30 days') as user_messages_30d,
             count(*) filter(where tm.role='ASSISTANT' and tm.created_at>=now()-interval '30 days') as assistant_messages_30d,
             coalesce(sum(tm.input_tokens) filter(where tm.created_at>=now()-interval '30 days'),0) as input_tokens_30d,
             coalesce(sum(tm.output_tokens) filter(where tm.created_at>=now()-interval '30 days'),0) as output_tokens_30d
      from tutor_message tm join tutor_session ts on ts.id=tm.session_id
      """));
    result.put("modes",jdbc.queryForList("select coalesce(mode,'UNKNOWN') as mode,count(*) as messages from tutor_message where role='USER' and created_at>=now()-interval '30 days' group by mode order by messages desc"));
    return result;
  }
}
