package io.github.respawnedguy.familyapp.data.offers

import io.github.respawnedguy.familyapp.domain.offers.Chain
import io.github.respawnedguy.familyapp.domain.offers.Offer
import io.github.respawnedguy.familyapp.domain.offers.Postcodes

private const val MB = 1024 * 1024
private const val HTML_MB = 5
private const val JSON_MB = 2

/** What one plain request to a market brought back. */
sealed interface HttpAnswer {
    data class Ok(val text: String) : HttpAnswer

    /** HTTP 401, 403, 429 or a challenge page: the market does not want to be read automatically. */
    data class Refused(val code: Int) : HttpAnswer

    /** Network gone, timeout, server error, too big: try again later the same way. */
    data class Failed(val reason: String) : HttpAnswer
}

/**
 * One honest GET request, labelled as this app, without cookies, without following redirects, with a
 * size limit (threat model AN-1). No retry with other means, no browser engine (Nils, 27.11.2026).
 * Blocking: call it off the main thread. Implementations accept only [MarketUrls.ALLOWED_HOSTS].
 */
fun interface MarketHttp {
    fun get(url: String, kind: MarketHttp.Kind): HttpAnswer

    enum class Kind(val maxBytes: Int, val accept: String) {
        /** Kaufland's offer page is about 3.8 MB of HTML (27.11.2026). */
        HTML(HTML_MB * MB, "text/html"),
        JSON(JSON_MB * MB, "application/json"),
    }
}

/** The fixed addresses the adapters use (docs/angebote-und-vorschlaege.md "Rohmaterial"). */
object MarketUrls {
    val ALLOWED_HOSTS = setOf(
        "asl.api.aldi-sued.de", "www.aldi-nord.de", "filiale.kaufland.de", "www.lidl.de", "endpoints.leaflets.schwarz",
    )

    const val ALDI_SUED_PAGE = 60

    fun aldiSued(offset: Int) = "https://asl.api.aldi-sued.de/commerce/v3/product-search?currency=EUR" +
        "&serviceType=walk-in&categoryKey=1588161426582123&limit=$ALDI_SUED_PAGE&offset=$offset&servicePoint=B384"

    const val ALDI_NORD = "https://www.aldi-nord.de/angebote.html"

    fun kauflandStore(point: Postcodes.Point) =
        "https://filiale.kaufland.de/.klstorebygeo.json?lat=${point.latText}&lng=${point.lonText}"

    const val KAUFLAND_OFFERS = "https://filiale.kaufland.de/angebote/uebersicht.html?kloffer-week=current"

    fun kauflandStoreOffers(storeId: String) = "https://filiale.kaufland.de/.kloffers.storeName=$storeId.json"

    const val LIDL_LEAFLETS = "https://www.lidl.de/c/online-prospekte/s10005610"

    fun lidlFlyer(id: String) =
        "https://endpoints.leaflets.schwarz/v4/flyer?flyer_identifier=$id&region_id=0&region_code=0"

    /** Store ids as Kaufland writes them ("DE1234"); anything else never goes into a URL. */
    val STORE_ID = Regex("""^[A-Z]{2}\d{3,6}$""")

    /** Host of an http(s) URL, or null. */
    fun host(url: String): String? =
        Regex("""^https://([a-z0-9.-]+)(?:[/?]|$)""").find(url)?.groupValues?.get(1)
}

/** Result of reading one market. */
sealed interface MarketResult {
    val chain: Chain

    data class Ok(override val chain: Chain, val offers: List<Offer>, val store: MarketParsers.Store? = null) :
        MarketResult

    data class Refused(override val chain: Chain, val code: Int) : MarketResult

    data class Failed(override val chain: Chain, val reason: String) : MarketResult
}

/**
 * The four adapters (Empfehlung 4): each reads its market with [http] and returns its own result, so a
 * market that fails never stops the others. Blocking, off the main thread.
 */
class MarketFetch(private val http: MarketHttp) {

    /** Stops the adapter at the first answer that is not [HttpAnswer.Ok]. */
    private class Stop(val result: MarketResult) : Exception()

    private fun text(chain: Chain, url: String, kind: MarketHttp.Kind): String {
        val answer = if (MarketUrls.host(url) in MarketUrls.ALLOWED_HOSTS) http.get(url, kind) else NOT_ALLOWED
        val stop = when (answer) {
            is HttpAnswer.Ok -> return answer.text
            is HttpAnswer.Refused -> MarketResult.Refused(chain, answer.code)
            is HttpAnswer.Failed -> MarketResult.Failed(chain, answer.reason)
        }
        throw Stop(stop)
    }

    /**
     * Reads [chain]. [point] is the centre of the family's postcode (Kaufland's store search);
     * [kauflandStore] a store already known, which skips that search.
     */
    fun fetch(chain: Chain, point: Postcodes.Point?, kauflandStore: MarketParsers.Store? = null): MarketResult =
        try {
            read(chain, point, kauflandStore)
        } catch (stop: Stop) {
            stop.result
        }

    private fun read(chain: Chain, point: Postcodes.Point?, kauflandStore: MarketParsers.Store?): MarketResult {
        val result = when (chain) {
            Chain.ALDI_SUED -> MarketResult.Ok(chain, aldiSued())
            Chain.ALDI_NORD ->
                MarketResult.Ok(chain, MarketParsers.aldiNord(text(chain, MarketUrls.ALDI_NORD, MarketHttp.Kind.HTML)))
            Chain.KAUFLAND -> kaufland(point, kauflandStore)
            Chain.LIDL -> MarketResult.Ok(chain, lidl())
        }
        val empty = result is MarketResult.Ok && result.offers.isEmpty()
        return if (empty) MarketResult.Failed(chain, NOTHING_FOUND) else result
    }

    private fun aldiSued(): List<Offer> {
        val first = MarketParsers.aldiSued(text(Chain.ALDI_SUED, MarketUrls.aldiSued(0), MarketHttp.Kind.JSON))
        val size = MarketUrls.ALDI_SUED_PAGE
        val pages = (first.total.coerceAtMost(MAX_ALDI_OFFERS) + size - 1) / size
        val rest = (1 until pages).flatMap { page ->
            MarketParsers.aldiSued(text(Chain.ALDI_SUED, MarketUrls.aldiSued(page * size), MarketHttp.Kind.JSON)).offers
        }
        return (first.offers + rest).distinctBy { it.title }
    }

    /** Store from the store search (or the known one), offers of the page filtered to that store's article numbers. */
    private fun kaufland(point: Postcodes.Point?, known: MarketParsers.Store?): MarketResult {
        val chain = Chain.KAUFLAND
        val store = known ?: point?.let {
            MarketParsers.kauflandStore(text(chain, MarketUrls.kauflandStore(it), MarketHttp.Kind.JSON))
        }
        // An id from the answer goes into the next URL only if it looks like Kaufland's (threat model AN-4).
        if (store == null || !MarketUrls.STORE_ID.matches(store.id)) return MarketResult.Failed(chain, NO_STORE)
        val all = MarketParsers.kaufland(text(chain, MarketUrls.KAUFLAND_OFFERS, MarketHttp.Kind.HTML))
        val articles = MarketParsers.kauflandStoreArticles(
            text(chain, MarketUrls.kauflandStoreOffers(store.id), MarketHttp.Kind.JSON),
        )
        // Without the store's list the page's own selection (by the phone's connection) is used.
        val offers = if (articles == null) all else all.filter { it.klNr == null || it.klNr in articles }
        return MarketResult.Ok(chain, offers.map { it.offer }, store)
    }

    private fun lidl(): List<Offer> {
        val ids = MarketParsers.lidlFlyerIds(text(Chain.LIDL, MarketUrls.LIDL_LEAFLETS, MarketHttp.Kind.HTML))
            .filter { LIDL_ID.matches(it) }
            .take(MAX_LIDL_FLYERS)
        return ids.flatMap { MarketParsers.lidlFlyer(text(Chain.LIDL, MarketUrls.lidlFlyer(it), MarketHttp.Kind.JSON)) }
            .distinctBy { it.title }
    }

    companion object {
        const val NOTHING_FOUND = "Keine Angebote erkannt"
        const val NO_STORE = "Keine Filiale gefunden"
        private val NOT_ALLOWED = HttpAnswer.Failed("Adresse nicht erlaubt")
        private const val MAX_ALDI_OFFERS = 300
        private const val MAX_LIDL_FLYERS = 3
        private val LIDL_ID = Regex("""^aktionsprospekt-[a-z0-9-]{1,80}$""")
    }
}
