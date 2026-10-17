package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InstitutionThemeTokensTest {
  @Test
  void defaultThemeRetainsOrangeBrandAndProducesBothSemanticSchemes() {
    Map<String, Object> theme = InstitutionThemeTokens.normalize(Map.of());

    assertEquals(2, theme.get("schemaVersion"));
    assertEquals("quantaedge-orange", theme.get("presetId"));
    assertEquals("system", theme.get("appearance"));
    assertEquals("#F97316", theme.get("primary"));
    assertEquals("#C2410C", theme.get("accent"));

    Map<?, ?> light = assertInstanceOf(Map.class, theme.get("light"));
    Map<?, ?> dark = assertInstanceOf(Map.class, theme.get("dark"));
    for (String token : List.of("primary", "onPrimary", "primaryText", "background", "surface",
        "surfaceElevated", "text", "textMuted", "border", "borderSubtle", "focus", "success",
        "warning", "error", "selectedBackground", "selectedText", "navBackground", "navText",
        "navActiveBackground", "navActiveText", "disabledBackground", "disabledText")) {
      assertNotNull(light.get(token), "Missing light token " + token);
      assertNotNull(dark.get(token), "Missing dark token " + token);
    }
    assertEquals("#0B1220", dark.get("background"));
    assertEquals("#F8FAFC", light.get("background"));
  }

  @Test
  void returnsManyCuratedPresetsIncludingCustomConfigurationOption() {
    List<Map<String, Object>> presets = InstitutionThemeTokens.presets();
    assertTrue(presets.size() >= 15);
    assertTrue(presets.stream().anyMatch(p -> "quantaedge-orange".equals(p.get("id"))));
    assertTrue(presets.stream().anyMatch(p -> "academic-blue".equals(p.get("id"))));
    assertTrue(presets.stream().anyMatch(p -> "custom".equals(p.get("id"))));
    assertFalse(presets.stream().anyMatch(p -> p.get("themeTokens") == null));
    for (Map<String, Object> preset : presets) {
      Map<String, Object> normalized = InstitutionThemeTokens.normalize(preset.get("themeTokens"));
      assertAccessible(normalized, "preset " + preset.get("id"));
    }
  }

  @Test
  void legacyFiveColourThemeIsUpgradedWithoutBreakingOldCallers() {
    Map<String, Object> theme = InstitutionThemeTokens.normalize(Map.of(
        "primary", "#F97316",
        "accent", "#C2410C",
        "background", "#F7F8FC",
        "surface", "#FFFFFF",
        "text", "#152038"));

    assertEquals(2, theme.get("schemaVersion"));
    assertEquals("#F97316", theme.get("primary"));
    assertEquals("#F7F8FC", theme.get("background"));
    assertEquals("#FFFFFF", theme.get("surface"));
    assertEquals("#152038", theme.get("text"));
    assertNotNull(theme.get("dark"));
  }

  @Test
  void rejectsUnknownPresetsMalformedColorsUnsupportedVersionsAndInvalidAppearance() {
    assertThrows(IllegalArgumentException.class,
        () -> InstitutionThemeTokens.normalize(Map.of("schemaVersion", 2, "presetId", "untrusted-css")));
    assertThrows(IllegalArgumentException.class,
        () -> InstitutionThemeTokens.normalize(Map.of("schemaVersion", 2, "brandPrimary", "red")));
    assertThrows(IllegalArgumentException.class,
        () -> InstitutionThemeTokens.normalize(Map.of("schemaVersion", 99)));
    assertThrows(IllegalArgumentException.class,
        () -> InstitutionThemeTokens.normalize(Map.of("schemaVersion", 2, "appearance", "neon")));
    assertThrows(IllegalArgumentException.class,
        () -> InstitutionThemeTokens.normalize(Map.of("schemaVersion", 2, "dropShadow", "#000000")));
  }

  @Test
  void customThemeKeepsTenantColoursAndGeneratesContrastSafeForegrounds() {
    Map<String, Object> theme = InstitutionThemeTokens.normalize(Map.of(
        "schemaVersion", 2,
        "presetId", "custom",
        "appearance", "dark",
        "brandPrimary", "#F3D21B",
        "brandAccent", "#6D28D9"));
    Map<?, ?> light = (Map<?, ?>) theme.get("light");
    Map<?, ?> dark = (Map<?, ?>) theme.get("dark");

    assertEquals("dark", theme.get("appearance"));
    assertEquals("#F3D21B", theme.get("brandPrimary"));
    assertEquals("#6D28D9", theme.get("brandAccent"));
    assertTrue(contrast((String) light.get("onPrimary"), (String) light.get("primary")) >= 4.5);
    assertTrue(contrast((String) dark.get("onPrimary"), (String) dark.get("primary")) >= 4.5);
    assertTrue(contrast((String) light.get("text"), (String) light.get("background")) >= 4.5);
    assertTrue(contrast((String) dark.get("text"), (String) dark.get("background")) >= 4.5);
    assertTrue(contrast((String) light.get("primaryText"), (String) light.get("surface")) >= 4.5);
    assertTrue(contrast((String) dark.get("primaryText"), (String) dark.get("surface")) >= 4.5);
    assertTrue(contrast((String) light.get("navText"), (String) light.get("navBackground")) >= 4.5);
    assertTrue(contrast((String) dark.get("navText"), (String) dark.get("navBackground")) >= 4.5);
    assertAccessible(theme, "custom yellow/purple");
  }

  @Test
  void alreadyMaterializedThemeCanBeReadAndNormalizedAgain() {
    Map<String, Object> original = InstitutionThemeTokens.normalize(Map.of(
        "schemaVersion", 2, "presetId", "academic-blue", "appearance", "dark"));
    Map<String, Object> loaded = InstitutionThemeTokens.normalize(original);
    assertEquals(original, loaded);
  }

  private static void assertAccessible(Map<String, Object> theme, String description) {
    for (String mode : List.of("light", "dark")) {
      Map<?, ?> colors = (Map<?, ?>) theme.get(mode);
      for (String pair : List.of(
          "text|background", "text|surface", "textMuted|background", "textMuted|surface",
          "primaryText|surface", "accentText|surface", "onPrimary|primary", "onAccent|accent",
          "onPrimaryContainer|primaryContainer", "onSuccess|success", "onWarning|warning",
          "onError|error", "onSuccessContainer|successContainer",
          "onWarningContainer|warningContainer", "onErrorContainer|errorContainer",
          "selectedText|selectedBackground", "navText|navBackground",
          "navActiveText|navActiveBackground", "disabledText|disabledBackground")) {
        String[] roles = pair.split("\\|");
        assertTrue(contrast((String) colors.get(roles[0]), (String) colors.get(roles[1])) >= 4.5,
            description + " " + mode + " contrast " + pair);
      }
      assertTrue(contrast((String) colors.get("focus"), (String) colors.get("surface")) >= 3.0,
          description + " " + mode + " focus contrast");
      assertTrue(contrast((String) colors.get("border"), (String) colors.get("background")) >= 3.0,
          description + " " + mode + " control border/background contrast");
      assertTrue(contrast((String) colors.get("border"), (String) colors.get("surface")) >= 3.0,
          description + " " + mode + " control border/surface contrast");
      assertTrue(contrast((String) colors.get("border"), (String) colors.get("surfaceElevated")) >= 3.0,
          description + " " + mode + " control border/elevated-surface contrast");
    }
  }

  private static double contrast(String first, String second) {
    double a = luminance(first);
    double b = luminance(second);
    return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
  }

  private static double luminance(String color) {
    int r = Integer.parseInt(color.substring(1, 3), 16);
    int g = Integer.parseInt(color.substring(3, 5), 16);
    int b = Integer.parseInt(color.substring(5, 7), 16);
    return 0.2126 * linear(r / 255.0) + 0.7152 * linear(g / 255.0) + 0.0722 * linear(b / 255.0);
  }

  private static double linear(double value) {
    return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
  }
}
