package familyapp.spike

/** A single deterministic condition. Specificity decides which rule wins when several match. */
sealed interface Condition {
    val specificity: Int
    fun matches(item: Item): Boolean

    /** Matches the sender's domain, e.g. "schulederkinder.de". */
    data class SenderDomain(val domain: String) : Condition {
        override val specificity = 1
        override fun matches(item: Item) = item.senderDomain == domain.removePrefix("@").lowercase()
    }

    /** Matches one exact sender address. */
    data class SenderAddress(val address: String) : Condition {
        override val specificity = 2
        override fun matches(item: Item) = item.sender.equals(address, ignoreCase = true)
    }

    /** Matches when every keyword occurs in subject or body (case-insensitive). */
    data class ContainsAll(val keywords: List<String>) : Condition {
        override val specificity = keywords.size
        override fun matches(item: Item): Boolean {
            val text = item.fullText.lowercase()
            return keywords.all { text.contains(it.lowercase()) }
        }
    }
}

/** AUTO assigns directly; SUGGEST leaves the item open with a pre-filled proposal (HITL). */
enum class RuleMode { AUTO, SUGGEST }

/**
 * All conditions must match (logical AND).
 * Default mode: a rule that names a person only suggests; it assigns directly only when marked AUTO,
 * which is meant for hard features such as "Eltern der Klasse 1" or the child's name.
 */
data class Rule(
    val id: String,
    val conditions: List<Condition>,
    val assignment: Assignment,
    val mode: RuleMode = if (assignment.person == null) RuleMode.AUTO else RuleMode.SUGGEST,
) {
    init {
        require(conditions.isNotEmpty()) { "Rule $id needs at least one condition" }
    }

    val specificity: Int get() = conditions.sumOf { it.specificity }
    fun matches(item: Item) = conditions.all { it.matches(item) }
}
