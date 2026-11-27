package io.github.respawnedguy.familyapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import io.github.respawnedguy.familyapp.domain.FamilyData
import io.github.respawnedguy.familyapp.domain.Member
import io.github.respawnedguy.familyapp.domain.contacts.Contact
import io.github.respawnedguy.familyapp.domain.contacts.ContactLink
import io.github.respawnedguy.familyapp.domain.contacts.ContactRules
import io.github.respawnedguy.familyapp.domain.contacts.ContactViews
import io.github.respawnedguy.familyapp.presentation.AppModel
import io.github.respawnedguy.familyapp.resources.Res
import io.github.respawnedguy.familyapp.resources.common_cancel
import io.github.respawnedguy.familyapp.resources.contact_birthday
import io.github.respawnedguy.familyapp.resources.contact_call
import io.github.respawnedguy.familyapp.resources.contact_changed_by
import io.github.respawnedguy.familyapp.resources.contact_edit
import io.github.respawnedguy.familyapp.resources.contact_for_whom
import io.github.respawnedguy.familyapp.resources.contact_for_whom_hint
import io.github.respawnedguy.familyapp.resources.contact_mail
import io.github.respawnedguy.familyapp.resources.contact_route
import io.github.respawnedguy.familyapp.resources.contact_sms
import io.github.respawnedguy.familyapp.resources.contact_tasks
import io.github.respawnedguy.familyapp.resources.contact_to_phone_book
import io.github.respawnedguy.familyapp.resources.contact_to_phone_book_hint
import io.github.respawnedguy.familyapp.resources.people_delete
import io.github.respawnedguy.familyapp.resources.people_save
import io.github.respawnedguy.familyapp.resources.role_none
import io.github.respawnedguy.familyapp.resources.role_placeholder
import io.github.respawnedguy.familyapp.resources.role_remove
import io.github.respawnedguy.familyapp.resources.role_title
import org.jetbrains.compose.resources.stringResource

/**
 * One contact: everything that reaches the contact opens another app (E1), below it for whom the
 * contact is, with the role per person (n:m). Tapping a person links or changes the role.
 */
@Composable
internal fun ContactScreen(contact: Contact, data: FamilyData, model: AppModel) {
    var roleFor by remember { mutableStateOf<Pair<String, ContactLink?>?>(null) }
    val links = ContactViews.linksOf(data, contact.id)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PeoplePadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ContactDetails(contact, model)
        SectionHeader(stringResource(Res.string.contact_for_whom))
        val hint = stringResource(Res.string.contact_for_whom_hint)
        Text(hint, fontSize = 12.sp, color = LocalFamilyColors.current.textTertiary)
        LinkChips(data.members, links) { member, link -> roleFor = member.id to link }
        ContactFooter(contact, data, model)
    }

    roleFor?.let { (memberId, link) ->
        val member = data.member(memberId)
        if (member != null) {
            RoleDialog(
                memberName = member.name,
                initial = link?.role ?: model.people.contacts.suggestedRole(contact),
                onDismiss = { roleFor = null },
                onRemove = link?.let { { model.people.contacts.unlink(it.id); roleFor = null } },
                onSave = { role -> model.people.contacts.link(contact.id, memberId, role); roleFor = null },
            )
        }
    }
}

@Composable
private fun ContactDetails(contact: Contact, model: AppModel) {
    val c = LocalFamilyColors.current
    val apps = model.people.apps
    contact.organization?.let { Text(it, color = c.textSecondary, fontSize = 15.sp) }
    contact.phones.forEach { phone ->
        ActionLine(phone) {
            TextButton(onClick = { apps.call(phone) }) { Text(stringResource(Res.string.contact_call)) }
            TextButton(onClick = { apps.sms(phone) }) { Text(stringResource(Res.string.contact_sms)) }
        }
    }
    contact.emails.forEach { mail ->
        ActionLine(mail) {
            TextButton(onClick = { apps.mail(mail) }) { Text(stringResource(Res.string.contact_mail)) }
        }
    }
    contact.address?.let { address ->
        ActionLine(address) {
            TextButton(onClick = { apps.showAddress(address) }) { Text(stringResource(Res.string.contact_route)) }
        }
    }
    contact.birthday?.let {
        val text = stringResource(Res.string.contact_birthday, ContactRules.formatBirthday(it))
        Text(text, color = c.text, fontSize = 15.sp)
    }
}

@Composable
private fun ActionLine(text: String, actions: @Composable () -> Unit) {
    val c = LocalFamilyColors.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.card).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f).padding(vertical = 12.dp), color = c.text, fontSize = 15.sp)
        actions()
    }
}

/** Every person as a chip; linked ones are bold and show their role. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinkChips(members: List<Member>, links: List<ContactLink>, onPick: (Member, ContactLink?) -> Unit) {
    val c = LocalFamilyColors.current
    val noRole = stringResource(Res.string.role_none)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        members.forEach { member ->
            val link = links.firstOrNull { it.memberId == member.id }
            Row(
                Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp))
                    .background(if (link != null) c.card else c.background)
                    .clickable { onPick(member, link) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PersonDot(member, 28.dp)
                Column(Modifier.padding(start = 8.dp)) {
                    val weight = if (link != null) FontWeight.SemiBold else FontWeight.Normal
                    Text(member.name, color = c.text, fontSize = 14.sp, fontWeight = weight)
                    if (link != null) Text(link.role.ifBlank { noRole }, color = c.textTertiary, fontSize = 12.sp)
                }
            }
        }
    }
}

/** Open items that name the contact, then phone book, edit, delete and who changed it last. */
@Composable
private fun ContactFooter(contact: Contact, data: FamilyData, model: AppModel) {
    val c = LocalFamilyColors.current
    val tasks = ContactViews.mentioningItems(data, contact)
    if (tasks.isNotEmpty()) {
        SectionHeader(stringResource(Res.string.contact_tasks), tasks.size)
        tasks.forEach { Text(it.title, color = c.text, fontSize = 15.sp, modifier = Modifier.padding(vertical = 4.dp)) }
    }
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { model.people.apps.addToPhoneBook(contact) }) {
            Text(stringResource(Res.string.contact_to_phone_book))
        }
        Text(stringResource(Res.string.contact_to_phone_book_hint), fontSize = 12.sp, color = c.textTertiary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { model.people.editContact(contact.id) }) {
                Text(stringResource(Res.string.contact_edit))
            }
            TextButton(onClick = { model.people.contacts.deleteContact(contact.id) }) {
                Text(stringResource(Res.string.people_delete), color = c.overdue)
            }
        }
        data.member(contact.changedBy)?.let {
            Text(stringResource(Res.string.contact_changed_by, it.name), fontSize = 12.sp, color = c.textTertiary)
        }
    }
}

/** Role of one link, with the presets as chips. [onRemove] is null while the person is not linked yet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoleDialog(
    memberName: String,
    initial: String,
    onDismiss: () -> Unit,
    onRemove: (() -> Unit)?,
    onSave: (String) -> Unit,
) {
    val c = LocalFamilyColors.current
    var role by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.role_title, memberName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = role,
                    onValueChange = { role = it },
                    placeholder = { Text(stringResource(Res.string.role_placeholder)) },
                    singleLine = true,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ContactRules.ROLE_PRESETS.forEach { preset ->
                        Text(
                            preset,
                            Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp))
                                .background(if (preset == role) c.accent else c.background)
                                .clickable { role = preset }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            color = if (preset == role) c.onAccent else c.text,
                            fontSize = 14.sp,
                        )
                    }
                }
                onRemove?.let {
                    TextButton(onClick = it) { Text(stringResource(Res.string.role_remove), color = c.overdue) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(role) }) { Text(stringResource(Res.string.people_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } },
    )
}
