package familyapp.spike

import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Parses mail header dates: RFC 1123 ("Wed, 18 Nov 2026 15:32:04 +0000") and Gmail's German forward format. */
object MailDate {
    private val germanMonths = listOf("jan", "feb", "mär", "apr", "mai", "jun", "jul", "aug", "sep", "okt", "nov", "dez")
    private val german = Regex("""(\d{1,2})\.\s*([A-Za-zä]{3})[a-zä]*\.?\s+(\d{4})""")

    fun parse(value: String): LocalDate? {
        runCatching { return ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDate() }
        val m = german.find(value) ?: return null
        val (day, month, year) = m.destructured
        val monthIndex = germanMonths.indexOf(month.lowercase())
        if (monthIndex < 0) return null
        return runCatching { LocalDate.of(year.toInt(), monthIndex + 1, day.toInt()) }.getOrNull()
    }
}
