package com.quantaedge.android.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Redacts grading-only fields recursively before protected assessment JSON is written locally.
 * Learner responses are stored separately in the encrypted answers map; generic answer fields
 * in question snapshots are not trusted because their meaning can vary by API response.
 */
internal object OfflineAssessmentSanitizer {
    private val forbiddenFields = setOf(
        "answer", "answerkey", "answerpayload", "correctanswer", "correctoption", "correctoptions",
        "expectedanswer", "acceptedanswers", "iscorrect", "correct", "solution", "solutionsteps",
        "markingscheme", "rubric", "gradingnotes", "gradingfeedback", "graderationale",
        "marksawarded", "pointsawarded", "earnedmarks", "score", "evaluation"
    )

    fun sanitize(source: JSONObject): JSONObject {
        val result = JSONObject()
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (normalize(key) in forbiddenFields) continue
            val value = source.opt(key)
            when (value) {
                is JSONObject -> result.put(key, sanitize(value))
                is JSONArray -> result.put(key, sanitize(value))
                else -> result.put(key, value ?: JSONObject.NULL)
            }
        }
        return result
    }

    /**
     * Called only with authenticated server responses. Released results may legitimately contain
     * correct answers and explanations for student review; unreleased responses remain redacted.
     */
    fun sanitizeServerResult(source: JSONObject): JSONObject {
        val released = source.optBoolean("resultReleased") ||
            source.optString("status").equals("RELEASED", ignoreCase = true)
        return if (released) JSONObject(source.toString()) else sanitize(source)
    }

    private fun sanitize(source: JSONArray): JSONArray {
        val result = JSONArray()
        for (index in 0 until source.length()) {
            val value = source.opt(index)
            result.put(when (value) {
                is JSONObject -> sanitize(value)
                is JSONArray -> sanitize(value)
                else -> value ?: JSONObject.NULL
            })
        }
        return result
    }

    private fun normalize(fieldName: String): String =
        fieldName.filter { it.isLetterOrDigit() }.lowercase()
}
