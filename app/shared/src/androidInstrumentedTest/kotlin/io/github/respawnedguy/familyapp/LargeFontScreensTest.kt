package io.github.respawnedguy.familyapp

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.DarkMode
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.then
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.test.platform.app.InstrumentationRegistry
import io.github.respawnedguy.familyapp.presentation.CaptureState
import io.github.respawnedguy.familyapp.presentation.Tab
import io.github.respawnedguy.familyapp.preview.PreviewData
import io.github.respawnedguy.familyapp.preview.rememberPreviewModel
import io.github.respawnedguy.familyapp.ui.CaptureSheet
import io.github.respawnedguy.familyapp.ui.FamilyTheme
import io.github.respawnedguy.familyapp.ui.LocalFamilyColors
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import kotlin.math.roundToInt

/**
 * Quality plan P1-4: every screen at 100 % and 200 % font size, light and dark, on the emulator
 * (scripts/emulator-font-scale.sh). Each scrollable screen is paged through; on every page the test
 *
 * - finds text that is cut off: ellipsis or overflow in the text layout, or clipped by its container,
 * - finds words split in the middle because a single word does not fit the width,
 * - finds text that overlaps other text,
 * - runs the Accessibility Test Framework (Android 14+, the Compose integration needs API 34),
 * - saves a screenshot, so a person can look at the result (CI artifact).
 *
 * Deliberately shortened free text (ellipsis after two or more lines, full text one tap away) is
 * listed as a note, not as a failure.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(Parameterized::class)
class LargeFontScreensTest(private val case: Case) {

    enum class Screen(val fileName: String) {
        TODAY("heute"),
        TODAY_EMPTY("heute-leer"),
        FAMILY("familie"),
        UPCOMING("demnaechst"),
        SEARCH("suchen"),
        SETTINGS("einstellungen"),
        CAPTURE_VOICE("erfassen-sprechen"),
        CAPTURE_TEXT("erfassen-tippen"),
        ONBOARDING("start"),
    }

    data class Case(val screen: Screen, val fontScale: Float, val dark: Boolean) {
        val name = "${screen.fileName}-schrift${(fontScale * 100).roundToInt()}-${if (dark) "dunkel" else "hell"}"
        override fun toString() = name
    }

    companion object {
        private const val TAG = "LargeFontScreens"
        private const val MAX_PAGES = 15
        private const val PAGE_FRACTION = 0.8f

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Case> = Screen.entries.flatMap { screen ->
            listOf(1f, 2f).flatMap { scale -> listOf(false, true).map { dark -> Case(screen, scale, dark) } }
        }
    }

    private val outDir: File by lazy {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null) ?: context.filesDir, "schrift").apply { mkdirs() }
    }

    @Test
    fun textFitsOnEveryPage() = runComposeUiTest {
        val atf = Build.VERSION.SDK_INT >= 34
        if (atf) enableAccessibilityChecks()
        setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.FontScale(case.fontScale) then DeviceConfigurationOverride.DarkMode(case.dark),
            ) { ScreenUnderTest(case.screen) }
        }
        waitForIdle()

        val problems = linkedSetOf<String>()
        val notes = linkedSetOf<String>()
        for (page in 1..MAX_PAGES) {
            checkTexts(problems, notes)
            if (atf) {
                @Suppress("TooGenericExceptionCaught") // ATF throws its own exception type; any failure is a finding
                try {
                    onRoot().tryPerformAccessibilityChecks()
                } catch (e: Throwable) {
                    problems += "Accessibility Test Framework: " + (e.message ?: e.toString()).take(1500)
                }
            }
            screenshot(page)
            if (!scrollOnePage()) break
        }

        val report = buildString {
            appendLine("== ${case.name} (API ${Build.VERSION.SDK_INT})")
            problems.forEach { appendLine("FEHLER  $it") }
            notes.forEach { appendLine("HINWEIS $it") }
            if (problems.isEmpty()) appendLine("OK      kein abgeschnittener, zerbrochener oder überlappender Text")
        }
        File(outDir, "${case.name}.txt").writeText(report)
        Log.i(TAG, report)
        assertTrue(report, problems.isEmpty())
    }

    // ---- checks ------------------------------------------------------------------------------

    private class TextNode(val node: SemanticsNode, val text: String, val layout: TextLayoutResult?, val rect: Rect)

    private fun ComposeUiTest.checkTexts(problems: MutableSet<String>, notes: MutableSet<String>) {
        val nodes = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes()
        val root = onRoot(useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val dp = density.density
        val texts = mutableListOf<TextNode>()
        runOnUiThread {
            for (node in nodes) {
                val layouts = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                val layout = layouts.firstOrNull()
                val text = layout?.layoutInput?.text?.text
                    ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
                    ?: continue
                if (text.isBlank()) continue
                val pos = node.positionInRoot
                texts += TextNode(node, text, layout, Rect(pos.x, pos.y, pos.x + node.size.width, pos.y + node.size.height))
            }
        }

        val visible = mutableListOf<TextNode>()
        for (t in texts) {
            val label = "„${t.text.take(60)}“"
            t.layout?.let { checkLayout(it, label, problems, notes) }
            if (t.rect.right > root.right + dp || t.rect.left < root.left - dp) {
                problems += "$label ragt seitlich über den Bildschirm"
                continue
            }
            // Partly outside the scrolled area: shown fully on another page.
            val viewport = viewportOf(t.node) ?: root
            if (t.rect.top < viewport.top - 1 || t.rect.bottom > viewport.bottom + 1) continue
            val clipped = t.node.boundsInRoot
            if (clipped.width < t.rect.width - dp || clipped.height < t.rect.height - dp) {
                problems += "$label wird vom umgebenden Element abgeschnitten"
                continue
            }
            if (t.node.config.getOrNull(SemanticsProperties.EditableText) == null) visible += t
        }

        for (i in visible.indices) {
            for (j in i + 1 until visible.size) {
                val a = visible[i]
                val b = visible[j]
                if (isAncestor(a.node, b.node) || isAncestor(b.node, a.node)) continue
                val overlap = a.rect.intersect(b.rect)
                if (overlap.width > 2 * dp && overlap.height > 2 * dp) {
                    problems += "„${a.text.take(40)}“ überlappt „${b.text.take(40)}“"
                }
            }
        }
    }

    private fun checkLayout(layout: TextLayoutResult, label: String, problems: MutableSet<String>, notes: MutableSet<String>) {
        val text = layout.layoutInput.text.text
        val lines = layout.lineCount
        val ellipsized = lines > 0 && layout.isLineEllipsized(lines - 1)
        if (ellipsized || layout.hasVisualOverflow) {
            val deliberate = layout.layoutInput.overflow == TextOverflow.Ellipsis && layout.layoutInput.maxLines >= 2
            if (deliberate) {
                notes += "$label nach ${layout.layoutInput.maxLines} Zeilen mit „…“ gekürzt (freier Text, ganz per Tippen)"
            } else {
                problems += "$label ist abgeschnitten (${if (ellipsized) "„…“" else "passt nicht in die Fläche"})"
            }
        }
        for (line in 0 until lines - 1) {
            if (layout.isLineEllipsized(line)) continue
            val end = layout.getLineEnd(line)
            if (end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()) {
                problems += "$label: Wort mitten getrennt („${text.substring(maxOf(0, end - 8), end)}|${text.substring(end, minOf(text.length, end + 8))}“)"
            }
        }
    }

    private fun viewportOf(node: SemanticsNode): Rect? {
        var p = node.parent
        while (p != null) {
            if (p.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null) return p.boundsInRoot
            p = p.parent
        }
        return null
    }

    private fun isAncestor(ancestor: SemanticsNode, node: SemanticsNode): Boolean {
        var p = node.parent
        while (p != null) {
            if (p.id == ancestor.id) return true
            p = p.parent
        }
        return false
    }

    // ---- paging and screenshots --------------------------------------------------------------

    /** Scrolls the largest vertical list by most of its height. False when it is at the end. */
    private fun ComposeUiTest.scrollOnePage(): Boolean {
        val scrollable = onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().maxByOrNull { it.boundsInRoot.width * it.boundsInRoot.height } ?: return false
        val range = scrollable.config[SemanticsProperties.VerticalScrollAxisRange]
        val before = range.value()
        if (before >= range.maxValue() - 1f) return false
        runOnUiThread {
            scrollable.config[SemanticsActions.ScrollBy].action?.invoke(0f, scrollable.boundsInRoot.height * PAGE_FRACTION)
        }
        waitForIdle()
        return range.value() > before + 1f
    }

    private fun ComposeUiTest.screenshot(page: Int) {
        val bitmap = onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "${case.name}-s$page.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

@Composable
private fun ScreenUnderTest(screen: LargeFontScreensTest.Screen) {
    val data = when (screen) {
        LargeFontScreensTest.Screen.TODAY_EMPTY -> PreviewData.justCreated()
        LargeFontScreensTest.Screen.ONBOARDING -> PreviewData.fresh()
        else -> PreviewData.family()
    }
    val model = rememberPreviewModel(data) {
        when (screen) {
            LargeFontScreensTest.Screen.FAMILY -> select(Tab.FAMILY)
            LargeFontScreensTest.Screen.UPCOMING -> select(Tab.UPCOMING)
            LargeFontScreensTest.Screen.SEARCH -> select(Tab.SEARCH)
            LargeFontScreensTest.Screen.SETTINGS -> openSettings()
            else -> Unit
        }
    }
    val sheet = when (screen) {
        LargeFontScreensTest.Screen.CAPTURE_VOICE -> CaptureState.Listening(partial = "Hausschuhe für Mia bis Donnerstag", ready = true)
        LargeFontScreensTest.Screen.CAPTURE_TEXT -> CaptureState.Typing
        else -> null
    }
    Box(Modifier.fillMaxSize()) {
        // With the sheet open only the sheet is checked; the screen behind it is covered.
        if (sheet == null) {
            App(versionName = "0.0.0-test", model = model, photos = null, reducedMotion = true)
        } else {
            FamilyTheme {
                Box(Modifier.fillMaxSize().background(LocalFamilyColors.current.background))
                CaptureSheet(sheet, model, reducedMotion = true)
            }
        }
    }
}
