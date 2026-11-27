package io.github.respawnedguy.familyapp.domain.offers

import io.github.respawnedguy.familyapp.domain.DateFormat
import kotlinx.datetime.LocalDate

/** The offers of one market as the tab "Einkauf" lists them. */
data class MarketOffers(val chain: Chain, val offers: List<Offer>)

/**
 * All regional offers under Familie › Einkauf (Nils, 27.11.2026): every offer of the chosen markets
 * that has not run out, grouped by market. Pure: the offers come from the cache on this phone, the
 * texts are built here so the screen decides nothing.
 */
object OfferList {

    /**
     * Offers still valid today or later, grouped by market in the order of [Chain]. Within a market:
     * valid today first, then the biggest saving, then by title. The same product twice (two weeks
     * in one leaflet) is listed once. [query] keeps offers whose title holds every word typed.
     */
    fun byMarket(offers: List<Offer>, today: LocalDate, query: String = ""): List<MarketOffers> {
        val words = query.lowercase().split(SPACE).filter { it.isNotBlank() }
        return offers
            .asSequence()
            .filter { it.validUntil == null || it.validUntil >= today }
            .filter { o -> words.all { o.title.lowercase().contains(it) } }
            .distinctBy { Triple(it.chain, it.title.lowercase(), it.priceCents) }
            .groupBy { it.chain }
            .toList()
            .sortedBy { (chain, _) -> chain.ordinal }
            .map { (chain, list) ->
                MarketOffers(
                    chain,
                    list.sortedWith(
                        compareBy<Offer> { !it.validBetween(today, today) }
                            .thenByDescending { it.savingPercent ?: 0 }
                            .thenBy { it.title.lowercase() },
                    ),
                )
            }
    }

    /** "1,69 €"; null without a price (Lidl food offers only exist as leaflet keywords). */
    fun price(cents: Int?): String? =
        cents?.let { "${it / CENTS},${(it % CENTS).toString().padStart(2, '0')} €" }

    /**
     * The second line of an offer: "statt 2,29 € · −26 % · bis Sa 29.11." or "ab Mo 01.12.".
     * Offers that start later say so, so nobody drives to the market a day too early.
     */
    fun details(offer: Offer, today: LocalDate): String = buildList {
        price(offer.oldPriceCents)?.let { add("statt $it") }
        offer.savingPercent?.takeIf { it > 0 }?.let { add("−$it %") }
        val from = offer.validFrom
        val until = offer.validUntil
        when {
            from != null && from > today -> add("ab ${DateFormat.short(from)}")
            until != null -> add("bis ${DateFormat.short(until)}")
        }
    }.joinToString(" · ")

    /** True if this offer already stands, not ticked, on the shopping list ([openNames] lower case). */
    fun onList(offer: Offer, openNames: Set<String>): Boolean = offer.title.trim().lowercase() in openNames

    private val SPACE = Regex("""\s+""")
    private const val CENTS = 100
}
