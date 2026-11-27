package io.github.respawnedguy.familyapp.data.offers

import io.github.respawnedguy.familyapp.data.TextStore
import io.github.respawnedguy.familyapp.domain.offers.Chain
import io.github.respawnedguy.familyapp.domain.offers.Offer
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.DatePeriod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How the last read of a market went. */
@Serializable
enum class MarketStatus { OK, REFUSED, FAILED }

/** The last read of one market, kept on this phone only. */
@Serializable
data class MarketSnapshot(
    val chain: Chain,
    val fetchedMs: Long,
    val fetchedOn: LocalDate,
    val status: MarketStatus,
    val offers: List<Offer> = emptyList(),
    /** Kaufland: the store the offers belong to. A different store in the place means: read again. */
    val storeId: String? = null,
    /** HTTP code of a refusal, or the reason of a failure, for the diagnosis line. */
    val detail: String? = null,
)

/**
 * Offers of all markets on this phone (docs/angebote-und-vorschlaege.md "Wann wird abgerufen?").
 * Not synced and not in the backup: worthless after a week, and each phone reads for itself.
 */
@Serializable
data class OfferCache(val markets: List<MarketSnapshot> = emptyList()) {

    fun of(chain: Chain): MarketSnapshot? = markets.firstOrNull { it.chain == chain }

    fun with(snapshot: MarketSnapshot) = OfferCache(markets.filter { it.chain != snapshot.chain } + snapshot)

    fun without(chain: Chain) = OfferCache(markets.filter { it.chain != chain })

    /** Offers of the markets the family chose (a market that was ticked off no longer counts). */
    fun offers(chosen: Collection<Chain>): List<Offer> =
        markets.filter { it.chain in chosen && it.status == MarketStatus.OK }.flatMap { it.offers }

    companion object {
        /** Offers older than this are read again. */
        const val MAX_AGE_DAYS = 3

        /** A failed read (no network, server error) is tried again on an app start after this. */
        const val FAILED_RETRY_MS = 60L * 60 * 1000

        /**
         * True if [chain] should be read now: never read, older than [MAX_AGE_DAYS], from an earlier
         * week (offers change on Monday), for another Kaufland store, or failed more than an hour ago.
         * A market that refused stays off until the person asks again (Grundsatz, point 4).
         */
        fun due(snapshot: MarketSnapshot?, today: LocalDate, nowMs: Long, storeId: String? = null): Boolean = when {
            snapshot == null -> true
            snapshot.status == MarketStatus.REFUSED -> false
            snapshot.status == MarketStatus.FAILED -> nowMs - snapshot.fetchedMs >= FAILED_RETRY_MS
            storeId != null && snapshot.storeId != storeId -> true
            else -> snapshot.fetchedOn.daysUntil(today) >= MAX_AGE_DAYS || monday(snapshot.fetchedOn) != monday(today)
        }

        private fun monday(day: LocalDate): LocalDate =
            day.minus(DatePeriod(days = day.dayOfWeek.ordinal - DayOfWeek.MONDAY.ordinal))

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        /** Unreadable or from another version: start empty, the next read fills it again. */
        fun load(store: TextStore): OfferCache =
            store.read()?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: OfferCache()

        fun save(store: TextStore, cache: OfferCache) {
            runCatching { store.write(json.encodeToString(serializer(), cache)) }
        }
    }
}
