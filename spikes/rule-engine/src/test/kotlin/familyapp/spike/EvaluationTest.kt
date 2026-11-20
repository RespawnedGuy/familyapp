package familyapp.spike

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvaluationTest {
    private val rule = Rule("r", listOf(Condition.SenderDomain("a.de")), Assignment(person = "Mia", type = ItemType.TASK))

    @Test
    fun `counts correct and wrong assignments`() {
        val outcomes = mapOf(
            "1" to Outcome.Assigned(rule, emptyList()),
            "2" to Outcome.Assigned(rule, emptyList()),
            "3" to Outcome.Open(Outcome.Reason.NO_RULE, emptyList(), emptyList()),
            "4" to Outcome.Open(Outcome.Reason.NO_RULE, emptyList(), emptyList()),
        )
        val expected = listOf(
            Expected("1", true, "Mia", ItemType.TASK),
            Expected("2", true, "Ben", null),
            Expected("3", false, null, null),
            Expected("4", true, null, null),
        )
        val score = Evaluation.score(outcomes, expected)
        assertEquals(Score(4, 1, 1, 1, 1), score)
        assertFalse(score.passes)
    }

    @Test
    fun `passes at 80 percent without wrong assignments`() =
        assertTrue(Score(5, 3, 0, 1, 1).passes)

    @Test
    fun `parses expected csv`() {
        val list = Evaluation.parseExpected("file;expected;person;type\na.eml;assigned;Mia;task\nb.txt;open;;")
        assertEquals(listOf(Expected("a.eml", true, "Mia", ItemType.TASK), Expected("b.txt", false, null, null)), list)
    }

    @Test
    fun `dash means the item must not be assigned to a person`() {
        val outcomes = mapOf("1" to Outcome.Assigned(rule, emptyList()))
        val score = Evaluation.score(outcomes, listOf(Expected("1", true, NO_PERSON, null)))
        assertEquals(1, score.wronglyAssigned)
    }

    @Test
    fun `correct suggestion counts as correct, wrong suggestion is not a false assignment`() {
        val open = { r: Rule -> Outcome.Open(Outcome.Reason.SUGGESTION, listOf(r), emptyList()) }
        val score = Evaluation.score(
            mapOf("1" to open(rule), "2" to open(rule)),
            listOf(Expected("1", true, "Mia", null), Expected("2", true, NO_PERSON, null)),
        )
        assertEquals(1, score.correctlySuggested)
        assertEquals(1, score.wronglySuggested)
        assertEquals(0, score.wronglyAssigned)
    }
}
