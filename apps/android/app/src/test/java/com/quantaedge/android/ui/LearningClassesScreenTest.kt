package com.quantaedge.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LearningClassesScreenTest {
    private val now = Instant.parse("2026-10-17T10:00:00Z").toEpochMilli()
    private val end = "2026-10-17T11:30:00Z"

    @Test fun thirtyMinuteWindowHonoursExactBoundary() {
        assertFalse(isLiveClassJoinWindowOpen("SCHEDULED","2026-10-17T10:31:00Z",end,30,now))
        assertTrue(isLiveClassJoinWindowOpen("SCHEDULED","2026-10-17T10:30:00Z",end,30,now))
    }
    @Test fun zeroRemovesOnlyPreStartGate() {
        assertTrue(isLiveClassJoinWindowOpen("SCHEDULED","2026-10-17T12:00:00Z","2026-10-17T13:00:00Z",0,now))
        assertFalse(isLiveClassJoinWindowOpen("COMPLETED","2026-10-17T12:00:00Z","2026-10-17T13:00:00Z",0,now))
        assertFalse(isLiveClassJoinWindowOpen("LIVE","2026-10-17T09:00:00Z","2026-10-17T09:59:59Z",0,now))
    }
    @Test fun fifteenMinuteDefaultUsesTheSameBoundaryAsHint() {
        val startIn16 = "2026-10-17T10:16:00Z"
        assertFalse(isLiveClassJoinWindowOpen("SCHEDULED",startIn16,end,15,now))
        assertTrue(isLiveClassWaitingForJoinWindow("SCHEDULED",startIn16,end,15,now))
        assertTrue(isLiveClassJoinWindowOpen("SCHEDULED","2026-10-17T10:15:00Z",end,15,now))
        assertFalse(isLiveClassWaitingForJoinWindow("SCHEDULED","2026-10-17T10:15:00Z",end,15,now))
    }
    @Test fun malformedTimestampFailsClosed() {
        assertFalse(isLiveClassJoinWindowOpen("LIVE","not-a-timestamp",end,0,now))
    }
    @Test fun offsetlessTimestampCanBeParsed() {
        assertTrue(parseLiveClassTimestampMillis("2026-10-17T10:00:00") != null)
    }
}
