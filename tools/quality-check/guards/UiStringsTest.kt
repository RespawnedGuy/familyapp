import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Quality plan P1-7: UI texts live in app/shared/src/commonMain/composeResources/values/strings.xml.
 *
 * Counts the text literals (string literals with letters, comments and string templates removed) in
 * the Compose UI files and compares them with config/ui-strings-baseline.txt. A file may never get
 * more literals than its baseline, a new UI file starts at 0. When a file gets fewer, lower its
 * number in the baseline in the same change, so the ratchet only moves down.
 */
class UiStringsTest {
    private val root = File("../../app/shared/src/commonMain")
    private val code = File(root, "kotlin/io/github/respawnedguy/familyapp")
    private val baselineFile = File("config/ui-strings-baseline.txt")

    /** Not UI text: product name, measurement tags. */
    private val ignored = setOf("FamilyApp", "Knopf")

    private fun uiFiles(): List<File> =
        (listOf(File(code, "App.kt"), File(code, "update/UpdateSection.kt")) +
            File(code, "ui").walk().filter { it.isFile && it.extension == "kt" && it.name != "Icons.kt" })
            .filter { it.exists() }
            .sortedBy { it.path }

    private fun baseline(): Map<String, Int> =
        baselineFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line -> line.substringBeforeLast(' ') to line.substringAfterLast(' ').toInt() }

    @Test fun uiTextsComeFromResources() {
        val budget = baseline()
        val problems = mutableListOf<String>()
        var total = 0
        for (file in uiFiles()) {
            val name = file.relativeTo(code).invariantSeparatorsPath
            val texts = textLiterals(file.readText()).filter { it !in ignored }
            total += texts.size
            val allowed = budget[name] ?: 0
            when {
                texts.size > allowed -> problems += "$name: ${texts.size} texts in code, at most $allowed allowed. " +
                    "Move them to composeResources/values/strings.xml: ${texts.take(5)}"
                texts.size < allowed -> println("UI-STRINGS $name: ${texts.size} < baseline $allowed, please lower config/ui-strings-baseline.txt")
            }
        }
        println("UI-STRINGS total=$total")
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test fun everyResourceNameIsUnique() {
        val xml = File(root, "composeResources/values/strings.xml").readText()
        val names = Regex("""name="(\w+)"""").findAll(xml).map { it.groupValues[1] }.toList()
        assertTrue(names.isNotEmpty(), "no strings found")
        val duplicates = names.groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue(duplicates.isEmpty(), "duplicate string names: $duplicates")
    }

    @Test fun scannerSeesOnlyRealTexts() {
        val sample = """
            // "Kommentar"
            /* "Block" */
            @Suppress("LongParameterList")
            val a = "Heute"
            val b = "${'$'}{x.name} · ${'$'}count"
            val c = "Hallo ${'$'}{name}!"
            val d = ""${'"'}Roh "text""${'"'}
            val e = "a\"b"
        """.trimIndent()
        val found = textLiterals(sample)
        assertTrue(found == listOf("Heute", "Hallo !", "Roh \"text", "a\\\"b"), "found $found")
    }
}

/** String literals that contain letters once comments, annotations and `$` templates are removed. */
fun textLiterals(source: String): List<String> {
    val out = mutableListOf<String>()
    var i = 0
    val n = source.length
    fun at(s: String) = source.startsWith(s, i)
    while (i < n) {
        when {
            at("//") -> i = source.indexOf('\n', i).let { if (it < 0) n else it }
            at("/*") -> i = source.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
            source[i] == '\'' -> i = source.indexOf('\'', i + if (at("'\\")) 3 else 1).let { if (it < 0) n else it + 1 }
            at("@Suppress(") -> i = source.indexOf(')', i).let { if (it < 0) n else it + 1 }
            at("\"\"\"") -> {
                val end = source.indexOf("\"\"\"", i + 3).let { if (it < 0) n else it }
                out += source.substring(i + 3, end)
                i = end + 3
            }
            source[i] == '"' -> {
                val sb = StringBuilder()
                i++
                var depth = 0
                while (i < n && (depth > 0 || source[i] != '"') && source[i] != '\n') {
                    val ch = source[i]
                    when {
                        depth == 0 && ch == '\\' && i + 1 < n -> { sb.append(ch).append(source[i + 1]); i += 2; continue }
                        depth == 0 && ch == '$' && i + 1 < n && source[i + 1] == '{' -> { depth = 1; i += 2; continue }
                        depth == 0 && ch == '$' -> { i++; while (i < n && (source[i].isLetterOrDigit() || source[i] == '_')) i++; continue }
                        depth > 0 && ch == '{' -> depth++
                        depth > 0 && ch == '}' -> depth--
                        depth == 0 -> sb.append(ch)
                    }
                    i++
                }
                i++
                out += sb.toString()
            }
            else -> i++
        }
    }
    return out.filter { text -> text.count { it.isLetter() } >= 2 }
}
