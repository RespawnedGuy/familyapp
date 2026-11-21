package familyapp.recipe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecipeTextParserTest {

    @Test fun `caption with headings`() {
        val r = RecipeTextParser.parse(
            """
            Ofengemüse 🥕
            Zutaten für 4 Portionen:
            - 500 g Kartoffeln
            - 2 Paprika
            - 3 EL Öl
            Zubereitung:
            1. Gemüse schneiden.
            2. Bei 200 Grad 30 Minuten backen.
            #ofengemüse #einfach
            """.trimIndent()
        )
        assertEquals("Ofengemüse", r.title)
        assertEquals(4.0, r.servings)
        assertEquals(30, r.totalMinutes)
        assertEquals(listOf("Kartoffeln", "Paprika", "Öl"), r.ingredients.map { it.name })
        assertEquals(listOf("Gemüse schneiden.", "Bei 200 Grad 30 Minuten backen."), r.steps)
        assertEquals(ParsedRecipe.Status.COMPLETE, r.status)
    }

    @Test fun `no recipe means no ingredients and nothing invented`() {
        val r = RecipeTextParser.parse("So lecker war unser Wochenende 😍 Rezept im Video! #food")
        assertEquals(ParsedRecipe.Status.NO_INGREDIENTS, r.status)
        assertTrue(r.ingredients.isEmpty())
    }

    @Test fun `list without heading needs at least three lines`() {
        assertEquals(ParsedRecipe.Status.NO_INGREDIENTS, RecipeTextParser.parse("Titel\n200 g Reis\n1 Zwiebel").status)
        assertEquals(3, RecipeTextParser.parse("Titel\n200 g Reis\n1 Zwiebel\n400 ml Brühe").ingredients.size)
    }

    @Test fun `sub headings are skipped`() {
        val r = RecipeTextParser.parse("Kuchen\nZutaten\nFür den Teig:\n200 g Mehl\nFür die Creme:\n200 g Quark")
        assertEquals(listOf("Mehl", "Quark"), r.ingredients.map { it.name })
    }

    @Test fun `promo lines are not steps`() {
        val r = RecipeTextParser.parse("Salat\nZutaten:\n1 Gurke\nZubereitung:\nGurke schneiden.\nSpeichert euch das Rezept!")
        assertEquals(listOf("Gurke schneiden."), r.steps)
    }

    @Test fun `hours and minutes`() {
        assertEquals(90, RecipeTextParser.totalMinutes("1 Stunde ruhen, dann 30 Minuten backen"))
    }
}
