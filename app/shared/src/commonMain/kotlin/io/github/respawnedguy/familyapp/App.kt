package io.github.respawnedguy.familyapp

import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.foundation.selection.selectable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.respawnedguy.familyapp.domain.CaptureAction
import io.github.respawnedguy.familyapp.domain.FamilyData
import io.github.respawnedguy.familyapp.domain.Views
import io.github.respawnedguy.familyapp.presentation.AppModel
import io.github.respawnedguy.familyapp.presentation.Feedback
import io.github.respawnedguy.familyapp.presentation.RestoreCodeRequest
import io.github.respawnedguy.familyapp.presentation.Tab
import io.github.respawnedguy.familyapp.ui.CaptureSheet
import io.github.respawnedguy.familyapp.ui.FamilyIcons
import io.github.respawnedguy.familyapp.ui.FamilyScreen
import io.github.respawnedguy.familyapp.ui.FamilyTheme
import io.github.respawnedguy.familyapp.ui.LocalFamilyColors
import io.github.respawnedguy.familyapp.ui.Motion
import io.github.respawnedguy.familyapp.ui.PersonDot
import io.github.respawnedguy.familyapp.ui.PhotoLoader
import io.github.respawnedguy.familyapp.ui.SearchScreen
import io.github.respawnedguy.familyapp.ui.SettingsScreen
import io.github.respawnedguy.familyapp.ui.TodayScreen
import io.github.respawnedguy.familyapp.ui.UpcomingScreen
import io.github.respawnedguy.familyapp.update.UpdateController
import io.github.respawnedguy.familyapp.update.UpdateSection
import io.github.respawnedguy.familyapp.update.UpdateState
import kotlinx.coroutines.delay

/**
 * Root of the shared UI (Android now, iOS later). Navigation A from the design doc:
 * Heute · Familie · Erfassen (centre) · Demnächst · Suchen, settings behind the own avatar.
 */
@Composable
fun App(
    versionName: String,
    model: AppModel,
    photos: PhotoLoader?,
    reducedMotion: Boolean,
    updates: UpdateController? = null,
) {
    FamilyTheme {
        val c = LocalFamilyColors.current
        val data by model.repo.data.collectAsState()
        Box(Modifier.fillMaxSize().background(c.background)) {
            if (data.selfId == null) {
                Onboarding(model)
            } else {
                Main(data, model, photos, reducedMotion, updates, versionName)
            }
            if (updates != null) UpdateBanner(updates, Modifier.align(Alignment.TopCenter))
        }
    }
}

object AppInfo {
    const val NAME = "FamilyApp"
    const val tagline = "Schnell rein, komplett später."

    fun versionLabel(versionName: String): String =
        if (versionName.isBlank()) "Version unbekannt" else "Version $versionName"
}

@Composable
private fun Onboarding(model: AppModel) {
    val c = LocalFamilyColors.current
    var name by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Spacer(Modifier.height(48.dp))
        Text(AppInfo.NAME, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = c.text)
        Text(AppInfo.tagline, fontSize = 17.sp, color = c.textSecondary)
        Text("Wie heißt du?", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.text, modifier = Modifier.padding(top = 24.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = { Text("Dein Vorname") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { model.createFamily(name) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { model.createFamily(name) }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Los geht's", fontSize = 16.sp)
        }
        Text(
            "Kein Konto, keine E-Mail. Damit ist deine Familie angelegt, weitere Personen fügst du unter „Familie“ hinzu.",
            fontSize = 13.sp, color = c.textTertiary,
        )
        model.repo.loadProblem?.let { Text(it, fontSize = 13.sp, color = c.overdue) }
    }
}

@Composable
private fun Main(
    data: FamilyData,
    model: AppModel,
    photos: PhotoLoader?,
    reducedMotion: Boolean,
    updates: UpdateController?,
    versionName: String,
) {
    val c = LocalFamilyColors.current
    val tab by model.tab.collectAsState()
    val settingsOpen by model.settingsOpen.collectAsState()
    val capture by model.capture.collectAsState()
    val feedback by model.feedback.collectAsState()
    val saveProblem by model.repo.saveProblem.collectAsState()
    val restoreCode by model.restoreCode.collectAsState()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                title = if (settingsOpen) "Einstellungen" else when (tab) {
                    Tab.TODAY -> "Heute"
                    Tab.FAMILY -> "Familie"
                    Tab.UPCOMING -> "Demnächst"
                    Tab.SEARCH -> "Suchen"
                },
                data = data,
                onAvatar = { model.openSettings() },
            )
            saveProblem?.let { SaveProblemBanner(it) { model.retrySave() } }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (settingsOpen) {
                    SettingsScreen(data, model, updates, versionName)
                } else when (tab) {
                    Tab.TODAY -> TodayScreen(data, model, photos, reducedMotion)
                    Tab.FAMILY -> FamilyScreen(data, model, photos, reducedMotion)
                    Tab.UPCOMING -> UpcomingScreen(data, model)
                    Tab.SEARCH -> SearchScreen(data, model, photos)
                }
                FeedbackBar(feedback, model, reducedMotion, Modifier.align(Alignment.BottomCenter))
            }
            BottomBar(tab.takeUnless { settingsOpen }, Views.inbox(data).size, data.settings.firstCaptureAction, model)
        }
        CaptureSheet(capture, model, reducedMotion)
        restoreCode?.let { RestoreCodeDialog(it, model) }
    }
}

/** Stays until saving works again (P0-1): data loss must never be silent. */
@Composable
private fun SaveProblemBanner(message: String, onRetry: () -> Unit) {
    val c = LocalFamilyColors.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp))
            .background(c.card).border(1.dp, c.overdue, RoundedCornerShape(12.dp)).padding(12.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive },
    ) {
        Text(message, color = c.overdue, fontSize = 14.sp)
        TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.End)) { Text("Erneut speichern") }
    }
}

/** Asks for the recovery code when a backup does not open with the code on this phone. */
@Composable
private fun RestoreCodeDialog(request: RestoreCodeRequest, model: AppModel) {
    val c = LocalFamilyColors.current
    var code by remember(request) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { model.cancelRestore() },
        title = { Text("Wiederherstellungscode") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (request.wrong) "Dieser Code öffnet die Sicherung nicht. Bitte genau prüfen." else "Gib den Code ein, den du beim Einrichten der Sicherung aufgeschrieben hast.",
                    color = if (request.wrong) c.overdue else c.text,
                )
                OutlinedTextField(value = code, onValueChange = { code = it }, singleLine = true, label = { Text("z. B. 7K3M-Q9TB-…") })
            }
        },
        confirmButton = { TextButton(onClick = { model.restoreWithCode(code) }, enabled = code.isNotBlank()) { Text("Öffnen") } },
        dismissButton = { TextButton(onClick = { model.cancelRestore() }) { Text("Abbrechen") } },
    )
}

@Composable
private fun TopBar(title: String, data: FamilyData, onAvatar: () -> Unit) {
    val c = LocalFamilyColors.current
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = c.text)
        Box(
            Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = "Einstellungen", onClick = onAvatar),
            contentAlignment = Alignment.Center,
        ) { PersonDot(data.self, 36.dp) }
    }
}

@Composable
private fun BottomBar(selected: Tab?, inboxCount: Int, first: CaptureAction, model: AppModel) {
    val c = LocalFamilyColors.current
    Row(
        // Minimum instead of fixed height: labels must not be cut off at 200 % font size (P1-6).
        Modifier.fillMaxWidth().background(c.card).navigationBarsPadding().heightIn(min = 80.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavItem("Heute", FamilyIcons.Today, selected == Tab.TODAY, badge = inboxCount) { model.select(Tab.TODAY) }
        NavItem("Familie", FamilyIcons.Family, selected == Tab.FAMILY) { model.select(Tab.FAMILY) }
        // Erfassen: centre, slightly raised (D2). The first tap starts speech at once (D3).
        Column(
            Modifier.weight(1f).semantics { role = Role.Button }
                .clickable(onClickLabel = "Erfassen") { model.startCapture("Knopf", model.elapsedMs()) },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.offset(y = (-10).dp).size(60.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(c.accent),
                contentAlignment = Alignment.Center,
            ) {
                val icon = when (first) {
                    CaptureAction.VOICE -> FamilyIcons.Mic
                    CaptureAction.PHOTO -> FamilyIcons.Camera
                    CaptureAction.TEXT -> FamilyIcons.Keyboard
                }
                Icon(icon, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(30.dp))
            }
            Text("Erfassen", fontSize = 12.sp, color = c.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.offset(y = (-6).dp))
        }
        NavItem("Demnächst", FamilyIcons.Upcoming, selected == Tab.UPCOMING) { model.select(Tab.UPCOMING) }
        NavItem("Suchen", FamilyIcons.Search, selected == Tab.SEARCH) { model.select(Tab.SEARCH) }
    }
}

@Composable
private fun RowScope.NavItem(label: String, icon: ImageVector, selected: Boolean, badge: Int = 0, onClick: () -> Unit) {
    val c = LocalFamilyColors.current
    Column(
        Modifier.weight(1f).heightIn(min = 80.dp).selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Box(
                Modifier.clip(RoundedCornerShape(16.dp)).background(if (selected) c.background else c.card)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) { Icon(icon, contentDescription = null, tint = if (selected) c.accent else c.textSecondary, modifier = Modifier.size(24.dp)) }
            if (badge > 0) {
                Text(
                    badge.toString(),
                    Modifier.align(Alignment.TopEnd).clip(CircleShape).background(c.accent).padding(horizontal = 5.dp),
                    color = c.onAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
        Text(label, fontSize = 12.sp, color = if (selected) c.text else c.textSecondary, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** Bottom message with "Rückgängig", visible for 6 s (design: undo beats warning dialogs). */
@Composable
private fun FeedbackBar(feedback: Feedback?, model: AppModel, reducedMotion: Boolean, modifier: Modifier) {
    val c = LocalFamilyColors.current
    var last by remember { mutableStateOf<Feedback?>(null) }
    if (feedback != null) last = feedback
    // People who set a longer "time to take action" in the system get it here too (WCAG 2.2.1).
    val a11y = LocalAccessibilityManager.current
    LaunchedEffect(feedback?.id) {
        val f = feedback ?: return@LaunchedEffect
        val visible = a11y?.calculateRecommendedTimeoutMillis(
            Motion.UNDO_VISIBLE_MS, containsIcons = false, containsText = true, containsControls = f.undo != null,
        ) ?: Motion.UNDO_VISIBLE_MS
        delay(visible)
        model.dismissFeedback(f.id)
    }
    val enter = if (reducedMotion) fadeIn(tween(0)) else slideInVertically(tween(Motion.FEEDBACK_MS, easing = Motion.Standard)) { it } + fadeIn()
    val exit = if (reducedMotion) fadeOut(tween(0)) else slideOutVertically(tween(Motion.FEEDBACK_MS, easing = Motion.Standard)) { it } + fadeOut()
    AnimatedVisibility(feedback != null, modifier = modifier, enter = enter, exit = exit) {
        val f = last ?: return@AnimatedVisibility
        Row(
            // Polite live region: TalkBack reads "Gespeichert." without the person looking for it (WCAG 4.1.3).
            Modifier.padding(12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.text).padding(start = 16.dp, end = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(f.message, Modifier.weight(1f).padding(vertical = 14.dp), color = c.background, fontSize = 14.sp)
            if (f.undo != null) {
                TextButton(onClick = { model.undo(f) }) { Text("Rückgängig", color = c.background, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/**
 * The updater must stay reachable on every screen, also during onboarding (the CI update test
 * installs a fresh app and expects "Installieren" right after the start).
 */
@Composable
private fun UpdateBanner(updates: UpdateController, modifier: Modifier) {
    val state by updates.state.collectAsState()
    var hiddenFor by remember { mutableStateOf<UpdateState?>(null) }
    val active = state is UpdateState.Available || state is UpdateState.Downloading || state is UpdateState.NeedsPermission ||
        state is UpdateState.Installing || state is UpdateState.Failed
    if (!active || hiddenFor == state) return
    val c = LocalFamilyColors.current
    Column(modifier.statusBarsPadding().padding(12.dp).clip(RoundedCornerShape(16.dp)).background(c.background)) {
        UpdateSection(updates)
        TextButton(onClick = { hiddenFor = state }, modifier = Modifier.align(Alignment.End)) { Text("Ausblenden") }
    }
}
