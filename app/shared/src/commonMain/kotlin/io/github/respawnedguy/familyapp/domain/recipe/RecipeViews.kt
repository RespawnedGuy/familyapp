package io.github.respawnedguy.familyapp.domain.recipe

import kotlin.math.max

/** Read-only views of the library for the screens; pure functions, tested without UI. */
object RecipeViews {

    /** Keys shorter than this ("ei") would match inside many words of a step. */
    private const val MIN_MATCH = 3

    /** Smallest and largest number of servings the plus and minus buttons go to. */
    const val MIN_SERVINGS = 1
    const val MAX_SERVINGS = 40

    /**
     * Live recipes matching [query] in title, ingredients or notes ("was mache ich mit Zucchini"),
     * newest first. Every word of the query has to match somewhere.
     */
    fun library(recipes: List<Recipe>, query: String = ""): List<Recipe> {
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        return recipes.filterNot { it.deleted }
            .filter { r -> words.isEmpty() || haystack(r).let { text -> words.all { it in text } } }
            .sortedByDescending { it.createdMs }
    }

    private fun haystack(r: Recipe): String =
        (listOf(r.title, r.notes) + r.ingredients.map { it.name }).joinToString(" ").lowercase()

    /** Factor from the recipe's servings to [servings]; a recipe without servings counts as one batch. */
    fun factor(recipe: Recipe, servings: Int): Double = servings / (recipe.servings ?: 1.0)

    /** Servings to start with: the recipe's own, or 1 batch when it does not say. */
    fun startServings(recipe: Recipe): Int = max(MIN_SERVINGS, (recipe.servings ?: 1.0).toInt())

    /**
     * Ingredients a step mentions, for the cooking mode (concept: "Zutat-Name kommt im Schritttext
     * vor"). Compares the stems, so "Zwiebeln" in the list finds "die Zwiebel" in the step.
     */
    fun ingredientsOfStep(recipe: Recipe, step: String): List<Ingredient> {
        val text = step.lowercase()
        return recipe.ingredients.filter { i ->
            // The key (head noun after synonyms) and the head noun as written: "Olivenöl" has key "öl".
            val head = i.name.lowercase().split(' ', ',').lastOrNull { it.isNotBlank() }.orEmpty()
            val stems = listOf(i.key, IngredientKey.stem(head))
            stems.any { it.length >= MIN_MATCH && it in text }
        }
    }
}
