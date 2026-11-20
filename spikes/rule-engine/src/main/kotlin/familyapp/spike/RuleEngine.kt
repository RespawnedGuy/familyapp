package familyapp.spike

import java.time.LocalDate

sealed interface Outcome {
    val dates: List<LocalDate>

    /** Exactly one most specific rule matched: assigned without asking (100 % unambiguous). */
    data class Assigned(val rule: Rule, override val dates: List<LocalDate>) : Outcome {
        val dueDate: LocalDate? get() = dates.singleOrNull()
    }

    /** Stays open in the inbox until a person confirms it or marks it irrelevant. */
    data class Open(val reason: Reason, val candidates: List<Rule>, override val dates: List<LocalDate>) : Outcome

    enum class Reason { NO_RULE, CONFLICTING_RULES }
}

class RuleEngine(private val rules: List<Rule>, private val dateParser: GermanDateParser = GermanDateParser()) {

    fun evaluate(item: Item, today: LocalDate): Outcome {
        val dates = dateParser.parse(item.fullText, item.writtenOn ?: today)
        val matching = rules.filter { it.matches(item) }
        if (matching.isEmpty()) return Outcome.Open(Outcome.Reason.NO_RULE, emptyList(), dates)

        val best = matching.maxOf { it.specificity }
        val winners = matching.filter { it.specificity == best }
        val distinctAssignments = winners.map { it.assignment }.distinct()
        return if (distinctAssignments.size == 1) {
            Outcome.Assigned(winners.first(), dates)
        } else {
            Outcome.Open(Outcome.Reason.CONFLICTING_RULES, winners, dates)
        }
    }
}
