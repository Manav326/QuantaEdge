package com.quantaedge.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Server-side loader and contract boundary for repository-authored structured lessons.
 * A learner can only receive a reviewed, published artifact with author-only keys removed.
 */
@Service
public class StructuredLessonArtifactService {
  private static final Logger LOG = LoggerFactory.getLogger(StructuredLessonArtifactService.class);
  private static final String RESOURCE_PATTERN =
      "classpath*:structured-lessons/class-*/science/chapter-*/topic-*.json";
  private static final Set<String> QUESTION_TYPES = Set.of(
      "multiple_choice", "multiple_select", "true_false", "matching",
      "fill_blank", "image_observation", "short_answer", "scenario_based");
  private static final Set<String> BLOCK_TYPES =
      Set.of("rich_text", "image", "diagram", "interactive", "recap");
  private static final Set<String> OPTION_TYPES =
      Set.of("multiple_choice", "image_observation", "scenario_based");
  private static final Set<String> PRIVATE_FIELDS = Set.of(
      "correct_answer_ids", "correct_answer", "correct_pairs", "accepted_answers",
      "answer_guide_by_locale", "grading", "answer_payload", "answer_key", "answer_keys",
      "correct_option_id", "correct_option_ids", "correct_options", "expected_answer",
      "model_answer_by_locale", "solution_by_locale", "answer_explanation_by_locale",
      "grader_config", "is_correct", "correct");
  private final ObjectMapper mapper;
  private final ResourcePatternResolver resolver;
  private volatile Map<String, LoadedArtifact> cache;

  @Autowired
  public StructuredLessonArtifactService(ObjectMapper mapper) {
    this(mapper, new PathMatchingResourcePatternResolver());
  }

  StructuredLessonArtifactService(ObjectMapper mapper, ResourcePatternResolver resolver) {
    this.mapper = mapper;
    this.resolver = resolver;
  }

  /** Package-private fixture constructor for unit tests. */
  StructuredLessonArtifactService(ObjectMapper mapper, String inlineJson) {
    this.mapper = mapper;
    this.resolver = null;
    try {
      ObjectNode root = (ObjectNode) mapper.readTree(inlineJson);
      String id = root.path("artifact_id").asText("");
      if (!isSafeArtifactId(id)) throw new IllegalArgumentException("Fixture artifact_id is invalid.");
      this.cache = Map.of(id, new LoadedArtifact(root.deepCopy(), sha256(inlineJson.getBytes(StandardCharsets.UTF_8))));
    } catch (JacksonException | ClassCastException error) {
      throw new IllegalArgumentException("Fixture JSON must be a JSON object.", error);
    }
  }

  public record PublishedArtifact(String artifactId, String sha256, ObjectNode source) {}
  public record Evaluation(String status, Integer score, Integer maxScore,
                           boolean autoGraded, String feedback) {}
  private record LoadedArtifact(ObjectNode source, String sha256) {}

  /** Unpublished/missing artifacts intentionally look the same to learners. */
  public PublishedArtifact requirePublished(String artifactId) {
    if (!isSafeArtifactId(artifactId)) throw notAvailable();
    LoadedArtifact artifact = artifacts().get(artifactId);
    if (artifact == null || !isPublicationApproved(artifact.source())
        || !validateStructure(artifact.source()).isEmpty()) throw notAvailable();
    return new PublishedArtifact(artifactId, artifact.sha256(), artifact.source().deepCopy());
  }

  /** Sanitize a copy; the cached/server-side record retains its grading key. */
  public ObjectNode toStudentPayload(PublishedArtifact artifact) {
    ObjectNode safe = artifact.source().deepCopy();
    ObjectNode explain = objectAt(safe, "explain");
    ArrayNode safeBlocks = mapper.createArrayNode();
    for (JsonNode block : arrayAt(explain, "blocks")) {
      String type = block.path("type").asText("");
      if ((type.equals("image") || type.equals("diagram")) && !isRenderableVisual(block)) continue;
      safeBlocks.add(block.deepCopy());
    }
    explain.set("blocks", safeBlocks);

    ObjectNode practice = objectAt(safe, "practice");
    ArrayNode safeQuestions = mapper.createArrayNode();
    for (JsonNode question : arrayAt(practice, "questions")) {
      if (!question.isObject()) continue;
      if (question.path("type").asText("").equals("image_observation")
          && !isQuestionVisualApproved(safe, question)) continue;
      ObjectNode publicQuestion = ((ObjectNode) question).deepCopy();
      stripPrivateFields(publicQuestion);
      publicQuestion.remove("feedback_by_locale");
      safeQuestions.add(publicQuestion);
    }
    practice.set("questions", safeQuestions);
    practice.put("question_count", safeQuestions.size());

    ObjectNode publicWorkflow = mapper.createObjectNode();
    publicWorkflow.put("status", "PUBLISHED");
    publicWorkflow.put("publication_allowed", true);
    safe.set("workflow", publicWorkflow);
    return safe;
  }

  /** Return a server-only question only if the same question is present in learner-safe payload. */
  public JsonNode questionForStudent(PublishedArtifact artifact, String questionId) {
    if (questionId == null || !questionId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,179}")) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Structured question is not available.");
    }
    ObjectNode payload = toStudentPayload(artifact);
    boolean available = false;
    for (JsonNode question : arrayAt(objectAt(payload, "practice"), "questions")) {
      if (questionId.equals(question.path("id").asText(""))) available = true;
    }
    if (!available) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Structured question is not available.");
    for (JsonNode question : arrayAt(objectAt(artifact.source(), "practice"), "questions")) {
      if (questionId.equals(question.path("id").asText(""))) return question.deepCopy();
    }
    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Structured question is not available.");
  }

  /** Check the response type and every submitted choice before grading or persistence. */
  public JsonNode validateResponse(JsonNode question, Object submitted) {
    if (submitted == null) throw new IllegalArgumentException("A response is required.");
    JsonNode response = mapper.valueToTree(submitted);
    String type = question.path("type").asText("");
    if (response.isNull() || response.isMissingNode()) {
      throw new IllegalArgumentException("A response is required.");
    }

    if (OPTION_TYPES.contains(type)) {
      if (!response.isTextual() || response.asText().isBlank()
          || !optionIds(question).contains(response.asText())) {
        throw new IllegalArgumentException("Choose a valid answer option.");
      }
    } else if (type.equals("multiple_select")) {
      if (!response.isArray() || response.isEmpty()) {
        throw new IllegalArgumentException("Choose at least one answer option.");
      }
      Set<String> allowed = optionIds(question);
      Set<String> selected = new HashSet<>();
      for (JsonNode value : response) {
        if (!value.isTextual() || !allowed.contains(value.asText()) || !selected.add(value.asText())) {
          throw new IllegalArgumentException("The selected answer options are invalid.");
        }
      }
    } else if (type.equals("true_false")) {
      if (!response.isBoolean()) throw new IllegalArgumentException("Choose True or False.");
    } else if (type.equals("matching")) {
      if (!response.isObject()) throw new IllegalArgumentException("The matching response is invalid.");
      Set<String> leftIds = itemIds(question, "left_items");
      Set<String> rightIds = itemIds(question, "right_items");
      Set<String> responseIds = new HashSet<>();
      for (Map.Entry<String, JsonNode> field : response.properties()) responseIds.add(field.getKey());
      if (leftIds.isEmpty() || !responseIds.equals(leftIds)) {
        throw new IllegalArgumentException("Complete every matching row before submitting.");
      }
      for (String leftId : leftIds) {
        JsonNode selected = response.get(leftId);
        if (selected == null || !selected.isTextual() || !rightIds.contains(selected.asText())) {
          throw new IllegalArgumentException("One or more matching choices are invalid.");
        }
      }
    } else if (type.equals("fill_blank") || type.equals("short_answer")) {
      if (!response.isTextual()) throw new IllegalArgumentException("Enter a text response.");
      String text = response.asText().trim();
      int max = type.equals("fill_blank") ? 1000 : 10000;
      if (text.isBlank() || text.length() > max) {
        throw new IllegalArgumentException(type.equals("fill_blank")
            ? "Enter an answer of up to 1,000 characters."
            : "Enter an answer of up to 10,000 characters.");
      }
      return mapper.getNodeFactory().textNode(text);
    } else {
      throw new IllegalArgumentException("This structured question type is not supported.");
    }

    try {
      if (mapper.writeValueAsString(response).length() > 16000) {
        throw new IllegalArgumentException("The response is too large.");
      }
    } catch (JacksonException error) {
      throw new IllegalArgumentException("The response could not be serialized.", error);
    }
    return response.deepCopy();
  }

  /** Only objectively specified answers are graded; short answers remain pending human review. */
  public Evaluation evaluateResponse(JsonNode question, JsonNode response, String language) {
    String type = question.path("type").asText("");
    String feedback = localized(question.path("feedback_by_locale"), language);
    if (type.equals("short_answer")) {
      return new Evaluation("pending_review", null, null, false,
          feedback.isBlank() ? pendingFeedback(language) : feedback);
    }

    if (OPTION_TYPES.contains(type) || type.equals("multiple_select")) {
      Set<String> expected = textValues(question.path("correct_answer_ids"));
      if (expected.isEmpty()) return pendingEvaluation(language, feedback);
      boolean correct = type.equals("multiple_select")
          ? new HashSet<>(textValues(response)).equals(expected)
          : response.isTextual() && expected.contains(response.asText());
      return objectiveResult(correct, feedback, 1, language);
    }
    if (type.equals("true_false")) {
      JsonNode expected = question.get("correct_answer");
      if (expected == null || !expected.isBoolean()) return pendingEvaluation(language, feedback);
      return objectiveResult(response.isBoolean() && response.booleanValue() == expected.booleanValue(),
          feedback, 1, language);
    }
    if (type.equals("fill_blank")) {
      JsonNode accepted = question.path("accepted_answers").path(language);
      if (!accepted.isArray() || accepted.isEmpty()) return pendingEvaluation(language, feedback);
      String actual = normalizeAnswer(response.asText(), language);
      for (JsonNode answer : accepted) {
        if (answer.isTextual() && normalizeAnswer(answer.asText(), language).equals(actual)) {
          return objectiveResult(true, feedback, 1, language);
        }
      }
      return objectiveResult(false, feedback, 1, language);
    }
    if (type.equals("matching")) {
      JsonNode pairs = question.path("correct_pairs");
      if (!pairs.isArray() || pairs.isEmpty()) return pendingEvaluation(language, feedback);
      int score = 0;
      for (JsonNode pair : pairs) {
        String leftId = pair.path("left_id").asText("");
        String rightId = pair.path("right_id").asText("");
        if (!leftId.isBlank() && response.path(leftId).asText("").equals(rightId)) score++;
      }
      String status = score == pairs.size() ? "correct" : score > 0 ? "partially_correct" : "incorrect";
      return new Evaluation(status, score, pairs.size(), true,
          feedback.isBlank() ? defaultFeedback(status, language) : feedback);
    }
    return pendingEvaluation(language, feedback);
  }

  public int classNumber(PublishedArtifact artifact) {
    int number = artifact.source().path("curriculum").path("class").asInt(-1);
    if (number < 1 || number > 12) throw notAvailable();
    return number;
  }

  public String subjectCode(PublishedArtifact artifact) {
    String value = artifact.source().path("curriculum").path("subject").asText("")
        .trim().toLowerCase(Locale.ROOT);
    if (!value.matches("[a-z][a-z0-9_-]{0,39}")) throw notAvailable();
    return value;
  }

  private Map<String, LoadedArtifact> artifacts() {
    Map<String, LoadedArtifact> found = cache;
    if (found != null) return found;
    synchronized (this) {
      if (cache != null) return cache;
      Map<String, LoadedArtifact> loaded = new LinkedHashMap<>();
      Set<String> duplicates = new HashSet<>();
      try {
        Resource[] resources = resolver.getResources(RESOURCE_PATTERN);
        for (Resource resource : resources) {
          try (InputStream input = resource.getInputStream()) {
            byte[] bytes = input.readAllBytes();
            JsonNode parsed = mapper.readTree(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isObject()) continue;
            ObjectNode source = (ObjectNode) parsed;
            String id = source.path("artifact_id").asText("");
            if (!isSafeArtifactId(id) || duplicates.contains(id)) continue;
            if (loaded.containsKey(id)) {
              loaded.remove(id);
              duplicates.add(id);
              LOG.error("Duplicate structured lesson artifact_id {}; refusing both copies.", id);
              continue;
            }
            loaded.put(id, new LoadedArtifact(source, sha256(bytes)));
          } catch (Exception error) {
            LOG.warn("Ignoring malformed structured lesson resource {}.", resource.getDescription(), error);
          }
        }
      } catch (IOException error) {
        LOG.error("Unable to scan packaged structured lesson resources.", error);
        throw new IllegalStateException("Structured lesson resources are unavailable.", error);
      }
      if (loaded.isEmpty()) LOG.warn("No structured lesson artifacts found in {}", RESOURCE_PATTERN);
      cache = Map.copyOf(loaded);
      return cache;
    }
  }

  private List<String> validateStructure(ObjectNode artifact) {
    List<String> errors = new ArrayList<>();
    if (!"quantaedge.structured-lesson.v1".equals(artifact.path("schema_version").asText())) {
      errors.add("schema_version");
    }
    if (!hasLocale(artifact.path("chapter").path("title_by_locale"))) errors.add("chapter.title_by_locale");
    if (!hasLocale(artifact.path("topic").path("title_by_locale"))) errors.add("topic.title_by_locale");
    if (artifact.path("topic").path("topic_id").asText("").isBlank()) errors.add("topic.topic_id");

    JsonNode blocks = artifact.path("explain").path("blocks");
    if (!blocks.isArray()) {
      errors.add("explain.blocks");
    } else {
      Set<String> ids = new HashSet<>();
      for (JsonNode block : blocks) {
        String id = block.path("id").asText("");
        String type = block.path("type").asText("");
        if (id.isBlank() || !ids.add(id)) errors.add("explain.blocks.id");
        if (!BLOCK_TYPES.contains(type)) errors.add("explain.blocks.type");
        if (type.equals("rich_text") && !hasLocalizedContentObject(block.path("text_by_locale"))) {
          errors.add("explain.blocks.text_by_locale");
        }
        if (type.equals("image") && !block.path("asset").isObject()) errors.add("explain.blocks.asset");
        if (type.equals("diagram") && !block.path("diagram_data").isObject()) errors.add("explain.blocks.diagram_data");
        if (type.equals("interactive") && !hasLocalizedActivityInstructions(block.path("instructions_by_locale"))) {
          errors.add("explain.blocks.instructions_by_locale");
        }
        if ((type.equals("image") || type.equals("diagram")) && !hasLocale(block.path("alt_by_locale"))) {
          errors.add("explain.blocks.alt_by_locale");
        }
      }
    }

    JsonNode questions = artifact.path("practice").path("questions");
    if (!questions.isArray()) {
      errors.add("practice.questions");
    } else {
      Set<String> ids = new HashSet<>();
      for (JsonNode question : questions) {
        String id = question.path("id").asText("");
        String type = question.path("type").asText("");
        if (id.isBlank() || !ids.add(id)) errors.add("practice.questions.id");
        if (!QUESTION_TYPES.contains(type)) errors.add("practice.questions.type");
        if (question.path("rendering_contract_id").asText("").isBlank()) errors.add("practice.questions.rendering_contract_id");
        if (!hasLocale(question.path("prompt_by_locale"))) errors.add("practice.questions.prompt_by_locale");

        if (OPTION_TYPES.contains(type) || type.equals("multiple_select")) {
          Set<String> options = optionIds(question);
          if (options.isEmpty()) errors.add("practice.questions.options");
          for (JsonNode option : question.path("options")) {
            if (!hasLocale(option.path("text_by_locale"))) errors.add("practice.questions.options.text_by_locale");
          }
          Set<String> correct = textValues(question.path("correct_answer_ids"));
          if (correct.isEmpty() || !options.containsAll(correct)) errors.add("practice.questions.correct_answer_ids");
        } else if (type.equals("true_false")) {
          if (!question.path("correct_answer").isBoolean()) errors.add("practice.questions.correct_answer");
        } else if (type.equals("matching")) {
          Set<String> left = itemIds(question, "left_items");
          Set<String> right = itemIds(question, "right_items");
          JsonNode pairs = question.path("correct_pairs");
          if (left.isEmpty() || right.isEmpty() || !pairs.isArray() || pairs.isEmpty()) {
            errors.add("practice.questions.matching");
          } else {
            for (JsonNode pair : pairs) {
              if (!left.contains(pair.path("left_id").asText(""))
                  || !right.contains(pair.path("right_id").asText(""))) errors.add("practice.questions.correct_pairs");
            }
          }
        } else if (type.equals("fill_blank")) {
          JsonNode accepted = question.path("accepted_answers");
          if (!accepted.path("en").isArray() || accepted.path("en").isEmpty()
              || !accepted.path("hi").isArray() || accepted.path("hi").isEmpty()) {
            errors.add("practice.questions.accepted_answers");
          }
        }
      }
    }
    return errors;
  }

  private boolean isPublicationApproved(ObjectNode artifact) {
    JsonNode workflow = artifact.path("workflow");
    if (!"PUBLISHED".equalsIgnoreCase(workflow.path("status").asText(""))
        || !workflow.path("publication_allowed").asBoolean(false)) return false;
    for (String field : List.of("content_review_status", "source_completeness_status", "bilingual_parity_status")) {
      if (!"APPROVED".equalsIgnoreCase(workflow.path(field).asText(""))) return false;
    }
    String scientific = workflow.path("scientific_review_status")
        .asText(workflow.path("science_review_status").asText(""));
    if (!"APPROVED".equalsIgnoreCase(scientific)) return false;
    String visual = workflow.path("visual_review_status").asText("");
    return "APPROVED".equalsIgnoreCase(visual)
        || "APPROVED_FOR_STUDENT_RENDERING".equalsIgnoreCase(visual);
  }

  private boolean isRenderableVisual(JsonNode block) {
    String type = block.path("type").asText("");
    if (type.equals("image")) {
      JsonNode asset = block.path("asset");
      String url = asset.path("url").asText("");
      return asset.path("publication_allowed").asBoolean(false)
          && asset.path("student_renderable").asBoolean(false)
          && url.startsWith("https://")
          && isApprovedReview(asset.path("review_status").asText("").toUpperCase(Locale.ROOT))
          && hasLocale(asset.path("alt_by_locale"));
    }
    if (type.equals("diagram")) {
      JsonNode data = block.path("diagram_data");
      boolean renderable = block.has("student_renderable")
          ? block.path("student_renderable").asBoolean(false)
          : data.path("student_renderable").asBoolean(false);
      String review = block.path("review_status").asText(data.path("review_status").asText(""))
          .toUpperCase(Locale.ROOT);
      return renderable && isApprovedReview(review) && hasLocale(block.path("alt_by_locale"))
          && data.path("nodes").isArray() && !data.path("nodes").isEmpty();
    }
    return true;
  }

  private boolean isQuestionVisualApproved(JsonNode artifact, JsonNode question) {
    if (!"APPROVED_FOR_STUDENT_RENDERING".equalsIgnoreCase(
        question.path("renderability").path("status").asText(""))) return false;
    String ref = question.path("visual_asset_ref").asText("");
    if (ref.isBlank()) return false;
    for (JsonNode block : arrayAt(objectAt(artifact, "explain"), "blocks")) {
      JsonNode asset = block.path("asset");
      JsonNode diagram = block.path("diagram_data");
      boolean match = ref.equals(block.path("id").asText(""))
          || (ref + "-block").equals(block.path("id").asText(""))
          || ref.equals(asset.path("asset_id").asText(""))
          || ref.equals(diagram.path("visual_id").asText(""))
          || ref.equals(diagram.path("diagram_id").asText(""));
      if (!match && block.path("asset_refs").isArray()) {
        for (JsonNode assetRef : block.path("asset_refs")) if (ref.equals(assetRef.asText(""))) match = true;
      }
      if (match && isRenderableVisual(block)) return true;
    }
    return false;
  }

  private void stripPrivateFields(JsonNode node) {
    if (node.isObject()) {
      ObjectNode object = (ObjectNode) node;
      Iterator<Map.Entry<String, JsonNode>> fields = object.properties().iterator();
      List<String> remove = new ArrayList<>();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        if (PRIVATE_FIELDS.contains(field.getKey().toLowerCase(Locale.ROOT))) remove.add(field.getKey());
        else stripPrivateFields(field.getValue());
      }
      remove.forEach(object::remove);
    } else if (node.isArray()) {
      for (JsonNode child : node) stripPrivateFields(child);
    }
  }

  private Set<String> optionIds(JsonNode question) {
    Set<String> ids = new LinkedHashSet<>();
    JsonNode options = question.path("options");
    if (!options.isArray()) return ids;
    for (JsonNode option : options) {
      String id = option.path("id").asText("");
      if (id.isBlank() || !ids.add(id)) return Set.of();
    }
    return ids;
  }

  private Set<String> itemIds(JsonNode question, String field) {
    Set<String> ids = new LinkedHashSet<>();
    JsonNode items = question.path(field);
    if (!items.isArray()) return ids;
    for (JsonNode item : items) {
      String id = item.path("id").asText("");
      if (id.isBlank() || !ids.add(id)) return Set.of();
    }
    return ids;
  }

  private Set<String> textValues(JsonNode values) {
    Set<String> result = new LinkedHashSet<>();
    if (!values.isArray()) return result;
    for (JsonNode value : values) if (value.isTextual()) result.add(value.asText());
    return result;
  }

  private boolean hasLocale(JsonNode value) {
    return nonEmpty(value.path("en")) && nonEmpty(value.path("hi"));
  }

  private boolean hasLocalizedContentObject(JsonNode value) {
    return value.isObject() && value.path("en").isObject() && !value.path("en").isEmpty()
        && value.path("hi").isObject() && !value.path("hi").isEmpty();
  }

  private boolean hasLocalizedActivityInstructions(JsonNode value) {
    return value.isObject() && hasActivityInstruction(value.path("en")) && hasActivityInstruction(value.path("hi"));
  }

  private boolean hasActivityInstruction(JsonNode value) {
    if (value.isTextual()) return !value.asText().isBlank();
    if (value.isArray()) return !value.isEmpty();
    return false;
  }

  private boolean nonEmpty(JsonNode value) {
    return value != null && value.isTextual() && !value.asText().isBlank();
  }

  private boolean isApprovedReview(String review) {
    return "APPROVED".equals(review) || "APPROVED_FOR_STUDENT_RENDERING".equals(review);
  }

  private Evaluation objectiveResult(boolean correct, String feedback, int maxScore, String language) {
    String status = correct ? "correct" : "incorrect";
    return new Evaluation(status, correct ? maxScore : 0, maxScore, true,
        feedback.isBlank() ? defaultFeedback(status, language) : feedback);
  }

  private Evaluation pendingEvaluation(String language, String feedback) {
    return new Evaluation("pending_review", null, null, false,
        feedback.isBlank() ? pendingFeedback(language) : feedback);
  }

  private String localized(JsonNode value, String language) {
    JsonNode text = value.path("en".equals(language) ? "en" : "hi");
    return text.isTextual() ? text.asText("") : "";
  }

  private String normalizeAnswer(String value, String language) {
    String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFC)
        .trim().replaceAll("\\s+", " ");
    return "en".equals(language) ? normalized.toLowerCase(Locale.ROOT) : normalized;
  }

  private String defaultFeedback(String status, String language) {
    boolean en = "en".equals(language);
    if (status.equals("correct")) return en ? "Correct." : "सही उत्तर।";
    if (status.equals("partially_correct")) return en ? "Partially correct." : "आंशिक रूप से सही।";
    return en ? "Not correct yet. Review the lesson and try again." : "अभी सही नहीं है। पाठ दोबारा देखें और प्रयास करें।";
  }

  private String pendingFeedback(String language) {
    return "en".equals(language)
        ? "Your response was saved and needs teacher or rubric-based review."
        : "आपका उत्तर सुरक्षित है और इसकी शिक्षक या मूल्यांकन-निर्देश के अनुसार जाँच आवश्यक है।";
  }

  private boolean isSafeArtifactId(String id) {
    return id != null && id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}");
  }

  private ResponseStatusException notAvailable() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Structured lesson is not available.");
  }

  private ObjectNode objectAt(JsonNode parent, String field) {
    JsonNode value = parent.path(field);
    return value.isObject() ? (ObjectNode) value : mapper.createObjectNode();
  }

  private ArrayNode arrayAt(JsonNode parent, String field) {
    JsonNode value = parent.path(field);
    return value.isArray() ? (ArrayNode) value : mapper.createArrayNode();
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable.", error);
    }
  }
}
