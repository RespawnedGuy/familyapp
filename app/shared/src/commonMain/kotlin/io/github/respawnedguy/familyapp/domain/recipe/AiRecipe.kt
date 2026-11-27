package io.github.respawnedguy.familyapp.domain.recipe

import kotlinx.serialization.Serializable

/** A recipe as the optional cloud AI read it (data/RecipeAi.kt). Same fields as the rules fill. */
@Serializable
data class AiRecipe(
    val title: String = "",
    val servings: Double? = null,
    val totalMinutes: Int? = null,
    val ingredients: List<String> = emptyList(),
    val steps: List<String> = emptyList(),
    /** Second of the video where the finished dish is best to see; only asked when a video comes along. */
    val dishSecond: Double? = null,
) {
    /**
     * Into the same draft the rules build: every ingredient line goes through [IngredientParser],
     * so amounts scale like any other recipe. [sourceText] is the text the AI read, kept for checking.
     */
    fun toDraft(sourceText: String): RecipeDraft {
        val ingredients = ingredients.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_LINES)
            .map(IngredientParser::parse).filter { it.name.isNotBlank() }
        return RecipeDraft(
            title = title.trim().take(MAX_TITLE).ifEmpty { RecipeImport.UNTITLED },
            servings = servings?.takeIf { it > 0 && it <= MAX_SERVINGS },
            totalMinutes = totalMinutes?.takeIf { it in 1..MAX_MINUTES },
            ingredients = ingredients,
            steps = steps.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_LINES),
            sourceUrl = null,
            sourceText = sourceText,
            needsText = ingredients.isEmpty(),
        )
    }
}

private const val MAX_LINES = 100
private const val MAX_TITLE = 200
private const val MAX_SERVINGS = 100.0
private const val MAX_MINUTES = 7 * 24 * 60
