package familyapp.spike

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RuleEngineTest {
    private val today = LocalDate.of(2026, 11, 20)

    private val school = Rule(
        "school",
        listOf(Condition.SenderDomain("schulederkinder.de")),
        Assignment(area = "Schule", owner = "Lisa", type = ItemType.TASK),
    )
    private val doctorMia = Rule(
        "doctor-mia",
        listOf(Condition.ContainsAll(listOf("Mia", "Lindenstraße 4"))),
        Assignment(person = "Mia", area = "Gesundheit Kinder", type = ItemType.APPOINTMENT),
        RuleMode.AUTO, // child's name plus the practice address is a hard feature
    )
    private val teacher = Rule(
        "teacher",
        listOf(Condition.SenderAddress("frau.weber@schulederkinder.de")),
        Assignment(person = "Mia", area = "Schule", owner = "Nils", type = ItemType.INFO),
    )

    private fun item(sender: String = "", subject: String = "", body: String = "") = Item("x", sender, subject, body)

    @Test
    fun `assigns by sender domain`() {
        val outcome = RuleEngine(listOf(school)).evaluate(item(sender = "info@schulederkinder.de"), today)
        assertIs<Outcome.Assigned>(outcome)
        assertEquals("school", outcome.rule.id)
    }

    @Test
    fun `domain match is case insensitive and ignores leading at`() {
        val rule = school.copy(conditions = listOf(Condition.SenderDomain("@SchuleDerKinder.de")))
        assertIs<Outcome.Assigned>(RuleEngine(listOf(rule)).evaluate(item(sender = "a@schulederkinder.de"), today))
    }

    @Test
    fun `keyword rule needs all keywords`() {
        val engine = RuleEngine(listOf(doctorMia))
        assertIs<Outcome.Open>(engine.evaluate(item(body = "Mia hat Turnen"), today))
        assertIs<Outcome.Assigned>(engine.evaluate(item(body = "Termin für Mia, Praxis Lindenstraße 4"), today))
    }

    @Test
    fun `no matching rule leaves item open`() {
        val outcome = RuleEngine(listOf(school)).evaluate(item(sender = "shop@example.com"), today)
        assertIs<Outcome.Open>(outcome)
        assertEquals(Outcome.Reason.NO_RULE, outcome.reason)
    }

    @Test
    fun `more specific rule wins`() {
        val outcome = RuleEngine(listOf(school, teacher.copy(mode = RuleMode.AUTO)))
            .evaluate(item(sender = "frau.weber@schulederkinder.de"), today)
        assertIs<Outcome.Assigned>(outcome)
        assertEquals("teacher", outcome.rule.id)
    }

    @Test
    fun `equally specific rules with different assignments stay open`() {
        val other = school.copy(id = "school-2", assignment = Assignment(owner = "Nils"))
        val outcome = RuleEngine(listOf(school, other)).evaluate(item(sender = "a@schulederkinder.de"), today)
        assertIs<Outcome.Open>(outcome)
        assertEquals(Outcome.Reason.CONFLICTING_RULES, outcome.reason)
    }

    @Test
    fun `equally specific rules with the same assignment are not a conflict`() {
        val twin = school.copy(id = "school-twin")
        assertIs<Outcome.Assigned>(RuleEngine(listOf(school, twin)).evaluate(item(sender = "a@schulederkinder.de"), today))
    }

    @Test
    fun `due date is set only when exactly one date is found`() {
        val engine = RuleEngine(listOf(school))
        val one = engine.evaluate(item(sender = "a@schulederkinder.de", body = "Abgabe bis 12.03.2027"), today)
        assertIs<Outcome.Assigned>(one)
        assertEquals(LocalDate.of(2027, 3, 12), one.dueDate)
        val two = engine.evaluate(item(sender = "a@schulederkinder.de", body = "am 01.12. oder 03.12."), today)
        assertIs<Outcome.Assigned>(two)
        assertEquals(null, two.dueDate)
    }

    @Test
    fun `relative dates use the date the item was written`() {
        val written = LocalDate.of(2026, 9, 10) // a Thursday
        val outcome = RuleEngine(listOf(school))
            .evaluate(Item("x", "a@schulederkinder.de", "", "ab Montag, dem 14.09.", written), today)
        assertEquals(listOf(LocalDate.of(2026, 9, 14)), outcome.dates)
    }

    @Test
    fun `rule naming a person only suggests by default`() {
        val outcome = RuleEngine(listOf(teacher)).evaluate(item(sender = "frau.weber@schulederkinder.de"), today)
        assertIs<Outcome.Open>(outcome)
        assertEquals(Outcome.Reason.SUGGESTION, outcome.reason)
        assertEquals("teacher", outcome.candidates.single().id)
    }

    @Test
    fun `person rule marked auto assigns directly`() {
        val hard = Rule(
            "class1",
            listOf(Condition.SenderDomain("schulederkinder.de"), Condition.ContainsAll(listOf("Eltern der Klasse 1"))),
            Assignment(person = "Kind A", area = "Schule"),
            RuleMode.AUTO,
        )
        assertIs<Outcome.Assigned>(
            RuleEngine(listOf(school, hard)).evaluate(item(sender = "a@schulederkinder.de", body = "Liebe Eltern der Klasse 1"), today),
        )
    }

    @Test
    fun `tie between auto and suggest with same assignment assigns via the hard feature`() {
        val a = Assignment(person = "Kind A")
        val auto = Rule("auto", listOf(Condition.SenderAddress("x@y.de")), a, RuleMode.AUTO)
        val suggest = Rule("suggest", listOf(Condition.ContainsAll(listOf("foo", "bar"))), a, RuleMode.SUGGEST)
        val outcome = RuleEngine(listOf(suggest, auto)).evaluate(item(sender = "x@y.de", body = "foo bar"), today)
        assertIs<Outcome.Assigned>(outcome)
        assertEquals("auto", outcome.rule.id)
    }
}
