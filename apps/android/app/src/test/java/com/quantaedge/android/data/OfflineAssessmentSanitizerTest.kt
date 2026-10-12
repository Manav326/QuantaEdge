package com.quantaedge.android.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineAssessmentSanitizerTest {
    @Test
    fun removesAnswerKeysRecursivelyButKeepsLearnerResponse() {
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

        assertEquals("B", safe.getString("answer"))
        assertEquals("B", question.getString("answer"))
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
    fun unreleasedServerResponseRemainsRedactedButReleasedReviewKeepsCorrectAnswer() {
        val unreleased = JSONObject()
            .put("status", "AWAITING_REVIEW")
            .put("resultReleased", false)
            .put("questions", org.json.JSONArray().put(
                JSONObject().put("correctAnswer", "A").put("answer", "B")
            ))
        val safeUnreleased = OfflineAssessmentSanitizer.sanitizeServerResult(unreleased)
        assertFalse(safeUnreleased.getJSONArray("questions").getJSONObject(0).has("correctAnswer"))

        val released = JSONObject()
            .put("status", "RELEASED")
            .put("resultReleased", true)
            .put("questions", org.json.JSONArray().put(
                JSONObject().put("correctAnswer", "A").put("answer", "B")
            ))
        val safeReleased = OfflineAssessmentSanitizer.sanitizeServerResult(released)
        assertEquals("A", safeReleased.getJSONArray("questions").getJSONObject(0).getString("correctAnswer"))
        assertEquals("B", safeReleased.getJSONArray("questions").getJSONObject(0).getString("answer"))
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
