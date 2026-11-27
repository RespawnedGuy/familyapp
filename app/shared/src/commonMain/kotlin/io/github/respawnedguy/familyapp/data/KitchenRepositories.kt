package io.github.respawnedguy.familyapp.data

import io.github.respawnedguy.familyapp.domain.recipe.IngredientKey
import io.github.respawnedguy.familyapp.domain.recipe.NewEntry
import io.github.respawnedguy.familyapp.domain.recipe.PlannedMeal
import io.github.respawnedguy.familyapp.domain.recipe.Recipe
import io.github.respawnedguy.familyapp.domain.recipe.RecipeDraft
import io.github.respawnedguy.familyapp.domain.recipe.RecipeImport
import io.github.respawnedguy.familyapp.domain.recipe.RecipeStatus
import io.github.respawnedguy.familyapp.domain.recipe.ShoppingEntry
import kotlinx.datetime.LocalDate

/** What the edit form hands back; the repository turns it into a new version of the recipe. */
data class RecipeEdit(
    val title: String,
    val servings: Double?,
    val totalMinutes: Int?,
    val ingredientLines: String,
    val stepLines: String,
    val sourceUrl: String?,
    val notes: String,
    /** "Bild entfernen" ticked in the form. */
    val removePhoto: Boolean = false,
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
        return recipe to Undo(emptyMap(), kitchen = KitchenBefore(recipes = mapOf(recipe.id to null)))
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
        return Undo(emptyMap(), kitchen = KitchenBefore(recipes = mapOf(id to before)))
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
            photoPath = if (edit.removePhoto) null else r.photoPath,
            status = RecipeStatus.CONFIRMED,
        )
    }

    /**
     * Text fetched later for a suggestion that came as a link (Instagram caption, ADR 0007). Stays a
     * suggestion: the rules filled it, a person still confirms. The link-only title gives way to the
     * caption's title; source link and notes stay. [photoPath] (the post's cover) only fills a gap.
     */
    fun fillSuggestion(id: String, draft: RecipeDraft, photoPath: String? = null): Undo = changeRecipe(id) { r ->
        val placeholder = draft.title == RecipeImport.UNTITLED || draft.title == RecipeImport.INSTAGRAM_TITLE
        r.copy(
            title = if (placeholder) r.title else draft.title,
            servings = draft.servings ?: r.servings,
            totalMinutes = draft.totalMinutes ?: r.totalMinutes,
            ingredients = draft.ingredients,
            steps = draft.steps,
            sourceText = draft.sourceText?.take(SharedInput.MAX_TEXT_CHARS),
            photoPath = r.photoPath ?: photoPath,
        )
    }

    /**
     * A picture for a recipe that has none yet (cover fetched later, frame of the video). Never
     * replaces a picture, so a picture chosen meanwhile on another phone stays. Null: nothing changed.
     */
    fun attachPhoto(id: String, photoPath: String): Undo? {
        if (recipe(id)?.photoPath != null) return null
        return changeRecipe(id) { it.copy(photoPath = photoPath) }
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
    copy(
        deleted = true, ingredients = emptyList(), steps = emptyList(), sourceText = null, notes = "", photoPath = null,
    )

/**
 * The shared shopping list. Every entry is its own record with its own stamp, ticking is a field
 * (last change wins), so two people in the same shop see each other's ticks once their phones meet.
 */
class ShoppingRepository internal constructor(private val repo: FamilyRepository) {

    private val self: String? get() = repo.current.selfId

    /** Adds entries, from a recipe ([recipeId]) or typed. Returns the undo handle. */
    fun addEntries(entries: List<NewEntry>, recipeId: String? = null, offers: Map<String, String> = emptyMap()): Undo {
        val created = entries.map { e ->
            ShoppingEntry(
                offer = offers[IngredientKey.of(e.name)],
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
        return Undo(emptyMap(), kitchen = KitchenBefore(shopping = created.associate { it.id to null }))
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
        return Undo(emptyMap(), kitchen = KitchenBefore(shopping = before.associateBy { it.id }))
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

/**
 * The family's meal plan (docs/angebote-und-vorschlaege.md "Essensplan"). Same state and clock as
 * [FamilyRepository]; every change is a new version with a new stamp, removing sets `deleted`.
 */
class MealPlanRepository internal constructor(private val repo: FamilyRepository) {

    private val self: String? get() = repo.current.selfId

    private fun meal(id: String): PlannedMeal? = repo.current.meals.firstOrNull { it.id == id && !it.deleted }

    /** Plans [recipeId] for [day] (null: "Ohne Tag") with [servings]. */
    fun plan(recipeId: String, day: LocalDate?, servings: Int): Undo {
        val meal = PlannedMeal(
            id = repo.nextId(),
            recipeId = recipeId,
            day = day,
            servings = servings,
            plannedBy = self.orEmpty(),
            createdMs = repo.now(),
            changedBy = self,
            stamp = repo.tick(),
        )
        repo.edit { it.copy(meals = it.meals + meal) }
        return Undo(emptyMap(), kitchen = KitchenBefore(meals = mapOf(meal.id to null)))
    }

    private fun change(id: String, block: (PlannedMeal) -> PlannedMeal): Undo {
        val before = meal(id) ?: return Undo(emptyMap())
        repo.edit { d ->
            d.copy(
                meals = d.meals.map {
                    if (it.id == id) block(it).copy(changedBy = d.selfId, stamp = repo.tick()) else it
                },
            )
        }
        return Undo(emptyMap(), kitchen = KitchenBefore(meals = mapOf(id to before)))
    }

    fun move(id: String, day: LocalDate?): Undo = change(id) { it.copy(day = day) }

    fun remove(id: String): Undo = change(id) { it.copy(deleted = true) }

    /**
     * "Gekocht?" in the cooking mode: the dish planned for this recipe nearest to [today]
     * (today, else the earliest open one) is ticked off.
     */
    fun markCooked(recipeId: String, today: LocalDate): Undo? {
        val open = repo.current.meals.filter { it.recipeId == recipeId && !it.deleted && !it.done }
        val target = open.firstOrNull { it.day == today }
            ?: open.filter { it.day == null || it.day <= today }.minByOrNull { it.day ?: today }
            ?: return null
        return change(target.id) { it.copy(done = true) }
    }
}
