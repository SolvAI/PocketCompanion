package io.github.solvai.pocketcompanion

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Streaming windowed-sinc resampler for mono PCM16. */
class Resampler(inRate: Int, outRate: Int) {

    private val passthrough = inRate == outRate
    private val step = inRate.toDouble() / outRate
    private val cutoff = min(1.0, outRate.toDouble() / inRate) * 0.95

    // Input carried over between calls, starting with HALF_WIDTH samples of silence.
    private var history = ShortArray(HALF_WIDTH)
    private var position = HALF_WIDTH.toDouble()

    fun process(input: ShortArray): ShortArray {
        if (passthrough) return input

        val buffer = history + input
        val output = ArrayList<Short>((input.size / step).toInt() + 2)
        while (position + HALF_WIDTH < buffer.size) {
            output.add(interpolate(buffer, position))
            position += step
        }

        val keepFrom = (floor(position).toInt() - HALF_WIDTH).coerceIn(0, buffer.size)
        history = buffer.copyOfRange(keepFrom, buffer.size)
        position -= keepFrom
        return output.toShortArray()
    }

    private fun interpolate(buffer: ShortArray, at: Double): Short {
        var sum = 0.0
        var weightSum = 0.0
        val first = floor(at).toInt() - HALF_WIDTH + 1
        for (i in first until first + 2 * HALF_WIDTH) {
            val distance = i - at
            val window = 0.5 + 0.5 * cos(PI * distance / HALF_WIDTH)
            val sinc = if (distance == 0.0) cutoff else sin(PI * cutoff * distance) / (PI * distance)
            val weight = sinc * window
            sum += buffer[i] * weight
            weightSum += weight
        }
        return (sum / weightSum).roundToInt().coerceIn(-32768, 32767).toShort()
    }

    private companion object {
        const val HALF_WIDTH = 8
    }
}
