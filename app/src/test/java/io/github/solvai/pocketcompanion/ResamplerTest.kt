package io.github.solvai.pocketcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class ResamplerTest {

    private fun sine(frequency: Double, rate: Int, seconds: Double): ShortArray =
        ShortArray((rate * seconds).toInt()) { i ->
            (12_000 * sin(2 * PI * frequency * i / rate)).roundToInt().toShort()
        }

    private fun rms(samples: ShortArray): Double =
        sqrt(samples.map { it.toDouble() * it }.average())

    /** Average frequency, measured from upward zero crossings. */
    private fun frequency(samples: ShortArray, rate: Int): Double {
        var crossings = 0
        for (i in 1 until samples.size) if (samples[i - 1] < 0 && samples[i] >= 0) crossings++
        return crossings.toDouble() * rate / samples.size
    }

    private fun resampleInChunks(input: ShortArray, inRate: Int, outRate: Int, chunk: Int): ShortArray {
        val resampler = Resampler(inRate, outRate)
        var output = ShortArray(0)
        var offset = 0
        while (offset < input.size) {
            val end = minOf(offset + chunk, input.size)
            output += resampler.process(input.copyOfRange(offset, end))
            offset = end
        }
        return output
    }

    @Test
    fun `keeps pitch, level and duration when downsampling`() {
        val output = resampleInChunks(sine(440.0, 24_000, 2.0), 24_000, 16_000, 480)
        assertEquals(32_000.0, output.size.toDouble(), 32.0)
        val settled = output.copyOfRange(200, output.size - 200)
        assertEquals(440.0, frequency(settled, 16_000), 2.0)
        assertEquals(rms(sine(440.0, 16_000, 2.0)), rms(settled), 150.0)
    }

    @Test
    fun `chunk size does not change the result`() {
        val input = sine(1_000.0, 24_000, 1.0)
        val whole = resampleInChunks(input, 24_000, 16_000, input.size)
        val tiny = resampleInChunks(input, 24_000, 16_000, 7)
        val shared = minOf(whole.size, tiny.size)
        assertTrue(abs(whole.size - tiny.size) <= 1)
        for (i in 0 until shared) assertEquals("sample $i", whole[i].toInt(), tiny[i].toInt())
    }

    @Test
    fun `removes content above the new nyquist limit`() {
        val output = resampleInChunks(sine(11_000.0, 24_000, 1.0), 24_000, 16_000, 480)
        assertTrue(rms(output.copyOfRange(200, output.size - 200)) < 400)
    }

    @Test
    fun `matching rates pass through unchanged`() {
        val input = sine(440.0, 16_000, 0.1)
        assertTrue(input.contentEquals(Resampler(16_000, 16_000).process(input)))
    }
}
