package io.github.solvai.pocketcompanion

import ai.guiji.duix.sdk.client.DUIX
import java.io.ByteArrayOutputStream
import kotlin.math.min

/** Feeds one spoken response at a time into the avatar, which plays it and moves the lips. */
class LipSyncPlayer(private val duix: () -> DUIX?) {

    private val prebuffer = ByteArrayOutputStream()
    private var accepting = false
    private var pushing = false
    private var pushedBytes = 0L

    @Synchronized
    fun begin() {
        prebuffer.reset()
        pushing = false
        pushedBytes = 0
        accepting = true
    }

    @Synchronized
    fun feed(pcm: ByteArray) {
        if (!accepting) return
        if (pushing) {
            push(pcm)
            return
        }
        prebuffer.write(pcm)
        if (prebuffer.size() >= PREBUFFER_BYTES) startPushing()
    }

    @Synchronized
    fun end() {
        if (!accepting) return
        accepting = false
        if (!pushing) {
            if (prebuffer.size() == 0) return
            startPushing()
        }
        // The SDK ignores segments shorter than one second, so pad with silence.
        if (pushedBytes < MIN_SEGMENT_BYTES) push(ByteArray((MIN_SEGMENT_BYTES - pushedBytes).toInt()))
        duix()?.stopPush()
        pushing = false
    }

    /** Cuts the avatar off mid-sentence and drops any audio still arriving for this response. */
    @Synchronized
    fun interrupt() {
        accepting = false
        prebuffer.reset()
        if (pushing) {
            duix()?.stopAudio()
            duix()?.stopPush()
            pushing = false
        }
    }

    private fun startPushing() {
        duix()?.startPush()
        pushing = true
        push(prebuffer.toByteArray())
        prebuffer.reset()
    }

    private fun push(pcm: ByteArray) {
        var offset = 0
        while (offset < pcm.size) {
            val end = min(offset + CHUNK_BYTES, pcm.size)
            duix()?.pushPcm(pcm.copyOfRange(offset, end))
            offset = end
        }
        pushedBytes += pcm.size
    }

    private companion object {
        const val CHUNK_BYTES = 320
        const val MIN_SEGMENT_BYTES = 32_000L // one second at 16 kHz, 16-bit, mono
        const val PREBUFFER_BYTES = 24_000    // 0.75 s, to ride out slow generation
    }
}
