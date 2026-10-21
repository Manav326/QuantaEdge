package com.quantaedge.android.ui

import com.quantaedge.android.data.ApiClient
import com.quantaedge.android.domain.LessonAnswerPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Keeps response transport and learner-state hydration out of the main Compose navigation function. */
internal data class LegacyLessonAnswerRestore(
    val answers: Map<String, String>,
    val feedbacks: Map<String, String>,
    val answerLocks: Map<String, Boolean>
)

internal fun restoreLegacyLessonAnswers(lesson: JSONObject, language: String): LegacyLessonAnswerRestore {
    val answers = linkedMapOf<String, String>()
    val feedbacks = linkedMapOf<String, String>()
    val locks = linkedMapOf<String, Boolean>()
    val questions = lesson.optJSONArray("questions")
    if (questions != null) for (index in 0 until questions.length()) {
        val question = questions.optJSONObject(index) ?: continue
        val questionId = question.optLong("id", question.optLong("questionId", question.optLong("question_id")))
        if (questionId <= 0L || !question.optBoolean("attempted")) continue
        val key = questionId.toString()
        if (question.has("saved_answer") && !question.isNull("saved_answer")) {
            answers[key] = question.optString("saved_answer")
        }
        val correct = if (question.has("saved_correct") && !question.isNull("saved_correct")) {
            question.optBoolean("saved_correct")
        } else null
        locks[key] = LessonAnswerPolicy.shouldLock(correct)
        feedbacks[key] = when (correct) {
            true -> if (language == "hi") "सही उत्तर।" else "Correct answer."
            false -> if (language == "hi") "उत्तर सही नहीं था। दोबारा समझकर प्रयास करें।" else "The saved answer was incorrect. Review and try again."
            null -> if (language == "hi") "उत्तर सुरक्षित है; मूल्यांकन बाकी है।" else "Your answer is saved and may need review."
        }
    }
    return LegacyLessonAnswerRestore(answers, feedbacks, locks)
}

/**
 * Removes only MCQs already shown by the original lesson UI.
 *
 * The remaining structured question types stay available in the optional insert.
 * Work on a copy so opening the optional view never mutates the API response.
 */
internal fun structuredLessonWithoutLegacyMcqDuplicates(
    structuredLesson: JSONObject,
    legacyLesson: JSONObject
): JSONObject {
    val filteredLesson = JSONObject(structuredLesson.toString())
    val legacyQuestions = legacyLesson.optJSONArray("questions") ?: return filteredLesson

    val legacyMcqIds = mutableSetOf<String>()
    for (index in 0 until legacyQuestions.length()) {
        val question = legacyQuestions.optJSONObject(index) ?: continue
        if (!question.optString("question_type").equals("MCQ", ignoreCase = true)) continue

        val questionId = question.optLong("id", question.optLong("question_id", 0L))
        if (questionId > 0L) {
            legacyMcqIds.add("db-q-$questionId")
        }
    }
    if (legacyMcqIds.isEmpty()) return filteredLesson

    val practice = filteredLesson.optJSONObject("practice") ?: return filteredLesson
    val structuredQuestions = practice.optJSONArray("questions") ?: return filteredLesson
    val retainedQuestions = JSONArray()

    for (index in 0 until structuredQuestions.length()) {
        val rawQuestion = structuredQuestions.opt(index)
        val question = rawQuestion as? JSONObject

        // Preserve unknown entries; remove only the exact MCQs shown by the legacy UI.
        if (question == null || !legacyMcqIds.contains(question.optString("id"))) {
            retainedQuestions.put(rawQuestion ?: JSONObject.NULL)
        }
    }

    practice.put("questions", retainedQuestions)
    return filteredLesson
}

internal suspend fun loadStructuredResponsesForLegacyLesson(lesson: JSONObject, courseId: Long, language: String): JSONObject {
    val artifactId = lesson.optJSONObject("structured_lesson")?.optString("artifact_id").orEmpty()
    if (artifactId.isBlank() || courseId <= 0L) return JSONObject()
    return runCatching {
        ApiClient.getObject("/api/v1/learning/structured-lessons/" + ApiClient.encodeQuery(artifactId) +
            "/responses?courseId=" + courseId + "&language=" + language)
    }.getOrDefault(JSONObject())
}

internal suspend fun postStructuredQuestionResponse(
    artifactId: String,
    questionId: String,
    courseId: Long,
    language: String,
    responseJson: String,
    previous: JSONObject
): JSONObject {
    val responseValue = JSONTokener(responseJson).nextValue()
    val result = ApiClient.postObject(
        "/api/v1/learning/structured-lessons/" + ApiClient.encodeQuery(artifactId) +
            "/questions/" + ApiClient.encodeQuery(questionId) + "/responses?courseId=" + courseId + "&language=" + language,
        JSONObject().put("response", responseValue)
    )
    val saved = JSONObject().put("response", responseValue)
        .put("status", result.optString("status"))
        .put("feedback", result.optString("feedback"))
    result.optJSONObject("feedbackByLocale")?.let { saved.put("feedbackByLocale", it) }
    if (result.has("score")) saved.put("score", result.optInt("score"))
    if (result.has("maxScore")) saved.put("maxScore", result.optInt("maxScore"))
    val all = JSONObject(previous.toString())
    val questions = JSONObject(all.optJSONObject("questions")?.toString() ?: "{}")
    questions.put(questionId, saved)
    return all.put("questions", questions)
}

internal suspend fun postStructuredActivityResponse(
    artifactId: String,
    blockId: String,
    courseId: Long,
    responseJson: String,
    previous: JSONObject
): JSONObject {
    ApiClient.postObject(
        "/api/v1/learning/structured-lessons/" + ApiClient.encodeQuery(artifactId) +
            "/activities/" + ApiClient.encodeQuery(blockId) + "/responses?courseId=" + courseId,
        JSONObject().put("response", responseJson)
    )
    val all = JSONObject(previous.toString())
    val activities = JSONObject(all.optJSONObject("activities")?.toString() ?: "{}")
    activities.put(blockId, JSONObject(responseJson))
    return all.put("activities", activities)
}
