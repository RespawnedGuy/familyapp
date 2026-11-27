package io.github.respawnedguy.familyapp.domain.offers

import io.github.respawnedguy.familyapp.domain.recipe.IngredientKey
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Titles written after the pattern of real offers from 27.11.2026, not copied from a market. */
class OfferMatchTest {
    private fun offer(title: String, saving: Int? = null, price: Int? = null, chain: Chain = Chain.ALDI_SUED) =
        Offer(chain, title, priceCents = price, savingPercent = saving)

    private fun matches(title: String, ingredient: String) = OfferMatch.matches(offer(title), IngredientKey.of(ingredient))

    @Test fun freshIngredientsMatchProductTitles() {
        assertTrue(matches("Deutsches Rinderhackfleisch gemischt 500 g", "Hackfleisch"))
        assertTrue(matches("Deutsches Rinderhackfleisch gemischt 500 g", "Rinderhack"))
        assertTrue(matches("Eisbergsalat, Stück", "Eisbergsalat"))
        assertTrue(matches("Bio Möhren 1 kg", "Karotten"))
        assertTrue(matches("Speisekartoffeln festkochend 2,5-kg-Netz", "Kartoffeln"))
        assertTrue(matches("Hokkaido Kürbis Klasse I", "Kürbis"))
        assertTrue(matches("Langkorn-Reis 1 kg", "Reis"), "„Reis“ endet auf „eis“, ist aber kein Eis")
        assertTrue(matches("Frische Eier Freilandhaltung 10 Stück", "Eier"))
    }

    @Test fun processedProductsAreNotTheFreshIngredient() {
        assertFalse(matches("Erdbeer-Joghurt 4 x 125 g", "Erdbeeren"))
        assertFalse(matches("Tomatenmark 200 g", "Tomaten"))
        assertFalse(matches("Tomaten-Sauce Basilikum", "Tomaten"))
        assertFalse(matches("Zucchini Pflanzen im Topf", "Zucchini"))
        assertFalse(matches("Apfelsaft naturtrüb 1 l", "Äpfel"))
        assertFalse(matches("Kartoffelchips Paprika", "Kartoffeln"))
        assertFalse(matches("Vanilleeis 1 l", "Vanille"))
    }

    @Test fun otherIngredientsDoNotMatch() {
        assertFalse(matches("Eisbergsalat, Stück", "Gurke"))
        assertFalse(matches("Hähnchenbrust 400 g", "Hackfleisch"))
        assertFalse(matches("Kaki 1 kg", "Kartoffeln"))
    }

    @Test fun passtNichtIsRemembered() {
        val key = IngredientKey.of("Lachs")
        val o = offer("Seelachs-Filets 400 g") // Seelachs is pollock, the compound rule takes it for salmon
        assertTrue(OfferMatch.matches(o, key))
        val rejected = setOf(OfferMatch.rejectionKey("Seelachs Filets, 400 g", key))
        assertFalse(OfferMatch.matches(o, key, rejected))
    }

    @Test fun biggestSavingFirstAndUnpricedLast() {
        val offers = listOf(
            offer("Bio Möhren 1 kg", chain = Chain.LIDL),
            offer("Möhren 2 kg", saving = 20, price = 199),
            offer("Möhren 1 kg", saving = 35, price = 99, chain = Chain.KAUFLAND),
        )
        assertEquals(listOf(Chain.KAUFLAND, Chain.ALDI_SUED, Chain.LIDL), OfferMatch.offersFor("möhr", offers).map { it.chain })
    }

    @Test fun validity() {
        val mon = LocalDate(2026, 11, 30)
        val o = Offer(Chain.ALDI_NORD, "Möhren", validFrom = mon, validUntil = LocalDate(2026, 12, 5))
        assertTrue(o.validBetween(LocalDate(2026, 11, 27), LocalDate(2026, 12, 3)))
        assertFalse(o.validBetween(LocalDate(2026, 11, 20), LocalDate(2026, 11, 29)))
        assertFalse(o.validBetween(LocalDate(2026, 12, 6), LocalDate(2026, 12, 12)))
        assertTrue(Offer(Chain.LIDL, "Möhren").validBetween(mon, mon))
    }
}
