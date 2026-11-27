package io.github.respawnedguy.familyapp.data

import io.github.respawnedguy.familyapp.domain.contacts.Contact
import io.github.respawnedguy.familyapp.domain.contacts.ContactViews
import io.github.respawnedguy.familyapp.domain.contacts.ContactDraft
import io.github.respawnedguy.familyapp.domain.contacts.ContactLink
import io.github.respawnedguy.familyapp.domain.contacts.ContactRules
import io.github.respawnedguy.familyapp.domain.contacts.ContactSource
import io.github.respawnedguy.familyapp.domain.contacts.EntryKind
import io.github.respawnedguy.familyapp.domain.contacts.PortfolioEntry
import io.github.respawnedguy.familyapp.domain.contacts.PortfolioViews

/** What adding contacts did: new ones, existing ones that got the new numbers instead of a duplicate. */
data class ContactsAdded(val added: List<Contact>, val merged: List<Contact>, val undo: Undo) {
    val all: List<Contact> get() = added + merged
}

/**
 * Shared family contacts and their links to people (docs/kontakte-und-portfolio.md). Same state,
 * clock and storage as [FamilyRepository]; every change gets a new stamp and who made it, so sync
 * merges per record and everybody sees who changed what. Deleting sets `deleted` and empties the
 * fields instead of removing the record.
 */
class ContactRepository internal constructor(private val repo: FamilyRepository) {

    private val self: String? get() = repo.current.selfId

    fun contact(id: String): Contact? = ContactViews.contact(repo.current, id)

    /**
     * Stores contacts from the picker, a vCard or the form, each cleaned by [ContactRules.clean].
     * A contact with a phone number or mail address the family already has is merged into that one
     * (new numbers added, nothing overwritten) instead of becoming a duplicate. With [linkTo] every
     * contact is linked to that person, with the suggested role.
     */
    fun add(drafts: List<ContactDraft>, source: ContactSource, linkTo: String? = null): ContactsAdded {
        val added = mutableListOf<Contact>()
        val merged = mutableListOf<Contact>()
        val before = mutableMapOf<String, Contact?>()
        val linksBefore = mutableMapOf<String, ContactLink?>()
        for (raw in drafts) {
            val draft = ContactRules.clean(raw) ?: continue
            // Also finds a contact added a moment ago from the same file: it is already in the state.
            val existing = ContactViews.duplicateOf(repo.current, draft)
            val contact = if (existing != null) {
                before.getOrPut(existing.id) { existing }
                // A contact new in this batch stays "added"; only older ones count as merged.
                val target = if (added.any { it.id == existing.id }) added else merged
                mergeInto(existing, draft).also { target.removeAll { m -> m.id == it.id }; target += it }
            } else {
                newContact(draft, source).also { before[it.id] = null; added += it }
            }
            if (linkTo != null && repo.current.member(linkTo) != null) {
                linkInternal(contact.id, linkTo, ContactRules.suggestRole(draft))?.let { (id, old) ->
                    linksBefore.getOrPut(id) { old }
                }
            }
        }
        fun now(list: List<Contact>) = list.mapNotNull { m -> repo.current.contacts.firstOrNull { it.id == m.id } }
        val undo = Undo(emptyMap(), people = PeopleBefore(contacts = before, links = linksBefore))
        return ContactsAdded(now(added), now(merged), undo)
    }

    private fun newContact(draft: ContactDraft, source: ContactSource): Contact {
        val contact = Contact(
            id = repo.nextId(),
            name = draft.name,
            phones = draft.phones,
            emails = draft.emails,
            address = draft.address,
            organization = draft.organization,
            birthday = draft.birthday,
            source = source,
            createdMs = repo.now(),
            createdBy = self.orEmpty(),
            changedBy = self,
            stamp = repo.tick(),
        )
        repo.edit { it.copy(contacts = it.contacts + contact) }
        return contact
    }

    /** Adds what is new; what the family already wrote stays (a shared vCard must not overwrite it). */
    private fun mergeInto(existing: Contact, draft: ContactDraft): Contact {
        val current = repo.current.contacts.first { it.id == existing.id }
        val phones = (current.phones + draft.phones).distinctBy(ContactRules::phoneKey).take(ContactRules.MAX_VALUES)
        val emails = (current.emails + draft.emails).distinctBy { it.lowercase() }.take(ContactRules.MAX_VALUES)
        val updated = current.copy(
            phones = phones,
            emails = emails,
            address = current.address ?: draft.address,
            organization = current.organization ?: draft.organization,
            birthday = current.birthday ?: draft.birthday,
            changedBy = self,
            stamp = repo.tick(),
        )
        repo.edit { d -> d.copy(contacts = d.contacts.map { if (it.id == updated.id) updated else it }) }
        return updated
    }

    /** Saving the form. Returns null when the name is empty (nothing changed). */
    fun update(id: String, draft: ContactDraft): Undo? {
        val before = contact(id)
        val clean = ContactRules.clean(draft)
        if (before == null || clean == null) return null
        repo.edit { d ->
            d.copy(contacts = d.contacts.map {
                if (it.id != id) it else it.copy(
                    name = clean.name,
                    phones = clean.phones,
                    emails = clean.emails,
                    address = clean.address,
                    organization = clean.organization,
                    birthday = clean.birthday,
                    changedBy = d.selfId,
                    stamp = repo.tick(),
                )
            })
        }
        return Undo(emptyMap(), people = PeopleBefore(contacts = mapOf(id to before)))
    }

    /** Deletes the contact and its links; the fields are emptied, the record stays as a marker for sync. */
    fun delete(id: String): Undo {
        val before = contact(id) ?: return Undo(emptyMap())
        val links = repo.current.contactLinks.filter { it.contactId == id && !it.deleted }
        repo.edit { d ->
            d.copy(
                contacts = d.contacts.map {
                    if (it.id == id) emptied(it).copy(changedBy = d.selfId, stamp = repo.tick()) else it
                },
                contactLinks = d.contactLinks.map {
                    if (it in links) it.copy(deleted = true, changedBy = d.selfId, stamp = repo.tick()) else it
                },
            )
        }
        val people = PeopleBefore(contacts = mapOf(id to before), links = links.associateBy { it.id })
        return Undo(emptyMap(), people = people)
    }

    /** Links a contact to a person, or changes the role of an existing link. */
    fun link(contactId: String, memberId: String, role: String): Undo {
        val known = contact(contactId) != null && repo.current.member(memberId) != null
        val changed = if (known) linkInternal(contactId, memberId, role) else null
        return Undo(emptyMap(), people = PeopleBefore(links = changed?.let { mapOf(it) }.orEmpty()))
    }

    /** Returns the link id and its version before (null = new), or null when nothing changed. */
    private fun linkInternal(contactId: String, memberId: String, role: String): Pair<String, ContactLink?>? {
        val cleanRole = ContactRules.field(role).orEmpty()
        val existing = repo.current.contactLinks.firstOrNull {
            it.contactId == contactId && it.memberId == memberId && !it.deleted
        }
        return when {
            existing == null -> {
                val link = ContactLink(repo.nextId(), contactId, memberId, cleanRole, self, stamp = repo.tick())
                repo.edit { it.copy(contactLinks = it.contactLinks + link) }
                link.id to null
            }
            existing.role == cleanRole -> null
            else -> {
                repo.edit { d ->
                    d.copy(contactLinks = d.contactLinks.map {
                        if (it.id != existing.id) it
                        else it.copy(role = cleanRole, changedBy = d.selfId, stamp = repo.tick())
                    })
                }
                existing.id to existing
            }
        }
    }

    fun unlink(linkId: String): Undo {
        val before = repo.current.contactLinks.firstOrNull { it.id == linkId && !it.deleted } ?: return Undo(emptyMap())
        repo.edit { d ->
            d.copy(contactLinks = d.contactLinks.map {
                if (it.id == linkId) it.copy(deleted = true, changedBy = d.selfId, stamp = repo.tick()) else it
            })
        }
        return Undo(emptyMap(), people = PeopleBefore(links = mapOf(linkId to before)))
    }

    internal companion object {
        fun emptied(c: Contact) = c.copy(
            deleted = true,
            phones = emptyList(),
            emails = emptyList(),
            address = null,
            organization = null,
            birthday = null,
        )
    }
}

/**
 * Facts in a person's portfolio. A blank value deletes the fact. Sizes remember when they were
 * entered or confirmed, so a child's old size can be asked about again ([PortfolioViews.isStale]).
 */
class PortfolioRepository internal constructor(private val repo: FamilyRepository) {

    private fun entry(id: String): PortfolioEntry? = repo.current.portfolio.firstOrNull { it.id == id && !it.deleted }

    /**
     * New fact ([entryId] null) or a changed one; an empty value deletes an existing fact. Returns
     * null when nothing was saved: empty label, a new fact without value, or a link that is no web
     * link. A changed size counts as measured today.
     */
    fun save(memberId: String, entryId: String?, kind: EntryKind, label: String, value: String): Undo? {
        val cleanLabel = ContactRules.field(label)
        val before = entryId?.let(::entry)
        return when {
            cleanLabel == null -> null
            before != null && ContactRules.field(value) == null -> delete(before.id)
            else -> clean(kind, value)?.let { v ->
                if (before == null) create(memberId, kind, cleanLabel, v) else change(before, kind, cleanLabel, v)
            }
        }
    }

    private fun create(memberId: String, kind: EntryKind, label: String, value: String): Undo? {
        if (repo.current.member(memberId) == null) return null
        val created = PortfolioEntry(
            id = repo.nextId(),
            memberId = memberId,
            kind = kind,
            label = label,
            value = value,
            measuredOn = if (kind == EntryKind.SIZE) repo.day() else null,
            changedBy = repo.current.selfId,
            stamp = repo.tick(),
        )
        repo.edit { it.copy(portfolio = it.portfolio + created) }
        return Undo(emptyMap(), people = PeopleBefore(entries = mapOf(created.id to null)))
    }

    private fun change(before: PortfolioEntry, kind: EntryKind, label: String, value: String): Undo {
        val measured = when {
            kind != EntryKind.SIZE -> null
            value != before.value || before.measuredOn == null -> repo.day()
            else -> before.measuredOn
        }
        replace(before.id) { it.copy(kind = kind, label = label, value = value, measuredOn = measured) }
        return Undo(emptyMap(), people = PeopleBefore(entries = mapOf(before.id to before)))
    }

    /** "Stimmt noch" on an old size: measured today, value unchanged. */
    fun confirm(entryId: String): Undo? {
        val before = entry(entryId) ?: return null
        replace(entryId) { it.copy(measuredOn = repo.day()) }
        return Undo(emptyMap(), people = PeopleBefore(entries = mapOf(entryId to before)))
    }

    fun delete(entryId: String): Undo? {
        val before = entry(entryId) ?: return null
        replace(entryId) { emptied(it) }
        return Undo(emptyMap(), people = PeopleBefore(entries = mapOf(entryId to before)))
    }

    private fun replace(id: String, block: (PortfolioEntry) -> PortfolioEntry) {
        repo.edit { d ->
            d.copy(portfolio = d.portfolio.map {
                if (it.id == id) block(it).copy(changedBy = d.selfId, stamp = repo.tick()) else it
            })
        }
    }

    /**
     * Links only as http(s) (threat model TE-8, KT-2): "elternportal.de" becomes https://…, any other
     * scheme (`javascript:`, `intent:`) is refused. Other values as one cut line.
     */
    private fun clean(kind: EntryKind, value: String): String? {
        val v = ContactRules.field(value)
        val bare = v != null && !v.contains(':') && !v.contains(' ') && v.contains('.')
        return when {
            v == null || kind != EntryKind.LINK || isWebLink(v) -> v
            bare -> "https://$v"
            else -> null
        }
    }

    internal companion object {
        fun emptied(e: PortfolioEntry) = e.copy(deleted = true, value = "", measuredOn = null)

        fun isWebLink(url: String): Boolean =
            url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)
    }
}
