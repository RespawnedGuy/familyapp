package io.github.respawnedguy.familyapp.data.offers

import io.github.respawnedguy.familyapp.data.offers.MarketJson.arr
import io.github.respawnedguy.familyapp.data.offers.MarketJson.int
import io.github.respawnedguy.familyapp.data.offers.MarketJson.num
import io.github.respawnedguy.familyapp.data.offers.MarketJson.obj
import io.github.respawnedguy.familyapp.data.offers.MarketJson.str
import io.github.respawnedguy.familyapp.data.offers.MarketValues.cents
import io.github.respawnedguy.familyapp.data.offers.MarketValues.date
import io.github.respawnedguy.familyapp.data.offers.MarketValues.title
import io.github.respawnedguy.familyapp.domain.offers.Chain
import io.github.respawnedguy.familyapp.domain.offers.Offer
import kotlinx.serialization.json.JsonObject

/** Kaufland: offers embedded in the overview page (window.SSR … OfferTemplate), store answers. */
internal object KauflandParsers {

    /** Food categories of Kaufland's overview ("02_Obst__Gemuese__Pflanzen" …); the rest is left out. */
    private val FOOD = Regex("""^0[1-6][a-z]?_""")
    private const val TEMPLATE = "\"component\":\"OfferTemplate\""

    fun offers(html: String): List<MarketParsers.KauflandOffer> {
        val marker = html.indexOf(TEMPLATE)
        val start = if (marker < 0) -1 else html.lastIndexOf('{', marker)
        val end = if (marker < 0) -1 else html.indexOf("</script>", marker)
        if (start < 0 || end < 0) return emptyList()
        val body = html.substring(start, end).trim().removeSuffix(";")
        val data = MarketJson.parse(body).obj()?.get("props").obj()?.get("offerData").obj()
        return data?.get("cycles").arr().orEmpty()
            .flatMap { c -> c.obj()?.get("categories").arr().orEmpty() }
            .mapNotNull { it.obj() }
            .filter { cat -> cat.str("name")?.let { FOOD.containsMatchIn(it) } == true }
            .flatMap { cat -> cat["offers"].arr().orEmpty().mapNotNull { offer(it.obj()) } }
            .distinctBy { it.klNr ?: it.offer.title }
    }

    private fun offer(o: JsonObject?): MarketParsers.KauflandOffer? {
        val t = o?.str("title") ?: return null
        val offer = Offer(
            chain = Chain.KAUFLAND,
            title = title(t, o.str("unit")),
            priceCents = cents(o.num("price")),
            oldPriceCents = o.str("formattedOldPrice")?.toDoubleOrNull()?.let(::cents),
            savingPercent = o.int("discount")?.takeIf { it > 0 },
            validFrom = date(o.str("salesFrom") ?: o.str("dateFrom")),
            validUntil = date(o.str("salesTo") ?: o.str("dateTo")),
        )
        return MarketParsers.KauflandOffer(o.str("klNr"), offer)
    }

    fun storeArticles(text: String): Set<String>? =
        MarketJson.parse(text).arr()?.mapNotNull { it.obj()?.str("klNr") }?.toSet()

    fun store(text: String): MarketParsers.Store? {
        val o = MarketJson.parse(text).obj()
        val id = o?.str("n") ?: return null
        return MarketParsers.Store(id, o.str("cn") ?: id)
    }
}
