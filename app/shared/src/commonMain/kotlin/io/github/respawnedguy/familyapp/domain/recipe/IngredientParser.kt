package io.github.respawnedguy.familyapp.domain.recipe

/**
 * Deterministic parser for German ingredient lines such as "250 g Kirschtomaten, halbiert",
 * "1/2 TL Salz", "2-3 Zehen Knoblauch" or "Salz und Pfeffer". Taken over from the recipe spike
 * (spikes/recipe-import, IngredientLineParser) with its tests. It never guesses: without a number
 * the line keeps no amount, and only units from the fixed list count as units.
 */
object IngredientParser {

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

    @Suppress("MagicNumber") // the values of the fraction characters
    private val VULGAR = mapOf(
        '½' to 1.0 / 2, '¼' to 1.0 / 4, '¾' to 3.0 / 4, '⅓' to 1.0 / 3, '⅔' to 2.0 / 3, '⅛' to 1.0 / 8,
    )

    /** Bullets, list markers and decorative symbols at the start of a line. */
    private val LEADING_DECORATION = Regex("""^[\s\-–—•*·▪►▶✓✔✅☑➡👉🔸🔹◾◽▫️]+""")
    private val SYMBOLS = Regex("""\p{So}|\p{Cs}|️""")
    private val GLUED_UNIT = Regex("""(\d)([A-Za-zÄÖÜäöüß])""")
    private val SPACES = Regex("""\s+""")
    private val BRACKETS = Regex("""\(([^)]*)\)""")

    // A single number: "2", "1,5", "1.5", "1/2", "1 1/2", "½", "1½"
    private const val NUMBER = """(?:\d+\s+\d+/\d+|\d+/\d+|\d+[.,]\d+|\d*[½¼¾⅓⅔⅛]|\d+)"""
    private val AMOUNT = Regex("""^($NUMBER)(?:\s*(?:-|–|bis)\s*($NUMBER))?""")
    private val TRAILING_AMOUNT =
        Regex("""^(.+?)[,:]?\s+($NUMBER)(?:\s*(?:-|–|bis)\s*($NUMBER))?\s*([A-Za-zÄÖÜäöüß.]*)$""")

    private const val GROUP_MAX = 3
    private const val GROUP_UNIT = 4

    /** Longest ingredient name (in words) a measured line may have; longer lines are sentences. */
    private const val MAX_NAME_WORDS = 6

    fun parse(line: String): Ingredient {
        val raw = line.trim()
        val text = clean(raw)
        return leadingAmount(raw, text) ?: trailingAmount(raw, text) ?: splitNote(text).let { (name, note) ->
            Ingredient(raw, name = name, note = note)
        }
    }

    /** "250 g Mehl", "2-3 Zehen Knoblauch". */
    private fun leadingAmount(raw: String, text: String): Ingredient? {
        val m = AMOUNT.find(text) ?: return null
        val amount = parseNumber(m.groupValues[1])
        val amountMax = m.groupValues[2].takeIf { it.isNotEmpty() }?.let(::parseNumber)
        val (unit, rest) = takeUnit(text.substring(m.range.last + 1))
        val (name, note) = splitNote(rest)
        return Ingredient(raw, amount, amountMax, unit, name, note).takeIf { name.isNotEmpty() && amount != null }
    }

    /** "Mehl 250 g" or "Salz, 1 Prise": amount at the end. */
    private fun trailingAmount(raw: String, text: String): Ingredient? {
        val m = TRAILING_AMOUNT.find(text) ?: return null
        val unitWord = m.groupValues[GROUP_UNIT].trimEnd('.')
        val unit = if (unitWord.isEmpty()) null else unitOf(unitWord)
        val (name, note) = splitNote(m.groupValues[1])
        val max = m.groupValues[GROUP_MAX].takeIf { it.isNotEmpty() }?.let(::parseNumber)
        val amount = parseNumber(m.groupValues[2])
        // A word after the number that is no unit ("Mehl 250 Gäste") means it is no amount.
        val known = unitWord.isEmpty() || unit != null
        return Ingredient(raw, amount, max, unit, name, note).takeIf { known && amount != null }
    }

    /** True when the line has a number and a known unit; used to find ingredient lists without a heading. */
    fun looksLikeMeasuredIngredient(line: String): Boolean {
        val parsed = parse(line)
        return parsed.amount != null && parsed.unit != null && parsed.name.length >= 2 &&
            parsed.name.split(' ').size <= MAX_NAME_WORDS
    }

    fun clean(line: String): String = line
        .replace(LEADING_DECORATION, "")
        .replace(SYMBOLS, " ")
        .replace(' ', ' ')
        // glued unit after number: "250g" -> "250 g", but keep "1/2"
        .replace(GLUED_UNIT, "$1 $2")
        .replace(SPACES, " ")
        .trim()

    private fun takeUnit(rest: String): Pair<String?, String> {
        val trimmed = rest.trim()
        val lower = trimmed.lowercase()
        val hit = SPELLING_TO_UNIT.firstOrNull { (spelling, _) ->
            // the unit must end at a word boundary ("g Mehl" yes, "gelbe Paprika" no)
            lower.startsWith(spelling) && trimmed.substring(spelling.length).let { after ->
                after.isEmpty() || after[0] == '.' || after[0].isWhitespace() || after[0] == ','
            }
        } ?: return null to trimmed
        return hit.second to trimmed.substring(hit.first.length).trimStart('.', ' ', ',')
    }

    private fun unitOf(word: String): String? =
        SPELLING_TO_UNIT.firstOrNull { it.first == word.lowercase() }?.second

    private fun splitNote(text: String): Pair<String, String?> {
        var name = text.trim().trimStart('x', 'X').trim().removePrefix("von ").removePrefix("vom ")
        var note: String? = null
        BRACKETS.find(name)?.let {
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

    /** Value of one number as written; null for something like "1/0" that has no finite value. */
    internal fun parseNumber(s: String): Double? {
        val t = s.trim()
        val vulgar = t.lastOrNull()?.let { VULGAR[it] }
        val value = when {
            t.contains('/') -> t.split(SPACES).sumOf { p ->
                if (p.contains('/')) p.split('/').let { it[0].toDouble() / it[1].toDouble() } else p.toDouble()
            }
            vulgar != null -> (t.dropLast(1).toDoubleOrNull() ?: 0.0) + vulgar
            else -> t.replace(',', '.').toDouble()
        }
        return value.takeIf { it.isFinite() }
    }
}
