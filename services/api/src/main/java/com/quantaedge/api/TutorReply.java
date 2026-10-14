package com.quantaedge.api;

public record TutorReply(
    String text,
    String providerResponseId,
    Integer inputTokens,
    Integer outputTokens,
    String model
) {
  /** Backwards-compatible constructor for existing client tests. */
  public TutorReply(String text, String providerResponseId, Integer inputTokens, Integer outputTokens) {
    this(text, providerResponseId, inputTokens, outputTokens, "unknown");
  }

  public TutorReply withModelLabel(String label) {
    return new TutorReply(text, providerResponseId, inputTokens, outputTokens, label);
  }
}
