package io.github.respawnedguy.familyapp.domain.recipe

/** What the rules found in a text. Nothing in here is invented: missing parts stay empty or null. */
data class ParsedRecipe(
    val title: String?,
    val servings: Double?,
    val totalMinutes: Int?,
    val ingredients: List<Ingredient>,
    val steps: List<String>,
    val status: Status,
) {
    /**
     * COMPLETE: ingredients and steps found. INGREDIENTS_ONLY: list found, no steps.
     * NO_INGREDIENTS: nothing usable; the recipe stays a suggestion with a hint (HITL).
     */
    enum class Status { COMPLETE, INGREDIENTS_ONLY, NO_INGREDIENTS }
}

/**
 * Turns free text (an Instagram caption, pasted text, later OCR output) into a recipe, using fixed
 * rules only (docs/kochbibliothek.md "Rezept bauen: Regeln zuerst"). Taken over from the recipe
 * spike. It never guesses ingredients: lines count as ingredients only under an ingredient heading
 * or, without a heading, in a block of at least three lines with amounts, two of them with a unit.
 */
object RecipeTextParser {

    internal val INGREDIENT_HEADING = Regex(
        """^(zutaten|ingredients|du brauchst|ihr braucht|das brauchst du|einkaufsliste|einkaufszettel)\b.*""",
        RegexOption.IGNORE_CASE,
    )
    internal val STEPS_HEADING = Regex(
        """^(zubereitung|anleitung|so geht'?s|so gehts|so wird'?s gemacht|schritte|instructions|method|""" +
            """und so geht'?s)\b.*""",
        RegexOption.IGNORE_CASE,
    )
    internal val OTHER_HEADING =
        Regex("""^(tipp|tipps|hinweis|nährwerte|naehrwerte|notiz|werbung|anzeige)\b.*""", RegexOption.IGNORE_CASE)

    private val SERVINGS = listOf(
        Regex("""(?:für|fuer)\s+(\d+)\s*(?:personen|portionen|pers\.?|stück|leute)""", RegexOption.IGNORE_CASE),
        Regex("""(\d+)\s*(?:portionen|personen)""", RegexOption.IGNORE_CASE),
        Regex("""(?:portionen|servings)\s*:?\s*(\d+)""", RegexOption.IGNORE_CASE),
    )
    private val MINUTES = Regex("""(\d+)\s*(?:min\b|minuten|min\.)""", RegexOption.IGNORE_CASE)
    private val HOURS = Regex("""(\d+(?:[.,]\d+)?)\s*(?:std\.?|stunden?)\b""", RegexOption.IGNORE_CASE)
    internal val NUMBERED_STEP = Regex("""^(?:schritt\s*)?(\d{1,2})\s*[.):\-]\s*(.+)""", RegexOption.IGNORE_CASE)

    /** Calls to action that are never part of a recipe. */
    internal val PROMO = Regex(
        """speicher|folg[te]|link in (der )?bio|werbung|anzeige|kommentier|abonnier|teilt? (das|es)|markier|""" +
            """rezept (unten|im video)""",
        RegexOption.IGNORE_CASE,
    )
    private val HASHTAG_LINE = Regex("""^(?:[#@][\p{L}\p{N}_.]+\s*)+$""")
    private val INLINE_HASHTAG = Regex("""(?<!\S)#[\p{L}\p{N}_]+""")
    private val INLINE_MENTION = Regex("""(?<!\S)@[\p{L}\p{N}_.]+""")

    private const val MINUTES_PER_HOUR = 60
    private const val MAX_SUB_HEADING = 40

    internal fun numbered(line: String) = NUMBERED_STEP.find(IngredientParser.clean(line))

    /** Sub-headings inside the list such as "Für den Teig:" or "Topping:" (short, no amount, ends with a colon). */
    internal fun isSubHeading(line: String): Boolean {
        val t = IngredientParser.clean(line)
        return t.endsWith(":") && t.length <= MAX_SUB_HEADING && IngredientParser.parse(t.trimEnd(':')).amount == null
    }

    /** "Zutaten: Mehl, Zucker, 2 Eier" on one line. */
    internal fun afterColon(line: String): String? = line.substringAfter(':', "").trim().takeIf { it.isNotEmpty() }

    fun parse(text: String): ParsedRecipe {
        val lines = text.lines()
            .map { it.replace(INLINE_HASHTAG, "").replace(INLINE_MENTION, "").trimEnd() }
            .filter { it.isNotBlank() && !HASHTAG_LINE.matches(it) }
        val state = ParseState()
        lines.forEach(state::take)
        return state.result(servings(text), totalMinutes(text))
    }

    internal fun servings(text: String): Double? =
        SERVINGS.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1)?.toDoubleOrNull() }

    /** Sum of the largest stated hours and minutes; only a hint, shown for confirmation. */
    internal fun totalMinutes(text: String): Int? {
        val h = HOURS.findAll(text).maxOfOrNull { it.groupValues[1].replace(',', '.').toDouble() } ?: 0.0
        val m = MINUTES.findAll(text).maxOfOrNull { it.groupValues[1].toInt() } ?: 0
        val total = (h * MINUTES_PER_HOUR).toInt() + m
        return total.takeIf { it > 0 }
    }
}

/** Walks the lines once; the section changes at headings. */
private class ParseState {
    private enum class Section { NONE, INGREDIENTS, STEPS, OTHER }

    private var section = Section.NONE
    private var title: String? = null
    private val ingredients = mutableListOf<Ingredient>()
    private val steps = mutableListOf<String>()
    private val looseNumbered = mutableListOf<String>()
    private val looseSentences = mutableListOf<String>()
    private val block = mutableListOf<Ingredient>()
    private var adoptedLooseList = false

    fun take(line: String) {
        val bare = IngredientParser.clean(line).trimEnd(':').trim()
        if (heading(line, bare)) return
        if (title == null && section == Section.NONE && looksLikeTitle(line)) {
            title = bare.take(MAX_TITLE)
            return
        }
        when (section) {
            Section.INGREDIENTS -> ingredientLine(line, bare)
            Section.STEPS -> steps += RecipeTextParser.numbered(line)?.groupValues?.get(2) ?: bare
            Section.NONE -> looseLine(line, bare)
            Section.OTHER -> Unit
        }
    }

    /**
     * The title is the first line before any heading that is not an ingredient: "1 Zwiebel" is none,
     * "3-Minuten-Pasta" or "5 Zutaten Bananenbrot" with more words are.
     */
    private fun looksLikeTitle(line: String): Boolean {
        if (IngredientParser.looksLikeMeasuredIngredient(line)) return false
        val parsed = IngredientParser.parse(line)
        return parsed.amount == null || parsed.name.startsWith("-") || parsed.name.split(' ').size >= TITLE_WORDS
    }

    private fun heading(line: String, bare: String): Boolean {
        when {
            RecipeTextParser.INGREDIENT_HEADING.matches(bare) -> {
                section = Section.INGREDIENTS
                RecipeTextParser.afterColon(line)?.let(::addInlineList)
            }
            RecipeTextParser.STEPS_HEADING.matches(bare) -> {
                section = Section.STEPS
                RecipeTextParser.afterColon(line)?.let { steps += it }
            }
            RecipeTextParser.OTHER_HEADING.matches(bare) -> section = Section.OTHER
            else -> return false
        }
        return true
    }

    private fun ingredientLine(line: String, bare: String) {
        if (RecipeTextParser.isSubHeading(line)) return
        val numbered = RecipeTextParser.numbered(line)
        // A long sentence after the list without a heading usually starts the method.
        val sentence = bare.length > LONG_SENTENCE && IngredientParser.parse(line).amount == null
        val longStep = numbered != null && numbered.groupValues[2].length > LONG_NUMBERED_STEP
        if (sentence || longStep) {
            section = Section.STEPS
            steps += numbered?.groupValues?.get(2) ?: bare
        } else {
            ingredients += IngredientParser.parse(line)
        }
    }

    private fun looseLine(line: String, bare: String) {
        val parsed = IngredientParser.parse(line)
        if (parsed.amount != null && parsed.name.length >= 2 && parsed.name.split(' ').size <= MAX_LOOSE_NAME_WORDS) {
            block += parsed
            return
        }
        flushBlock()
        val numbered = RecipeTextParser.numbered(line)
        when {
            numbered != null && numbered.groupValues[2].length > MIN_NUMBERED_STEP ->
                looseNumbered += numbered.groupValues[2]
            adoptedLooseList && bare.length > MIN_LOOSE_SENTENCE && !RecipeTextParser.PROMO.containsMatchIn(bare) ->
                looseSentences += bare
        }
    }

    /** A run of >= 3 short lines with amounts (>= 2 with a unit) counts as the list when there is no heading. */
    private fun flushBlock() {
        if (ingredients.isEmpty() && block.size >= MIN_LOOSE_LIST && block.count { it.unit != null } >= 2) {
            ingredients += block
            adoptedLooseList = true
        }
        block.clear()
    }

    fun result(servings: Double?, minutes: Int?): ParsedRecipe {
        flushBlock()
        if (steps.isEmpty()) steps += looseNumbered.ifEmpty { looseSentences }
        steps.removeAll { RecipeTextParser.PROMO.containsMatchIn(it) }
        val found = ingredients.filter { it.name.isNotBlank() }
        val status = when {
            found.isEmpty() -> ParsedRecipe.Status.NO_INGREDIENTS
            steps.isEmpty() -> ParsedRecipe.Status.INGREDIENTS_ONLY
            else -> ParsedRecipe.Status.COMPLETE
        }
        return ParsedRecipe(title, servings, minutes, found, steps.toList(), status)
    }

    private fun addInlineList(text: String) {
        text.split(INLINE_SEPARATOR).filter { it.isNotBlank() }.forEach { ingredients += IngredientParser.parse(it) }
    }

    private companion object {
        val INLINE_SEPARATOR = Regex("""\s*[,;|]\s*|\s+[-–•]\s+""")
        const val MAX_TITLE = 80
        const val TITLE_WORDS = 3
        const val LONG_SENTENCE = 70
        const val LONG_NUMBERED_STEP = 40
        const val MIN_NUMBERED_STEP = 15
        const val MIN_LOOSE_SENTENCE = 25
        const val MAX_LOOSE_NAME_WORDS = 5
        const val MIN_LOOSE_LIST = 3
    }
}
