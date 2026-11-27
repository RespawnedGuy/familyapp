package io.github.respawnedguy.familyapp.data.offers

import io.github.respawnedguy.familyapp.data.offers.MarketJson.arr
import io.github.respawnedguy.familyapp.data.offers.MarketJson.num
import io.github.respawnedguy.familyapp.data.offers.MarketJson.obj
import io.github.respawnedguy.familyapp.data.offers.MarketJson.str
import io.github.respawnedguy.familyapp.data.offers.MarketValues.percent
import io.github.respawnedguy.familyapp.data.offers.MarketValues.title
import io.github.respawnedguy.familyapp.domain.offers.Chain
import io.github.respawnedguy.familyapp.domain.offers.Offer
import kotlinx.serialization.json.JsonObject

/**
 * Penny (docs/angebote-maerkte.md): the store list the website loads (/.rest/market, every store with
 * coordinates and its selling region), the categories of this week on /angebote, and per category the
 * offers of one region (/.rest/offers/by-category/{week}/{category}?region=…). Read on 27.11.2026.
 */
internal object PennyParsers {

    /** One Penny store as the list names it. */
    data class Store(val id: String, val name: String, val lat: Double, val lon: Double, val region: String)

    /** This week ("2026-48") and the food categories of this week, in page order. */
    data class Week(val week: String, val categories: List<String>)

    /** Store ids are digits; regions look like "15A-08-26" (threat model AN-9). */
    val STORE_ID = Regex("""^\d{3,8}$""")
    val REGION = Regex("""^[0-9A-Z-]{1,20}$""")
    val CATEGORY = Regex("""^[a-z0-9-]{1,60}$""")
    private val WEEK = Regex("""^\d{4}-\d{2}$""")
    private val SECTION = Regex("""<section\b[^>]*>""")

    /** Food categories that are not cooking: drinks, household, plants. */
    private val NOT_COOKING = listOf("drogerie", "getraenke", "pflanzen")

    private const val MAX_CATEGORIES = 20

    fun stores(text: String): List<Store> = MarketJson.parse(text).arr().orEmpty().mapNotNull { e ->
        val o = e.obj() ?: return@mapNotNull null
        val id = o.str("wwIdent")?.takeIf { STORE_ID.matches(it) } ?: return@mapNotNull null
        val region = o.str("sellingRegion")?.takeIf { REGION.matches(it) } ?: return@mapNotNull null
        val lat = o.coordinate("latitude") ?: return@mapNotNull null
        val lon = o.coordinate("longitude") ?: return@mapNotNull null
        val name = title(o.str("marketName") ?: "Penny", o.str("streetWithHouseNumber")?.let { ", $it" }, o.str("city"))
        Store(id, name.replace(" ,", ","), lat, lon, region)
    }

    private fun JsonObject.coordinate(key: String): Double? = num(key) ?: str(key)?.toDoubleOrNull()

    fun week(html: String): Week? {
        val week = MarketHtml.attribute(html, "data-current-week")?.takeIf { WEEK.matches(it) } ?: return null
        return Week(week, categories(html))
    }

    private fun categories(html: String): List<String> {
        val start = html.indexOf("data-week=\"current\"")
        val end = html.indexOf("data-week=\"next\"").let { if (it < 0) html.length else it }
        val current = if (start < 0 || end <= start) "" else html.substring(start, end)
        return SECTION.findAll(current).map { it.value }
            .filter { MarketHtml.attribute(it, "data-category-type") == "food" }
            .mapNotNull { MarketHtml.attribute(it, "data-category-name")?.takeIf { n -> CATEGORY.matches(n) } }
            .filter { n -> NOT_COOKING.none { n.startsWith(it) } }
            .distinct().take(MAX_CATEGORIES).toList()
    }

    fun offers(text: String): List<Offer> =
        MarketJson.parse(text).obj()?.get("offerTiles").arr().orEmpty().mapNotNull { e ->
            val o = e.obj()?.takeIf { it.str("type") == "food" } ?: return@mapNotNull null
            val name = o.str("title")?.trimEnd('*', ' ') ?: return@mapNotNull null
            Offer(
                chain = Chain.PENNY,
                title = title(name, o.str("quantity")),
                priceCents = o.str("price")?.let(::dotCents),
                oldPriceCents = (o.str("crossOutPrice") ?: o.str("listPrice"))?.let(::dotCents),
                savingPercent = percent(o.str("advantage")),
            )
        }

    /** "1.79" -> 179; anything else (footnote marks, empty) gives null. */
    fun dotCents(text: String): Int? = Regex("""^(\d{1,4})\.(\d{2})""").find(text.trim())
        ?.let { it.groupValues[1].toInt() * HUNDRED + it.groupValues[2].toInt() }

    private const val HUNDRED = 100
}
