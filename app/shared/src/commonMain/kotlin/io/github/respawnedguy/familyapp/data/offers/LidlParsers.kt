package io.github.respawnedguy.familyapp.data.offers

import io.github.respawnedguy.familyapp.data.offers.MarketJson.arr
import io.github.respawnedguy.familyapp.data.offers.MarketJson.obj
import io.github.respawnedguy.familyapp.data.offers.MarketJson.str
import io.github.respawnedguy.familyapp.data.offers.MarketValues.date
import io.github.respawnedguy.familyapp.domain.offers.Chain
import io.github.respawnedguy.familyapp.domain.offers.Offer

/** Lidl: leaflet ids from the leaflet overview page, food from the leaflet's recipe links. */
internal object LidlParsers {

    private val FLYER = Regex("""/l/prospekte/(aktionsprospekt-[a-z0-9-]+)/""")
    private val QUERY = Regex("""[?&]q=([^&#]+)""")
    private val WORD = Regex("""^[\p{L}][\p{L} \-]{1,40}$""")
    private const val HEX = 16
    private const val HEX_END = 3

    fun flyerIds(html: String): List<String> = FLYER.findAll(html).map { it.groupValues[1] }.distinct().toList()

    fun flyer(text: String): List<Offer> {
        val flyer = MarketJson.parse(text).obj()?.get("flyer").obj() ?: return emptyList()
        val from = date(flyer.str("offerStartDate"))
        val until = date(flyer.str("offerEndDate"))
        return flyer["pages"].arr().orEmpty()
            .flatMap { p -> p.obj()?.get("links").arr().orEmpty() }
            .mapNotNull { it.obj() }
            .filter { it.str("displayType") == "recipe" }
            .mapNotNull { l -> l.str("url")?.let(::queryWord) }
            .distinct()
            .map { Offer(Chain.LIDL, it, validFrom = from, validUntil = until) }
    }

    private fun queryWord(url: String): String? =
        QUERY.find(url)?.groupValues?.get(1)?.let(::urlDecode)?.trim()?.takeIf { WORD.matches(it) }

    /** Minimal percent-decoding for UTF-8 query words ("K%C3%BCrbis" -> "Kürbis", "+" -> " "). */
    fun urlDecode(s: String): String {
        val bytes = ArrayList<Byte>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '%' && i + 2 <= s.lastIndex -> {
                    val b = s.substring(i + 1, i + HEX_END).toIntOrNull(HEX)
                    if (b == null) bytes += c.code.toByte() else { bytes += b.toByte(); i += 2 }
                }
                c == '+' -> bytes += ' '.code.toByte()
                else -> c.toString().encodeToByteArray().forEach { bytes += it }
            }
            i++
        }
        return bytes.toByteArray().decodeToString()
    }
}
