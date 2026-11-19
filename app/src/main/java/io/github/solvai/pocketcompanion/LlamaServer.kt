package io.github.solvai.pocketcompanion

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Runs llama-server on the phone as a child process, reachable only from this app. */
class LlamaServer(private val context: Context, private val model: File, private val projector: File) {

    val port: Int = ServerSocket(0).use { it.localPort }
    val apiKey: String = UUID.randomUUID().toString()

    private val logFile = File(context.cacheDir, "llama-server.log")
    private val http = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
    private var process: Process? = null

    fun start() {
        killStaleServers()
        val libDir = context.applicationInfo.nativeLibraryDir
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(4, 6)
        val command = listOf(
            File(libDir, BINARY).path,
            "-m", model.path,
            "-mm", projector.path,
            "--reasoning", "off",
            "--temp", "0",
            "--host", "127.0.0.1",
            "--port", port.toString(),
            "--api-key", apiKey,
            "--no-webui",
            "-c", "8192", // room for photos, which take many tokens each
            "-np", "1",
            "-t", threads.toString(),
            // Gemma 4 mixes sliding-window and global attention layers. Without a full sliding-window
            // cache, the server re-reads the whole conversation every turn.
            "--swa-full",
            "--cache-ram", "1024",
        ) + debugExtraArgs()
        Diag.log("llama-server args: ${command.drop(1).joinToString(" ").replace(apiKey, "***")}")
        process = ProcessBuilder(command)
            .directory(context.filesDir)
            .redirectErrorStream(true)
            .redirectOutput(logFile)
            .apply {
                environment()["LD_LIBRARY_PATH"] = libDir
                environment()["HOME"] = context.filesDir.path
            }
            .start()
    }

    fun awaitHealthy(timeoutMs: Long = 240_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        val request = Request.Builder().url("http://127.0.0.1:$port/health").build()
        while (System.currentTimeMillis() < deadline) {
            if (process?.isAlive != true) throw IOException("The language model stopped: ${lastLogLines()}")
            val healthy = try {
                http.newCall(request).execute().use { it.isSuccessful }
            } catch (e: IOException) {
                false
            }
            if (healthy) return
            Thread.sleep(1_000)
        }
        throw IOException("The language model took too long to load: ${lastLogLines()}")
    }

    fun stop() {
        process?.destroy()
        process = null
    }

    /** Debug builds only: extra flags from files/llama-extra-args.txt, for tuning without rebuilding. */
    private fun debugExtraArgs(): List<String> {
        val debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val file = File(context.getExternalFilesDir(null), "llama-extra-args.txt")
        if (!debuggable || !file.exists()) return emptyList()
        return file.readText().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    private fun lastLogLines(): String =
        if (logFile.exists()) logFile.readLines().takeLast(4).joinToString(" | ") else "no log"

    /** If Android killed the app earlier, its server can outlive it and hold the model in memory. */
    private fun killStaleServers() {
        File("/proc").listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            val cmdline = runCatching { File(dir, "cmdline").readText() }.getOrNull() ?: return@forEach
            if (BINARY in cmdline) android.os.Process.killProcess(pid)
        }
    }

    private companion object {
        const val BINARY = "libllama_server.so"
    }
}
