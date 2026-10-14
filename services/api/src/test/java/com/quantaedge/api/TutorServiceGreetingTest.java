package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TutorServiceGreetingTest {

  @Test
  void recognizesEnglishQuestionsAskingForTheCurrentTopicName() {
    assertTrue(TutorService.isCurrentTopicNameQuestion("What is name of this topic"));
    assertTrue(TutorService.isCurrentTopicNameQuestion("What's the name of this lesson?"));
    assertTrue(TutorService.isCurrentTopicNameQuestion("What topic are we studying now?"));
  }

  @Test
  void recognizesHindiQuestionsAskingForTheCurrentTopicName() {
    assertTrue(TutorService.isCurrentTopicNameQuestion("इस विषय का नाम क्या है?"));
    assertTrue(TutorService.isCurrentTopicNameQuestion("अभी हम कौन सा टॉपिक पढ़ रहे हैं?"));
  }

  @Test
  void doesNotTreatAnOrdinaryLearningQuestionAsATopicLookup() {
    assertFalse(TutorService.isCurrentTopicNameQuestion("Why should we repeat an experiment?"));
    assertFalse(TutorService.isCurrentTopicNameQuestion("Explain this topic to me."));
  }

  @Test
  void buildsAContextGroundedEnglishTopicAnswer() {
    assertTrue(TutorService.currentTopicAnswer("en", "Food Chains", "Getting to Know Plants", "Science")
        .contains("The topic we’re studying is “Food Chains”."));
  }

  @Test
  void buildsAContextGroundedHindiTopicAnswer() {
    assertTrue(TutorService.currentTopicAnswer("hi", "भिन्न", "पूर्ण संख्याएँ", "गणित")
        .contains("अभी हम “भिन्न” पढ़ रहे हैं।"));
  }

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
