package io.github.respawnedguy.familyapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.respawnedguy.familyapp.update.UpdateController
import io.github.respawnedguy.familyapp.update.UpdateSection

/**
 * Placeholder start screen. The real look and click paths come from the design work;
 * this screen only proves that the shared Compose UI runs on the device.
 */
@Composable
fun App(versionName: String, updates: UpdateController? = null) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = AppInfo.NAME, style = MaterialTheme.typography.headlineMedium)
                Text(text = AppInfo.tagline, style = MaterialTheme.typography.bodyLarge)
                Text(text = AppInfo.versionLabel(versionName), style = MaterialTheme.typography.bodySmall)
                if (updates != null) {
                    UpdateSection(updates, modifier = Modifier.padding(top = 32.dp))
                }
            }
        }
    }
}

object AppInfo {
    const val NAME = "FamilyApp"
    const val tagline = "Schnell rein, komplett später."

    fun versionLabel(versionName: String): String =
        if (versionName.isBlank()) "Version unbekannt" else "Version $versionName"
}
