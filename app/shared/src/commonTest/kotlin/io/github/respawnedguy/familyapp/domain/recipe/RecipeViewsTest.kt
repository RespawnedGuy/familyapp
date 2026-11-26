package io.github.respawnedguy.familyapp.domain.recipe

import io.github.respawnedguy.familyapp.domain.Stamp
import kotlin.test.Test
import kotlin.test.assertEquals

class RecipeViewsTest {
    private fun recipe(id: String, title: String, lines: String, created: Long, servings: Double? = 4.0, deleted: Boolean = false) =
        Recipe(
            id = id, title = title, servings = servings, ingredients = RecipeImport.ingredientsFrom(lines),
            createdMs = created, createdBy = "me", deleted = deleted, stamp = Stamp(created, 0, "d"),
        )

    private val soup = recipe("a", "Zucchinisuppe", "2 Zucchini\n1 Zwiebel\n2 EL Olivenöl", created = 1)
    private val pasta = recipe("b", "Pasta mit Zucchini", "250 g Spaghetti\n1 Zucchini", created = 2)
    private val cake = recipe("c", "Kuchen", "200 g Mehl", created = 3)
    private val gone = recipe("d", "Alt", "1 Zucchini", created = 4, deleted = true)

    @Test fun searchFindsTitleAndIngredientsNewestFirstWithoutDeleted() {
        val all = listOf(soup, pasta, cake, gone)
        assertEquals(listOf("c", "b", "a"), RecipeViews.library(all).map { it.id })
        assertEquals(listOf("b", "a"), RecipeViews.library(all, "zucchini").map { it.id })
        assertEquals(listOf("b"), RecipeViews.library(all, "zucchini spaghetti").map { it.id })
        assertEquals(emptyList(), RecipeViews.library(all, "Lachs"))
    }

    @Test fun factorAndStartServings() {
        assertEquals(0.5, RecipeViews.factor(soup, 2))
        assertEquals(4, RecipeViews.startServings(soup))
        val noServings = recipe("x", "X", "1 Ei", created = 5, servings = null)
        assertEquals(1, RecipeViews.startServings(noServings))
        assertEquals(3.0, RecipeViews.factor(noServings, 3))
    }

    @Test fun stepFindsItsIngredientsByStem() {
        assertEquals(listOf("Zwiebel", "Olivenöl"), RecipeViews.ingredientsOfStep(soup, "Die Zwiebel im Olivenöl andünsten.").map { it.name })
        assertEquals(listOf("Zucchini"), RecipeViews.ingredientsOfStep(soup, "Zucchini dazugeben.").map { it.name })
        assertEquals(emptyList(), RecipeViews.ingredientsOfStep(soup, "Abschmecken."))
    }
}
