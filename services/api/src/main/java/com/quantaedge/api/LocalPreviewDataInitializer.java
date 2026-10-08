package com.quantaedge.api;

import java.util.UUID;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.demo-seed", havingValue = "true")
public class LocalPreviewDataInitializer implements CommandLineRunner {
  private final JdbcTemplate jdbc;

  public LocalPreviewDataInitializer(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void run(String... args) {
    jdbc.update("delete from student where environment='LOCAL_PREVIEW'");

    Long studentId = jdbc.queryForObject("""
      insert into student(public_id, display_name, class_code, board, language, environment)
      values (?, 'आर्यन', '7', 'Bihar Board', 'hi', 'LOCAL_PREVIEW')
      returning id
      """, Long.class, UUID.fromString("7f4c7e1a-1b21-4db9-a6f6-9d4a1f1e7a31"));

    jdbc.update("""
      insert into student_lesson_progress(
        student_id, lesson_id, status, progress_percent, started_at, completed_at, last_opened_at
      )
      select ?, id, 'COMPLETED', 100, now(), now(), now()
      from lesson
      where active=true and status='PUBLISHED'
      """, studentId);

    jdbc.update("""
      insert into student_question_attempt(student_id, question_id, selected_option, correct, answered_at)
      select ?, q.id, qo.option_key, true, now()
      from question q
      join question_option qo on qo.question_id=q.id and qo.is_correct=true
      join lesson l on l.id=q.lesson_id and l.active=true and l.status='PUBLISHED'
      where not exists (
        select 1 from student_question_attempt a
        where a.student_id=? and a.question_id=q.id
      )
      """, studentId, studentId);
  }
}
