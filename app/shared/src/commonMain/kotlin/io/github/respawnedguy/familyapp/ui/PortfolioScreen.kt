package io.github.respawnedguy.familyapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.respawnedguy.familyapp.domain.DateFormat
import io.github.respawnedguy.familyapp.domain.FamilyData
import io.github.respawnedguy.familyapp.domain.Member
import io.github.respawnedguy.familyapp.domain.contacts.EntryKind
import io.github.respawnedguy.familyapp.domain.contacts.EntryTemplate
import io.github.respawnedguy.familyapp.domain.contacts.PortfolioEntry
import io.github.respawnedguy.familyapp.domain.contacts.ContactViews
import io.github.respawnedguy.familyapp.domain.contacts.PortfolioViews
import io.github.respawnedguy.familyapp.presentation.AppModel
import io.github.respawnedguy.familyapp.presentation.Tab
import io.github.respawnedguy.familyapp.resources.Res
import io.github.respawnedguy.familyapp.resources.common_cancel
import io.github.respawnedguy.familyapp.resources.contacts_from_phone
import io.github.respawnedguy.familyapp.resources.contacts_new
import io.github.respawnedguy.familyapp.resources.entry_edit_title
import io.github.respawnedguy.familyapp.resources.entry_kind_date
import io.github.respawnedguy.familyapp.resources.entry_kind_link
import io.github.respawnedguy.familyapp.resources.entry_kind_place
import io.github.respawnedguy.familyapp.resources.entry_kind_size
import io.github.respawnedguy.familyapp.resources.entry_kind_text
import io.github.respawnedguy.familyapp.resources.entry_label
import io.github.respawnedguy.familyapp.resources.entry_new_title
import io.github.respawnedguy.familyapp.resources.entry_open_link
import io.github.respawnedguy.familyapp.resources.entry_value
import io.github.respawnedguy.familyapp.resources.people_delete
import io.github.respawnedguy.familyapp.resources.people_save
import io.github.respawnedguy.familyapp.resources.portfolio_add_info
import io.github.respawnedguy.familyapp.resources.portfolio_codes_later
import io.github.respawnedguy.familyapp.resources.portfolio_contacts
import io.github.respawnedguy.familyapp.resources.portfolio_infos
import io.github.respawnedguy.familyapp.resources.portfolio_measured
import io.github.respawnedguy.familyapp.resources.portfolio_no_contacts
import io.github.respawnedguy.familyapp.resources.portfolio_nothing_open
import io.github.respawnedguy.familyapp.resources.portfolio_open
import io.github.respawnedguy.familyapp.resources.portfolio_open_hint
import io.github.respawnedguy.familyapp.resources.portfolio_stale
import io.github.respawnedguy.familyapp.resources.portfolio_still_right
import io.github.respawnedguy.familyapp.resources.portfolio_take_over_all
import io.github.respawnedguy.familyapp.resources.portfolio_template_hint
import io.github.respawnedguy.familyapp.resources.portfolio_waiting
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** An entry being edited: an existing fact, a template being filled, or a new own fact. */
private data class EntryEdit(val entry: PortfolioEntry?, val label: String, val kind: EntryKind)

/**
 * The portfolio of one person (docs/kontakte-und-portfolio.md, "Vier Abschnitte"): in the order
 * someone stepping in needs it. What is open, whom to call, the facts. Codes come with stage 2.
 */
@Composable
internal fun PortfolioScreen(
    member: Member,
    data: FamilyData,
    model: AppModel,
    photos: PhotoLoader?,
    reducedMotion: Boolean,
) {
    val c = LocalFamilyColors.current
    val today = model.today()
    var editing by remember { mutableStateOf<EntryEdit?>(null) }
    val open = PortfolioViews.open(data, member.id)
    val waiting = PortfolioViews.waiting(data, member.id)
    val contacts = ContactViews.contactsFor(data, member.id)
    val entries = PortfolioViews.entries(data, member)
    val templates = PortfolioViews.openTemplates(data, member)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PeoplePadding) {
        // 1 Offen
        item { OpenHeader(member, data, model, nothingOpen = open.isEmpty() && waiting.isEmpty()) }
        items(open, key = { it.id }) { item ->
            val takeOver = item.assignee != data.selfId
            Box(if (reducedMotion) Modifier else Modifier.animateItem()) {
                TaskRow(item, data, model, photos, showAssignee = true, takeOver = takeOver)
            }
        }
        if (waiting.isNotEmpty()) {
            item {
                val text = stringResource(Res.string.portfolio_waiting)
                Text(text, fontSize = 13.sp, color = c.textSecondary, modifier = Modifier.padding(top = 8.dp))
            }
            items(waiting, key = { it.id }) { item -> WaitingRow(item.title) { model.select(Tab.TODAY) } }
        }

        // 2 Kontakte
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader(stringResource(Res.string.portfolio_contacts), contacts.size)
                if (contacts.isEmpty()) {
                    Text(stringResource(Res.string.portfolio_no_contacts), fontSize = 14.sp, color = c.textSecondary)
                }
            }
        }
        items(contacts, key = { it.link.id }) { linked ->
            ContactRow(linked.contact, data, model, role = linked.link.role)
        }
        item { PortfolioAddContact(model, member.id) }

        // 3 Infos
        item { SectionHeader(stringResource(Res.string.portfolio_infos)) }
        items(entries, key = { it.id }) { entry ->
            EntryRow(entry, member, today, model) { editing = EntryEdit(entry, entry.label, entry.kind) }
        }
        items(templates, key = { it.label }) { template ->
            TemplateRow(template) { editing = EntryEdit(null, template.label, template.kind) }
        }
        item { AddInfo { editing = EntryEdit(null, "", EntryKind.TEXT) } }
    }

    editing?.let { edit ->
        EntryDialog(
            edit = edit,
            onDismiss = { editing = null },
            onDelete = edit.entry?.let { e -> { model.people.portfolio.deleteEntry(e.id); editing = null } },
            onSave = { kind, label, value ->
                model.people.portfolio.saveEntry(member.id, edit.entry?.id, kind, label, value)
                editing = null
            },
        )
    }
}

/** "Offen" with the hint that anyone can finish these directly, and "Alles übernehmen" (E6). */
@Composable
private fun OpenHeader(member: Member, data: FamilyData, model: AppModel, nothingOpen: Boolean) {
    val c = LocalFamilyColors.current
    val canTakeOver = member.id != data.selfId && PortfolioViews.takeOverable(data, member.id).isNotEmpty()
    Column {
        SectionHeader(stringResource(Res.string.portfolio_open), PortfolioViews.open(data, member.id).size)
        Text(stringResource(Res.string.portfolio_open_hint, member.name), fontSize = 12.sp, color = c.textTertiary)
        if (canTakeOver) {
            OutlinedButton(
                onClick = { model.people.portfolio.takeOverAll(member.id) },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(stringResource(Res.string.portfolio_take_over_all, member.name))
            }
        }
        if (nothingOpen) EmptyNote(stringResource(Res.string.portfolio_nothing_open))
    }
}

@Composable
private fun WaitingRow(title: String, onClick: () -> Unit) {
    val c = LocalFamilyColors.current
    Text(
        title,
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(c.card)
            .clickable(onClick = onClick).padding(12.dp),
        color = c.text,
        fontSize = 15.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun AddInfo(onAdd: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        OutlinedButton(onClick = onAdd) { Text(stringResource(Res.string.portfolio_add_info)) }
        val hint = stringResource(Res.string.portfolio_codes_later)
        Text(hint, fontSize = 12.sp, color = LocalFamilyColors.current.textTertiary)
    }
}

@Composable
private fun PortfolioAddContact(model: AppModel, memberId: String) {
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (model.people.canPickContacts) {
            TextButton(onClick = { model.people.contacts.pickFromPhoneBook(memberId) }) {
                Text(stringResource(Res.string.contacts_from_phone))
            }
        }
        TextButton(onClick = { model.people.newContact(memberId) }) {
            Text(stringResource(Res.string.contacts_new))
        }
    }
}

@Composable
private fun EntryRow(entry: PortfolioEntry, member: Member, today: LocalDate, model: AppModel, onEdit: () -> Unit) {
    val c = LocalFamilyColors.current
    val stale = PortfolioViews.isStale(entry, member, today)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(c.card)
            .clickable(onClick = onEdit).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.label, color = c.textSecondary, fontSize = 13.sp)
                Text(entry.value, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            if (entry.kind == EntryKind.LINK) {
                TextButton(onClick = { model.people.apps.openLink(entry.value) }) {
                    Text(stringResource(Res.string.entry_open_link))
                }
            }
        }
        entry.measuredOn?.let {
            val text = stringResource(Res.string.portfolio_measured, DateFormat.dayMonth(it))
            Text(text, color = c.textTertiary, fontSize = 12.sp)
        }
        if (stale) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val staleText = stringResource(Res.string.portfolio_stale)
                Text(staleText, Modifier.weight(1f), color = c.dueSoon, fontSize = 14.sp)
                TextButton(onClick = { model.people.portfolio.confirmEntry(entry.id) }) {
                    Text(stringResource(Res.string.portfolio_still_right))
                }
            }
        }
    }
}

@Composable
private fun TemplateRow(template: EntryTemplate, onClick: () -> Unit) {
    val c = LocalFamilyColors.current
    Text(
        stringResource(Res.string.portfolio_template_hint, template.label),
        Modifier.fillMaxWidth().padding(vertical = 4.dp).heightIn(min = 48.dp)
            .dashedBorder(c.textTertiary, 12.dp).clickable(onClick = onClick).padding(12.dp),
        color = c.textTertiary,
        fontSize = 15.sp,
    )
}

private val KIND_LABELS: List<Pair<EntryKind, StringResource>> = listOf(
    EntryKind.SIZE to Res.string.entry_kind_size,
    EntryKind.DATE to Res.string.entry_kind_date,
    EntryKind.TEXT to Res.string.entry_kind_text,
    EntryKind.PLACE to Res.string.entry_kind_place,
    EntryKind.LINK to Res.string.entry_kind_link,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryDialog(
    edit: EntryEdit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (EntryKind, String, String) -> Unit,
) {
    val c = LocalFamilyColors.current
    var label by remember { mutableStateOf(edit.label) }
    var value by remember { mutableStateOf(edit.entry?.value.orEmpty()) }
    var kind by remember { mutableStateOf(edit.kind) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (edit.entry == null) Res.string.entry_new_title else Res.string.entry_edit_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PeopleField(label, { label = it }, stringResource(Res.string.entry_label))
                PeopleField(value, { value = it }, stringResource(Res.string.entry_value))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    KIND_LABELS.forEach { (k, res) ->
                        Text(
                            stringResource(res),
                            Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp))
                                .background(if (k == kind) c.accent else c.background)
                                .clickable { kind = k }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            color = if (k == kind) c.onAccent else c.text,
                            fontSize = 14.sp,
                        )
                    }
                }
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text(stringResource(Res.string.people_delete), color = c.overdue) }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(kind, label, value) }) { Text(stringResource(Res.string.people_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } },
    )
}
