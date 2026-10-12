package com.quantaedge.android.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineAssessmentSanitizerTest {
    @Test
    fun removesAnswerFieldsAndAnswerKeysRecursivelyFromQuestionSnapshots() {
        val input = JSONObject(
            """
            {
              "attemptId": 104,
              "answer": "B",
              "questions": [{
                "prompt": "Choose the matching option.",
                "answer": "B",
                "correctAnswer": "A",
                "answer_payload": {"kind":"MCQ","value":"A"},
                "markingScheme": "SECRET_RUBRIC",
                "options": [
                  {"key":"A","label":"Option A","is_correct":true},
                  {"key":"B","label":"Option B","isCorrect":false}
                ],
                "nested": [{"correct_option":"A","text":"keep this prompt"}]
              }]
            }
            """.trimIndent()
        )

        val safe = OfflineAssessmentSanitizer.sanitize(input)
        val question = safe.getJSONArray("questions").getJSONObject(0)
        val options = question.getJSONArray("options")
        val nested = question.getJSONArray("nested").getJSONObject(0)

        assertFalse(safe.has("answer"))
        assertFalse(question.has("answer"))
        assertFalse(question.has("correctAnswer"))
        assertFalse(question.has("answer_payload"))
        assertFalse(question.has("markingScheme"))
        assertFalse(options.getJSONObject(0).has("is_correct"))
        assertFalse(options.getJSONObject(1).has("isCorrect"))
        assertFalse(nested.has("correct_option"))
        assertEquals("keep this prompt", nested.getString("text"))
        assertTrue(safe.toString().contains("SECRET_RUBRIC").not())
    }

    @Test
    fun removesAdditionalGradingMetadataRecursivelyButKeepsLearningContent() {
        val input = JSONObject(
            """
            {
              "status": "IN_PROGRESS",
              "score": 10,
              "questions": [{
                "answer": "student response",
                "expected_answer": "teacher key",
                "acceptedAnswers": ["secret one", "secret two"],
                "grading_notes": "private examiner notes",
                "gradingFeedback": "feedback not released yet",
                "marks_awarded": 3,
                "pointsAwarded": 2,
                "earned_marks": 1,
                "solution_steps": ["private solution"],
                "evaluation": {"grade": "A"},
                "explanation": "published learning explanation",
                "prompt": "Explain your reasoning."
              }]
            }
            """.trimIndent()
        )

        val safe = OfflineAssessmentSanitizer.sanitize(input)
        val question = safe.getJSONArray("questions").getJSONObject(0)

        assertFalse(safe.has("score"))
        assertFalse(question.has("expected_answer"))
        assertFalse(question.has("acceptedAnswers"))
        assertFalse(question.has("grading_notes"))
        assertFalse(question.has("gradingFeedback"))
        assertFalse(question.has("marks_awarded"))
        assertFalse(question.has("pointsAwarded"))
        assertFalse(question.has("earned_marks"))
        assertFalse(question.has("solution_steps"))
        assertFalse(question.has("evaluation"))
        assertFalse(question.has("answer"))
        assertEquals("published learning explanation", question.getString("explanation"))
        assertEquals("Explain your reasoning.", question.getString("prompt"))
        assertFalse(safe.toString().contains("private examiner notes"))
        assertFalse(safe.toString().contains("teacher key"))
    }

    @Test
    fun unreleasedServerResponseRemainsRedactedButReleasedReviewKeepsCorrectAnswer() {
        val unreleased = JSONObject()
            .put("status", "AWAITING_REVIEW")
            .put("resultReleased", false)
            .put("questions", org.json.JSONArray().put(
                JSONObject().put("correctAnswer", "A").put("answer", "B")
                    .put("grading_notes", "secret")
            ))
        val safeUnreleased = OfflineAssessmentSanitizer.sanitizeServerResult(unreleased)
        val unreleasedQuestion = safeUnreleased.getJSONArray("questions").getJSONObject(0)
        assertFalse(unreleasedQuestion.has("correctAnswer"))
        assertFalse(unreleasedQuestion.has("answer"))
        assertFalse(unreleasedQuestion.has("grading_notes"))

        val released = JSONObject()
            .put("status", "RELEASED")
            .put("resultReleased", true)
            .put("questions", org.json.JSONArray().put(
                JSONObject().put("correctAnswer", "A").put("answer", "B")
                    .put("grading_notes", "released review metadata")
            ))
        val safeReleased = OfflineAssessmentSanitizer.sanitizeServerResult(released)
        val releasedQuestion = safeReleased.getJSONArray("questions").getJSONObject(0)
        assertEquals("A", releasedQuestion.getString("correctAnswer"))
        assertEquals("B", releasedQuestion.getString("answer"))
        assertEquals("released review metadata", releasedQuestion.getString("grading_notes"))
    }

    @Test
    fun removesCommonNamingVariantsCaseInsensitively() {
        val input = JSONObject()
            .put("Correct_Answer", "SECRET_A")
            .put("answer-Key", "SECRET_B")
            .put("SOLUTION", "SECRET_C")
            .put("response", "learner value")

        val safe = OfflineAssessmentSanitizer.sanitize(input)
        assertEquals("learner value", safe.getString("response"))
        assertFalse(safe.has("Correct_Answer"))
        assertFalse(safe.has("answer-Key"))
        assertFalse(safe.has("SOLUTION"))
    }
}
