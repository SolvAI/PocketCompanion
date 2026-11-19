package io.github.solvai.pocketcompanion

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** On-device Kokoro speech, returned as PCM16 mono at the rate the avatar needs. */
class KokoroVoice(
    dir: File,
    threads: Int,
    @Volatile var speakerId: Int,
    modelFile: String = MODEL,
    provider: String = "cpu",
) {

    private val tts = OfflineTts(
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = File(dir, modelFile).path,
                    voices = File(dir, VOICES).path,
                    tokens = File(dir, TOKENS).path,
                    lexicon = File(dir, LEXICON).path,
                    lang = "en-us",
                ),
                numThreads = threads,
                provider = provider,
            ),
        ),
    )

    fun release() = tts.release()

    fun synthesize(text: String, speaker: Int = speakerId): ByteArray? {
        val spoken = TextCleaner.forSpeech(text)
        if (spoken.isBlank()) return null
        val audio = tts.generate(spoken, speaker, 1.0f)
        if (audio.samples.isEmpty()) return null

        val pcm = ShortArray(audio.samples.size) { i ->
            (audio.samples[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        val resampled = Resampler(audio.sampleRate, AVATAR_RATE).process(pcm)
        val out = ByteBuffer.allocate(resampled.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        out.asShortBuffer().put(resampled)
        return out.array()
    }

    companion object {
        // Full precision: on this class of phone CPU it measured faster than every quantized build.
        const val MODEL = "model.onnx"
        const val VOICES = "voices.bin"
        const val TOKENS = "tokens.txt"
        const val LEXICON = "lexicon-us-en.txt"
        val FILES = listOf(MODEL, VOICES, TOKENS, LEXICON)

        const val AVATAR_RATE = 16_000
    }
}

/** Kokoro skips anything missing from its dictionary, including digits, so make text speakable first. */
object TextCleaner {

    private val markdown = Regex("[*_#`~>\\[\\]]")
    private val number = Regex("\\d[\\d,]*(\\.\\d+)?")
    private val spaces = Regex("\\s+")

    fun forSpeech(text: String): String =
        number.replace(text.replace('’', '\'').replace(markdown, " ")) { spellNumber(it.value) }
            .replace(spaces, " ")
            .trim()

    private fun spellNumber(raw: String): String {
        val parts = raw.replace(",", "").split(".")
        val whole = parts[0].toLongOrNull() ?: return raw
        val spelled = words(whole)
        if (parts.size == 1) return spelled
        return spelled + " point " + parts[1].map { ONES[it - '0'] }.joinToString(" ")
    }

    private fun words(n: Long): String = when {
        n < 20 -> ONES[n.toInt()]
        n < 100 -> TENS[(n / 10).toInt()] + if (n % 10 > 0) " " + ONES[(n % 10).toInt()] else ""
        n < 1_000 -> ONES[(n / 100).toInt()] + " hundred" + rest(n % 100)
        n < 1_000_000 -> words(n / 1_000) + " thousand" + rest(n % 1_000)
        n < 1_000_000_000 -> words(n / 1_000_000) + " million" + rest(n % 1_000_000)
        else -> n.toString().map { ONES[it - '0'] }.joinToString(" ")
    }

    private fun rest(n: Long) = if (n > 0) " " + words(n) else ""

    private val ONES = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
}
