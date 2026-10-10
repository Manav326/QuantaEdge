package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class QuestionHistoryServiceTest {
  @Mock private JdbcTemplate jdbc;
  @Mock private ObjectMapper mapper;

  @Test
  void statusTransitionsAreStoredWithBeforeAndAfterSnapshotsAndReviewerReason() throws Exception {
    QuestionHistoryService service = new QuestionHistoryService(jdbc, mapper);
    AuthContext reviewer = new AuthContext(100L, null, 99L, "ADMIN", "Reviewer");
    String reason = "Correct answer verified after the author corrected the options.";
    Map<String, Object> before = Map.of("id", 7L, "review_status", "REJECTED", "review_notes", "Wrong answer");
    Map<String, Object> after = Map.of("id", 7L, "review_status", "APPROVED", "review_notes", reason);
    String beforeJson = "{\"review_status\":\"REJECTED\"}";
    String afterJson = "{\"review_status\":\"APPROVED\"}";

    when(mapper.writeValueAsString(before)).thenReturn(beforeJson);
    when(mapper.writeValueAsString(after)).thenReturn(afterJson);

    assertDoesNotThrow(() -> service.recordChange(
        7L, 11L, "REVIEW_DECISION", reviewer, reason, before, after));

    verify(jdbc).update(
        contains("insert into question_review_history"),
        eq(7L), eq(11L), eq("STATUS_CHANGED"), eq("REJECTED"), eq("APPROVED"),
        eq(99L), eq("ADMIN"), eq(reason), eq(beforeJson), eq(afterJson));
  }

  @Test
  void identicalSnapshotsDoNotCreateNoiseInTheHistory() throws Exception {
    QuestionHistoryService service = new QuestionHistoryService(jdbc, mapper);
    Map<String, Object> snapshot = Map.of("id", 7L, "review_status", "DRAFT");
    when(mapper.writeValueAsString(snapshot)).thenReturn("{\"id\":7,\"review_status\":\"DRAFT\"}");

    service.recordChange(7L, 11L, "CONTENT_CHANGED", null, "No effective change", snapshot, snapshot);

    verifyNoInteractions(jdbc);
  }
}
