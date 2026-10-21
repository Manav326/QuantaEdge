package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class TutorCurriculumRetrieverTest {
  @Test
  void tokenizesEnglishAndHindiLearningTerms() {
    assertTrue(TutorCurriculumRetriever.terms("Explain fractions in सरल हिन्दी").contains("fractions"));
    assertTrue(TutorCurriculumRetriever.terms("Explain fractions in सरल हिन्दी").contains("हिन्दी"));
  }

  @Test
  void ranksContentThatSharesTermsWithTheQuestion() {
    Set<String> query = TutorCurriculumRetriever.terms("plant nutrition photosynthesis");
    Set<String> relevant = TutorCurriculumRetriever.terms("Photosynthesis helps plants make food");
    Set<String> unrelated = TutorCurriculumRetriever.terms("fractions numerator denominator");
    assertTrue(TutorCurriculumRetriever.overlapScore(query, relevant)
        > TutorCurriculumRetriever.overlapScore(query, unrelated));
  }

  @Test
  void returnsNoTermsForBlankInput() {
    assertTrue(TutorCurriculumRetriever.terms("  ").isEmpty());
    assertFalse(TutorCurriculumRetriever.terms("force and motion").isEmpty());
  }
}
