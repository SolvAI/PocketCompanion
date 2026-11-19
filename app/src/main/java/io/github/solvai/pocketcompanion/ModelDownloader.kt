package io.github.solvai.pocketcompanion

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Downloads a large file once, resuming from a .part file if a previous attempt was interrupted. */
object ModelDownloader {

    private val http = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun fetch(url: String, target: File, onProgress: (done: Long, total: Long) -> Unit) {
        if (target.exists()) return
        target.parentFile?.mkdirs()

        val part = File(target.path + ".part")
        val offset = if (part.exists()) part.length() else 0L
        val request = Request.Builder().url(url).apply {
            if (offset > 0) header("Range", "bytes=$offset-")
        }.build()

        http.newCall(request).execute().use { response ->
            if (response.code == 416 && offset > 0) {
                // The partial file already holds everything.
                finish(part, target)
                return
            }
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} downloading ${target.name}")

            val body = response.body ?: throw IOException("Empty response for ${target.name}")
            val resuming = response.code == 206
            val start = if (resuming) offset else 0L
            val total = if (body.contentLength() >= 0) start + body.contentLength() else -1L

            FileOutputStream(part, resuming).use { out ->
                val buffer = ByteArray(256 * 1024)
                var done = start
                body.byteStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                        onProgress(done, total)
                    }
                }
            }
            if (total >= 0 && part.length() != total) throw IOException("Incomplete download of ${target.name}")
        }
        finish(part, target)
    }

    private fun finish(part: File, target: File) {
        if (!part.renameTo(target)) throw IOException("Could not save ${target.name}")
    }
}
