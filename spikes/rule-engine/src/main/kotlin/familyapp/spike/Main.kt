package familyapp.spike

import java.io.File
import java.time.LocalDate
import kotlin.system.exitProcess

/** Usage: <samples-dir> <rules-file> [expected.csv] [today=YYYY-MM-DD] */
fun main(args: Array<String>) {
    System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, Charsets.UTF_8))
    if (args.size < 2) {
        System.err.println("Usage: <samples-dir> <rules-file> [expected.csv] [today=YYYY-MM-DD]")
        exitProcess(2)
    }
    val samples = File(args[0]).listFiles { f -> f.extension in setOf("txt", "eml") }?.sortedBy { it.name }.orEmpty()
    val engine = RuleEngine(RuleFileParser.parse(File(args[1]).readText()))
    val today = args.firstOrNull { it.startsWith("today=") }?.substringAfter("=")?.let(LocalDate::parse) ?: LocalDate.now()
    val expectedFile = args.getOrNull(2)?.takeUnless { it.startsWith("today=") }

    println("file;status;rule_or_reason;person;owner;type;area;dates")
    val outcomes = samples.associate { file ->
        val outcome = engine.evaluate(SampleReader.read(file), today)
        val dates = outcome.dates.joinToString(",")
        val row = when (outcome) {
            is Outcome.Assigned -> with(outcome.rule.assignment) {
                listOf(file.name, "assigned", outcome.rule.id, person, owner, type, area, dates)
            }
            is Outcome.Open -> listOf(file.name, "open", outcome.reason, "", "", "", "", dates)
        }
        println(row.joinToString(";") { it?.toString() ?: "" })
        file.name to outcome
    }

    if (expectedFile != null) {
        val score = Evaluation.score(outcomes, Evaluation.parseExpected(File(expectedFile).readText()))
        println()
        println("total=${score.total} correctlyAssigned=${score.correctlyAssigned} correctlyOpen=${score.correctlyOpen} " +
            "openButAssignable=${score.openButAssignable} wronglyAssigned=${score.wronglyAssigned}")
        println("correctShare=${"%.0f".format(score.correctShare * 100)}% passes=${score.passes}")
    }
}
