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
    }

    @Test
    fun acceptsLegacyEnglishAndHindiAliasesWithoutAdvertisingNewLocales() {
        assertEquals("en", resolveInterfaceLanguage("en"))
        assertEquals("hi", resolveInterfaceLanguage("hi"))
        assertEquals("hi", resolveInterfaceLanguage("Hindi"))
        assertNull(resolveInterfaceLanguage("bhojpuri"))
        assertNull(resolveInterfaceLanguage("fr"))
    }
}
