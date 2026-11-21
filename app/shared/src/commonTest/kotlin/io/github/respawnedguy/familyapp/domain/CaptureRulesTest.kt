package io.github.respawnedguy.familyapp.domain

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureRulesTest {
    private val rules = CaptureRules()
    private val friday = LocalDate(2026, 11, 20)
    private val s = Stamp(0, 0, "d")
    private val mia = Member("m1", "Mia", 1, MemberKind.CHILD, stamp = s)
    private val ben = Member("m2", "Ben", 2, MemberKind.CHILD, stamp = s)
    private val members = listOf(mia, ben)

    @Test
    fun nameAndDateAreSuggestedWithReasons() {
        val r = rules.analyze("Mia braucht bis Donnerstag neue Hausschuhe", members, friday)
        assertEquals("m1", r.concerns)
        assertEquals(LocalDate(2026, 11, 26), r.due)
        assertEquals(listOf("Name „Mia“ im Text", "„Donnerstag“ → Do 26.11."), r.reasons)
    }

    @Test
    fun genitiveAndLowercaseMatch() {
        assertEquals("m2", rules.analyze("bens Turnbeutel waschen", members, friday).concerns)
        assertEquals("m1", rules.analyze("Mias Zeugnis unterschreiben", members, friday).concerns)
    }

    @Test
    fun nameInsideAnotherWordDoesNotMatch() =
        assertNull(rules.analyze("Benzin tanken, Miami buchen", members, friday).concerns)

    @Test
    fun twoNamesSuggestNobody() {
        val r = rules.analyze("Mia und Ben zum Zahnarzt", members, friday)
        assertNull(r.concerns)
        assertTrue(r.reasons.single().startsWith("Mehrere Namen erkannt"))
    }

    @Test
    fun twoDatesSuggestNoDate() {
        val r = rules.analyze("Elternabend am 3.12. oder am 10.12.", members, friday)
        assertNull(r.due)
        assertTrue(r.reasons.single().startsWith("Mehrere Daten erkannt"))
    }

    @Test
    fun nothingFoundMeansNoReasons() =
        assertEquals(Suggestions(), rules.analyze("Milch kaufen", members, friday))
}
