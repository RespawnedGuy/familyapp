package familyapp.spike

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Deterministic parser for German date phrases. Returns all distinct dates found, sorted.
 * Supported: "12.03.2027", "12.03.27", "12.03." (next occurrence from today), "heute", "morgen",
 * "übermorgen", weekday names ("am Freitag", "bis Freitag": next occurrence after today).
 */
class GermanDateParser {

    private val fullDate = Regex("""\b(\d{1,2})\.(\d{1,2})\.(\d{4}|\d{2})(?!\d)""")
    private val shortDate = Regex("""\b(\d{1,2})\.(\d{1,2})\.(?!\d)""")
    private val relative = Regex("""(?U)\b(übermorgen|morgen|heute)\b""", RegexOption.IGNORE_CASE)
    private val weekday = Regex(
        """\b(montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String, today: LocalDate): List<LocalDate> {
        val found = mutableSetOf<LocalDate>()
        var rest = text

        fullDate.findAll(text).forEach { m ->
            val (d, mo, y) = m.destructured
            val year = if (y.length == 2) 2000 + y.toInt() else y.toInt()
            dateOrNull(year, mo.toInt(), d.toInt())?.let(found::add)
        }
        rest = fullDate.replace(rest, " ")

        shortDate.findAll(rest).forEach { m ->
            val (d, mo) = m.destructured
            val thisYear = dateOrNull(today.year, mo.toInt(), d.toInt())
            val date = if (thisYear != null && thisYear.isBefore(today)) {
                dateOrNull(today.year + 1, mo.toInt(), d.toInt())
            } else {
                thisYear
            }
            date?.let(found::add)
        }

        relative.findAll(text).forEach { m ->
            when (m.value.lowercase()) {
                "heute" -> found.add(today)
                "morgen" -> found.add(today.plusDays(1))
                "übermorgen" -> found.add(today.plusDays(2))
            }
        }

        weekday.findAll(text).forEach { m -> found.add(nextWeekday(today, dayOf(m.value))) }

        return found.sorted()
    }

    private fun dateOrNull(year: Int, month: Int, day: Int): LocalDate? =
        runCatching { LocalDate.of(year, month, day) }.getOrNull()

    private fun nextWeekday(today: LocalDate, day: DayOfWeek): LocalDate {
        val diff = (day.value - today.dayOfWeek.value + 7) % 7
        return today.plusDays(if (diff == 0) 7L else diff.toLong())
    }

    private fun dayOf(word: String): DayOfWeek = when (word.lowercase()) {
        "montag" -> DayOfWeek.MONDAY
        "dienstag" -> DayOfWeek.TUESDAY
        "mittwoch" -> DayOfWeek.WEDNESDAY
        "donnerstag" -> DayOfWeek.THURSDAY
        "freitag" -> DayOfWeek.FRIDAY
        "samstag" -> DayOfWeek.SATURDAY
        else -> DayOfWeek.SUNDAY
    }
}
