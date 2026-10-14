package com.quantaedge.api;

import tools.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Routes tutor inference to the private in-house model first.
 * The hosted API client is usable only when the fallback feature flag is explicitly enabled.
 */
@Component
public class TutorProviderRouter {
  private final OpenAiTutorClient primary;
  private final OpenAiTutorClient fallback;
  private final boolean fallbackEnabled;
  private final int minOutputChars;

  public TutorProviderRouter(ObjectMapper mapper, Environment env) {
    boolean tutorEnabled=env.getProperty("app.ai-tutor.enabled", Boolean.class, false);
    int maxOutputTokens=env.getProperty("app.ai-tutor.max-output-tokens", Integer.class, 500);
    this.minOutputChars=Math.max(1,env.getProperty("app.ai-tutor.min-output-chars", Integer.class, 8));

    this.primary=new OpenAiTutorClient(
        mapper,
        tutorEnabled,
        env.getProperty("app.ai-tutor.primary.api-key", "local-inference"),
        env.getProperty("app.ai-tutor.primary.model", "qwen2.5:0.5b"),
        env.getProperty("app.ai-tutor.primary.base-url", "http://ollama:11434"),
        maxOutputTokens,
        env.getProperty("app.ai-tutor.primary.api-mode", "CHAT_COMPLETIONS"));

    this.fallbackEnabled=tutorEnabled
        && env.getProperty("app.ai-tutor.fallback.enabled", Boolean.class, false);
    this.fallback=new OpenAiTutorClient(
        mapper,
        this.fallbackEnabled,
        env.getProperty("app.ai-tutor.fallback.api-key", ""),
        env.getProperty("app.ai-tutor.fallback.model", "gpt-5.6-luna"),
        env.getProperty("app.ai-tutor.fallback.base-url", "https://api.openai.com"),
        maxOutputTokens,
        env.getProperty("app.ai-tutor.fallback.api-mode", "RESPONSES"));
  }

  /** True means at least one explicitly enabled provider has complete configuration, not live health. */
  public boolean isConfigured() {
    return primary.isConfigured() || canUseFallback();
  }

  /** Retained for source compatibility; persisted replies use the provider actually selected. */
  public String model() {
    return primary.model();
  }

  public TutorReply generate(String instructions, String input) {
    if (!primary.isConfigured()) {
      if (canUseFallback()) return generateFallback(instructions, input);
      throw new TutorUnavailableException("The in-house AI tutor is disabled or not configured");
    }

    try {
      TutorReply local=primary.generate(instructions, input);
      if (canUseFallback() && isLowQuality(local)) {
        return generateFallback(instructions, input);
      }
      return local.withModelLabel("in-house/" + primary.model());
    } catch (TutorUnavailableException | TutorProviderException ex) {
      if (!canUseFallback()) throw ex;
      return generateFallback(instructions, input);
    }
  }

  private TutorReply generateFallback(String instructions, String input) {
    try {
      TutorReply reply=fallback.generate(instructions, input);
      return reply.withModelLabel("api-fallback/" + fallback.model());
    } catch (TutorUnavailableException | TutorProviderException ex) {
      throw new TutorProviderException("The in-house tutor and the explicitly enabled fallback are both unavailable", ex);
    }
  }

  private boolean canUseFallback() {
    return fallbackEnabled && fallback.isConfigured();
  }

  /**
   * Conservative structural quality gate, not a claim of semantic correctness.
   * Semantic quality must be measured with reviewed curriculum examples and teacher evaluation.
   */
  private boolean isLowQuality(TutorReply reply) {
    if (reply == null || reply.text() == null) return true;
    String text=reply.text().trim();
    if (text.isEmpty() || text.length() > 20000) return true;
    long visible=text.codePoints().filter(ch -> !Character.isWhitespace(ch) && !Character.isISOControl(ch)).count();
    if (visible < minOutputChars) return true;
    String normalized=text.toLowerCase(java.util.Locale.ROOT);
    return normalized.startsWith("{\"error\"")
        || normalized.startsWith("error:")
        || normalized.contains("ollama server is loading")
        || normalized.contains("model not found")
        || normalized.contains("connection refused");
  }
}
