package io.github.respawnedguy.familyapp.domain.offers

import io.github.respawnedguy.familyapp.domain.Stamp
import kotlinx.serialization.Serializable

/**
 * Where the family shops (Einstellungen › Einkauf, docs/angebote-und-vorschlaege.md). One record per
 * family with the fixed id [ID], synced like recipes (newest stamp wins), so only one person enters it.
 * Markets are stored by name, not as enum, so an older app that meets a market it does not know
 * still reads the family data (it just skips that market).
 */
@Serializable
data class ShoppingPlace(
    val id: String = ID,
    /** The address as typed, so it is recognisable; only [postcode] is ever used. */
    val label: String,
    val postcode: String,
    val chains: List<String> = emptyList(),
    /** Kaufland store found for the postcode (or chosen via another postcode); null: look it up. */
    val kauflandStoreId: String? = null,
    val kauflandStoreName: String? = null,
    /** Postcode the Kaufland store was looked up for, when it differs from [postcode]. */
    val kauflandPostcode: String? = null,
    /** Penny store chosen from the nearest ones (its "wwIdent"); null: the nearest is taken. */
    val pennyStoreId: String? = null,
    val pennyStoreName: String? = null,
    /** "Passt nicht" keys ([OfferMatch.rejectionKey]): this offer word is not this ingredient. */
    val rejected: List<String> = emptyList(),
    val changedBy: String? = null,
    val stamp: Stamp,
) {
    val markets: List<Chain> get() = chains.mapNotNull { n -> Chain.entries.firstOrNull { it.name == n } }

    fun has(chain: Chain): Boolean = chain.name in chains

    companion object {
        const val ID = "shopping-place"
        const val MAX_LABEL = 120

        /** "Passt nicht" kept per family; the oldest go first (threat model AN-6). */
        const val MAX_REJECTED = 500

        /**
         * Markets ticked for a new place. Lidl, Kaufland and Penny are everywhere. Aldi is split in two
         * companies; a rough guess by the first digit (0–3 north, 6–9 south) is only a suggestion the
         * person sees and changes, and 4/5 (NRW, both) stays open.
         */
        @Suppress("MagicNumber") // postcode regions
        fun suggestedChains(postcode: String): List<String> {
            val aldi = when (postcode.firstOrNull()?.digitToIntOrNull()) {
                in 0..3 -> Chain.ALDI_NORD
                in 6..9 -> Chain.ALDI_SUED
                else -> null
            }
            return listOfNotNull(aldi, Chain.KAUFLAND, Chain.LIDL, Chain.PENNY).map { it.name }
        }
    }
}
