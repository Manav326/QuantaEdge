package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
