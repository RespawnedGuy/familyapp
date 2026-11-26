package io.github.respawnedguy.familyapp

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import io.github.respawnedguy.familyapp.preview.rememberPreviewModel
import kotlin.test.Test
import kotlin.test.assertTrue

/** Keeps the desktop dev window (Compose Hot Reload) working: the shared UI renders on the JVM. */
@OptIn(ExperimentalTestApi::class)
class DevWindowTest {
    @Test
    fun sharedUiRendersWithSampleFamily() = runComposeUiTest {
        setContent { App(versionName = "dev", model = rememberPreviewModel(), photos = null, reducedMotion = false) }
        assertTrue(onAllNodesWithText("Heute").fetchSemanticsNodes().isNotEmpty(), "bottom bar with \"Heute\" is shown")
    }
}
