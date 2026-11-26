import io.github.respawnedguy.familyapp.domain.CaptureRules
import kotlinx.datetime.LocalDate
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Quality plan P1-5: measures the date suggestion against a corpus of texts, like the rule spike.
 * Public corpus must have zero wrong dates; a private corpus of real transcripts
 * (corpus/dates-private.csv, git-ignored) is measured and printed when present.
 */
class DateCorpusTest {
    private data class Row(val today: LocalDate, val text: String, val expected: String)
    private data class Result(val total: Int, val correct: Int, val wrong: List<String>, val missed: List<String>)

    private fun load(file: File): List<Row> = file.readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line ->
            val parts = line.split(';')
            Row(LocalDate.parse(parts[0]), parts[1], parts.getOrElse(2) { "" }.trim())
        }

    private fun measure(rows: List<Row>): Result {
        val rules = CaptureRules()
        var correct = 0
        val wrong = mutableListOf<String>()
        val missed = mutableListOf<String>()
        for (r in rows) {
            val due = rules.analyze(r.text, emptyList(), r.today).due
            when {
                r.expected == "?" || r.expected.isEmpty() -> if (due == null) correct++ else wrong += "${r.text} → $due"
                due == null -> missed += r.text
                due.toString() == r.expected -> correct++
                else -> wrong += "${r.text} → $due (erwartet ${r.expected})"
            }
        }
        return Result(rows.size, correct, wrong, missed)
    }

    private fun report(name: String, r: Result) {
        println("DATES $name n=${r.total} richtig=${r.correct} falsch=${r.wrong.size} verpasst=${r.missed.size}")
        r.wrong.forEach { println("  FALSCH  $it") }
        r.missed.forEach { println("  OFFEN   $it") }
    }

    @Test
    fun publicCorpusHasNoWrongDate() {
        val r = measure(load(File("corpus/dates.csv")))
        report("public", r)
        assertEquals(emptyList(), r.wrong, "a wrong date is worse than none")
    }

    @Test
    fun privateCorpusIsMeasuredWhenPresent() {
        val file = File("corpus/dates-private.csv")
        if (file.exists()) report("private", measure(load(file)))
    }
}
