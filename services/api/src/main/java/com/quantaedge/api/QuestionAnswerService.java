package com.quantaedge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class QuestionAnswerService {
  public record Evaluation(boolean autoGraded, Boolean correct, String kind) {}

  private final ObjectMapper mapper;

  public QuestionAnswerService(ObjectMapper mapper) { this.mapper=mapper; }

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
        case "MULTI_OPTION" -> compareJsonArray(expected,submitted);
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
    try {
      BigDecimal expected=new BigDecimal(root.path("value").asText());
      BigDecimal actual=new BigDecimal(String.valueOf(submitted).trim().replace(",",""));
      BigDecimal tolerance=new BigDecimal(root.path("tolerance").asText("0"));
      boolean ok=actual.subtract(expected).abs().compareTo(tolerance)<=0;
      return new Evaluation(true,ok,"NUMERIC");
    } catch(Exception ex) {
      return new Evaluation(true,false,"NUMERIC");
    }
  }

  private Evaluation compareJsonArray(JsonNode expected,Object submitted) throws Exception {
    JsonNode actual=toJson(submitted);
    if(!actual.isArray()) return new Evaluation(true,false,"JSON_ARRAY");
    return new Evaluation(true,canonical(actual).equals(canonical(expected)),"JSON_ARRAY");
  }

  private Evaluation compareJsonValue(JsonNode expected,Object submitted) throws Exception {
    JsonNode actual=toJson(submitted);
    return new Evaluation(true,canonical(actual).equals(canonical(expected)),"JSON");
  }

  private JsonNode toJson(Object value) throws Exception {
    if(value==null) return mapper.createObjectNode();
    if(value instanceof String s) return mapper.readTree(s);
    return mapper.valueToTree(value);
  }

  private String canonical(JsonNode node) {
    if(node==null||node.isNull()) return "null";
    if(node.isObject()) {
      List<String> fields=new ArrayList<>();
      node.fieldNames().forEachRemaining(fields::add);
      Collections.sort(fields);
      StringBuilder out=new StringBuilder("{");
      for(String field:fields) out.append(mapper.valueToTree(field)).append(":").append(canonical(node.get(field))).append(",");
      return out.append("}").toString();
    }
    if(node.isArray()) {
      StringBuilder out=new StringBuilder("[");
      for(JsonNode n:node) out.append(canonical(n)).append(",");
      return out.append("]").toString();
    }
    return node.toString();
  }

  private String normalized(String value) {
    return value.trim().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);
  }
}
