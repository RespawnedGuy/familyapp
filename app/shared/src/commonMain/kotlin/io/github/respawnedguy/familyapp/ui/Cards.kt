package io.github.respawnedguy.familyapp.ui

import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.respawnedguy.familyapp.domain.DateFormat
import io.github.respawnedguy.familyapp.domain.FamilyData
import io.github.respawnedguy.familyapp.domain.Item
import io.github.respawnedguy.familyapp.domain.ItemStatus
import io.github.respawnedguy.familyapp.domain.MemberKind
import io.github.respawnedguy.familyapp.domain.Origin
import io.github.respawnedguy.familyapp.presentation.AppModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus

/** Inbox card (design "Eingang: eine Karte, ein Tap"): at most three chips, reason line, two buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InboxCard(item: Item, data: FamilyData, model: AppModel, photos: PhotoLoader?, reducedMotion: Boolean) {
    val c = LocalFamilyColors.current
    val today = model.today()
    val confirmLabel = if (item.assignee == null) "Mach ich" else "Passt"
    var editing by remember { mutableStateOf(false) }

    SwipeToDecide(
        rightLabel = confirmLabel,
        onRight = { model.confirm(item) },
        leftLabel = "Irrelevant",
        onLeft = { model.irrelevant(item) },
        reducedMotion = reducedMotion,
        modifier = Modifier.padding(vertical = 6.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card)
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(confirmLabel) { model.confirm(item); true },
                        CustomAccessibilityAction("Irrelevant") { model.irrelevant(item); true },
                    )
                }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val by = data.member(item.createdBy)?.takeIf { it.id != data.selfId }?.let { " · von ${it.name}" } ?: ""
            Text(
                "${sourceLabel(item.source)}$by · ${ago(item.createdMs, model.repo.now())}",
                color = c.textTertiary, fontSize = 12.sp,
            )
            Text(
                item.title,
                // Tap target at least 48 dp high even for a one-line title (P1-6).
                modifier = Modifier.heightIn(min = 48.dp).clickable { editing = true }
                    .wrapContentHeight(Alignment.CenterVertically),
                color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            item.photoPath?.let { PhotoThumb(it, photos) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PersonChip(
                    data = data,
                    selectedId = item.concerns,
                    origin = item.concernsOrigin,
                    question = "Betrifft wen?",
                    prefix = "betrifft",
                    allowNone = true,
                    onPick = { model.setConcerns(item, it) },
                )
                PersonChip(
                    data = data,
                    selectedId = item.assignee,
                    origin = item.assigneeOrigin,
                    question = "Wer kümmert sich?",
                    prefix = null,
                    allowNone = false,
                    onPick = { id -> id?.let { model.setAssignee(item, it) } },
                )
                DateChip(item, today) { model.setDue(item, it) }
            }
            if (item.reasons.isNotEmpty()) {
                Text("Regel: " + item.reasons.joinToString(" · "), color = c.textTertiary, fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { model.irrelevant(item) }) { Text("Irrelevant", color = c.textSecondary) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { model.confirm(item) }) { Text(confirmLabel) }
            }
        }
    }
    if (editing) ItemDialog(item, model, photos) { editing = false }
}

@Composable
private fun PersonChip(
    data: FamilyData,
    selectedId: String?,
    origin: Origin?,
    question: String,
    prefix: String?,
    allowNone: Boolean,
    onPick: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val member = data.member(selectedId)
    Box {
        DecisionChip(
            label = member?.let { if (prefix != null) "$prefix ${it.name}" else it.name } ?: question,
            missing = member == null,
            suggested = member != null && origin == Origin.RULE,
            onClick = { open = true },
            leading = member?.let { m -> { PersonDot(m, 20.dp) } },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // Adults first: they are the ones who take care of things.
            data.members.sortedBy { it.kind != MemberKind.ADULT }.forEach { m ->
                DropdownMenuItem(
                    text = { Text(if (m.id == data.selfId) "${m.name} (ich)" else m.name) },
                    leadingIcon = { PersonDot(m, 24.dp) },
                    onClick = { open = false; onPick(m.id) },
                )
            }
            if (allowNone) {
                DropdownMenuItem(text = { Text("Niemand Bestimmtes") }, onClick = { open = false; onPick(null) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateChip(item: Item, today: LocalDate, onPick: (LocalDate?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    val label = when {
        item.due != null -> "bis " + DateFormat.chip(item.due, today)
        item.dueDecided -> "Kein Datum"
        else -> "Bis wann?"
    }
    Box {
        DecisionChip(
            label = label,
            missing = item.due == null && !item.dueDecided,
            suggested = item.due != null && item.dueOrigin == Origin.RULE,
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            quickDates(today).forEach { (text, date) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(date) })
            }
            DropdownMenuItem(text = { Text("Datum wählen …") }, onClick = { open = false; picker = true })
            DropdownMenuItem(text = { Text("Kein Datum") }, onClick = { open = false; onPick(null) })
        }
    }
    if (picker) {
        val state = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { picker = false },
            confirmButton = {
                TextButton(onClick = {
                    picker = false
                    state.selectedDateMillis?.let { onPick(epochDay((it / 86_400_000L).toInt())) }
                }) { Text("Übernehmen") }
            },
            dismissButton = { TextButton(onClick = { picker = false }) { Text("Abbrechen") } },
        ) { DatePicker(state) }
    }
}

private fun epochDay(days: Int): LocalDate = LocalDate(1970, 1, 1).plus(days, DateTimeUnit.DAY)

/** Quick choices from the design doc: Heute, Morgen, Freitag, Nächste Woche. */
fun quickDates(today: LocalDate): List<Pair<String, LocalDate>> {
    val toFriday = (5 - today.dayOfWeek.isoDayNumber + 7) % 7
    val friday = today.plus(if (toFriday == 0) 7 else toFriday, DateTimeUnit.DAY)
    val nextMonday = DateFormat.mondayOf(today).plus(7, DateTimeUnit.DAY)
    return listOf(
        "Heute" to today,
        "Morgen" to today.plus(1, DateTimeUnit.DAY),
        "Freitag, ${DateFormat.dayMonth(friday)}" to friday,
        "Nächste Woche (Mo ${DateFormat.dayMonth(nextMonday)})" to nextMonday,
    )
}

@Composable
fun PhotoThumb(path: String, photos: PhotoLoader?, height: Int = 140) {
    if (photos == null) return
    var full by remember { mutableStateOf(false) }
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.Default) { photos.load(path, 800) }
    }
    bitmap?.let { b ->
        Image(
            b,
            contentDescription = "Foto",
            modifier = Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(12.dp)).clickable { full = true },
            contentScale = ContentScale.Crop,
        )
    }
    if (full) {
        val big by produceState<ImageBitmap?>(null, path) {
            value = withContext(Dispatchers.Default) { photos.load(path, 2000) }
        }
        AlertDialog(
            onDismissRequest = { full = false },
            confirmButton = { TextButton(onClick = { full = false }) { Text("Schließen") } },
            text = { big?.let { Image(it, contentDescription = "Foto", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) } },
        )
    }
}

/**
 * Open task row ("Meins", "Familie"): tap the circle or swipe right for done.
 * Overdue is written out in red text, never colour alone.
 */
@Composable
fun TaskRow(
    item: Item,
    data: FamilyData,
    model: AppModel,
    photos: PhotoLoader?,
    reducedMotion: Boolean,
    showAssignee: Boolean,
    takeOver: Boolean,
) {
    val c = LocalFamilyColors.current
    val today = model.today()
    var editing by remember { mutableStateOf(false) }
    SwipeToDecide(
        rightLabel = "Erledigt",
        onRight = { model.complete(item) },
        leftLabel = null,
        onLeft = null,
        reducedMotion = reducedMotion,
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card)
                .semantics { customActions = listOf(CustomAccessibilityAction("Erledigt") { model.complete(item); true }) }
                .clickable { editing = true }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                // A label for screen readers; ATF reported this button as unnamed (P1-4).
                Modifier.size(48.dp).clip(CircleShape).clickable { model.complete(item) }
                    .semantics { contentDescription = "Als erledigt markieren" },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(24.dp).dashedBorder(c.textSecondary, 12.dp, 2.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 6.dp)) {
                Text(item.title, color = c.text, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val parts = buildList {
                    item.due?.let { due ->
                        add(DateFormat.overdue(due, today) ?: ("bis " + DateFormat.chip(due, today)))
                    }
                    data.member(item.concerns)?.let { add("betrifft ${it.name}") }
                }
                if (parts.isNotEmpty()) {
                    val overdue = item.due?.let { DateFormat.overdue(it, today) } != null
                    Text(
                        parts.joinToString(" · "),
                        color = if (overdue) c.overdue else c.textSecondary,
                        fontSize = 13.sp,
                        fontWeight = if (overdue) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
            if (takeOver) {
                TextButton(onClick = { model.takeOver(item) }) { Text("Übernehme ich") }
            } else if (showAssignee) {
                PersonDot(data.member(item.assignee), 28.dp, Modifier.padding(end = 8.dp))
            }
        }
    }
    if (editing) ItemDialog(item, model, photos) { editing = false }
}

/** Details: rename, see the photo, put back, delete (with undo, no confirmation dialog). */
@Composable
fun ItemDialog(item: Item, model: AppModel, photos: PhotoLoader?, onClose: () -> Unit) {
    var title by remember(item.id) { mutableStateOf(item.title) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Eintrag") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Titel") }, modifier = Modifier.fillMaxWidth())
                item.photoPath?.let { PhotoThumb(it, photos, height = 200) }
                if (item.reasons.isNotEmpty()) Text("Regel: " + item.reasons.joinToString(" · "), fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.status != ItemStatus.INBOX) {
                        TextButton(onClick = { onClose(); model.reopen(item) }) { Text("Zurück in den Eingang") }
                    }
                    TextButton(onClick = { onClose(); model.delete(item) }) { Text("Löschen") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onClose(); if (title != item.title) model.rename(item, title) }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Abbrechen") } },
    )
}

/** Status with the person who finished it: done work is visible to everyone (P0-5). */
@Composable
fun StatusLabel(item: Item, data: FamilyData? = null) {
    val c = LocalFamilyColors.current
    val by = data?.member(item.doneBy)?.let { " von ${it.name}" }.orEmpty()
    val text = when (item.status) {
        ItemStatus.INBOX -> "im Eingang"
        ItemStatus.OPEN -> "offen"
        ItemStatus.DONE -> "erledigt$by"
        ItemStatus.IRRELEVANT -> "irrelevant"
    }
    Text(text, color = c.textTertiary, fontSize = 12.sp, modifier = Modifier.heightIn(min = 16.dp))
}
