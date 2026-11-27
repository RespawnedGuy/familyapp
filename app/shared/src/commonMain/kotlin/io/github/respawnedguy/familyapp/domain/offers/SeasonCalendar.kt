package io.github.respawnedguy.familyapp.domain.offers

import io.github.respawnedguy.familyapp.domain.recipe.IngredientKey

/** How a vegetable or fruit is available in a month (docs/angebote-und-vorschlaege.md "Saisonkalender"). */
@Suppress("MagicNumber") // weights
enum class SeasonLevel(val weight: Double) {
    /** Harvest from German fields or protected cultivation. */
    SEASON(1.0),

    /** German produce from storage (carrots, apples, potatoes in winter). Counts half. */
    STORED(0.5),
}

/**
 * Fixed season table for Germany. Own compilation of harvest months (facts, not the BZfE graphic,
 * which is CC BY-ND / © BLE); checked against the BZfE poster before release (concept, Prüfung 3).
 * Always-available produce (mushrooms) and tropical fruit are left out on purpose: they are no signal.
 */
object SeasonCalendar {

    data class Entry(val name: String, val season: Set<Int>, val stored: Set<Int> = emptySet()) {
        /** Same key as a recipe line with this name gets from [IngredientKey]. */
        val key: String = IngredientKey.of(name)

        fun level(month: Int): SeasonLevel? = when (month) {
            in season -> SeasonLevel.SEASON
            in stored -> SeasonLevel.STORED
            else -> null
        }
    }

    private const val DECEMBER = 12

    /** Months [from] to [to], over the turn of the year when [to] is smaller ("Okt bis Mär"). */
    private fun m(from: Int, to: Int): Set<Int> =
        if (from <= to) (from..to).toSet() else ((from..DECEMBER) + (1..to)).toSet()

    @Suppress("MagicNumber") // months
    val ENTRIES: List<Entry> = listOf(
        // Gemüse
        Entry("Bärlauch", m(3, 5)),
        Entry("Blumenkohl", m(6, 10)),
        Entry("Bohnen", m(6, 9)),
        Entry("Brokkoli", m(6, 10)),
        Entry("Chinakohl", m(7, 11)),
        Entry("Eisbergsalat", m(6, 10)),
        Entry("Erbsen", m(6, 8)),
        Entry("Feldsalat", m(10, 3)),
        Entry("Fenchel", m(6, 10)),
        Entry("Grünkohl", m(11, 2)),
        Entry("Gurke", m(5, 9)),
        Entry("Kartoffeln", m(6, 10), m(11, 5)),
        Entry("Kohlrabi", m(5, 10)),
        Entry("Kopfsalat", m(5, 10)),
        Entry("Kürbis", m(8, 10), m(11, 2)),
        Entry("Lauch", m(7, 3)),
        Entry("Lauchzwiebel", m(5, 10)),
        Entry("Mais", m(8, 10)),
        Entry("Mangold", m(6, 10)),
        Entry("Möhren", m(6, 11), m(12, 4)),
        Entry("Paprika", m(7, 9)),
        Entry("Pastinake", m(10, 3)),
        Entry("Radieschen", m(4, 9)),
        Entry("Rhabarber", m(4, 6)),
        Entry("Rosenkohl", m(10, 2)),
        Entry("Rote Bete", m(7, 10), m(11, 3)),
        Entry("Rotkohl", m(7, 11), m(12, 3)),
        Entry("Schwarzwurzel", m(10, 3)),
        Entry("Sellerie", m(9, 11), m(12, 3)),
        Entry("Spargel", m(4, 6)),
        Entry("Spinat", setOf(3, 4, 5, 9, 10, 11)),
        Entry("Spitzkohl", m(5, 11)),
        Entry("Staudensellerie", m(7, 10)),
        Entry("Steckrübe", m(10, 12), m(1, 3)),
        Entry("Tomaten", m(7, 10)),
        Entry("Weißkohl", m(6, 11), m(12, 4)),
        Entry("Wirsing", m(6, 12)),
        Entry("Zucchini", m(6, 9)),
        Entry("Zwiebeln", m(7, 10), m(11, 4)),
        // Obst
        Entry("Äpfel", m(8, 11), m(12, 5)),
        Entry("Aprikosen", m(7, 8)),
        Entry("Birnen", m(8, 10), m(11, 2)),
        Entry("Brombeeren", m(8, 9)),
        Entry("Erdbeeren", m(5, 8)),
        Entry("Heidelbeeren", m(7, 9)),
        Entry("Himbeeren", m(6, 9)),
        Entry("Johannisbeeren", m(6, 8)),
        Entry("Kirschen", m(6, 8)),
        Entry("Pflaumen", m(7, 9)),
        Entry("Quitten", m(9, 11)),
        Entry("Stachelbeeren", m(6, 8)),
        Entry("Weintrauben", m(9, 10)),
    )

    /**
     * Keys that end like a table entry but are something else: dried or canned pulses, herbs and
     * garlic ("Knoblauch" ends with "lauch"). Never counted as season produce.
     */
    private val NOT_PRODUCE = setOf(
        "Knoblauch", "Schnittlauch", "Kichererbsen", "Kidneybohnen", "Sojabohnen", "Kaffeebohnen",
        "Kartoffelpüree", "Paprikapulver",
    ).map(IngredientKey::of).toSet()

    /** Shortest key that may be matched at the end of a longer word ("Hokkaidokürbis" -> "kürbis"). */
    private const val MIN_SUFFIX = 4

    /**
     * The table entry for an ingredient key: equal, or the key ends with the entry's key
     * (compound words). The longest match wins, so "staudenselleri" finds Staudensellerie, not Sellerie.
     */
    fun entryFor(key: String): Entry? {
        if (key in NOT_PRODUCE) return null
        return ENTRIES.filter { e -> key == e.key || (e.key.length >= MIN_SUFFIX && key.endsWith(e.key)) }
            .maxByOrNull { it.key.length }
    }

    fun level(key: String, month: Int): SeasonLevel? = entryFor(key)?.level(month)

    /** Everything with harvest season in [month], in table order. */
    fun inSeason(month: Int): List<Entry> = ENTRIES.filter { it.level(month) == SeasonLevel.SEASON }
}
