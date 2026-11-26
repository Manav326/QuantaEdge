package com.quantaedge.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InterfaceLanguagePreferenceTest {
    @Test
    fun resolvesSupportedAccountLocaleIds() {
        assertEquals("en", resolveInterfaceLanguage("english"))
        assertEquals("en", resolveInterfaceLanguage(" ENGLISH "))
        assertEquals("hi", resolveInterfaceLanguage("hinglish"))
        assertEquals("hi", resolveInterfaceLanguage("Hinglish"))
        assertEquals("hi", resolveInterfaceLanguage("pure-hindi"))
        assertEquals("hi", resolveInterfaceLanguage("hi-Deva"))
    }

    @Test
    fun explicitLocalPreferenceWinsAndAccountPreferenceSeedsFirstUse() {
        assertEquals("hi", initialInterfaceLanguage("hi", "english"))
        assertEquals("en", initialInterfaceLanguage("english", "hinglish"))
        assertEquals("en", initialInterfaceLanguage(null, "english"))
        assertEquals("hi", initialInterfaceLanguage(null, "bhojpuri"))
    }

    @Test
    fun legacyHindiValuesRemainHinglishWhilePureHindiHasAnExplicitId() {
        assertEquals("english", resolveInterfaceLocale("en"))
        assertEquals("hinglish", resolveInterfaceLocale("hi"))
        assertEquals("hinglish", resolveInterfaceLocale("Hindi"))
        assertEquals("hinglish", resolveInterfaceLocale("hi-Latn"))
        assertEquals("pure-hindi", resolveInterfaceLocale("pure-hindi"))
        assertEquals("pure-hindi", resolveInterfaceLocale("hi-Deva"))
        assertEquals("en", interfaceLocaleLanguageTag("english"))
        assertEquals("hi-Latn", interfaceLocaleLanguageTag("hinglish"))
        assertEquals("hi-Deva", interfaceLocaleLanguageTag("pure-hindi"))
        assertNull(resolveInterfaceLocale("bhojpuri"))
        assertNull(resolveInterfaceLocale("fr"))
    }

    @Test
    fun explicitLocalePreferenceWinsAndAccountPreferenceSeedsFirstUse() {
        assertEquals("pure-hindi", initialInterfaceLocale("pure-hindi", "english"))
        assertEquals("hinglish", initialInterfaceLocale("hi", "pure-hindi"))
        assertEquals("english", initialInterfaceLocale(null, "english"))
        assertEquals("hinglish", initialInterfaceLocale(null, "bhojpuri"))
    }
}
