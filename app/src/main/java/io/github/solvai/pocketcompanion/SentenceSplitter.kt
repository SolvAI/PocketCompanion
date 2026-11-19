package io.github.solvai.pocketcompanion

/** Cuts streaming text into sentences, so each can be spoken as soon as it is complete. */
class SentenceSplitter {

    private val pending = StringBuilder()

    fun add(delta: String): List<String> {
        pending.append(delta)
        val sentences = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < pending.length - 1) {
            val endsSentence = pending[i] in TERMINATORS && pending[i + 1].isWhitespace()
            if (endsSentence && pending.substring(start, i + 1).trim().length >= MIN_LENGTH) {
                sentences.add(pending.substring(start, i + 1).trim())
                start = i + 1
            }
            i++
        }
        pending.delete(0, start)
        return sentences
    }

    /** Returns whatever is left at the end of a response. */
    fun flush(): String? {
        val rest = pending.toString().trim()
        pending.setLength(0)
        return rest.ifEmpty { null }
    }

    companion object {
        private const val TERMINATORS = ".!?…\n"
        private const val MIN_LENGTH = 12 // keeps "Dr." or a lone "Oh!" attached to the following words
        private const val QUICK_START_MAX = 45
        private const val QUICK_START_MIN = 15

        /**
         * Splits a long opening sentence at a comma. Speech starts once the first piece is
         * synthesized, and a long first sentence measured about 7 s of silence on the test phone.
         */
        fun quickStart(sentences: List<String>): List<String> {
            val first = sentences.firstOrNull() ?: return sentences
            if (first.length <= QUICK_START_MAX) return sentences
            val cut = first.indexOf(", ", QUICK_START_MIN)
            if (cut < 0 || cut > first.length - QUICK_START_MIN) return sentences
            return listOf(first.substring(0, cut + 1), first.substring(cut + 2)) + sentences.drop(1)
        }
    }
}
