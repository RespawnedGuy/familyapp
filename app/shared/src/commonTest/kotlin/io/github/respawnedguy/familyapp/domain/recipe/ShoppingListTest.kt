package io.github.respawnedguy.familyapp.domain.recipe

import io.github.respawnedguy.familyapp.domain.Stamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShoppingListTest {
    private var n = 0

    private fun entry(name: String, amount: Double? = null, unit: String? = null, checked: Boolean = false, ask: Boolean = false, recipe: String? = null) =
        ShoppingEntry(
            id = "e${n++}", name = name, amount = amount, unit = unit, recipeId = recipe, ask = ask, checked = checked,
            createdMs = n.toLong(), createdBy = "me", stamp = Stamp(n.toLong(), 0, "d"),
        )

    @Test fun sameIngredientIsAddedUpAcrossUnitsOfOneKind() {
        val view = ShoppingList.view(listOf(entry("Mehl", 500.0, "g"), entry("Mehl", 0.5, "kg", recipe = "r1")))
        val line = view.open.single()
        assertEquals("1 kg", line.amounts)
        assertEquals(setOf("r1"), line.recipeIds)
        assertEquals(2, line.entryIds.size)
    }

    @Test fun spoonsAndGramsStaySideBySide() {
        val view = ShoppingList.view(listOf(entry("Zucker", 2.0, "EL"), entry("Zucker", 100.0, "g")))
        assertEquals("2 EL + 100 g", view.open.single().amounts)
    }

    @Test fun pluralAndSynonymsMeetInOneLine() {
        val view = ShoppingList.view(listOf(entry("Zwiebel", 1.0), entry("rote Zwiebeln", 2.0)))
        assertEquals("3", view.open.single().amounts)
    }

    @Test fun checkedAskAndDeletedAreSeparated() {
        val deleted = entry("Butter").copy(deleted = true)
        val view = ShoppingList.view(listOf(entry("Milch"), entry("Eier", checked = true), entry("Salz", ask = true), deleted))
        assertEquals(listOf("Milch"), view.open.map { it.name })
        assertEquals(listOf("Eier"), view.checked.map { it.name })
        assertEquals(listOf("Salz"), view.ask.map { it.name })
    }

    @Test fun fromRecipeScalesAsksForBasicsAndSkipsWater() {
        val recipe = Recipe(
            id = "r", title = "Suppe", servings = 2.0, createdMs = 0, createdBy = "me", stamp = Stamp(0, 0, "d"),
            ingredients = RecipeImport.ingredientsFrom("200 g Linsen\n1 l Wasser\n1 TL Salz\n2 EL Olivenöl\nPetersilie"),
        )
        val entries = ShoppingList.fromRecipe(recipe, 2.0)
        assertEquals(listOf("Linsen", "Salz", "Olivenöl", "Petersilie"), entries.map { it.name })
        assertEquals(400.0, entries[0].amount)
        assertEquals(listOf(false, true, true, false), entries.map { it.ask })
    }

    @Test fun typedTextSplitsIntoEntries() {
        val entries = ShoppingList.splitTyped("Milch und 6 Eier, 2 l Saft")
        assertEquals(listOf("Milch", "Eier", "Saft"), entries.map { it.name })
        assertEquals(listOf(null, 6.0, 2.0), entries.map { it.amount })
        assertEquals("l", entries[2].unit)
        assertTrue(entries.none { it.ask })
        assertTrue(ShoppingList.splitTyped("  ,  ").isEmpty())
    }
}
