package familyapp.recipe

import java.io.File
import kotlin.system.exitProcess

/**
 * Usage: <samples-dir> [expected.csv]
 * Each sample is a folder with caption.txt and optionally transcript.txt / ocr.txt.
 */
fun main(args: Array<String>) {
    System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, Charsets.UTF_8))
    if (args.isEmpty()) {
        System.err.println("Usage: <samples-dir> [expected.csv]")
        exitProcess(2)
    }
    val samples = File(args[0]).listFiles { f -> f.isDirectory }?.sortedBy { it.name }.orEmpty()
    val expected = args.getOrNull(1)?.let { ExpectedFile.parse(File(it).readText()) }

    val scores = mutableListOf<SampleScore>()
    println("id;status;title;servings;minutes;ingredients;steps")
    for (dir in samples) {
        val parsed = parseSample(dir)
        println(listOf(dir.name, parsed.status, parsed.title, parsed.servings, parsed.totalMinutes, parsed.ingredients.size, parsed.steps.size)
            .joinToString(";") { it?.toString() ?: "" })
        expected?.get(dir.name)?.let { scores += Evaluation.score(dir.name, parsed, it) }
    }

    if (expected != null) {
        println()
        println("id;expected;found;amountUnitOk;invented;missing;wrongAmount")
        scores.forEach { s ->
            println("${s.id};${s.expected};${s.found};${s.amountAndUnitCorrect};${s.invented};${s.missing.joinToString(" | ")};${s.wrongAmount.joinToString(" | ")}")
        }
        val t = TotalScore(scores)
        println()
        println("samples=${scores.size} expectedLines=${t.expected} found=${t.found} amountUnitOk=${t.amountAndUnitCorrect} invented=${t.invented}")
        println("recall=${pct(t.recall)} amountUnitShare=${pct(t.amountShare)} passes=${t.passes}")
        val missingSamples = samples.map { it.name }.filter { it !in expected }
        if (missingSamples.isNotEmpty()) println("noExpectation=${missingSamples.joinToString(",")}")
    }
}

/**
 * Each source is parsed on its own (caption, then on-screen text, then speech). The ingredient list comes from the
 * first source that has one, the steps from the first source with steps. Mixing sources line by line would let a
 * transcript sentence slip into the caption's ingredient list.
 */
fun parseSample(dir: File): ParsedRecipe {
    val parsed = listOf("caption.txt", "ocr.txt", "transcript.txt")
        .map { File(dir, it) }
        .filter { it.exists() }
        .map { RecipeTextParser.parse(it.readText()) }
    if (parsed.isEmpty()) return RecipeTextParser.parse("")
    val withIngredients = parsed.firstOrNull { it.ingredients.isNotEmpty() } ?: parsed.first()
    val steps = withIngredients.steps.ifEmpty { parsed.firstOrNull { it.steps.isNotEmpty() }?.steps.orEmpty() }
    val status = when {
        withIngredients.ingredients.isEmpty() -> ParsedRecipe.Status.NO_INGREDIENTS
        steps.isEmpty() -> ParsedRecipe.Status.INGREDIENTS_ONLY
        else -> ParsedRecipe.Status.COMPLETE
    }
    return withIngredients.copy(
        title = parsed.first().title ?: withIngredients.title,
        servings = parsed.firstNotNullOfOrNull { it.servings },
        totalMinutes = parsed.firstNotNullOfOrNull { it.totalMinutes },
        steps = steps,
        status = status,
    )
}

private fun pct(x: Double) = "%.0f%%".format(x * 100)
