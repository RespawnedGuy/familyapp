package io.github.respawnedguy.familyapp

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.github.respawnedguy.familyapp.domain.ItemStatus
import io.github.respawnedguy.familyapp.presentation.AppModel
import io.github.respawnedguy.familyapp.presentation.FamilySection
import io.github.respawnedguy.familyapp.presentation.Tab
import io.github.respawnedguy.familyapp.preview.rememberPreviewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Quality plan P1-4/P1-6: semantic accessibility checks on the real screens with sample data.
 * Runs on the JVM (Compose Desktop), so it needs no emulator; Android-only checks such as the
 * Accessibility Test Framework come later with the emulator job.
 */
@OptIn(ExperimentalTestApi::class)
class AccessibilityTest {

    private fun ComposeUiTest.showApp(): AppModel {
        lateinit var model: AppModel
        setContent {
            model = rememberPreviewModel()
            App(versionName = "test", model = model, photos = null, reducedMotion = true)
        }
        waitForIdle()
        return model
    }

    /**
     * Everything tappable must offer at least 48 × 48 dp (Material; WCAG 2.5.8 asks 24 dp).
     * Touch bounds include the extra area Material adds around 40 dp buttons. The window is a
     * phone width but very tall, so no node is cut off by the window edge.
     */
    private fun ComposeUiTest.smallTouchTargets(): List<String> {
        val minPx = 47.5f * density.density
        return onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .filter { it.touchBoundsInRoot.width < minPx || it.touchBoundsInRoot.height < minPx }
            .map { node ->
                val w = node.touchBoundsInRoot.width / density.density
                val h = node.touchBoundsInRoot.height / density.density
                "${label(node)} (${w.toInt()} × ${h.toInt()} dp)"
            }
    }

    private fun tallPhone(block: ComposeUiTest.() -> Unit) = runDesktopComposeUiTest(width = 411, height = 4000) { block() }

    private fun label(node: SemanticsNode): String {
        val c = node.config
        return c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: c.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
            ?: c.getOrNull(SemanticsActions.OnClick)?.label
            ?: node.children.firstNotNullOfOrNull { child -> child.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } }
            ?: "Knoten ${node.id}"
    }

    @Test
    fun touchTargetsOnTodayAreLargeEnough() = tallPhone {
        showApp()
        assertEquals(emptyList(), smallTouchTargets())
    }

    @Test
    fun touchTargetsInSettingsAreLargeEnough() = tallPhone {
        val model = showApp()
        runOnIdle { model.openSettings() }
        waitForIdle()
        assertEquals(emptyList(), smallTouchTargets())
    }

    /** Every tab under Familie (Nils, 27.11.2026) with the sample family, offers and recipes. */
    @Test
    fun touchTargetsOnEveryFamilyTabAreLargeEnough() = tallPhone {
        val model = showApp()
        FamilySection.entries.forEach { section ->
            runOnIdle { model.openSection(section) }
            waitForIdle()
            assertEquals(emptyList(), smallTouchTargets(), "tab $section")
        }
    }

    @Test
    fun familyTabsAnnounceWhichOneIsSelected() = runComposeUiTest {
        val model = showApp()
        runOnIdle { model.select(Tab.FAMILY) }
        waitForIdle()
        onAllNodes(isSelectable() and isSelected() and hasText("Aufgaben")).assertCountEquals(1)
        onAllNodes(isSelectable() and hasText("Einkauf")).assertCountEquals(1)
        runOnIdle { model.openSection(FamilySection.SHOPPING) }
        waitForIdle()
        onAllNodes(isSelectable() and isSelected() and hasText("Einkauf")).assertCountEquals(1)
        onAllNodes(isSelectable() and isSelected() and hasText("Aufgaben")).assertCountEquals(0)
    }

    @Test
    fun shoppingTabShowsSuggestionsListAndOffersInThatOrder() = tallPhone {
        val model = showApp()
        runOnIdle { model.openSection(FamilySection.SHOPPING) }
        waitForIdle()
        fun top(text: String) = onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes()
            .also { assertTrue(it.isNotEmpty(), "„$text“ is shown") }.first().boundsInRoot.top
        val suggestions = top("Vorgeschlagene Rezepte")
        val list = top("Einkaufsliste")
        val offers = top("Angebote in der Nähe")
        assertTrue(suggestions < list && list < offers, "suggestions, then the list, then the offers")
        top("Hokkaido Kürbis, Stück")
        top("Grünkohl")
    }

    @Test
    fun bottomBarTabsAnnounceWhichOneIsSelected() = runComposeUiTest {
        showApp()
        val tabs = onAllNodes(isSelectable()).fetchSemanticsNodes()
        assertTrue(tabs.size >= 4, "four tabs are selectable, found ${tabs.size}")
        onAllNodes(isSelectable() and isSelected() and hasText("Heute")).assertCountEquals(1)
        onAllNodes(isSelectable() and isSelected() and hasText("Suchen")).assertCountEquals(0)
    }

    @Test
    fun sectionTitlesAreHeadings() = runComposeUiTest {
        showApp()
        val headings = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNodes()
        assertTrue(headings.isNotEmpty(), "screen readers can jump between sections")
    }

    @Test
    fun feedbackIsAnnouncedByScreenReaders() = runComposeUiTest {
        val model = showApp()
        mainClock.autoAdvance = false
        runOnIdle { model.delete(model.repo.current.items.first { it.status == ItemStatus.INBOX }) }
        mainClock.advanceTimeBy(500)
        val live = onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion) and
                hasAnyDescendant(hasText("Gelöscht.", substring = true)),
        )
        live.assertCountEquals(1)
    }
}
