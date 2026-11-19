package io.github.solvai.pocketcompanion

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/** Records mono speech while the talk button is held and hands it on in 100 ms pieces, as floats. */
class MicRecorder(private val rate: Int, private val onSamples: (FloatArray) -> Unit) {

    @Volatile
    private var running = false
    private var thread: Thread? = null
    @Volatile
    private var recorded = 0L

    @SuppressLint("MissingPermission")
    fun start() {
        val minBuffer = AudioRecord.getMinBufferSize(rate, CHANNEL, ENCODING)
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, CHANNEL, ENCODING, minBuffer * 2)
        recorded = 0
        running = true
        thread = Thread {
            val buffer = ShortArray(rate / 10)
            record.startRecording()
            while (running) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                recorded += read
                onSamples(FloatArray(read) { buffer[it] / 32768f })
            }
            record.stop()
            record.release()
        }.also { it.start() }
    }

    /** Stops recording and returns how many seconds were captured. */
    fun stop(): Double {
        running = false
        thread?.join()
        thread = null
        return recorded.toDouble() / rate
    }

    private companion object {
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
