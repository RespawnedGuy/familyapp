package familyapp.recipe

/**
 * Maps an ingredient name to a comparison key ("Rote Zwiebeln, fein gewürfelt" -> "zwiebel").
 * Same idea as suchwoerter()/_normalisierter_zutaten_schluessel() in the preisvergleich repo:
 * drop adjectives and preparation words, take the head noun, cut plural endings, apply synonyms.
 */
object IngredientKey {

    private val ADJECTIVES = setOf(
        "frisch", "frische", "frischer", "frischen", "rote", "roter", "roten", "rot", "gelbe", "gelber", "gelb", "grüne", "grüner", "grün",
        "weiße", "weißer", "weiss", "weisse", "schwarze", "schwarzer", "große", "großer", "groß", "grosse", "kleine", "kleiner", "klein",
        "mittelgroße", "mittelgroßer", "gehackte", "gehackt", "gehackter", "geriebene", "gerieben", "geriebener", "gewürfelte", "gewürfelt",
        "getrocknete", "getrocknet", "gemahlene", "gemahlen", "gemahlener", "zerlassene", "weiche", "weich", "kalte", "kalt", "warme", "warm",
        "reife", "reif", "bio", "tk", "tiefgekühlte", "passierte", "stückige", "geschälte", "geschält", "gekochte", "gekocht", "fein", "grob",
        "optional", "etwas", "ca", "circa", "evtl", "nach", "belieben", "geschmack", "zum", "zur", "für", "und", "oder", "mit", "ohne", "der", "die", "das",
        "extra", "natives", "nativ", "light", "mehlig", "mehligkochende", "festkochende", "vorwiegend", "italienische", "griechischer", "griechische",
    )

    private val SYNONYMS = mapOf(
        "knoblauchzehe" to "knoblauch", "zehe" to "knoblauch", "hack" to "hackfleisch", "gehacktes" to "hackfleisch", "rinderhack" to "hackfleisch",
        "paradeiser" to "tomate", "kirschtomate" to "tomate", "cocktailtomate" to "tomate", "cherrytomate" to "tomate", "datteltomate" to "tomate",
        "frühlingszwiebel" to "lauchzwiebel", "schalotte" to "zwiebel", "olivenöl" to "öl", "rapsöl" to "öl", "sonnenblumenöl" to "öl", "pflanzenöl" to "öl",
        "schlagsahne" to "sahne", "kochsahne" to "sahne", "obers" to "sahne", "parmesan" to "parmesan", "hartweizengrieß" to "grieß",
        "weizenmehl" to "mehl", "dinkelmehl" to "mehl", "rohrzucker" to "zucker", "puderzucker" to "puderzucker", "ei" to "ei", "eier" to "ei",
        "chiliflocken" to "chili", "chilischote" to "chili", "petersilie" to "petersilie", "spaghetti" to "spaghetti", "nudel" to "nudeln", "pasta" to "nudeln",
    )

    /** Synonyms with both sides reduced to their stem, so "Kirschtomaten" and "Kirschtomate" hit the same entry. */
    private val STEMMED_SYNONYMS: Map<String, String> = SYNONYMS.entries.associate { (k, v) -> stem(k) to stem(v) }

    fun of(name: String): String {
        val words = name.lowercase()
            .replace(Regex("""\(.*?\)"""), " ")
            .split(',').first()
            .split(Regex("""\s+(?:und|oder|&)\s+""")).first() // "Salz und Pfeffer" -> "salz"
            .split(Regex("""[^\p{L}\-]+"""))
            .map { it.trim('-') }
            .filter { it.length > 1 && it !in ADJECTIVES }
        val head = words.lastOrNull()?.substringAfterLast('-') ?: return name.lowercase().trim()
        val s = stem(head)
        return STEMMED_SYNONYMS[s] ?: s
    }

    private fun stem(w: String): String {
        for (suffix in listOf("en", "n", "e", "s")) {
            if (w.endsWith(suffix) && w.length - suffix.length >= 4) return w.dropLast(suffix.length)
        }
        return w
    }
}
