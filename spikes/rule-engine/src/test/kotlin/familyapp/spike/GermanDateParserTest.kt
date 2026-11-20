package familyapp.spike

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class GermanDateParserTest {
    private val parser = GermanDateParser()
    private val friday = LocalDate.of(2026, 11, 20) // a Friday

    @Test
    fun `full date with four digit year`() =
        assertEquals(listOf(LocalDate.of(2027, 3, 12)), parser.parse("Elternabend am 12.03.2027", friday))

    @Test
    fun `full date with two digit year`() =
        assertEquals(listOf(LocalDate.of(2027, 3, 12)), parser.parse("bis 12.03.27 zahlen", friday))

    @Test
    fun `short date in the future stays in this year`() =
        assertEquals(listOf(LocalDate.of(2026, 12, 4)), parser.parse("Basar am 4.12.", friday))

    @Test
    fun `short date in the past rolls over to next year`() =
        assertEquals(listOf(LocalDate.of(2027, 3, 1)), parser.parse("Abgabe 01.03.", friday))

    @Test
    fun `relative words`() = assertEquals(
        listOf(friday, friday.plusDays(1), friday.plusDays(2)),
        parser.parse("heute, morgen oder übermorgen", friday),
    )

    @Test
    fun `weekday means next occurrence after today`() {
        assertEquals(listOf(LocalDate.of(2026, 11, 24)), parser.parse("bis Dienstag", friday))
        assertEquals(listOf(LocalDate.of(2026, 11, 27)), parser.parse("am Freitag", friday))
    }

    @Test
    fun `invalid dates are ignored`() = assertEquals(emptyList(), parser.parse("am 31.02.2027", friday))

    @Test
    fun `version numbers and amounts are not dates`() =
        assertEquals(emptyList(), parser.parse("Kosten 12,50 Euro, Version 1.2", friday))

    @Test
    fun `duplicates are merged and sorted`() = assertEquals(
        listOf(LocalDate.of(2026, 12, 4), LocalDate.of(2027, 1, 8)),
        parser.parse("08.01.2027 und 4.12. und nochmal 04.12.2026", friday),
    )
}
