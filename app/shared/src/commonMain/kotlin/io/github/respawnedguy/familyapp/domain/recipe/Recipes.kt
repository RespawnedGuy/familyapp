package io.github.respawnedguy.familyapp.domain.recipe

import io.github.respawnedguy.familyapp.domain.Stamp
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * One ingredient line, kept as written ([text]) and split into amount, unit, name and note
 * (schema.org `recipeIngredient` plus structure, docs/kochbibliothek.md "Datenmodell").
 * Amount and unit stay null when the text has none ("Salz und Pfeffer").
 */
@Serializable
data class Ingredient(
    val text: String,
    val amount: Double? = null,
    val amountMax: Double? = null,
    val unit: String? = null,
    val name: String,
    val note: String? = null,
) {
    /** Comparison key ("Rote Zwiebeln" -> "zwiebel"), for the shopping list and later offers. */
    val key: String get() = IngredientKey.of(name)
}

@Serializable
enum class RecipeStatus {
    /** Came from an import and nobody has looked at it yet (HITL, marked "Vorschlag"). */
    SUGGESTED,
    CONFIRMED,
}

/**
 * A recipe of the family library, close to schema.org/Recipe. Synced like items (newest stamp
 * wins per recipe). Deleting keeps the record with [deleted] set and the content emptied, so a
 * device that still has the old version cannot bring it back, and devices with an older app
 * version (which do not know recipes) need no new tombstone kind.
 */
@Serializable
data class Recipe(
    val id: String,
    val title: String,
    /** schema.org recipeYield as a number, the base for scaling; null when the source does not say. */
    val servings: Double? = null,
    val totalMinutes: Int? = null,
    val ingredients: List<Ingredient> = emptyList(),
    val steps: List<String> = emptyList(),
    /** Where it came from (Instagram post, web page); always shown, the source stays visible. */
    val sourceUrl: String? = null,
    /** The text it was built from (caption, pasted text), kept so anybody can check the rules' work. */
    val sourceText: String? = null,
    /** Family notes such as "doppelt Knoblauch". */
    val notes: String = "",
    /**
     * Picture of the dish in this device's private photo folder (the reel's cover or a frame of its
     * video), shrunk to [RecipePhoto] limits. Synced like a capture's photo: the record carries the
     * path, the file goes once to every device under the same name (older apps ignore the field).
     */
    val photoPath: String? = null,
    val status: RecipeStatus = RecipeStatus.SUGGESTED,
    val createdMs: Long,
    val createdBy: String,
    val cookedOn: LocalDate? = null,
    val cookedCount: Int = 0,
    val changedBy: String? = null,
    val deleted: Boolean = false,
    val stamp: Stamp,
)

/**
 * One line of the shared shopping list. Every entry is its own record, so two people who add milk
 * at the same time get two entries; the list shows entries with the same key together.
 * [ask] marks basics such as salt or oil that came from a recipe: shown greyed as "Hast du noch …?"
 * until somebody taps it (docs/kochbibliothek.md "Einkaufsliste").
 */
@Serializable
data class ShoppingEntry(
    val id: String,
    val name: String,
    val amount: Double? = null,
    val unit: String? = null,
    val recipeId: String? = null,
    val ask: Boolean = false,
    val checked: Boolean = false,
    val checkedBy: String? = null,
    /** "bei Aldi Süd, 1,69 €" for entries planned from an offer (Empfehlung 12); older apps skip it. */
    val offer: String? = null,
    val createdMs: Long,
    val createdBy: String,
    val changedBy: String? = null,
    val deleted: Boolean = false,
    val stamp: Stamp,
) {
    val key: String get() = IngredientKey.of(name)
}
