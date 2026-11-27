package io.github.respawnedguy.familyapp.domain.recipe

import io.github.respawnedguy.familyapp.domain.Stamp
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class MealPlanTest {
    private val fri = LocalDate(2026, 11, 27)
    private val sat = LocalDate(2026, 11, 28)
    private val sun = LocalDate(2026, 11, 29)
    private var n = 0

    private fun meal(day: LocalDate?, recipe: String = "r", done: Boolean = false, deleted: Boolean = false) = PlannedMeal(
        id = "m${n++}", recipeId = recipe, day = day, servings = 4, plannedBy = "me", createdMs = n.toLong(),
        done = done, deleted = deleted, stamp = Stamp(n.toLong(), 0, "d"),
    )

    @Test fun nextFreeDaySkipsPlannedDays() {
        assertEquals(fri, MealPlan.nextFreeDay(emptyList(), fri))
        assertEquals(sun, MealPlan.nextFreeDay(listOf(meal(fri), meal(sat)), fri))
        assertEquals(fri, MealPlan.nextFreeDay(listOf(meal(fri, deleted = true), meal(fri, done = true)), fri))
        val week = MealPlan.choosableDays(fri)
        assertEquals(fri, MealPlan.nextFreeDay(week.map { meal(it) }, fri), "volle Woche: heute")
        assertEquals(MealPlan.DAYS_AHEAD + 1, week.size)
    }

    @Test fun todayAndUpcoming() {
        val yesterday = meal(LocalDate(2026, 11, 26), "old")
        val noDay = meal(null, "later")
        val today = meal(fri, "a")
        val tomorrow = meal(sat, "b")
        val meals = listOf(noDay, tomorrow, yesterday, today, meal(fri, "x", deleted = true))
        assertEquals(listOf("a"), MealPlan.today(meals, fri).map { it.recipeId })
        assertEquals(listOf("a", "b", "later"), MealPlan.upcoming(meals, fri).map { it.recipeId })
        assertEquals(setOf("a", "b", "later"), MealPlan.plannedRecipeIds(meals, fri))
    }
}
