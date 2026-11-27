package io.github.respawnedguy.familyapp.domain.contacts

/** What a shared vCard file gave: the contacts, and how many were left out or unusable. */
data class VCardResult(val drafts: List<ContactDraft>, val skipped: Int, val tooLarge: Boolean = false)

/**
 * Reads vCards (RFC 2426 / 6350, plus the 2.1 dialect Android's contacts app still shares, with
 * quoted-printable umlauts). Any app can share a .vcf here, so the file is untrusted
 * (threat model KT-1, KT-2): size and number of contacts are capped, PHOTO and every field we do not
 * use are dropped before they are even decoded, and each value goes through [ContactRules.clean].
 */
object VCard {
    /** A contact without photo is a few hundred bytes; 1 MB leaves room for 20 with long addresses. */
    const val MAX_CHARS = 1_000_000

    /** More than this per share is an address-book dump, not "family-relevant contacts" (concept). */
    const val MAX_CONTACTS = 20

    private val USED = setOf("FN", "N", "TEL", "EMAIL", "ADR", "ORG", "BDAY")

    fun parse(text: String): VCardResult {
        if (text.length > MAX_CHARS) return VCardResult(emptyList(), 0, tooLarge = true)
        val drafts = mutableListOf<ContactDraft>()
        var skipped = 0
        var card: MutableList<Property>? = null
        for (line in logicalLines(text)) {
            val upper = line.trim().uppercase()
            when {
                upper == "BEGIN:VCARD" -> card = mutableListOf()
                upper == "END:VCARD" -> {
                    val draft = card?.let(::toDraft)?.let(ContactRules::clean)
                    when {
                        draft == null -> skipped++
                        drafts.size >= MAX_CONTACTS -> skipped++
                        else -> drafts += draft
                    }
                    card = null
                }
                card != null -> property(line)?.let { card += it }
            }
        }
        return VCardResult(drafts, skipped)
    }

    private class Property(val name: String, val params: List<String>, val raw: String) {
        private val quotedPrintable = params.any { p ->
            val u = p.uppercase()
            u == "QUOTED-PRINTABLE" || u == "ENCODING=QUOTED-PRINTABLE"
        }

        /** The value with encoding undone, structured parts still separated by unescaped `;`. */
        fun parts(): List<String> {
            val decoded = if (quotedPrintable) decodeQuotedPrintable(raw) else raw
            return splitUnescaped(decoded, ';').map(::unescape)
        }

        fun value(): String = parts().joinToString(" ").trim()
    }

    /**
     * Unfolds continuation lines: a line starting with a space or tab continues the previous one
     * (3.0/4.0); in quoted-printable a line ending with `=` continues with the next one (2.1).
     */
    private fun logicalLines(text: String): List<String> {
        val out = mutableListOf<String>()
        var softBreak = false
        for (line in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            when {
                softBreak && out.isNotEmpty() -> out[out.lastIndex] = out.last().dropLast(1) + line
                (line.startsWith(" ") || line.startsWith("\t")) && out.isNotEmpty() ->
                    out[out.lastIndex] = out.last() + line.substring(1)
                else -> out += line
            }
            val last = out.lastOrNull().orEmpty()
            softBreak = last.endsWith("=") && last.substringBefore(':').uppercase().contains("QUOTED-PRINTABLE")
        }
        return out
    }

    /** "item1.TEL;TYPE=CELL:0170 …" → TEL with its parameters; unused properties are skipped here. */
    private fun property(line: String): Property? {
        val colon = line.indexOf(':')
        val head = line.substring(0, colon.coerceAtLeast(0)).split(';')
        val name = head.first().substringAfterLast('.').trim().uppercase()
        return if (colon > 0 && name in USED) Property(name, head.drop(1), line.substring(colon + 1)) else null
    }

    private fun toDraft(props: List<Property>): ContactDraft {
        fun first(name: String) = props.firstOrNull { it.name == name }
        fun all(name: String) = props.filter { it.name == name }.take(ContactRules.MAX_VALUES * 2)
        val structuredName = first("N")?.parts()?.let { p ->
            // N = family;given;additional;prefix;suffix
            listOfNotNull(p.getOrNull(1), p.getOrNull(0)).filter { it.isNotBlank() }.joinToString(" ")
        }
        val address = first("ADR")?.parts()?.let { p ->
            // ADR = pobox;extended;street;locality;region;postal code;country
            val street = p.getOrNull(ADR_STREET).orEmpty()
            val city = listOfNotNull(p.getOrNull(ADR_POSTAL_CODE), p.getOrNull(ADR_LOCALITY))
                .filter { it.isNotBlank() }.joinToString(" ")
            listOf(street, city).filter { it.isNotBlank() }.joinToString(", ")
        }
        return ContactDraft(
            name = first("FN")?.value()?.takeIf { it.isNotBlank() } ?: structuredName.orEmpty(),
            phones = all("TEL").map { it.value().removePrefix("tel:") },
            emails = all("EMAIL").map { it.value() },
            address = address,
            organization = first("ORG")?.parts()?.filter { it.isNotBlank() }?.joinToString(" · "),
            birthday = ContactRules.parseBirthday(first("BDAY")?.value()),
        )
    }

    private fun splitUnescaped(s: String, sep: Char): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        for (ch in s) {
            when {
                escaped -> { current.append('\\').append(ch); escaped = false }
                ch == '\\' -> escaped = true
                ch == sep -> { out += current.toString(); current.clear() }
                else -> current.append(ch)
            }
        }
        if (escaped) current.append('\\')
        out += current.toString()
        return out
    }

    private fun unescape(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                val next = s[i + 1]
                out.append(if (next == 'n' || next == 'N') ' ' else next)
                i += 2
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }

    /** `=C3=BC` → ü (bytes decoded as UTF-8, which is what Android writes); broken sequences stay as text. */
    private fun decodeQuotedPrintable(s: String): String {
        val bytes = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            val hex = if (ch == '=' && i + 2 <= s.lastIndex) s.substring(i + 1, i + QP_ESCAPE_LENGTH) else null
            val byte = hex?.toIntOrNull(HEX)
            if (byte != null) {
                bytes += byte.toByte()
                i += QP_ESCAPE_LENGTH
            } else {
                ch.toString().encodeToByteArray().forEach { bytes += it }
                i++
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    private const val HEX = 16

    /** "=C3": the equals sign and two hex digits. */
    private const val QP_ESCAPE_LENGTH = 3

    // Positions in ADR = pobox;extended;street;locality;region;postal code;country
    private const val ADR_STREET = 2
    private const val ADR_LOCALITY = 3
    private const val ADR_POSTAL_CODE = 5
}
