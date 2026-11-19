package io.github.solvai.pocketcompanion

import android.util.Log
import org.json.JSONObject

/** Logs reply speed and lip rendering under the PocketDiag tag. */
object Diag {

    private const val TAG = "PocketDiag"

    private val seenEvents = HashSet<String>()

    private var responseIndex = 0
    private var createdAt = 0L
    private var responseStartedAt = 0L
    private var firstTextAt = 0L
    private var lastTextAt = 0L
    private var characters = 0
    private var deltas = 0
    private val replyText = StringBuilder()

    private var frames = 0
    private var lipFrames = 0
    private var failedFrames = 0
    private var totalRenderMs = 0L
    private var maxRenderMs = 0L

    fun log(message: String) {
        Log.i(TAG, message)
    }

    @Synchronized
    fun event(message: JSONObject) {
        val type = message.optString("type")
        if (seenEvents.add(type)) {
            val detail = if (type.startsWith("session.") || type == "error") message.toString().take(1500)
            else message.keys().asSequence().toList().toString()
            log("first event $type: $detail")
        }
    }

    @Synchronized
    fun responseStarted() {
        responseIndex++
        createdAt = System.currentTimeMillis()
        responseStartedAt = createdAt
        firstTextAt = 0
        lastTextAt = 0
        characters = 0
        deltas = 0
        replyText.setLength(0)
    }

    @Synchronized
    fun speechStarted() {
        if (createdAt != 0L) {
            log("first audio ${System.currentTimeMillis() - createdAt} ms after the request")
            createdAt = 0
        }
    }

    @Synchronized
    fun responseText(delta: String) {
        val now = System.currentTimeMillis()
        if (firstTextAt == 0L) firstTextAt = now
        lastTextAt = now
        characters += delta.length
        deltas++
        replyText.append(delta)
    }

    @Synchronized
    fun responseDone() {
        if (firstTextAt == 0L) {
            log("response $responseIndex finished with no text")
            return
        }
        val seconds = (lastTextAt - firstTextAt) / 1000.0
        val rate = if (seconds > 0) deltas / seconds else 0.0
        log(
            "response $responseIndex: first text after ${firstTextAt - responseStartedAt} ms, " +
                "$characters chars in $deltas deltas over %.2f s, %.1f deltas/s".format(seconds, rate)
        )
        log("reply $responseIndex: ${replyText.toString().trim().take(400)}")
    }

    @Synchronized
    fun render(resultCode: Int, isLip: Boolean, useTimeMs: Long) {
        frames++
        if (isLip) lipFrames++
        if (resultCode < 0) failedFrames++
        totalRenderMs += useTimeMs
        maxRenderMs = maxOf(maxRenderMs, useTimeMs)
        if (frames == 50) {
            log(
                "render: lip frames $lipFrames/$frames, failed $failedFrames, last code $resultCode, " +
                    "avg ${totalRenderMs / frames} ms, max $maxRenderMs ms"
            )
            frames = 0
            lipFrames = 0
            failedFrames = 0
            totalRenderMs = 0
            maxRenderMs = 0
        }
    }
}
