package io.github.respawnedguy.familyapp.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.respawnedguy.familyapp.domain.CaptureAction
import io.github.respawnedguy.familyapp.presentation.AppModel
import io.github.respawnedguy.familyapp.presentation.CaptureState

/**
 * Capture sheet (design D3): opened by the centre button and already listening. "Foto" sits in
 * the centre of the bottom row, where the "Erfassen" button was, so tapping twice opens the camera.
 * Typing sits next to it. Saving needs no tap: speech is saved when the speaker stops (D8).
 */
@Composable
fun CaptureSheet(state: CaptureState, model: AppModel, reducedMotion: Boolean) {
    if (state == CaptureState.Closed || state == CaptureState.WaitingForPhoto) return
    val c = LocalFamilyColors.current
    Box(Modifier.fillMaxSize()) {
        // Tapping outside cancels (design: "Abbrechen durch ... Tippen daneben").
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { model.dismissCapture() },
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(c.card)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(32.dp).height(4.dp).clip(CircleShape).background(c.outline))
            when (state) {
                is CaptureState.Listening -> Listening(state, model, reducedMotion)
                CaptureState.Typing -> Typing(model)
                is CaptureState.Problem -> {
                    Text(state.message, color = c.text, fontSize = 16.sp)
                    TextButton(onClick = { model.switchCapture(CaptureAction.VOICE) }) { Text("Nochmal sprechen") }
                }
                else -> Unit
            }
            BottomRow(state, model)
        }
    }
}

@Composable
private fun ColumnScope.Listening(state: CaptureState.Listening, model: AppModel, reducedMotion: Boolean) {
    val c = LocalFamilyColors.current
    val pulse = if (reducedMotion || !state.ready) 1f else {
        val t = rememberInfiniteTransition()
        t.animateFloat(0.85f, 1.15f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse)).value
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(16.dp).scale(pulse).clip(CircleShape).background(if (state.ready) c.overdue else c.outline))
        Text(
            if (state.ready) "Ich höre zu …" else "Mikrofon startet …",
            color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
        )
    }
    Text(
        state.partial.ifEmpty { "Zum Beispiel: „Mia braucht bis Donnerstag neue Hausschuhe“" },
        color = if (state.partial.isEmpty()) c.textTertiary else c.text,
        fontSize = if (state.partial.isEmpty()) 15.sp else 20.sp,
        modifier = Modifier.heightIn(min = 56.dp),
    )
    Text("Gespeichert wird von selbst, sobald du aufhörst zu sprechen.", color = c.textTertiary, fontSize = 12.sp)
    if (state.ready) TextButton(onClick = { model.stopListening() }) { Text("Fertig") }
}

@Composable
private fun ColumnScope.Typing(model: AppModel) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text("Was ist zu tun?") },
        // Enter saves (soft keyboard "Fertig" and hardware keyboards alike); no newline in a quick note.
        modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent {
            if (it.key != Key.Enter && it.key != Key.NumPadEnter) return@onPreviewKeyEvent false
            if (it.type == KeyEventType.KeyUp) model.saveText(text)
            true
        },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { model.saveText(text) }),
        minLines = 2,
    )
    Button(onClick = { model.saveText(text) }, enabled = text.isNotBlank(), modifier = Modifier.align(Alignment.End)) { Text("Speichern") }
}

@Composable
private fun BottomRow(state: CaptureState, model: AppModel) {
    val c = LocalFamilyColors.current
    Row(Modifier.fillMaxWidth().height(80.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (state == CaptureState.Typing) {
                SheetAction("Sprechen") { model.switchCapture(CaptureAction.VOICE) }
            } else {
                SheetAction("Tippen") { model.switchCapture(CaptureAction.TEXT) }
            }
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(c.accent).clickable { model.switchCapture(CaptureAction.PHOTO) },
                contentAlignment = Alignment.Center,
            ) { Icon(FamilyIcons.Camera, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(30.dp)) }
            Text("Foto", color = c.text, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            SheetAction("Abbrechen") { model.dismissCapture() }
        }
    }
}

@Composable
private fun SheetAction(label: String, onClick: () -> Unit) {
    val c = LocalFamilyColors.current
    Text(
        label,
        modifier = Modifier.clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        color = c.accent,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
    )
}
