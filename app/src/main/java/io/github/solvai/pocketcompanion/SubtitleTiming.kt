package io.github.solvai.pocketcompanion

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A word on screen. Times are seconds on the clip's timeline. */
data class TimedWord(val text: String, val start: Double, val end: Double)

/** One to four words shown together, from [t0] until [until]. */
data class SubtitleChunk(val t0: Double, val end: Double, val until: Double, val words: List<TimedWord>)

/**
 * TikTok-style subtitle timing, ported from the Short Factory brief: the fallback word timing
 * estimator, grouping into 1 to 4 word chunks, and which word is lit at a given time.
 */
object SubtitleTiming {

    const val MAX_WORDS = 4
    private const val CHUNK_GAP_S = 0.6
    private const val LAST_CHUNK_LINGER_S = 0.8
    private const val APPEAR_EARLY_S = 0.05
    private const val HIGHLIGHT_EARLY_S = 0.03

    private val vowelRuns = Regex("[aeiouyAEIOUY]+")
    private val emotionTags = Regex("\\[[^\\]]+\\]\\s*")
    private val whitespace = Regex("\\s+")
    private val leadingPunctuation = Regex("^([.,!?;:…\"')\\]]+)\\s*(.*)$")
    private val endsSentence = Regex("[.!?…]$")

    /** Maps the foreign punctuation and curly quotes some engines produce to plain ASCII. */
    fun normalize(text: String): String = text
        .replace('、', ',').replace('，', ',').replace('。', '.')
        .replace('‘', '\'').replace('’', '\'').replace('“', '\'').replace('”', '\'')

    /** Start and end of the audible part of 16-bit mono PCM, in seconds. */
    fun voicedSpan(pcm: ShortArray, sampleRate: Int): Pair<Double, Double> {
        val n = pcm.size
        if (n == 0) return 0.0 to 0.0
        val hop = max(1, sampleRate / 100) // 10 ms
        val peaks = ArrayList<Int>()
        var offset = 0
        while (offset < n) {
            var peak = 0
            var i = offset
            val stop = min(offset + hop, n)
            while (i < stop) {
                peak = max(peak, abs(pcm[i].toInt()))
                i += 8
            }
            peaks.add(peak)
            offset += hop
        }
        val loudest = peaks.maxOrNull()?.takeIf { it > 0 } ?: 1
        val threshold = max(300.0, loudest * 0.05)
        val first = peaks.indexOfFirst { it >= threshold }.let { if (it < 0) 0 else it }
        val last = peaks.indexOfLast { it >= threshold }.let { if (it < 0) peaks.size - 1 else it }
        return first.toDouble() * hop / sampleRate to min(n, (last + 1) * hop).toDouble() / sampleRate
    }

    /** Estimates word timings for a TTS clip that came without timestamps. */
    fun estimateWords(text: String, pcm: ShortArray, sampleRate: Int): List<TimedWord> {
        val (t0, t1) = voicedSpan(pcm, sampleRate)
        if (t1 - t0 <= 0.05) return emptyList()

        class Token(val word: String, val weight: Double, var pause: Double)

        val tokens = ArrayList<Token>()
        for (raw in normalize(text).replace(emotionTags, "").split(whitespace)) {
            val core = raw.trim('"', '\'', '(', ')', '[', ']', '*')
            val word = core.trimEnd('.', ',', '!', '?', ';', ':', '…', '—', '-')
            if (word.none { it.isLetterOrDigit() }) continue
            val weight = max(1, vowelRuns.findAll(word).count()) + 0.4
            val tail = core.substring(word.length)
            val pause = when {
                tail.any { it in ".!?…" } -> 0.9
                tail.any { it in ",;:—-" } -> 0.45
                else -> 0.0
            }
            tokens.add(Token(word, weight, pause))
        }
        if (tokens.isEmpty()) return emptyList()
        tokens.last().pause = 0.0

        val total = tokens.sumOf { it.weight + it.pause }
        val span = t1 - t0
        var cursor = t0
        return tokens.map { token ->
            val duration = span * token.weight / total
            TimedWord(token.word, cursor, cursor + duration).also { cursor += duration + span * token.pause / total }
        }
    }

    /**
     * Groups a clip's words into chunks with absolute times. [words] are relative to the clip start [t0];
     * without them the words of [rawText] are spread evenly over [seconds].
     */
    fun buildChunks(words: List<TimedWord>, t0: Double, rawText: String, seconds: Double): List<SubtitleChunk> {
        var source = words
        if (source.isEmpty()) {
            val tokens = normalize(rawText).replace(emotionTags, "").split(whitespace).filter { it.isNotBlank() }
            source = tokens.mapIndexed { i, word ->
                TimedWord(word, i.toDouble() / tokens.size * seconds, (i + 1).toDouble() / tokens.size * seconds)
            }
        }

        val clean = ArrayList<TimedWord>()
        for (word in source) {
            var text = normalize(word.text).trim()
            if (text.isEmpty()) continue
            val match = leadingPunctuation.find(text)
            if (match != null && clean.isNotEmpty()) {
                // A line must never open with punctuation: glue it onto the previous word.
                val previous = clean.removeAt(clean.size - 1)
                val rest = match.groupValues[2]
                if (rest.isEmpty()) {
                    clean.add(previous.copy(text = previous.text + match.groupValues[1], end = t0 + word.end))
                    continue
                }
                clean.add(previous.copy(text = previous.text + match.groupValues[1]))
                text = rest
            }
            clean.add(TimedWord(text, t0 + word.start, t0 + word.end))
        }

        val groups = ArrayList<MutableList<TimedWord>>()
        var current: MutableList<TimedWord>? = null
        for (i in clean.indices) {
            val word = clean[i]
            val chunk = current ?: ArrayList<TimedWord>().also { groups.add(it) }
            current = chunk
            chunk.add(word)
            val gap = if (i + 1 < clean.size) clean[i + 1].start - word.end else 0.0
            if (chunk.size >= MAX_WORDS || endsSentence.containsMatchIn(word.text) || gap > CHUNK_GAP_S) current = null
        }

        return groups.mapIndexed { i, group ->
            val until = if (i + 1 < groups.size) groups[i + 1].first().start else group.last().end + LAST_CHUNK_LINGER_S
            SubtitleChunk(group.first().start, group.last().end, until, group)
        }
    }

    /** The chunk on screen at time [t] and the index of the lit word (-1 before the first word), or null. */
    fun active(chunks: List<SubtitleChunk>, t: Double): Pair<SubtitleChunk, Int>? {
        for (chunk in chunks) {
            if (t >= chunk.t0 - APPEAR_EARLY_S && t < chunk.until) {
                var lit = -1
                chunk.words.forEachIndexed { i, word -> if (t >= word.start - HIGHLIGHT_EARLY_S) lit = i }
                return chunk to lit
            }
        }
        return null
    }
}
