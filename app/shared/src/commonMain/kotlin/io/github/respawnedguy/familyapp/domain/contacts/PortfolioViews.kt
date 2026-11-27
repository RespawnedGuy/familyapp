package io.github.respawnedguy.familyapp.domain.contacts

import io.github.respawnedguy.familyapp.domain.FamilyData
import io.github.respawnedguy.familyapp.domain.Item
import io.github.respawnedguy.familyapp.domain.ItemStatus
import io.github.respawnedguy.familyapp.domain.Member
import io.github.respawnedguy.familyapp.domain.MemberKind
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil

/** A placeholder in the portfolio ("Schuhgröße – antippen zum Eintragen"). */
data class EntryTemplate(val label: String, val kind: EntryKind)

/**
 * Read-only views for the portfolio (docs/kontakte-und-portfolio.md); contacts are in [ContactViews]. The portfolio
 * is what someone needs to step in for a person without a handover: what is open, whom to call,
 * the facts. Deleted records never show.
 */
object PortfolioViews {
    /** A child's size older than this gets "noch aktuell?" when the portfolio is opened (E7). */
    const val CHILD_SIZE_STALE_DAYS = 120

    private val CHILD_TEMPLATES = listOf(
        EntryTemplate("Schuhgröße", EntryKind.SIZE),
        EntryTemplate("Kleidergröße", EntryKind.SIZE),
        EntryTemplate("Schule / Klasse", EntryKind.TEXT),
        EntryTemplate("Allergien", EntryKind.TEXT),
        EntryTemplate("Impfpass liegt", EntryKind.PLACE),
    )
    private val ADULT_TEMPLATES = listOf(
        EntryTemplate("Kleidergröße", EntryKind.SIZE),
        EntryTemplate("Schuhgröße", EntryKind.SIZE),
        EntryTemplate("Personalausweis gültig bis", EntryKind.DATE),
        EntryTemplate("Führerschein gültig bis", EntryKind.DATE),
    )

    fun templates(kind: MemberKind): List<EntryTemplate> =
        if (kind == MemberKind.CHILD) CHILD_TEMPLATES else ADULT_TEMPLATES

    // ---- Open work ---------------------------------------------------------------------------

    /**
     * Section "Offen": confirmed open items the person takes care of **or** that concern them.
     * For a child without a phone "betrifft" is the normal case, so their portfolio is not empty (E5).
     */
    fun open(data: FamilyData, memberId: String): List<Item> =
        data.items.filter { it.status == ItemStatus.OPEN && (it.assignee == memberId || it.concerns == memberId) }
            .sortedWith(compareBy({ it.due == null }, { it.due }, { it.createdMs }))

    /** Inbox cards about the person that nobody took yet. */
    fun waiting(data: FamilyData, memberId: String): List<Item> =
        data.items.filter { it.status == ItemStatus.INBOX && it.concerns == memberId && it.assignee == null }
            .sortedBy { it.createdMs }

    /** What "Alles übernehmen" would move: open items this person takes care of. */
    fun takeOverable(data: FamilyData, memberId: String): List<Item> =
        data.items.filter { it.status == ItemStatus.OPEN && it.assignee == memberId }

    // ---- Facts ---------------------------------------------------------------------------------

    /** Section "Infos": templates first in their order, then the person's own labels alphabetically. */
    fun entries(data: FamilyData, member: Member): List<PortfolioEntry> {
        val order = templates(member.kind).map { it.label }
        return data.portfolio.filter { !it.deleted && it.memberId == member.id }
            .sortedWith(compareBy({ rank(order, it.label) }, { it.label.lowercase() }))
    }

    private fun rank(order: List<String>, label: String) = order.indexOf(label).let { if (it < 0) order.size else it }

    /** Templates the person has no fact for yet; shown greyed, never counted as "missing" (no progress bar). */
    fun openTemplates(data: FamilyData, member: Member): List<EntryTemplate> {
        val used = entries(data, member).map { it.label.lowercase() }.toSet()
        return templates(member.kind).filter { it.label.lowercase() !in used }
    }

    /** A child's size measured more than [CHILD_SIZE_STALE_DAYS] ago: ask "noch aktuell?" (never a notification). */
    fun isStale(entry: PortfolioEntry, member: Member, today: LocalDate): Boolean {
        val measured = entry.measuredOn ?: return false
        return entry.kind == EntryKind.SIZE && member.kind == MemberKind.CHILD &&
            measured.daysUntil(today) > CHILD_SIZE_STALE_DAYS
    }
}
