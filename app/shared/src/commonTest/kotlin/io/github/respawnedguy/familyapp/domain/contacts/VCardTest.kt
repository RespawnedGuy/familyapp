package io.github.respawnedguy.familyapp.domain.contacts

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shared vCards are untrusted input (threat model KT-1, KT-2). All names and numbers are invented. */
class VCardTest {

    @Test
    fun androidContactsAppWithQuotedPrintableUmlauts() {
        // Shape of what Android's contacts app shares (vCard 2.1, umlauts as quoted-printable UTF-8).
        val vcf = """
            BEGIN:VCARD
            VERSION:2.1
            N;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:Lindner;J=C3=BClia;;Dr.;
            FN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:Dr. J=C3=BClia Lindner
            TEL;CELL:0170 1234567
            TEL;WORK:+49 351 7654321
            EMAIL;HOME:praxis@example.org
            ORG;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:Kinderarztpraxis am M=C3=BChlgraben
            ADR;HOME;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:;;M=C3=BChlgraben 3;Dresden;;01099;
            PHOTO;ENCODING=BASE64;JPEG:/9j/4AAQSkZJRgABAQAAAQABAAD
             AAAAAAAAAAAAAAA
            END:VCARD
        """.trimIndent()
        val result = VCard.parse(vcf)
        val c = result.drafts.single()
        assertEquals("Dr. Jülia Lindner", c.name)
        assertEquals(listOf("0170 1234567", "+49 351 7654321"), c.phones)
        assertEquals(listOf("praxis@example.org"), c.emails)
        assertEquals("Kinderarztpraxis am Mühlgraben", c.organization)
        assertEquals("Mühlgraben 3, 01099 Dresden", c.address)
        assertEquals(0, result.skipped)
    }

    @Test
    fun softLineBreaksInQuotedPrintableAreJoined() {
        val vcf = "BEGIN:VCARD\r\nVERSION:2.1\r\nFN;ENCODING=QUOTED-PRINTABLE;CHARSET=UTF-8:Gro=C3=9Fmutter =\r\nM=C3=BCller\r\nEND:VCARD\r\n"
        assertEquals("Großmutter Müller", VCard.parse(vcf).drafts.single().name)
    }

    @Test
    fun version3WithFoldingEscapesGroupsAndBirthday() {
        val vcf = """
            BEGIN:VCARD
            VERSION:3.0
            FN:Trainer Ben\, TSV Süd
            N:Ben;Trainer;;;
            item1.TEL;TYPE=CELL:+49 160 
             9876543
            item1.X-ABLabel:Mobil
            EMAIL;TYPE=INTERNET:trainer@example.org
            BDAY:1985-04-12
            NOTE:Darf nicht übernommen werden
            END:VCARD
        """.trimIndent()
        val c = VCard.parse(vcf).drafts.single()
        assertEquals("Trainer Ben, TSV Süd", c.name)
        assertEquals(listOf("+49 160 9876543"), c.phones)
        assertEquals(LocalDate(1985, 4, 12), c.birthday)
    }

    @Test
    fun version4TelUriAndStructuredNameWithoutFn() {
        val vcf = "BEGIN:VCARD\nVERSION:4.0\nN:Muster;Oma;;;\nTEL;VALUE=uri;TYPE=home:tel:+49-351-111222\nEND:VCARD"
        val c = VCard.parse(vcf).drafts.single()
        assertEquals("Oma Muster", c.name)
        assertEquals(listOf("+49-351-111222"), c.phones)
    }

    @Test
    fun kt2_serviceCodesAndForeignSchemesDoNotSurvive() {
        val vcf = "BEGIN:VCARD\nFN:Falle\nTEL:*#06#\nTEL:0351 1;ext=99,,*21*\nEMAIL:javascript:alert(1)\nEND:VCARD"
        val c = VCard.parse(vcf).drafts.single()
        assertEquals(listOf("06", "0351 1 9921"), c.phones, "only digits and separators stay")
        assertTrue(c.phones.none { it.contains('*') || it.contains('#') || it.contains(',') || it.contains(';') })
        assertTrue(c.emails.isEmpty())
    }

    @Test
    fun kt1_tooLargeFileIsRefusedBeforeParsing() {
        val result = VCard.parse("BEGIN:VCARD\nFN:A\n" + "X".repeat(VCard.MAX_CHARS) + "\nEND:VCARD")
        assertTrue(result.tooLarge)
        assertTrue(result.drafts.isEmpty())
    }

    @Test
    fun kt1_atMostTwentyContactsPerShare() {
        val vcf = (1..25).joinToString("\n") { "BEGIN:VCARD\nFN:Kontakt $it\nTEL:0351 $it\nEND:VCARD" }
        val result = VCard.parse(vcf)
        assertEquals(VCard.MAX_CONTACTS, result.drafts.size)
        assertEquals(5, result.skipped)
    }

    @Test
    fun kt1_cardsWithoutNameNumberOrOrganisationAreSkipped() {
        val result = VCard.parse("BEGIN:VCARD\nNOTE:nur Notiz\nEND:VCARD\nBEGIN:VCARD\nORG:Hort Sonnenschein\nEND:VCARD")
        assertEquals(1, result.skipped)
        assertEquals("Hort Sonnenschein", result.drafts.single().name)
    }

    @Test
    fun kt1_longFieldsAreCutAndValuesCapped() {
        val phones = (1..12).joinToString("\n") { "TEL:0351 10$it" }
        val c = VCard.parse("BEGIN:VCARD\nFN:${"N".repeat(500)}\n$phones\nEND:VCARD").drafts.single()
        assertEquals(ContactRules.MAX_FIELD_CHARS, c.name.length)
        assertEquals(ContactRules.MAX_VALUES, c.phones.size)
    }

    @Test
    fun brokenQuotedPrintableAndLinesWithoutColonStayHarmless() {
        val vcf = "BEGIN:VCARD\nFN;ENCODING=QUOTED-PRINTABLE:A=ZZb=4\nGARBAGE\nEND:VCARD"
        assertEquals("A=ZZb=4", VCard.parse(vcf).drafts.single().name)
    }

    @Test
    fun textWithoutVCardGivesNothing() {
        val result = VCard.parse("Hallo, hier ist kein Kontakt.")
        assertTrue(result.drafts.isEmpty())
        assertEquals(0, result.skipped)
        assertNull(result.drafts.firstOrNull())
    }
}
