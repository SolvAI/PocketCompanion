package io.github.solvai.pocketcompanion

import java.io.File

/**
 * Debug builds only: keeps a copy of every sentence the avatar speaks (and of synthesized user
 * speech), named by the wall-clock time it started playing. `adb shell screenrecord` captures no
 * audio, so demo videos are put together from these files afterwards.
 */
object DemoAudio {

    @Volatile var dir: File? = null
    private var pending: ByteArray? = null

    @Synchronized
    fun sentenceQueued(pcm: ByteArray) {
        if (dir != null) pending = pcm
    }

    /** Called when the avatar reports that it started playing the queued sentence. */
    @Synchronized
    fun playStarted() {
        val pcm = pending ?: return
        pending = null
        save("companion", System.currentTimeMillis(), pcm)
    }

    fun userSpoke(startedAt: Long, pcm: ByteArray) = save("user", startedAt, pcm)

    private fun save(who: String, startedAt: Long, pcm: ByteArray) {
        val target = dir ?: return
        target.mkdirs()
        File(target, "$startedAt-$who.pcm").writeBytes(pcm)
    }
}
