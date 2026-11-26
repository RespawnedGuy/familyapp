package io.github.respawnedguy.familyapp

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.respawnedguy.familyapp.preview.rememberPreviewModel

/**
 * Desktop window for UI work with Compose Hot Reload: the real shared UI over the sample
 * family from the previews, so code changes show up instantly in a running app.
 *
 * Start in Android Studio with the gutter icon next to main() → "Run … with Compose Hot Reload",
 * or `./gradlew :shared:hotRunJvm --auto`. Development only: no microphone, camera or folders,
 * and changes to the sample data are lost when the window closes.
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "FamilyApp Dev (Desktop)",
        // Roughly a Pixel phone in dp, so layouts look like on the phone.
        state = rememberWindowState(width = 412.dp, height = 900.dp),
        alwaysOnTop = true,
    ) {
        App(versionName = "dev", model = rememberPreviewModel(), photos = null, reducedMotion = false)
    }
}
