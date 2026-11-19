package io.github.solvai.pocketcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentenceSplitterTest {

    @Test
    fun `emits a sentence only once the next word has started`() {
        val splitter = SentenceSplitter()
        assertEquals(emptyList<String>(), splitter.add("I love the beach"))
        assertEquals(emptyList<String>(), splitter.add("."))
        assertEquals(listOf("I love the beach."), splitter.add(" The sound"))
        assertEquals("The sound", splitter.flush())
    }

    @Test
    fun `handles several sentences split across deltas`() {
        val splitter = SentenceSplitter()
        val out = splitter.add("What a lovely day it is! Do you want to go out? I wo") + splitter.add("uld.")
        assertEquals(listOf("What a lovely day it is!", "Do you want to go out?"), out)
        assertEquals("I would.", splitter.flush())
    }

    @Test
    fun `keeps short fragments with the following words`() {
        val splitter = SentenceSplitter()
        assertEquals(listOf("Oh! That sounds wonderful."), splitter.add("Oh! That sounds wonderful. Next"))
    }

    @Test
    fun `a long opening sentence is split at a comma`() {
        val long = "A curious tabby named Luna chased a glittering feather, leaping over the rose bushes in the garden."
        val pieces = SentenceSplitter.quickStart(listOf(long, "Then she slept."))
        assertEquals(
            listOf("A curious tabby named Luna chased a glittering feather,", "leaping over the rose bushes in the garden.", "Then she slept."),
            pieces,
        )
    }

    @Test
    fun `short openings and commas near the edges are left alone`() {
        val short = listOf("Hi, how are you today?")
        assertEquals(short, SentenceSplitter.quickStart(short))
        val lateComma = listOf("This opening sentence is long enough but its only comma sits right at the end, ok.")
        assertEquals(lateComma, SentenceSplitter.quickStart(lateComma))
        assertEquals(emptyList<String>(), SentenceSplitter.quickStart(emptyList()))
    }

    @Test
    fun `flush is empty when nothing remains`() {
        val splitter = SentenceSplitter()
        splitter.add("   ")
        assertNull(splitter.flush())
    }
}
