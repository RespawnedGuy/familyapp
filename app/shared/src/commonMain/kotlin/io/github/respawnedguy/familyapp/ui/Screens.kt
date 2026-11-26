package io.github.respawnedguy.familyapp.ui

import io.github.respawnedguy.familyapp.resources.urgent_title
import io.github.respawnedguy.familyapp.resources.local_only_later
import io.github.respawnedguy.familyapp.resources.local_only_setup
import io.github.respawnedguy.familyapp.resources.local_only_text
import io.github.respawnedguy.familyapp.resources.local_only_title
import org.jetbrains.compose.resources.stringResource
import io.github.respawnedguy.familyapp.resources.Res
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import io.github.respawnedguy.familyapp.domain.ReminderPlanner
import io.github.respawnedguy.familyapp.domain.ReminderKind
import io.github.respawnedguy.familyapp.domain.Reminder
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.respawnedguy.familyapp.domain.DateFormat
import io.github.respawnedguy.familyapp.domain.FamilyData
import io.github.respawnedguy.familyapp.domain.ItemStatus
import io.github.respawnedguy.familyapp.domain.Member
import io.github.respawnedguy.familyapp.domain.MemberKind
import io.github.respawnedguy.familyapp.domain.PERSON_COLOR_COUNT
import io.github.respawnedguy.familyapp.domain.Views
import io.github.respawnedguy.familyapp.domain.Load
import io.github.respawnedguy.familyapp.presentation.AppModel

private val ListPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)

/** Start screen: inbox on top, then "Meins" (design: idea taken over from variant B). */
@Composable
fun TodayScreen(data: FamilyData, model: AppModel, photos: PhotoLoader?, reducedMotion: Boolean) {
    val c = LocalFamilyColors.current
    val inbox = Views.inbox(data)
    val mine = Views.mine(data)
    val planner = remember { ReminderPlanner() }
    val paired = LocalSync.current?.controller?.status?.collectAsState()?.value?.paired ?: false
    val urgent = planner.urgent(data, model.today())
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding) {
        // What matters today waits here instead of in many notifications (Nils, 26.11.2026).
        if (urgent.isNotEmpty()) {
            item(key = "urgent") { UrgentCard(urgent, data, planner) }
        }
        if (Views.showLocalOnlyHint(data, paired)) {
            item(key = "local-only") { LocalOnlyCard(model) }
        }
        item(key = "h-inbox") { SectionHeader("Eingang", inbox.size) }
        if (inbox.isEmpty()) {
            item(key = "e-inbox") { EmptyNote("Alles sortiert. Darüber musst du gerade nicht nachdenken.") }
        }
        items(inbox, key = { it.id }) { item ->
            Box(if (reducedMotion) Modifier else Modifier.animateItem()) {
                InboxCard(item, data, model, photos, reducedMotion)
            }
        }
        item(key = "h-mine") { SectionHeader("Meins", mine.size) }
        if (mine.isEmpty()) {
            item(key = "e-mine") { EmptyNote("Nichts offen für dich.") }
        }
        items(mine, key = { it.id }) { item ->
            Box(if (reducedMotion) Modifier else Modifier.animateItem()) {
                TaskRow(item, data, model, photos, reducedMotion, showAssignee = false, takeOver = false)
            }
        }
        item(key = "done") {
            Text(
                "Diese Woche erledigt: ${Views.doneThisWeek(data, model.today())}",
                Modifier.fillMaxWidth().padding(top = 16.dp),
                color = c.textSecondary, fontSize = 14.sp,
            )
        }
    }
}

/** P1-11: one quiet card, no notification; "Später" puts it aside for 30 more entries. */
@Composable
private fun LocalOnlyCard(model: AppModel) {
    val c = LocalFamilyColors.current
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(16.dp)).background(c.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(Res.string.local_only_title), Modifier.semantics { heading() },
            fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.text,
        )
        Text(
            stringResource(Res.string.local_only_text),
            fontSize = 14.sp, color = c.textSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { model.openSettings(); model.chooseBackupFolder() }) {
                Text(stringResource(Res.string.local_only_setup))
            }
            TextButton(onClick = { model.dismissLocalOnlyHint() }) { Text(stringResource(Res.string.local_only_later)) }
        }
    }
}

@Composable
private fun UrgentCard(urgent: List<Reminder>, data: FamilyData, planner: ReminderPlanner) {
    val c = LocalFamilyColors.current
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(16.dp)).background(c.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(Res.string.urgent_title), Modifier.semantics { heading() },
            fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.text,
        )
        urgent.forEach { r ->
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Text(r.title, fontSize = 15.sp, color = c.text, fontWeight = FontWeight.Medium)
                val color = if (r.kind == ReminderKind.DUE_TODAY) c.textSecondary else c.overdue
                Text(planner.reason(r, data), fontSize = 13.sp, color = color)
            }
        }
    }
}

/** Transparency has a fixed place: everybody's stack, load chart, take over openly. */
@Composable
fun FamilyScreen(data: FamilyData, model: AppModel, photos: PhotoLoader?, reducedMotion: Boolean) {
    val c = LocalFamilyColors.current
    val today = model.today()
    var filter by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Member?>(null) }
    val loads = Views.loads(data, today)
    val maxOpen = (loads.maxOfOrNull { it.open } ?: 0).coerceAtLeast(1)
    val shown = data.members.filter { filter == null || it.id == filter }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding) {
        item(key = "load") {
            Column(
                Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(16.dp)).background(c.card).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Lastkarte", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                Text("Nur feste Fristen und Termine. Antippen filtert die Liste.", fontSize = 12.sp, color = c.textTertiary)
                loads.forEach { load ->
                    val selected = filter == load.member.id
                    LoadRow(load, maxOpen, selected) { filter = if (selected) null else load.member.id }
                }
                val unowned = Views.unowned(data)
                Text(
                    if (unowned == 0) "Im Eingang ist alles verteilt." else "Im Eingang gehören $unowned Dinge noch niemandem.",
                    fontSize = 13.sp, color = c.textSecondary,
                )
            }
        }
        item(key = "members") {
            // With large fonts the button goes on its own line; beside it the heading had 15 dp (P1-4).
            val large = largeFont()
            val addPerson = "Person hinzufügen"
            Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Personen", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                    if (!large) OutlinedButton(onClick = { adding = true }) { Text(addPerson) }
                }
                if (large) OutlinedButton(onClick = { adding = true }) { Text(addPerson) }
            }
        }
        shown.forEach { member ->
            val open = Views.openFor(data, member.id)
            item(key = "m-${member.id}") {
                Row(
                    Modifier.fillMaxWidth().clickable { editing = member }.padding(top = 16.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PersonDot(member, 32.dp)
                    Column(Modifier.padding(start = 10.dp).weight(1f)) {
                        Text(if (member.id == data.selfId) "${member.name} (ich)" else member.name, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        val kind = if (member.kind == MemberKind.CHILD) "Kind" else "Erwachsen"
                        val device = if (member.hasDevice) "eigenes Handy" else "ohne eigenes Handy"
                        Text("$kind · $device · ${open.size} offen", color = c.textTertiary, fontSize = 12.sp)
                    }
                    Text("Ändern", color = c.accent, fontSize = 13.sp)
                }
            }
            items(open, key = { "f-${it.id}" }) { item ->
                TaskRow(
                    item, data, model, photos, reducedMotion,
                    showAssignee = false,
                    takeOver = member.id != data.selfId,
                )
            }
        }
    }

    if (adding) MemberDialog(null, data, onDismiss = { adding = false }) { name, _, kind ->
        model.addMember(name, kind)
        adding = false
    }
    editing?.let { m ->
        MemberDialog(m, data, onDismiss = { editing = null }, onRemove = if (m.id != data.selfId) ({ model.removeMember(m.id); editing = null }) else null) { name, color, kind ->
            model.updateMember(m.id, name, color, kind)
            editing = null
        }
    }
}

@Composable
private fun MemberDialog(
    member: Member?,
    data: FamilyData,
    onDismiss: () -> Unit,
    onRemove: (() -> Unit)? = null,
    onSave: (String, Int, MemberKind) -> Unit,
) {
    val c = LocalFamilyColors.current
    var name by remember { mutableStateOf(member?.name ?: "") }
    var kind by remember { mutableStateOf(member?.kind ?: MemberKind.ADULT) }
    var color by remember { mutableStateOf(member?.colorIndex ?: 0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (member == null) "Person hinzufügen" else "Person ändern") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                // One radio group with whole-option targets of at least 48 dp (P1-6).
                Row(Modifier.selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                    listOf(MemberKind.ADULT to "Erwachsen", MemberKind.CHILD to "Kind").forEach { (k, label) ->
                        Row(
                            Modifier.heightIn(min = 48.dp)
                                .selectable(selected = kind == k, role = Role.RadioButton) { kind = k }
                                .padding(end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = kind == k, onClick = null)
                            Text(label, Modifier.padding(start = 8.dp))
                        }
                    }
                }
                if (member != null) {
                    Text("Farbe", fontSize = 13.sp, color = c.textSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (0 until PERSON_COLOR_COUNT).forEach { i ->
                            Box(
                                Modifier.size(48.dp).clip(RoundedCornerShape(24.dp))
                                    .background(if (i == color) c.text else c.card)
                                    .clickable { color = i }
                                    .padding(4.dp),
                                contentAlignment = Alignment.Center,
                            ) { PersonDot(member.copy(colorIndex = i, name = name.ifBlank { member.name }), 40.dp) }
                        }
                    }
                }
                if (member == null) {
                    Text(
                        "Ohne eigenes Handy (Kind, Oma) genügt der Name.",
                        fontSize = 12.sp, color = c.textTertiary,
                    )
                    InviteWithPhoneButton(onDismiss)
                }
                if (onRemove != null) TextButton(onClick = onRemove) { Text("Aus der Familie entfernen", color = c.overdue) }
            }
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(name, color, kind) }) { Text("Speichern") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Six weeks by calendar week; open responsibility is a dashed circle with a question mark. */
@Composable
fun UpcomingScreen(data: FamilyData, model: AppModel) {
    val c = LocalFamilyColors.current
    val today = model.today()
    val weeks = Views.upcoming(data, today)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding) {
        weeks.forEach { week ->
            item(key = "w-${week.monday}") { SectionHeader(DateFormat.weekLabel(week.monday)) }
            if (week.items.isEmpty()) {
                item(key = "we-${week.monday}") { Text("Nichts mit Datum.", color = c.textTertiary, fontSize = 14.sp) }
            }
            items(week.items, key = { "u-${it.id}" }) { item ->
                val overdue = DateFormat.overdue(item.due!!, today)
                val large = largeFont()
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(c.card).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val overdueOpen = overdue != null && item.status == ItemStatus.OPEN
                    val dateColor = if (overdueOpen) c.overdue else c.textSecondary
                    val date: @Composable (Modifier) -> Unit = { m ->
                        Text(
                            DateFormat.short(item.due),
                            m,
                            color = dateColor,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                    // With large fonts the date goes above the title; beside it the title column
                    // would be too narrow for words like "Tabletten" (P1-4).
                    if (!large) date(Modifier.width(84.dp))
                    Column(Modifier.weight(1f)) {
                        if (large) date(Modifier)
                        // No line limit: this row cannot be opened, so a shortened title could never be read in full.
                        Text(item.title, color = c.text, fontSize = 15.sp)
                        val note = buildList {
                            if (item.status == ItemStatus.INBOX) add("noch nicht bestätigt")
                            if (overdue != null && item.status == ItemStatus.OPEN) add(overdue)
                        }
                        if (note.isNotEmpty()) Text(note.joinToString(" · "), color = c.textTertiary, fontSize = 12.sp)
                    }
                    PersonDot(data.member(item.assignee), 28.dp)
                }
            }
        }
    }
}

/**
 * One person in the load card. With large fonts (P1-4) name, bar and counts are stacked; side by
 * side they would push the counts off a narrow screen.
 */
@Composable
private fun LoadRow(load: Load, maxOpen: Int, selected: Boolean, onClick: () -> Unit) {
    val c = LocalFamilyColors.current
    val open = if (load.overdue > 0) "${load.open} · ${load.overdue} überfällig" else "${load.open} offen"
    val counts = if (load.doneThisWeek > 0) "$open · ${load.doneThisWeek} erledigt" else open
    val countsColor = if (load.overdue > 0) c.overdue else c.textSecondary
    val rowModifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(if (selected) c.background else c.card)
        .clickable(onClick = onClick)
        .padding(vertical = 6.dp, horizontal = 4.dp)
    if (largeFont()) {
        Column(rowModifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PersonDot(load.member, 28.dp)
                Text(load.member.name, Modifier.weight(1f).padding(start = 8.dp), color = c.text, fontSize = 14.sp)
            }
            LoadBar(load, maxOpen, Modifier.fillMaxWidth())
            Text(counts, color = countsColor, fontSize = 13.sp)
        }
    } else {
        Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
            PersonDot(load.member, 28.dp)
            Text(load.member.name, Modifier.width(80.dp).padding(start = 8.dp), color = c.text, fontSize = 14.sp)
            LoadBar(load, maxOpen, Modifier.weight(1f))
            Text(counts, Modifier.padding(start = 8.dp), color = countsColor, fontSize = 13.sp)
        }
    }
}

@Composable
private fun LoadBar(load: Load, maxOpen: Int, modifier: Modifier) {
    val c = LocalFamilyColors.current
    Box(modifier.height(14.dp).clip(RoundedCornerShape(7.dp)).background(c.background)) {
        Row(Modifier.fillMaxHeight().fillMaxWidth(load.open.toFloat() / maxOpen)) {
            val calm = load.open - load.overdue
            if (calm > 0) Box(Modifier.weight(calm.toFloat()).fillMaxHeight().background(c.person(load.member.colorIndex)))
            if (load.overdue > 0) Box(Modifier.weight(load.overdue.toFloat()).fillMaxHeight().background(c.overdue))
        }
    }
}

/** Family knowledge: everything ever captured, including done and dismissed things. */
@Composable
fun SearchScreen(data: FamilyData, model: AppModel, photos: PhotoLoader?) {
    val c = LocalFamilyColors.current
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<String?>(null) }
    val results = Views.search(data, query)
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Suchen in allem, auch Erledigtem") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            if (query.isNotBlank() && results.isEmpty()) item { EmptyNote("Nichts gefunden.") }
            if (query.isBlank()) item { EmptyNote("${data.items.size} Einträge durchsuchbar.") }
            items(results, key = { it.id }) { item ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(c.card)
                        .clickable { open = item.id }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, color = c.text, fontSize = 15.sp, maxLines = 2)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusLabel(item, data)
                            item.due?.let { Text(DateFormat.short(it), color = c.textTertiary, fontSize = 12.sp) }
                            Text(ago(item.createdMs, model.repo.now()), color = c.textTertiary, fontSize = 12.sp)
                        }
                    }
                    PersonDot(data.member(item.assignee), 28.dp)
                }
            }
        }
    }
    open?.let { id -> data.items.firstOrNull { it.id == id }?.let { ItemDialog(it, model, photos) { open = null } } }
}
