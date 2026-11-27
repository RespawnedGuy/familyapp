package io.github.respawnedguy.familyapp.presentation

import io.github.respawnedguy.familyapp.data.FamilyRepository
import io.github.respawnedguy.familyapp.data.InMemoryTextStore
import io.github.respawnedguy.familyapp.data.TextStore
import io.github.respawnedguy.familyapp.data.offers.HttpAnswer
import io.github.respawnedguy.familyapp.data.offers.MarketHttp
import io.github.respawnedguy.familyapp.data.offers.MarketStatus
import io.github.respawnedguy.familyapp.data.offers.OfferCache
import io.github.respawnedguy.familyapp.domain.offers.Chain
import io.github.respawnedguy.familyapp.domain.offers.Offer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A phone with a fake network that answers like the four markets (made-up offers). */
private class OfferPlatform(var unmetered: Boolean = true) : PlatformServices {
    val asked = mutableListOf<String>()
    var refuse: Set<String> = emptySet()
    override val offerStore: TextStore = InMemoryTextStore()
    override val marketHttp = MarketHttp { url, _ ->
        asked += url
        val host = url.substringAfter("://").substringBefore('/')
        when {
            refuse.any { it in host } -> HttpAnswer.Refused(403)
            "aldi-sued" in host -> HttpAnswer.Ok(
                """{"meta":{"pagination":{"totalCount":1}},"data":[{"name":"Rinderhackfleisch","price":{"amount":399}}]}""",
            )
            ".klstorebygeo.json" in url -> HttpAnswer.Ok("""{"n":"DE0001","cn":"Kaufland Musterstadt"}""")
            ".kloffers." in url -> HttpAnswer.Ok("""[{"klNr":"1"}]""")
            "kaufland" in host -> HttpAnswer.Ok(
                """<script>window.SSR['o'] = {"component":"OfferTemplate","props":{"offerData":{"cycles":[{"categories":[""" +
                    """{"name":"02_Obst__Gemuese__Pflanzen","offers":[{"title":"Grünkohl","price":1.49,"discount":25,"klNr":"1"}]}""" +
                    """]}]}}};</script>""",
            )
            "online-prospekte" in url -> HttpAnswer.Ok("/l/prospekte/aktionsprospekt-kw49/ar/0")
            "/.rest/market" in url -> HttpAnswer.Ok(
                """[{"wwIdent":"530002","marketName":"Penny Mitte","latitude":52.53,"longitude":13.38,"sellingRegion":"15A-08-26"},""" +
                    """{"wwIdent":"530009","marketName":"Penny Süd","latitude":48.14,"longitude":11.57,"sellingRegion":"11B-01-01"}]""",
            )
            url.endsWith("penny.de/angebote") -> HttpAnswer.Ok(
                """<div data-current-week="2026-49"></div><div data-week="current">""" +
                    """<section data-category-type="food" data-category-name="obst-und-gemuese"></section></div>""",
            )
            "by-category" in url -> HttpAnswer.Ok("""{"offerTiles":[{"title":"Wirsing","type":"food","price":"0.99"}]}""")
            "leaflets" in host -> HttpAnswer.Ok(
                """{"flyer":{"pages":[{"links":[{"displayType":"recipe","url":"x?q=Spitzkohl"}]}]}}""",
            )
            else -> HttpAnswer.Failed("unbekannt")
        }
    }
    override fun unmeteredNetwork() = unmetered
    override val speech: SpeechInput? = null
    override fun elapsedMs() = 0L
    override fun reducedMotion() = false
    override fun takePhoto(onResult: (String?) -> Unit) = onResult(null)
    override fun pickBackupFolder(onResult: (String?) -> Unit) = onResult(null)
    override fun writeBackup(folder: String, backup: BackupContent): String? = null
    override fun readBackup(folder: String, code: String?): RestoreRead = RestoreRead.Failed("leer")
    override fun newRecoveryCode() = "ABCD-EFGH-JKMN-PQRS-TVWX"
    override fun requestNotificationPermission() = Unit
    override fun describeFolder(folder: String) = folder
}

@OptIn(ExperimentalCoroutinesApi::class)
class OffersModelTest {
    private val monday = LocalDate(2026, 11, 30)
    private var ids = 0

    private fun TestScope.model(platform: OfferPlatform = OfferPlatform()): AppModel {
        val repo = FamilyRepository(
            InMemoryTextStore(), backgroundScope, UnconfinedTestDispatcher(testScheduler),
            { testScheduler.currentTime }, { monday }, { "id${ids++}" },
        )
        return AppModel(repo, platform, backgroundScope, { monday }, UnconfinedTestDispatcher(testScheduler))
            .apply { createFamily("Nils") }
    }

    private val gruenkohl = "Grünkohl für 4 Personen\nZutaten:\n500 g Grünkohl\n800 g Kartoffeln\n1 Zwiebel\n2 EL Öl\n" +
        "Zubereitung:\n1. Alles kochen."

    @Test
    fun addressWithoutPostcodeIsSaidAndNothingIsSaved() = runTest {
        val m = model()
        m.offers.place.setAddress("Musterweg 1, Musterstadt")
        assertEquals(PlaceActions.NO_POSTCODE, m.feedback.value?.message)
        assertNull(m.repo.current.place)
    }

    @Test
    fun firstPlaceSuggestsMarketsAndReadsThemOnWlan() = runTest {
        val platform = OfferPlatform()
        val m = model(platform)
        m.offers.place.setAddress("Musterweg 1, 80331 München")
        runCurrent()
        val place = assertNotNull(m.repo.current.place)
        assertEquals("80331", place.postcode)
        assertEquals("Musterweg 1, 80331 München", place.label)
        assertEquals(listOf(Chain.ALDI_SUED, Chain.KAUFLAND, Chain.LIDL, Chain.PENNY), place.markets)
        // The store search found a store; it is shown and kept for the family.
        assertEquals("DE0001", m.repo.current.place?.kauflandStoreId)
        assertEquals(
            listOf(
                "Aldi Süd: 1 Angebote",
                "Kaufland: 1 Angebote",
                "Lidl: 1 Lebensmittel im Angebot (ohne Preise)",
                "Penny: 1 Angebote",
            ),
            m.offers.marketLines().map { it.text },
        )
        // Nothing of the address but the postcode centre went out.
        assertTrue(platform.asked.none { "Musterweg" in it || "80331" in it })
        val before = platform.asked.size
        m.offers.refreshIfDue()
        runCurrent()
        assertEquals(before, platform.asked.size) // fresh offers are not read again
    }

    @Test
    fun onMobileDataOffersWaitForWlanUnlessAskedFor() = runTest {
        val platform = OfferPlatform(unmetered = false)
        val m = model(platform)
        m.offers.place.setAddress("10115")
        runCurrent()
        assertTrue(platform.asked.isEmpty())
        assertTrue(m.offers.waitingForWifi.value)
        m.offers.refreshNow()
        runCurrent()
        assertTrue(platform.asked.isNotEmpty())
        assertFalse(m.offers.waitingForWifi.value)
    }

    @Test
    fun an5_refusedMarketIsSwitchedOffUntilThePersonAsksAgain() = runTest {
        val platform = OfferPlatform().apply { refuse = setOf("lidl") }
        val m = model(platform)
        m.offers.place.setAddress("10115")
        runCurrent()
        val lidl = m.offers.marketLines().single { it.chain == Chain.LIDL }
        assertTrue(lidl.refused)
        assertEquals("Lidl lässt sich gerade nicht automatisch lesen (HTTP 403).", lidl.text)
        assertEquals(1, platform.asked.count { "lidl" in it })
        m.offers.refreshNow()
        runCurrent()
        assertEquals(1, platform.asked.count { "lidl" in it }) // "Jetzt abrufen" leaves it off
        platform.refuse = emptySet()
        m.offers.retry(Chain.LIDL)
        runCurrent()
        assertEquals(MarketStatus.OK, m.offers.cache.value.of(Chain.LIDL)?.status)
    }

    @Test
    fun marketTickedOffNoLongerCountsAndAnotherKauflandStoreIsUsed() = runTest {
        val platform = OfferPlatform()
        val m = model(platform)
        m.offers.place.setAddress("10115")
        runCurrent()
        m.offers.place.setChain(Chain.LIDL, false)
        assertEquals(listOf(Chain.ALDI_NORD, Chain.KAUFLAND, Chain.PENNY), m.repo.current.place?.markets)
        m.offers.place.findKauflandStore("20095")
        runCurrent()
        assertEquals("20095", m.repo.current.place?.kauflandPostcode)
        assertTrue(platform.asked.any { it.endsWith("lat=53.55&lng=10.01") })
        assertEquals("Kaufland-Filiale: Kaufland Musterstadt.", m.feedback.value?.message)
    }

    @Test
    fun pennyStoreIsTakenForTheFamilyAndAnotherCanBeChosen() = runTest {
        val platform = OfferPlatform()
        val m = model(platform)
        m.offers.place.setAddress("10115")
        runCurrent()
        assertEquals("530002", m.repo.current.place?.pennyStoreId)
        val penny = OfferCache.load(platform.offerStore).markets.single { it.chain == Chain.PENNY }
        assertEquals(listOf("530002", "530009"), penny.nearby.map { it.id })
        assertTrue(platform.asked.any { "region=15A-08-26" in it })
        platform.asked.clear()
        m.offers.place.choosePennyStore(penny.nearby[1])
        runCurrent()
        assertEquals("530009", m.repo.current.place?.pennyStoreId)
        val chosen = OfferCache.load(platform.offerStore).markets.single { it.chain == Chain.PENNY }
        assertEquals("530009", chosen.storeId)
        assertTrue(platform.asked.any { "region=11B-01-01" in it })
        assertTrue(platform.asked.none { "aldi" in it || "kaufland" in it })
        assertEquals("Penny-Filiale: Penny Süd.", m.feedback.value?.message)
        m.offers.place.setAddress("80331 München")
        assertEquals(null, m.repo.current.place?.pennyStoreId)
    }

    @Test
    fun offerSuggestionCanBeRejectedForTheFamilyAndUndone() = runTest {
        val m = model()
        m.kitchen.recipes.createFromText(gruenkohl)
        m.offers.place.setAddress("10115")
        runCurrent()
        val card = m.offers.suggestions.recipes().single()
        val hit = card.offers.single()
        assertEquals("Grünkohl bei Kaufland −25 %", card.reasons.first())
        m.offers.suggestions.reject(hit)
        assertTrue(m.offers.suggestions.recipes().single().offers.isEmpty())
        m.undo(assertNotNull(m.feedback.value))
        assertEquals(1, m.offers.suggestions.recipes().single().offers.size)
    }

    @Test
    fun planSheetPutsTheDishInThePlanAndTheMarketOnTheList() = runTest {
        val m = model()
        m.kitchen.recipes.createFromText(gruenkohl)
        m.offers.place.setAddress("10115")
        runCurrent()
        m.offers.plan.open(m.offers.suggestions.recipes().single())
        val sheet = assertNotNull(m.offers.plan.sheet.value)
        assertEquals(monday, sheet.day) // next free day
        assertEquals(4, sheet.servings)
        assertTrue(sheet.hasServings)
        m.offers.plan.setServings(2)
        m.offers.plan.setDay(monday.plusDays(2))
        m.offers.plan.confirm()
        assertNull(m.offers.plan.sheet.value)
        val meal = m.repo.current.meals.single()
        assertEquals(2, meal.servings)
        assertEquals(LocalDate(2026, 12, 2), meal.day)
        assertEquals("Grünkohl für 4 Personen: Mi, 2 Personen, 4 Zutaten auf der Liste.", m.feedback.value?.message)
        assertEquals("bei Kaufland, 1,49 €", m.repo.current.shopping.single { it.name == "Grünkohl" }.offer)
        assertEquals(2, m.repo.current.settings.lastServings)
        // Planned, so it is not suggested again.
        assertTrue(m.offers.suggestions.recipes().isEmpty())
    }

    @Test
    fun plannedDishCanBeMovedAndRemoved() = runTest {
        val m = model()
        m.kitchen.recipes.createFromText(gruenkohl)
        m.offers.plan.open(m.offers.suggestions.recipes().single())
        m.offers.plan.confirm()
        val meal = m.repo.current.meals.single()
        m.offers.plan.openPlanned(meal)
        m.offers.plan.setDay(null)
        m.offers.plan.confirm()
        assertEquals("Grünkohl für 4 Personen: ohne Tag.", m.feedback.value?.message)
        assertNull(m.repo.current.meals.single().day)
        m.offers.plan.openPlanned(m.repo.current.meals.single())
        m.offers.plan.removePlanned()
        assertTrue(m.repo.current.meals.single().deleted)
    }

    @Test
    fun backClosesTheSheetFirst() = runTest {
        val m = model()
        m.kitchen.recipes.createFromText(gruenkohl)
        m.offers.plan.open(m.offers.suggestions.recipes().single())
        assertTrue(m.handleBack())
        assertNull(m.offers.plan.sheet.value)
    }

    @Test
    fun seasonSuggestionsWorkWithoutAnyPlace() = runTest {
        val m = model()
        m.kitchen.recipes.createFromText(gruenkohl)
        val card = m.offers.suggestions.recipes().single()
        assertTrue(card.offers.isEmpty())
        assertEquals("Grünkohl hat Saison", card.reasons.first())
    }

    @Test
    fun ingredientSuggestionGoesStraightOnTheList() = runTest {
        val m = model()
        m.offers.place.setAddress("10115")
        runCurrent()
        val s = m.offers.suggestions.ingredients().first { it.entry.name == "Grünkohl" }
        m.offers.suggestions.addIngredient(s)
        val entry = m.repo.current.shopping.single()
        assertEquals("Grünkohl", entry.name)
        assertEquals("bei Kaufland, 1,49 €", entry.offer)
    }

    @Test
    fun offerNotesAndLines() {
        assertEquals("bei Lidl im Angebot", OffersModel.offerNote(Offer(Chain.LIDL, "Spitzkohl")))
        assertEquals("bei Aldi Nord, 0,05 €", OffersModel.offerNote(Offer(Chain.ALDI_NORD, "x", priceCents = 5)))
        assertEquals("Kaufland: noch nicht abgerufen", OffersModel.lineText(Chain.KAUFLAND, null, false))
        assertEquals("Kaufland: wird abgerufen …", OffersModel.lineText(Chain.KAUFLAND, null, true))
    }

    @Test
    fun offersSurviveARestartOfTheApp() = runTest {
        val platform = OfferPlatform()
        val m = model(platform)
        m.offers.place.setAddress("10115")
        runCurrent()
        assertEquals(4, OfferCache.load(platform.offerStore).markets.size)
    }
}

private fun LocalDate.plusDays(n: Int) = LocalDate.fromEpochDays(toEpochDays() + n)
