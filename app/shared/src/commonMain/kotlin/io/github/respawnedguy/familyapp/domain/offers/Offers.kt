package io.github.respawnedguy.familyapp.domain.offers

import io.github.respawnedguy.familyapp.domain.recipe.IngredientKey
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * Markets the app may read (docs/angebote-und-vorschlaege.md, docs/angebote-maerkte.md). Only markets
 * that allow automated reading, without getting past bot protection and without terms that forbid it,
 * are listed (Nils, 27.11.2026); REWE and Edeka are not.
 */
@Serializable
enum class Chain(val label: String) {
    ALDI_SUED("Aldi Süd"),
    ALDI_NORD("Aldi Nord"),
    KAUFLAND("Kaufland"),
    LIDL("Lidl"),
    PENNY("Penny"),
    NORMA("Norma"),
    NETTO("Netto"),
    DENNS("denn's BioMarkt"),
}

/**
 * One weekly offer as a market's adapter reads it. Prices in cents; Lidl food offers have no price
 * (they only exist as leaflet keywords). Kept on the device only, never synced or backed up.
 */
@Serializable
data class Offer(
    val chain: Chain,
    val title: String,
    val priceCents: Int? = null,
    val oldPriceCents: Int? = null,
    val savingPercent: Int? = null,
    val validFrom: LocalDate? = null,
    val validUntil: LocalDate? = null,
) {
    /** Valid on at least one day from [from] to [to]; offers without dates count as this week's. */
    fun validBetween(from: LocalDate, to: LocalDate): Boolean =
        (validFrom == null || validFrom <= to) && (validUntil == null || validUntil >= from)
}

/**
 * Offer title → ingredient key (concept "Abgleich Angebot → Zutat"). Product names are built
 * differently from recipe lines ("Deutsches Rinderhackfleisch 500 g"), so every word is checked:
 * equal to the key, or a compound ending in it. Processed products ("Erdbeer-Joghurt") never count
 * as the fresh ingredient, like the processing malus in the preisvergleich repo.
 */
object OfferMatch {

    /** Words that make a product something else than the fresh ingredient it names. */
    private val PROCESSED = setOf(
        "joghurt", "jogurt", "quark", "pudding", "dessert", "saft", "nektar", "schorle", "limonade", "smoothie",
        "drink", "tee", "sirup", "likör", "chips", "eis", "fruchtgummi", "aufstrich", "marmelade", "konfitüre",
        "fruchtaufstrich", "riegel", "kuchen", "torte", "keks", "kekse", "schokolade", "müsli", "bonbon",
        "sauce", "soße", "dressing", "suppe", "püree", "pulver", "gewürz", "aroma", "pommes", "kroketten",
        "rösti", "gnocchi", "chutney", "pesto", "ketchup", "mark", "creme", "kerze",
        "dünger", "pflanze", "pflanzen", "samen", "saatgut", "topf", "tierfutter", "katzenfutter", "hundefutter",
    ).map(IngredientKey::stem).toSet()

    /** Words that are amounts and packaging, not the product. */
    private val PACKAGING = setOf(
        "g", "kg", "ml", "l", "stück", "stk", "packung", "packg", "pack", "beutel", "schale", "dose", "glas",
        "becher", "flasche", "bund", "netz", "kiste", "je", "ca", "x", "er", "klasse", "ursprung", "herkunft",
        "deutschland", "spanien", "italien", "niederlande", "lose",
    )

    /** Shortest key matched at the end of a compound ("Rinderhackfleisch" ends with "hackfleisch"). */
    private const val MIN_SUFFIX = 4

    private val SPLIT = Regex("""[^\p{L}]+""")

    /** Keys of the title's words, packaging removed ("Eisbergsalat, Stück" -> ["eisbergsalat"]). */
    fun words(title: String): List<String> =
        title.lowercase().split(SPLIT)
            .filter { it.length > 1 && it !in PACKAGING }
            .map(IngredientKey::of)
            .filter { it.isNotBlank() }

    fun isProcessed(title: String): Boolean =
        words(title).any { w -> PROCESSED.any { w == it || (it.length >= MIN_SUFFIX && w.endsWith(it)) } }

    /** True if the offer is the fresh ingredient [key] (and nothing a family marked as wrong). */
    fun matches(offer: Offer, key: String, rejected: Set<String> = emptySet()): Boolean {
        val excluded = key.isBlank() || rejectionKey(offer.title, key) in rejected || isProcessed(offer.title)
        return !excluded && words(offer.title).any { w -> w == key || (key.length >= MIN_SUFFIX && w.endsWith(key)) }
    }

    /** Offers for [key], biggest saving first, offers without a price last. */
    fun offersFor(key: String, offers: List<Offer>, rejected: Set<String> = emptySet()): List<Offer> =
        offers.filter { matches(it, key, rejected) }
            .sortedWith(compareByDescending<Offer> { it.savingPercent ?: -1 }.thenBy { it.priceCents ?: Int.MAX_VALUE })

    /**
     * What "Passt nicht" remembers: this product title is not this ingredient. Titles are compared
     * by their words, so "Eisbergsalat, Stück" and "Eisbergsalat Stück" are the same.
     */
    fun rejectionKey(title: String, key: String): String = "$key|${words(title).joinToString(" ")}"
}
