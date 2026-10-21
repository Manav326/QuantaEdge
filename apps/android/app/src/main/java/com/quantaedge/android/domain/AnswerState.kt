package com.quantaedge.android.domain

/** Mirrors persisted practice behaviour: incorrect auto-graded answers may be retried. */
data class PracticeQuestionState(val answered: Boolean, val lastCorrect: Boolean?, val attemptCount: Int) {
    fun canSubmit(answer: String, saving: Boolean): Boolean = !saving && !answered && answer.isNotBlank()
    fun statusMessage(): String? = when {
        answered && lastCorrect == true -> "उत्तर सही है और सुरक्षित है।"
        answered && lastCorrect == null -> "उत्तर सुरक्षित है; मूल्यांकन बाकी है।"
        !answered && lastCorrect == false && attemptCount > 0 -> "पिछला उत्तर सही नहीं था। फिर प्रयास करें।"
        else -> null
    }
}
