package io.github.respawnedguy.familyapp.domain.contacts

import io.github.respawnedguy.familyapp.domain.Stamp
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/** How a contact came into the app (docs/kontakte-und-portfolio.md, "Wie ein Kontakt hereinkommt"). */
@Serializable
enum class ContactSource { PICKER, VCARD, MANUAL }

/**
 * A shared family contact (paediatrician, coach, grandma). A copy, never linked live to a phone
 * book (recommendation E2). Shared with every family device by sync; deleting sets [deleted] and
 * empties the fields, like recipes, so older app versions keep syncing everything else.
 * Only the fields the concept asks for: no photo, no notes (data minimisation, threat model KT-3).
 */
@Serializable
data class Contact(
    val id: String,
    val name: String,
    val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    val address: String? = null,
    val organization: String? = null,
    val birthday: LocalDate? = null,
    val source: ContactSource = ContactSource.MANUAL,
    val createdMs: Long,
    val createdBy: String,
    val changedBy: String? = null,
    val deleted: Boolean = false,
    val stamp: Stamp,
)

/**
 * Contact ↔ family member, n:m. The role belongs to the link, not the contact (E3): the same
 * practice is "Kinderarztpraxis" for Mia and Ben and nothing for the adults. A record of its own,
 * because sync decides per record: a link added on one phone must survive a phone number changed
 * on another at the same time.
 */
@Serializable
data class ContactLink(
    val id: String,
    val contactId: String,
    val memberId: String,
    val role: String = "",
    val changedBy: String? = null,
    val deleted: Boolean = false,
    val stamp: Stamp,
)

/**
 * Kinds of portfolio facts. Codes (PINs, locker codes) come only with the vault of stage 2 (E11),
 * so there is deliberately no CODE here yet.
 */
@Serializable
enum class EntryKind { SIZE, DATE, TEXT, PLACE, LINK }

/**
 * One fact in a person's portfolio ("Schuhgröße 31", "Impfpass: Ordner Gesundheit"). One record
 * per fact, so two parents updating two sizes after shopping do not overwrite each other.
 * [measuredOn] is set for sizes, so a child's size can be asked about again when it gets old.
 */
@Serializable
data class PortfolioEntry(
    val id: String,
    val memberId: String,
    val kind: EntryKind,
    val label: String,
    val value: String,
    val measuredOn: LocalDate? = null,
    val changedBy: String? = null,
    val deleted: Boolean = false,
    val stamp: Stamp,
)

/** A contact before it is stored: from the system picker, a shared vCard or the form. */
data class ContactDraft(
    val name: String,
    val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    val address: String? = null,
    val organization: String? = null,
    val birthday: LocalDate? = null,
)
