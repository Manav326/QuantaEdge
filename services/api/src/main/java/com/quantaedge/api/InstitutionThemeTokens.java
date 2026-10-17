package com.quantaedge.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Canonical institution colour-theme contract.
 *
 * Tenant input is intentionally limited to a palette preset, approved brand colours,
 * and an appearance preference. Complete semantic light/dark palettes are generated
 * centrally so web and Android consume the same values rather than deriving shades
 * independently. Legacy five-colour records are accepted and normalized on write.
 */
public final class InstitutionThemeTokens {
  public static final int SCHEMA_VERSION = 2;
  public static final String DEFAULT_PRESET = "quantaedge-orange";
  public static final String DEFAULT_APPEARANCE = "system";

  private static final Set<String> INPUT_KEYS = Set.of(
      "schemaVersion", "presetId", "appearance",
      "primary", "accent", "background", "surface", "text",
      "brandPrimary", "brandAccent");
  private static final Map<String, Preset> PRESETS = createPresets();

  private InstitutionThemeTokens() {}

  public static List<Map<String, Object>> presets() {
    List<Map<String, Object>> result = new ArrayList<>();
    for (Preset preset : PRESETS.values()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", preset.id());
      item.put("name", preset.name());
      item.put("primary", preset.primary());
      item.put("accent", preset.accent());
      item.put("themeTokens", normalize(Map.of(
          "schemaVersion", SCHEMA_VERSION,
          "presetId", preset.id(),
          "appearance", DEFAULT_APPEARANCE)));
      result.add(item);
    }
    return result;
  }

  public static Map<String, Object> normalize(Object raw) {
    if (raw != null && !(raw instanceof Map<?, ?>)) {
      throw new IllegalArgumentException("themeTokens must be an object of approved theme settings.");
    }
    Map<?, ?> input = raw instanceof Map<?, ?> map ? map : Map.of();

    // Stored v2 records contain generated light/dark token maps. Re-normalize their approved
    // source settings so clients always receive canonical tokens and persisted JSON is never trusted.
    if (input.get("schemaVersion") instanceof Number version && version.intValue() == SCHEMA_VERSION
        && input.get("light") instanceof Map<?, ?> && input.get("dark") instanceof Map<?, ?>) {
      Map<String, Object> source = new LinkedHashMap<>();
      for (String key : List.of("schemaVersion", "presetId", "appearance", "brandPrimary", "brandAccent")) {
        if (input.containsKey(key)) source.put(key, input.get(key));
      }
      return normalize(source);
    }

    for (Object key : input.keySet()) {
      if (!INPUT_KEYS.contains(String.valueOf(key))) {
        throw new IllegalArgumentException("Unsupported theme setting: " + key);
      }
    }

    boolean legacy = !input.containsKey("schemaVersion");
    if (!legacy) {
      Object version = input.get("schemaVersion");
      if (!(version instanceof Number number) || number.intValue() != SCHEMA_VERSION) {
        throw new IllegalArgumentException("Unsupported themeTokens schemaVersion; expected 2.");
      }
    }

    String presetId = stringValue(input.get("presetId"), DEFAULT_PRESET).trim().toLowerCase(Locale.ROOT);
    Preset preset = PRESETS.get(presetId);
    if (preset == null && !"custom".equals(presetId)) {
      throw new IllegalArgumentException("Unknown institution colour preset: " + presetId);
    }

    String appearance = stringValue(input.get("appearance"), DEFAULT_APPEARANCE).trim().toLowerCase(Locale.ROOT);
    if (!Set.of("system", "light", "dark").contains(appearance)) {
      throw new IllegalArgumentException("Appearance must be system, light, or dark.");
    }

    String primaryDefault = preset == null ? PRESETS.get(DEFAULT_PRESET).primary() : preset.primary();
    String accentDefault = preset == null ? PRESETS.get(DEFAULT_PRESET).accent() : preset.accent();
    String primary = colorValue(firstPresent(input, "brandPrimary", "primary"), primaryDefault, "primary");
    String accent = colorValue(firstPresent(input, "brandAccent", "accent"), accentDefault, "accent");
    String lightBackground = legacy && input.containsKey("background")
        ? colorValue(input.get("background"), "#F7F8FC", "background") : "#F8FAFC";
    String lightSurface = legacy && input.containsKey("surface")
        ? colorValue(input.get("surface"), "#FFFFFF", "surface") : "#FFFFFF";
    String lightText = legacy && input.containsKey("text")
        ? colorValue(input.get("text"), "#152038", "text") : "#172033";

    Map<String, String> light = palette(primary, accent, false);
    Map<String, String> dark = palette(primary, accent, true);
    if (legacy) {
      light.put("background", lightBackground);
      light.put("onBackground", lightText);
      light.put("surface", lightSurface);
      light.put("onSurface", lightText);
      light.put("surfaceElevated", lightSurface);
      light.put("onSurfaceElevated", lightText);
      light.put("text", lightText);
      if (contrastRatio(lightText, lightBackground) < 4.5
          || contrastRatio(lightText, lightSurface) < 4.5) {
        throw new IllegalArgumentException("Text must have a contrast ratio of at least 4.5:1 against the light background and surface.");
      }
      light.put("border", ensureContrast("#8B95A7", lightBackground, 3.0, true));
      light.put("borderSubtle", ensureContrast("#D1D5DB", lightBackground, 1.5, true));
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("schemaVersion", SCHEMA_VERSION);
    result.put("presetId", presetId);
    result.put("appearance", appearance);
    result.put("brandPrimary", primary);
    result.put("brandAccent", accent);
    // v1 aliases remain for older clients while all new clients use light/dark semantic tokens.
    result.put("primary", primary);
    result.put("accent", accent);
    result.put("background", light.get("background"));
    result.put("surface", light.get("surface"));
    result.put("text", light.get("text"));
    result.put("light", light);
    result.put("dark", dark);
    return result;
  }

  private static Map<String, String> palette(String brandPrimary, String brandAccent, boolean darkMode) {
    String background = darkMode ? "#0B1220" : "#F8FAFC";
    String surface = darkMode ? "#111827" : "#FFFFFF";
    String elevated = darkMode ? "#182235" : "#FFFFFF";
    String text = darkMode ? "#F3F4F6" : "#172033";
    String muted = darkMode ? "#D1D5DB" : "#475467";
    String border = darkMode ? "#6B7280" : "#737D91";
    String borderSubtle = darkMode ? "#4B5563" : "#D1D5DB";
    String primaryContainer = mix(surface, brandPrimary, darkMode ? 0.28 : 0.10);
    String accentContainer = mix(surface, brandAccent, darkMode ? 0.26 : 0.10);
    // Text/accent roles appear on both neutral surfaces and their tinted containers. Check both
    // backgrounds rather than merely ensuring contrast on white/black, which can fail on a tint.
    String primaryText = ensureContrast(
        ensureContrast(brandPrimary, surface, 4.5, !darkMode), primaryContainer, 4.5, !darkMode);
    String accentText = ensureContrast(
        ensureContrast(brandAccent, surface, 4.5, !darkMode), accentContainer, 4.5, !darkMode);
    String success = darkMode ? "#4ADE80" : "#166534";
    String warning = darkMode ? "#FCD34D" : "#92400E";
    String error = darkMode ? "#FDA4AF" : "#B42318";
    String disabledBg = darkMode ? "#273244" : "#E5E7EB";
    String disabledText = darkMode ? "#9CA3AF" : "#4B5563";
    String navBackground = darken(brandPrimary, 0.22);
    // Keep nav chrome intentionally dark even for light mode; its contents remain contrast-safe.
    String navActiveBackground = brandPrimary;
    String selectedBackground = mix(background, brandPrimary, darkMode ? 0.32 : 0.10);
    String selectedText = ensureContrast(ensureContrast(brandPrimary, surface, 4.5, !darkMode),
        selectedBackground, 4.5, !darkMode);
    String onPrimary = bestContrastText(brandPrimary);
    String onAccent = bestContrastText(brandAccent);

    Map<String, String> c = new LinkedHashMap<>();
    c.put("primary", brandPrimary);
    c.put("onPrimary", onPrimary);
    c.put("primaryText", primaryText);
    c.put("primaryContainer", primaryContainer);
    c.put("onPrimaryContainer", bestContrastText(primaryContainer));
    c.put("accent", brandAccent);
    c.put("onAccent", onAccent);
    c.put("accentText", accentText);
    c.put("accentContainer", accentContainer);
    c.put("onAccentContainer", bestContrastText(accentContainer));
    c.put("background", background);
    c.put("onBackground", text);
    c.put("surface", surface);
    c.put("onSurface", text);
    c.put("surfaceElevated", elevated);
    c.put("onSurfaceElevated", text);
    c.put("text", text);
    c.put("textMuted", muted);
    c.put("border", border);
    c.put("borderSubtle", borderSubtle);
    c.put("focus", ensureContrast(brandPrimary, surface, 3.0, !darkMode));
    c.put("success", success);
    c.put("onSuccess", darkMode ? "#052E16" : "#FFFFFF");
    c.put("successContainer", darkMode ? "#14532D" : "#DCFCE7");
    c.put("onSuccessContainer", darkMode ? "#DCFCE7" : "#14532D");
    c.put("warning", warning);
    c.put("onWarning", darkMode ? "#422006" : "#FFFFFF");
    c.put("warningContainer", darkMode ? "#713F12" : "#FEF3C7");
    c.put("onWarningContainer", darkMode ? "#FEF3C7" : "#78350F");
    c.put("error", error);
    c.put("onError", darkMode ? "#450A0A" : "#FFFFFF");
    c.put("errorContainer", darkMode ? "#7F1D1D" : "#FEE2E2");
    c.put("onErrorContainer", darkMode ? "#FEE2E2" : "#7F1D1D");
    c.put("selectedBackground", selectedBackground);
    c.put("selectedText", selectedText);
    c.put("navBackground", navBackground);
    c.put("navText", "#FFFFFF");
    c.put("navActiveBackground", navActiveBackground);
    c.put("navActiveText", onPrimary);
    c.put("disabledBackground", disabledBg);
    c.put("disabledText", disabledText);
    return c;
  }

  private static Map<String, Preset> createPresets() {
    Map<String, Preset> values = new LinkedHashMap<>();
    add(values, "quantaedge-orange", "QuantaEdge Orange", "#F97316", "#C2410C");
    add(values, "academic-blue", "Academic Blue", "#2563EB", "#1D4ED8");
    add(values, "forest-green", "Forest Green", "#15803D", "#166534");
    add(values, "royal-violet", "Royal Violet", "#7C3AED", "#6D28D9");
    add(values, "teal", "Teal", "#0F766E", "#115E59");
    add(values, "burgundy", "Burgundy", "#9F1239", "#881337");
    add(values, "navy-gold", "Navy & Gold", "#1E3A8A", "#92400E");
    add(values, "graphite", "Graphite", "#374151", "#1F2937");
    add(values, "indigo", "Indigo", "#4338CA", "#3730A3");
    add(values, "emerald", "Emerald", "#047857", "#065F46");
    add(values, "plum", "Plum", "#6B21A8", "#581C87");
    add(values, "maroon", "Maroon", "#991B1B", "#7F1D1D");
    add(values, "raspberry", "Raspberry", "#BE185D", "#9D174D");
    add(values, "cobalt", "Cobalt", "#1D4ED8", "#1E40AF");
    add(values, "slate", "Slate", "#475569", "#334155");
    add(values, "custom", "Custom colours", "#F97316", "#C2410C");
    return values;
  }

  private static void add(Map<String, Preset> values, String id, String name, String primary, String accent) {
    values.put(id, new Preset(id, name, primary, accent));
  }

  private static Object firstPresent(Map<?, ?> input, String first, String second) {
    return input.containsKey(first) ? input.get(first) : input.get(second);
  }

  private static String stringValue(Object value, String fallback) {
    return value == null ? fallback : String.valueOf(value);
  }

  private static String colorValue(Object raw, String fallback, String name) {
    if (raw == null) return fallback;
    String value = String.valueOf(raw).trim();
    if (!value.matches("^#[0-9A-Fa-f]{6}$")) {
      throw new IllegalArgumentException("Theme colour '" + name + "' must use #RRGGBB format.");
    }
    return value.toUpperCase(Locale.ROOT);
  }

  private static String bestContrastText(String background) {
    String nearBlack = "#111827";
    String white = "#FFFFFF";
    return contrastRatio(nearBlack, background) >= contrastRatio(white, background) ? nearBlack : white;
  }

  private static String ensureContrast(String foreground, String background, double target, boolean darken) {
    if (contrastRatio(foreground, background) >= target) return foreground;
    String destination = darken ? "#111827" : "#F9FAFB";
    for (int i = 1; i <= 100; i++) {
      String candidate = mix(foreground, destination, i / 100.0);
      if (contrastRatio(candidate, background) >= target) return candidate;
    }
    return destination;
  }

  private static String mix(String base, String target, double amount) {
    int[] a = rgb(base);
    int[] b = rgb(target);
    int r = (int)Math.round(a[0] * (1 - amount) + b[0] * amount);
    int g = (int)Math.round(a[1] * (1 - amount) + b[1] * amount);
    int bl = (int)Math.round(a[2] * (1 - amount) + b[2] * amount);
    return String.format(Locale.ROOT, "#%02X%02X%02X", r, g, bl);
  }

  private static String darken(String color, double multiplier) {
    int[] a = rgb(color);
    return String.format(Locale.ROOT, "#%02X%02X%02X",
        (int)Math.round(a[0] * multiplier), (int)Math.round(a[1] * multiplier), (int)Math.round(a[2] * multiplier));
  }

  private static int[] rgb(String color) {
    return new int[] {
        Integer.parseInt(color.substring(1, 3), 16),
        Integer.parseInt(color.substring(3, 5), 16),
        Integer.parseInt(color.substring(5, 7), 16)
    };
  }

  private static double contrastRatio(String foreground, String background) {
    double a = luminance(foreground);
    double b = luminance(background);
    return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
  }

  private static double luminance(String color) {
    int[] channels = rgb(color);
    double r = linear(channels[0] / 255.0);
    double g = linear(channels[1] / 255.0);
    double b = linear(channels[2] / 255.0);
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
  }

  private static double linear(double channel) {
    return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
  }

  private record Preset(String id, String name, String primary, String accent) {}
}
