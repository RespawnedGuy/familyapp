package io.github.respawnedguy.familyapp.domain.offers

import io.github.respawnedguy.familyapp.domain.Stamp
import io.github.respawnedguy.familyapp.domain.recipe.Ingredient
import io.github.respawnedguy.familyapp.domain.recipe.Recipe
import io.github.respawnedguy.familyapp.domain.recipe.RecipeStatus
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SuggestionsTest {
    private val friday = LocalDate(2026, 11, 27)
    private var n = 0

    private fun recipe(
        title: String,
        vararg ingredients: String,
        status: RecipeStatus = RecipeStatus.CONFIRMED,
        cookedOn: LocalDate? = null,
    ) = Recipe(
        id = "r${n++}", title = title, servings = 4.0,
        ingredients = ingredients.map { Ingredient(text = it, name = it) },
        status = status, createdMs = n.toLong(), createdBy = "me", cookedOn = cookedOn, stamp = Stamp(n.toLong(), 0, "d"),
    )

    private val gruenkohl = recipe("Grünkohl mit Kartoffeln", "Grünkohl", "Kartoffeln", "Zwiebel", "Salz", "Öl")
    private val bolognese = recipe("Bolognese", "Hackfleisch", "Tomaten", "Nudeln", "Salz")
    private val salat = recipe("Sommersalat", "Gurke", "Tomaten", "Radieschen")

    private val offers = listOf(
        Offer(Chain.ALDI_SUED, "Deutsches Rinderhackfleisch 500 g", priceCents = 379, savingPercent = 24),
        Offer(Chain.KAUFLAND, "Grünkohl frisch 500 g", priceCents = 149, savingPercent = 20),
    )

    @Test fun seasonAndOffersRankRecipesWithReasons() {
        val result = Suggestions.recipes(listOf(salat, bolognese, gruenkohl), offers, friday)
        // Grünkohl: 1 of 3 main ingredients on offer; Grünkohl in season, potatoes and onions stored -> 1/3 + (1 + 0.5 + 0.5)/3
        assertEquals(listOf("Grünkohl mit Kartoffeln", "Bolognese"), result.map { it.recipe.title })
        val top = result.first()
        assertEquals(1.0 / 3 + 2.0 / 3, top.score, 1e-9)
        assertEquals(
            listOf("Grünkohl bei Kaufland −20 %", "Grünkohl hat Saison", "Kartoffeln aus heimischem Lager"),
            top.reasons,
        )
        assertEquals("Hackfleisch bei Aldi Süd −24 %", result[1].reasons.first())
    }

    @Test fun recentlyCookedPlannedAndUnconfirmedRecipesAreLeftOut() {
        val cooked = recipe("Grünkohl wie letzte Woche", "Grünkohl", cookedOn = friday.minusDays(7))
        val longAgo = recipe("Grünkohl wie im Januar", "Grünkohl", cookedOn = friday.minusDays(Suggestions.RECENT_DAYS))
        val suggested = recipe("Grünkohl-Import", "Grünkohl", status = RecipeStatus.SUGGESTED)
        val deleted = recipe("Weg", "Grünkohl").copy(deleted = true)
        val result = Suggestions.recipes(listOf(cooked, longAgo, suggested, deleted, gruenkohl), offers, friday, planned = setOf(gruenkohl.id))
        assertEquals(listOf("Grünkohl wie im Januar"), result.map { it.recipe.title })
    }

    @Test fun atMostFiveAndNothingWithoutAnyHit() {
        val many = (1..8).map { recipe("Grünkohl $it", "Grünkohl") } + recipe("Nur Salz", "Salz")
        val result = Suggestions.recipes(many, emptyList(), friday)
        assertEquals(Suggestions.MAX_RECIPES, result.size)
        assertTrue(result.none { it.recipe.title == "Nur Salz" })
    }

    @Test fun lidlOffersWithoutPriceSayImAngebot() {
        val lidl = listOf(Offer(Chain.LIDL, "Spitzkohl"))
        val r = recipe("Spitzkohl-Pfanne", "Spitzkohl", "Speck")
        assertEquals("Spitzkohl bei Lidl im Angebot", Suggestions.recipes(listOf(r), lidl, friday).single().reasons.first())
    }

    @Test fun expiredOffersDoNotCount() {
        val old = listOf(Offer(Chain.ALDI_NORD, "Hackfleisch", savingPercent = 30, validUntil = friday.minusDays(1)))
        assertTrue(Suggestions.recipes(listOf(bolognese), old, friday).isEmpty())
    }

    @Test fun ingredientSuggestionsAreSeasonProduceOnOffer() {
        val list = offers + Offer(Chain.LIDL, "Rosenkohl") + Offer(Chain.ALDI_SUED, "Erdbeeren 500 g", savingPercent = 50)
        val result = Suggestions.ingredients(list, friday)
        assertEquals(listOf("Grünkohl", "Rosenkohl"), result.map { it.entry.name })
        assertEquals("Grünkohl bei Kaufland −20 %", result.first().reason)
    }

    private fun LocalDate.minusDays(days: Int) = LocalDate.fromEpochDays(toEpochDays() - days)
}
