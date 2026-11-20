package familyapp.spike

/**
 * Expected result for one sample, written by a person (ground truth).
 * person: null = not checked, [NO_PERSON] = must have no person (e.g. a school-wide letter).
 */
data class Expected(val file: String, val assigned: Boolean, val person: String?, val type: ItemType?)

const val NO_PERSON = "-"

data class Score(
    val total: Int,
    val correctlyAssigned: Int,
    val wronglyAssigned: Int,
    val correctlyOpen: Int,
    val openButAssignable: Int,
) {
    val correctShare: Double get() = if (total == 0) 0.0 else (correctlyAssigned + correctlyOpen).toDouble() / total
    val passes: Boolean get() = correctShare >= 0.8 && wronglyAssigned == 0
}

object Evaluation {

    /** CSV with header: file;expected(assigned|open);person;type */
    fun parseExpected(text: String): List<Expected> = text.lines().drop(1)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            val c = line.split(";").map { it.trim() } + listOf("", "", "")
            Expected(
                file = c[0],
                assigned = c[1].equals("assigned", ignoreCase = true),
                person = c[2].ifEmpty { null },
                type = c[3].ifEmpty { null }?.let { ItemType.valueOf(it.uppercase()) },
            )
        }

    fun score(outcomes: Map<String, Outcome>, expected: List<Expected>): Score {
        var correctlyAssigned = 0
        var wronglyAssigned = 0
        var correctlyOpen = 0
        var openButAssignable = 0
        expected.forEach { e ->
            when (val o = outcomes[e.file]) {
                is Outcome.Assigned -> {
                    val a = o.rule.assignment
                    val ok = e.assigned &&
                        (e.person == null || e.person == (a.person ?: NO_PERSON)) &&
                        (e.type == null || e.type == a.type)
                    if (ok) correctlyAssigned++ else wronglyAssigned++
                }
                is Outcome.Open -> if (e.assigned) openButAssignable++ else correctlyOpen++
                null -> throw IllegalArgumentException("No sample for expected entry '${e.file}'")
            }
        }
        return Score(expected.size, correctlyAssigned, wronglyAssigned, correctlyOpen, openButAssignable)
    }
}
