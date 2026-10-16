package com.quantaedge.android.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class StructuredLessonRecapContractTest {
    @Test fun readsLocalizedStringRecapsFromExistingClassSixData() {
        val block = JSONObject("""{"content_by_locale":{"en":"A measurement combines a number and a unit.","hi":"मापन संख्या और मात्रक को जोड़ता है।"}}""")
        assertEquals(listOf("A measurement combines a number and a unit."), recapTakeaways(block, "en"))
        assertEquals(listOf("मापन संख्या और मात्रक को जोड़ता है।"), recapTakeaways(block, "hi"))
    }

    @Test fun readsKeyTakeawaysAndVocabularyObjectShape() {
        val block = JSONObject("""{"content_by_locale":{"en":{"key_takeaways":["Observe carefully."],"vocabulary":[{"term":"Evidence","meaning":"Information that supports a conclusion."}]},"hi":{"key_takeaways":["ध्यान से देखें।"],"vocabulary":[]}}}""")
        assertEquals(listOf("Observe carefully."), recapTakeaways(block, "en"))
        assertEquals(listOf("ध्यान से देखें।"), recapTakeaways(block, "hi"))
    }

    @Test fun readsPointsAndArrayPayloads() {
        val objectPoints = JSONObject("""{"content_by_locale":{"en":{"points":["Point one.","Point two."]},"hi":["बिंदु एक।","बिंदु दो।"]}}""")
        assertEquals(listOf("Point one.", "Point two."), recapTakeaways(objectPoints, "en"))
        assertEquals(listOf("बिंदु एक।", "बिंदु दो।"), recapTakeaways(objectPoints, "hi"))
    }
}
