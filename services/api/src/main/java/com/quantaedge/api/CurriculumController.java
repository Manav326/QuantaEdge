package com.quantaedge.api;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/curriculum")
public class CurriculumController {
  private final JdbcTemplate jdbc;

  public CurriculumController(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @GetMapping
  public List<Map<String, Object>> curriculum() {
    return jdbc.queryForList("""
      select c.code as class_code, c.display_name as class_name,
             s.code as subject_code, s.display_name as subject_name,
             ch.code as chapter_code, ch.display_name as chapter_name,
             ch.description as chapter_description
      from curriculum_class c
      join curriculum_subject s on s.class_id = c.id and s.active = true
      left join curriculum_chapter ch on ch.subject_id = s.id and ch.active = true
      where c.active = true
      order by c.sort_order, s.sort_order, ch.sort_order
      """);
  }
}