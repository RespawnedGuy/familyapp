package familyapp.recipe

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
     * NO_INGREDIENTS: nothing usable; the card stays open and nothing is invented (HITL).
     */
    enum class Status { COMPLETE, INGREDIENTS_ONLY, NO_INGREDIENTS }
}

/**
 * Turns free text (an Instagram caption, OCR output or pasted text) into a recipe, using fixed rules only.
 * It never guesses ingredients: lines count as ingredients only under an ingredient heading
 * or, without a heading, when they carry a number and a known unit.
 */
object RecipeTextParser {

    private enum class Section { NONE, INGREDIENTS, STEPS, OTHER }

    private val INGREDIENT_HEADING = Regex("""^(zutaten|ingredients|du brauchst|ihr braucht|das brauchst du|einkaufsliste|einkaufszettel)\b.*""", RegexOption.IGNORE_CASE)
    private val STEPS_HEADING = Regex("""^(zubereitung|anleitung|so geht'?s|so gehts|so wird'?s gemacht|schritte|instructions|method|und so geht'?s)\b.*""", RegexOption.IGNORE_CASE)
    private val OTHER_HEADING = Regex("""^(tipp|tipps|hinweis|nährwerte|naehrwerte|notiz|werbung|anzeige)\b.*""", RegexOption.IGNORE_CASE)


    private val SERVINGS = listOf(
        Regex("""(?:für|fuer)\s+(\d+)\s*(?:personen|portionen|pers\.?|stück|leute)""", RegexOption.IGNORE_CASE),
        Regex("""(\d+)\s*(?:portionen|personen)""", RegexOption.IGNORE_CASE),
        Regex("""(?:portionen|servings)\s*:?\s*(\d+)""", RegexOption.IGNORE_CASE),
    )
    private val MINUTES = Regex("""(\d+)\s*(?:min\b|minuten|min\.)""", RegexOption.IGNORE_CASE)
    private val HOURS = Regex("""(\d+(?:[.,]\d+)?)\s*(?:std\.?|stunden?)\b""", RegexOption.IGNORE_CASE)
    private val NUMBERED_STEP = Regex("""^(?:schritt\s*)?(\d{1,2})\s*[.):\-]\s*(.+)""", RegexOption.IGNORE_CASE)
    /** Calls to action that are never part of a recipe. */
    private val PROMO = Regex("""speicher|folg[te]|link in (der )?bio|werbung|anzeige|kommentier|abonnier|teilt? (das|es)|markier|rezept (unten|im video)""", RegexOption.IGNORE_CASE)
    private val HASHTAG_LINE = Regex("""^(?:[#@][\p{L}\p{N}_.]+\s*)+$""")

    fun parse(text: String): ParsedRecipe {
        val lines = text.lines()
            .map { stripInline(it) }
            .filter { it.isNotBlank() && !HASHTAG_LINE.matches(it) }

        var section = Section.NONE
        val ingredients = mutableListOf<Ingredient>()
        val steps = mutableListOf<String>()
        val looseNumbered = mutableListOf<String>()
        val looseSentences = mutableListOf<String>()
        val block = mutableListOf<Ingredient>()
        var adoptedLooseList = false

        // A run of >= 3 short lines with amounts (>= 2 with a unit) counts as the list when there is no heading
        fun flushBlock() {
            if (ingredients.isEmpty() && block.size >= 3 && block.count { it.unit != null } >= 2) {
                ingredients += block
                adoptedLooseList = true
            }
            block.clear()
        }
        var title: String? = null

        for (line in lines) {
            val bare = IngredientLineParser.clean(line).trimEnd(':').trim()
            when {
                INGREDIENT_HEADING.matches(bare) -> { section = Section.INGREDIENTS; inlineAfterColon(line)?.let { addInlineList(it, ingredients) }; continue }
                STEPS_HEADING.matches(bare) -> { section = Section.STEPS; inlineAfterColon(line)?.let { steps += it }; continue }
                OTHER_HEADING.matches(bare) -> { section = Section.OTHER; continue }
            }
            if (title == null && section == Section.NONE && !IngredientLineParser.looksLikeMeasuredIngredient(line)) {
                title = bare.take(80)
                continue
            }
            when (section) {
                Section.INGREDIENTS -> {
                    if (isSubHeading(line)) continue
                    val numbered = NUMBERED_STEP.find(IngredientLineParser.clean(line))
                    // A long sentence after the list without a heading usually starts the method
                    if (bare.length > 70 && IngredientLineParser.parse(line).amount == null || numbered != null && numbered.groupValues[2].length > 40) {
                        section = Section.STEPS
                        steps += numbered?.groupValues?.get(2) ?: bare
                    } else {
                        ingredients += IngredientLineParser.parse(line)
                    }
                }
                Section.STEPS -> steps += NUMBERED_STEP.find(IngredientLineParser.clean(line))?.groupValues?.get(2) ?: bare
                Section.NONE -> {
                    val parsed = IngredientLineParser.parse(line)
                    if (parsed.amount != null && parsed.name.length >= 2 && parsed.name.split(' ').size <= 5) {
                        block += parsed
                    } else {
                        flushBlock()
                        val numbered = NUMBERED_STEP.find(IngredientLineParser.clean(line))
                        when {
                            numbered != null && numbered.groupValues[2].length > 15 -> looseNumbered += numbered.groupValues[2]
                            adoptedLooseList && bare.length > 25 && !PROMO.containsMatchIn(bare) -> looseSentences += bare
                        }
                    }
                }
                Section.OTHER -> Unit
            }
        }

        flushBlock()
        if (steps.isEmpty()) steps += looseNumbered.ifEmpty { looseSentences }
        steps.removeAll { PROMO.containsMatchIn(it) }

        val status = when {
            ingredients.isEmpty() -> ParsedRecipe.Status.NO_INGREDIENTS
            steps.isEmpty() -> ParsedRecipe.Status.INGREDIENTS_ONLY
            else -> ParsedRecipe.Status.COMPLETE
        }
        return ParsedRecipe(title, servings(text), totalMinutes(text), ingredients.filter { it.name.isNotBlank() }, steps, status)
    }

    /** Sub-headings inside the ingredient list such as "Für den Teig:" or "Topping:" (no amount, ends with a colon, short). */
    private fun isSubHeading(line: String): Boolean {
        val t = IngredientLineParser.clean(line)
        return t.endsWith(":") && t.length <= 40 && IngredientLineParser.parse(t.trimEnd(':')).amount == null
    }

    /** "Zutaten: Mehl, Zucker, 2 Eier" on one line. */
    private fun inlineAfterColon(line: String): String? =
        line.substringAfter(':', "").trim().takeIf { it.isNotEmpty() }

    private fun addInlineList(text: String, into: MutableList<Ingredient>) {
        text.split(Regex("""\s*[,;|]\s*|\s+[-–•]\s+""")).filter { it.isNotBlank() }.forEach { into += IngredientLineParser.parse(it) }
    }

    private fun stripInline(line: String): String = line
        .replace(Regex("""(?<!\S)#[\p{L}\p{N}_]+"""), "")
        .replace(Regex("""(?<!\S)@[\p{L}\p{N}_.]+"""), "")
        .trimEnd()

    internal fun servings(text: String): Double? =
        SERVINGS.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1)?.toDoubleOrNull() }

    /** Sum of the largest stated hours and minutes; only a hint, shown for confirmation. */
    internal fun totalMinutes(text: String): Int? {
        val h = HOURS.findAll(text).maxOfOrNull { it.groupValues[1].replace(',', '.').toDouble() } ?: 0.0
        val m = MINUTES.findAll(text).maxOfOrNull { it.groupValues[1].toInt() } ?: 0
        val total = (h * 60).toInt() + m
        return total.takeIf { it > 0 }
    }
}
