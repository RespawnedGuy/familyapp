package io.github.respawnedguy.familyapp.domain.offers

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Familie › Einkauf lists every regional offer (Nils, 27.11.2026). Titles invented after real leaflets. */
class OfferListTest {
    private val friday = LocalDate(2026, 11, 27)
    private val saturday = LocalDate(2026, 11, 28)
    private val monday = LocalDate(2026, 11, 30)

    private fun offer(
        title: String,
        chain: Chain = Chain.ALDI_SUED,
        price: Int? = 199,
        saving: Int? = null,
        from: LocalDate? = null,
        until: LocalDate? = null,
        old: Int? = null,
    ) = Offer(chain, title, priceCents = price, oldPriceCents = old, savingPercent = saving, validFrom = from, validUntil = until)

    @Test fun groupsByMarketInFixedOrderAndDropsExpiredOffers() {
        val list = OfferList.byMarket(
            listOf(
                offer("Grünkohl 500 g", Chain.LIDL, price = null),
                offer("Rinderhack 500 g", Chain.KAUFLAND),
                offer("Bananen 1 kg", Chain.ALDI_SUED, until = LocalDate(2026, 11, 26)),
                offer("Eisbergsalat", Chain.ALDI_SUED),
            ),
            friday,
        )
        assertEquals(listOf(Chain.ALDI_SUED, Chain.KAUFLAND, Chain.LIDL), list.map { it.chain })
        assertEquals(listOf("Eisbergsalat"), list.first().offers.map { it.title }, "yesterday's offer is gone")
    }

    @Test fun validTodayComesFirstThenTheBiggestSaving() {
        val offers = OfferList.byMarket(
            listOf(
                offer("Äpfel", saving = 10),
                offer("Kürbis", saving = 40, from = monday),
                offer("Möhren", saving = 30),
                offer("Birnen"),
            ),
            friday,
        ).single().offers
        assertEquals(listOf("Möhren", "Äpfel", "Birnen", "Kürbis"), offers.map { it.title })
    }

    @Test fun theSameProductTwiceIsListedOnce() {
        val offers = OfferList.byMarket(
            listOf(offer("Butter 250 g", until = saturday), offer("butter 250 g", from = monday), offer("Butter 250 g", price = 149)),
            friday,
        ).single().offers
        assertEquals(2, offers.size, "same title and price once, another price stays")
    }

    @Test fun searchKeepsOffersWithEveryWord() {
        val all = listOf(offer("Bio Möhren 1 kg"), offer("Möhren 2 kg"), offer("Bio Äpfel"))
        assertEquals(listOf("Bio Möhren 1 kg"), OfferList.byMarket(all, friday, "  möhren  BIO ").single().offers.map { it.title })
        assertTrue(OfferList.byMarket(all, friday, "Hackfleisch").isEmpty())
        assertEquals(3, OfferList.byMarket(all, friday, " ").single().offers.size)
    }

    @Test fun priceAndDetailsAreReadable() {
        assertEquals("1,69 €", OfferList.price(169))
        assertEquals("0,05 €", OfferList.price(5))
        assertEquals("12,00 €", OfferList.price(1200))
        assertNull(OfferList.price(null))
        assertEquals(
            "statt 2,29 € · −26 % · bis Sa 28.11.",
            OfferList.details(offer("Hack", old = 229, saving = 26, until = saturday), friday),
        )
        assertEquals("ab Mo 30.11.", OfferList.details(offer("Kürbis", from = monday, until = LocalDate(2026, 12, 5)), friday))
        assertEquals("", OfferList.details(offer("Lidl-Schlagwort", saving = 0), friday))
    }

    @Test fun anOfferOnTheListIsRecognised() {
        assertTrue(OfferList.onList(offer(" Bio Möhren 1 kg "), setOf("bio möhren 1 kg")))
        assertFalse(OfferList.onList(offer("Bio Möhren 1 kg"), setOf("möhren")))
    }
}
