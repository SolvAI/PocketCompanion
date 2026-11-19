package io.github.solvai.pocketcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaTest {

    @Test
    fun defaultUsesTheCompanionsName() {
        val prompt = Persona.render(Persona.DEFAULT, "Yi Yao")
        assertTrue(prompt, prompt.startsWith("You are Yi Yao,"))
        assertTrue(prompt, Persona.NAME_TOKEN !in prompt)
    }

    @Test
    fun everyNameTokenIsReplaced() {
        assertEquals("Mia here. Call me Mia.", Persona.render("{name} here. Call me {name}.", "Mia"))
    }

    @Test
    fun promptWithoutTokenIsUsedAsIs() {
        assertEquals("You are a pirate.", Persona.render("  You are a pirate.\n", "Mia"))
    }

    @Test
    fun blankPromptFallsBackToDefault() {
        assertEquals(Persona.DEFAULT, Persona.normalize("   \n"))
        assertEquals(Persona.DEFAULT, Persona.normalize(null))
    }
}
