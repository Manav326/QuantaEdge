package com.quantaedge.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class QuestionHistoryService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public QuestionHistoryService(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  /** Returns the complete editable question snapshot, including its options and answer key. */
  public Map<String,Object> snapshot(long questionId) {
    List<Map<String,Object>> rows = jdbc.queryForList("""
      select q.id,q.lesson_id,q.question_type,q.prompt,q.explanation,q.difficulty,q.sort_order,
             q.active,q.review_status,q.review_notes,q.reviewed_at,q.reviewed_by_staff_id,
             q.marks,q.exam_format,q.source_kind,q.source_title,q.source_ref,q.source_year,
             q.source_id,q.board,q.topic,q.subtopic,q.skill,q.tags::text as tags,
             q.answer_payload::text as answer_payload
      from question q where q.id=?
      """, questionId);
    if (rows.isEmpty()) return Map.of();
    Map<String,Object> snapshot = new LinkedHashMap<>(rows.getFirst());
    snapshot.put("options", jdbc.queryForList("""
      select option_key,label,is_correct,sort_order
      from question_option where question_id=? order by sort_order,option_key
      """, questionId));
    return snapshot;
  }

  public void recordChange(long questionId, long lessonId, String eventType, AuthContext actor,
      String reason, Map<String,Object> before, Map<String,Object> after) {
    if (after == null || after.isEmpty()) return;
    if (before != null && !before.isEmpty() && Objects.equals(json(before), json(after))) return;
    String oldStatus = status(before);
    String newStatus = status(after);
    String resolvedReason = reason;
    if (resolvedReason == null || resolvedReason.isBlank()) {
      Object note = after.get("review_notes");
      resolvedReason = note == null ? null : String.valueOf(note);
    }
    jdbc.update("""
      insert into question_review_history(
        question_id,lesson_id,event_type,previous_status,new_status,actor_staff_id,actor_role,
        change_reason,before_snapshot,after_snapshot,occurred_at
      ) values (?,?,?,?,?,?,?, ?,?::jsonb,?::jsonb,now())
      """, questionId, lessonId,
      oldStatus != null && !Objects.equals(oldStatus, newStatus) ? "STATUS_CHANGED" : eventType,
      oldStatus, newStatus,
      actor == null ? null : actor.staffId(),
      actor == null || actor.role() == null ? null : actor.role(),
      resolvedReason,
      before == null || before.isEmpty() ? null : json(before),
      json(after));
  }

  public List<Map<String,Object>> history(long questionId) {
    return jdbc.queryForList("""
      select id,question_id,lesson_id,event_type,previous_status,new_status,
             actor_staff_id,actor_role,change_reason,before_snapshot::text as before_snapshot,
             after_snapshot::text as after_snapshot,occurred_at
      from question_review_history
      where question_id=?
      order by occurred_at desc,id desc
      """, questionId);
  }

  public String json(Map<String,Object> value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JacksonException ex) {
      throw new IllegalStateException("Could not serialize question history snapshot.", ex);
    }
  }

  private String status(Map<String,Object> snapshot) {
    if (snapshot == null || snapshot.isEmpty() || snapshot.get("review_status") == null) return null;
    return String.valueOf(snapshot.get("review_status")).toUpperCase();
  }
}
