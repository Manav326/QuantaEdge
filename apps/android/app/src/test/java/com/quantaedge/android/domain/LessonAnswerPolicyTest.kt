package com.quantaedge.android.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LessonAnswerPolicyTest {
    @Test fun wrongGradedAnswerCanBeRetried() {
        assertFalse(LessonAnswerPolicy.shouldLock(false))
    }

    @Test fun correctGradedAnswerIsLocked() {
        assertTrue(LessonAnswerPolicy.shouldLock(true))
    }

    @Test fun savedUngradedAnswerIsLocked() {
        assertTrue(LessonAnswerPolicy.shouldLock(null))
    }
}
