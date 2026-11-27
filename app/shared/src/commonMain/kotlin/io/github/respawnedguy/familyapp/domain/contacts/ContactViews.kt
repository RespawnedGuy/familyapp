package io.github.respawnedguy.familyapp.domain.contacts

import io.github.respawnedguy.familyapp.domain.FamilyData
import io.github.respawnedguy.familyapp.domain.Item
import io.github.respawnedguy.familyapp.domain.ItemStatus

/** A contact as it appears for one person: with the role of that link. */
data class LinkedContact(val contact: Contact, val link: ContactLink)

/**
 * Read-only views of the shared contacts and their links (docs/kontakte-und-portfolio.md).
 * Deleted records never show.
 */
object ContactViews {
    fun contacts(data: FamilyData): List<Contact> =
        data.contacts.filter { !it.deleted }.sortedBy { it.name.lowercase() }

    fun contact(data: FamilyData, id: String): Contact? = data.contacts.firstOrNull { it.id == id && !it.deleted }

    /** Live links of a contact, to people that still exist. */
    fun linksOf(data: FamilyData, contactId: String): List<ContactLink> {
        val members = data.members.map { it.id }.toSet()
        return data.contactLinks.filter { !it.deleted && it.contactId == contactId && it.memberId in members }
    }

    /** Section "Kontakte" of a portfolio, sorted by name. */
    fun contactsFor(data: FamilyData, memberId: String): List<LinkedContact> {
        val byId = data.contacts.filter { !it.deleted }.associateBy { it.id }
        return data.contactLinks.filter { !it.deleted && it.memberId == memberId }
            .mapNotNull { link -> byId[link.contactId]?.let { LinkedContact(it, link) } }
            .sortedBy { it.contact.name.lowercase() }
    }

    /** Contacts linked to nobody: "Familie allgemein" (craftsman, landlord), explicitly allowed (E3). */
    fun general(data: FamilyData): List<Contact> {
        val linked = data.contacts.map { it.id }.filter { linksOf(data, it).isNotEmpty() }.toSet()
        return contacts(data).filter { it.id !in linked }
    }

    /** Every search word must appear in name, organisation, numbers, mails, address or a role. */
    fun searchContacts(data: FamilyData, query: String): List<Contact> {
        val terms = query.trim().lowercase().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        return contacts(data).filter { c ->
            val haystack = buildString {
                append(c.name).append(' ').append(c.organization.orEmpty()).append(' ')
                append(c.address.orEmpty()).append(' ')
                c.emails.forEach { append(it).append(' ') }
                c.phones.forEach { append(it).append(' ').append(ContactRules.dialable(it)).append(' ') }
                linksOf(data, c.id).forEach { append(it.role).append(' ') }
            }.lowercase()
            terms.all { haystack.contains(it) }
        }
    }

    /** Existing contact with the same phone number or mail address, for merging instead of a duplicate. */
    fun duplicateOf(data: FamilyData, draft: ContactDraft): Contact? {
        val phones = draft.phones.map(ContactRules::phoneKey).toSet()
        val mails = draft.emails.map { it.lowercase() }.toSet()
        return contacts(data).firstOrNull { c ->
            c.phones.any { ContactRules.phoneKey(it) in phones } || c.emails.any { it.lowercase() in mails }
        }
    }

    /** Open items whose title names the contact (a search, not a stored link; that comes in stage 3). */
    fun mentioningItems(data: FamilyData, contact: Contact): List<Item> =
        data.items.filter {
            (it.status == ItemStatus.OPEN || it.status == ItemStatus.INBOX) && ContactRules.mentions(it.title, contact)
        }
}
