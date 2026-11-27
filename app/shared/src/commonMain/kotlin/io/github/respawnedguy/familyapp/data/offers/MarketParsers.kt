package io.github.respawnedguy.familyapp.data.offers

import io.github.respawnedguy.familyapp.domain.offers.Offer

/**
 * Turns what the markets' own pages deliver into [Offer]s (docs/angebote-und-vorschlaege.md
 * "Rohmaterial", structures as read on 27.11.2026). Pure functions over text, tested with small
 * hand-written answers of the same shape. Everything here is untrusted input (threat model AN-2):
 * unknown shapes give an empty list, never an exception, and only plain text fields are taken
 * (no links, no images). One parser object per market behind this entry point.
 */
object MarketParsers {

    /** One page of Aldi Süd's weekly offers and how many there are in total. */
    data class Page(val offers: List<Offer>, val total: Int)

    /** Kaufland offer with its article number, for filtering by store. */
    data class KauflandOffer(val klNr: String?, val offer: Offer)

    /** A Kaufland store: number and name. */
    data class Store(val id: String, val name: String)

    fun aldiSued(text: String): Page = AldiParsers.aldiSued(text)

    fun aldiNord(html: String): List<Offer> = AldiParsers.aldiNord(html)

    fun kaufland(html: String): List<KauflandOffer> = KauflandParsers.offers(html)

    /** Article numbers valid in one store (/.kloffers.storeName=…json); null if the answer is no list. */
    fun kauflandStoreArticles(text: String): Set<String>? = KauflandParsers.storeArticles(text)

    /** Nearest Kaufland store to a point (/.klstorebygeo.json), or null. */
    fun kauflandStore(text: String): Store? = KauflandParsers.store(text)

    /** Ids of the current "Aktionsprospekt" leaflets linked on lidl.de's leaflet page. */
    fun lidlFlyerIds(html: String): List<String> = LidlParsers.flyerIds(html)

    /**
     * Food offers of one leaflet. Lidl lists food only as page images; the leaflet marks fresh
     * products with a recipe link ("…alle-rezepte?q=Spitzkohl"). That word becomes the offer, without
     * price, valid for the leaflet's offer dates.
     */
    fun lidlFlyer(text: String): List<Offer> = LidlParsers.flyer(text)

    internal fun urlDecode(s: String): String = LidlParsers.urlDecode(s)
}
