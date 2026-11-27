package io.github.respawnedguy.familyapp.presentation

import io.github.respawnedguy.familyapp.data.FamilyRepository
import io.github.respawnedguy.familyapp.data.InMemoryTextStore
import io.github.respawnedguy.familyapp.data.Undo
import io.github.respawnedguy.familyapp.data.offers.MarketFetch
import io.github.respawnedguy.familyapp.data.offers.MarketHttp
import io.github.respawnedguy.familyapp.data.offers.MarketParsers
import io.github.respawnedguy.familyapp.data.offers.MarketResult
import io.github.respawnedguy.familyapp.data.offers.MarketSnapshot
import io.github.respawnedguy.familyapp.data.offers.MarketStatus
import io.github.respawnedguy.familyapp.data.offers.NearbyStore
import io.github.respawnedguy.familyapp.data.offers.OfferCache
import io.github.respawnedguy.familyapp.domain.offers.Chain
import io.github.respawnedguy.familyapp.domain.offers.Offer
import io.github.respawnedguy.familyapp.domain.offers.Postcodes
import io.github.respawnedguy.familyapp.domain.offers.ShoppingPlace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

/** One line per chosen market in Einstellungen › Einkauf ("Aldi Süd: 118 Angebote"). */
data class MarketLine(
    val chain: Chain,
    val text: String,
    val refused: Boolean,
    val busy: Boolean,
    val canRetry: Boolean = false,
)

/**
 * Offers, season and meal suggestions (docs/angebote-und-vorschlaege.md): reads the markets when the
 * app opens and the offers are old. The place lives in [place], the cards in [suggestions], the
 * sheet "Wann? / Für wie viele?" in [plan]. Nothing runs in the background and nothing notifies
 * (premise of 26.11.2026).
 */
class OffersModel internal constructor(
    private val repo: FamilyRepository,
    private val platform: OfferSources,
    private val background: Background,
    kitchen: KitchenModel,
    show: (String, Undo?) -> Unit,
) {
    private val store = platform.offerStore ?: InMemoryTextStore()

    private val _cache = MutableStateFlow(OfferCache.load(store))
    val cache: StateFlow<OfferCache> = _cache.asStateFlow()

    private val _busy = MutableStateFlow<Set<Chain>>(emptySet())
    val busy: StateFlow<Set<Chain>> = _busy.asStateFlow()

    /** Offers are old but the phone is on mobile data: they wait for WLAN (or "Jetzt abrufen"). */
    private val _waitingForWifi = MutableStateFlow(false)
    val waitingForWifi: StateFlow<Boolean> = _waitingForWifi.asStateFlow()

    val place = PlaceActions(repo, this, platform.marketHttp, background, show)
    val suggestions = SuggestionActions(repo, show) { _cache.value }
    val plan = PlanActions(repo, kitchen, show)

    /** On app start: reads every chosen market whose offers are due, only on WLAN. */
    fun refreshIfDue() = refresh(manual = false)

    /** "Jetzt abrufen" in the settings: also on mobile data and before the offers are old. Refusals stay. */
    fun refreshNow() = refresh(manual = true)

    /** One market again at once, for example after another store was chosen; the old offers stay until then. */
    fun refreshOne(chain: Chain) = refresh(manual = true, only = chain)

    /** "Nochmal versuchen" for a market that refused or failed: the same honest request once more. */
    fun retry(chain: Chain) {
        update { it.without(chain) }
        refresh(manual = true, only = chain)
    }

    private fun refresh(manual: Boolean, only: Chain? = null) {
        val place = repo.current.place
        val http = platform.marketHttp
        val due = if (place == null || http == null) emptyList() else dueMarkets(place, manual, only)
        if (due.isEmpty()) return
        val offline = !manual && !platform.unmeteredNetwork()
        _waitingForWifi.value = offline
        if (!offline && place != null && http != null) start(place, http, due)
    }

    private fun start(place: ShoppingPlace, http: MarketHttp, due: List<Chain>) {
        val point = Postcodes.find(place.kauflandPostcode ?: place.postcode)
        val fetch = MarketFetch(http)
        _busy.value = _busy.value + due
        due.forEach { chain ->
            background.scope.launch {
                val result = try {
                    withContext(background.io) { fetch.fetch(chain, point, knownStore(place, chain)) }
                } finally {
                    _busy.value = _busy.value - chain
                }
                record(result)
            }
        }
    }

    private fun dueMarkets(place: ShoppingPlace, manual: Boolean, only: Chain?): List<Chain> {
        val now = repo.now()
        val today = repo.day()
        return place.markets.filter { c ->
            val snapshot = _cache.value.of(c)
            val wanted = if (manual) {
                snapshot?.status != MarketStatus.REFUSED
            } else {
                OfferCache.due(snapshot, today, now, knownStore(place, c)?.id)
            }
            (only == null || c == only) && c !in _busy.value && wanted
        }
    }

    /** Keeps the result on this phone; a store found by the search becomes the family's (Kaufland, Penny). */
    internal fun record(result: MarketResult) {
        update { it.with(snapshotOf(result, repo.now(), repo.day())) }
        val found = (result as? MarketResult.Ok)?.store ?: return
        val place = repo.current.place ?: return
        when {
            result.chain == Chain.KAUFLAND && place.kauflandStoreId == null ->
                repo.placeStore.setKauflandStore(found.id, found.name)
            result.chain == Chain.PENNY && place.pennyStoreId == null ->
                repo.placeStore.setPennyStore(found.id, found.name)
        }
    }

    private fun update(block: (OfferCache) -> OfferCache) {
        _cache.value = block(_cache.value)
        OfferCache.save(store, _cache.value)
    }

    /** The diagnosis line of each chosen market (Prüfung 1 reads it). */
    fun marketLines(cache: OfferCache = _cache.value, busy: Set<Chain> = _busy.value): List<MarketLine> {
        val place = repo.current.place ?: return emptyList()
        return place.markets.map { c ->
            val s = cache.of(c)
            val reading = c in busy
            MarketLine(
                c, lineText(c, s, reading),
                refused = s?.status == MarketStatus.REFUSED,
                busy = reading,
                canRetry = !reading && s != null && s.status != MarketStatus.OK,
            )
        }
    }

    companion object {
        /** The store already chosen for [chain], which the adapter uses instead of the nearest. */
        internal fun knownStore(place: ShoppingPlace, chain: Chain): MarketParsers.Store? = when (chain) {
            Chain.KAUFLAND -> place.kauflandStoreId?.let { MarketParsers.Store(it, place.kauflandStoreName ?: it) }
            Chain.PENNY -> place.pennyStoreId?.let { MarketParsers.Store(it, place.pennyStoreName ?: it) }
            else -> null
        }

        internal fun snapshotOf(result: MarketResult, nowMs: Long, today: LocalDate): MarketSnapshot = when (result) {
            is MarketResult.Ok ->
                MarketSnapshot(
                    result.chain, nowMs, today, MarketStatus.OK, result.offers, result.store?.id, result.nearby,
                )
            is MarketResult.Refused ->
                MarketSnapshot(result.chain, nowMs, today, MarketStatus.REFUSED, detail = "HTTP ${result.code}")
            is MarketResult.Failed ->
                MarketSnapshot(result.chain, nowMs, today, MarketStatus.FAILED, detail = result.reason)
        }

        /** "Aldi Süd: 118 Angebote", "Kaufland lässt sich gerade nicht automatisch lesen" … */
        internal fun lineText(chain: Chain, s: MarketSnapshot?, busy: Boolean): String = when {
            busy -> "${chain.label}: wird abgerufen …"
            s == null -> "${chain.label}: noch nicht abgerufen"
            s.status == MarketStatus.REFUSED ->
                "${chain.label} lässt sich gerade nicht automatisch lesen (${s.detail})."
            s.status == MarketStatus.FAILED ->
                "${chain.label}: Abruf fehlgeschlagen (${s.detail}), neuer Versuch später."
            chain == Chain.LIDL -> "${chain.label}: ${s.offers.size} Lebensmittel im Angebot (ohne Preise)"
            else -> "${chain.label}: ${s.offers.size} Angebote"
        }

        /** "bei Aldi Süd, 1,69 €" or "bei Lidl im Angebot". */
        fun offerNote(offer: Offer): String {
            val price = offer.priceCents?.let { c -> ", ${c / CENTS},${(c % CENTS).toString().padStart(2, '0')} €" }
            return "bei ${offer.chain.label}" + (price ?: " im Angebot")
        }

        private const val CENTS = 100
    }
}

/** Einstellungen › Einkauf: address, markets, another Kaufland store, taking back a "Passt nicht". */
class PlaceActions internal constructor(
    private val repo: FamilyRepository,
    private val offers: OffersModel,
    private val http: MarketHttp?,
    private val background: Background,
    private val show: (String, Undo?) -> Unit,
) {
    private val places get() = repo.placeStore

    /** Address or postcode as typed; a new place reads its markets at once (on WLAN). */
    fun setAddress(text: String) {
        if (places.setAddress(text)) offers.refreshIfDue() else show(NO_POSTCODE, null)
    }

    fun setChain(chain: Chain, on: Boolean) {
        places.setChain(chain, on)
        if (on) offers.refreshIfDue()
    }

    /** "Andere Filiale": Kaufland's store search for another postcode (no store list: robots.txt forbids it). */
    fun findKauflandStore(postcodeText: String) {
        val postcode = Postcodes.extract(postcodeText)
        val point = postcode?.let(Postcodes::find)
        if (point == null || http == null) {
            show(NO_POSTCODE, null)
            return
        }
        background.scope.launch {
            val result = withContext(background.io) { MarketFetch(http).fetch(Chain.KAUFLAND, point) }
            offers.record(result)
            val store = (result as? MarketResult.Ok)?.store
            if (store != null) places.setKauflandStore(store.id, store.name, postcode)
            show(store?.let { "Kaufland-Filiale: ${it.name}." } ?: NO_STORE, null)
        }
    }

    /** "Weitere Filialen": another of the nearest Penny stores; its offers are read at once (on WLAN). */
    fun choosePennyStore(store: NearbyStore) {
        places.setPennyStore(store.id, store.name)
        offers.refreshOne(Chain.PENNY)
        show("Penny-Filiale: ${store.name}.", null)
    }

    fun unreject(rejectionKey: String) = places.unreject(rejectionKey)

    companion object {
        const val NO_POSTCODE = "Keine deutsche Postleitzahl gefunden. Bitte mit PLZ eingeben, z. B. „10115 Berlin“."
        const val NO_STORE = "Keine Kaufland-Filiale gefunden. Später noch einmal versuchen."
    }
}
