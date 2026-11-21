package familyapp.recipe

/** One ingredient line split into amount, unit, name and note. Amount and unit stay null when the text has none. */
data class Ingredient(
    val raw: String,
    val amount: Double?,
    val amountMax: Double?,
    val unit: String?,
    val name: String,
    val note: String?,
)

/**
 * Deterministic parser for German ingredient lines such as "250 g Kirschtomaten, halbiert",
 * "1/2 TL Salz", "2-3 Zehen Knoblauch" or "Salz und Pfeffer".
 */
object IngredientLineParser {

    /** Canonical unit -> spellings (lowercase, without trailing dot). Longest spellings are tried first. */
    private val UNITS: Map<String, List<String>> = linkedMapOf(
        "g" to listOf("g", "gr", "gramm"),
        "kg" to listOf("kg", "kilo", "kilogramm"),
        "mg" to listOf("mg"),
        "ml" to listOf("ml", "milliliter"),
        "cl" to listOf("cl"),
        "l" to listOf("l", "liter"),
        "EL" to listOf("el", "esslöffel", "eßlöffel", "tbsp"),
        "TL" to listOf("tl", "teelöffel", "tsp"),
        "Msp" to listOf("msp", "messerspitze", "messerspitzen"),
        "Prise" to listOf("prise", "prisen"),
        "Bund" to listOf("bund", "bd"),
        "Zehe" to listOf("zehe", "zehen"),
        "Dose" to listOf("dose", "dosen"),
        "Packung" to listOf("packung", "packungen", "pck", "pkg", "päckchen", "pack"),
        "Becher" to listOf("becher"),
        "Glas" to listOf("glas", "gläser"),
        "Stück" to listOf("stück", "stk", "st"),
        "Scheibe" to listOf("scheibe", "scheiben"),
        "Tasse" to listOf("tasse", "tassen", "cup", "cups"),
        "Handvoll" to listOf("handvoll", "hand voll"),
        "Zweig" to listOf("zweig", "zweige"),
        "Blatt" to listOf("blatt", "blätter"),
        "Stange" to listOf("stange", "stangen"),
        "Knolle" to listOf("knolle", "knollen"),
        "Kopf" to listOf("kopf", "köpfe"),
        "Spritzer" to listOf("spritzer"),
        "Schuss" to listOf("schuss"),
        "Würfel" to listOf("würfel"),
        "Tropfen" to listOf("tropfen"),
        "cm" to listOf("cm"),
    )

    private val SPELLING_TO_UNIT: List<Pair<String, String>> = UNITS
        .flatMap { (canonical, spellings) -> spellings.map { it to canonical } }
        .sortedByDescending { it.first.length }

    private val VULGAR = mapOf('½' to 0.5, '¼' to 0.25, '¾' to 0.75, '⅓' to 1.0 / 3, '⅔' to 2.0 / 3, '⅛' to 0.125)

    /** Bullets, list markers and decorative emojis at the start of a line. */
    private val LEADING_DECORATION = Regex("""^[\s\-–—•*·▪►▶✓✔✅☑️➡️👉🔸🔹◾◽▫️]+""")

    // A single number: "2", "1,5", "1.5", "1/2", "1 1/2", "½", "1½"
    private const val NUMBER = """(?:\d+\s+\d+/\d+|\d+/\d+|\d+[.,]\d+|\d*[½¼¾⅓⅔⅛]|\d+)"""
    private val AMOUNT = Regex("""^($NUMBER)(?:\s*(?:-|–|bis)\s*($NUMBER))?""")
    private val TRAILING_AMOUNT = Regex("""^(.+?)[,:]?\s+($NUMBER)(?:\s*(?:-|–|bis)\s*($NUMBER))?\s*([A-Za-zÄÖÜäöüß.]*)$""")

    fun parse(line: String): Ingredient {
        val raw = line.trim()
        val text = clean(raw)

        AMOUNT.find(text)?.let { m ->
            val amount = parseNumber(m.groupValues[1])
            val amountMax = m.groupValues[2].takeIf { it.isNotEmpty() }?.let(::parseNumber)
            val (unit, rest) = takeUnit(text.substring(m.range.last + 1))
            val (name, note) = splitNote(rest)
            if (name.isNotEmpty()) return Ingredient(raw, amount, amountMax, unit, name, note)
        }

        // "Mehl 250 g" or "Salz, 1 Prise": amount at the end
        TRAILING_AMOUNT.find(text)?.let { m ->
            val unitWord = m.groupValues[4].trimEnd('.')
            val unit = if (unitWord.isEmpty()) null else unitOf(unitWord)
            if (unitWord.isEmpty() || unit != null) {
                val (name, note) = splitNote(m.groupValues[1])
                return Ingredient(raw, parseNumber(m.groupValues[2]), m.groupValues[3].takeIf { it.isNotEmpty() }?.let(::parseNumber), unit, name, note)
            }
        }

        val (name, note) = splitNote(text)
        return Ingredient(raw, null, null, null, name, note)
    }

    /** True when the line looks like an ingredient with a number and a known unit, used to find lists without a heading. */
    fun looksLikeMeasuredIngredient(line: String): Boolean {
        val parsed = parse(line)
        return parsed.amount != null && parsed.unit != null && parsed.name.length >= 2 && parsed.name.split(' ').size <= 6
    }

    fun clean(line: String): String = line
        .replace(LEADING_DECORATION, "")
        .replace(Regex("""\p{So}|\p{Cs}"""), " ") // remaining emojis
        .replace(' ', ' ')
        // glued unit after number: "250g" -> "250 g", but keep "1/2"
        .replace(Regex("""(\d)([A-Za-zÄÖÜäöüß])"""), "$1 $2")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun takeUnit(rest: String): Pair<String?, String> {
        val trimmed = rest.trim()
        val lower = trimmed.lowercase()
        for ((spelling, canonical) in SPELLING_TO_UNIT) {
            if (!lower.startsWith(spelling)) continue
            val after = trimmed.substring(spelling.length)
            // the unit must end at a word boundary ("g Mehl" yes, "gelbe Paprika" no)
            if (after.isEmpty() || after[0] == '.' || after[0].isWhitespace() || after[0] == ',') {
                return canonical to after.trimStart('.', ' ', ',')
            }
        }
        return null to trimmed
    }

    private fun unitOf(word: String): String? =
        SPELLING_TO_UNIT.firstOrNull { it.first == word.lowercase() }?.second

    private fun splitNote(text: String): Pair<String, String?> {
        var name = text.trim().trimStart('x', 'X').trim().removePrefix("von ").removePrefix("vom ")
        var note: String? = null
        Regex("""\(([^)]*)\)""").find(name)?.let {
            note = it.groupValues[1].trim()
            name = name.removeRange(it.range).trim()
        }
        val comma = name.indexOf(',')
        if (comma > 0) {
            val extra = name.substring(comma + 1).trim()
            note = listOfNotNull(note, extra.ifEmpty { null }).joinToString(", ").ifEmpty { null }
            name = name.substring(0, comma).trim()
        }
        return name.trim(' ', '.', ':', ';') to note
    }

    internal fun parseNumber(s: String): Double {
        val t = s.trim()
        if (t.contains('/')) {
            val parts = t.split(Regex("""\s+"""))
            return parts.sumOf { p ->
                if (p.contains('/')) p.split('/').let { it[0].toDouble() / it[1].toDouble() } else p.toDouble()
            }
        }
        val vulgar = t.lastOrNull()?.let { VULGAR[it] }
        if (vulgar != null) {
            val whole = t.dropLast(1)
            return (if (whole.isEmpty()) 0.0 else whole.toDouble()) + vulgar
        }
        return t.replace(',', '.').toDouble()
    }
}
