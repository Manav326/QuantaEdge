package com.quantaedge.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeGreetingTest {
    @Test
    fun englishGreetingFollowsLocalHourRanges() {
        assertEquals("Good night", HomeGreeting.english(0))
        assertEquals("Good morning", HomeGreeting.english(5))
        assertEquals("Good morning", HomeGreeting.english(11))
        assertEquals("Good afternoon", HomeGreeting.english(12))
        assertEquals("Good afternoon", HomeGreeting.english(16))
        assertEquals("Good evening", HomeGreeting.english(17))
        assertEquals("Good evening", HomeGreeting.english(20))
        assertEquals("Good night", HomeGreeting.english(21))
    }

    @Test
    fun hindiGreetingFollowsSameHourRanges() {
        assertEquals("शुभ रात्रि", HomeGreeting.hindi(4))
        assertEquals("सुप्रभात", HomeGreeting.hindi(8))
        assertEquals("शुभ दोपहर", HomeGreeting.hindi(13))
        assertEquals("शुभ संध्या", HomeGreeting.hindi(19))
        assertEquals("शुभ रात्रि", HomeGreeting.hindi(23))
    }

    @Test
    fun selectedLanguageChoosesGreetingLanguage() {
        assertEquals("Good morning", HomeGreeting.text(8, "en"))
        assertEquals("सुप्रभात", HomeGreeting.text(8, "hi"))
        assertEquals("Good night", HomeGreeting.text(1, "English"))
    }
}
