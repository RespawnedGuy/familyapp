package familyapp.recipe

import kotlin.math.abs

/** Expected ingredient lines per sample, read from `id;line` rows (one row per ingredient). */
object ExpectedFile {
    fun parse(text: String): Map<String, List<String>> = text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("id;") }
        .map { it.substringBefore(';').trim() to it.substringAfter(';').trim() }
        .groupBy({ it.first }, { it.second })
}

data class SampleScore(
    val id: String,
    val expected: Int,
    val found: Int,
    val amountAndUnitCorrect: Int,
    val invented: Int,
    val missing: List<String>,
    val wrongAmount: List<String>,
    val status: ParsedRecipe.Status,
)

data class TotalScore(val samples: List<SampleScore>) {
    val expected = samples.sumOf { it.expected }
    val found = samples.sumOf { it.found }
    val amountAndUnitCorrect = samples.sumOf { it.amountAndUnitCorrect }
    val invented = samples.sumOf { it.invented }
    val recall get() = if (expected == 0) 0.0 else found.toDouble() / expected
    val amountShare get() = if (expected == 0) 0.0 else amountAndUnitCorrect.toDouble() / expected

    /** Acceptance from docs/kochbibliothek.md: >= 90 % lines found, >= 80 % with correct amount and unit. */
    val passes get() = recall >= 0.9 && amountShare >= 0.8
}

/**
 * Compares parsed ingredients with the expected lines. Expected lines are clean ("250 g Kirschtomaten"),
 * so they are parsed with the same line parser; its unit tests guard that step.
 * "Invented" counts parsed ingredients whose key appears nowhere in the expected list: this catches
 * headings or sentences that slipped into the list, which is the error HITL must never hide.
 */
object Evaluation {

    fun score(id: String, parsed: ParsedRecipe, expectedLines: List<String>): SampleScore {
        val expected = expectedLines.map { IngredientLineParser.parse(it) }
        val remaining = parsed.ingredients.toMutableList()
        var found = 0
        var amountOk = 0
        val missing = mutableListOf<String>()
        val wrongAmount = mutableListOf<String>()

        for (e in expected) {
            val key = IngredientKey.of(e.name)
            val match = remaining.firstOrNull { IngredientKey.of(it.name) == key }
            if (match == null) {
                missing += e.raw
                continue
            }
            remaining -= match
            found++
            if (sameAmount(e, match)) amountOk++ else wrongAmount += "${e.raw} ≠ ${match.raw}"
        }
        val expectedKeys = expected.map { IngredientKey.of(it.name) }.toSet()
        val invented = remaining.count { IngredientKey.of(it.name) !in expectedKeys }
        return SampleScore(id, expected.size, found, amountOk, invented, missing, wrongAmount, parsed.status)
    }

    private fun sameAmount(expected: Ingredient, actual: Ingredient): Boolean {
        if (expected.amount == null) return actual.amount == null || actual.unit == null
        val a = actual.amount ?: return false
        return abs(a - expected.amount) < 0.01 && expected.unit == actual.unit
    }
}
