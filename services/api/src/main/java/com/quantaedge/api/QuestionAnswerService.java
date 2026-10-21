package com.quantaedge.api;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class QuestionAnswerService {
  public record Evaluation(boolean autoGraded, Boolean correct, String kind) {}

  private final ObjectMapper mapper;

  public QuestionAnswerService(ObjectMapper mapper) { this.mapper=mapper; }

  /** Keeps text answers readable and persists structured MATCH/ORDER answers as valid JSON. */
  public String serializeForPersistence(Object submitted) {
    if (submitted == null) return "";
    if (submitted instanceof String value) return value.trim();
    try {
      return mapper.writeValueAsString(submitted);
    } catch (JacksonException ex) {
      throw new IllegalArgumentException("The answer could not be saved.", ex);
    }
  }

  public Evaluation evaluate(String payload, Object submitted) {
    try {
      JsonNode root=mapper.readTree(payload==null||payload.isBlank()?"{}":payload);
      String kind=root.path("kind").asText("");
      JsonNode expected=root.get("value");
      if(expected==null) return new Evaluation(false,null,kind);

      return switch(kind) {
        case "OPTION","TEXT" -> compareText(expected.asText(""),submitted);
        case "NUMERIC" -> compareNumeric(root,submitted);
        case "ORDER" -> compareJsonArray(expected,submitted);
        case "MATCH" -> compareJsonValue(expected,submitted);
        case "MULTI_OPTION" -> compareJsonArrayUnordered(expected,submitted);
        default -> new Evaluation(false,null,kind);
      };
    } catch(Exception ex) {
      return new Evaluation(false,null,"");
    }
  }

  private Evaluation compareText(String expected,Object submitted) {
    String actual=normalized(String.valueOf(submitted==null?"":submitted));
    return new Evaluation(true,actual.equals(normalized(expected)),"TEXT");
  }

  private Evaluation compareNumeric(JsonNode root,Object submitted) {
    final BigDecimal expected;
    final BigDecimal tolerance;
    try {
      JsonNode expectedNode=root.get("value");
      if(expectedNode==null||expectedNode.isNull()) return new Evaluation(false,null,"NUMERIC");
      expected=new BigDecimal(expectedNode.asText());
      JsonNode toleranceNode=root.get("tolerance");
      tolerance=toleranceNode==null||toleranceNode.isNull()
          ?BigDecimal.ZERO:new BigDecimal(toleranceNode.asText());
      if(tolerance.signum()<0) return new Evaluation(false,null,"NUMERIC");
    } catch(Exception ex) {
      // Invalid persisted answer keys must not turn every student's answer into an incorrect mark.
      return new Evaluation(false,null,"NUMERIC");
    }
    try {
      BigDecimal actual=new BigDecimal(String.valueOf(submitted).trim().replace(",",""));
      boolean ok=actual.subtract(expected).abs().compareTo(tolerance)<=0;
      return new Evaluation(true,ok,"NUMERIC");
    } catch(Exception ex) {
      // A student's non-numeric response is still a valid, incorrect objective response.
      return new Evaluation(true,false,"NUMERIC");
    }
  }

  private Evaluation compareJsonArray(JsonNode expected,Object submitted) throws Exception {
    JsonNode actual=toJson(submitted);
    if(!actual.isArray()) return new Evaluation(true,false,"JSON_ARRAY");
    return new Evaluation(true,actual.isArray() && actual.equals(expected),"JSON_ARRAY");
  }

  /** Multi-select grading is set-based; selection order must not change the result. */
  private Evaluation compareJsonArrayUnordered(JsonNode expected,Object submitted) throws Exception {
    if(expected==null || !expected.isArray()) {
      // A malformed authored key must not auto-grade every response as incorrect.
      return new Evaluation(false,null,"MULTI_OPTION");
    }
    JsonNode actual=toJson(submitted);
    if(!actual.isArray()) return new Evaluation(true,false,"MULTI_OPTION");
    List<String> expectedValues=new ArrayList<>();
    List<String> actualValues=new ArrayList<>();
    expected.forEach(value -> expectedValues.add(value.toString()));
    actual.forEach(value -> actualValues.add(value.toString()));
    Collections.sort(expectedValues);
    Collections.sort(actualValues);
    return new Evaluation(true,expectedValues.equals(actualValues),"MULTI_OPTION");
  }

  private Evaluation compareJsonValue(JsonNode expected,Object submitted) throws Exception {
    JsonNode actual=toJson(submitted);
    return new Evaluation(true,actual.equals(expected),"JSON");
  }

  private JsonNode toJson(Object value) throws Exception {
    if(value==null) return mapper.createObjectNode();
    if(value instanceof String s) return mapper.readTree(s);
    return mapper.valueToTree(value);
  }


  private String normalized(String value) {
    return value.trim().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);
  }
}
