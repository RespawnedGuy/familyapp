package io.github.respawnedguy.familyapp.data.offers

import io.github.respawnedguy.familyapp.domain.offers.Chain
import io.github.respawnedguy.familyapp.domain.offers.Postcodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The adapters with a fake network: what is asked, and what a refusal or failure does. */
class MarketFetchTest {

    private class FakeHttp(private val answers: (String) -> HttpAnswer) : MarketHttp {
        val asked = mutableListOf<String>()
        override fun get(url: String, kind: MarketHttp.Kind): HttpAnswer {
            asked += url
            return answers(url)
        }
    }

    private fun aldiPage(names: List<String>, total: Int) =
        """{"meta":{"pagination":{"totalCount":$total}},"data":[${names.joinToString(",") { "{\"name\":\"$it\",\"price\":{\"amount\":100}}" }}]}"""

    @Test
    fun aldiSuedReadsAllPages() {
        val http = FakeHttp { url ->
            if ("offset=0" in url) HttpAnswer.Ok(aldiPage(List(60) { "A$it" }, 115))
            else HttpAnswer.Ok(aldiPage(List(55) { "B$it" }, 115))
        }
        val result = assertIs<MarketResult.Ok>(MarketFetch(http).fetch(Chain.ALDI_SUED, null))
        assertEquals(115, result.offers.size)
        assertEquals(2, http.asked.size)
        assertTrue(http.asked[1].contains("offset=60"))
    }

    @Test
    fun an5_refusalStopsTheMarketAtOnce() {
        val http = FakeHttp { HttpAnswer.Refused(403) }
        assertEquals(MarketResult.Refused(Chain.ALDI_SUED, 403), MarketFetch(http).fetch(Chain.ALDI_SUED, null))
        assertEquals(1, http.asked.size) // no second request, no other way in
    }

    @Test
    fun failureAndEmptyAnswersAreFailures() {
        assertEquals(
            MarketResult.Failed(Chain.ALDI_NORD, "HTTP 503"),
            MarketFetch(FakeHttp { HttpAnswer.Failed("HTTP 503") }).fetch(Chain.ALDI_NORD, null),
        )
        assertEquals(
            MarketResult.Failed(Chain.ALDI_NORD, MarketFetch.NOTHING_FOUND),
            MarketFetch(FakeHttp { HttpAnswer.Ok("<html>neu gebaut</html>") }).fetch(Chain.ALDI_NORD, null),
        )
    }

    private val kauflandPage = """
        <script>window.SSR['o'] = {"component":"OfferTemplate","props":{"offerData":{"cycles":[{"categories":[
        {"name":"02_Obst__Gemuese__Pflanzen","offers":[
          {"title":"Spitzkohl","price":0.99,"klNr":"1001"},
          {"title":"Möhren","price":0.79,"klNr":"1002"},
          {"title":"Lauch","price":0.89,"klNr":"1003"}]}]}]}}};</script>
    """.trimIndent()

    @Test
    fun an3_kauflandGetsOnlyThePostcodeCentreAndOffersAreFilteredToTheStore() {
        val point = Postcodes.Point(52.53, 13.38)
        val http = FakeHttp { url ->
            when {
                ".klstorebygeo.json" in url -> HttpAnswer.Ok("""{"n":"DE0001","cn":"Kaufland Musterstadt"}""")
                ".kloffers.storeName=DE0001.json" in url -> HttpAnswer.Ok("""[{"klNr":"1001"},{"klNr":"1003"}]""")
                "uebersicht.html" in url -> HttpAnswer.Ok(kauflandPage)
                else -> HttpAnswer.Failed("unerwartet")
            }
        }
        val result = assertIs<MarketResult.Ok>(MarketFetch(http).fetch(Chain.KAUFLAND, point))
        assertEquals(listOf("Spitzkohl", "Lauch"), result.offers.map { it.title })
        assertEquals(MarketParsers.Store("DE0001", "Kaufland Musterstadt"), result.store)
        assertEquals("https://filiale.kaufland.de/.klstorebygeo.json?lat=52.53&lng=13.38", http.asked.first())
    }

    @Test
    fun kauflandWithAKnownStoreSkipsTheStoreSearch() {
        val http = FakeHttp { url ->
            if ("uebersicht.html" in url) HttpAnswer.Ok(kauflandPage) else HttpAnswer.Ok("""{"kein":"array"}""")
        }
        val result = assertIs<MarketResult.Ok>(
            MarketFetch(http).fetch(Chain.KAUFLAND, null, MarketParsers.Store("DE0002", "Kaufland B")),
        )
        assertEquals(3, result.offers.size) // store list unreadable: the page's own selection counts
        assertTrue(http.asked.none { "klstorebygeo" in it })
    }

    @Test
    fun an4_storeIdsFromAnAnswerNeverBuildOtherUrls() {
        val http = FakeHttp { url ->
            if ("klstorebygeo" in url) HttpAnswer.Ok("""{"n":"../../evil?x=","cn":"Böse"}""") else HttpAnswer.Ok(kauflandPage)
        }
        val result = MarketFetch(http).fetch(Chain.KAUFLAND, Postcodes.Point(52.0, 13.0))
        assertIs<MarketResult.Failed>(result)
        assertEquals(1, http.asked.size)
    }

    @Test
    fun an4_lidlLeafletIdsAreCheckedAndCapped() {
        val ids = (1..5).joinToString(" ") { "/l/prospekte/aktionsprospekt-woche-$it/ar/0" }
        val flyer = """{"flyer":{"pages":[{"links":[{"displayType":"recipe","url":"x?q=Spitzkohl"}]}]}}"""
        val http = FakeHttp { url -> if ("online-prospekte" in url) HttpAnswer.Ok(ids) else HttpAnswer.Ok(flyer) }
        val result = assertIs<MarketResult.Ok>(MarketFetch(http).fetch(Chain.LIDL, null))
        assertEquals(listOf("Spitzkohl"), result.offers.map { it.title })
        assertEquals(1 + 3, http.asked.size)
        assertTrue(http.asked.drop(1).all { it.startsWith("https://endpoints.leaflets.schwarz/v4/flyer?flyer_identifier=aktionsprospekt-") })
    }

    @Test
    fun an4_everyUrlGoesToAnAllowedHost() {
        val urls = listOf(
            MarketUrls.aldiSued(0), MarketUrls.ALDI_NORD, MarketUrls.kauflandStore(Postcodes.Point(1.0, 2.0)),
            MarketUrls.KAUFLAND_OFFERS, MarketUrls.kauflandStoreOffers("DE1"), MarketUrls.LIDL_LEAFLETS,
            MarketUrls.lidlFlyer("aktionsprospekt-x"),
        )
        urls.forEach { assertTrue(MarketUrls.host(it) in MarketUrls.ALLOWED_HOSTS, it) }
        assertEquals(null, MarketUrls.host("http://www.lidl.de/"))
        assertEquals("www.lidl.de.evil.example", MarketUrls.host("https://www.lidl.de.evil.example/"))
    }
}
