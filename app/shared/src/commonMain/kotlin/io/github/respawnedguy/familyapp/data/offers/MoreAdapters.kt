package io.github.respawnedguy.familyapp.data.offers

import io.github.respawnedguy.familyapp.domain.offers.Chain
import io.github.respawnedguy.familyapp.domain.offers.Offer
import io.github.respawnedguy.familyapp.domain.offers.Postcodes
import kotlin.math.roundToInt

/**
 * The adapters of stage 2 (docs/angebote-maerkte.md): Penny, Norma, Netto, denn's. Same rules as the first
 * four: every request through [MarketFetch.text], which stops the market at the first refusal.
 */
internal class MoreAdapters(private val fetch: MarketFetch) {

    /**
     * Penny: the store list, the chosen store (or the nearest to [point]), this week's food categories
     * and their offers for the store's selling region. The five nearest stores come along for choosing.
     */
    fun penny(point: Postcodes.Point?, chosenId: String?): MarketResult {
        val chain = Chain.PENNY
        val stores = PennyParsers.stores(fetch.text(chain, MarketUrls.PENNY_STORES, MarketHttp.Kind.STORE_LIST))
        val byDistance = if (point == null) emptyList() else stores.sortedBy { point.distanceKm(it.lat, it.lon) }
        val store = stores.firstOrNull { it.id == chosenId } ?: byDistance.firstOrNull()
            ?: return MarketResult.Failed(chain, MarketFetch.NO_STORE)
        val nearby = byDistance.take(NEARBY).map { s ->
            NearbyStore(s.id, s.name, ((point?.distanceKm(s.lat, s.lon) ?: 0.0) * TENTHS).roundToInt() / TENTHS)
        }
        val week = PennyParsers.week(fetch.text(chain, MarketUrls.PENNY_OFFERS, MarketHttp.Kind.HTML))
        return if (week == null) MarketResult.Failed(chain, MarketFetch.NOTHING_FOUND) else {
            val offers = week.categories.flatMap { category ->
                val url = MarketUrls.pennyCategory(week.week, category, store.region)
                PennyParsers.offers(fetch.text(chain, url, MarketHttp.Kind.JSON))
            }.distinctBy { it.title }
            MarketResult.Ok(chain, offers, MarketParsers.Store(store.id, store.name), nearby)
        }
    }

    /** Norma: fresh produce page and the day pages of the overview, each with its first day. */
    fun norma(): List<Offer> {
        val overview = fetch.text(Chain.NORMA, MarketUrls.NORMA_OVERVIEW, MarketHttp.Kind.HTML)
        val produce = NormaParsers.offers(fetch.text(Chain.NORMA, MarketUrls.NORMA_PRODUCE, MarketHttp.Kind.HTML))
        val days = NormaParsers.dayPages(overview).flatMap { path ->
            val page = fetch.text(Chain.NORMA, MarketUrls.normaPage(path), MarketHttp.Kind.HTML)
            NormaParsers.offers(page, NormaParsers.dayOf(path))
        }
        return (produce + days).distinctBy { it.title }
    }

    fun netto(): List<Offer> = MarketUrls.NETTO_PAGES
        .flatMap { NettoParsers.offers(fetch.text(Chain.NETTO, it, MarketHttp.Kind.HTML)) }
        .distinctBy { it.title }

    fun denns(): List<Offer> = DennsParsers.offers(fetch.text(Chain.DENNS, MarketUrls.DENNS, MarketHttp.Kind.JSON))

    private companion object {
        const val NEARBY = 5
        const val TENTHS = 10.0
    }
}
