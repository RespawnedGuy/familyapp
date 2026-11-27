package io.github.respawnedguy.familyapp.presentation

import io.github.respawnedguy.familyapp.data.FamilyRepository
import io.github.respawnedguy.familyapp.data.Undo
import io.github.respawnedguy.familyapp.data.offers.OfferCache
import io.github.respawnedguy.familyapp.domain.FamilyData
import io.github.respawnedguy.familyapp.domain.offers.IngredientSuggestion
import io.github.respawnedguy.familyapp.domain.offers.Offer
import io.github.respawnedguy.familyapp.domain.offers.OfferHit
import io.github.respawnedguy.familyapp.domain.offers.OfferMatch
import io.github.respawnedguy.familyapp.domain.offers.RecipeSuggestion
import io.github.respawnedguy.familyapp.domain.offers.Suggestions
import io.github.respawnedguy.familyapp.domain.recipe.MealPlan
import io.github.respawnedguy.familyapp.domain.recipe.NewEntry
import io.github.respawnedguy.familyapp.domain.recipe.PlannedMeal
import io.github.respawnedguy.familyapp.domain.recipe.RecipeViews
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.LocalDate

/**
 * The "Wann? / Für wie viele?" sheet. [mealId] set: an already planned dish is moved or removed.
 * [hasServings] false: the recipe names no servings, amounts stay as written.
 */
data class PlanSheet(
    val recipeId: String,
    val title: String,
    val day: LocalDate?,
    val servings: Int,
    val hasServings: Boolean,
    val days: List<LocalDate>,
    val mealId: String? = null,
    /** "bei Aldi Süd, 1,69 €" per ingredient key, written to the list entries (Empfehlung 12). */
    val offerNotes: Map<String, String> = emptyMap(),
)

/** The cards of "Essen diese Woche": recipes, produce, and "Passt nicht". Pure over data and offers. */
class SuggestionActions internal constructor(
    private val repo: FamilyRepository,
    private val show: (String, Undo?) -> Unit,
    private val offers: () -> OfferCache,
) {
    private fun current(data: FamilyData, cache: OfferCache): List<Offer> =
        data.place?.let { cache.offers(it.markets) }.orEmpty()

    private fun rejected(data: FamilyData): Set<String> = data.place?.rejected.orEmpty().toSet()

    /** Recipe cards (same data, same cards). Without a place: season only. */
    fun recipes(data: FamilyData = repo.current, cache: OfferCache = offers()): List<RecipeSuggestion> {
        val today = repo.day()
        val planned = MealPlan.plannedRecipeIds(data.meals, today)
        return Suggestions.recipes(data.recipes, current(data, cache), today, planned, rejected(data))
    }

    /** Produce in season and on offer, shown while the library gives fewer than three recipe cards. */
    fun ingredients(data: FamilyData = repo.current, cache: OfferCache = offers()): List<IngredientSuggestion> =
        if (recipes(data, cache).size >= FEW_RECIPES) emptyList()
        else Suggestions.ingredients(current(data, cache), repo.day(), rejected(data))

    /** "Passt nicht" on an offer reason: this product is not this ingredient, for the whole family. */
    fun reject(hit: OfferHit) {
        val key = OfferMatch.rejectionKey(hit.offer.title, hit.ingredient.key)
        show("„${hit.offer.title}“ zählt nicht mehr als ${hit.ingredient.name}.", repo.placeStore.reject(key))
    }

    /** A produce card tapped: straight onto the list, with the market. */
    fun addIngredient(suggestion: IngredientSuggestion) {
        val undo = repo.shoppingStore.addEntries(
            listOf(NewEntry(suggestion.entry.name, null, null, ask = false)),
            offers = mapOf(suggestion.entry.key to OffersModel.offerNote(suggestion.offers.first())),
        )
        show("${suggestion.entry.name} steht auf der Einkaufsliste.", undo)
    }

    private companion object {
        const val FEW_RECIPES = 3
    }
}

/** "Wann? / Für wie viele?": one sheet for a new dish (list and plan) or a planned one (move, remove). */
class PlanActions internal constructor(
    private val repo: FamilyRepository,
    private val kitchen: KitchenModel,
    private val show: (String, Undo?) -> Unit,
) {
    private val _sheet = MutableStateFlow<PlanSheet?>(null)
    val sheet: StateFlow<PlanSheet?> = _sheet.asStateFlow()

    /** Day: the next one without a dish; people: the number last used on this phone. */
    fun open(suggestion: RecipeSuggestion) {
        val recipe = suggestion.recipe
        val today = repo.day()
        _sheet.value = PlanSheet(
            recipeId = recipe.id,
            title = recipe.title,
            day = MealPlan.nextFreeDay(repo.current.meals, today),
            servings = RecipeViews.startServings(recipe, repo.current.settings.lastServings),
            hasServings = RecipeViews.remembers(recipe),
            days = MealPlan.choosableDays(today),
            offerNotes = suggestion.offers.associate { it.ingredient.key to OffersModel.offerNote(it.offer) },
        )
    }

    fun openPlanned(meal: PlannedMeal) {
        val recipe = repo.recipeStore.recipe(meal.recipeId) ?: return
        _sheet.value = PlanSheet(
            recipeId = recipe.id,
            title = recipe.title,
            day = meal.day,
            servings = meal.servings,
            hasServings = RecipeViews.remembers(recipe),
            days = MealPlan.choosableDays(repo.day()),
            mealId = meal.id,
        )
    }

    fun setDay(day: LocalDate?) {
        _sheet.value = _sheet.value?.copy(day = day)
    }

    fun setServings(servings: Int) {
        _sheet.value = _sheet.value?.let {
            it.copy(servings = servings.coerceIn(RecipeViews.MIN_SERVINGS, RecipeViews.MAX_SERVINGS))
        }
    }

    /** "Auf die Einkaufsliste" (new dish) or "Verschieben" (planned dish). */
    fun confirm() {
        val s = _sheet.value ?: return
        _sheet.value = null
        val mealId = s.mealId
        val recipe = repo.recipeStore.recipe(s.recipeId)
        when {
            mealId != null -> show(
                "${s.title}: ${ShoppingActions.dayLabel(s.day, repo.day())}.",
                repo.mealStore.move(mealId, s.day),
            )
            recipe != null -> kitchen.shopping.planAndAdd(recipe, s.day, s.servings, s.offerNotes)
        }
    }

    fun removePlanned() {
        val s = _sheet.value ?: return
        val mealId = s.mealId ?: return
        _sheet.value = null
        show("${s.title} aus dem Plan genommen.", repo.mealStore.remove(mealId))
    }

    /** "Rezept ansehen" from the sheet of a planned dish: opens it with the planned servings. */
    fun openRecipe() {
        val s = _sheet.value ?: return
        _sheet.value = null
        kitchen.openRecipe(s.recipeId)
        if (s.mealId != null) kitchen.setServings(s.servings)
    }

    fun close() {
        _sheet.value = null
    }
}
