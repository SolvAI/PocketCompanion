package io.github.solvai.pocketcompanion

import org.junit.Assert.assertEquals
import org.junit.Test

class TextCleanerTest {

    @Test
    fun `spells out numbers kokoro would skip`() {
        assertEquals("I have three cats.", TextCleaner.forSpeech("I have 3 cats."))
        assertEquals("twenty one", TextCleaner.forSpeech("21"))
        assertEquals("one hundred five", TextCleaner.forSpeech("105"))
        assertEquals("two thousand twenty six", TextCleaner.forSpeech("2026"))
        assertEquals("one thousand five hundred", TextCleaner.forSpeech("1,500"))
        assertEquals("three point five", TextCleaner.forSpeech("3.5"))
        assertEquals("zero", TextCleaner.forSpeech("0"))
    }

    @Test
    fun `removes markdown and normalizes apostrophes`() {
        assertEquals("it's really nice", TextCleaner.forSpeech("**it’s** _really_  nice"))
    }

    @Test
    fun `leaves ordinary sentences alone`() {
        assertEquals("Hello, how are you?", TextCleaner.forSpeech("Hello, how are you?"))
    }
}
