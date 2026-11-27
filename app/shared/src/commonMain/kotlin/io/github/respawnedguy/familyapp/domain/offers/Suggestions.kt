package io.github.respawnedguy.familyapp.domain.offers

import io.github.respawnedguy.familyapp.domain.recipe.Ingredient
import io.github.respawnedguy.familyapp.domain.recipe.Recipe
import io.github.respawnedguy.familyapp.domain.recipe.RecipeStatus
import io.github.respawnedguy.familyapp.domain.recipe.ShoppingList
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus

/** An ingredient of a suggested recipe that is on offer this week. */
data class OfferHit(val ingredient: Ingredient, val offer: Offer)

/** An ingredient of a suggested recipe that is in season (or from German storage). */
data class SeasonHit(val ingredient: Ingredient, val level: SeasonLevel)

/** A recipe of the family library worth cooking now, with the reasons the card shows. */
data class RecipeSuggestion(
    val recipe: Recipe,
    val score: Double,
    val offers: List<OfferHit>,
    val season: List<SeasonHit>,
) {
    /** "Hackfleisch bei Aldi Süd −24 %", "Grünkohl hat Saison": at most [Suggestions.MAX_REASONS]. */
    val reasons: List<String>
        get() = (offers.map { Suggestions.offerReason(it.ingredient.name, it.offer) } +
            season.map { Suggestions.seasonReason(it.ingredient.name, it.level) })
            .distinct().take(Suggestions.MAX_REASONS)
}

/** Produce in season that is also on offer, for families whose library is still small. */
data class IngredientSuggestion(val entry: SeasonCalendar.Entry, val offers: List<Offer>) {
    val reason: String get() = Suggestions.offerReason(entry.name, offers.first())
}

/**
 * Deterministic suggestions (docs/angebote-und-vorschlaege.md "Wie Vorschläge entstehen").
 * Score per recipe = share of main ingredients on offer + share of produce in season (stored half).
 * Pure function of library, offers and date, so the same input always gives the same cards.
 */
object Suggestions {

    const val MAX_RECIPES = 5
    const val MAX_INGREDIENTS = 5
    const val MAX_REASONS = 3

    /** Nothing cooked in the last three weeks is suggested again. */
    const val RECENT_DAYS = 21

    /** Offers valid on any day of the coming week count ("ab Mo." shows on the card). */
    private const val WINDOW_DAYS = 6

    fun recipes(
        recipes: List<Recipe>,
        offers: List<Offer>,
        today: LocalDate,
        planned: Set<String> = emptySet(),
        rejected: Set<String> = emptySet(),
    ): List<RecipeSuggestion> {
        val current = currentOffers(offers, today)
        return recipes.asSequence()
            .filter { !it.deleted && it.status == RecipeStatus.CONFIRMED && it.id !in planned }
            .filter { r -> r.cookedOn?.let { it.daysUntil(today) >= RECENT_DAYS } ?: true }
            .map { score(it, current, today, rejected) }
            .filter { it.score > 0.0 }
            .sortedWith(
                compareByDescending<RecipeSuggestion> { it.score }
                    .thenBy { it.recipe.cookedOn ?: LocalDate(1, 1, 1) } // longer ago first, never cooked first
                    .thenBy { it.recipe.title.lowercase() },
            )
            .take(MAX_RECIPES)
            .toList()
    }

    internal fun score(recipe: Recipe, offers: List<Offer>, today: LocalDate, rejected: Set<String>): RecipeSuggestion {
        val main = recipe.ingredients.filter { it.name.isNotBlank() && it.key !in ShoppingList.PANTRY }
            .distinctBy { it.key }
        val offerHits = main.mapNotNull { i ->
            OfferMatch.offersFor(i.key, offers, rejected).firstOrNull()?.let { OfferHit(i, it) }
        }
        val produce = main.filter { SeasonCalendar.entryFor(it.key) != null }
        val month = today.month.ordinal + 1
        val seasonHits = produce.mapNotNull { i -> SeasonCalendar.level(i.key, month)?.let { SeasonHit(i, it) } }
        val offerShare = if (main.isEmpty()) 0.0 else offerHits.size.toDouble() / main.size
        val seasonShare = if (produce.isEmpty()) 0.0 else seasonHits.sumOf { it.level.weight } / produce.size
        return RecipeSuggestion(recipe, offerShare + seasonShare, offerHits, seasonHits)
    }

    /** In-season produce with at least one matching offer, biggest saving first. */
    fun ingredients(
        offers: List<Offer>,
        today: LocalDate,
        rejected: Set<String> = emptySet(),
    ): List<IngredientSuggestion> {
        val current = currentOffers(offers, today)
        return SeasonCalendar.inSeason(today.month.ordinal + 1)
            .mapNotNull { e ->
                val matching = OfferMatch.offersFor(e.key, current, rejected)
                if (matching.isEmpty()) null else IngredientSuggestion(e, matching)
            }
            .sortedByDescending { it.offers.first().savingPercent ?: -1 }
            .take(MAX_INGREDIENTS)
    }

    private fun currentOffers(offers: List<Offer>, today: LocalDate) =
        offers.filter { it.validBetween(today, today.plus(DatePeriod(days = WINDOW_DAYS))) }

    fun offerReason(name: String, offer: Offer): String {
        val saving = offer.savingPercent?.let { " −$it %" } ?: " im Angebot"
        return "$name bei ${offer.chain.label}$saving"
    }

    fun seasonReason(name: String, level: SeasonLevel): String = when (level) {
        SeasonLevel.SEASON -> "$name hat Saison"
        SeasonLevel.STORED -> "$name aus heimischem Lager"
    }
}
