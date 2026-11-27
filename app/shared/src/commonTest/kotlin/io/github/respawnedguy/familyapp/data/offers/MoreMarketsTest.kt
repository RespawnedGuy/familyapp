package io.github.respawnedguy.familyapp.data.offers

import io.github.respawnedguy.familyapp.domain.offers.Chain
import io.github.respawnedguy.familyapp.domain.offers.Postcodes
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stage 2 markets (docs/angebote-maerkte.md): Penny, Norma, Netto, denn's. The answers are shortened
 * and invented, but in the shape the real pages had on 27.11.2026.
 */
class MoreMarketsTest {

    private class FakeHttp(private val answers: (String) -> HttpAnswer) : MarketHttp {
        val asked = mutableListOf<Pair<String, MarketHttp.Kind>>()
        override fun get(url: String, kind: MarketHttp.Kind): HttpAnswer {
            asked += url to kind
            return answers(url)
        }
    }

    private val berlin = Postcodes.Point(52.53, 13.38)

    private fun store(id: String, name: String, lat: Double, lon: Double, region: String = "15A-08-26") =
        """{"wwIdent":"$id","sellingRegion":"$region","marketName":"$name","streetWithHouseNumber":"Musterweg 1",""" +
            """"zipCode":"10115","city":"Berlin","latitude":$lat,"longitude":$lon}"""

    private val stores = "[" + listOf(
        store("530001", "Penny Hamburg", 53.55, 9.99, "11B-01-01"),
        store("530002", "Penny Mitte", 52.531, 13.381),
        store("530003", "Penny Wedding", 52.55, 13.36),
        store("bad", "Penny Kaputt", 52.53, 13.38),
        store("530004", "Penny Region kaputt", 52.53, 13.38, "x/../y"),
    ).joinToString(",") + "]"

    private val pennyPage = """
        <div class="tabs__content--weeks" data-current-week="2026-48" data-next-week="2026-49">
        <div class="week-offers tabs__content tabs__content--current-week" data-week="current">
        <section id="ab-montag--obst-und-gemuese" class="js-category-section" data-category-type="food"
          data-period-category="false" data-category-name="obst-und-gemuese"></section>
        <section id="ab-montag--getraenke" class="js-category-section" data-category-type="food"
          data-period-category="false" data-category-name="getraenke"></section>
        <section id="ab-montag--black-week" class="js-category-section" data-category-type="nonfood"
          data-period-category="false" data-category-name="black-week"></section>
        <section id="ab-montag--boese" class="js-category-section" data-category-type="food"
          data-period-category="false" data-category-name="../../admin"></section>
        <section id="ab-donnerstag--kuehlregal" class="js-category-section" data-category-type="food"
          data-period-category="false" data-category-name="kuehlregal"></section>
        </div>
        <div class="week-offers tabs__content tabs__content--next-week" data-week="next">
        <section id="ab-naechsten-montag--fleisch" class="js-category-section" data-category-type="food"
          data-period-category="false" data-category-name="fleisch-und-wurst"></section>
        </div>
    """.trimIndent()

    private val pennyOffers = """{"offerTiles":[
        {"title":"Speisemöhren*","type":"food","quantity":"je 2 kg","price":"1.49","listPrice":"1.99","advantage":"25%"},
        {"title":"Lichterkette","type":null,"quantity":"je Stück","price":"9.99"},
        {"title":"Hokkaido-Kürbis","type":"food","quantity":"je Stück","price":"1.29²","crossOutPrice":"1.79"}
    ]}"""

    @Test
    fun pennyStoresKeepOnlyCleanIdsAndRegions_an9() {
        val parsed = PennyParsers.stores(stores)
        assertEquals(listOf("530001", "530002", "530003"), parsed.map { it.id })
        assertEquals("Penny Mitte, Musterweg 1 Berlin", parsed[1].name)
    }

    @Test
    fun pennyWeekTakesThisWeeksFoodCategoriesForCooking_an9() {
        val week = PennyParsers.week(pennyPage)
        assertEquals("2026-48", week?.week)
        // drinks left out, non-food left out, a name that is no slug never goes into a URL, next week not yet
        assertEquals(listOf("obst-und-gemuese", "kuehlregal"), week?.categories)
        assertNull(PennyParsers.week("<html>umgebaut</html>"))
    }

    @Test
    fun pennyOffersAreFoodWithPrices() {
        val offers = PennyParsers.offers(pennyOffers)
        assertEquals(listOf("Speisemöhren je 2 kg", "Hokkaido-Kürbis je Stück"), offers.map { it.title })
        assertEquals(149, offers[0].priceCents)
        assertEquals(199, offers[0].oldPriceCents)
        assertEquals(25, offers[0].savingPercent)
        assertEquals(129, offers[1].priceCents) // footnote mark after the price is ignored
        assertEquals(179, offers[1].oldPriceCents)
    }

    private fun pennyHttp() = FakeHttp { url ->
        when {
            url == MarketUrls.PENNY_STORES -> HttpAnswer.Ok(stores)
            url == MarketUrls.PENNY_OFFERS -> HttpAnswer.Ok(pennyPage)
            "/.rest/offers/by-category/" in url -> HttpAnswer.Ok(pennyOffers)
            else -> HttpAnswer.Failed("unerwartet: $url")
        }
    }

    @Test
    fun pennyReadsTheNearestStoresRegionAndOffersFiveToChoose() {
        val http = pennyHttp()
        val result = assertIs<MarketResult.Ok>(MarketFetch(http).fetch(Chain.PENNY, berlin))
        assertEquals("530002", result.store?.id)
        assertEquals(listOf("530002", "530003", "530001"), result.nearby.map { it.id })
        assertTrue(result.nearby[0].distanceKm < 1.0)
        assertEquals(2, result.offers.size)
        val categoryUrls = http.asked.map { it.first }.filter { "by-category" in it }
        assertEquals(
            listOf(
                "https://www.penny.de/.rest/offers/by-category/2026-48/obst-und-gemuese?region=15A-08-26",
                "https://www.penny.de/.rest/offers/by-category/2026-48/kuehlregal?region=15A-08-26",
            ),
            categoryUrls,
        )
        assertEquals(MarketHttp.Kind.STORE_LIST, http.asked.first().second)
    }

    @Test
    fun pennyKeepsTheChosenStore() {
        val http = pennyHttp()
        val chosen = MarketParsers.Store("530001", "Penny Hamburg")
        val result = assertIs<MarketResult.Ok>(MarketFetch(http).fetch(Chain.PENNY, berlin, chosen))
        assertEquals("530001", result.store?.id)
        assertTrue(http.asked.any { "region=11B-01-01" in it.first })
    }

    @Test
    fun pennyRefusalStopsAtOnce_an5() {
        val http = FakeHttp { HttpAnswer.Refused(429) }
        assertEquals(MarketResult.Refused(Chain.PENNY, 429), MarketFetch(http).fetch(Chain.PENNY, berlin))
        assertEquals(1, http.asked.size)
    }

    @Test
    fun pennyWithoutPostcodeOrStoresHasNoStore() {
        assertEquals(
            MarketResult.Failed(Chain.PENNY, MarketFetch.NO_STORE),
            MarketFetch(pennyHttp()).fetch(Chain.PENNY, null),
        )
    }

    private fun normaBox(name: String, price: String, old: String? = null) = """
        <article class="b463 produktBoxContainer" id="of_1">
          <a href="/de/angebote/x/"><div class="produktBox">
            <div class="produktBox-txt"><strong class="supplier">Hof</strong>
              <h3 class="produktBox-txt-headline">$name</h3>
              <ul><li class="produktBox-txt-inh">je 1 kg</li><li class="produktBox-txt-price">1 kg = $price</li></ul>
            </div>
            <div class="produktBox-cont"><div class="produktBox-cont-wrapper">
              ${old?.let { "<div class=\"produktBox-cont-wrapper-billiger\" title=\"Sie sparen\"><p>25% billiger</p></div>" } ?: ""}
              <ul>${old?.let { "<li class=\"produktBox-cont-wrapper-uvp\">statt $it</li>" } ?: ""}
              <li class="produktBox-cont-wrapper-price"><span aria-label="$price Euro">$price<sup>*</sup></span></li></ul>
            </div></div>
          </div></a>
        </article>
    """

    @Test
    fun normaReadsProductBoxesAndDayPages() {
        val produce = "<main>" + normaBox("Äpfel &quot;Elstar&quot;", "1,99", "2,65") + normaBox("Möhren", "0,99") + "</main>"
        val offers = NormaParsers.offers(produce, LocalDate(2026, 11, 23))
        assertEquals(listOf("Äpfel \"Elstar\" je 1 kg", "Möhren je 1 kg"), offers.map { it.title })
        assertEquals(199, offers[0].priceCents)
        assertEquals(265, offers[0].oldPriceCents)
        assertEquals(25, offers[0].savingPercent)
        assertEquals(LocalDate(2026, 11, 23), offers[0].validFrom)

        val overview = """<a href="/de/angebote/ab-montag,-23.11.26/">Mo</a>
            <a href="/de/angebote/ab-montag,-23.11.26/asia-food-t-1/">Thema</a>
            <a href="/de/angebote/ab-mittwoch,-25.11.26/">Mi</a>
            <a href="https://boese.example/de/angebote/ab-montag,-23.11.26/">fremd</a>"""
        assertEquals(listOf("/de/angebote/ab-montag,-23.11.26/", "/de/angebote/ab-mittwoch,-25.11.26/"), NormaParsers.dayPages(overview))
        assertEquals(LocalDate(2026, 11, 25), NormaParsers.dayOf("/de/angebote/ab-mittwoch,-25.11.26/"))
        assertNull(NormaParsers.dayOf("/de/angebote/ab-montag,-31.02.26/"))
    }

    @Test
    fun normaFetchesOverviewProduceAndDays() {
        val http = FakeHttp { url ->
            when (url) {
                MarketUrls.NORMA_OVERVIEW -> HttpAnswer.Ok("""<a href="/de/angebote/ab-montag,-23.11.26/">Mo</a>""")
                MarketUrls.NORMA_PRODUCE -> HttpAnswer.Ok(normaBox("Möhren", "0,99"))
                else -> HttpAnswer.Ok(normaBox("Frischkäse", "1,11") + normaBox("Möhren", "0,99"))
            }
        }
        val result = assertIs<MarketResult.Ok>(MarketFetch(http).fetch(Chain.NORMA, null))
        assertEquals(listOf("Möhren je 1 kg", "Frischkäse je 1 kg"), result.offers.map { it.title })
        assertEquals("https://www.norma-online.de/de/angebote/ab-montag,-23.11.26/", http.asked.last().first)
    }

    private fun nettoCard(n: Int, name: String, details: String, euros: String, cents: String, old: String? = null) = """
        <div class="rounded-2xl" aria-label="product-$n"><div class="w-full h-32"><img alt="" src="x.png"/></div>
        <div class="w-full h-44"><h4 class="text-paragraph-sm-bold">$name</h4>
        <p class="text-paragraph-xs-regular">$details</p></div>
        <div class="absolute bottom-0 right-0">${old?.let { "<span>statt $it</span>" } ?: ""}
        <h3 class="text-heading-4xl">$euros<!-- -->.<span class="underline">$cents</span></h3></div></div>
    """

    @Test
    fun nettoReadsCards() {
        val page = nettoCard(1, "MEINE FLEISCHEREI\nRinder-Hamburger", "4 x 100 g, \n400 g\n1 kg = 13.73", "5", "49") +
            nettoCard(2, "GO BIO Gurke", "Spanien, Kl. I Stück", "0", "99", old = "1.29") +
            nettoCard(3, "", "leer", "1", "00")
        val offers = NettoParsers.offers(page)
        assertEquals(listOf("MEINE FLEISCHEREI Rinder-Hamburger 4 x 100 g, 400 g", "GO BIO Gurke Spanien, Kl. I Stück"), offers.map { it.title })
        assertEquals(549, offers[0].priceCents)
        assertEquals(99, offers[1].priceCents)
        assertEquals(129, offers[1].oldPriceCents)
    }

    @Test
    fun nettoReadsItsFoodPagesOnly() {
        val http = FakeHttp { HttpAnswer.Ok(nettoCard(1, "Gurke", "Stück", "0", "59")) }
        val result = assertIs<MarketResult.Ok>(MarketFetch(http).fetch(Chain.NETTO, null))
        assertEquals(1, result.offers.size)
        assertEquals(MarketUrls.NETTO_PAGES, http.asked.map { it.first })
        assertTrue(http.asked.none { "nonfood" in it.first })
    }

    private val dennsData = """{"componentChunkName":"x","result":{"data":{"noLimitOffers":{"totalCount":3,"nodes":[
        {"title":"Hähnchenflügel","brand":"Freiländer ","subtitle":"100 g ","price":"0,70","priceb":"1,39",
         "pricebanner":"49 %","hidden":false,"land":"de","validfrom":"2026-11-27","validto":"2026-11-28"},
        {"title":"Versteckt","price":"1,00","hidden":true,"land":"de"},
        {"title":"Nur Österreich","price":"1,00","hidden":false,"land":"at"}
    ]}}}}"""

    @Test
    fun dennsReadsVisibleGermanOffers() {
        val offers = DennsParsers.offers(dennsData)
        assertEquals(1, offers.size)
        assertEquals("Freiländer Hähnchenflügel 100 g", offers[0].title)
        assertEquals(70, offers[0].priceCents)
        assertEquals(139, offers[0].oldPriceCents)
        assertEquals(49, offers[0].savingPercent)
        assertEquals(LocalDate(2026, 11, 28), offers[0].validUntil)
        assertEquals(emptyList(), DennsParsers.offers("<html>"))
    }

    @Test
    fun newHostsAreAllowedAndNothingElse_an1() {
        listOf(MarketUrls.PENNY_STORES, MarketUrls.NORMA_OVERVIEW, MarketUrls.DENNS, MarketUrls.NETTO_PAGES[0]).forEach {
            assertTrue(MarketUrls.host(it) in MarketUrls.ALLOWED_HOSTS, it)
        }
        assertTrue("www.edeka.de" !in MarketUrls.ALLOWED_HOSTS) // terms forbid automated reading
    }

    @Test
    fun htmlTextDropsTagsCommentsAndDecodes() {
        assertEquals("0.59€", MarketHtml.text("0<!-- -->.<span>59</span> &euro;").replace(" ", ""))
        assertEquals("Ä \"x\" & y", MarketHtml.text("<b>&#196;</b> &quot;x&quot; &amp; y"))
    }

    @Test
    fun distanceIsRoughlyRight() {
        val hamburg = Postcodes.Point(53.55, 9.99)
        val km = berlin.distanceKm(hamburg.lat, hamburg.lon)
        assertTrue(km in 250.0..262.0, "Berlin–Hamburg $km km")
        assertEquals(0.0, berlin.distanceKm(berlin.lat, berlin.lon))
    }
}
