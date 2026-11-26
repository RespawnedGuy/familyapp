package io.github.respawnedguy.familyapp.domain.recipe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Cases from the recipe spike; the examples are made up (the repository is public). */
class RecipeTextParserTest {

    @Test fun captionWithHeadings() {
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
            """.trimIndent(),
        )
        assertEquals("Ofengemüse", r.title)
        assertEquals(4.0, r.servings)
        assertEquals(30, r.totalMinutes)
        assertEquals(listOf("Kartoffeln", "Paprika", "Öl"), r.ingredients.map { it.name })
        assertEquals(listOf("Gemüse schneiden.", "Bei 200 Grad 30 Minuten backen."), r.steps)
        assertEquals(ParsedRecipe.Status.COMPLETE, r.status)
    }

    @Test fun noRecipeMeansNoIngredientsAndNothingInvented() {
        val r = RecipeTextParser.parse("So lecker war unser Wochenende 😍 Rezept im Video! #food")
        assertEquals(ParsedRecipe.Status.NO_INGREDIENTS, r.status)
        assertTrue(r.ingredients.isEmpty())
    }

    @Test fun listWithoutHeadingNeedsAtLeastThreeLines() {
        assertEquals(ParsedRecipe.Status.NO_INGREDIENTS, RecipeTextParser.parse("Titel\n200 g Reis\n1 Zwiebel").status)
        assertEquals(3, RecipeTextParser.parse("Titel\n200 g Reis\n1 Zwiebel\n400 ml Brühe").ingredients.size)
    }

    @Test fun subHeadingsAreSkipped() {
        val r = RecipeTextParser.parse("Kuchen\nZutaten\nFür den Teig:\n200 g Mehl\nFür die Creme:\n200 g Quark")
        assertEquals(listOf("Mehl", "Quark"), r.ingredients.map { it.name })
    }

    @Test fun promoLinesAreNotSteps() {
        val r = RecipeTextParser.parse("Salat\nZutaten:\n1 Gurke\nZubereitung:\nGurke schneiden.\nSpeichert euch das Rezept!")
        assertEquals(listOf("Gurke schneiden."), r.steps)
    }

    @Test fun inlineListAfterHeading() {
        val r = RecipeTextParser.parse("Pfannkuchen\nZutaten: 200 g Mehl, 2 Eier, 300 ml Milch\nSo geht's: Alles verrühren und ausbacken.")
        assertEquals(listOf("Mehl", "Eier", "Milch"), r.ingredients.map { it.name })
        assertEquals(listOf("Alles verrühren und ausbacken."), r.steps)
    }

    @Test fun looseListWithSentencesAsSteps() {
        val r = RecipeTextParser.parse(
            "Linsensuppe\n200 g Linsen\n1 Zwiebel\n1 l Brühe\n2 EL Öl\nZwiebel würfeln und im Öl andünsten.\nMit Brühe aufgießen.",
        )
        assertEquals(4, r.ingredients.size)
        assertEquals(listOf("Zwiebel würfeln und im Öl andünsten."), r.steps)
    }

    @Test fun titleIsNoIngredientButMayStartWithANumber() {
        assertEquals(null, RecipeTextParser.parse("1 Zwiebel\n200 g Reis\n400 ml Brühe").title)
        assertEquals("3-Minuten-Pasta", RecipeTextParser.parse("3-Minuten-Pasta\nZutaten:\n200 g Nudeln").title)
        assertEquals("5 Zutaten Bananenbrot backen", RecipeTextParser.parse("5 Zutaten Bananenbrot backen\nZutaten:\n2 Bananen").title)
    }

    @Test fun hoursAndMinutes() {
        assertEquals(90, RecipeTextParser.totalMinutes("1 Stunde ruhen, dann 30 Minuten backen"))
    }
}
