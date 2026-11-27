package io.github.respawnedguy.familyapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.respawnedguy.familyapp.domain.FamilyData
import io.github.respawnedguy.familyapp.domain.contacts.Contact
import io.github.respawnedguy.familyapp.domain.contacts.ContactViews
import io.github.respawnedguy.familyapp.domain.contacts.ContactDraft
import io.github.respawnedguy.familyapp.domain.contacts.ContactRules
import io.github.respawnedguy.familyapp.presentation.AppModel
import io.github.respawnedguy.familyapp.presentation.PeopleRoute
import io.github.respawnedguy.familyapp.resources.Res
import io.github.respawnedguy.familyapp.resources.contact_call
import io.github.respawnedguy.familyapp.resources.contact_edit_title
import io.github.respawnedguy.familyapp.resources.contact_field_address
import io.github.respawnedguy.familyapp.resources.contact_field_birthday
import io.github.respawnedguy.familyapp.resources.contact_field_emails
import io.github.respawnedguy.familyapp.resources.contact_field_name
import io.github.respawnedguy.familyapp.resources.contact_field_organization
import io.github.respawnedguy.familyapp.resources.contact_field_phones
import io.github.respawnedguy.familyapp.resources.contact_new_title
import io.github.respawnedguy.familyapp.resources.contacts_empty
import io.github.respawnedguy.familyapp.resources.contacts_entry_counts
import io.github.respawnedguy.familyapp.resources.contacts_from_phone
import io.github.respawnedguy.familyapp.resources.contacts_general
import io.github.respawnedguy.familyapp.resources.contacts_hint
import io.github.respawnedguy.familyapp.resources.contacts_new
import io.github.respawnedguy.familyapp.resources.contacts_nothing_found
import io.github.respawnedguy.familyapp.resources.contacts_open
import io.github.respawnedguy.familyapp.resources.contacts_search_placeholder
import io.github.respawnedguy.familyapp.resources.contacts_title
import io.github.respawnedguy.familyapp.resources.people_save
import org.jetbrains.compose.resources.stringResource

internal val PeoplePadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)

/** People shown as dots next to a contact in the list; more would crowd out the name. */
private const val MAX_DOTS = 4

/** Entry under "Familie", like the kitchen library: no own tab (navigation D1 has five places). */
@Composable
fun ContactsEntryCard(data: FamilyData, model: AppModel) {
    val c = LocalFamilyColors.current
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(16.dp)).background(c.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(Res.string.contacts_title), Modifier.semantics { heading() },
            fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.text,
        )
        Text(
            stringResource(Res.string.contacts_entry_counts, ContactViews.contacts(data).size),
            fontSize = 14.sp, color = c.textSecondary,
        )
        OutlinedButton(onClick = { model.people.openContacts() }) { Text(stringResource(Res.string.contacts_open)) }
    }
}

/** Title for the top bar while contacts or a portfolio are open. */
@Composable
fun peopleTitle(route: PeopleRoute, data: FamilyData): String {
    val contacts = stringResource(Res.string.contacts_title)
    return when (route) {
        PeopleRoute.Contacts -> contacts
        is PeopleRoute.Contact -> ContactViews.contact(data, route.id)?.name ?: contacts
        is PeopleRoute.EditContact ->
            stringResource(if (route.id == null) Res.string.contact_new_title else Res.string.contact_edit_title)
        is PeopleRoute.Portfolio -> data.member(route.memberId)?.name ?: contacts
    }
}

/** Content area for contacts and portfolios. Something deleted on another phone falls back to the list. */
@Composable
fun PeopleScreen(route: PeopleRoute, data: FamilyData, model: AppModel, photos: PhotoLoader?, reducedMotion: Boolean) {
    when (route) {
        PeopleRoute.Contacts -> ContactListScreen(data, model)
        is PeopleRoute.Contact -> {
            val contact = ContactViews.contact(data, route.id)
            if (contact == null) ContactListScreen(data, model) else ContactScreen(contact, data, model)
        }
        is PeopleRoute.EditContact -> {
            val contact = route.id?.let { ContactViews.contact(data, it) }
            ContactEditScreen(contact, route.memberId, model)
        }
        is PeopleRoute.Portfolio -> {
            val member = data.member(route.memberId)
            if (member == null) ContactListScreen(data, model)
            else PortfolioScreen(member, data, model, photos, reducedMotion)
        }
    }
}

@Composable
private fun AddContactButtons(model: AppModel, memberId: String?) {
    val fromPhone: @Composable () -> Unit = {
        Button(onClick = { model.people.contacts.pickFromPhoneBook(memberId) }) {
            Text(stringResource(Res.string.contacts_from_phone))
        }
    }
    val new: @Composable () -> Unit = {
        OutlinedButton(onClick = { model.people.newContact(memberId) }) {
            Text(stringResource(Res.string.contacts_new))
        }
    }
    // At large font sizes two buttons side by side do not fit on narrow phones (P1-4).
    if (largeFont()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (model.people.canPickContacts) fromPhone()
            new()
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (model.people.canPickContacts) fromPhone()
            new()
        }
    }
}

@Composable
private fun ContactListScreen(data: FamilyData, model: AppModel) {
    val c = LocalFamilyColors.current
    var query by remember { mutableStateOf("") }
    val all = ContactViews.contacts(data)
    val shown = if (query.isBlank()) all else ContactViews.searchContacts(data, query)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PeoplePadding) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.contacts_hint), fontSize = 13.sp, color = c.textSecondary)
                AddContactButtons(model, null)
                if (all.isNotEmpty()) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(stringResource(Res.string.contacts_search_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        if (all.isEmpty()) item { EmptyNote(stringResource(Res.string.contacts_empty)) }
        if (all.isNotEmpty() && shown.isEmpty()) item { EmptyNote(stringResource(Res.string.contacts_nothing_found)) }
        items(shown, key = { it.id }) { contact -> ContactRow(contact, data, model) }
    }
}

@Composable
internal fun ContactRow(contact: Contact, data: FamilyData, model: AppModel, role: String? = null) {
    val c = LocalFamilyColors.current
    val links = ContactViews.linksOf(data, contact.id)
    val general = stringResource(Res.string.contacts_general)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(c.card)
            .clickable { model.people.openContact(contact.id) }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(contact.name, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            val sub = role?.takeIf { it.isNotBlank() }
                ?: links.map { it.role }.filter { it.isNotBlank() }.distinct().joinToString(" · ").ifEmpty {
                    contact.organization ?: if (links.isEmpty()) general else ""
                }
            if (sub.isNotEmpty()) Text(sub, color = c.textTertiary, fontSize = 12.sp)
        }
        if (role == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                links.mapNotNull { data.member(it.memberId) }.distinct().take(MAX_DOTS).forEach { PersonDot(it, 24.dp) }
            }
        }
        contact.phones.firstOrNull()?.let { phone ->
            TextButton(onClick = { model.people.apps.call(phone) }) { Text(stringResource(Res.string.contact_call)) }
        }
    }
}

/** The form: numbers and mail addresses one per line, so nothing needs plus and minus buttons. */
@Composable
private fun ContactEditScreen(contact: Contact?, memberId: String?, model: AppModel) {
    val key = contact?.id
    var name by remember(key) { mutableStateOf(contact?.name.orEmpty()) }
    var phones by remember(key) { mutableStateOf(contact?.phones.orEmpty().joinToString("\n")) }
    var emails by remember(key) { mutableStateOf(contact?.emails.orEmpty().joinToString("\n")) }
    var address by remember(key) { mutableStateOf(contact?.address.orEmpty()) }
    var organization by remember(key) { mutableStateOf(contact?.organization.orEmpty()) }
    var birthday by remember(key) { mutableStateOf(contact?.birthday?.let(ContactRules::formatBirthday).orEmpty()) }
    val save = {
        model.people.contacts.saveContact(
            contact?.id,
            ContactDraft(
                name = name,
                phones = phones.lines(),
                emails = emails.lines(),
                address = address,
                organization = organization,
                birthday = ContactRules.parseBirthday(birthday),
            ),
            memberId,
        )
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PeoplePadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PeopleField(name, { name = it }, stringResource(Res.string.contact_field_name))
        PeopleField(organization, { organization = it }, stringResource(Res.string.contact_field_organization))
        val phonesLabel = stringResource(Res.string.contact_field_phones)
        PeopleField(phones, { phones = it }, phonesLabel, single = false, type = KeyboardType.Phone)
        val emailsLabel = stringResource(Res.string.contact_field_emails)
        PeopleField(emails, { emails = it }, emailsLabel, single = false, type = KeyboardType.Email)
        PeopleField(address, { address = it }, stringResource(Res.string.contact_field_address))
        val birthdayLabel = stringResource(Res.string.contact_field_birthday)
        PeopleField(birthday, { birthday = it }, birthdayLabel, type = KeyboardType.Number)
        Button(onClick = save, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(stringResource(Res.string.people_save), fontSize = 16.sp)
        }
    }
}

@Composable
internal fun PeopleField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    single: Boolean = true,
    type: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = single,
        minLines = if (single) 1 else 2,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        modifier = Modifier.fillMaxWidth(),
    )
}
