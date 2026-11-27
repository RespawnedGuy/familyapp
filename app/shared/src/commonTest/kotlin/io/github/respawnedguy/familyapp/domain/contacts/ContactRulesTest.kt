package io.github.respawnedguy.familyapp.domain.contacts

import io.github.respawnedguy.familyapp.domain.Stamp
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContactRulesTest {

    @Test
    fun sameNumberSameKeyWhateverTheSpelling() {
        val key = "+49351123456"
        listOf("0351 123456", "+49 351 123456", "0049 351/123456", "(0351) 12 34 56", "+49-351-123456").forEach {
            assertEquals(key, ContactRules.phoneKey(it), it)
        }
        assertEquals("123", ContactRules.phoneKey("123"))
    }

    @Test
    fun kt2_dialableKeepsOnlyDigitsAndLeadingPlus() {
        assertEquals("+49351123", ContactRules.dialable("+49 (351) 123"))
        assertEquals("0351123", ContactRules.dialable("0351/123"))
        assertNull(ContactRules.cleanPhone("*#"))
        assertNull(ContactRules.cleanPhone("  "))
        assertEquals("+49 170 1", ContactRules.cleanPhone(" +49 170 1;p;w "))
    }

    @Test
    fun mailAddressesAreChecked() {
        assertEquals("a@example.org", ContactRules.cleanEmail(" mailto:a@example.org "))
        assertNull(ContactRules.cleanEmail("kein at"))
        assertNull(ContactRules.cleanEmail("a@b"))
        assertNull(ContactRules.cleanEmail("\"x\"@example.org"))
        assertNull(ContactRules.cleanEmail("a".repeat(250) + "@example.org"))
    }

    @Test
    fun birthdaysInGermanAndIsoFormat() {
        assertEquals(LocalDate(2019, 12, 24), ContactRules.parseBirthday("24.12.2019"))
        assertEquals(LocalDate(2019, 12, 24), ContactRules.parseBirthday("2019-12-24"))
        assertEquals(LocalDate(2019, 12, 24), ContactRules.parseBirthday("20191224"))
        assertNull(ContactRules.parseBirthday("--1224"), "no year, no birthday")
        assertNull(ContactRules.parseBirthday("31.02.2019"))
        assertNull(ContactRules.parseBirthday("01.01.1800"))
        assertNull(ContactRules.parseBirthday(null))
        assertEquals("04.03.2020", ContactRules.formatBirthday(LocalDate(2020, 3, 4)))
    }

    @Test
    fun cleanFallsBackToOrganisationThenNumber() {
        assertEquals("Hort Sonnenschein", ContactRules.clean(ContactDraft(" ", organization = "Hort Sonnenschein"))?.name)
        assertEquals("0351 1", ContactRules.clean(ContactDraft("", phones = listOf("0351 1")))?.name)
        assertNull(ContactRules.clean(ContactDraft("", emails = listOf("a@example.org"))))
        val twice = ContactRules.clean(ContactDraft("A", phones = listOf("0351 1", "+49 351 1"), emails = listOf("A@x.de", "a@x.de")))!!
        assertEquals(listOf("0351 1"), twice.phones)
        assertEquals(listOf("A@x.de"), twice.emails)
        assertNull(ContactRules.clean(ContactDraft("A", birthday = LocalDate(1700, 1, 1)))?.birthday)
        assertEquals("Zeile eins Zeile zwei", ContactRules.field("Zeile eins\nZeile\tzwei"))
    }

    @Test
    fun rolesAreSuggestedFromOrganisationAndName() {
        fun role(org: String?, name: String = "X") = ContactRules.suggestRole(ContactDraft(name, organization = org))
        assertEquals("Kinderarztpraxis", role("Praxis für Kinder- und Jugendmedizin"))
        assertEquals("Kinderarztpraxis", role("Kinderarztpraxis am Park"))
        assertEquals("Zahnarztpraxis", role("Zahnarztpraxis Weiß"))
        assertEquals("Arztpraxis", role("Hausarzt"))
        assertEquals("Arztpraxis", role("Gemeinschaftspraxis"))
        assertEquals("Kita", role("Kita Regenbogen"))
        assertEquals("Hort", role("Hort an der Grundschule"))
        assertEquals("Schule", role("Grundschule am Park"))
        assertEquals("Verein", role("TSV Süd"))
        assertEquals("", role(null, "Oma"))
        assertEquals("", role("Horten AG"), "not a whole word")
    }

    @Test
    fun mentionsNeedTheWholeName() {
        val c = Contact("c", "Dr. Lindner", organization = "Praxis", createdMs = 0, createdBy = "", stamp = Stamp(0, 0, "d"))
        assertTrue(ContactRules.mentions("U9 bei Dr. Lindner vereinbaren", c))
        assertTrue(ContactRules.mentions("Rezept von der Praxis holen", c))
        assertFalse(ContactRules.mentions("Lindnerstraße sperren", c))
        val short = c.copy(name = "Ben", organization = null)
        assertFalse(ContactRules.mentions("Ben abholen", short), "names under four characters are too common")
    }
}
