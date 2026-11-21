package io.github.respawnedguy.familyapp.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Placeholder UI for updates until the design work defines where it lives. */
@Composable
fun UpdateSection(controller: UpdateController, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    var autoCheck by remember { mutableStateOf(controller.autoCheckEnabled) }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Updates", style = MaterialTheme.typography.titleMedium)

            when (val s = state) {
                UpdateState.Idle -> Text("Noch nicht geprüft.")
                UpdateState.Checking -> Text("Suche nach Updates …")
                UpdateState.UpToDate -> Text("Die App ist aktuell.")
                UpdateState.NothingPublished -> Text("Die App ist aktuell. Es gibt noch keine neuere Version.")
                is UpdateState.Available -> {
                    Text("Update auf Version ${s.manifest.versionName} verfügbar.")
                    if (s.manifest.releaseNotes.isNotBlank()) {
                        Text(s.manifest.releaseNotes, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { controller.install(s.manifest) }) { Text("Installieren") }
                }
                is UpdateState.DeviceTooOld ->
                    Text("Version ${s.manifest.versionName} braucht ein neueres Android als dieses Gerät.")
                is UpdateState.Downloading -> {
                    Text("Lade Version ${s.manifest.versionName} …")
                    LinearProgressIndicator(progress = { s.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
                is UpdateState.NeedsPermission -> {
                    Text("Android muss FamilyApp einmalig erlauben, Updates zu installieren.")
                    Button(onClick = { controller.openPermissionSettings() }) { Text("Erlauben") }
                    OutlinedButton(onClick = { controller.install(s.manifest) }) { Text("Erneut versuchen") }
                }
                is UpdateState.Installing ->
                    Text("Bitte die Installation bestätigen. Danach FamilyApp neu öffnen.")
                is UpdateState.Failed -> Text("Fehler: ${s.message}", color = MaterialTheme.colorScheme.error)
            }

            OutlinedButton(
                onClick = { controller.check() },
                enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading,
            ) { Text("Nach Updates suchen") }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = autoCheck, onCheckedChange = { autoCheck = it; controller.setAutoCheck(it) })
                Text("Täglich automatisch prüfen", modifier = Modifier.weight(1f))
            }
            Text(
                "Fragt nur github.com nach einer neuen Version. Es werden keine Daten gesendet.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
