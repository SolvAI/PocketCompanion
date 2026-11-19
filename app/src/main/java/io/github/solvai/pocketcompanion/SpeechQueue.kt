package io.github.solvai.pocketcompanion

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speaks a response sentence by sentence. Each sentence is fully synthesized before it reaches
 * the avatar, which needs complete audio to compute mouth shapes.
 */
class SpeechQueue(
    private val player: LipSyncPlayer,
    private val onDrained: () -> Unit,
    /** Receives each sentence's subtitles just before its audio goes to the avatar. */
    private val onSentence: (List<SubtitleChunk>) -> Unit,
) {

    private class Sentence(val text: String, val generation: Int)

    private val sentences = LinkedBlockingQueue<Sentence>()
    private val splitter = SentenceSplitter()
    private val held = ArrayList<String>()
    private val idleLock = Object()
    private val voiceReady = CountDownLatch(1)
    private val active = AtomicInteger(0)

    @Volatile private var voice: KokoroVoice? = null
    @Volatile private var generation = 0
    @Volatile private var running = true
    private var speaking = false
    private var speakingUntil = 0L

    private val worker = Thread { speakLoop() }.apply {
        name = "yi-yao-speech"
        start()
    }

    /** True when nothing is queued, being synthesized or being played. */
    val isIdle: Boolean get() = active.get() == 0

    fun attachVoice(kokoro: KokoroVoice) {
        voice = kokoro
        voiceReady.countDown()
    }

    fun startResponse() {
        splitter.flush()
        held.clear()
    }

    /**
     * Sentences wait here until the reply is complete. Synthesizing while the language model is
     * still generating makes both fight for the same fast cores and slows everything down.
     */
    fun addText(delta: String) {
        held.addAll(splitter.add(delta))
    }

    fun finishResponse() {
        val all = held + listOfNotNull(splitter.flush())
        held.clear()
        SentenceSplitter.quickStart(all).forEach(::enqueue)
    }

    /** Speaks one line outside of a reply, such as a voice sample. */
    fun speakNow(text: String) {
        enqueue(text)
    }

    /** Called when the avatar has finished playing the current sentence. */
    fun onPlayEnded() {
        val wasSpeaking = synchronized(idleLock) {
            val was = speaking
            speaking = false
            idleLock.notifyAll()
            was
        }
        if (wasSpeaking) finishOne()
    }

    /** Stops speaking now and forgets everything that was queued. */
    fun interrupt() {
        generation++
        sentences.clear()
        splitter.flush()
        held.clear()
        active.set(0)
        player.interrupt()
        synchronized(idleLock) {
            speaking = false
            idleLock.notifyAll()
        }
    }

    fun shutdown() {
        running = false
        interrupt()
        worker.interrupt()
    }

    private fun enqueue(text: String) {
        active.incrementAndGet()
        sentences.put(Sentence(text, generation))
    }

    private fun finishOne() {
        if (active.get() > 0 && active.decrementAndGet() == 0) onDrained()
    }

    private fun speakLoop() {
        try {
            voiceReady.await()
            while (running) {
                val sentence = sentences.take()
                if (sentence.generation != generation) continue

                val startedAt = System.currentTimeMillis()
                val pcm = voice?.synthesize(sentence.text)
                if (sentence.generation != generation) continue
                if (pcm == null) {
                    finishOne()
                    continue
                }
                val seconds = pcm.size / 2.0 / KokoroVoice.AVATAR_RATE
                val tookMs = System.currentTimeMillis() - startedAt
                Diag.log("tts: ${sentence.text.length} chars, %.2f s audio in $tookMs ms, RTF %.2f".format(seconds, tookMs / 1000.0 / seconds))

                awaitAvatarIdle(sentence.generation)
                if (sentence.generation != generation) continue

                synchronized(idleLock) {
                    speaking = true
                    speakingUntil = System.currentTimeMillis() + (seconds * 1000).toLong() + PLAY_END_GRACE_MS
                }
                val samples = ShortArray(pcm.size / 2)
                ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                val words = SubtitleTiming.estimateWords(sentence.text, samples, KokoroVoice.AVATAR_RATE)
                onSentence(SubtitleTiming.buildChunks(words, 0.0, sentence.text, seconds))

                Diag.speechStarted()
                DemoAudio.sentenceQueued(pcm)
                player.begin()
                player.feed(pcm)
                player.end()
            }
        } catch (e: InterruptedException) {
            // Shutting down.
        }
    }

    private fun awaitAvatarIdle(sentenceGeneration: Int) {
        var timedOut = false
        synchronized(idleLock) {
            while (speaking && sentenceGeneration == generation) {
                val remaining = speakingUntil - System.currentTimeMillis()
                if (remaining <= 0) {
                    Diag.log("no play-end callback from the avatar, continuing")
                    speaking = false
                    timedOut = true
                    break
                }
                idleLock.wait(remaining)
            }
        }
        if (timedOut) finishOne()
    }

    private companion object {
        const val PLAY_END_GRACE_MS = 4_000L
    }
}
