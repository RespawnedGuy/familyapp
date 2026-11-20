package familyapp.spike

import java.io.File

/**
 * Reads .txt/.eml samples. Header lines "From:" and "Subject:" are read until the first blank line;
 * everything after is the body. Files without headers are treated as body only (e.g. a transcribed letter).
 */
object SampleReader {

    fun read(file: File): Item = parse(file.name, file.readText())

    fun parse(id: String, raw: String): Item {
        val lines = raw.replace("\r\n", "\n").lines()
        var sender = ""
        var subject = ""
        var date: java.time.LocalDate? = null
        var bodyStart = 0
        val hasHeaders = lines.firstOrNull()?.contains(':') == true &&
            lines.first().substringBefore(':').trim().lowercase() in setOf("from", "von", "subject", "betreff", "date", "datum")
        if (hasHeaders) {
            for ((i, line) in lines.withIndex()) {
                if (line.isBlank()) { bodyStart = i + 1; break }
                val key = line.substringBefore(':').trim().lowercase()
                val value = line.substringAfter(':').trim()
                when (key) {
                    "from", "von" -> sender = Regex("""<([^>]+)>""").find(value)?.groupValues?.get(1) ?: value
                    "subject", "betreff" -> subject = value
                    "date", "datum" -> date = MailDate.parse(value)
                }
                bodyStart = i + 1
            }
        }
        val body = lines.drop(bodyStart).joinToString("\n")
        return unwrapForward(Item(id, sender, subject, body, date))
    }

    private val forwardMarker = Regex("""-{5,}\s*(Forwarded message|Weitergeleitete Nachricht)\s*-{5,}""", RegexOption.IGNORE_CASE)
    private val forwardFrom = Regex("""^(?:Von|From):\s*(.+)$""", RegexOption.MULTILINE)
    private val forwardDate = Regex("""^(?:Date|Datum|Gesendet|Sent):\s*(.+)$""", RegexOption.MULTILINE)
    private val forwardSubject = Regex("""^(?:Subject|Betreff):\s*(.+)$""", RegexOption.MULTILINE)

    /**
     * A mail forwarded to the family inbox carries the forwarder as sender. Rules must see the
     * original sender, so the first forwarded header block wins over the outer headers.
     */
    private fun unwrapForward(item: Item): Item {
        val marker = forwardMarker.find(item.body) ?: return item
        val block = item.body.substring(marker.range.last + 1).lineSequence().take(8).joinToString("\n")
        val from = forwardFrom.find(block)?.groupValues?.get(1)?.trim() ?: return item
        val address = Regex("""<([^>\s]+@[^>\s]+)>""").find(from)?.groupValues?.get(1)
            ?: Regex("""\S+@\S+""").find(from)?.value
            ?: return item
        val subject = forwardSubject.find(block)?.groupValues?.get(1)?.trim() ?: item.subject
        val date = forwardDate.find(block)?.groupValues?.get(1)?.let(MailDate::parse) ?: item.writtenOn
        return item.copy(sender = address, subject = subject, writtenOn = date)
    }
}
