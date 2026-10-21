package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NotificationContentValidatorTest {
  private NotificationContentValidator validator;

  @BeforeEach
  void setUp() {
    validator = new NotificationContentValidator(new ObjectMapper());
  }

  @Test
  void acceptsVersionedRichDocumentWithMediaCardsAndActions() {
    Map<String,Object> document = Map.of(
        "version",1,
        "blocks", List.of(
            Map.of("type","hero","title","Course update","subtitle","A new resource is ready"),
            Map.of("type","image","assetId","8f136a40-80e2-4e67-a6fa-e3b7e89f0b01","alt","New science resource"),
            Map.of("type","courseCard","courseName","Class 6 Mathematics","target","/student/courses"),
            Map.of("type","cta","label","Open course","target","/student/courses"),
            Map.of("type","footer","text","Sent by your institution")
        ));
    assertDoesNotThrow(() -> validator.validate(document));
  }

  @Test
  void rejectsUnknownVersionAndUnsupportedBlocks() {
    assertThrows(IllegalArgumentException.class, () ->
        validator.validate(Map.of("version",2,"blocks",List.of(Map.of("type","hero","title","Hello")))));
    assertThrows(IllegalArgumentException.class, () ->
        validator.validate(Map.of("version",1,"blocks",List.of(Map.of("type","iframe","src","https://example.com")))));
  }

  @Test
  void rejectsUnsafeLinksAndExecutableMarkupProperties() {
    assertThrows(IllegalArgumentException.class, () ->
        validator.validate(Map.of("version",1,"blocks",List.of(
            Map.of("type","cta","label","Open","target","javascript:alert(1)")))));
    assertThrows(IllegalArgumentException.class, () ->
        validator.validate(Map.of("version",1,"blocks",List.of(
            Map.of("type","hero","title","Hello","rawHtml","<script>alert(1)</script>")))));
    assertThrows(IllegalArgumentException.class, () ->
        validator.validate(Map.of("version",1,"blocks",List.of(
            Map.of("type","cta","label","Open","target","//example.com")))));
  }

  @Test
  void requiresAccessibleAlternativeTextForMeaningfulImages() {
    assertThrows(IllegalArgumentException.class, () ->
        validator.validate(Map.of("version",1,"blocks",List.of(
            Map.of("type","image","assetId","8f136a40-80e2-4e67-a6fa-e3b7e89f0b01")))));
    assertDoesNotThrow(() -> validator.validate(Map.of("version",1,"blocks",List.of(
        Map.of("type","image","assetId","8f136a40-80e2-4e67-a6fa-e3b7e89f0b01","decorative",true)))));
  }

  @Test
  void rejectsUnsafePropertiesRegardlessOfKeyCaseAndRejectsNestedUnknownBlocks() {
    assertThrows(IllegalArgumentException.class, () ->
        validator.validate(Map.of("version",1,"blocks",List.of(
            Map.of("type","hero","title","Hello","OnClick","alert(1)")))));
    assertThrows(IllegalArgumentException.class, () ->
        validator.validate(Map.of("version",1,"blocks",List.of(
            Map.of("type","container","children",List.of(Map.of("type","iframe","src","https://example.com")))))));
  }

  @Test
  void rejectsDocumentsWithTooManyFields() {
    java.util.Map<String,Object> block = new java.util.HashMap<>();
    block.put("type","hero");
    for (int i=0;i<900;i++) block.put("field"+i,"value");
    assertThrows(IllegalArgumentException.class, () ->
        validator.validate(Map.of("version",1,"blocks",List.of(block))));
  }

  @Test
  void rejectsExcessiveNestingAndOversizedText() {
    assertThrows(IllegalArgumentException.class, () ->
        validator.validate(Map.of("version",1,"blocks",List.of(
            Map.of("type","richText","text","x".repeat(5001))))));
  }
}
