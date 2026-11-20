package familyapp.spike

import kotlin.test.Test
import kotlin.test.assertEquals

class SampleReaderTest {
    @Test
    fun `reads headers and body`() {
        val item = SampleReader.parse("a.eml", "From: Schule <info@schulederkinder.de>\nSubject: Basar\n\nHallo\nzusammen")
        assertEquals("info@schulederkinder.de", item.sender)
        assertEquals("schulederkinder.de", item.senderDomain)
        assertEquals("Basar", item.subject)
        assertEquals("Hallo\nzusammen", item.body)
    }

    @Test
    fun `file without headers is body only`() {
        val item = SampleReader.parse("brief.txt", "Sehr geehrte Familie,\nTermin am 12.03.")
        assertEquals("", item.sender)
        assertEquals("Sehr geehrte Familie,\nTermin am 12.03.", item.body)
    }

    @Test
    fun `forwarded mail uses the original sender and subject`() {
        val raw = """
            From: Nils <nils@example.com>
            Subject: Fwd: Radfahrausbildung

            ---------- Forwarded message ---------
            Von: Lehrerin <lehrerin@schule.example>
            Date: Do., 10. Sept. 2026 um 16:09 Uhr
            Subject: Radfahrausbildung

            Liebe Eltern
        """.trimIndent()
        val item = SampleReader.parse("f.eml", raw)
        assertEquals("lehrerin@schule.example", item.sender)
        assertEquals("Radfahrausbildung", item.subject)
    }

    @Test
    fun `quoted reply without forward marker keeps outer sender`() {
        val raw = "From: Nils <nils@example.com>\nSubject: Re: Termin\n\nDanke!\nVon: Lehrerin <lehrerin@schule.example>"
        assertEquals("nils@example.com", SampleReader.parse("r.eml", raw).sender)
    }

    @Test
    fun `reads written date from header and from forwarded block`() {
        val plain = SampleReader.parse("a.eml", "From: a@b.de\nDate: Wed, 18 Nov 2026 15:32:04 +0000\n\nHallo")
        assertEquals(java.time.LocalDate.of(2026, 11, 18), plain.writtenOn)
        val fwd = SampleReader.parse(
            "f.eml",
            "From: n@x.de\nDate: Sun, 11 Oct 2026 16:55:08 +0200\n\n---------- Forwarded message ---------\n" +
                "Von: L <l@schule.example>\nDate: Do., 10. Sept. 2026 um 16:09 Uhr\nSubject: Rad\n\nText",
        )
        assertEquals(java.time.LocalDate.of(2026, 9, 10), fwd.writtenOn)
    }
}
