package io.github.respawnedguy.familyapp.domain.recipe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Threat model TE-1: shared text is untrusted; only rules decide, nothing goes online. Examples are made up. */
class RecipeImportTest {

    private val caption = """
        Cremige Tomatensuppe 🍅 für 2 Personen
        Zutaten:
        - 500 g Tomaten
        - 1 Zwiebel
        - 100 ml Sahne
        Zubereitung:
        1. Zwiebel würfeln und andünsten.
        2. Tomaten dazu, 15 Minuten köcheln und pürieren.
        #suppe
        https://www.instagram.com/reel/ABC123/?igsh=xyz&utm_source=ig
    """.trimIndent()

    @Test fun completeCaptionIsARecipe() {
        assertTrue(RecipeImport.isRecipe(caption))
        val d = RecipeImport.fromText(caption)
        assertEquals("Cremige Tomatensuppe für 2 Personen", d.title)
        assertEquals(2.0, d.servings)
        assertEquals(3, d.ingredients.size)
        assertEquals(2, d.steps.size)
        assertEquals("https://www.instagram.com/reel/ABC123/", d.sourceUrl)
        assertFalse(d.needsText)
    }

    @Test fun schoolMailIsNotARecipe() {
        val mail = "Liebe Eltern, am Freitag ist Kuchenbasar. Bitte bis Donnerstag 2 Kuchen anmelden. 1. Klasse"
        assertFalse(RecipeImport.isRecipe(mail))
        assertFalse(RecipeImport.isRecipe("Termin Zahnarzt 14 Uhr"))
        assertFalse(RecipeImport.isRecipe("https://www.schule.example/elternbrief.pdf"))
    }

    @Test fun ingredientsWithoutStepsStayACapture() {
        // A list alone is more often a shopping note than a recipe: it stays in the inbox.
        assertFalse(RecipeImport.isRecipe("Zutaten:\n200 g Mehl\n2 Eier\n100 ml Milch"))
    }

    @Test fun instagramLinkAloneBecomesASuggestionWithoutText() {
        val shared = "Schau mal – https://www.instagram.com/reel/C0de-9x/?igsh=MTc4"
        assertTrue(RecipeImport.isRecipe(shared))
        val d = RecipeImport.fromText(shared)
        assertEquals(RecipeImport.INSTAGRAM_TITLE, d.title)
        assertEquals("https://www.instagram.com/reel/C0de-9x/", d.sourceUrl)
        assertTrue(d.needsText)
        assertTrue(d.ingredients.isEmpty())
    }

    @Test fun instagramProfileLinkIsNoRecipe() {
        assertFalse(RecipeImport.isRecipe("https://www.instagram.com/someaccount/"))
    }

    @Test fun trackingParametersAreRemovedOthersKept() {
        assertEquals("https://example.org/r?id=5", RecipeImport.cleanUrl("https://example.org/r?utm_source=x&id=5&fbclid=abc"))
        assertEquals("https://example.org/r#zutaten", RecipeImport.cleanUrl("https://example.org/r?si=1#zutaten"))
        assertNull(RecipeImport.firstUrl("kein Link"))
        assertEquals("https://example.org/a", RecipeImport.firstUrl("(siehe https://example.org/a)."))
    }

    @Test fun onlyWebLinksAreTaken() {
        assertNull(RecipeImport.firstUrl("javascript:alert(1) intent://x file:///data/data"))
    }

    @Test fun editLinesBecomeIngredientsAndSteps() {
        assertEquals(listOf("Mehl", "Eier"), RecipeImport.ingredientsFrom("250 g Mehl\n\n  2 Eier ").map { it.name })
        assertEquals(listOf("Teig rühren.", "Backen."), RecipeImport.stepsFrom("1. Teig rühren.\n2) Backen.\n"))
    }

    @Test fun untitledTextGetsAPlainTitle() {
        assertEquals(RecipeImport.UNTITLED, RecipeImport.fromText("200 g Reis\n1 Zwiebel\n400 ml Brühe").title)
    }
}
