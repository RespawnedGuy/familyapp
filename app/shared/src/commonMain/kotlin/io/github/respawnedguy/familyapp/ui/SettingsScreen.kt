package io.github.respawnedguy.familyapp.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.respawnedguy.familyapp.AppInfo
import io.github.respawnedguy.familyapp.domain.CaptureAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.respawnedguy.familyapp.domain.FamilyData
import io.github.respawnedguy.familyapp.presentation.AppModel
import io.github.respawnedguy.familyapp.update.UpdateController
import io.github.respawnedguy.familyapp.update.UpdateSection

@Composable
fun SettingsScreen(data: FamilyData, model: AppModel, updates: UpdateController?, versionName: String) {
    val c = LocalFamilyColors.current
    val s = data.settings
    var name by remember { mutableStateOf(data.self?.name ?: "") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 96.dp),
    ) {
        TextButton(onClick = { model.select(model.tab.value) }) { Text("← Zurück") }

        SettingsGroup("Profil") {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Dein Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (name.isNotBlank() && name.trim() != data.self?.name) {
                Button(onClick = { model.renameSelf(name) }) { Text("Namen speichern") }
            }
            Text(
                "Kein Konto, keine E-Mail. Alles liegt nur auf euren Geräten.",
                fontSize = 12.sp, color = c.textTertiary,
            )
        }

        LocalSync.current?.let { SyncSettings(it, data, model.repo.now()) }

        SettingsGroup("Erfassen") {
            Text("Was der erste Tap auf „Erfassen“ startet:", fontSize = 14.sp, color = c.textSecondary)
            listOf(
                CaptureAction.VOICE to "Sprache (Foto liegt dann an derselben Stelle)",
                CaptureAction.PHOTO to "Foto",
                CaptureAction.TEXT to "Tippen",
            ).let { options ->
                // One radio group: TalkBack announces "1 of 3, selected"; the whole row is the target (P1-6).
                Column(Modifier.selectableGroup()) {
                    options.forEach { (action, label) ->
                        val chosen = s.firstCaptureAction == action
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(selected = chosen, role = Role.RadioButton) {
                                    model.setFirstCaptureAction(action)
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = chosen, onClick = null)
                            Text(label, Modifier.padding(start = 12.dp), fontSize = 15.sp, color = c.text)
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Spracherkennung auch online", fontSize = 15.sp, color = c.text)
                    Text(
                        "Aus: Sprache wird nur auf dem Handy erkannt. Ein: Fehlt das Offline-Sprachpaket, darf Google online erkennen.",
                        fontSize = 12.sp, color = c.textTertiary,
                    )
                }
                Switch(checked = s.allowOnlineSpeech, onCheckedChange = { model.setAllowOnlineSpeech(it) })
            }
            Text(
                "Schneller als die App: Schnellkachel „Erfassen“ in den Schnelleinstellungen, langes Drücken aufs App-Symbol, oder „Teilen“ aus Mail und Galerie.",
                fontSize = 12.sp, color = c.textTertiary,
            )
        }

        SettingsGroup("Erinnerungen") {
            // Whole row is one switch for screen readers and a large touch target (P1-6).
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                    value = s.remindersOn,
                    role = Role.Switch,
                    onValueChange = { model.setRemindersOn(it) },
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("An Fristen erinnern", fontSize = 15.sp, color = c.text)
                    Text(
                        "Um 8 Uhr am Tag der Frist und am Tag danach, wenn sie noch offen ist. " +
                            "Ist sie dann immer noch offen, erfahren es alle. " +
                            "Mehrere Fristen kommen als eine Nachricht.",
                        fontSize = 12.sp, color = c.textTertiary,
                    )
                }
                Switch(checked = s.remindersOn, onCheckedChange = null)
            }
        }

        SettingsGroup("Sicherung in Ordner") {
            Text(
                "Die Daten liegen geschützt im App-Speicher. Zusätzlich schreibt die App nach jeder Änderung eine verschlüsselte Sicherung mit allen Fotos in einen Ordner deiner Wahl, z. B. einen Synology-Drive-Ordner.",
                fontSize = 13.sp, color = c.textSecondary,
            )
            val folder = s.backupFolder
            if (folder == null) {
                Button(onClick = { model.chooseBackupFolder() }) { Text("Ordner wählen") }
            } else {
                Text("Ordner: ${model.describeFolder(folder)}", fontSize = 14.sp, color = c.text)
                val last = s.lastBackupMs?.let { "Zuletzt gesichert: ${ago(it, model.repo.now())}" } ?: "Noch nicht gesichert."
                Text(last, fontSize = 13.sp, color = c.textSecondary)
                s.lastBackupError?.let { Text("Fehler: $it", fontSize = 13.sp, color = c.overdue) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { model.backupNow() }) { Text("Jetzt sichern") }
                    OutlinedButton(onClick = { model.chooseBackupFolder() }) { Text("Anderer Ordner") }
                }
                TextButton(onClick = { model.stopBackups() }) { Text("Sicherung beenden") }
            }
            s.backupCode?.let { code ->
                Text("Wiederherstellungscode", fontSize = 14.sp, color = c.text, fontWeight = FontWeight.SemiBold)
                Text(code, fontSize = 20.sp, color = c.text, fontFamily = FontFamily.Monospace, modifier = Modifier.semantics { contentDescription = code.toList().joinToString(" ") })
                Text(
                    "Schreib ihn auf Papier. Ohne diesen Code lässt sich die Sicherung auf einem neuen Handy nicht öffnen, auch nicht von uns.",
                    fontSize = 12.sp, color = c.textTertiary,
                )
            }
            OutlinedButton(onClick = { model.restoreFromFolder() }) { Text("Aus Sicherung wiederherstellen") }
            Text(
                "Wähle den Ordner, in dem die Sicherung liegt. Der jetzige Stand wird vorher aufbewahrt und lässt sich zurückholen.",
                fontSize = 12.sp, color = c.textTertiary,
            )
            if (model.hasRestoreSnapshot()) {
                TextButton(onClick = { model.restoreSnapshot() }) { Text("Stand vor der Wiederherstellung zurückholen") }
            }
        }

        SettingsGroup("Messwerte (nur lokal)") {
            val median = model.latencyMedianMs()
            val samples = s.latency
            Text(
                if (median == null) "Noch keine Messung: einmal per Knopf, Kachel oder Verknüpfung sprechen."
                else "Bis die Aufnahme läuft: Median ${formatMs(median)} aus ${samples.size} Messungen (Ziel ≤ 2,0 s).",
                fontSize = 14.sp, color = c.text,
            )
            samples.takeLast(5).reversed().forEach {
                Text("${it.trigger}: ${formatMs(it.ms)} · ${ago(it.atMs, model.repo.now())}", fontSize = 12.sp, color = c.textTertiary)
            }
            val (one, all) = model.oneTapShare()
            if (all > 0) {
                Text("Karten mit einem Tap erledigt: $one von $all (${one * 100 / all} %, Ziel ≥ 70 %).", fontSize = 14.sp, color = c.text)
            }
        }

        if (updates != null) {
            UpdateSection(updates, Modifier.padding(vertical = 8.dp))
        }

        SettingsGroup("Über") {
            Text("${AppInfo.NAME} · ${AppInfo.versionLabel(versionName)}", fontSize = 14.sp, color = c.text)
            Text(
                "Prototyp. Alles bleibt auf euren Geräten, keine Cloud, keine KI. Die App vibriert nicht.",
                fontSize = 12.sp, color = c.textTertiary,
            )
        }
    }
}

private fun formatMs(ms: Long): String {
    val tenths = (ms + 50) / 100
    return "${tenths / 10},${tenths % 10} s"
}
