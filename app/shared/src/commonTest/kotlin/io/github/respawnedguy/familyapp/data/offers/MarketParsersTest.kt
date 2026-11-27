package io.github.respawnedguy.familyapp.data.offers

import io.github.respawnedguy.familyapp.domain.offers.Chain
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parsers against small hand-written answers of the same shape the markets delivered on 27.11.2026
 * (docs/angebote-und-vorschlaege.md "Rohmaterial"). Products and prices are made up. Threat model AN-2:
 * hostile or broken answers give fewer offers, never an exception.
 */
class MarketParsersTest {

    // ---- Aldi Süd -------------------------------------------------------------------------------

    private val aldiSued = """
        {"meta":{"pagination":{"offset":0,"limit":60,"totalCount":115}},
         "data":[
          {"name":"Rinderhackfleisch","brandName":null,"price":{"amount":399,"amountRelevant":349,
            "wasPriceDisplay":"4,59 €","savingsDisplay":"24 %"},"assets":[{"url":"https://example.org/x.jpg"}]},
          {"name":"  Grünkohl,\n frisch  ","price":{"amount":199}},
          {"name":"","price":{"amount":1}},
          {"price":{"amount":5}},
          "kaputt"
         ]}
    """.trimIndent()

    @Test
    fun aldiSuedReadsNamePricesAndSaving() {
        val page = MarketParsers.aldiSued(aldiSued)
        assertEquals(115, page.total)
        assertEquals(2, page.offers.size)
        val hack = page.offers[0]
        assertEquals(Chain.ALDI_SUED, hack.chain)
        assertEquals("Rinderhackfleisch", hack.title)
        assertEquals(349, hack.priceCents)
        assertEquals(459, hack.oldPriceCents)
        assertEquals(24, hack.savingPercent)
        assertEquals("Grünkohl, frisch", page.offers[1].title)
        assertEquals(199, page.offers[1].priceCents)
        assertNull(page.offers[1].savingPercent)
    }

    @Test
    fun an2_aldiSuedBrokenAnswersGiveAnEmptyPage() {
        listOf("", "<html>Wartung</html>", "[]", "{\"data\":\"nein\"}", "{\"data\":[1,2,3]}").forEach {
            val page = MarketParsers.aldiSued(it)
            assertTrue(page.offers.isEmpty(), it)
        }
    }

    // ---- Aldi Nord ------------------------------------------------------------------------------

    private fun nextData(json: String) =
        "<html><head></head><body><div id=\"__next\"></div>" +
            "<script id=\"__NEXT_DATA__\" type=\"application/json\">$json</script></body></html>"

    private val nordProduct = """
        {"name":"Feldsalat","brandName":"MEINE ERNTE","salesUnit":"150 g",
         "promotionPrices":[{"priceValue":1.29,"strikePrice":{"strikePriceValue":1.79},
           "priceTagLabels":{"promoText1":"-27 %"},
           "validFromLocalDate":"2026-11-30","validUntilLocalDate":"2026-12-05"}]}
    """.trimIndent()

    @Test
    fun aldiNordFindsProductsInsideTheEmbeddedJsonString() {
        // apiData is a JSON string inside the JSON, as on aldi-nord.de.
        val api = """[["x",{"res":{"algoliaDataMap":{"a1":$nordProduct,"a2":$nordProduct}}}]]"""
        val quoted = "\"" + api.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "") + "\""
        val html = nextData("""{"props":{"pageProps":{"apiData":$quoted}}}""")
        val offers = MarketParsers.aldiNord(html)
        assertEquals(1, offers.size) // same product twice counts once
        val o = offers.single()
        assertEquals(Chain.ALDI_NORD, o.chain)
        assertEquals("MEINE ERNTE Feldsalat 150 g", o.title)
        assertEquals(129, o.priceCents)
        assertEquals(179, o.oldPriceCents)
        assertEquals(27, o.savingPercent)
        assertEquals(LocalDate(2026, 11, 30), o.validFrom)
        assertEquals(LocalDate(2026, 12, 5), o.validUntil)
    }

    @Test
    fun an2_aldiNordWithoutDataOrWithTooDeepNestingGivesNothing() {
        assertTrue(MarketParsers.aldiNord("<html>Wartung</html>").isEmpty())
        assertTrue(MarketParsers.aldiNord(nextData("kein json")).isEmpty())
        assertTrue(MarketParsers.aldiNord("<script id=\"__NEXT_DATA__\">{\"a\":1}").isEmpty())
        val deep = "[".repeat(60) + nordProduct + "]".repeat(60)
        assertTrue(MarketParsers.aldiNord(nextData(deep)).isEmpty())
    }

    // ---- Kaufland -------------------------------------------------------------------------------

    private fun kauflandPage(categories: String) = """
        <html><body><script>window.SSR = window.SSR || {};
        window.SSR['offers'] = {"component":"OfferTemplate","props":{"offerData":{"cycles":[{"categories":[$categories]}]}}};
        </script></body></html>
    """.trimIndent()

    private val food = """
        {"name":"02_Obst__Gemuese__Pflanzen","displayName":"Obst, Gemüse, Pflanzen","offers":[
          {"offerId":"o1","title":"Spitzkohl","unit":"je Stück","price":0.99,"discount":33,"klNr":"1001",
           "formattedOldPrice":"1.49","dateFrom":"2026-11-26","dateTo":"2026-12-02"},
          {"offerId":"o2","title":"Hokkaido-Kürbis","price":1.79,"discount":0,"klNr":"1002",
           "salesFrom":"2026-11-27","salesTo":"2026-11-28"},
          {"offerId":"o3","price":2.0}
        ]}
    """.trimIndent()

    private val nonFood = """{"name":"12_Haushalt","offers":[{"title":"Kerzen","price":3.0,"klNr":"9"}]}"""

    @Test
    fun kauflandReadsFoodCategoriesOnly() {
        val offers = MarketParsers.kaufland(kauflandPage("$food,$nonFood"))
        assertEquals(listOf("1001", "1002"), offers.map { it.klNr })
        val kohl = offers[0].offer
        assertEquals("Spitzkohl je Stück", kohl.title)
        assertEquals(99, kohl.priceCents)
        assertEquals(149, kohl.oldPriceCents)
        assertEquals(33, kohl.savingPercent)
        assertEquals(LocalDate(2026, 11, 26), kohl.validFrom)
        val kuerbis = offers[1].offer
        assertNull(kuerbis.savingPercent) // 0 % is no saving
        assertEquals(LocalDate(2026, 11, 27), kuerbis.validFrom) // salesFrom wins over dateFrom
        assertEquals(LocalDate(2026, 11, 28), kuerbis.validUntil)
    }

    @Test
    fun kauflandStoreAnswers() {
        assertEquals(setOf("1001", "7"), MarketParsers.kauflandStoreArticles("""[{"klNr":"1001"},{"klNr":"7"},{"x":1}]"""))
        assertNull(MarketParsers.kauflandStoreArticles("""{"error":"no"}"""))
        assertEquals(MarketParsers.Store("DE0001", "Kaufland Musterstadt"),
            MarketParsers.kauflandStore("""{"n":"DE0001","cn":"Kaufland Musterstadt","t":"Musterstadt"}"""))
        assertEquals(MarketParsers.Store("DE0002", "DE0002"), MarketParsers.kauflandStore("""{"n":"DE0002"}"""))
        assertNull(MarketParsers.kauflandStore("""{"cn":"ohne Nummer"}"""))
    }

    @Test
    fun an2_kauflandWithoutOfferTemplateGivesNothing() {
        assertTrue(MarketParsers.kaufland("<html>Bitte wählen Sie Ihre Filiale</html>").isEmpty())
        assertTrue(MarketParsers.kaufland("\"component\":\"OfferTemplate\" ohne Klammer und Ende").isEmpty())
        assertTrue(MarketParsers.kaufland(kauflandPage("\"kaputt\"")).isEmpty())
    }

    // ---- Lidl -----------------------------------------------------------------------------------

    @Test
    fun lidlFlyerIdsComeFromTheLeafletLinks() {
        val html = """
            <a href="https://www.lidl.de/l/prospekte/aktionsprospekt-30-11-2026-05-12-2026-1a2b3c/ar/0">KW 49</a>
            <a href="/l/prospekte/aktionsprospekt-30-11-2026-05-12-2026-1a2b3c/ar/0">doppelt</a>
            <a href="/l/prospekte/getraenke-angebote-abc/ar/0">anderes</a>
        """.trimIndent()
        assertEquals(listOf("aktionsprospekt-30-11-2026-05-12-2026-1a2b3c"), MarketParsers.lidlFlyerIds(html))
    }

    @Test
    fun lidlFoodComesFromRecipeLinksWithTheLeafletDates() {
        val flyer = """
            {"flyer":{"offerStartDate":"2026-11-30","offerEndDate":"2026-12-05","pages":[
              {"links":[
                {"displayType":"recipe","url":"https://www.lidl.de/r/alle-rezepte?q=Spitzkohl"},
                {"displayType":"recipe","url":"https://www.lidl.de/r/alle-rezepte?q=K%C3%BCrbis&x=1"},
                {"displayType":"recipe","url":"https://www.lidl.de/r/alle-rezepte?q=Rote+Bete"},
                {"displayType":"recipe","url":"https://www.lidl.de/r/alle-rezepte?q=Spitzkohl"},
                {"displayType":"product","url":"https://www.lidl.de/p/123?q=Rasenmaeher"},
                {"displayType":"recipe","url":"https://evil.example/?q=%3Cscript%3E"},
                {"displayType":"recipe","url":"https://www.lidl.de/r/alle-rezepte?q=12345"}
              ]},
              {"links":"kaputt"}
            ]}}
        """.trimIndent()
        val offers = MarketParsers.lidlFlyer(flyer)
        assertEquals(listOf("Spitzkohl", "Kürbis", "Rote Bete"), offers.map { it.title })
        assertTrue(offers.all { it.chain == Chain.LIDL && it.priceCents == null })
        assertEquals(LocalDate(2026, 11, 30), offers[0].validFrom)
        assertEquals(LocalDate(2026, 12, 5), offers[0].validUntil)
    }

    @Test
    fun an2_urlDecodeKeepsBrokenEscapesAsText() {
        assertEquals("Kürbis", MarketParsers.urlDecode("K%C3%BCrbis"))
        assertEquals("a b", MarketParsers.urlDecode("a+b"))
        assertEquals("100%", MarketParsers.urlDecode("100%"))
        assertEquals("%zz", MarketParsers.urlDecode("%zz"))
    }

    @Test
    fun an2_titlesAreCappedAndCollapsed() {
        val long = "A".repeat(500)
        val page = MarketParsers.aldiSued("""{"data":[{"name":"$long\t\t B"}]}""")
        assertEquals(160, page.offers.single().title.length)
    }
}
