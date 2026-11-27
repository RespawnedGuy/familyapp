package io.github.respawnedguy.familyapp.domain.contacts

import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

/**
 * Deterministic rules for contact data: cleaning what comes in (picker, vCard, form), the key that
 * finds duplicates, and the role a link suggests. Whatever comes from outside is untrusted
 * (threat model KT-1, KT-2), so every field is cut and checked here, in one place.
 */
object ContactRules {
    const val MAX_FIELD_CHARS = 200
    const val MAX_VALUES = 5
    private const val MAX_PHONE_CHARS = 32
    private const val MAX_EMAIL_CHARS = 254
    private const val MIN_MENTION_CHARS = 4
    private const val FIRST_YEAR = 1900
    private const val LAST_YEAR = 2100

    /** Words a person picks from when linking (concept, "Verknüpfung n:m mit Rolle"). Free text is fine too. */
    val ROLE_PRESETS = listOf(
        "Kinderarztpraxis", "Zahnarztpraxis", "Arztpraxis", "Schule", "Kita", "Hort",
        "Verein", "Babysitter", "Notfall", "Familie", "Freunde",
    )

    /** A draft with every field cut and checked; null when nothing usable is left (no name). */
    fun clean(draft: ContactDraft): ContactDraft? {
        val phones = draft.phones.mapNotNull(::cleanPhone).distinctBy(::phoneKey).take(MAX_VALUES)
        val emails = draft.emails.mapNotNull(::cleanEmail).distinctBy { it.lowercase() }.take(MAX_VALUES)
        val organization = field(draft.organization)
        val name = field(draft.name) ?: organization ?: phones.firstOrNull() ?: return null
        return ContactDraft(
            name = name,
            phones = phones,
            emails = emails,
            address = field(draft.address),
            organization = organization,
            birthday = draft.birthday?.takeIf { it.year in FIRST_YEAR..LAST_YEAR },
        )
    }

    /** One line, control characters removed, at most [MAX_FIELD_CHARS]. */
    fun field(raw: String?): String? =
        raw?.replace(Regex("""[\u0000-\u001F\u007F]+"""), " ")?.replace(Regex("""\s+"""), " ")?.trim()
            ?.take(MAX_FIELD_CHARS)?.takeIf { it.isNotEmpty() }

    /**
     * A phone number as shown: digits, a leading +, spaces and the usual separators. Everything else
     * goes (letters, `*`, `#`, `;`, `,`), so a number can never carry a service code or a pause
     * sequence into the dialler (KT-2).
     */
    fun cleanPhone(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        val kept = trimmed.filter { it.isDigit() || it in PHONE_SEPARATORS }.replace(Regex("""\s+"""), " ").trim()
        val sign = if (trimmed.startsWith("+")) "+" else ""
        return (sign + kept).take(MAX_PHONE_CHARS).takeIf { kept.any { it.isDigit() } }
    }

    private const val PHONE_SEPARATORS = " /-()"

    /** What goes into a `tel:` link: only digits and a leading +. */
    fun dialable(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return if (phone.trim().startsWith("+")) "+$digits" else digits
    }

    /**
     * Same number, same key: "0351 123", "+49 351 123" and "0049351123" all become "+49351123".
     * German numbers without a country code are assumed (the family is in Germany, KONZEPT).
     */
    fun phoneKey(phone: String): String {
        val d = dialable(phone)
        return when {
            d.startsWith("+") -> d
            d.startsWith("00") -> "+" + d.drop(2)
            d.startsWith("0") -> "+49" + d.drop(1)
            else -> d
        }
    }

    private val EMAIL = Regex("""^[^\s@<>()",;:]+@[^\s@<>()",;:]+\.[^\s@<>()",;:]+$""")

    fun cleanEmail(raw: String?): String? =
        raw?.trim()?.removePrefix("mailto:")?.takeIf { it.length <= MAX_EMAIL_CHARS && EMAIL.matches(it) }

    /** "24.12.2019", "2019-12-24" or "20191224"; anything else (and dates without a year) gives null. */
    fun parseBirthday(raw: String?): LocalDate? {
        val s = raw?.trim().orEmpty()
        // Both patterns capture day, month and year under the same names.
        val match = GERMAN_DATE.find(s) ?: ISO_DATE.find(s)
        fun part(name: String) = match?.groups?.get(name)?.value?.toIntOrNull() ?: 0
        // Month 0 is never valid, so a missing match ends in null as well.
        return runCatching { LocalDate(part("y"), part("m"), part("d")) }.getOrNull()
            ?.takeIf { it.year in FIRST_YEAR..LAST_YEAR }
    }

    private val GERMAN_DATE = Regex("""^(?<d>\d{1,2})\.(?<m>\d{1,2})\.(?<y>\d{4})$""")
    private val ISO_DATE = Regex("""^(?<y>\d{4})-?(?<m>\d{2})-?(?<d>\d{2})""")

    /** "24.12.2019" for the form and the contact page. */
    fun formatBirthday(date: LocalDate): String =
        "${date.day.toString().padStart(2, '0')}.${date.month.number.toString().padStart(2, '0')}.${date.year}"

    /**
     * Suggested role from organisation and name. Only a suggestion: the person sees it in the role
     * field and changes it (HITL). Order matters: the paediatrician before any doctor.
     */
    fun suggestRole(draft: ContactDraft): String {
        val text = listOfNotNull(draft.organization, draft.name).joinToString(" ").lowercase()
        return ROLE_RULES.firstOrNull { it.matches(text) }?.role.orEmpty()
    }

    /** A role and the words that suggest it: [parts] anywhere, [words] as whole words, [endings] at the end. */
    private class RoleRule(
        val role: String,
        val parts: List<String>,
        val words: List<String> = emptyList(),
        val endings: List<String> = emptyList(),
    ) {
        fun matches(text: String) = parts.any { text.contains(it) } ||
            words.any { Regex("""\b$it\b""").containsMatchIn(text) } ||
            endings.any { text.endsWith(it) }
    }

    private val ROLE_RULES = listOf(
        RoleRule("Kinderarztpraxis", listOf("kinderarzt", "kinderärzt", "kinder- und jugend", "pädiat", "paediat")),
        RoleRule("Zahnarztpraxis", listOf("zahnarzt", "zahnärzt", "kieferorthop", "zahnmedizin")),
        RoleRule(
            "Arztpraxis",
            listOf("praxis", "dr. med", "ärztin", "arzt ", "allgemeinmedizin"),
            endings = listOf("arzt"),
        ),
        RoleRule("Kita", listOf("kita", "kindergarten", "kindertages", "krippe")),
        RoleRule("Hort", emptyList(), words = listOf("hort")),
        RoleRule("Schule", listOf("schule", "gymnasium")),
        RoleRule("Verein", listOf("verein", "sportclub"), words = listOf("tsv", "sv", "fc")),
    )

    /**
     * Does [title] mention this contact? Name or organisation, at least four characters, as a
     * whole word sequence. Used for "Offene Aufgaben dazu" on the contact page (a search, not a link).
     */
    fun mentions(title: String, contact: Contact): Boolean {
        val lower = title.lowercase()
        return listOfNotNull(contact.name, contact.organization)
            .map { it.lowercase().trim() }
            .filter { it.length >= MIN_MENTION_CHARS }
            .any { needle -> Regex("""(^|\W)${Regex.escape(needle)}(\W|$)""").containsMatchIn(lower) }
    }
}
