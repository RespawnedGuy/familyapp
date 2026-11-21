package io.github.respawnedguy.familyapp.domain

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** The first nine cases are the spike's tests (spikes/rule-engine), unchanged in meaning. */
class GermanDateParserTest {
    private val parser = GermanDateParser()
    private val friday = LocalDate(2026, 11, 20) // a Friday

    @Test
    fun fullDateWithFourDigitYear() =
        assertEquals(listOf(LocalDate(2027, 3, 12)), parser.parse("Elternabend am 12.03.2027", friday))

    @Test
    fun fullDateWithTwoDigitYear() =
        assertEquals(listOf(LocalDate(2027, 3, 12)), parser.parse("bis 12.03.27 zahlen", friday))

    @Test
    fun shortDateInTheFutureStaysInThisYear() =
        assertEquals(listOf(LocalDate(2026, 12, 4)), parser.parse("Basar am 4.12.", friday))

    @Test
    fun shortDateInThePastRollsOverToNextYear() =
        assertEquals(listOf(LocalDate(2027, 3, 1)), parser.parse("Abgabe 01.03.", friday))

    @Test
    fun relativeWords() = assertEquals(
        listOf(friday, LocalDate(2026, 11, 21), LocalDate(2026, 11, 22)),
        parser.parse("heute, morgen oder übermorgen", friday),
    )

    @Test
    fun weekdayMeansNextOccurrenceAfterToday() {
        assertEquals(listOf(LocalDate(2026, 11, 24)), parser.parse("bis Dienstag", friday))
        assertEquals(listOf(LocalDate(2026, 11, 27)), parser.parse("am Freitag", friday))
    }

    @Test
    fun invalidDatesAreIgnored() = assertEquals(emptyList(), parser.parse("am 31.02.2027", friday))

    @Test
    fun versionNumbersAndAmountsAreNotDates() =
        assertEquals(emptyList(), parser.parse("Kosten 12,50 Euro, Version 1.2", friday))

    @Test
    fun duplicatesAreMergedAndSorted() = assertEquals(
        listOf(LocalDate(2026, 12, 4), LocalDate(2027, 1, 8)),
        parser.parse("08.01.2027 und 4.12. und nochmal 04.12.2026", friday),
    )

    // ---- Extensions for spoken input -------------------------------------------------------

    @Test
    fun spokenSentenceWithWeekday() = assertEquals(
        listOf(LocalDate(2026, 11, 26)),
        parser.parse("Mia braucht bis Donnerstag neue Hausschuhe", friday),
    )

    @Test
    fun monthNames() {
        assertEquals(listOf(LocalDate(2026, 12, 12)), parser.parse("Weihnachtsfeier am 12. Dezember", friday))
        assertEquals(listOf(LocalDate(2027, 3, 3)), parser.parse("Zahnarzt 3. März", friday))
        assertEquals(listOf(LocalDate(2027, 1, 15)), parser.parse("bis 15. Jan. 2027", friday))
    }

    @Test
    fun monthNameInThePastRollsOver() =
        assertEquals(listOf(LocalDate(2027, 11, 2)), parser.parse("am 2. November", friday))

    @Test
    fun inDaysAndWeeks() {
        assertEquals(listOf(LocalDate(2026, 11, 23)), parser.parse("in 3 Tagen", friday))
        assertEquals(listOf(LocalDate(2026, 12, 4)), parser.parse("in zwei Wochen zurückgeben", friday))
    }

    @Test
    fun umlautWordsAreWholeWords() {
        assertEquals(listOf(LocalDate(2026, 11, 22)), parser.parse("Übermorgen Kuchen backen", friday))
        assertEquals(emptyList(), parser.parse("Morgenkreis und Freitagsgebet", friday))
    }

    @Test
    fun vaguePhrasesGiveNoDate() =
        assertEquals(emptyList(), parser.parse("nächste Woche mal Oma anrufen", friday))

    @Test
    fun timeOfDayIsNotADate() =
        assertEquals(listOf(LocalDate(2026, 11, 21)), parser.parse("morgen um 14.30 Uhr", friday))

    @Test
    fun phraseIsReportedForTheReasonLine() =
        assertEquals("12.03.27", parser.find("bis 12.03.27 zahlen", friday).single().phrase)
}
