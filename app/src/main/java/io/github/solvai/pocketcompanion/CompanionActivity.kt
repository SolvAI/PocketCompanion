package io.github.solvai.pocketcompanion

import io.github.solvai.pocketcompanion.databinding.ActivityCompanionBinding
import ai.guiji.duix.sdk.client.Constant
import ai.guiji.duix.sdk.client.DUIX
import ai.guiji.duix.sdk.client.VirtualModelUtil
import ai.guiji.duix.sdk.client.render.DUIXRenderer
import ai.guiji.duix.sdk.client.thread.RenderThread
import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import kotlin.math.max

class CompanionActivity : AppCompatActivity(), ChatClient.Listener, SettingsSheet.Listener, StreamingAsr.Listener {

    private lateinit var binding: ActivityCompanionBinding
    private var renderer: DUIXRenderer? = null
    private var duix: DUIX? = null
    private var server: LlamaServer? = null
    private var client: ChatClient? = null
    private var mic: MicRecorder? = null
    private var asr: StreamingAsr? = null
    private val player = LipSyncPlayer { duix }
    private lateinit var speech: SpeechQueue
    private lateinit var settings: SettingsSheet
    private lateinit var prefs: SharedPreferences
    private lateinit var avatar: AvatarOption
    private lateinit var voiceChoice: VoiceOption
    private var promptTemplate = Persona.DEFAULT
    private var kokoro: KokoroVoice? = null
    private var switching = false

    private val ui = Handler(Looper.getMainLooper())
    private val fadeCaptions = Runnable { setCaptionsVisible(false) }
    @Volatile private var responding = false
    @Volatile private var pendingSubtitles: List<SubtitleChunk>? = null
    private var ready = false
    private var showingSend = false
    private var attachment: ByteArray? = null
    private var imageSide = MAX_IMAGE_SIDE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityCompanionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        prefs = getSharedPreferences("companion", MODE_PRIVATE)
        avatar = Catalog.avatar(prefs.getString(PREF_AVATAR, null))
        voiceChoice = Catalog.voice(prefs.getString(PREF_VOICE, null))
        promptTemplate = Persona.normalize(prefs.getString(PREF_PROMPT, null))
        showCompanionName()
        settings = SettingsSheet(this, binding, this)
        binding.btnSettings.setOnClickListener { settings.show(avatar, voiceChoice, promptTemplate) }

        speech = SpeechQueue(
            player,
            onDrained = { runOnUiThread { onSpeechDrained() } },
            onSentence = { pendingSubtitles = it },
        )

        binding.glTextureView.setEGLContextClientVersion(GL_CONTEXT_VERSION)
        binding.glTextureView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        binding.glTextureView.isOpaque = false
        renderer = DUIXRenderer(this, binding.glTextureView)
        binding.glTextureView.setRenderer(renderer)
        binding.glTextureView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        setUpInput()
        warnIfLowMemory()
        Thread { prepare() }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        mic?.stop()
        asr?.release()
        speech.shutdown()
        client?.shutdown()
        server?.stop()
        duix?.release()
    }

    /** Debug builds only, driven over adb, e.g. `am start -n io.github.solvai.pocketcompanion/.CompanionActivity --es say "hello"`. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable || intent == null) return
        if (intent.getBooleanExtra("openSettings", false)) settings.show(avatar, voiceChoice, promptTemplate)
        if (intent.getBooleanExtra("scrollSettings", false)) {
            binding.settingsScroll.post { binding.settingsScroll.fullScroll(View.FOCUS_DOWN) }
        }
        intent.getStringExtra("pickAvatar")?.let { onAvatarPicked(Catalog.avatar(it)) }
        intent.getStringExtra("pickVoice")?.let { onVoicePicked(Catalog.voice(it)) }
        intent.getStringExtra("prompt")?.let { onPromptSaved(it) }
        intent.getIntExtra("imageSide", 0).takeIf { it > 0 }?.let { imageSide = it }
        intent.getStringExtra("image")?.let { path ->
            val (bitmap, jpeg) = decodeForModel(ImageDecoder.createSource(File(path)))
            showAttachment(bitmap, jpeg)
        }
        intent.getStringExtra("say")?.let { if (client != null) sendMessage(it) }
        if (intent.hasExtra("recordAudio")) {
            DemoAudio.dir = if (intent.getBooleanExtra("recordAudio", false)) File(getExternalFilesDir(null), "demo") else null
        }
        val userVoice = intent.getIntExtra("userVoice", -1)
        intent.getStringExtra("speakAsUser")?.let { text -> Thread { sendSynthesizedVoice(text, userVoice) }.start() }
        intent.getStringExtra("ttsBench")?.let { threads -> Thread { benchmarkVoice(threads) }.start() }
    }

    private fun applyWindowInsets() {
        val gap = dp(12)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            binding.tvStatus.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = bars.top + gap }
            binding.btnSettings.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = bars.top + gap }
            // The keyboard pushes the sheet up, so the instructions field stays visible while typing.
            binding.settingsSheet.updatePadding(bottom = max(bars.bottom, keyboard.bottom))
            binding.bottomArea.updatePadding(bottom = max(bars.bottom, keyboard.bottom) + gap)
            insets
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setUpInput() {
        binding.etMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateActionIcon()
        })
        binding.etMessage.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) {
                sendTypedMessage()
                true
            } else {
                false
            }
        }
        binding.btnAttach.setOnClickListener { onAttachClicked() }
        binding.btnAction.setOnTouchListener { button, event ->
            if (!ready) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (showingSend) sendTypedMessage() else startTalking()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopTalking()
            }
            true
        }
    }

    private fun updateActionIcon() {
        val canSend = binding.etMessage.text.toString().isNotBlank() || attachment != null
        if (canSend == showingSend) return
        showingSend = canSend
        binding.btnAction.setImageResource(if (canSend) R.drawable.ic_send else R.drawable.ic_mic)
        binding.btnAction.contentDescription = if (canSend) "Send" else "Hold to talk"
    }

    private fun prepare() {
        try {
            if (!VirtualModelUtil.checkBaseConfig(this)) {
                awaitAvatarDownload("Preparing the avatar engine") { callback ->
                    VirtualModelUtil.baseConfigDownload(this, BASE_CONFIG_URL, callback)
                }
            }
            ensureAvatarDownloaded(avatar)

            val modelDir = File(getExternalFilesDir(null), "llm")
            val model = File(modelDir, MODEL_FILE)
            val projector = File(modelDir, PROJECTOR_FILE)
            val kokoroDir = File(getExternalFilesDir(null), "tts/$KOKORO_NAME")
            val asrDir = File(getExternalFilesDir(null), "asr/$ASR_NAME")
            val missing = listOf(model, projector) + KokoroVoice.FILES.map { File(kokoroDir, it) } +
                StreamingAsr.FILES.map { File(asrDir, it) }
            if (missing.any { !it.exists() }) awaitDownloadConsent()

            download("Downloading her mind", MODEL_URL, model)
            download("Downloading her eyes", PROJECTOR_URL, projector)
            modelDir.listFiles()?.filter { it.name.endsWith(".gguf") && it.name != MODEL_FILE && it.name != PROJECTOR_FILE }
                ?.forEach { if (it.delete()) Diag.log("removed old model ${it.name}") }

            KokoroVoice.FILES.forEach { download("Downloading her voice", "$KOKORO_REPO/$it", File(kokoroDir, it)) }
            StreamingAsr.FILES.forEach { download("Downloading her ears", "$ASR_REPO/$it", File(asrDir, it)) }

            // The language model loads in its own process, alongside everything below.
            val llama = LlamaServer(this, model, projector).also { server = it }
            llama.start()

            showSetup("Waking her up", -1)
            initAvatar(avatar)

            showSetup("Finding her voice", -1)
            kokoro = KokoroVoice(kokoroDir, TTS_THREADS, voiceChoice.speakerId).also { speech.attachVoice(it) }

            showSetup("Opening her ears", -1)
            val asrStart = System.currentTimeMillis()
            asr = StreamingAsr(asrDir, ASR_THREADS)
            Diag.log("speech recognizer loaded in ${System.currentTimeMillis() - asrStart} ms")

            showSetup("Gathering her thoughts", -1)
            llama.awaitHealthy()

            client = ChatClient(llama.port, llama.apiKey, this).also { it.reset(instructions(avatar.name)) }
            onReady()
        } catch (e: Exception) {
            runOnUiThread {
                binding.tvSetup.text = "Setup failed: ${e.message}"
                binding.progress.visibility = View.INVISIBLE
            }
        }
    }

    /**
     * The first start downloads several gigabytes, so it waits for the user to agree, ideally on Wi-Fi.
     * Avatar files are fetched before this point; they are small next to the models.
     */
    private fun awaitDownloadConsent() {
        val agreed = CountDownLatch(1)
        val freeGb = (getExternalFilesDir(null)?.usableSpace ?: 0L) / 1e9
        runOnUiThread {
            binding.progress.visibility = View.INVISIBLE
            binding.tvSetup.text = if (freeGb < FIRST_DOWNLOAD_GB + 1) {
                "PocketCompanion needs about $FIRST_DOWNLOAD_GB GB of free space for its AI models, " +
                    "and this phone has %.1f GB free. Free up some space, then reopen the app.".format(freeGb)
            } else {
                "Everything runs on your phone. First, it needs to download about $FIRST_DOWNLOAD_GB GB of AI " +
                    "models (Wi-Fi recommended). After that, no internet connection is needed."
            }
            if (freeGb >= FIRST_DOWNLOAD_GB + 1) {
                binding.btnSetupAction.visibility = View.VISIBLE
                binding.btnSetupAction.setOnClickListener {
                    binding.btnSetupAction.visibility = View.GONE
                    binding.progress.visibility = View.VISIBLE
                    agreed.countDown()
                }
            }
        }
        agreed.await()
    }

    private fun onReady() {
        Diag.log("READY")
        runOnUiThread { finishSetup() }
    }

    private fun ensureAvatarDownloaded(option: AvatarOption) {
        if (VirtualModelUtil.checkModel(this, option.url)) return
        awaitAvatarDownload("Bringing ${option.name} here") { callback ->
            VirtualModelUtil.modelDownload(this, option.url, callback)
        }
    }

    override fun isDownloaded(option: AvatarOption): Boolean = VirtualModelUtil.checkModel(this, option.url)

    override fun onVoicePicked(option: VoiceOption) {
        voiceChoice = option
        prefs.edit().putString(PREF_VOICE, option.id).apply()
        kokoro?.speakerId = option.speakerId
        settings.selectVoice(option)
        interruptIfSpeaking()
        speech.speakNow(VOICE_SAMPLE)
    }

    /** New instructions take effect on a fresh conversation, so old replies don't pull her back. */
    override fun onPromptSaved(template: String) {
        promptTemplate = Persona.normalize(template)
        prefs.edit().apply {
            if (promptTemplate == Persona.DEFAULT) remove(PREF_PROMPT) else putString(PREF_PROMPT, promptTemplate)
        }.apply()
        Diag.log("prompt saved (${promptTemplate.length} chars)")
        interruptIfSpeaking()
        client?.reset(instructions(avatar.name))
        Toast.makeText(this, "Saved. ${avatar.name} starts fresh with these instructions.", Toast.LENGTH_SHORT).show()
    }

    private fun instructions(name: String) = Persona.render(promptTemplate, name)

    /** Swaps the companion in place. The language model stays loaded; only her conversation restarts. */
    override fun onAvatarPicked(option: AvatarOption) {
        if (option.id == avatar.id || switching || !ready) return
        switching = true
        ready = false
        settings.hide()
        interruptIfSpeaking()
        binding.tvSetupName.text = option.name
        binding.tvSetup.text = "One moment"
        binding.progress.visibility = View.VISIBLE
        binding.setupPanel.animate().cancel()
        binding.setupPanel.alpha = 1f
        binding.setupPanel.visibility = View.VISIBLE
        setCaptionsVisible(false)

        Thread {
            val previous = avatar
            try {
                ensureAvatarDownloaded(option)
                showSetup("Waking her up", -1)
                val current = duix
                duix = null
                current?.releaseAndWait(AVATAR_RELEASE_TIMEOUT_MS)
                try {
                    initAvatar(option)
                } catch (e: IOException) {
                    initAvatar(previous)
                    throw e
                }
                avatar = option
                prefs.edit().putString(PREF_AVATAR, option.id).apply()
                runOnUiThread { showCompanionName() }
                client?.reset(instructions(option.name))
                onReady()
            } catch (e: Exception) {
                Diag.log("avatar switch failed: $e")
                runOnUiThread {
                    showCompanionName()
                    finishSetup()
                    Toast.makeText(this, "Couldn't load ${option.name}: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showCompanionName() {
        binding.tvSetupName.text = avatar.name
        binding.etMessage.hint = "Message ${avatar.name}"
    }

    private fun finishSetup() {
        ready = true
        switching = false
        binding.etMessage.isEnabled = true
        binding.setupPanel.animate().alpha(0f).setDuration(350).withEndAction {
            binding.setupPanel.visibility = View.GONE
        }.start()
        binding.bottomArea.animate().alpha(1f).setDuration(350).start()
        binding.btnSettings.visibility = View.VISIBLE
        binding.btnSettings.animate().alpha(1f).setDuration(350).start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (settings.isOpen) settings.hide() else super.onBackPressed()
    }

    private fun awaitAvatarDownload(label: String, start: (VirtualModelUtil.ModelDownloadCallback) -> Unit) {
        val done = CountDownLatch(1)
        var failure: String? = null
        showSetup(label, -1)
        start(object : VirtualModelUtil.ModelDownloadCallback {
            override fun onDownloadProgress(url: String?, current: Long, total: Long) {
                showSetup(label, percent(current, total))
            }

            override fun onUnzipProgress(url: String?, current: Long, total: Long) {
                showSetup("Unpacking", percent(current, total))
            }

            override fun onDownloadComplete(url: String?, dir: File?) {
                done.countDown()
            }

            override fun onDownloadFail(url: String?, code: Int, msg: String?) {
                failure = msg ?: "error $code"
                done.countDown()
            }
        })
        done.await()
        failure?.let { throw IOException(it) }
    }

    private fun download(label: String, url: String, target: File) {
        var lastPercent = -1
        ModelDownloader.fetch(url, target) { done, total ->
            val current = percent(done, total)
            if (current != lastPercent) {
                lastPercent = current
                showSetup(label, current)
            }
        }
    }

    private fun initAvatar(option: AvatarOption) {
        val done = CountDownLatch(1)
        var failure: String? = null
        runOnUiThread {
            duix = DUIX(this, option.url, renderer) { event, message, _ ->
                when (event) {
                    Constant.CALLBACK_EVENT_INIT_READY -> done.countDown()
                    Constant.CALLBACK_EVENT_INIT_ERROR -> {
                        failure = message ?: "avatar failed to start"
                        done.countDown()
                    }
                    Constant.CALLBACK_EVENT_AUDIO_PLAY_START -> {
                        DemoAudio.playStarted()
                        runOnUiThread { onSentencePlaying() }
                    }
                    Constant.CALLBACK_EVENT_AUDIO_PLAY_END -> speech.onPlayEnded()
                    Constant.CALLBACK_EVENT_AUDIO_PLAY_ERROR -> {
                        showStatus("Audio error: $message")
                        speech.onPlayEnded()
                    }
                }
            }
            duix?.init()
        }
        done.await()
        failure?.let { throw IOException(it) }
        duix?.setReporter(RenderThread.Reporter { code, isLip, useTime -> Diag.render(code, isLip, useTime) })
    }

    /** The avatar started playing a sentence: its subtitles start on the same clock. */
    private fun onSentencePlaying() {
        pendingSubtitles?.let { binding.subtitles.play(it) }
        pendingSubtitles = null
        showStatus("Speaking")
    }

    private fun sendTypedMessage() {
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty() && attachment == null) return
        binding.etMessage.setText("")
        sendMessage(text)
    }

    private fun sendMessage(text: String) {
        val image = attachment
        clearAttachment()
        interruptIfSpeaking()
        val label = when {
            image == null -> text
            text.isEmpty() -> "Photo"
            else -> "Photo · $text"
        }
        beginExchange(label)
        client?.sendText(text, image)
    }

    private fun onAttachClicked() {
        if (attachment != null) {
            clearAttachment()
            return
        }
        val picker = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES) else Intent(Intent.ACTION_GET_CONTENT)
        @Suppress("DEPRECATION")
        startActivityForResult(picker.setType("image/*"), REQUEST_IMAGE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQUEST_IMAGE || resultCode != RESULT_OK || uri == null) return
        Thread {
            try {
                val (bitmap, jpeg) = decodeForModel(ImageDecoder.createSource(contentResolver, uri))
                runOnUiThread { showAttachment(bitmap, jpeg) }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Couldn't open that image", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    /** Downscales a picked image: the vision encoder resizes it anyway, and smaller is faster. */
    private fun decodeForModel(source: ImageDecoder.Source): Pair<Bitmap, ByteArray> {
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val longest = max(info.size.width, info.size.height)
            if (longest > imageSide) {
                val scale = imageSide.toFloat() / longest
                decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        return bitmap to jpeg
    }

    private fun showAttachment(bitmap: Bitmap, jpeg: ByteArray) {
        attachment = jpeg
        binding.btnAttach.apply {
            setImageBitmap(bitmap)
            imageTintList = null
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundResource(R.drawable.bg_thumb)
            clipToOutline = true
            contentDescription = "Remove photo"
        }
        updateActionIcon()
    }

    private fun clearAttachment() {
        attachment = null
        binding.btnAttach.apply {
            setImageResource(R.drawable.ic_add)
            imageTintList = android.content.res.ColorStateList.valueOf(0xCCFFFFFF.toInt())
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setBackgroundResource(R.drawable.bg_attach)
            clipToOutline = false
            contentDescription = "Attach a photo"
        }
        updateActionIcon()
    }

    private fun startTalking() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            return
        }
        val recognizer = asr ?: return
        if (client == null) return
        beginListening()
        recognizer.start(this)
        mic = MicRecorder(StreamingAsr.SAMPLE_RATE) { recognizer.accept(it) }.also { it.start() }
    }

    private fun stopTalking() {
        val recorder = mic ?: return
        mic = null
        setRecordingLook(false)
        if (recorder.stop() >= MIN_SPEECH_SECONDS) {
            asr?.finish()
        } else {
            asr?.cancel()
            endListening()
            Toast.makeText(this, "Hold the button while you speak", Toast.LENGTH_SHORT).show()
        }
    }

    private fun beginListening() {
        interruptIfSpeaking()
        setRecordingLook(true)
        showStatus("Listening")
        ui.removeCallbacks(fadeCaptions)
        binding.tvUser.text = ""
        binding.tvUser.visibility = View.GONE
        setCaptionsVisible(true)
    }

    private fun endListening() {
        showStatus(null)
        setCaptionsVisible(false)
    }

    /** Live caption of what she has understood so far, while the user is still talking. */
    override fun onHeardSoFar(text: String) {
        runOnUiThread {
            binding.tvUser.text = StreamingAsr.tail(text)
            binding.tvUser.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    override fun onHeard(text: String) {
        runOnUiThread {
            if (mic != null) return@runOnUiThread // already talking again
            if (text.isBlank()) {
                endListening()
                Toast.makeText(this, "Sorry, I didn't catch that", Toast.LENGTH_SHORT).show()
            } else {
                sendMessage(text)
            }
        }
    }

    /** Debug builds only: speaks [text] with Kokoro and feeds it to speech recognition at talking pace. */
    private fun sendSynthesizedVoice(text: String, userVoice: Int) {
        val recognizer = asr ?: return
        val voice = kokoro ?: return
        val pcm = (if (userVoice >= 0) voice.synthesize(text, userVoice) else voice.synthesize(text)) ?: return
        DemoAudio.userSpoke(System.currentTimeMillis(), pcm)
        val shorts = ShortArray(pcm.size / 2).also { ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
        val step = KokoroVoice.AVATAR_RATE / 10
        runOnUiThread { beginListening() }
        recognizer.start(this)
        for (from in shorts.indices step step) {
            val piece = shorts.copyOfRange(from, minOf(from + step, shorts.size))
            recognizer.accept(FloatArray(piece.size) { piece[it] / 32768f })
            Thread.sleep(100)
        }
        runOnUiThread { setRecordingLook(false) }
        recognizer.finish()
    }

    private fun setRecordingLook(recording: Boolean) {
        binding.btnAction.setBackgroundResource(if (recording) R.drawable.bg_action_recording else R.drawable.bg_action)
        binding.btnAction.imageTintList = ContextCompat.getColorStateList(this, if (recording) android.R.color.white else R.color.ink)
        val scale = if (recording) 1.14f else 1f
        binding.btnAction.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
    }

    private fun interruptIfSpeaking() {
        client?.cancel()
        responding = false
        speech.interrupt()
        pendingSubtitles = null
        binding.subtitles.clear()
    }

    private fun beginExchange(userText: String?) {
        ui.removeCallbacks(fadeCaptions)
        binding.tvUser.text = userText.orEmpty()
        binding.tvUser.visibility = if (userText.isNullOrEmpty()) View.GONE else View.VISIBLE
        setCaptionsVisible(true)
        showStatus("Thinking")
    }

    override fun onResponseStarted() {
        Diag.responseStarted()
        responding = true
        speech.startResponse()
    }

    override fun onResponseText(delta: String) {
        Diag.responseText(delta)
        speech.addText(delta)
    }

    override fun onResponseFinished() {
        Diag.responseDone()
        responding = false
        speech.finishResponse()
        if (speech.isIdle) runOnUiThread { onSpeechDrained() }
    }

    override fun onError(message: String) {
        Diag.log("chat error: $message")
        responding = false
        speech.finishResponse()
        showStatus("Error: ${message.take(60)}")
    }

    private fun onSpeechDrained() {
        if (responding || mic != null) return
        Diag.log("speech drained")
        showStatus(null)
        ui.removeCallbacks(fadeCaptions)
        ui.postDelayed(fadeCaptions, CAPTION_LINGER_MS)
    }

    private fun setCaptionsVisible(visible: Boolean) {
        binding.captions.animate().alpha(if (visible) 1f else 0f).setDuration(if (visible) 120 else 500).start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "${avatar.name} needs the microphone to hear you", Toast.LENGTH_LONG).show()
        }
    }

    /** Shows what she is doing right now; null means she is idle and nothing is shown. */
    private fun showStatus(text: String?) {
        runOnUiThread {
            if (text == null) {
                binding.tvStatus.animate().alpha(0f).setDuration(250).start()
            } else {
                binding.tvStatus.text = text
                binding.tvStatus.visibility = View.VISIBLE
                binding.tvStatus.animate().alpha(1f).setDuration(120).start()
            }
        }
    }

    private fun showSetup(text: String, percent: Int) {
        runOnUiThread {
            binding.tvSetup.text = text
            binding.progress.isIndeterminate = percent < 0
            if (percent >= 0) binding.progress.progress = percent
        }
    }

    /** Debug builds only: measures Kokoro speed per thread count, e.g. `--es ttsBench 2,4`. */
    private fun benchmarkVoice(threadCounts: String) {
        val dir = File(getExternalFilesDir(null), "tts/$KOKORO_NAME")
        val lines = listOf(
            "I love curling up with a warm blanket and a good book.",
            "Rainy days are perfect for hot chocolate and old movies.",
            "What about you, what makes you feel cozy?",
        )
        threadCounts.split(",").mapNotNull { it.trim().toIntOrNull() }.forEach { threads ->
            val voice = KokoroVoice(dir, threads, voiceChoice.speakerId)
            voice.synthesize(lines[0])
            val start = System.currentTimeMillis()
            val bytes = lines.sumOf { voice.synthesize(it)?.size ?: 0 }
            val seconds = bytes / 2.0 / KokoroVoice.AVATAR_RATE
            val took = (System.currentTimeMillis() - start) / 1000.0
            Diag.log("ttsBench threads=$threads audio=%.2fs took=%.2fs RTF=%.2f".format(seconds, took, took / seconds))
            voice.release()
        }
        Diag.log("ttsBench done")
    }

    private fun percent(current: Long, total: Long): Int =
        if (total > 0) (current * 100 / total).toInt() else -1

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun warnIfLowMemory() {
        val info = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        if (info.totalMem < MIN_RECOMMENDED_RAM) {
            Toast.makeText(this, "This phone has under 8 GB of RAM, the companion may not run well", Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val GL_CONTEXT_VERSION = 2
        const val REQUEST_MIC = 1
        const val REQUEST_IMAGE = 2
        const val MIN_SPEECH_SECONDS = 0.4 // shorter is an accidental tap
        const val MAX_IMAGE_SIDE = 768
        const val MIN_RECOMMENDED_RAM = 7_000_000_000L
        const val CAPTION_LINGER_MS = 5_000L
        const val TTS_THREADS = 4 // measured best; 6 and 8 are slower on this 4+4 core CPU
        const val ASR_THREADS = 4 // the language model is idle while the user talks
        const val AVATAR_RELEASE_TIMEOUT_MS = 8_000L
        const val PREF_AVATAR = "avatar"
        const val PREF_VOICE = "voice"
        const val PREF_PROMPT = "prompt"
        const val VOICE_SAMPLE = "Hi there, this is how I sound."

        const val BASE_CONFIG_URL = "https://github.com/duixcom/Duix-Mobile/releases/download/v1.0.0/gj_dh_res.zip"

        // Gemma 4 E2B with refusals removed (huihui-ai), quantized by mradermacher. The projector is
        // its vision encoder, used for photo messages; speech goes through StreamingAsr as text.
        const val GEMMA_REPO = "https://huggingface.co/mradermacher/Huihui-gemma-4-E2B-it-abliterated-GGUF/resolve/main"
        const val MODEL_FILE = "Huihui-gemma-4-E2B-it-abliterated.Q4_K_M.gguf"
        const val MODEL_URL = "$GEMMA_REPO/$MODEL_FILE"
        const val PROJECTOR_FILE = "Huihui-gemma-4-E2B-it-abliterated.mmproj-Q8_0.gguf"
        const val PROJECTOR_URL = "$GEMMA_REPO/$PROJECTOR_FILE"

        const val KOKORO_NAME = "kokoro-multi-lang-v1_0"
        const val KOKORO_REPO = "https://huggingface.co/csukuangfj/kokoro-multi-lang-v1_0/resolve/main"

        // Nemotron Speech Streaming 0.6B, 560 ms chunks, English, from sherpa-onnx's "asr-models" release
        // (sherpa-onnx-nemotron-speech-streaming-en-0.6b-560ms-int8-2026-04-25.tar.bz2). That release only
        // has the .tar.bz2, so the four unpacked files are mirrored on this project's GitHub release.
        const val ASR_NAME = "nemotron-speech-streaming-en-0.6b-560ms-int8"
        const val ASR_REPO = "https://github.com/SolvAI/PocketCompanion/releases/download/speech-model-v1"

        const val FIRST_DOWNLOAD_GB = 5 // Gemma 3.4 GB, vision 0.6 GB, speech recognition 0.7 GB, voice 0.3 GB
    }
}
