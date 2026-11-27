package io.github.respawnedguy.familyapp.data.offers

import io.github.respawnedguy.familyapp.data.offers.MarketJson.arr
import io.github.respawnedguy.familyapp.data.offers.MarketJson.int
import io.github.respawnedguy.familyapp.data.offers.MarketJson.num
import io.github.respawnedguy.familyapp.data.offers.MarketJson.obj
import io.github.respawnedguy.familyapp.data.offers.MarketJson.str
import io.github.respawnedguy.familyapp.data.offers.MarketValues.cents
import io.github.respawnedguy.familyapp.data.offers.MarketValues.date
import io.github.respawnedguy.familyapp.data.offers.MarketValues.percent
import io.github.respawnedguy.familyapp.data.offers.MarketValues.title
import io.github.respawnedguy.familyapp.domain.offers.Chain
import io.github.respawnedguy.familyapp.domain.offers.Offer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Aldi Süd (JSON of the website's product search) and Aldi Nord (__NEXT_DATA__ of /angebote.html). */
internal object AldiParsers {

    fun aldiSued(text: String): MarketParsers.Page {
        val root = MarketJson.parse(text).obj() ?: return MarketParsers.Page(emptyList(), 0)
        val offers = root["data"].arr().orEmpty().mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            val name = o.str("name") ?: return@mapNotNull null
            val price = o["price"].obj()
            Offer(
                chain = Chain.ALDI_SUED,
                title = title(name),
                priceCents = price?.int("amountRelevant") ?: price?.int("amount"),
                oldPriceCents = price?.str("wasPriceDisplay")?.let(MarketValues::euroText),
                savingPercent = percent(price?.str("savingsDisplay")),
            )
        }
        val total = root["meta"].obj()?.get("pagination").obj()?.int("totalCount") ?: offers.size
        return MarketParsers.Page(offers, total)
    }

    private const val NEXT_DATA = "id=\"__NEXT_DATA__\""
    private const val MAX_DEPTH = 40
    private const val EMBEDDED_JSON_MIN = 2

    fun aldiNord(html: String): List<Offer> {
        val start = html.indexOf(NEXT_DATA)
        val bodyStart = if (start < 0) -1 else html.indexOf('>', start) + 1
        val end = if (bodyStart <= 0) -1 else html.indexOf("</script>", bodyStart)
        if (end < 0) return emptyList()
        val found = mutableListOf<JsonObject>()
        collectProducts(MarketJson.parse(html.substring(bodyStart, end)), found, depth = 0)
        return found.mapNotNull(::aldiNordOffer).distinctBy { it.title + it.validFrom }
    }

    /** Walks the tree, also into strings holding JSON (apiData is one), for objects with promotionPrices. */
    private fun collectProducts(e: JsonElement?, out: MutableList<JsonObject>, depth: Int) {
        if (e == null || depth > MAX_DEPTH) return
        when (e) {
            is JsonObject -> if ("promotionPrices" in e && "name" in e) out += e
            else e.values.forEach { collectProducts(it, out, depth + 1) }
            is JsonArray -> e.forEach { collectProducts(it, out, depth + 1) }
            is JsonPrimitive -> if (e.isString && embedsJson(e.content)) {
                collectProducts(MarketJson.parse(e.content), out, depth + 1)
            }
        }
    }

    private fun embedsJson(s: String) = s.length > EMBEDDED_JSON_MIN && (s.first() == '{' || s.first() == '[')

    private fun aldiNordOffer(p: JsonObject): Offer? {
        val name = p.str("name") ?: return null
        val promo = p["promotionPrices"].arr()?.firstOrNull().obj() ?: p["currentPrice"].obj()
        val strike = promo?.get("strikePrice").obj()?.num("strikePriceValue")
        return Offer(
            chain = Chain.ALDI_NORD,
            title = title(p.str("brandName"), name, p.str("salesUnit")),
            priceCents = cents(promo?.num("priceValue")),
            oldPriceCents = cents(strike),
            savingPercent = percent(promo?.get("priceTagLabels").obj()?.str("promoText1")),
            validFrom = date(promo?.str("validFromLocalDate")),
            validUntil = date(promo?.str("validUntilLocalDate")),
        )
    }
}
