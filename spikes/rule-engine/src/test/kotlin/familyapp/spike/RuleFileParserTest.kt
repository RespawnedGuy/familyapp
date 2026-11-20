package familyapp.spike

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RuleFileParserTest {
    @Test
    fun `parses conditions and assignment`() {
        val rules = RuleFileParser.parse(
            """
            # comment
            doctor | domain=praxis.de; contains=Mia, Lindenstraße 4 | person=Mia; type=appointment; area=Gesundheit
            """.trimIndent(),
        )
        val rule = rules.single()
        assertEquals("doctor", rule.id)
        assertEquals(
            listOf(Condition.SenderDomain("praxis.de"), Condition.ContainsAll(listOf("Mia", "Lindenstraße 4"))),
            rule.conditions,
        )
        assertEquals(Assignment(person = "Mia", type = ItemType.APPOINTMENT, area = "Gesundheit"), rule.assignment)
    }

    @Test
    fun `rejects unknown condition`() {
        assertFailsWith<IllegalArgumentException> { RuleFileParser.parse("x | color=red | person=Mia") }
    }

    @Test
    fun `rejects rule without condition`() {
        assertFailsWith<IllegalArgumentException> { RuleFileParser.parse("x |  | person=Mia") }
    }

    @Test
    fun `mode defaults to suggest for person rules and can be set`() {
        val rules = RuleFileParser.parse("a | domain=x.de | person=Kind A\nb | domain=x.de | person=Kind A; mode=auto\nc | domain=x.de | area=Schule")
        assertEquals(listOf(RuleMode.SUGGEST, RuleMode.AUTO, RuleMode.AUTO), rules.map { it.mode })
    }
}
