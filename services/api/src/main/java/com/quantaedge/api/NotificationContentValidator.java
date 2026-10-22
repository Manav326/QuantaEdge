package com.quantaedge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
  private static final int MAX_DOCUMENT_NODES = 800;
  private static final Set<String> UNSAFE_KEYS = Set.of(
      "html", "rawhtml", "script", "javascript", "onclick", "onerror", "srcdoc", "iframe");

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
    JsonNode versionNode = document.path("version");
    boolean supportedVersion = versionNode.isIntegralNumber() && versionNode.intValue() == 1;
    // Older clients serialized the version as "1". Accept that compatible value,
    // but canonicalize it before persistence so every renderer receives number 1.
    if (versionNode.isTextual()) supportedVersion = "1".equals(versionNode.asText().trim());
    if (!supportedVersion) throw new IllegalArgumentException("Unsupported notification content version.");
    ((ObjectNode) document).put("version", 1);
    JsonNode blocks = document.path("blocks");
    if (!blocks.isArray() || blocks.isEmpty() || blocks.size() > 40) {
      throw new IllegalArgumentException("Add between 1 and 40 content blocks.");
    }
    validateTree(document, 0, new int[] {0});
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
        if ((type.equals("image") || type.equals("animation"))
            && !Boolean.TRUE.equals(block.path("decorative").asBoolean(false))
            && block.path("alt").asText("").trim().isEmpty()) {
          throw new IllegalArgumentException("Meaningful images and animations require alternative text.");
        }
        if (type.equals("hero") && !assetId.isEmpty()
            && block.path("alt").asText(block.path("title").asText("")).trim().isEmpty()) {
          throw new IllegalArgumentException("Hero images require alternative text or a descriptive title.");
        }
        if (type.equals("video") && !assetId.isEmpty()
            && block.path("alt").asText(block.path("title").asText("")).trim().isEmpty()) {
          throw new IllegalArgumentException("Videos require an accessible title or alternative text.");
        }
      }
    }
    return document;
  }

  private void validateTree(JsonNode node, int depth, int[] nodeCount) {
    if (depth > 12) throw new IllegalArgumentException("Notification content nesting is too deep.");
    nodeCount[0]++;
    if (nodeCount[0] > MAX_DOCUMENT_NODES) {
      throw new IllegalArgumentException("Notification content contains too many nested elements.");
    }
    if (node.isObject()) {
      Iterator<String> names = node.fieldNames();
      while (names.hasNext()) {
        String name = names.next();
        nodeCount[0]++;
        if (nodeCount[0] > MAX_DOCUMENT_NODES) {
          throw new IllegalArgumentException("Notification content contains too many nested elements.");
        }
        String normalizedName = name.toLowerCase(java.util.Locale.ROOT);
        if (UNSAFE_KEYS.contains(normalizedName)) {
          throw new IllegalArgumentException("Executable HTML and script properties are not allowed in notification content.");
        }
        JsonNode value = node.get(name);
        if (name.equals("type") && value.isTextual() && !BLOCK_TYPES.contains(value.asText())) {
          throw new IllegalArgumentException("Unsupported notification block type: " + value.asText());
        }
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
          validateTree(value, depth + 1, nodeCount);
        }
      }
    } else if (node.isArray()) {
      for (JsonNode child : node) validateTree(child, depth + 1, nodeCount);
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
