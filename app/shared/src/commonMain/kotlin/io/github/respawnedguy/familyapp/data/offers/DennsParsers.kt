package io.github.respawnedguy.familyapp.data.offers

import io.github.respawnedguy.familyapp.data.offers.MarketJson.arr
import io.github.respawnedguy.familyapp.data.offers.MarketJson.obj
import io.github.respawnedguy.familyapp.data.offers.MarketJson.str
import io.github.respawnedguy.familyapp.data.offers.MarketValues.date
import io.github.respawnedguy.familyapp.data.offers.MarketValues.euroText
import io.github.respawnedguy.familyapp.data.offers.MarketValues.percent
import io.github.respawnedguy.familyapp.data.offers.MarketValues.title
import io.github.respawnedguy.familyapp.domain.offers.Chain
import io.github.respawnedguy.familyapp.domain.offers.Offer
import kotlinx.serialization.json.JsonPrimitive

/**
 * denn's BioMarkt and the BioMarkt-Verbund: the data file of biomarkt.de/angebote
 * (/page-data/angebote/page-data.json, result.data.noLimitOffers.nodes), valid in all taking part.
 */
internal object DennsParsers {

    fun offers(text: String): List<Offer> {
        val data = MarketJson.parse(text).obj()?.get("result").obj()?.get("data").obj()
        val nodes = data?.get("noLimitOffers").obj()?.get("nodes").arr().orEmpty()
        return nodes.mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            if ((o["hidden"] as? JsonPrimitive)?.content == "true" || o.str("land")?.let { it != "de" } == true) {
                return@mapNotNull null
            }
            val name = o.str("title") ?: return@mapNotNull null
            Offer(
                chain = Chain.DENNS,
                title = title(o.str("brand"), name, o.str("subtitle")),
                priceCents = o.str("price")?.let(::euroText),
                oldPriceCents = o.str("priceb")?.let(::euroText),
                savingPercent = percent(o.str("pricebanner")),
                validFrom = date(o.str("validfrom")),
                validUntil = date(o.str("validto")),
            )
        }.distinctBy { it.title + it.validFrom }
    }
}
