package io.github.respawnedguy.familyapp.data.offers

import io.github.respawnedguy.familyapp.data.FamilyRepository
import io.github.respawnedguy.familyapp.data.KitchenBefore
import io.github.respawnedguy.familyapp.data.Undo
import io.github.respawnedguy.familyapp.domain.offers.Chain
import io.github.respawnedguy.familyapp.domain.offers.Postcodes
import io.github.respawnedguy.familyapp.domain.offers.ShoppingPlace

/**
 * The family's shopping place (Einstellungen › Einkauf). One synced record; every change is a new
 * version with a new stamp, so the newest entry wins on every phone.
 */
class ShoppingPlaceRepository internal constructor(private val repo: FamilyRepository) {

    val place: ShoppingPlace? get() = repo.current.place

    private fun save(block: (ShoppingPlace?) -> ShoppingPlace?): Undo {
        val before = place
        val next = block(before)?.takeIf { it != before } ?: return Undo(emptyMap())
        val stamped = next.copy(changedBy = repo.current.selfId, stamp = repo.tick())
        repo.edit { d -> d.copy(places = d.places.filter { it.id != ShoppingPlace.ID } + stamped) }
        return Undo(emptyMap(), kitchen = KitchenBefore(places = mapOf(ShoppingPlace.ID to before)))
    }

    /**
     * Address or postcode as typed. Returns false when it holds no German postcode. A new postcode
     * forgets the Kaufland store (it is looked up again); a first place gets the suggested markets.
     */
    fun setAddress(text: String): Boolean {
        val postcode = Postcodes.extract(text) ?: return false
        val label = text.trim().replace(WHITESPACE, " ").take(ShoppingPlace.MAX_LABEL)
        save { old ->
            when {
                old == null -> ShoppingPlace(
                    label = label,
                    postcode = postcode,
                    chains = ShoppingPlace.suggestedChains(postcode),
                    stamp = repo.tick(),
                )
                old.postcode == postcode -> old.copy(label = label)
                else -> old.copy(
                    label = label,
                    postcode = postcode,
                    kauflandStoreId = null,
                    kauflandStoreName = null,
                    kauflandPostcode = null,
                )
            }
        }
        return true
    }

    fun setChain(chain: Chain, on: Boolean) {
        save { old ->
            old?.copy(chains = if (on) (old.chains + chain.name).distinct() else old.chains - chain.name)
        }
    }

    /** The store Kaufland's store search found; [forPostcode] when the person asked for another one. */
    fun setKauflandStore(id: String, name: String, forPostcode: String? = null) {
        save { old ->
            old?.copy(
                kauflandStoreId = id,
                kauflandStoreName = name.take(ShoppingPlace.MAX_LABEL),
                kauflandPostcode = forPostcode ?: old.kauflandPostcode,
            )
        }
    }

    /** "Passt nicht": this offer title is not this ingredient, for the whole family. */
    fun reject(rejectionKey: String): Undo = save { old ->
        old?.takeIf { rejectionKey !in it.rejected }
            ?.copy(rejected = (old.rejected + rejectionKey).takeLast(ShoppingPlace.MAX_REJECTED))
    }

    /** Takes a "Passt nicht" back (list in Einstellungen › Einkauf). */
    fun unreject(rejectionKey: String) {
        save { old -> old?.copy(rejected = old.rejected - rejectionKey) }
    }

    private companion object {
        val WHITESPACE = Regex("""\s+""")
    }
}
