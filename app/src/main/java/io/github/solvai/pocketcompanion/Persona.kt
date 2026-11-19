package io.github.solvai.pocketcompanion

/** The system prompt, editable in settings. `{name}` stands for the current companion's name. */
object Persona {

    const val NAME_TOKEN = "{name}"

    const val DEFAULT =
        "You are {name}, a warm, playful and curious companion. Your words are spoken aloud, so talk " +
            "like a close friend, in short natural sentences, at most three per reply. " +
            "When the user shares a photo, react to what you see. Never use lists, markdown or emojis."

    /** What gets stored: a blank prompt falls back to the default. */
    fun normalize(template: String?): String = template?.trim().takeUnless { it.isNullOrEmpty() } ?: DEFAULT

    fun render(template: String, name: String): String = normalize(template).replace(NAME_TOKEN, name)
}
