package com.quantaedge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Iterator;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Validates the versioned, non-executable rich notification document shared by web and Android. */
@Component
public class NotificationContentValidator {
  private static final Set<String> BLOCK_TYPES = Set.of(
      "hero", "richText", "image", "video", "animation", "courseCard", "eventCard",
      "highlight", "badge", "icon", "divider", "spacer", "cta", "footer", "container",
      "carousel");
  private static final Set<String> UNSAFE_KEYS = Set.of(
      "html", "rawHtml", "script", "javascript", "onClick", "onError", "srcDoc", "iframe");

  private final ObjectMapper mapper;

  public NotificationContentValidator(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public JsonNode validate(Object input) {
    if (input == null) throw new IllegalArgumentException("A rich notification content document is required.");
    JsonNode document = mapper.valueToTree(input);
    if (document == null || !document.isObject()) {
      throw new IllegalArgumentException("Notification content must be a JSON object.");
    }
    int version = document.path("version").asInt(-1);
    if (version != 1) throw new IllegalArgumentException("Unsupported notification content version.");
    JsonNode blocks = document.path("blocks");
    if (!blocks.isArray() || blocks.isEmpty() || blocks.size() > 40) {
      throw new IllegalArgumentException("Add between 1 and 40 content blocks.");
    }
    validateTree(document, 0);
    int index = 0;
    for (JsonNode block : blocks) {
      index++;
      if (!block.isObject()) throw new IllegalArgumentException("Content block " + index + " must be an object.");
      String type = block.path("type").asText("");
      if (!BLOCK_TYPES.contains(type)) {
        throw new IllegalArgumentException("Unsupported notification block type: " + type);
      }
      if (type.equals("richText") && block.path("text").asText("").isBlank()) {
        throw new IllegalArgumentException("Rich-text blocks require text.");
      }
      if (type.equals("cta")) {
        String label = block.path("label").asText("").trim();
        String target = block.path("target").asText("").trim();
        if (label.isEmpty() || label.length() > 80 || target.isEmpty()) {
          throw new IllegalArgumentException("Call-to-action blocks require a short label and validated target.");
        }
        validateTarget(target);
      }
      if (type.equals("image") || type.equals("video") || type.equals("animation") || type.equals("hero")) {
        String assetId = block.path("assetId").asText("").trim();
        if (!assetId.isEmpty() && assetId.length() > 80) {
          throw new IllegalArgumentException("Media asset reference is invalid.");
        }
        if (type.equals("image") && !Boolean.TRUE.equals(block.path("decorative").asBoolean(false))
            && block.path("alt").asText("").trim().isEmpty()) {
          throw new IllegalArgumentException("Meaningful images require alternative text.");
        }
      }
    }
    return document;
  }

  private void validateTree(JsonNode node, int depth) {
    if (depth > 12) throw new IllegalArgumentException("Notification content nesting is too deep.");
    if (node.isObject()) {
      Iterator<String> names = node.fieldNames();
      while (names.hasNext()) {
        String name = names.next();
        if (UNSAFE_KEYS.contains(name)) {
          throw new IllegalArgumentException("Executable HTML and script properties are not allowed in notification content.");
        }
        JsonNode value = node.get(name);
        if (value.isTextual()) {
          String text = value.asText();
          if (text.length() > 5000) throw new IllegalArgumentException("A notification text field exceeds 5000 characters.");
          String lower = text.toLowerCase(java.util.Locale.ROOT).trim();
          if (lower.startsWith("javascript:") || lower.startsWith("data:text/html:")
              || lower.startsWith("vbscript:")) {
            throw new IllegalArgumentException("Unsafe notification URLs are not allowed.");
          }
          if (name.equals("url") || name.equals("href") || name.equals("target")) validateTarget(text);
        } else {
          validateTree(value, depth + 1);
        }
      }
    } else if (node.isArray()) {
      for (JsonNode child : node) validateTree(child, depth + 1);
    }
  }

  private static void validateTarget(String raw) {
    String target = raw == null ? "" : raw.trim();
    if (target.isEmpty()) return;
    if (target.startsWith("/") && !target.startsWith("//") && !target.contains("\\")
        && !target.contains("\r") && !target.contains("\n")) return;
    try {
      java.net.URI uri = java.net.URI.create(target);
      if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
          && uri.getUserInfo() == null) return;
    } catch (RuntimeException ignored) {
      // Return the same safe validation response for malformed and non-HTTPS targets.
    }
    throw new IllegalArgumentException("Notification links must be internal application routes or HTTPS URLs.");
  }
}
