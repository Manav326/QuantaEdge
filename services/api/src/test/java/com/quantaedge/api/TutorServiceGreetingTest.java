package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TutorServiceGreetingTest {
  @Test
  void recognizesTheCannedAssistantSupportGreeting() {
    assertTrue(TutorService.isGenericTutorGreeting(
        "ASSISTANT", "Hello! How can we help you today, QuantaEdge Tutor?"));
  }

  @Test
  void recognizesTheEquivalentGenericHindiGreeting() {
    assertTrue(TutorService.isGenericTutorGreeting(
        "ASSISTANT", "नमस्ते, मैं आपकी कैसे मदद कर सकता हूँ?"));
  }

  @Test
  void preservesRealLessonAnswers() {
    assertFalse(TutorService.isGenericTutorGreeting(
        "ASSISTANT",
        "Repeating an experiment helps identify inconsistent readings and reduce the effect of random variation."));
  }

  @Test
  void neverFiltersLearnerMessages() {
    assertFalse(TutorService.isGenericTutorGreeting(
        "USER", "Hello! How can we help you today, QuantaEdge Tutor?"));
  }
}
