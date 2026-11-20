package familyapp.spike

/**
 * Plain-text rule format, one rule per line:
 *   id | domain=schulederkinder.de; sender=a@b.de; contains=Mia,Kinderarzt | person=Mia; owner=Lisa; type=APPOINTMENT; area=Schule; mode=auto
 * mode is optional (auto|suggest); without it, rules naming a person only suggest.
 * Lines starting with '#' and blank lines are ignored.
 */
object RuleFileParser {

    fun parse(text: String): List<Rule> = text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapIndexed { index, line -> parseLine(line, index + 1) }

    private fun parseLine(line: String, lineNo: Int): Rule {
        val parts = line.split("|").map { it.trim() }
        require(parts.size == 3) { "Line $lineNo: expected 'id | conditions | assignment'" }
        val (id, conditionPart, assignmentPart) = parts
        val assignmentPairs = pairs(assignmentPart, lineNo).toMap()
        val assignment = parseAssignment(assignmentPairs - "mode", lineNo)
        val conditions = parseConditions(conditionPart, lineNo)
        val mode = assignmentPairs["mode"]?.let { RuleMode.valueOf(it.uppercase()) }
        return if (mode == null) Rule(id, conditions, assignment) else Rule(id, conditions, assignment, mode)
    }

    private fun pairs(part: String, lineNo: Int): List<Pair<String, String>> = part.split(";")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map {
            val key = it.substringBefore("=", "").trim()
            require(key.isNotEmpty()) { "Line $lineNo: expected key=value in '$it'" }
            key.lowercase() to it.substringAfter("=").trim()
        }

    private fun parseConditions(part: String, lineNo: Int): List<Condition> = pairs(part, lineNo).map { (key, value) ->
        when (key) {
            "domain" -> Condition.SenderDomain(value)
            "sender" -> Condition.SenderAddress(value)
            "contains" -> Condition.ContainsAll(value.split(",").map { it.trim() }.filter { it.isNotEmpty() })
            else -> throw IllegalArgumentException("Line $lineNo: unknown condition '$key'")
        }
    }

    private fun parseAssignment(map: Map<String, String>, lineNo: Int): Assignment {
        val unknown = map.keys - setOf("person", "owner", "type", "area")
        require(unknown.isEmpty()) { "Line $lineNo: unknown assignment keys $unknown" }
        return Assignment(
            person = map["person"],
            owner = map["owner"],
            type = map["type"]?.let { ItemType.valueOf(it.uppercase()) },
            area = map["area"],
        )
    }
}
