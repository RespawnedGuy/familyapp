package io.github.respawnedguy.familyapp.domain

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DateFormatTest {
    private val friday = LocalDate(2026, 11, 20)

    @Test
    fun shortAndChip() {
        assertEquals("Do 26.11.", DateFormat.short(LocalDate(2026, 11, 26)))
        assertEquals("Heute", DateFormat.chip(friday, friday))
        assertEquals("Morgen", DateFormat.chip(LocalDate(2026, 11, 21), friday))
        assertEquals("Mo 23.11.", DateFormat.chip(LocalDate(2026, 11, 23), friday))
    }

    @Test
    fun overdueIsWrittenOut() {
        assertNull(DateFormat.overdue(friday, friday))
        assertEquals("überfällig seit gestern", DateFormat.overdue(LocalDate(2026, 11, 19), friday))
        assertEquals("überfällig seit 5 Tagen", DateFormat.overdue(LocalDate(2026, 11, 15), friday))
    }

    @Test
    fun isoWeeksIncludingYearBoundaries() {
        assertEquals(47, DateFormat.isoWeek(friday))
        assertEquals(53, DateFormat.isoWeek(LocalDate(2026, 12, 31))) // 2026 has 53 ISO weeks
        assertEquals(53, DateFormat.isoWeek(LocalDate(2027, 1, 3)))
        assertEquals(1, DateFormat.isoWeek(LocalDate(2027, 1, 4)))
        assertEquals(1, DateFormat.isoWeek(LocalDate(2024, 12, 30)))
    }

    @Test
    fun weekLabel() {
        assertEquals("KW 48 · 23.–29. Nov.", DateFormat.weekLabel(LocalDate(2026, 11, 23)))
        assertEquals("KW 49 · 30. Nov. – 6. Dez.", DateFormat.weekLabel(LocalDate(2026, 11, 30)))
    }
}
