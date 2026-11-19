package io.github.solvai.pocketcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * Draws TikTok-style subtitles: 1 to 4 words in Komika Axis, white with a thick black outline,
 * the spoken word in red. Values come from the Short Factory brief, authored at 1080 px wide.
 */
class TikTokSubtitleView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    private val face = Typeface.createFromAsset(context.assets, FONT)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        color = Color.BLACK
        typeface = face
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        typeface = face
    }

    private class Word(val index: Int, val text: String, val width: Float)

    private var chunks: List<SubtitleChunk> = emptyList()
    private var startedAt = 0L

    /** Starts showing a clip's subtitles; call when its audio starts playing. */
    fun play(clip: List<SubtitleChunk>) {
        chunks = clip
        startedAt = System.nanoTime()
        postInvalidateOnAnimation()
    }

    fun clear() {
        chunks = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val current = chunks
        if (current.isEmpty()) return
        val t = (System.nanoTime() - startedAt) / 1e9
        SubtitleTiming.active(current, t)?.let { (chunk, lit) -> drawChunk(canvas, chunk, lit) }
        if (t < current.last().until) postInvalidateOnAnimation() else if (chunks === current) chunks = emptyList()
    }

    private fun drawChunk(canvas: Canvas, chunk: SubtitleChunk, lit: Int) {
        val w = width.toFloat()
        val h = height.toFloat()
        val scale = w / AUTHORED_WIDTH
        val size = SIZE * scale
        fill.textSize = size
        outline.textSize = size
        outline.strokeWidth = OUTLINE * scale

        val space = fill.measureText(" ") * 1.6f
        val maxWidth = w - 20f * w / 390f
        val lines = mutableListOf(mutableListOf<Word>())
        var lineWidth = 0f
        chunk.words.forEachIndexed { i, word ->
            val wordWidth = fill.measureText(word.text)
            if (lineWidth > 0 && lineWidth + space + wordWidth > maxWidth) {
                lines.add(mutableListOf())
                lineWidth = 0f
            }
            lines.last().add(Word(i, word.text, wordWidth))
            lineWidth += (if (lineWidth > 0) space else 0f) + wordWidth
        }

        // Canvas draws on the baseline; this offset centers glyphs on y, like textBaseline = 'middle'.
        val metrics = fill.fontMetrics
        val middle = -(metrics.ascent + metrics.descent) / 2f
        var y = Y_FRACTION * h + size * 0.5f
        for (line in lines) {
            val total = line.sumOf { it.width.toDouble() }.toFloat() + space * (line.size - 1)
            var x = (w - total) / 2f
            for (word in line) {
                canvas.drawText(word.text, x, y + middle, outline)
                fill.color = if (word.index == lit) HIGHLIGHT else Color.WHITE
                canvas.drawText(word.text, x, y + middle, fill)
                x += word.width + space
            }
            y += size * 1.15f
        }
    }

    private companion object {
        const val FONT = "fonts/KomikaAxis.ttf"
        const val AUTHORED_WIDTH = 1080f
        const val SIZE = 64f
        const val OUTLINE = 17f
        const val Y_FRACTION = 0.66f
        val HIGHLIGHT = Color.parseColor("#E0244A")
    }
}
