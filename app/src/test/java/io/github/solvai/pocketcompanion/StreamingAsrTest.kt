package io.github.solvai.pocketcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingAsrTest {

    @Test
    fun shortTextIsShownWhole() {
        assertEquals("What should I cook tonight?", StreamingAsr.tail("What should I cook tonight?"))
    }

    @Test
    fun longTextKeepsTheEndFromAWordStart() {
        val text = "So I was walking home yesterday and I saw the strangest thing on the corner " +
            "near the bakery, a cat riding on a skateboard"
        val shown = StreamingAsr.tail(text, maxChars = 40)
        assertTrue(shown, shown.startsWith("…"))
        assertTrue(shown, text.endsWith(shown.removePrefix("…")))
        assertTrue(shown, shown.length <= 41)
        val firstWord = shown.removePrefix("…").substringBefore(' ')
        assertTrue(shown, text.contains(" $firstWord "))
    }

    @Test
    fun textWithoutSpacesIsCutAtTheLimit() {
        val text = "a".repeat(120)
        assertEquals("…" + "a".repeat(90), StreamingAsr.tail(text))
    }
}
