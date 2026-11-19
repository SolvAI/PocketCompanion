package io.github.solvai.pocketcompanion

import android.util.Base64
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Talks to llama-server's OpenAI-compatible chat endpoint and keeps the conversation. Typed and
 * spoken messages arrive as text (speech is transcribed on the phone first); photos ride along as images.
 */
class ChatClient(private val port: Int, private val apiKey: String, private val listener: Listener) {

    interface Listener {
        fun onResponseStarted()
        fun onResponseText(delta: String)
        fun onResponseFinished()
        fun onError(message: String)
    }

    private val http = OkHttpClient.Builder().readTimeout(180, TimeUnit.SECONDS).build()
    private val worker = Executors.newSingleThreadExecutor()
    private val history = ArrayList<JSONObject>() // only touched on the worker thread
    private var systemPrompt = ""

    @Volatile private var generation = 0
    @Volatile private var call: Call? = null

    /** Starts a new conversation with this system prompt. */
    fun reset(prompt: String) {
        cancel()
        worker.execute {
            history.clear()
            systemPrompt = prompt
        }
    }

    fun sendText(text: String, imageJpeg: ByteArray?) {
        val content: Any = if (imageJpeg == null) {
            text
        } else {
            val image = JSONObject().put("url", "data:image/jpeg;base64," + Base64.encodeToString(imageJpeg, Base64.NO_WRAP))
            JSONArray()
                .put(JSONObject().put("type", "image_url").put("image_url", image))
                .put(JSONObject().put("type", "text").put("text", text.ifBlank { PHOTO_ONLY_TEXT }))
        }
        send(JSONObject().put("role", "user").put("content", content))
    }

    /** Stops the reply being generated; it is kept in the history as far as it got. */
    fun cancel() {
        generation++
        call?.cancel()
    }

    fun shutdown() {
        cancel()
        worker.shutdownNow()
    }

    private fun send(user: JSONObject) {
        val turn = generation
        worker.execute { respond(user, turn) }
    }

    private fun respond(user: JSONObject, turn: Int) {
        if (turn != generation) return
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt))
        history.forEach { messages.put(it) }
        messages.put(user)
        val body = JSONObject()
            .put("messages", messages)
            .put("stream", true)
            .put("temperature", 0)
            .put("max_tokens", MAX_TOKENS)
            .put("cache_prompt", true)
        val request = Request.Builder()
            .url("http://127.0.0.1:$port/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(JSON))
            .build()

        listener.onResponseStarted()
        val reply = StringBuilder()
        var failure: String? = null
        try {
            val pending = http.newCall(request).also { call = it }
            pending.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${response.body?.string()?.take(200)}")
                val source = response.body?.source() ?: throw IOException("empty response")
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val delta = JSONObject(data).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                    val text = if (delta == null || delta.isNull("content")) "" else delta.optString("content")
                    if (text.isNotEmpty() && turn == generation) {
                        reply.append(text)
                        listener.onResponseText(text)
                    }
                }
            }
        } catch (e: IOException) {
            failure = e.message ?: "request failed"
        } finally {
            call = null
        }

        remember(user, reply.toString())
        if (turn != generation) return
        if (failure != null) listener.onError(failure) else listener.onResponseFinished()
    }

    /**
     * The server reuses its cache for the unchanged start of the conversation, so history is only
     * ever appended to. When it grows too long it is cut in one go, keeping the newer half, and old
     * photos in what remains become short text, to keep the context small.
     */
    private fun remember(user: JSONObject, reply: String) {
        history.add(user)
        history.add(JSONObject().put("role", "assistant").put("content", reply.ifBlank { "..." }))
        if (history.size <= MAX_HISTORY_MESSAGES) return

        val keep = history.takeLast(TRIMMED_HISTORY_MESSAGES)
        history.clear()
        keep.forEachIndexed { i, message ->
            history.add(if (i < keep.size - 2) withoutMedia(message) else message)
        }
    }

    private fun withoutMedia(message: JSONObject): JSONObject {
        val content = message.opt("content") as? JSONArray ?: return message
        val parts = (0 until content.length()).mapNotNull { i ->
            val part = content.getJSONObject(i)
            when (part.optString("type")) {
                "text" -> part.optString("text")
                "image_url" -> "(shared a photo)"
                else -> null
            }
        }
        return JSONObject().put("role", "user").put("content", parts.joinToString(" "))
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val MAX_TOKENS = 256
        const val MAX_HISTORY_MESSAGES = 32
        const val TRIMMED_HISTORY_MESSAGES = 12
        const val PHOTO_ONLY_TEXT = "Look at this photo."
    }
}
