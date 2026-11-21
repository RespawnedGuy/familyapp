package io.github.respawnedguy.familyapp.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus

/** German date texts used on cards. Status is always written out, never colour alone. */
object DateFormat {
    private val DAYS = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
    private val MONTHS = listOf("Jan.", "Feb.", "März", "Apr.", "Mai", "Juni", "Juli", "Aug.", "Sep.", "Okt.", "Nov.", "Dez.")

    fun weekdayShort(date: LocalDate): String = DAYS[date.dayOfWeek.isoDayNumber - 1]

    private fun two(n: Int) = n.toString().padStart(2, '0')

    /** "Do 26.11." */
    fun short(date: LocalDate): String = "${weekdayShort(date)} ${two(date.day)}.${two(date.month.number)}."

    /** "26. Nov." */
    fun dayMonth(date: LocalDate): String = "${date.day}. ${MONTHS[date.month.number - 1]}"

    /** Text for the date chip: "Heute", "Morgen", "Fr 27.11.". */
    fun chip(date: LocalDate, today: LocalDate): String = when (today.daysUntil(date)) {
        0 -> "Heute"
        1 -> "Morgen"
        else -> short(date)
    }

    /** "überfällig seit gestern", "überfällig seit 3 Tagen", or null if not overdue. */
    fun overdue(due: LocalDate, today: LocalDate): String? {
        val days = due.daysUntil(today)
        return when {
            days <= 0 -> null
            days == 1 -> "überfällig seit gestern"
            else -> "überfällig seit $days Tagen"
        }
    }

    fun mondayOf(date: LocalDate): LocalDate = date.minus(date.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)

    /** ISO 8601 week number (weeks start on Monday, week 1 contains the first Thursday). */
    fun isoWeek(date: LocalDate): Int {
        val thursday = mondayOf(date).plus(3, DateTimeUnit.DAY)
        val firstThursday = LocalDate(thursday.year, 1, 1).let { jan1 ->
            jan1.plus((4 - jan1.dayOfWeek.isoDayNumber + 7) % 7, DateTimeUnit.DAY)
        }
        return firstThursday.daysUntil(thursday) / 7 + 1
    }

    /** "KW 48 · 23.–29. Nov." */
    fun weekLabel(monday: LocalDate): String {
        val sunday = monday.plus(6, DateTimeUnit.DAY)
        val range = if (monday.month == sunday.month) "${monday.day}.–${dayMonth(sunday)}" else "${dayMonth(monday)} – ${dayMonth(sunday)}"
        return "KW ${isoWeek(monday)} · $range"
    }
}
