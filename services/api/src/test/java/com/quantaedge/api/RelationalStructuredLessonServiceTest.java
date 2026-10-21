package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(MockitoExtension.class)
class RelationalStructuredLessonServiceTest {
  @Mock private JdbcTemplate jdbc;
  private ObjectMapper mapper;
  private RelationalStructuredLessonService service;

  @BeforeEach
  void setUp() {
    mapper = new ObjectMapper();
    service = new RelationalStructuredLessonService(jdbc, mapper);
  }

  @Test
  void adapterKeepsMatchingAndOrderingInteractionsWithoutExposingAnswerKeys() throws Exception {
    List<Map<String, Object>> rows = List.of(
        questionRow(101L, "ORDER",
            "[{\"key\":\"A\",\"label\":\"Step A\"},{\"key\":\"B\",\"label\":\"Step B\"},{\"key\":\"C\",\"label\":\"Step C\"}]",
            "{\"kind\":\"ORDER\",\"value\":[\"C\",\"A\",\"B\"]}"),
        questionRow(102L, "MATCH",
            "[{\"key\":\"L1\",\"label\":\"Left one\"},{\"key\":\"L2\",\"label\":\"Left two\"},{\"key\":\"R1\",\"label\":\"Right one\"},{\"key\":\"R2\",\"label\":\"Right two\"}]",
            "{\"kind\":\"MATCH\",\"value\":{\"L1\":\"R2\",\"L2\":\"R1\"}}")
    );
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(rows);

    ArrayNode output = service.questions(9L);

    assertEquals(2, output.size());
    JsonNode ordering = output.get(0);
    assertEquals("ORDER", ordering.path("legacy_interaction_type").asText());
    assertEquals("short_answer", ordering.path("type").asText(),
        "The authored question schema stays unchanged; the adapter uses a compatible interaction extension.");
    assertEquals(3, ordering.path("options").size());
    assertEquals("A", ordering.path("options").get(0).path("id").asText(),
        "The expected sequence must not be leaked through option order.");
    JsonNode matching = output.get(1);
    assertEquals("MATCH", matching.path("legacy_interaction_type").asText());
    assertEquals("matching", matching.path("type").asText());
    assertEquals(2, matching.path("left_items").size());
    assertEquals(2, matching.path("right_items").size());

    for (JsonNode question : output) {
      assertFalse(question.has("answer_payload"));
      assertFalse(question.has("correct_pairs"));
      assertFalse(question.has("correct_answer"));
    }
  }

  @Test
  void responseValidationAcceptsCompleteMatchingAndOrderingShapesOnly() throws Exception {
    ObjectNode payload = mapper.createObjectNode();
    ArrayNode questions = payload.putObject("practice").putArray("questions");
    ObjectNode order = questions.addObject();
    order.put("id", "db-q-101");
    order.put("type", "short_answer");
    order.put("legacy_interaction_type", "ORDER");
    ArrayNode orderOptions = order.putArray("options");
    for (String id : List.of("A", "B", "C")) orderOptions.addObject().put("id", id);

    ObjectNode match = questions.addObject();
    match.put("id", "db-q-102");
    match.put("type", "matching");
    match.put("legacy_interaction_type", "MATCH");
    ArrayNode left = match.putArray("left_items");
    left.addObject().put("id", "L1");
    left.addObject().put("id", "L2");
    ArrayNode right = match.putArray("right_items");
    right.addObject().put("id", "R1");
    right.addObject().put("id", "R2");

    RelationalStructuredLessonService.LoadedLesson loaded =
        new RelationalStructuredLessonService.LoadedLesson(9L, 20L, "db-lesson-9", "sha", payload);

    assertDoesNotThrow(() -> service.validateStudentQuestionResponse(
        loaded, "db-q-101", List.of("B", "A", "C")));
    assertThrows(IllegalArgumentException.class, () -> service.validateStudentQuestionResponse(
        loaded, "db-q-101", List.of("A", "A", "C")));
    assertDoesNotThrow(() -> service.validateStudentQuestionResponse(
        loaded, "db-q-102", Map.of("L1", "R2", "L2", "R1")));
    assertThrows(IllegalArgumentException.class, () -> service.validateStudentQuestionResponse(
        loaded, "db-q-102", Map.of("L1", "R2", "L2", "R2")));
  }

  private Map<String, Object> questionRow(long id, String type, String options, String answerPayload) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", id);
    row.put("question_type", type);
    row.put("prompt_en", "Prompt " + id);
    row.put("prompt_hi", "प्रश्न " + id);
    row.put("explanation_en", "");
    row.put("explanation_hi", "");
    row.put("options_en", options);
    row.put("options_hi", options);
    row.put("answer_payload", answerPayload);
    return row;
  }
}
