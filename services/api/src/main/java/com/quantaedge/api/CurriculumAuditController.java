package com.quantaedge.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/curriculum")
public class CurriculumAuditController {
  private final JdbcTemplate jdbc;

  public CurriculumAuditController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @GetMapping("/audit")
  public Map<String,Object> audit() {
    var result = new LinkedHashMap<String,Object>();
    result.put("expected", Map.of(
      "6/maths",15, "6/science",18,
      "7/maths",16, "7/science",20,
      "8/maths",16, "8/science",19
    ));
    result.put("actual", jdbc.queryForList("""
      select c.code||'/'||s.code as curriculum, count(*) as chapters
      from curriculum_chapter ch
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      where c.code in ('6','7','8') and s.code in ('maths','science')
        and ch.active=true and ch.content_status='PUBLISHED'
      group by c.code,s.code
      order by c.code,s.code
      """));
    result.put("ordered", jdbc.queryForList("""
      select c.code as class_code,s.code as subject_code,
             ch.textbook_chapter_no,ch.teaching_order,ch.code,ch.display_name
      from curriculum_chapter ch
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      where c.code in ('6','7','8') and s.code in ('maths','science')
        and ch.active=true and ch.content_status='PUBLISHED'
      order by c.sort_order,s.sort_order,ch.teaching_order
      """));
    return result;
  }
}
