package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InstitutionThemeAccentContrastTest {
  @Test
  void accentTextAndForegroundsRemainReadableOnAccentContainersForEveryPreset() {
    for (Map<String, Object> preset : InstitutionThemeTokens.presets()) {
      Map<String, Object> theme = InstitutionThemeTokens.normalize(preset.get("themeTokens"));
      for (String mode : List.of("light", "dark")) {
        Map<?, ?> palette = (Map<?, ?>) theme.get(mode);
        String accentText = (String) palette.get("accentText");
        String surface = (String) palette.get("surface");
        String accentContainer = (String) palette.get("accentContainer");
        String onAccentContainer = (String) palette.get("onAccentContainer");
        assertTrue(contrast(accentText, surface) >= 4.5,
            "Accent text must contrast on a neutral surface: " + preset.get("id") + "/" + mode);
        assertTrue(contrast(accentText, accentContainer) >= 4.5,
            "Accent text must contrast on its tinted container: " + preset.get("id") + "/" + mode);
        assertTrue(contrast(onAccentContainer, accentContainer) >= 4.5,
            "Container foreground must contrast on its tint: " + preset.get("id") + "/" + mode);
      }
    }
  }

  private static double contrast(String foreground, String background) {
    double a = luminance(foreground);
    double b = luminance(background);
    return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
  }

  private static double luminance(String hex) {
    int red = Integer.parseInt(hex.substring(1, 3), 16);
    int green = Integer.parseInt(hex.substring(3, 5), 16);
    int blue = Integer.parseInt(hex.substring(5, 7), 16);
    return 0.2126 * linear(red / 255.0)
        + 0.7152 * linear(green / 255.0)
        + 0.0722 * linear(blue / 255.0);
  }

  private static double linear(double channel) {
    return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
  }
}
