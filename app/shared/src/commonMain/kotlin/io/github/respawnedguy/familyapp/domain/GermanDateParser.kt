package io.github.respawnedguy.familyapp.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus

/** A date found in text together with the words it came from, for the reason line on the card. */
data class FoundDate(val date: LocalDate, val phrase: String)

/**
 * Deterministic parser for German date phrases, ported from spikes/rule-engine (same behaviour,
 * same tests) and extended for spoken input. Returns all distinct dates, sorted.
 *
 * Supported: "12.03.2027", "12.03.27", "12.03." (next occurrence), "12. März [2027]",
 * "heute", "morgen", "übermorgen", weekdays ("bis Freitag": next occurrence after today),
 * "in 3 Tagen", "in zwei Wochen". Vague phrases ("nächste Woche", "bald") give no date on purpose:
 * the card then asks instead of guessing (HITL).
 */
class GermanDateParser {

    private val fullDate = Regex("""(?<!\d)(\d{1,2})\.\s?(\d{1,2})\.\s?(\d{4}|\d{2})(?!\d)""")
    private val shortDate = Regex("""(?<![\d.])(\d{1,2})\.\s?(\d{1,2})\.(?!\d)""")
    private val monthDate = Regex(
        """(?<!\d)(\d{1,2})\.?\s+($MONTHS)(?:\s+(\d{4}))?$WORD_END""",
        RegexOption.IGNORE_CASE,
    )
    private val relative = Regex("""$WORD_START(übermorgen|morgen|heute)$WORD_END""", RegexOption.IGNORE_CASE)
    private val weekday = Regex(
        """$WORD_START(montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag)$WORD_END""",
        RegexOption.IGNORE_CASE,
    )
    private val inDays = Regex(
        """${WORD_START}in\s+(\d{1,2}|einem|einer|zwei|drei|vier|fünf|sechs|sieben|acht|neun|zehn)\s+(tag|tagen|woche|wochen)$WORD_END""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String, today: LocalDate): List<LocalDate> = find(text, today).map { it.date }

    fun find(text: String, today: LocalDate): List<FoundDate> {
        val found = linkedMapOf<LocalDate, String>()
        fun add(date: LocalDate?, phrase: String) {
            if (date != null && date !in found) found[date] = phrase.trim()
        }
        var rest = text

        fullDate.findAll(rest).forEach { m ->
            val (d, mo, y) = m.destructured
            val year = if (y.length == 2) 2000 + y.toInt() else y.toInt()
            add(dateOrNull(year, mo.toInt(), d.toInt()), m.value)
        }
        rest = fullDate.replace(rest, " ")

        monthDate.findAll(rest).forEach { m ->
            val (d, monthWord, y) = m.destructured
            val month = monthNumber(monthWord)
            val date = if (y.isNotEmpty()) dateOrNull(y.toInt(), month, d.toInt()) else nextOccurrence(today, month, d.toInt())
            add(date, m.value)
        }
        rest = monthDate.replace(rest, " ")

        shortDate.findAll(rest).forEach { m ->
            val (d, mo) = m.destructured
            add(nextOccurrence(today, mo.toInt(), d.toInt()), m.value)
        }

        relative.findAll(text).forEach { m ->
            when (m.groupValues[1].lowercase()) {
                "heute" -> add(today, m.value)
                "morgen" -> add(today.plus(1, DateTimeUnit.DAY), m.value)
                "übermorgen" -> add(today.plus(2, DateTimeUnit.DAY), m.value)
            }
        }

        weekday.findAll(text).forEach { m -> add(nextWeekday(today, isoDay(m.groupValues[1])), m.value) }

        inDays.findAll(text).forEach { m ->
            val n = number(m.groupValues[1])
            val days = if (m.groupValues[2].lowercase().startsWith("woche")) n * 7 else n
            add(today.plus(days, DateTimeUnit.DAY), m.value)
        }

        return found.entries.map { FoundDate(it.key, it.value) }.sortedBy { it.date }
    }

    private fun nextOccurrence(today: LocalDate, month: Int, day: Int): LocalDate? {
        val thisYear = dateOrNull(today.year, month, day)
        return if (thisYear != null && thisYear < today) dateOrNull(today.year + 1, month, day) else thisYear
    }

    private fun dateOrNull(year: Int, month: Int, day: Int): LocalDate? =
        runCatching { LocalDate(year, month, day) }.getOrNull()

    private fun nextWeekday(today: LocalDate, isoDay: Int): LocalDate {
        val diff = (isoDay - today.dayOfWeek.isoDayNumber + 7) % 7
        return today.plus(if (diff == 0) 7 else diff, DateTimeUnit.DAY)
    }

    private fun isoDay(word: String): Int = WEEKDAYS.indexOf(word.lowercase()) + 1

    private fun monthNumber(word: String): Int {
        val w = word.lowercase().removeSuffix(".")
        return MONTH_NAMES.indexOfFirst { w == it || (w.length >= 3 && it.startsWith(w)) } + 1
    }

    private fun number(word: String): Int = word.toIntOrNull() ?: when (word.lowercase()) {
        "einem", "einer" -> 1
        "zwei" -> 2
        "drei" -> 3
        "vier" -> 4
        "fünf" -> 5
        "sechs" -> 6
        "sieben" -> 7
        "acht" -> 8
        "neun" -> 9
        else -> 10
    }

    companion object {
        // \b is ASCII-only on some platforms and would split "übermorgen"; use explicit letter classes.
        private const val LETTER = "A-Za-zÄÖÜäöüß"
        private const val WORD_START = "(?<![$LETTER])"
        private const val WORD_END = "(?![$LETTER])"
        private val WEEKDAYS = listOf("montag", "dienstag", "mittwoch", "donnerstag", "freitag", "samstag", "sonntag")
        val MONTH_NAMES = listOf(
            "januar", "februar", "märz", "april", "mai", "juni",
            "juli", "august", "september", "oktober", "november", "dezember",
        )
        private val MONTHS =
            "januar|jan\\.?|februar|feb\\.?|märz|mär\\.?|april|apr\\.?|mai|juni|jun\\.?|juli|jul\\.?|" +
                "august|aug\\.?|september|sept?\\.?|oktober|okt\\.?|november|nov\\.?|dezember|dez\\.?"
    }
}
