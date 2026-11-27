package io.github.respawnedguy.familyapp.presentation

import io.github.respawnedguy.familyapp.data.FamilyRepository
import io.github.respawnedguy.familyapp.data.Undo
import io.github.respawnedguy.familyapp.domain.contacts.Contact
import io.github.respawnedguy.familyapp.domain.contacts.ContactDraft
import io.github.respawnedguy.familyapp.domain.contacts.ContactRules
import io.github.respawnedguy.familyapp.domain.contacts.ContactSource
import io.github.respawnedguy.familyapp.domain.contacts.EntryKind
import io.github.respawnedguy.familyapp.domain.contacts.VCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where in contacts and portfolios the person is. Empty stack in [PeopleModel.route]: not there.
 * The contact list itself is the tab "Kontakte" under Familie ([FamilySection.CONTACTS]).
 */
sealed interface PeopleRoute {
    data class Contact(val id: String) : PeopleRoute

    /** [id] null: a new contact, linked to [memberId] when it comes from a portfolio. */
    data class EditContact(val id: String?, val memberId: String? = null) : PeopleRoute
    data class Portfolio(val memberId: String) : PeopleRoute
}

/**
 * Presentation logic of shared contacts and the portfolio per person
 * (docs/kontakte-und-portfolio.md, stage 1). Lives inside [AppModel]; messages go through its
 * feedback bar with undo, nothing asks a question first. Navigation is a small stack, so "back"
 * from a contact opened in a portfolio returns to that portfolio.
 */
class PeopleModel internal constructor(
    private val repo: FamilyRepository,
    private val platform: ContactActions,
    show: (String, Undo?) -> Unit,
    /** The contact list and the people are tabs under "Familie", not pages here. */
    private val sections: (FamilySection) -> Unit = {},
    /** Called whenever a people screen opens, so the kitchen and settings close. */
    private val onOpen: () -> Unit = {},
) {
    /** Adding, changing and linking contacts. */
    val contacts = ContactEdits(repo, platform, show, this)

    /** Facts in a portfolio and "Alles übernehmen". */
    val portfolio = PortfolioEdits(repo, show)

    /** Dialler, SMS, mail, maps, browser and the phone's contacts app. */
    val apps = OtherApps(platform, show)

    private val stack = mutableListOf<PeopleRoute>()
    private val _route = MutableStateFlow<PeopleRoute?>(null)
    val route: StateFlow<PeopleRoute?> = _route.asStateFlow()

    val canPickContacts: Boolean get() = platform.canPickContacts

    private fun push(route: PeopleRoute) {
        if (stack.isEmpty()) onOpen()
        if (stack.lastOrNull() != route) stack += route
        _route.value = route
    }

    internal fun replaceTop(route: PeopleRoute) {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        push(route)
    }

    /** The tab "Kontakte" under Familie; open contact pages close. */
    fun openContacts() = sections(FamilySection.CONTACTS)

    fun openContact(id: String) {
        if (repo.contactStore.contact(id) != null) push(PeopleRoute.Contact(id))
    }

    fun openPortfolio(memberId: String) {
        if (repo.current.member(memberId) != null) push(PeopleRoute.Portfolio(memberId))
    }

    fun newContact(memberId: String? = null) = push(PeopleRoute.EditContact(null, memberId))

    fun editContact(id: String) = push(PeopleRoute.EditContact(id))

    fun close() {
        stack.clear()
        _route.value = null
    }

    /** A deleted contact: its screens leave the stack. */
    internal fun forget(contactId: String) {
        stack.removeAll { it == PeopleRoute.Contact(contactId) || it == PeopleRoute.EditContact(contactId) }
        _route.value = stack.lastOrNull()
    }

    /** System back inside contacts and portfolios. False when none is open. */
    fun back(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        _route.value = stack.lastOrNull()
        return true
    }
}

/** Contacts from the picker, a shared vCard or the form, and their links to people. */
class ContactEdits internal constructor(
    private val repo: FamilyRepository,
    private val platform: ContactActions,
    private val show: (String, Undo?) -> Unit,
    private val nav: PeopleModel,
) {
    // ---- Adding contacts ---------------------------------------------------------------------

    /** "Aus Telefonbuch": the system picker, no permission (E1). [memberId] links the contact right away. */
    fun pickFromPhoneBook(memberId: String? = null) {
        platform.pickContact { draft -> if (draft != null) addDrafts(listOf(draft), ContactSource.PICKER, memberId) }
    }

    /** A vCard shared from the contacts app (threat model KT-1). Stored at once, with undo. */
    fun importVCard(text: String) {
        val result = VCard.parse(text)
        when {
            result.tooLarge -> show("Kontakt nicht übernommen: Die Datei ist größer als 1 MB.", null)
            result.drafts.isEmpty() -> show("In der geteilten Datei war kein Kontakt.", null)
            else -> {
                val skipped = " ${result.skipped} nicht übernommen (höchstens ${VCard.MAX_CONTACTS})."
                val note = if (result.skipped > 0) skipped else ""
                addDrafts(result.drafts, ContactSource.VCARD, null, note)
            }
        }
    }

    private fun addDrafts(drafts: List<ContactDraft>, source: ContactSource, memberId: String?, note: String = "") {
        val result = repo.contactStore.add(drafts, source, memberId)
        val contacts = result.all
        if (contacts.isEmpty()) {
            show("Kein Name oder keine Nummer gefunden, nichts gespeichert.", null)
            return
        }
        val text = when {
            result.added.isEmpty() && contacts.size == 1 ->
                "Mit vorhandenem Kontakt ${contacts.first().name} zusammengeführt."
            contacts.size == 1 -> "${contacts.first().name} gespeichert."
            else -> "${contacts.size} Kontakte gespeichert."
        }
        show(text + note, result.undo)
        when {
            memberId != null -> Unit // stays in the portfolio, the contact appears there
            contacts.size == 1 -> nav.openContact(contacts.first().id)
            else -> nav.openContacts()
        }
    }

    /** The form. A new contact from a portfolio is linked to that person. */
    fun saveContact(id: String?, draft: ContactDraft, memberId: String? = null) {
        if (id == null) {
            create(draft, memberId)
        } else {
            val undo = repo.contactStore.update(id, draft)
            if (undo == null) {
                show(NAME_MISSING, null)
            } else {
                show("Gespeichert.", undo)
                nav.back()
            }
        }
    }

    private fun create(draft: ContactDraft, memberId: String?) {
        val result = repo.contactStore.add(listOf(draft), ContactSource.MANUAL, memberId)
        val contact = result.all.firstOrNull()
        when {
            contact == null -> show(NAME_MISSING, null)
            memberId != null -> {
                show("${contact.name} gespeichert.", result.undo)
                nav.back()
            }
            else -> {
                show("${contact.name} gespeichert.", result.undo)
                nav.replaceTop(PeopleRoute.Contact(contact.id))
            }
        }
    }

    fun deleteContact(id: String) {
        val name = repo.contactStore.contact(id)?.name ?: return
        show("$name gelöscht.", repo.contactStore.delete(id))
        nav.forget(id)
    }

    // ---- Links -------------------------------------------------------------------------------

    /** Role suggested for a new link between this contact and anybody (rules, HITL). */
    fun suggestedRole(contact: Contact): String =
        ContactRules.suggestRole(ContactDraft(contact.name, organization = contact.organization))

    fun link(contactId: String, memberId: String, role: String) {
        val name = repo.current.member(memberId)?.name ?: return
        show("Verknüpft mit $name.", repo.contactStore.link(contactId, memberId, role))
    }

    fun unlink(linkId: String) {
        val link = repo.current.contactLinks.firstOrNull { it.id == linkId } ?: return
        val name = repo.current.member(link.memberId)?.name ?: "der Person"
        show("Nicht mehr mit $name verknüpft.", repo.contactStore.unlink(linkId))
    }

    private companion object {
        const val NAME_MISSING = "Bitte einen Namen oder eine Nummer eintragen."
    }
}

/** The facts of a portfolio and taking over somebody's open items. */
class PortfolioEdits internal constructor(
    private val repo: FamilyRepository,
    private val show: (String, Undo?) -> Unit,
) {
    // ---- Portfolio ---------------------------------------------------------------------------

    fun saveEntry(memberId: String, entryId: String?, kind: EntryKind, label: String, value: String) {
        val undo = repo.portfolioStore.save(memberId, entryId, kind, label, value)
        when {
            undo != null -> show("Gespeichert.", undo)
            kind == EntryKind.LINK && value.isNotBlank() -> show("Als Link gehen nur Webadressen (https://…).", null)
            else -> show("Bitte Bezeichnung und Wert eintragen.", null)
        }
    }

    fun confirmEntry(entryId: String) {
        repo.portfolioStore.confirm(entryId)?.let { show("Als aktuell vermerkt.", it) }
    }

    fun deleteEntry(entryId: String) {
        repo.portfolioStore.delete(entryId)?.let { show("Gelöscht.", it) }
    }

    /** "Alles von Nils übernehmen" (E6). Everybody sees who took what. */
    fun takeOverAll(memberId: String) {
        val name = repo.current.member(memberId)?.name ?: return
        val (count, undo) = repo.takeOverAll(memberId)
        if (count == 0) show("$name hat gerade keine offenen Aufgaben.", null)
        else show("$count Aufgaben von $name übernommen.", undo)
    }
}

/** Everything that hands over to another app the person controls (E1, KT-2, KT-5). */
class OtherApps internal constructor(
    private val platform: ContactActions,
    private val show: (String, Undo?) -> Unit,
) {
    // ---- Opening other apps -----------------------------------------------------------------

    fun call(number: String) = tell(platform.dial(ContactRules.dialable(number)), "Keine Telefon-App gefunden.")

    fun sms(number: String) = tell(platform.sendSms(ContactRules.dialable(number)), "Keine SMS-App gefunden.")

    fun mail(address: String) = tell(platform.sendMail(address), "Keine Mail-App gefunden.")

    fun showAddress(address: String) = tell(platform.showAddress(address), "Keine Karten-App gefunden.")

    fun openLink(url: String) {
        if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) return
        tell(platform.openWebLink(url), "Kein Browser gefunden.")
    }

    fun addToPhoneBook(contact: Contact) {
        val draft = with(contact) { ContactDraft(name, phones, emails, address, organization, birthday) }
        tell(platform.addToPhoneBook(draft), "Keine Kontakte-App gefunden.")
    }

    private fun tell(ok: Boolean, problem: String) {
        if (!ok) show(problem, null)
    }
}
