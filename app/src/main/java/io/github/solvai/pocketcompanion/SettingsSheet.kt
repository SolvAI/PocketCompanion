package io.github.solvai.pocketcompanion

import io.github.solvai.pocketcompanion.databinding.ActivityCompanionBinding
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/** The bottom sheet where the user picks a companion and her voice, and edits her instructions. */
class SettingsSheet(
    private val context: Context,
    private val binding: ActivityCompanionBinding,
    private val listener: Listener,
) {

    interface Listener {
        fun onAvatarPicked(option: AvatarOption)
        fun onVoicePicked(option: VoiceOption)
        fun isDownloaded(option: AvatarOption): Boolean
        fun onPromptSaved(template: String)
    }

    private class Card(val root: FrameLayout, val size: TextView)

    private val cards = HashMap<String, Card>()
    private val chips = HashMap<String, TextView>()
    private var built = false
    private lateinit var promptField: EditText
    private lateinit var saveButton: TextView
    private lateinit var resetButton: TextView
    private var savedPrompt = Persona.DEFAULT

    var isOpen = false
        private set

    init {
        binding.settingsScrim.setOnClickListener { hide() }
    }

    fun show(avatar: AvatarOption, voice: VoiceOption, promptTemplate: String) {
        if (!built) build()
        savedPrompt = promptTemplate
        promptField.setText(promptTemplate)
        updatePromptButtons()
        Catalog.avatars.forEach { option ->
            val card = cards.getValue(option.id)
            val selected = option.id == avatar.id
            card.root.foreground = if (selected) drawable(R.drawable.bg_card_selected) else null
            card.size.text = if (listener.isDownloaded(option)) "" else "${option.sizeMb} MB"
            card.size.visibility = if (card.size.text.isEmpty()) View.GONE else View.VISIBLE
        }
        selectVoice(voice)

        isOpen = true
        binding.settingsScrim.visibility = View.VISIBLE
        binding.settingsScrim.animate().alpha(1f).setDuration(200).start()
        binding.settingsSheet.visibility = View.VISIBLE
        binding.settingsSheet.post {
            binding.settingsSheet.translationY = binding.settingsSheet.height.toFloat()
            binding.settingsSheet.animate().translationY(0f).setDuration(240).start()
        }
    }

    fun hide() {
        if (!isOpen) return
        isOpen = false
        hideKeyboard()
        binding.settingsScrim.animate().alpha(0f).setDuration(200).withEndAction {
            binding.settingsScrim.visibility = View.GONE
        }.start()
        binding.settingsSheet.animate().translationY(binding.settingsSheet.height.toFloat()).setDuration(220)
            .withEndAction { binding.settingsSheet.visibility = View.GONE }.start()
    }

    fun selectVoice(voice: VoiceOption) {
        Catalog.voices.forEach { option ->
            val chip = chips[option.id] ?: return@forEach
            val selected = option.id == voice.id
            chip.background = drawable(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip)
            chip.setTextColor(ContextCompat.getColor(context, if (selected) R.color.ink else R.color.paper))
        }
    }

    private fun build() {
        built = true
        val content = binding.settingsContent
        content.addView(sectionLabel("Companion"))
        content.addView(avatarGrid())
        Catalog.voices.groupBy { it.language }.forEach { (language, voices) ->
            content.addView(sectionLabel("$language voice"))
            content.addView(voiceGrid(voices))
        }
        content.addView(sectionLabel("Her instructions"))
        content.addView(promptEditor())
        content.addView(credits())
    }

    /** duix.com's license asks for a visible "Powered by Duix.com"; the other parts get credit too. */
    private fun credits() = TextView(context).apply {
        text = "PocketCompanion ${BuildConfig.VERSION_NAME} · open source\n" +
            "Powered by Duix.com · llama.cpp · sherpa-onnx\n" +
            "Gemma 4 · Kokoro · NVIDIA Nemotron Speech\n" +
            "github.com/SolvAI/PocketCompanion"
        textSize = 12f
        gravity = Gravity.CENTER
        setLineSpacing(0f, 1.3f)
        setTextColor(0x66FFFFFF)
        setPadding(0, dp(28), 0, dp(8))
        isClickable = true
        setOnClickListener {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL)))
        }
    }

    private fun promptEditor(): LinearLayout {
        promptField = EditText(context).apply {
            background = drawable(R.drawable.bg_chip)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 5
            textSize = 14f
            setLineSpacing(0f, 1.15f)
            setTextColor(ContextCompat.getColor(context, R.color.paper))
            setHintTextColor(0x66FFFFFF)
            hint = "Who she is and how she talks"
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            textCursorDrawable = drawable(R.drawable.cursor)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = updatePromptButtons()
            })
        }
        val note = TextView(context).apply {
            text = "${Persona.NAME_TOKEN} becomes her name. Saving starts a new conversation."
            textSize = 12f
            setTextColor(0x80FFFFFF.toInt())
            setPadding(dp(2), dp(8), 0, dp(12))
        }
        resetButton = TextView(context).apply {
            text = "Reset to default"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xB3FFFFFF.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            setOnClickListener { promptField.setText(Persona.DEFAULT) }
        }
        saveButton = TextView(context).apply {
            text = "Save"
            textSize = 15f
            gravity = Gravity.CENTER
            background = drawable(R.drawable.bg_chip_selected)
            setTextColor(ContextCompat.getColor(context, R.color.ink))
            setPadding(dp(28), dp(12), dp(28), dp(12))
            isClickable = true
            setOnClickListener {
                savedPrompt = Persona.normalize(promptField.text.toString())
                promptField.setText(savedPrompt)
                promptField.clearFocus()
                hideKeyboard()
                listener.onPromptSaved(savedPrompt)
            }
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            addView(resetButton)
            addView(saveButton)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(promptField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(note)
            addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /** Save only lights up when there is a change to save; reset only when the text is not already the default. */
    private fun updatePromptButtons() {
        val text = promptField.text.toString()
        val changed = Persona.normalize(text) != savedPrompt
        saveButton.isEnabled = changed
        saveButton.alpha = if (changed) 1f else 0.35f
        val isDefault = Persona.normalize(text) == Persona.DEFAULT
        resetButton.isEnabled = !isDefault
        resetButton.alpha = if (isDefault) 0.35f else 1f
    }

    private fun hideKeyboard() {
        if (!built) return
        val keyboard = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        keyboard.hideSoftInputFromWindow(promptField.windowToken, 0)
    }

    private fun sectionLabel(text: String) = TextView(context).apply {
        this.text = text
        textSize = 13f
        letterSpacing = 0.02f
        setTextColor(0x99FFFFFF.toInt())
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(20)
            bottomMargin = dp(10)
        }
    }

    private fun avatarGrid(): GridLayout {
        val columns = 3
        val gap = dp(10)
        val width = (context.resources.displayMetrics.widthPixels - 2 * dp(SHEET_PADDING_DP) - (columns - 1) * gap) / columns
        val height = width * 4 / 3
        val grid = GridLayout(context).apply { columnCount = columns }
        Catalog.avatars.forEachIndexed { index, option ->
            val card = avatarCard(option)
            cards[option.id] = card
            grid.addView(card.root, GridLayout.LayoutParams().apply {
                this.width = width
                this.height = height
                rightMargin = if (index % columns == columns - 1) 0 else gap
                bottomMargin = gap
            })
        }
        return grid
    }

    private fun avatarCard(option: AvatarOption): Card {
        val root = FrameLayout(context).apply {
            background = drawable(R.drawable.bg_card)
            clipToOutline = true
            isClickable = true
            contentDescription = option.name
            setOnClickListener { listener.onAvatarPicked(option) }
        }
        root.addView(ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            context.assets.open("avatars/${option.id}.jpg").use { setImageBitmap(BitmapFactory.decodeStream(it)) }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(View(context).apply { background = drawable(R.drawable.bg_card_name) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64), Gravity.BOTTOM))

        val size = TextView(context).apply {
            textSize = 11f
            setTextColor(0xB3FFFFFF.toInt())
        }
        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(10), dp(8))
            addView(TextView(context).apply {
                text = option.name
                textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.paper))
            })
            addView(size)
        }
        root.addView(labels, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM,
        ))
        return Card(root, size)
    }

    private fun voiceGrid(voices: List<VoiceOption>): GridLayout {
        val columns = 2
        val gap = dp(8)
        val grid = GridLayout(context).apply { columnCount = columns }
        voices.forEachIndexed { index, option ->
            val chip = TextView(context).apply {
                text = option.name
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(12))
                isClickable = true
                setOnClickListener { listener.onVoicePicked(option) }
            }
            chips[option.id] = chip
            grid.addView(chip, GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
            ).apply {
                width = 0
                rightMargin = if (index % columns == columns - 1) 0 else gap
                bottomMargin = gap
            })
        }
        return grid
    }

    private fun drawable(id: Int) = ContextCompat.getDrawable(context, id)

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val SHEET_PADDING_DP = 20
        const val PROJECT_URL = "https://github.com/SolvAI/PocketCompanion"
    }
}
