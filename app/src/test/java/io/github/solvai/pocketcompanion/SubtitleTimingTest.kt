package io.github.solvai.pocketcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SubtitleTimingTest {

    private fun w(text: String, start: Double, end: Double) = TimedWord(text, start, end)

    @Test
    fun `chunks hold at most four words`() {
        val words = (0 until 6).map { w("word$it", it * 0.3, it * 0.3 + 0.25) }
        val chunks = SubtitleTiming.buildChunks(words, 0.0, "", 0.0)
        assertEquals(listOf(4, 2), chunks.map { it.words.size })
    }

    @Test
    fun `sentence punctuation and long silences close a chunk`() {
        val words = listOf(w("Hi.", 0.0, 0.3), w("so", 0.4, 0.5), w("then", 1.3, 1.5), w("now", 1.6, 1.8))
        val chunks = SubtitleTiming.buildChunks(words, 0.0, "", 0.0)
        assertEquals(listOf(listOf("Hi."), listOf("so"), listOf("then", "now")), chunks.map { c -> c.words.map { it.text } })
    }

    @Test
    fun `leading punctuation is glued to the previous word`() {
        val words = listOf(w("Oh", 0.1, 0.3), w(", so", 0.35, 0.5), w("now", 0.55, 0.8), w("!", 0.8, 0.9))
        val chunk = SubtitleTiming.buildChunks(words, 0.0, "", 0.0).single()
        assertEquals(listOf("Oh,", "so", "now!"), chunk.words.map { it.text })
        assertEquals(0.9, chunk.words.last().end, 1e-9)
    }

    @Test
    fun `times become absolute and each chunk leaves when the next arrives`() {
        val words = (0 until 5).map { w("w$it", it * 0.5, it * 0.5 + 0.4) }
        val chunks = SubtitleTiming.buildChunks(words, 10.0, "", 0.0)
        assertEquals(10.0, chunks[0].t0, 1e-9)
        assertEquals(chunks[1].t0, chunks[0].until, 1e-9)
        assertEquals(chunks[1].end + 0.8, chunks[1].until, 1e-9)
    }

    @Test
    fun `missing timings are spread evenly over the clip`() {
        val chunks = SubtitleTiming.buildChunks(emptyList(), 0.0, "[warm] one two", 2.0)
        val words = chunks.flatMap { it.words }
        assertEquals(listOf("one", "two"), words.map { it.text })
        assertEquals(1.0, words[1].start, 1e-9)
    }

    @Test
    fun `chunk appears early and the lit word is sticky`() {
        val chunks = SubtitleTiming.buildChunks(listOf(w("a", 1.0, 1.2), w("b", 1.5, 1.7)), 0.0, "", 0.0)
        assertNull(SubtitleTiming.active(chunks, 0.9))
        assertEquals(-1, SubtitleTiming.active(chunks, 0.96)!!.second)
        assertEquals(0, SubtitleTiming.active(chunks, 1.3)!!.second)
        assertEquals(1, SubtitleTiming.active(chunks, 1.48)!!.second)
        assertEquals(1, SubtitleTiming.active(chunks, 2.4)!!.second)
        assertNull(SubtitleTiming.active(chunks, 2.6))
    }

    @Test
    fun `estimator finds the audible span and weights words by syllables`() {
        val rate = 16_000
        val pcm = ShortArray(rate * 2) { i ->
            if (i < rate / 2 || i >= rate * 3 / 2) 0 else (8000 * sin(2 * PI * 220 * i / rate)).toInt().toShort()
        }
        val (start, end) = SubtitleTiming.voicedSpan(pcm, rate)
        assertEquals(0.5, start, 0.011)
        assertEquals(1.5, end, 0.011)

        val words = SubtitleTiming.estimateWords("Hello, wonderful cat!", pcm, rate)
        assertEquals(listOf("Hello", "wonderful", "cat"), words.map { it.text })
        assertEquals(0.5, words.first().start, 0.011)
        assertEquals(1.5, words.last().end, 0.011)
        assertTrue("three syllables last longer than one", words[1].end - words[1].start > words[2].end - words[2].start)
        assertTrue("comma adds a pause", words[1].start > words[0].end)
    }

    @Test
    fun `silent audio falls back to the whole clip and punctuation only tokens are dropped`() {
        val rate = 16_000
        // Same as the reference: with nothing above the threshold, the span is the whole clip.
        val silent = SubtitleTiming.estimateWords("hello", ShortArray(rate), rate).single()
        assertEquals(0.0, silent.start, 1e-9)
        assertEquals(1.0, silent.end, 1e-9)
        assertEquals(0, SubtitleTiming.estimateWords("hello", ShortArray(400), rate).size)
        val tone = ShortArray(rate) { i -> (6000 * sin(2 * PI * 200 * i / rate)).toInt().toShort() }
        assertEquals(listOf("hi"), SubtitleTiming.estimateWords("... hi —", tone, rate).map { it.text })
    }
}
