package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
  void answersTutorLanguageQuestionsWithoutCallingTheModel() {
    assertTrue(TutorService.currentTutorMetaAnswer("en",
        "Are you responding in English or Hindi?", "Patterns in Mathematics")
        .contains("responding in English"));
    assertTrue(TutorService.currentTutorMetaAnswer("hi",
        "Are you responding in English or Hindi?", "गुणा और आकृतियों में छिपे नियम")
        .contains("हिन्दी में उत्तर"));
  }

  @Test
  void answersTutorIdentityQuestionsWithoutCallingTheModel() {
    assertTrue(TutorService.currentTutorMetaAnswer("en",
        "You are QuantaEdge AI tutor", "Patterns in Mathematics")
        .contains("QuantaEdge’s AI tutor"));
    assertTrue(TutorService.currentTutorMetaAnswer("hi",
        "You are QuantaEdge AI tutor", "गुणा और आकृतियों में छिपे नियम")
        .contains("QuantaEdge AI Tutor"));
  }

  @Test
  void leavesNormalLearningQuestionsToTheModel() {
    org.junit.jupiter.api.Assertions.assertNull(TutorService.currentTutorMetaAnswer(
        "en", "Explain number patterns", "Patterns in Mathematics"));
  }

  @Test
  void answersInTheExplicitlyRequestedLanguageWithoutBlockingTheQuestion() {
    assertEquals("hi", TutorService.detectTutorResponseLanguage("en", "Your response can be in hindi?"));
    assertEquals("en", TutorService.detectTutorResponseLanguage("hi", "Can you answer in English?"));
    assertEquals("en", TutorService.detectTutorResponseLanguage("hi", "What is the difference between addition and subtraction?"));
    assertEquals("hi", TutorService.detectTutorResponseLanguage("en", "जोड़ और घटाव में क्या अंतर है?"));
  }

  @Test
  void addsAReminderOnlyWhenAnswerLanguageDiffersFromLearningLanguage() {
    assertTrue(TutorService.buildLearningLanguageReminder("hi", "en").contains("learning language is set to Hindi"));
    assertTrue(TutorService.buildLearningLanguageReminder("en", "hi").contains("पढ़ाई की भाषा English"));
    assertTrue(TutorService.buildLearningLanguageReminder("en", "en").isEmpty());
  }

  @Test
  void appendsTheReminderAfterTheEducationalAnswer() {
    String answer = TutorService.appendLanguageReminder("5 + 3 = 8.", "Reminder: selected language is Hindi.");
    assertTrue(answer.startsWith("5 + 3 = 8."));
    assertTrue(answer.contains("Reminder: selected language is Hindi."));
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
  void recognizesTheContextlessLessonClarificationDeflection() {
    assertTrue(TutorService.isGenericTutorGreeting(
        "ASSISTANT",
        "Hi there! Can you tell me which lesson you're referring to right now please? I'll be happy to guide you through the information given before checking back in with you."));
  }

  @Test
  void preservesAUsefulClarifyingQuestionAboutTheLearnersConfusion() {
    assertFalse(TutorService.isGenericTutorGreeting(
        "ASSISTANT", "Which step felt confusing? I can explain that part another way."));
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
