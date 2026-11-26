package io.github.respawnedguy.familyapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.respawnedguy.familyapp.domain.FamilyData
import io.github.respawnedguy.familyapp.presentation.AppModel
import io.github.respawnedguy.familyapp.sync.KnownDevice
import io.github.respawnedguy.familyapp.sync.PairingState
import io.github.respawnedguy.familyapp.sync.QrMatrix
import io.github.respawnedguy.familyapp.sync.SyncUi
import kotlinx.coroutines.delay

/** Pairing and sync for the screens below the root; null where the platform cannot sync. */
val LocalSync = staticCompositionLocalOf<SyncUi?> { null }

private const val QR_QUIET_ZONE = 4
private const val SECOND_MS = 1_000L
private const val SAS_HALF = 3
private const val SECONDS_PER_MINUTE = 60
private const val HALF_PIXEL = 0.5f

/**
 * Pairing on both devices (design doc "Onboarding und Pairing", ADR 0003): QR code on the inviting
 * device, then the same six digits on both screens, both people confirm. Covers the whole screen
 * while active, so nothing else can be tapped by accident.
 */
@Composable
fun PairingOverlay(sync: SyncUi, data: FamilyData, model: AppModel) {
    val state by sync.controller.pairing.collectAsState()
    if (state == PairingState.Idle) return
    val c = LocalFamilyColors.current
    val politely = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    Column(
        Modifier.fillMaxSize().background(c.background).statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (val s = state) {
            is PairingState.Inviting -> Inviting(s, sync, model)
            is PairingState.Confirm -> Confirm(s, sync)
            is PairingState.Working -> {
                Spacer(Modifier.height(48.dp))
                CircularProgressIndicator()
                Text(s.text, color = c.text, fontSize = 16.sp, modifier = politely)
                TextButton(onClick = { sync.controller.cancelPairing() }) { Text("Abbrechen") }
            }
            is PairingState.Done -> Done(s, data, sync, model)
            is PairingState.Failed -> {
                Title("Nicht gekoppelt")
                val urgent = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                Text(s.message, color = c.overdue, fontSize = 16.sp, modifier = urgent)
                Button(onClick = { sync.controller.cancelPairing() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Schließen")
                }
            }
            PairingState.Idle -> Unit
        }
    }
}

@Composable
private fun Title(text: String) {
    val color = LocalFamilyColors.current.text
    Text(text, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Inviting(s: PairingState.Inviting, sync: SyncUi, model: AppModel) {
    val c = LocalFamilyColors.current
    var now by remember { mutableLongStateOf(model.repo.now()) }
    var showText by remember { mutableStateOf(false) }
    LaunchedEffect(s.code) {
        while (true) {
            now = model.repo.now()
            delay(SECOND_MS)
        }
    }
    val left = ((s.expiresAtMs - now) / SECOND_MS).coerceAtLeast(0)
    val clock = "${left / SECONDS_PER_MINUTE}:${(left % SECONDS_PER_MINUTE).toString().padStart(2, '0')}"
    Title("Handy koppeln")
    Text(
        "Auf dem anderen Handy FamilyApp öffnen, „Ich wurde eingeladen“ wählen und diesen Code scannen.",
        color = c.textSecondary, fontSize = 15.sp, modifier = Modifier.fillMaxWidth(),
    )
    val matrix = remember(s.code) { sync.platform.qrCode(s.code) }
    if (matrix != null) QrImage(matrix)
    Text(
        if (left > 0) "Gültig noch $clock Min., nur einmal." else "Abgelaufen.",
        color = if (left > 0) c.textSecondary else c.overdue, fontSize = 14.sp,
    )
    TextButton(onClick = { showText = !showText }) { Text(if (showText) "Code ausblenden" else "Code als Text zeigen") }
    if (showText) {
        SelectionContainer { Text(s.code, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = c.text) }
        Text(
            "Zum Einfügen auf dem anderen Gerät, falls die Kamera nicht geht.",
            fontSize = 12.sp, color = c.textTertiary,
        )
    }
    Text(
        "Beide Handys müssen im selben WLAN sein (oder im selben Tailscale-Netz). Nichts geht über einen Server.",
        fontSize = 12.sp, color = c.textTertiary, modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(onClick = { sync.controller.cancelPairing() }, modifier = Modifier.fillMaxWidth()) {
        Text("Abbrechen")
    }
}

/** Always dark on white, also in dark mode: scanners expect it. */
@Composable
private fun QrImage(matrix: QrMatrix) {
    Canvas(
        Modifier.size(280.dp).clip(RoundedCornerShape(12.dp)).background(Color.White)
            .semantics { contentDescription = "QR-Code zum Koppeln" },
    ) {
        val cells = matrix.size + 2 * QR_QUIET_ZONE
        val cell = size.minDimension / cells
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (matrix[x, y]) {
                    drawRect(
                        Color.Black,
                        topLeft = Offset((x + QR_QUIET_ZONE) * cell, (y + QR_QUIET_ZONE) * cell),
                        size = Size(cell + HALF_PIXEL, cell + HALF_PIXEL),
                    )
                }
            }
        }
    }
}

@Composable
private fun Confirm(s: PairingState.Confirm, sync: SyncUi) {
    val c = LocalFamilyColors.current
    var choice by remember(s) { mutableStateOf(s.suggested) }
    Title("Stimmen die Zahlen?")
    Text(
        "Auf dem ${if (s.inviting) "anderen" else "einladenden"} Handy muss dieselbe Zahl stehen.",
        color = c.textSecondary, fontSize = 15.sp, modifier = Modifier.fillMaxWidth(),
    )
    val who = "${s.other.personName} · ${s.other.deviceName}"
    val spaced = s.sas.take(SAS_HALF) + " " + s.sas.drop(SAS_HALF)
    Text(
        spaced, fontSize = 44.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = c.text,
        modifier = Modifier.semantics { contentDescription = "Prüfzahl " + s.sas.toList().joinToString(" ") },
    )
    Text(
        if (s.inviting) "$who möchte beitreten." else "Familie von $who",
        color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth(),
    )
    if (s.inviting) {
        Text("Wem gehört das Gerät?", color = c.textSecondary, fontSize = 14.sp, modifier = Modifier.fillMaxWidth())
        s.choices.forEach { option ->
            Row(
                Modifier.fillMaxWidth().clickable { choice = option }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = choice == option, onClick = { choice = option })
                Text(option.label, color = c.text, fontSize = 15.sp)
            }
        }
        Text(
            "Kennst du das Gerät nicht? Dann „Stimmt nicht“: Jemand könnte den QR-Code abfotografiert haben.",
            fontSize = 12.sp, color = c.textTertiary, modifier = Modifier.fillMaxWidth(),
        )
    }
    Button(
        onClick = { sync.controller.answer(true, choice) },
        enabled = !s.inviting || choice != null,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) { Text("Stimmt") }
    OutlinedButton(onClick = { sync.controller.answer(false) }, modifier = Modifier.fillMaxWidth()) {
        Text("Stimmt nicht")
    }
}

/** After the first pairing the design offers the recovery code (D7) instead of at the first start. */
@Composable
private fun Done(s: PairingState.Done, data: FamilyData, sync: SyncUi, model: AppModel) {
    val c = LocalFamilyColors.current
    Title("Gekoppelt")
    val politely = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }
    Text(s.message, color = c.text, fontSize = 16.sp, modifier = politely)
    if (s.firstPairing && data.settings.backupFolder == null) {
        Text(
            "Eure Einträge liegen jetzt auf mehreren Geräten. Geht eins verloren, " +
                "koppelst du das neue einfach wieder. Wer zusätzlich eine verschlüsselte Sicherung möchte, " +
                "bekommt dabei einen Wiederherstellungscode zum Aufschreiben.",
            color = c.textSecondary, fontSize = 14.sp, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = {
                sync.controller.cancelPairing()
                model.openSettings()
                model.chooseBackupFolder()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Sicherung und Wiederherstellungscode einrichten") }
    }
    Button(onClick = { sync.controller.cancelPairing() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text("Fertig")
    }
}

/**
 * New device: name first (the inviter sees who joins, design doc), then scan or paste the code.
 * Also reachable from settings for someone who already used the app alone; own entries come along.
 */
@Composable
fun JoinFamilyForm(sync: SyncUi, initialName: String, hasOwnData: Boolean, onBack: () -> Unit) {
    val c = LocalFamilyColors.current
    var name by remember { mutableStateOf(initialName) }
    var pasted by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Einer Familie beitreten", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.text)
        Text(
            "Auf dem Handy, das schon dabei ist: Familie → „Person hinzufügen“ → „Mit eigenem Handy“ " +
                "oder Einstellungen → „Handy koppeln“.",
            fontSize = 14.sp, color = c.textSecondary,
        )
        OutlinedTextField(
            value = name, onValueChange = { name = it }, singleLine = true,
            label = { Text("Dein Vorname") }, modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { sync.platform.scanQr { code -> if (code != null) sync.controller.join(code, name) } },
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Weiter zum Scannen") }
        OutlinedTextField(
            value = pasted, onValueChange = { pasted = it }, singleLine = true,
            label = { Text("Oder Code einfügen") }, modifier = Modifier.fillMaxWidth(),
        )
        if (pasted.isNotBlank()) {
            OutlinedButton(
                onClick = { sync.controller.join(pasted, name) },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Mit Code verbinden") }
        }
        if (hasOwnData) {
            Text(
                "Deine bisherigen Einträge kommen mit. Personen mit gleichem Namen werden zusammengelegt.",
                fontSize = 12.sp, color = c.textTertiary,
            )
        }
        TextButton(onClick = onBack) { Text("Zurück") }
    }
}

/** Settings section "Geräte und Abgleich". */
@Composable
fun SyncSettings(sync: SyncUi, data: FamilyData, now: Long) {
    val c = LocalFamilyColors.current
    val status by sync.controller.status.collectAsState()
    var joining by remember { mutableStateOf(false) }
    SettingsGroup("Geräte und Abgleich") {
        if (joining) {
            JoinFamilyForm(sync, data.self?.name.orEmpty(), hasOwnData = data.items.isNotEmpty()) { joining = false }
        } else {
            Text(
                "Gekoppelte Geräte gleichen sich direkt ab, im WLAN oder über Tailscale. " +
                    "Kein Server, alles Ende-zu-Ende verschlüsselt.",
                fontSize = 13.sp, color = c.textSecondary,
            )
            if (status.devices.isEmpty()) {
                Text("Noch mit keinem anderen Gerät gekoppelt.", fontSize = 14.sp, color = c.text)
            }
            status.devices.forEach { DeviceRow(it, data, now) }
            status.lastResult?.let { Text(it, fontSize = 13.sp, color = c.textSecondary) }
            status.problem?.let { Text(it, fontSize = 13.sp, color = c.overdue) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { sync.controller.invite() }) {
                    Text(if (status.devices.isEmpty()) "Handy koppeln" else "Weiteres Gerät")
                }
                if (status.paired) {
                    OutlinedButton(onClick = { sync.controller.syncNow() }, enabled = !status.syncing) {
                        Text(if (status.syncing) "Gleicht ab …" else "Jetzt abgleichen")
                    }
                }
            }
            if (status.devices.isEmpty()) {
                TextButton(onClick = { joining = true }) { Text("Einer Familie auf einem anderen Handy beitreten") }
            }

            Text("Abgleich über einen Ordner", fontSize = 15.sp, color = c.text, fontWeight = FontWeight.SemiBold)
            Text(
                "Syncthing, Synology Drive, Nextcloud oder Google Drive: Jedes Gerät legt seinen Stand " +
                    "verschlüsselt dort ab. So kommen Änderungen auch an, wenn das andere Handy gerade aus ist. " +
                    "Alle Geräte wählen denselben Ordner.",
                fontSize = 12.sp, color = c.textTertiary,
            )
            val folder = status.folder
            if (folder == null) {
                val pick = { sync.platform.pickFolder { ref -> if (ref != null) sync.controller.useFolder(ref) } }
                OutlinedButton(onClick = pick) {
                    Text("Ordner wählen")
                }
            } else {
                Text("Ordner: ${sync.platform.describeFolder(folder)}", fontSize = 14.sp, color = c.text)
                Text(
                    status.lastFolderSyncMs?.let { "Zuletzt abgeglichen: ${ago(it, now)}" }
                        ?: "Noch nicht abgeglichen.",
                    fontSize = 13.sp, color = c.textSecondary,
                )
                TextButton(onClick = { sync.controller.useFolder(null) }) { Text("Ordner nicht mehr nutzen") }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: KnownDevice, data: FamilyData, now: Long) {
    val c = LocalFamilyColors.current
    val owner = data.member(device.memberId)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (owner != null) {
            PersonDot(owner, 28.dp)
        } else {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).background(c.outline))
        }
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(listOfNotNull(owner?.name, device.name).joinToString(" · "), fontSize = 15.sp, color = c.text)
            Text(
                device.lastSyncMs?.let { "Abgeglichen ${ago(it, now)}" } ?: "Noch nicht direkt abgeglichen",
                fontSize = 12.sp, color = c.textTertiary,
            )
        }
    }
}

/** Shown in the member dialog: invite a person who has their own phone. */
@Composable
fun InviteWithPhoneButton(onDone: () -> Unit) {
    val sync = LocalSync.current ?: return
    TextButton(onClick = {
        onDone()
        sync.controller.invite()
    }) { Text("Mit eigenem Handy: QR-Code zeigen") }
}
