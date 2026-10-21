package com.quantaedge.android.domain

/** Locks correct or ungraded persisted answers but allows retry after a graded wrong answer. */
object LessonAnswerPolicy {
    fun shouldLock(correct: Boolean?): Boolean = correct != false
}
