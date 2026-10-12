package com.quantaedge.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerStateTest {
    @Test fun ungradedSavedAnswerCannotBeSubmittedTwice() {
        val state = PracticeQuestionState(true, null, 1)
        assertFalse(state.canSubmit("answer", false))
        assertEquals("उत्तर सुरक्षित है; मूल्यांकन बाकी है।", state.statusMessage())
    }
    @Test fun incorrectAnswerCanBeRetried() {
        val state = PracticeQuestionState(false, false, 1)
        assertTrue(state.canSubmit("revised answer", false))
        assertFalse(state.canSubmit("", false))
        assertFalse(state.canSubmit("revised answer", true))
    }
    @Test fun completedCorrectAnswerIsLocked() {
        val state = PracticeQuestionState(true, true, 1)
        assertFalse(state.canSubmit("another answer", false))
        assertEquals("उत्तर सही है और सुरक्षित है।", state.statusMessage())
    }
}
