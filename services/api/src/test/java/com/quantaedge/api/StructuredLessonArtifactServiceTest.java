package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class StructuredLessonArtifactServiceTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void draftArtifactIsNeverServedToStudents() throws Exception {
    StructuredLessonArtifactService service =
        new StructuredLessonArtifactService(mapper, fixture(false, true));
    assertThrows(ResponseStatusException.class,
        () -> service.requirePublished("qe-test-topic-v1"));
  }

  @Test
  void publicationRequiresEveryReviewGate() throws Exception {
    String json = fixture(true, true).replace(
        "\"bilingual_parity_status\":\"APPROVED\"",
        "\"bilingual_parity_status\":\"PENDING\"");
    StructuredLessonArtifactService service = new StructuredLessonArtifactService(mapper, json);
    assertThrows(ResponseStatusException.class,
        () -> service.requirePublished("qe-test-topic-v1"));
  }

  @Test
  void learnerPayloadRemovesAnswerKeysAndServerCanGradeSeparately() throws Exception {
    StructuredLessonArtifactService service =
        new StructuredLessonArtifactService(mapper, fixture(true, true));
    var artifact = service.requirePublished("qe-test-topic-v1");
    JsonNode payloadQuestion = service.toStudentPayload(artifact).path("practice").path("questions").get(0);
    assertFalse(payloadQuestion.has("correct_answer_ids"));
    assertFalse(payloadQuestion.has("feedback_by_locale"));

    JsonNode sourceQuestion = service.questionForStudent(artifact, "q-test-1");
    JsonNode response = service.validateResponse(sourceQuestion, "option-a");
    var evaluation = service.evaluateResponse(sourceQuestion, response, "en");
    assertEquals("correct", evaluation.status());
    assertEquals(1, evaluation.score());
    assertEquals(1, evaluation.maxScore());
    assertEquals(true, evaluation.autoGraded());
  }

  @Test
  void publishedCatalogExcludesDraftsAndIncludesApprovedArtifacts() throws Exception {
    var published = new StructuredLessonArtifactService(mapper, fixture(true, true));
    var draft = new StructuredLessonArtifactService(mapper, fixture(false, true));
    assertEquals(1, published.listPublishedArtifacts().size());
    assertEquals(0, draft.listPublishedArtifacts().size());
  }

  @Test
  void currentInteractiveStepSchemaIsAcceptedAndActivityIdsRemainStringKeys() throws Exception {
    ObjectNode root = (ObjectNode) mapper.readTree(fixture(true, true));
    ArrayNode blocks = (ArrayNode) root.path("explain").path("blocks");

    ObjectNode interactive = blocks.addObject();
    interactive.put("id", "c6-sci-ch01-t01-b05");
    interactive.put("type", "interactive");
    ObjectNode configuration = interactive.putObject("configuration");
    configuration.put("input_mode", "free_text_and_choice");
    ArrayNode steps = configuration.putArray("steps");
    ObjectNode observe = steps.addObject();
    observe.put("id", "step-observe");
    observe.set("instruction_by_locale", bilingual("Observe one item.", "एक वस्तु का अवलोकन करें।"));
    ObjectNode question = steps.addObject();
    question.put("id", "step-question");
    question.set("instruction_by_locale", bilingual("Write one question.", "एक प्रश्न लिखें।"));

    ObjectNode activity = blocks.addObject();
    activity.put("id", "c6-sci-ch03-t01-b04");
    activity.put("type", "activity");
    activity.set("instructions_by_locale", bilingual("Record observations.", "अवलोकन दर्ज करें।"));

    StructuredLessonArtifactService service =
        new StructuredLessonArtifactService(mapper, mapper.writeValueAsString(root));
    var artifact = service.requirePublished("qe-test-topic-v1");
    assertEquals(6, service.classNumber(artifact));
    assertEquals("science", service.subjectCode(artifact));
    assertEquals("c6-sci-ch01-t01-b05",
        service.activityBlockForStudent(artifact, "c6-sci-ch01-t01-b05").path("id").asText());
  }

  @Test
  void invalidOptionIdIsRejectedBeforeGrading() throws Exception {
    StructuredLessonArtifactService service =
        new StructuredLessonArtifactService(mapper, fixture(true, true));
    var artifact = service.requirePublished("qe-test-topic-v1");
    JsonNode question = service.questionForStudent(artifact, "q-test-1");
    assertThrows(IllegalArgumentException.class,
        () -> service.validateResponse(question, "not-an-option"));
    assertThrows(IllegalArgumentException.class,
        () -> service.validateResponse(question, ""));
  }


  @Test
  void allEightRegisteredQuestionTypesHaveExplicitServerResponsePolicies() throws Exception {
    StructuredLessonArtifactService service =
        new StructuredLessonArtifactService(mapper, fixture(true, true));

    assertEvaluation(service, """
        {"type":"multiple_choice","options":[{"id":"a"},{"id":"b"}],
         "correct_answer_ids":["a"],"feedback_by_locale":{"en":"Correct","hi":"सही"}}
        """, "a", "correct", 1, 1, true);
    assertEvaluation(service, """
        {"type":"multiple_select","options":[{"id":"a"},{"id":"b"}],
         "correct_answer_ids":["a","b"],"feedback_by_locale":{"en":"Correct","hi":"सही"}}
        """, List.of("a", "b"), "correct", 1, 1, true);
    assertEvaluation(service, """
        {"type":"true_false","correct_answer":true,"feedback_by_locale":{"en":"Correct","hi":"सही"}}
        """, true, "correct", 1, 1, true);
    assertEvaluation(service, """
        {"type":"matching","left_items":[{"id":"l1"},{"id":"l2"}],
         "right_items":[{"id":"r1"},{"id":"r2"}],
         "correct_pairs":[{"left_id":"l1","right_id":"r1"},{"left_id":"l2","right_id":"r2"}],
         "feedback_by_locale":{"en":"Correct","hi":"सही"}}
        """, Map.of("l1", "r1", "l2", "r2"), "correct", 2, 2, true);
    assertEvaluation(service, """
        {"type":"fill_blank","accepted_answers":{"en":["water"],"hi":["जल"]},
         "feedback_by_locale":{"en":"Correct","hi":"सही"}}
        """, "  Water  ", "correct", 1, 1, true);
    assertEvaluation(service, """
        {"type":"image_observation","options":[{"id":"a"},{"id":"b"}],
         "correct_answer_ids":["b"],"feedback_by_locale":{"en":"Correct","hi":"सही"}}
        """, "b", "correct", 1, 1, true);
    assertEvaluation(service, """
        {"type":"short_answer","feedback_by_locale":{"en":"Saved for review","hi":"जाँच के लिए सहेजा गया"}}
        """, "A reasoned observation.", "pending_review", null, null, false);
    assertEvaluation(service, """
        {"type":"scenario_based","options":[{"id":"a"},{"id":"b"}],
         "correct_answer_ids":["b"],"feedback_by_locale":{"en":"Correct","hi":"सही"}}
        """, "b", "correct", 1, 1, true);
  }

  @Test
  void activityBlocksAreAcceptedAndRemainAvailableToTheActivityResponseAdapter() throws Exception {
    String json = fixture(true, true).replace(
        "\"blocks\":[]",
        "\"blocks\":[{\"id\":\"activity-test-1\",\"type\":\"activity\",\"order\":1,"
            + "\"instructions_by_locale\":{\"en\":\"Record observations\",\"hi\":\"अवलोकन दर्ज करें\"}}]");
    StructuredLessonArtifactService service = new StructuredLessonArtifactService(mapper, json);
    var artifact = service.requirePublished("qe-test-topic-v1");
    assertEquals("activity",
        service.activityBlockForStudent(artifact, "activity-test-1").path("type").asText());
  }

  private void assertEvaluation(StructuredLessonArtifactService service, String questionJson,
      Object response, String expectedStatus, Integer expectedScore, Integer expectedMaxScore,
      boolean expectedAutoGraded) throws Exception {
    JsonNode question = mapper.readTree(questionJson);
    JsonNode normalized = service.validateResponse(question, response);
    var evaluation = service.evaluateResponse(question, normalized, "en");
    assertEquals(expectedStatus, evaluation.status());
    assertEquals(expectedScore, evaluation.score());
    assertEquals(expectedMaxScore, evaluation.maxScore());
    assertEquals(expectedAutoGraded, evaluation.autoGraded());
  }

  private String fixture(boolean published, boolean validAnswer) throws Exception {
    ObjectNode root = mapper.createObjectNode();
    root.put("schema_version", "quantaedge.structured-lesson.v1");
    root.put("artifact_type", "draft_lesson");
    root.put("artifact_id", "qe-test-topic-v1");

    ObjectNode workflow = root.putObject("workflow");
    workflow.put("status", published ? "PUBLISHED" : "DRAFT_GENERATED_NOT_REVIEWED");
    workflow.put("publication_allowed", published);
    workflow.put("content_review_status", "APPROVED");
    workflow.put("source_completeness_status", "APPROVED");
    workflow.put("bilingual_parity_status", "APPROVED");
    workflow.put("scientific_review_status", "APPROVED");
    workflow.put("visual_review_status", "APPROVED");

    ObjectNode curriculum = root.putObject("curriculum");
    curriculum.put("class", 6);
    curriculum.put("subject", "Science");
    ObjectNode chapter = root.putObject("chapter");
    chapter.put("chapter_number", 1);
    chapter.set("title_by_locale", bilingual("Chapter", "अध्याय"));
    ObjectNode topic = root.putObject("topic");
    topic.put("topic_number", 1);
    topic.put("topic_id", "test-topic");
    topic.set("title_by_locale", bilingual("Test topic", "परीक्षण पाठ"));

    root.putObject("explain").putArray("blocks");
    ArrayNode questions = root.putObject("practice").putArray("questions");
    ObjectNode question = questions.addObject();
    question.put("id", "q-test-1");
    question.put("type", "multiple_choice");
    question.put("rendering_contract_id", "qtype-multiple-choice.v1");
    question.set("prompt_by_locale", bilingual("Choose an answer", "उत्तर चुनें"));
    question.set("feedback_by_locale", bilingual("Try again.", "फिर प्रयास करें।"));
    ArrayNode options = question.putArray("options");
    ObjectNode first = options.addObject();
    first.put("id", "option-a");
    first.set("text_by_locale", bilingual("Option A", "विकल्प क"));
    ObjectNode second = options.addObject();
    second.put("id", "option-b");
    second.set("text_by_locale", bilingual("Option B", "विकल्प ख"));
    ArrayNode answers = question.putArray("correct_answer_ids");
    answers.add(validAnswer ? "option-a" : "invalid-option");
    return mapper.writeValueAsString(root);
  }

  private ObjectNode bilingual(String en, String hi) {
    ObjectNode value = mapper.createObjectNode();
    value.put("en", en);
    value.put("hi", hi);
    return value;
  }
}
