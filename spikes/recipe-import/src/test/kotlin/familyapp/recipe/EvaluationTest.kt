package familyapp.recipe

import kotlin.test.Test
import kotlin.test.assertEquals

class EvaluationTest {

    @Test fun `keys group spellings`() {
        assertEquals("zwiebel", IngredientKey.of("rote Zwiebeln, fein gewürfelt"))
        assertEquals(IngredientKey.of("Kirschtomaten"), IngredientKey.of("Tomate"))
        assertEquals("salz", IngredientKey.of("Salz und Pfeffer"))
        assertEquals(IngredientKey.of("Eier"), IngredientKey.of("Ei"))
    }

    @Test fun `counts found, amount and invented`() {
        val parsed = RecipeTextParser.parse("Test\nZutaten:\n250 g Mehl\n2 Eier\nLecker lecker\nZubereitung:\nBacken.")
        val s = Evaluation.score("t", parsed, listOf("250 g Mehl", "3 Eier", "1 Prise Salz"))
        assertEquals(3, s.expected)
        assertEquals(2, s.found)
        assertEquals(1, s.amountAndUnitCorrect) // 2 Eier vs. 3 Eier
        assertEquals(1, s.invented) // "Lecker lecker"
        assertEquals(listOf("1 Prise Salz"), s.missing)
    }

    @Test fun `expected file`() {
        val e = ExpectedFile.parse("id;line\na;250 g Mehl\na;2 Eier\n# Kommentar\nb;1 Gurke\n")
        assertEquals(mapOf("a" to listOf("250 g Mehl", "2 Eier"), "b" to listOf("1 Gurke")), e)
    }
}
