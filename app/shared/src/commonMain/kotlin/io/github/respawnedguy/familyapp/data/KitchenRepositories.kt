package io.github.respawnedguy.familyapp.data

import io.github.respawnedguy.familyapp.domain.recipe.NewEntry
import io.github.respawnedguy.familyapp.domain.recipe.Recipe
import io.github.respawnedguy.familyapp.domain.recipe.RecipeDraft
import io.github.respawnedguy.familyapp.domain.recipe.RecipeImport
import io.github.respawnedguy.familyapp.domain.recipe.RecipeStatus
import io.github.respawnedguy.familyapp.domain.recipe.ShoppingEntry

/** What the edit form hands back; the repository turns it into a new version of the recipe. */
data class RecipeEdit(
    val title: String,
    val servings: Double?,
    val totalMinutes: Int?,
    val ingredientLines: String,
    val stepLines: String,
    val sourceUrl: String?,
    val notes: String,
)

/**
 * Recipes of the kitchen library (docs/kochbibliothek.md). Same state, clock and storage as
 * [FamilyRepository]; every change gets a new stamp and the name of who made it, so sync merges
 * per recipe (newest wins) and everybody sees who changed what. Deleting sets `deleted` instead of
 * removing (see [Recipe]).
 */
class RecipeRepository internal constructor(private val repo: FamilyRepository) {

    private val self: String? get() = repo.current.selfId

    fun recipe(id: String): Recipe? = repo.current.recipes.firstOrNull { it.id == id && !it.deleted }

    /** A new recipe from the rules' draft. [status] is SUGGESTED for imports, CONFIRMED when typed by hand. */
    fun add(draft: RecipeDraft, status: RecipeStatus): Pair<Recipe, Undo> {
        val recipe = Recipe(
            id = repo.nextId(),
            title = draft.title,
            servings = draft.servings,
            totalMinutes = draft.totalMinutes,
            ingredients = draft.ingredients,
            steps = draft.steps,
            sourceUrl = draft.sourceUrl,
            sourceText = draft.sourceText?.take(SharedInput.MAX_TEXT_CHARS),
            status = status,
            createdMs = repo.now(),
            createdBy = self.orEmpty(),
            changedBy = self,
            stamp = repo.tick(),
        )
        repo.edit { it.copy(recipes = it.recipes + recipe) }
        return recipe to Undo(emptyMap(), recipesBefore = mapOf(recipe.id to null))
    }

    private fun changeRecipe(id: String, block: (Recipe) -> Recipe): Undo {
        val before = recipe(id) ?: return Undo(emptyMap())
        repo.edit { d ->
            d.copy(
                recipes = d.recipes.map {
                    if (it.id == id) block(it).copy(changedBy = d.selfId, stamp = repo.tick()) else it
                },
            )
        }
        return Undo(emptyMap(), recipesBefore = mapOf(id to before))
    }

    /** Saving the edit form also confirms a suggestion: somebody looked at it. */
    fun update(id: String, edit: RecipeEdit): Undo = changeRecipe(id) { r ->
        r.copy(
            title = edit.title.trim().ifEmpty { r.title },
            servings = edit.servings?.takeIf { it > 0 },
            totalMinutes = edit.totalMinutes?.takeIf { it > 0 },
            ingredients = RecipeImport.ingredientsFrom(edit.ingredientLines),
            steps = RecipeImport.stepsFrom(edit.stepLines),
            sourceUrl = edit.sourceUrl?.trim()?.takeIf { it.isNotEmpty() },
            notes = edit.notes.trim(),
            status = RecipeStatus.CONFIRMED,
        )
    }

    /** "Passt" on a suggestion. */
    fun confirm(id: String): Undo = changeRecipe(id) { it.copy(status = RecipeStatus.CONFIRMED) }

    /** "Gekocht" at the end of the cooking mode: keeps "zuletzt gekocht" fresh for later suggestions. */
    fun markCooked(id: String): Undo =
        changeRecipe(id) { it.copy(cookedOn = repo.day(), cookedCount = it.cookedCount + 1) }

    fun delete(id: String): Undo = changeRecipe(id) { it.emptied() }

    /** Puts recipes back; one that did not exist before is deleted again (with a stamp, so sync agrees). */
    internal fun revert(current: List<Recipe>, before: Map<String, Recipe?>): List<Recipe> =
        current.map { r ->
            when {
                r.id !in before -> r
                else -> before[r.id]?.copy(stamp = repo.tick()) ?: r.emptied().copy(stamp = repo.tick())
            }
        }
}

private fun Recipe.emptied() =
    copy(deleted = true, ingredients = emptyList(), steps = emptyList(), sourceText = null, notes = "")

/**
 * The shared shopping list. Every entry is its own record with its own stamp, ticking is a field
 * (last change wins), so two people in the same shop see each other's ticks once their phones meet.
 */
class ShoppingRepository internal constructor(private val repo: FamilyRepository) {

    private val self: String? get() = repo.current.selfId

    /** Adds entries, from a recipe ([recipeId]) or typed. Returns the undo handle. */
    fun addEntries(entries: List<NewEntry>, recipeId: String? = null): Undo {
        val created = entries.map { e ->
            ShoppingEntry(
                id = repo.nextId(),
                name = e.name,
                amount = e.amount,
                unit = e.unit,
                recipeId = recipeId,
                ask = e.ask,
                createdMs = repo.now(),
                createdBy = self.orEmpty(),
                changedBy = self,
                stamp = repo.tick(),
            )
        }
        if (created.isEmpty()) return Undo(emptyMap())
        repo.edit { it.copy(shopping = it.shopping + created) }
        return Undo(emptyMap(), shoppingBefore = created.associate { it.id to null })
    }

    private fun changeEntries(ids: Collection<String>, block: (ShoppingEntry) -> ShoppingEntry): Undo {
        val before = repo.current.shopping.filter { it.id in ids && !it.deleted }
        if (before.isEmpty()) return Undo(emptyMap())
        val touched = before.map { it.id }.toSet()
        repo.edit { d ->
            d.copy(
                shopping = d.shopping.map {
                    if (it.id in touched) block(it).copy(changedBy = d.selfId, stamp = repo.tick()) else it
                },
            )
        }
        return Undo(emptyMap(), shoppingBefore = before.associateBy { it.id })
    }

    /** Ticks or unticks a line (all its entries). Everybody sees who ticked it. */
    fun setChecked(ids: Collection<String>, checked: Boolean): Undo =
        changeEntries(ids) { it.copy(checked = checked, checkedBy = if (checked) self else null) }

    /** "Hast du noch Salz?" tapped: it goes on the list as a normal line. */
    fun needIt(ids: Collection<String>): Undo = changeEntries(ids) { it.copy(ask = false) }

    fun remove(ids: Collection<String>): Undo = changeEntries(ids) { it.copy(deleted = true) }

    /** After shopping: ticked lines go away for everybody. */
    fun clearChecked(): Undo = remove(repo.current.shopping.filter { it.checked && !it.deleted }.map { it.id })

    /** Undo: puts entries back; one that did not exist before is removed again. */
    internal fun revert(
        current: List<ShoppingEntry>,
        before: Map<String, ShoppingEntry?>,
    ): List<ShoppingEntry> =
        current.map { e ->
            when {
                e.id !in before -> e
                else -> before[e.id]?.copy(stamp = repo.tick()) ?: e.copy(deleted = true, stamp = repo.tick())
            }
        }
}
