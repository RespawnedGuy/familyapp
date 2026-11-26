package io.github.respawnedguy.familyapp.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Quality plan P1-5: properties of the date parser over many generated days and phrases
 * (seeded, so a failure is reproducible). A wrong date is worse than none: the card would show
 * a confident suggestion that someone confirms with one tap.
 */
class DateParserPropertiesTest {
    private val parser = GermanDateParser()
    private val rules = CaptureRules(parser)
    private val random = Random(20261126)
    private val start = LocalDate(2026, 1, 1)

    /** 1 000 days over almost three years, including leap day 2028 and year ends. */
    private val days = (0 until 1000).map { start.plus(it, DateTimeUnit.DAY) }
    private val weekdays = listOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")

    @Test
    fun aWeekdayIsAlwaysWithinTheComingSevenDaysNeverToday() {
        for (today in days) for (word in weekdays) {
            val d = parser.parse("Bitte bis $word erledigen", today).single()
            assertTrue(today.daysUntil(d) in 1..7, "$word am $today → $d")
            assertEquals(word.take(2), DateFormat.weekdayShort(d), "$word am $today → $d")
        }
    }

    @Test
    fun aDayAndMonthWithoutYearIsNeverInThePastAndAtMostAYearAhead() {
        repeat(3000) {
            val today = days.random(random)
            val target = today.plus(random.nextInt(0, 365), DateTimeUnit.DAY)
            val text = "Abgabe ${target.day}.${target.month.ordinal + 1}. im Sekretariat"
            assertEquals(listOf(target), parser.parse(text, today), "$text am $today")
        }
    }

    @Test
    fun spokenMonthNamesAgreeWithDigits() {
        repeat(1000) {
            val today = days.random(random)
            val target = today.plus(random.nextInt(0, 365), DateTimeUnit.DAY)
            val month = GermanDateParser.MONTH_NAMES[target.month.ordinal].replaceFirstChar { it.uppercase() }
            assertEquals(listOf(target), parser.parse("am ${target.day}. $month", today), "${target.day}. $month am $today")
        }
    }

    @Test
    fun relativePhrasesCountFromToday() {
        for (today in days.filterIndexed { i, _ -> i % 7 == 0 }) {
            assertEquals(listOf(today), parser.parse("heute noch", today))
            assertEquals(listOf(today.plus(1, DateTimeUnit.DAY)), parser.parse("morgen früh", today))
            assertEquals(listOf(today.plus(2, DateTimeUnit.DAY)), parser.parse("übermorgen", today))
            for (n in 1..30) assertEquals(listOf(today.plus(n, DateTimeUnit.DAY)), parser.parse("in $n Tagen", today))
            assertEquals(listOf(today.plus(14, DateTimeUnit.DAY)), parser.parse("in zwei Wochen", today))
        }
    }

    @Test
    fun impossibleDatesGiveNoDateInsteadOfRollingOver() {
        val today = LocalDate(2026, 11, 26)
        for (text in listOf("31.02.", "30. Februar", "31.04.2027", "29.02.2027", "32.01.", "15.13.", "0.5.")) {
            assertTrue(parser.parse(text, today).isEmpty(), text)
        }
        assertEquals(listOf(LocalDate(2028, 2, 29)), parser.parse("29.02.2028", today), "leap day exists")
    }

    @Test
    fun vaguePhrasesNeverBecomeADate() {
        val today = LocalDate(2026, 11, 26)
        val vague = listOf(
            "nächste Woche", "bald", "demnächst", "Ende des Monats", "irgendwann", "nach den Ferien",
            "am Wochenende", "übernächste Woche", "zeitnah", "Anfang Dezember", "Mitte März",
        )
        for (text in vague) assertTrue(parser.parse(text, today).isEmpty(), text)
    }

    @Test
    fun numbersThatAreNotDatesStayNumbers() {
        val today = LocalDate(2026, 11, 26)
        val notDates = listOf(
            "Treffen um 14.30 Uhr", "kostet 2.50 €", "3 Kinder, 2 Erwachsene", "Raum 1.12", "Version 2.0",
            "Klasse 3b", "Tel. 0171 2345678", "IBAN DE12 3456 7890",
        )
        for (text in notDates) assertTrue(parser.parse(text, today).isEmpty(), "$text → ${parser.parse(text, today)}")
    }

    @Test
    fun twoDifferentDatesSuggestNothing() {
        repeat(500) {
            val today = days.random(random)
            val a = weekdays.random(random)
            val b = weekdays.filter { it != a }.random(random)
            val s = rules.analyze("Entweder $a oder $b", emptyList(), today)
            assertNull(s.due, "$a/$b am $today")
            assertTrue(s.reasons.any { it.startsWith("Mehrere Daten") })
        }
    }

    @Test
    fun theSameDateSaidTwiceIsStillOneSuggestion() {
        val today = LocalDate(2026, 11, 26)
        val s = rules.analyze("Freitag, 27.11., also morgen", emptyList(), today)
        assertEquals(LocalDate(2026, 11, 27), s.due)
    }

    @Test
    fun arbitraryInputNeverThrows() {
        val alphabet = "0123456789.,:;- abcdefghijklmnopqrstuvwxyzäöüß MontagFreitagMärzmorgenin Tagen Wochen".toList()
        repeat(5000) {
            val text = (1..random.nextInt(0, 60)).map { alphabet.random(random) }.joinToString("")
            parser.find(text, days.random(random)) // must not throw
        }
    }

    @Test
    fun numberingAndReferenceDatesAreNoDeadlines() {
        val today = LocalDate(2026, 11, 26)
        for (word in listOf("Kapitel", "Seite", "Nr.", "Aufgabe", "Abschnitt", "Übung")) {
            assertTrue(parser.parse("$word 4.1. bearbeiten", today).isEmpty(), word)
        }
        assertTrue(parser.parse("Rechnung vom 12.11. liegt bei", today).isEmpty())
        assertEquals(listOf(LocalDate(2026, 12, 10)), parser.parse("Schreiben vom 12.11.2026, Frist bis 10.12.", today))
    }

    @Test
    fun aRangeGivesBothEndsSoTheCardAsks() {
        val today = LocalDate(2026, 11, 26)
        assertEquals(listOf(LocalDate(2027, 6, 15), LocalDate(2027, 6, 19)), parser.parse("Klassenfahrt 15.-19.6.", today))
        assertNull(rules.analyze("Ferien vom 23.12. bis 2.1.", emptyList(), today).due)
    }
}
