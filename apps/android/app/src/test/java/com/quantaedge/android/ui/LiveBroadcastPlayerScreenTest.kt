package com.quantaedge.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveBroadcastPlayerScreenTest {
    @Test
    fun acceptsOnlyAnElevenCharacterYouTubeVideoId() {
        assertTrue(isValidYouTubeVideoId("abcdefghijk"))
        assertTrue(isValidYouTubeVideoId("aB_9-xYz012"))
        assertFalse(isValidYouTubeVideoId(""))
        assertFalse(isValidYouTubeVideoId("abcdefghij"))
        assertFalse(isValidYouTubeVideoId("abcdefghijkl"))
        assertFalse(isValidYouTubeVideoId("abcdefghijk<script>"))
        assertFalse(isValidYouTubeVideoId("https://youtube.com/watch?v=abcdefghijk"))
    }
}
