package io.github.solvai.pocketcompanion

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.concurrent.Executors

/**
 * On-device streaming speech recognition (NVIDIA Nemotron Speech Streaming 0.6B, through sherpa-onnx).
 * Audio is decoded while the user is still talking, so the text is ready soon after they let go.
 */
class StreamingAsr(dir: File, threads: Int) {

    interface Listener {
        fun onHeardSoFar(text: String)
        fun onHeard(text: String)
    }

    private val recognizer = OnlineRecognizer(
        config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(dir, ENCODER).path,
                    decoder = File(dir, DECODER).path,
                    joiner = File(dir, JOINER).path,
                ),
                tokens = File(dir, TOKENS).path,
                numThreads = threads,
            ),
            enableEndpoint = false, // releasing the talk button ends the turn
        ),
    )

    // Everything below runs on this one thread, in the order it was asked for.
    private val worker = Executors.newSingleThreadExecutor()
    private var stream: OnlineStream? = null
    private var listener: Listener? = null
    private var lastText = ""
    private var audioSamples = 0L
    private var decodeNanos = 0L

    @Volatile private var session = 0

    /** Begins a new utterance; anything still pending from the previous one is dropped. */
    fun start(listener: Listener) {
        val id = ++session
        worker.execute {
            if (id != session) return@execute
            stream?.release()
            // Without a little silence first, a word spoken right at the start was dropped ("What
            // should I cook" came out as "Should I cook"). It is decoded before the user gets going.
            stream = recognizer.createStream().also {
                it.acceptWaveform(FloatArray((SAMPLE_RATE * LEAD_PADDING_SECONDS).toInt()), SAMPLE_RATE)
            }
            this.listener = listener
            lastText = ""
            audioSamples = 0
            decodeNanos = 0
        }
    }

    fun accept(samples: FloatArray) {
        val id = session
        worker.execute {
            val current = stream
            if (id != session || current == null) return@execute
            current.acceptWaveform(samples, SAMPLE_RATE)
            audioSamples += samples.size
            decodeReady(current)
            val text = textOf(current)
            if (text != lastText) {
                lastText = text
                listener?.onHeardSoFar(text)
            }
        }
    }

    /** The user stopped talking: decodes what is left and reports the whole utterance. */
    fun finish() {
        val id = session
        val requestedAt = System.nanoTime()
        worker.execute {
            val current = stream
            if (id != session || current == null) return@execute
            // The model works on fixed chunks, so silence pushes the last words through it.
            current.acceptWaveform(FloatArray((SAMPLE_RATE * TAIL_PADDING_SECONDS).toInt()), SAMPLE_RATE)
            current.inputFinished()
            decodeReady(current)
            val text = textOf(current)
            val seconds = audioSamples.toDouble() / SAMPLE_RATE
            Diag.log(
                "asr %.1f s of speech, decoding took %.2f s (RTF %.2f), final text %d ms after release: %s".format(
                    seconds, decodeNanos / 1e9, decodeNanos / 1e9 / seconds.coerceAtLeast(0.1),
                    (System.nanoTime() - requestedAt) / 1_000_000, text,
                ),
            )
            current.release()
            stream = null
            listener?.onHeard(text)
        }
    }

    fun cancel() {
        val id = ++session
        worker.execute {
            if (id != session) return@execute
            stream?.release()
            stream = null
        }
    }

    fun release() {
        cancel()
        worker.execute { recognizer.release() }
        worker.shutdown()
    }

    private fun decodeReady(current: OnlineStream) {
        val start = System.nanoTime()
        while (recognizer.isReady(current)) recognizer.decode(current)
        decodeNanos += System.nanoTime() - start
    }

    private fun textOf(current: OnlineStream): String = recognizer.getResult(current).text.trim()

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val FEATURE_DIM = 128 // NeMo models use 128 mel bins
        private const val LEAD_PADDING_SECONDS = 0.5f
        private const val TAIL_PADDING_SECONDS = 0.7f // a bit more than one 560 ms chunk plus its look-back

        const val ENCODER = "encoder.int8.onnx"
        const val DECODER = "decoder.int8.onnx"
        const val JOINER = "joiner.int8.onnx"
        const val TOKENS = "tokens.txt"
        val FILES = listOf(ENCODER, DECODER, JOINER, TOKENS)

        /** For a two-line caption: the end of what was heard, starting at a word. */
        fun tail(text: String, maxChars: Int = 90): String {
            if (text.length <= maxChars) return text
            val cut = text.length - maxChars
            val wordStart = text.indexOf(' ', cut).takeIf { it in cut until text.length - 1 } ?: cut
            return "…" + text.substring(wordStart).trimStart()
        }
    }
}
