package io.github.respawnedguy.familyapp.domain.recipe

import io.github.respawnedguy.familyapp.domain.Stamp
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable

/**
 * A dish planned for a day ("Wann soll es das geben?", docs/angebote-und-vorschlaege.md
 * "Essensplan"). Synced like recipes as an extra field older app versions skip; deleting is a newer
 * version with [deleted] set. [day] null means "Ohne Tag".
 */
@Serializable
data class PlannedMeal(
    val id: String,
    val recipeId: String,
    val day: LocalDate? = null,
    val servings: Int,
    val plannedBy: String,
    val createdMs: Long,
    val done: Boolean = false,
    val changedBy: String? = null,
    val deleted: Boolean = false,
    val stamp: Stamp,
)

/** Read-only views of the meal plan for "Heute" and "Demnächst"; pure functions. */
object MealPlan {

    /** How many days ahead "Wann?" offers and looks for a free day. */
    const val DAYS_AHEAD = 6

    private fun live(meals: List<PlannedMeal>) = meals.filter { !it.deleted && !it.done }

    /** Dishes for [today], in the order they were planned. */
    fun today(meals: List<PlannedMeal>, today: LocalDate): List<PlannedMeal> =
        live(meals).filter { it.day == today }.sortedBy { it.createdMs }

    /** Dishes from [today] on (and those without a day at the end). */
    fun upcoming(meals: List<PlannedMeal>, today: LocalDate): List<PlannedMeal> =
        live(meals).filter { it.day == null || it.day >= today }
            .sortedWith(compareBy<PlannedMeal, LocalDate?>(nullsLast()) { it.day }.thenBy { it.createdMs })

    /** Recipes already in the plan from [today] on; not suggested again. */
    fun plannedRecipeIds(meals: List<PlannedMeal>, today: LocalDate): Set<String> =
        upcoming(meals, today).map { it.recipeId }.toSet()

    /** Default for "Wann?": the first day from [today] without a planned dish, else today. */
    fun nextFreeDay(meals: List<PlannedMeal>, today: LocalDate): LocalDate {
        val taken = live(meals).mapNotNull { it.day }.toSet()
        return (0..DAYS_AHEAD).map { today.plus(DatePeriod(days = it)) }.firstOrNull { it !in taken } ?: today
    }

    /** The days "Wann?" offers as chips: today and the next [DAYS_AHEAD] days. */
    fun choosableDays(today: LocalDate): List<LocalDate> = (0..DAYS_AHEAD).map { today.plus(DatePeriod(days = it)) }
}
