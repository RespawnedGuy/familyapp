package io.github.respawnedguy.familyapp.domain

import kotlinx.datetime.LocalDate

/** What the deterministic rules found in a captured text. Everything here is a suggestion. */
data class Suggestions(
    val concerns: String? = null,
    val due: LocalDate? = null,
    val reasons: List<String> = emptyList(),
)

/**
 * Deterministic rules applied when something is captured (no AI, see KONZEPT.md):
 * - a member's name in the text suggests "betrifft" (whom it concerns),
 * - exactly one date in the text suggests "bis wann".
 * Ambiguous findings (two names, two dates) suggest nothing and say so; the card then asks.
 * "Kümmert sich" is never guessed: that is the decision the family makes (HITL).
 */
class CaptureRules(private val dates: GermanDateParser = GermanDateParser()) {

    fun analyze(text: String, members: List<Member>, today: LocalDate): Suggestions {
        val reasons = mutableListOf<String>()

        val named = members.filter { mentions(text, it.name) }
        val concerns = when {
            named.size == 1 -> named.first().also { reasons += "Name „${it.name}“ im Text" }.id
            named.size > 1 -> null.also { reasons += "Mehrere Namen erkannt (${named.joinToString { it.name }}), bitte wählen" }
            else -> null
        }

        val found = dates.find(text, today)
        val due = when {
            found.size == 1 -> found.first().also { reasons += "„${it.phrase}“ → ${DateFormat.short(it.date)}" }.date
            found.size > 1 -> null.also { reasons += "Mehrere Daten erkannt (${found.joinToString { it.phrase }}), bitte wählen" }
            else -> null
        }

        return Suggestions(concerns, due, reasons)
    }

    /** Whole-word, case-insensitive match; also the German genitive "Mias". */
    private fun mentions(text: String, name: String): Boolean {
        val n = name.trim()
        if (n.length < 2) return false
        val pattern = Regex("(?<![$LETTER])${Regex.escape(n)}s?(?![$LETTER])", RegexOption.IGNORE_CASE)
        return pattern.containsMatchIn(text)
    }

    private companion object {
        const val LETTER = "A-Za-zÄÖÜäöüß"
    }
}
