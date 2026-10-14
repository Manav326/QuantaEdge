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
  private final boolean tutorEnabled;
  private final boolean fallbackEnabled;
  private final int minOutputChars;
  private static final long READINESS_CACHE_NANOS=10_000_000_000L;
  private volatile long readinessCheckedAtNanos;
  private volatile boolean readinessValue;

  public TutorProviderRouter(ObjectMapper mapper, Environment env) {
    this.tutorEnabled=env.getProperty("app.ai-tutor.enabled", Boolean.class, false);
    boolean tutorEnabled=this.tutorEnabled;
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

  public boolean isEnabled() {
    return tutorEnabled;
  }

  /** True means at least one explicitly enabled provider has complete configuration, not live health. */
  public boolean isConfigured() {
    return primary.isConfigured() || canUseFallback();
  }

  /** Cached liveness probe only; no student prompt or learner data is sent during readiness checks. */
  public boolean isAvailable() {
    if (!tutorEnabled) return false;
    long now=System.nanoTime();
    if (readinessCheckedAtNanos!=0L && now-readinessCheckedAtNanos<READINESS_CACHE_NANOS) {
      return readinessValue;
    }
    synchronized (this) {
      now=System.nanoTime();
      if (readinessCheckedAtNanos==0L || now-readinessCheckedAtNanos>=READINESS_CACHE_NANOS) {
        boolean available=primary.isReachable();
        if (!available && canUseFallback()) available=fallback.isReachable();
        readinessValue=available;
        readinessCheckedAtNanos=System.nanoTime();
      }
      return readinessValue;
    }
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
      if (canUseFallback() && isLowQuality(local, instructions, input)) {
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
  private boolean isLowQuality(TutorReply reply, String instructions, String input) {
    if (reply == null || reply.text() == null) return true;
    String text=reply.text().trim();
    if (text.isEmpty() || text.length() > 20000) return true;
    long visible=text.codePoints().filter(ch -> !Character.isWhitespace(ch) && !Character.isISOControl(ch)).count();
    if (visible < minOutputChars) return true;
    String normalized=text.toLowerCase(java.util.Locale.ROOT);
    if (normalized.startsWith("{\"error\"") || normalized.startsWith("error:")
        || normalized.contains("ollama server is loading") || normalized.contains("model not found")
        || normalized.contains("connection refused")) return true;

    // Catch a strong language mismatch against the selected learner language. This is a
    // lightweight quality signal, not a semantic correctness detector.
    String prompt=(input==null?"":input).toLowerCase(java.util.Locale.ROOT);
    boolean expectsEnglish=prompt.contains("response language: en");
    boolean expectsHindi=prompt.contains("response language: hi");
    long letters=0, devanagari=0;
    for (int offset=0; offset<text.length();) {
      int cp=text.codePointAt(offset);
      if (Character.isLetter(cp)) {
        letters++;
        if (Character.UnicodeScript.of(cp)==Character.UnicodeScript.DEVANAGARI) devanagari++;
      }
      offset+=Character.charCount(cp);
    }
    if (letters>=24 && expectsEnglish && devanagari/(double)letters>=0.15) return true;
    if (letters>=24 && expectsHindi && devanagari/(double)letters<0.05) return true;
    return false;
  }
}
