package com.quantaedge.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineLearningVideoContractTest {
    @Test fun onlyTheRecordingApiMediaTypesAreAccepted() {
        assertEquals("mp4", OfflineLearningVideoContract.extensionFor("video/mp4"))
        assertEquals("webm", OfflineLearningVideoContract.extensionFor("video/webm"))
        assertEquals("mov", OfflineLearningVideoContract.extensionFor("video/quicktime"))
        assertEquals("mp4", OfflineLearningVideoContract.extensionFor("video/mp4; codecs=avc1"))
        assertNull(OfflineLearningVideoContract.extensionFor("application/octet-stream"))
        assertNull(OfflineLearningVideoContract.extensionFor("text/html"))
    }

    @Test fun sizeAndShaChecksRejectInvalidMetadata() {
        assertTrue(OfflineLearningVideoContract.isValidSize(1))
        assertTrue(OfflineLearningVideoContract.isValidSize(OfflineLearningVideoContract.MAX_VIDEO_BYTES))
        assertFalse(OfflineLearningVideoContract.isValidSize(0))
        assertFalse(OfflineLearningVideoContract.isValidSize(OfflineLearningVideoContract.MAX_VIDEO_BYTES + 1))
        assertTrue(OfflineLearningVideoContract.isValidSha256("a".repeat(64)))
        assertTrue(OfflineLearningVideoContract.isValidSha256("A".repeat(64)))
        assertFalse(OfflineLearningVideoContract.isValidSha256("a".repeat(63)))
        assertFalse(OfflineLearningVideoContract.isValidSha256("../" + "a".repeat(61)))
    }

    @Test fun rangeParserRejectsMalformedAndOutOfBoundsServerResponses() {
        val parsed = OfflineLearningVideoContract.parseContentRange("bytes 128-255/512")
        assertNotNull(parsed)
        assertEquals(128L, parsed!!.start)
        assertEquals(255L, parsed.end)
        assertEquals(512L, parsed.total)
        assertNull(OfflineLearningVideoContract.parseContentRange(null))
        assertNull(OfflineLearningVideoContract.parseContentRange("items 128-255/512"))
        assertNull(OfflineLearningVideoContract.parseContentRange("bytes 255-128/512"))
        assertNull(OfflineLearningVideoContract.parseContentRange("bytes 0-512/512"))
    }

    @Test fun interruptedTransferCanAppendOnlyAtTheExactAuthorizedOffset() {
        val valid = OfflineLearningVideoContract.parseContentRange("bytes 128-511/512")
        assertEquals(OfflineLearningVideoContract.ResumeDisposition.APPEND,
            OfflineLearningVideoContract.resumeDisposition(128, 206, valid, 512))
        assertEquals(OfflineLearningVideoContract.ResumeDisposition.RETRY_INVALID,
            OfflineLearningVideoContract.resumeDisposition(127, 206, valid, 512))
        assertEquals(OfflineLearningVideoContract.ResumeDisposition.RETRY_INVALID,
            OfflineLearningVideoContract.resumeDisposition(128, 206,
                OfflineLearningVideoContract.parseContentRange("bytes 128-511/513"), 512))
    }

    @Test fun fullResponseRestartsStalePartialAndCompleteRangeRequiresFullLength() {
        assertEquals(OfflineLearningVideoContract.ResumeDisposition.RESTART,
            OfflineLearningVideoContract.resumeDisposition(128, 200, null, 512))
        assertEquals(OfflineLearningVideoContract.ResumeDisposition.VERIFY_COMPLETE,
            OfflineLearningVideoContract.resumeDisposition(512, 416, null, 512))
        assertEquals(OfflineLearningVideoContract.ResumeDisposition.RETRY_INVALID,
            OfflineLearningVideoContract.resumeDisposition(511, 416, null, 512))
    }
}
