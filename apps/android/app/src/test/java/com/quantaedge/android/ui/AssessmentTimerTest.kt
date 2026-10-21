package com.quantaedge.android.ui

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssessmentTimerTest {
    @Test
    fun countdownRespectsIsoOffsetAndRemainingSeconds() {
        val now = Instant.parse("2026-10-12T15:30:00Z").toEpochMilli()
        assertEquals(
            60L,
            AssessmentTimer.remainingSeconds("2026-10-12T21:01:00+05:30", now)
        )
    }

    @Test
    fun expiredDeadlineClampsToZero() {
        val now = Instant.parse("2026-10-12T15:31:00Z").toEpochMilli()
        assertEquals(
            0L,
            AssessmentTimer.remainingSeconds("2026-10-12T21:01:00+05:30", now)
        )
    }

    @Test
    fun invalidOrMissingDeadlineDoesNotInventATimer() {
        assertNull(AssessmentTimer.remainingSeconds("", 0L))
        assertNull(AssessmentTimer.remainingSeconds("not-a-date", 0L))
    }
}
