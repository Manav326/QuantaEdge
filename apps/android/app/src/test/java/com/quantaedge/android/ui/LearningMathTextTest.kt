package com.quantaedge.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LearningMathTextTest {
    @Test
    fun splitsSimpleFractionsWithoutChangingSurroundingProse() {
        assertEquals(
            listOf(
                InlineFractionPart("What is "),
                InlineFractionPart("3/4", "3", "4"),
                InlineFractionPart(" of "),
                InlineFractionPart("2/5", "2", "5"),
                InlineFractionPart("?")
            ),
            splitInlineFractions("What is 3/4 of 2/5?")
        )
    }

    @Test
    fun leavesDecimalDatesAndPathLikeRatiosUntouched() {
        assertEquals(
            listOf(InlineFractionPart("On 2.5/3.5 use 2026/10/13 and a/b.")),
            splitInlineFractions("On 2.5/3.5 use 2026/10/13 and a/b.")
        )
    }

    @Test
    fun handlesStandaloneAndMultiDigitFractions() {
        assertEquals(
            listOf(InlineFractionPart("12 / 25", "12", "25")),
            splitInlineFractions("12 / 25")
        )
    }

    @Test
    fun leavesTextWithoutFractionsUnchanged() {
        assertEquals(
            listOf(InlineFractionPart("समीकरण हल करें: x + y")),
            splitInlineFractions("समीकरण हल करें: x + y")
        )
    }
}
