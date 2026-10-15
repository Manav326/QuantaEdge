package com.quantaedge.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorMessageParserTest {
    @Test
    fun parsesHeadingsParagraphsAndOrderedAndUnorderedLists() {
        val blocks = parseTutorMessageBlocks(
            "# Main idea\nA short explanation.\n\n1. First step\n2. Second step\n\n- Check the sign\n- Simplify"
        )

        assertEquals(
            listOf("heading", "paragraph", "ordered", "unordered"),
            blocks.map { it.kind }
        )
        assertEquals(1, blocks.first().level)
        assertEquals(listOf("First step", "Second step"), blocks[2].items)
        assertEquals(listOf("Check the sign", "Simplify"), blocks[3].items)
    }

    @Test
    fun parsesTablesCodeFencesAndDisplayEquations() {
        val blocks = parseTutorMessageBlocks(
            "| x | x² |\n| --- | --- |\n| 2 | 4 |\n\n```kotlin\nval answer = 42\n```\n\n$$\na² + b² = c²\n$$"
        )

        assertEquals(listOf("table", "code", "equation"), blocks.map { it.kind })
        assertEquals(listOf("x", "x²"), blocks[0].headers)
        assertEquals(listOf(listOf("2", "4")), blocks[0].rows)
        assertTrue(blocks[1].text.contains("val answer = 42"))
        assertTrue(blocks[2].text.contains("a² + b² = c²"))
    }

    @Test
    fun keepsAdjacentParagraphTextAndInlineMarkersReadable() {
        val inlineMath = '
        assertEquals(1, blocks.size)
        assertEquals("paragraph", blocks[0].kind)
        assertTrue(blocks[0].text.contains("**Remember:**"))
        assertTrue(blocks[0].text.contains("`x`"))
        assertTrue(blocks[0].text.contains(inlineMath))
    }
}
 + "a+b" + '
        assertEquals(1, blocks.size)
        assertEquals("paragraph", blocks[0].kind)
        assertTrue(blocks[0].text.contains("**Remember:**"))
        assertTrue(blocks[0].text.contains("`x`"))
        assertTrue(blocks[0].text.contains("$a+b$"))
    }
}

        val blocks = parseTutorMessageBlocks("**Remember:** use `x` and " + inlineMath + " carefully.\nContinue the explanation.")
        assertEquals(1, blocks.size)
        assertEquals("paragraph", blocks[0].kind)
        assertTrue(blocks[0].text.contains("**Remember:**"))
        assertTrue(blocks[0].text.contains("`x`"))
        assertTrue(blocks[0].text.contains("$a+b$"))
    }
}
