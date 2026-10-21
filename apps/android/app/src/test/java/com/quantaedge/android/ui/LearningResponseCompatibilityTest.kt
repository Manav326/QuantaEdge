package com.quantaedge.android.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningResponseCompatibilityTest {
    @Test
    fun optionalStructuredLessonFiltersOnlyDuplicateLegacyMcqs() {
        val legacyLesson = JSONObject(
            """
            {
              "questions": [
                {"id": 7, "question_type": "MCQ"},
                {"id": 8, "question_type": "SHORT_ANSWER"}
              ]
            }
            """.trimIndent()
        )
        val structuredLesson = JSONObject(
            """
            {
              "artifact_id": "db-lesson-9",
              "practice": {
                "questions": [
                  {"id": "db-q-7", "type": "multiple_choice"},
                  {"id": "db-q-8", "type": "short_answer"},
                  {"id": "structured-q-1", "type": "matching"}
                ]
              }
            }
            """.trimIndent()
        )

        val filtered = structuredLessonWithoutLegacyMcqDuplicates(structuredLesson, legacyLesson)
        val remaining = filtered.getJSONObject("practice").getJSONArray("questions")

        assertEquals(2, remaining.length())
        assertEquals("db-q-8", remaining.getJSONObject(0).getString("id"))
        assertEquals("structured-q-1", remaining.getJSONObject(1).getString("id"))

        // The original lesson payload remains reusable by the legacy answer flow.
        assertEquals(3, structuredLesson.getJSONObject("practice").getJSONArray("questions").length())
    }

    @Test
    fun savedLegacyAnswersRestoreFeedbackAndRetryPolicy() {
        val lesson = JSONObject(
            """
            {
              "questions": [
                {"id": 1, "attempted": true, "saved_answer": "B", "saved_correct": false},
                {"id": 2, "attempted": true, "saved_answer": "yes", "saved_correct": true},
                {"id": 3, "attempted": true, "saved_answer": "written", "saved_correct": null},
                {"id": 4, "attempted": false, "saved_answer": "not saved"}
              ]
            }
            """.trimIndent()
        )

        val restored = restoreLegacyLessonAnswers(lesson, "en")

        assertEquals(mapOf("1" to "B", "2" to "yes", "3" to "written"), restored.answers)
        assertFalse(restored.answerLocks["1"] == true)
        assertTrue(restored.answerLocks["2"] == true)
        assertTrue(restored.answerLocks["3"] == true)
        assertFalse(restored.answers.containsKey("4"))
        assertTrue(restored.feedbacks["1"]!!.contains("try again"))
    }
}
