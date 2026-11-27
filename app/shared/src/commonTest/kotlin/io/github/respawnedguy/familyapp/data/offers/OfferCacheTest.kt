package io.github.respawnedguy.familyapp.data.offers

import io.github.respawnedguy.familyapp.data.InMemoryTextStore
import io.github.respawnedguy.familyapp.domain.offers.Chain
import io.github.respawnedguy.familyapp.domain.offers.Offer
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When offers are read again, and that the local file survives anything (threat model AN-7). */
class OfferCacheTest {

    private val monday = LocalDate(2026, 11, 30)
    private val hour = 60L * 60 * 1000

    private fun snap(status: MarketStatus, on: LocalDate = monday, ms: Long = 0, store: String? = null) =
        MarketSnapshot(Chain.KAUFLAND, ms, on, status, storeId = store)

    @Test
    fun neverReadIsDue() = assertTrue(OfferCache.due(null, monday, 0))

    @Test
    fun freshOffersOfThisWeekAreNotDue() {
        assertFalse(OfferCache.due(snap(MarketStatus.OK), LocalDate(2026, 12, 2), 0)) // Wednesday, 2 days
    }

    @Test
    fun threeDaysOldOrLastWeekIsDue() {
        assertTrue(OfferCache.due(snap(MarketStatus.OK), LocalDate(2026, 12, 3), 0)) // Thursday, 3 days
        // Saturday to Monday: only 2 days, but a new week with new offers.
        assertTrue(OfferCache.due(snap(MarketStatus.OK, on = LocalDate(2026, 11, 28)), monday, 0))
        assertFalse(OfferCache.due(snap(MarketStatus.OK, on = LocalDate(2026, 11, 28)), LocalDate(2026, 11, 29), 0))
    }

    @Test
    fun anotherKauflandStoreIsDue() {
        assertTrue(OfferCache.due(snap(MarketStatus.OK, store = "DE1"), monday, 0, storeId = "DE2"))
        assertFalse(OfferCache.due(snap(MarketStatus.OK, store = "DE1"), monday, 0, storeId = "DE1"))
    }

    @Test
    fun failedIsTriedAgainAfterAnHour() {
        assertFalse(OfferCache.due(snap(MarketStatus.FAILED, ms = 0), monday, hour - 1))
        assertTrue(OfferCache.due(snap(MarketStatus.FAILED, ms = 0), monday, hour))
    }

    @Test
    fun an5_refusedStaysOffWhateverTheAge() {
        assertFalse(OfferCache.due(snap(MarketStatus.REFUSED, on = LocalDate(2026, 1, 1)), monday, Long.MAX_VALUE))
    }

    @Test
    fun onlyChosenMarketsThatWorkedCount() {
        val o = Offer(Chain.LIDL, "Spitzkohl")
        val cache = OfferCache()
            .with(MarketSnapshot(Chain.LIDL, 0, monday, MarketStatus.OK, listOf(o)))
            .with(MarketSnapshot(Chain.KAUFLAND, 0, monday, MarketStatus.FAILED, listOf(o.copy(chain = Chain.KAUFLAND))))
        assertEquals(listOf(o), cache.offers(listOf(Chain.LIDL, Chain.KAUFLAND)))
        assertEquals(emptyList(), cache.offers(listOf(Chain.ALDI_SUED)))
        assertEquals(null, cache.without(Chain.LIDL).of(Chain.LIDL))
    }

    @Test
    fun an7_storedCacheRoundTripsAndBrokenFilesStartEmpty() {
        val store = InMemoryTextStore()
        val cache = OfferCache().with(MarketSnapshot(Chain.LIDL, 5, monday, MarketStatus.OK, listOf(Offer(Chain.LIDL, "Kürbis"))))
        OfferCache.save(store, cache)
        assertEquals(cache, OfferCache.load(store))
        listOf("", "kaputt", "{\"markets\":[{\"chain\":\"NETTO\"}]}", "[]").forEach {
            assertEquals(OfferCache(), OfferCache.load(InMemoryTextStore(it)), it)
        }
    }
}
